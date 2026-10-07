package io.pfaumc.pfauprotect.rollback

import io.pfaumc.pfauprotect.command.Action
import io.pfaumc.pfauprotect.command.LookupQuery
import io.pfaumc.pfauprotect.command.RowFilter
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.BlockLog
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.RocksItemLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import io.pfaumc.pfauprotect.ServerRegistries
import net.minecraft.world.SimpleContainer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.item.Items
import java.nio.file.Path
import java.util.UUID
import net.minecraft.world.item.ItemStack as NmsItemStack

private const val T0 = 1_700_000_000_000L
private const val AIR = "minecraft:air"
private const val STONE = "minecraft:stone"
private const val DIRT = "minecraft:dirt"
private const val LAVA = "minecraft:lava[level=0]"

class RollbackTest {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000006")
    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val bob = UUID.fromString("00000000-0000-4000-8000-0000000000b0")
    private val diamond = byteArrayOf(9)

    private lateinit var shared: RocksItemLog
    private lateinit var logs: BlockLogs
    private lateinit var log: BlockLog

    @BeforeEach
    fun open(@TempDir dir: Path) {
        shared = RocksItemLog(dir.resolve("items"))
        logs = BlockLogs(dir.resolve("blocks"), shared)
        log = logs.open(world)
    }

    @AfterEach
    fun closeAll() {
        logs.close()
        shared.close()
    }

    private fun step(before: String, after: String) = Step(before, after, null)

    @Test
    fun `a placement is undone back to what it replaced`() {
        val placed = step(AIR, STONE)
        val settled = settle(STONE, listOf(placed))
        assertSame(placed, settled.back)
        assertFalse(settled.conflict)
    }

    // Undone once already, the position stands on the row's `before`; a second rollback finds nothing
    // to do there, and that is not a conflict.
    @Test
    fun `a row already undone is stepped over`() {
        val settled = settle(STONE, listOf(step(STONE, AIR)))
        assertNull(settled.back)
        assertFalse(settled.conflict)
    }

    // Bob built on the hole Alice left. Rolling Alice back must not tear Bob's block out, and undoing her
    // later row stays undone.
    @Test
    fun `a change by something the rollback leaves alone stops the walk where it got to`() {
        val lava = step(DIRT, LAVA)
        val settled = settle(LAVA, listOf(lava, step(STONE, AIR)))
        assertSame(lava, settled.back)
        assertTrue(settled.conflict)
    }

    // A fence joins its new neighbours without a row of its own, and it is still the fence the row put
    // down; so is a door the row opened, whatever else changed on it.
    @Test
    fun `blocks are compared and not whole states`() {
        val fence = step(AIR, "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
        assertSame(fence, settle("minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=false]", listOf(fence)).back)
        val opened = step("minecraft:oak_door[open=false]", "minecraft:oak_door[open=true]")
        assertSame(opened, settle("minecraft:oak_door[open=true]", listOf(opened)).back)
    }

    // A plank burnt out and lava ran into the hole: the lava wrote no row and goes when its source does,
    // so the plank goes back. A source standing there is somebody's bucket, and that is a later change.
    @Test
    fun `liquid that ran in is the air it ran into`() {
        val burnt = step("minecraft:oak_planks", AIR)
        val settled = settle("minecraft:lava[level=3]", listOf(burnt))
        assertSame(burnt, settled.back)
        assertFalse(settled.conflict)
        assertTrue(settle(LAVA, listOf(burnt)).conflict)
    }

    // The griefer's fire took the plank and went out by itself, which is nobody's row; the plank goes back.
    @Test
    fun `a fire that went out is no later change`() {
        val burnt = step("minecraft:oak_planks", "minecraft:fire[age=3]")
        val settled = settle(AIR, listOf(burnt))
        assertSame(burnt, settled.back)
        assertFalse(settled.conflict)
        // Nor is a fire ever put back: lit again it would burn down what the rollback put back.
        assertTrue(passing("minecraft:soul_fire[age=13]"))
        assertFalse(passing(LAVA))
    }

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

        val planned = around(RowFilter(shared, LookupQuery(), setOf(alice), emptySet(), rollback = true))
        val stair = planned.chunks.flatMap { it.positions }.associateBy { it.at.x }
        assertEquals(setOf(1, 5), stair.keys)
        assertEquals(listOf(DIRT to grass, AIR to DIRT, grass to AIR), stair.getValue(1).steps.map { it.before to it.after })
        assertEquals(grass, settle(grass, stair.getValue(1).steps).back?.before)
        assertTrue(settle(STONE, stair.getValue(5).steps).conflict)
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

        val planned = around(RowFilter(shared, LookupQuery(), setOf(alice), emptySet(), rollback = true))
        val at = planned.chunks.flatMap { it.positions }.associateBy { Triple(it.at.x, it.at.y, it.at.z) }
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

        val planned = around(RowFilter(shared, LookupQuery(), setOf(alice), emptySet(), rollback = true))
        val at = planned.chunks.flatMap { it.positions }.map { Triple(it.at.x, it.at.y, it.at.z) }.toSet()
        assertEquals(setOf(Triple(1, 64, 1), Triple(1, 65, 1), Triple(2, 66, 1), Triple(5, 64, 1), Triple(5, 65, 1), Triple(5, 66, 1)), at)
    }

