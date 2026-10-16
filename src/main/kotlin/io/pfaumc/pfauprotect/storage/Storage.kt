package io.pfaumc.pfauprotect.storage
import io.pfaumc.pfauprotect.Metrics
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.HolderType
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.PostingRef
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.WorldBlock
import org.rocksdb.BlockBasedTableConfig
import org.rocksdb.BloomFilter
import org.rocksdb.Cache
import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.CompressionType
import org.rocksdb.DBOptions
import org.rocksdb.Filter
import org.rocksdb.IndexType
import org.rocksdb.LRUCache
import org.rocksdb.Options
import org.rocksdb.ReadOptions
import org.rocksdb.RocksDB
import org.rocksdb.WriteBatch
import org.rocksdb.WriteBufferManager
import org.rocksdb.WriteOptions
import java.nio.file.Files
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.Cause
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

// A block can't say which item it was built from (a named box reads as a plain one), so the placed form
// is kept here while it stands. The batch calls exist because an explosion reaches these from the region
// thread, where one JNI trip per position costs a tick.
interface PlacedForms {
    fun formAt(at: WorldBlock): ByteArray?
    fun setFormAt(at: WorldBlock, form: ByteArray)
    fun clearFormAt(at: WorldBlock)
    fun takeFormsAt(positions: List<WorldBlock>): Map<WorldBlock, ByteArray>

    fun takeFormAt(at: WorldBlock): ByteArray? = formAt(at).also { clearFormAt(at) }
}

// A row this build cannot decode is not a row without gaps: it is a row nobody looked at. Counting
// the two together would let a ledger that has become unreadable report a clean sweep.
data class SweepReport(val checked: Int, val unreadable: Int, val gaps: List<String>, val reachedEnd: Boolean)

// `complete` tells a walk that reached the end from one cut off by its budget, which the list size can't;
// `unreadable` lets a rollback refuse to act over rows it couldn't decode.
data class EntryPage(val entries: List<LedgerEntry>, val complete: Boolean, val unreadable: Int = 0)

// Everything the item plane holds against one block position, and the rows of that position nobody
// could decode. A row that did not decode never balanced to anything, so it is counted apart from the
// postings rather than left out of them; `at` is null only where no row of the position decoded at all
// and the position cannot even be named.
data class BlockPostings(val at: WorldBlock?, val entries: List<LedgerEntry>, val unreadable: Int)

data class BlockPostingsPage(val positions: List<BlockPostings>, val reachedEnd: Boolean)

// What a rollback took back from a player who was not there to give it: the form, how many, and who ran
// the rollback. `key` is where it is kept, to be forgiven once taken.
class OwedItem(val key: ByteArray, val formId: Long, val qty: Int, val actor: UUID?, val since: Long = 0)

// Raised by a change to the key layout, the set of column families or the meaning of stored numbers:
// the record version in the value covers none of them, so without the bump an older database opens and
// every key is parsed as something it never was. Version 4 moved menu numbers to a registry without
// touching the layout; an ordinary entry's header byte is pinned to 0x00, so only this number can refuse
// such a database. WIDENS_FROM differ only by families this build adds on open, and an older build then
// refuses the result by the stamp.
private const val SCHEMA_VERSION = 6L
private val WIDENS_FROM = setOf(4L, 5L)

// How many times a giving back is followed to the giving back of itself: a rollback, its undo, the
// undo of that. Past this a posting is taken as given back, which is the side that cannot mint items.
internal const val MAX_UNDO_DEPTH = 16

private const val FORMS = 0
private const val OWNERS = 1
private const val MAX_REGION_SIDE_CHUNKS = 32
private const val MAX_REGION_CHUNKS = MAX_REGION_SIDE_CHUNKS * MAX_REGION_SIDE_CHUNKS

// The widest radius whose square fits the region read wherever its centre falls: 2r blocks starting on
// a chunk's last block reach into ceil(2r / 16) chunks after it.
internal const val MAX_REGION_RADIUS = (MAX_REGION_SIDE_CHUNKS - 1) * 8

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

// One cache for every column family of the ledger and of every world's base, with their memtables and
// their index and filter blocks counted inside it: the whole native footprint of the plugin has this one
// bound. An unconfigured family quietly gets a 32 MiB cache of its own, and a base memtables of 64 MiB
// a family, which across the ledger and every world adds up to hundreds of MiB nobody sized.
internal const val BLOCK_CACHE_BYTES = 256L * 1024 * 1024

// Of that, what memtables of all the bases together may hold before the fullest is flushed.
internal const val MEMTABLE_BYTES = 64L * 1024 * 1024
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
private val COMPENSATED_CF = "compensated".toByteArray()
private val CONFISCATIONS_CF = "confiscations".toByteArray()

private val META_SCHEMA = "schema".toByteArray()
private val META_TX_ID = "tx_id".toByteArray()
private val META_ITEM_FORM_ID = "item_form_id".toByteArray()
private val META_BLOCK_PAYLOAD_ID = "block_payload_id".toByteArray()
private val META_SWEEP_CURSOR = "sweep_cursor".toByteArray()
private val META_BLOCK_CURSOR = "block_cursor".toByteArray()

private val WORLD_BLOCK_PREFIX = byteArrayOf(HolderType.WORLD_BLOCK.toByte())

internal val LOGGER: Logger = Logger.getLogger("PfauProtect")

internal fun longBytes(v: Long): ByteArray = ByteWriter(8).longBE(v).toByteArray()

internal fun RocksDB.readCounter(cf: ColumnFamilyHandle, key: ByteArray): Long =
    get(cf, key)?.let { ByteReader(it).longBE() } ?: 0L

internal fun openingOptions(writeBuffers: WriteBufferManager): DBOptions = DBOptions()
    .setCreateIfMissing(true)
    .setCreateMissingColumnFamilies(true)
    .setWriteBufferManager(writeBuffers)

// The handles before the database that issued them; the options are the caller's to free after both.
internal fun RocksDB.closeWithHandles(handles: List<ColumnFamilyHandle>) {
    handles.forEach { it.close() }
    close()
}

// Cheap compression while a level is still being rewritten, and the slow thorough one once it has
// settled at the bottom and will be read far more often than it is written.
internal fun compressed() = ColumnFamilyOptions()
    .setCompressionType(CompressionType.LZ4_COMPRESSION)
    .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)

