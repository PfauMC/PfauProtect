package io.pfaumc.pfauprotect.capture.block
import io.pfaumc.pfauprotect.AIR
import io.pfaumc.pfauprotect.DIRT
import io.pfaumc.pfauprotect.GRASS
import io.pfaumc.pfauprotect.SOURCE_WATER
import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.worldStub
import io.pfaumc.pfauprotect.disabledPlugin
import io.pfaumc.pfauprotect.BothPlanes
import io.pfaumc.pfauprotect.Spawns
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import com.destroystokyo.paper.event.block.BlockDestroyEvent
import io.pfaumc.pfauprotect.attribution.Attributed
import io.pfaumc.pfauprotect.attribution.Attribution
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.BlockRow
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.attribution.EntityOrigins
import io.pfaumc.pfauprotect.attribution.Falling
import io.pfaumc.pfauprotect.check.SETTLE_MILLIS
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.stub
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack as NmsItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.bukkit.ExplosionResult
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.World
import org.bukkit.entity.Creeper
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

private const val OAK_LOG = "minecraft:oak_log[axis=y]"
private const val SHULKER_BOX = "minecraft:shulker_box[facing=up]"
private const val SAND = "minecraft:sand"
private const val PISTON = "minecraft:piston[extended=false,facing=east]"
private const val PISTON_EXTENDED = "minecraft:piston[extended=true,facing=east]"
private const val PISTON_HEAD = "minecraft:piston_head[facing=east,short=false,type=normal]"
private const val WHITE_BED_HEAD = "minecraft:white_bed[facing=east,occupied=false,part=head]"
private const val WHITE_BED_FOOT = "minecraft:white_bed[facing=east,occupied=false,part=foot]"

@ExtendWith(Vanilla::class)
class BlockDestructionTest : BothPlanes() {
    override val world: UUID = UUID.fromString("00000000-0000-4000-8000-000000000003")
    private val codec by lazy { ItemFormCodec(shared.registries, ServerRegistries.access) }

    // The first varint of a form is the item type, which is what decides whether the form remembered
    // at a position still belongs to the block standing in it. A trailing byte sets each apart from the
    // shell a position with nothing remembered would give back.
    private fun named(data: BlockData) = codec.shellOf(data)!! + 1
    private val namedBed by lazy { named(whiteBedHead) }
    private val dirtForm by lazy { named(Blocks.DIRT.defaultBlockState().asBlockData()) }
    private val grassForm by lazy { named(Blocks.GRASS_BLOCK.defaultBlockState().asBlockData()) }
    private val sandForm by lazy { named(Blocks.SAND.defaultBlockState().asBlockData()) }

    private val spawns = Spawns()
    private val origins = spawns.origins
    private val entityOrigins = EntityOrigins()

    private val airData: BlockData get() = Blocks.AIR.defaultBlockState().asBlockData()

    // Late enough for every row the handlers write at the wall clock to have settled.
    private val settled get() = System.currentTimeMillis() + 10 * SETTLE_MILLIS

    private fun listener(
        attribution: Attribution = Attribution(shared.registries, logs),
        sink: (List<Transfer>) -> Unit = {},
        touches: HandTouches = HandTouches { now },
        later: (Location, () -> Unit) -> Unit = { _, _ -> },
    ) = BlockDestructionListener(
        // Disabled, so a read-back is never queued: there is no region scheduler to queue it on.
        plugin = disabledPlugin(),
        registries = shared.registries,
        logs = logs,
        attribution = attribution,
        codec = codec,
        origins = origins,
        entities = entityOrigins,
        placed = shared,
        owners = shared,
        sink = sink,
        touches = touches,
        later = later,
    )

