package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.key
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerHolder
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID

class IntentTest {
    private val world = UUID.randomUUID()
    private val player = UUID.randomUUID()
    private val ground = ItemEntityRef(UUID.randomUUID())
    private val placed = WorldBlock(world, 10, 65, -3)

    private fun bag(slot: Int) = PlayerInv(player, slot)

    private fun lost(name: String, qty: Int, from: Holder = bag(0)) =
        Edge(from, Void, key(name), qty, Confidence.INFERRED)

    private fun gained(name: String, qty: Int, to: Holder = bag(0)) =
        Edge(Void, to, key(name), qty, Confidence.INFERRED)

    private fun carried(moves: List<Move>) = moves.sumOf { move ->
        (if (move.to is PlayerHolder) move.qty else 0) - (if (move.from is PlayerHolder) move.qty else 0)
    }

    // A command's reason is handed on by a pass that did not spend it, so the pass has to say which it
    // spent: none when the inventory had not changed yet, the clear once the clear shows.
    @Test
    fun `a pass says which intents it spent and leaves the rest for the next one`() {
        val clear = Intent(Cause.CMD_CLEAR, to = Void, untilTick = 1)
        val give = Intent(Cause.CMD_GIVE, from = Void, form = "stone".toByteArray(), untilTick = 1)
        val spent = HashSet<Intent>()
        Intents.explain(emptyList(), listOf(clear, give), player, used = spent::add)
        assertTrue(spent.isEmpty())

        val moves = Intents.explain(listOf(lost("dirt", 5)), listOf(clear, give), player, used = spent::add)
        assertEquals(setOf(clear), spent)
        assertEquals(Cause.CMD_CLEAR, moves.single().cause)
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

    // An unexplained end is named for what it is. Borrowing the name of a click made the counter of
    // unexplained movements read as shift-clicks nobody could account for.
    @Test
    fun `an end nothing explains is named as turning up or going`() {
        val moves = Intents.explain(listOf(gained("stone", 2), lost("dirt", 1)), emptyList(), player)
        assertEquals(listOf(Cause.DIRECT_NEW_ITEM, Cause.ITEM_VANISHED), moves.map { it.cause })
        assertTrue(moves.all { it.confidence == Confidence.INFERRED })
    }

    @Test
    fun `a remainder nothing explains stays inferred and faces the void`() {
        val moves =
            Intents.explain(listOf(lost("stone", 4)), listOf(Intent(Cause.PICKUP, from = ground)), player)
        assertEquals(listOf(Move(bag(0), Void, key("stone"), 4, Cause.ITEM_VANISHED, Confidence.INFERRED)), moves)
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
                Move(bag(0), Void, key("stone"), 3, Cause.ITEM_VANISHED, Confidence.INFERRED),
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
    fun `an intent that names no amount keeps explaining`() {
        val moves = Intents.explain(
            listOf(lost("stone", 2), lost("dirt", 1, from = bag(1))),
            listOf(Intent(Cause.DROP_FROM_HAND, to = ground)),
            player,
        )
        assertEquals(
            listOf(
                Move(bag(0), ground, key("stone"), 2, Cause.DROP_FROM_HAND, Confidence.FACT),
                Move(bag(1), ground, key("dirt"), 1, Cause.DROP_FROM_HAND, Confidence.FACT),
            ),
            moves,
        )
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
        assertEquals(listOf(Move(bag(0), Void, key("stone"), 1, Cause.ITEM_VANISHED, Confidence.INFERRED)), moves)
    }

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
                Move(chest, Void, key("stone"), 1, Cause.ITEM_VANISHED, Confidence.INFERRED),
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
        assertEquals(listOf(Move(stranger, Void, key("stone"), 1, Cause.ITEM_VANISHED, Confidence.INFERRED)), moves)
    }

    @Test
    fun `a drop is handed back to the event that silenced its spawn for whatever no slot paid`() {
        fun handBack(losses: List<Edge>): Pair<List<Move>, List<Pair<Cause, Int>>> {
            val unspent = ArrayList<Pair<Cause, Int>>()
            val dropped = Intent(Cause.DROP_FROM_MENU, to = ground, form = "stone".toByteArray(), qty = 5)
            val moves = Intents.explain(losses, listOf(dropped), player, { intent, qty -> unspent += intent.cause to qty })
            return moves to unspent
        }

        assertEquals(emptyList<Move>() to listOf(Cause.DROP_FROM_MENU to 5), handBack(emptyList()))
        assertEquals(
            listOf(Move(bag(0), ground, key("stone"), 3, Cause.DROP_FROM_MENU, Confidence.FACT)) to
                listOf(Cause.DROP_FROM_MENU to 2),
            handBack(listOf(lost("stone", 3))),
        )
        assertEquals(emptyList<Pair<Cause, Int>>(), handBack(listOf(lost("stone", 5))).second)
    }

    @Test
    fun `one unspent intent is never written on its own authority`() {
        val place = Intent(Cause.BLOCK_PLACE, to = placed, form = "cobble".toByteArray(), qty = 1)
        assertEquals(emptyList<Move>(), Intents.explain(emptyList(), listOf(place), player) { bag(3) })
    }

    // Two opposite movements that cancelled in one slot run through a slot of the player's: the one
    // either intent names, or else one the pass sees holding the form.
    @ParameterizedTest
    @CsvSource("3, -1, 3", "-1, 4, 4", "-1, -1, 7")
    fun `a placement and a pickup that cancelled in one slot are written through a slot of the player's`(
        placedFrom: Int,
        pickedInto: Int,
        through: Int,
    ) {
        fun named(slot: Int) = if (slot < 0) null else bag(slot)
        val handed = ArrayList<Cause>()
        val intents = listOf(
            Intent(Cause.BLOCK_PLACE, to = placed, form = "cobble".toByteArray(), qty = 2, holder = named(placedFrom)),
            Intent(Cause.PICKUP, from = ground, form = "cobble".toByteArray(), qty = 2, actor = player, holder = named(pickedInto)),
        )
        val moves = Intents.explain(emptyList(), intents, player, { intent, _ -> handed += intent.cause }) { bag(7) }
        assertEquals(
            listOf(
                Move(bag(through), placed, key("cobble"), 2, Cause.BLOCK_PLACE, Confidence.FACT),
                Move(ground, bag(through), key("cobble"), 2, Cause.PICKUP, Confidence.FACT, player),
            ),
            moves,
        )
        assertEquals(0, carried(moves))
        assertEquals(emptyList<Cause>(), handed)
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
        assertEquals(setOf(Cause.DROP_FROM_HAND, Cause.PICKUP), handed.toSet())
        assertEquals(2, handed.size)
    }

    @Test
    fun `an intent that would match any form is not married to an unrelated one`() {
        val intents = listOf(
            Intent(Cause.BLOCK_PLACE, to = placed, qty = 1, holder = bag(3)),
            Intent(Cause.PICKUP, from = ground, form = "cobble".toByteArray(), qty = 1),
        )
        assertEquals(emptyList<Move>(), Intents.explain(emptyList(), intents, player) { bag(3) })
    }

    // An event that still knew the slot speaks for that slot only, whether it already wrote the row or
    // leaves it to the pass: the same form lost from another slot stays what nothing explained.
    @Test
    fun `an intent that names a slot explains the loss from that slot and no other`() {
        val edges = listOf(lost("stone", 3, from = bag(1)), lost("stone", 1))
        val vanished = Move(bag(1), Void, key("stone"), 3, Cause.ITEM_VANISHED, Confidence.INFERRED)
        val stone = "stone".toByteArray()

        val written = Intent(Cause.DEATH_DROP, form = stone, qty = 1, recorded = true, holder = bag(0))
        assertEquals(listOf(vanished), Intents.explain(edges, listOf(written), player))

        val used = Intent(Cause.ITEM_USED, to = Void, form = stone, holder = bag(0))
        assertEquals(
            listOf(vanished, Move(bag(0), Void, key("stone"), 1, Cause.ITEM_USED, Confidence.FACT)),
            Intents.explain(edges, listOf(used), player),
        )
    }

    @Test
    fun `an intent that names both ends is not married to itself`() {
        val entity = ItemEntityRef(UUID.randomUUID())
        val both = Intent(Cause.PICKUP, from = entity, to = entity, form = "stone".toByteArray(), qty = 4)
        val moves = Intents.explain(emptyList(), listOf(both), player) { bag(0) }
        assertEquals(emptyList<Move>(), moves)
    }

    @Test
    fun `a player's queue is drained by one take or by forgetting them, and no other player's with it`() {
        val queue = PlayerIntents()
        val other = UUID.randomUUID()
        val mine = Intent(Cause.BLOCK_PLACE, to = placed, qty = 1)
        val theirs = Intent(Cause.PICKUP, from = ground, qty = 3)
        queue.add(player, mine)
        queue.add(other, theirs)

        assertEquals(listOf(mine), queue.take(player))
        assertEquals(emptyList<Intent>(), queue.take(player))

        queue.add(player, mine)
        queue.forget(player)
        assertEquals(emptyList<Intent>(), queue.take(player))
        assertEquals(listOf(theirs), queue.take(other))
    }
}
