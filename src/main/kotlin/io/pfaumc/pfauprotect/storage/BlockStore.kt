package io.pfaumc.pfauprotect.storage
import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.EntityKind
import org.rocksdb.BloomFilter
import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.RocksDB
import org.rocksdb.WriteBatch
import org.rocksdb.WriteOptions
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import kotlin.concurrent.read
import kotlin.concurrent.write

// What a world's base takes in one submission: changes to blocks, and to the entities standing among them.
sealed interface WorldChange {
    val x: Int
    val y: Int
    val z: Int
    val cause: Cause
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
    override val cause: Cause,
    val type: String,
    val uuid: UUID,
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Confidence = Confidence.FACT,
    override val actor: UUID? = null,
    val before: ByteArray? = null,
    val after: ByteArray? = null,
    val drops: List<UUID> = emptyList(),
    // What killed it, by damage type, and the entity that dealt the blow when that was not the actor:
    // a dog, an arrow. Null for a removal that was no death.
    val death: String? = null,
    val via: String? = null,
) : WorldChange

// Both sides are nullable so that a capture which only learned one of them says so and is refused,
// rather than filing air for the side it never saw.
data class BlockChange(
    override val x: Int,
    override val y: Int,
    override val z: Int,
    val before: String?,
    val after: String?,
    override val cause: Cause,
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

data class ActorTouches(val positions: Set<List<Int>>, val complete: Boolean)

data class ActorRows(val blocks: List<BlockRow>, val entities: List<EntityRow>, val complete: Boolean)

// Raised by a change to the key layout or to the set of column families. The record version in the
// value covers neither: without the bump an older database opens and every key is parsed as something it
// never was. A version 1 base gets its by-actor index built from its rows on first open; a version 2 base
// simply has no entity rows yet.
private const val BLOCK_SCHEMA_VERSION = 3L
private const val INDEXED_FROM = 1L
private const val WITHOUT_ENTITIES = 2L

private const val ACTOR_KEY_SIZE = 4 + BlockCodec.KEY_SIZE
private val NOTHING = ByteArray(0)

private val ROWS_CF = "rows".toByteArray()
private val BLOCK_META_CF = "meta".toByteArray()
private val BY_ACTOR_CF = "by_actor".toByteArray()
private val ENTITIES_CF = "entities".toByteArray()

private val META_SCHEMA_KEY = "schema".toByteArray()
private val META_EVENT_ID = "event_id".toByteArray()
private val META_LAST_TS = "last_ts".toByteArray()


// One world's block history. The world number is not in the key because the database is the world,
// which is what makes deleting a world a matter of deleting a directory: registry numbers are never
// reused, so rows of a world that is gone could otherwise only be got rid of by a range delete.
//
// The state numbers, the player numbers and the interned payloads all live in `shared`, and each is
// made durable there before the row that names it, for the reason given at `RocksItemLog.syncWriteOptions`.
class BlockLog(
    dir: Path,
    private val shared: RocksItemLog,
    // Shown every list of block changes the log accepts, on the thread that submitted it.
    private val watch: (List<BlockChange>) -> Unit,
    // Asked of every change before it is accepted: false drops it, as another plugin may ask.
    private val keeps: (WorldChange) -> Boolean,
) : AutoCloseable {
    // Before the options that take them, and given back once those are freed.
    init {
        shared.borrowShared()
    }

    // The ledger's cache and memtable budget, so a world loaded is not another bound of its own.
    private val dbOptions = openingOptions(shared.writeBuffers)
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
    private val progress = WriterProgress("block", "submissions", queue)

    val backlog: Long get() = progress.backlog

    /** How many rows each family holds, as RocksDB estimates it without reading them. */
    fun estimatedRows(): Map<String, Long> = dbLock.read {
        if (closed) return emptyMap()
        mapOf("rows" to rowsCf, "entities" to entitiesCf, "by_actor" to byActorCf)
            .mapValues { (_, cf) -> db.getLongProperty(cf, ESTIMATED_KEYS) }
    }

    fun compactionDebt(): Long = dbLock.read { if (closed) 0 else db.compactionDebt(cfHandles) }

    @Volatile
    private var running = true

    @Volatile
    private var closed = false

    private val dbLock = ReentrantReadWriteLock()

    private var nextEventId = 0L
    private var lastTs = 0L

    init {
        RocksDB.loadLibrary()
        Files.createDirectories(dir)
        val path = dir.toAbsolutePath().toString()
        // Read before the open, as the ledger's is, so a base refused for its schema is left as it was found.
        val stored = cleaningUpOnFailure({ closeOptions() }) {
            storedSchema(path, BLOCK_META_CF, META_SCHEMA_KEY)?.also {
                require(it == BLOCK_SCHEMA_VERSION || it == INDEXED_FROM || it == WITHOUT_ENTITIES) {
                    "block database schema $it cannot be read by this build (schema $BLOCK_SCHEMA_VERSION)"
                }
            }
        }
        val descriptors = listOf(
            RocksDB.DEFAULT_COLUMN_FAMILY to metaOptions,
            ROWS_CF to rowsOptions,
            BLOCK_META_CF to metaOptions,
            BY_ACTOR_CF to byActorOptions,
            ENTITIES_CF to rowsOptions,
        ).map { (name, options) -> ColumnFamilyDescriptor(name, options) }
        // Each world load that retries a failed open would otherwise strand another set of natives.
        db = cleaningUpOnFailure({ closeOptions() }) { RocksDB.open(dbOptions, path, descriptors, cfHandles) }
        rowsCf = cfHandles[1]
        metaCf = cfHandles[2]
        byActorCf = cfHandles[3]
        entitiesCf = cfHandles[4]

        cleaningUpOnFailure({ closeNatives() }) {
            if (stored == INDEXED_FROM) indexActors()
            if (stored != BLOCK_SCHEMA_VERSION) db.put(metaCf, META_SCHEMA_KEY, longBytes(BLOCK_SCHEMA_VERSION))
            nextEventId = db.readCounter(metaCf, META_EVENT_ID)
            lastTs = db.readCounter(metaCf, META_LAST_TS)
        }
    }

    // Once, on the first open of a base written before the index existed, and before the schema says it
    // has one: a crash halfway through leaves the old stamp, and the next open starts again, writing the
    // same keys over themselves.
    private fun indexActors() {
        ChunkedBatch(db, writeOptions).use { batch ->
            db.forEachRow(rowsCf) { key, value ->
                val actor = BlockCodec.actorNumberOf(value)
                if (actor != null && key.size == BlockCodec.KEY_SIZE) batch.put(byActorCf, actorKey(actor, key), NOTHING)
            }
            batch.flush()
        }
    }

    /**
     * Rows older than the cutoff, deleted unless `dryRun`: every one but the newest of a position no later
     * row stands over, which is what the attribution asks a position. The index entries of those rows and
     * the entity rows older than the cutoff go too. Answers how many of each went.
     */
    fun purgeBefore(cutoff: Long, dryRun: Boolean): Pair<Int, Int> = dbLock.read {
        if (closed) return 0 to 0
        var rows = 0
        var entities = 0
        ChunkedBatch(db, writeOptions).use { batch ->
            fun delete(cf: ColumnFamilyHandle, key: ByteArray) {
                if (!dryRun) batch.delete(cf, key)
            }
            var position: ByteArray? = null
            var held: ByteArray? = null
            db.forEachRow(rowsCf) { key, _ ->
                val here = key.copyOfRange(0, Zcode.SIZE)
                if (!here.contentEquals(position)) {
                    position = here
                    held = null
                }
                // Any later row of the position, old or not, means the one held is no longer its newest old row.
                held?.let { delete(rowsCf, it); rows++ }
                held = key.takeIf { timeAt(it, Zcode.SIZE) < cutoff }
            }
            db.forEachRow(entitiesCf) { key, _ ->
                if (timeAt(key, Zcode.SIZE) < cutoff) {
                    delete(entitiesCf, key)
                    entities++
                }
            }
            batch.flush()
        }
        if (!dryRun) ChunkedBatch(db, writeOptions).use { batch ->
            // After the rows are gone: an entry is kept exactly when its row was, whichever plane it names.
            db.forEachRow(byActorCf) { key, _ ->
                if (key.size != ACTOR_KEY_SIZE || timeAt(key, 4) >= cutoff) return@forEachRow
                val row = rowKeyOf(key)
                if (db.get(rowsCf, row) == null && db.get(entitiesCf, row) == null) batch.delete(byActorCf, key)
            }
            batch.flush()
        }
        rows to entities
    }

    /**
     * Where an actor's rows in the window stand: the positions, from the index, without reading a row.
     * `budget` bounds the index rows walked, and a walk that reaches it says so.
     */
    fun touchedBy(actor: UUID, fromTs: Long, toTs: Long, budget: Int): ActorTouches = dbLock.read {
        val number = shared.registries.lookupUuid(RegistryNamespace.PLAYER, actor)
        if (closed) return ActorTouches(emptySet(), false)
        if (number == null) return ActorTouches(emptySet(), true)
        val prefix = actorPrefix(number)
        val start = ByteWriter(12).bytes(prefix).longBE(fromTs).toByteArray()
        val positions = HashSet<List<Int>>()
        var walked = 0
        db.forEachUnder(byActorCf, prefix, reverse = false, from = start) { key, _ ->
            val ts = timeAt(key, prefix.size)
            if (ts > toTs) return@forEachUnder false
            if (walked++ >= budget) return ActorTouches(positions, false)
            positions += Zcode.decode(key, ACTOR_KEY_SIZE - Zcode.SIZE).toList()
            true
        }
        ActorTouches(positions, true)
    }

    /**
     * An actor's own rows in the window, newest first, each read by the key its index entry holds rather
     * than with the rest of its position's history. Stops once `wanted` rows were kept, or `budget` index
     * entries were walked, which leaves the answer incomplete.
     */
    fun byActor(
        actor: UUID,
        fromTs: Long,
        toTs: Long,
        wanted: Int,
        budget: Int,
        keepBlock: (BlockRow) -> Boolean,
        keepEntity: (EntityRow) -> Boolean,
    ): ActorRows = dbLock.read {
        val number = shared.registries.lookupUuid(RegistryNamespace.PLAYER, actor)
        if (closed) return ActorRows(emptyList(), emptyList(), false)
        if (number == null) return ActorRows(emptyList(), emptyList(), true)
        val prefix = actorPrefix(number)
        val blocks = ArrayList<BlockRow>()
        val entities = ArrayList<EntityRow>()
        var walked = 0
        var complete = true
        db.forEachUnder(byActorCf, prefix, reverse = true, from = lastUnder(ByteWriter(12).bytes(prefix).longBE(toTs).toByteArray())) { key, _ ->
            if (key.size != ACTOR_KEY_SIZE || timeAt(key, prefix.size) < fromTs) return@forEachUnder false
            if (blocks.size + entities.size >= wanted) return@forEachUnder false
            if (walked++ >= budget) {
                complete = false
                return@forEachUnder false
            }
            // Which plane the row is in the entry does not say; a block row is far the likelier.
            val row = rowKeyOf(key)
            val block = db.get(rowsCf, row)
            if (block != null) blockRow(row, block)?.takeIf(keepBlock)?.let { blocks += it }
            else db.get(entitiesCf, row)?.let { entityRow(row, it) }?.takeIf(keepEntity)?.let { entities += it }
            true
        }
        ActorRows(blocks, entities, complete)
    }

    private fun actorPrefix(actor: Int): ByteArray =
        ByteWriter(4).intBE(actor).toByteArray()

    // The actor's number, then the row key turned round: time, event and ordinal, then the position.
    private fun actorKey(actor: Int, rowKey: ByteArray): ByteArray =
        ByteWriter(ACTOR_KEY_SIZE)
            .bytes(actorPrefix(actor))
            .bytes(rowKey.copyOfRange(Zcode.SIZE, BlockCodec.KEY_SIZE))
            .bytes(rowKey.copyOf(Zcode.SIZE))
            .toByteArray()

    private fun rowKeyOf(actorKey: ByteArray): ByteArray =
        actorKey.copyOfRange(ACTOR_KEY_SIZE - Zcode.SIZE, ACTOR_KEY_SIZE) + actorKey.copyOfRange(4, ACTOR_KEY_SIZE - Zcode.SIZE)

    private val writerThread =
        Thread(::runWriter, "pfauprotect-block-writer-${dir.fileName}").apply { isDaemon = true }

    init {
        cleaningUpOnFailure({ closeNatives() }) { writerThread.start() }
    }

    /**
     * Every change in the list shares one event id: one explosion, one piston firing, one tree
     * growing is one event across every position it touched, so all of them are findable together.
     * A change missing either side is refused and dropped on its own; the rest of the list is
     * written. False means the log took nothing and the caller still holds the only copy.
     */
    fun submit(written: List<WorldChange>): Boolean {
        val off = Settings.disabledCauses
        val changes = written.filter { it.cause !in off && keeps(it) }
        // Not `closed`: that is only set once the writer has been joined, and from the moment the
        // writer stops there is nobody left to take the queue. A change accepted in between would be
        // counted as submitted, sit in the queue and never be written, and `drain` would not wait for
        // it either.
        if (!running || closed || progress.failure != null || changes.isEmpty()) return false
        progress.submitted.incrementAndGet()
        queue.add(changes)
        val blocks = changes.filterIsInstance<BlockChange>()
        if (blocks.isNotEmpty()) watch(blocks)
        return true
    }

    fun submit(one: WorldChange): Boolean = submit(listOf(one))

    fun drain() {
        if (!closed) progress.drain()
    }

    fun at(
        x: Int,
        y: Int,
        z: Int,
        fromTs: Long,
        toTs: Long,
        limit: Int,
        reverse: Boolean,
    ): List<BlockRow> = dbLock.read {
        val found = ArrayList<BlockRow>()
        if (closed || limit <= 0) return found
        forEachUnder(BlockCodec.positionPrefix(x, y, z), reverse) { key, value ->
            // The timestamp follows a position prefix directly, so the walk is already in time order
            // and the window is nothing but the rows it drops.
            val row = blockRow(key, value)
            if (row != null && row.timestamp in fromTs..toTs) found += row
            found.size < limit
        }
        found
    }

    // Inside a chunk the key sorts by position and only then by time, so a walk answers in position
    // order: `reverse` would mean the far corner of the chunk rather than the newest change, and a
    // limit would cut the answer off at a position rather than at a time. Every row under the prefix
    // has to be in hand and back in time order before either can be applied.
    fun inChunk(
        chunkX: Int,
        chunkZ: Int,
        fromTs: Long,
        toTs: Long,
        limit: Int,
        reverse: Boolean,
        // Applied before the limit for the same reason the limit waits for the whole chunk.
        within: (BlockRow) -> Boolean = { true },
    ): List<BlockRow> = dbLock.read {
        if (closed || limit <= 0) return emptyList()
        val found = ArrayList<BlockRow>()
        forEachUnder(Zcode.chunkPrefix(chunkX, chunkZ), reverse = false) { key, value ->
            val row = blockRow(key, value)
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
    ): Window<BlockRow> = window(rowsCf, Zcode.chunkPrefix(chunkX, chunkZ), fromTs, toTs, budget, within, ::blockRow)

    /** The newest block rows of a chunk inside the box, see [newestInBox]. */
    fun newestInChunk(chunkX: Int, chunkZ: Int, box: Box, fromTs: Long, toTs: Long, wanted: Int, budget: Int): BoxPage<BlockRow> =
        newestIn(rowsCf, chunkX, chunkZ, box, fromTs, toTs, wanted, budget, ::blockRow)

    /** The newest entity rows of a chunk inside the box, see [newestInBox]. */
    fun newestEntitiesInChunk(chunkX: Int, chunkZ: Int, box: Box, fromTs: Long, toTs: Long, wanted: Int, budget: Int): BoxPage<EntityRow> =
        newestIn(entitiesCf, chunkX, chunkZ, box, fromTs, toTs, wanted, budget, ::entityRow)

    private fun <T> newestIn(
        cf: ColumnFamilyHandle, chunkX: Int, chunkZ: Int, box: Box, fromTs: Long, toTs: Long, wanted: Int, budget: Int,
        decode: (ByteArray, ByteArray) -> T?,
    ): BoxPage<T> = dbLock.read {
        if (closed) return BoxPage(emptyList(), false)
        db.newestInBox(cf, ByteArray(0), chunkX, chunkZ, box, fromTs, toTs, wanted, budget, decode)
    }

    fun windowAt(x: Int, y: Int, z: Int, fromTs: Long, toTs: Long, budget: Int): Window<BlockRow> =
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

    private fun indexActor(batch: WriteBatch, actor: UUID?, key: ByteArray) {
        if (actor != null) batch.put(byActorCf, actorKey(shared.registries.id(RegistryNamespace.PLAYER, actor), key), NOTHING)
    }

    private fun blockRow(key: ByteArray, value: ByteArray): BlockRow? = BlockCodec.decodeOrNull(key, value, shared.registries)

    private fun entityRow(key: ByteArray, value: ByteArray): EntityRow? = EntityCodec.decodeOrNull(key, value, shared.registries)

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
            when {
                row == null -> unreadable++
                at != null && timeAt(key, Zcode.SIZE) in fromTs..toTs && within(at[0], at[1], at[2]) -> rows += row
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
            row = blockRow(key, value)
            false
        }
        BlockStanding(row, torn = found && row == null)
    }

    /**
     * The last row of a position older than `ts`: what stood there at that moment, and who left it so. The
     * walk steps back over the newer rows, which a caller asking this has just read anyway.
     */
    fun standingBefore(x: Int, y: Int, z: Int, ts: Long): BlockRow? = dbLock.read {
        if (closed) return null
        var found: BlockRow? = null
        forEachUnder(BlockCodec.positionPrefix(x, y, z), reverse = true) { key, value ->
            val row = blockRow(key, value) ?: return@forEachUnder true
            if (row.timestamp >= ts) return@forEachUnder true
            found = row
            false
        }
        found
    }

    /** The first row the position has, or null for none; what stood there before it is what the plane never saw. */
    fun oldestAt(x: Int, y: Int, z: Int): BlockRow? = dbLock.read {
        if (closed) return null
        var row: BlockRow? = null
        forEachUnder(BlockCodec.positionPrefix(x, y, z), reverse = false) { key, value ->
            row = blockRow(key, value)
            row == null
        }
        row
    }

    /**
     * The rows of a position older than `ts`, newest first, at most `limit` of them. One this build cannot
     * read comes back as null, where it stood, so a walk back over them stops there rather than past it.
     */
    fun rowsBefore(x: Int, y: Int, z: Int, ts: Long, limit: Int): List<BlockRow?> = dbLock.read {
        if (closed) return emptyList()
        val found = ArrayList<BlockRow?>()
        forEachUnder(BlockCodec.positionPrefix(x, y, z), reverse = true) { key, value ->
            val row = blockRow(key, value)
            // Its time is in the key, which decodes when the rest does not.
            if (row == null) found += null else if (row.timestamp < ts) found += row
            found.size < limit
        }
        found
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

    private inline fun forEachUnder(
        prefix: ByteArray,
        reverse: Boolean,
        cf: ColumnFamilyHandle = rowsCf,
        action: (ByteArray, ByteArray) -> Boolean,
    ) = db.forEachUnder(cf, prefix, reverse, action = action)

    private fun runWriter() = progress.run(db, finished = { !running }, write = ::writeAll)

    // A change that cannot be written is dropped on its own, as a movement is in the ledger's writeAll.
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
            drops = change.drops, death = change.death, via = change.via,
        )
        val key = BlockCodec.key(change.x, change.y, change.z, change.timestamp, eventId, ordinal)
        batch.put(entitiesCf, key, EntityCodec.value(row, shared.registries))
        indexActor(batch, change.actor, key)
    }

    private fun writeChange(
        batch: WriteBatch,
        change: BlockChange,
        eventId: Long,
        ordinal: Int,
        batchTs: MutableMap<Triple<Int, Int, Int>, Long>,
    ) {
        val before = requireNotNull(change.before) { "the block at ${change.x} ${change.y} ${change.z} was changed from nothing known" }
        val after = requireNotNull(change.after) { "the block at ${change.x} ${change.y} ${change.z} was changed into nothing known" }
        val stateBefore = shared.registries.idForKey(RegistryNamespace.BLOCK_STATE, before)
        val stateAfter = shared.registries.idForKey(RegistryNamespace.BLOCK_STATE, after)
        val payloadBefore = change.payloadBefore?.let { shared.payloads.idOf(it) }
        val payloadAfter = change.payloadAfter?.let { shared.payloads.idOf(it) }
        // Key order is time order, so a row filed late must not sort behind its position's newest.
        // `lastTs` is the fast path, and it is persisted so a clock stepped back cannot skip the
        // check after a restart.
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
        indexActor(batch, change.actor, key)
        batchTs[here] = ts
    }

    // The time is in the key, so a position whose newest row does not decode still bounds what may
    // be filed under it, and no row has to be decoded to ask.
    private fun newestTsAt(x: Int, y: Int, z: Int): Long {
        var ts = 0L
        forEachUnder(BlockCodec.positionPrefix(x, y, z), reverse = true) { key, _ ->
            if (key.size < BlockCodec.KEY_SIZE) return@forEachUnder true
            ts = timeAt(key, Zcode.SIZE)
            false
        }
        return ts
    }

    private fun closeNatives() {
        db.closeWithHandles(cfHandles)
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
        shared.returnShared()
    }
}