    // Rolled back once, the plank that burnt and then took the griefer's lava is a plank again: the second
    // rollback finds it done. Dirt somebody else put in its place is still somebody else's.
    @Test
    fun `a chain undone before is done and no conflict`() {
        val chain = listOf(step("minecraft:fire[age=13]", "minecraft:lava[level=8]"), step("minecraft:oak_planks", "minecraft:fire[age=13]"))
        val again = settle("minecraft:oak_planks", chain)
        assertNull(again.back)
        assertFalse(again.conflict)
        assertTrue(settle(DIRT, chain).conflict)
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

        val planned = around(RowFilter(shared, LookupQuery(), setOf(alice), emptySet(), rollback = true))
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

        val planned = around(RowFilter(shared, LookupQuery(), setOf(alice), emptySet(), rollback = true))
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
        log.drain()

        val reader = RollbackReader(shared, logs)
        val filter = RowFilter(shared, LookupQuery(), setOf(alice), emptySet(), rollback = true)
        val planned = reader.around(world, 0, 64, 0, 15, T0 + 50, Long.MAX_VALUE, filter::keeps, filter::keeps) as Planned
        assertEquals(3, planned.positions)
        assertEquals(1, reader.leftByThemBefore(listOf(planned), setOf(alice), T0 + 50))
    }

    // Alice put dynamite down before the window and blew it up inside; an earlier rollback had already put the
    // air back. From the window's start the position would get her dynamite again (D108); it stands as before
    // her, so there is nothing to do. Where it does not, the window's start is still what it goes back to.
    @Test
    fun `a position standing as before the player first touched it is left alone`() {
        val tnt = "minecraft:tnt[unstable=false]"
        log.submit(listOf(BlockChange(1, 64, 1, AIR, tnt, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, tnt, AIR, Cause.BLK_TNT, T0 + 100, actor = alice)))
        log.drain()

        val filter = RowFilter(shared, LookupQuery(), setOf(alice), emptySet(), rollback = true)
        val planned = RollbackReader(shared, logs).around(world, 0, 64, 0, 15, T0 + 50, Long.MAX_VALUE, filter::keeps, filter::keeps) as Planned
        val position = planned.chunks.flatMap { it.positions }.single()
        assertEquals(AIR, position.origin)
        assertNull(settle(AIR, position.steps, position.origin).back)
        assertEquals(tnt, settle(STONE, position.steps.map { Step(it.before, STONE, null) }, position.origin).back?.before)
    }

    @Test
    fun `the airs are one`() {
        val dug = step(STONE, AIR)
        assertSame(dug, settle("minecraft:cave_air", listOf(dug)).back)
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

        val planned = around(RowFilter(shared, LookupQuery(), setOf(alice), emptySet(), rollback = true))
        assertEquals(setOf(1, 2), planned.chunks.flatMap { it.positions }.map { it.at.x }.toSet())
    }

