package io.pfaumc.pfauprotect

import org.rocksdb.BlockBasedTableConfig
import org.rocksdb.BloomFilter
import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
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
import kotlin.concurrent.read
import kotlin.concurrent.write

// Both sides are nullable so that a capture which only learned one of them says so and is refused,
// rather than filing air for the side it never saw.
data class BlockChange(
    val x: Int,
    val y: Int,
    val z: Int,
    val before: String?,
    val after: String?,
    val cause: Cause,
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Confidence = Confidence.FACT,
    val alongside: Boolean = false,
    val actor: UUID? = null,
    val payloadBefore: ByteArray? = null,
    val payloadAfter: ByteArray? = null,
)

// What stands at a position, with `row` null where the position has no history at all. `torn` marks
// an answer nothing may be concluded from: the newest row of the position did not decode, so the row
// behind it names a state that has since been replaced, and a closed base answers the same way rather
// than as a position nothing ever happened to.
data class BlockStanding(val row: BlockRow?, val torn: Boolean)

// Raised by a change to the key layout or to the set of column families. The record version in the
// value covers neither: keys carry a version this build reads, so without the bump an older database
// opens and every key is parsed as something it never was.
private const val BLOCK_SCHEMA_VERSION = 1L

private val ROWS_CF = "rows".toByteArray()
private val BLOCK_META_CF = "meta".toByteArray()

private val META_SCHEMA_KEY = "schema".toByteArray()
private val META_EVENT_ID = "event_id".toByteArray()
private val META_LAST_TS = "last_ts".toByteArray()

// Padded rather than the upper bound: seeking backwards has to start from a key that still belongs
// to this prefix, or the prefix filter is asked about the wrong one and finds nothing.
private fun lastUnder(prefix: ByteArray): ByteArray =
    ByteWriter(prefix.size + KEY_TAIL_PAD)
        .bytes(prefix)
        .bytes(ByteArray(KEY_TAIL_PAD) { 0xFF.toByte() })
        .toByteArray()

