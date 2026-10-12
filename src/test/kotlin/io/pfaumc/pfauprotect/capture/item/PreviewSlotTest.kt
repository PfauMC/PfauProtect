package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.stub
import org.bukkit.NamespacedKey
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.CartographyInventory
import org.bukkit.inventory.ComplexRecipe
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
        assertEquals(0, previewSlot(stub(CraftingInventory::class.java, mapOf("getSize" to 10))))
        assertEquals(0, previewSlot(stub(CraftingInventory::class.java, mapOf("getSize" to 5))))
    }

    @Test
    fun `a station built on a result inventory computes its last slot`() {
        assertEquals(2, previewSlot(stub(AnvilInventory::class.java, mapOf("getSize" to 3))))
        assertEquals(2, previewSlot(stub(GrindstoneInventory::class.java, mapOf("getSize" to 3))))
        assertEquals(3, previewSlot(stub(SmithingInventory::class.java, mapOf("getSize" to 4))))
        assertEquals(1, previewSlot(stub(StonecutterInventory::class.java, mapOf("getSize" to 2))))
        assertEquals(3, previewSlot(stub(LoomInventory::class.java, mapOf("getSize" to 4))))
        assertEquals(2, previewSlot(stub(CartographyInventory::class.java, mapOf("getSize" to 3))))
    }

    @Test
    fun `a menu that stores its output hides nothing`() {
        assertNull(previewSlot(stub(FurnaceInventory::class.java, mapOf("getSize" to 3))))
        assertNull(previewSlot(stub(Inventory::class.java, mapOf("getSize" to 27))))
    }

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

    // A copy and a recolour go through the same grid as any craft; only the recipe the grid matched
    // tells them apart, and only while the click is delivered.
    @Test
    fun `a special recipe in the crafting grid is named for what it does`() {
        fun grid(key: String): Inventory {
            val recipe = stub(ComplexRecipe::class.java, mapOf("getKey" to NamespacedKey.minecraft(key)))
            return stub(CraftingInventory::class.java, mapOf("getSize" to 10, "getRecipe" to recipe))
        }
        assertEquals(Cause.BOOK_COPY, shiftOf(grid("book_cloning"))!!.consume)
        assertEquals(Cause.DYE_ITEM, shiftOf(grid("leather_chestplate_dyed"))!!.consume)
        assertEquals(Cause.DYE_ITEM, shiftOf(grid("red_shulker_box"))!!.consume)
        assertEquals(Cause.BANNER_DUPLICATE, shiftOf(grid("white_banner_duplicate"))!!.consume)
        assertEquals(Cause.CRAFT_CONSUME, shiftOf(grid("shulker_box"))!!.consume)
        assertEquals(Cause.CRAFT_CONSUME, shiftOf(stub(CraftingInventory::class.java, mapOf("getSize" to 10)))!!.consume)
    }

    private fun smithing(recipe: Any?): Inventory =
        stub(SmithingInventory::class.java, mapOf("getSize" to 4, "getRecipe" to recipe))

    @Test
    fun `a trade is a station whose result is its last slot`() {
        val merchant = stub(MerchantInventory::class.java, mapOf("getSize" to 3))
        assertEquals(2, previewSlot(merchant))
        val shift = shiftOf(merchant)!!
        assertEquals(Cause.TRADE_PAYMENT, shift.consume)
        assertEquals(Cause.TRADE_RESULT, shift.result)
    }

    @Test
    fun `a dropped form comes out of the slot that booked it`() {
        val held = mapOf(0 to "sword".toByteArray(), 5 to "gold_ingot".toByteArray())
        assertEquals(5, heldSlotOf(held, "gold_ingot".toByteArray()))
        assertNull(heldSlotOf(held, "rotten_flesh".toByteArray()))
    }

    // A golem dies holding three copper ingots and its loot is one more: the one falls first and is the
    // loot, the three after it are the hand.
    @Test
    fun `a mob's own loot of the form it holds is not taken for what it held`() {
        assertEquals(false, heldDrop(need = 1, holding = 3, later = listOf(3)))
        assertEquals(true, heldDrop(need = 3, holding = 3, later = emptyList()))
        assertEquals(true, heldDrop(need = 1, holding = null, later = listOf(3)))
    }

    // Wolf armour one point from breaking breaks under a hit of one; a fresh piece does not under one
    // of three, and what has no durability never wears through.
    @Test
    fun `gear breaks on the wear that takes its last point`() {
        fun mirror(stack: net.minecraft.world.item.ItemStack) = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(stack)
        val armor = net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WOLF_ARMOR)
        assertEquals(false, wornThrough(mirror(armor), 3))
        armor.damageValue = armor.maxDamage - 1
        assertEquals(true, wornThrough(mirror(armor), 1))
        assertEquals(false, wornThrough(mirror(net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SADDLE)), 100))
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

    @Test
    fun `a horse window names its slots the way its equipment does`() {
        assertEquals(net.minecraft.world.entity.EquipmentSlot.SADDLE.ordinal, horseSlot(0))
        assertEquals(net.minecraft.world.entity.EquipmentSlot.BODY.ordinal, horseSlot(1))
        assertEquals(17, horseSlot(2))
    }
}
