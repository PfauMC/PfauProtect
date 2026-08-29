package io.pfaumc.pfauprotect

import org.rocksdb.BlockBasedTableConfig
import org.rocksdb.BloomFilter
import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.CompressionType
import org.rocksdb.DBOptions
import org.rocksdb.LRUCache
import org.rocksdb.Options
import org.rocksdb.ReadOptions
import org.rocksdb.RocksDB
import org.rocksdb.Slice
import org.rocksdb.WriteBatch
import org.rocksdb.WriteOptions
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.read
import kotlin.concurrent.write

// A shulker's loot table copies a handful of components onto the dropped item and the owner mark is
// not among them, so a box loses its name every time it is broken. This remembers the name for as
// long as the box stands, and hands it back at the break so the chain of custody survives the cycle.
interface NestedOwners {
    fun ownerAt(world: UUID, x: Int, y: Int, z: Int): UUID?
    fun setOwnerAt(world: UUID, x: Int, y: Int, z: Int, owner: UUID)
    fun clearOwnerAt(world: UUID, x: Int, y: Int, z: Int)
}

// A position takes over the item it was built from, and breaking it has to give back the same thing.
// The block alone cannot say what that was: a named box, an enchanted head and a plain one all answer
// with the bare item, so the position would give back something it never received and its history
// would part company by form at the break. What was put down is only knowable while it is being put
// down, so it is remembered here for as long as it stands.
//
// A whole set of positions is asked and cleared in one call as well as one at a time: an explosion
// reaches these with every position it took away, from the thread ticking the region, where a trip
// through JNI per position is a tick spent on nothing else.
interface PlacedForms {
    fun formAt(world: UUID, x: Int, y: Int, z: Int): ByteArray?
    fun formsAt(positions: List<WorldBlock>): Map<WorldBlock, ByteArray>
    fun setFormAt(world: UUID, x: Int, y: Int, z: Int, form: ByteArray)
    fun clearFormAt(world: UUID, x: Int, y: Int, z: Int)
    fun clearFormsAt(positions: List<WorldBlock>)
}

// A row this build cannot decode is not a row without gaps: it is a row nobody looked at. Counting
// the two together would let a ledger that has become unreadable report a clean sweep.
data class SweepReport(val checked: Int, val unreadable: Int, val gaps: List<String>, val reachedEnd: Boolean)

// Whether the walk ran out of rows or out of budget travels with the rows it brought back. A caller
// that has to tell the difference cannot get it from the size of the list: a scan that stopped one
// row short of the end and one that stopped in the middle of a year both come back full.
data class EntryPage(val entries: List<LedgerEntry>, val complete: Boolean)

// Everything the item plane holds against one block position, and the rows of that position nobody
// could decode. A row that did not decode never balanced to anything, so it is counted apart from the
// postings rather than left out of them; `at` is null only where no row of the position decoded at all
// and the position cannot even be named.
data class BlockPostings(val at: WorldBlock?, val entries: List<LedgerEntry>, val unreadable: Int)

data class BlockPostingsPage(val positions: List<BlockPostings>, val reachedEnd: Boolean)

// Raised by a change to the key layout — version 2 took a fixed-width world number and a trailing
// posting ordinal — and by a change to the set of column families, which is what version 3 is: the
// family of interned block-entity payloads. The record version in the value covers neither. Keys
// carry a version this build reads, so without the bump an older database opens and every key is
// parsed as something it never was; a family holds rows the value version never speaks for at all.
private const val SCHEMA_VERSION = 3L
private const val MAX_REGION_CHUNKS = 1024

// What one region query may hold in memory at once. Reached only by a query over an area whose
// history is larger than any answer could carry, and a read that reaches it says it did.
private const val MAX_REGION_ROWS = 100_000
internal const val MAX_BATCH = 256
internal const val WRITER_POLL_MILLIS = 50L
internal const val WAL_FLUSH_INTERVAL_NANOS = 1_000_000_000L
internal const val DRAIN_TIMEOUT_NANOS = 10_000_000_000L
private const val UNASSIGNED = -1

// Longer than the longest tail a key can carry after any prefix this class scans, so a prefix padded
// with this many 0xFF bytes sorts after every key under it while still sharing its prefix.
internal const val KEY_TAIL_PAD = 32

// One cache for every column family. An unconfigured family quietly gets a 32 MiB cache of its own,
// so leaving this out is not "no cache" but eight of them.
internal const val BLOCK_CACHE_BYTES = 64L * 1024 * 1024
internal const val BLOOM_BITS_PER_KEY = 10.0

// The ledger takes every write; the rest hold a handful of small rows each and have no use for the
// 64 MiB the default memtable would reserve for them.
internal const val COLD_WRITE_BUFFER_BYTES = 4L * 1024 * 1024

private val ENTRIES_CF = "entries".toByteArray()
private val ITEM_FORMS_CF = "item_forms".toByteArray()
private val REGISTRY_CF = "registry".toByteArray()
private val META_CF = "meta".toByteArray()
private val NESTED_OWNERS_CF = "nested_owners".toByteArray()
private val TX_CF = "tx".toByteArray()
private val PLACED_FORMS_CF = "placed_forms".toByteArray()
private val BLOCK_PAYLOADS_CF = "block_payloads".toByteArray()