    // A narrower rollback run after a wider one would otherwise find the first rollback's rows inside
    // its window and put the grief back.
    @Test
    fun `a rollback's own rows are left alone unless asked for`() {
        log.submit(listOf(BlockChange(1, 64, 1, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0, actor = alice)))
        log.submit(listOf(BlockChange(1, 64, 1, AIR, STONE, Cause.ROLLBACK, T0 + 10, actor = bob)))
        log.drain()

        val plain = around(RowFilter(shared, LookupQuery(), emptySet(), emptySet(), rollback = true))
        // Walked past on the way back to before the break, the rollback's stone is where the walk ends anyway.
        assertEquals(listOf(STONE, AIR), steps(plain).map { it.after })
        assertEquals(STONE, settle(STONE, steps(plain)).back?.before)
        val undo = around(RowFilter(shared, LookupQuery(causes = Action.ROLLBACK.causes), emptySet(), emptySet(), rollback = true))
        assertEquals(listOf(STONE), steps(undo).map { it.after }, "only the rollback")
        // A lookup is not a rollback and shows them like any other row.
        assertTrue(RowFilter(shared, LookupQuery(), emptySet(), emptySet()).keeps(log.at(1, 64, 1).last()))
    }

    // Newest first, and a slot posting turned round: what left the chest goes back in.
    @Test
    fun `a position plans its rows newest first and turns its slot postings round`() {
        log.submit(listOf(BlockChange(5, 64, 5, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(5, 64, 5, STONE, DIRT, Cause.BLK_PLAYER_USE, T0 + 10, actor = alice)))
        log.drain()
        val chest = Container(world, 6, 64, 5, 3)
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(alice, 0), diamond, null, 7, T0 + 20))
        shared.drain()

