package io.pfaumc.pfauprotect.capture.block
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
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.LIGHTNING
import io.pfaumc.pfauprotect.capture.item.NestedItems
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.stub
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
import org.bukkit.entity.LightningStrike
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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

@ExtendWith(Vanilla::class)
class BlockDestructionTest : BothPlanes() {
    override val world: UUID = UUID.fromString("00000000-0000-4000-8000-000000000003")
    // The first varint of a form is the item type, which is what decides whether the form remembered
    // at a position still belongs to the block standing in it.
    private val bedShell = byteArrayOf(7)
    private val namedBed = byteArrayOf(7, 1, 2)
    private val torchForm = byteArrayOf(9)
    private val dirtForm = byteArrayOf(3)
    private val saplingForm = byteArrayOf(11)
    private val logForm = byteArrayOf(12)
    private val grassForm = byteArrayOf(13)

    private val codec by lazy { ItemFormCodec(shared.registries, ServerRegistries.access) }

    private val spawns = Spawns()
    private val origins = spawns.origins
    private val entityOrigins = EntityOrigins()

    private val bedHead = Blocks.BED.white().defaultBlockState()
        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
        .setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
        .asBlockData()

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
        state: org.bukkit.block.BlockState? = null,
    ): Block {
        val stubWorld = worldStub(world)
        return stub(
            Block::class.java,
            mapOf(
                "getWorld" to stubWorld,
                "getLocation" to { _: Array<out Any?> -> Location(stubWorld, x.toDouble(), y.toDouble(), z.toDouble()) },
                "getDrops" to drops,
                "getState" to state,
                "getBlockData" to data,
                "getRelative" to { args: Array<out Any?> -> neighbours[args[0] as BlockFace] },
                "getX" to x,
                "getY" to y,
                "getZ" to z,
                "equals" to { args: Array<out Any?> -> (args[0] as? Block)?.let { it.x == x && it.y == y && it.z == z } == true },
                "hashCode" to (x * 31 + y) * 31 + z,
            ),
        )
    }

    private fun handlers() = BlockDestructionListener::class.java.declaredMethods
        .mapNotNull { method -> method.getAnnotation(EventHandler::class.java)?.let { method to it } }

    private fun listener(
        attribution: Attribution = Attribution(shared.registries, logs),
        sink: (List<Transfer>) -> Unit = {},
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
        later = later,
    )

    // The claim a handler leaves is what the event behind it reads, and it is the only trace either
    // of them leaves for a list of no positions.
    private fun claimsOf(listener: BlockDestructionListener) = BlockDestructionListener::class.java
        .getDeclaredField("growing")
        .apply { isAccessible = true }
        .get(listener) as GrowClaims

    private fun structureGrow(blocks: MutableList<BlockState>, bonemeal: Boolean): StructureGrowEvent {
        val grown = worldStub(world)
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

    private fun stateAfter(row: BlockRow) = shared.registries.keyOf(RegistryNamespace.BLOCK_STATE, row.stateAfter)

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

    // A storm's bolt is nobody's grief, and its rows say whose it is not: the weather's, not a bystander's.
    @Test
    fun `a bolt answers to the trident's thrower, else its summoner, else the weather`() {
        val thrower = stub(Player::class.java, mapOf("getUniqueId" to alice))
        val bolt = { by: Entity? -> stub(LightningStrike::class.java, mapOf("getCausingEntity" to by, "getUniqueId" to UUID(7L, 7L))) }
        val summoned = { id: UUID -> if (id == UUID(7L, 7L)) Attributed(bob) else null }
        assertEquals(Attributed(alice, Confidence.FACT), boltBy(bolt(thrower), summoned))
        assertEquals(Attributed(bob), boltBy(bolt(null), summoned))
        assertEquals(Attributed(LIGHTNING, Confidence.FACT), boltBy(bolt(null)) { null })
    }

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
        assertTrue(emptied(AIR))
        assertArrayEquals(torchForm, heldForm(null, torchForm, twoPositions = false))
        // A waterlogged block leaves its water behind and is gone all the same.
        assertTrue(emptied(SOURCE_WATER))
        // The remembered form outranks the bare shell: a named bed and a plain one answer alike.
        assertArrayEquals(namedBed, heldForm(namedBed, bedShell, twoPositions = false))
        // A crop advancing a stage is still holding what it was given.
        assertFalse(emptied("minecraft:wheat[age=2]"))
        // Grass spreading onto dirt destroys nothing and drops nothing, and booked as a loss it would
        // debit the shell of a block the position was never credited with in the first place.
        assertFalse(emptied(GRASS))
        // A block with no item form of its own writes nothing off.
        assertNull(heldForm(null, null, twoPositions = false))
        // Both halves of a bed lose their block, and the bed was paid for once: only the half that
        // remembers the form gives it back.
        assertArrayEquals(namedBed, heldForm(namedBed, bedShell, twoPositions = true))
        assertNull(heldForm(null, bedShell, twoPositions = true))
    }

    // By the time the read-back runs a placement may have noted a fresh form there; cleared, the named
    // box standing in it now would break into a plain one.
    @Test
    fun `a read-back that finds the position filled again leaves it alone`() {
        assertTrue(emptied(AIR))
        assertTrue(emptied(SOURCE_WATER))
        assertTrue(emptied("minecraft:fire[age=3]"))

        assertFalse(emptied(SHULKER_BOX))
        assertFalse(emptied(GRASS))
    }

    @Test
    fun `a position with a read-back pending refuses a second and takes one again once it has run`() {
        // A clock that stands still, so a claim reads as pending for exactly as long as it is held.
        val readBacks = ReadBacks { now }
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
        assertFalse(readBacks.pending(sapling))
        assertFalse(readBacks.pending(neighbour))
    }

    // A plank burnt into fire that went out on the spot: the fade comes while the burn's read-back is on
    // its way and leaves the filing to it. Once the read-back has run, a fade there is a change of its own.
    @Test
    fun `a read-back on its way is known as pending until it runs`() {
        var clock = now
        val readBacks = ReadBacks { clock }
        val plank = WorldBlock(world, 5, 64, 5)
        assertFalse(readBacks.pending(plank))
        readBacks.claim(plank)
        assertTrue(readBacks.pending(plank))
        clock += READ_BACK_MILLIS + 1
        assertFalse(readBacks.pending(plank))
        readBacks.done(plank)
        assertFalse(readBacks.pending(plank))
    }

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

    // A queued task can still never run (the world unloads, the plugin goes down), and a claim held for
    // ever would refuse every later read-back of that position.
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

    // A state that backs no item must answer with none, or the position is credited with what nobody
    // made; any other block must answer with one, or its credit is stranded where no self-check looks.
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

        assertEquals(Confidence.FACT, left.confidence)
        assertEquals(Confidence.FACT, arrived.confidence)
        assertEquals(alice, arrived.actor)
    }

    // A block that turned into another item-backed block was not destroyed, so a write-off would invent
    // a loss; an exchange on a position left empty would credit it with something nobody made.
    @Test
    fun `a position emptied is written off and one another block took over is exchanged`() {
        val listener = listener()
        val at = WorldBlock(world, 12, 64, 12)
        val torch = Blocks.TORCH.defaultBlockState().asBlockData()
        val dirt = Blocks.DIRT.defaultBlockState().asBlockData()

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
        shared.submit(moved(torch, Void, torchForm, Cause.BLK_LIQUID_DESTROY, null, longAgo + 1))
        drainBoth()

        val grown = shared.holderEntries(sapling, 0, Long.MAX_VALUE)
        assertEquals(listOf(1, -1, 1), grown.map { it.qty })
        assertEquals(listOf(Kind.TRANSFER, Kind.MUTATE, Kind.MUTATE), grown.map { it.kind })
        // Both halves of the exchange under one transaction, which is what carries a walk across Void.
        assertEquals(1, grown.drop(1).map { it.txId }.distinct().size)
        // What the position holds now is the block standing in it, which is what a break gives back.
        assertEquals(shared.forms.lookup(logForm), grown.last().itemFormId)
        assertEquals(shared.forms.lookup(grassForm), shared.holderEntries(dirt, 0, Long.MAX_VALUE).last().itemFormId)

        assertEquals(1, grown.sumOf { it.qty })
        assertEquals(1, shared.holderEntries(dirt, 0, Long.MAX_VALUE).sumOf { it.qty })
        assertEquals(0, shared.holderEntries(torch, 0, Long.MAX_VALUE).sumOf { it.qty })

        val report = assertPlanesAgree(now)
        assertEquals(3, report.checked)
        assertEquals(0, report.overdrawn)
        assertEquals(emptyList<String>(), shared.sweep(100).gaps)
    }

    // The guess is about who and not whether: the block did go, and the cross-check counts inferred
    // postings apart, so the item row stays a fact whatever the ladder guessed.
    @Test
    fun `the item side is a fact whatever the ladder had to guess about the name on it`() {
        val at = WorldBlock(world, 3, 64, 3)
        val guessed = moved(at, Void, torchForm, Cause.BLK_FADE, Attributed(alice, Confidence.INFERRED), now)
        val witnessed = moved(at, Void, torchForm, Cause.BLK_TNT, Attributed(bob, Confidence.FACT), now)
        val nobody = moved(at, Void, torchForm, Cause.BLK_LIQUID_DESTROY, null, now)

        for (posting in listOf(guessed, witnessed, nobody)) {
            assertEquals(Confidence.FACT, posting.confidence)
            assertEquals(at, posting.from)
            assertEquals(Void, posting.to)
        }
        assertEquals(alice, guessed.actor)
        assertNull(nobody.actor)
    }

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
        shared.submit(moved(at, Void, torchForm, Cause.BLK_FADE, Attributed(bob, Confidence.INFERRED), longAgo + 1))
        drainBoth()

        assertPlanesAgree(now)
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

    // A dispenser puts the pumpkin down and the pattern takes it in the same call, so nobody placed a
    // block of it; the note the dispenser left on itself names who set it going.
    @Test
    fun `a pattern nobody placed is built by whoever set the dispenser beside it going`() {
        val energy = io.pfaumc.pfauprotect.attribution.Energy()
        val head = WorldBlock(world, 0, 64, 0)
        energy.note(WorldBlock(world, 0, 64, 1), Attributed(alice, Confidence.FACT))
        assertEquals(Attributed(alice, Confidence.INFERRED), builderOf(listOf(null, null), listOf(head), energy))

        val placer = Attributed(UUID.randomUUID())
        assertEquals(placer, builderOf(listOf(null, placer), listOf(head), energy))
        assertNull(builderOf(listOf(null), listOf(WorldBlock(world, 0, 64, 10)), energy))
    }

    @Test
    fun `a read-back that finds the same block finds nothing to write`() {
        assertTrue(wentAway(TORCH, AIR))
        assertTrue(wentAway(TORCH, "minecraft:water[level=0]"))
        assertFalse(wentAway("minecraft:fire[age=0]", "minecraft:fire[age=7]"))
        assertFalse(wentAway(TORCH, TORCH))
    }

    // Growing a tree with bone meal raises both the structure event and the fertilize event, over the same list.
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
        assertFalse(claims.claims(natural))

        claims.claim(spread) { filed += "spread" }
        assertTrue(claims.claims(spread))
        claims.settle()
        claims.settle()

        assertEquals(listOf("natural"), filed)
        assertFalse(claims.claims(natural))
        assertFalse(claims.claims(spread))
    }

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

        val claims = claimsOf(listener)
        assertFalse(claims.claims(spread))
        assertFalse(claims.claims(dispensed))
        assertTrue(claims.claims(natural))
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

        val foot = log.at(4, 64, 4, 0, Long.MAX_VALUE, limit = 100, reverse = false)
        val head = log.at(5, 64, 4, 0, Long.MAX_VALUE, limit = 100, reverse = false)
        assertEquals(listOf(Cause.BLK_PLAYER_PLACE, Cause.BLK_BED_EXPLOSION), foot.map { it.cause })
        assertEquals(listOf(Cause.BLK_PLAYER_PLACE, Cause.BLK_BED_EXPLOSION), head.map { it.cause })
        assertEquals(Confidence.INFERRED, foot[1].confidence)
        assertEquals(alice, foot[1].actor)
        assertEquals(foot[1].eventId, head[1].eventId)
        assertEquals(
            shared.registries.lookupKey(RegistryNamespace.BLOCK_STATE, AIR),
            foot[1].stateAfter,
        )

        val report = assertPlanesAgree(now)
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

        val report = assertPlanesAgree(now)
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

    @Test
    fun `a block that moved is one transfer naming both ends`() {
        val from = WorldBlock(world, 1, 64, 0)
        val to = WorldBlock(world, 2, 64, 0)
        val transfer = moved(from, to, dirtForm, Cause.BLK_PISTON_EXTEND, null, now)

        assertEquals(from, transfer.from)
        assertEquals(to, transfer.to)
        assertEquals(Kind.TRANSFER, transfer.kind)
        assertEquals(1, transfer.qty)
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

        val left = log.at(1, 64, 0, 0, Long.MAX_VALUE, limit = 100, reverse = false)
        val through = log.at(2, 64, 0, 0, Long.MAX_VALUE, limit = 100, reverse = false)
        assertEquals(1, (left.drop(1) + through.drop(1) + log.at(0, 64, 0, 0, Long.MAX_VALUE, limit = 100, reverse = false)).map { it.eventId }.distinct().size)
        assertEquals(listOf(AIR, PISTON_HEAD), left.drop(1).map { stateAfter(it) })
        assertEquals(listOf(AIR, DIRT), through.drop(1).map { stateAfter(it) })
        assertEquals(PISTON_HEAD, stateAfter(log.standingAt(1, 64, 0).row!!))
        assertEquals(DIRT, stateAfter(log.standingAt(2, 64, 0).row!!))
        assertEquals(GRASS, stateAfter(log.standingAt(3, 64, 0).row!!))
        assertEquals(PISTON_EXTENDED, stateAfter(log.standingAt(0, 64, 0).row!!))

        assertEquals(0, shared.holderEntries(first, 0, Long.MAX_VALUE).sumOf { it.qty })
        val holding = shared.holderEntries(middle, 0, Long.MAX_VALUE)
            .groupingBy { it.itemFormId }
            .fold(0) { held, entry -> held + entry.qty }
        assertEquals(mapOf(shared.forms.lookup(dirtForm) to 1, shared.forms.lookup(grassForm) to 0), holding)
        assertEquals(shared.forms.lookup(grassForm), shared.holderEntries(last, 0, Long.MAX_VALUE).single().itemFormId)

        val report = assertPlanesAgree(now)
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

        val start = log.at(5, 70, 5, 0, Long.MAX_VALUE, limit = 100, reverse = false)[1]
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

        val report = assertPlanesAgree(now)
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

    private fun watched(): Watched {
        val attribution = Attribution(shared.registries, logs)
        val written = ArrayList<List<Transfer>>()
        val nextTick = ArrayList<() -> Unit>()
        return Watched(attribution, listener(attribution, { written += it }) { _, task -> nextTick += task }, written, nextTick)
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
        assertPlanesAgree(now)
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
        val sand = byteArrayOf(5)
        attribution.tookOff(entity, Falling(from, SAND, Attributed(bob), sand))

        listener.onEntityRemove(EntityRemoveEvent(fallingStub(entity, 4.5, 64.0, 4.5), EntityRemoveEvent.Cause.DISCARD))
        val item = UUID.randomUUID()
        assertEquals(1, origins.claim(item, Spot(world, 4.5, 64.0, 4.5), ItemKey(sand, null), 1))
        nextTick.forEach { it() }

        assertEquals(emptyList<List<Transfer>>(), written)
        val row = spawns.rows().single()
        assertEquals(from, row.from)
        assertEquals(ItemEntityRef(item), row.to)
        assertEquals(Cause.BLK_FALL_START, row.cause)
        assertEquals(bob, row.actor)
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

    // A read-back files its row a tick after the change, and the row carries the time of the event.
    // Filed under the time of the read it would sort after changes that really came later.
    @Test
    fun `a row filed late keeps the time of the event that raised it`() {
        log.submit(listOf(BlockChange(7, 64, 7, AIR, TORCH, Cause.BLK_PLAYER_PLACE, longAgo + 5_000)))
        log.submit(listOf(BlockChange(8, 64, 8, TORCH, AIR, Cause.BLK_FADE, longAgo)))
        drainBoth()

        assertEquals(longAgo, log.standingAt(8, 64, 8).row!!.timestamp)
    }

    // An unloading chunk saves the block mid-flight and it comes back to land; written off here, the
    // landing would credit a position nothing was taken from.
    @Test
    fun `a flight whose chunk unloads is not an ending`() {
        val (attribution, listener, written, nextTick) = watched()
        val entity = UUID.fromString("00000000-0000-4000-8000-0000000000f2")
        val from = WorldBlock(world, 3, 70, 3)
        attribution.tookOff(entity, Falling(from, SAND, Attributed(bob), byteArrayOf(5)))

        listener.onEntityRemove(EntityRemoveEvent(fallingStub(entity, 3.5, 70.0, 3.5), EntityRemoveEvent.Cause.UNLOAD))
        nextTick.forEach { it() }

        assertEquals(emptyList<List<Transfer>>(), written)
        // The flight is still there for the landing that follows the chunk coming back.
        assertEquals(from, attribution.landed(entity)?.from)
    }

    // A piston, like a blast, reaches one half of a bed at a time.
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

        assertEquals(listOf(WorldBlock(world, 2, 64, 0), WorldBlock(world, 1, 64, 0)), emptied.take(2).map { it.at })
        assertTrue(emptied.take(2).all { it.after == AIR && it.went == null })
        assertEquals(WorldBlock(world, 4, 64, 0), emptied.last().went)
        assertEquals(listOf(WorldBlock(world, 4, 64, 0)), filled.map { it.at })
    }

    @Test
    fun `a block the world takes away explains the item it drops`() {
        val cactus = CraftItemStack.asBukkitMirror(NmsItemStack(Items.CACTUS))
        val block = blockStub(5, 64, 7, Blocks.CACTUS.defaultBlockState().asBlockData(), drops = listOf(cactus))
        val breaker = UUID.randomUUID()

        expectDrops(origins, codec, block, Cause.BLK_FADE, breaker)

        val entity = UUID.randomUUID()
        val key = codec.encodeOrNull(cactus)!!.key
        assertEquals(1, origins.claim(entity, Spot(world, 5.2, 64.0, 7.4), key, 1))

        val row = spawns.rows().single()
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
        assertNull(origins.ownerFor(diamonds.copy(), spot))

        assertEquals(1, origins.claim(UUID.randomUUID(), spot, codec.encode(stoneDrop).key, 1))
        assertEquals(1, origins.claim(UUID.randomUUID(), spot, codec.encode(diamondDrop).key, 1))
    }

    // A box broken by hand before carries the name it was given then, and the loot of its next break
    // may or may not copy it onto the drop. Either way the drop is expected, and named, under the name
    // its contents were just packed under.
    @Test
    fun `a box used before is given the name its contents were packed under`() {
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

    @Test
    fun `a block the world destroys expects its drops on the destroy event itself`() {
        val cactus = CraftItemStack.asBukkitMirror(NmsItemStack(Items.CACTUS))
        val data = Blocks.CACTUS.defaultBlockState().asBlockData()
        val block = blockStub(5, 64, 7, data, drops = listOf(cactus))

        listener().onBlockDestroy(BlockDestroyEvent(block, Blocks.AIR.defaultBlockState().asBlockData(), data, 0, true))

        val key = codec.encodeOrNull(cactus)!!.key
        assertEquals(1, origins.claim(UUID.randomUUID(), Spot(world, 5.3, 64.0, 7.6), key, 1))
    }

    // A barrel a creeper takes away spills what it held: the diamonds come out of the slot they were
    // booked to, not out of nowhere.
    @Test
    fun `a container the world destroys expects its contents out of their slots`() {
        val diamonds = CraftItemStack.asBukkitCopy(NmsItemStack(Items.DIAMOND, 3))
        val inventory = stub(org.bukkit.inventory.Inventory::class.java, mapOf("getContents" to arrayOf(null, diamonds)))
        val barrel = stub(org.bukkit.block.Barrel::class.java, mapOf("getSnapshotInventory" to inventory))
        val block = blockStub(5, 64, 7, Blocks.BARREL.defaultBlockState().asBlockData(), state = barrel)

        expectDrops(origins, codec, block, Cause.BLK_CREEPER, alice)

        assertEquals(3, origins.claim(UUID.randomUUID(), Spot(world, 5.5, 64.0, 7.5), codec.encodeOrNull(diamonds)!!.key, 3))
        val row = spawns.rows().single()
        assertEquals(io.pfaumc.pfauprotect.model.Container(world, 5, 64, 7, 1), row.from)
        assertEquals(Cause.CONTAINER_BREAK_DROP, row.cause)
    }

    // Glass the world breaks drops nothing, and one roll cannot tell it from a vine that drops one time
    // in three, so it too leaves the note for any form. That note is the tick's and no longer: it is
    // gone by the second sweep, like every note nothing claimed.
    @Test
    fun `a block that drops nothing leaves nothing past the tick`() {
        val block = blockStub(5, 64, 7, Blocks.GLASS.defaultBlockState().asBlockData())

        expectDrops(origins, codec, block, Cause.BLK_FADE, null)
        origins.sweep()
        spawns.clock += 1_000
        origins.sweep()

        assertTrue(origins.isEmpty)
        assertTrue(spawns.rows().isEmpty())
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

    // Bars beside a plank that burnt let go of it with no event, and were put back still facing air:
    // what the neighbours decide gets a row of its own, and only that does.
    @Test
    fun `a shape is told apart from every other change of a block`() {
        assertTrue(reshaped("minecraft:iron_bars[east=false,north=true,south=true,waterlogged=false,west=false]",
            "minecraft:iron_bars[east=false,north=false,south=true,waterlogged=false,west=false]"))
        assertTrue(reshaped("minecraft:diorite_wall[east=none,north=none,south=low,up=true,waterlogged=false,west=none]",
            "minecraft:diorite_wall[east=none,north=none,south=tall,up=true,waterlogged=false,west=none]"))
        assertTrue(reshaped("minecraft:quartz_stairs[facing=east,half=top,shape=inner_right,waterlogged=false]",
            "minecraft:quartz_stairs[facing=east,half=top,shape=straight,waterlogged=false]"))
        assertTrue(reshaped("minecraft:grass_block[snowy=false]", "minecraft:grass_block[snowy=true]"))

        // Water let in is the bucket's row, a stair turned is the hand's, a block gone is not a shape.
        assertFalse(reshaped("minecraft:iron_bars[east=false,north=true,south=true,waterlogged=false,west=false]",
            "minecraft:iron_bars[east=false,north=true,south=true,waterlogged=true,west=false]"))
        assertFalse(reshaped("minecraft:quartz_stairs[facing=east,half=top,shape=straight,waterlogged=false]",
            "minecraft:quartz_stairs[facing=west,half=top,shape=straight,waterlogged=false]"))
        assertFalse(reshaped("minecraft:iron_bars[east=false,north=true,south=true,waterlogged=false,west=false]", "minecraft:air"))
        assertFalse(reshaped("minecraft:vine[east=false,north=true,south=false,up=false,west=false]",
            "minecraft:vine[east=true,north=true,south=false,up=false,west=false]"))
        assertFalse(reshaped("minecraft:grass_block[snowy=false]", "minecraft:grass_block[snowy=false]"))
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