    /**
     * The choice between writing a position off and exchanging what it holds, driven where the listener
     * really makes it. The payload is handed in rather than read off the block, which is what the
     * read-back does as well: by the time it runs there is nothing left to read one from.
     */
    private fun releasedBy(
        listener: BlockDestructionListener,
        at: WorldBlock,
        before: BlockData,
        after: String,
        remembered: ByteArray? = null,
        went: WorldBlock? = null,
    ): List<Transfer> {
        val site = Site(blockStub(world, at.x, at.y, at.z, before), before, after, payload = null, went = went)
        return listener.released(site, remembered, Cause.BLK_FADE, null, now)
    }

    private fun nameOf(stateId: Int) = shared.registries.keyOf(RegistryNamespace.BLOCK_STATE, stateId)

    private fun rowsAt(x: Int, y: Int, z: Int) = log.at(x, y, z, 0, Long.MAX_VALUE, limit = 100, reverse = false)

    // A block that turned into another item-backed block was not destroyed, so a write-off would invent
    // a loss; an exchange on a position left empty would credit it with something nobody made.
    @Test
    fun `a position emptied is written off and one another block took over is exchanged`() {
        val listener = listener()
        val at = WorldBlock(world, 12, 64, 12)
        val torch = Blocks.TORCH.defaultBlockState().asBlockData()
        val dirt = Blocks.DIRT.defaultBlockState().asBlockData()

        val plainTorch = codec.encode(NmsItemStack(Items.TORCH)).form
        assertArrayEquals(plainTorch, releasedBy(listener, at, torch, AIR).single().form)
        val namedTorch = plainTorch + 1
        for (empty in listOf(AIR, SOURCE_WATER)) {
            val off = releasedBy(listener, at, torch, empty, namedTorch)
            assertEquals(1, off.size, empty)
            assertEquals(Kind.TRANSFER, off.single().kind, empty)
            assertEquals(at, off.single().from, empty)
            assertEquals(Void, off.single().to, empty)
            // What the position was credited with is what it gives back, not a shell encoded afresh.
            assertArrayEquals(namedTorch, off.single().form, empty)
        }

        val spread = releasedBy(listener, at, dirt, GRASS)

        assertEquals(listOf(Kind.MUTATE, Kind.MUTATE), spread.map { it.kind })
        assertEquals(listOf(at, Void), spread.map { it.from })
        assertEquals(listOf(Void, at), spread.map { it.to })
        assertArrayEquals(codec.encode(NmsItemStack(Items.DIRT)).form, spread[0].form)
        assertArrayEquals(codec.encode(NmsItemStack(Items.GRASS_BLOCK)).form, spread[1].form)
        val namedDirt = spread[0].form + 1
        assertArrayEquals(namedDirt, releasedBy(listener, at, dirt, GRASS, namedDirt)[0].form)

        // The arriving form is the block standing there now: a different block arrives differently.
        val grown = releasedBy(listener, at, dirt, OAK_LOG)
        assertEquals(2, grown.size)
        assertArrayEquals(spread[0].form, grown[0].form)
        assertArrayEquals(codec.encode(NmsItemStack(Items.OAK_LOG)).form, grown[1].form)
        // Both halves of a bed lose their block and the bed was paid for once: the half that remembers
        // nothing speaks for nothing, whichever shape the change would otherwise take.
        assertEquals(emptyList<Transfer>(), releasedBy(listener, at, whiteBedHead, AIR))
        assertEquals(emptyList<Transfer>(), releasedBy(listener, at, whiteBedHead, SHULKER_BOX))
    }