        val planned = around(RowFilter(shared, LookupQuery(), emptySet(), emptySet(), rollback = true))
        val at = planned.chunks.single().positions.associateBy { it.at }
        assertEquals(listOf(DIRT, STONE), at.getValue(WorldBlock(world, 5, 64, 5)).steps.map { it.after })
        val refill = at.getValue(WorldBlock(world, 6, 64, 5)).refills.single()
        assertEquals(7, refill.qty)
        assertEquals(chest, refill.at)
    }

    // The second rollback of the same theft would mint the diamonds a second time.
    @Test
    fun `a slot posting already given back is not planned again`() {
        val chest = Container(world, 6, 64, 5, 3)
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(alice, 0), diamond, null, 7, T0))
        shared.drain()
        val theft = shared.holderEntries(chest, 0, Long.MAX_VALUE).single()
        shared.submit(Transfer(Cause.ROLLBACK, Void, chest, diamond, null, 7, T0 + 10, reverts = listOf(theft.ref)))
        shared.drain()

        val planned = around(RowFilter(shared, LookupQuery(), emptySet(), emptySet(), rollback = true))
        assertTrue(planned.chunks.isEmpty(), "${planned.chunks.flatMap { it.positions }.map { it.at }}")
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

        val planned = around(RowFilter(shared, LookupQuery(causes = Action.ROLLBACK.causes), emptySet(), emptySet(), rollback = true))
        assertEquals(listOf(-5, 5), planned.chunks.single().positions.single().refills.map { it.qty })
        val plain = around(RowFilter(shared, LookupQuery(), emptySet(), emptySet(), rollback = true))
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
        val filter = RowFilter(shared, LookupQuery(), setOf(alice), emptySet(), rollback = true)
        val planned = reader.at(world, touched.getValue(world) + WorldBlock(world, 1, 64, 2), 0, Long.MAX_VALUE, filter::keeps, filter::keeps)
            as Planned
        val positions = planned.chunks.flatMap { it.positions }.associateBy { it.at }
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
        log.submit(listOf(io.pfaumc.pfauprotect.storage.EntityChange(3, 64, 3, io.pfaumc.pfauprotect.model.EntityKind.CHANGED, Cause.ENTITY_CHANGED, "minecraft:horse", horse, T0, actor = alice, before = untouched, after = renamed, drops = listOf(cut))))
        log.submit(listOf(io.pfaumc.pfauprotect.storage.EntityChange(4, 64, 3, io.pfaumc.pfauprotect.model.EntityKind.REMOVED, Cause.ENTITY_KILLED, "minecraft:horse", horse, T0 + 10, actor = alice, before = renamed, drops = listOf(pile))))
        log.drain()
        shared.submit(Transfer(Cause.CONTAINER_BREAK_DROP, io.pfaumc.pfauprotect.model.EntitySlot(horse, 400), io.pfaumc.pfauprotect.model.ItemEntityRef(UUID.randomUUID()), diamond, null, 1, T0 + 10))
        shared.drain()

        val planned = around(RowFilter(shared, LookupQuery(), setOf(alice), emptySet(), rollback = true))
        val plan = planned.chunks.flatMap { it.positions }.flatMap { it.entities }.single()
        assertEquals(io.pfaumc.pfauprotect.model.EntityKind.CHANGED, plan.oldest.kind)
        assertEquals(untouched.toList(), plan.before!!.toList())
        assertEquals(listOf(-1), plan.slots.map { it.qty })
        assertEquals(setOf(cut, pile), plan.drops.toSet())
        assertTrue(plan.removed, "it went within the window, so it is brought back rather than changed back")
        assertEquals(WorldBlock(world, 3, 64, 3), plan.at)
    }

    // The widest radius reads a square of chunks larger than any lookup does, and has to stay inside
    // what the region read allows; a world with no open base has no history to roll back over.
    @Test
    fun `the widest radius reads and a world with no open base is refused`() {
        val reading = RollbackReader(shared, logs).around(
            world, 0, 64, 0, 200, 0, Long.MAX_VALUE, { true }, { true },
        )
        assertTrue(reading is Planned)
        val refused = RollbackReader(shared, logs).around(
            UUID.randomUUID(), 0, 64, 0, 4, 0, Long.MAX_VALUE, { true }, { true },
        )
        assertTrue(refused is Refused, "a world with no open base has nothing to roll back over")
    }

    // A stolen stack goes back into the slot it left; when somebody has filled that slot since, it goes
    // wherever else in the same chest it fits, and what fits nowhere is said rather than lost quietly.
    @Test
    fun `a slot posting goes back into its own slot first and then wherever it fits`() {
        ServerRegistries.access
        val chest = ContainerSlots(SimpleContainer(3))
        val diamonds = NmsItemStack(Items.DIAMOND)
        chest.set(1, NmsItemStack(Items.DIRT, 64))

        assertEquals(10, putBack(chest, 0, diamonds, 10) { false })
        assertEquals(10, chest.get(0).count)
        assertEquals(64, putBack(chest, 1, diamonds, 64) { false })
        assertEquals(64, chest.get(0).count, "topped up where the same item already lay")
        assertEquals(10, chest.get(2).count, "and the rest into the free slot")
        assertEquals(54, putBack(chest, 1, diamonds, 200) { false }, "54 more fit, the rest has no room")
    }

    // A lectern and a campfire are no containers, and a rollback still has to put their things back: the
    // book onto the lectern, the food onto the campfire, one item a slot.
    @Test
    fun `a book goes back onto a lectern and food back onto a campfire`() {
        ServerRegistries.access
        val lectern = net.minecraft.world.level.block.entity.LecternBlockEntity(net.minecraft.core.BlockPos(1, 64, 1), net.minecraft.world.level.block.Blocks.LECTERN.defaultBlockState())
        assertEquals(1, putBack(slotsOf(lectern)!!, 0, NmsItemStack(Items.WRITABLE_BOOK), 1) { false })
        assertEquals(Items.WRITABLE_BOOK, lectern.book.item)

        val campfire = net.minecraft.world.level.block.entity.CampfireBlockEntity(net.minecraft.core.BlockPos(2, 64, 1), net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState())
        assertEquals(2, putBack(slotsOf(campfire)!!, 1, NmsItemStack(Items.BEEF), 2) { false })
        // Its own slot first, then the first free one: one piece of food a slot.
        assertEquals(listOf(true, true, false, false), campfire.items.map { it.item == Items.BEEF })
    }

    // What arrived is taken out again from wherever it lies by now, and no more than is there.
    @Test
    fun `a slot posting taken out is looked for in the whole chest`() {
        ServerRegistries.access
        val chest = ContainerSlots(SimpleContainer(3))
        chest.set(2, NmsItemStack(Items.TNT, 5))
        val isTnt = { stack: NmsItemStack -> stack.item == Items.TNT }

        assertEquals(5, putBack(chest, 0, NmsItemStack(Items.TNT), -8, isTnt))
        assertTrue(chest.get(2).isEmpty)
    }

    // Alice poured lava from forty blocks over the house. Her rollback reaches it; a rollback of nobody in
    // particular keeps to the cube, so a build high above is not someone else's to lose.
    @Test
    fun `a rollback of named players reaches the whole height of its square`() {
        log.submit(listOf(BlockChange(2, 104, 2, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.drain()
        val filter = RowFilter(shared, LookupQuery(), setOf(alice), emptySet(), rollback = true)
        val named = RollbackReader(shared, logs).around(world, 0, 64, 0, 15, 0, Long.MAX_VALUE, filter::keeps, filter::keeps, column = true) as Planned
        assertEquals(listOf(WorldBlock(world, 2, 104, 2)), named.chunks.flatMap { it.positions }.map { it.at })
        val cube = RollbackReader(shared, logs).around(world, 0, 64, 0, 15, 0, Long.MAX_VALUE, filter::keeps, filter::keeps) as Planned
        assertTrue(cube.chunks.flatMap { it.positions }.isEmpty())
    }

    // A machine is put back with no signal in it, or it fires before it settles; a switch keeps its own state.
    @Test
    fun `a rollback puts redstone back with no signal in it`() {
        assertEquals("minecraft:comparator[facing=west,mode=subtract,powered=false]", quiet("minecraft:comparator[facing=west,mode=subtract,powered=true]"))
        assertEquals("minecraft:redstone_wire[east=side,north=none,power=0,south=side,west=none]", quiet("minecraft:redstone_wire[east=side,north=none,power=15,south=side,west=none]"))
        assertEquals("minecraft:observer[facing=north,powered=false]", quiet("minecraft:observer[facing=north,powered=true]"))
        assertEquals("minecraft:redstone_torch[lit=true]", quiet("minecraft:redstone_torch[lit=false]"))
        assertEquals("minecraft:lever[face=floor,facing=east,powered=true]", quiet("minecraft:lever[face=floor,facing=east,powered=true]"))
        assertEquals("minecraft:light_weighted_pressure_plate[power=4]", quiet("minecraft:light_weighted_pressure_plate[power=4]"))
        assertEquals("minecraft:dispenser[facing=east,triggered=true]", quiet("minecraft:dispenser[facing=east,triggered=true]"))
        assertEquals("minecraft:stone", quiet("minecraft:stone"))
    }

    // A preview bigger than what a player is shown shows the part around them, then other worlds.
    @Test
    fun `a preview shows the nearest ghosts first`() {
        ServerRegistries.access
        val stone = Blocks.STONE.defaultBlockState().asBlockData()
        val nether = UUID.fromString("00000000-0000-4000-8000-000000000007")
        val ghosts = listOf(
            Ghost(world, 150, 64, 0, stone), Ghost(nether, 1, 64, 0, stone), Ghost(world, 3, 64, 0, stone),
            Ghost(world, -40, 64, 0, stone), Ghost(world, 0, 70, 0, stone),
        )
        val shown = nearest(ghosts, world, 0.5, 64.0, 0.5, 4).map { Triple(it.world == world, it.x, it.y) }
        assertEquals(listOf(Triple(true, 3, 64), Triple(true, 0, 70), Triple(true, -40, 64), Triple(true, 150, 64)), shown)
        assertEquals(nether, nearest(ghosts, world, 0.5, 64.0, 0.5, 5).last().world)
    }

    private fun around(filter: RowFilter): Planned {
        val reading = RollbackReader(shared, logs).around(
            world, 0, 64, 0, 15, 0, Long.MAX_VALUE, filter::keeps, filter::keeps,
        )
        return reading as? Planned ?: throw AssertionError((reading as Refused).reason)
    }

    private fun steps(planned: Planned) = planned.chunks.flatMap { it.positions }.flatMap { it.steps }
}
