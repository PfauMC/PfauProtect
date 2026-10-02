package io.pfaumc.pfauprotect.capture.item

import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.storage.ItemKey
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MobInventoriesTest {
    private val wheat = ItemKey("wheat".toByteArray(), null)
    private val bread = ItemKey("bread".toByteArray(), null)
    private val hoe = ItemKey("hoe".toByteArray(), 12)

    private fun changes(before: List<Pocket>, after: List<Pocket>) =
        pocketChanges(before, after).map { Triple(it.slot, String(it.key.form), if (it.gained) it.qty else -it.qty) }

    // Bread baked from wheat in one slot is two changes, and a stack only partly spent is one.
    @Test
    fun `a pocket read twice gives what went out and what came in, slot by slot`() {
        assertEquals(
            listOf(Triple(0, "wheat", -3), Triple(1, "bread", 1)),
            changes(listOf(Pocket(0, wheat, 3)), listOf(Pocket(1, bread, 1))),
        )
        assertEquals(listOf(Triple(0, "wheat", -2)), changes(listOf(Pocket(0, wheat, 5)), listOf(Pocket(0, wheat, 3))))
        assertEquals(
            listOf(Triple(0, "wheat", -3), Triple(0, "bread", 1)),
            changes(listOf(Pocket(0, wheat, 3)), listOf(Pocket(0, bread, 1))),
        )
        assertEquals(emptyList<Triple<Int, String, Int>>(), changes(listOf(Pocket(2, wheat, 3)), listOf(Pocket(2, wheat, 3))))
    }

    // The copy lives in the mob and has to come back exactly, wear included.
    @Test
    fun `a pocket copy survives being written into the mob`() {
        val pockets = listOf(Pocket(0, wheat, 64), Pocket(7, hoe, 1))
        val back = decodePockets(encodePockets(pockets))
        assertEquals(pockets.map { it.slot to it.count }, back.map { it.slot to it.count })
        assertArrayEquals(hoe.form, back[1].key.form)
        assertEquals(12, back[1].key.damage)
        assertEquals(null, back[0].key.damage)
    }

    // A villager whose second slot emptied comes back from its chunk with everything after it one slot
    // lower; the same stacks are moves. One that ate its first slot empty just before it was saved comes
    // back with the rest moved down and the food gone.
    @Test
    fun `a pocket packed by loading is the same stacks in other slots and what changed besides`() {
        fun moves(before: List<Pocket>, after: List<Pocket>) =
            pocketShifts(before, after).first.map { (was, now) -> Triple(String(was.key.form), was.slot, now.slot) }
        val before = listOf(Pocket(0, wheat, 2), Pocket(2, bread, 5), Pocket(3, hoe, 1))
        val after = listOf(Pocket(0, wheat, 2), Pocket(1, bread, 5), Pocket(2, hoe, 1))

        assertEquals(listOf(Triple("bread", 2, 1), Triple("hoe", 3, 2)), moves(before, after))
        assertEquals(emptyList<PocketChange>(), pocketShifts(before, after).second)
        assertEquals(emptyList<Triple<String, Int, Int>>(), moves(after, after))

        val carrot = ItemKey("carrot".toByteArray(), null)
        val ate = pocketShifts(listOf(Pocket(0, carrot, 12)) + after.map { Pocket(it.slot + 1, it.key, it.count) }, after)
        assertEquals(listOf(Triple("wheat", 1, 0), Triple("bread", 2, 1), Triple("hoe", 3, 2)), ate.first.map { (was, now) ->
            Triple(String(was.key.form), was.slot, now.slot)
        })
        assertEquals(listOf(Triple(0, "carrot", -12)), ate.second.map { Triple(it.slot, String(it.key.form), if (it.gained) it.qty else -it.qty) })
    }

    // A villager that ate its bread and part of its carrots just before it was saved: the carrots left
    // come back a slot lower. They moved, and what was eaten went from the slot they were in. A stack
    // that came back higher up was not packed there, and stays a loss and a gain.
    @Test
    fun `a stack partly eaten and moved down by loading is a move and a loss`() {
        val carrot = ItemKey("carrot".toByteArray(), null)
        fun signed(changes: List<PocketChange>) = changes.map { Triple(it.slot, String(it.key.form), if (it.gained) it.qty else -it.qty) }
        val (moves, rest) = pocketShifts(
            listOf(Pocket(0, bread, 3), Pocket(1, carrot, 12), Pocket(2, wheat, 5)),
            listOf(Pocket(0, carrot, 7), Pocket(1, wheat, 5)),
        )

        assertEquals(
            listOf(Triple("wheat", 2, 1), Triple("carrot", 1, 0)),
            moves.map { (was, now) -> Triple(String(was.key.form), was.slot, now.slot) },
        )
        assertEquals(7, moves.last().second.count)
        assertEquals(listOf(Triple(0, "bread", -3), Triple(1, "carrot", -5)), signed(rest))

        val (none, up) = pocketShifts(listOf(Pocket(0, carrot, 5)), listOf(Pocket(1, carrot, 3)))
        assertEquals(emptyList<Pair<Pocket, Pocket>>(), none)
        assertEquals(listOf(Triple(0, "carrot", -5), Triple(1, "carrot", 3)), signed(up))
    }

    // A farmer bakes three wheat into a bread and eats twelve points of food to breed with no bed for the
    // child; neither raises an event, and anything else left over is an edit nobody saw.
    @Test
    fun `what a villager does without an event is told apart from an unseen edit`() {
        val carrot = ItemKey("carrot".toByteArray(), null)
        val wheatOf = { form: ByteArray -> String(form) == "wheat" }
        val breadOf = { form: ByteArray -> String(form) == "bread" }
        val foodOf = { form: ByteArray -> String(form) == "bread" || String(form) == "carrot" }
        fun guess(vararg changes: PocketChange) = villagerGuess(changes.toList(), wheatOf, breadOf, foodOf)

        assertEquals(
            listOf(Cause.CRAFT_CONSUME, Cause.CRAFT_RESULT, Cause.CONSUME_FOOD),
            guess(PocketChange(0, wheat, 3, false), PocketChange(1, bread, 1, true), PocketChange(2, carrot, 10, false)),
        )
        assertEquals(listOf(Cause.INVENTORY_LOAD, Cause.INVENTORY_LOAD), guess(PocketChange(0, wheat, 2, false), PocketChange(1, bread, 1, true)))
        assertEquals(listOf(Cause.INVENTORY_LOAD, Cause.INVENTORY_LOAD), guess(PocketChange(0, carrot, 4, false), PocketChange(1, hoe, 1, true)))
    }

    // A farmer replants the carrot it picks up within one reading, and the pocket shows nothing; a
    // villager picks one up while it eats unseen; a piglin's gold is in its hand, not the pocket.
    @Test
    fun `a pickup the pocket shows nothing of went through it unless a hand holds it`() {
        val carrot = ItemKey("carrot".toByteArray(), null)
        val item = io.pfaumc.pfauprotect.model.ItemEntityRef(java.util.UUID.randomUUID())
        val block = io.pfaumc.pfauprotect.model.WorldBlock(java.util.UUID.randomUUID(), 0, -60, 0)
        fun pickup() = PocketLabel(carrot.form, true, item, Cause.ITEM_PICKUP_BY_MOB_INV, 1, hand = 0)
        fun read(before: List<Pocket>, after: List<Pocket>, vararg labels: PocketLabel, held: Boolean = false) =
            explainPocket(before, after, labels.toList()) { held }
                .map { (change, label) -> Triple(change.slot, if (change.gained) change.qty else -change.qty, label?.cause) }

        val five = listOf(Pocket(2, carrot, 5))
        assertEquals(
            listOf(Triple(2, 1, Cause.ITEM_PICKUP_BY_MOB_INV), Triple(2, -1, Cause.BLOCK_PLACE)),
            read(five, five, pickup(), PocketLabel(null, false, block, Cause.BLOCK_PLACE, 1)),
        )
        assertEquals(
            listOf(Triple(0, -11, null), Triple(0, 1, Cause.ITEM_PICKUP_BY_MOB_INV), Triple(0, -1, null)),
            read(listOf(Pocket(0, carrot, 12)), listOf(Pocket(0, carrot, 1)), pickup()),
        )
        assertEquals(emptyList<Triple<Int, Int, Cause?>>(), read(five, five, pickup(), held = true))
        assertEquals(
            listOf(Triple(0, 1, Cause.ITEM_PICKUP_BY_MOB_INV), Triple(0, -1, Cause.BLOCK_PLACE)),
            read(emptyList(), emptyList(), pickup(), PocketLabel(null, false, block, Cause.BLOCK_PLACE, 1)),
        )
    }

    // A golem takes out of one slot and puts into the first that fits; whatever else changed in the
    // chest meanwhile, in another form, is not read as its doing.
    @Test
    fun `a golem's move is read out of the chest by its own form only`() {
        val world = java.util.UUID.randomUUID()
        fun slot(i: Int): io.pfaumc.pfauprotect.model.Holder = io.pfaumc.pfauprotect.model.Container(world, 0, 64, 0, i)
        val before = mapOf(slot(0) to Stack(wheat, 20), slot(3) to Stack(bread, 2))
        val took = mapOf(slot(0) to Stack(wheat, 4), slot(3) to Stack(bread, 1))

        assertEquals(listOf(slot(0) to 16), chestShifts(before, took, wheat.form, gave = true))
        val put = mapOf(slot(0) to Stack(wheat, 64), slot(1) to Stack(wheat, 8))
        assertEquals(
            listOf(slot(0) to 44, slot(1) to 8),
            chestShifts(before, put, wheat.form, gave = false),
        )
    }

    // The latest copy already shows the take when the hand change is announced, so the copy before it
    // is read. A hand emptied with the chest unchanged, as by the golem's death, is no put at all.
    @Test
    fun `a golem's move is what the chest shows against the copy from before it reached in`() {
        val world = java.util.UUID.randomUUID()
        fun slot(i: Int): io.pfaumc.pfauprotect.model.Holder = io.pfaumc.pfauprotect.model.Container(world, 0, 64, 0, i)
        val previous = mapOf(slot(0) to Stack(wheat, 16), slot(1) to Stack(bread, 10))
        val now = mapOf(slot(0) to Stack(wheat, 16))

        assertEquals(listOf(slot(1) to 10), golemSlots(previous, now, bread.form, took = true, moved = 10))
        assertEquals(emptyList<Pair<io.pfaumc.pfauprotect.model.Holder, Int>>(), golemSlots(now, now, bread.form, took = false, moved = 5))
        assertEquals(emptyList<Pair<io.pfaumc.pfauprotect.model.Holder, Int>>(), golemSlots(null, now, bread.form, took = false, moved = 5))
    }
}
