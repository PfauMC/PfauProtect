package io.pfaumc.pfauprotect

import org.rocksdb.BlockBasedTableConfig
import org.rocksdb.BloomFilter
import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.CompressionType
import org.rocksdb.DBOptions
import org.rocksdb.LRUCache
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
interface PlacedForms {
    fun formAt(world: UUID, x: Int, y: Int, z: Int): ByteArray?
    fun setFormAt(world: UUID, x: Int, y: Int, z: Int, form: ByteArray)
    fun clearFormAt(world: UUID, x: Int, y: Int, z: Int)
}

// A row this build cannot decode is not a row without gaps: it is a row nobody looked at. Counting
// the two together would let a ledger that has become unreadable report a clean sweep.
data class SweepReport(val checked: Int, val unreadable: Int, val gaps: List<String>, val reachedEnd: Boolean)

// Whether the walk ran out of rows or out of budget travels with the rows it brought back. A caller
// that has to tell the difference cannot get it from the size of the list: a scan that stopped one
// row short of the end and one that stopped in the middle of a year both come back full.
data class EntryPage(val entries: List<LedgerEntry>, val complete: Boolean)

// Raised when the key layout took a fixed-width world number and a trailing posting ordinal. The
// record version in the value cannot cover a key change: those rows carry a version this build reads,
// so without the bump an older database opens and every key is parsed as something it never was.
private const val SCHEMA_VERSION = 2L
private const val MAX_REGION_CHUNKS = 1024
private const val MAX_BATCH = 256
private const val WRITER_POLL_MILLIS = 50L
private const val WAL_FLUSH_INTERVAL_NANOS = 1_000_000_000L
private const val DRAIN_TIMEOUT_NANOS = 10_000_000_000L
private const val UNASSIGNED = -1

// Longer than the longest tail a key can carry after any prefix this class scans, so a prefix padded
// with this many 0xFF bytes sorts after every key under it while still sharing its prefix.
private const val KEY_TAIL_PAD = 32

// One cache for every column family. An unconfigured family quietly gets a 32 MiB cache of its own,
// so leaving this out is not "no cache" but eight of them.
private const val BLOCK_CACHE_BYTES = 64L * 1024 * 1024
private const val BLOOM_BITS_PER_KEY = 10.0

// The ledger takes every write; the rest hold a handful of small rows each and have no use for the
// 64 MiB the default memtable would reserve for them.
private const val COLD_WRITE_BUFFER_BYTES = 4L * 1024 * 1024

private val ENTRIES_CF = "entries".toByteArray()
private val ITEM_FORMS_CF = "item_forms".toByteArray()
private val REGISTRY_CF = "registry".toByteArray()
private val META_CF = "meta".toByteArray()
private val NESTED_OWNERS_CF = "nested_owners".toByteArray()
private val TX_CF = "tx".toByteArray()
private val PLACED_FORMS_CF = "placed_forms".toByteArray()

private val META_SCHEMA = "schema".toByteArray()
private val META_TX_ID = "tx_id".toByteArray()
private val META_ITEM_FORM_ID = "item_form_id".toByteArray()
private val META_SWEEP_CURSOR = "sweep_cursor".toByteArray()

private val LOGGER: Logger = Logger.getLogger("PfauProtect")

private fun longBytes(v: Long): ByteArray = ByteWriter(8).longBE(v).toByteArray()

// Cheap compression while a level is still being rewritten, and the slow thorough one once it has
// settled at the bottom and will be read far more often than it is written.
private fun compressed() = ColumnFamilyOptions()
    .setCompressionType(CompressionType.LZ4_COMPRESSION)
    .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)

