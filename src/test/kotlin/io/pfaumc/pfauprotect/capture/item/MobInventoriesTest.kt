package io.pfaumc.pfauprotect.capture.item

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
    // is read; without any copy, a take is still looked for where the form lies, not in the first slot.
    @Test
    fun `a golem's take is read against the chest from before it reached in`() {
        val world = java.util.UUID.randomUUID()
        fun slot(i: Int): io.pfaumc.pfauprotect.model.Holder = io.pfaumc.pfauprotect.model.Container(world, 0, 64, 0, i)
        val previous = mapOf(slot(0) to Stack(wheat, 16), slot(1) to Stack(bread, 10))
        val now = mapOf(slot(0) to Stack(wheat, 16))

        assertEquals(
            listOf(Triple(slot(1), 10, io.pfaumc.pfauprotect.model.Confidence.FACT)),
            golemSlots(previous, now, bread.form, took = true, moved = 10, fallback = slot(0)),
        )
        assertEquals(
            listOf(Triple(slot(1), 4, io.pfaumc.pfauprotect.model.Confidence.INFERRED)),
            golemSlots(null, mapOf(slot(0) to Stack(wheat, 16), slot(1) to Stack(bread, 6)), bread.form, took = true, moved = 4, fallback = slot(0)),
        )
    }
}
