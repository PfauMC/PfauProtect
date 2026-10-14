package io.pfaumc.pfauprotect.rollback

import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.capture.block.Standing
import io.pfaumc.pfauprotect.command.EventRef
import io.pfaumc.pfauprotect.command.parseLookupQuery
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.PostingRef
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.BlockRow
import io.pfaumc.pfauprotect.storage.ItemKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import net.minecraft.world.SimpleContainer
import net.minecraft.world.level.block.Blocks
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.CampfireBlockEntity
import net.minecraft.world.level.block.entity.LecternBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.item.Items
import java.util.UUID
import net.minecraft.world.item.ItemStack as NmsItemStack

private const val AIR = "minecraft:air"
private const val STONE = "minecraft:stone"
private const val DIRT = "minecraft:dirt"
private const val LAVA = "minecraft:lava[level=0]"

@ExtendWith(Vanilla::class)
class RollbackRulesTest {
    private val world: UUID = UUID.fromString("00000000-0000-4000-8000-000000000006")
    private val diamond = byteArrayOf(9)

    private fun step(before: String, after: String) = Step(before, after, null)

    private fun planOf(vararg steps: Step) =
        PositionPlan(WorldBlock(world, 1, 64, 1), steps.toList(), emptyList(), emptyList(), emptyList(), emptyList(), null)

    private fun counted(standing: String, payload: ByteArray? = null, vararg steps: Step): Pair<Tally, Boolean> {
        val plan = planOf(*steps)
        val tally = Tally()
        val changes = tally.count(plan, decide(standing, payload, { false }, plan))
        return tally to changes
    }

