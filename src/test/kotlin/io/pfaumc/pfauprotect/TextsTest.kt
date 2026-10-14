package io.pfaumc.pfauprotect

import org.junit.jupiter.api.extension.ExtendWith
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@ExtendWith(Vanilla::class)
class TextsTest {
    @AfterEach
    fun english() {
        Settings.language = "en"
    }

    @Test
    fun `a line goes out in the language asked for`() {
        val line = "  took back 32 minecraft:diamond from Test2."
        assertEquals(line, Texts.translate(line))
        Settings.language = "ru"
        assertTranslatedKeeping(line, "32", "minecraft:diamond", "Test2")
        assertTranslatedKeeping("Nothing to roll back: 5 blocks around 1 2 3.", "5", "1 2 3")
    }

    // The wording is the translator's to change; the values a sentence was filled with are not.
    private fun assertTranslatedKeeping(english: String, vararg values: String) {
        val translated = Texts.translate(english)
        assertNotEquals(english, translated)
        for (value in values) assertTrue(value in translated, "$value lost from \"$translated\"")
    }

    @Test
    fun `an item id in a line is the game's name of the item`() {
        val parts = Ui.named("  изъято 32 minecraft:diamond у Test2.").children()
        assertEquals("  изъято 32 ", (parts[0] as TextComponent).content())
        assertEquals("item.minecraft.diamond", (parts[1] as TranslatableComponent).key())
        assertEquals(" у Test2.", (parts[2] as TextComponent).content())
        // The dot after an id ends the sentence, it is no part of the id.
        val last = Ui.named("  would take back from Test2: 16 minecraft:diamond.").children()
        assertEquals("item.minecraft.diamond", (last[1] as TranslatableComponent).key())
        assertEquals(".", (last[2] as TextComponent).content())
    }
}
