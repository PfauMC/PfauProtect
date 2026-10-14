package io.pfaumc.pfauprotect.check
import io.pfaumc.pfauprotect.OneLedger
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.storage.FormKey
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerEnder
import io.pfaumc.pfauprotect.model.PlayerEquip
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.world
import io.pfaumc.pfauprotect.model.WorldBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ReconcileTest : OneLedger() {
    private val chest = Container(world, 8, 64, 8, 0)
    private val stone = byteArrayOf(1, 1)
    private val pickaxe = byteArrayOf(2, 2)
    private val bread = byteArrayOf(3, 3)

    private val reconcile by lazy { Reconciliation(log) }

    private var clock = T0

    private fun move(cause: Cause, from: Holder, to: Holder, form: ByteArray, qty: Int, damage: Int? = null) {
        log.submit(Transfer(cause, from, to, form, damage, qty, clock++))
    }

    private fun held(vararg forms: Pair<ByteArray, Int>) = forms.associate { FormKey(it.first) to it.second }

    @Test
    fun `a session of only covered paths reconciles to nothing`() {
        val dropped = ItemEntityRef(UUID.randomUUID())
        val position = WorldBlock(world, 8, 65, 8)
        move(Cause.BLOCK_DROP, Void, dropped, stone, 1)
        move(Cause.PICKUP, dropped, PlayerInv(alice, 0), stone, 1)
        move(Cause.CONTAINER_ADD, PlayerInv(alice, 0), chest, stone, 1)
        move(Cause.CONTAINER_REMOVE, chest, PlayerInv(alice, 3), stone, 1)
        move(Cause.BLOCK_PLACE, PlayerInv(alice, 3), position, stone, 1)
        move(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(alice, 0), pickaxe, 1, damage = 40)
        log.drain()

        assertEquals(emptyList<Mismatch>(), reconcile.compare(alice, held(pickaxe to 1)))
    }

    // Loaves the player no longer holds and the ledger never saw leave: the difference names bread,
    // and bread is the address of the missing path.
    @Test
    fun `an uncaptured path shows up as the form it lost`() {
        move(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(alice, 0), bread, 3)
        log.drain()

        val found = reconcile.compare(alice, held(bread to 1)).single()
        assertTrue(found.form.contentEquals(bread))
        assertEquals(3, found.ledger)
        assertEquals(1, found.actual)
        assertEquals(-2, found.difference)
    }

    // The other direction, and the one that matters: items the player holds that no posting ever
    // handed them. A form the ledger has never even seen has to read as a surplus, not as a miss.
    @Test
    fun `items that arrived by no posting at all are counted as a surplus`() {
        move(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(alice, 0), stone, 1)
        log.drain()

        val found = reconcile.compare(alice, held(stone to 1, pickaxe to 2))
        assertEquals(1, found.size)
        assertTrue(found.single().form.contentEquals(pickaxe))
        assertEquals(0, found.single().ledger)
        assertEquals(2, found.single().actual)
    }

    @Test
    fun `the postings of another player are not counted`() {
        move(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(bob, 0), stone, 64)
        log.drain()

        assertEquals(emptyList<Mismatch>(), reconcile.compare(alice, held()))
    }

    // Every slot a player owns hangs off four prefixes and the slot number is not in the key, so the
    // balance has to gather armour, cursor and ender chest as well as the bag.
    @Test
    fun `the balance gathers every kind of slot the player owns`() {
        move(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(alice, 0), stone, 4)
        move(Cause.EQUIP_ARMOR, PlayerInv(alice, 0), PlayerEquip(alice, 38), stone, 3)
        move(Cause.CURSOR_TAKE, PlayerEquip(alice, 38), PlayerCursor(alice), stone, 2)
        move(Cause.CONTAINER_ADD, PlayerCursor(alice), PlayerEnder(alice, 2), stone, 1)
        log.drain()

        assertEquals(emptyList<Mismatch>(), reconcile.compare(alice, held(stone to 4)))
        assertEquals(4, reconcile.compare(alice, held()).single().ledger)
    }

    // The live count reads the bag, not the crafting grid, so what lies in the grid is left out of the
    // ledger's side too; counted there alone it would read as a loss every time a recipe is laid out.
    @Test
    fun `what lies in the crafting grid is left out of both sides`() {
        move(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(alice, 0), stone, 4)
        move(Cause.CONTAINER_ADD, PlayerInv(alice, 0), EntitySlot(alice, 1), stone, 1)
        log.drain()

        assertEquals(emptyList<Mismatch>(), reconcile.compare(alice, held(stone to 3)))
    }
}
