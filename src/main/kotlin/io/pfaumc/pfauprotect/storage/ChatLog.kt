package io.pfaumc.pfauprotect.storage

import io.pfaumc.pfauprotect.model.idIndex
import org.rocksdb.Options
import org.rocksdb.RocksDB
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import kotlin.concurrent.read
import kotlin.concurrent.write

/** What a player said, ran or did on the way in and out: what an investigation reads beside the planes. */
enum class ChatKind(val id: Int) {
    CHAT(0), COMMAND(1), JOIN(2), QUIT(3);

    companion object {
        val byId = idIndex(entries) { it.id }
    }
}

data class ChatLine(
    val timestamp: Long,
    val kind: ChatKind,
    val player: UUID,
    val text: String,
    val world: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val z: Int = 0,
)

/**
 * A base of its own beside the ledger, so the ledger's families stay as they are. Keyed by time, then a
 * sequence that keeps two lines of one millisecond apart; no address is kept of anyone, only who and where
 * in the world. Written by one thread of its own, read from any.
 */
class ChatLog(dir: Path) : AutoCloseable {
    private val options = Options().setCreateIfMissing(true)
    private val db: RocksDB
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "pfauprotect-chat-writer").apply { isDaemon = true } }
    // Starts at an arbitrary point, which makes it unlikely that a restart within one millisecond reuses the
    // numbers a line of that millisecond already has. Kept positive: the read seeks below Int.MAX_VALUE.
    private val sequence = AtomicInteger((System.nanoTime() and 0x3FFF_FFFF).toInt())

    // A lookup reads from the async scheduler and the writer may be mid-put when the plugin is disabled;
    // RocksDB must not be closed under either, and a freed handle in RocksJava is a crash, not an error.
    private val dbLock = ReentrantReadWriteLock()
    private var closed = false

    init {
        RocksDB.loadLibrary()
        Files.createDirectories(dir)
        db = cleaningUpOnFailure({ options.close() }) { RocksDB.open(options, dir.toAbsolutePath().toString()) }
    }

    fun submit(line: ChatLine) {
        val key = keyOf(line.timestamp, sequence.getAndIncrement())
        runCatching { writer.execute { dbLock.read { if (!closed) db.put(key, encode(line)) } } }
    }

    /** Lines from `fromTs` to `toTs`, newest first, those the filter keeps, at most `limit`. */
    fun read(fromTs: Long, toTs: Long, limit: Int, keeps: (ChatLine) -> Boolean): List<ChatLine> = dbLock.read {
        val out = ArrayList<ChatLine>()
        if (closed) return out
        db.newIterator().use { iter ->
            iter.seekForPrev(keyOf(toTs, Int.MAX_VALUE))
            while (iter.isValid && out.size < limit) {
                val ts = ByteReader(iter.key()).longBE()
                if (ts < fromTs) break
                decode(ts, iter.value())?.takeIf(keeps)?.let(out::add)
                iter.prev()
            }
            iter.status()
        }
        out
    }

    // The lines already queued get their chance to be written. A line the writer took up just as the wait
    // ran out finds the base closed and is dropped, rather than reaching a freed handle.
    override fun close() {
        writer.shutdown()
        if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
            LOGGER.log(Level.WARNING, "the chat log closed with ${writer.shutdownNow().size} lines unwritten")
        }
        dbLock.write {
            if (closed) return
            closed = true
            // Closing does not sync the log, and every line was written without a sync.
            runCatching { db.flushWal(true) }.onFailure { LOGGER.log(Level.WARNING, "the chat log's last lines may not be on disk", it) }
            db.close()
            options.close()
        }
    }

    private fun keyOf(ts: Long, seq: Int): ByteArray = ByteWriter(12).longBE(ts).intBE(seq).toByteArray()

    private fun encode(line: ChatLine): ByteArray {
        val text = line.text.toByteArray(Charsets.UTF_8)
        val world = line.world.toByteArray(Charsets.UTF_8)
        return ByteWriter(1 + 1 + 16 + 4 + world.size + 12 + text.size)
            .byte(VERSION).byte(line.kind.id)
            .uuid(line.player)
            .intBE(world.size).bytes(world).intBE(line.x).intBE(line.y).intBE(line.z)
            .bytes(text).toByteArray()
    }

    private fun decode(ts: Long, value: ByteArray): ChatLine? = runCatching {
        val b = ByteReader(value)
        if (b.byte() != VERSION) return null
        val kind = ChatKind.byId(b.byte()) ?: return null
        val player = b.uuid()
        val world = b.bytes(b.intBE()).toString(Charsets.UTF_8)
        val x = b.intBE()
        val y = b.intBE()
        val z = b.intBE()
        val text = b.bytes(b.remaining).toString(Charsets.UTF_8)
        ChatLine(ts, kind, player, text, world, x, y, z)
    }.getOrNull()

    private companion object {
        const val VERSION = 0
    }
}
