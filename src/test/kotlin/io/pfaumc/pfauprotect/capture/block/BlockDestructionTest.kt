package io.pfaumc.pfauprotect.capture.block
import com.destroystokyo.paper.event.block.BlockDestroyEvent
import io.pfaumc.pfauprotect.attribution.Attributed
import io.pfaumc.pfauprotect.attribution.Attribution
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.BlockLog
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.BlockRow
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.attribution.EntityOrigins
import io.pfaumc.pfauprotect.attribution.Falling
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.capture.item.NestedItems
import io.pfaumc.pfauprotect.check.PlaneGap
import io.pfaumc.pfauprotect.check.PlaneSync
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.check.SETTLE_MILLIS
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.attribution.behind
import io.pfaumc.pfauprotect.check.emptied
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack as NmsItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.TreeType
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Bed
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Creeper
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Fireball
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Wither
import org.bukkit.entity.WitherSkull
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.EntityBlockFormEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.UUID

private const val AIR = "minecraft:air"
private const val TORCH = "minecraft:torch"
private const val DIRT = "minecraft:dirt"
private const val SAPLING = "minecraft:oak_sapling[stage=1]"
private const val OAK_LOG = "minecraft:oak_log[axis=y]"
private const val GRASS = "minecraft:grass_block[snowy=false]"
private const val SOURCE_WATER = "minecraft:water[level=0]"
private const val SHULKER_BOX = "minecraft:shulker_box[facing=up]"
private const val SAND = "minecraft:sand"
private const val PISTON = "minecraft:piston[extended=false,facing=east]"
private const val PISTON_EXTENDED = "minecraft:piston[extended=true,facing=east]"
private const val PISTON_HEAD = "minecraft:piston_head[facing=east,short=false,type=normal]"
private const val STICKY_HEAD = "minecraft:piston_head[facing=east,short=false,type=sticky]"
private const val WHITE_BED_HEAD = "minecraft:white_bed[facing=east,occupied=false,part=head]"
private const val WHITE_BED_FOOT = "minecraft:white_bed[facing=east,occupied=false,part=foot]"

class BlockDestructionTest {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000003")
    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val bob = UUID.fromString("00000000-0000-4000-8000-0000000000b0")
    // The first varint of a form is the item type, which is what decides whether the form remembered
    // at a position still belongs to the block standing in it.
    private val bedShell = byteArrayOf(7)
    private val namedBed = byteArrayOf(7, 1, 2)
    private val torchForm = byteArrayOf(9)
    private val dirtForm = byteArrayOf(3)
    private val saplingForm = byteArrayOf(11)
    private val logForm = byteArrayOf(12)
    private val grassForm = byteArrayOf(13)

    private val now = System.currentTimeMillis()
    private val longAgo = now - 10 * SETTLE_MILLIS

    private lateinit var shared: RocksItemLog
    private lateinit var logs: BlockLogs
    private lateinit var log: BlockLog

    private val spawned = ArrayList<Transfer>()
    private val coalescer = TickCoalescer(spawned::add)
    private val origins = SpawnOrigins(coalescer)
    private val entityOrigins = EntityOrigins()

    // A getter and not a field: touching `Blocks` before the bootstrap in `open` throws out of the
    // class initializer, and a field is initialized before it.
    private val bedHead
        get() = Blocks.BED.white().defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
            .setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
            .asBlockData()

    @BeforeEach
    fun open(@TempDir tempDir: Path) {
        ServerRegistries.access
        shared = RocksItemLog(tempDir.resolve("items"))
        logs = BlockLogs(tempDir.resolve("blocks"), shared)
        log = logs.open(world)
    }

    @AfterEach
    fun closeAll() {
        logs.close()
        shared.close()
    }

