package io.pfaumc.pfauprotect

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

private const val T0 = 1_700_000_000_000L

class StorageTest {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val chest = Container(world, 100, 64, -200, 0)
    private val chestUpperSlot = Container(world, 100, 64, -200, 5)
    private val aliceInv = PlayerInv(alice, 9)
    private val farSideOfChunk = Container(world, 104, 64, -200, 0)
    private val cobblestone = byteArrayOf(1, 2, 3)
    private val pickaxe = byteArrayOf(4, 5)
    private val torch = byteArrayOf(6, 7, 8, 9)

    private lateinit var dir: Path
    private lateinit var log: RocksItemLog

    @BeforeEach
    fun openAndSeed(@TempDir tempDir: Path) {
        dir = tempDir
        log = RocksItemLog(dir)
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, cobblestone, null, 32, T0))
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chestUpperSlot, aliceInv, cobblestone, null, 5, T0 + 10))
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, pickaxe, 7, 1, T0 + 20))
        log.submit(
            Transfer(
                cause = Cause.ITEM_DESPAWN,
                from = chest,
                to = Void,
                form = cobblestone,
                damage = null,
                qty = 3,
                timestamp = T0 + 30,
                confidence = Confidence.INFERRED,
            )
        )
        log.drain()
    }

    @AfterEach
    fun closeLog() {
        log.close()
    }

    @Test
    fun `a sound ledger sweeps without a word`() {
        val report = log.sweep(100)
        assertTrue(report.reachedEnd)
        assertEquals(emptyList<String>(), report.gaps)
        assertTrue(report.checked > 0)
    }

    // Both ends of one movement landing on the same key is how half a transaction gets lost: the
    // second write silently replaces the first, and only the sum gives it away.
    @Test
    fun `a movement whose ends collapse onto one row is reported`() {
        log.submit(Transfer(Cause.CONTAINER_ADD, chest, chest, torch, null, 4, T0 + 40))
        log.drain()
        val report = log.sweep(100)
        assertEquals(1, report.gaps.size)
        assertTrue(report.gaps.single().contains("leave"), report.gaps.single())
    }

    @Test
    fun `sweeping continues where the last pass stopped`() {
        val first = log.sweep(2)
        assertEquals(2, first.checked)
        assertFalse(first.reachedEnd)
        var passes = 0
        var seen = first.checked
        while (passes++ < 10) {
            val next = log.sweep(2)
            seen += next.checked
            if (next.reachedEnd) break
        }
        assertEquals(log.sweep(100).checked, seen)
    }

    // A shulker loses its mark when it is broken, so the name has to outlive the box standing there,
    // restarts included.
    @Test
    fun `the name of a placed container outlives the session`() {
        val world = UUID.randomUUID()
        val owner = UUID.randomUUID()
        assertNull(log.ownerAt(world, 1, 2, 3))
        log.setOwnerAt(world, 1, 2, 3, owner)
        assertEquals(owner, log.ownerAt(world, 1, 2, 3))
        assertNull(log.ownerAt(world, 1, 2, 4))
        assertNull(log.ownerAt(UUID.randomUUID(), 1, 2, 3))

        log.close()
        log = RocksItemLog(dir)
        assertEquals(owner, log.ownerAt(world, 1, 2, 3))

        log.clearOwnerAt(world, 1, 2, 3)
        assertNull(log.ownerAt(world, 1, 2, 3))
    }

    @Test
    fun `holder entries read forwards and backwards`() {
        val forwards = log.holderEntries(aliceInv, 0, Long.MAX_VALUE)
        assertEquals(listOf(-32, 5, -1), forwards.map { it.qty })
        assertEquals(listOf(T0, T0 + 10, T0 + 20), forwards.map { it.timestamp })
        assertEquals(listOf(chest, chestUpperSlot, chest), forwards.map { it.counterparty })
        assertEquals(Cause.CONTAINER_REMOVE, forwards[1].cause)
        assertEquals(7, forwards[2].damage)

        val backwards = log.holderEntries(aliceInv, 0, Long.MAX_VALUE, reverse = true)
        assertEquals(forwards.reversed(), backwards)
    }

    @Test
    fun `holder entries respect the time window and the limit`() {
        assertEquals(1, log.holderEntries(aliceInv, T0 + 10, T0 + 10).size)
        assertEquals(2, log.holderEntries(aliceInv, T0 + 10, Long.MAX_VALUE).size)
        assertEquals(1, log.holderEntries(aliceInv, 0, Long.MAX_VALUE, limit = 1).size)
        assertEquals(-1, log.holderEntries(aliceInv, 0, Long.MAX_VALUE, reverse = true, limit = 1).single().qty)
        assertTrue(log.holderEntries(PlayerInv(UUID.randomUUID(), 0), 0, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `region entries cover the block box and nothing else`() {
        val inBox = log.regionEntries(world, 96, -208, 112, -192, 0, Long.MAX_VALUE)
        assertEquals(listOf(32, -5, 1, -3), inBox.map { it.qty })
        assertTrue(inBox.all { it.holder is Container })
        assertEquals(5, (inBox[1].holder as Container).slot)

        assertTrue(log.regionEntries(world, 5000, 5000, 5016, 5016, 0, Long.MAX_VALUE).isEmpty())
        assertTrue(log.regionEntries(UUID.randomUUID(), 96, -208, 112, -192, 0, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `region entries see the far half of a chunk and respect the time window`() {
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, farSideOfChunk, cobblestone, null, 2, T0 + 50))
        log.drain()

        val whole = log.regionEntries(world, 96, -208, 112, -192, 0, Long.MAX_VALUE)
        assertEquals(5, whole.size)
        assertEquals(1, whole.count { it.holder == farSideOfChunk })

        val window = log.regionEntries(world, 96, -208, 112, -192, T0 + 10, T0 + 30)
        assertEquals(listOf(T0 + 10, T0 + 20, T0 + 30), window.map { it.timestamp })
        assertTrue(log.regionEntries(world, 96, -208, 112, -192, T0 + 60, Long.MAX_VALUE).isEmpty())
    }

    // A capped region read has to keep the newest rows, not the oldest, or every lookup of a busy
    // area answers with its earliest history.
    @Test
    fun `a reversed region read keeps the newest rows`() {
        val forwards = log.regionEntries(world, 96, -208, 112, -192, 0, Long.MAX_VALUE, limit = 2)
        assertEquals(listOf(T0, T0 + 10), forwards.map { it.timestamp })

        val backwards = log.regionEntries(world, 96, -208, 112, -192, 0, Long.MAX_VALUE, reverse = true, limit = 2)
        assertEquals(listOf(T0 + 30, T0 + 20), backwards.map { it.timestamp })
    }

    @Test
    fun `an oversized region is refused instead of scanned`() {
        assertThrows(IllegalArgumentException::class.java) {
            log.regionEntries(world, 0, 0, 100_000, 0, 0, Long.MAX_VALUE)
        }
    }

    @Test
    fun `transaction entries find the other half of the pair`() {
        val fromChest = log.holderEntries(chest, 0, Long.MAX_VALUE)
        val paired = log.transactionEntries(fromChest.first())
        assertEquals(2, paired.size)
        assertEquals(1, paired.map { it.txId }.distinct().size)
        assertEquals(0, paired.sumOf { it.qty })
        assertEquals(setOf(chest, aliceInv), paired.map { it.holder }.toSet())

        val intoVoid = fromChest.single { it.counterparty === Void }
        assertEquals(listOf(intoVoid), log.transactionEntries(intoVoid))
    }

    @Test
    fun `every transaction that avoids the void sums to zero`() {
        val stored = log.holderEntries(aliceInv, 0, Long.MAX_VALUE, limit = 1000) +
            log.holderEntries(chest, 0, Long.MAX_VALUE, limit = 1000)
        val byTransaction = stored.distinctBy { it.txId to it.holder }.groupBy { it.txId }
        assertEquals(4, byTransaction.size)

        var balanced = 0
        for ((txId, legs) in byTransaction) {
            if (legs.any { it.holder === Void || it.counterparty === Void }) continue
            assertEquals(2, legs.size, "transaction $txId lost a leg")
            assertEquals(0, legs.sumOf { it.qty }, "transaction $txId does not balance")
            assertEquals(1, legs.map { it.itemFormId }.distinct().size, "transaction $txId split across forms")
            balanced++
        }
        assertEquals(3, balanced)
    }

    @Test
    fun `equal forms are interned and readable by id`() {
        val fromChest = log.holderEntries(chest, 0, Long.MAX_VALUE)
        assertEquals(fromChest[0].itemFormId, fromChest[1].itemFormId)
        assertNotEquals(fromChest[0].itemFormId, fromChest[2].itemFormId)
        assertArrayEquals(cobblestone, log.form(fromChest[0].itemFormId))
        assertArrayEquals(pickaxe, log.form(fromChest[2].itemFormId))
        assertEquals(null, log.form(9999))
    }

    @Test
    fun `a reopened log keeps entries forms and counters`() {
        val before = log.holderEntries(chest, 0, Long.MAX_VALUE)
        log.close()
        log = RocksItemLog(dir)

        assertEquals(before, log.holderEntries(chest, 0, Long.MAX_VALUE))
        assertArrayEquals(cobblestone, log.form(before[0].itemFormId))

        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, cobblestone, null, 8, T0 + 40))
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 1, T0 + 50))
        log.drain()
        val after = log.holderEntries(chest, 0, Long.MAX_VALUE)
        assertEquals(before.size + 2, after.size)
        assertEquals(after.size, after.map { it.txId }.distinct().size)
        assertEquals(before[0].itemFormId, after[after.size - 2].itemFormId)

        val freshForm = after.last().itemFormId
        assertTrue(before.none { it.itemFormId == freshForm }, "a form first seen after the reopen took an old id")
        assertArrayEquals(torch, log.form(freshForm))
        assertArrayEquals(cobblestone, log.form(before[0].itemFormId))
        assertArrayEquals(pickaxe, log.form(before[2].itemFormId))
    }

    @Test
    fun `analytical queries refuse instead of answering emptily`() {
        assertThrows(UnsupportedOperationException::class.java) { log.balanceOf(aliceInv, 0) }
        assertThrows(UnsupportedOperationException::class.java) { log.formPath(0) }
    }
}
