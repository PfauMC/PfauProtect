package io.pfaumc.pfauprotect.command
import io.pfaumc.pfauprotect.OneLedger
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.world
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class WholeTransactionsTest : OneLedger() {
    private val aliceInv = PlayerInv(alice, 9)
    private val cobblestone = byteArrayOf(1, 2, 3)
    private val torch = byteArrayOf(6, 7, 8, 9)

    // A lookup reads by position, and the item that came out of the break is held by no position at
    // all, so on its own the reader sees a debit whose other half is nowhere. Pulling the whole
    // transaction is what puts the two back on one screen.
    @Test
    fun `expanding a position row brings back the item the break produced`() {
        val position = WorldBlock(world, 12, 65, 34)
        val dropped = ItemEntityRef(UUID.randomUUID())
        log.submit(
            listOf(
                Transfer(Cause.BLOCK_DROP, position, Void, cobblestone, null, 1, T0 + 70, actor = alice),
                Transfer(Cause.BLOCK_DROP, Void, dropped, torch, null, 1, T0 + 70, actor = alice),
            )
        )
        log.drain()

        val byPosition = log.holderEntries(position, 0, Long.MAX_VALUE)
        assertEquals(listOf(position), byPosition.map { it.holder }, "the drop is not indexed by position")

        val shown = wholeTransactions(log, byPosition)
        assertEquals(setOf(position, dropped), shown.map { it.holder }.toSet())

        // Both halves already in hand must not double the answer: a break of a double block puts two
        // positions of one transaction on the same screen.
        assertEquals(shown, wholeTransactions(log, shown))
    }

    // An ordinary movement already names both ends on the one row the position holds, so pulling its
    // mirror in would print the same movement twice, once from each side of it.
    @Test
    fun `expanding an ordinary movement does not add its mirror half`() {
        val position = WorldBlock(world, 14, 65, 34)
        log.submit(Transfer(Cause.BLOCK_PLACE, aliceInv, position, cobblestone, null, 1, T0 + 90, actor = alice))
        log.drain()

        val byPosition = log.holderEntries(position, 0, Long.MAX_VALUE)
        assertEquals(listOf(aliceInv), byPosition.map { it.counterparty }, "the movement names its far end")
        assertEquals(byPosition, wholeTransactions(log, byPosition))
    }
}
