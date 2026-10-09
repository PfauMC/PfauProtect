package io.pfaumc.pfauprotect

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextsTest {
    @AfterEach
    fun english() {
        Settings.language = "en"
    }

    // In Russian a sentence goes over whole, with what it names translated word by word; in English as it is.
    @Test
    fun `a line goes out in the language asked for`() {
        val line = "  took back 32 minecraft:diamond from Test2."
        assertEquals(line, Texts.translate(line))
        Settings.language = "ru"
        assertEquals("  изъято 32 minecraft:diamond у Test2.", Texts.translate(line))
        assertEquals("Откатывать нечего: 5 блоков вокруг 1 2 3.", Texts.translate("Nothing to roll back: 5 blocks around 1 2 3."))
        // " slot " is a word of its own only after the phrases that hold it.
        assertEquals("(1 строк блоков, 1 строк слотов прочитано)", Texts.translate("(1 block rows, 1 slot rows read)"))
    }
}