    // Undone once already, the position stands on the row's `before`; a second rollback finds nothing
    // to do there, and that is not a conflict. So for a chain: the plank that burnt and then took the
    // griefer's lava is a plank again. Dirt somebody else put in its place is still somebody else's.
    @Test
    fun `a row already undone is stepped over`() {
        val settled = settle(STONE, listOf(step(STONE, AIR)))
        assertNull(settled.back)
        assertFalse(settled.conflict)
        val chain = listOf(step("minecraft:fire[age=13]", "minecraft:lava[level=8]"), step("minecraft:oak_planks", "minecraft:fire[age=13]"))
        val again = settle("minecraft:oak_planks", chain)
        assertNull(again.back)
        assertFalse(again.conflict)
        assertTrue(settle(DIRT, chain).conflict)
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
        val opened = step("minecraft:oak_door[open=false,powered=false]", "minecraft:oak_door[open=true,powered=false]")
        assertSame(opened, settle("minecraft:oak_door[open=true,powered=true]", listOf(opened)).back)
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
        // Her water ran in, became a source by itself between two others, and her sponge drank it twice
        // over: the source the walk meets where her row left a flow is the same water.
        val drunk = settle(AIR, listOf(step("minecraft:water[level=0]", AIR), step("minecraft:water[level=0]", AIR), step(AIR, "minecraft:water[level=3]")))
        assertEquals(AIR, drunk.back?.before)
        assertFalse(drunk.conflict)
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

    // Alice put stone into running water before the window and broke it inside. The air the break left is
    // not the running water it was put into: the stone is what the window opens on, and it goes back.
    @Test
    fun `air left by a break is not the running water the block was put into`() {
        val flow = "minecraft:water[level=3]"
        assertEquals(STONE, settle(AIR, listOf(Step(STONE, AIR, null)), flow).back?.before)
        assertNull(settle(AIR, listOf(Step(STONE, AIR, null)), AIR).back)
    }

    // A row this build cannot read may be anybody's change, so no beginning is known past it.
    @Test
    fun `an unreadable row ends the walk back to the origin`() {
        val placed = BlockRow(1, 64, 1, T0, 1, 0, Cause.BLK_PLAYER_PLACE, 0, 1, actor = alice)
        assertEquals(0, originOf(listOf(placed)) { it.actor == alice })
        assertNull(originOf(listOf(null, placed)) { it.actor == alice })
    }

    // Undoing a rollback goes back to what that rollback found, not on past it to before the player it
    // undid; a rollback the filter leaves alone is walked past like nature.
    @Test
    fun `a kept rollback row leaves the position without an origin`() {
        val placed = BlockRow(1, 64, 1, T0, 1, 0, Cause.BLK_PLAYER_PLACE, 0, 1, actor = alice)
        val undone = BlockRow(1, 64, 1, T0 + 10, 2, 0, Cause.ROLLBACK, 1, 0, actor = alice)
        assertNull(originOf(listOf(undone, placed)) { true })
        assertEquals(0, originOf(listOf(undone, placed)) { it.cause != Cause.ROLLBACK })
    }

    @Test
    fun `a wall joined otherwise than at the origin is not as it was`() {
        val joined = "minecraft:diorite_wall[east=low,north=none,south=none,up=false,waterlogged=false,west=low]"
        val loose = "minecraft:diorite_wall[east=low,north=none,south=none,up=true,waterlogged=false,west=none]"
        val steps = listOf(step(joined, loose))
        assertEquals(joined, settle(loose, steps, joined).back?.before)
        assertNull(settle(joined, steps, joined).back)
    }

    @Test
    fun `the airs are one`() {
        val dug = step(STONE, AIR)
        assertSame(dug, settle("minecraft:cave_air", listOf(dug)).back)
    }

    @Test
    fun `a rollback is refused without a place and a time to undo`() {
        val refusals = mapOf(
            "r:5" to "needs time",
            "t:1h r:5 player:Steve" to "use user:",
            "t:1h" to "needs radius",
            "t:1h r:global" to "give user:",
            "t:1h r:5 amount:>=8" to "amount:",
            "t:1h r:5 rolledback:no" to "rolledback:",
            "t:1h r:5 a:steal" to "action:steal",
            EventRef(1, 0, 64, 0, T0).toString() to null,
            "t:1h r:5" to null,
            "t:1h r:global u:Steve" to null,
        )
        for ((query, refusal) in refusals) {
            val said = refusalOf(parseLookupQuery(query))
            if (refusal == null) assertNull(said, "$query") else assertTrue(said?.contains(refusal) == true, "$query -> $said")
        }
    }

    // A posting given back is filed against the rows that carry it, so the next rollback skips it. With no
    // slot moving to carry it, a bare mark has to, or it is given back a second time.
    @Test
    fun `postings given back ride on what moved or else on a bare mark`() {
        val given = listOf(PostingRef(7, 0), PostingRef(7, 1))
        val back = GivenBack().apply { postings += given; marker = diamond }
        val chest = Blocks.CHEST.defaultBlockState()
        fun difference() = rollbackDifference(world, alice, T0)

        val nothing = difference().apply { add(1, 64, 1, Standing(chest, null, listOf(null)), Standing(chest, null, listOf(null))) }
        val mark = givenBackRows(nothing, back, alice).single()
        assertTrue(mark.from == Void && mark.to == Void && mark.cause == Cause.ROLLBACK, "${mark.from} -> ${mark.to}")
        assertEquals(given, mark.reverts)
        assertTrue(givenBackRows(nothing, GivenBack(), alice).isEmpty())

        val stack = ItemKey(diamond, null) to 7
        val filled = difference().apply { add(1, 64, 1, Standing(chest, null, listOf(null)), Standing(chest, null, listOf(stack))) }
        val rows = givenBackRows(filled, back, alice)
        assertEquals(listOf(Container(world, 1, 64, 1, 0)), rows.map { it.to })
        assertTrue(rows.all { it.cause == Cause.ROLLBACK && it.from == Void && it.qty == 7 }, "$rows")
        assertEquals(given, rows.flatMap { it.reverts })

        val dug = difference().apply { add(1, 64, 2, Standing(Blocks.STONE.defaultBlockState(), null, null), Standing(Blocks.AIR.defaultBlockState(), null, null)) }
        assertEquals(listOf(Cause.ROLLBACK), dug.rows.map { it.cause })
    }

    // A stolen stack goes back into the slot it left; when somebody has filled that slot since, it goes
    // wherever else in the same chest it fits, and what fits nowhere is said rather than lost quietly.
    @Test
    fun `a slot posting goes back into its own slot first and then wherever it fits`() {
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

    @Test
    fun `a book goes back onto a lectern and food back onto a campfire`() {
        val lectern = LecternBlockEntity(BlockPos(1, 64, 1), Blocks.LECTERN.defaultBlockState())
        assertEquals(1, putBack(slotsOf(lectern)!!, 0, NmsItemStack(Items.WRITABLE_BOOK), 1) { false })
        assertEquals(Items.WRITABLE_BOOK, lectern.book.item)

        val campfire = CampfireBlockEntity(BlockPos(2, 64, 1), Blocks.CAMPFIRE.defaultBlockState())
        assertEquals(2, putBack(slotsOf(campfire)!!, 1, NmsItemStack(Items.BEEF), 2) { false })
        assertEquals(listOf(true, true, false, false), campfire.items.map { it.item == Items.BEEF })

        assertNull(slotsOf(SignBlockEntity(BlockPos(3, 64, 1), Blocks.OAK_SIGN.defaultBlockState())))
    }

    @Test
    fun `a slot posting taken out is looked for in the whole chest`() {
        val chest = ContainerSlots(SimpleContainer(3))
        chest.set(2, NmsItemStack(Items.TNT, 5))
        val isTnt = { stack: NmsItemStack -> stack.item == Items.TNT }

        assertEquals(5, putBack(chest, 0, NmsItemStack(Items.TNT), -8, isTnt))
        assertTrue(chest.get(2).isEmpty)
    }

    @Test
    fun `a rollback puts redstone back with no signal in it`() {
        assertEquals("minecraft:comparator[facing=west,mode=subtract,powered=false]", quiet("minecraft:comparator[facing=west,mode=subtract,powered=true]"))
        assertEquals("minecraft:redstone_wire[east=side,north=none,power=0,south=side,west=none]", quiet("minecraft:redstone_wire[east=side,north=none,power=15,south=side,west=none]"))
        assertEquals("minecraft:observer[facing=north,powered=false]", quiet("minecraft:observer[facing=north,powered=true]"))
        assertEquals("minecraft:redstone_torch[lit=true]", quiet("minecraft:redstone_torch[lit=false]"))
        assertEquals("minecraft:lever[face=floor,facing=east,powered=true]", quiet("minecraft:lever[face=floor,facing=east,powered=true]"))
        assertEquals("minecraft:light_weighted_pressure_plate[power=4]", quiet("minecraft:light_weighted_pressure_plate[power=4]"))
        assertEquals("minecraft:dispenser[facing=east,triggered=true]", quiet("minecraft:dispenser[facing=east,triggered=true]"))
        // A leaf's distance is its neighbours'; one a player placed keeps it, never decaying anyway.
        assertEquals("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]", quiet("minecraft:oak_leaves[distance=7,persistent=false,waterlogged=false]"))
        assertEquals("minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]", quiet("minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]"))
        assertEquals("minecraft:stone", quiet("minecraft:stone"))
    }

    @Test
    fun `a preview shows the nearest ghosts first`() {
        val stone = Blocks.STONE.defaultBlockState().asBlockData()
        val nether = UUID.fromString("00000000-0000-4000-8000-000000000007")
        val ghosts = listOf(
            Ghost(WorldBlock(world, 150, 64, 0), stone), Ghost(WorldBlock(nether, 1, 64, 0), stone), Ghost(WorldBlock(world, 3, 64, 0), stone),
            Ghost(WorldBlock(world, -40, 64, 0), stone), Ghost(WorldBlock(world, 0, 70, 0), stone),
        )
        val shown = nearest(ghosts, world, 0.5, 64.0, 0.5, 4).map { Triple(it.at.world == world, it.at.x, it.at.y) }
        assertEquals(listOf(Triple(true, 3, 64), Triple(true, 0, 70), Triple(true, -40, 64), Triple(true, 150, 64)), shown)
        assertEquals(nether, nearest(ghosts, world, 0.5, 64.0, 0.5, 5).last().at.world)
    }

    @Test
    fun `a position is counted as changed, as it was, or stopped by a later change`() {
        val (broken, changes) = counted(STONE, null, step(AIR, STONE))
        assertTrue(changes)
        assertEquals(listOf(1, 0, 0), listOf(broken.changed, broken.unchanged, broken.conflicts))
        assertEquals(listOf(WorldBlock(world, 1, 64, 1)), broken.undone)

        val (already, _) = counted(AIR, null, step(AIR, STONE))
        assertEquals(listOf(0, 1, 0), listOf(already.changed, already.unchanged, already.conflicts))

        val (stopped, _) = counted(DIRT, null, step(AIR, STONE))
        assertEquals(listOf(0, 0, 1), listOf(stopped.changed, stopped.unchanged, stopped.conflicts))

        // Walked back past the newest row and then stopped by an older one: what it got to is put back, and
        // the stop is said all the same.
        val (partial, _) = counted(DIRT, null, step(STONE, DIRT), step(AIR, LAVA))
        assertEquals(listOf(1, 0, 1), listOf(partial.changed, partial.unchanged, partial.conflicts))
    }

    @Test
    fun `a block standing as it stood gets its tag back unless its slots carry what it held`() {
        val sign = "minecraft:oak_sign[rotation=0,waterlogged=false]"
        val written = Step(sign, sign, byteArrayOf(1))
        val plan = planOf(written)

        val retagged = decide(sign, byteArrayOf(2), { false }, plan)
        assertFalse(retagged.reshaped)
        assertTrue(retagged.retagged)
        assertFalse(decide(sign, byteArrayOf(2), { true }, plan).retagged, "a container's contents are its postings' to give back")
        assertFalse(decide(sign, byteArrayOf(1), { false }, plan).retagged)
        assertEquals(listOf(1, 0), counted(sign, byteArrayOf(2), written).first.let { listOf(it.changed, it.undone.size) })
    }

    @Test
    fun `the postings of a container the rollback took away are given back with it`() {
        val chest = Container(world, 1, 64, 1, 0)
        val player = PlayerInv(alice, 0)
        fun refill(ordinal: Int, qty: Int, lead: Holder) =
            Refill(chest, diamond, 5, null, qty, PostingRef(9, ordinal), lead)
        val hopper = refill(0, 3, Container(world, 1, 63, 1, 0))
        val carried = refill(1, 2, player)
        val arrived = refill(2, -4, Container(world, 1, 65, 1, 0))
        val tally = Tally()
        val given = GivenBack()
        for (r in listOf(hopper, carried, arrived)) tally.giveBack(r, null, removedHere = true, given) { error("no container to move into") }

        assertEquals(listOf(PostingRef(9, 0), PostingRef(9, 1), PostingRef(9, 2)), given.postings)
        assertTrue(given.marker.contentEquals(diamond))
        assertEquals(0, tally.missed)
        assertEquals(listOf(player to 2, arrived.lead to -4), tally.traces.map { it.lead to it.qty }, "a stop on the way is undone there, not followed")

        val left = Tally()
        val none = GivenBack()
        left.giveBack(hopper, null, removedHere = false, none) { error("no container to move into") }
        assertEquals(1, left.missed, "a container that went some other way leaves the posting unmet")
        assertTrue(none.postings.isEmpty())

        val short = Tally()
        val some = GivenBack()
        short.giveBack(hopper, ContainerSlots(SimpleContainer(1)), removedHere = false, some) { 1 }
        assertEquals(1, short.missed)
        assertEquals(listOf(PostingRef(9, 0)), some.postings)
        assertEquals(listOf(1), short.traces.map { it.qty }, "what moved is followed, not what was asked")
    }
}
