package io.pfaumc.pfauprotect.capture.item
import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.stub
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ComplexRecipe
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.FurnaceInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.SmithingInventory
import org.bukkit.inventory.SmithingTrimRecipe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@ExtendWith(Vanilla::class)
class PreviewSlotTest {
    @Test
    fun `a station hides the slot it computes and a menu that stores its output hides nothing`() {
        // A crafting menu addresses its result ahead of the grid.
        assertEquals(0, previewSlot(stub(CraftingInventory::class.java, mapOf("getSize" to 10))))
        assertEquals(3, previewSlot(stub(SmithingInventory::class.java, mapOf("getSize" to 4))))
        assertNull(previewSlot(stub(FurnaceInventory::class.java, mapOf("getSize" to 3))))
    }

    private fun grid(key: String?): Inventory {
        val recipe = key?.let { stub(ComplexRecipe::class.java, mapOf("getKey" to NamespacedKey.minecraft(it))) }
        return stub(CraftingInventory::class.java, mapOf("getSize" to 10, "getRecipe" to recipe))
    }

    private fun smithing(recipe: Any?): Inventory =
        stub(SmithingInventory::class.java, mapOf("getSize" to 4, "getRecipe" to recipe))

    // A workbench rearranges whole items and runs a recipe; a station hands back the item that went in,
    // changed. Only the recipe the grid matched tells a copy or a recolour from an ordinary craft.
    @Test
    fun `the station a result came out of names its causes and its kind`() {
        val trim = SmithingTrimRecipe(
            NamespacedKey.minecraft("test_trim"),
            RecipeChoice.empty(),
            RecipeChoice.empty(),
            RecipeChoice.empty(),
            stub(org.bukkit.inventory.meta.trim.TrimPattern::class.java),
        )
        fun same(cause: Cause, kind: Kind) = listOf(cause, cause, kind)
        val craft = listOf(Cause.CRAFT_CONSUME, Cause.CRAFT_RESULT, Kind.TRANSFER)
        val cases = listOf(
            "no recipe" to grid(null) to craft,
            "shulker_box" to grid("shulker_box") to craft,
            "book_cloning" to grid("book_cloning") to same(Cause.BOOK_COPY, Kind.TRANSFER),
            "leather_chestplate_dyed" to grid("leather_chestplate_dyed") to same(Cause.DYE_ITEM, Kind.TRANSFER),
            "red_shulker_box" to grid("red_shulker_box") to same(Cause.DYE_ITEM, Kind.TRANSFER),
            "white_banner_duplicate" to grid("white_banner_duplicate") to same(Cause.BANNER_DUPLICATE, Kind.TRANSFER),
            "trim" to smithing(trim) to same(Cause.SMITHING_TRIM, Kind.MUTATE),
            // A result-slot click that matched nothing is ordinary traffic, and the default carries it.
            "smithing, no recipe" to smithing(null) to same(Cause.SMITHING_TRANSFORM, Kind.MUTATE),
        )
        for ((named, expected) in cases) {
            val (name, top) = named
            val shift = shiftOf(top)!!
            assertEquals(expected, listOf(shift.consume, shift.result, shift.kind), name)
        }
        assertEquals(Cause.CRAFT_REMAINDER, shiftOf(grid(null))!!.remainder)
        assertEquals(Cause.CRAFT_REMAINDER, shiftOf(grid("book_cloning"))!!.remainder)
        assertNull(shiftOf(stub(Inventory::class.java)))
    }
}
