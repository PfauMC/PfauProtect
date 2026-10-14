package io.pfaumc.pfauprotect.rollback

import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.BothPlanes
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.senderInto
import io.pfaumc.pfauprotect.stub
import io.pfaumc.pfauprotect.command.LookupTarget
import io.pfaumc.pfauprotect.command.Lookups
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.MAX_REGION_RADIUS
import org.bukkit.plugin.Plugin
import io.pfaumc.pfauprotect.command.Action
import io.pfaumc.pfauprotect.command.EventRef
import io.pfaumc.pfauprotect.command.LookupQuery
import io.pfaumc.pfauprotect.command.RowFilter
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.EntityChange
import io.pfaumc.pfauprotect.model.LIGHTNING
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.BlockCodec
import io.pfaumc.pfauprotect.storage.BlockRow
import io.pfaumc.pfauprotect.storage.rawPut
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

private const val AIR = "minecraft:air"
private const val STONE = "minecraft:stone"
private const val DIRT = "minecraft:dirt"

@ExtendWith(Vanilla::class)
class RollbackTest : BothPlanes() {
    override val world: UUID = UUID.fromString("00000000-0000-4000-8000-000000000006")
    private val diamond = byteArrayOf(9)

    // Alice's dirt stair grew grass, which nobody answers for: the walk passes it and the dirt goes. Grass
    // that grew there before her, or where she never was, is no part of her rollback; Bob's stone on her
    // dirt still stops it.
    @Test
    fun `nature on a position being undone is walked past and a player still stops the walk`() {
        val grass = "minecraft:grass_block[snowy=false]"
        log.submit(listOf(BlockChange(1, 64, 1, DIRT, grass, Cause.BLK_GROW, T0)))
        log.submit(listOf(BlockChange(1, 64, 1, grass, AIR, Cause.BLK_PLAYER_BREAK, T0 + 1, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, AIR, DIRT, Cause.BLK_PLAYER_PLACE, T0 + 2, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, DIRT, grass, Cause.BLK_GROW, T0 + 3)))
        log.submit(listOf(BlockChange(3, 64, 1, DIRT, grass, Cause.BLK_GROW, T0 + 3)))
        log.submit(listOf(BlockChange(5, 64, 1, AIR, DIRT, Cause.BLK_PLAYER_PLACE, T0 + 2, actor = alice)))
        log.submit(listOf(BlockChange(5, 64, 1, DIRT, STONE, Cause.BLK_PLAYER_PLACE, T0 + 3, actor = bob)))
        log.drain()

        val planned = around(rollbackFilter(alice))
        val stair = positions(planned).associateBy { it.at.x }
        assertEquals(setOf(1, 5), stair.keys)
        assertEquals(listOf(DIRT to grass, AIR to DIRT, grass to AIR), stair.getValue(1).steps.map { it.before to it.after })
        assertEquals(grass, settle(grass, stair.getValue(1).steps).back?.before)
        assertTrue(settle(STONE, stair.getValue(5).steps).conflict)
    }

    // A storm burnt Alice's planks: the bolt is named on the row, and still it is nature the walk passes,
    // where Bob's fire stops it.
    @Test
    fun `a storm's fire on a position being undone is walked past like any nature`() {
        val planks = "minecraft:oak_planks"
        val fire = "minecraft:fire[age=3]"
        for ((x, by) in listOf(7 to LIGHTNING, 9 to bob)) {
            log.submit(listOf(BlockChange(x, 64, 1, AIR, planks, Cause.BLK_PLAYER_PLACE, T0 + 2, actor = alice)))
            log.submit(listOf(BlockChange(x, 64, 1, planks, fire, Cause.BLK_FIRE_BURN, T0 + 3, Confidence.INFERRED, actor = by)))
        }
        log.drain()

        val planned = around(rollbackFilter(alice))
        val steps = positions(planned).associate { it.at.x to it.steps.map { s -> s.before to s.after } }
        assertEquals(listOf(planks to fire, AIR to planks), steps.getValue(7))
        assertEquals(listOf(AIR to planks), steps.getValue(9))
    }

