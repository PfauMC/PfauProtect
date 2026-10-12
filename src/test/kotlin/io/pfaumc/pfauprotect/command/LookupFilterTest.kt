package io.pfaumc.pfauprotect.command
import io.pfaumc.pfauprotect.ledgerEntry
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LookupFilterTest {
    private val steve = UUID.randomUUID()
    private val alex = UUID.randomUUID()
    private val world = UUID.randomUUID()
    private val chest = Container(world, 4, 70, 8, 0)

    @Test
    fun `a row naming the player only as the actor belongs to that player`() {
        val broken = ledgerEntry(WorldBlock(world, 4, 70, 8), Void, actor = steve)
        assertTrue(namesUser(broken, setOf(steve)))
        assertFalse(namesUser(broken, setOf(alex)))
    }

    @Test
    fun `either end of a movement names the player standing there`() {
        assertTrue(namesUser(ledgerEntry(PlayerInv(steve, 0), chest), setOf(steve)))
        assertTrue(namesUser(ledgerEntry(chest, PlayerInv(steve, 0)), setOf(steve)))
        assertFalse(namesUser(ledgerEntry(chest, Void), setOf(steve)))
    }

    // Nobody named is nobody excluded: the exclusion runs over every row and must not swallow the
    // rows that name no player at all.
    @Test
    fun `a row naming nobody is named by nobody`() {
        assertFalse(namesUser(ledgerEntry(chest, Void), emptySet()))
        assertFalse(namesUser(ledgerEntry(PlayerInv(steve, 0), chest), emptySet()))
    }
}
