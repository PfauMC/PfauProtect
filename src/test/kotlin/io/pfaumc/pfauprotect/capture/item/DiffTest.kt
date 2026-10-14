package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.world
import io.pfaumc.pfauprotect.key
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
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

    private fun stack(name: String, count: Int, damage: Int? = null) = Stack(key(name, damage), count)
    private fun chest(slot: Int) = Container(world, 10, 64, -3, slot)
    private fun edge(from: Holder, to: Holder) = Edge(from, to, key("stone"), 1, Confidence.FACT)
    private fun bag(slot: Int) = PlayerInv(alice, slot)
    private val cursor = PlayerCursor(alice)

    private fun seen(vararg rows: Pair<Holder, Stack>, containers: Set<UUID> = emptySet()) =
        Snapshot(mapOf(*rows), containers)

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
    fun `a worn tool that vanishes carries the wear it left with`() {
        val edges = Netting.diff(seen(bag(0) to stack("pickaxe", 1, damage = 3)), seen())
        assertEquals(listOf(Edge(bag(0), Void, key("pickaxe", 3), 1, Confidence.INFERRED)), edges)
    }

    @Test
    fun `the cause of an edge follows the ends it connects`() {
        val ender = PlayerEnder(alice, 0)
        val mob = EntitySlot(UUID.randomUUID(), 0)
        val inside = Nested(UUID.randomUUID(), 0)
        val cases = listOf(
            edge(bag(0), ender) to Cause.CONTAINER_ADD,
            edge(mob, bag(0)) to Cause.CONTAINER_REMOVE,
            edge(cursor, bag(0)) to Cause.CURSOR_PLACE,
            edge(bag(0), cursor) to Cause.CURSOR_TAKE,
            edge(bag(0), bag(1)) to Cause.QUICK_MOVE,
            // A container's side names the click, not the cursor's.
            edge(chest(0), cursor) to Cause.CONTAINER_REMOVE,
            edge(cursor, mob) to Cause.CONTAINER_ADD,
            // Going into or out of a bundle wins over the container on the other end.
            edge(chest(0), inside) to Cause.BUNDLE_INSERT,
            edge(inside, chest(0)) to Cause.BUNDLE_EXTRACT,
        )
        for ((edge, cause) in cases) assertEquals(cause, causeOf(edge), "${edge.from} -> ${edge.to}")
    }

    // Unclaimed, the loss would pair with the picked-up gain and leave the gain in bag 1 out of nowhere.
    @Test
    fun `intents claim their share before the rest is paired loss to gain`() {
        val entity = ItemEntityRef(UUID.randomUUID())
        val placed = WorldBlock(world, 10, 65, -3)
        val intents = listOf(
            Intent(Cause.BLOCK_PLACE, to = placed, qty = 1),
            Intent(Cause.PICKUP, from = entity, qty = 3, holder = bag(9)),
        )
        val edges = Netting.diff(
            seen(bag(0) to stack("cobble", 5)),
            seen(bag(0) to stack("cobble", 1), bag(1) to stack("cobble", 3), bag(9) to stack("cobble", 3)),
            intents,
            alice,
        )
        assertEquals(
            setOf(
                Move(bag(0), placed, key("cobble"), 1, Cause.BLOCK_PLACE, Confidence.FACT),
                Move(bag(0), bag(1), key("cobble"), 3, Cause.QUICK_MOVE, Confidence.FACT),
                Move(entity, bag(9), key("cobble"), 3, Cause.PICKUP, Confidence.FACT),
            ),
            Intents.explain(edges, intents, alice).toSet(),
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

        val edges = Netting.diff(before, after, listOf(drop), alice, carried = carried)

        assertEquals(
            setOf(
                Move(Nested(bundle, 0), dropped, key("gold"), 5, Cause.BUNDLE_DUMP, Confidence.FACT),
                Move(Void, bag(3), key("gold"), 5, Cause.DIRECT_NEW_ITEM, Confidence.INFERRED),
            ),
            Intents.explain(edges, listOf(drop), alice, carried = carried).toSet(),
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

    // Two stacks of six and four moved whole into two other slots: paired in the order they were read,
    // the six would be split across the four and the six, and one move written as two.
    @Test
    fun `an unseen move of whole stacks pairs the largest first`() {
        val diamond = FormKey("diamond".toByteArray())
        val rows = loadDifferences(
            mapOf(bag(0) to mapOf(diamond to 4), bag(1) to mapOf(diamond to 6)),
            mapOf(bag(2) to mapOf(diamond to 6), bag(3) to mapOf(diamond to 4)),
            0L,
        ).map { Triple(it.from, it.to, it.qty) }
        assertEquals(2, rows.size)
        assertEquals(setOf(Triple(bag(2), bag(1), 6), Triple(bag(3), bag(0), 4)), rows.toSet())
    }

    @Test
    fun `an unseen change brings every slot to what it holds and pairs what it can`() {
        val random = Random(11)
        val forms = listOf("diamond", "stone").map { FormKey(it.toByteArray()) }
        val slots: List<Holder> = (0..4).map { bag(it) }
        fun reading() = slots.filter { random.nextBoolean() }.associateWith { _ ->
            forms.filter { random.nextBoolean() }.associateWith { random.nextInt(0, 9) }
        }

        repeat(200) { round ->
            val live = reading()
            val booked = reading()
            val rows = loadDifferences(live, booked, 0L)
            val context = "round $round: live $live, booked $booked"

            assertTrue(rows.all { it.qty > 0 && it.cause == Cause.INVENTORY_LOAD && it.confidence == Confidence.INFERRED }, context)
            for (form in forms) {
                val mine = rows.filter { FormKey(it.form) == form }
                for (slot in slots) {
                    val gap = (live[slot]?.get(form) ?: 0) - (booked[slot]?.get(form) ?: 0)
                    val net = mine.filter { it.to == slot }.sumOf { it.qty } - mine.filter { it.from == slot }.sumOf { it.qty }
                    assertEquals(gap, net, "$slot, $context")
                }
                // A slot short and another over is a move: only one side of a form may face the Void.
                assertTrue(mine.none { it.from == Void } || mine.none { it.to == Void }, context)
            }
        }
    }

    // A death that already wrote its row out of bag(1) is the only reason that can speak for bag(1); a
    // drop that could have come from either slot must leave it to the death, or bag(0)'s loss is married
    // to an unrelated gain and the drop is left with nothing to explain.
    @Test
    fun `an intent pinned to its slot and already written claims that slot before a free one`() {
        val stone = "stone".toByteArray()
        val pile = ItemEntityRef(UUID.randomUUID())
        val intents = listOf(
            Intent(Cause.DROP_FROM_HAND, to = pile, form = stone, qty = 1),
            Intent(Cause.DEATH_DROP, form = stone, qty = 1, recorded = true, holder = bag(1)),
        )
        val edges = Netting.diff(
            seen(bag(1) to stack("stone", 1), bag(0) to stack("stone", 1)),
            seen(bag(5) to stack("stone", 1)),
            intents,
            alice,
        )

        assertEquals(
            setOf(
                Move(bag(0), pile, key("stone"), 1, Cause.DROP_FROM_HAND, Confidence.FACT),
                Move(Void, bag(5), key("stone"), 1, Cause.DIRECT_NEW_ITEM, Confidence.INFERRED),
            ),
            Intents.explain(edges, intents, alice).toSet(),
        )
    }

    @Test
    fun `every edge and every move is positive and nets each slot to what the snapshots say it gained`() {
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

            val edges = Netting.diff(before, after, intents, alice, unpaired)

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
            fun net(rows: List<Triple<Holder, Holder, Pair<String, Int>>>): Map<Pair<Holder, String>, Int> {
                val netted = HashMap<Pair<Holder, String>, Int>()
                for ((from, to, moved) in rows) {
                    if (to in holders) netted.merge(to to moved.first, moved.second, Int::plus)
                    if (from in holders) netted.merge(from to moved.first, -moved.second, Int::plus)
                }
                return netted.filterValues { it != 0 }
            }
            val moves = Intents.explain(edges, intents, alice)

            val context = "round $round: $edges"
            assertTrue(edges.all { it.qty > 0 }, context)
            assertEquals(expected.filterValues { it != 0 }, net(edges.map { Triple(it.from, it.to, String(it.key.form) to it.qty) }), context)
            // Splitting an edge between intents and naming its far end must leave every slot's balance as it was.
            assertTrue(moves.all { it.qty > 0 }, "$moves, $context")
            assertEquals(expected.filterValues { it != 0 }, net(moves.map { Triple(it.from, it.to, String(it.key.form) to it.qty) }), "$moves, $context")

            // Only an end nothing named is a guess: a pair was seen on both sides, an intent named the far end.
            assertTrue(edges.all { (it.confidence == Confidence.INFERRED) == (it.from == Void || it.to == Void) }, context)
            val unexplained = setOf(Cause.ITEM_VANISHED, Cause.DIRECT_NEW_ITEM)
            assertTrue(moves.all { (it.confidence == Confidence.INFERRED) == (it.cause in unexplained) }, "$moves, $context")

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
