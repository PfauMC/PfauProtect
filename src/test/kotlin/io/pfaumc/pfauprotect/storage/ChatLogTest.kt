package io.pfaumc.pfauprotect.storage

import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ChatLogTest {
    // Lines come back newest first, by time and by what the filter keeps; reopening loses none of them.
    @Test
    fun `what was said comes back newest first and by filter`(@TempDir dir: Path) {
        ChatLog(dir).use { log ->
            log.submit(ChatLine(1_000, ChatKind.CHAT, alice, "hello"))
            log.submit(ChatLine(2_000, ChatKind.COMMAND, bob, "/give Bob diamond", "world", 1, 64, 2))
            log.submit(ChatLine(3_000, ChatKind.QUIT, bob, "", "world", 1, 64, 2))
        }
        ChatLog(dir).use { log ->
            assertEquals(listOf(3_000L, 2_000L, 1_000L), log.read(0, Long.MAX_VALUE, 10) { true }.map { it.timestamp })
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