private val META_SCHEMA = "schema".toByteArray()
private val META_TX_ID = "tx_id".toByteArray()
private val META_ITEM_FORM_ID = "item_form_id".toByteArray()
private val META_BLOCK_PAYLOAD_ID = "block_payload_id".toByteArray()
private val META_SWEEP_CURSOR = "sweep_cursor".toByteArray()
private val META_BLOCK_CURSOR = "block_cursor".toByteArray()

private val WORLD_BLOCK_PREFIX = byteArrayOf(HolderType.WORLD_BLOCK.toByte())

internal val LOGGER: Logger = Logger.getLogger("PfauProtect")

internal fun longBytes(v: Long): ByteArray = ByteWriter(8).longBE(v).toByteArray()

// Cheap compression while a level is still being rewritten, and the slow thorough one once it has
// settled at the bottom and will be read far more often than it is written.
internal fun compressed() = ColumnFamilyOptions()
    .setCompressionType(CompressionType.LZ4_COMPRESSION)
    .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)

// The smallest key that sorts after everything under `prefix`. A prefix of nothing but 0xFF bytes
// has none, and neither layout can produce one: an item key opens with a holder type, which is never
// 0xFF, and a block key opens with a Z-code that would have to sit eight million chunks out, well
// past any world border.
internal fun afterPrefix(prefix: ByteArray): ByteArray {
    for (i in prefix.indices.reversed()) {
        if (prefix[i] != 0xFF.toByte()) {
            val end = prefix.copyOf(i + 1)
            end[i]++
            return end
        }
    }
    throw IllegalArgumentException("a prefix of nothing but 0xFF bytes has no key after it")
}

