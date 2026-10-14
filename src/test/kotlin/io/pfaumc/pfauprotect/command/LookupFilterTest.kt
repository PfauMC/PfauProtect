package io.pfaumc.pfauprotect.command
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.ledgerEntry
import io.pfaumc.pfauprotect.world
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LookupFilterTest {
    private val chest = Container(world, 4, 70, 8, 0)

    @Test
    fun `a row naming the player only as the actor belongs to that player`() {
        val broken = ledgerEntry(WorldBlock(world, 4, 70, 8), Void, actor = alice)
        assertTrue(namesUser(broken, setOf(alice)))
        assertFalse(namesUser(broken, setOf(bob)))
    }

    @Test
    fun `either end of a movement names the player standing there`() {
        assertTrue(namesUser(ledgerEntry(PlayerInv(alice, 0), chest), setOf(alice)))
        assertTrue(namesUser(ledgerEntry(chest, PlayerInv(alice, 0)), setOf(alice)))
        assertFalse(namesUser(ledgerEntry(chest, Void), setOf(alice)))
    }
}