// One world's block history. The world number is not in the key because the database is the world,
// which is what makes deleting a world a matter of deleting a directory: registry numbers are never
// reused, so rows of a world that is gone could otherwise only be got rid of by a range delete.
//
// The state numbers, the player numbers and the interned payloads all live in `shared`, and there is
// no write-ahead log spanning the two databases. The guarantee is the order: what a row names is
// made durable before the row that names it. An orphaned payload is garbage nobody reads; a row
// whose payload never landed is lost data.
class BlockLog(dir: Path, private val shared: RocksItemLog) : AutoCloseable {
    private val dbOptions = DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true)
    private val blockCache = LRUCache(BLOCK_CACHE_BYTES)
    private val bloom = BloomFilter(BLOOM_BITS_PER_KEY)
    private val filteredTable = BlockBasedTableConfig().setBlockCache(blockCache).setFilterPolicy(bloom)

    private val rowsOptions = compressed()
        .setTableFormatConfig(filteredTable)
        // A chunk is the first CHUNK_PREFIX_SIZE bytes of every row key, so this is what lets a walk
        // of one chunk skip the files and memtables that hold none of it, and a walk of one position
        // is a longer prefix under the same extractor.
        .useFixedLengthPrefixExtractor(Zcode.CHUNK_PREFIX_SIZE)
        .setMemtablePrefixBloomSizeRatio(0.1)

    // Two counters and a schema stamp, read by key and never walked.
    private val metaOptions = compressed()
        .setTableFormatConfig(filteredTable)
        .setWriteBufferSize(COLD_WRITE_BUFFER_BYTES)

    private val writeOptions = WriteOptions()
    private val cfHandles = ArrayList<ColumnFamilyHandle>()
    private val db: RocksDB
    private val rowsCf: ColumnFamilyHandle
    private val metaCf: ColumnFamilyHandle

    private val queue = LinkedBlockingQueue<List<BlockChange>>()
    private val submitted = AtomicLong()
    private val written = AtomicLong()

    @Volatile
    private var running = true

    @Volatile
    private var writerFailure: Throwable? = null

    @Volatile
    private var closed = false

    private val dbLock = ReentrantReadWriteLock()

    private var nextEventId = 0L
    private var lastTs = 0L

    init {
        RocksDB.loadLibrary()
        Files.createDirectories(dir)
        val path = dir.toAbsolutePath().toString()
        // Opening writes every missing column family into the manifest before anything can look at
        // the schema, and a build that does not know those families can no longer open the database
        // at all. A database refused for its schema has to be left exactly as it was found.
        val stored = try {
            storedSchema(path)?.also {
                require(it == BLOCK_SCHEMA_VERSION) {
                    "block database schema $it cannot be read by this build (schema $BLOCK_SCHEMA_VERSION)"
                }
            }
        } catch (failure: Throwable) {
            runCatching { closeOptions() }
            throw failure
        }
        val descriptors = listOf(
            RocksDB.DEFAULT_COLUMN_FAMILY to metaOptions,
            ROWS_CF to rowsOptions,
            BLOCK_META_CF to metaOptions,
        ).map { (name, options) -> ColumnFamilyDescriptor(name, options) }
        // A stale lock file or a truncated manifest fails the open, and the options, the cache and the
        // filter behind it answer to nothing afterwards: the bindings free no native memory on their
        // own, and every world load that retries would strand another set of them.
        db = try {
            RocksDB.open(dbOptions, path, descriptors, cfHandles)
        } catch (failure: Throwable) {
            runCatching { closeOptions() }
            throw failure
        }
        rowsCf = cfHandles[1]
        metaCf = cfHandles[2]

        // Nothing outside reaches a constructor that threw, so a failure here would hold the file
        // lock and the native memory until the process ends and no later open could succeed.
        try {
            if (stored == null) db.put(metaCf, META_SCHEMA_KEY, longBytes(BLOCK_SCHEMA_VERSION))
            nextEventId = readCounter(META_EVENT_ID)
            lastTs = readCounter(META_LAST_TS)
        } catch (failure: Throwable) {
            runCatching { closeNatives() }
            throw failure
        }
    }

    private val writerThread =
        Thread(::runWriter, "pfauprotect-block-writer-${dir.fileName}").apply { isDaemon = true }

    init {
        writerThread.start()
    }

    /**
     * Every change in the list shares one event id: one explosion, one piston firing, one tree
     * growing is one event across every position it touched, so all of them are findable together.
     * A change missing either side is refused and dropped on its own; the rest of the list is
     * written. False means the log took nothing and the caller still holds the only copy.
     */
    fun submit(changes: List<BlockChange>): Boolean {
        // Not `closed`: that is only set once the writer has been joined, and from the moment the
        // writer stops there is nobody left to take the queue. A change accepted in between would be
        // counted as submitted, sit in the queue and never be written, and `drain` would not wait for
        // it either. Refusing it is the difference between a caller that knows and history that lies.
        if (!running || closed || writerFailure != null || changes.isEmpty()) return false
        submitted.incrementAndGet()
        queue.add(changes)
        return true
    }

    fun drain() {
        if (closed) return
        val target = submitted.get()
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS
        while (written.get() < target) {
            failIfWriterStopped()
            check(System.nanoTime() < deadline) {
                "block writer did not catch up in ${DRAIN_TIMEOUT_NANOS / 1_000_000} ms, " +
                    "${target - written.get()} submissions are unwritten"
            }
            Thread.sleep(1)
        }
        failIfWriterStopped()
    }

    fun at(
        x: Int,
        y: Int,
        z: Int,
        fromTs: Long = 0,
        toTs: Long = Long.MAX_VALUE,
        limit: Int = 100,
        reverse: Boolean = false,
    ): List<BlockRow> = scan(BlockCodec.positionPrefix(x, y, z), fromTs, toTs, limit, reverse)

    // Inside a chunk the key sorts by position and only then by time, so a walk answers in position
    // order: `reverse` would mean the far corner of the chunk rather than the newest change, and a
    // limit would cut the answer off at a position rather than at a time. Every row under the prefix
    // has to be in hand and back in time order before either can be applied.
    fun inChunk(
        chunkX: Int,
        chunkZ: Int,
        fromTs: Long = 0,
        toTs: Long = Long.MAX_VALUE,
        limit: Int = 100,
        reverse: Boolean = false,
        // Applied before the limit for the same reason the limit waits for the whole chunk.
        within: (BlockRow) -> Boolean = { true },
    ): List<BlockRow> = dbLock.read {
        if (closed || limit <= 0) return emptyList()
        val found = ArrayList<BlockRow>()
        forEachUnder(Zcode.chunkPrefix(chunkX, chunkZ), reverse = false) { key, value ->
            val row = BlockCodec.decodeOrNull(key, value, shared.registries)
            if (row != null && row.timestamp in fromTs..toTs && within(row)) found += row
            true
        }
        val byTime = compareBy<BlockRow>({ it.timestamp }, { it.eventId }, { it.ordinal })
        found.sortedWith(if (reverse) byTime.reversed() else byTime).take(limit)
    }

    /**
     * The last row of a position, which is what stands there now: the log is a journal of states, so
     * nothing has to be replayed to get at it.
     */
    fun standingAt(x: Int, y: Int, z: Int): BlockStanding = dbLock.read {
        if (closed) return BlockStanding(null, torn = true)
        var found = false
        var row: BlockRow? = null
        forEachUnder(BlockCodec.positionPrefix(x, y, z), reverse = true) { key, value ->
            found = true
            row = BlockCodec.decodeOrNull(key, value, shared.registries)
            false
        }
        BlockStanding(row, torn = found && row == null)
    }

    // Reads run on any thread, so the native handles may only be freed once every reader has left,
    // and a write arriving from a region thread during shutdown would dereference a freed handle and
    // take the JVM down. Hence: writers first, handles after.
    override fun close() {
        running = false
        writerThread.join()
        val unwritten = queue.size
        if (unwritten > 0) {
            LOGGER.log(Level.SEVERE, "block writer stopped with $unwritten submissions unwritten")
        }
        dbLock.write {
            if (closed) return
            closed = true
            runCatching { db.flushWal(true) }
            closeNatives()
        }
    }

    private fun scan(
        prefix: ByteArray,
        fromTs: Long,
        toTs: Long,
        limit: Int,
        reverse: Boolean,
    ): List<BlockRow> = dbLock.read {
        val found = ArrayList<BlockRow>()
        if (closed || limit <= 0) return found
        forEachUnder(prefix, reverse) { key, value ->
            // The timestamp follows a position prefix directly, so the walk is already in time order
            // and the window is nothing but the rows it drops.
            val row = BlockCodec.decodeOrNull(key, value, shared.registries)
            if (row != null && row.timestamp in fromTs..toTs) found += row
            found.size < limit
        }
        found
    }

    // The bounds keep the walk inside the prefix, which under a prefix extractor is the only way to
    // do it: an iterator in prefix mode may not be carried past the prefix it was seeked into, and a
    // seek to the key just after the prefix lands in the next prefix, where the filter answers with
    // nothing at all. The slices have to outlive the iterator. `action` returns false to stop.
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
                // so no seek key can stand for all of them. A prefix exactly as long as the extractor
                // is no better off: its upper bound is the first key of the next extractor prefix, and
                // an upper bound outside the prefix seeked into leaves what an iterator in prefix mode
                // returns undefined. Either way the walk has to leave prefix mode. A longer prefix
                // keeps the extractor bytes it shares with its own bound and stays in it.
                .setTotalOrderSeek(prefix.size <= Zcode.CHUNK_PREFIX_SIZE)
                .use { options ->
                    db.newIterator(rowsCf, options).use { iter ->
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

    private fun runWriter() {
        var lastFlush = System.nanoTime()
        try {
            while (true) {
                val first = queue.poll(WRITER_POLL_MILLIS, TimeUnit.MILLISECONDS)
                if (first == null && !running) return
                if (first != null) {
                    val batched = ArrayList<List<BlockChange>>(MAX_BATCH)
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
                "block writer stopped, ${queue.size} submissions are unwritten and further ones are dropped",
                failure,
            )
        }
    }

    // One change that cannot be written is a bug in the capture; every change after it is not.
    // Letting the first one stop the writer turns a single bad row into a history that silently
    // records nothing for the rest of the session.
    private fun writeAll(submissions: List<List<BlockChange>>) {
        WriteBatch().use { batch ->
            // Nothing in the batch is readable until it is written, so a position this batch already
            // holds a row for can only be clamped against what is remembered here.
            val batchTs = HashMap<Triple<Int, Int, Int>, Long>()
            for (changes in submissions) {
                val eventId = nextEventId++
                batch.put(metaCf, META_EVENT_ID, longBytes(nextEventId))
                // Counted per position rather than across the whole event, because the ordinal is
                // one byte and an event is not small: an explosion changes thousands of positions,
                // but none of them more than a handful of times.
                val seen = HashMap<Triple<Int, Int, Int>, Int>()
                for (change in changes) {
                    batch.setSavePoint()
                    try {
                        val ordinal = seen.merge(Triple(change.x, change.y, change.z), 0) { old, _ -> old + 1 }!!
                        writeChange(batch, change, eventId, ordinal, batchTs)
                    } catch (failure: Exception) {
                        batch.rollbackToSavePoint()
                        LOGGER.log(Level.SEVERE, "a block change could not be written and was dropped", failure)
                    }
                }
            }
            batch.put(metaCf, META_LAST_TS, longBytes(lastTs))
            db.write(writeOptions, batch)
        }
    }

    private fun writeChange(
        batch: WriteBatch,
        change: BlockChange,
        eventId: Long,
        ordinal: Int,
        batchTs: MutableMap<Triple<Int, Int, Int>, Long>,
    ) {
        val at = "${change.x} ${change.y} ${change.z}"
        val before = requireNotNull(change.before) { "the block at $at was changed from nothing known" }
        val after = requireNotNull(change.after) { "the block at $at was changed into nothing known" }
        val stateBefore = shared.registries.idForKey(RegistryNamespace.BLOCK_STATE, before)
        val stateAfter = shared.registries.idForKey(RegistryNamespace.BLOCK_STATE, after)
        val payloadBefore = change.payloadBefore?.let { shared.payloads.idOf(it) }
        val payloadAfter = change.payloadAfter?.let { shared.payloads.idOf(it) }
        // Key order is read as time order, so the newest row of a position is what stands there, and
        // a row filed behind one already at that position would silently take its place. Only that
        // position is constrained: a change filed late, which a capture that has to see the outcome
        // before it can file it always is, keeps its own time when what it fell behind is somewhere
        // else. The world-wide mark spares the common case the lookup, a time at or past everything
        // the world holds being past this position too, and it has to survive a restart or a clock
        // stepped backwards by time synchronisation takes that fast path and reorders a position
        // after every reopen. The cost: a change behind its own position keeps that position's time.
        val here = Triple(change.x, change.y, change.z)
        val ts = if (change.timestamp >= lastTs) {
            change.timestamp
        } else {
            maxOf(change.timestamp, batchTs[here] ?: newestTsAt(change.x, change.y, change.z))
        }
        lastTs = maxOf(lastTs, ts)
        val row = BlockRow(
            x = change.x,
            y = change.y,
            z = change.z,
            timestamp = ts,
            eventId = eventId,
            ordinal = ordinal,
            cause = change.cause,
            stateBefore = stateBefore,
            stateAfter = stateAfter,
            confidence = change.confidence,
            alongside = change.alongside,
            actor = change.actor,
            payloadBefore = payloadBefore,
            payloadAfter = payloadAfter,
        )
        batch.put(
            rowsCf,
            BlockCodec.key(change.x, change.y, change.z, ts, eventId, ordinal),
            BlockCodec.value(row, shared.registries),
        )
        batchTs[here] = ts
    }

    // The time is in the key, so a position whose newest row does not decode still bounds what may
    // be filed under it, and no row has to be decoded to ask.
    private fun newestTsAt(x: Int, y: Int, z: Int): Long {
        var ts = 0L
        forEachUnder(BlockCodec.positionPrefix(x, y, z), reverse = true) { key, _ ->
            if (key.size < BlockCodec.KEY_SIZE) return@forEachUnder true
            val reader = ByteReader(key)
            reader.bytes(Zcode.SIZE)
            ts = reader.longBE()
            false
        }
        return ts
    }

    // Read-only and with exactly the families already on disk, so a database this build refuses is
    // handed back untouched. Null means there is nothing to refuse: no database, or one from before
    // the schema was stamped.
    private fun storedSchema(path: String): Long? = Options().use { probe ->
        val existing = RocksDB.listColumnFamilies(probe, path)
        val metaIndex = existing.indexOfFirst { it.contentEquals(BLOCK_META_CF) }
        if (metaIndex < 0) return null
        val handles = ArrayList<ColumnFamilyHandle>()
        ColumnFamilyOptions().use { cfOptions ->
            DBOptions().use { options ->
                try {
                    RocksDB.openReadOnly(options, path, existing.map { ColumnFamilyDescriptor(it, cfOptions) }, handles)
                        .use { probed -> probed.get(handles[metaIndex], META_SCHEMA_KEY)?.let { ByteReader(it).longBE() } }
                } finally {
                    handles.forEach { it.close() }
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
        rowsOptions.close()
        metaOptions.close()
        // The table config holds these, so they may only go once nothing can reach them.
        bloom.close()
        blockCache.close()
        dbOptions.close()
    }

    private fun readCounter(key: ByteArray): Long = db.get(metaCf, key)?.let { ByteReader(it).longBE() } ?: 0L

    private fun failIfWriterStopped() {
        writerFailure?.let {
            throw IllegalStateException("block writer stopped and ${queue.size} submissions are unwritten", it)
        }
    }
}

// One database per world, each in a directory named after it.
class BlockLogs(private val root: Path, private val shared: RocksItemLog) : AutoCloseable {
    private val logs = ConcurrentHashMap<UUID, BlockLog>()

    fun open(world: UUID): BlockLog = logs.computeIfAbsent(world) { BlockLog(dirOf(it), shared) }

    fun get(world: UUID): BlockLog? = logs[world]

    val size: Int get() = logs.size

    fun close(world: UUID) {
        logs.remove(world)?.close()
    }

    // One world whose close throws still holds its own lock file, and stopping there would leave every
    // world after it holding one too, so the next start finds databases it cannot open.
    fun closeAll() {
        for (world in logs.keys.toList()) {
            runCatching { close(world) }.onFailure {
                LOGGER.log(Level.SEVERE, "the block log of world $world did not close", it)
            }
        }
    }

    // The handles go before the files they hold open.
    fun delete(world: UUID) {
        close(world)
        dirOf(world).toFile().deleteRecursively()
    }

    override fun close() = closeAll()

    private fun dirOf(world: UUID): Path = root.resolve(world.toString())
}
