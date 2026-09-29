package io.pfaumc.pfauprotect

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

    private fun key(name: String) = ItemKey(name.toByteArray(), null)
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

    // A bundle emptied with a right click drops its contents as the player's own drops, and those come
    // out of the bundle rather than out of a slot.
    @Test
    fun `a bundle emptied from the hand is the player's drop out of the bundle`() {
        val inside = Nested(owner, 0)
        val entity = ItemEntityRef(UUID.randomUUID())
        val edges = listOf(Edge(inside, Void, gold, 20, Confidence.INFERRED))
        val drop = Intent(Cause.DROP_FROM_HAND, to = entity, form = gold.form, qty = 20)

        val move = Intents.explain(edges, listOf(drop), player, carried = { it.ownerId == owner }).single()

        assertEquals(inside, move.from)
        assertEquals(entity, move.to)
        assertEquals(Cause.BUNDLE_DUMP, move.cause)
        assertEquals(Confidence.FACT, move.confidence)
    }
}
