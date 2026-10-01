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
    // lower; the same stacks are moves, and anything that differs as well is no packing.
    @Test
    fun `a pocket packed by loading is the same stacks in other slots`() {
        val before = listOf(Pocket(0, wheat, 2), Pocket(2, bread, 5), Pocket(3, hoe, 1))
        val after = listOf(Pocket(0, wheat, 2), Pocket(1, bread, 5), Pocket(2, hoe, 1))

        val moves = pocketShifts(before, after)!!.map { (was, now) -> Triple(String(was.key.form), was.slot, now.slot) }
        assertEquals(listOf(Triple("bread", 2, 1), Triple("hoe", 3, 2)), moves)
        assertEquals(emptyList<Pair<Pocket, Pocket>>(), pocketShifts(after, after))
        assertEquals(null, pocketShifts(before, listOf(Pocket(0, wheat, 3), Pocket(1, bread, 5), Pocket(2, hoe, 1))))
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
