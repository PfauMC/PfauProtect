package io.pfaumc.pfauprotect.storage
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.EntityKind
import org.rocksdb.BloomFilter
import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.DBOptions
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

// What a world's base takes in one submission: changes to blocks, and to the entities standing among them.
sealed interface WorldChange {
    val x: Int
    val y: Int
    val z: Int
    val actor: UUID?
}

/**
 * What became of one entity, filed at the block position it stood in. `before` and `after` are its
 * whole NBT, kept byte for byte in the shared payload table; `drops` the item entities that fell out of it.
 */
data class EntityChange(
    override val x: Int,
    override val y: Int,
    override val z: Int,
    val kind: EntityKind,
    val cause: Cause,
    val type: String,
    val uuid: UUID,
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Confidence = Confidence.FACT,
    override val actor: UUID? = null,
    val before: ByteArray? = null,
    val after: ByteArray? = null,
    val drops: List<UUID> = emptyList(),
) : WorldChange

// Both sides are nullable so that a capture which only learned one of them says so and is refused,
// rather than filing air for the side it never saw.
data class BlockChange(
    override val x: Int,
    override val y: Int,
    override val z: Int,
    val before: String?,
    val after: String?,
    val cause: Cause,
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Confidence = Confidence.FACT,
    val alongside: Boolean = false,
    override val actor: UUID? = null,
    val payloadBefore: ByteArray? = null,
    val payloadAfter: ByteArray? = null,
) : WorldChange

// What stands at a position, with `row` null where the position has no history at all. `torn` marks
// an answer nothing may be concluded from: the newest row of the position did not decode, so the row
// behind it names a state that has since been replaced, and a closed base answers the same way rather
// than as a position nothing ever happened to.
data class BlockStanding(val row: BlockRow?, val torn: Boolean)

// What a rollback reads: every row of the window, how many rows the walk could not read, and whether
// it ran out of budget first. A rollback that went ahead over less than all of it would put back part
// of a place and report it done.
data class Window<T>(val rows: List<T>, val unreadable: Int, val complete: Boolean, val walked: Int)

typealias BlockWindow = Window<BlockRow>

// The positions an actor's rows stand at in a window, and whether the walk got to its end.
data class ActorTouches(val positions: Set<List<Int>>, val complete: Boolean)

// Raised by a change to the key layout or to the set of column families. The record version in the
// value covers neither: keys carry a version this build reads, so without the bump an older database
// opens and every key is parsed as something it never was.
//
// Version 2 adds the index of rows by actor. A version 1 base is the same base with the index still to
// build, and it is built from its rows the first time it opens. Version 3 adds the entity plane, which an
// older base simply has none of yet.
private const val BLOCK_SCHEMA_VERSION = 3L
private const val INDEXED_FROM = 1L
private const val WITHOUT_ENTITIES = 2L

// How many index rows a base being indexed for the first time writes per batch.
private const val INDEX_BATCH = 10_000

private const val ACTOR_KEY_SIZE = 4 + BlockCodec.KEY_SIZE
private val NOTHING = ByteArray(0)

