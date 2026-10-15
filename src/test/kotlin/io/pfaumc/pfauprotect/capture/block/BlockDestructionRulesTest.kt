package io.pfaumc.pfauprotect.capture.block
import io.pfaumc.pfauprotect.AIR
import io.pfaumc.pfauprotect.DIRT
import io.pfaumc.pfauprotect.GRASS
import io.pfaumc.pfauprotect.SOURCE_WATER
import io.pfaumc.pfauprotect.Spawns
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.attribution.Attributed
import io.pfaumc.pfauprotect.attribution.Energy
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.key
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.capture.item.NestedItems
import io.pfaumc.pfauprotect.check.emptied
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.LIGHTNING
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.stub
import io.pfaumc.pfauprotect.testCodec
import io.pfaumc.pfauprotect.worldStub
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Creeper
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Fireball
import org.bukkit.entity.LightningStrike
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Wither
import org.bukkit.entity.WitherSkull
import org.bukkit.event.EventHandler
import org.bukkit.event.block.EntityBlockFormEvent
import org.bukkit.inventory.Inventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID
import net.minecraft.world.item.ItemStack as NmsItemStack
import org.bukkit.block.BlockState as BukkitBlockState
import org.bukkit.inventory.ItemStack as BukkitItemStack

private const val TORCH = "minecraft:torch"
private const val SAPLING = "minecraft:oak_sapling[stage=1]"
private const val OAK_LOG = "minecraft:oak_log[axis=y]"
private const val SHULKER_BOX = "minecraft:shulker_box[facing=up]"
private const val STICKY_HEAD = "minecraft:piston_head[facing=east,short=false,type=sticky]"

internal fun whiteBed(part: BedPart): BlockState = Blocks.BED.white().defaultBlockState()
    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
    .setValue(BlockStateProperties.BED_PART, part)

internal val whiteBedHead: BlockData
    get() = whiteBed(BedPart.HEAD).asBlockData()

// As much of a block as finding the other half takes: where it stands, what stands there, and the
// neighbour behind each face. Equality is by position, which is `CraftBlock`'s own, so a partner
// derived from one half and the same position already in the list are one entry and not two.
internal fun blockStub(
    world: UUID,
    x: Int,
    y: Int,
    z: Int,
    data: BlockData,
    neighbours: Map<BlockFace, Block> = emptyMap(),
    drops: Collection<BukkitItemStack> = emptyList(),
    state: BukkitBlockState? = null,
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
            "getType" to data.material,
            "getRelative" to { args: Array<out Any?> -> neighbours[args[0] as BlockFace] },
            "getX" to x,
            "getY" to y,
            "getZ" to z,
            "equals" to { args: Array<out Any?> -> (args[0] as? Block)?.let { it.x == x && it.y == y && it.z == z } == true },
            "hashCode" to (x * 31 + y) * 31 + z,
        ),
    )
}

/** What the destruction capture decides without a journal or a ledger to write to. */
@ExtendWith(Vanilla::class)
class BlockDestructionRulesTest {
    private val world: UUID = UUID.fromString("00000000-0000-4000-8000-000000000004")

    private val codec by lazy { testCodec() }
    private val spawns = Spawns()
    private val origins = spawns.origins

