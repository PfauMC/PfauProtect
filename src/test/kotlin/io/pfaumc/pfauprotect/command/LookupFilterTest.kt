package io.pfaumc.pfauprotect.command
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.LedgerEntry
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

    private fun entry(holder: Holder, counterparty: Holder, actor: UUID? = null) = LedgerEntry(
        holder = holder,
        timestamp = 0,
        txId = 1,
        kind = Kind.TRANSFER,
        cause = Cause.BLOCK_DROP,
        confidence = Confidence.FACT,
        counterparty = counterparty,
        itemFormId = 1,
        qty = -1,
        damage = null,
        actor = actor,
    )

    // Breaking a block writes the block losing what it was made of, and the person who swung is in
    // the actor column and nowhere else. Read from the two ends alone, asking after a player hides
    // exactly the rows an investigation of them is about.
    @Test
    fun `a row naming the player only as the actor belongs to that player`() {
        val broken = entry(WorldBlock(world, 4, 70, 8), Void, actor = steve)
        assertTrue(namesUser(broken, setOf(steve)))
        assertFalse(namesUser(broken, setOf(alex)))
    }

    @Test
    fun `either end of a movement names the player standing there`() {
        assertTrue(namesUser(entry(PlayerInv(steve, 0), chest), setOf(steve)))
        assertTrue(namesUser(entry(chest, PlayerInv(steve, 0)), setOf(steve)))
        assertFalse(namesUser(entry(chest, Void), setOf(steve)))
    }

    // Nobody named is nobody excluded: the exclusion runs over every row and must not swallow the
    // rows that name no player at all.
    @Test
    fun `a row naming nobody is named by nobody`() {
        assertFalse(namesUser(entry(chest, Void), emptySet()))
        assertFalse(namesUser(entry(PlayerInv(steve, 0), chest), emptySet()))
    }
}
