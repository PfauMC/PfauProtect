package io.pfaumc.pfauprotect.storage

import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ChatLogTest {
    // Lines come back newest first, by time and by what the filter keeps; reopening loses none of them,
    // nor one of two said in the same millisecond.
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
        }
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
