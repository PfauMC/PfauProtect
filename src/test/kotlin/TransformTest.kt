package io.pfaumc.pfauprotect

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

    private val craft = Shift(Cause.CRAFT_CONSUME, Cause.CRAFT_RESULT, Kind.TRANSFER)
    private val anvil = Shift(Cause.ANVIL_COMBINE, Cause.ANVIL_COMBINE, Kind.MUTATE)

    private fun key(name: String) = ItemKey(name.toByteArray(), null)
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

    // The ingredients leave for the Void and the result arrives out of it. Apart they are unrelated
    // losses and an unexplained gain; together they are the recipe that was run.
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

        assertEquals(listOf(elsewhere), grouped.first())
        assertEquals(2, grouped.last().size)
        assertEquals(Cause.QUICK_MOVE, grouped.first().single().cause)
    }

    // Both halves of an anvil face the Void and carry one reason, which is what tells a reader the
    // item changed rather than one item dying and another being born.
    @Test
    fun `an anvil names one reason on both of its sides`() {
        val moves = listOf(
            move(table(0), Void, "damaged sword", 1, Cause.CONTAINER_REMOVE),
            move(table(1), Void, "damaged sword", 1, Cause.CONTAINER_REMOVE),
            move(Void, cursor, "repaired sword", 1, Cause.CONTAINER_ADD),
        )

        val transaction = transactions(moves, anvil).single()

        assertTrue(transaction.all { it.cause == Cause.ANVIL_COMBINE })
        assertEquals(3, transaction.size)
    }

    // A creative craft consumes nothing, so the transformation is a lone arrival. There is no pair to
    // call a mutation, and calling it one would say an item became something it never was.
    @Test
    fun `a result with nothing consumed is a movement and not a mutation`() {
        val moves = listOf(move(Void, cursor, "furnace", 1, Cause.CONTAINER_ADD))

        val transaction = transactions(moves, anvil).single()

        assertEquals(1, transaction.size)
        assertEquals(Cause.ANVIL_COMBINE, transaction.single().cause)
    }

    // Which station a taken result came out of, and whether it changed an item or ran a recipe.
    @Test
    fun `a workbench runs a recipe while a station changes the item`() {
        assertEquals(Kind.TRANSFER, craft.kind)
        assertEquals(Kind.MUTATE, anvil.kind)
        assertNull(shiftOf(inventoryStub()))
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

    private fun inventoryStub(): org.bukkit.inventory.Inventory {
        val type = org.bukkit.inventory.Inventory::class.java
        return java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ ->
            null
        } as org.bukkit.inventory.Inventory
    }
}
