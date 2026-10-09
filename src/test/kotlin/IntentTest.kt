package io.pfaumc.pfauprotect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class IntentTest {
    private val world = UUID.randomUUID()
    private val player = UUID.randomUUID()
    private val ground = ItemEntityRef(UUID.randomUUID())
    private val placed = WorldBlock(world, 10, 65, -3)

    private fun key(name: String) = ItemKey(name.toByteArray(), null)
    private fun bag(slot: Int) = PlayerInv(player, slot)

    private fun lost(name: String, qty: Int, from: Holder = bag(0)) =
        Edge(from, Void, key(name), qty, Confidence.INFERRED)

    private fun gained(name: String, qty: Int, to: Holder = bag(0)) =
        Edge(Void, to, key(name), qty, Confidence.INFERRED)

    private fun carried(moves: List<Move>) = moves.sumOf { move ->
        (if (move.to is PlayerHolder) move.qty else 0) - (if (move.from is PlayerHolder) move.qty else 0)
    }

    @Test
    fun `an intent explains a loss the pass could not pair`() {
        val moves = Intents.explain(
            listOf(lost("stone", 1)),
            listOf(Intent(Cause.BLOCK_PLACE, to = placed, qty = 1)),
            player,
        )
        assertEquals(listOf(Move(bag(0), placed, key("stone"), 1, Cause.BLOCK_PLACE, Confidence.FACT)), moves)
    }

    @Test
    fun `an intent explains a gain the pass could not pair`() {
        val moves = Intents.explain(
            listOf(gained("cobblestone", 3)),
            listOf(Intent(Cause.PICKUP, from = ground, qty = 3, actor = player)),
            player,
        )
        assertEquals(
            listOf(Move(ground, bag(0), key("cobblestone"), 3, Cause.PICKUP, Confidence.FACT, player)),
            moves,
        )
    }

    @Test
    fun `a label renames a paired edge without moving its ends`() {
        val edges = listOf(
            Edge(bag(0), bag(1), key("stone"), 30, Confidence.FACT),
            Edge(bag(0), bag(2), key("stone"), 34, Confidence.FACT),
        )
        assertEquals(
            listOf(
                Move(bag(0), bag(1), key("stone"), 30, Cause.COLLECT_ALL, Confidence.FACT),
                Move(bag(0), bag(2), key("stone"), 34, Cause.COLLECT_ALL, Confidence.FACT),
            ),
            Intents.explain(edges, listOf(Intent(Cause.COLLECT_ALL)), player),
        )
        assertEquals(Cause.QUICK_MOVE, Intents.explain(edges, emptyList(), player).first().cause)
    }

    // A grid belongs to the player as an entity rather than to their inventory, so only the receiving
    // end is theirs; that is still a movement they made.
    @Test
    fun `a closed window labels what the grid hands back`() {
        val grid = EntitySlot(player, 1)
        val edges = listOf(Edge(grid, bag(5), key("diamond"), 9, Confidence.FACT))

        val moves = Intents.explain(edges, listOf(Intent(Cause.MENU_CLOSE_RETURN)), player)

        assertEquals(listOf(Move(grid, bag(5), key("diamond"), 9, Cause.MENU_CLOSE_RETURN, Confidence.FACT)), moves)
    }

    @Test
    fun `an intent nobody needed leaves no trace`() {
        val intents = listOf(Intent(Cause.BLOCK_PLACE, to = placed, qty = 1))
        assertEquals(emptyList<Move>(), Intents.explain(emptyList(), intents, player))
    }

    @Test
    fun `a remainder nothing explains stays inferred and faces the void`() {
        val moves =
            Intents.explain(listOf(lost("stone", 4)), listOf(Intent(Cause.PICKUP, from = ground)), player)
        assertEquals(listOf(Move(bag(0), Void, key("stone"), 4, Cause.QUICK_MOVE, Confidence.INFERRED)), moves)
    }

    @Test
    fun `an intent explains only the amount it names`() {
        val moves = Intents.explain(
            listOf(lost("stone", 5)),
            listOf(Intent(Cause.DROP_FROM_HAND, to = ground, qty = 2)),
            player,
        )
        assertEquals(
            listOf(
                Move(bag(0), ground, key("stone"), 2, Cause.DROP_FROM_HAND, Confidence.FACT),
                Move(bag(0), Void, key("stone"), 3, Cause.QUICK_MOVE, Confidence.INFERRED),
            ),
            moves,
        )
    }

    @Test
    fun `what one intent could not cover the next one explains`() {
        val moves = Intents.explain(
            listOf(lost("stone", 5)),
            listOf(Intent(Cause.DROP_FROM_HAND, to = ground, qty = 2), Intent(Cause.BLOCK_PLACE, to = placed)),
            player,
        )
        assertEquals(
            listOf(
                Move(bag(0), ground, key("stone"), 2, Cause.DROP_FROM_HAND, Confidence.FACT),
                Move(bag(0), placed, key("stone"), 3, Cause.BLOCK_PLACE, Confidence.FACT),
            ),
            moves,
        )
    }

    @Test
    fun `two intents in one pass explain one edge each`() {
        val other = ItemEntityRef(UUID.randomUUID())
        val moves = Intents.explain(
            listOf(lost("stone", 1), lost("stone", 1, from = bag(1))),
            listOf(
                Intent(Cause.DROP_FROM_HAND, to = ground, qty = 1),
                Intent(Cause.DROP_FROM_MENU, to = other, qty = 1),
            ),
            player,
        )
        assertEquals(
            listOf(
                Move(bag(0), ground, key("stone"), 1, Cause.DROP_FROM_HAND, Confidence.FACT),
                Move(bag(1), other, key("stone"), 1, Cause.DROP_FROM_MENU, Confidence.FACT),
            ),
            moves,
        )
    }

    @Test
    fun `an intent that names no amount keeps explaining`() {
        val moves = Intents.explain(
            listOf(lost("stone", 2), lost("dirt", 1, from = bag(1))),
            listOf(Intent(Cause.DROP_FROM_HAND, to = ground)),
            player,
        )
        assertEquals(2, moves.size)
        assertTrue(moves.all { it.to == ground && it.cause == Cause.DROP_FROM_HAND })
    }

    @Test
    fun `a movement already written is swallowed rather than written again`() {
        val written = Intent(Cause.DEATH_DROP, to = Void, qty = 3, recorded = true)
        assertEquals(emptyList<Move>(), Intents.explain(listOf(lost("stone", 3)), listOf(written), player))
        assertEquals(
            emptyList<Move>(),
            Intents.explain(listOf(lost("stone", 3)), listOf(Intent(Cause.DEATH_DROP, recorded = true)), player),
        )
    }

    @Test
    fun `a written movement is preferred over one that would explain the same loss`() {
        val moves = Intents.explain(
            listOf(lost("stone", 1)),
            listOf(
                Intent(Cause.DROP_FROM_HAND, to = ground, qty = 1),
                Intent(Cause.DEATH_DROP, to = Void, qty = 1, recorded = true),
            ),
            player,
        )
        assertEquals(emptyList<Move>(), moves)
    }

    @Test
    fun `a written movement swallows only the amount it was written for`() {
        val cursor = PlayerCursor(player)
        val moves = Intents.explain(
            listOf(lost("stone", 2), lost("stone", 1, from = cursor)),
            listOf(
                Intent(Cause.DEATH_DROP, qty = 2, recorded = true),
                Intent(Cause.DROP_FROM_HAND, to = ground, qty = 1),
            ),
            player,
        )
        assertEquals(
            listOf(Move(cursor, ground, key("stone"), 1, Cause.DROP_FROM_HAND, Confidence.FACT)),
            moves,
        )
    }

    @Test
    fun `an intent naming another form explains nothing`() {
        val moves = Intents.explain(
            listOf(lost("stone", 1)),
            listOf(Intent(Cause.BLOCK_PLACE, to = placed, form = "dirt".toByteArray(), qty = 1)),
            player,
        )
        assertEquals(listOf(Move(bag(0), Void, key("stone"), 1, Cause.QUICK_MOVE, Confidence.INFERRED)), moves)
    }

    // The open container is snapshotted before the player's own slots, so its unpaired loss is offered
    // every intent first. Left to match on form alone it takes the drop the player made out of their
    // own hand and the chest is written as the thrower.
    @Test
    fun `an intent cannot explain a loss from a holder that is not the player's`() {
        val chest = Container(world, 10, 64, -3, 0)
        val moves = Intents.explain(
            listOf(Edge(chest, Void, key("stone"), 1, Confidence.INFERRED), lost("stone", 1)),
            listOf(Intent(Cause.DROP_FROM_HAND, to = ground, qty = 1)),
            player,
        )
        assertEquals(
            listOf(
                Move(chest, Void, key("stone"), 1, Cause.CONTAINER_REMOVE, Confidence.INFERRED),
                Move(bag(0), ground, key("stone"), 1, Cause.DROP_FROM_HAND, Confidence.FACT),
            ),
            moves,
        )
    }

    @Test
    fun `an intent belongs to one player and explains nothing for another`() {
        val stranger = PlayerInv(UUID.randomUUID(), 0)
        val moves = Intents.explain(
            listOf(lost("stone", 1, from = stranger)),
            listOf(Intent(Cause.DROP_FROM_HAND, to = ground, qty = 1)),
            player,
        )
        assertEquals(listOf(Move(stranger, Void, key("stone"), 1, Cause.QUICK_MOVE, Confidence.INFERRED)), moves)
    }

    // A drop out of the creative menu empties no slot, so the pass finds nothing to spend the intent
    // on. The drop silenced its own spawn on the promise that the pass would write the birth instead,
    // and has to hear that the promise went unkept or the entity is never born at all.
    @Test
    fun `a drop that emptied no slot is handed back to the event that silenced its spawn`() {
        val unspent = ArrayList<Pair<Cause, Int>>()
        val dropped = Intent(Cause.DROP_FROM_MENU, to = ground, form = "stone".toByteArray(), qty = 5)
        val moves = Intents.explain(emptyList(), listOf(dropped), player, { intent, qty ->
            unspent += intent.cause to qty
        })
        assertEquals(emptyList<Move>(), moves)
        assertEquals(listOf(Cause.DROP_FROM_MENU to 5), unspent)
    }

    @Test
    fun `a drop the pass paid for in full is handed back to nobody`() {
        val unspent = ArrayList<Pair<Cause, Int>>()
        val dropped = Intent(Cause.DROP_FROM_HAND, to = ground, form = "stone".toByteArray(), qty = 5)
        Intents.explain(listOf(lost("stone", 5)), listOf(dropped), player, { intent, qty ->
            unspent += intent.cause to qty
        })
        assertEquals(emptyList<Pair<Cause, Int>>(), unspent)
    }

    // A death writes one note per stack it dropped. Swallowing whichever loss of that form the pass
    // reaches first would leave the slot the note was written for to be written off a second time.
    // Bridging: one cobblestone leaves the held slot for the world and another is picked up straight
    // back into it inside one tick. The slot reads the same on both sides, so the pass finds no delta
    // and neither intent has anything to be spent on. Run through one holder the two rows cancel on
    // the player, and the position and the item entity each get the row they are owed.
    @Test
    fun `a placement and a pickup that cancelled in one slot are written through that slot`() {
        val handed = ArrayList<Cause>()
        val intents = listOf(
            Intent(Cause.BLOCK_PLACE, to = placed, form = "cobble".toByteArray(), qty = 1, holder = bag(3)),
            Intent(Cause.PICKUP, from = ground, form = "cobble".toByteArray(), qty = 1, actor = player),
        )
        val moves = Intents.explain(emptyList(), intents, player, { intent, _ -> handed += intent.cause })
        assertEquals(
            listOf(
                Move(bag(3), placed, key("cobble"), 1, Cause.BLOCK_PLACE, Confidence.FACT),
                Move(ground, bag(3), key("cobble"), 1, Cause.PICKUP, Confidence.FACT, player),
            ),
            moves,
        )
        assertEquals(0, carried(moves))
        assertEquals(emptyList<Cause>(), handed)
    }

    // One half of a pair is not evidence of anything: an intent names its far end and not its slot,
    // and a placement in creative consumes nothing, so writing it alone would invent a debit.
    @Test
    fun `one unspent intent is never written on its own authority`() {
        val place = Intent(Cause.BLOCK_PLACE, to = placed, form = "cobble".toByteArray(), qty = 1)
        assertEquals(emptyList<Move>(), Intents.explain(emptyList(), listOf(place), player) { bag(3) })
    }

    @Test
    fun `the slot an intent names beats the one the pass can see`() {
        val intents = listOf(
            Intent(Cause.BLOCK_PLACE, to = placed, form = "cobble".toByteArray(), qty = 1, holder = bag(3)),
            Intent(Cause.PICKUP, from = ground, form = "cobble".toByteArray(), qty = 1),
        )
        val moves = Intents.explain(emptyList(), intents, player) { bag(7) }
        assertEquals(
            listOf(
                Move(bag(3), placed, key("cobble"), 1, Cause.BLOCK_PLACE, Confidence.FACT),
                Move(ground, bag(3), key("cobble"), 1, Cause.PICKUP, Confidence.FACT),
            ),
            moves,
        )
    }

    @Test
    fun `a pair no intent gave a slot goes through one the pass can see carrying the form`() {
        val other = ItemEntityRef(UUID.randomUUID())
        val intents = listOf(
            Intent(Cause.DROP_FROM_HAND, to = ground, form = "cobble".toByteArray(), qty = 2),
            Intent(Cause.PICKUP, from = other, form = "cobble".toByteArray(), qty = 2),
        )
        val moves = Intents.explain(emptyList(), intents, player) { bag(5) }
        assertEquals(
            listOf(
                Move(bag(5), ground, key("cobble"), 2, Cause.DROP_FROM_HAND, Confidence.FACT),
                Move(other, bag(5), key("cobble"), 2, Cause.PICKUP, Confidence.FACT),
            ),
            moves,
        )
        assertEquals(0, carried(moves))
    }

    @Test
    fun `a pair with no slot to run through writes nothing and is handed back instead`() {
        val handed = ArrayList<Cause>()
        val other = ItemEntityRef(UUID.randomUUID())
        val intents = listOf(
            Intent(Cause.DROP_FROM_HAND, to = ground, form = "cobble".toByteArray(), qty = 1),
            Intent(Cause.PICKUP, from = other, form = "cobble".toByteArray(), qty = 1),
        )
        val moves = Intents.explain(emptyList(), intents, player, { intent, _ -> handed += intent.cause })
        assertEquals(emptyList<Move>(), moves)
        assertEquals(listOf(Cause.DROP_FROM_HAND, Cause.PICKUP), handed)
    }

    @Test
    fun `an intent that would match any form is not married to an unrelated one`() {
        val intents = listOf(
            Intent(Cause.BLOCK_PLACE, to = placed, qty = 1, holder = bag(3)),
            Intent(Cause.PICKUP, from = ground, form = "cobble".toByteArray(), qty = 1),
        )
        assertEquals(emptyList<Move>(), Intents.explain(emptyList(), intents, player) { bag(3) })
    }

    @Test
    fun `a written movement is swallowed at the slot it was written for`() {
        val moves = Intents.explain(
            listOf(lost("stone", 1), lost("stone", 1, from = bag(1))),
            listOf(
                Intent(
                    Cause.DEATH_DROP,
                    form = "stone".toByteArray(),
                    qty = 1,
                    recorded = true,
                    holder = bag(1),
                ),
            ),
            player,
        )
        assertEquals(listOf(Move(bag(0), Void, key("stone"), 1, Cause.QUICK_MOVE, Confidence.INFERRED)), moves)
    }

    // An intent that named both of its ends is its own counterparty on both sides of the netting, and
    // pairing it with itself would spend its remainder twice and write two rows out of one movement.
    @Test
    fun `an intent that names both ends is not married to itself`() {
        val entity = ItemEntityRef(UUID.randomUUID())
        val both = Intent(Cause.PICKUP, from = entity, to = entity, form = "stone".toByteArray(), qty = 4)
        val moves = Intents.explain(emptyList(), listOf(both), player) { bag(0) }
        assertEquals(emptyList<Move>(), moves)
    }

    @Test
    fun `an intent survives exactly one take`() {
        val queue = PlayerIntents()
        val intent = Intent(Cause.BLOCK_PLACE, to = placed, qty = 1)
        queue.add(player, intent)
        assertEquals(listOf(intent), queue.take(player))
        assertEquals(emptyList<Intent>(), queue.take(player))
    }

    @Test
    fun `forgetting a player discards what is queued`() {
        val queue = PlayerIntents()
        queue.add(player, Intent(Cause.BLOCK_PLACE, to = placed, qty = 1))
        queue.forget(player)
        assertEquals(emptyList<Intent>(), queue.take(player))
    }

    @Test
    fun `one player's take does not drain another's`() {
        val queue = PlayerIntents()
        val other = UUID.randomUUID()
        val mine = Intent(Cause.BLOCK_PLACE, to = placed, qty = 1)
        val theirs = Intent(Cause.PICKUP, from = ground, qty = 3)
        queue.add(player, mine)
        queue.add(other, theirs)
        assertEquals(listOf(mine), queue.take(player))
        assertEquals(listOf(theirs), queue.take(other))
    }
}
