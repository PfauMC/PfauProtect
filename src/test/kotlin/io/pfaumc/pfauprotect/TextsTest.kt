package io.pfaumc.pfauprotect

import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
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
    }

    // The items a rollback takes back are named as the game names them, in the reader's own language.
    @Test
    fun `an item id in a line is the game's name of the item`() {
        ServerRegistries.access
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