// The smallest key that sorts after everything under `prefix`. Every prefix this class scans opens
// with a holder type, which is never 0xFF, so such a key always exists.
private fun afterPrefix(prefix: ByteArray): ByteArray {
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
    private val cfHandles = ArrayList<ColumnFamilyHandle>()
    private val db: RocksDB
    private val entriesCf: ColumnFamilyHandle
    private val itemFormsCf: ColumnFamilyHandle
    private val registryCf: ColumnFamilyHandle
    private val metaCf: ColumnFamilyHandle
    private val nestedOwnersCf: ColumnFamilyHandle
    private val txCf: ColumnFamilyHandle
    private val placedFormsCf: ColumnFamilyHandle

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
    private var nextTxId: Long = 0
    private var nextItemFormId: Long = 0
    private val registryCounters = ConcurrentHashMap<RegistryNamespace, Long>()

    init {
        RocksDB.loadLibrary()
        Files.createDirectories(dir)
        val descriptors = listOf(
            RocksDB.DEFAULT_COLUMN_FAMILY to unfilteredOptions,
            ENTRIES_CF to entriesOptions,
            ITEM_FORMS_CF to pointReadOptions,
            REGISTRY_CF to unfilteredOptions,
            META_CF to unfilteredOptions,
            NESTED_OWNERS_CF to pointReadOptions,
            TX_CF to pointReadOptions,
            PLACED_FORMS_CF to pointReadOptions,
        ).map { (name, options) -> ColumnFamilyDescriptor(name, options) }
        db = RocksDB.open(dbOptions, dir.toAbsolutePath().toString(), descriptors, cfHandles)
        entriesCf = cfHandles[1]
        itemFormsCf = cfHandles[2]
        registryCf = cfHandles[3]
        metaCf = cfHandles[4]
        nestedOwnersCf = cfHandles[5]
        txCf = cfHandles[6]
        placedFormsCf = cfHandles[7]

        failClosed {
            val schema = db.get(metaCf, META_SCHEMA)
            if (schema == null) {
                db.put(metaCf, META_SCHEMA, longBytes(SCHEMA_VERSION))
            } else {
                val stored = ByteReader(schema).longBE()
                require(stored == SCHEMA_VERSION) { "database schema $stored cannot be read by this build (schema $SCHEMA_VERSION)" }
            }
            nextTxId = readCounter(META_TX_ID)
            nextItemFormId = readCounter(META_ITEM_FORM_ID)
            for (ns in RegistryNamespace.entries) registryCounters[ns] = readCounter(registryCounterKey(ns))
        }
    }

    private val writerThread = Thread(::runWriter, "pfauprotect-ledger-writer").apply { isDaemon = true }

    val registries = Registries(this)
    val forms = ItemFormTable()

    // An unregistered world or player owns no rows at all, so a scan for one has to find nothing.
    // A player number is a varint and -1 encodes to something the registry never hands out; a world
    // number is two fixed bytes and -1 lands on 0xFFFF, which stays free only while the registry is
    // never asked for its 65536th world.
    private val knownIds = IdResolver { ns, uuid -> registries.lookupKey(ns, uuid.toString()) ?: UNASSIGNED }

    init {
        failClosed {
            registries.load()
            forms.load()
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
            // Keys inside a chunk are ordered by position and only then by time, so a chunk holding
            // more rows than the limit contributes them by position rather than by time.
            for (cx in chunkX) {
                for (cz in chunkZ) {
                    for (prefix in EntryCodec.blockChunkPrefixes(world, cx, cz, knownIds)) {
                        val page = scan(prefix, fromTs, toTs, reverse, limit)
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
        val other = entry.counterparty
        // A transaction of one movement writes exactly the ordinals 0 and 1, so the half facing this
        // one is a point read away. A larger transaction can hold an unrelated posting at that
        // ordinal, so the row found has to face back before it is believed; the rest goes to the index.
        if (other.addressable && entry.ordinal <= 1) {
            val otherKey = EntryCodec.key(other, entry.timestamp, entry.txId, 1 - entry.ordinal, knownIds)
            val decoded = db.get(entriesCf, otherKey)?.let { EntryCodec.decodeOrNull(otherKey, it, registries) }
            if (decoded != null && decoded.holder == other && decoded.counterparty == entry.holder) {
                return listOf(entry, decoded)
            }
        }
        val packed = db.get(txCf, longBytes(entry.txId)) ?: return listOf(entry)
        val found = unpackKeys(packed).mapNotNull { key ->
            db.get(entriesCf, key)?.let { EntryCodec.decodeOrNull(key, it, registries) }
        }
        if (found.isEmpty()) listOf(entry) else found
    }

    fun form(itemFormId: Long): ByteArray? = forms.formOf(itemFormId)

    fun formId(form: ByteArray): Long? = forms.lookup(form)

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

    private fun closeNatives() {
        cfHandles.forEach { it.close() }
        db.close()
        writeOptions.close()
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
        action: (ByteArray, ByteArray) -> Boolean,
    ) {
        val lower = Slice(prefix)
        val upper = Slice(afterPrefix(prefix))
        try {
            ReadOptions()
                .setIterateLowerBound(lower)
                .setIterateUpperBound(upper)
                // A prefix shorter than the extractor spreads its rows over many extractor prefixes,
                // so no seek key can stand for all of them and the walk has to leave prefix mode.
                .setTotalOrderSeek(prefix.size < EntryCodec.CHUNK_PREFIX_SIZE)
                .use { options ->
                    db.newIterator(entriesCf, options).use { iter ->
                        if (reverse) iter.seekForPrev(lastUnder(prefix)) else iter.seekToFirst()
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

    // Registry and form rows must land in the same batch as the counter that named them, or a crash
    // in between hands the same number out twice. Startup registry fills run before the writer has a
    // batch open, so they get a batch of their own.
    private fun stage(write: (WriteBatch) -> Unit) {
        val pending = if (Thread.currentThread() === writerThread) pendingBatch else null
        if (pending != null) {
            write(pending)
            return
        }
        dbLock.read {
            check(!closed) { "the ledger is closed" }
            WriteBatch().use { batch ->
                write(batch)
                db.write(writeOptions, batch)
            }
        }
    }

    private fun readCounter(key: ByteArray): Long = db.get(metaCf, key)?.let { ByteReader(it).longBE() } ?: 0L

    private fun registryCounterKey(ns: RegistryNamespace): ByteArray = "reg:${ns.id}".toByteArray()

    private fun failIfWriterStopped() {
        writerFailure?.let { throw IllegalStateException("ledger writer stopped and ${queue.size} transfers are unwritten", it) }
    }

    inner class ItemFormTable {
        private val idByForm = ConcurrentHashMap<FormKey, Long>()
        private val formById = ConcurrentHashMap<Long, ByteArray>()

        fun load() {
            db.newIterator(itemFormsCf, wholeCfRead).use { iter ->
                iter.seekToFirst()
                while (iter.isValid) {
                    remember(ByteReader(iter.key()).longBE(), iter.value())
                    iter.next()
                }
            }
        }

        fun idOf(form: ByteArray): Long {
            idByForm[FormKey(form)]?.let { return it }
            val id = nextItemFormId++
            remember(id, form)
            stage {
                it.put(itemFormsCf, longBytes(id), form)
                it.put(metaCf, META_ITEM_FORM_ID, longBytes(nextItemFormId))
            }
            return id
        }

        fun formOf(id: Long): ByteArray? = formById[id]

        // Asking whether a form is known must not name it: a reconciliation that walks a live
        // inventory would otherwise mint an id for every item the ledger has never recorded.
        fun lookup(form: ByteArray): Long? = idByForm[FormKey(form)]

        private fun remember(id: Long, form: ByteArray) {
            idByForm[FormKey(form)] = id
            formById[id] = form
        }
    }
}