    private fun doorHalf(half: DoubleBlockHalf) = Blocks.OAK_DOOR.defaultBlockState()
        .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, half)
        .asBlockData()

    // As much of a block as finding the other half takes: where it stands, what stands there, and the
    // neighbour behind each face. Equality is by position, which is `CraftBlock`'s own, so a partner
    // derived from one half and the same position already in the list are one entry and not two.
    private fun blockStub(
        x: Int,
        y: Int,
        z: Int,
        data: BlockData,
        neighbours: Map<BlockFace, Block> = emptyMap(),
        drops: Collection<org.bukkit.inventory.ItemStack> = emptyList(),
    ) =
        Proxy.newProxyInstance(Block::class.java.classLoader, arrayOf(Block::class.java)) { _, method, args ->
            val stubWorld = stub(World::class.java, mapOf("getUID" to world))
            when (method.name) {
                "getWorld" -> stubWorld
                "getLocation" -> Location(stubWorld, x.toDouble(), y.toDouble(), z.toDouble())
                "getDrops" -> drops
                "getBlockData" -> data
                "getRelative" -> neighbours[args[0] as BlockFace]
                "getX" -> x
                "getY" -> y
                "getZ" -> z
                "equals" -> (args[0] as? Block)?.let { it.x == x && it.y == y && it.z == z } == true
                "hashCode" -> (x * 31 + y) * 31 + z
                else -> null
            }
        } as Block

    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>, answers: Map<String, Any?>): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> answers[method.name] } as T

    private fun handlers() = BlockDestructionListener::class.java.declaredMethods
        .mapNotNull { method -> method.getAnnotation(EventHandler::class.java)?.let { method to it } }

    private fun listener(
        attribution: Attribution = Attribution(shared.registries, logs),
        sink: (List<Transfer>) -> Unit = {},
    ) = BlockDestructionListener(
        // Disabled, so a read-back is never queued: there is no region scheduler to queue it on.
        plugin = stub(Plugin::class.java, mapOf("isEnabled" to false)),
        registries = shared.registries,
        logs = logs,
        attribution = attribution,
        codec = ItemFormCodec(shared.registries, ServerRegistries.access),
        origins = origins,
        entities = entityOrigins,
        placed = shared,
        owners = shared,
        sink = sink,
    )

    // The claim a handler leaves is what the event behind it reads, and it is the only trace either
    // of them leaves for a list of no positions.
    private fun claimsOf(listener: BlockDestructionListener) = BlockDestructionListener::class.java
        .getDeclaredField("growing")
        .apply { isAccessible = true }
        .get(listener) as GrowClaims

    private fun structureGrow(blocks: MutableList<BlockState>, bonemeal: Boolean): StructureGrowEvent {
        val grown = stub(World::class.java, mapOf("getUID" to world))
        return StructureGrowEvent(Location(grown, 0.0, 64.0, 0.0), TreeType.TREE, bonemeal, null, blocks)
    }

    private fun fertilize(blocks: MutableList<BlockState>) = BlockFertilizeEvent(
        blockStub(0, 64, 0, Blocks.OAK_SAPLING.defaultBlockState().asBlockData()),
        null,
        blocks,
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
        val site = Site(at, blockStub(at.x, at.y, at.z, before), before, after, payload = null, went = went)
        @Suppress("UNCHECKED_CAST")
        return BlockDestructionListener::class.java
            .getDeclaredMethod(
                "released",
                Site::class.java,
                ByteArray::class.java,
                Cause::class.java,
                Attributed::class.java,
                Long::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }
            .invoke(listener, site, remembered, Cause.BLK_FADE, null, now) as List<Transfer>
    }

    private fun heldAt(x: Int, y: Int, z: Int, form: ByteArray, ts: Long = longAgo) {
        shared.submit(
            Transfer(
                cause = Cause.BLOCK_PLACE,
                from = PlayerInv(alice, 0),
                to = WorldBlock(world, x, y, z),
                form = form,
                damage = null,
                qty = 1,
                timestamp = ts,
            )
        )
    }

    private fun releasedAt(x: Int, y: Int, z: Int, form: ByteArray, cause: Cause, actor: UUID?, ts: Long = longAgo) {
        shared.submit(
            Transfer(
                cause = cause,
                from = WorldBlock(world, x, y, z),
                to = Void,
                form = form,
                damage = null,
                qty = 1,
                timestamp = ts,
                actor = actor,
            )
        )
    }

    private fun stateAfter(row: BlockRow) = shared.registries.keyOf(RegistryNamespace.BLOCK_STATE, row.stateAfter)

    private fun drainBoth() {
        log.drain()
        shared.drain()
    }

    @Test
    fun `an explosion is named by what set it off, and by the block itself where no entity did`() {
        assertEquals(Cause.BLK_TNT, explosionCause(EntityType.TNT))
        assertEquals(Cause.BLK_TNT, explosionCause(EntityType.TNT_MINECART))
        assertEquals(Cause.BLK_CREEPER, explosionCause(EntityType.CREEPER))
        assertEquals(Cause.BLK_END_CRYSTAL, explosionCause(EntityType.END_CRYSTAL))
        // A fireball and whatever a plugin blows up with are an explosion and nothing more precise
        // than that.
        assertEquals(Cause.BLK_EXPLOSION, explosionCause(EntityType.FIREBALL))

        assertEquals(Cause.BLK_BED_EXPLOSION, explosionCause(WHITE_BED_HEAD))
        assertEquals(Cause.BLK_BED_EXPLOSION, explosionCause("minecraft:red_bed[part=foot]"))
        assertEquals(Cause.BLK_RESPAWN_ANCHOR, explosionCause("minecraft:respawn_anchor[charges=4]"))
        assertEquals(Cause.BLK_EXPLOSION, explosionCause(AIR))
    }

    @Test
    fun `an entity changing a block names the mob, and the two that are somebody else's are refused`() {
        assertEquals(Cause.BLK_ENDERMAN, entityBlockCause(EntityType.ENDERMAN))
        assertEquals(Cause.BLK_WITHER, entityBlockCause(EntityType.WITHER))
        assertEquals(Cause.BLK_WITHER, entityBlockCause(EntityType.WITHER_SKULL))
        assertEquals(Cause.BLK_RAVAGER, entityBlockCause(EntityType.RAVAGER))
        assertEquals(Cause.BLK_SILVERFISH, entityBlockCause(EntityType.SILVERFISH))
        assertEquals(Cause.BLK_SNOWMAN, entityBlockCause(EntityType.SNOW_GOLEM))
        assertEquals(Cause.BLK_MOB_GRIEF, entityBlockCause(EntityType.SHEEP))

        // A player stripping a log with an axe raises this same event and is no mob griefing anything;
        // a falling block is a movement with two ends and belongs to the capture that owns both.
        assertNull(entityBlockCause(EntityType.PLAYER))
        assertNull(entityBlockCause(EntityType.FALLING_BLOCK))

        assertEquals(Cause.BLK_SNOWMAN, entityFormCause(EntityType.SNOW_GOLEM))
        assertEquals(Cause.BLK_FROST_WALKER, entityFormCause(EntityType.PLAYER))
    }

    /**
     * A wither breaks blocks with its head and throws a skull that explodes, and the skull is an
     * entity of its own. Two causes for one entity means a lookup filtered by cause finds half of the
     * incident, so the entity answers with the same cause wherever it turns up.
     */
    @Test
    fun `an entity answers with one cause in both the explosion and the change it makes`() {
        assertEquals(Cause.BLK_WITHER, explosionCause(EntityType.WITHER_SKULL))
        assertEquals(Cause.BLK_WITHER, entityBlockCause(EntityType.WITHER_SKULL))
        assertEquals(Cause.BLK_WITHER, entityBlockCause(EntityType.WITHER))
        assertEquals(Cause.BLK_END_CRYSTAL, entityBlockCause(EntityType.END_CRYSTAL))
        assertEquals(Cause.BLK_CREEPER, entityBlockCause(EntityType.CREEPER))
        assertEquals(Cause.BLK_TNT, entityBlockCause(EntityType.TNT))
    }

    @Test
    fun `a block formed where a liquid stood is the liquid's doing and one formed elsewhere is not`() {
        assertEquals(Cause.BLK_LIQUID_FORM, formCause("minecraft:lava[level=0]"))
        assertEquals(Cause.BLK_LIQUID_FORM, formCause("minecraft:water[level=3]"))
        // Ice forming over water forms in the position the water is still in, which is air above it.
        assertEquals(Cause.BLK_FORM, formCause(AIR))
        assertEquals(Cause.BLK_FORM, formCause("minecraft:pointed_dripstone[thickness=tip]"))

        assertEquals(Cause.BLK_FIRE_SPREAD, spreadCause("minecraft:fire[age=3]"))
        assertEquals(Cause.BLK_FIRE_SPREAD, spreadCause("minecraft:soul_fire"))
        assertEquals(Cause.BLK_SCULK, spreadCause("minecraft:sculk"))
        assertEquals(Cause.BLK_SCULK, spreadCause("minecraft:sculk_vein[down=true]"))
        assertEquals(Cause.BLK_GROW, spreadCause("minecraft:grass_block[snowy=false]"))
    }

    // The difference between an honest transaction and a phantom loss booked against every block water
    // ever ran over, so it is the server's own rule that answers rather than the event.
    @Test
    fun `a liquid destroys what stands in its way and runs past everything else`() {
        assertTrue(liquidDestroys(Blocks.TORCH.defaultBlockState()))
        assertTrue(liquidDestroys(Blocks.WHEAT.defaultBlockState()))
        assertTrue(liquidDestroys(Blocks.REDSTONE_WIRE.defaultBlockState()))

        // Nothing was standing there at all.
        assertFalse(liquidDestroys(Blocks.AIR.defaultBlockState()))
        // A position already holding a fluid changes its level and loses nothing.
        assertFalse(liquidDestroys(Blocks.WATER.defaultBlockState()))
        // These take the liquid inside themselves and go on standing.
        assertFalse(liquidDestroys(Blocks.OAK_SLAB.defaultBlockState()))
        assertFalse(liquidDestroys(Blocks.LADDER.defaultBlockState()))
        assertFalse(liquidDestroys(Blocks.OAK_SIGN.defaultBlockState()))
        // The liquid never reaches these to begin with.
        assertFalse(liquidDestroys(Blocks.STONE.defaultBlockState()))
        assertFalse(liquidDestroys(Blocks.OAK_DOOR.defaultBlockState()))
    }

    @Test
    fun `only a position that emptied gives a form back`() {
        assertArrayEquals(torchForm, lostForm(null, torchForm, AIR, twoPositions = false))
        // A waterlogged block leaves its water behind and is gone all the same.
        assertArrayEquals(torchForm, lostForm(null, torchForm, SOURCE_WATER, twoPositions = false))
        // The remembered form outranks the bare shell: a named bed and a plain one answer alike.
        assertArrayEquals(namedBed, lostForm(namedBed, bedShell, AIR, twoPositions = false))
        // A crop advancing a stage is still holding what it was given.
        assertNull(lostForm(null, torchForm, "minecraft:wheat[age=2]", twoPositions = false))
        // Grass spreading onto dirt destroys nothing and drops nothing, and booked as a loss it would
        // debit the shell of a block the position was never credited with in the first place.
        assertNull(lostForm(null, dirtForm, GRASS, twoPositions = false))
        // A block with no item form of its own writes nothing off.
        assertNull(lostForm(null, null, AIR, twoPositions = false))
        // Both halves of a bed lose their block, and the bed was paid for once: only the half that
        // remembers the form gives it back.
        assertArrayEquals(namedBed, lostForm(namedBed, bedShell, AIR, twoPositions = true))
        assertNull(lostForm(null, bedShell, AIR, twoPositions = true))
    }

    /**
     * A torch loses its support and the read-back runs a tick later, by which time another player has
     * put a named shulker box in the position and the placement has written a fresh note there. What
     * stands in the position is no longer what the read watched go, and neither the write-off nor the
     * clearing of the note is the read's to do: cleared, the box would give back a plain one when it
     * is broken in turn.
     */
    @Test
    fun `a read-back that finds the position filled again leaves it alone`() {
        assertTrue(emptied(AIR))
        assertTrue(emptied(SOURCE_WATER))
        assertTrue(emptied("minecraft:fire[age=3]"))

        assertFalse(emptied(SHULKER_BOX))
        assertFalse(emptied(GRASS))
        assertNull(lostForm(namedBed, bedShell, SHULKER_BOX, twoPositions = false))
    }

    /**
     * Two physics events reach one position in one tick: the neighbour updates of `Level.setBlock`
     * fire one, and the neighbour shape update behind them fires a second from the block's own
     * `updateShape` before it returns air. Both would read back air and file it, which is one
     * disappearance booked twice and a position driven below zero on the item plane.
     */
    @Test
    fun `a position with a read-back pending refuses a second and takes one again once it has run`() {
        val readBacks = ReadBacks()
        val sapling = WorldBlock(world, 4, 64, 4)
        val neighbour = WorldBlock(world, 4, 65, 4)

        assertTrue(readBacks.claim(sapling))
        assertFalse(readBacks.claim(sapling))
        // A neighbour is a position of its own, and the claim on this one says nothing about it.
        assertTrue(readBacks.claim(neighbour))

        readBacks.done(sapling)
        assertTrue(readBacks.claim(sapling))

        readBacks.done(sapling)
        readBacks.done(neighbour)
        assertTrue(readBacks.isEmpty)
    }

    /**
     * A ravager and a wither each raise their own event for a block and then destroy it, so one
     * capture files the change and the read-back behind it asks whether it is already there. Asking
     * the journal cannot answer: a submit only queues the row for a writer thread of its own, so the
     * read-back is told no and books the same disappearance a second time.
     */
    @Test
    fun `a change filed this tick is known before the journal has it`() {
        var clock = now
        val readBacks = ReadBacks { clock }
        val at = WorldBlock(world, 6, 64, 6)

        assertFalse(readBacks.wasFiled(at, TORCH, AIR))
        assertFalse(readBacks.settled(at))
        readBacks.filed(at, TORCH, AIR)
        assertTrue(readBacks.wasFiled(at, TORCH, AIR))
        // A different change at the same position is a different change and is nobody else's answer,
        // but the position has still been spoken for this tick, and a read-back queued before that
        // change carries a state that is stale now.
        assertFalse(readBacks.wasFiled(at, DIRT, AIR))
        assertTrue(readBacks.settled(at))

        clock += READ_BACK_MILLIS + 1
        assertFalse(readBacks.wasFiled(at, TORCH, AIR))
        assertFalse(readBacks.settled(at))
        readBacks.sweep()
        assertTrue(readBacks.isEmpty)
    }

    /**
     * The claim is taken before the two steps that can fail to queue the task — the block-entity read
     * and the queueing itself — and a task that was queued can still never run, because the world
     * unloaded or the plugin went down. Held for ever, the claim would refuse every later read-back of
     * that position for as long as the server runs.
     */
    @Test
    fun `a claim nobody released costs a tick and not the session`() {
        var clock = now
        val readBacks = ReadBacks { clock }
        val at = WorldBlock(world, 5, 64, 5)

        assertTrue(readBacks.claim(at))
        assertFalse(readBacks.claim(at))

        // Still the tick it was taken in, where a second read-back really is the same disappearance.
        clock += READ_BACK_MILLIS
        assertFalse(readBacks.claim(at))

        clock += 1
        assertTrue(readBacks.claim(at))
    }

    /**
     * Whether a position gets a write-off or an exchange turns on what is standing in it now, read off
     * the state string the row carries. A state that backs no item has to answer with no item, or the
     * position would be credited with something nobody ever made; a block outside that set has to
     * answer with one, or its credit is stranded where no self-check can find it.
     */
    @Test
    fun `the block behind the state a position ended up in says whether anything is holding it`() {
        for (state in listOf(AIR, SOURCE_WATER, "minecraft:lava[level=0]", "minecraft:fire[age=3]")) {
            assertTrue(emptied(state), state)
            assertTrue(NmsItemStack(blockBehind(state).asItem()).isEmpty, state)
        }
        for (state in listOf(SAPLING, OAK_LOG, DIRT, GRASS, SHULKER_BOX)) {
            assertFalse(emptied(state), state)
            assertFalse(NmsItemStack(blockBehind(state).asItem()).isEmpty, state)
        }
    }

    /**
     * A block that turned into another block was not destroyed and dropped nothing, so a write-off
     * would invent a loss. The ledger's shape for a thing that becomes another thing without leaving
     * is the one an item changed in place takes.
     */
    @Test
    fun `a change in place is the old form leaving and the new form arriving`() {
        val at = WorldBlock(world, 3, 64, 3)
        val (left, arrived) = tookOver(at, dirtForm, grassForm, Cause.BLK_GROW, Attributed(alice, Confidence.INFERRED), now)

        assertEquals(Kind.MUTATE, left.kind)
        assertEquals(Kind.MUTATE, arrived.kind)
        assertEquals(at, left.from)
        assertEquals(Void, left.to)
        assertEquals(Void, arrived.from)
        assertEquals(at, arrived.to)
        assertArrayEquals(dirtForm, left.form)
        assertArrayEquals(grassForm, arrived.form)
        assertEquals(now, arrived.timestamp)

        // Both halves are facts: the exchange was watched, and only the name on it was worked out.
        // Marked as guesses they would sit in the bucket the cross-check never lets cancel the credit,
        // and a position that changed hands correctly would read as a hole for ever.
        assertEquals(Confidence.FACT, left.confidence)
        assertEquals(Confidence.FACT, arrived.confidence)
        assertEquals(alice, arrived.actor)
    }

    /**
     * The two shapes a disappearance can take, chosen where the listener chooses between them. A
     * position whose block turned into another block that backs an item was not destroyed and dropped
     * nothing, so a write-off there would invent a loss; a position left holding a state no item was
     * ever made into really emptied, and an exchange there would credit it with something nobody made.
     */
    @Test
    fun `a position emptied is written off and one another block took over is exchanged`() {
        val listener = listener()
        val at = WorldBlock(world, 12, 64, 12)
        val torch = Blocks.TORCH.defaultBlockState().asBlockData()
        val dirt = Blocks.DIRT.defaultBlockState().asBlockData()

        // A form the position remembers only outranks the bare shell where it is the same item, so the
        // named one is the shell itself with something else written on it.
        val namedTorch = releasedBy(listener, at, torch, AIR).single().form + 1
        for (empty in listOf(AIR, SOURCE_WATER, "minecraft:fire[age=3]", "minecraft:nether_portal[axis=x]")) {
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
        // The old form leaves and the new one arrives, and they are not the same form.
        assertFalse(spread[0].form.contentEquals(spread[1].form))
        val namedDirt = spread[0].form + 1
        assertArrayEquals(namedDirt, releasedBy(listener, at, dirt, GRASS, namedDirt)[0].form)

        // The arriving form is the block standing there now: a different block arrives differently.
        val grown = releasedBy(listener, at, dirt, OAK_LOG)
        assertEquals(2, grown.size)
        assertArrayEquals(spread[0].form, grown[0].form)
        assertFalse(spread[1].form.contentEquals(grown[1].form))
        // Both halves of a bed lose their block and the bed was paid for once: the half that remembers
        // nothing speaks for nothing, whichever shape the change would otherwise take.
        assertEquals(emptyList<Transfer>(), releasedBy(listener, at, bedHead, AIR))
        assertEquals(emptyList<Transfer>(), releasedBy(listener, at, bedHead, SHULKER_BOX))
    }

    /**
     * Three positions the item plane was told about. Two of them went on holding a block an item was
     * made into, and written off they would be a loss nobody suffered; left unsaid they would keep the
     * credit for ever where the cross-check cannot see it, since what stands there is not one of the
     * states that back no item. Only the third really emptied.
     */
    @Test
    fun `a position that took another block on keeps its credit and only an emptied one loses it`() {
        val sapling = WorldBlock(world, 9, 64, 9)
        val dirt = WorldBlock(world, 10, 64, 10)
        val torch = WorldBlock(world, 11, 64, 11)
        heldAt(9, 64, 9, saplingForm)
        heldAt(10, 64, 10, dirtForm)
        heldAt(11, 64, 11, torchForm)
        log.submit(
            listOf(
                BlockChange(9, 64, 9, AIR, SAPLING, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice),
                BlockChange(10, 64, 10, AIR, DIRT, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice),
                BlockChange(11, 64, 11, AIR, TORCH, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice),
            )
        )
        // What the listener submits: the sapling grew into a tree, grass spread onto the dirt, and a
        // liquid took the torch away.
        log.submit(
            listOf(
                BlockChange(9, 64, 9, SAPLING, OAK_LOG, Cause.BLK_GROW, longAgo + 1),
                BlockChange(10, 64, 10, DIRT, GRASS, Cause.BLK_GROW, longAgo + 1),
                BlockChange(11, 64, 11, TORCH, SOURCE_WATER, Cause.BLK_LIQUID_DESTROY, longAgo + 1),
            )
        )
        shared.submit(tookOver(sapling, saplingForm, logForm, Cause.BLK_GROW, null, longAgo + 1))
        shared.submit(tookOver(dirt, dirtForm, grassForm, Cause.BLK_GROW, null, longAgo + 1))
        shared.submit(wroteOff(torch, torchForm, Cause.BLK_LIQUID_DESTROY, null, longAgo + 1))
        drainBoth()

        val grown = shared.holderEntries(sapling, 0, Long.MAX_VALUE)
        assertEquals(listOf(1, -1, 1), grown.map { it.qty })
        assertEquals(listOf(Kind.TRANSFER, Kind.MUTATE, Kind.MUTATE), grown.map { it.kind })
        // Both halves of the exchange under one transaction, which is what carries a walk across Void.
        assertEquals(1, grown.drop(1).map { it.txId }.distinct().size)
        // What the position holds now is the block standing in it, which is what a break gives back.
        assertEquals(shared.formId(logForm), grown.last().itemFormId)
        assertEquals(shared.formId(grassForm), shared.holderEntries(dirt, 0, Long.MAX_VALUE).last().itemFormId)

        assertEquals(1, grown.sumOf { it.qty })
        assertEquals(1, shared.holderEntries(dirt, 0, Long.MAX_VALUE).sumOf { it.qty })
        assertEquals(0, shared.holderEntries(torch, 0, Long.MAX_VALUE).sumOf { it.qty })

        val report = PlaneSync(shared, logs).pass(100, now)

        assertEquals(emptyList<PlaneGap>(), report.gaps)
        assertEquals(3, report.checked)
        assertEquals(0, report.overdrawn)
        assertEquals(emptyList<String>(), shared.sweep(100).gaps)
    }

    /**
     * The support search finds a note in the diagonal ring, which the ladder itself calls a guess, and
     * the block row says so. The cross-check counts confirmed and inferred postings apart precisely so
     * that a guess cannot be read as an observation, and an item row left at the default would say
     * somebody watched the block go.
     */
    @Test
    fun `the item side is a fact whatever the ladder had to guess about the name on it`() {
        val at = WorldBlock(world, 3, 64, 3)
        val guessed = wroteOff(at, torchForm, Cause.BLK_FADE, Attributed(alice, Confidence.INFERRED), now)
        val witnessed = wroteOff(at, torchForm, Cause.BLK_TNT, Attributed(bob, Confidence.FACT), now)
        val nobody = wroteOff(at, torchForm, Cause.BLK_LIQUID_DESTROY, null, now)

        // The disappearance was watched in all three; only the name on it was worked out, and a
        // posting has nowhere to say so. Carrying the doubt across would put a confirmed credit and a
        // guessed write-off in the two buckets the cross-check keeps apart and never lets cancel, so
        // every position that settled correctly would read as a hole.
        for (posting in listOf(guessed, witnessed, nobody)) {
            assertEquals(Confidence.FACT, posting.confidence)
            assertEquals(at, posting.from)
            assertEquals(Void, posting.to)
        }
        assertEquals(alice, guessed.actor)
        assertNull(nobody.actor)
    }

    // A placement credits the position as a fact, and the cross-check never lets the two buckets
    // cancel, so a write-off reporting itself as a guess would leave every position it settled
    // standing as a hole no matter how correctly it was settled.
    @Test
    fun `a position settled by a guessed culprit is not left standing as a hole`() {
        val at = WorldBlock(world, 7, 64, 7)
        heldAt(7, 64, 7, torchForm)
        log.submit(
            listOf(
                BlockChange(7, 64, 7, AIR, TORCH, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice),
                BlockChange(7, 64, 7, TORCH, AIR, Cause.BLK_FADE, longAgo + 1, confidence = Confidence.INFERRED),
            )
        )
        shared.submit(wroteOff(at, torchForm, Cause.BLK_FADE, Attributed(bob, Confidence.INFERRED), longAgo + 1))
        drainBoth()

        val report = PlaneSync(shared, logs).pass(100, now)

        assertEquals(emptyList<PlaneGap>(), report.gaps)
        assertEquals(0, shared.holderEntries(at, 0, Long.MAX_VALUE).sumOf { it.qty })
    }

    /**
     * A blast traces its positions one at a time and reaches one half of a door; the other half is
     * cleared by the server under no event of its own, and its credit would stand for ever — the
     * cross-check cannot see it either, since a position it never recorded is not its business.
     */
    @Test
    fun `a blast that takes one half of a two-position block follows the other`() {
        val upstairs = HashMap<BlockFace, Block>()
        val downstairs = HashMap<BlockFace, Block>()
        val lower = blockStub(0, 64, 0, doorHalf(DoubleBlockHalf.LOWER), upstairs)
        val upper = blockStub(0, 65, 0, doorHalf(DoubleBlockHalf.UPPER), downstairs)
        upstairs[BlockFace.UP] = upper
        downstairs[BlockFace.DOWN] = lower

        assertEquals(listOf(upper, lower), withOtherHalves(listOf(upper)).toList())
        // A blast that reached both halves took two positions, not four.
        assertEquals(listOf(upper, lower), withOtherHalves(listOf(upper, lower)).toList())

        // A block standing in one position has no other half to follow, and a half whose neighbour
        // does not name it back is standing next to somebody else's block.
        val stone = blockStub(2, 64, 0, Blocks.STONE.defaultBlockState().asBlockData())
        val orphan = blockStub(4, 64, 0, doorHalf(DoubleBlockHalf.UPPER), mutableMapOf(BlockFace.DOWN to stone))

        assertEquals(listOf(stone), withOtherHalves(listOf(stone)).toList())
        assertEquals(listOf(orphan), withOtherHalves(listOf(orphan)).toList())
    }

    @Test
    fun `the other half of a bed is derived from the half the explosion names`() {
        val head = bedHead
        val foot = otherBedHalf(head) as Bed

        assertEquals(Bed.Part.FOOT, foot.part)
        assertEquals((head as Bed).facing, foot.facing)
        assertEquals(BlockFace.WEST, partnerFace(head))
        assertEquals(Bed.Part.HEAD, (otherBedHalf(foot) as Bed).part)

        assertNull(otherBedHalf(Blocks.STONE.defaultBlockState().asBlockData()))
    }

    @Test
    fun `a read-back that finds the same block finds nothing to write`() {
        assertTrue(wentAway(TORCH, AIR))
        assertTrue(wentAway(TORCH, "minecraft:water[level=0]"))
        assertFalse(wentAway("minecraft:fire[age=0]", "minecraft:fire[age=7]"))
        assertFalse(wentAway(TORCH, TORCH))
    }

    /**
     * Growing a tree with bone meal raises both the structure event and the fertilize event, over the
     * same list. Two rows for one change would make the second declare as old what the first had just
     * made new, and the newest row of the position would stop being what stands there.
     */
    @Test
    fun `the event that files a tree takes the list and the one behind it is refused`() {
        val claims = GrowClaims()
        // Two distinct empty lists, because it is the object the two events share that settles it.
        val grown = ArrayList<BlockState>()
        val other = ArrayList<BlockState>()

        claims.claim(grown) {}

        assertTrue(claims.claims(grown))
        // Taken once: a second fertilize event over the same list is a new change, not this one.
        assertFalse(claims.claims(grown))

        // A tree that grew on its own is claimed and nothing follows it, and the claim it leaves may
        // not silence the next bone meal anywhere else.
        claims.claim(grown) {}
        assertFalse(claims.claims(other))
    }

    /**
     * A claim settled in the tick it was made could be settled between the two events: the sweep runs
     * on the global region while the events run on the region owning the tree, and the fertilize event
     * follows the structure event within one call. A whole tick is what keeps them apart.
     */
    @Test
    fun `a claim nobody took is filed a tick later and one the fertilize event took never is`() {
        val claims = GrowClaims()
        val filed = ArrayList<String>()
        val natural = ArrayList<BlockState>()
        val spread = ArrayList<BlockState>()

        claims.claim(natural) { filed += "natural" }
        claims.settle()
        assertEquals(emptyList<String>(), filed)

        claims.settle()
        assertEquals(listOf("natural"), filed)
        assertTrue(claims.isEmpty)

        claims.claim(spread) { filed += "spread" }
        assertTrue(claims.claims(spread))
        claims.settle()
        claims.settle()

        assertEquals(listOf("natural"), filed)
        assertTrue(claims.isEmpty)
    }

    /**
     * The server places the captured snapshots gated on the fertilize event alone wherever that event
     * fires, and the cancel link from the structure event runs one way, so a tree filed from the
     * structure event would stand in the journal after a plugin refused the fertilize event and left
     * no tree in the world. A dispenser's bone meal raises the structure event declaring itself not to
     * be bone meal and naming no player, so the flag cannot tell it from a tree that grew alone.
     */
    @Test
    fun `a tree is claimed however it grew, and the fertilize event behind it takes the claim`() {
        val listener = listener()
        val spread = ArrayList<BlockState>()
        val dispensed = ArrayList<BlockState>()
        val natural = ArrayList<BlockState>()

        listener.onStructureGrow(structureGrow(spread, bonemeal = true))
        listener.onStructureGrow(structureGrow(dispensed, bonemeal = false))
        listener.onStructureGrow(structureGrow(natural, bonemeal = false))

        listener.onFertilize(fertilize(spread))
        listener.onFertilize(fertilize(dispensed))

        // Only the tree nobody spread anything on is still waiting to be filed.
        assertFalse(claimsOf(listener).isEmpty)
        assertTrue(claimsOf(listener).claims(natural))
        assertTrue(claimsOf(listener).isEmpty)
    }

    /**
     * The server records the igniter on the creeper itself, for anything in its creeper-igniters tag
     * — a fire charge as much as flint and steel — and for a plugin igniting one through the API. A
     * record kept here beside it would answer for the one of those it happened to know about.
     */
    @Test
    fun `an entity that goes off names whoever the server recorded behind it`() {
        val player = stub(Player::class.java, mapOf("getUniqueId" to bob))
        val mob = stub(Entity::class.java, emptyMap())

        assertEquals(bob, litBy(stub(Creeper::class.java, mapOf("getIgniter" to player)))?.uniqueId)
        assertEquals(bob, litBy(stub(TNTPrimed::class.java, mapOf("getSource" to player)))?.uniqueId)

        // A creeper that went off on its own, one a mob lit, and dynamite nobody is left holding.
        assertNull(litBy(stub(Creeper::class.java, emptyMap())))
        assertNull(litBy(stub(Creeper::class.java, mapOf("getIgniter" to mob))))
        assertNull(litBy(stub(TNTPrimed::class.java, emptyMap())))
        // An end crystal remembers nobody at all.
        assertNull(litBy(mob))
        // A fireball a player hit back.
        assertEquals(bob, litBy(stub(Fireball::class.java, mapOf("getShooter" to player)))?.uniqueId)
    }

    // A sticky piston against obsidian takes its head back without an event. The one trace is the
    // head's physics update with the base already turned into the moving block.
    @Test
    fun `a head whose base has turned into the moving block is being taken back`() {
        fun block(state: net.minecraft.world.level.block.state.BlockState, next: Block? = null): Block {
            val data = state.asBlockData()
            return stub(Block::class.java, mapOf("getType" to data.material, "getBlockData" to data, "getRelative" to next))
        }
        val head = (pistonHead(Direction.EAST, sticky = true) as CraftBlockData).state
        val moving = Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST)
        val retracting = block(moving)

        assertSame(retracting, retractingBase(block(head, retracting)))
        // An extended piston behind its head is a piston at rest.
        val resting = block((pistonBase(Direction.EAST, sticky = true, extended = true) as CraftBlockData).state)
        assertNull(retractingBase(block(head, resting)))
        // A moving block facing elsewhere belongs to some other piston.
        val other = block(moving.setValue(BlockStateProperties.FACING, Direction.UP))
        assertNull(retractingBase(block(head, other)))
        // Anything that is not a head is not asked about at all.
        assertNull(retractingBase(block(Blocks.STONE.defaultBlockState(), retracting)))
    }

    @Test
    fun `a skull is asked about as the wither that fired it`() {
        val wither = stub(Wither::class.java, emptyMap())
        val skull = stub(WitherSkull::class.java, mapOf("getShooter" to wither))

        assertSame(wither, firedBy(skull))
        assertSame(wither, firedBy(wither))
        // A skull whose wither is gone has nobody behind it but itself.
        val orphan = stub(WitherSkull::class.java, emptyMap())
        assertSame(orphan, firedBy(orphan))
    }

    /**
     * Emptying a bucket into a cauldron raises the same event as emptying one into the world, with the
     * cauldron's own position as the block that changed. Noted there, the cauldron would answer for
     * water that never arrived, and the note the position already carried would be gone.
     */
    @Test
    fun `a bucket notes the liquid it really put down, and a cauldron takes the note away`() {
        val air = Blocks.AIR.defaultBlockState()

        assertEquals(SOURCE_WATER, bucketPlaced(Material.WATER_BUCKET, air))
        assertEquals("minecraft:lava[level=0]", bucketPlaced(Material.LAVA_BUCKET, air))
        // A bucket of fish empties water into the world and the fish with it.
        assertEquals(SOURCE_WATER, bucketPlaced(Material.SALMON_BUCKET, air))

        assertNull(bucketPlaced(Material.WATER_BUCKET, Blocks.CAULDRON.defaultBlockState()))
        assertNull(bucketPlaced(Material.LAVA_BUCKET, Blocks.WATER_CAULDRON.defaultBlockState()))
        assertNull(bucketPlaced(Material.POWDER_SNOW_BUCKET, Blocks.CAULDRON.defaultBlockState()))
        // Powder snow is a block placed like any other and milk is drunk: neither is a liquid the
        // world ever holds.
        assertNull(bucketPlaced(Material.POWDER_SNOW_BUCKET, air))
        assertNull(bucketPlaced(Material.MILK_BUCKET, air))
    }

    /**
     * A protection plugin cancels at `NORMAL` or `HIGH`, and every handler here sits behind that:
     * a refused explosion takes nothing away, a refused ignition lights no fire and a refused bucket
     * empties nothing, so there is neither a row to write nor a position to attribute.
     */
    @Test
    fun `every handler runs behind the cancellation`() {
        val found = handlers()

        assertTrue(found.isNotEmpty())
        for ((method, handler) in found) {
            assertEquals(EventPriority.MONITOR, handler.priority, method.name)
            // An entity being removed is announced after the fact and cannot be refused, so there is
            // nothing for it to sit behind. A touch is only read back: its cancelled flag speaks for
            // the block's half of the click and not the item's, and a refused touch reads as nothing.
            if (method.name == "onTouch") continue
            if (Cancellable::class.java.isAssignableFrom(method.parameterTypes.single())) {
                assertTrue(handler.ignoreCancelled, method.name)
            }
        }
    }

    /**
     * `EntityBlockFormEvent` declares no handler list of its own, so the server delivers it to the
     * `BlockFormEvent` handlers. A second method taking it would be registered against that same list
     * and every snow golem trail and every frost walker step would be journalled twice.
     */
    @Test
    fun `an entity forming a block is taken by the one handler that already receives it`() {
        assertThrows(NoSuchMethodException::class.java) {
            EntityBlockFormEvent::class.java.getDeclaredMethod("getHandlerList")
        }
        BlockFormEvent::class.java.getDeclaredMethod("getHandlerList")

        val taken = handlers().map { (method, _) -> method.parameterTypes.single() }

        assertTrue(taken.contains(BlockFormEvent::class.java))
        assertFalse(taken.contains(EntityBlockFormEvent::class.java))
    }

    /**
     * A cactus and a sugar cane whose support goes do not take themselves away from the shape update:
     * they schedule a block tick and remove themselves from it, under no event any of the handlers
     * here receives. Block ticks run after the read-back of the tick that took the support, which
     * finds them standing, so without this handler the removal is never seen at all.
     */
    @Test
    fun `the removal a block does to itself from its own tick is taken`() {
        val taken = handlers().map { (method, _) -> method.parameterTypes.single() }

        assertTrue(taken.contains(BlockDestroyEvent::class.java))
    }

    @Test
    fun `a bed blown up is one event over both its positions and leaves the two planes agreeing`() {
        heldAt(4, 64, 4, namedBed)
        log.submit(
            listOf(
                BlockChange(4, 64, 4, AIR, WHITE_BED_FOOT, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice),
                BlockChange(5, 64, 4, AIR, WHITE_BED_HEAD, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice),
            )
        )
        // What the listener submits for the explosion: both halves under one event, and the form the
        // position took over given back on the half that remembered it.
        log.submit(
            listOf(
                BlockChange(
                    5, 64, 4, WHITE_BED_HEAD, AIR, Cause.BLK_BED_EXPLOSION, longAgo + 1,
                    confidence = Confidence.INFERRED, actor = alice,
                ),
                BlockChange(
                    4, 64, 4, WHITE_BED_FOOT, AIR, Cause.BLK_BED_EXPLOSION, longAgo + 1,
                    confidence = Confidence.INFERRED, actor = alice,
                ),
            )
        )
        releasedAt(4, 64, 4, namedBed, Cause.BLK_BED_EXPLOSION, alice, longAgo + 1)
        drainBoth()

        val foot = log.at(4, 64, 4)
        val head = log.at(5, 64, 4)
        assertEquals(listOf(Cause.BLK_PLAYER_PLACE, Cause.BLK_BED_EXPLOSION), foot.map { it.cause })
        assertEquals(listOf(Cause.BLK_PLAYER_PLACE, Cause.BLK_BED_EXPLOSION), head.map { it.cause })
        assertEquals(Confidence.INFERRED, foot[1].confidence)
        assertEquals(alice, foot[1].actor)
        // One explosion is one event over every position it touched.
        assertEquals(foot[1].eventId, head[1].eventId)
        assertEquals(
            shared.registries.lookupKey(RegistryNamespace.BLOCK_STATE, AIR),
            foot[1].stateAfter,
        )

        val report = PlaneSync(shared, logs).pass(100, now)

        assertEquals(emptyList<PlaneGap>(), report.gaps)
        assertEquals(1, report.checked)
        assertEquals(0, report.overdrawn)
    }

    // An unfound culprit is no reason not to record that the block disappeared: that omission is
    // exactly the hole in a position's history this whole plane exists to avoid.
    @Test
    fun `a disappearance nobody can be named for is written all the same`() {
        heldAt(6, 64, 6, torchForm)
        log.submit(listOf(BlockChange(6, 64, 6, AIR, TORCH, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice)))
        log.submit(listOf(BlockChange(6, 64, 6, TORCH, AIR, Cause.BLK_LIQUID_DESTROY, longAgo + 1)))
        releasedAt(6, 64, 6, torchForm, Cause.BLK_LIQUID_DESTROY, actor = null, ts = longAgo + 1)
        drainBoth()

        val row = log.standingAt(6, 64, 6).row!!
        assertEquals(Cause.BLK_LIQUID_DESTROY, row.cause)
        assertNull(row.actor)
        assertEquals(Confidence.FACT, row.confidence)

        val report = PlaneSync(shared, logs).pass(100, now)
        assertEquals(emptyList<PlaneGap>(), report.gaps)
        assertEquals(1, report.checked)
    }

    /**
     * A piston hands over the blocks it moves and the blocks it breaks in one list and never says which
     * of the two a block is; only what stands there says. Filed as moved, a block the piston broke
     * would credit a position it never reached and leave the item plane holding it there for ever.
     */
    @Test
    fun `a piston moves what it can push and breaks what it cannot`() {
        assertTrue(pistonDestroys(Blocks.TORCH.defaultBlockState().asBlockData()))
        assertTrue(pistonDestroys(Blocks.REDSTONE_WIRE.defaultBlockState().asBlockData()))
        assertTrue(pistonDestroys(Blocks.SHORT_GRASS.defaultBlockState().asBlockData()))
        // A liquid in the way is broken as well, and drops nothing to be written off.
        assertTrue(pistonDestroys(Blocks.WATER.defaultBlockState().asBlockData()))

        assertFalse(pistonDestroys(Blocks.STONE.defaultBlockState().asBlockData()))
        assertFalse(pistonDestroys(Blocks.SLIME_BLOCK.defaultBlockState().asBlockData()))
        assertFalse(pistonDestroys(Blocks.OAK_SLAB.defaultBlockState().asBlockData()))
    }

    /**
     * The piston's own two positions, which no piston event mentions. Both are derived rather than
     * read: an extending piston has not put its head down while the event runs, a retracting one that
     * pulls a block has already taken it away, and the base of a retracting sticky piston is the moving
     * block by then.
     */
    @Test
    fun `the head and the base of a piston are states of their own`() {
        assertEquals(PISTON_HEAD, pistonHead(Direction.EAST, sticky = false).asString)
        assertEquals(STICKY_HEAD, pistonHead(Direction.EAST, sticky = true).asString)

        assertEquals(PISTON, pistonBase(Direction.EAST, sticky = false, extended = false).asString)
        assertEquals(PISTON_EXTENDED, pistonBase(Direction.EAST, sticky = false, extended = true).asString)
        assertEquals(
            "minecraft:sticky_piston[extended=true,facing=up]",
            pistonBase(Direction.UP, sticky = true, extended = true).asString,
        )
    }

    // A block breaking loose leaves behind whatever it was standing in; a block landing puts down the
    // very state it has been carrying all the way down.
    @Test
    fun `the two halves of a fall are told apart by what the position becomes`() {
        assertFalse(isLanding(SAND, AIR))
        assertFalse(isLanding(SAND, SOURCE_WATER))
        assertTrue(isLanding(SAND, SAND))
    }

    /**
     * A block that moved is one transfer naming both ends, which is what lets a walk of the graph
     * follow it across the move. A write-off at the one end and a birth at the other would be two
     * blocks where there is one.
     */
    @Test
    fun `a block that moved is one transfer naming both ends`() {
        val from = WorldBlock(world, 1, 64, 0)
        val to = WorldBlock(world, 2, 64, 0)
        val transfer = moved(from, to, dirtForm, Cause.BLK_PISTON_EXTEND, null, now)

        assertEquals(from, transfer.from)
        assertEquals(to, transfer.to)
        assertEquals(Kind.TRANSFER, transfer.kind)
        assertEquals(1, transfer.qty)
        // The movement was watched; only a name on it could ever have been worked out.
        assertEquals(Confidence.FACT, transfer.confidence)
        assertArrayEquals(dirtForm, transfer.form)
        assertNull(transfer.actor)
    }

    /**
     * The choice the listener makes for a position a movement emptied, driven where it makes it. The
     * form goes to the position the block arrived in, and what the position was holding — a named box
     * pushed by a piston — is what arrives there rather than a shell encoded afresh.
     */
    @Test
    fun `a position whose block moved hands its form over instead of writing it off`() {
        val listener = listener()
        val at = WorldBlock(world, 12, 64, 12)
        val to = WorldBlock(world, 13, 64, 12)
        val dirt = Blocks.DIRT.defaultBlockState().asBlockData()
        val named = releasedBy(listener, at, dirt, AIR).single().form + 1

        val handed = releasedBy(listener, at, dirt, AIR, named, went = to).single()

        assertEquals(Kind.TRANSFER, handed.kind)
        assertEquals(at, handed.from)
        assertEquals(to, handed.to)
        assertArrayEquals(named, handed.form)
        // The same position with nowhere to hand it to writes it off, and that is the whole difference.
        assertEquals(Void, releasedBy(listener, at, dirt, AIR, named).single().to)
    }

    /**
     * A piston push in both planes: two block rows under one event, and one transfer between the two
     * positions. Written as a disappearance in the position it left, the block would be duplicated by
     * every backwards reading of the journal and the position it arrived in would hold nothing.
     */
    @Test
    fun `a line a piston pushed leaves every position right and the planes agreeing`() {
        val first = WorldBlock(world, 1, 64, 0)
        val middle = WorldBlock(world, 2, 64, 0)
        val last = WorldBlock(world, 3, 64, 0)
        heldAt(1, 64, 0, dirtForm)
        heldAt(2, 64, 0, grassForm)
        log.submit(
            listOf(
                BlockChange(1, 64, 0, AIR, DIRT, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice),
                BlockChange(2, 64, 0, AIR, GRASS, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice),
            )
        )
        // What the listener submits for one firing of a piston at 0 64 0 facing east: every position
        // the two blocks left, every position they arrived in, and the piston's own base and head,
        // under one event. The middle position is both, and its rows say so in that order.
        log.submit(
            listOf(
                BlockChange(1, 64, 0, DIRT, AIR, Cause.BLK_PISTON_EXTEND, longAgo + 1),
                BlockChange(2, 64, 0, GRASS, AIR, Cause.BLK_PISTON_EXTEND, longAgo + 1),
                BlockChange(2, 64, 0, AIR, DIRT, Cause.BLK_PISTON_EXTEND, longAgo + 1),
                BlockChange(3, 64, 0, AIR, GRASS, Cause.BLK_PISTON_EXTEND, longAgo + 1),
                BlockChange(1, 64, 0, AIR, PISTON_HEAD, Cause.BLK_PISTON_EXTEND, longAgo + 1),
                BlockChange(0, 64, 0, PISTON, PISTON_EXTENDED, Cause.BLK_PISTON_EXTEND, longAgo + 1),
            )
        )
        shared.submit(
            listOf(
                moved(first, middle, dirtForm, Cause.BLK_PISTON_EXTEND, null, longAgo + 1),
                moved(middle, last, grassForm, Cause.BLK_PISTON_EXTEND, null, longAgo + 1),
            )
        )
        drainBoth()

        val left = log.at(1, 64, 0)
        val through = log.at(2, 64, 0)
        // One firing is one event over every position it touched.
        assertEquals(1, (left.drop(1) + through.drop(1) + log.at(0, 64, 0)).map { it.eventId }.distinct().size)
        // The position the first block left holds the head the piston put down, in that order, and the
        // position in the middle gave its block up before the next one arrived in it.
        assertEquals(listOf(AIR, PISTON_HEAD), left.drop(1).map { stateAfter(it) })
        assertEquals(listOf(AIR, DIRT), through.drop(1).map { stateAfter(it) })
        assertEquals(PISTON_HEAD, stateAfter(log.standingAt(1, 64, 0).row!!))
        assertEquals(DIRT, stateAfter(log.standingAt(2, 64, 0).row!!))
        assertEquals(GRASS, stateAfter(log.standingAt(3, 64, 0).row!!))
        assertEquals(PISTON_EXTENDED, stateAfter(log.standingAt(0, 64, 0).row!!))

        // Nothing was lost and nothing was born: the position the line left owes nothing, the one it
        // moved through holds what came into it rather than what left, and the far end holds the rest.
        assertEquals(0, shared.holderEntries(first, 0, Long.MAX_VALUE).sumOf { it.qty })
        val holding = shared.holderEntries(middle, 0, Long.MAX_VALUE)
            .groupingBy { it.itemFormId }
            .fold(0) { held, entry -> held + entry.qty }
        assertEquals(mapOf(shared.formId(dirtForm) to 1, shared.formId(grassForm) to 0), holding)
        assertEquals(shared.formId(grassForm), shared.holderEntries(last, 0, Long.MAX_VALUE).single().itemFormId)

        val report = PlaneSync(shared, logs).pass(100, now)

        assertEquals(emptyList<PlaneGap>(), report.gaps)
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
        val from = WorldBlock(world, 5, 70, 5)
        val to = WorldBlock(world, 5, 64, 5)
        heldAt(5, 70, 5, dirtForm)
        log.submit(listOf(BlockChange(5, 70, 5, AIR, SAND, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice)))
        // Take-off: the culprit is whoever took the support away, and everything the ladder worked out
        // is a guess in the block plane.
        log.submit(
            listOf(
                BlockChange(
                    5, 70, 5, SAND, AIR, Cause.BLK_FALL_START, longAgo + 1,
                    confidence = Confidence.INFERRED, actor = bob,
                )
            )
        )
        // Landing, a flight later: the same name, fetched back from under the entity.
        log.submit(
            listOf(
                BlockChange(
                    5, 64, 5, AIR, SAND, Cause.BLK_FALL_LAND, longAgo + 2,
                    confidence = Confidence.INFERRED, actor = bob,
                )
            )
        )
        shared.submit(listOf(moved(from, to, dirtForm, Cause.BLK_FALL_LAND, Attributed(bob), longAgo + 2)))
        drainBoth()

        val start = log.at(5, 70, 5)[1]
        val land = log.standingAt(5, 64, 5).row!!
        // Two events, not one: a flight has a tick or a minute in it and nothing joins its ends.
        assertNotEquals(start.eventId, land.eventId)
        assertEquals(bob, start.actor)
        assertEquals(bob, land.actor)
        assertEquals(Confidence.INFERRED, land.confidence)
        assertEquals(AIR, stateAfter(start))
        assertEquals(SAND, stateAfter(land))

        assertEquals(0, shared.holderEntries(from, 0, Long.MAX_VALUE).sumOf { it.qty })
        assertEquals(1, shared.holderEntries(to, 0, Long.MAX_VALUE).sumOf { it.qty })

        val report = PlaneSync(shared, logs).pass(100, now)

        assertEquals(emptyList<PlaneGap>(), report.gaps)
        assertEquals(2, report.checked)
        assertEquals(emptyList<String>(), shared.sweep(100).gaps)
    }

    /**
     * A flight that ends in anything but a landing — destroyed in the air, out of the world, turned
     * into an item — is not a movement at all: the position it left really did lose its block, and what
     * it was holding is written off there. The note it was holding goes with it, or whatever is put
     * down there next would be given back as a block somebody else paid for.
     */
    @Test
    fun `a flight that never landed is written off where it started`() {
        val attribution = Attribution(shared.registries, logs)
        val written = ArrayList<List<Transfer>>()
        val listener = listener(attribution) { written += it }
        val entity = UUID.fromString("00000000-0000-4000-8000-0000000000f1")
        val from = WorldBlock(world, 2, 70, 2)
        // A form the position remembers only outranks the bare shell where it is the same item, so the
        // named one is the sand itself with something else written on it.
        val sand = Blocks.SAND.defaultBlockState().asBlockData()
        val named = releasedBy(listener, from, sand, AIR).single().form + 1
        heldAt(2, 70, 2, named)
        log.submit(listOf(BlockChange(2, 70, 2, AIR, SAND, Cause.BLK_PLAYER_PLACE, longAgo, actor = alice)))
        log.submit(listOf(BlockChange(2, 70, 2, SAND, AIR, Cause.BLK_FALL_START, longAgo + 1)))
        // The form left with the block: the position was free the moment it broke loose, and reading
        // the table now would take whatever has moved in behind it instead.
        attribution.tookOff(entity, Falling(from, SAND, Attributed(bob), named))

        listener.onEntityRemove(
            EntityRemoveEvent(
                stub(FallingBlock::class.java, mapOf("getUniqueId" to entity)),
                EntityRemoveEvent.Cause.OUT_OF_WORLD,
            )
        )

        val off = written.single().single()
        assertEquals(from, off.from)
        assertEquals(Void, off.to)
        assertEquals(Cause.BLK_FALL_START, off.cause)
        assertEquals(bob, off.actor)
        // What the position was holding is what it gives back.
        assertArrayEquals(named, off.form)
        // The flight was taken, so a second removal of the same entity has nothing left to write off.
        assertNull(attribution.landed(entity))

        shared.submit(listOf(off))
        drainBoth()
        assertEquals(0, shared.holderEntries(from, 0, Long.MAX_VALUE).sumOf { it.qty })
        val report = PlaneSync(shared, logs).pass(100, now)
        assertEquals(emptyList<PlaneGap>(), report.gaps)
        assertEquals(emptyList<String>(), shared.sweep(100).gaps)
    }

    // A read-back files its row a tick after the change, and the row carries the time of the event.
    // Filed under the time of the read it would sort after changes that really came later.
    @Test
    fun `a row filed late keeps the time of the event that raised it`() {
        log.submit(listOf(BlockChange(7, 64, 7, AIR, TORCH, Cause.BLK_PLAYER_PLACE, longAgo + 5_000)))
        log.submit(listOf(BlockChange(8, 64, 8, TORCH, AIR, Cause.BLK_FADE, longAgo)))
        drainBoth()

        assertEquals(longAgo, log.standingAt(8, 64, 8).row!!.timestamp)
    }

    /**
     * A chunk going out of memory removes a falling block the same way the world eating it does, and
     * the two must not be read alike: the block is written to the region file still on its way down
     * and comes back under the same name to land. Written off here, the loss never happened, and the
     * landing behind it credits a position nothing was ever taken for.
     */
    @Test
    fun `a flight whose chunk unloads is not an ending`() {
        val attribution = Attribution(shared.registries, logs)
        val written = ArrayList<List<Transfer>>()
        val listener = listener(attribution) { written += it }
        val entity = UUID.fromString("00000000-0000-4000-8000-0000000000f2")
        val from = WorldBlock(world, 3, 70, 3)
        attribution.tookOff(entity, Falling(from, SAND, Attributed(bob), byteArrayOf(5)))

        listener.onEntityRemove(
            EntityRemoveEvent(
                stub(FallingBlock::class.java, mapOf("getUniqueId" to entity)),
                EntityRemoveEvent.Cause.UNLOAD,
            )
        )

        assertEquals(emptyList<List<Transfer>>(), written)
        // The flight is still there for the landing that follows the chunk coming back.
        assertEquals(from, attribution.landed(entity)?.from)
    }


    /**
     * A bed and a door are shoved apart one half at a time: the piston reaches one of the two, and the
     * block clears its partner behind it under no event of its own. A half left unfollowed keeps the
     * form the position was credited with and its newest row goes on naming a bed that is not there,
     * which the cross-check cannot see either, since a bed is not one of the states that back no item.
     */
    @Test
    fun `a piston that breaks one half of a bed follows the other`() {
        val head = blockStub(2, 64, 0, bedHead)
        val foot = blockStub(1, 64, 0, Blocks.BED.white().defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
            .setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
            .asBlockData())
        val air = blockStub(4, 64, 0, Blocks.AIR.defaultBlockState().asBlockData())
        val stone = blockStub(
            3, 64, 0,
            Blocks.STONE.defaultBlockState().asBlockData(),
            mapOf(BlockFace.EAST to air),
        )

        val (emptied, filled) = pistonSites(
            listOf(
                blockStub(2, 64, 0, bedHead, mapOf(BlockFace.WEST to foot, BlockFace.EAST to head)),
                stone,
            ),
            BlockFace.EAST,
        ) { null }

        // The head the piston reached and the foot it never touched, both emptied and neither moved.
        assertEquals(listOf(WorldBlock(world, 2, 64, 0), WorldBlock(world, 1, 64, 0)), emptied.take(2).map { it.at })
        assertTrue(emptied.take(2).all { it.after == AIR && it.went == null })
        // The stone is pushed rather than broken, so it names where it went and fills the next position.
        assertEquals(WorldBlock(world, 4, 64, 0), emptied.last().went)
        assertEquals(listOf(WorldBlock(world, 4, 64, 0)), filled.map { it.at })
    }

    @Test
    fun `a block the world takes away explains the item it drops`() {
        val cactus = CraftItemStack.asBukkitMirror(NmsItemStack(Items.CACTUS))
        val block = blockStub(5, 64, 7, Blocks.CACTUS.defaultBlockState().asBlockData(), drops = listOf(cactus))
        val breaker = UUID.randomUUID()

        expectDrops(origins, ItemFormCodec(shared.registries, ServerRegistries.access), block, Cause.BLK_FADE, breaker)

        val entity = UUID.randomUUID()
        val key = ItemFormCodec(shared.registries, ServerRegistries.access).encodeOrNull(cactus)!!.key
        assertEquals(1, origins.claim(entity, Spot(world, 5.2, 64.0, 7.4), key, 1))

        coalescer.flush()
        val row = spawned.single()
        // Born out of the same void a player break's drop is born out of, under the cause that took
        // the block away and naming whoever the ladder held responsible for it.
        assertEquals(Void, row.from)
        assertEquals(ItemEntityRef(entity), row.to)
        assertEquals(Cause.BLK_FADE, row.cause)
        assertEquals(breaker, row.actor)
        assertEquals(1, row.qty)
    }

    // A shulker box a piston broke drops with its contents inside, and those were packed under the
    // name the position kept. The box that appears has to carry that name, and a second box broken
    // beside it must not be handed the first one's.
    @Test
    fun `a box broken by the world is expected under the name its contents were packed under`() {
        fun box(item: net.minecraft.world.item.Item, count: Int) = NmsItemStack(Items.SHULKER_BOX).apply {
            set(DataComponents.CONTAINER, ItemContainerContents.fromItems(listOf(NmsItemStack(item, count))))
        }
        val codec = ItemFormCodec(shared.registries, ServerRegistries.access)
        val diamonds = box(Items.DIAMOND, 5)
        val stone = box(Items.STONE, 64)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val state = Blocks.SHULKER_BOX.defaultBlockState().asBlockData()
        expectDrops(
            origins, codec, blockStub(5, 64, 7, state, drops = listOf(CraftItemStack.asBukkitMirror(diamonds.copy()))),
            Cause.BLK_PISTON_EXTEND, null, first,
        )
        expectDrops(
            origins, codec, blockStub(6, 64, 7, state, drops = listOf(CraftItemStack.asBukkitMirror(stone.copy()))),
            Cause.BLK_PISTON_EXTEND, null, second,
        )
        val spot = Spot(world, 6.2, 64.0, 7.4)

        // What the spawn does with each box before it reads the form, in the order they appear.
        val stoneDrop = stone.copy()
        assertEquals(second, origins.ownerFor(stoneDrop, spot))
        NestedItems.mark(stoneDrop, second)
        val diamondDrop = diamonds.copy()
        assertEquals(first, origins.ownerFor(diamondDrop, spot))
        NestedItems.mark(diamondDrop, first)
        // Each name is handed out once.
        assertNull(origins.ownerFor(diamonds.copy(), spot))

        assertEquals(1, origins.claim(UUID.randomUUID(), spot, codec.encode(stoneDrop).key, 1))
        assertEquals(1, origins.claim(UUID.randomUUID(), spot, codec.encode(diamondDrop).key, 1))
    }

    // A box broken by hand before carries the name it was given then, and the loot of its next break
    // may or may not copy it onto the drop. Either way the drop is expected, and named, under the name
    // its contents were just packed under.
    @Test
    fun `a box used before is given the name its contents were packed under`() {
        val codec = ItemFormCodec(shared.registries, ServerRegistries.access)
        val packed = UUID.randomUUID()
        val box = NmsItemStack(Items.SHULKER_BOX).apply {
            set(DataComponents.CONTAINER, ItemContainerContents.fromItems(listOf(NmsItemStack(Items.DIAMOND, 5))))
            NestedItems.mark(this, UUID.randomUUID())
        }
        val state = Blocks.SHULKER_BOX.defaultBlockState().asBlockData()
        expectDrops(
            origins, codec, blockStub(5, 64, 7, state, drops = listOf(CraftItemStack.asBukkitMirror(box.copy()))),
            Cause.BLK_TNT, null, packed,
        )
        val spot = Spot(world, 5.2, 64.0, 7.4)

        // The drop still carries the old name; the new one wins.
        val spawned = box.copy()
        assertEquals(packed, origins.ownerFor(spawned, spot))
        NestedItems.mark(spawned, packed)
        assertEquals(1, origins.claim(UUID.randomUUID(), spot, codec.encode(spawned).key, 1))
    }

    @Test
    fun `a crater reaches as far as its blocks are apart`() {
        val stone = Blocks.STONE.defaultBlockState().asBlockData()
        assertEquals(SPAWN_REACH, craterReach(emptyList()))
        assertEquals(SPAWN_REACH, craterReach(listOf(blockStub(0, 64, 0, stone))))
        assertEquals(7.0, craterReach(listOf(blockStub(0, 64, 0, stone), blockStub(6, 62, -2, stone))))
    }

    // A cactus breaks a tick after its support, from its own block tick, while the read-back that the
    // support's physics queued may still hold the position. The destroy event is the moment the drops
    // are certain and follow at once, so it expects them whatever the read-back is doing.
    @Test
    fun `a block the world destroys expects its drops on the destroy event itself`() {
        val cactus = CraftItemStack.asBukkitMirror(NmsItemStack(Items.CACTUS))
        val data = Blocks.CACTUS.defaultBlockState().asBlockData()
        val block = blockStub(5, 64, 7, data, drops = listOf(cactus))

        listener().onBlockDestroy(BlockDestroyEvent(block, Blocks.AIR.defaultBlockState().asBlockData(), data, 0, true))

        val key = ItemFormCodec(shared.registries, ServerRegistries.access).encodeOrNull(cactus)!!.key
        assertEquals(1, origins.claim(UUID.randomUUID(), Spot(world, 5.3, 64.0, 7.6), key, 1))
    }

    @Test
    fun `a block that drops nothing leaves no note behind`() {
        val block = blockStub(5, 64, 7, Blocks.GLASS.defaultBlockState().asBlockData())

        expectDrops(origins, ItemFormCodec(shared.registries, ServerRegistries.access), block, Cause.BLK_FADE, null)

        assertTrue(origins.isEmpty)
    }

    // What a hand leaves standing is kept; what the signal carries is the switch rows' business, and
    // what flips back by itself would read as a change nobody made once it has.
    @Test
    fun `a change by hand is told apart from signal and from what flips back by itself`() {
        assertTrue(handMade("minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]",
            "minecraft:oak_door[facing=east,half=lower,hinge=left,open=true,powered=false]"))
        assertTrue(handMade("minecraft:oak_log[axis=y]", "minecraft:stripped_oak_log[axis=y]"))
        assertTrue(handMade("minecraft:repeater[delay=1,facing=north,locked=false,powered=false]",
            "minecraft:repeater[delay=2,facing=north,locked=false,powered=false]"))
        // A mode flipped by hand still counts when the output moves with it in the same read.
        assertTrue(handMade("minecraft:comparator[facing=north,mode=compare,powered=false]",
            "minecraft:comparator[facing=north,mode=subtract,powered=true]"))

        assertFalse(handMade("minecraft:lever[face=floor,facing=east,powered=false]",
            "minecraft:lever[face=floor,facing=east,powered=true]"))
        assertFalse(handMade("minecraft:barrel[facing=up,open=false]", "minecraft:barrel[facing=up,open=true]"))
        assertFalse(handMade("minecraft:red_bed[facing=east,occupied=false,part=head]",
            "minecraft:red_bed[facing=east,occupied=true,part=head]"))
        assertFalse(handMade("minecraft:redstone_ore[lit=false]", "minecraft:redstone_ore[lit=true]"))
        assertFalse(handMade("minecraft:suspicious_sand[dusted=0]", "minecraft:suspicious_sand[dusted=2]"))
        // What the neighbours decide is theirs; what a vine clings to is the vine's.
        assertFalse(handMade("minecraft:grass_block[snowy=false]", "minecraft:grass_block[snowy=true]"))
        assertFalse(handMade("minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]",
            "minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=false]"))
        assertTrue(handMade("minecraft:vine[east=false,north=true,south=false,up=false,west=false]",
            "minecraft:vine[east=true,north=true,south=false,up=false,west=false]"))
        assertTrue(handMade("minecraft:suspicious_sand[dusted=3]", "minecraft:sand"))
        assertFalse(handMade(TORCH, TORCH))
    }

    // A second hand in the same tick must not queue a second read, and a row filed by the capture that
    // made the change is what the read leaves the position to.
    @Test
    fun `a touch is read back once and left to whoever filed it`() {
        var clock = 1_000L
        val touches = HandTouches { clock }
        val at = WorldBlock(world, 1, 64, 1)

        val touchedBy = Attributed(alice, Confidence.FACT)
        assertTrue(touches.touch(at, by = touchedBy))
        assertFalse(touches.touch(at))
        assertEquals(touchedBy, touches.toucher(at))
        assertEquals(Cause.BLK_PLAYER_USE, touches.take(at))

        // The click comes before the bucket it carries, and the bucket's cause is the one kept.
        assertTrue(touches.touch(at))
        assertFalse(touches.touch(at, Cause.BLK_BUCKET))
        assertEquals(Cause.BLK_BUCKET, touches.take(at))

        assertTrue(touches.touch(at))
        touches.filed(world, listOf(BlockChange(1, 64, 1, AIR, TORCH, Cause.BLK_PLAYER_PLACE, clock, actor = alice)))
        assertNull(touches.take(at))

        // A read that never ran does not shut the position for good.
        assertTrue(touches.touch(at))
        clock += 10_000
        assertTrue(touches.touch(at))
    }
}
