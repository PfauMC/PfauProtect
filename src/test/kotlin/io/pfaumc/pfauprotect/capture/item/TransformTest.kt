package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.capture.block.brewed
import io.pfaumc.pfauprotect.key
import io.pfaumc.pfauprotect.stub
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.Inventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

// The grouping alone: what the pass hands it, and what it hands the writer. Everything here is a
// plain value, so nothing needs a server or a database to run.
class TransformTest {
    private val world = UUID.randomUUID()
    private val player = UUID.randomUUID()

    private val craft = Shift(Cause.CRAFT_CONSUME, Cause.CRAFT_RESULT, Kind.TRANSFER, Cause.CRAFT_REMAINDER)
    private val anvil = Shift(Cause.ANVIL_COMBINE, Cause.ANVIL_COMBINE, Kind.MUTATE)

    private fun grid(slot: Int) = EntitySlot(player, slot)
    private fun table(slot: Int) = Container(world, 8, 70, 2, slot)
    private val cursor = PlayerCursor(player)

    private fun move(from: Holder, to: Holder, name: String, qty: Int, cause: Cause) =
        Move(from, to, key(name), qty, cause, Confidence.INFERRED)

    @Test
    fun `without a transformation every movement stays on its own`() {
        val moves = listOf(
            move(grid(1), Void, "cobblestone", 8, Cause.CONTAINER_REMOVE),
            move(PlayerInv(player, 0), PlayerInv(player, 1), "stick", 1, Cause.QUICK_MOVE),
        )

        assertEquals(moves.map { listOf(it) }, transactions(moves, null))
    }

    // The lapis is spent on the enchantment beside the item it enchants, and says so by its form.
    @Test
    fun `a consumed form with a reason of its own keeps it`() {
        val lapis = "lapis".toByteArray()
        val enchant = Shift(Cause.ENCHANT_APPLY, Cause.ENCHANT_APPLY, Kind.MUTATE) { form ->
            if (form.contentEquals(lapis)) Cause.ENCHANT_LAPIS_CONSUME else null
        }
        val moves = listOf(
            move(table(0), Void, "sword", 1, Cause.CONTAINER_REMOVE),
            move(table(1), Void, "lapis", 3, Cause.CONTAINER_REMOVE),
            move(Void, table(0), "sword+sharpness", 1, Cause.CONTAINER_ADD),
        )

        val causes = transactions(moves, enchant).single().map { it.cause }

        assertEquals(listOf(Cause.ENCHANT_APPLY, Cause.ENCHANT_LAPIS_CONSUME, Cause.ENCHANT_APPLY), causes)
    }

    @Test
    fun `a craft gathers its ingredients and its result into one transaction`() {
        val moves = listOf(
            move(grid(1), Void, "cobblestone", 8, Cause.CONTAINER_REMOVE),
            move(grid(2), Void, "coal", 1, Cause.CONTAINER_REMOVE),
            move(Void, cursor, "furnace", 1, Cause.CONTAINER_ADD),
        )

        val grouped = transactions(moves, craft)

        assertEquals(1, grouped.size)
        val transaction = grouped.single()
        assertEquals(3, transaction.size)
        assertEquals(
            listOf(Cause.CRAFT_CONSUME, Cause.CRAFT_CONSUME, Cause.CRAFT_RESULT),
            transaction.map { it.cause },
        )
        // The pass counted; the grouping only renamed. Quantities have to survive it untouched.
        assertEquals(listOf(8, 1, 1), transaction.map { it.qty })
    }

    // A cake leaves its buckets in the grid. They come out of the Void just as the cake does, and only
    // where they land tells them apart.
    @Test
    fun `what a recipe leaves in the grid is its remainder and not its result`() {
        val moves = listOf(
            move(grid(1), Void, "milk_bucket", 1, Cause.CONTAINER_REMOVE),
            move(Void, grid(1), "bucket", 1, Cause.CONTAINER_ADD),
            move(Void, cursor, "cake", 1, Cause.CONTAINER_ADD),
        )

        val transaction = transactions(moves, craft).single()

        assertEquals(
            listOf(Cause.CRAFT_CONSUME, Cause.CRAFT_REMAINDER, Cause.CRAFT_RESULT),
            transaction.map { it.cause },
        )
    }