    // The grass under the griefer's block went to dirt a while after he put it down: taking his block away
    // brings the grass back. Grass that died under nothing of his stays as the world left it.
    @Test
    fun `grass gone to dirt under a block taken away comes back`() {
        val grass = "minecraft:grass_block[snowy=false]"
        log.submit(listOf(BlockChange(1, 65, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, grass, DIRT, Cause.BLK_FADE, T0 + 50)))
        log.submit(listOf(BlockChange(3, 64, 1, grass, DIRT, Cause.BLK_FADE, T0 + 50)))
        // Rolled back once: the planks are back, and the grass that died under them since is the world's.
        log.submit(listOf(BlockChange(1, 65, 5, "minecraft:oak_planks", AIR, Cause.BLK_PLAYER_BREAK, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 65, 5, AIR, "minecraft:oak_planks", Cause.ROLLBACK, T0 + 10)))
        log.submit(listOf(BlockChange(1, 64, 5, grass, DIRT, Cause.BLK_FADE, T0 + 60)))
        // Fire under the stone went out: a fade too, and never to be lit again.
        log.submit(listOf(BlockChange(1, 65, 3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 3, "minecraft:fire[age=0,east=false,north=false,south=false,up=false,west=false]", AIR, Cause.BLK_FADE, T0 + 50)))
        log.drain()

        val planned = around(rollbackFilter(alice))
        val at = positions(planned).associateBy { Triple(it.at.x, it.at.y, it.at.z) }
        assertEquals(setOf(Triple(1, 65, 1), Triple(1, 64, 1), Triple(1, 65, 3), Triple(1, 65, 5)), at.keys)
        assertEquals(grass, settle(DIRT, at.getValue(Triple(1, 64, 1)).steps).back?.before)
    }

