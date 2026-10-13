package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.model.Cause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommandsTest {
    @Test
    fun `a command is read the way the server reads it`() {
        assertEquals(listOf("give", "Steve", "diamond", "3"), commandWords("/minecraft:GIVE Steve diamond 3"))
        assertEquals(emptyList<String>(), commandWords("  "))
        // Only what runs counts: the give at the end of an execute chain, however deep.
        assertEquals(
            listOf("give", "@s", "diamond"),
            commandWords("execute as @a at @s run execute if entity @s run minecraft:give @s diamond"),
        )
        assertTrue(executed("/minecraft:execute as Steve run clear @s"))
    }

    @Test
    fun `give, clear and enchant name their players and their reasons`() {
        val give = commandUse(commandWords("give @a diamond"))!!
        assertEquals("@a", give.targets)
        assertEquals(Cause.CMD_GIVE, give.gain)
        assertNull(give.loss)
        // No target: the one who ran it.
        assertNull(commandUse(commandWords("clear"))!!.targets)
        assertEquals(Cause.CMD_CLEAR, commandUse(commandWords("clear Steve"))!!.loss)
        assertEquals(Cause.CMD_ENCHANT, commandUse(commandWords("enchant Steve sharpness"))!!.change)
    }

    @Test
    fun `only the item command that reaches into an entity names a player`() {
        val replace = commandUse(commandWords("item replace entity Steve weapon.mainhand with stone"))!!
        assertEquals("Steve", replace.targets)
        assertEquals(Cause.CMD_ITEM_REPLACE, replace.gain)
        assertEquals(Cause.CMD_ITEM_REPLACE, replace.loss)
        assertNull(commandUse(commandWords("item replace block 0 64 0 container.0 with stone")))
        assertNull(commandUse(commandWords("loot spawn 0 64 0 loot minecraft:chests/simple_dungeon")))
        assertNull(commandUse(commandWords("tp Steve 0 64 0")))
    }
}
