package io.pfaumc.pfauprotect

import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.CartographyInventory
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.FurnaceInventory
import org.bukkit.inventory.GrindstoneInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.LoomInventory
import org.bukkit.inventory.SmithingInventory
import org.bukkit.inventory.StonecutterInventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class PreviewSlotTest {
    // A crafting menu addresses its result ahead of the grid, so the workbench and the player's own
    // two-by-two both hide the same slot however many grid slots follow it.
    @Test
    fun `a crafting menu computes its first slot`() {
        assertEquals(0, previewSlot(sized(CraftingInventory::class.java, 10)))
        assertEquals(0, previewSlot(sized(CraftingInventory::class.java, 5)))
    }

    // Every station built on a result inventory puts its ingredients first and its result last, so the
    // slot to hide follows the size rather than a number per station.
    @Test
    fun `a station built on a result inventory computes its last slot`() {
        assertEquals(2, previewSlot(sized(AnvilInventory::class.java, 3)))
        assertEquals(2, previewSlot(sized(GrindstoneInventory::class.java, 3)))
        assertEquals(3, previewSlot(sized(SmithingInventory::class.java, 4)))
        assertEquals(1, previewSlot(sized(StonecutterInventory::class.java, 2)))
        assertEquals(3, previewSlot(sized(LoomInventory::class.java, 4)))
        assertEquals(2, previewSlot(sized(CartographyInventory::class.java, 3)))
    }

    // A furnace output holds a real item that stands there until it is taken, and hiding it would lose
    // every smelted stack sitting in a furnace nobody has emptied.
    @Test
    fun `a menu that stores its output hides nothing`() {
        assertNull(previewSlot(sized(FurnaceInventory::class.java, 3)))
        assertNull(previewSlot(sized(Inventory::class.java, 27)))
    }

    private fun <T : Inventory> sized(type: Class<T>, size: Int): Inventory {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            if (method.name == "getSize") size else null
        } as T
    }
}