// The smallest key that sorts after everything under `prefix`. A prefix of nothing but 0xFF bytes has
// none, and an item key cannot produce one: it opens with a holder type, which is never 0xFF.
internal fun afterPrefix(prefix: ByteArray): ByteArray {
    val last = prefix.indexOfLast { it != 0xFF.toByte() }
    require(last >= 0) { "a prefix of nothing but 0xFF bytes has no key after it" }
    return prefix.copyOf(last + 1).also { it[last]++ }
}

// Index and filter blocks live in the cache too: outside it a table reader keeps them for as long as
// its file is open, and they grow with the data with nothing to bound them. They go into the cache's
// high-priority pool, ahead of data blocks, since evicting an index costs a read on every lookup.
// A file's index and filter, whole, grow with its rows to megabytes, and a base of billions of rows has
// more of them than the cache holds: a point read loaded a whole file's worth to answer one key. Cut into
// partitions, a read loads the few kilobytes it needs, and the small top level of each stays pinned.
internal fun tableIn(cache: Cache, filter: Filter? = null): BlockBasedTableConfig =
    BlockBasedTableConfig()
        .setBlockCache(cache)
        .setCacheIndexAndFilterBlocks(true)
        .setCacheIndexAndFilterBlocksWithHighPriority(true)
        .setIndexType(IndexType.kTwoLevelIndexSearch)
        .setPartitionFilters(filter != null)
        .setMetadataBlockSize(METADATA_BLOCK_BYTES)
        .setPinTopLevelIndexAndFilter(true)
        .also { table -> filter?.let { table.setFilterPolicy(it) } }

private const val METADATA_BLOCK_BYTES = 4096L
internal const val CACHE_SHARD_BITS = 2

// Refusing a database that is already open, or failing to open it, has to hand the natives back with
// the refusal. Nothing outside reaches a constructor that threw, so the file lock and the native memory
// would be held until the process ends, and the next attempt to open — a retry, a reload — could never
// succeed.
internal inline fun <T> cleaningUpOnFailure(cleanup: () -> Unit, body: () -> T): T =
    try {
        body()
    } catch (failure: Throwable) {
        runCatching { cleanup() }.onFailure(failure::addSuppressed)
        throw failure
    }

// Read-only and with exactly the families already on disk, so a database this build refuses is handed
// back untouched. Null means there is nothing to refuse: no database, or one from before the schema was
// stamped.
internal fun storedSchema(path: String, metaCf: ByteArray, key: ByteArray): Long? = Options().use { probe ->
    // Every database has a CURRENT file naming its manifest, so a directory without one holds none. The
    // listing cannot answer that: the Java binding drops its error and hands back an empty list, for a
    // database it failed to read as much as for none at all.
    if (!Files.exists(Path.of(path, "CURRENT"))) return null
    val existing = RocksDB.listColumnFamilies(probe, path)
    check(existing.isNotEmpty()) { "the database at $path exists but its column families could not be read" }
    val metaIndex = existing.indexOfFirst { it.contentEquals(metaCf) }
    if (metaIndex < 0) return null
    val handles = ArrayList<ColumnFamilyHandle>()
    ColumnFamilyOptions().use { cfOptions ->
        DBOptions().use { options ->
            RocksDB.openReadOnly(options, path, existing.map { ColumnFamilyDescriptor(it, cfOptions) }, handles)
                .let { probed ->
                    try {
                        probed.get(handles[metaIndex], key)?.let { ByteReader(it).longBE() }
                    } finally {
                        probed.closeWithHandles(handles)
                    }
                }
        }
    }
}

// Padded rather than the key after the prefix: a backward seek lands on the last key at or before its
// target, and the key after the prefix may itself be a key, one that already belongs to the next prefix.
internal const val ESTIMATED_KEYS = "rocksdb.estimate-num-keys"

/** Bytes compaction still has to rewrite across the families, with each compaction running counted as one. */
internal fun RocksDB.compactionDebt(handles: List<ColumnFamilyHandle>): Long =
    handles.sumOf { getLongProperty(it, "rocksdb.estimate-pending-compaction-bytes") } + getLongProperty("rocksdb.num-running-compactions")

internal fun lastUnder(prefix: ByteArray): ByteArray =
    ByteWriter(prefix.size + KEY_TAIL_PAD)
        .bytes(prefix)
        .bytes(ByteArray(KEY_TAIL_PAD) { 0xFF.toByte() })
        .toByteArray()

internal fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && java.util.Arrays.equals(this, 0, prefix.size, prefix, 0, prefix.size)

// In a family with a prefix extractor RocksDB defines a prefix-mode walk only inside the extractor prefix
// it was seeked into, and says the same of a seek key and its bounds; a walk here may span many extractor
// prefixes, start from the first key or run backwards, and total order with no bounds is defined for all
// of it. `action` returns false to stop.
internal inline fun RocksDB.forEachUnder(
    cf: ColumnFamilyHandle,
    prefix: ByteArray,
    reverse: Boolean,
    from: ByteArray? = null,
    action: (ByteArray, ByteArray) -> Boolean,
) {
    ReadOptions().setTotalOrderSeek(true).use { options ->
        newIterator(cf, options).use { iter ->
            if (reverse) iter.seekForPrev(from ?: lastUnder(prefix)) else iter.seek(from ?: prefix)
            while (iter.isValid) {
                val key = iter.key()
                if (!key.startsWith(prefix)) break
                if (!action(key, iter.value())) return
                if (reverse) iter.prev() else iter.next()
            }
            // An I/O error or a corrupt block ends the walk exactly as the last row does, and only the
            // status tells the two apart; a partial answer must not pass for a whole one.
            iter.status()
        }
    }
}

internal fun timeAt(key: ByteArray, offset: Int): Long = ByteReader(key).also { it.bytes(offset) }.longBE()

/** A box of block positions, both ends included. */
data class Box(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int, val minZ: Int, val maxZ: Int)

/** The newest rows a walk of a box found, newest first; not `complete` when it ran out of budget first. */
data class BoxPage<T>(val rows: List<T>, val complete: Boolean)

/**
 * The [wanted] newest rows of the window whose position is in [box] and in one chunk, newest first, of a
 * family whose keys run [head], the position as `Zcode` writes it, then the time: each position's history
 * is one run, oldest first, and a chunk's positions run column by column. The positions are walked backward
 * and each is read back only while its rows are newer than the [wanted]th newest found so far, which after
 * the first few positions is one row. A busy place costs a seek per position of the box that has a history,
 * where reading the chunk would cost all of it. [budget] bounds the seeks and the rows together.
 */