    private fun doorHalf(half: DoubleBlockHalf) = Blocks.OAK_DOOR.defaultBlockState()
        .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, half)
        .asBlockData()

    // An entity answers with one cause in the explosion and in the change it makes, or a lookup by cause
    // finds half an incident.
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "explodes, CREEPER, BLK_CREEPER",
        "explodes, WITHER_SKULL, BLK_WITHER",
        "explodes, TNT, BLK_TNT",
        "explodes, TNT_MINECART, BLK_TNT",
        "explodes, END_CRYSTAL, BLK_END_CRYSTAL",
        // A fireball and whatever a plugin blows up with are an explosion and nothing more precise.
        "explodes, FIREBALL, BLK_EXPLOSION",
        "goes off, 'minecraft:white_bed[facing=east,occupied=false,part=head]', BLK_BED_EXPLOSION",
        "goes off, 'minecraft:respawn_anchor[charges=4]', BLK_RESPAWN_ANCHOR",
        "goes off, minecraft:air, BLK_EXPLOSION",
        "changes, CREEPER, BLK_CREEPER",
        "changes, SHEEP, BLK_MOB_GRIEF",
        "changes, ENDERMAN, BLK_ENDERMAN",
        "changes, RAVAGER, BLK_RAVAGER",
        "changes, SILVERFISH, BLK_SILVERFISH",
        "changes, SNOW_GOLEM, BLK_SNOWMAN",
        "changes, PLAYER,",
        "changes, FALLING_BLOCK,",
        "forms, SNOW_GOLEM, BLK_SNOWMAN",
        "forms, PLAYER, BLK_FROST_WALKER",
        "forms over, 'minecraft:lava[level=0]', BLK_LIQUID_FORM",
        "forms over, 'minecraft:water[level=3]', BLK_LIQUID_FORM",
        // Ice forming over water forms in the position the water is still in, which is air above it.
        "forms over, minecraft:air, BLK_FORM",
        "forms over, 'minecraft:pointed_dripstone[thickness=tip]', BLK_FORM",
        "spreads, 'minecraft:fire[age=3]', BLK_FIRE_SPREAD",
        "spreads, minecraft:soul_fire, BLK_FIRE_SPREAD",
        "spreads, minecraft:sculk, BLK_SCULK",
        "spreads, 'minecraft:sculk_vein[down=true]', BLK_SCULK",
        "spreads, 'minecraft:grass_block[snowy=false]', BLK_GROW",
    )
    fun `what set a block change off names its cause`(what: String, by: String, expected: String?) {
        val cause = when (what) {
            "explodes" -> explosionCause(EntityType.valueOf(by))
            "goes off" -> explosionCause(by)
            "changes" -> entityBlockCause(EntityType.valueOf(by))
            "forms" -> entityFormCause(EntityType.valueOf(by))
            "forms over" -> formCause(by)
            else -> spreadCause(by)
        }
        assertEquals(expected?.let(Cause::valueOf), cause)
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

    // A state that backs no item must answer with none, or the position is credited with what nobody
    // made; any other block must answer with one, or its credit is stranded where no self-check looks.
    // A waterlogged block leaves its water behind and is gone all the same; a crop a stage on, or grass
    // spread onto dirt, is still holding what it was given.
    @Test
    fun `the block behind the state a position ended up in says whether anything is holding it`() {
        for (state in listOf(AIR, SOURCE_WATER, "minecraft:lava[level=0]", "minecraft:fire[age=3]")) {
            assertTrue(emptied(state), state)
            assertTrue(NmsItemStack(blockBehind(state).asItem()).isEmpty, state)
        }
        for (state in listOf(SAPLING, OAK_LOG, DIRT, GRASS, SHULKER_BOX, "minecraft:wheat[age=2]")) {
            assertFalse(emptied(state), state)
            assertFalse(NmsItemStack(blockBehind(state).asItem()).isEmpty, state)
        }
    }

    // A plank burnt into fire that went out on the spot: the fade comes while the burn's read-back is on
    // its way and leaves the filing to it. A queued task can still never run (the world unloads, the
    // plugin goes down), and a claim held for ever would refuse every later read-back of that position.
    @Test
    fun `a read-back holds its position until it runs or its tick is over`() {
        var clock = T0
        val readBacks = ReadBacks { clock }
        val at = WorldBlock(world, 5, 64, 5)
        val neighbour = WorldBlock(world, 5, 65, 5)

        assertFalse(readBacks.pending(at))
        assertTrue(readBacks.claim(at))
        assertTrue(readBacks.pending(at))
        assertFalse(readBacks.claim(at))
        assertTrue(readBacks.claim(neighbour))

        readBacks.done(at)
        assertFalse(readBacks.pending(at))
        assertTrue(readBacks.pending(neighbour))
        assertTrue(readBacks.claim(at))

        // Still the tick it was taken in, where a second read-back really is the same disappearance.
        clock += READ_BACK_MILLIS
        assertFalse(readBacks.claim(at))
        assertTrue(readBacks.pending(at))

        clock += 1
        assertFalse(readBacks.pending(at))
        assertTrue(readBacks.claim(at))
    }

    @Test
    fun `a change filed this tick is known before the journal has it`() {
        var clock = T0
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
     * A blast traces its positions one at a time and reaches one half of a door; the other half is
     * cleared by the server under no event of its own, and its credit would stand for ever — the
     * cross-check cannot see it either, since a position it never recorded is not its business.
     */
    @Test
    fun `a blast that takes one half of a two-position block follows the other`() {
        val upstairs = HashMap<BlockFace, Block>()
        val downstairs = HashMap<BlockFace, Block>()
        val lower = blockStub(world, 0, 64, 0, doorHalf(DoubleBlockHalf.LOWER), upstairs)
        val upper = blockStub(world, 0, 65, 0, doorHalf(DoubleBlockHalf.UPPER), downstairs)
        upstairs[BlockFace.UP] = upper
        downstairs[BlockFace.DOWN] = lower

        assertEquals(setOf(upper, lower), withOtherHalves(listOf(upper)))
        assertEquals(setOf(upper, lower), withOtherHalves(listOf(upper, lower)))

        // A block standing in one position has no other half to follow, and a half whose neighbour
        // does not name it back is standing next to somebody else's block.
        val stone = blockStub(world, 2, 64, 0, Blocks.STONE.defaultBlockState().asBlockData())
        val orphan = blockStub(world, 4, 64, 0, doorHalf(DoubleBlockHalf.UPPER), mutableMapOf(BlockFace.DOWN to stone))

        assertEquals(setOf(stone), withOtherHalves(listOf(stone)))
        assertEquals(setOf(orphan), withOtherHalves(listOf(orphan)))
    }

    // A piston, like a blast, reaches one half of a bed at a time.
    @Test
    fun `a piston that breaks one half of a bed follows the other`() {
        val foot = blockStub(world, 1, 64, 0, whiteBed(BedPart.FOOT).asBlockData())
        val air = blockStub(world, 4, 64, 0, Blocks.AIR.defaultBlockState().asBlockData())
        val stone = blockStub(
            world,
            3, 64, 0,
            Blocks.STONE.defaultBlockState().asBlockData(),
            mapOf(BlockFace.EAST to air),
        )

        val (emptied, filled) = pistonSites(
            listOf(
                blockStub(world, 2, 64, 0, whiteBedHead, mapOf(BlockFace.WEST to foot)),
                stone,
            ),
            BlockFace.EAST,
        )

        assertEquals(listOf(WorldBlock(world, 2, 64, 0), WorldBlock(world, 1, 64, 0)), emptied.take(2).map { it.at })
        assertTrue(emptied.take(2).all { it.after == AIR && it.went == null })
        assertEquals(WorldBlock(world, 4, 64, 0), emptied.last().went)
        assertEquals(listOf(WorldBlock(world, 4, 64, 0)), filled.map { it.at })
    }

    // A dispenser puts the pumpkin down and the pattern takes it in the same call, so nobody placed a
    // block of it; the note the dispenser left on itself names who set it going.
    @Test
    fun `a pattern nobody placed is built by whoever set the dispenser beside it going`() {
        val energy = Energy()
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

    // Growing a tree with bone meal raises both the structure event and the fertilize event, over the same
    // list. Two distinct empty lists, because it is the object the two events share that settles it.
    @Test
    fun `a claim nobody took is filed a tick later and one the fertilize event took never is`() {
        val claims = GrowClaims()
        val filed = ArrayList<String>()
        val natural = ArrayList<BukkitBlockState>()
        val spread = ArrayList<BukkitBlockState>()

        claims.claim(natural) { filed += "natural" }
        // The claim a tree that grew on its own leaves may not silence the next bone meal anywhere else.
        assertFalse(claims.claims(ArrayList<BukkitBlockState>()))
        claims.settle()
        assertEquals(emptyList<String>(), filed)

        claims.settle()
        assertEquals(listOf("natural"), filed)
        assertFalse(claims.claims(natural))

        claims.claim(spread) { filed += "spread" }
        assertTrue(claims.claims(spread))
        // Taken once: a second fertilize event over the same list is a new change, not this one.
        assertFalse(claims.claims(spread))
        claims.settle()
        claims.settle()

        assertEquals(listOf("natural"), filed)
        assertFalse(claims.claims(natural))
        assertFalse(claims.claims(spread))
    }

    @Test
    fun `an entity that goes off names whoever the server recorded behind it`() {
        val player = stub(Player::class.java, mapOf("getUniqueId" to bob))
        val mob = stub(Entity::class.java, emptyMap())

        assertEquals(bob, litBy(stub(Creeper::class.java, mapOf("getIgniter" to player)))?.uniqueId)
        assertEquals(bob, litBy(stub(TNTPrimed::class.java, mapOf("getSource" to player)))?.uniqueId)

        assertNull(litBy(stub(Creeper::class.java, emptyMap())))
        assertNull(litBy(stub(Creeper::class.java, mapOf("getIgniter" to mob))))
        assertNull(litBy(stub(TNTPrimed::class.java, emptyMap())))
        // An end crystal remembers nobody at all.
        assertNull(litBy(mob))
        assertEquals(bob, litBy(stub(Fireball::class.java, mapOf("getShooter" to player)))?.uniqueId)
    }

    // A sticky piston against obsidian takes its head back without an event. The one trace is the
    // head's physics update with the base already turned into the moving block.
    @Test
    fun `a head whose base has turned into the moving block is being taken back`() {
        fun block(state: BlockState, next: Block? = null): Block {
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
        assertNull(retractingBase(block(Blocks.STONE.defaultBlockState(), retracting)))
    }

    @Test
    fun `a skull is asked about as the wither that fired it`() {
        val wither = stub(Wither::class.java, emptyMap())
        val skull = stub(WitherSkull::class.java, mapOf("getShooter" to wither))

        assertSame(wither, firedBy(skull))
        assertSame(wither, firedBy(wither))
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

    // The event declares no handler list of its own and is dispatched to the handlers of the block
    // forming one, which already take it: a second handler would file the change twice.
    @Test
    fun `no handler takes an entity forming a block on its own`() {
        val handlers = BlockDestructionListener::class.java.declaredMethods.filter { it.isAnnotationPresent(EventHandler::class.java) }
        assertFalse(handlers.any { it.parameterTypes.single() == EntityBlockFormEvent::class.java })
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
    fun `the head and the base of a sticky piston are states of their own`() {
        assertEquals(STICKY_HEAD, pistonHead(Direction.EAST, sticky = true).asString)
        assertEquals(
            "minecraft:sticky_piston[extended=true,facing=up]",
            pistonBase(Direction.UP, sticky = true, extended = true).asString,
        )
    }

    // A shulker box a piston broke drops with its contents inside, and those were packed under the
    // name the position kept. The box that appears has to carry that name, and a second box broken
    // beside it must not be handed the first one's. A box broken by hand before already carries the
    // name it was given then, which the loot may or may not copy onto the drop; either way the drop is
    // expected, and named, under the name its contents were just packed under.
    @Test
    fun `a box broken by the world is expected under the name its contents were packed under`() {
        fun box(item: net.minecraft.world.item.Item, count: Int) = NmsItemStack(Items.SHULKER_BOX).apply {
            set(DataComponents.CONTAINER, ItemContainerContents.fromItems(listOf(NmsItemStack(item, count))))
        }
        val diamonds = box(Items.DIAMOND, 5).apply { NestedItems.mark(this, UUID.randomUUID()) }
        val stone = box(Items.STONE, 64)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val state = Blocks.SHULKER_BOX.defaultBlockState().asBlockData()
        expectDrops(
            origins, codec, blockStub(world, 5, 64, 7, state, drops = listOf(CraftItemStack.asBukkitMirror(diamonds.copy()))),
            Cause.BLK_PISTON_EXTEND, null, first,
        )
        expectDrops(
            origins, codec, blockStub(world, 6, 64, 7, state, drops = listOf(CraftItemStack.asBukkitMirror(stone.copy()))),
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

    // An explosion drops a pile where the first block of its kind stood, so a diamond from one corner of
    // the crater can come down in the far corner's block.
    @Test
    fun `a crater reaches as far as its blocks are apart`() {
        val stone = Blocks.STONE.defaultBlockState().asBlockData()
        assertEquals(SPAWN_REACH, craterReach(emptyList()))
        assertEquals(SPAWN_REACH, craterReach(listOf(blockStub(world, 0, 64, 0, stone))))

        val diamond = CraftItemStack.asBukkitCopy(NmsItemStack(Items.DIAMOND))
        val crater = listOf(blockStub(world, 0, 64, 0, stone, drops = listOf(diamond)), blockStub(world, 6, 62, -2, stone))
        expectDrops(origins, codec, crater[0], Cause.BLK_TNT, null, reach = craterReach(crater))
        assertEquals(1, origins.claim(UUID.randomUUID(), Spot(world, 6.5, 62.0, -1.5), codec.encodeOrNull(diamond)!!.key, 1))
    }

    // A barrel a creeper takes away spills what it held: the diamonds come out of the slot they were
    // booked to, not out of nowhere.
    @Test
    fun `a container the world destroys expects its contents out of their slots`() {
        val diamonds = CraftItemStack.asBukkitCopy(NmsItemStack(Items.DIAMOND, 3))
        val inventory = stub(Inventory::class.java, mapOf("getContents" to arrayOf(null, diamonds)))
        val barrel = stub(Barrel::class.java, mapOf("getSnapshotInventory" to inventory))
        val block = blockStub(world, 5, 64, 7, Blocks.BARREL.defaultBlockState().asBlockData(), state = barrel)

        expectDrops(origins, codec, block, Cause.BLK_CREEPER, alice)

        assertEquals(3, origins.claim(UUID.randomUUID(), Spot(world, 5.5, 64.0, 7.5), codec.encodeOrNull(diamonds)!!.key, 3))
        val row = spawns.rows().single()
        assertEquals(Container(world, 5, 64, 7, 1), row.from)
        assertEquals(Cause.CONTAINER_BREAK_DROP, row.cause)
    }

    // Glass the world breaks drops nothing, and one roll cannot tell it from a vine that drops one time
    // in three, so it too leaves the note for any form.
    @Test
    fun `a block that drops nothing still expects whatever falls by it`() {
        val block = blockStub(world, 5, 64, 7, Blocks.GLASS.defaultBlockState().asBlockData())

        expectDrops(origins, codec, block, Cause.BLK_FADE, null)

        assertEquals(2, origins.claim(UUID.randomUUID(), Spot(world, 5.5, 64.0, 7.5), key("twisting_vines"), 2))
        val row = spawns.rows().single()
        assertEquals(Void to Cause.BLK_FADE, row.from to row.cause)
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
        // A clock flips these under an inspector's click; a hand lights a candle, never a lamp.
        assertFalse(handMade("minecraft:redstone_lamp[lit=false]", "minecraft:redstone_lamp[lit=true]"))
        assertFalse(handMade("minecraft:dropper[facing=up,triggered=false]", "minecraft:dropper[facing=up,triggered=true]"))
        assertFalse(handMade("minecraft:hopper[enabled=true,facing=down]", "minecraft:hopper[enabled=false,facing=down]"))
        assertTrue(handMade("minecraft:candle[candles=1,lit=false,waterlogged=false]",
            "minecraft:candle[candles=1,lit=true,waterlogged=false]"))
    }

    // A stone in front of a piston, carried from one position to the next: `name` tells two moves that
    // change the same apart, as the player they name does.
    private fun carried(name: String, from: Int, to: Int) = PistonMove(
        name,
        mapOf(
            WorldBlock(world, from, 64, 0) to ("minecraft:stone" to "minecraft:air"),
            WorldBlock(world, to, 64, 0) to ("minecraft:air" to "minecraft:stone"),
        ),
    )

    private class Driven(world: UUID) {
        var now = T0
        val cycles = PistonCycles { now }
        val base = WorldBlock(world, 0, 64, 0)
        val filed = ArrayList<String>()

        fun move(move: PistonMove?, extending: Boolean, idle: Boolean = false, name: String = move!!.signature) {
            now += 150
            cycles.move(base, extending, idle, move) { filed += name }.forEach { it() }
        }

        fun stop() {
            now += PISTON_CYCLE_MILLIS + 1
            for (quiet in cycles.quiet()) cycles.takeQuiet(quiet).forEach { it() }
        }
    }

    // An idle clock moved its piston six times a second, every move a row, all day long.
    @Test
    fun `a piston repeating its round is filed once and ends where it stopped`() {
        val out = carried("out", 1, 2)
        val back = carried("in", 2, 1)
        val piston = Driven(world)

        repeat(5) { piston.move(out, true); piston.move(back, false) }
        piston.move(out, true)
        assertEquals(listOf("out", "in"), piston.filed)
        // Stopped out, while the journal ends in: the last move is filed after all, and only once.
        piston.stop()
        piston.stop()
        assertEquals(listOf("out", "in", "out"), piston.filed)

        piston.filed.clear()
        repeat(3) { piston.move(back, false); piston.move(out, true) }
        // Stopped where the journal already ends.
        piston.stop()
        assertEquals(listOf("in", "out"), piston.filed)

        piston.filed.clear()
        piston.move(back, false); piston.move(out, true); piston.move(back, false)
        // Somebody else drives it now: the held move goes first, then the new one is filed.
        piston.move(carried("out by bob", 1, 2), true)
        assertEquals(listOf("in", "out", "in", "out by bob"), piston.filed)
    }

    // A player breaks the stone where a held move left it: the break's row would sort ahead of the move
    // filed after it, and the journal would end with a stone in an empty place.
    @Test
    fun `a held move is filed ahead of any other row of its positions`() {
        val out = carried("out", 1, 2)
        val back = carried("in", 2, 1)
        val piston = Driven(world)
        piston.move(out, true); piston.move(back, false); piston.move(out, true)
        assertEquals(listOf("out", "in"), piston.filed)

        assertTrue(piston.cycles.heldOver(WorldBlock(world, 5, 64, 0)).isEmpty())
        piston.cycles.heldOver(WorldBlock(world, 2, 64, 0)).forEach { it() }
        assertEquals(listOf("out", "in", "out"), piston.filed)
        // Handed out once: neither the clock stopping nor another row there files it again.
        assertTrue(piston.cycles.heldOver(WorldBlock(world, 2, 64, 0)).isEmpty())
        piston.stop()
        assertEquals(listOf("out", "in", "out"), piston.filed)
    }

    // Two clocks over one stone: the second piston's claim on the position must not hide the first one's,
    // and their moves go in in the order they were made.
    @Test
    fun `moves two pistons hold over one position are all filed, oldest first`() {
        var now = T0
        val cycles = PistonCycles { now }
        val first = WorldBlock(world, 0, 64, 0)
        val second = WorldBlock(world, 0, 64, 5)
        val filed = ArrayList<String>()
        fun move(base: WorldBlock, move: PistonMove, extending: Boolean, name: String) {
            now += 50
            cycles.move(base, extending, false, move) { filed += name }.forEach { it() }
        }
        val out = carried("out", 1, 2)
        val back = carried("in", 2, 1)
        move(first, out, true, "first out"); move(first, back, false, "first in")
        move(second, out, true, "second out"); move(second, back, false, "second in")
        move(first, out, true, "first out again")
        move(second, out, true, "second out again")
        assertEquals(listOf("first out", "first in", "second out", "second in"), filed)

        cycles.heldOver(WorldBlock(world, 2, 64, 0)).forEach { it() }
        assertEquals(listOf("first out again", "second out again"), filed.drop(4))
        assertTrue(cycles.heldOver(WorldBlock(world, 2, 64, 0)).isEmpty())
    }

    // A rollback of a running clock read the journal a move behind the world and took the stone it was
    // to put back for somebody's later change.
    @Test
    fun `a paused clock has its held moves filed and holds nothing more back`() {
        val out = carried("out", 1, 2)
        val back = carried("in", 2, 1)
        val piston = Driven(world)
        piston.move(out, true); piston.move(back, false); piston.move(out, true)

        val holding = piston.cycles.pause()
        assertEquals(listOf(piston.base), holding)
        piston.cycles.takeHeld(piston.base).forEach { it() }
        piston.move(back, false); piston.move(out, true)
        assertEquals(listOf("out", "in", "out", "in", "out"), piston.filed)

        piston.cycles.resume()
        piston.move(back, false); piston.move(out, true)
        assertEquals(listOf("out", "in", "out", "in", "out"), piston.filed)
    }

    // A flying machine stuck at a wall: one piston pushes the blocks on, the other drags them back, so each
    // piston repeats its own move without ever making a round of it. Every move has to be filed.
    @Test
    fun `a piston pushing the same way over and over is filed every time`() {
        val piston = Driven(world)
        repeat(4) { piston.move(carried("push", 1, 2), true) }
        assertEquals(4, piston.filed.size)
    }

    @Test
    fun `a piston pushing nothing is filed neither going out nor coming back`() {
        val piston = Driven(world)
        piston.move(null, true, idle = true, name = "out")
        piston.move(null, false, idle = true, name = "in")
        // A head that went out pushing a block is filed coming back, though it pulls nothing.
        piston.move(carried("push", 1, 2), true)
        piston.move(null, false, idle = true, name = "back")
        assertEquals(listOf("push", "back"), piston.filed)
    }

    // A sticky piston on a short pulse spits its stone and goes back in with no event; the next push finds
    // nothing in front, and the pull after it brings the stone back. Seen from here the round is three
    // moves. The idle push is filed the first time round all the same: the journal still has the piston
    // out from the push before, and a rollback reading it would put the piston back standing out.
    @Test
    fun `a sticky piston spitting its block is one round of three moves`() {
        val head = "minecraft:piston_head[facing=east,short=false,type=sticky]"
        val inside = "minecraft:sticky_piston[extended=false,facing=east]"
        val outside = "minecraft:sticky_piston[extended=true,facing=east]"
        val base = WorldBlock(world, 0, 64, 0)
        val front = WorldBlock(world, 1, 64, 0)
        val beyond = WorldBlock(world, 2, 64, 0)
        val push = PistonMove("push", mapOf(
            front to ("minecraft:stone" to head),
            beyond to ("minecraft:air" to "minecraft:stone"),
            base to (inside to outside),
        ))
        val idle = PistonMove("idle", mapOf(front to ("minecraft:air" to head), base to (inside to outside)))
        val pull = PistonMove("pull", mapOf(
            beyond to ("minecraft:stone" to "minecraft:air"),
            front to ("minecraft:air" to "minecraft:stone"),
            base to (outside to inside),
        ))
        val piston = Driven(world)
        repeat(10) {
            piston.move(push, true)
            piston.move(idle, true, idle = true)
            piston.move(pull, false)
        }
        assertEquals(listOf("push", "idle", "pull"), piston.filed)
        piston.stop()
        assertEquals(listOf("push", "idle", "pull"), piston.filed)
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
