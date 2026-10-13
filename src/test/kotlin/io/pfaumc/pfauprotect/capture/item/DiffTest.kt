package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.key
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.FormKey
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerEnder
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

class DiffTest {
    private val world = UUID.randomUUID()
    private val player = UUID.randomUUID()

    private fun stack(name: String, count: Int, damage: Int? = null) = Stack(key(name, damage), count)
    private fun chest(slot: Int) = Container(world, 10, 64, -3, slot)
    private fun edge(from: Holder, to: Holder) = Edge(from, to, key("stone"), 1, Confidence.FACT)
    private fun bag(slot: Int) = PlayerInv(player, slot)
    private val cursor = PlayerCursor(player)

    private fun seen(vararg rows: Pair<Holder, Stack>, containers: Set<UUID> = emptySet()) =
        Snapshot(mapOf(*rows), containers)

    @Test
    fun `a whole stack moves as one edge`() {
        val edges = Netting.diff(
            seen(chest(0) to stack("stone", 64)),
            seen(bag(0) to stack("stone", 64)),
        )
        assertEquals(listOf(Edge(chest(0), bag(0), key("stone"), 64, Confidence.FACT)), edges)
    }

    @Test
    fun `a partial stack moves only the taken amount`() {
        val edges = Netting.diff(
            seen(chest(0) to stack("stone", 64)),
            seen(chest(0) to stack("stone", 40), bag(0) to stack("stone", 24)),
        )
        assertEquals(listOf(Edge(chest(0), bag(0), key("stone"), 24, Confidence.FACT)), edges)
    }

    @Test
    fun `wearing a tool down in place moves nothing`() {
        val edges = Netting.diff(
            seen(bag(0) to stack("pickaxe", 1, damage = 3)),
            seen(bag(0) to stack("pickaxe", 1, damage = 4)),
        )
        assertEquals(emptyList<Edge>(), edges)
    }

    @Test
    fun `a worn tool that moves is one edge carrying the wear it arrived with`() {
        val edges = Netting.diff(
            seen(bag(0) to stack("pickaxe", 1, damage = 3)),
            seen(bag(1) to stack("pickaxe", 1, damage = 4)),
        )
        assertEquals(listOf(Edge(bag(0), bag(1), key("pickaxe", 4), 1, Confidence.FACT)), edges)
    }

    // The price of leaving durability out of identity, named rather than discovered later: two tools
    // of one form trading places cancel each other and leave no trace of the swap.
    @Test
    fun `two tools of one form swapping places cancel out`() {
        val edges = Netting.diff(
            seen(bag(0) to stack("pickaxe", 1, damage = 3), bag(1) to stack("pickaxe", 1, damage = 900)),
            seen(bag(0) to stack("pickaxe", 1, damage = 900), bag(1) to stack("pickaxe", 1, damage = 3)),
        )
        assertEquals(emptyList<Edge>(), edges)
    }

