package io.pfaumc.pfauprotect

import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.DBOptions
import org.rocksdb.RocksDB
import org.rocksdb.WriteBatch
import org.rocksdb.WriteOptions
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
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

interface ItemLog : AutoCloseable {
    fun submit(transfer: Transfer)
    fun holderEntries(
        holder: Holder,
        fromTs: Long,
        toTs: Long,
        reverse: Boolean = false,
        limit: Int = 100,
    ): List<LedgerEntry>

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
    ): List<LedgerEntry>

    fun form(itemFormId: Long): ByteArray?
    fun transactionEntries(entry: LedgerEntry): List<LedgerEntry>
    fun balanceOf(holder: Holder, itemFormId: Long): Nothing
    fun formPath(itemFormId: Long): Nothing
    fun drain()
}

// A shulker's loot table copies a handful of components onto the dropped item and the owner mark is
// not among them, so a box loses its name every time it is broken. This remembers the name for as
// long as the box stands, and hands it back at the break so the chain of custody survives the cycle.
interface NestedOwners {
    fun ownerAt(world: UUID, x: Int, y: Int, z: Int): UUID?
    fun setOwnerAt(world: UUID, x: Int, y: Int, z: Int, owner: UUID)
    fun clearOwnerAt(world: UUID, x: Int, y: Int, z: Int)
}

data class SweepReport(val checked: Int, val gaps: List<String>, val reachedEnd: Boolean)

private const val SCHEMA_VERSION = 1L
private const val MAX_REGION_CHUNKS = 1024
private const val MAX_BATCH = 256
private const val WRITER_POLL_MILLIS = 50L
private const val WAL_FLUSH_INTERVAL_NANOS = 1_000_000_000L
private const val DRAIN_TIMEOUT_NANOS = 10_000_000_000L
private const val UNASSIGNED = -1

// Longer than the longest tail a key can carry after any prefix this class scans, so a prefix padded
// with this many 0xFF bytes sorts after every key under it.
private const val KEY_TAIL_PAD = 32

private val ENTRIES_CF = "entries".toByteArray()
private val ITEM_FORMS_CF = "item_forms".toByteArray()
private val REGISTRY_CF = "registry".toByteArray()
private val META_CF = "meta".toByteArray()
private val NESTED_OWNERS_CF = "nested_owners".toByteArray()

private val META_SCHEMA = "schema".toByteArray()
private val META_TX_ID = "tx_id".toByteArray()
private val META_ITEM_FORM_ID = "item_form_id".toByteArray()
private val META_SWEEP_CURSOR = "sweep_cursor".toByteArray()

private val LOGGER: Logger = Logger.getLogger("PfauProtect")

private fun longBytes(v: Long): ByteArray = ByteWriter(8).longBE(v).toByteArray()

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && Arrays.equals(this, 0, prefix.size, prefix, 0, prefix.size)

