package io.pfaumc.pfauprotect.storage

import org.rocksdb.Options
import org.rocksdb.RocksDB
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What a player said, ran or did on the way in and out: what an investigation reads beside the planes. */
enum class ChatKind(val id: Int) {
    CHAT(0), COMMAND(1), JOIN(2), QUIT(3);

    companion object {
        fun byId(id: Int) = entries.firstOrNull { it.id == id }
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
    private val sequence = AtomicInteger()

    init {
        RocksDB.loadLibrary()
        Files.createDirectories(dir)
        db = RocksDB.open(options, dir.toAbsolutePath().toString())
    }

    fun submit(line: ChatLine) {
        val key = ByteBuffer.allocate(12).putLong(line.timestamp).putInt(sequence.getAndIncrement()).array()
        writer.execute { db.put(key, encode(line)) }
    }

    /** Lines from `fromTs` to `toTs`, newest first, those the filter keeps, at most `limit`. */
    fun read(fromTs: Long, toTs: Long, limit: Int, keeps: (ChatLine) -> Boolean): List<ChatLine> {
        val out = ArrayList<ChatLine>()
        db.newIterator().use { iter ->
            iter.seekForPrev(ByteBuffer.allocate(12).putLong(toTs).putInt(Int.MAX_VALUE).array())
            while (iter.isValid && out.size < limit) {
                val ts = ByteBuffer.wrap(iter.key()).long
                if (ts < fromTs) break
                decode(ts, iter.value())?.takeIf(keeps)?.let(out::add)
                iter.prev()
            }
        }
        return out
    }

    override fun close() {
        writer.shutdown()
        writer.awaitTermination(10, TimeUnit.SECONDS)
        db.close()
        options.close()
    }

    private fun encode(line: ChatLine): ByteArray {
        val text = line.text.toByteArray(Charsets.UTF_8)
        val world = line.world.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + 1 + 16 + 4 + world.size + 12 + text.size)
            .put(VERSION.toByte()).put(line.kind.id.toByte())
            .putLong(line.player.mostSignificantBits).putLong(line.player.leastSignificantBits)
            .putInt(world.size).put(world).putInt(line.x).putInt(line.y).putInt(line.z)
            .put(text).array()
    }

    private fun decode(ts: Long, value: ByteArray): ChatLine? = runCatching {
        val b = ByteBuffer.wrap(value)
        if (b.get().toInt() != VERSION) return null
        val kind = ChatKind.byId(b.get().toInt()) ?: return null
        val player = UUID(b.long, b.long)
        val world = ByteArray(b.int).also { b.get(it) }.toString(Charsets.UTF_8)
        val x = b.int
        val y = b.int
        val z = b.int
        val text = ByteArray(b.remaining()).also { b.get(it) }.toString(Charsets.UTF_8)
        ChatLine(ts, kind, player, text, world, x, y, z)
    }.getOrNull()

    private companion object {
        const val VERSION = 0
    }
}