    // The bed went off from its head, where the player clicked; the foot is cleared by the server under no
    // event of its own and is the half that remembers the form the bed was put down as.
    @Test
    fun `a bed blown up is one event over both its positions and leaves the two planes agreeing`() {
        val (_, listener, written) = watched()
        val foot = WorldBlock(world, 4, 64, 4)
        blowUpAlicesBed(listener)

        val off = written.flatten().single()
        assertEquals(foot, off.from)
        assertEquals(Void, off.to)
        assertArrayEquals(namedBed, off.form)
        assertEquals(Cause.BLK_BED_EXPLOSION, off.cause)
        assertEquals(alice, off.actor)
        // The guess is about who and not whether: the block did go.
        assertEquals(Confidence.FACT, off.confidence)

        shared.submit(written.flatten())
        drainBoth()
        val footRows = rowsAt(4, 64, 4)
        val headRows = rowsAt(5, 64, 4)
        assertEquals(listOf(Cause.BLK_PLAYER_PLACE, Cause.BLK_BED_EXPLOSION), footRows.map { it.cause })
        assertEquals(listOf(Cause.BLK_PLAYER_PLACE, Cause.BLK_BED_EXPLOSION), headRows.map { it.cause })
        // Who put the bed down is read back from the journal, which is a guess.
        assertEquals(Confidence.INFERRED, footRows[1].confidence)
        assertEquals(alice, footRows[1].actor)
        assertEquals(footRows[1].eventId, headRows[1].eventId)
        assertEquals(listOf(AIR, AIR), listOf(footRows[1], headRows[1]).map { nameOf(it.stateAfter) })
        assertEquals(listOf(WHITE_BED_FOOT, WHITE_BED_HEAD), listOf(footRows[1], headRows[1]).map { nameOf(it.stateBefore) })

        val report = assertPlanesAgree(settled)
        assertEquals(1, report.checked)
        assertEquals(0, report.overdrawn)
    }

    // Alice's bed with its foot at 4 64 4 and its head at 5 64 4, gone off from the head.
    private fun blowUpAlicesBed(listener: BlockDestructionListener) {
        heldAt(4, 64, 4, namedBed)
        shared.setFormAt(WorldBlock(world, 4, 64, 4), namedBed)
        log.submit(
            listOf(
                BlockChange(4, 64, 4, AIR, WHITE_BED_FOOT, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice),
                BlockChange(5, 64, 4, AIR, WHITE_BED_HEAD, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice),
            )
        )
        drainBoth()

        // Both halves are already air when the event is raised.
        val head = blockStub(world, 5, 64, 4, airData, mapOf(BlockFace.WEST to blockStub(world, 4, 64, 4, airData)))
        val exploded = stub(BlockState::class.java, mapOf("getBlockData" to whiteBedHead))
        listener.onBlockExplode(BlockExplodeEvent(head, exploded, mutableListOf(), 1f, ExplosionResult.DESTROY))
    }

    // A bed in the Nether goes off under the click of whoever tried to sleep in it, and that is seen.
    @Test
    fun `a bed set off by a click is on whoever clicked it and not on whoever put it down`() {
        val touches = HandTouches { now }
        val (_, listener, written) = watched(touches)
        touches.touch(WorldBlock(world, 5, 64, 4), by = Attributed(bob, Confidence.FACT))

        blowUpAlicesBed(listener)

        assertEquals(bob, written.flatten().single().actor)
        shared.submit(written.flatten())
        drainBoth()
        assertEquals(bob to Confidence.FACT, rowsAt(4, 64, 4)[1].let { it.actor to it.confidence })
    }

    /**
     * A piston push in both planes: two block rows under one event, and one transfer between the two
     * positions. Written as a disappearance in the position it left, the block would be duplicated by
     * every backwards reading of the journal and the position it arrived in would hold nothing.
     */
    @Test
    fun `a line a piston pushed leaves every position right and the planes agreeing`() {
        val (_, listener, written) = watched()
        val first = WorldBlock(world, 1, 64, 0)
        val middle = WorldBlock(world, 2, 64, 0)
        val last = WorldBlock(world, 3, 64, 0)
        heldAt(1, 64, 0, dirtForm)
        heldAt(2, 64, 0, grassForm)
        shared.setFormAt(first, dirtForm)
        shared.setFormAt(middle, grassForm)
        log.submit(
            listOf(
                BlockChange(1, 64, 0, AIR, DIRT, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice),
                BlockChange(2, 64, 0, AIR, GRASS, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice),
            )
        )
        drainBoth()

        // A piston at 0 64 0 facing east, with dirt and grass in front of it and air behind them.
        val grass = blockStub(world, 2, 64, 0, Blocks.GRASS_BLOCK.defaultBlockState().asBlockData(), mapOf(BlockFace.EAST to blockStub(world, 3, 64, 0, airData)))
        val dirt = blockStub(world, 1, 64, 0, Blocks.DIRT.defaultBlockState().asBlockData(), mapOf(BlockFace.EAST to grass))
        val base = blockStub(
            world,
            0, 64, 0,
            Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST).asBlockData(),
            mapOf(BlockFace.EAST to dirt),
        )
        listener.onPistonExtend(BlockPistonExtendEvent(base, listOf(dirt, grass), BlockFace.EAST))

