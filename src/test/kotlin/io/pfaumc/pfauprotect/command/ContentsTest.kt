package io.pfaumc.pfauprotect.command

import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.capture.entity.bytesOf
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(Vanilla::class)
class ContentsTest {

    private fun payload(id: String, vararg slots: Pair<Int, String>): ByteArray {
        val items = ListTag()
        for ((slot, item) in slots) items.add(CompoundTag().apply { putByte("Slot", slot.toByte()); putString("id", item); putInt("count", 5) })
        return bytesOf(CompoundTag().apply { putString("id", id); if (slots.isNotEmpty()) put("Items", items) })
    }

    @Test
    fun `a chest is three rows of nine with its items in their slots`() {
        val contents = contentsOf(payload("minecraft:chest", 3 to "minecraft:diamond", 20 to "minecraft:stone"), ServerRegistries.access)!!
        assertEquals(9, contents.columns)
        assertEquals(27, contents.cells)
        assertEquals(setOf(3, 20), contents.items.keys)
        assertEquals(Items.DIAMOND, contents.items.getValue(3).item)
        assertEquals(5, contents.items.getValue(3).count)
    }

    @Test
    fun `a hopper is a row of five and an unknown container as long as its last slot`() {
        val hopper = contentsOf(payload("minecraft:hopper", 2 to "minecraft:diamond"), ServerRegistries.access)!!
        assertEquals(5 to 5, hopper.columns to hopper.cells)
        val donkey = contentsOf(payload("minecraft:donkey", 30 to "minecraft:diamond"), ServerRegistries.access)!!
        assertEquals(9 to 36, donkey.columns to donkey.cells)
    }

    @Test
    fun `an empty container has no window to open`() {
        val empty = payload("minecraft:chest")
        assertFalse(holdsItems(empty))
        assertNull(contentsOf(empty, ServerRegistries.access))
        assertTrue(holdsItems(payload("minecraft:chest", 0 to "minecraft:diamond")))
    }

    @Test
    fun `sprites come from the item's own atlas, and an item that would pass for its material has none`() {
        assertEquals("items" to "item/diamond", ItemSprites.of("minecraft:diamond"))
        assertEquals("blocks" to "block/stone", ItemSprites.of("minecraft:stone"))
        // Drawn by code, its particles are planks; a stair's faces are planks too.
        assertNull(ItemSprites.of("minecraft:chest"))
        assertNull(ItemSprites.of("minecraft:oak_stairs"))
    }
}
