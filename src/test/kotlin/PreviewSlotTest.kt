package io.pfaumc.pfauprotect

import org.bukkit.NamespacedKey
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.CartographyInventory
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.FurnaceInventory
import org.bukkit.inventory.GrindstoneInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.LoomInventory
import org.bukkit.inventory.MerchantInventory
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.SmithingInventory
import org.bukkit.inventory.SmithingTrimRecipe
import org.bukkit.inventory.StonecutterInventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.Proxy

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreviewSlotTest {
    // A trim recipe builds an ItemStack in its own constructor, and every ItemStack constructor throws
    // until a datapack load has bound the item prototypes.
    @BeforeAll
    fun loadServerRegistries() {
        ServerRegistries.access
    }

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

    // The station names which of the two smithing recipes matched, and it only names it while the
    // click is being delivered — which is why it is read here and not from the deferred pass.
    @Test
    fun `a smithing station tells a trim apart from a transform`() {
        val trim = SmithingTrimRecipe(
            NamespacedKey.minecraft("test_trim"),
            RecipeChoice.empty(),
            RecipeChoice.empty(),
            RecipeChoice.empty(),
            stub(org.bukkit.inventory.meta.trim.TrimPattern::class.java),
        )

        assertEquals(Cause.SMITHING_TRIM, shiftOf(smithing(trim))!!.consume)
        // A result-slot click that matched nothing is ordinary traffic, and the default carries it.
        assertEquals(Cause.SMITHING_TRANSFORM, shiftOf(smithing(null))!!.consume)
    }

    private fun smithing(recipe: Any?): Inventory {
        val type = SmithingInventory::class.java
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.name) {
                "getSize" -> 4
                "getRecipe" -> recipe
                else -> null
            }
        } as Inventory
    }

    // The goods are computed from the payment the way a craft is from its grid, and taking them
    // destroys the payment and makes the goods.
    @Test
    fun `a trade is a station whose result is its last slot`() {
        val merchant = sized(MerchantInventory::class.java, 3)
        assertEquals(2, previewSlot(merchant))
        val shift = shiftOf(merchant)!!
        assertEquals(Cause.TRADE_PAYMENT, shift.consume)
        assertEquals(Cause.TRADE_RESULT, shift.result)
    }

    // A mob gives up the booked item out of the slot that took the same form, whichever it was.
    @Test
    fun `a dropped form comes out of the slot that booked it`() {
        val held = mapOf(0 to "sword".toByteArray(), 5 to "gold_ingot".toByteArray())
        assertEquals(5, heldSlotOf(held, "gold_ingot".toByteArray()))
        assertNull(heldSlotOf(held, "rotten_flesh".toByteArray()))
    }

    // A boat is removed before it drops: its drop still finds the slot, once, and the rest is left to
    // be written off.
    @Test
    fun `a removed entity's drop takes its slot out of what it held when it went`() {
        val gone = mutableMapOf(16 to "oak_boat".toByteArray(), 0 to "chest".toByteArray())
        assertEquals(16, claimHeld(emptyMap(), gone, "oak_boat".toByteArray()))
        assertNull(claimHeld(emptyMap(), gone, "oak_boat".toByteArray()))
        assertEquals(listOf(0), gone.keys.toList())
        assertNull(claimHeld(emptyMap(), null, "oak_boat".toByteArray()))
    }

    // The saddle a click books and the saddle the window takes out are one slot.
    @Test
    fun `a horse window names its slots the way its equipment does`() {
        assertEquals(net.minecraft.world.entity.EquipmentSlot.SADDLE.ordinal, horseSlot(0))
        assertEquals(net.minecraft.world.entity.EquipmentSlot.BODY.ordinal, horseSlot(1))
        assertEquals(17, horseSlot(2))
    }

    private fun <T : Any> stub(type: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null } as T
    }

    private fun <T : Inventory> sized(type: Class<T>, size: Int): Inventory {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            if (method.name == "getSize") size else null
        } as T
    }
}