    // The griefer planted a sapling and a stalk of bamboo; both grew on their own after. Taking the planting
    // away takes the tree with it, every block of the one event, and the stalk above the shoot.
    @Test
    fun `what grew out of a planting goes with it`() {
        val sapling = "minecraft:oak_sapling[stage=0]"
        val log0 = "minecraft:oak_log[axis=y]"
        val leaves = "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]"
        val bamboo = "minecraft:bamboo[age=0,leaves=none,stage=0]"
        log.submit(listOf(BlockChange(1, 64, 1, AIR, sapling, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(5, 64, 1, AIR, bamboo, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(
            BlockChange(1, 64, 1, sapling, log0, Cause.BLK_GROW, T0 + 50),
            BlockChange(1, 65, 1, AIR, log0, Cause.BLK_GROW, T0 + 50),
            BlockChange(2, 66, 1, AIR, leaves, Cause.BLK_GROW, T0 + 50),
        ))
        log.submit(listOf(BlockChange(5, 65, 1, AIR, bamboo, Cause.BLK_GROW, T0 + 60)))
        log.submit(listOf(BlockChange(5, 66, 1, AIR, bamboo, Cause.BLK_GROW, T0 + 70)))
        log.submit(listOf(BlockChange(9, 65, 1, AIR, bamboo, Cause.BLK_GROW, T0 + 70)))
        log.drain()

        val planned = around(rollbackFilter(alice))
        val at = positions(planned).map { Triple(it.at.x, it.at.y, it.at.z) }.toSet()
        assertEquals(setOf(Triple(1, 64, 1), Triple(1, 65, 1), Triple(2, 66, 1), Triple(5, 64, 1), Triple(5, 65, 1), Triple(5, 66, 1)), at)
    }

    // Alice broke the door and put dirt in its place, grass grew on it, and a rollback cut short took the
    // grass away. Rolled back again, the door's lower half comes back: the earlier rollback's row is walked
    // past like nature, not taken for somebody's later change.
    @Test
    fun `an earlier rollback on a position being undone is walked past`() {
        val door = "minecraft:oak_door[half=lower]"
        val grass = "minecraft:grass_block[snowy=false]"
        log.submit(listOf(BlockChange(1, 64, 1, door, AIR, Cause.BLK_PLAYER_BREAK, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, AIR, DIRT, Cause.BLK_PLAYER_PLACE, T0 + 1, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, DIRT, grass, Cause.BLK_GROW, T0 + 2)))
        log.submit(listOf(BlockChange(1, 64, 1, grass, AIR, Cause.ROLLBACK, T0 + 3)))
        log.drain()

        val planned = around(rollbackFilter(alice))
        val settled = settle(AIR, steps(planned))
        assertEquals(door, settled.back?.before)
        assertFalse(settled.conflict)
    }

    // Alice's blast took the grass; the rollback put it back; the wall restored over it turned it to dirt.
    // That last is the world's own doing after the position was already put back, so a second rollback
    // has nothing to do here and must not dig the dirt up for grass again.
    @Test
    fun `nature after an earlier rollback is not walked past`() {
        val grass = "minecraft:grass_block[snowy=false]"
        log.submit(listOf(BlockChange(1, 64, 1, grass, AIR, Cause.BLK_TNT, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, AIR, grass, Cause.ROLLBACK, T0 + 1)))
        log.submit(listOf(BlockChange(1, 64, 1, grass, DIRT, Cause.BLK_FADE, T0 + 2)))
        log.drain()

        val planned = around(rollbackFilter(alice))
        val settled = settle(DIRT, steps(planned))
        assertNull(settled.back)
    }

    // Alice put the stone down before the window and broke it inside: rolled back from the window's start, the
    // position goes back to her own stone, and the preview has to say so. Bob's stone before the window, and
    // an earlier rollback's, are nobody's grief to warn about.
    @Test
    fun `a window opening on what the player left is counted for the warning`() {
        log.submit(listOf(BlockChange(1, 64, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0 + 100, actor = alice)))
        log.submit(listOf(BlockChange(2, 64, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = bob)))
        log.submit(listOf(BlockChange(2, 64, 1, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0 + 100, actor = alice)))
        log.submit(listOf(BlockChange(3, 64, 1, AIR, STONE, Cause.ROLLBACK, T0, actor = alice)))
        log.submit(listOf(BlockChange(3, 64, 1, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0 + 100, actor = alice)))
        log.submit(listOf(BlockChange(4, 64, 1, AIR, DIRT, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(4, 64, 1, DIRT, AIR, Cause.BLK_PLAYER_BREAK, T0 + 100, actor = alice)))
        log.drain()

        val planned = around(rollbackFilter(alice), from = T0 + 50)
        assertEquals(4, planned.positions)
        assertEquals(2, RollbackReader(shared, logs).leftByThemBefore(listOf(planned), setOf(alice), T0 + 50))
    }

    // Alice put dynamite down before the window and blew it up inside; an earlier rollback had already put the
    // air back. From the window's start the position would get her dynamite again; it stands as before
    // her, so there is nothing to do. Where it does not, the window's start is still what it goes back to.
    @Test
    fun `a position standing as before the player first touched it is left alone`() {
        val tnt = "minecraft:tnt[unstable=false]"
        log.submit(listOf(BlockChange(1, 64, 1, AIR, tnt, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, tnt, AIR, Cause.BLK_TNT, T0 + 100, actor = alice)))
        log.drain()

        val planned = around(rollbackFilter(alice), from = T0 + 50)
        val position = positions(planned).single()
        assertEquals(AIR, position.origin)
        assertNull(settle(AIR, position.steps, position.origin).back)
    }

    // A row stamped with the window's very start is the window's: undone, and not also read as what the
    // position stood as before it, where Bob's stone would otherwise be taken for her beginning.
    @Test
    fun `a row at the window's start is in the window and not before it`() {
        log.submit(listOf(BlockChange(1, 64, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = bob)))
        log.submit(listOf(BlockChange(1, 64, 1, STONE, DIRT, Cause.BLK_PLAYER_PLACE, T0 + 100, actor = alice)))
        log.drain()

        val position = positions(around(rollbackFilter(alice), from = T0 + 100)).single()
        assertEquals(listOf(STONE to DIRT), position.steps.map { it.before to it.after })
        assertNull(position.origin)
    }

    // Alice burnt a leaf before the window; a command put it back inside it, and she burnt it again. Air stands
    // as before her first burning, but the window opens on the command's leaf, and that is what goes back.
    @Test
    fun `a position somebody else changed inside the window goes back to the window's start`() {
        val leaf = "minecraft:oak_leaves[distance=2,persistent=false,waterlogged=false]"
        log.submit(listOf(BlockChange(1, 64, 1, leaf, AIR, Cause.BLK_FIRE_BURN, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, AIR, leaf, Cause.BLK_COMMAND, T0 + 100)))
        log.submit(listOf(BlockChange(1, 64, 1, leaf, AIR, Cause.BLK_FIRE_BURN, T0 + 200, actor = alice)))
        log.drain()

        val planned = around(rollbackFilter(alice), from = T0 + 50)
        val position = positions(planned).single()
        assertNull(position.origin)
        assertEquals(leaf, settle(AIR, position.steps, position.origin).back?.before)
    }

    // Standing near a lever someone else pulled is a fact about where a player stood, not about what
    // they did, and a rollback of that player must not undo it.
    @Test
    fun `a user answers for what they did and never for having stood near`() {
        log.submit(listOf(BlockChange(1, 64, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(2, 64, 1, AIR, STONE, Cause.BLK_TNT, T0, Confidence.INFERRED, actor = alice)))
        log.submit(listOf(BlockChange(3, 64, 1, AIR, STONE, Cause.BLK_ENTITY_SWITCH, T0, Confidence.NEARBY, actor = alice)))
        log.submit(listOf(BlockChange(4, 64, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = bob)))
        log.drain()
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, Container(world, 5, 64, 1, 0), PlayerInv(bob, 0), diamond, null, 1, T0))
        shared.drain()

        val planned = around(rollbackFilter(alice))
        assertTrue(positions(planned).none { it.refills.isNotEmpty() }, "Bob's theft is not refilled")
        assertEquals(setOf(1, 2), positions(planned).map { it.at.x }.toSet())
    }

    // A narrower rollback run after a wider one would otherwise find the first rollback's rows inside
    // its window and put the grief back.
    @Test
    fun `a rollback's own rows are left alone unless asked for`() {
        log.submit(listOf(BlockChange(1, 64, 1, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, AIR, STONE, Cause.ROLLBACK, T0 + 10, actor = bob)))
        log.drain()

        val plain = around(rollbackFilter())
        assertEquals(listOf(STONE, AIR), steps(plain).map { it.after })
        assertEquals(STONE, settle(STONE, steps(plain)).back?.before)
        assertEquals(emptyList<PositionPlan>(), positions(around(rollbackFilter(bob))), "bob's only row is the rollback")
        val undo = around(rollbackFilter(causes = Action.ROLLBACK.causes))
        assertEquals(listOf(STONE), steps(undo).map { it.after }, "only the rollback")
        assertEquals(AIR, settle(STONE, steps(undo)).back?.before, "undone, the grief stands again")
    }

    // A window that ends in the past: what came after it is somebody's later change, Alice's own included,
    // and it stops the position from being undone.
    @Test
    fun `a row after the window's end is a later change`() {
        log.submit(listOf(BlockChange(1, 64, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, STONE, DIRT, Cause.BLK_PLAYER_PLACE, T0 + 100, actor = alice)))
        log.drain()

        val settled = settle(DIRT, steps(around(rollbackFilter(alice), to = T0 + 50)))
        assertTrue(settled.conflict)
        assertNull(settled.back)
    }

    @Test
    fun `a rollback over more positions than one may write is refused`() {
        log.submit((1..3).map { BlockChange(it, 64, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice) })
        log.drain()
        val limit = Settings.maxRollbackPositions
        try {
            Settings.maxRollbackPositions = 3
            assertEquals(3, around(rollbackFilter(alice)).positions)
            Settings.maxRollbackPositions = 2
            val refused = RollbackReader(shared, logs).around(world, 0, 64, 0, 15, 0, Long.MAX_VALUE, rollbackFilter(alice), false)
            assertEquals(3, (refused as Refused).positions)
        } finally {
            Settings.maxRollbackPositions = limit
        }
    }

    // Going ahead over part of the history would put back part of the place, so a row the read could not
    // decode refuses the rollback, and so does one naming a state the ledger does not know.
    @Test
    fun `a rollback over a row it cannot read is refused`() {
        log.submit(listOf(BlockChange(1, 64, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(2, 64, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.drain()
        val unknown = BlockRow(2, 64, 1, T0 + 5, 999, 0, Cause.BLK_PLAYER_PLACE, 60_000, 60_001)
        logs.close(world)
        val base = dir.resolve("blocks").resolve(world.toString())
        rawPut(base, "rows", BlockCodec.key(1, 64, 1, T0 + 5, 998, 0), ByteArray(0))
        rawPut(base, "rows", BlockCodec.key(2, 64, 1, T0 + 5, 999, 0), BlockCodec.value(unknown) { _, _ -> error("no actor") })
        log = logs.open(world)

        val reader = RollbackReader(shared, logs)
        for (x in 1..2) {
            val reading = reader.at(world, setOf(WorldBlock(world, x, 64, 1)), 0, Long.MAX_VALUE, rollbackFilter())
            assertTrue((reading as? Refused)?.reason?.contains("could not be read") == true, "at x=$x: ${(reading as? Refused)?.reason ?: "planned"}")
        }
    }

    // Excluding Bob drops what he did in both planes, and only that.
    @Test
    fun `an excluded player's blocks and postings are left out`() {
        log.submit(listOf(BlockChange(1, 64, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = bob)))
        log.submit(listOf(BlockChange(2, 64, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.drain()
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, Container(world, 3, 64, 1, 0), PlayerInv(bob, 0), diamond, null, 1, T0))
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, Container(world, 4, 64, 1, 0), PlayerInv(alice, 0), diamond, null, 1, T0))
        shared.drain()

        val filter = RowFilter(shared, LookupQuery(), emptySet(), setOf(bob), rollback = true)
        assertEquals(setOf(2, 4), positions(around(filter)).map { it.at.x }.toSet())
    }

    // A neighbour's shape goes back with what changed beside it, so a rollback narrowed to explosions takes
    // it along; undoing a rollback wrote none, and a lookup has one for every fence beside every change.
    @Test
    fun `a neighbour's shape row rides with a rollback and shows in a lookup only when asked`() {
        val shape = BlockRow(2, 64, 1, T0, 1, 0, Cause.BLK_SHAPE, 0, 0, actor = alice)
        fun keeps(query: LookupQuery, rollback: Boolean) = RowFilter(shared, query, emptySet(), emptySet(), rollback).keeps(shape)

        assertTrue(keeps(LookupQuery(causes = Action.EXPLOSION.causes), rollback = true))
        assertTrue(keeps(LookupQuery(), rollback = true))
        assertFalse(keeps(LookupQuery(causes = Action.ROLLBACK.causes), rollback = true))
        assertFalse(keeps(LookupQuery(), rollback = false))
        assertFalse(keeps(LookupQuery(causes = Action.EXPLOSION.causes), rollback = false))
        assertTrue(keeps(LookupQuery(all = true), rollback = false))
        assertTrue(keeps(LookupQuery(causes = setOf(Cause.BLK_SHAPE)), rollback = false))
    }

    // A neighbour's chest emptied or block broken in the same second is somebody else's doing, not part of
    // the event.
    @Test
    fun `one event's rollback gives back only the blocks and slots where the event stood`() {
        log.submit(listOf(BlockChange(5, 64, 5, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0, actor = alice)))
        log.submit(listOf(BlockChange(7, 64, 5, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0 + 1, actor = bob)))
        log.drain()
        val broken = Container(world, 5, 64, 5, 0)
        val neighbour = Container(world, 6, 64, 5, 0)
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, broken, PlayerInv(alice, 0), diamond, null, 3, T0))
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, neighbour, PlayerInv(bob, 0), diamond, null, 7, T0 + 1))
        shared.drain()
        val event = log.at(5, 64, 5, 0, Long.MAX_VALUE, limit = 10, reverse = false).single().eventId
        val filter = RowFilter(shared, LookupQuery(event = EventRef(event, 5, 64, 5, T0)), emptySet(), emptySet(), rollback = true)

        val planned = positions(around(filter))
        assertEquals(listOf(WorldBlock(world, 5, 64, 5)), planned.map { it.at })
        assertEquals(listOf(WorldBlock(world, 5, 64, 5)), planned.filter { it.refills.isNotEmpty() }.map { it.at })
    }

    // The second rollback of the same theft would mint the diamonds a second time.
    @Test
    fun `a slot posting already given back is not planned again`() {
        val chest = Container(world, 6, 64, 5, 3)
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(alice, 0), diamond, null, 7, T0))
        shared.drain()
        val theft = shared.holderEntries(chest, 0, Long.MAX_VALUE).single()
        assertEquals(listOf(7), positions(around(rollbackFilter())).flatMap { p -> p.refills.map { it.qty } })
        shared.submit(Transfer(Cause.ROLLBACK, Void, chest, diamond, null, 7, T0 + 10, reverts = listOf(theft.ref)))
        shared.drain()

        val planned = around(rollbackFilter())
        assertTrue(planned.chunks.isEmpty(), "${positions(planned).map { it.at }}")
    }