    @Test
    fun `an unpaired loss goes to the void as inferred`() {
        val vanished = Netting.diff(
            seen(bag(0) to stack("pickaxe", 1, damage = 3)),
            seen(),
        )
        assertEquals(listOf(Edge(bag(0), Void, key("pickaxe", 3), 1, Confidence.INFERRED)), vanished)

        val appeared = Netting.diff(
            seen(),
            seen(bag(0) to stack("stone", 4)),
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
        val inside = Nested(UUID.randomUUID(), 0)
        assertEquals(Cause.BUNDLE_INSERT, causeOf(edge(bag(0), inside)))
        assertEquals(Cause.BUNDLE_EXTRACT, causeOf(edge(inside, chest(0))))
    }

    @Test
    fun `an intent claims its share before an unrelated gain can be married to the loss`() {
        val entity = ItemEntityRef(UUID.randomUUID())
        val placed = WorldBlock(world, 10, 65, -3)
        val intents = listOf(
            Intent(Cause.BLOCK_PLACE, to = placed, qty = 1),
            Intent(Cause.PICKUP, from = entity, qty = 3),
        )
        val edges = Netting.diff(
            seen(bag(0) to stack("cobble", 5)),
            seen(bag(0) to stack("cobble", 4), bag(9) to stack("cobble", 3)),
            intents,
            player,
        )
        assertEquals(
            listOf(
                Move(bag(0), placed, key("cobble"), 1, Cause.BLOCK_PLACE, Confidence.FACT),
                Move(entity, bag(9), key("cobble"), 3, Cause.PICKUP, Confidence.FACT),
            ),
            Intents.explain(edges, intents, player),
        )
    }

    // A bundle emptied with a right click drops its contents as the player's own drops, and those come
    // out of the bundle rather than out of a slot; an unrelated gain of the same form must not take them.
    @Test
    fun `a drop out of a carried bundle is claimed before an unrelated gain can take it`() {
        val bundle = UUID.randomUUID()
        val dropped = ItemEntityRef(UUID.randomUUID())
        val drop = Intent(Cause.DROP_FROM_HAND, to = dropped, form = key("gold").form, qty = 5)
        val before = seen(bag(0) to stack("bundle", 1), Nested(bundle, 0) to stack("gold", 5), containers = setOf(bundle))
        val after = seen(bag(0) to stack("bundle", 1), bag(3) to stack("gold", 5), containers = setOf(bundle))
        val carried = before::sees

        val edges = Netting.diff(before, after, listOf(drop), player, carried = carried)

        assertEquals(
            setOf(
                Move(Nested(bundle, 0), dropped, key("gold"), 5, Cause.BUNDLE_DUMP, Confidence.FACT),
                Move(Void, bag(3), key("gold"), 5, Cause.DIRECT_NEW_ITEM, Confidence.INFERRED),
            ),
            Intents.explain(edges, listOf(drop), player, carried = carried).toSet(),
        )
    }

    @Test
    fun `what no intent claimed is still paired loss to gain`() {
        val placed = WorldBlock(world, 10, 65, -3)
        val intents = listOf(Intent(Cause.BLOCK_PLACE, to = placed, qty = 1))
        val edges = Netting.diff(
            seen(bag(0) to stack("cobble", 5)),
            seen(bag(0) to stack("cobble", 1), bag(1) to stack("cobble", 3)),
            intents,
            player,
        )
        assertEquals(
            setOf(
                Edge(bag(0), bag(1), key("cobble"), 3, Confidence.FACT),
                Edge(bag(0), Void, key("cobble"), 1, Confidence.INFERRED),
            ),
            edges.toSet(),
        )
    }

    @Test
    fun `a bundle that stays in view still moves what goes in and out of it`() {
        val bundle = UUID.randomUUID()
        val empty = seen(
            bag(0) to stack("bundle", 1),
            bag(1) to stack("stone", 5),
            containers = setOf(bundle),
        )
        val filled = seen(
            bag(0) to stack("bundle", 1),
            Nested(bundle, 0) to stack("stone", 5),
            containers = setOf(bundle),
        )
        assertEquals(
            listOf(Edge(bag(1), Nested(bundle, 0), key("stone"), 5, Confidence.FACT)),
            Netting.diff(empty, filled),
        )
        assertEquals(
            listOf(Edge(Nested(bundle, 0), bag(1), key("stone"), 5, Confidence.FACT)),
            Netting.diff(filled, empty),
        )
    }

    @Test
    fun `an unseen change is written where it shows and a move as a move`() {
        val diamond = FormKey("diamond".toByteArray())
        val slot0 = PlayerInv(player, 0)
        val slot3 = PlayerInv(player, 3)
        val slot7 = PlayerInv(player, 7)
        fun rows(live: Map<Holder, Map<FormKey, Int>>, booked: Map<Holder, Map<FormKey, Int>>) =
            loadDifferences(live, booked, 0L).map { Triple(it.from, it.to, it.qty) }

        assertEquals(listOf(Triple(slot0, slot7, 5)), rows(mapOf(slot7 to mapOf(diamond to 5)), mapOf(slot0 to mapOf(diamond to 5))))
        assertEquals(emptyList<Triple<Holder, Holder, Int>>(), rows(mapOf(slot0 to mapOf(diamond to 5)), mapOf(slot0 to mapOf(diamond to 5))))

        assertEquals(
            listOf(Triple(Void, slot7, 2)),
            rows(mapOf(slot0 to mapOf(diamond to 5), slot7 to mapOf(diamond to 2)), mapOf(slot0 to mapOf(diamond to 5))),
        )
        assertEquals(
            setOf(Triple(slot0, slot7, 4), Triple(slot0, Void, 1)),
            rows(mapOf(slot7 to mapOf(diamond to 4)), mapOf(slot0 to mapOf(diamond to 5))).toSet(),
        )
        // Ten and five taken out of two slots and booked out of a third, as the copper golem's were once:
        // two slots hold less than the ledger says, the third more by as much.
        assertEquals(
            listOf(Triple(slot3, slot0, 10), Triple(slot7, slot0, 5)),
            rows(mapOf(slot0 to mapOf(diamond to 15)), mapOf(slot3 to mapOf(diamond to 10), slot7 to mapOf(diamond to 5))),
        )
    }

    @Test
    fun `every edge is positive and nets each slot to what the snapshots say it gained`() {
        val random = Random(7)
        val forms = listOf("stone", "dirt", "pickaxe")
        val boxes = List(2) { UUID.randomUUID() }
        val holders = (0..3).map { bag(it) } + (0..2).map { chest(it) } + cursor +
            boxes.flatMap { box -> (0..1).map { Nested(box, it) } }
        val ground = ItemEntityRef(UUID.randomUUID())
        val placed = WorldBlock(world, 10, 65, -3)

        fun snapshot() = Snapshot(
            holders.filter { random.nextInt(3) > 0 }.associateWith {
                Stack(key(forms.random(random), random.nextInt(4).takeIf { it > 0 }), random.nextInt(-1, 6))
            },
            boxes.filter { random.nextBoolean() }.toSet(),
        )

        fun intent(): Intent {
            val form = forms.random(random).toByteArray().takeIf { random.nextBoolean() }
            val qty = random.nextInt(1, 6).takeIf { random.nextBoolean() }
            return if (random.nextBoolean()) Intent(Cause.PICKUP, from = ground, form = form, qty = qty)
            else Intent(Cause.BLOCK_PLACE, to = placed, form = form, qty = qty)
        }

        repeat(300) { round ->
            val before = snapshot()
            val after = snapshot()
            // A box out of view on either side carries its contents across unmoved, whether it is
            // leaving the view or coming back into it.
            val inView = before.containers intersect after.containers
            val intents = List(random.nextInt(3)) { intent() }
            val unpaired = holders.filter { random.nextInt(5) == 0 }.toSet()

            val edges = Netting.diff(before, after, intents, player, unpaired)

            val expected = HashMap<Pair<Holder, String>, Int>()
            fun count(snapshot: Snapshot, sign: Int) {
                for ((holder, stack) in snapshot.stacks) {
                    if (holder is Nested && holder.ownerId !in inView) continue
                    if (stack.count <= 0) continue
                    expected.merge(holder to String(stack.key.form), sign * stack.count, Int::plus)
                }
            }
            count(after, 1)
            count(before, -1)
            val netted = HashMap<Pair<Holder, String>, Int>()
            for (edge in edges) {
                if (edge.to != Void) netted.merge(edge.to to String(edge.key.form), edge.qty, Int::plus)
                if (edge.from != Void) netted.merge(edge.from to String(edge.key.form), -edge.qty, Int::plus)
            }

            val context = "round $round: $edges"
            assertTrue(edges.all { it.qty > 0 }, context)
            assertEquals(expected.filterValues { it != 0 }, netted.filterValues { it != 0 }, context)

            val paired = edges.filter { it.from != Void && it.to != Void }
            assertTrue(paired.none { it.from in unpaired || it.to in unpaired }, "unpaired $unpaired, $context")
            // Intents claim deltas before pairing, so only a round without them must pair everything it can.
            if (intents.isEmpty()) {
                val lostForms = edges.filter { it.to == Void && it.from !in unpaired }.map { String(it.key.form) }.toSet()
                val gainedForms = edges.filter { it.from == Void && it.to !in unpaired }.map { String(it.key.form) }.toSet()
                assertEquals(emptySet<String>(), lostForms intersect gainedForms, "unpaired $unpaired, $context")
            }
        }
    }
}