private val ROWS_CF = "rows".toByteArray()
private val BLOCK_META_CF = "meta".toByteArray()
private val BY_ACTOR_CF = "by_actor".toByteArray()
private val ENTITIES_CF = "entities".toByteArray()

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
class BlockLog(
    dir: Path,
    private val shared: RocksItemLog,
    // Shown every list of block changes the log accepts, on the thread that submitted it.
    private val watch: (List<BlockChange>) -> Unit = {},
) : AutoCloseable {
    // The ledger's cache and memtable budget, so a world loaded is not another bound of its own.
    private val dbOptions = DBOptions()
        .setCreateIfMissing(true)
        .setCreateMissingColumnFamilies(true)
        .setWriteBufferManager(shared.writeBuffers)
    private val bloom = BloomFilter(BLOOM_BITS_PER_KEY)
    private val filteredTable = tableIn(shared.blockCache, bloom)

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

    // Walked by actor and time, never asked for one key.
    private val byActorOptions = compressed().setTableFormatConfig(tableIn(shared.blockCache))

    private val writeOptions = WriteOptions()
    private val cfHandles = ArrayList<ColumnFamilyHandle>()
    private val db: RocksDB
    private val rowsCf: ColumnFamilyHandle
    private val metaCf: ColumnFamilyHandle

    // Every row that names an actor, again, under the actor: the player number, the time, the event, the
    // ordinal and the position as the key, nothing as the value. The ordinal is counted per position, so
    // the position has to be in the key or two positions of one event would share it. A block row has no
    // ends to file it under, so without this "everything this player did" is a walk of the whole world.
    private val byActorCf: ColumnFamilyHandle

    // What became of the entities of this world, keyed as the block rows are.
    private val entitiesCf: ColumnFamilyHandle

    private val queue = LinkedBlockingQueue<List<WorldChange>>()
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
                require(it == BLOCK_SCHEMA_VERSION || it == INDEXED_FROM || it == WITHOUT_ENTITIES) {
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
            BY_ACTOR_CF to byActorOptions,
            ENTITIES_CF to rowsOptions,
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
        byActorCf = cfHandles[3]
        entitiesCf = cfHandles[4]

        // Nothing outside reaches a constructor that threw, so a failure here would hold the file
        // lock and the native memory until the process ends and no later open could succeed.
        try {
            if (stored == INDEXED_FROM) indexActors()
            if (stored != BLOCK_SCHEMA_VERSION) db.put(metaCf, META_SCHEMA_KEY, longBytes(BLOCK_SCHEMA_VERSION))
            nextEventId = readCounter(META_EVENT_ID)
            lastTs = readCounter(META_LAST_TS)
        } catch (failure: Throwable) {
            runCatching { closeNatives() }
            throw failure
        }
    }

    // Once, on the first open of a base written before the index existed, and before the schema says it
    // has one: a crash halfway through leaves the old stamp, and the next open starts again, writing the
    // same keys over themselves.
    private fun indexActors() {
        ReadOptions().setTotalOrderSeek(true).use { options ->
            db.newIterator(rowsCf, options).use { iter ->
                var batch = WriteBatch()
                try {
                    iter.seekToFirst()
                    while (iter.isValid) {
                        val key = iter.key()
                        val actor = BlockCodec.actorNumberOf(iter.value())
                        if (actor != null && key.size == BlockCodec.KEY_SIZE) {
                            batch.put(byActorCf, actorKey(actor, key), NOTHING)
                        }
                        if (batch.count() >= INDEX_BATCH) {
                            db.write(writeOptions, batch)
                            batch.close()
                            batch = WriteBatch()
                        }
                        iter.next()
                    }
                    db.write(writeOptions, batch)
                } finally {
                    batch.close()
                }
            }
        }
    }

    /**
     * Where an actor's rows in the window stand: the positions, from the index, without reading a row.
     * `budget` bounds the index rows walked, and a walk that reaches it says so.
     */
    fun touchedBy(actor: UUID, fromTs: Long, toTs: Long, budget: Int): ActorTouches = dbLock.read {
        val number = shared.registries.lookupKey(RegistryNamespace.PLAYER, actor.toString())
        if (closed) return ActorTouches(emptySet(), false)
        if (number == null) return ActorTouches(emptySet(), true)
        val prefix = actorPrefix(number)
        val positions = HashSet<List<Int>>()
        var walked = 0
        val lower = Slice(ByteWriter(12).bytes(prefix).longBE(fromTs).toByteArray())
        val upper = Slice(afterPrefix(prefix))
        try {
            ReadOptions().setIterateLowerBound(lower).setIterateUpperBound(upper).use { options ->
                db.newIterator(byActorCf, options).use { iter ->
                    iter.seekToFirst()
                    while (iter.isValid) {
                        val ts = ByteReader(iter.key()).also { it.bytes(prefix.size) }.longBE()
                        if (ts > toTs) break
                        if (walked++ >= budget) return ActorTouches(positions, false)
                        positions += Zcode.decode(iter.key(), ACTOR_KEY_SIZE - Zcode.SIZE).toList()
                        iter.next()
                    }
                }
            }
        } finally {
            lower.close()
            upper.close()
        }
        ActorTouches(positions, true)
    }

    private fun actorPrefix(actor: Int): ByteArray =
        ByteWriter(4).byte(actor ushr 24).byte(actor ushr 16).byte(actor ushr 8).byte(actor).toByteArray()

    // The actor's number, then the row key turned round: time, event and ordinal, then the position.
    private fun actorKey(actor: Int, rowKey: ByteArray): ByteArray =
        ByteWriter(ACTOR_KEY_SIZE)
            .bytes(actorPrefix(actor))
            .bytes(rowKey.copyOfRange(Zcode.SIZE, BlockCodec.KEY_SIZE))
            .bytes(rowKey.copyOf(Zcode.SIZE))
            .toByteArray()

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
    fun submit(changes: List<WorldChange>): Boolean {
        // Not `closed`: that is only set once the writer has been joined, and from the moment the
        // writer stops there is nobody left to take the queue. A change accepted in between would be
        // counted as submitted, sit in the queue and never be written, and `drain` would not wait for
        // it either. Refusing it is the difference between a caller that knows and history that lies.
        if (!running || closed || writerFailure != null || changes.isEmpty()) return false
        submitted.incrementAndGet()
        queue.add(changes)
        val blocks = changes.filterIsInstance<BlockChange>()
        if (blocks.isNotEmpty()) watch(blocks)
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
     * Every row of a chunk inside the window and the box, in key order, with `budget` the most rows
     * the walk may step over in all. A row that cannot be read counts wherever it lies, since its own
     * bytes are what would have said whether it was in the window.
     */
    fun windowInChunk(
        chunkX: Int,
        chunkZ: Int,
        fromTs: Long,
        toTs: Long,
        budget: Int,
        within: (Int, Int, Int) -> Boolean,
    ): BlockWindow = window(rowsCf, Zcode.chunkPrefix(chunkX, chunkZ), fromTs, toTs, budget, within, ::blockRow)

    fun windowAt(x: Int, y: Int, z: Int, fromTs: Long, toTs: Long, budget: Int): BlockWindow =
        window(rowsCf, BlockCodec.positionPrefix(x, y, z), fromTs, toTs, budget, { _, _, _ -> true }, ::blockRow)

    /** The entity plane's rows of a chunk, read the way `windowInChunk` reads the block plane's. */
    fun entitiesInChunk(
        chunkX: Int,
        chunkZ: Int,
        fromTs: Long,
        toTs: Long,
        budget: Int,
        within: (Int, Int, Int) -> Boolean,
    ): Window<EntityRow> = window(entitiesCf, Zcode.chunkPrefix(chunkX, chunkZ), fromTs, toTs, budget, within, ::entityRow)

    fun entitiesAt(x: Int, y: Int, z: Int, fromTs: Long, toTs: Long, budget: Int): Window<EntityRow> =
        window(entitiesCf, BlockCodec.positionPrefix(x, y, z), fromTs, toTs, budget, { _, _, _ -> true }, ::entityRow)

    private fun blockRow(key: ByteArray, value: ByteArray): BlockRow? = BlockCodec.decodeOrNull(key, value, shared.registries)

    private fun entityRow(key: ByteArray, value: ByteArray): EntityRow? =
        EntityCodec.decodeOrNull(key, value, shared.registries) { shared.registries.keyOf(RegistryNamespace.ENTITY_TYPE, it) }

    private fun <T> window(
        cf: ColumnFamilyHandle,
        prefix: ByteArray,
        fromTs: Long,
        toTs: Long,
        budget: Int,
        within: (Int, Int, Int) -> Boolean,
        decode: (ByteArray, ByteArray) -> T?,
    ): Window<T> = dbLock.read {
        if (closed) return Window(emptyList(), 0, false, 0)
        val rows = ArrayList<T>()
        var unreadable = 0
        var walked = 0
        var complete = true
        forEachUnder(prefix, reverse = false, cf) { key, value ->
            if (walked >= budget) {
                complete = false
                return@forEachUnder false
            }
            walked++
            val row = decode(key, value)
            // The key alone says where and when, so a window and a box are asked of it, decoded or not.
            val at = if (key.size >= BlockCodec.KEY_SIZE) Zcode.decode(key) else null
            val ts = if (at != null) ByteReader(key).also { it.bytes(Zcode.SIZE) }.longBE() else null
            when {
                row == null -> unreadable++
                ts != null && ts in fromTs..toTs && within(at!![0], at[1], at[2]) -> rows += row
            }
            true
        }
        Window(rows, unreadable, complete, walked)
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
        cf: ColumnFamilyHandle = rowsCf,
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
                    db.newIterator(cf, options).use { iter ->
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
                    val batched = ArrayList<List<WorldChange>>(MAX_BATCH)
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
    private fun writeAll(submissions: List<List<WorldChange>>) {
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
                        when (change) {
                            is BlockChange -> writeChange(batch, change, eventId, ordinal, batchTs)
                            is EntityChange -> writeEntity(batch, change, eventId, ordinal)
                        }
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

    // An entity row is not a state of its position, so it needs none of the clamping a block row does:
    // two rows of one position order by their event, and the key keeps them apart by it.
    private fun writeEntity(batch: WriteBatch, change: EntityChange, eventId: Long, ordinal: Int) {
        val row = EntityRow(
            x = change.x, y = change.y, z = change.z,
            timestamp = change.timestamp, eventId = eventId, ordinal = ordinal,
            kind = change.kind, cause = change.cause, type = change.type, uuid = change.uuid,
            confidence = change.confidence, actor = change.actor,
            payloadBefore = change.before?.let { shared.payloads.idOf(it) },
            payloadAfter = change.after?.let { shared.payloads.idOf(it) },
            drops = change.drops,
        )
        val key = BlockCodec.key(change.x, change.y, change.z, change.timestamp, eventId, ordinal)
        val type = shared.registries.idForKey(RegistryNamespace.ENTITY_TYPE, change.type)
        batch.put(entitiesCf, key, EntityCodec.value(row, shared.registries, type))
        change.actor?.let { actor ->
            batch.put(byActorCf, actorKey(shared.registries.id(RegistryNamespace.PLAYER, actor), key), NOTHING)
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
        val key = BlockCodec.key(change.x, change.y, change.z, ts, eventId, ordinal)
        batch.put(rowsCf, key, BlockCodec.value(row, shared.registries))
        // In the same batch as the row, so the index never names a row that is not there or misses one.
        change.actor?.let { actor ->
            batch.put(byActorCf, actorKey(shared.registries.id(RegistryNamespace.PLAYER, actor), key), NOTHING)
        }
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
        byActorOptions.close()
        // The table config holds the filter, so it may only go once nothing can reach it. The cache is
        // the ledger's to close.
        bloom.close()
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
class BlockLogs(
    private val root: Path,
    private val shared: RocksItemLog,
    // Every accepted change, with the world it belongs to: a row that names somebody is also the
    // freshest thing that somebody did at that position.
    private val watch: (UUID, List<BlockChange>) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val logs = ConcurrentHashMap<UUID, BlockLog>()

    fun open(world: UUID): BlockLog =
        logs.computeIfAbsent(world) { BlockLog(dirOf(it), shared) { changes -> watch(it, changes) } }

    fun get(world: UUID): BlockLog? = logs[world]

    val worlds: Set<UUID> get() = logs.keys.toSet()

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