        val moves = written.flatten()
        assertEquals(listOf(first to middle, middle to last), moves.map { it.from to it.to })
        assertEquals(listOf(dirtForm.toList(), grassForm.toList()), moves.map { it.form.toList() })
        assertTrue(moves.all { it.kind == Kind.TRANSFER && it.cause == Cause.BLK_PISTON_EXTEND && it.actor == null }, "$moves")

        shared.submit(moves)
        drainBoth()
        val left = rowsAt(1, 64, 0)
        val through = rowsAt(2, 64, 0)
        assertEquals(1, (left.drop(1) + through.drop(1) + rowsAt(0, 64, 0)).map { it.eventId }.distinct().size)
        // The middle position is both a position a block left and one a block arrived in, in that order.
        assertEquals(listOf(AIR, PISTON_HEAD), left.drop(1).map { nameOf(it.stateAfter) })
        assertEquals(listOf(AIR, DIRT), through.drop(1).map { nameOf(it.stateAfter) })
        assertEquals(GRASS, nameOf(log.standingAt(3, 64, 0).row!!.stateAfter))
        assertEquals(listOf(PISTON to PISTON_EXTENDED), rowsAt(0, 64, 0).map {
            nameOf(it.stateBefore) to nameOf(it.stateAfter)
        })

        assertEquals(0, shared.holderEntries(first, 0, Long.MAX_VALUE).sumOf { it.qty })
        val holding = shared.holderEntries(middle, 0, Long.MAX_VALUE)
            .groupingBy { it.itemFormId }
            .fold(0) { held, entry -> held + entry.qty }
        assertEquals(mapOf(shared.forms.lookup(dirtForm) to 1, shared.forms.lookup(grassForm) to 0), holding)
        assertEquals(shared.forms.lookup(grassForm), shared.holderEntries(last, 0, Long.MAX_VALUE).single().itemFormId)
        // What a position was holding goes with its block, so a break where it arrived gives that back.
        assertArrayEquals(dirtForm, shared.formAt(middle))
        assertArrayEquals(grassForm, shared.formAt(last))