class BlockLogs(
    private val root: Path,
    private val shared: RocksItemLog,
    // Every accepted change, with the world it belongs to: a row that names somebody is also the
    // freshest thing that somebody did at that position.
    private val watch: (UUID, List<BlockChange>) -> Unit = { _, _ -> },
    // Asked of every change before a world's log accepts it.
    private val keeps: (UUID, WorldChange) -> Boolean = { _, _ -> true },
) : AutoCloseable {
    private val logs = ConcurrentHashMap<UUID, BlockLog>()

    // A world loaded once the bases have started to close would get one that nothing ever closes.
    private var closed = false

    @Synchronized
    fun open(world: UUID): BlockLog {
        check(!closed) { "the world bases are closed" }
        return logs.computeIfAbsent(world) { BlockLog(dirOf(it), shared, { changes -> watch(it, changes) }) { change -> keeps(it, change) } }
    }

    fun get(world: UUID): BlockLog? = logs[world]

    val worlds: Set<UUID> get() = logs.keys.toSet()

    fun drain() {
        for (log in logs.values) log.drain()
    }

    @Synchronized
    fun close(world: UUID) {
        logs.remove(world)?.close()
    }

    // One world whose close throws still holds its own lock file, and stopping there would leave every
    // world after it holding one too, so the next start finds databases it cannot open.
    @Synchronized
    override fun close() {
        closed = true
        for (world in logs.keys.toList()) {
            runCatching { close(world) }.onFailure {
                LOGGER.log(Level.SEVERE, "the block log of world $world did not close", it)
            }
        }
    }

    private fun dirOf(world: UUID): Path = root.resolve(world.toString())
}
