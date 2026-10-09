package io.pfaumc.pfauprotect.rollback

import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.RocksItemLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

private const val T0 = 1_700_000_000_000L

class ConfiscationTest {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000007")
    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val bob = UUID.fromString("00000000-0000-4000-8000-0000000000b0")
    private val diamond = byteArrayOf(9)
    private val stone = byteArrayOf(3)
    private val chest = Container(world, 1, 64, 1, 0)

    private lateinit var ledger: RocksItemLog

    @BeforeEach
    fun open(@TempDir dir: Path) {
        ledger = RocksItemLog(dir)
    }

    @AfterEach
    fun close() {
        ledger.close()
    }

    private fun returned(lead: io.pfaumc.pfauprotect.model.Holder, qty: Int): Tally {
        val id = ledger.formId(diamond)!!
        return Tally().apply { traces += Trace(lead, id, qty) }
    }

    private fun owed(tally: Tally) = owedFor(ledger, tally).associate { it.taker to it.qty }

    // Taken straight out of the chest into a hand: that hand owes it.
    @Test
    fun `what went straight into a player's hands is owed by that player`() {
        ledger.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 4), diamond, null, 7, T0))
        ledger.drain()

        assertEquals(mapOf(Carrier(bob) to 7), owed(returned(PlayerInv(bob, 4), 7)))
    }

    // Bob put the loot into a chest of his own far away: what he no longer holds is looked for there,
    // newest first, and a chest he only took from is no stash.
    @Test
    fun `the chests a carrier put the loot into are where the rest is looked for`() {
        val his = Container(world, 500, 64, 500, 3)
        val older = Container(world, 600, 64, 600, 0)
        ledger.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 4), diamond, null, 7, T0))
        ledger.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 4), older, diamond, null, 2, T0 + 1))
        ledger.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 4), his, diamond, null, 5, T0 + 2))
        ledger.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 5), chest, stone, null, 1, T0 + 3))
        ledger.drain()

        val tally = returned(PlayerInv(bob, 4), 7).apply { since = T0 }
        val owed = owedFor(ledger, tally).single()
        assertEquals(listOf(his.copy(slot = 0), older), owed.stashes)
    }

    // Bob crafted the loot into a block: the block stands for nine diamonds, and is what is looked for once
    // his hands and his chests have none of them.
    @Test
    fun `what a carrier crafted the loot into stands for it`() {
        val block = byteArrayOf(57)
        val table = Container(world, 9, 64, 9, 0)
        ledger.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 4), diamond, null, 9, T0))
        ledger.submit((1..9).map { Transfer(Cause.CRAFT_CONSUME, table.copy(slot = it), Void, diamond, null, 1, T0 + 1) } +
            Transfer(Cause.CRAFT_RESULT, Void, PlayerInv(bob, 5), block, null, 1, T0 + 1))
        ledger.drain()

        val tally = returned(PlayerInv(bob, 4), 9).apply { since = T0 }
        val conversion = owedFor(ledger, tally).single().conversions.single()
        assertEquals(ledger.formId(block), conversion.made)
        assertEquals(9, conversion.inputsEach)
    }

    // Bob built steps of the loot that the same rollback takes away: those are back already, and only what he
    // set where the rollback does not reach is still owed (O13).
    @Test
    fun `what a carrier set as blocks the rollback takes away is not owed again`() {
        val steps = WorldBlock(world, 3, 64, 3)
        val elsewhere = WorldBlock(world, 300, 64, 300)
        ledger.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 4), diamond, null, 7, T0))
        ledger.submit(Transfer(Cause.BLOCK_PLACE, PlayerInv(bob, 4), steps, diamond, null, 1, T0 + 1))
        ledger.submit(Transfer(Cause.BLOCK_PLACE, PlayerInv(bob, 4), elsewhere, diamond, null, 1, T0 + 2))
        ledger.drain()

        val tally = returned(PlayerInv(bob, 4), 7).apply { since = T0; undone += steps }
        assertEquals(mapOf(Carrier(bob) to 6), owed(tally))
        tally.undone += elsewhere
        tally.traces.clear()
        tally.traces += Trace(PlayerInv(bob, 4), ledger.formId(diamond)!!, 2)
        assertEquals(emptyMap<Taker, Int>(), owed(tally))
    }

    // A helmet that fell off the head goes back onto it, a shield out of the off hand back into it.
    @Test
    fun `what fell out of a killed player is known by the slot it fell from`() {
        val helmet = byteArrayOf(41)
        val shield = byteArrayOf(42)
        val head = ItemEntityRef(UUID.randomUUID())
        val hand = ItemEntityRef(UUID.randomUUID())
        ledger.submit(Transfer(Cause.DEATH_DROP, io.pfaumc.pfauprotect.model.PlayerEquip(alice, 39), head, helmet, null, 1, T0))
        ledger.submit(Transfer(Cause.DEATH_DROP, io.pfaumc.pfauprotect.model.PlayerEquip(alice, 40), hand, shield, null, 1, T0))
        ledger.drain()
        val death = io.pfaumc.pfauprotect.storage.EntityRow(
            0, 64, 0, T0, 1, 0, io.pfaumc.pfauprotect.model.EntityKind.PLAYER_DIED, Cause.PLAYER_KILLED,
            "minecraft:player", alice, actor = bob, drops = listOf(head.uuid, hand.uuid),
        )

        assertEquals(mapOf(ledger.formId(helmet) to listOf(39), ledger.formId(shield) to listOf(40)), fellFrom(ledger, death))
    }

    // Bob carried the diamonds to a chest of his own in the same area, and the rollback took them back
    // out of it. Taking them from his hands as well would take his own diamonds.
    @Test
    fun `what a rollback took out of the taker's own chest is not taken from him again`() {
        ledger.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 4), diamond, null, 7, T0))
        ledger.drain()
        val id = ledger.formId(diamond)!!
        val tally = returned(PlayerInv(bob, 4), 7).apply { traces += Trace(PlayerInv(bob, 4), id, -5) }

        assertEquals(mapOf(Carrier(bob) to 2), owed(tally))
    }

    // A chest broken open: the pile fell, part of it merged into another pile that Alice picked up, and
    // the rest still lies there. Each part is owed where it is now.
    @Test
    fun `a dropped pile is followed through a merge to the player who picked it up`() {
        val fell = ItemEntityRef(UUID.randomUUID())
        val other = ItemEntityRef(UUID.randomUUID())
        ledger.submit(Transfer(Cause.CONTAINER_BREAK_DROP, chest, fell, diamond, null, 10, T0))
        ledger.submit(Transfer(Cause.ITEM_MERGE, fell, other, diamond, null, 4, T0 + 1))
        ledger.submit(Transfer(Cause.PICKUP, other, PlayerInv(alice, 0), diamond, null, 4, T0 + 2))
        ledger.drain()

        assertEquals(mapOf(Lying(fell.uuid) to 6, Carrier(alice) to 4), owed(returned(fell, 10)))
    }

    // A cow the rollback brought back dropped beef when it died, and Bob ate none of it yet: the whole
    // pile it was born as is owed by whoever picked it up.
    @Test
    fun `a pile that fell out of what came back is owed whole`() {
        val pile = ItemEntityRef(UUID.randomUUID())
        ledger.submit(Transfer(Cause.MOB_DROP, Void, pile, diamond, null, 3, T0))
        ledger.submit(Transfer(Cause.PICKUP, pile, PlayerInv(bob, 2), diamond, null, 3, T0 + 1))
        ledger.drain()

        assertEquals(mapOf(Carrier(bob) to 3), owed(Tally().apply { piles += pile.uuid }))
    }

    // Bob killed Alice. Of what fell out of her, Bob picked one pile up, one burned in lava, and one she
    // picked up again herself. She is owed back the first from Bob and the second out of nothing; the
    // third she has.
    @Test
    fun `a killed player is owed back what others took and what is gone`() {
        val picked = ItemEntityRef(UUID.randomUUID())
        val burned = ItemEntityRef(UUID.randomUUID())
        val regained = ItemEntityRef(UUID.randomUUID())
        for (pile in listOf(picked, burned, regained)) {
            ledger.submit(Transfer(Cause.DEATH_DROP, PlayerInv(alice, 0), pile, diamond, null, 2, T0))
        }
        ledger.submit(Transfer(Cause.PICKUP, picked, PlayerInv(bob, 1), diamond, null, 2, T0 + 1))
        ledger.submit(Transfer(Cause.ITEM_DESTROY_FIRE, burned, Void, diamond, null, 2, T0 + 1))
        ledger.submit(Transfer(Cause.PICKUP, regained, PlayerInv(alice, 3), diamond, null, 2, T0 + 1))
        ledger.drain()
        val death = io.pfaumc.pfauprotect.storage.EntityRow(
            0, 64, 0, T0, 1, 0, io.pfaumc.pfauprotect.model.EntityKind.PLAYER_DIED, Cause.PLAYER_KILLED,
            "minecraft:player", alice, actor = bob, drops = listOf(picked.uuid, burned.uuid, regained.uuid),
        )

        val back = restitutionFor(ledger, death).associate { it.taker to it.qty }
        assertEquals(mapOf(Carrier(bob) to 2, Vanished(burned.uuid) to 2), back)

        // Given back once, the births are marked and a second rollback owes her nothing.
        ledger.submit(Transfer(Cause.ROLLBACK, Void, Void, diamond, null, 1, T0 + 2, reverts = pileBirths(ledger, death).values.flatten()))
        ledger.drain()
        assertTrue(restitutionFor(ledger, death).isEmpty())
    }

    // A hopper took it into some other chest: past where a rollback follows, and owed by nobody.
    @Test
    fun `a pile a hopper took is beyond reach`() {
        val fell = ItemEntityRef(UUID.randomUUID())
        ledger.submit(Transfer(Cause.CONTAINER_BREAK_DROP, chest, fell, diamond, null, 3, T0))
        ledger.submit(Transfer(Cause.HOPPER_PULL_GROUND, fell, Container(world, 9, 60, 9, 0), diamond, null, 3, T0 + 1))
        ledger.drain()

        assertTrue(owed(returned(fell, 3)).isEmpty())
    }

    // A break writes the block's item off and the drop's birth in one transaction. Once the block is back,
    // what fell out of it is owed by whoever picked it up — but only if every position the transaction
    // took was put back.
    @Test
    fun `the drops of a break are owed only once every position of it came back`() {
        val here = WorldBlock(world, 2, 64, 2)
        val there = WorldBlock(world, 3, 64, 2)
        val drop = ItemEntityRef(UUID.randomUUID())
        ledger.submit(
            listOf(
                Transfer(Cause.BLOCK_DROP, here, Void, stone, null, 1, T0, actor = bob),
                Transfer(Cause.BLOCK_DROP, there, Void, stone, null, 1, T0, actor = bob),
                Transfer(Cause.BLOCK_DROP, Void, drop, stone, null, 2, T0, actor = bob),
            )
        )
        ledger.submit(Transfer(Cause.PICKUP, drop, PlayerInv(bob, 1), stone, null, 2, T0 + 1))
        ledger.drain()
        val loss = ledger.holderEntries(here, 0, Long.MAX_VALUE).single()

        val half = Tally().apply {
            restored += here
            breaks += loss
        }
        assertTrue(owed(half).isEmpty(), "half the break came back")
        val whole = Tally().apply {
            restored += listOf(here, there)
            breaks += loss
        }
        assertEquals(mapOf(Carrier(bob) to 2), owed(whole))
    }
}