    // An opening stands for what the chest held before a purge, not for anything done in the window: undone,
    // it would empty a chest nobody touched.
    @Test
    fun `a purge's opening balance is not rolled back`() {
        val chest = Container(world, 6, 64, 5, 0)
        shared.submit(Transfer(Cause.PURGE_OPENING, Void, chest, diamond, null, 5, T0))
        shared.drain()

        assertTrue(around(rollbackFilter()).chunks.isEmpty())
    }

    @Test
    fun `a purge's opening balance in an entity's slot is not undone with the entity`() {
        val horse = UUID.randomUUID()
        log.submit(listOf(EntityChange(3, 64, 3, EntityKind.CHANGED, Cause.ENTITY_CHANGED, "minecraft:horse", horse, T0, actor = alice, before = byteArrayOf(10, 0, 0, 1), after = byteArrayOf(10, 0, 0, 2))))
        log.drain()
        shared.submit(Transfer(Cause.PURGE_OPENING, Void, EntitySlot(horse, 400), diamond, null, 1, T0 + 5))
        shared.drain()

        val plan = positions(around(rollbackFilter(alice))).flatMap { it.entities }.single()
        assertTrue(plan.slots.isEmpty(), "${plan.slots}")
    }

    // A rollback undone and then both rolled back together by `action:rollback`: the blocks of the two
    // cancel out, so the slots have to as well. Skipping the first rollback's posting because the undo
    // gave it back would empty the chest the blocks leave standing.
    @Test
    fun `a rollback rolled back together with its undo leaves both slot postings in`() {
        val chest = Container(world, 6, 64, 5, 0)
        shared.submit(Transfer(Cause.ROLLBACK, chest, Void, diamond, null, 5, T0))
        shared.drain()
        val first = shared.holderEntries(chest, 0, Long.MAX_VALUE).single()
        shared.submit(Transfer(Cause.ROLLBACK, Void, chest, diamond, null, 5, T0 + 10, reverts = listOf(first.ref)))
        shared.drain()

        val planned = around(rollbackFilter(causes = Action.ROLLBACK.causes))
        assertEquals(listOf(-5, 5), planned.chunks.single().positions.single().refills.map { it.qty }.sorted())
        val plain = around(rollbackFilter())
        assertTrue(plain.chunks.isEmpty(), "a rollback's rows are not touched unless named")
    }