    @Test
    fun `a station changing its own slots is not the player's movement to write`() {
        val furnace = { slot: Int -> Container(world, 3, 64, 3, slot) }
        val moves = listOf(
            move(furnace(0), Void, "raw_iron", 2, Cause.CONTAINER_REMOVE),
            move(Void, furnace(2), "iron_ingot", 2, Cause.CONTAINER_ADD),
            move(furnace(2), cursor, "iron_ingot", 4, Cause.CONTAINER_REMOVE),
            move(Void, cursor, "stick", 1, Cause.QUICK_MOVE),
        )

        val kept = withoutForeignEnds(moves, null)

        assertEquals(listOf(moves[2], moves[3]), kept)
        // A transformation is the one case where such ends are the player's: the recipe needs them.
        assertEquals(moves, withoutForeignEnds(moves, anvil))
    }

    // A movement that happened for its own reasons in the same tick keeps that reason and its own row.
    @Test
    fun `a movement with both ends named is left out of the transformation`() {
        val elsewhere = move(PlayerInv(player, 3), PlayerInv(player, 4), "stick", 2, Cause.QUICK_MOVE)
        val moves = listOf(
            move(grid(1), Void, "cobblestone", 8, Cause.CONTAINER_REMOVE),
            elsewhere,
            move(Void, cursor, "furnace", 1, Cause.CONTAINER_ADD),
        )

        val grouped = transactions(moves, craft)

        assertEquals(2, grouped.size)
        assertEquals(Cause.QUICK_MOVE, grouped.single { elsewhere in it }.single().cause)
        assertEquals(
            setOf(Cause.CRAFT_CONSUME, Cause.CRAFT_RESULT),
            grouped.single { elsewhere !in it }.map { it.cause }.toSet(),
        )
    }

    @Test
    fun `a transformation with one side stays a guess`() {
        val moves = listOf(move(Void, cursor, "furnace", 1, Cause.CONTAINER_ADD))

        val transaction = transactions(moves, craft).single()

        assertEquals(Cause.CRAFT_RESULT, transaction.single().cause)
        assertEquals(Confidence.INFERRED, transaction.single().confidence)
    }

    // Which station a taken result came out of, and whether it changed an item or ran a recipe.
    @Test
    fun `a workbench runs a recipe while a station changes the item`() {
        assertEquals(Kind.TRANSFER, shiftOf(stub(CraftingInventory::class.java))?.kind)
        assertEquals(Kind.MUTATE, shiftOf(stub(AnvilInventory::class.java))?.kind)
        assertNull(shiftOf(stub(Inventory::class.java)))
    }

    // A stand brews three bottles at once, and every combination of "there was nothing there", "the
    // recipe left it alone" and "it became a potion" happens on one and the same brew.
    @Test
    fun `a brew names only the bottles that changed`() {
        val water = key("water bottle")
        val awkward = key("awkward potion")
        val empty: ItemKey? = null

        val changed = brewed(
            listOf(water, empty, water),
            listOf(awkward, awkward, water),
        )

        assertEquals(listOf(Triple(0, water, awkward)), changed)
    }

    // The game hands back as many results as it made, and reading past them would pair a bottle with
    // a result that is not there.
    @Test
    fun `a results list shorter than the stand leaves the rest alone`() {
        val water = key("water bottle")
        val awkward = key("awkward potion")

        assertEquals(
            listOf(Triple(0, water, awkward)),
            brewed(listOf(water, water, water), listOf(awkward)),
        )
        assertTrue(brewed(listOf(water, water, water), emptyList()).isEmpty())
    }

    // The whole pure path in one go: two snapshots of one slot, the intent the event left, and the
    // grouping. A signed book never leaves the hand it was written in, so the slot reads as one form
    // gone and another arrived, and only the shift says they are the same book.
    @Test
    fun `signing a book is one book changing and not two strangers`() {
        val slot = PlayerInv(player, 0)
        val sign = Shift(Cause.BOOK_SIGN, Cause.BOOK_SIGN, Kind.MUTATE)
        val intents = listOf(Intent(sign.consume, shift = sign))

        val edges = Netting.diff(
            Snapshot(mapOf(slot to Stack(key("writable_book"), 1)), emptySet()),
            Snapshot(mapOf(slot to Stack(key("written_book"), 1)), emptySet()),
            intents,
            player,
        )
        val moves = Intents.explain(edges, intents, player)
        val transaction = transactions(moves, sign).single()

        assertEquals(2, transaction.size)
        assertTrue(transaction.all { it.cause == Cause.BOOK_SIGN }, "$transaction")
        assertTrue(transaction.all { it.confidence == Confidence.FACT }, "$transaction")
        // One side leaves the slot and the other arrives in it; neither pairs with the other, which is
        // what keeps both facing the Void and the transaction readable as a change rather than a move.
        assertEquals(1, transaction.count { it.to == Void })
        assertEquals(1, transaction.count { it.from == Void })
    }
}