        val report = assertPlanesAgree(settled)
        assertEquals(3, report.checked)
        assertEquals(0, report.overdrawn)
        assertEquals(emptyList<String>(), shared.sweep(100).gaps)
    }

    /**
     * The same movement with a flight in the middle of it. The two ends are separate events — nothing
     * positional survives the flight — and the item plane still books one transfer between the two
     * positions rather than a loss and a birth.
     */
    @Test
    fun `a block that fell is written at both ends and transferred once`() {
        val (attribution, listener, written) = watched()
        val entity = UUID.fromString("00000000-0000-4000-8000-0000000000f4")
        val from = WorldBlock(world, 5, 70, 5)
        val to = WorldBlock(world, 5, 64, 5)
        heldAt(5, 70, 5, sandForm)
        shared.setFormAt(from, sandForm)
        log.submit(listOf(BlockChange(5, 70, 5, AIR, SAND, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice)))
        drainBoth()
        // Bob took away what held it up.
        attribution.removed(WorldBlock(world, 5, 69, 5), bob)
        val sand = Blocks.SAND.defaultBlockState().asBlockData()
        val falling = stub(FallingBlock::class.java, mapOf("getUniqueId" to entity, "getBlockData" to sand))

        listener.onEntityChangeBlock(EntityChangeBlockEvent(falling, blockStub(world, 5, 70, 5, sand), airData))
        assertEquals(emptyList<List<Transfer>>(), written)
        listener.onEntityChangeBlock(EntityChangeBlockEvent(falling, blockStub(world, 5, 64, 5, airData), sand))

        val move = written.flatten().single()
        assertEquals(from, move.from)
        assertEquals(to, move.to)
        assertArrayEquals(sandForm, move.form)
        assertEquals(Cause.BLK_FALL_LAND, move.cause)
        assertEquals(bob, move.actor)
        // The guess is about who, not whether: the sand did land here.
        assertEquals(Confidence.FACT, move.confidence)

        shared.submit(listOf(move))
        drainBoth()
        val start = rowsAt(5, 70, 5)[1]
        val land = log.standingAt(5, 64, 5).row!!
        // Two events, not one: a flight has a tick or a minute in it and nothing joins its ends.
        assertNotEquals(start.eventId, land.eventId)
        assertEquals(listOf(Cause.BLK_FALL_START, Cause.BLK_FALL_LAND), listOf(start.cause, land.cause))
        // Who took the support away is the ladder's guess, and the landing carries that same guess on.
        assertEquals(listOf(bob, bob), listOf(start.actor, land.actor))
        assertEquals(listOf(Confidence.INFERRED, Confidence.INFERRED), listOf(start.confidence, land.confidence))
        assertEquals(AIR, nameOf(start.stateAfter))
        assertEquals(SAND, nameOf(land.stateAfter))

        assertEquals(0, shared.holderEntries(from, 0, Long.MAX_VALUE).sumOf { it.qty })
        assertEquals(1, shared.holderEntries(to, 0, Long.MAX_VALUE).sumOf { it.qty })

        val report = assertPlanesAgree(settled)
        assertEquals(2, report.checked)
        assertEquals(emptyList<String>(), shared.sweep(100).gaps)
    }

    private fun fallingStub(entity: UUID, x: Double, y: Double, z: Double) = stub(
        FallingBlock::class.java,
        mapOf("getUniqueId" to entity, "getLocation" to Location(worldStub(world), x, y, z)),
    )

    private data class Watched(
        val attribution: Attribution,
        val listener: BlockDestructionListener,
        val written: List<List<Transfer>>,
        val nextTick: List<() -> Unit>,
    )

    private fun watched(touches: HandTouches = HandTouches { now }): Watched {
        val attribution = Attribution(shared.registries, logs)
        val written = ArrayList<List<Transfer>>()
        val nextTick = ArrayList<() -> Unit>()
        return Watched(attribution, listener(attribution, { written += it }, touches) { _, task -> nextTick += task }, written, nextTick)
    }

    @Test
    fun `a flight that never landed is written off where it started`() {
        val (attribution, listener, written, nextTick) = watched()
        val entity = UUID.fromString("00000000-0000-4000-8000-0000000000f1")
        val from = WorldBlock(world, 2, 70, 2)
        val sand = Blocks.SAND.defaultBlockState().asBlockData()
        val named = releasedBy(listener, from, sand, AIR).single().form + 1
        heldAt(2, 70, 2, named)
        log.submit(listOf(BlockChange(2, 70, 2, AIR, SAND, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice)))
        log.submit(listOf(BlockChange(2, 70, 2, SAND, AIR, Cause.BLK_FALL_START, longAgo + 1)))
        // The form left with the block: the position was free the moment it broke loose, and reading
        // the table now would take whatever has moved in behind it instead.
        attribution.tookOff(entity, Falling(from, SAND, Attributed(bob), named))

        listener.onEntityRemove(EntityRemoveEvent(fallingStub(entity, 2.5, -70.0, 2.5), EntityRemoveEvent.Cause.OUT_OF_WORLD))
        // Nothing until the tick in which a drop could have claimed it is over.
        assertEquals(emptyList<List<Transfer>>(), written)
        nextTick.forEach { it() }

        val off = written.single().single()
        assertEquals(from, off.from)
        assertEquals(Void, off.to)
        assertEquals(Cause.BLK_FALL_START, off.cause)
        assertEquals(bob, off.actor)
        assertArrayEquals(named, off.form)
        assertNull(attribution.landed(entity))

        shared.submit(listOf(off))
        drainBoth()
        assertEquals(0, shared.holderEntries(from, 0, Long.MAX_VALUE).sumOf { it.qty })
        assertEquals(emptyList<String>(), shared.sweep(100).gaps)
    }

    /**
     * Sand that comes down on a torch is removed and then drops itself inside the same call, and that
     * item is what the position gave up: one movement out of the position into the item, on whoever
     * let the sand fall, and nothing left over to write off.
     */
    @Test
    fun `a flight broken on landing drops out of the position it left`() {
        val (attribution, listener, written, nextTick) = watched()
        val entity = UUID.fromString("00000000-0000-4000-8000-0000000000f3")
        val from = WorldBlock(world, 4, 70, 4)
        attribution.tookOff(entity, Falling(from, SAND, Attributed(bob), sandForm))

        listener.onEntityRemove(EntityRemoveEvent(fallingStub(entity, 4.5, 64.0, 4.5), EntityRemoveEvent.Cause.DISCARD))
        val item = UUID.randomUUID()
        assertEquals(1, origins.claim(item, Spot(world, 4.5, 64.0, 4.5), ItemKey(sandForm, null), 1))
        nextTick.forEach { it() }

        assertEquals(emptyList<List<Transfer>>(), written)
        val row = spawns.rows().single()
        assertEquals(from, row.from)
        assertEquals(ItemEntityRef(item), row.to)
        assertArrayEquals(sandForm, row.form)
        assertEquals(1, row.qty)
        assertEquals(Cause.BLK_FALL_START, row.cause)
        assertEquals(bob, row.actor)
        assertEquals(Confidence.FACT, row.confidence)
    }

    /**
     * A vine tip turns into stem the moment the next one is put on it, and a stem has no item of its own.
     * It is still the vine that was put down: the turn moves nothing and keeps the name the vine was put
     * down under, and the stem gives the vine back when it breaks.
     */
    @Test
    fun `a vine stem is the vine it was put down as`() {
        val listener = listener()
        val at = WorldBlock(world, 3, 64, 3)
        val vine = codec.encode(NmsItemStack(Items.TWISTING_VINES)).form
        val tip = Blocks.TWISTING_VINES.defaultBlockState().asBlockData()
        val stem = Blocks.TWISTING_VINES_PLANT.defaultBlockState().asBlockData()

        val off = releasedBy(listener, at, stem, AIR).single()
        assertEquals(Void, off.to)
        assertArrayEquals(vine, off.form)

        val named = vine + 1
        assertEquals(emptyList<Transfer>(), releasedBy(listener, at, tip, stem.asString, remembered = named))
        assertArrayEquals(named, shared.formAt(WorldBlock(world, 3, 64, 3)))
    }

    // An unloading chunk saves the block mid-flight and it comes back to land; written off here, the
    // landing would credit a position nothing was taken from.
    @Test
    fun `a flight whose chunk unloads is not an ending`() {
        val (attribution, listener, written, nextTick) = watched()
        val entity = UUID.fromString("00000000-0000-4000-8000-0000000000f2")
        val from = WorldBlock(world, 3, 70, 3)
        attribution.tookOff(entity, Falling(from, SAND, Attributed(bob), sandForm))

        listener.onEntityRemove(EntityRemoveEvent(fallingStub(entity, 3.5, 70.0, 3.5), EntityRemoveEvent.Cause.UNLOAD))
        nextTick.forEach { it() }

        assertEquals(emptyList<List<Transfer>>(), written)
        // The flight is still there for the landing that follows the chunk coming back.
        assertEquals(from, attribution.landed(entity)?.from)
    }

    // Who lit it is seen; where the entity came from and who put the dynamite down are guesses; a creeper's
    // target was only standing there. A lower rung never speaks over a higher one.
    @Test
    fun `an explosion names who lit it, else its summoner, else who placed the dynamite, else the creeper's target`() {
        val (attribution, listener, written) = watched()
        val carol = UUID.randomUUID()
        val dirt = Blocks.DIRT.defaultBlockState().asBlockData()
        val tntAt = WorldBlock(world, 20, 64, 0)
        attribution.placed(tntAt, BlockDestructionListener.TNT, carol)
        val ground = stub(World::class.java, mapOf(
            "getUID" to world,
            "getBlockAt" to { _: Array<out Any?> -> blockStub(world, tntAt.x, tntAt.y, tntAt.z, Blocks.TNT.defaultBlockState().asBlockData()) },
        ))
        fun tnt(source: Player?) = stub(TNTPrimed::class.java, mapOf(
            "getUniqueId" to UUID.randomUUID(),
            "getSource" to source,
            "getType" to EntityType.TNT,
            "getLocation" to Location(ground, 20.5, 64.0, 0.5),
        ), lenient = true)
        fun blowUp(source: Entity, x: Int): BlockRow {
            listener.onEntityExplode(EntityExplodeEvent(source, Location(ground, x + 0.5, 64.0, 0.5), mutableListOf(blockStub(world, x, 64, 0, dirt)), 1f, ExplosionResult.DESTROY))
            log.drain()
            return rowsAt(x, 64, 0).single()
        }
        fun player(id: UUID) = stub(Player::class.java, mapOf("getUniqueId" to id))

        val lit = tnt(player(alice))
        entityOrigins.appeared(lit.uniqueId, bob)
        assertEquals(alice to Confidence.FACT, blowUp(lit, 30).let { it.actor to it.confidence })

        val summoned = tnt(null)
        entityOrigins.appeared(summoned.uniqueId, bob)
        assertEquals(bob to Confidence.INFERRED, blowUp(summoned, 31).let { it.actor to it.confidence })

        assertEquals(carol to Confidence.INFERRED, blowUp(tnt(null), 32).let { it.actor to it.confidence })

        val creeper = stub(Creeper::class.java, mapOf("getType" to EntityType.CREEPER, "getTarget" to player(alice), "getUniqueId" to UUID.randomUUID()), lenient = true)
        val earlier = written.size
        assertEquals(alice to Confidence.NEARBY, blowUp(creeper, 33).let { it.actor to it.confidence })
        // An item row has no confidence to say the player was only near, so it names nobody.
        val off = written.drop(earlier).flatten().single()
        assertEquals(Void, off.to)
        assertNull(off.actor)
    }

    // A cactus whose sand was taken away breaks on its own tick, and the item it drops is the world's
    // doing on whoever took the support.
    @Test
    fun `a block the world destroys expects its drops on the destroy event itself`() {
        val attribution = Attribution(shared.registries, logs)
        attribution.removed(WorldBlock(world, 5, 63, 7), alice)
        val cactus = CraftItemStack.asBukkitMirror(NmsItemStack(Items.CACTUS))
        val data = Blocks.CACTUS.defaultBlockState().asBlockData()
        val block = blockStub(world, 5, 64, 7, data, drops = listOf(cactus))

        listener(attribution).onBlockDestroy(BlockDestroyEvent(block, airData, data, 0, true))

        val entity = UUID.randomUUID()
        assertEquals(1, origins.claim(entity, Spot(world, 5.3, 64.0, 7.6), codec.encodeOrNull(cactus)!!.key, 1))
        val row = spawns.rows().single()
        assertEquals(Void, row.from)
        assertEquals(ItemEntityRef(entity), row.to)
        assertEquals(Cause.BLK_FADE, row.cause)
        assertEquals(alice, row.actor)
    }
}