    // Without a radius a player's history is found where it stands: the blocks they changed through the
    // block plane's index, and the chests they took from as the far ends of their own rows, however far
    // apart. Somebody else's rows at the same places are not theirs to undo.
    @Test
    fun `a player's rollback without a radius finds every place they touched`() {
        log.submit(listOf(BlockChange(1, 64, 1, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 2, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = bob)))
        log.drain()
        val far = Container(world, 5000, 64, -5000, 2)
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, far, PlayerInv(alice, 0), diamond, null, 3, T0))
        shared.drain()

        val reader = RollbackReader(shared, logs)
        val touched = reader.touchedBy(setOf(alice), 0, Long.MAX_VALUE)!!
        assertEquals(setOf(WorldBlock(world, 1, 64, 1), WorldBlock(world, 5000, 64, -5000)), touched.getValue(world))
        val planned = reader.at(world, touched.getValue(world) + WorldBlock(world, 1, 64, 2), 0, Long.MAX_VALUE, rollbackFilter(alice))
            as Planned
        val positions = positions(planned).associateBy { it.at }
        assertEquals(setOf(WorldBlock(world, 1, 64, 1), WorldBlock(world, 5000, 64, -5000)), positions.keys)
        assertEquals(3, positions.getValue(WorldBlock(world, 5000, 64, -5000)).refills.single().qty)
    }

    // Alice renamed Bob's horse and then killed it, and its saddle fell out. Rolled back, the horse goes
    // back to what it was before she touched it at all, its saddle's way out is undone with it whoever's
    // row that was, and what fell out of it is followed.
    @Test
    fun `an entity goes back to what its oldest row says it was`() {
        val horse = UUID.randomUUID()
        val pile = UUID.randomUUID()
        // What her hand took off it on the way, like wool a pair of shears cut.
        val cut = UUID.randomUUID()
        val untouched = byteArrayOf(10, 0, 0, 1)
        val renamed = byteArrayOf(10, 0, 0, 2)
        log.submit(listOf(EntityChange(3, 64, 3, EntityKind.CHANGED, Cause.ENTITY_CHANGED, "minecraft:horse", horse, T0, actor = alice, before = untouched, after = renamed, drops = listOf(cut))))
        log.submit(listOf(EntityChange(4, 64, 3, EntityKind.REMOVED, Cause.ENTITY_KILLED, "minecraft:horse", horse, T0 + 10, actor = alice, before = renamed, drops = listOf(pile))))
        log.drain()
        shared.submit(Transfer(Cause.CONTAINER_BREAK_DROP, EntitySlot(horse, 400), ItemEntityRef(UUID.randomUUID()), diamond, null, 1, T0 + 10))
        shared.drain()

        val planned = around(rollbackFilter(alice))
        val plan = positions(planned).flatMap { it.entities }.single()
        assertEquals(EntityKind.CHANGED, plan.oldest.kind)
        assertEquals(untouched.toList(), plan.before!!.toList())
        assertEquals(listOf(-1), plan.slots.map { it.qty })
        assertEquals(setOf(cut, pile), plan.drops.toSet())
        assertTrue(plan.removed, "it went within the window, so it is brought back rather than changed back")
        assertEquals(WorldBlock(world, 3, 64, 3), plan.at)
    }

    // The saddle that fell out was put back by an earlier rollback; rolled back again, the horse has
    // nothing left in its slots to give back or take away.
    @Test
    fun `an entity slot posting already given back is not planned again`() {
        val horse = UUID.randomUUID()
        log.submit(listOf(EntityChange(4, 64, 3, EntityKind.REMOVED, Cause.ENTITY_KILLED, "minecraft:horse", horse, T0 + 10, actor = alice, before = byteArrayOf(10, 0, 0, 1))))
        log.drain()
        val saddle = EntitySlot(horse, 400)
        shared.submit(Transfer(Cause.CONTAINER_BREAK_DROP, saddle, ItemEntityRef(UUID.randomUUID()), diamond, null, 1, T0 + 10))
        shared.drain()
        val fell = shared.holderEntries(saddle, 0, Long.MAX_VALUE).single()
        shared.submit(Transfer(Cause.ROLLBACK, Void, saddle, diamond, null, 1, T0 + 20, reverts = listOf(fell.ref)))
        shared.drain()

        val plan = positions(around(rollbackFilter(alice))).flatMap { it.entities }.single()
        assertEquals(emptyList<Int>(), plan.slots.map { it.qty })

        // Undoing that rollback takes the horse away again, and the saddle it was given back with it.
        log.submit(listOf(EntityChange(4, 64, 3, EntityKind.CREATED, Cause.ROLLBACK, "minecraft:horse", horse, T0 + 20, after = byteArrayOf(10, 0, 0, 1))))
        log.drain()
        val undo = positions(around(rollbackFilter(causes = Action.ROLLBACK.causes))).flatMap { it.entities }.single()
        assertEquals(listOf(1), undo.slots.map { it.qty })
    }

    // The widest radius the config allows has to stay inside what the region read takes, wherever in
    // its chunk the centre falls.
    @Test
    fun `the widest radius the config allows is read rather than refused`() {
        val configured = Settings.maxRadius
        try {
            val widest = MAX_REGION_RADIUS
            Settings.maxRadius = widest
            // The centre whose square starts on a chunk's last block and so spans the most chunks.
            val centre = 15 + widest % 16
            val reading = RollbackReader(shared, logs).around(
                world, centre, 64, centre, widest, 0, Long.MAX_VALUE, RowFilter(shared, LookupQuery(all = true), emptySet(), emptySet()),
            )
            assertTrue(reading is Planned)
            val said = ArrayList<String>()
            Lookups(stub(Plugin::class.java), shared, logs, ItemFormCodec(shared.registries, ServerRegistries.access))
                .report(senderInto(said), LookupTarget(world, centre, 64, centre, "here"), LookupQuery(radius = widest))
            val answer = said.single()
            assertTrue(answer.startsWith("PfauProtect") && "$widest" in answer && "$centre 64 $centre" in answer, answer)
        } finally {
            Settings.maxRadius = configured
        }
    }

    @Test
    fun `a world with no open base is refused`() {
        val refused = RollbackReader(shared, logs).around(
            UUID.randomUUID(), 0, 64, 0, 4, 0, Long.MAX_VALUE, RowFilter(shared, LookupQuery(all = true), emptySet(), emptySet()),
        )
        assertTrue(refused is Refused, "a world with no open base has nothing to roll back over")
    }

    // Alice poured lava from forty blocks over the house. Her rollback reaches it; a rollback of nobody in
    // particular keeps to the cube, so a build high above is not someone else's to lose.
    @Test
    fun `a rollback of named players reaches the whole height of its square`() {
        log.submit(listOf(BlockChange(2, 104, 2, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.drain()
        val named = around(rollbackFilter(alice), column = true)
        assertEquals(listOf(WorldBlock(world, 2, 104, 2)), positions(named).map { it.at })
        assertTrue(positions(around(rollbackFilter(alice))).isEmpty())
    }

    private fun around(filter: RowFilter, from: Long = 0, column: Boolean = false, to: Long = Long.MAX_VALUE): Planned {
        val reading = RollbackReader(shared, logs).around(world, 0, 64, 0, 15, from, to, filter, column)
        return reading as? Planned ?: throw AssertionError((reading as Refused).reason)
    }

    private fun rollbackFilter(vararg users: UUID, causes: Set<Cause>? = null) =
        RowFilter(shared, LookupQuery(causes = causes), users.toSet(), emptySet(), rollback = true)

    private fun positions(planned: Planned) = planned.chunks.flatMap { it.positions }

    private fun steps(planned: Planned) = positions(planned).flatMap { it.steps }
}
