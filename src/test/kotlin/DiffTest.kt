package io.pfaumc.pfauprotect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class DiffTest {
    private val world = UUID.randomUUID()
    private val player = UUID.randomUUID()

    private fun key(name: String, damage: Int? = null) = ItemKey(name.toByteArray(), damage)
    private fun stack(name: String, count: Int, damage: Int? = null) = Stack(key(name, damage), count)
    private fun chest(slot: Int) = Container(world, 10, 64, -3, slot)
    private fun edge(from: Holder, to: Holder) = Edge(from, to, key("stone"), 1, Confidence.FACT)
    private fun bag(slot: Int) = PlayerInv(player, slot)
    private val cursor = PlayerCursor(player)

    @Test
    fun `a whole stack moves as one edge`() {
        val edges = Netting.diff(
            mapOf(chest(0) to stack("stone", 64)),
            mapOf(bag(0) to stack("stone", 64)),
        )
        assertEquals(listOf(Edge(chest(0), bag(0), key("stone"), 64, Confidence.FACT)), edges)
    }

    @Test
    fun `a partial stack moves only the taken amount`() {
        val edges = Netting.diff(
            mapOf(chest(0) to stack("stone", 64)),
            mapOf(chest(0) to stack("stone", 40), bag(0) to stack("stone", 24)),
        )
        assertEquals(listOf(Edge(chest(0), bag(0), key("stone"), 24, Confidence.FACT)), edges)
    }

    @Test
    fun `a cursor swap trades both stacks`() {
        val edges = Netting.diff(
            mapOf(cursor to stack("stone", 1), chest(0) to stack("dirt", 1)),
            mapOf(cursor to stack("dirt", 1), chest(0) to stack("stone", 1)),
        )
        assertEquals(
            setOf(
                Edge(cursor, chest(0), key("stone"), 1, Confidence.FACT),
                Edge(chest(0), cursor, key("dirt"), 1, Confidence.FACT),
            ),
            edges.toSet(),
        )
        assertEquals(2, edges.size)
    }

    @Test
    fun `a slot left holding a zero counter is empty`() {
        val edges = Netting.diff(
            mapOf(chest(0) to stack("stone", 64), bag(0) to stack("dirt", 1)),
            mapOf(bag(0) to stack("stone", 0), bag(1) to stack("dirt", 1)),
        )
        assertEquals(
            setOf(
                Edge(chest(0), Void, key("stone"), 64, Confidence.INFERRED),
                Edge(bag(0), bag(1), key("dirt"), 1, Confidence.FACT),
            ),
            edges.toSet(),
        )
        assertEquals(2, edges.size)
        assertTrue(edges.all { it.qty > 0 })
    }

    @Test
    fun `an unchanged snapshot yields no edges`() {
        val snapshot = mapOf(chest(0) to stack("stone", 64), bag(0) to stack("dirt", 3))
        assertEquals(emptyList<Edge>(), Netting.diff(snapshot, snapshot))
    }

    @Test
    fun `damage is part of the item key`() {
        val edges = Netting.diff(
            mapOf(bag(0) to stack("pickaxe", 1, damage = 3)),
            mapOf(bag(1) to stack("pickaxe", 1, damage = 4)),
        )
        assertEquals(
            setOf(
                Edge(bag(0), Void, key("pickaxe", 3), 1, Confidence.INFERRED),
                Edge(Void, bag(1), key("pickaxe", 4), 1, Confidence.INFERRED),
            ),
            edges.toSet(),
        )
    }

    @Test
    fun `an unpaired loss goes to the void as inferred`() {
        val vanished = Netting.diff(
            mapOf(bag(0) to stack("stone", 10)),
            emptyMap(),
        )
        assertEquals(listOf(Edge(bag(0), Void, key("stone"), 10, Confidence.INFERRED)), vanished)

        val appeared = Netting.diff(
            emptyMap(),
            mapOf(bag(0) to stack("stone", 4)),
        )
        assertEquals(listOf(Edge(Void, bag(0), key("stone"), 4, Confidence.INFERRED)), appeared)
    }

    @Test
    fun `the cause of an edge follows the ends it connects`() {
        val ender = PlayerEnder(player, 0)
        assertEquals(Cause.CONTAINER_ADD, causeOf(edge(bag(0), chest(0))))
        assertEquals(Cause.CONTAINER_ADD, causeOf(edge(bag(0), ender)))
        assertEquals(Cause.CONTAINER_REMOVE, causeOf(edge(chest(0), bag(0))))
        assertEquals(Cause.CONTAINER_REMOVE, causeOf(edge(ender, bag(0))))
        assertEquals(Cause.CURSOR_PLACE, causeOf(edge(cursor, bag(0))))
        assertEquals(Cause.CURSOR_TAKE, causeOf(edge(bag(0), cursor)))
        assertEquals(Cause.QUICK_MOVE, causeOf(edge(bag(0), bag(1))))
    }

    @Test
    fun `a quick move spread over several slots nets into real edges`() {
        val edges = Netting.diff(
            mapOf(
                chest(0) to stack("stone", 64),
                bag(0) to stack("stone", 20),
                bag(1) to stack("stone", 30),
            ),
            mapOf(
                chest(0) to stack("stone", 0),
                bag(0) to stack("stone", 64),
                bag(1) to stack("stone", 50),
            ),
        )
        assertEquals(
            setOf(
                Edge(chest(0), bag(0), key("stone"), 44, Confidence.FACT),
                Edge(chest(0), bag(1), key("stone"), 20, Confidence.FACT),
            ),
            edges.toSet(),
        )
        assertEquals(2, edges.size)
        assertEquals(64, edges.sumOf { it.qty })
    }
}
