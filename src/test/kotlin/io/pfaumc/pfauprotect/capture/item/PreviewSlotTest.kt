package io.pfaumc.pfauprotect.capture.item
import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.stub
import org.bukkit.NamespacedKey
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.ComplexRecipe
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.FurnaceInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.MerchantInventory
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.SmithingInventory
import org.bukkit.inventory.SmithingTrimRecipe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@ExtendWith(Vanilla::class)
class PreviewSlotTest {
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
        assertEquals(3, previewSlot(stub(SmithingInventory::class.java, mapOf("getSize" to 4))))
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
}