class RocksItemLog(dir: Path) : AutoCloseable, RegistryStore, NestedOwners, PlacedForms {
    private val dbOptions = DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true)
    private val blockCache = LRUCache(BLOCK_CACHE_BYTES)
    private val bloom = BloomFilter(BLOOM_BITS_PER_KEY)

    // Every family we ever ask for a single row by key wants the filter; the ones we only ever walk
    // would pay for it on every write and save nothing.
    private val filteredTable = BlockBasedTableConfig().setBlockCache(blockCache).setFilterPolicy(bloom)
    private val plainTable = BlockBasedTableConfig().setBlockCache(blockCache)

    private val entriesOptions = compressed()
        .setTableFormatConfig(filteredTable)
        // A chunk is the first CHUNK_PREFIX_SIZE bytes of every key that addresses a position, so
        // this is what lets a lookup of one chunk skip the files and memtables that hold none of it.
        .useFixedLengthPrefixExtractor(EntryCodec.CHUNK_PREFIX_SIZE)
        .setMemtablePrefixBloomSizeRatio(0.1)
    private val pointReadOptions = compressed()
        .setTableFormatConfig(filteredTable)
        .setWriteBufferSize(COLD_WRITE_BUFFER_BYTES)
    private val unfilteredOptions = compressed()
        .setTableFormatConfig(plainTable)
        .setWriteBufferSize(COLD_WRITE_BUFFER_BYTES)

    // A prefix extractor lets a plain iterator stop at the end of the prefix it was seeked into, so a
    // walk that means to cover a whole column family has to say it wants total order.
    private val wholeCfRead = ReadOptions().setTotalOrderSeek(true)

    private val writeOptions = WriteOptions()

    // A number minted outside the writer thread is minted for another database to name, and that
    // database flushes its own log on a timer of its own. Write order alone does not survive a crash:
    // the row citing the number can reach the disk while the interning that produced it is still in
    // this log, and on restart the counter has rewound and hands the same number to a different value,
    // so the surviving row decodes to something it never named. Fsyncing here is what orders the two.
    // The ledger's own writes owe nothing of the sort — a row and the counter that named it share one
    // batch in one database — and interning is a first-encounter cost, with the startup fill a single
    // batch, so the flush is paid once per new value rather than once per row.
    private val syncWriteOptions = WriteOptions().setSync(true)

    private val cfHandles = ArrayList<ColumnFamilyHandle>()
    private val db: RocksDB
    private val entriesCf: ColumnFamilyHandle
    private val itemFormsCf: ColumnFamilyHandle
    private val registryCf: ColumnFamilyHandle
    private val metaCf: ColumnFamilyHandle
    private val nestedOwnersCf: ColumnFamilyHandle
    private val txCf: ColumnFamilyHandle
    private val placedFormsCf: ColumnFamilyHandle
    private val blockPayloadsCf: ColumnFamilyHandle

    private val queue = LinkedBlockingQueue<List<Transfer>>()
    private val submitted = AtomicLong()
    private val written = AtomicLong()

    @Volatile
    private var running = true

    @Volatile
    private var writerFailure: Throwable? = null

    @Volatile
    private var closed = false

    private val dbLock = ReentrantReadWriteLock()

    private var pendingBatch: WriteBatch? = null

    // A staging session belongs to the thread that opened it: two threads sharing one batch would be
    // writing into the same native object, and a bulk fill is the only caller that opens one.
    private val stagingBatch = ThreadLocal<WriteBatch?>()
    private var nextTxId: Long = 0
    private val registryCounters = ConcurrentHashMap<RegistryNamespace, Long>()

    init {
        RocksDB.loadLibrary()
        Files.createDirectories(dir)
        val path = dir.toAbsolutePath().toString()
        // Opening writes every missing column family into the manifest before anything can look at
        // the schema, and a build that does not know those families can no longer open the database
        // at all. A database refused for its schema has to be left exactly as it was found, or one
        // failed start of a newer build makes going back impossible.
        val stored = try {
            storedSchema(path)?.also {
                require(it == SCHEMA_VERSION) {
                    "database schema $it cannot be read by this build (schema $SCHEMA_VERSION)"
                }
            }
        } catch (failure: Throwable) {
            runCatching { closeOptions() }
            throw failure
        }
        val descriptors = listOf(
            RocksDB.DEFAULT_COLUMN_FAMILY to unfilteredOptions,
            ENTRIES_CF to entriesOptions,
            ITEM_FORMS_CF to pointReadOptions,
            REGISTRY_CF to unfilteredOptions,
            META_CF to unfilteredOptions,
            NESTED_OWNERS_CF to pointReadOptions,
            TX_CF to pointReadOptions,
            PLACED_FORMS_CF to pointReadOptions,
            BLOCK_PAYLOADS_CF to pointReadOptions,
        ).map { (name, options) -> ColumnFamilyDescriptor(name, options) }
        db = RocksDB.open(dbOptions, path, descriptors, cfHandles)
        entriesCf = cfHandles[1]
        itemFormsCf = cfHandles[2]
        registryCf = cfHandles[3]
        metaCf = cfHandles[4]
        nestedOwnersCf = cfHandles[5]
        txCf = cfHandles[6]
        placedFormsCf = cfHandles[7]
        blockPayloadsCf = cfHandles[8]

        failClosed {
            if (stored == null) db.put(metaCf, META_SCHEMA, longBytes(SCHEMA_VERSION))
            nextTxId = readCounter(META_TX_ID)
            for (ns in RegistryNamespace.entries) registryCounters[ns] = readCounter(registryCounterKey(ns))
        }
    }

    private val writerThread = Thread(::runWriter, "pfauprotect-ledger-writer").apply { isDaemon = true }

    val registries = Registries(this)
    val forms = InternTable(itemFormsCf, META_ITEM_FORM_ID)

    // The block rows that will name these payloads live in a database per world, with no WAL shared
    // between that database and this one. Writing the payload first is half of it; the other half is
    // that the write is fsynced before it returns, so a row written afterwards can never reach the
    // disk ahead of the payload it names. An orphaned payload is garbage nobody reads; a row whose
    // payload was never written is lost data.
    val payloads = InternTable(blockPayloadsCf, META_BLOCK_PAYLOAD_ID)

    // An unregistered world or player owns no rows at all, so a scan for one has to find nothing.
    // A player number is a varint and -1 encodes to something the registry never hands out; a world
    // number is two fixed bytes and -1 lands on 0xFFFF, which stays free only while the registry is
    // never asked for its 65536th world.
    private val knownIds = IdResolver { ns, uuid -> registries.lookupKey(ns, uuid.toString()) ?: UNASSIGNED }

    init {
        failClosed {
            registries.load()
            forms.load()
            payloads.load()
        }
        writerThread.start()
    }

    // Refusing a database that is already open has to hand the handle back with the refusal. Nothing
    // outside reaches a constructor that threw, so the file lock and the native memory would be held
    // until the process ends, and the next attempt to open — a retry, a reload — could never succeed.
    private inline fun <T> failClosed(body: () -> T): T =
        try {
            body()
        } catch (failure: Throwable) {
            runCatching { closeNatives() }
            throw failure
        }

    fun submit(transfer: Transfer) = submit(listOf(transfer))

    /** Every movement in the list shares one `tx_id`, which is what carries a graph walk across `Void`. */
    fun submit(transaction: List<Transfer>) {
        if (writerFailure != null || transaction.isEmpty()) return
        submitted.incrementAndGet()
        queue.add(transaction)
    }

    fun drain() {
        val target = submitted.get()
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS
        while (written.get() < target) {
            failIfWriterStopped()
            check(System.nanoTime() < deadline) {
                "ledger writer did not catch up in ${DRAIN_TIMEOUT_NANOS / 1_000_000} ms, " +
                    "${target - written.get()} transfers are unwritten"
            }
            Thread.sleep(1)
        }
        failIfWriterStopped()
    }

    fun holderEntries(
        holder: Holder,
        fromTs: Long,
        toTs: Long,
        reverse: Boolean = false,
        limit: Int = 100,
    ): List<LedgerEntry> = holderPage(holder, fromTs, toTs, reverse, limit).entries

    fun holderPage(
        holder: Holder,
        fromTs: Long,
        toTs: Long,
        reverse: Boolean = false,
        limit: Int = 100,
    ): EntryPage = dbLock.read {
        if (closed) return EntryPage(emptyList(), false)
        scan(EntryCodec.holderPrefix(holder, knownIds), fromTs, toTs, reverse, limit)
    }

    fun regionEntries(
        world: UUID,
        minX: Int,
        minZ: Int,
        maxX: Int,
        maxZ: Int,
        fromTs: Long,
        toTs: Long,
        reverse: Boolean = false,
        limit: Int = 100,
    ): List<LedgerEntry> = regionPage(world, minX, minZ, maxX, maxZ, fromTs, toTs, reverse, limit).entries

    fun regionPage(
        world: UUID,
        minX: Int,
        minZ: Int,
        maxX: Int,
        maxZ: Int,
        fromTs: Long,
        toTs: Long,
        reverse: Boolean = false,
        limit: Int = 100,
    ): EntryPage {
        val chunkX = (minOf(minX, maxX) shr 4)..(maxOf(minX, maxX) shr 4)
        val chunkZ = (minOf(minZ, maxZ) shr 4)..(maxOf(minZ, maxZ) shr 4)
        val chunks = (chunkX.last - chunkX.first + 1).toLong() * (chunkZ.last - chunkZ.first + 1).toLong()
        require(chunks <= MAX_REGION_CHUNKS) {
            "region spans $chunks chunks, at most $MAX_REGION_CHUNKS can be scanned in one query"
        }
        if (registries.lookupKey(RegistryNamespace.WORLD, world.toString()) == null) {
            return EntryPage(emptyList(), true)
        }
        return dbLock.read {
            if (closed) return EntryPage(emptyList(), false)
            val found = ArrayList<LedgerEntry>()
            var complete = true
            // Keys inside a chunk are ordered by position and only then by time, so a chunk cut off
            // at the limit contributes its rows by position: a crater packed into one chunk answers
            // with one corner of itself and reads as silence over every position that is not in that
            // corner. Every row under the prefix is therefore in hand before the limit is applied,
            // which is how the block plane has always read a chunk.
            for (cx in chunkX) {
                for (cz in chunkZ) {
                    for (prefix in EntryCodec.blockChunkPrefixes(world, cx, cz, knownIds)) {
                        val room = MAX_REGION_ROWS - found.size
                        if (room <= 0) {
                            complete = false
                            continue
                        }
                        val page = scan(prefix, fromTs, toTs, reverse, room)
                        found += page.entries
                        complete = complete && page.complete
                    }
                }
            }
            val byTime = compareBy<LedgerEntry>({ it.timestamp }, { it.txId })
            val ordered = found.sortedWith(if (reverse) byTime.reversed() else byTime)
            EntryPage(ordered.take(limit), complete && ordered.size <= limit)
        }
    }

    fun transactionEntries(entry: LedgerEntry): List<LedgerEntry> = dbLock.read {
        if (closed) return listOf(entry)
        // The index has to be asked first. A transaction that reaches past the first pair holds a
        // posting at the facing ordinal that is nobody's other half, and answering from it would give
        // the same transaction a different extent depending on which of its own postings asked.
        val packed = db.get(txCf, longBytes(entry.txId))
        if (packed != null) {
            val found = unpackKeys(packed).mapNotNull { key ->
                db.get(entriesCf, key)?.let { EntryCodec.decodeOrNull(key, it, registries) }
            }
            return if (found.isEmpty()) listOf(entry) else found
        }
        // No index row means one movement, which writes exactly the ordinals 0 and 1, so the half
        // facing this one is a point read away.
        val other = entry.counterparty
        if (!other.addressable) return listOf(entry)
        val otherKey = EntryCodec.key(other, entry.timestamp, entry.txId, 1 - entry.ordinal, knownIds)
        val decoded = db.get(entriesCf, otherKey)?.let { EntryCodec.decodeOrNull(otherKey, it, registries) }
        if (decoded == null) listOf(entry) else listOf(entry, decoded)
    }

    fun form(itemFormId: Long): ByteArray? = forms.valueOf(itemFormId)

    fun formId(form: ByteArray): Long? = forms.lookup(form)

    fun payload(payloadId: Long): ByteArray? = payloads.valueOf(payloadId)

    fun payloadId(payload: ByteArray): Long? = payloads.lookup(payload)

    // The slot never enters the key, so one prefix per holder type already covers every slot a player
    // owns and the whole balance is four scans rather than a read per row.
    fun formBalance(player: UUID): Map<Long, Int> = dbLock.read {
        val totals = HashMap<Long, Int>()
        if (closed) return totals
        val holders = listOf(
            PlayerInv(player, 0), PlayerEquip(player, 0), PlayerCursor(player), PlayerEnder(player, 0),
        )
        for (holder in holders) {
            forEachUnder(EntryCodec.holderPrefix(holder, knownIds), reverse = false) { key, value ->
                EntryCodec.decodeOrNull(key, value, registries)?.let {
                    totals.merge(it.itemFormId, it.qty, Int::plus)
                }
                true
            }
        }
        totals.entries.removeIf { it.value == 0 }
        totals
    }

    // The standing test of the capture: an entry that does not face the void has a second half, and
    // the two cancel each other out. A half that is missing is not a dupe but a hole in the capture —
    // two ends of one movement that collapsed onto one key, or a write that never landed — and it is
    // worth hearing about now rather than in the middle of an investigation years later. Each pass
    // picks up where the last one stopped, so the whole ledger is covered over time at a fixed cost.
    fun sweep(limit: Int): SweepReport = dbLock.read {
        if (closed) return SweepReport(0, 0, emptyList(), false)
        val gaps = ArrayList<String>()
        var checked = 0
        var unreadable = 0
        var last: ByteArray? = null
        var reachedEnd = false
        db.newIterator(entriesCf, wholeCfRead).use { iter ->
            val cursor = db.get(metaCf, META_SWEEP_CURSOR)
            if (cursor == null) {
                iter.seekToFirst()
            } else {
                iter.seek(cursor)
                if (iter.isValid && iter.key().contentEquals(cursor)) iter.next()
                if (!iter.isValid) iter.seekToFirst()
            }
            // Unreadable rows spend the budget too: a stretch of them the length of a year would
            // otherwise be walked in a single pass, on the thread that asked for one pass worth.
            while (iter.isValid && checked + unreadable < limit) {
                val key = iter.key()
                val entry = EntryCodec.decodeOrNull(key, iter.value(), registries)
                if (entry == null) {
                    unreadable++
                } else {
                    checked++
                    gapOf(entry)?.let { gaps += it }
                }
                last = key
                iter.next()
            }
            reachedEnd = !iter.isValid
        }
        if (reachedEnd || last == null) db.delete(metaCf, META_SWEEP_CURSOR) else db.put(metaCf, META_SWEEP_CURSOR, last)
        SweepReport(checked, unreadable, gaps, reachedEnd)
    }

    /**
     * The postings standing against block positions, walked under the one holder type that addresses
     * one. The rows of a position are contiguous — the key is the holder type, the world number, the
     * position, and only then the time — so every position comes back whole and is settled once.
     * Each pass picks up where the last one stopped, unloaded chunks and all.
     *
     * The budget is counted in rows but spent at a position boundary: half of a position's postings
     * balance to something the position never held. A pass resumes past the last position it settled
     * rather than at its last row, so a posting that lands on a settled position waits for the next
     * cycle instead of coming back alone.
     */
    fun blockPostings(limit: Int): BlockPostingsPage = dbLock.read {
        if (closed || limit <= 0) return BlockPostingsPage(emptyList(), false)
        val cursor = db.get(metaCf, META_BLOCK_CURSOR)
        val positions = ArrayList<BlockPostings>()
        val entries = ArrayList<LedgerEntry>()
        var unreadable = 0
        var position: ByteArray? = null
        var rows = 0
        var settled: ByteArray? = null
        var reachedEnd = true
        forEachUnder(WORLD_BLOCK_PREFIX, reverse = false, from = cursor) { key, value ->
            val at = key.copyOf(EntryCodec.POSITION_PREFIX_SIZE)
            val done = position
            if (done != null && !done.contentEquals(at)) {
                positions += BlockPostings(entries.firstOrNull()?.holder as? WorldBlock, ArrayList(entries), unreadable)
                entries.clear()
                unreadable = 0
                // Parked past the whole position rather than on its last row: the time follows the
                // position in the key, so a posting written to a position already settled sorts after
                // every row of it this pass read, and a cursor on that row would hand the next pass the
                // position with nothing but its new rows and a total that was never the position's.
                settled = afterPrefix(done)
                if (rows >= limit) {
                    reachedEnd = false
                    return@forEachUnder false
                }
            }
            position = at
            val entry = EntryCodec.decodeOrNull(key, value, registries)
            if (entry == null) unreadable++ else entries += entry
            rows++
            true
        }
        if (reachedEnd && position != null) {
            positions += BlockPostings(entries.firstOrNull()?.holder as? WorldBlock, entries, unreadable)
        }
        val end = settled
        if (reachedEnd || end == null) db.delete(metaCf, META_BLOCK_CURSOR) else db.put(metaCf, META_BLOCK_CURSOR, end)
        BlockPostingsPage(positions, reachedEnd)
    }

    private fun gapOf(entry: LedgerEntry): String? {
        val other = entry.counterparty
        if (!other.addressable) return null
        // A transaction reassembled through the index brings back every posting it holds and not just
        // this pair, so the half that cancels this row is looked for rather than assumed to be second.
        val half = transactionEntries(entry).firstOrNull { it.holder == other && it.qty == -entry.qty }
        val where = "${entry.cause} of ${entry.qty} at ${entry.holder}, transaction ${entry.txId}"
        if (half == null) return "$where: nothing at $other gives back the ${entry.qty}"
        return if (half.itemFormId == entry.itemFormId) null else "$where: the halves name different items"
    }

    override fun ownerAt(world: UUID, x: Int, y: Int, z: Int): UUID? =
        note(nestedOwnersCf, world, x, y, z)?.let { ByteReader(it).uuid() }

    override fun setOwnerAt(world: UUID, x: Int, y: Int, z: Int, owner: UUID) =
        putNote(nestedOwnersCf, world, x, y, z, ByteWriter(16).uuid(owner).toByteArray())

    override fun clearOwnerAt(world: UUID, x: Int, y: Int, z: Int) =
        clearNote(nestedOwnersCf, world, x, y, z)

    override fun formAt(world: UUID, x: Int, y: Int, z: Int): ByteArray? =
        note(placedFormsCf, world, x, y, z)

    override fun setFormAt(world: UUID, x: Int, y: Int, z: Int, form: ByteArray) =
        putNote(placedFormsCf, world, x, y, z, form)

    override fun clearFormAt(world: UUID, x: Int, y: Int, z: Int) =
        clearNote(placedFormsCf, world, x, y, z)

    // A position with no note comes back as a null in its own place, so the answers stay aligned with
    // the positions asked about and only the ones holding something are named.
    override fun formsAt(positions: List<WorldBlock>): Map<WorldBlock, ByteArray> = dbLock.read {
        if (closed || positions.isEmpty()) return emptyMap()
        val keys = positions.map { blockKey(it.world, it.x, it.y, it.z) }
        val values = db.multiGetAsList(List(keys.size) { placedFormsCf }, keys)
        val forms = HashMap<WorldBlock, ByteArray>(positions.size)
        positions.forEachIndexed { i, at -> values[i]?.let { forms[at] = it } }
        forms
    }

    override fun clearFormsAt(positions: List<WorldBlock>) {
        dbLock.read {
            if (closed || positions.isEmpty()) return
            WriteBatch().use { batch ->
                for (at in positions) batch.delete(placedFormsCf, blockKey(at.world, at.x, at.y, at.z))
                db.write(writeOptions, batch)
            }
        }
    }

    // Both tables hold a note about what a block position is carrying while it stands there, so they
    // are read, written and cleared the same way and differ only in which family they land in.
    private fun note(cf: ColumnFamilyHandle, world: UUID, x: Int, y: Int, z: Int): ByteArray? = dbLock.read {
        if (closed) return null
        db.get(cf, blockKey(world, x, y, z))
    }

    private fun putNote(cf: ColumnFamilyHandle, world: UUID, x: Int, y: Int, z: Int, value: ByteArray) {
        dbLock.read {
            if (closed) return
            db.put(cf, blockKey(world, x, y, z), value)
        }
    }

    private fun clearNote(cf: ColumnFamilyHandle, world: UUID, x: Int, y: Int, z: Int) {
        dbLock.read {
            if (closed) return
            db.delete(cf, blockKey(world, x, y, z))
        }
    }

    private fun blockKey(world: UUID, x: Int, y: Int, z: Int): ByteArray =
        ByteWriter(16 + Zcode.SIZE).uuid(world).bytes(Zcode.encode(x, y, z)).toByteArray()

    // Reads run on any thread, so the native handles may only be freed once every reader has left.
    override fun close() {
        running = false
        writerThread.join()
        dbLock.write {
            if (closed) return
            closed = true
            runCatching { db.flushWal(true) }
            closeNatives()
        }
    }

    // Read-only and with exactly the families already on disk, so a database this build refuses is
    // handed back untouched. Null means there is nothing to refuse: no database, or one from before
    // the schema was stamped.
    private fun storedSchema(path: String): Long? = Options().use { probe ->
        val existing = RocksDB.listColumnFamilies(probe, path)
        val metaIndex = existing.indexOfFirst { it.contentEquals(META_CF) }
        if (metaIndex < 0) return null
        val handles = ArrayList<ColumnFamilyHandle>()
        ColumnFamilyOptions().use { cfOptions ->
            DBOptions().use { options ->
                RocksDB.openReadOnly(options, path, existing.map { ColumnFamilyDescriptor(it, cfOptions) }, handles)
                    .use { probed ->
                        try {
                            probed.get(handles[metaIndex], META_SCHEMA)?.let { ByteReader(it).longBE() }
                        } finally {
                            handles.forEach { it.close() }
                        }
                    }
            }
        }
    }

    private fun closeNatives() {
        cfHandles.forEach { it.close() }
        db.close()
        closeOptions()
    }

    private fun closeOptions() {
        writeOptions.close()
        syncWriteOptions.close()
        wholeCfRead.close()
        entriesOptions.close()
        pointReadOptions.close()
        unfilteredOptions.close()
        // The table configs hold these, so they may only go once nothing can reach them.
        bloom.close()
        blockCache.close()
        dbOptions.close()
    }

    override fun loadAll(): List<RegistryRow> = dbLock.read {
        val rows = ArrayList<RegistryRow>()
        if (closed) return rows
        db.newIterator(registryCf, wholeCfRead).use { iter ->
            iter.seekToFirst()
            while (iter.isValid) {
                val key = iter.key()
                val ns = RegistryNamespace.byId(key[0].toInt() and 0xFF)
                if (ns != null) rows += RegistryRow(ns, key.copyOfRange(1, key.size), ByteReader(iter.value()).varInt())
                iter.next()
            }
        }
        rows
    }

    override fun put(ns: RegistryNamespace, keyBytes: ByteArray, id: Int) {
        val key = ByteWriter(1 + keyBytes.size).byte(ns.id).bytes(keyBytes).toByteArray()
        stage { it.put(registryCf, key, ByteWriter(5).varInt(id).toByteArray()) }
    }

    override fun nextId(ns: RegistryNamespace): Int {
        val id = registryCounters.getValue(ns)
        registryCounters[ns] = id + 1
        stage { it.put(metaCf, registryCounterKey(ns), longBytes(id + 1)) }
        return id.toInt()
    }

    private fun runWriter() {
        var lastFlush = System.nanoTime()
        try {
            while (true) {
                val first = queue.poll(WRITER_POLL_MILLIS, TimeUnit.MILLISECONDS)
                if (first == null && !running) return
                if (first != null) {
                    val batched = ArrayList<List<Transfer>>(MAX_BATCH)
                    batched += first
                    queue.drainTo(batched, MAX_BATCH - 1)
                    writeAll(batched)
                    written.addAndGet(batched.size.toLong())
                }
                if (System.nanoTime() - lastFlush >= WAL_FLUSH_INTERVAL_NANOS) {
                    db.flushWal(true)
                    lastFlush = System.nanoTime()
                }
            }
        } catch (failure: Throwable) {
            writerFailure = failure
            running = false
            LOGGER.log(
                Level.SEVERE,
                "ledger writer stopped, ${queue.size} transfers are unwritten and further ones are dropped",
                failure,
            )
        }
    }

    // One movement that cannot be encoded is a bug in the capture; every movement after it is not.
    // Letting the first one stop the writer turns a single bad row into a ledger that silently records
    // nothing for the rest of the session, which for a journal kept to be trusted is the worst of the
    // available outcomes. The offending transaction is rolled back out of the batch and counted; a
    // failure of the write itself is a different animal and still stops everything.
    private fun writeAll(transactions: List<List<Transfer>>) {
        WriteBatch().use { batch ->
            pendingBatch = batch
            try {
                for (transaction in transactions) {
                    batch.setSavePoint()
                    try {
                        writeTransaction(batch, transaction)
                    } catch (failure: Exception) {
                        batch.rollbackToSavePoint()
                        LOGGER.log(Level.SEVERE, "a movement could not be written and was dropped", failure)
                    }
                }
            } finally {
                pendingBatch = null
            }
            db.write(writeOptions, batch)
        }
    }

    private fun writeTransaction(batch: WriteBatch, transaction: List<Transfer>) {
        val txId = nextTxId++
        batch.put(metaCf, META_TX_ID, longBytes(nextTxId))
        val keys = ArrayList<ByteArray>(transaction.size * 2)
        for (transfer in transaction) {
            val itemFormId = forms.idOf(transfer.form)
            val postings = listOf(
                entryOf(transfer, transfer.from, transfer.to, -transfer.qty, itemFormId, txId),
                entryOf(transfer, transfer.to, transfer.from, transfer.qty, itemFormId, txId),
            )
            for (posting in postings) {
                if (!posting.holder.addressable) continue
                val entry = posting.copy(ordinal = keys.size)
                val key = EntryCodec.key(entry.holder, entry.timestamp, txId, entry.ordinal, registries)
                batch.put(entriesCf, key, EntryCodec.value(entry, registries))
                keys += key
            }
        }
        // The two halves of a plain movement name each other and sit at ordinals 0 and 1, so the
        // second is a point read away and an index row would be a second write for nothing. Anything
        // larger — a break that drops several stacks at once, both sides of a mutation facing the
        // Void — has no such handle on its remaining postings and could not be reassembled at all.
        if (transaction.size > 1) batch.put(txCf, longBytes(txId), packKeys(keys))
    }

    private fun packKeys(keys: List<ByteArray>): ByteArray {
        val w = ByteWriter(keys.sumOf { it.size + 2 } + 2)
        w.varInt(keys.size)
        for (key in keys) w.varInt(key.size).bytes(key)
        return w.toByteArray()
    }

    private fun unpackKeys(packed: ByteArray): List<ByteArray> {
        val r = ByteReader(packed)
        return List(r.varInt()) { r.bytes(r.varInt()) }
    }

    private fun entryOf(
        transfer: Transfer,
        holder: Holder,
        counterparty: Holder,
        qty: Int,
        itemFormId: Long,
        txId: Long,
    ) = LedgerEntry(
        holder = holder,
        timestamp = transfer.timestamp,
        txId = txId,
        kind = transfer.kind,
        cause = transfer.cause,
        confidence = transfer.confidence,
        counterparty = counterparty,
        itemFormId = itemFormId,
        qty = qty,
        damage = transfer.damage,
        actor = transfer.actor,
    )

    // The bounds keep the walk inside the prefix, which under a prefix extractor is the only way to
    // do it: an iterator in prefix mode may not be carried past the prefix it was seeked into, and a
    // seek to the key just after the prefix lands in the next prefix, where the filter answers with
    // nothing at all. `action` returns false to stop.
    private inline fun forEachUnder(
        prefix: ByteArray,
        reverse: Boolean,
        from: ByteArray? = null,
        action: (ByteArray, ByteArray) -> Boolean,
    ) {
        val lower = Slice(prefix)
        val upper = Slice(afterPrefix(prefix))
        try {
            ReadOptions()
                .setIterateLowerBound(lower)
                .setIterateUpperBound(upper)
                // A prefix shorter than the extractor spreads its rows over many extractor prefixes,
                // so no seek key can stand for all of them. A prefix exactly as long as the extractor
                // is the case that matters here: a chunk prefix is that length, and the key just
                // after it belongs to the next extractor prefix, which is an upper bound prefix mode
                // does not define a walk against. Either way the walk has to leave prefix mode.
                .setTotalOrderSeek(prefix.size <= EntryCodec.CHUNK_PREFIX_SIZE)
                .use { options ->
                    db.newIterator(entriesCf, options).use { iter ->
                        when {
                            reverse -> iter.seekForPrev(lastUnder(prefix))
                            from != null -> iter.seek(from)
                            else -> iter.seekToFirst()
                        }
                        while (iter.isValid) {
                            if (!action(iter.key(), iter.value())) return
                            if (reverse) iter.prev() else iter.next()
                        }
                    }
                }
        } finally {
            lower.close()
            upper.close()
        }
    }

    // Padded rather than the upper bound: seeking backwards has to start from a key that still
    // belongs to this prefix, or the prefix filter is asked about the wrong one and finds nothing.
    private fun lastUnder(prefix: ByteArray): ByteArray =
        ByteWriter(prefix.size + KEY_TAIL_PAD)
            .bytes(prefix)
            .bytes(ByteArray(KEY_TAIL_PAD) { 0xFF.toByte() })
            .toByteArray()

    // Each call brings back at most `limit` entries under its own prefix, so a region scan gives every
    // chunk the same allowance rather than letting the first one it walks spend the whole budget.
    private fun scan(
        prefix: ByteArray,
        fromTs: Long,
        toTs: Long,
        reverse: Boolean,
        limit: Int,
    ): EntryPage {
        val found = ArrayList<LedgerEntry>()
        if (limit <= 0) return EntryPage(found, false)
        // One row past the limit is what separates a scan that ended from one that was cut off, and
        // it costs a single decode. Guessing from the size instead is wrong both ways: a range that
        // ends exactly on the limit reads as cut off, and rows dropped by the time window read as
        // room to spare.
        forEachUnder(prefix, reverse) { key, value ->
            // A chunk prefix stops three position bytes short of the timestamp, so no byte range
            // under it can express a time window; the window is applied to the decoded entry instead.
            val entry = EntryCodec.decodeOrNull(key, value, registries)
            if (entry != null && entry.timestamp in fromTs..toTs) found += entry
            found.size <= limit
        }
        return if (found.size <= limit) EntryPage(found, true) else EntryPage(found.take(limit), false)
    }

    // A fill that mints thousands of numbers in a row would otherwise pay a write apiece. The batch is
    // written even when the body fails: what it holds is already whole, every row alongside the
    // counter that named it, and the numbers it minted are already being handed out in memory.
    fun <T> staged(body: () -> T): T = dbLock.read {
        check(!closed) { "the ledger is closed" }
        WriteBatch().use { batch ->
            stagingBatch.set(batch)
            try {
                val result = body()
                db.write(syncWriteOptions, batch)
                result
            } catch (failure: Throwable) {
                // Written even so, but its own failure is kept quiet: the reason the body stopped is
                // the one worth reporting, and losing it to a storage error hides the ceiling that
                // was actually hit.
                runCatching { db.write(syncWriteOptions, batch) }
                throw failure
            } finally {
                stagingBatch.remove()
            }
        }
    }

    // Registry and form rows must land in the same batch as the counter that named them, or a crash
    // in between hands the same number out twice. Startup registry fills run before the writer has a
    // batch open, so they go into the session the caller opened, or into a batch of their own.
    private fun stage(write: (WriteBatch) -> Unit) {
        val pending = if (Thread.currentThread() === writerThread) pendingBatch else stagingBatch.get()
        if (pending != null) {
            write(pending)
            return
        }
        dbLock.read {
            check(!closed) { "the ledger is closed" }
            WriteBatch().use { batch ->
                write(batch)
                db.write(syncWriteOptions, batch)
            }
        }
    }

    private fun readCounter(key: ByteArray): Long = db.get(metaCf, key)?.let { ByteReader(it).longBE() } ?: 0L

    private fun registryCounterKey(ns: RegistryNamespace): ByteArray = "reg:${ns.id}".toByteArray()

    private fun failIfWriterStopped() {
        writerFailure?.let { throw IllegalStateException("ledger writer stopped and ${queue.size} transfers are unwritten", it) }
    }

    // Interning, lossless and whole: what goes in comes back out byte for byte, because an edit to a
    // sign has to be reproducible from what was kept of it.
    inner class InternTable(private val cf: ColumnFamilyHandle, private val counterKey: ByteArray) {
        private val idByValue = ConcurrentHashMap<FormKey, Long>()
        private val valueById = ConcurrentHashMap<Long, ByteArray>()
        private var nextId: Long = 0

        fun load() {
            nextId = readCounter(counterKey)
            db.newIterator(cf, wholeCfRead).use { iter ->
                iter.seekToFirst()
                while (iter.isValid) {
                    remember(ByteReader(iter.key()).longBE(), iter.value())
                    iter.next()
                }
            }
        }

        // Minting is a read-modify-write of the counter, and the block plane interns from a writer
        // thread of its own, so two threads reaching a value neither has seen would hand out one
        // number twice and file two different things under it.
        @Synchronized
        fun idOf(value: ByteArray): Long {
            idByValue[FormKey(value)]?.let { return it }
            val id = nextId++
            remember(id, value)
            stage {
                it.put(cf, longBytes(id), value)
                it.put(metaCf, counterKey, longBytes(nextId))
            }
            return id
        }

        fun valueOf(id: Long): ByteArray? = valueById[id]

        // Asking whether a form is known must not name it: a reconciliation that walks a live
        // inventory would otherwise mint an id for every item the ledger has never recorded.
        fun lookup(value: ByteArray): Long? = idByValue[FormKey(value)]

        private fun remember(id: Long, value: ByteArray) {
            idByValue[FormKey(value)] = id
            valueById[id] = value
        }
    }
}
