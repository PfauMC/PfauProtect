package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import io.pfaumc.pfauprotect.model.Transfer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

// Which command reaches into whose slots, and with which reasons. Plain words in, plain reasons out.
class CommandsTest {
    @Test
    fun `a command is read the way the server reads it`() {
        assertEquals(listOf("give", "Steve", "diamond", "3"), commandWords("/minecraft:GIVE Steve diamond 3"))
        assertEquals(emptyList<String>(), commandWords("  "))
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

    // Nothing left the inventory for a drop out of the creative menu: the drop made the item, and that
    // is known rather than guessed.
    @Test
    fun `a drop nothing paid for is creative's own making`() {
        val player = UUID.randomUUID()
        val entity = ItemEntityRef(UUID.randomUUID())
        val written = ArrayList<Transfer>()
        val pending = TickCoalescer { written += it }
        val drop = Intent(Cause.DROP_FROM_HAND, to = entity, form = "stone".toByteArray(), qty = 64, actor = player)

        unspentDrop(pending, drop, 64) { it == player }
        pending.flush()

        val row = written.single()
        assertEquals(Cause.CREATIVE_SET, row.cause)
        assertEquals(Confidence.FACT, row.confidence)
    }
}
