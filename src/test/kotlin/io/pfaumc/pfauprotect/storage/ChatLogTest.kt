package io.pfaumc.pfauprotect.storage

import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.model.Confidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ChatLogTest {
    // Reopening loses no line, nor one of two said in the same millisecond.
    @Test
    fun `what was said comes back newest first and by filter`(@TempDir dir: Path) {
        ChatLog(dir).use { log ->
            log.submit(ChatLine(1_000, ChatKind.CHAT, alice, "hello"))
            log.submit(ChatLine(2_000, ChatKind.COMMAND, bob, "/give Bob diamond", "world", 1, 64, 2))
            log.submit(ChatLine(3_000, ChatKind.QUIT, bob, "", "world", 1, 64, 2))
            log.submit(ChatLine(4_000, ChatKind.CHAT, alice, "one"))
            log.submit(ChatLine(4_000, ChatKind.CHAT, bob, "two"))
        }
        ChatLog(dir).use { log ->
            val all = log.read(0, Long.MAX_VALUE, 10) { true }
            assertEquals(listOf(4_000L, 4_000L, 3_000L, 2_000L, 1_000L), all.map { it.timestamp })
            assertEquals(setOf("one", "two"), all.take(2).map { it.text }.toSet())
            val bobs = log.read(0, Long.MAX_VALUE, 10) { it.player == bob && "diamond" in it.text }
            assertEquals(listOf(ChatLine(2_000, ChatKind.COMMAND, bob, "/give Bob diamond", "world", 1, 64, 2)), bobs)
            assertEquals(listOf(1_000L), log.read(0, 1_500, 10) { true }.map { it.timestamp })
            // The limit counts what the filter kept, not what the read walked past.
            assertEquals(listOf("one", "hello"), log.read(0, Long.MAX_VALUE, 2) { it.player == alice }.map { it.text })
            assertEquals(listOf("hello"), log.read(0, Long.MAX_VALUE, 1) { it.text == "hello" }.map { it.text })
        }
    }

    // A line that no longer decodes is skipped without a word, so a changed layout would empty the log
    // silently; the bytes are written out by hand for that reason.
    @Test
    fun `a line keeps its stored layout`(@TempDir dir: Path) {
        ChatLog(dir).use { it.submit(ChatLine(2_000, ChatKind.COMMAND, bob, "/give", "world", 1, 64, 2)) }
        val (key, value) = rawRowsOf(dir, "default").entries.single()

        assertEquals("00000000000007d0", key.take(16), "the time, big-endian, then a sequence")
        assertEquals(24, key.length)
        val expected = "00" + "01" + "000000000000400080000000000000b0" +
            "00000005" + "world".toByteArray().hex() + "00000001" + "00000040" + "00000002" +
            "/give".toByteArray().hex()
        assertEquals(expected, value)
    }

    // A hit's victim, damage and confidence sit between the place and the text; the other kinds are as they were.
    @Test
    fun `a hit comes back with whom it struck`(@TempDir dir: Path) {
        val hit = ChatLine(2_000, ChatKind.HIT, bob, "minecraft:wolf", "world", 1, 64, 2, Hit(alice, 7f, Confidence.INFERRED))
        ChatLog(dir).use { it.submit(hit) }
        val value = rawRowsOf(dir, "default").values.single()
        val expected = "00" + "04" + "000000000000400080000000000000b0" +
            "00000005" + "world".toByteArray().hex() + "00000001" + "00000040" + "00000002" +
            alice.toString().replace("-", "") + "40e00000" + "01" + "minecraft:wolf".toByteArray().hex()
        assertEquals(expected, value)
        ChatLog(dir).use { assertEquals(listOf(hit), it.read(0, Long.MAX_VALUE, 10) { true }) }
    }

    // A lookup or a line can arrive while the plugin is being disabled; after the close it meets a base
    // that says it is closed, never the freed native handle.
    @Test
    fun `a closed log answers with nothing and takes nothing`(@TempDir dir: Path) {
        val log = ChatLog(dir)
        log.submit(ChatLine(1_000, ChatKind.CHAT, alice, "before"))
        log.close()

        assertEquals(emptyList<ChatLine>(), log.read(0, Long.MAX_VALUE, 10) { true })
        log.submit(ChatLine(2_000, ChatKind.CHAT, alice, "after"))
        log.close()

        ChatLog(dir).use { reopened ->
            assertEquals(listOf("before"), reopened.read(0, Long.MAX_VALUE, 10) { true }.map { it.text })
        }
    }
}
