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
}