internal fun <T> RocksDB.newestInBox(
    cf: ColumnFamilyHandle,
    head: ByteArray,
    chunkX: Int,
    chunkZ: Int,
    box: Box,
    fromTs: Long,
    toTs: Long,
    wanted: Int,
    budget: Int,
    decode: (ByteArray, ByteArray) -> T?,
): BoxPage<T> {
    val xs = maxOf(box.minX, chunkX shl 4)..minOf(box.maxX, (chunkX shl 4) + 15)
    val zs = maxOf(box.minZ, chunkZ shl 4)..minOf(box.maxZ, (chunkZ shl 4) + 15)
    if (xs.isEmpty() || zs.isEmpty() || box.minY > box.maxY || wanted <= 0) return BoxPage(emptyList(), true)
    val found = java.util.PriorityQueue<Pair<Long, T>>(compareBy { it.first })
    val stamp = head.size + Zcode.SIZE
    var spent = 0
    ReadOptions().setTotalOrderSeek(true).use { options ->
        newIterator(cf, options).use { iter ->
            for (x in xs.reversed()) for (z in zs.reversed()) {
                val column = head + Zcode.encode(x, box.maxY, z).copyOf(Zcode.SIZE - 2)
                iter.seekForPrev(lastUnder(head + Zcode.encode(x, box.maxY, z)))
                while (iter.isValid) {
                    val key = iter.key()
                    if (!key.startsWith(column) || key.size < stamp + 8 || Zcode.decode(key, head.size)[1] < box.minY) break
                    val position = key.copyOf(stamp)
                    if (toTs != Long.MAX_VALUE) iter.seekForPrev(lastUnder(position + longBytes(toTs)))
                    while (iter.isValid) {
                        val row = iter.key()
                        if (!row.startsWith(position)) break
                        if (++spent > budget) return BoxPage(found.sortedByDescending { it.first }.map { it.second }, false)
                        val ts = timeAt(row, stamp)
                        if (ts < fromTs || found.size >= wanted && ts <= found.peek().first) break
                        decode(row, iter.value())?.let {
                            found += ts to it
                            if (found.size > wanted) found.poll()
                        }
                        iter.prev()
                    }
                    if (++spent > budget) return BoxPage(found.sortedByDescending { it.first }.map { it.second }, false)
                    // The last key before this position: the newest row of the one below it, or another column's.
                    iter.seekForPrev(position)
                }
            }
            iter.status()
        }
    }
    return BoxPage(found.sortedByDescending { it.first }.map { it.second }, true)
}

// Every row of a family in key order: every key starts with the empty prefix.
internal inline fun RocksDB.forEachRow(cf: ColumnFamilyHandle, action: (ByteArray, ByteArray) -> Unit) =
    forEachUnder(cf, ByteArray(0), reverse = false) { key, value ->
        action(key, value)
        true
    }

// How many rows a walk over a whole family writes per batch: a purge, a base being indexed.
private const val WALK_BATCH_ROWS = 10_000

// A batch written and started afresh every so many rows, so a walk over a whole family never holds
// all of its writes in memory at once. `flush` writes what is left.
internal class ChunkedBatch(private val db: RocksDB, private val options: WriteOptions) : AutoCloseable {
    private var batch = WriteBatch()

    fun put(cf: ColumnFamilyHandle, key: ByteArray, value: ByteArray) {
        batch.put(cf, key, value)
        if (batch.count() >= WALK_BATCH_ROWS) flush()
    }

    fun delete(cf: ColumnFamilyHandle, key: ByteArray) {
        batch.delete(cf, key)
        if (batch.count() >= WALK_BATCH_ROWS) flush()
    }

    fun flush() {
        if (batch.count() == 0) return
        db.write(options, batch)
        batch.close()
        batch = WriteBatch()
    }

    override fun close() = batch.close()
}

// What the writer of either base has been handed, what it has written, and why it stopped if it did.
// `writer` and `unit` name them in the errors: the ledger writer and its transfers, a block writer and
// its submissions.
internal class WriterProgress<T>(private val writer: String, private val unit: String, private val queue: BlockingQueue<T>) {
    val submitted = AtomicLong()
    val written = AtomicLong()

    @Volatile
    var failure: Throwable? = null

    val backlog: Long get() = submitted.get() - written.get()

    fun drain() {
        val target = submitted.get()
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS
        while (written.get() < target) {
            failIfStopped()
            check(System.nanoTime() < deadline) {
                "$writer writer did not catch up in ${DRAIN_TIMEOUT_NANOS / 1_000_000} ms, " +
                    "${target - written.get()} $unit are unwritten"
            }
            Thread.sleep(1)
        }
        failIfStopped()
    }

    private fun failIfStopped() {
        failure?.let { throw IllegalStateException("$writer writer stopped and ${queue.size} $unit are unwritten", it) }
    }

    // The loop both writers run: a batch at a time until `finished` says an empty queue is the end, with the
    // log fsynced once a second. `beforeEach` runs after every wait, whether anything came or not. A failure
    // stops the writer for good and is kept for `drain` to report.
    fun run(db: RocksDB, finished: () -> Boolean, beforeEach: () -> Unit = {}, write: (List<T>) -> Unit) {
        var lastFlush = System.nanoTime()
        try {
            while (true) {
                val first = queue.poll(WRITER_POLL_MILLIS, TimeUnit.MILLISECONDS)
                beforeEach()
                if (first == null && finished()) return
                if (first != null) {
                    val batched = ArrayList<T>(MAX_BATCH)
                    batched += first
                    queue.drainTo(batched, MAX_BATCH - 1)
                    Metrics.writes.labelValues(writer).startTimer().use { write(batched) }
                    written.addAndGet(batched.size.toLong())
                    Metrics.written.labelValues(writer).inc(batched.size.toDouble())
                }
                if (System.nanoTime() - lastFlush >= WAL_FLUSH_INTERVAL_NANOS) {
                    db.flushWal(true)
                    lastFlush = System.nanoTime()
                }
            }
        } catch (failure: Throwable) {
            this.failure = failure
            LOGGER.log(Level.SEVERE, "$writer writer stopped, ${queue.size} $unit are unwritten and further ones are dropped", failure)
        }
    }
}

class RocksItemLog(dir: Path) : AutoCloseable, RegistryStore, PlacedForms {
    // Before any native object: a cache, unlike the option classes, does not load the library itself.
    init {
        RocksDB.loadLibrary()
    }

