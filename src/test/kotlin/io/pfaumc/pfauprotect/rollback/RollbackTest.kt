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
        assertEquals(listOf(AIR), steps(plain).map { it.after }, "only the break")
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
        val untouched = byteArrayOf(10, 0, 0, 1)
        val renamed = byteArrayOf(10, 0, 0, 2)
        log.submit(listOf(io.pfaumc.pfauprotect.storage.EntityChange(3, 64, 3, io.pfaumc.pfauprotect.model.EntityKind.CHANGED, Cause.ENTITY_CHANGED, "minecraft:horse", horse, T0, actor = alice, before = untouched, after = renamed)))
        log.submit(listOf(io.pfaumc.pfauprotect.storage.EntityChange(4, 64, 3, io.pfaumc.pfauprotect.model.EntityKind.REMOVED, Cause.ENTITY_KILLED, "minecraft:horse", horse, T0 + 10, actor = alice, before = renamed, drops = listOf(pile))))
        log.drain()
        shared.submit(Transfer(Cause.CONTAINER_BREAK_DROP, io.pfaumc.pfauprotect.model.EntitySlot(horse, 400), io.pfaumc.pfauprotect.model.ItemEntityRef(UUID.randomUUID()), diamond, null, 1, T0 + 10))
        shared.drain()

        val planned = around(RowFilter(shared, LookupQuery(), setOf(alice), emptySet(), rollback = true))
        val plan = planned.chunks.flatMap { it.positions }.flatMap { it.entities }.single()
        assertEquals(io.pfaumc.pfauprotect.model.EntityKind.CHANGED, plan.oldest.kind)
        assertEquals(untouched.toList(), plan.before!!.toList())
        assertEquals(listOf(-1), plan.slots.map { it.qty })
        assertEquals(listOf(pile), plan.drops)
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

    private fun around(filter: RowFilter): Planned {
        val reading = RollbackReader(shared, logs).around(
            world, 0, 64, 0, 15, 0, Long.MAX_VALUE, filter::keeps, filter::keeps,
        )
        return reading as? Planned ?: throw AssertionError((reading as Refused).reason)
    }

    private fun steps(planned: Planned) = planned.chunks.flatMap { it.positions }.flatMap { it.steps }
}
