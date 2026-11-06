package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.key
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Void
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

// A bundle gets its name the first time the pass sees something in it, and the name is part of its
// form. The name must not read as the bundle going and another arriving, and what was put into it in
// the same click must still land in it.
class BundleNamingTest {
    private val player = UUID.randomUUID()
    private val owner = UUID.randomUUID()

    private val unnamed = key("bundle")
    private val named = key("bundle#owner")
    private val gold = key("gold_ingot")

    @Test
    fun `gold put into a bundle that got its name in the same pass lands in it`() {
        val slot = PlayerInv(player, 0)
        val cursor = PlayerCursor(player)
        val inside = Nested(owner, 0)
        val before = Snapshot(mapOf(slot to Stack(unnamed, 1), cursor to Stack(gold, 20)))
        val after = Snapshot(
            mapOf(slot to Stack(named, 1), inside to Stack(gold, 20)),
            setOf(owner),
            mapOf(slot to Naming(unnamed, owner)),
        )

        val (was, now) = comparable(before, after)
        val edge = Netting.diff(was, now, player = player).single()

        assertEquals(cursor, edge.from)
        assertEquals(inside, edge.to)
        assertEquals(20, edge.qty)

        val naming = namingRows(after, 0L).single()
        assertEquals(listOf(slot to Void, Void to slot), naming.map { it.from to it.to })
        assertTrue(naming.all { it.cause == Cause.CONTAINER_NAMED && it.kind == Kind.MUTATE })
        assertTrue(naming[0].form.contentEquals(unnamed.form) && naming[1].form.contentEquals(named.form))
    }

    // A box that arrives already full (given, picked up, filled before anything watched) gets its name in
    // the pass that first sees it. What it held goes on record then, or a rollback that gives it back has
    // nothing to fill it with; a bundle that sat empty in its slot is the pass's own to compare.
    @Test
    fun `what a container held when it first came into view is written as arriving in it`() {
        val slot = PlayerInv(player, 4)
        val inside = Nested(owner, 3)
        val after = Snapshot(mapOf(slot to Stack(named, 1), inside to Stack(gold, 5)), setOf(owner), mapOf(slot to Naming(unnamed, owner)))
        val elsewhere = Nested(UUID.randomUUID(), 0)
        val arrivals = { before: Snapshot? ->
            firstSightRows(before, Snapshot(after.stacks + (elsewhere to Stack(gold, 9)), after.containers, after.named), 0L)
                .map { Triple(it.from, it.to, it.qty) to it.cause }
        }

        assertEquals(listOf(Triple(Void, inside, 5) to Cause.CONTAINER_NAMED), arrivals(Snapshot(emptyMap())))
        assertEquals(listOf(Triple(Void, inside, 5) to Cause.CONTAINER_NAMED), arrivals(null))
        assertTrue(arrivals(Snapshot(mapOf(slot to Stack(unnamed, 1)))).isEmpty())
        // Kept out of the pass's pairing: gold lost from the cursor in the same pass is not what the box brought.
        val (was, now) = comparable(Snapshot(mapOf(PlayerCursor(player) to Stack(gold, 5))), after)
        assertTrue(Netting.diff(was, now, player = player).none { it.to is Nested })
    }
}