    // Lent to every world's base for as long as it is open. The pool is the native default's half for
    // index and filter blocks, which RocksJava's one-argument constructor leaves at nothing. Four shards
    // rather than the native default's 64: a file written before its index and filter were partitioned
    // has them as one block of megabytes, which a 4 MB shard cannot keep, and every read loaded it again.
    internal val blockCache = LRUCache(BLOCK_CACHE_BYTES, CACHE_SHARD_BITS, false, 0.5)
    internal val writeBuffers = WriteBufferManager(MEMTABLE_BYTES, blockCache)

    // Every base built on the cache and the memtable budget holds them until its own options are freed,
    // and one that failed to close still holds them; freeing them earlier is a use after free in native
    // code. So they go when the ledger and the last borrower are both done, whichever is later.
    private val lendingLock = Any()
    private var borrowers = 0
    private var lending = true

    internal fun borrowShared() = synchronized(lendingLock) {
        check(lending) { "the ledger is closed" }
        borrowers++
    }

    internal fun returnShared() = synchronized(lendingLock) {
        borrowers--
        if (!lending && borrowers == 0) freeShared()
    }

    private fun stopLending() = synchronized(lendingLock) {
        lending = false
        if (borrowers == 0) freeShared()
        else LOGGER.log(Level.WARNING, "the block cache stays allocated for $borrowers world bases still open")
    }

    private fun freeShared() {
        writeBuffers.close()
        blockCache.close()
    }
    private val dbOptions = openingOptions(writeBuffers)
    private val bloom = BloomFilter(BLOOM_BITS_PER_KEY)

    // Every family we ever ask for a single row by key wants the filter; the ones we only ever walk
    // would pay for it on every write and save nothing.
    private val filteredTable = tableIn(blockCache, bloom)
    private val plainTable = tableIn(blockCache)

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

    private val writeOptions = WriteOptions()

    // A registry number or a payload may be named by another database, and that database flushes its
    // own log on a timer of its own. Write order alone does not survive a crash: the row citing the
    // number can reach the disk while the interning that produced it is still in this log, and on
    // restart the counter has rewound and hands the same number to a different value, so the surviving
    // row decodes to something it never named. Fsyncing here is what orders the two. Interning is a
    // first-encounter cost, with the startup fill a single batch, so the flush is paid once per new value
    // rather than once per row.
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

    // Which postings a rollback has given back, and by which transaction: the original's tx_id and
    // ordinal as the key, the compensating tx_id as the value.
    private val compensatedCf: ColumnFamilyHandle

    // What a rollback took back from a player who was offline, kept until they join: the player and an
    // ever-growing number as the key, the form, the quantity and who ran the rollback as the value.
    private val confiscationsCf: ColumnFamilyHandle
    private val nextOwed = AtomicLong(System.currentTimeMillis() * 1000)

    private val queue = LinkedBlockingQueue<List<Transfer>>()

    // Notes are set from region threads, which must not wait on the writer, so a note goes into this
    // overlay at once (reads look here first) and the writer removes it once written. A null value is a
    // cleared note.
    private data class NoteKey(val family: Int, val at: WorldBlock)

    // Compared by identity: the writer takes a note off the overlay only if it is still this very write.
    private class NoteWrite(val key: NoteKey, val value: ByteArray?)

    private val pendingNotes = ConcurrentHashMap<NoteKey, NoteWrite>()
    private val noteQueue = LinkedBlockingQueue<NoteWrite>()
    private val progress = WriterProgress("ledger", "transfers", queue)

    val backlog: Long get() = progress.backlog

    /** How many postings the ledger holds, as RocksDB estimates it without reading them. */
    fun estimatedRows(): Map<String, Long> = dbLock.read {
        if (closed) return emptyMap()
        mapOf("entries" to db.getLongProperty(entriesCf, ESTIMATED_KEYS))
    }

    fun compactionDebt(): Long = dbLock.read { if (closed) 0 else db.compactionDebt(cfHandles) }

    @Volatile
    private var running = true

    @Volatile
    private var closed = false

    private val dbLock = ReentrantReadWriteLock()

    private var pendingBatch: WriteBatch? = null

    // Held by the writer around each batch and by a purge around its single write: both take transaction
    // ids, and the counter is not shared otherwise.
    private val writing = java.util.concurrent.locks.ReentrantLock()

    private var nextTxId: Long = 0
    private val registryCounters = ConcurrentHashMap<RegistryNamespace, Long>()

    init {
        Files.createDirectories(dir)
        val path = dir.toAbsolutePath().toString()
        // Opening writes every missing column family into the manifest before anything can look at
        // the schema, and a build that does not know those families can no longer open the database
        // at all. A database refused for its schema has to be left exactly as it was found, or one
        // failed start of a newer build makes going back impossible.
        val stored = cleaningUpOnFailure({ closeOptions() }) {
            storedSchema(path, META_CF, META_SCHEMA)?.also {
                require(it == SCHEMA_VERSION || it in WIDENS_FROM) {
                    "database schema $it cannot be read by this build (schema $SCHEMA_VERSION); " +
                        "the ledger and blocks directories have to be removed together, because a " +
                        "block journal cites state, payload and player numbers minted in the ledger"
                }
            }
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
            COMPENSATED_CF to pointReadOptions,
            CONFISCATIONS_CF to unfilteredOptions,
        ).map { (name, options) -> ColumnFamilyDescriptor(name, options) }
        db = cleaningUpOnFailure({ closeOptions() }) { RocksDB.open(dbOptions, path, descriptors, cfHandles) }
        entriesCf = cfHandles[1]
        itemFormsCf = cfHandles[2]
        registryCf = cfHandles[3]
        metaCf = cfHandles[4]
        nestedOwnersCf = cfHandles[5]
        txCf = cfHandles[6]
        placedFormsCf = cfHandles[7]
        blockPayloadsCf = cfHandles[8]
        compensatedCf = cfHandles[9]
        confiscationsCf = cfHandles[10]

        failClosed {
            if (stored != SCHEMA_VERSION) db.put(metaCf, META_SCHEMA, longBytes(SCHEMA_VERSION))
            nextTxId = db.readCounter(metaCf, META_TX_ID)
            for (ns in RegistryNamespace.entries) registryCounters[ns] = db.readCounter(metaCf, registryCounterKey(ns))
        }
    }

    private val writerThread = Thread(::runWriter, "pfauprotect-ledger-writer").apply { isDaemon = true }

    val registries = Registries(this)
    val forms = InternTable(itemFormsCf, META_ITEM_FORM_ID)

    // Block rows in the world bases cite these ids, so interning is synced (see syncWriteOptions).
    val payloads = InternTable(blockPayloadsCf, META_BLOCK_PAYLOAD_ID)

