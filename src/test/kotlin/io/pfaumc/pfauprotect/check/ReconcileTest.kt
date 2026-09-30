package io.pfaumc.pfauprotect.check
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.storage.FormKey
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerEnder
import io.pfaumc.pfauprotect.model.PlayerEquip
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

private const val T0 = 1_700_000_000_000L

class ReconcileTest {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000011")
    private val steve = UUID.fromString("00000000-0000-4000-8000-0000000000f1")
    private val chest = Container(world, 8, 64, 8, 0)
    private val stone = byteArrayOf(1, 1)
    private val pickaxe = byteArrayOf(2, 2)
    private val bread = byteArrayOf(3, 3)

    private lateinit var log: RocksItemLog
    private lateinit var reconcile: Reconciliation

    private var clock = T0

    @BeforeEach
    fun open(@TempDir dir: Path) {
        log = RocksItemLog(dir)
        reconcile = Reconciliation(log)
    }

    @AfterEach
    fun close() {
        log.close()
    }

    private fun move(cause: Cause, from: Holder, to: Holder, form: ByteArray, qty: Int, damage: Int? = null) {
        log.submit(Transfer(cause, from, to, form, damage, qty, clock++))
    }

    private fun held(vararg forms: Pair<ByteArray, Int>) = forms.associate { FormKey(it.first) to it.second }

    // Dig, carry, stack: every path a session like that walks is captured, so the sum of the postings
    // is the inventory, slot for slot in form and count.
    @Test
    fun `a session of only covered paths reconciles to nothing`() {
        val dropped = ItemEntityRef(UUID.randomUUID())
        val position = WorldBlock(world, 8, 65, 8)
        move(Cause.BLOCK_DROP, Void, dropped, stone, 1)
        move(Cause.PICKUP, dropped, PlayerInv(steve, 0), stone, 1)
        move(Cause.CONTAINER_ADD, PlayerInv(steve, 0), chest, stone, 1)
        move(Cause.CONTAINER_REMOVE, chest, PlayerInv(steve, 3), stone, 1)
        move(Cause.BLOCK_PLACE, PlayerInv(steve, 3), position, stone, 1)
        move(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(steve, 0), pickaxe, 1, damage = 40)
        log.drain()

        assertEquals(emptyList<Mismatch>(), reconcile.compare(steve, held(pickaxe to 1)))
    }

    // Wear is not identity, so the tool the player is carrying is the tool the ledger handed them
    // even though every swing has moved its damage on.
    @Test
    fun `a worn tool still matches the posting that handed it over`() {
        move(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(steve, 0), pickaxe, 1, damage = 0)
        log.drain()
        assertEquals(emptyList<Mismatch>(), reconcile.compare(steve, held(pickaxe to 1)))
    }

    // Eating is not captured yet, so the loaves the player no longer has are still on their account:
    // the difference names bread, and bread is the address of the missing path.
    @Test
    fun `an uncaptured path shows up as the form it lost`() {
        move(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(steve, 0), bread, 3)
        log.drain()

        val found = reconcile.compare(steve, held(bread to 1)).single()
        assertTrue(found.form.contentEquals(bread))
        assertEquals(3, found.ledger)
        assertEquals(1, found.actual)
        assertEquals(-2, found.difference)
    }

    // The other direction, and the one that matters: items the player holds that no posting ever
    // handed them. A form the ledger has never even seen has to read as a surplus, not as a miss.
    @Test
    fun `items that arrived by no posting at all are counted as a surplus`() {
        move(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(steve, 0), stone, 1)
        log.drain()

        val found = reconcile.compare(steve, held(stone to 1, pickaxe to 2))
        assertEquals(1, found.size)
        assertTrue(found.single().form.contentEquals(pickaxe))
        assertEquals(0, found.single().ledger)
        assertEquals(2, found.single().actual)
    }

    @Test
    fun `the postings of another player are not counted`() {
        val alex = UUID.fromString("00000000-0000-4000-8000-0000000000f2")
        move(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(alex, 0), stone, 64)
        log.drain()

        assertEquals(emptyList<Mismatch>(), reconcile.compare(steve, held()))
        assertEquals(-64, reconcile.compare(alex, held()).single().difference)
    }

    // Every slot a player owns hangs off four prefixes and the slot number is not in the key, so the
    // balance has to gather armour, cursor and ender chest as well as the bag.
    @Test
    fun `the balance gathers every kind of slot the player owns`() {
        move(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(steve, 0), stone, 1)
        move(Cause.EQUIP_ARMOR, PlayerInv(steve, 0), PlayerEquip(steve, 38), stone, 1)
        move(Cause.CURSOR_TAKE, PlayerEquip(steve, 38), PlayerCursor(steve), stone, 1)
        move(Cause.CONTAINER_ADD, PlayerCursor(steve), PlayerEnder(steve, 2), stone, 1)
        log.drain()

        assertEquals(emptyList<Mismatch>(), reconcile.compare(steve, held(stone to 1)))
        assertEquals(1, reconcile.compare(steve, held()).single().ledger)
    }
}
