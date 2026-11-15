package io.pfaumc.pfauprotect.rollback

import io.pfaumc.pfauprotect.OneLedger
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.command.Crossing
import io.pfaumc.pfauprotect.command.crosses
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.model.PlayerEquip
import io.pfaumc.pfauprotect.storage.EntityRow
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.MenuSlot
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ConfiscationTest : OneLedger() {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000007")
    private val diamond = byteArrayOf(9)
    private val stone = byteArrayOf(3)
    private val chest = Container(world, 1, 64, 1, 0)

    private fun returned(lead: Holder, qty: Int): Tally {
        val id = log.forms.lookup(diamond)!!
        return Tally().apply { traces += Trace(lead, id, qty) }
    }

    // Summed, so a debt split over several entries counts all of them rather than whichever came last.
    private fun owed(tally: Tally): Map<Taker, Int> =
        owedFor(log, tally).groupingBy { it.taker }.fold(0) { sum, owed -> sum + owed.qty }

    private fun deathOf(vararg drops: ItemEntityRef) = EntityRow(
        0, 64, 0, T0, 1, 0, EntityKind.PLAYER_DIED, Cause.PLAYER_KILLED,
        "minecraft:player", alice, actor = bob, drops = drops.map { it.uuid },
    )

    // Bob put the loot into a chest of his own far away: what he no longer holds is looked for there,
    // newest first, and a chest he only took from is no stash.
    @Test
    fun `the chests a carrier put the loot into are where the rest is looked for`() {
        val his = Container(world, 500, 64, 500, 3)
        val older = Container(world, 600, 64, 600, 0)
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 4), diamond, null, 7, T0))
        log.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 4), older, diamond, null, 2, T0 + 1))
        log.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 4), his, diamond, null, 5, T0 + 2))
        log.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 5), chest, stone, null, 1, T0 + 3))
        log.drain()

        val tally = returned(PlayerInv(bob, 4), 7).apply { since = T0 }
        val owed = owedFor(log, tally).single()
        assertEquals(listOf(his.copy(slot = 0), older), owed.stashes)
        // Written with the debt of an offline player, whose join follows the stashes again from it.
        assertEquals(T0, owed.since)
    }

    // A debt with no window behind it has nothing to follow, and the loot bob hid would stay his.
    @Test
    fun `an offline carrier's debt is followed on join from when its window opened`() {
        val his = Container(world, 500, 64, 500, 3)
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 4), diamond, null, 7, T0))
        log.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 4), his, diamond, null, 7, T0 + 2))
        log.drain()
        log.owe(bob, log.forms.lookup(diamond)!!, 7, alice, since = T0)

        val trail = trailsOf(log, bob, log.owedBy(bob)).values.single()
        assertEquals(listOf(his.copy(slot = 0)), trail.stashes)
    }

    // Bob packed the loot into a shulker box and broke it. Left lying, the box is the pile the loot is
    // taken out of; picked up and set down again far away, it is a container like the chests.
    @Test
    fun `the loot packed into a box is followed to where the box is now`() {
        val box = byteArrayOf(41)
        val stood = Container(world, 20, 64, 20, 0)
        val owner = UUID.randomUUID()
        val pile = ItemEntityRef(UUID.randomUUID())
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 4), diamond, null, 16, T0))
        log.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 4), stood, diamond, null, 16, T0 + 1))
        log.submit(
            listOf(
                Transfer(Cause.BLOCK_DROP, WorldBlock(world, 20, 64, 20), Void, box, null, 1, T0 + 100, actor = bob),
                Transfer(Cause.BLOCK_DROP, Void, pile, box, null, 1, T0 + 100, actor = bob),
            )
        )
        log.submit(Transfer(Cause.CONTAINER_BREAK_PACK, stood, Nested(owner, 0), diamond, null, 16, T0 + 140))
        log.drain()
        val tally = returned(PlayerInv(bob, 4), 16).apply { since = T0 }

        val lying = owedFor(log, tally).single()
        assertEquals(listOf(pile.uuid), lying.boxes)

        val far = Container(world, 900, 64, 900, 5)
        log.submit(Transfer(Cause.PICKUP, pile, PlayerInv(bob, 7), box, null, 1, T0 + 200))
        log.submit(Transfer(Cause.CONTAINER_PLACE_UNPACK, Nested(owner, 0), far, diamond, null, 16, T0 + 300))
        log.drain()
        val setDown = owedFor(log, tally).single()
        assertEquals(listOf(stood, far.copy(slot = 0)), setDown.stashes)
        assertTrue(setDown.boxes.isEmpty())
    }

    // Bob crafted the loot into a block: the block stands for nine diamonds, and is what is looked for once
    // his hands and his chests have none of them.
    @Test
    fun `what a carrier crafted the loot into stands for it`() {
        val block = byteArrayOf(57)
        val table = Container(world, 9, 64, 9, 0)
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 4), diamond, null, 9, T0))
        log.submit((1..9).map { Transfer(Cause.CRAFT_CONSUME, table.copy(slot = it), Void, diamond, null, 1, T0 + 1) } +
            Transfer(Cause.CRAFT_RESULT, Void, PlayerInv(bob, 5), block, null, 1, T0 + 1))
        log.drain()

        val tally = returned(PlayerInv(bob, 4), 9).apply { since = T0 }
        val conversion = owedFor(log, tally).single().conversions.single()
        assertEquals(log.forms.lookup(block), conversion.made)
        assertEquals(9, conversion.inputsEach)
    }

    // Bob took something out of a plugin's window, which keeps no rows of its own: following him reads past
    // it rather than failing the whole rollback.
    @Test
    fun `what a carrier took out of a menu nobody owns does not stop the following`() {
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 4), diamond, null, 9, T0))
        log.submit(Transfer(Cause.CONTAINER_REMOVE, MenuSlot(3, 0), PlayerInv(bob, 6), stone, null, 1, T0 + 1))
        log.drain()

        val tally = returned(PlayerInv(bob, 4), 9).apply { since = T0 }
        assertTrue(owedFor(log, tally).single().conversions.isEmpty())
    }

    // Bob built steps of the loot that the same rollback takes away: those are back already, and only what he
    // set where the rollback does not reach is still owed.
    @Test
    fun `what a carrier set as blocks the rollback takes away is not owed again`() {
        val steps = WorldBlock(world, 3, 64, 3)
        val elsewhere = WorldBlock(world, 300, 64, 300)
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 4), diamond, null, 7, T0))
        log.submit(Transfer(Cause.BLOCK_PLACE, PlayerInv(bob, 4), steps, diamond, null, 1, T0 + 1))
        log.submit(Transfer(Cause.BLOCK_PLACE, PlayerInv(bob, 4), elsewhere, diamond, null, 1, T0 + 2))
        log.drain()

        val tally = returned(PlayerInv(bob, 4), 7).apply { since = T0; undone += steps }
        assertEquals(mapOf(Carrier(bob) to 6), owed(tally))
        tally.undone += elsewhere
        tally.traces.clear()
        tally.traces += Trace(PlayerInv(bob, 4), log.forms.lookup(diamond)!!, 2)
        assertEquals(emptyMap<Taker, Int>(), owed(tally))
    }

    // A helmet that fell off the head goes back onto it, a shield out of the off hand back into it.
    @Test
    fun `what fell out of a killed player is known by the slot it fell from`() {
        val helmet = byteArrayOf(41)
        val shield = byteArrayOf(42)
        val head = ItemEntityRef(UUID.randomUUID())
        val hand = ItemEntityRef(UUID.randomUUID())
        log.submit(Transfer(Cause.DEATH_DROP, PlayerEquip(alice, 39), head, helmet, null, 1, T0))
        log.submit(Transfer(Cause.DEATH_DROP, PlayerEquip(alice, 40), hand, shield, null, 1, T0))
        log.drain()

        assertEquals(mapOf(log.forms.lookup(helmet) to listOf(39), log.forms.lookup(shield) to listOf(40)), Fallen(log, deathOf(head, hand)).slots())
    }

    // Bob carried the diamonds to a chest of his own in the same area, and the rollback took them back
    // out of it. Taking them from his hands as well would take his own diamonds.
    @Test
    fun `what a rollback took out of the taker's own chest is not taken from him again`() {
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 4), diamond, null, 7, T0))
        log.drain()
        val id = log.forms.lookup(diamond)!!
        val tally = returned(PlayerInv(bob, 4), 7).apply { traces += Trace(PlayerInv(bob, 4), id, -5) }

        assertEquals(mapOf(Carrier(bob) to 2), owed(tally))
    }

    // A chest broken open: the pile fell, part of it merged into another pile that Alice picked up, and
    // the rest still lies there. Each part is owed where it is now.
    @Test
    fun `a dropped pile is followed through a merge to the player who picked it up`() {
        val fell = ItemEntityRef(UUID.randomUUID())
        val other = ItemEntityRef(UUID.randomUUID())
        log.submit(Transfer(Cause.CONTAINER_BREAK_DROP, chest, fell, diamond, null, 10, T0))
        log.submit(Transfer(Cause.ITEM_MERGE, fell, other, diamond, null, 4, T0 + 1))
        log.submit(Transfer(Cause.PICKUP, other, PlayerInv(alice, 0), diamond, null, 4, T0 + 2))
        log.drain()

        assertEquals(mapOf(Lying(fell.uuid) to 6, Carrier(alice) to 4), owed(returned(fell, 10)))
    }

    // A cow the rollback brought back dropped beef when it died, and Bob ate none of it yet: the whole
    // pile it was born as is owed by whoever picked it up.
    @Test
    fun `a pile that fell out of what came back is owed whole`() {
        val pile = ItemEntityRef(UUID.randomUUID())
        log.submit(Transfer(Cause.MOB_DROP, Void, pile, diamond, null, 3, T0))
        log.submit(Transfer(Cause.PICKUP, pile, PlayerInv(bob, 2), diamond, null, 3, T0 + 1))
        log.drain()

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
            log.submit(Transfer(Cause.DEATH_DROP, PlayerInv(alice, 0), pile, diamond, null, 2, T0))
        }
        log.submit(Transfer(Cause.PICKUP, picked, PlayerInv(bob, 1), diamond, null, 2, T0 + 1))
        log.submit(Transfer(Cause.ITEM_DESTROY_FIRE, burned, Void, diamond, null, 2, T0 + 1))
        log.submit(Transfer(Cause.PICKUP, regained, PlayerInv(alice, 3), diamond, null, 2, T0 + 1))
        log.drain()
        val death = deathOf(picked, burned, regained)

        val back = Fallen(log, death).restitution().associate { it.taker to it.qty }
        assertEquals(mapOf(Carrier(bob) to 2, Vanished(burned.uuid) to 2), back)

        val births = listOf(picked, burned, regained).flatMap { pile -> log.holderEntries(pile, 0, Long.MAX_VALUE).filter { it.cause == Cause.DEATH_DROP }.map { it.ref } }
        assertEquals(3, births.size)
        log.submit(Transfer(Cause.ROLLBACK, Void, Void, diamond, null, 1, T0 + 2, reverts = births))
        log.drain()
        assertTrue(Fallen(log, death).restitution().isEmpty())
    }

    // A phantom killed Alice, nobody behind it. `u:alice a:-inventory` gives her the loss back from wherever it is
    // now, as CoreProtect does: Bob's pile from Bob, the burnt one and the bread she ate out of nothing; what she
    // picked up again and took back out of the chest she has. Once given, never again; and `a:+inventory` takes
    // away what she took out of somebody's chest, but neither her own pile nor her own stone.
    @Test
    fun `what crossed a player's hands is undone from where it is now`() {
        val picked = ItemEntityRef(UUID.randomUUID())
        val burned = ItemEntityRef(UUID.randomUUID())
        val regained = ItemEntityRef(UUID.randomUUID())
        val bread = byteArrayOf(5)
        for (pile in listOf(picked, burned, regained)) {
            log.submit(Transfer(Cause.DEATH_DROP, PlayerInv(alice, 7), pile, diamond, null, 2, T0))
        }
        log.submit(Transfer(Cause.PICKUP, picked, PlayerInv(bob, 1), diamond, null, 2, T0 + 1))
        log.submit(Transfer(Cause.ITEM_DESTROY_FIRE, burned, Void, diamond, null, 2, T0 + 1))
        log.submit(Transfer(Cause.PICKUP, regained, PlayerInv(alice, 3), diamond, null, 2, T0 + 1))
        log.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 5), chest, stone, null, 3, T0 + 2))
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(alice, 5), stone, null, 1, T0 + 3))
        log.submit(Transfer(Cause.CONSUME_FOOD, PlayerInv(alice, 6), Void, bread, null, 1, T0 + 4))
        log.submit(Transfer(Cause.CONTAINER_REMOVE, Container(world, 9, 64, 9, 2), PlayerInv(alice, 8), diamond, null, 4, T0 + 4))
        log.drain()
        fun moves(inward: Boolean, outward: Boolean) =
            handMoves(log, alice, 0, Long.MAX_VALUE, inward = inward, outward = outward) { crosses(it, Crossing(inward, outward, containers = true)) { false } }!!
        fun owed(moves: List<HandMove>) = moves.flatMap { it.owed }.groupingBy { it.taker to it.formId }.fold(0) { sum, it -> sum + it.qty }
        val id = { form: ByteArray -> log.forms.lookup(form)!! }

        val out = moves(inward = false, outward = true)
        assertTrue(out.all { it.back })
        assertEquals(
            mapOf(
                (Carrier(bob) to id(diamond)) to 2, (Vanished(burned.uuid) to id(diamond)) to 2,
                (Stored(chest) to id(stone)) to 2, (Vanished(alice) to id(bread)) to 1,
            ),
            owed(out),
        )
        // Back into the slot it fell out of.
        assertEquals(listOf(7), out.first { it.owed.any { owed -> owed.taker == Carrier(bob) } }.slots[id(diamond)])

        log.submit(Transfer(Cause.ROLLBACK, Void, Void, diamond, null, 1, T0 + 5, reverts = out.flatMap { it.marks }))
        log.drain()
        assertTrue(moves(inward = false, outward = true).isEmpty())
        // The rollback of whoever killed her finds the same piles given back.
        assertTrue(Fallen(log, deathOf(picked, burned)).restitution().isEmpty())

        val taken = moves(inward = true, outward = false)
        assertTrue(taken.none { it.back })
        assertEquals(mapOf((Carrier(alice) to id(diamond)) to 4), owed(taken))
    }

    // What a hands rollback must not undo: diamonds taken out of Bob's chest and put back, a sword still on a
    // stand, a crafting's inputs (the block is still hers), an earlier rollback's own give. An arrow that
    // despawned comes back out of nothing. With both of them named, what Bob picked up of Alice's drop goes back
    // through her half only, not taken from him twice.
    @Test
    fun `a hands rollback leaves what came back, what still exists and what was made`() {
        val bobs = Container(world, 40, 64, 40, 0)
        val frame = EntitySlot(UUID.randomUUID(), 0)
        val arrow = EntitySlot(UUID.randomUUID(), 0)
        val pile = ItemEntityRef(UUID.randomUUID())
        val sword = byteArrayOf(6)
        val block = byteArrayOf(7)
        log.submit(Transfer(Cause.CONTAINER_REMOVE, bobs, PlayerInv(alice, 0), diamond, null, 4, T0))
        log.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 0), bobs, diamond, null, 4, T0 + 1))
        log.submit(Transfer(Cause.ARMOR_STAND_SWAP, PlayerInv(alice, 1), frame, sword, null, 1, T0 + 2))
        log.submit(Transfer(Cause.CRAFT_CONSUME, PlayerInv(alice, 2), Void, diamond, null, 9, T0 + 3))
        log.submit(Transfer(Cause.CRAFT_RESULT, Void, PlayerInv(alice, 2), block, null, 1, T0 + 3))
        log.submit(Transfer(Cause.ROLLBACK, Void, PlayerInv(alice, 3), stone, null, 5, T0 + 4))
        log.submit(Transfer(Cause.PROJ_SHOT, PlayerInv(alice, 4), arrow, stone, null, 1, T0 + 5))
        log.submit(Transfer(Cause.PROJ_DESPAWN, arrow, Void, stone, null, 1, T0 + 6))
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(alice, 5), pile, sword, null, 1, T0 + 7))
        log.submit(Transfer(Cause.PICKUP, pile, PlayerInv(bob, 0), sword, null, 1, T0 + 8))
        // A join's load, an admin's /clear and /give: nothing her hands did, neither given back nor taken.
        log.submit(Transfer(Cause.INVENTORY_LOAD, Void, PlayerInv(alice, 6), diamond, null, 20, T0 + 9))
        log.submit(Transfer(Cause.CMD_CLEAR, PlayerInv(alice, 6), Void, diamond, null, 20, T0 + 10))
        log.submit(Transfer(Cause.CMD_GIVE, Void, PlayerInv(alice, 6), diamond, null, 5, T0 + 11))
        log.drain()
        val both = Crossing(inward = true, outward = true, containers = true)
        val keeps = { entry: LedgerEntry -> crosses(entry, both) { false } }
        val id = { form: ByteArray -> log.forms.lookup(form)!! }

        val hers = handMoves(log, alice, 0, Long.MAX_VALUE, setOf(alice, bob), keeps = keeps)!!
        assertEquals(
            setOf(Vanished(arrow.uuid) to id(stone), Carrier(bob) to id(sword)),
            hers.flatMap { it.owed }.map { it.taker to it.formId }.toSet(),
        )
        assertTrue(hers.all { it.back })
        assertTrue(handMoves(log, bob, 0, Long.MAX_VALUE, setOf(alice, bob), keeps = keeps)!!.isEmpty())
        // Bob alone: what he picked up is taken from him.
        assertEquals(listOf(false), handMoves(log, bob, 0, Long.MAX_VALUE, setOf(bob), keeps = keeps)!!.map { it.back })
    }

    // Two of Alice's piles merged into one, and Bob picked one diamond out of it: three lie there, not four
    // twice over. Each loss gets its share of what lies there and of Bob's, not all of it.
    @Test
    fun `losses merged into one pile share it`() {
        val first = ItemEntityRef(UUID.randomUUID())
        val second = ItemEntityRef(UUID.randomUUID())
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(alice, 0), first, diamond, null, 2, T0))
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(alice, 1), second, diamond, null, 2, T0 + 1))
        log.submit(Transfer(Cause.ITEM_MERGE, second, first, diamond, null, 2, T0 + 2))
        log.submit(Transfer(Cause.PICKUP, first, PlayerInv(bob, 0), diamond, null, 1, T0 + 3))
        log.drain()

        val owed = handMoves(log, alice, 0, Long.MAX_VALUE) { crosses(it, Crossing(inward = false, outward = true, containers = true)) { false } }!!
            .flatMap { it.owed }.groupingBy { it.taker }.fold(0) { sum, it -> sum + it.qty }
        assertEquals(mapOf(Lying(first.uuid) to 3, Carrier(bob) to 1), owed)
    }

    // Alice's and Bob's drops merged into one pile of four: a rollback of both shares it between them.
    @Test
    fun `two players' piles merged into one are shared between them`() {
        val hers = ItemEntityRef(UUID.randomUUID())
        val his = ItemEntityRef(UUID.randomUUID())
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(alice, 0), hers, diamond, null, 2, T0))
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(bob, 0), his, diamond, null, 2, T0 + 1))
        log.submit(Transfer(Cause.ITEM_MERGE, his, hers, diamond, null, 2, T0 + 2))
        log.drain()
        val spent = HashMap<Any, Int>()
        val out = { entry: LedgerEntry -> crosses(entry, Crossing(inward = false, outward = true, containers = true)) { false } }

        val owed = listOf(alice, bob).flatMap { handMoves(log, it, 0, Long.MAX_VALUE, setOf(alice, bob), spent, keeps = out)!! }
            .flatMap { it.owed }.sumOf { it.qty }
        assertEquals(4, owed)
    }

    // Alice put 3 diamonds into a chest and Bob took 2 out: Bob owes 2, the chest 1. Her dropped stone a hopper
    // swallowed is beyond reach, not given again out of nothing. Bob's pile merged into hers and she picked the
    // four up: `+inventory` takes Bob's two, not her own.
    @Test
    fun `a hands rollback follows the chest, leaves the hopper and splits a merged pickup`() {
        val bread = byteArrayOf(5)
        val hopper = Container(world, 3, 64, 3, 0)
        val dropped = ItemEntityRef(UUID.randomUUID())
        val hers = ItemEntityRef(UUID.randomUUID())
        val his = ItemEntityRef(UUID.randomUUID())
        log.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 0), chest, diamond, null, 3, T0))
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 0), diamond, null, 2, T0 + 1))
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(alice, 1), dropped, stone, null, 1, T0 + 2))
        log.submit(Transfer(Cause.HOPPER_PULL_GROUND, dropped, hopper, stone, null, 1, T0 + 3))
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(alice, 2), hers, bread, null, 2, T0 + 4))
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(bob, 1), his, bread, null, 2, T0 + 5))
        log.submit(Transfer(Cause.ITEM_MERGE, his, hers, bread, null, 2, T0 + 6))
        log.submit(Transfer(Cause.PICKUP, hers, PlayerInv(alice, 2), bread, null, 4, T0 + 7))
        log.drain()
        fun moves(inward: Boolean, outward: Boolean) =
            handMoves(log, alice, 0, Long.MAX_VALUE, inward = inward, outward = outward) { crosses(it, Crossing(inward, outward, containers = true)) { false } }!!
        fun owed(moves: List<HandMove>) = moves.flatMap { it.owed }.groupingBy { it.taker }.fold(0) { sum, it -> sum + it.qty }

        assertEquals(mapOf(Carrier(bob) to 2, Stored(chest) to 1), owed(moves(inward = false, outward = true)))
        assertEquals(mapOf(Carrier(alice) to 2), owed(moves(inward = true, outward = false)))
    }

    // Bob took Alice's diamond out of the chest and put it back, then Charlie took it: Charlie owes it. And her
    // two diamonds merged into Bob's pile, which merged into a third: of the eight she picked up, two were hers.
    @Test
    fun `a returned withdrawal and a chain of merges are followed through`() {
        val charlie = UUID.fromString("00000000-0000-4000-8000-0000000000c3")
        log.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 0), chest, diamond, null, 1, T0))
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 0), diamond, null, 1, T0 + 1))
        log.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 0), chest, diamond, null, 1, T0 + 2))
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(charlie, 0), diamond, null, 1, T0 + 3))
        val hers = ItemEntityRef(UUID.randomUUID())
        val his = ItemEntityRef(UUID.randomUUID())
        val third = ItemEntityRef(UUID.randomUUID())
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(alice, 1), hers, stone, null, 2, T0 + 4))
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(bob, 1), his, stone, null, 2, T0 + 5))
        log.submit(Transfer(Cause.MOB_DROP, Void, third, stone, null, 4, T0 + 6))
        log.submit(Transfer(Cause.ITEM_MERGE, hers, his, stone, null, 2, T0 + 7))
        log.submit(Transfer(Cause.ITEM_MERGE, his, third, stone, null, 4, T0 + 8))
        log.submit(Transfer(Cause.PICKUP, third, PlayerInv(alice, 1), stone, null, 8, T0 + 9))
        log.drain()
        fun moves(inward: Boolean, outward: Boolean) =
            handMoves(log, alice, 0, Long.MAX_VALUE, inward = inward, outward = outward) { crosses(it, Crossing(inward, outward, containers = true)) { false } }!!
        fun owed(moves: List<HandMove>) = moves.flatMap { it.owed }.groupingBy { it.taker to it.formId }.fold(0) { sum, it -> sum + it.qty }
        val id = { form: ByteArray -> log.forms.lookup(form)!! }

        assertEquals(mapOf((Carrier(charlie) to id(diamond)) to 1), owed(moves(inward = false, outward = true)).filterKeys { it.second == id(diamond) })
        assertEquals(mapOf((Carrier(alice) to id(stone)) to 6), owed(moves(inward = true, outward = false)))
    }

    // Alice picked two out of a pile of hers and Bob's; Bob's two merged on into a mob's two, and she picked those
    // four up as well. Her share went with the first pickup: `+inventory` takes all four of the second.
    @Test
    fun `a share picked up once is not counted again after a merge`() {
        val hers = ItemEntityRef(UUID.randomUUID())
        val his = ItemEntityRef(UUID.randomUUID())
        val mobs = ItemEntityRef(UUID.randomUUID())
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(alice, 0), hers, stone, null, 2, T0))
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(bob, 0), his, stone, null, 2, T0 + 1))
        log.submit(Transfer(Cause.ITEM_MERGE, his, hers, stone, null, 2, T0 + 2))
        log.submit(Transfer(Cause.PICKUP, hers, PlayerInv(alice, 0), stone, null, 2, T0 + 3))
        log.submit(Transfer(Cause.MOB_DROP, Void, mobs, stone, null, 2, T0 + 4))
        log.submit(Transfer(Cause.ITEM_MERGE, hers, mobs, stone, null, 2, T0 + 5))
        log.submit(Transfer(Cause.PICKUP, mobs, PlayerInv(alice, 1), stone, null, 4, T0 + 6))
        log.drain()

        val taken = handMoves(log, alice, 0, Long.MAX_VALUE) { crosses(it, Crossing(inward = true, outward = false, containers = true)) { false } }!!
        assertEquals(4, taken.flatMap { it.owed }.sumOf { it.qty })
    }

    // Alice put one diamond into the chest twice and Bob took one: Bob owes one, the chest holds the other. She
    // gave Bob a stone, he dropped it and Charlie picked it up: Charlie owes it. And the bread she picked out of a
    // mob's pile before her own merged into it is not hers.
    @Test
    fun `deposits share a withdrawal, a carrier's drop is followed, a later merge is not counted back`() {
        val charlie = UUID.fromString("00000000-0000-4000-8000-0000000000c3")
        log.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 0), chest, diamond, null, 1, T0))
        log.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 0), chest, diamond, null, 1, T0 + 1))
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 0), diamond, null, 1, T0 + 2))
        val bobs = ItemEntityRef(UUID.randomUUID())
        log.submit(Transfer(Cause.GIVE_ITEM_TO_MOB, PlayerInv(alice, 1), PlayerInv(bob, 1), stone, null, 1, T0 + 3))
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(bob, 1), bobs, stone, null, 1, T0 + 4))
        log.submit(Transfer(Cause.PICKUP, bobs, PlayerInv(charlie, 0), stone, null, 1, T0 + 5))
        val mobs = ItemEntityRef(UUID.randomUUID())
        val hers = ItemEntityRef(UUID.randomUUID())
        val bread = byteArrayOf(5)
        log.submit(Transfer(Cause.MOB_DROP, Void, mobs, bread, null, 2, T0 + 6))
        log.submit(Transfer(Cause.PICKUP, mobs, PlayerInv(alice, 2), bread, null, 1, T0 + 7))
        log.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(alice, 3), hers, bread, null, 2, T0 + 8))
        log.submit(Transfer(Cause.ITEM_MERGE, hers, mobs, bread, null, 2, T0 + 9))
        log.drain()
        fun moves(inward: Boolean, outward: Boolean) =
            handMoves(log, alice, 0, Long.MAX_VALUE, inward = inward, outward = outward) { crosses(it, Crossing(inward, outward, containers = true)) { false } }!!
        fun owed(moves: List<HandMove>) = moves.flatMap { it.owed }.groupingBy { it.taker to it.formId }.fold(0) { sum, it -> sum + it.qty }
        val id = { form: ByteArray -> log.forms.lookup(form)!! }

        val out = owed(moves(inward = false, outward = true))
        assertEquals(1, out[Carrier(bob) to id(diamond)])
        assertEquals(1, out[Stored(chest) to id(diamond)])
        assertEquals(1, out[Carrier(charlie) to id(stone)])
        assertEquals(null, out[Carrier(bob) to id(stone)])
        assertEquals(mapOf((Carrier(alice) to id(bread)) to 1), owed(moves(inward = true, outward = false)))
    }

    // Bob knocked out Alice's totem: nothing fell, the totem from her off hand comes back into it out of
    // nothing, and only once.
    @Test
    fun `a totem a player knocked out is given back once`() {
        val totem = byteArrayOf(43)
        log.submit(Transfer(Cause.TOTEM_CONSUME, PlayerEquip(alice, 40), Void, totem, null, 1, T0 + 50))
        log.drain()
        val popped = EntityRow(0, 64, 0, T0, 1, 0, EntityKind.TOTEM_POPPED, Cause.TOTEM_CONSUME, "minecraft:player", alice, actor = bob)
        val fallen = Fallen(log, popped)
        val id = log.forms.lookup(totem)!!

        assertEquals(listOf(Vanished(alice) to 1), fallen.restitution().map { it.taker to it.qty })
        assertEquals(mapOf(id to listOf(40)), fallen.slots())
        log.submit(Transfer(Cause.ROLLBACK, Void, Void, totem, null, 1, T0 + 100, reverts = fallen.births().values.flatten()))
        log.drain()
        assertTrue(Fallen(log, popped).restitution().isEmpty())
    }

    // Two totems knocked out a second apart, a plain one from the off hand and a named one from the hand: each
    // pop is paired with its own spending, the same way however often it is read, so neither gives back the
    // other's totem and a second rollback of the first gives back nothing.
    @Test
    fun `two totems knocked out in a row are each given back their own`() {
        val plain = byteArrayOf(43)
        val named = byteArrayOf(44)
        log.submit(Transfer(Cause.TOTEM_CONSUME, PlayerEquip(alice, 40), Void, plain, null, 1, T0 + 50))
        log.submit(Transfer(Cause.TOTEM_CONSUME, PlayerInv(alice, 0), Void, named, null, 1, T0 + 1050))
        log.drain()
        fun pop(at: Long) = EntityRow(0, 64, 0, at, 1, 0, EntityKind.TOTEM_POPPED, Cause.TOTEM_CONSUME, "minecraft:player", alice, actor = bob)
        val first = Fallen(log, pop(T0))
        val second = Fallen(log, pop(T0 + 1000))

        assertEquals(listOf(log.forms.lookup(plain)), first.restitution().map { it.formId })
        assertEquals(listOf(log.forms.lookup(named)), second.restitution().map { it.formId })
        assertEquals(mapOf(log.forms.lookup(named) to listOf(0)), second.slots())
        log.submit(Transfer(Cause.ROLLBACK, Void, Void, plain, null, 1, T0 + 2000, reverts = first.births().values.flatten()))
        log.drain()
        assertTrue(Fallen(log, pop(T0)).restitution().isEmpty())
        assertEquals(1, Fallen(log, pop(T0 + 1000)).restitution().size)
    }

    // A hopper took it into some other chest: past where a rollback follows, and owed by nobody.
    @Test
    fun `a pile a hopper took is beyond reach`() {
        val fell = ItemEntityRef(UUID.randomUUID())
        log.submit(Transfer(Cause.CONTAINER_BREAK_DROP, chest, fell, diamond, null, 3, T0))
        log.submit(Transfer(Cause.HOPPER_PULL_GROUND, fell, Container(world, 9, 60, 9, 0), diamond, null, 3, T0 + 1))
        log.drain()

        assertTrue(owed(returned(fell, 3)).isEmpty())
    }

    // The chest's diamonds were dropped and Bob picked them up: the slot posting leads to the pile and the
    // pile is also among what fell out of what came back, and he owes them once.
    @Test
    fun `a pile a slot posting leads to is not owed a second time as a pile`() {
        val fell = ItemEntityRef(UUID.randomUUID())
        log.submit(Transfer(Cause.CONTAINER_BREAK_DROP, chest, fell, diamond, null, 5, T0))
        log.submit(Transfer(Cause.PICKUP, fell, PlayerInv(bob, 0), diamond, null, 5, T0 + 1))
        log.drain()

        val tally = returned(fell, 5).apply { piles += fell.uuid }
        assertEquals(mapOf(Carrier(bob) to 5), owed(tally))
    }

    // A break writes the block's item off and the drop's birth in one transaction. Once the block is back,
    // what fell out of it is owed by whoever picked it up — but only if every position the transaction
    // took was put back.
    @Test
    fun `the drops of a break are owed only once every position of it came back`() {
        val here = WorldBlock(world, 2, 64, 2)
        val there = WorldBlock(world, 3, 64, 2)
        val drop = ItemEntityRef(UUID.randomUUID())
        log.submit(
            listOf(
                Transfer(Cause.BLOCK_DROP, here, Void, stone, null, 1, T0, actor = bob),
                Transfer(Cause.BLOCK_DROP, there, Void, stone, null, 1, T0, actor = bob),
                Transfer(Cause.BLOCK_DROP, Void, drop, stone, null, 2, T0, actor = bob),
            )
        )
        log.submit(Transfer(Cause.PICKUP, drop, PlayerInv(bob, 1), stone, null, 2, T0 + 1))
        log.drain()
        val loss = log.holderEntries(here, 0, Long.MAX_VALUE).single()

        val half = Tally().apply {
            restored += here
            breaks += loss
        }
        assertTrue(owed(half).isEmpty(), "half the break came back")
        // Each position read brings its own loss row of the one transaction.
        val whole = Tally().apply {
            restored += listOf(here, there)
            breaks += listOf(loss, log.holderEntries(there, 0, Long.MAX_VALUE).single())
        }
        assertEquals(mapOf(Carrier(bob) to 2), owed(whole))
    }

    @Test
    fun `what one taker owes of one item more than once is owed as one sum`() {
        val other = Container(world, 2, 64, 1, 0)
        val merged = merged(
            listOf(
                Owed(Carrier(bob), 1, 3, stashes = listOf(chest)),
                Owed(Carrier(bob), 1, 4, stashes = listOf(chest, other)),
                Owed(Carrier(bob), 2, 5),
                Owed(Carrier(alice), 1, 6),
            )
        ).associateBy { it.taker to it.formId }

        assertEquals(mapOf((Carrier(bob) to 1L) to 7, (Carrier(bob) to 2L) to 5, (Carrier(alice) to 1L) to 6), merged.mapValues { it.value.qty })
        assertEquals(listOf(chest, other), merged.getValue(Carrier(bob) to 1L).stashes)
    }
}