    // Unregistered ids map to -1, which encodes to a number neither registry hands out, so a scan for
    // them finds nothing.
    private val knownIds = IdResolver { ns, uuid -> registries.lookupUuid(ns, uuid) ?: UNASSIGNED }

    init {
        failClosed {
            registries.load()
            forms.load()
            payloads.load()
            writerThread.start()
        }
    }

    private inline fun <T> failClosed(body: () -> T): T = cleaningUpOnFailure({ closeNatives() }, body)

    fun submit(transfer: Transfer) = submit(listOf(transfer))

    /** Every movement in the list shares one `tx_id`, which is what carries a graph walk across `Void`. */
    fun submit(transaction: List<Transfer>) {
        // Once the writer is told to stop nobody takes the queue, and `drain` would wait for it in vain.
        if (!running || progress.failure != null || transaction.isEmpty()) return
        progress.submitted.incrementAndGet()
        queue.add(transaction)
    }

    fun drain() = progress.drain()

    fun holderEntries(
        holder: Holder,
        fromTs: Long,
        toTs: Long,
        limit: Int = 100,
    ): List<LedgerEntry> = holderPage(holder, fromTs, toTs, limit = limit).entries

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

    /** The newest postings of the holders standing in the box, newest first, see [newestInBox]. */
    fun boxPage(world: UUID, box: Box, fromTs: Long, toTs: Long, limit: Int, budget: Int): EntryPage {
        if (registries.lookupUuid(RegistryNamespace.WORLD, world) == null) return EntryPage(emptyList(), true)
        return dbLock.read {
            if (closed) return EntryPage(emptyList(), false)
            val heads = EntryCodec.positionalHeads(world, knownIds)
            val pages = ((box.minX shr 4)..(box.maxX shr 4)).flatMap { cx ->
                ((box.minZ shr 4)..(box.maxZ shr 4)).flatMap { cz ->
                    // One row past the limit, which is what tells a box with more in it from one read to the end.
                    heads.map { head ->
                        db.newestInBox(entriesCf, head, cx, cz, box, fromTs, toTs, limit + 1, budget) { key, value ->
                            EntryCodec.decodeOrNull(key, value, registries)
                        }
                    }
                }
            }
            val newest = pages.flatMap { it.rows }.sortedWith(compareBy<LedgerEntry>({ it.timestamp }, { it.txId }).reversed())
            EntryPage(newest.take(limit), pages.all { it.complete } && newest.size <= limit)
        }
    }

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
        // The scan reads whole chunks. A caller that wants less than a chunk has to say so here and
        // not afterwards: filtered after the limit, the newest rows of the rest of the chunk crowd the
        // asked-for positions out of the page.
        within: ((Holder) -> Boolean)? = null,
    ): EntryPage {
        val chunkX = (minOf(minX, maxX) shr 4)..(maxOf(minX, maxX) shr 4)
        val chunkZ = (minOf(minZ, maxZ) shr 4)..(maxOf(minZ, maxZ) shr 4)
        val chunks = (chunkX.last - chunkX.first + 1).toLong() * (chunkZ.last - chunkZ.first + 1).toLong()
        require(chunks <= MAX_REGION_CHUNKS) {
            "region spans $chunks chunks, at most $MAX_REGION_CHUNKS can be scanned in one query"
        }
        if (registries.lookupUuid(RegistryNamespace.WORLD, world) == null) {
            return EntryPage(emptyList(), true)
        }
        return dbLock.read {
            if (closed) return EntryPage(emptyList(), false)
            val found = ArrayList<LedgerEntry>()
            var complete = true
            var unreadable = 0
            // Keys in a chunk sort by position before time, so every row is collected before sorting
            // and applying the limit.
            for (cx in chunkX) {
                for (cz in chunkZ) {
                    for (prefix in EntryCodec.blockChunkPrefixes(world, cx, cz, knownIds)) {
                        val room = MAX_REGION_ROWS - found.size
                        if (room <= 0) {
                            complete = false
                            continue
                        }
                        val page = scan(prefix, fromTs, toTs, reverse, room)
                        found += if (within == null) page.entries else page.entries.filter { within(it.holder) }
                        complete = complete && page.complete
                        unreadable += page.unreadable
                    }
                }
            }
            val byTime = compareBy<LedgerEntry>({ it.timestamp }, { it.txId })
            val ordered = found.sortedWith(if (reverse) byTime.reversed() else byTime)
            EntryPage(ordered.take(limit), complete && ordered.size <= limit, unreadable)
        }
    }

    // The entries family is keyed by holder, not by time, so a time window is a walk over all of it.
    internal fun forEachEntry(action: (LedgerEntry) -> Unit) = dbLock.read {
        if (closed) return
        db.forEachRow(entriesCf) { key, value -> EntryCodec.decodeOrNull(key, value, registries)?.let(action) }
    }

    /**
     * Deletes every posting older than the cutoff and writes, dated at the cutoff, what each holder held by
     * then. Both go in one write: a purge cut short between them would leave every balance short of what it
     * deleted, or holding it twice. The writer waits meanwhile, as the openings take its transaction ids.
     * Unreadable rows are left where they are. One walk of the whole family, off any region thread. Returns
     * how many postings went and how many openings stand for them.
     */
    // ponytail: the whole purge is one batch in memory, a few dozen bytes a posting; delete by holder ranges
    // if a purge ever outgrows that.
    fun purgeBefore(cutoff: Long, dryRun: Boolean): Pair<Int, Int> = dbLock.read {
        if (closed) return 0 to 0
        writing.withLock {
            WriteBatch().use { batch ->
                val held = HashMap<Triple<Holder, Long, Int?>, Int>()
                var deleted = 0
                // A posting whose form cannot be read has no opening to stand for it, and deleting it would
                // take its holder's balance with it: it stays.
                db.forEachRow(entriesCf) { key, value ->
                    val entry = EntryCodec.decodeOrNull(key, value, registries)
                    if (entry != null && entry.timestamp < cutoff && form(entry.itemFormId) != null) {
                        held.merge(Triple(entry.holder, entry.itemFormId, entry.damage), entry.qty, Int::plus)
                        deleted++
                        if (!dryRun) {
                            batch.delete(entriesCf, key)
                            batch.delete(txCf, longBytes(entry.txId))
                        }
                    }
                }
                val openings = held.filterValues { it != 0 }.map { (key, qty) ->
                    val form = form(key.second)!!
                    if (qty > 0) Transfer(Cause.PURGE_OPENING, Void, key.first, form, key.third, qty, cutoff)
                    else Transfer(Cause.PURGE_OPENING, key.first, Void, form, key.third, -qty, cutoff)
                }
                if (!dryRun) {
                    pendingBatch = batch
                    try {
                        for (opening in openings) writeTransaction(batch, listOf(opening))
                    } finally {
                        pendingBatch = null
                    }
                    db.write(writeOptions, batch)
                }
                return deleted to openings.size
            }
        }
    }

    fun transactionEntries(entry: LedgerEntry): List<LedgerEntry> = transactionEntries(entry, Int.MAX_VALUE)!!

    /** Null when the transaction's index names more than [atMost] postings, which are then not read. */
    fun transactionEntries(entry: LedgerEntry, atMost: Int): List<LedgerEntry>? = dbLock.read {
        if (closed) return listOf(entry)
        // The index has to be asked first. A transaction that reaches past the first pair holds a
        // posting at the facing ordinal that is nobody's other half, and answering from it would give
        // the same transaction a different extent depending on which of its own postings asked.
        val packed = db.get(txCf, longBytes(entry.txId))
        if (packed != null) {
            if (ByteReader(packed).varInt() > atMost) return null
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

    fun payload(payloadId: Long): ByteArray? = payloads.valueOf(payloadId)

    /**
     * What the ledger books to every slot under these holders, by form, counting only rows written up
     * to a moment: a pass that runs while this reads files rows about a later state than the one it is
     * being compared with. A holder stands for all its slots: a player's inventory, a container's
     * position.
     */
    fun slotBalances(under: List<Holder>, upTo: Long): Map<Holder, Map<FormKey, Int>> = dbLock.read {
        val totals = HashMap<Holder, HashMap<FormKey, Int>>()
        if (closed) return totals
        for (holder in under) {
            forEachUnder(EntryCodec.holderPrefix(holder, knownIds), reverse = false) { key, value ->
                val entry = EntryCodec.decodeOrNull(key, value, registries)
                val form = entry?.takeIf { it.timestamp <= upTo }?.let { forms.valueOf(it.itemFormId) }
                if (entry != null && form != null) {
                    totals.getOrPut(entry.holder) { HashMap() }.merge(FormKey(form), entry.qty, Int::plus)
                }
                true
            }
        }
        totals
    }

    // Every posting that doesn't face the void needs its opposite half; a missing half is a hole in the
    // capture. Each pass resumes from a stored cursor, so the whole ledger is covered at a fixed cost per
    // pass.
    fun sweep(limit: Int): SweepReport = dbLock.read {
        if (closed) return SweepReport(0, 0, emptyList(), false)
        val gaps = ArrayList<String>()
        var checked = 0
        var unreadable = 0
        var last: ByteArray? = null
        var reachedEnd = true
        var found = false
        fun walk(from: ByteArray?) = forEachUnder(ByteArray(0), reverse = false, from = from) { key, value ->
            found = true
            // Unreadable rows spend the budget too: a stretch of them the length of a year would
            // otherwise be walked in a single pass, on the thread that asked for one pass worth.
            if (checked + unreadable >= limit) {
                reachedEnd = false
                return@forEachUnder false
            }
            val entry = EntryCodec.decodeOrNull(key, value, registries)
            if (entry == null) {
                unreadable++
            } else {
                checked++
                gapOf(entry)?.let { gaps += it }
            }
            last = key
            true
        }
        val cursor = db.get(metaCf, META_SWEEP_CURSOR)
        // A trailing zero byte makes the first key after the cursor itself.
        walk(cursor?.let { it.copyOf(it.size + 1) })
        // The walk throws on an error rather than end, so this never wraps round past damage to restart
        // every pass at the first key.
        if (cursor != null && !found) walk(null)
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
     * balance to something the position never held.
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

    /**
     * Owes a player's items to a rollback that could not reach them: they were offline. Written at once
     * and fsynced, because the rollback has already put the items back where they came from and the
     * only other record of the debt is in memory. `since`, when the rollback's window opened, lets the join
     * follow what the player no longer holds into where they put it; it trails the value, so a debt written
     * before it was kept reads as one with nothing to follow.
     */
    fun owe(player: UUID, formId: Long, qty: Int, actor: UUID?, since: Long = 0) = dbLock.read {
        check(!closed) { "the ledger is closed" }
        val key = ByteWriter(24).uuid(player).longBE(nextOwed.getAndIncrement()).toByteArray()
        val value = ByteWriter(34).varLong(formId).varInt(qty).byte(if (actor == null) 0 else 1)
        if (actor != null) value.uuid(actor)
        if (since > 0) value.varLong(since)
        db.put(confiscationsCf, syncWriteOptions, key, value.toByteArray())
    }

    fun owedBy(player: UUID): List<OwedItem> = dbLock.read {
        if (closed) return emptyList()
        val prefix = ByteWriter(16).uuid(player).toByteArray()
        val found = ArrayList<OwedItem>()
        db.forEachUnder(confiscationsCf, prefix, reverse = false) { key, bytes ->
            val value = ByteReader(bytes)
            val formId = value.varLong()
            val qty = value.varInt()
            val actor = if (value.byte() == 1) value.uuid() else null
            found += OwedItem(key, formId, qty, actor, if (value.remaining > 0) value.varLong() else 0)
            true
        }
        found
    }

    fun forgive(owed: List<OwedItem>) {
        dbLock.read {
            if (closed || owed.isEmpty()) return
            writeSynced { batch -> for (item in owed) batch.delete(confiscationsCf, item.key) }
        }
    }

    // A shulker's loot table copies a handful of components onto the dropped item and the owner mark is
    // not among them, so a box loses its name every time it is broken. This remembers the name for as
    // long as the box stands, and hands it back at the break so the chain of custody survives the cycle.
    fun ownerAt(world: UUID, x: Int, y: Int, z: Int): UUID? =
        note(OWNERS, WorldBlock(world, x, y, z))?.let { ByteReader(it).uuid() }

    fun setOwnerAt(world: UUID, x: Int, y: Int, z: Int, owner: UUID) =
        putNote(OWNERS, WorldBlock(world, x, y, z), ByteWriter(16).uuid(owner).toByteArray())

    fun clearOwnerAt(world: UUID, x: Int, y: Int, z: Int) = putNote(OWNERS, WorldBlock(world, x, y, z), null)

    override fun formAt(at: WorldBlock): ByteArray? = note(FORMS, at)

    override fun setFormAt(at: WorldBlock, form: ByteArray) = putNote(FORMS, at, form)

    override fun clearFormAt(at: WorldBlock) = putNote(FORMS, at, null)

    override fun takeFormsAt(positions: List<WorldBlock>): Map<WorldBlock, ByteArray> =
        formsAt(positions).also { clearFormsAt(positions) }

    fun formsAt(positions: List<WorldBlock>): Map<WorldBlock, ByteArray> = dbLock.read {
        if (closed || positions.isEmpty()) return emptyMap()
        val forms = HashMap<WorldBlock, ByteArray>(positions.size)
        val unsettled = positions.filter { at ->
            val pending = pendingNotes[NoteKey(FORMS, at)] ?: return@filter true
            pending.value?.let { forms[at] = it }
            false
        }
        if (unsettled.isEmpty()) return forms
        val keys = unsettled.map(::blockKey)
        val values = db.multiGetAsList(List(keys.size) { placedFormsCf }, keys)
        unsettled.forEachIndexed { i, at -> values[i]?.let { forms[at] = it } }
        forms
    }

    fun clearFormsAt(positions: List<WorldBlock>) {
        for (at in positions) putNote(FORMS, at, null)
    }

    private fun note(family: Int, at: WorldBlock): ByteArray? {
        pendingNotes[NoteKey(family, at)]?.let { return it.value }
        return dbLock.read {
            if (closed) return null
            db.get(familyOf(family), blockKey(at))
        }
    }

    private fun putNote(family: Int, at: WorldBlock, value: ByteArray?) {
        if (closed) return
        val write = NoteWrite(NoteKey(family, at), value)
        pendingNotes[write.key] = write
        // A stopped writer drains nothing, and the overlay above is all a read still gets.
        if (progress.failure != null) return
        progress.submitted.incrementAndGet()
        noteQueue.add(write)
    }

    private fun familyOf(family: Int) = if (family == FORMS) placedFormsCf else nestedOwnersCf

    // On the writer thread. A note set again while this one waited stays on the overlay: only the
    // write that is still the latest for its position comes off it.
    private fun writeNotes(notes: List<NoteWrite>) {
        dbLock.read {
            if (closed) return
            WriteBatch().use { batch ->
                for (note in notes) {
                    val key = blockKey(note.key.at)
                    val value = note.value
                    if (value == null) batch.delete(familyOf(note.key.family), key) else batch.put(familyOf(note.key.family), key, value)
                }
                db.write(writeOptions, batch)
            }
            for (note in notes) pendingNotes.remove(note.key, note)
        }
    }

    private fun blockKey(at: WorldBlock): ByteArray =
        ByteWriter(16 + Zcode.SIZE).uuid(at.world).bytes(Zcode.encode(at.x, at.y, at.z)).toByteArray()

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
        db.closeWithHandles(cfHandles)
        closeOptions()
    }

    private fun closeOptions() {
        writeOptions.close()
        syncWriteOptions.close()
        entriesOptions.close()
        pointReadOptions.close()
        unfilteredOptions.close()
        // The table configs hold these, so they may only go once nothing can reach them.
        bloom.close()
        dbOptions.close()
        stopLending()
    }

    override fun loadAll(): List<RegistryRow> = dbLock.read {
        val rows = ArrayList<RegistryRow>()
        if (closed) return rows
        // Loaded in part, the registry would mint fresh numbers over names it still holds on disk, so a
        // walk that meets an error throws rather than ending early.
        db.forEachRow(registryCf) { key, value ->
            val ns = RegistryNamespace.byId(key[0].toInt() and 0xFF)
            if (ns != null) rows += RegistryRow(ns, key.copyOfRange(1, key.size), ByteReader(value).varInt())
        }
        rows
    }

    override fun nextId(ns: RegistryNamespace): Int {
        val id = registryCounters.getValue(ns)
        registryCounters[ns] = id + 1
        return id.toInt()
    }

    // Never into the writer's batch, even on the writer thread: a player first seen by the writer is
    // named by a block row the moment a block writer finds the number, long before that batch is written,
    // and a transaction rolled out of it would take the row along while the number stays handed out.
    // Called under the registry's lock, so each counter written is the highest yet.
    override fun persist(rows: List<RegistryRow>) = writeSynced { batch ->
        for (row in rows) {
            val key = ByteWriter(1 + row.keyBytes.size).byte(row.ns.id).bytes(row.keyBytes).toByteArray()
            batch.put(registryCf, key, ByteWriter(5).varInt(row.id).toByteArray())
        }
        for (ns in rows.mapTo(HashSet()) { it.ns }) {
            batch.put(metaCf, registryCounterKey(ns), longBytes(registryCounters.getValue(ns)))
        }
    }

    private fun runWriter() = progress.run(
        db,
        finished = { !running && noteQueue.isEmpty() },
        beforeEach = {
            val notes = ArrayList<NoteWrite>()
            noteQueue.drainTo(notes)
            if (notes.isNotEmpty()) {
                writeNotes(notes)
                progress.written.addAndGet(notes.size.toLong())
            }
        },
    ) { writing.withLock { writeAll(it) } }

    // A transaction that fails to encode is rolled back out of the batch and logged, so one bad row can't
    // silence the writer; a failed write still stops it.
    private fun writeAll(transactions: List<List<Transfer>>) {
        WriteBatch().use { batch ->
            pendingBatch = batch
            try {
                for (transaction in transactions.flatMap(::fitting)) {
                    batch.setSavePoint()
                    forms.recording()
                    payloads.recording()
                    try {
                        writeTransaction(batch, transaction)
                    } catch (failure: Exception) {
                        batch.rollbackToSavePoint()
                        // The forms this transaction named left with it; still interned, their numbers would
                        // go to later postings with no form row behind them.
                        forms.forgetRecorded()
                        payloads.forgetRecorded()
                        LOGGER.log(Level.SEVERE, "a movement could not be written and was dropped", failure)
                    } finally {
                        forms.stopRecording()
                        payloads.stopRecording()
                    }
                }
            } finally {
                pendingBatch = null
            }
            db.write(writeOptions, batch)
        }
    }

    // The posting ordinal is one byte, and a chunk of a rollback or a /fill over many containers can hold
    // more postings than that. Written whole it would fail and lose every row; cut at a movement's
    // boundary, each posting still finds its other half in its own transaction, and only the walk across
    // Void from one piece to the next is lost.
    private fun fitting(transaction: List<Transfer>): List<List<Transfer>> {
        if (transaction.size * 2 <= EntryCodec.MAX_ORDINAL + 1) return listOf(transaction)
        val pieces = ArrayList<List<Transfer>>()
        var piece = ArrayList<Transfer>()
        var postings = 0
        for (transfer in transaction) {
            val count = listOf(transfer.from, transfer.to).count { it.addressable }
            if (postings + count > EntryCodec.MAX_ORDINAL + 1) {
                pieces += piece
                piece = ArrayList()
                postings = 0
            }
            piece += transfer
            postings += count
        }
        pieces += piece
        return pieces
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
        // In the batch that gives them back, so a crash cannot leave the items returned and the
        // postings still waiting to be returned a second time.
        for (transfer in transaction) {
            for (ref in transfer.reverts) batch.put(compensatedCf, refKey(ref), longBytes(txId))
        }
    }

    private fun refKey(ref: PostingRef): ByteArray = ByteWriter(9).longBE(ref.txId).byte(ref.ordinal).toByteArray()

    /**
     * Which of these postings a rollback has already given back, each with the transaction that gave
     * it back. A giving back can be given back in turn — a rollback undone by rolling back its own rows
     * — and then the posting is owed again, so the chain is followed to its end: an odd number of links
     * is given back, an even one is not. A compensating transaction counts as undone when any of its
     * postings was given back.
     */
    fun compensated(refs: Collection<PostingRef>): Map<PostingRef, Long> = dbLock.read {
        if (closed || refs.isEmpty()) return emptyMap()
        val asked = refs.toList()
        val by = db.multiGetAsList(List(asked.size) { compensatedCf }, asked.map(::refKey))
        val given = HashMap<PostingRef, Long>()
        db.newIterator(compensatedCf).use { iter ->
            asked.forEachIndexed { index, ref ->
                val first = by[index]?.let { ByteReader(it).longBE() } ?: return@forEachIndexed
                var giver = first
                var givenBack = true
                var ended = false
                for (step in 0 until MAX_UNDO_DEPTH) {
                    val prefix = longBytes(giver)
                    iter.seek(prefix)
                    // A chain cut short by an error flips the parity of everything after it.
                    if (!iter.isValid) iter.status()
                    val key = if (iter.isValid) iter.key() else null
                    if (key == null || !key.startsWith(prefix)) {
                        ended = true
                        break
                    }
                    giver = ByteReader(iter.value()).longBE()
                    givenBack = !givenBack
                }
                if (givenBack || !ended) given[ref] = first
            }
        }
        given
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

    private inline fun forEachUnder(
        prefix: ByteArray,
        reverse: Boolean,
        from: ByteArray? = null,
        action: (ByteArray, ByteArray) -> Boolean,
    ) = db.forEachUnder(entriesCf, prefix, reverse, from, action)

    private fun scan(
        prefix: ByteArray,
        fromTs: Long,
        toTs: Long,
        reverse: Boolean,
        limit: Int,
    ): EntryPage {
        val found = ArrayList<LedgerEntry>()
        if (limit <= 0) return EntryPage(found, false)
        var unreadable = 0
        // One row past the limit is what separates a scan that ended from one that was cut off, and
        // it costs a single decode. Guessing from the size instead is wrong both ways: a range that
        // ends exactly on the limit reads as cut off, and rows dropped by the time window read as
        // room to spare.
        forEachUnder(prefix, reverse) { key, value ->
            // A chunk prefix stops three position bytes short of the timestamp, so no byte range
            // under it can express a time window; the window is applied to the decoded entry instead.
            // A row that does not decode cannot say when it was written, so it counts whatever the window.
            val entry = EntryCodec.decodeOrNull(key, value, registries)
            if (entry == null) unreadable++ else if (entry.timestamp in fromTs..toTs) found += entry
            found.size <= limit
        }
        return EntryPage(found.take(limit), found.size <= limit, unreadable)
    }

    // An interned row must land in the same batch as the counter that named it, or a crash in between
    // hands the same number out twice. The writer's forms go into its own batch, with the rows that name
    // them; anything else is written and fsynced on its own before its number is handed out.
    private fun stage(write: (WriteBatch) -> Unit) {
        if (Thread.currentThread() === writerThread) pendingBatch?.let { return write(it) }
        writeSynced(write)
    }

    private fun writeSynced(fill: (WriteBatch) -> Unit) = dbLock.read {
        check(!closed) { "the ledger is closed" }
        WriteBatch().use { batch ->
            fill(batch)
            db.write(syncWriteOptions, batch)
        }
    }

    private fun registryCounterKey(ns: RegistryNamespace): ByteArray = "reg:${ns.id}".toByteArray()

    // Interning, lossless and whole: what goes in comes back out byte for byte, because an edit to a
    // sign has to be reproducible from what was kept of it.
    inner class InternTable(private val cf: ColumnFamilyHandle, private val counterKey: ByteArray) {
        private val idByValue = ConcurrentHashMap<FormKey, Long>()
        private val valueById = ConcurrentHashMap<Long, ByteArray>()
        private var nextId: Long = 0

        fun load() {
            nextId = db.readCounter(metaCf, counterKey)
            db.forEachRow(cf) { key, value -> remember(ByteReader(key).longBE(), value) }
        }

        // One lock for the same mint race as `Registries`: the block plane interns from a writer thread of its own.
        // Handed out only once staged, so a failed write never leaves a cached number nothing was written under.
        fun idOf(value: ByteArray): Long {
            val key = FormKey(value)
            idByValue[key]?.let { return it }
            synchronized(this) {
                idByValue[key]?.let { return it }
                val id = nextId++
                stage {
                    it.put(cf, longBytes(id), value)
                    it.put(metaCf, counterKey, longBytes(nextId))
                }
                remember(id, value)
                minted.get()?.add(id)
                return id
            }
        }

        fun valueOf(id: Long): ByteArray? = valueById[id]

        // The ids this thread mints from `recording()` on, so the writer can forget the ones whose rows a
        // rolled-back transaction took with it. Other threads stage into batches of their own.
        private val minted = ThreadLocal<MutableList<Long>?>()

        fun recording() = minted.set(ArrayList())

        fun stopRecording() = minted.remove()

        @Synchronized
        fun forgetRecorded() {
            for (id in minted.get().orEmpty()) valueById.remove(id)?.let { idByValue.remove(FormKey(it), id) }
            minted.get()?.clear()
        }

        // Unlike idOf, never mints: asking about a value must not intern it.
        fun lookup(value: ByteArray): Long? = idByValue[FormKey(value)]

        private fun remember(id: Long, value: ByteArray) {
            idByValue[FormKey(value)] = id
            valueById[id] = value
        }
    }
}