private class FormKey(private val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is FormKey && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

class RocksItemLog(dir: Path) : ItemLog, RegistryStore, NestedOwners {
    private val dbOptions = DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true)
    private val cfOptions = ColumnFamilyOptions()
    private val writeOptions = WriteOptions()
    private val cfHandles = ArrayList<ColumnFamilyHandle>()
    private val db: RocksDB
    private val entriesCf: ColumnFamilyHandle
    private val itemFormsCf: ColumnFamilyHandle
    private val registryCf: ColumnFamilyHandle
    private val metaCf: ColumnFamilyHandle
    private val nestedOwnersCf: ColumnFamilyHandle

    private val queue = LinkedBlockingQueue<Transfer>()
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
    private var nextTxId: Long
    private var nextItemFormId: Long
    private val registryCounters = ConcurrentHashMap<RegistryNamespace, Long>()

    init {
        RocksDB.loadLibrary()
        Files.createDirectories(dir)
        val descriptors = listOf(
            RocksDB.DEFAULT_COLUMN_FAMILY, ENTRIES_CF, ITEM_FORMS_CF, REGISTRY_CF, META_CF, NESTED_OWNERS_CF,
        )
            .map { ColumnFamilyDescriptor(it, cfOptions) }
        db = RocksDB.open(dbOptions, dir.toAbsolutePath().toString(), descriptors, cfHandles)
        entriesCf = cfHandles[1]
        itemFormsCf = cfHandles[2]
        registryCf = cfHandles[3]
        metaCf = cfHandles[4]
        nestedOwnersCf = cfHandles[5]

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

    private val writerThread = Thread(::runWriter, "pfauprotect-ledger-writer").apply { isDaemon = true }

    val registries = Registries(this).apply { load() }
    val forms = ItemFormTable().apply { load() }

    // An unregistered world or player owns no rows at all, and varInt(-1) cannot collide with any
    // number the registry hands out, so a scan for one simply finds nothing.
    private val knownIds = IdResolver { ns, uuid -> registries.lookupKey(ns, uuid.toString()) ?: UNASSIGNED }

    init {
        writerThread.start()
    }

    override fun submit(transfer: Transfer) {
        if (writerFailure != null) return
        submitted.incrementAndGet()
        queue.add(transfer)
    }

    override fun drain() {
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

    override fun holderEntries(
        holder: Holder,
        fromTs: Long,
        toTs: Long,
        reverse: Boolean,
        limit: Int,
    ): List<LedgerEntry> = dbLock.read {
        if (closed) return emptyList()
        val found = ArrayList<LedgerEntry>()
        scan(EntryCodec.holderPrefix(holder, knownIds), fromTs, toTs, reverse, limit, found)
        found
    }

    override fun regionEntries(
        world: UUID,
        minX: Int,
        minZ: Int,
        maxX: Int,
        maxZ: Int,
        fromTs: Long,
        toTs: Long,
        reverse: Boolean,
        limit: Int,
    ): List<LedgerEntry> {
        val chunkX = (minOf(minX, maxX) shr 4)..(maxOf(minX, maxX) shr 4)
        val chunkZ = (minOf(minZ, maxZ) shr 4)..(maxOf(minZ, maxZ) shr 4)
        val chunks = (chunkX.last - chunkX.first + 1).toLong() * (chunkZ.last - chunkZ.first + 1).toLong()
        require(chunks <= MAX_REGION_CHUNKS) {
            "region spans $chunks chunks, at most $MAX_REGION_CHUNKS can be scanned in one query"
        }
        if (registries.lookupKey(RegistryNamespace.WORLD, world.toString()) == null) return emptyList()
        return dbLock.read {
            if (closed) return emptyList()
            val found = ArrayList<LedgerEntry>()
            // Keys inside a chunk are ordered by position and only then by time, so a chunk holding
            // more rows than the limit contributes them by position rather than by time.
            for (cx in chunkX) {
                for (cz in chunkZ) {
                    val chunkFound = ArrayList<LedgerEntry>()
                    scan(EntryCodec.containerChunkPrefix(world, cx, cz, knownIds), fromTs, toTs, reverse, limit, chunkFound)
                    found += chunkFound
                }
            }
            val byTime = compareBy<LedgerEntry>({ it.timestamp }, { it.txId })
            found.sortedWith(if (reverse) byTime.reversed() else byTime).take(limit)
        }
    }

    override fun transactionEntries(entry: LedgerEntry): List<LedgerEntry> = dbLock.read {
        val other = entry.counterparty
        if (closed || other === Void || other is MenuSlot) return listOf(entry)
        val key = EntryCodec.key(other, entry.timestamp, entry.txId, knownIds)
        val value = db.get(entriesCf, key) ?: return listOf(entry)
        val decoded = EntryCodec.decodeOrNull(key, value, registries) ?: return listOf(entry)
        listOf(entry, decoded)
    }

    override fun form(itemFormId: Long): ByteArray? = forms.formOf(itemFormId)

    // The standing test of the capture: an entry that does not face the void has a second half, and
    // the two cancel each other out. A half that is missing is not a dupe but a hole in the capture —
    // two ends of one movement that collapsed onto one key, or a write that never landed — and it is
    // worth hearing about now rather than in the middle of an investigation years later. Each pass
    // picks up where the last one stopped, so the whole ledger is covered over time at a fixed cost.
    fun sweep(limit: Int): SweepReport = dbLock.read {
        if (closed) return SweepReport(0, emptyList(), false)
        val gaps = ArrayList<String>()
        var checked = 0
        var last: ByteArray? = null
        var reachedEnd = false
        db.newIterator(entriesCf).use { iter ->
            val cursor = db.get(metaCf, META_SWEEP_CURSOR)
            if (cursor == null) {
                iter.seekToFirst()
            } else {
                iter.seek(cursor)
                if (iter.isValid && iter.key().contentEquals(cursor)) iter.next()
                if (!iter.isValid) iter.seekToFirst()
            }
            while (iter.isValid && checked < limit) {
                val key = iter.key()
                EntryCodec.decodeOrNull(key, iter.value(), registries)?.let { entry ->
                    checked++
                    gapOf(entry)?.let { gaps += it }
                }
                last = key
                iter.next()
            }
            reachedEnd = !iter.isValid
        }
        if (reachedEnd || last == null) db.delete(metaCf, META_SWEEP_CURSOR) else db.put(metaCf, META_SWEEP_CURSOR, last)
        SweepReport(checked, gaps, reachedEnd)
    }

    private fun gapOf(entry: LedgerEntry): String? {
        val other = entry.counterparty
        if (other === Void || other is MenuSlot) return null
        val pair = transactionEntries(entry)
        val where = "${entry.cause} of ${entry.qty} at ${entry.holder}, transaction ${entry.txId}"
        if (pair.size < 2) return "$where: the half at $other is missing"
        if (pair[0].itemFormId != pair[1].itemFormId) return "$where: the halves name different items"
        val sum = pair.sumOf { it.qty }
        return if (sum == 0) null else "$where: the halves leave $sum behind"
    }

    override fun ownerAt(world: UUID, x: Int, y: Int, z: Int): UUID? = dbLock.read {
        if (closed) return null
        db.get(nestedOwnersCf, ownerKey(world, x, y, z))?.let { ByteReader(it).uuid() }
    }

    override fun setOwnerAt(world: UUID, x: Int, y: Int, z: Int, owner: UUID) = dbLock.read {
        if (closed) return
        db.put(nestedOwnersCf, ownerKey(world, x, y, z), ByteWriter(16).uuid(owner).toByteArray())
    }

    override fun clearOwnerAt(world: UUID, x: Int, y: Int, z: Int) = dbLock.read {
        if (closed) return
        db.delete(nestedOwnersCf, ownerKey(world, x, y, z))
    }

    private fun ownerKey(world: UUID, x: Int, y: Int, z: Int): ByteArray =
        ByteWriter(16 + Zcode.SIZE).uuid(world).bytes(Zcode.encode(x, y, z)).toByteArray()

    override fun balanceOf(holder: Holder, itemFormId: Long): Nothing =
        throw UnsupportedOperationException("balances need the analytical backend; the ledger only stores raw entries")

    override fun formPath(itemFormId: Long): Nothing =
        throw UnsupportedOperationException("form paths need the analytical backend; the ledger only stores raw entries")

    // Reads run on any thread, so the native handles may only be freed once every reader has left.
    override fun close() {
        running = false
        writerThread.join()
        dbLock.write {
            if (closed) return
            closed = true
            runCatching { db.flushWal(true) }
            cfHandles.forEach { it.close() }
            db.close()
            writeOptions.close()
            cfOptions.close()
            dbOptions.close()
        }
    }

    override fun loadAll(): List<RegistryRow> = dbLock.read {
        val rows = ArrayList<RegistryRow>()
        if (closed) return rows
        db.newIterator(registryCf).use { iter ->
            iter.seekToFirst()
            while (iter.isValid) {
                val key = iter.key()
                val ns = RegistryNamespace.entries.firstOrNull { it.id == (key[0].toInt() and 0xFF) }
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
                    val batched = ArrayList<Transfer>(MAX_BATCH)
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

    private fun writeAll(transfers: List<Transfer>) {
        WriteBatch().use { batch ->
            pendingBatch = batch
            try {
                for (transfer in transfers) writeTransfer(batch, transfer)
            } finally {
                pendingBatch = null
            }
            db.write(writeOptions, batch)
        }
    }

    private fun writeTransfer(batch: WriteBatch, transfer: Transfer) {
        val itemFormId = forms.idOf(transfer.form)
        val txId = nextTxId++
        batch.put(metaCf, META_TX_ID, longBytes(nextTxId))
        putEntry(batch, entryOf(transfer, transfer.from, transfer.to, -transfer.qty, itemFormId, txId))
        putEntry(batch, entryOf(transfer, transfer.to, transfer.from, transfer.qty, itemFormId, txId))
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
        provenanceId = null,
        actor = transfer.actor,
    )

    private fun putEntry(batch: WriteBatch, entry: LedgerEntry) {
        if (entry.holder === Void || entry.holder is MenuSlot) return
        batch.put(
            entriesCf,
            EntryCodec.key(entry.holder, entry.timestamp, entry.txId, registries),
            EntryCodec.value(entry, registries),
        )
    }

    private fun scan(
        prefix: ByteArray,
        fromTs: Long,
        toTs: Long,
        reverse: Boolean,
        limit: Int,
        into: MutableList<LedgerEntry>,
    ) {
        // A chunk prefix stops three position bytes short of the timestamp, so no byte range under it
        // can express a time window; the window is applied to the decoded entry instead.
        val afterPrefix = ByteWriter(prefix.size + KEY_TAIL_PAD)
            .bytes(prefix)
            .bytes(ByteArray(KEY_TAIL_PAD) { 0xFF.toByte() })
            .toByteArray()
        db.newIterator(entriesCf).use { iter ->
            if (reverse) iter.seekForPrev(afterPrefix) else iter.seek(prefix)
            while (iter.isValid && into.size < limit) {
                val key = iter.key()
                if (!key.startsWith(prefix)) return
                val entry = EntryCodec.decodeOrNull(key, iter.value(), registries)
                if (entry != null && entry.timestamp in fromTs..toTs) into += entry
                if (reverse) iter.prev() else iter.next()
            }
        }
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
            db.newIterator(itemFormsCf).use { iter ->
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

        private fun remember(id: Long, form: ByteArray) {
            idByForm[FormKey(form)] = id
            formById[id] = form
        }
    }
}
