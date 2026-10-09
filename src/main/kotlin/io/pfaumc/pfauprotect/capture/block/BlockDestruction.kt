package io.pfaumc.pfauprotect.capture.block
import io.pfaumc.pfauprotect.capture.item.CommandBirths
import com.destroystokyo.paper.event.block.BlockDestroyEvent
import io.pfaumc.pfauprotect.attribution.Attributed
import io.pfaumc.pfauprotect.attribution.Attribution
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.EntityChange
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.storage.BlockLog
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.attribution.Energy
import io.pfaumc.pfauprotect.attribution.EntityOrigins
import io.pfaumc.pfauprotect.attribution.FIRING_CAUSES
import io.pfaumc.pfauprotect.attribution.Falling
import io.pfaumc.pfauprotect.attribution.POURING_CAUSES
import io.pfaumc.pfauprotect.attribution.FLOWING_CAUSES
import io.pfaumc.pfauprotect.attribution.LIQUIDS
import io.pfaumc.pfauprotect.attribution.blockNameOf
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.storage.itemTypeIdOf
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.capture.item.NestedItems
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.storage.PlacedForms
import io.pfaumc.pfauprotect.storage.Registries
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.capture.item.containerAt
import io.pfaumc.pfauprotect.check.emptied
import io.pfaumc.pfauprotect.attribution.energyAt
import io.pfaumc.pfauprotect.attribution.culprit
import io.pfaumc.pfauprotect.attribution.inferred
import io.pfaumc.pfauprotect.capture.item.packShulker
import io.pfaumc.pfauprotect.capture.item.positionOf
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.GrowingPlantBodyBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.LiquidBlockContainer
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.PistonType
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.PushReaction
import org.bukkit.ExplosionResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.ShulkerBox
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.PistonHead
import org.bukkit.block.data.type.TechnicalPiston
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.CraftBlock
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.craftbukkit.inventory.CraftItemType
import org.bukkit.entity.Creeper
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.Bukkit
import org.bukkit.event.block.TNTPrimeEvent
import org.bukkit.event.block.SpongeAbsorbEvent
import com.destroystokyo.paper.event.block.AnvilDamagedEvent
import io.papermc.paper.event.block.DragonEggFormEvent
import io.papermc.paper.event.entity.EntityConstructEvent
import io.canvasmc.canvas.event.EntityPortalAsyncEvent
import org.bukkit.block.Sign
import org.bukkit.block.CreatureSpawner
import org.bukkit.block.TrialSpawner
import org.bukkit.entity.LightningStrike
import org.bukkit.entity.Vehicle
import net.minecraft.world.level.block.ChestBlock
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.EntityBlockFormEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import net.minecraft.world.item.ItemStack as NmsItemStack
import net.minecraft.world.level.block.Block as NmsBlock
import net.minecraft.world.level.block.state.BlockState as NmsBlockState

// Only these two take blocks away. A wind charge raises the same event with `TRIGGER_BLOCK` and moves
// nothing, and a plugin can turn any explosion into `KEEP`.
private val DESTROYING = setOf(ExplosionResult.DESTROY, ExplosionResult.DESTROY_WITH_DECAY)

private const val AIR = "minecraft:air"

// What a piston leaves in every position it clears and what stands in every position it fills before
// the move: plain air, and never the fluid the block was standing in. Lazy because touching the block
// registry before the server has bootstrapped it throws.
private val AIR_DATA: BlockData by lazy { Blocks.AIR.defaultBlockState().asBlockData() }

// One position of one change: where it is, what stood there, and what stands there now. The block
// entity goes with the block, so it is read as the site is built rather than when the row is: a
// read-back builds its site after the block is already gone and hands in what it took earlier.
//
// `went` is where the block that stood here has gone, for the positions a movement emptied rather
// than a disappearance. It decides nothing about the row and everything about the item side.
internal class Site(
    val at: WorldBlock,
    val block: Block,
    val before: BlockData,
    val after: String,
    val payload: ByteArray? = payloadAt(block),
    val went: WorldBlock? = null,
)

/**
 * A block taken away by anything other than a player break drops its items with no event naming the
 * break behind them: only a player break raises the drop event, so every other way of taking a block
 * away leaves the birth of what it dropped to arrive at the spawn funnel unexplained, with neither
 * the cause nor the culprit of the break on it.
 *
 * The drops are read while the block still stands, because a moment later there is nothing left to
 * ask. Whether the block really goes need not be settled first: a note nobody claims is swept rather
 * than written, so a block that survives after all, or a drop chance that came up empty, costs the
 * note and nothing else.
 */
// How long after a break its drops are named: they spawn in the call that breaks the block, or in the
// tick after it for a read-back.
private const val DROPS_SETTLE_TICKS = 2L

internal fun expectDrops(
    origins: SpawnOrigins,
    codec: ItemFormCodec,
    block: Block,
    cause: Cause,
    actor: UUID?,
    // The name a shulker box's contents were packed under. The box that falls out has to carry it, and
    // the name is part of its form, so the drop is expected under the named form and the entity is
    // named the same way before its spawn reads it.
    boxOwner: UUID? = null,
    reach: Double = SPAWN_REACH,
    // Under which event the block's own drops are remembered. Not what it held: that is already tied to
    // its slots by the movement out of them.
    tag: Any? = null,
) {
    val spot = spotOf(block.location)
    for (drop in block.drops) {
        val stack = CraftItemStack.asNMSCopy(drop)
        if (stack.isEmpty) continue
        if (boxOwner != null && NestedItems.isShulkerBox(stack)) {
            origins.expectBox(stack, boxOwner, spot)
            NestedItems.mark(stack, boxOwner)
        }
        origins.expect(Void, cause, codec.encode(stack).key, spot, drop.amount, actor, reach, rolled = true, tag = tag)
    }
    // The roll above is this capture's own and the drop comes out of the server's, and where chance
    // decides the two can disagree on more than the count.
    origins.expectAny(Void, cause, spot, actor, reach, tag)
    // What it held spills out of the slots it was booked to, as from a hand's break.
    spilled(block.getState(false)).forEachIndexed { slot, item ->
        val encoded = codec.encodeOrNull(item) ?: return@forEachIndexed
        origins.expect(containerAt(block, slot), Cause.CONTAINER_BREAK_DROP, encoded.key, spot, encoded.count, actor, reach)
    }
}

// How far apart two blocks of one crater can stand, along any axis: an explosion drops each pile where
// the first block of its kind stood, which can be anywhere in the crater.
internal fun craterReach(blocks: Collection<Block>): Double {
    if (blocks.isEmpty()) return SPAWN_REACH
    val span = maxOf(
        blocks.maxOf { it.x } - blocks.minOf { it.x },
        blocks.maxOf { it.y } - blocks.minOf { it.y },
        blocks.maxOf { it.z } - blocks.minOf { it.z },
    )
    return maxOf(SPAWN_REACH, span + 1.0)
}

/**
 * The cause an entity answers with wherever it turns up. A wither takes blocks away with its head and
 * with its skull, and the skull is an entity of its own raising an explosion event; filed apart, a
 * lookup by cause finds half of one incident.
 */
private val ENTITY_CAUSES = mapOf(
    EntityType.TNT to Cause.BLK_TNT,
    EntityType.TNT_MINECART to Cause.BLK_TNT,
    EntityType.CREEPER to Cause.BLK_CREEPER,
    EntityType.END_CRYSTAL to Cause.BLK_END_CRYSTAL,
    EntityType.WITHER to Cause.BLK_WITHER,
    EntityType.WITHER_SKULL to Cause.BLK_WITHER,
    EntityType.ENDERMAN to Cause.BLK_ENDERMAN,
    EntityType.RAVAGER to Cause.BLK_RAVAGER,
    EntityType.SILVERFISH to Cause.BLK_SILVERFISH,
    EntityType.SNOW_GOLEM to Cause.BLK_SNOWMAN,
)

/** The explosion source that is an entity: primed dynamite, a creeper, an end crystal. */
internal fun explosionCause(source: EntityType): Cause = ENTITY_CAUSES[source] ?: Cause.BLK_EXPLOSION

/**
 * The explosion source that is a block, named from the state the event carries rather than from the
 * position: both the bed and the respawn anchor take themselves away before the explosion starts, so
 * by the time this is asked the position is already air.
 */
internal fun explosionCause(exploded: String): Cause = when {
    blockNameOf(exploded).endsWith("_bed") -> Cause.BLK_BED_EXPLOSION
    blockNameOf(exploded) == "minecraft:respawn_anchor" -> Cause.BLK_RESPAWN_ANCHOR
    else -> Cause.BLK_EXPLOSION
}

/**
 * The player behind an entity that goes off, read from the platform's own record rather than kept a
 * second time here. Dynamite remembers who primed it, and a creeper remembers whoever lit it: the
 * server writes that down for anything in its creeper-igniters tag — flint and steel and a fire
 * charge alike — and for a plugin igniting one through the API.
 */
internal fun litBy(source: Entity): Player? = when (source) {
    is TNTPrimed -> source.source as? Player
    is Creeper -> source.igniter as? Player
    // A fireball a player hit back is theirs from then on.
    is Projectile -> source.shooter as? Player
    else -> null
}

// A skull a wither fires is a projectile with no origin of its own, so it is the wither behind it that
// the summoner rung has to ask about.
internal fun firedBy(source: Entity): Entity = (source as? Projectile)?.shooter as? Entity ?: source

/**
 * An entity changing a block. Null for the two that belong to somebody else: a player using a shovel,
 * an axe or a honeycomb arrives here as well and is not a mob griefing anything, and a falling block
 * is a movement with two ends rather than a destruction.
 */
internal fun entityBlockCause(entity: EntityType): Cause? = when (entity) {
    EntityType.PLAYER, EntityType.FALLING_BLOCK -> null
    else -> ENTITY_CAUSES[entity] ?: Cause.BLK_MOB_GRIEF
}

// A block formed under an entity: the trail a snow golem leaves, and otherwise the ice an enchantment
// lays down under whoever is wearing it.
internal fun entityFormCause(entity: EntityType): Cause =
    if (entity == EntityType.SNOW_GOLEM) Cause.BLK_SNOWMAN else Cause.BLK_FROST_WALKER

/**
 * A block forming where a liquid stood is the liquid turning into stone, cobblestone, obsidian or
 * basalt; everything else forming — ice over water is not this, since the water is still there under
 * the ice — is the world's own weather and dripstone.
 */
internal fun formCause(before: String): Cause =
    if (blockNameOf(before) in LIQUIDS) Cause.BLK_LIQUID_FORM else Cause.BLK_FORM

// A catalyst blooms within eight blocks of a death, and its sculk spreads a few blocks further on.
private const val SCULK_REACH = 12

private val LIQUID_FACES = POUR_FACES + BlockFace.DOWN

private const val FIRE = "minecraft:fire"

private val FIRES = setOf(FIRE, "minecraft:soul_fire")

private val SCULK = setOf("minecraft:sculk", "minecraft:sculk_vein")

/** Fire, sculk and everything else that reaches a neighbouring position by growing into it. */
internal fun spreadCause(after: String): Cause = when (blockNameOf(after)) {
    in FIRES -> Cause.BLK_FIRE_SPREAD
    in SCULK -> Cause.BLK_SCULK
    else -> Cause.BLK_GROW
}

/**
 * Whether the liquid about to arrive destroys what stands in the position or merely runs past it.
 * This is the server's own rule, and without it every block water ever ran over would be booked as a
 * loss: `FlowingFluid.spreadTo` hands the position to `beforeDestroyingBlock` only where it holds a
 * block that is neither air nor able to take the liquid inside itself, and `canHoldAnyFluid` is the
 * test deciding whether the liquid may enter the position at all.
 *
 * A position already holding a fluid is a level changing rather than anything being destroyed.
 */
internal fun liquidDestroys(state: NmsBlockState): Boolean =
    !state.isAir &&
        state.fluidState.isEmpty &&
        state.block !is LiquidBlockContainer &&
        FlowingFluid.canHoldAnyFluid(state)

/**
 * What a bucket puts into the world at the position the event names, and null where nothing goes into
 * the world at all. Filling a cauldron raises the same event with the cauldron's own position as the
 * block that changed, and what the bucket held goes inside the cauldron rather than into the world;
 * the fluid is the bucket's own, so a bucket of fish puts down water and a bucket of milk puts down
 * nothing.
 */
internal fun bucketPlaced(bucket: Material, target: NmsBlockState): String? {
    if (target.block is AbstractCauldronBlock) return null
    val fluid = (CraftItemType.bukkitToMinecraft(bucket) as? BucketItem)?.content as? FlowingFluid ?: return null
    return fluid.defaultFluidState().createLegacyBlock().asBlockData().asString
}

/**
 * Whether a piston breaks this block where it stands instead of moving it. A piston event hands over
 * the blocks it moves and the blocks it destroys in one list and never says which is which; the push
 * reaction of what stands there is what the server itself decides by, and a block filed as moved when
 * it was broken would credit a position it never reached.
 */
internal fun pistonDestroys(data: BlockData) =
    (data as CraftBlockData).state.pistonPushReaction == PushReaction.POPPED

/**
 * Where each block a piston is about to shift ends up: the positions it leaves, and the positions it
 * arrives in. A block the piston breaks arrives nowhere, and it takes the other half of a bed or a
 * door with it — the piston reaches one of the two, and the block clears its partner behind it under
 * no event of its own, so a half left unfollowed keeps its form and its newest row goes on naming
 * something that is not standing there.
 *
 * A piston writes plain air over every position it empties, whatever the block was standing in, so a
 * waterlogged block takes its water with it rather than leaving a source behind.
 */
internal fun pistonSites(
    moving: List<Block>,
    step: BlockFace,
    payload: (Block) -> ByteArray? = ::payloadAt,
): Pair<List<Site>, List<Site>> {
    val emptied = ArrayList<Site>(moving.size + 1)
    val filled = ArrayList<Site>(moving.size + 2)
    for (block in moving) {
        val was = block.blockData
        if (pistonDestroys(was)) {
            for (half in withOtherHalves(listOf(block))) {
                emptied += Site(positionOf(half), half, half.blockData, AIR, payload(half))
            }
            continue
        }
        val to = block.getRelative(step)
        emptied += Site(positionOf(block), block, was, AIR, payload(block), went = positionOf(to))
        filled += Site(positionOf(to), to, AIR_DATA, was.asString, payload = null)
    }
    return emptied to filled
}

/**
 * The head a piston puts down and takes back. It stands in a position of its own that no event names,
 * and it is derived rather than read: an extending piston has not put it down while the event runs,
 * and a retracting one that pulls a block has already taken it away.
 */
internal fun pistonHead(facing: Direction, sticky: Boolean): BlockData = Blocks.PISTON_HEAD
    .defaultBlockState()
    .setValue(BlockStateProperties.FACING, facing)
    .setValue(BlockStateProperties.PISTON_TYPE, if (sticky) PistonType.STICKY else PistonType.DEFAULT)
    .asBlockData()

/**
 * The base of a piston taking its head back, asked of the head. A sticky piston that cannot pull what
 * stands in front of it raises no retract event at all: the server removes the head quietly, and the
 * block plane went on saying a head stood there. What it does not hide is the physics update the base
 * sends its neighbours once it has turned into the moving block, while the head is still standing to
 * be read. An extending piston never looks like this: its base stays a piston, and the moving block
 * stands where the head is going.
 */
internal fun retractingBase(head: Block): Block? {
    // Asked on every physics update in the world, so the cheap question goes first.
    if (head.type != Material.PISTON_HEAD) return null
    val data = head.blockData as? PistonHead ?: return null
    val base = head.getRelative(data.facing.oppositeFace)
    if (base.type != Material.MOVING_PISTON) return null
    return base.takeIf { (it.blockData as? Directional)?.facing == data.facing }
}

/**
 * The piston itself, which is the same block before and after and differs only in being extended. Both
 * sides are derived, because the base of a retracting sticky piston is already the moving block by the
 * time the event is raised: the row would otherwise start from an animation frame nobody asked about
 * and no longer meet the row the extension left.
 */
internal fun pistonBase(facing: Direction, sticky: Boolean, extended: Boolean): BlockData =
    (if (sticky) Blocks.STICKY_PISTON else Blocks.PISTON)
        .defaultBlockState()
        .setValue(BlockStateProperties.FACING, facing)
        .setValue(BlockStateProperties.EXTENDED, extended)
        .asBlockData()

/** Whether what was read back is a different block from the one the event was about. */
internal fun wentAway(before: String, now: String) = blockNameOf(before) != blockNameOf(now)

// The state a position ends up in travels as its string, and only the block behind the string says
// whether anything an item was ever made into is standing there now.
internal fun blockBehind(state: String): NmsBlock =
    BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockNameOf(state)))

/**
 * The item a block is made of. A stem — twisting and weeping vines, kelp, cave vines — is a block of its
 * own with no item: a tip turns into one the moment something is put or grows on top of it, and what was
 * put down there was the tip. Without this the stem gives back nothing when it breaks, and the position
 * holds the tip's item for ever. The game names each stem after its tip, and gives no public way to ask.
 */
internal fun itemOf(block: NmsBlock): Item {
    val own = block.asItem()
    if (own != Items.AIR || block !is GrowingPlantBodyBlock) return own
    return blockBehind(BuiltInRegistries.BLOCK.getKey(block).toString().removeSuffix("_plant")).asItem()
}

/**
 * What the block was made of, not what breaking it yields: a crop answers with the seed it was planted
 * from, and a block with no item form of its own — fire, a liquid, a portal — answers with nothing and so
 * is never written off.
 */
internal fun ItemFormCodec.shellOf(block: NmsBlock): ByteArray? {
    val stack = NmsItemStack(itemOf(block))
    return if (stack.isEmpty) null else encode(stack).form
}

/**
 * The form the position is holding, which falls back to the block's own shell where nothing was
 * remembered: a position the item plane never credited still answers with something.
 *
 * A block standing in two positions was paid for once and its form was remembered in one of them, so
 * only that position may speak for it: a half with nothing remembered says nothing rather than giving
 * the block up a second time.
 */
internal fun heldForm(remembered: ByteArray?, shell: ByteArray?, twoPositions: Boolean): ByteArray? {
    if (twoPositions && remembered == null) return null
    return gaveBack(remembered, shell)
}

/**
 * The form the position gives back, and null where it gives back nothing. Only a position that emptied
 * gives anything back: a crop advancing a stage and dirt turning to grass are both still holding what
 * they were given, and written off they would be a loss nobody suffered.
 */
internal fun lostForm(
    remembered: ByteArray?,
    shell: ByteArray?,
    after: String,
    twoPositions: Boolean,
): ByteArray? {
    if (!emptied(after)) return null
    return heldForm(remembered, shell, twoPositions)
}

/**
 * The item side of a disappearance. It is a fact whatever the ladder had to guess, and the difference
 * is not a nicety: the two planes ask their confidence field different questions. A block row is
 * always a change somebody watched happen, so the only thing left for it to be unsure of is who is
 * behind it. An item posting is unsure of the movement itself — a loss the pass found no gain for, a
 * birth no mechanism claimed. This disappearance was observed, so the movement is certain; only the
 * name on it may be worked out, and there is nowhere on a posting to say so.
 *
 * Carrying the ladder's doubt across would put a confirmed credit and a guessed write-off in the two
 * buckets the cross-check keeps apart on purpose and never lets cancel, so every position that
 * settled correctly would be reported as a hole for ever.
 */
internal fun wroteOff(at: WorldBlock, form: ByteArray, cause: Cause, by: Attributed?, timestamp: Long) =
    moved(at, Void, form, cause, by, timestamp)

/**
 * The item side of a block that changed position rather than disappearing: one transfer naming both
 * ends, which is what lets a walk of the graph follow the block across the move. Written off at the
 * one end and born again at the other, one block would be two, and the position it left would hand its
 * form back a second time when whatever stands there now is broken.
 */
internal fun moved(
    from: WorldBlock,
    to: Holder,
    form: ByteArray,
    cause: Cause,
    by: Attributed?,
    timestamp: Long,
) = Transfer(
    cause = cause,
    from = from,
    to = to,
    form = form,
    damage = null,
    qty = 1,
    timestamp = timestamp,
    actor = by.culprit(),
)

/**
 * The item side of a position whose block turned into another block that has an item form of its own.
 * Nothing was destroyed and nothing dropped, so a write-off would invent a loss; left alone, the credit
 * the position was given stands for ever where no self-check can reach it, since what is standing there
 * backs an item and reads as a holding. The position holds something still and it is no longer what it
 * held, which is the shape the ledger already gives an item changed in place: the old form leaving and
 * the new form arriving, both facing the void, submitted together so they share a transaction.
 */
internal fun tookOver(
    at: WorldBlock,
    held: ByteArray,
    taken: ByteArray,
    cause: Cause,
    by: Attributed?,
    timestamp: Long,
): List<Transfer> = listOf(
    Transfer(cause, at, Void, held, null, 1, timestamp, Kind.MUTATE, actor = by.culprit()),
    Transfer(cause, Void, at, taken, null, 1, timestamp, Kind.MUTATE, actor = by.culprit()),
)

/**
 * The positions a blast really cleared. An explosion traces its positions one at a time, so it reaches
 * one half of a door, a bed or a tall plant and not the other, and the half it did not reach is
 * cleared by the server under no event of its own — the same reason the break capture follows it.
 * Unfollowed, that half keeps for ever whatever the item plane credited to it.
 */
internal fun withOtherHalves(blocks: List<Block>): Set<Block> {
    val hit = LinkedHashSet(blocks)
    for (block in blocks) otherHalfOf(block)?.let { hit += it }
    return hit
}

/**
 * The other half of a bed, from the half the event names. Both halves are gone by the time the
 * explosion is announced, so neither the block nor `otherHalfOf` can be asked and the state of the
 * partner is derived from the one the event carries.
 */
internal fun otherBedHalf(data: BlockData): BlockData? {
    val bed = data as? Bed ?: return null
    val other = bed.clone() as Bed
    other.part = if (bed.part == Bed.Part.HEAD) Bed.Part.FOOT else Bed.Part.HEAD
    return other
}

/**
 * Growing a tree announces itself to two events, and only one of them may file it. Two rows for one
 * change would make the second declare as old what the first had just made new, and the newest row of
 * the position would stop being what stands there, which is what the whole attribution ladder reads.
 *
 * The fertilize event owns the change wherever it fires: the server places the captured snapshots
 * gated on that one alone and the cancel link from the structure event runs one way, so a tree filed
 * from the structure event would stand in the journal after a plugin refused it. A tree that grew
 * where nobody spread anything raises the structure event by itself and is gated on that, so its
 * filing cannot simply be dropped either.
 *
 * The structure event runs first and cannot see what follows it, so it leaves its filing here as a
 * claim; the fertilize event behind it takes the claim away and files the change itself. What nobody
 * takes is filed by the per-tick sweep, a whole tick after the claim was made: the sweep runs on the
 * global region while the events run on the region owning the tree, so a claim settled in the tick it
 * was made could be settled between the two events.
 *
 * The two events are handed the same list object, so identity settles which claim is whose.
 */
internal class GrowClaims {
    private class Claim(val blocks: List<BlockState>, val tick: Long, val file: () -> Unit)

    private val claimed = ConcurrentLinkedQueue<Claim>()
    private val tick = AtomicLong()

    val isEmpty: Boolean get() = claimed.isEmpty()

    fun claim(blocks: List<BlockState>, file: () -> Unit) {
        claimed += Claim(blocks, tick.get(), file)
    }

    fun claims(blocks: List<BlockState>): Boolean = claimed.removeIf { it.blocks === blocks }

    fun settle() {
        val ripe = tick.getAndIncrement()
        for (claim in claimed) {
            if (claim.tick >= ripe) continue
            if (claimed.remove(claim)) claim.file()
        }
    }
}

// A read-back is queued in one tick and runs at the start of the next, so a claim on one has to stand
// for a tick and no longer.
internal const val READ_BACK_MILLIS = 50L

// How recent the row a read-back takes for its own change has to be: the tick before, under a lagging region.
private const val FILED_MILLIS = 5_000L

/**
 * The positions a read-back is already queued for. Two physics events reach one position in one tick:
 * `Level.setBlock` fires one through `updateNeighborsAt`, and the neighbour shape update behind it
 * fires a second from `VegetationBlock.updateShape` before that returns air. Both read back air and
 * both file it, which is one disappearance booked twice and a position driven below zero on the item
 * plane. The journal cannot answer whether the first has filed: its submit is asynchronous and the row
 * is not there yet, so the answer is kept where it is knowable.
 *
 * A claim expires with the tick it was made in. Two steps between taking it and the task that releases
 * it can fail — the block-entity read and the queueing itself — and a task that was queued can still
 * never run, because the world unloaded or the plugin went down. Held for ever, such a claim would
 * refuse every later read-back of that position for as long as the server runs.
 */
internal class ReadBacks(private val now: () -> Long = System::currentTimeMillis) {
    private val queued = ConcurrentHashMap<WorldBlock, Long>()

    // What a capture has just filed for itself, kept here because the journal cannot answer in time:
    // a submit only queues the row for a writer thread of its own, so a read-back a tick later asking
    // the journal whether this change is already there can be told no and file it a second time. A
    // ravager and a wither both raise their own event and then destroy the block, which is exactly
    // that pair, and the position goes negative on the item plane for it.
    private val recent = ConcurrentHashMap<WorldBlock, Pair<String, Long>>()

    val isEmpty: Boolean get() = queued.isEmpty() && recent.isEmpty()

    fun claim(at: WorldBlock): Boolean {
        val taking = now()
        val held = queued.putIfAbsent(at, taking) ?: return true
        if (taking - held <= READ_BACK_MILLIS) return false
        return queued.replace(at, held, taking)
    }

    fun done(at: WorldBlock) {
        queued.remove(at)
    }

    /** Whether a read-back is on its way here: what changes at the position meanwhile is what it will find. */
    fun pending(at: WorldBlock): Boolean = queued[at]?.let { now() - it <= READ_BACK_MILLIS } == true

    fun filed(at: WorldBlock, before: String, after: String) {
        recent[at] = "$before>$after" to now()
    }

    fun wasFiled(at: WorldBlock, before: String, after: String): Boolean = fresh(at) == "$before>$after"

    /**
     * Whether any capture has filed a change at this position within the tick, whatever the change was.
     * A read-back carries the state the position held when it was queued, and a change filed behind its
     * back makes that state stale: whatever the read finds now, the row it would write names as old a
     * block that had already been replaced.
     */
    fun settled(at: WorldBlock): Boolean = fresh(at) != null

    private fun fresh(at: WorldBlock): String? {
        val (change, filed) = recent[at] ?: return null
        if (now() - filed > READ_BACK_MILLIS) {
            recent.remove(at)
            return null
        }
        return change
    }

    fun sweep() {
        val cutoff = now() - READ_BACK_MILLIS
        recent.values.removeIf { it.second < cutoff }
        queued.values.removeIf { it < cutoff }
    }
}

// Properties that carry the signal rather than the block, which the switch rows of phase 5.7 already
// cover; ones a block takes from its neighbours — grass under snow, a stair's corner, which sides a
// fence or a pane joins — which change with the neighbour and are filed by [Cause.BLK_SHAPE], not as the
// hand's; and ones that a hand flips and the block flips back by itself.
private val SIGNAL_PROPERTIES = setOf("powered", "power")

private val SIDES = setOf("north", "south", "east", "west", "up")

/**
 * The properties a block takes from its neighbours alone. A hand never sets them, and the server rewrites
 * them as a neighbour changes without raising anything: they get rows of their own, [Cause.BLK_SHAPE].
 * A vine's or a lichen's sides are what it clings to, and are not.
 */
internal fun shapeKeysOf(name: String): Set<String> = when {
    name.endsWith("_fence") || name.endsWith("_pane") || name.endsWith("_wall") || name.endsWith("_bars") ||
        name == "minecraft:redstone_wire" || name == "minecraft:tripwire" -> SIDES + "attached"
    name.endsWith("_stairs") -> setOf("shape")
    name.endsWith("_fence_gate") -> setOf("in_wall")
    name == "minecraft:grass_block" || name == "minecraft:podzol" || name == "minecraft:mycelium" -> setOf("snowy")
    else -> emptySet()
}

/** Whether a block went from one state to the other by what it takes from its neighbours and nothing else. */
internal fun reshaped(before: String, after: String): Boolean {
    val name = blockNameOf(before)
    if (before == after || name != blockNameOf(after)) return false
    val was = propertiesOf(before)
    val now = propertiesOf(after)
    val keys = shapeKeysOf(name)
    return (was.keys + now.keys).filter { was[it] != now[it] }.all { it in keys }
}

private fun selfRevertingOf(name: String): Set<String> = shapeKeysOf(name) + when {
    name == "minecraft:barrel" -> setOf("open")
    name.endsWith("_bed") -> setOf("occupied")
    name.endsWith("redstone_ore") -> setOf("lit")
    name == "minecraft:vault" -> setOf("vault_state")
    name == "minecraft:big_dripleaf" -> setOf("tilt")
    // A brush stroke dusts the block a step and the block settles back if the brushing stops; only
    // the brushing that finishes changes what stands there, and that changes the block's name.
    name.startsWith("minecraft:suspicious_") -> setOf("dusted")
    else -> emptySet()
}

private fun propertiesOf(state: String): Map<String, String> =
    state.substringAfter('[', "").removeSuffix("]").split(',').filter { it.isNotEmpty() }
        .associate { it.substringBefore('=') to it.substringAfter('=') }

/** Whether going from one state to the other is a change worth a row, rather than signal or noise. */
internal fun handMade(before: String, after: String): Boolean {
    if (before == after) return false
    val name = blockNameOf(before)
    if (name != blockNameOf(after)) return true
    val was = propertiesOf(before)
    val now = propertiesOf(after)
    val ignored = SIGNAL_PROPERTIES + selfRevertingOf(name)
    return (was.keys + now.keys).any { it !in ignored && was[it] != now[it] }
}

// Long enough that a read-back that never ran — its chunk gone before the region got to it — does not
// shut the position to every later touch.
private const val TOUCH_STALE_MILLIS = 5_000L

// Dynamite primed by these is filed by the capture of what primed it.
private val PRIMED_ELSEWHERE = setOf(
    TNTPrimeEvent.PrimeCause.EXPLOSION, TNTPrimeEvent.PrimeCause.FIRE, TNTPrimeEvent.PrimeCause.BLOCK_BREAK,
)

// Between dynamite primed and the entity it becomes there is one call; a note older than this was for an
// entity that never came.
private const val PRIMING_MILLIS = 1_000L

// How long after stepping through a portal a player can still be who the far side was built for: the
// journey loads the chunks there first.
private const val TRAVEL_MILLIS = 30_000L

// The largest portal the game builds is 21 by 21.
private const val PORTAL_MAX_BLOCKS = 21 * 21

/**
 * Positions a player or a mechanism has just touched, waiting for the read a tick later that files
 * what the touch changed. Every row any capture submits passes through [filed], so a touch whose change
 * was already written by the capture that made it — a placement, a break, a switch, a sign — is left
 * to that row. The journal cannot answer that in time: a submitted row is still queued for its writer.
 */
class HandTouches(private val now: () -> Long = System::currentTimeMillis) {
    private class Touch(val at: Long, @Volatile var cause: Cause, val by: Attributed?) {
        @Volatile var filed = false
    }

    private val pending = ConcurrentHashMap<WorldBlock, Touch>()

    /** True when this touch is the one to read the position back; a second one in the tick is not. */
    fun touch(at: WorldBlock, cause: Cause = Cause.BLK_PLAYER_USE, by: Attributed? = null): Boolean {
        val taking = now()
        val held = pending[at]
        if (held != null && taking - held.at < TOUCH_STALE_MILLIS) {
            // The click comes first and the bucket after it, and the bucket names the change better.
            if (cause != Cause.BLK_PLAYER_USE) held.cause = cause
            return false
        }
        pending[at] = Touch(taking, cause, by)
        return true
    }

    /** Whoever touched the position and is still waiting for its read: a click the block answers with a
     *  change of its own, like a dragon egg leaving. */
    fun toucher(at: WorldBlock): Attributed? = pending[at]?.by

    fun filed(world: UUID, changes: List<BlockChange>) {
        if (pending.isEmpty()) return
        for (change in changes) pending[WorldBlock(world, change.x, change.y, change.z)]?.filed = true
    }

    /** Whether some capture filed the position since it was touched, while its read still waits. */
    fun filedSinceTouch(at: WorldBlock): Boolean = pending[at]?.filed == true

    /** Ends the wait: the cause to file under, or null where some capture filed the position already. */
    fun take(at: WorldBlock): Cause? = pending.remove(at)?.takeIf { !it.filed }?.cause
}

/**
 * Who built a golem or a wither: whoever put down a block of the pattern, or else whoever set going the
 * dispenser beside it, whose pumpkin or skull the pattern took in the same call it was put down.
 */
internal fun builderOf(placers: List<Attributed?>, positions: List<WorldBlock>, energy: Energy): Attributed? =
    placers.firstNotNullOfOrNull { it } ?: energy.near(positions)?.inferred()

/**
 * Every change to a block that no player signed, in both planes: what disappears, and what merely
 * moves. A block row carries the two states the position went between; the item row of a disappearance
 * is the same shape a player's break already writes — the form the position was holding goes to `Void`
 * — and differs only in the cause and in that the actor may be empty.
 *
 * A piston and gravity take no block away, they change where it is, and filing them here among the
 * causes of destruction would be an error with a price: a shift written as one row saying a position
 * became air duplicates the block when the journal is played backwards. Both write two rows under one
 * event instead, and one transfer between the two positions.
 *
 * Nothing here is written speculatively. Where the outcome of an event is not knowable while it is
 * being handled — a block giving way under physics, a block burning that becomes either fire or air,
 * a liquid whose arriving level is decided afterwards — the position is read back on the region that
 * owns it, at the start of that region's next tick, and a row is written only if the block really
 * went. The row keeps the time of the event rather than of the read.
 *
 * Every handler sits at `MONITOR` behind the cancellation, the seeding ones included: a refused
 * ignition lights no fire and a refused bucket empties nothing, so there is nothing to note about
 * either, and a note seeded ahead of the refusal would spend its whole window offering the refused
 * player as the answer for whatever happens there next.
 */
class BlockDestructionListener(
    private val plugin: Plugin,
    private val registries: Registries,
    private val logs: BlockLogs,
    private val attribution: Attribution,
    private val codec: ItemFormCodec,
    private val origins: SpawnOrigins,
    private val entities: EntityOrigins,
    private val placed: PlacedForms,
    private val owners: RocksItemLog,
    private val sink: (List<Transfer>) -> Unit,
    private val energy: Energy = Energy(),
    private val touches: HandTouches = HandTouches(),
    // Runs a task on the region of the location a tick later.
    private val later: (Location, () -> Unit) -> Unit = { _, _ -> },
) : Listener {

    private val growing = GrowClaims()
    private val readBacks = ReadBacks()
    // The shaped neighbours waiting for their read, one per position and tick: the first look is the
    // state the tick found, and the next change beside it in that tick would only see it half rewritten.
    // A capture that files the position before the read takes the look over (D107).
    private class ShapeWatch(val before: BlockData, val by: Attributed, val timestamp: Long)
    private val shapeWatches = ConcurrentHashMap<WorldBlock, ShapeWatch>()
    // Who stands behind dynamite just primed, on its position until the entity appears there.
    private val priming = ConcurrentHashMap<WorldBlock, Pair<Attributed, Long>>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        // A wind charge or a mace's burst opens doors and gates and puts out candles without breaking
        // anything, and nothing else says so.
        if (event.explosionResult == ExplosionResult.TRIGGER_BLOCK) {
            val touched = event.blockList().flatMap { listOfNotNull(it, otherHalfOf(it)) }.distinct()
            // A mace's burst is raised with the player who swung it as the source.
            val source = event.entity
            val by = (source as? Player)?.let { Attributed(it.uniqueId, Confidence.FACT) } ?: whoSetOff(source)
            return readBack(touched, by, explosionCause(source.type))
        }
        if (event.explosionResult !in DESTROYING) return
        val source = event.entity
        explode(event.blockList(), explosionCause(source.type), by = whoSetOff(source), extra = emptyList())
    }

    /**
     * The block that went off is already air here, so its type comes from the state the event carries
     * and its own row has to be written by hand: neither half of a bed nor a respawn anchor raises an
     * event of its own on the way out.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (event.explosionResult !in DESTROYING) return
        val data = event.explodedBlockState.blockData
        val standing = data.asString
        val cause = explosionCause(standing)
        val at = event.block
        // The other half of a bed is where the player clicked, so it is the half that carries both
        // the placement note and the form the position took over.
        val partner = otherBedHalf(data)?.let { half -> partnerFace(data)?.let { at.getRelative(it) to half } }
        // A bed in the Nether and a charged anchor outside it go off under a player's click, and that
        // player set them off; who put the block down is asked only when nobody clicked (D82).
        val by = touches.toucher(positionOf(at))
            ?: partner?.let { (block, _) -> touches.toucher(positionOf(block)) }
            ?: placerOf(positionOf(at), standing)
            ?: partner?.let { (block, half) -> placerOf(positionOf(block), half.asString) }
        val gone = ArrayList<Site>(2)
        for ((block, was) in listOfNotNull(at to data, partner)) {
            val now = block.blockData.asString
            if (wentAway(was.asString, now)) gone += Site(positionOf(block), block, was, now)
        }
        explode(event.blockList(), cause, by, gone)
    }

    // The root of every fire chain: without a note here the burning and the spreading that follow
    // have no culprit to carry forward. The fire itself, or the candle or campfire lit, is read back
    // like any other touch; a dispenser's flint is the dispenser's read.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) {
        val block = event.block as CraftBlock
        // Over soul sand and soul soil the fire that goes down is soul fire, and a note has to name
        // what is standing there: every reader of one compares by block, so a note calling it plain
        // fire answers nothing and the whole chain off it burns unattributed.
        val lit = BaseFireBlock.getState(block.level, block.position).asBlockData().asString
        val player = event.player
        // A fire charge out of a dispenser somebody pressed, a burning arrow, a channeling trident's bolt: the
        // fire answers to whoever stands behind what lit it, and the whole chain off it with it.
        val entity = event.ignitingEntity
        if (player == null && entity != null) {
            val by = behindChange(entity) ?: return
            if (by.confidence != Confidence.NEARBY) attribution.placed(positionOf(block), lit, by.actor)
            readBack(listOf(block), by.inferred(), Cause.BLK_FIRE_SPREAD)
            return
        }
        if (player == null) {
            // Lava sets fire on a random tick, and nothing else announces the fire it puts down.
            if (event.cause != BlockIgniteEvent.IgniteCause.LAVA) return
            val by = event.ignitingBlock?.let(::pouredBy)
            if (by != null && by.confidence != Confidence.NEARBY) attribution.placed(positionOf(block), lit, by.actor)
            changed(block, block.blockData, lit, Cause.BLK_FIRE_SPREAD, by)
            return
        }
        attribution.placed(positionOf(block), lit, player.uniqueId)
        readBack(listOf(block), Attributed(player.uniqueId, Confidence.FACT))
    }

    // The same for the other chain: emptying a bucket raises no placement, so the water it puts down
    // would otherwise flow away from a position nobody was ever noted at.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        val block = event.block as CraftBlock
        readBack(listOf(block), Attributed(event.player.uniqueId, Confidence.FACT), Cause.BLK_BUCKET)
        val placed = bucketPlaced(event.bucket, block.blockState) ?: return
        attribution.placed(positionOf(block), placed, event.player.uniqueId)
    }

    // A source taken up, a waterlogged block drained, powder snow scooped.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        takenUp(event.block as CraftBlock)
        readBack(listOf(event.block), Attributed(event.player.uniqueId, Confidence.FACT), Cause.BLK_BUCKET)
    }

    /**
     * A source taken up leaves what it fed running down for seconds, and lava running down sets fire. Who
     * poured it answers for that, not who took it up: an owner scooping up a griefer's lava does not take
     * the fire on. The run is noted as it stands, while the source is still there to name the pourer.
     */
    private fun takenUp(source: CraftBlock) {
        val state = source.blockState
        if (state.block !is LiquidBlock || !state.fluidState.isSource) return
        val by = pouredBy(source)?.takeIf { it.confidence != Confidence.NEARBY } ?: return
        val world = source.world
        val run = ranFrom(source.position, source.level::getBlockState) { Bukkit.isOwnedByCurrentRegion(world, it.x shr 4, it.z shr 4) }
        for ((at, ran) in run) attribution.placed(WorldBlock(world.uid, at.x, at.y, at.z), ran.asBlockData().asString, by.actor)
    }

    /**
     * Whatever a dispenser does to the block in front of it: a liquid put down or taken up, a shulker
     * box, a carved pumpkin, powder snow, a fire, a beehive drained. The behaviour runs after the
     * event, so the position is read back, and whoever set the dispenser off is who the energy around
     * it names. What it throws and the entities it places are the item plane's.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDispensed(event: BlockDispenseEvent) {
        val block = event.block
        val facing = (block.blockData as? Directional)?.facing ?: return
        readBack(listOf(block.getRelative(facing)), energyAt(block, energy), Cause.BLK_DISPENSER)
    }

    /**
     * What a burning block leaves is decided by a roll the server makes after the event: the position
     * becomes either fire of a fresh age or whatever the block was standing in. Both are honest
     * outcomes and neither is knowable here, so the position is read back.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        val block = event.block as CraftBlock
        val by = event.ignitingBlock?.let(::fireStartedBy)
        // What the block leaves may be fire, and that fire may leap on before its row reaches the journal:
        // it is noted at once, as a fire that spread is. Left as air, the position never matches the note.
        if (by != null && by.confidence != Confidence.NEARBY) {
            val fire = BaseFireBlock.getState(block.level, block.position).asBlockData().asString
            attribution.placed(positionOf(block), fire, by.actor)
        }
        defer(block, block.blockData, Cause.BLK_FIRE_BURN, by)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) {
        val block = event.block
        if (capturing(block)) return
        val at = positionOf(block)
        val after = event.newState.blockData.asString
        // Fire leaps up to four blocks up and across the diagonal, past every neighbour's note, and the
        // fire it leapt from is on the event. The find is written forward onto this position, as the
        // carry does, so an arbitrarily long chain stays attributed while no note covers more than a step.
        val leapt = if (blockNameOf(after) in FIRES) fireStartedBy(event.source) else null
        if (leapt != null && leapt.confidence != Confidence.NEARBY) attribution.placed(at, after, leapt.actor)
        // A block burnt away in this very tick, with fire leaping straight into the air it left: the burn's
        // read-back files the block to fire. A row of the spread's own here would settle the position and
        // the read-back would drop the block, which a rollback then never puts back.
        if (blockNameOf(after) in FIRES && readBacks.pending(at)) return
        val cause = spreadCause(after)
        // Sculk spreads off a catalyst's bloom, and the bloom off a death somebody stands behind.
        val bloomed = if (cause == Cause.BLK_SCULK) attribution.killerNear(at, SCULK_REACH) else null
        changed(block, block.blockData, after, cause, leapt ?: bloomed ?: attribution.carriedTo(at, after))
        // A bamboo shoot turns into bamboo by its shape once the stalk above it is there, with no event of
        // its own; read back, so its row says what stands there and a rollback can take the planting away.
        val source = event.source
        if (source.type == Material.BAMBOO_SAPLING) defer(source, source.blockData, Cause.BLK_GROW, null, expectsDrops = false)
    }

    /**
     * Who set the fire burning at this position. A fire burns a minute and more, longer than its note,
     * and the journal has the row that set it there. A find is noted again, so what this fire sets
     * alight next carries on from it. A seek, only where the note has run out.
     *
     * The fire on the event may be out already: one that lost its footing goes out at the top of its tick
     * and still leaps and burns in the rest of it. It was plain fire either way, since soul fire does
     * neither, so that is what is asked about rather than what stands there now.
     */
    private fun fireStartedBy(fire: Block): Attributed? {
        val at = positionOf(fire)
        attribution.placerAt(at, FIRE)?.let { return it }
        val found = attribution.journalPlacerAt(at, FIRE, FIRING_CAUSES) ?: return null
        if (found.confidence != Confidence.NEARBY) attribution.placed(at, FIRE, found.actor)
        return found
    }

    /**
     * Ice under a snow golem and ice under a frost walker arrive here too: `EntityBlockFormEvent`
     * declares no handler list of its own and is therefore delivered to this one. Registering a
     * second handler for it would call this one twice for every such change.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onForm(event: BlockFormEvent) {
        val block = event.block
        if (capturing(block)) return
        val before = block.blockData
        val entity = (event as? EntityBlockFormEvent)?.entity
        val cause = entity?.let { entityFormCause(it.type) } ?: formCause(before.asString)
        // Frost walker freezes the water under the player wearing it, and that player is who froze it; a
        // snow golem's trail is whoever built the golem (D83). Stone out of a liquid and concrete out of
        // its powder are whoever let the liquid run.
        val by = when {
            entity is Player -> Attributed(entity.uniqueId, Confidence.FACT)
            entity != null -> entities.summonerOf(entity.uniqueId)
            cause == Cause.BLK_LIQUID_FORM || before.material.name.endsWith("_CONCRETE_POWDER") -> formedBy(block, before.asString)
            else -> null
        }
        changed(block, before, event.newState.blockData.asString, cause, by)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        val block = event.block
        if (capturing(block)) return
        // Fire a burning block left where it cannot stand goes out the moment it is set, before the burn's
        // read-back comes round. That read-back files the burn, plank to air, on whoever lit it; a row of
        // the fade's own here would make it take the change as filed and leave the plank unaccounted for.
        if (blockNameOf(block.blockData.asString) in FIRES && readBacks.pending(positionOf(block))) return
        changed(block, block.blockData, event.newState.blockData.asString, Cause.BLK_FADE, by = null)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGrow(event: BlockGrowEvent) {
        val block = event.block
        // Bone meal routes a crop through this event and then reports the same position again in the
        // fertilize event, which is the one that knows who spread it.
        if (capturing(block)) return
        changed(block, block.blockData, event.newState.blockData.asString, Cause.BLK_GROW, by = null)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeafDecay(event: LeavesDecayEvent) {
        val block = event.block
        val before = block.blockData
        changed(block, before, leftBehind(before).asString, Cause.BLK_LEAF_DECAY, attribution.fellerOf(positionOf(block)))
    }

    /**
     * The destination of a flowing liquid. What arrives there is a level the server works out after
     * the event, so the row waits for the read-back; the culprit does not, because the note on the
     * position the liquid came from is freshest now.
     *
     * The carry runs on every step, destroying or not. Nearly every step of a liquid is into air, and
     * a step that wrote no note where the liquid arrived would leave the next step with nothing to
     * find — which is the whole chain, since the window only ever covers one step of it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFromTo(event: BlockFromToEvent) {
        val to = event.toBlock
        val from = event.block
        // A dragon egg struck or used jumps elsewhere, and this is its only announcement. It is a move:
        // the egg leaves one position and arrives in the other, and the item it stands for goes along.
        if (from.type == Material.DRAGON_EGG) return eggJumped(from, to)
        val by = attribution.carriedTo(positionOf(to), from.blockData.asString)
        if (!liquidDestroys((to as CraftBlock).blockState)) {
            // Only a flow somebody let out: the world's own springs are no story to tell.
            if (to.type.isAir && by != null && by.confidence != Confidence.NEARBY) flowed(to, by)
            return
        }
        defer(to, to.blockData, Cause.BLK_LIQUID_DESTROY, by ?: pouredBy(from))
    }

    /**
     * Where a liquid somebody let out ran into air: the level it came to, a tick on, and nothing else. No
     * read-back of the full kind — no journal seek, no removal noted, no item plane — since a pour runs
     * into dozens of places a second and none of it is a block anybody owned.
     */
    private fun flowed(to: Block, by: Attributed) {
        if (!plugin.isEnabled) return
        val log = logs.get(to.world.uid) ?: return
        val timestamp = System.currentTimeMillis()
        plugin.server.regionScheduler.execute(plugin, to.world, to.x shr 4, to.z shr 4) {
            val now = to.blockData
            if (now.material != Material.WATER && now.material != Material.LAVA) return@execute
            log.submit(BlockChange(to.x, to.y, to.z, "minecraft:air", now.asString, Cause.BLK_LIQUID_FLOW, timestamp, by.confidence, actor = by.actor))
        }
    }

    /**
     * Who let the liquid run that turned into stone here, or hardened the concrete powder: the block put
     * down a moment ago first, then the liquids that met, by the tracker and then by the bucket behind
     * them. A lava cast is somebody's wall, and a rollback of them has to take it down with their lava.
     */
    private fun formedBy(block: Block, before: String): Attributed? {
        attribution.placerAt(positionOf(block), before)?.let { return it }
        val liquids = (listOf(block) + LIQUID_FACES.map(block::getRelative)).filter { it.type == Material.WATER || it.type == Material.LAVA }
        return liquids.firstNotNullOfOrNull { attribution.placerAt(positionOf(it), it.blockData.asString) }
            ?: liquids.firstNotNullOfOrNull { pouredBy(it) }
    }

    /**
     * Who poured the liquid acting now. Lava sets fire minutes after its bucket and runs on into what
     * the fire left, long after the note of the pour ran out; the source it runs from still has the
     * bucket's row. A find is noted here again, so the chain off it carries on as from a fresh pour.
     * A seek per source asked, and only where a row is about to be written anyway.
     */
    private fun pouredBy(liquid: Block): Attributed? {
        val at = positionOf(liquid)
        val standing = liquid.blockData.asString
        attribution.placerAt(at, standing)?.let { return it }
        // Its own flow row first: lava that ran far from its bucket is past any walk back to the source, and
        // 6595 fires it set on a real map were nobody's (D110).
        val found = attribution.journalPlacerAt(at, standing, FLOWING_CAUSES)
            ?: sourcesOf(liquid).firstNotNullOfOrNull {
                attribution.journalPlacerAt(positionOf(it), it.blockData.asString, POURING_CAUSES)
            } ?: return null
        if (found.confidence != Confidence.NEARBY) attribution.placed(at, standing, found.actor)
        return found
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val entity = event.entity
        // A falling block is the one entity here that moves a block rather than changing one, and both
        // ends of that movement arrive on this same event.
        if (entity is FallingBlock) return fell(event, entity)
        val block = event.block
        // A hoe, a shovel, an axe, a honeycomb, an eye of ender, a trampled field: the server names the
        // player here, while the block is still the old one.
        if (entity is Player) {
            val after = event.blockData.asString
            if (!handMade(block.blockData.asString, after)) return
            val by = Attributed(entity.uniqueId, Confidence.FACT)
            // A hoe on rooted dirt knocks the hanging roots out of it.
            if (block.type == Material.ROOTED_DIRT) {
                codec.encodeOrNull(org.bukkit.inventory.ItemStack(Material.HANGING_ROOTS))?.let { roots ->
                    origins.expect(Void, Cause.BLOCK_INTERACT_DROP, roots.key, spotOf(block.location), 1, entity.uniqueId)
                }
            }
            // A double copper chest waxed or scraped on one half reshapes the other with no event.
            chestPartnerOf(block)?.let { readBack(listOf(it), by) }
            return changed(block, block.blockData, after, Cause.BLK_PLAYER_USE, by)
        }
        // A burning arrow into dynamite primes it next, and the priming files the block on the shooter.
        if (block.type == Material.TNT) return
        val cause = entityBlockCause(entity.type) ?: return
        // A mob dying under weaving leaves cobwebs: whoever killed it put them there, as far as anything can say.
        val killer = (entity as? LivingEntity)?.takeIf { it.isDead }?.killer?.let { Attributed(it.uniqueId, Confidence.INFERRED) }
        changed(block, block.blockData, event.blockData.asString, cause, behindChange(entity) ?: killer)
    }

    /**
     * The net under everything a hand changes that raises no event of its own: a door, a trapdoor, a
     * gate, a repeater, a comparator, a note block, a daylight sensor, a cake, a candle, a composter.
     * Every touch is read back at the start of the next tick, and whatever changed there and no other
     * capture filed is written on the player. Not behind the cancellation: a refused touch changes
     * nothing, and the read finds exactly that.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onTouch(event: PlayerInteractEvent) {
        if (event.action == Action.LEFT_CLICK_AIR || event.action == Action.RIGHT_CLICK_AIR) return
        val clicked = event.clickedBlock ?: return
        readBack(listOfNotNull(clicked, otherHalfOf(clicked)), Attributed(event.player.uniqueId, Confidence.FACT))
    }

    /**
     * Queues the positions for the read at the start of the next tick. The server writes every one of
     * them from this thread, so this region owns them all now; a split before the next tick can hand
     * some to another region, and those are read again there.
     */
    private fun readBack(blocks: List<Block>, by: Attributed?, cause: Cause = Cause.BLK_PLAYER_USE) {
        if (!plugin.isEnabled) return
        val touched = blocks
            .filter { !commanded(it) && touches.touch(positionOf(it), cause, by) }
            // The block entity goes into the row as it was: a lectern, a jukebox or a pot put back by a
            // rollback needs what it held, not only its shape.
            .map { Site(positionOf(it), it, it.blockData, it.blockData.asString, payloadAt(it)) }
        val first = touched.firstOrNull()?.block ?: return
        val marks = touched.associate { it.at to markOf(it.block) }
        val timestamp = System.currentTimeMillis()
        plugin.server.regionScheduler.execute(plugin, first.world, first.x shr 4, first.z shr 4) {
            val (mine, moved) = touched.partition { plugin.server.isOwnedByCurrentRegion(it.block) }
            compare(mine, marks, by, timestamp)
            for (part in moved.groupBy { (it.block.x shr 4) to (it.block.z shr 4) }.values) {
                val at = part.first().block
                plugin.server.regionScheduler.execute(plugin, at.world, at.x shr 4, at.z shr 4) { compare(part, marks, by, timestamp) }
            }
        }
    }

    private fun compare(touched: List<Site>, marks: Map<WorldBlock, ByteArray?>, by: Attributed?, timestamp: Long) {
        val first = touched.firstOrNull()?.block ?: return
        val log = logs.get(first.world.uid) ?: return
        val retouched = ArrayList<BlockChange>()
        val changed = touched.mapNotNull { site ->
            val filedAs = touches.take(site.at) ?: return@mapNotNull null
            val now = site.block.blockData.asString
            if (!handMade(site.before.asString, now)) {
                // The block is the same and only what its entity holds changed: a sign dyed or
                // waxed, a spawner given an egg.
                val was = marks[site.at]
                if (was != null && !was.contentEquals(markOf(site.block) ?: was)) {
                    retouched += BlockChange(
                        site.at.x, site.at.y, site.at.z, now, now, filedAs, timestamp,
                        confidence = by?.confidence ?: Confidence.FACT, actor = by?.actor,
                        payloadBefore = site.payload, payloadAfter = payloadAt(site.block),
                    )
                }
                return@mapNotNull null
            }
            filedAs to Site(site.at, site.block, site.before, now, site.payload)
        }
        if (retouched.isNotEmpty()) log.submit(retouched)
        for ((filedAs, sites) in changed.groupBy({ it.first }, { it.second })) {
            file(log, sites, filedAs, by, timestamp)
        }
    }

    // What a hand can change in a block entity without changing the block: the text side of a sign,
    // and what a spawner spawns. A spawner's whole tag also counts down its timer every tick, so for
    // one of those only the kind it spawns is compared.
    private fun markOf(block: Block): ByteArray? = when (val state = block.getState(false)) {
        is Sign -> payloadAt(block)
        is CreatureSpawner -> (state.spawnedType?.name ?: "").toByteArray()
        is TrialSpawner -> listOf(state.normalConfiguration.spawnedType, state.ominousConfiguration.spawnedType)
            .joinToString { it?.name ?: "" }.toByteArray()
        else -> null
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) =
        piston(event.block, event.blocks, Cause.BLK_PISTON_EXTEND, extending = true)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) =
        piston(event.block, event.blocks, Cause.BLK_PISTON_RETRACT, extending = false)

    /**
     * A flight that ended in anything but a landing: the block was destroyed in the air, fell out of
     * the world, or turned into an item. The position it left really did lose its block then, so what
     * it was holding leaves it here rather than being handed on. A landing takes the flight itself, so
     * what reaches here is only what nothing else claimed.
     *
     * A block that breaks on a torch is removed first and drops itself right after, inside the same
     * call, and that item is what the position gave up: the drop is expected out of the position. Only
     * what no drop took by the next tick — a block gone out of the world, drops switched off — is
     * written off there.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemove(event: EntityRemoveEvent) {
        val entity = event.entity
        if (entity !is FallingBlock) return
        // A chunk going out of memory is not an end: the block goes into the region file still on its
        // way down and comes back under the same name to land, so a loss written here is a loss that
        // never happened and the landing behind it credits a position nothing was ever taken for.
        if (event.cause == EntityRemoveEvent.Cause.UNLOAD) return
        val flight = attribution.landed(entity.uniqueId) ?: return
        val form = flight.form ?: return
        val timestamp = System.currentTimeMillis()
        val at = entity.location
        val dropped = origins.expect(flight.from, Cause.BLK_FALL_START, ItemKey(form, null), spotOf(at), 1, flight.by.culprit())
        later(at) {
            if (dropped() < 1) sink(listOf(wroteOff(flight.from, form, Cause.BLK_FALL_START, flight.by, timestamp)))
        }
    }

    /**
     * A tree, whichever of the three paths grew it. A player's bone meal and a dispenser's both reach
     * here and both raise the fertilize event behind them, which is the one their placement is gated
     * on; only a tree that grew where nobody spread anything is gated on this event. What the event
     * says about bone meal cannot tell the two apart — a dispenser raises it declaring itself not to
     * be bone meal, and names no player either — so the filing waits for the rest of the call instead.
     *
     * The snapshots are read into sites now and not when the filing runs: the block behind each of
     * them still holds the old state only while the placement is waiting on the event that gates it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        val blocks = event.blocks
        val sites = sitesOf(blocks)
        val timestamp = System.currentTimeMillis()
        // A player named on the event witnessed it; nothing here was worked out.
        val by = event.player?.let { Attributed(it.uniqueId, Confidence.FACT) }
        // Settled on the global region, a tick later; filing reads the world, so it goes to the tree's own.
        // Run in place it failed Folia's thread check and the tree went unrecorded.
        val at = blocks.firstOrNull()?.location
        growing.claim(blocks) {
            if (at != null && plugin.isEnabled) plugin.server.regionScheduler.execute(plugin, at) { grew(sites, Cause.BLK_GROW, by, timestamp) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFertilize(event: BlockFertilizeEvent) {
        val blocks = event.blocks
        growing.claims(blocks)
        grew(sitesOf(blocks), Cause.BLK_BONEMEAL, event.player?.let { Attributed(it.uniqueId, Confidence.FACT) })
    }

    /**
     * Belongs on the per-tick task. A structure event leaves its filing behind for the fertilize event
     * that may follow it in the same call, and what no fertilize event took is a tree that grew on its
     * own and has been waiting a tick to be filed.
     */
    fun settleGrowth() {
        growing.settle()
        readBacks.sweep()
        // A read whose task never ran, its chunk gone first.
        val unread = System.currentTimeMillis() - TOUCH_STALE_MILLIS
        shapeWatches.values.removeIf { it.timestamp < unread }
        val stale = System.currentTimeMillis() - PRIMING_MILLIS
        priming.values.removeIf { it.second < stale }
    }

    /**
     * A block whose support went. The event is raised for every neighbour update there is — and the
     * server only raises it at all while somebody is listening — so the server's own question, can
     * this block still stand here, is what bounds it, and whether the block really goes is settled by
     * the read-back rather than guessed at: a torch that survived its physics tick must not be
     * journalled as broken, and the item plane must not lose a torch nobody dropped.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPhysics(event: BlockPhysicsEvent) {
        val block = event.block as CraftBlock
        // Raised about the block itself once it is set and before its neighbours are told, which is the last
        // moment they still stand as they were.
        if (event.sourceBlock == block) watchShapes(block)
        // Every retract passes through here too, and the ones that did raise their event have filed
        // this same change already; the read-back refuses the second row.
        retractingBase(block)?.let { base ->
            piston(base, emptyList(), Cause.BLK_PISTON_RETRACT, extending = false)
            return
        }
        // A portal stands as long as its frame does, which is a shape rule and not a survival one, and
        // the whole sheet goes at once when the frame is broken.
        if (block.type == Material.NETHER_PORTAL) return portalShaken(block)
        val state = block.blockState
        if (state.isAir || state.canSurvive(block.level, block.position)) return
        // The cause dictionary has no entry of its own for a block that could no longer stand where it
        // stood, and this is the one that says the world took the block away by its own rules.
        // What gives way under physics is destroyed through `Level.destroyBlock`, and its drops are
        // expected where that raises its own event.
        defer(block, block.blockData, Cause.BLK_FADE, attribution.supportRemoverAt(positionOf(block)), expectsDrops = false)
    }

    /**
     * What a change here does to the blocks beside it: the sides bars or a fence join, a stair's corner, a
     * wall's height. The server rewrites them with no event, so the neighbours are read now and again a tick
     * later, and each that changed gets a row on whoever changed this block. Without it bars beside a plank
     * that burnt were filed as joining air when the TNT took them, and the rollback put them back so (D104).
     */
    private fun watchShapes(block: CraftBlock) {
        val at = positionOf(block)
        // Only a change somebody is noted for. A world changing by itself is nobody's to roll back, and grass
        // under every snowfall would fill the journal; a radius rollback that needs more widens this.
        val by = attribution.removerAt(at) ?: attribution.placerAt(at, block.blockData.asString) ?: return
        if (!plugin.isEnabled || commanded(block)) return
        val log = logs.get(block.world.uid) ?: return
        val timestamp = System.currentTimeMillis()
        // And the shaped blocks beside those: the wall under a wall that lost its neighbour grows low with it,
        // a change of a change with no event of its own, and was put back tall under a wall put back low (D117).
        val shaped = SIX_FACES.map { block.getRelative(it) }.filter { Bukkit.isOwnedByCurrentRegion(it) && shapedNow(it) }
        val watched = LinkedHashSet<Block>(shaped)
        for (near in shaped) for (face in SIX_FACES) {
            val next = near.getRelative(face)
            if (next != block && Bukkit.isOwnedByCurrentRegion(next) && shapedNow(next)) watched += next
        }
        for (near in watched) {
            val before = near.blockData
            val there = positionOf(near)
            // The first watch stays until its own task reads it: one replaced under a long tick would lose the
            // first change's row, and the next would start from a state already half rewritten.
            val watch = ShapeWatch(before, by, timestamp)
            if (shapeWatches.putIfAbsent(there, watch) != null) continue
            plugin.server.regionScheduler.execute(plugin, near.world, near.x shr 4, near.z shr 4) {
                if (!shapeWatches.remove(there, watch)) return@execute
                val now = near.blockData.asString
                if (!reshaped(before.asString, now) || readBacks.settled(there)) return@execute
                log.submit(row(Site(there, near, before, now, payload = null), Cause.BLK_SHAPE, by, timestamp))
            }
        }
    }

    private fun shapedNow(block: Block) = shapeKeysOf(blockNameOf(block.blockData.asString)).isNotEmpty()

    /**
     * A shaped block filed by a capture while what a change beside it made of it still waits for its read:
     * that goes first, up to the state the capture found. A fence let go by a plank that burnt and blown up
     * by TNT in the same tick was read back as air, and its blast row named it already let go (D107).
     */
    private fun shapeFirst(log: BlockLog, at: WorldBlock, block: Block, found: BlockData) {
        val watch = shapeWatches.remove(at) ?: return
        if (!reshaped(watch.before.asString, found.asString)) return
        log.submit(row(Site(at, block, watch.before, found.asString, payload = null), Cause.BLK_SHAPE, watch.by, watch.timestamp))
    }

    private fun commanded(block: Block) = CommandBirths.writing(block.world.uid, block.x, block.y, block.z)

    // Every portal block joined to this one, read back: those the broken frame took with it are filed
    // on whoever broke the frame. The walk stays on the region that owns this block.
    private fun portalShaken(block: Block) {
        val by = attribution.supportRemoverAt(positionOf(block))
        val sheet = flood(block, cap = PORTAL_MAX_BLOCKS, withStart = true) {
            it.type == Material.NETHER_PORTAL && Bukkit.isOwnedByCurrentRegion(it)
        }
        for (part in sheet) defer(part, part.blockData, Cause.BLK_PORTAL_DESTROY, by, expectsDrops = false)
    }

    /**
     * Dynamite set off in place: by a flint, a fire charge or a burning arrow, by redstone, by a
     * dispenser's flint. The block goes into the primed entity in the same call, so the row is written
     * here and not read back. An explosion, a fire and a hand breaking unstable dynamite have filed the
     * block through their own capture already.
     *
     * The row takes the block away, and with it the journal's answer to who put the dynamite there,
     * which is what the explosion asks four seconds later. So a note is left over the position naming
     * whoever lit it, or failing that whoever put it down.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPrime(event: TNTPrimeEvent) {
        val block = event.block
        val at = positionOf(block)
        if (event.cause in PRIMED_ELSEWHERE) {
            // Primed by a fire or a blast, the entity gets no owner from the server, and where it goes off,
            // thrown about by the blasts beside it, nobody put dynamite down: a whole chain lit from one fire
            // went off as nobody's. Who set off what primed it is known now, and goes to the entity.
            val by = when (event.cause) {
                TNTPrimeEvent.PrimeCause.EXPLOSION -> event.primingEntity?.let(::whoSetOff)
                TNTPrimeEvent.PrimeCause.FIRE -> event.primingBlock?.let(::fireStartedBy)
                else -> null
            } ?: placerOf(at, TNT)
            if (by != null) priming[at] = by.inferred() to System.currentTimeMillis()
            return
        }
        val log = logs.get(block.world.uid) ?: return
        val lit = event.primingEntity.let { it as? Player ?: (it as? Projectile)?.shooter as? Player }
        val by = lit?.let { Attributed(it.uniqueId, Confidence.FACT) }
            ?: event.primingBlock?.let { energyAt(it, energy) }
            ?: energyAt(block, energy)
        val placer = placerOf(at, TNT)
        val standing = block.blockData
        file(log, listOf(Site(at, block, standing, AIR)), Cause.BLK_TNT, by ?: placer, expectsDrops = false)
        (by ?: placer)?.culprit()?.let { attribution.placed(at, standing.asString, it) }
        // The entity carries it too: dynamite lit by a redstone block in the air fell and went off where
        // nobody had put any, and the whole crater was nobody's (D106).
        (by ?: placer)?.let { priming[at] = it to System.currentTimeMillis() }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTntSpawn(event: EntitySpawnEvent) {
        val tnt = event.entity as? TNTPrimed ?: return
        val (by, at) = priming.remove(positionOf(tnt.location.block)) ?: return
        if (tnt.source is Player || System.currentTimeMillis() - at > PRIMING_MILLIS) return
        entities.appeared(tnt.uniqueId, by.actor, by.confidence)
    }

    // A sponge drinking the water around it: the water and the plants in it go, and the sponge turns
    // wet a moment later. Whoever put the sponge down is who the note over it names.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSponge(event: SpongeAbsorbEvent) {
        val sponge = event.block
        val by = attribution.placerAt(positionOf(sponge), sponge.blockData.asString)
        readBack(listOf(sponge) + event.blocks.map { it.block }, by, Cause.BLK_SPONGE)
    }

    // An anvil worn by a use at it, or broken by one: the wear is set right after the event.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAnvilWorn(event: AnvilDamagedEvent) {
        val block = event.inventory.location?.block ?: return
        readBack(listOf(block), Attributed(event.view.player.uniqueId, Confidence.FACT))
    }

    /**
     * A golem or a wither built: the pumpkin or the last skull completes the pattern and the server
     * clears it with no physics and no event but this one, raised just before. Whoever put the last
     * block down is who the note over it names; a copper golem leaves a copper chest behind.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConstruct(event: EntityConstructEvent) {
        val blocks = event.blocks
        if (blocks.isEmpty()) return
        val placers = blocks.map { attribution.placerAt(positionOf(it), it.blockData.asString) }
        readBack(blocks, builderOf(placers, blocks.map(::positionOf), energy), Cause.BLK_FORM)
    }

    private fun eggJumped(from: Block, to: Block) {
        val log = logs.get(from.world.uid) ?: return
        val egg = from.blockData
        val by = touches.toucher(positionOf(from))
        val left = Site(positionOf(from), from, egg, leftBehind(egg).asString, went = positionOf(to))
        val arrived = Site(positionOf(to), to, to.blockData, egg.asString, payload = null)
        file(log, listOf(left), Cause.BLK_PLAYER_USE, by, carried = listOf(arrived), expectsDrops = false)
    }

    // The egg the dragon leaves on its podium, placed right after the event.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEggFormed(event: DragonEggFormEvent) = readBack(listOf(event.block), null, Cause.BLK_FORM)

    private val travellers = ConcurrentHashMap<UUID, Pair<UUID, Long>>()

    // Canvas takes an entity through a portal on a path of its own, and this is the one event on it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPortalAsync(event: EntityPortalAsyncEvent) {
        val player = event.entity as? Player ?: return
        travellers[event.to.uid] = player.uniqueId to System.currentTimeMillis()
    }

    /**
     * A portal lit or built on the far side of a journey. The event hands over the new states while
     * the world still stands as it was, so both sides are read here; the frame a lit portal hands back
     * unchanged is no change. The pair built for a traveller clears and overwrites whatever stood where
     * it goes, and that is filed as taken away by the portal, on the traveller when that is a player.
     *
     * Overwriting drops nothing, but the frame goes down with its neighbours told, and a plant standing
     * where the portal will be — crimson roots on nylium the frame replaces — breaks off and drops before
     * the portal fills its place. The break finds its position already filed and leaves the drop to the
     * capture that filed it, so this one has to expect it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPortalCreate(event: PortalCreateEvent) {
        val log = logs.get(event.world.uid) ?: return
        // Canvas builds the far side of a journey with no traveller on the event; the player who last
        // stepped through a portal towards this world is who it was built for.
        val by = (event.entity as? Player)?.let { Attributed(it.uniqueId, Confidence.FACT) }
            ?: travellers[event.world.uid]?.takeIf { System.currentTimeMillis() - it.second <= TRAVEL_MILLIS }
                ?.let { Attributed(it.first, Confidence.INFERRED) }
        val sites = event.blocks.mapNotNull { state ->
            val block = state.block
            val before = block.blockData
            val after = state.blockData.asString
            if (before.asString == after) null else Site(positionOf(block), block, before, after)
        }
        if (sites.isNotEmpty()) file(log, sites, Cause.BLK_PORTAL_CREATE, by)
    }

    /**
     * A block the server means to destroy rather than merely set to air. A cactus and a sugar cane
     * do not go from the shape update when their support does: they schedule a block tick and remove
     * themselves from it, which raises none of the events above and happens after the read-back of
     * the tick that took the support has already run and found them standing.
     *
     * Everything reaching here through a capture of its own — a mob griefing, a block giving way
     * under physics — is filed by that one, and the read-back is what refuses the second row: a
     * position already waiting for one takes no other, and a position whose newest row already runs
     * between these two states has had this change filed by a capture that knew more about it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockDestroy(event: BlockDestroyEvent) {
        val block = event.block
        if (commanded(block)) return
        val by = attribution.supportRemoverAt(positionOf(block))
        // The drops follow this event inside the same call, so this is where they are expected, and
        // always: a cactus breaks a tick after its support, while the read-back the physics of that
        // support queued still holds the position, and the note that read-back left may already have
        // been swept by then. A capture that filed the position this tick expected them itself.
        if (!readBacks.settled(positionOf(block))) dropsOf(block, Cause.BLK_FADE, by)
        defer(block, block.blockData, Cause.BLK_FADE, by, expectsDrops = false)
    }

    // Everything an explosion took away, plus the positions the exploding block itself vacated before
    // the event was raised. One submit, so every position of one explosion shares an event id.
    private fun explode(blocks: List<Block>, cause: Cause, by: Attributed?, extra: List<Site>) {
        val hit = withOtherHalves(blocks)
        val world = hit.firstOrNull()?.world?.uid ?: extra.firstOrNull()?.at?.world ?: return
        val log = logs.get(world) ?: return
        // An explosion writes plain air over every position it clears, whatever the block was standing
        // in, so the after side is not derived from the fluid the way a break's is.
        file(log, hit.map { Site(positionOf(it), it, it.blockData, AIR) } + extra, cause, by, dropReach = craterReach(hit))
    }

    // The snapshots carry the new state and the block behind each of them still holds the old one.
    private fun sitesOf(blocks: List<BlockState>) = blocks.map { snapshot ->
        val block = snapshot.block
        Site(positionOf(block), block, block.blockData, snapshot.blockData.asString)
    }

    // Every position a tree or a bone meal filled in, as one event.
    private fun grew(
        sites: List<Site>,
        cause: Cause,
        by: Attributed?,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val log = sites.firstOrNull()?.let { logs.get(it.at.world) } ?: return
        file(log, sites, cause, by, timestamp)
    }

    private fun changed(block: Block, before: BlockData, after: String, cause: Cause, by: Attributed?) {
        val log = logs.get(block.world.uid) ?: return
        file(log, listOf(Site(positionOf(block), block, before, after)), cause, by)
    }

    /**
     * A piston firing, which is one event over every position it touches: each moved block leaves a row
     * where it stood and another where it arrived, and the piston's own base and head are positions of
     * their own that the event never mentions.
     *
     * Everything is read while the event runs because that is the only moment it can be read: the
     * piston raises it before it touches anything, and a tick later every position it emptied is air.
     * A piston writes plain air over those, whatever the block was standing in, so a waterlogged block
     * takes its water with it instead of leaving a source behind.
     *
     * Whoever fired it is who the energy around it names: the redstone that reached the base carries
     * the player who set the chain off, and the rows it writes carry that player on to what an observer
     * sees move. The player who last touched the piston is not behind every block the redstone
     * around it shifts afterwards, so nothing else is asked.
     */
    private fun piston(base: Block, moving: List<Block>, cause: Cause, extending: Boolean) {
        val log = logs.get(base.world.uid) ?: return
        val data = base.blockData
        // A retracting sticky piston is already the moving block here, and that block carries the
        // facing and the stickiness of the piston it stands for.
        val facing = (data as? Directional)?.facing ?: return
        val notch = CraftBlock.blockFaceToNotch(facing) ?: return
        val sticky = base.type == Material.STICKY_PISTON ||
            (data as? TechnicalPiston)?.type == TechnicalPiston.Type.STICKY
        val step = if (extending) facing else facing.oppositeFace
        val (left, arrived) = pistonSites(moving, step)
        val emptied = ArrayList(left)
        val filled = ArrayList(arrived)
        val head = base.getRelative(facing)
        if (extending) {
            filled += Site(positionOf(head), head, AIR_DATA, pistonHead(notch, sticky).asString, payload = null)
        } else {
            // A retract that pulls a block takes its own head away before it says anything, and that
            // position is spoken for by what arrives in it; one that pulls nothing loses it here.
            val standing = head.blockData
            if (standing is PistonHead) emptied += Site(positionOf(head), head, standing, AIR)
        }
        filled += Site(
            positionOf(base),
            base,
            pistonBase(notch, sticky, extended = !extending),
            pistonBase(notch, sticky, extended = extending).asString,
            payload = null,
        )
        val by = energyAt(base, energy)
        by?.let { energy.note(positionOf(base), it) }
        file(log, emptied, cause, by, carried = filled)
    }

    /**
     * A falling block, which is a movement with a flight in the middle of it. The two ends are separate
     * events and no positional state survives between them, so what the position it left is holding
     * travels under the entity's own id and is handed over where it lands.
     */
    private fun fell(event: EntityChangeBlockEvent, entity: FallingBlock) {
        val block = event.block
        val log = logs.get(block.world.uid) ?: return
        val at = positionOf(block)
        val carried = entity.blockData.asString
        val becomes = event.blockData.asString
        val timestamp = System.currentTimeMillis()
        // Which half of the fall this is. A block breaking loose leaves behind whatever it was standing in;
        // a block landing puts down the very state it has been carrying, so the two halves are told apart
        // by what the position is about to become rather than by anything about the entity.
        if (carried != becomes) {
            // Who took away what was holding it up, worked out here and kept under the entity: by the
            // time it lands, the position it left holds whatever has moved in behind it.
            // A block put down in the air falls the moment it is placed, with no support taken away:
            // the player who put it there is who let it fall.
            val by = attribution.supportRemoverAt(at) ?: attribution.placerAt(at, carried)
            attribution.tookOff(entity.uniqueId, Falling(at, carried, by, takeHeldForm(at, carried)))
            val site = Site(at, block, block.blockData, becomes)
            file(log, emptyList(), Cause.BLK_FALL_START, by, timestamp, carried = listOf(site))
            // A column comes down one block at a time, and each take-off is what the block above it
            // finds: without a note here the chain would be attributed at its first step only.
            by.culprit()?.let { noteRemoval(at, becomes, it) }
            return
        }
        val flight = attribution.landed(entity.uniqueId)
        val by = flight?.by
        val was = block.blockData
        val left = leftBehind(was)
        // Two rows in one position: whatever the landing replaced gives way, and the block that came
        // down arrives over it. A landing in air writes only the second, the first changing nothing.
        file(
            log,
            listOf(Site(at, block, was, left.asString)),
            Cause.BLK_FALL_LAND,
            by,
            timestamp,
            carried = listOf(Site(at, block, left, carried, payload = null)),
        )
        // A flight nobody watched take off — one a plugin dropped, one already in the air when this was
        // enabled — hands over nothing, and the position it came from is not this event's to name.
        val flown = flight ?: return
        val form = flown.form ?: return
        placed.setFormAt(at.world, at.x, at.y, at.z, form)
        sink(listOf(moved(flown.from, at, form, Cause.BLK_FALL_LAND, by, timestamp)))
    }

    // What a position was holding, taken away from it as the block leaves: the block is not there any
    // more, and a note left behind would be given back to whatever stands there next. Read at the
    // moment of leaving and not later, or a block that moves in while this one is away has its own
    // note read and cleared instead.
    private fun takeHeldForm(at: WorldBlock, state: String): ByteArray? {
        val remembered = placed.formAt(at.world, at.x, at.y, at.z)
        placed.clearFormAt(at.world, at.x, at.y, at.z)
        return gaveBack(remembered, shellForm(state))
    }

    /**
     * Every position of one change: one submit, so they share an event id, and one round trip to the
     * placed-form table for the whole set. A wither or a large cannon clears a few thousand positions
     * in one event, and a point read and a delete apiece would be a few thousand trips through JNI on
     * the thread ticking the region, which is the one place they have no business happening.
     */
    private fun file(
        log: BlockLog,
        sites: List<Site>,
        cause: Cause,
        by: Attributed?,
        timestamp: Long = System.currentTimeMillis(),
        // Positions of the same event whose item side belongs to somebody else: what arrives in a
        // position is spoken for at the other end of the movement that brought it, and what leaves in
        // the hands of a falling block is spoken for where the block lands.
        carried: List<Site> = emptyList(),
        dropReach: Double = SPAWN_REACH,
        // A block the world overwrites rather than destroys drops nothing to wait for.
        expectsDrops: Boolean = true,
    ) {
        val real = sites.filter { unfiled(it) && !commanded(it.block) }
        val rows = real + carried.filter { unfiled(it) && !commanded(it.block) }
        for (site in rows) shapeFirst(log, site.at, site.block, site.before)
        log.submit(rows.map { row(it, cause, by, timestamp) })
        for (site in rows) readBacks.filed(site.at, site.before.asString, site.after)
        val gone = real.filter { wentAway(it.before.asString, it.after) }
        if (gone.isEmpty()) return
        // A block that moved carries itself to the position it arrived in and drops nothing on the way,
        // and a position that was empty broke nothing: what stands there now arrived, and packing it
        // up as though it had been broken would empty a shulker box a dispenser has just put down.
        for (site in gone) {
            if (site.went != null || !expectsDrops || emptied(site.before.asString)) continue
            dropsOf(site.block, cause, by, packBox(site, by, timestamp), dropReach)
        }
        by.culprit()?.let { actor ->
            for (site in gone) {
                noteRemoval(site.at, site.after, actor)
                attribution.felledBy(site.block, actor)
            }
        }
        // The note saying what a position took over is cleared wherever the block it was written about
        // stopped standing there: left behind, it answers for a block that is not the one there.
        // A position that was empty released nothing, and a form noted there now belongs to whatever
        // was just put in it: a dispenser's shulker box notes its form in the same tick this runs.
        val releasing = gone.filter { !emptied(it.before.asString) }
        if (releasing.isEmpty()) return
        val positions = releasing.map { it.at }
        val remembered = placed.formsAt(positions)
        placed.clearFormsAt(positions)
        val transaction = releasing.flatMap { released(it, remembered[it.at], cause, by, timestamp) }
        if (transaction.isEmpty()) return
        // A position that took another block on is holding that one now, and that is what a break
        // there has to give back, so the note follows the position rather than staying cleared.
        for (posting in transaction) {
            val at = posting.to as? WorldBlock ?: continue
            placed.setFormAt(at.world, at.x, at.y, at.z, posting.form)
        }
        sink(transaction)
    }

    /**
     * A shulker box a piston or an explosion breaks keeps what it held: the contents fall out inside
     * the item. They are filed into the box's own name the way a hand's break files them, or they stay
     * booked to a position that holds nothing and come back as births nobody explains once the box is
     * opened. The name is handed back for the drop to carry.
     */
    private fun packBox(site: Site, by: Attributed?, timestamp: Long): UUID? {
        val box = site.block.getState(false) as? ShulkerBox ?: return null
        val packed = ArrayList<Transfer>()
        val owner = packShulker(owners, site.block, box) { slot, owner, item ->
            val encoded = codec.encode(item)
            packed += Transfer(
                cause = Cause.CONTAINER_BREAK_PACK,
                from = containerAt(site.block, slot),
                to = Nested(owner, slot),
                form = encoded.key.form,
                damage = encoded.key.damage,
                qty = encoded.count,
                timestamp = timestamp,
                actor = by.culprit(),
            )
        }
        if (packed.isNotEmpty()) sink(packed)
        return owner
    }

    // A change worth a row: one that changes something, and one the journal has not already been told
    // about this tick. Two captures reach one position — a piston clears the position it moved a block
    // out of and the physics behind it reads the same air — and the second row would declare as old
    // what the first had just made new.
    private fun unfiled(site: Site): Boolean {
        val before = site.before.asString
        return handMade(before, site.after) && !readBacks.wasFiled(site.at, before, site.after)
    }

    private fun row(
        site: Site,
        cause: Cause,
        by: Attributed?,
        timestamp: Long,
    ) = BlockChange(
        x = site.at.x,
        y = site.at.y,
        z = site.at.z,
        before = site.before.asString,
        after = site.after,
        cause = cause,
        timestamp = timestamp,
        confidence = by?.confidence ?: Confidence.FACT,
        // The row is written whether or not anybody can be named: an unfound culprit is no reason to
        // leave a hole in the history of the position.
        actor = by?.actor,
        payloadBefore = site.payload,
    )

    // What the position owes: handed to the position the block moved to where it moved, written off
    // where it emptied, and exchanged for the block that stands there now where that block has an item
    // form of its own. Refused either way where the position is one half of a block whose form was
    // remembered in the other.
    private fun released(
        site: Site,
        remembered: ByteArray?,
        cause: Cause,
        by: Attributed?,
        timestamp: Long,
    ): List<Transfer> {
        val shell = shellForm(site.before)
        val twoPositions = partnerFace(site.before) != null
        site.went?.let { to ->
            val form = heldForm(remembered, shell, twoPositions) ?: return emptyList()
            return listOf(moved(site.at, to, form, cause, by, timestamp))
        }
        lostForm(remembered, shell, site.after, twoPositions)?.let {
            return listOf(wroteOff(site.at, it, cause, by, timestamp))
        }
        val held = heldForm(remembered, shell, twoPositions) ?: return emptyList()
        val taken = shellForm(site.after) ?: return emptyList()
        // A vine tip that turned into stem under the next one is the same vine: taken over by itself it
        // would be a mutation of nothing, and the name it was put down under has to stay with it.
        if (itemTypeIdOf(held) == itemTypeIdOf(taken)) {
            remembered?.let { placed.setFormAt(site.at.world, site.at.x, site.at.y, site.at.z, it) }
            return emptyList()
        }
        return tookOver(site.at, held, taken, cause, by, timestamp)
    }

    // The two halves of what `Attribution.cleared` does, with the state the position ends up in taken
    // from what was observed rather than derived: fire spreading leaves fire standing there, and a
    // note naming air would break the chain at its first step.
    private fun noteRemoval(at: WorldBlock, after: String, actor: UUID) {
        attribution.removed(at, actor)
        attribution.placed(at, after, actor)
    }

    /**
     * The drops of a block broken by something other than a hand, expected under its position; a couple of
     * ticks on, once they have spawned, the position says which items they were (SPEC-v6 §2.5). A rollback
     * that puts the block back takes those back from whoever picked them up.
     */
    private fun dropsOf(block: Block, cause: Cause, by: Attributed?, boxOwner: UUID? = null, reach: Double = SPAWN_REACH) {
        val at = positionOf(block)
        expectDrops(origins, codec, block, cause, by.culprit(), boxOwner, reach, tag = at)
        if (!plugin.isEnabled) return
        plugin.server.regionScheduler.runDelayed(plugin, block.world, block.x shr 4, block.z shr 4, {
            val drops = origins.droppedFor(at)
            if (drops.isEmpty()) return@runDelayed
            logs.get(block.world.uid)?.submit(
                EntityChange(
                    at.x, at.y, at.z, EntityKind.DROPPED, cause, "minecraft:item", drops.first(),
                    confidence = by?.confidence ?: Confidence.FACT, actor = by.culprit(), drops = drops,
                )
            )
        }, DROPS_SETTLE_TICKS)
    }

    // The read has to happen on the region that owns the block, and this is queued rather than run
    // inline, so it lands at the start of that region's next tick with the tick that raised the event
    // already finished.
    private fun defer(block: Block, before: BlockData, cause: Cause, by: Attributed?, expectsDrops: Boolean = true) {
        // A task queued against a plugin already on its way down is refused outright, and an event can
        // still reach a handler while the server is taking the plugin apart.
        if (!plugin.isEnabled || commanded(block)) return
        val at = positionOf(block)
        // One position raises two physics events in one tick, and a second read-back of it would find
        // the same air the first did and file the disappearance again, in both planes.
        if (!readBacks.claim(at)) return
        logs.get(block.world.uid)?.let { shapeFirst(it, at, block, before) }
        // The time of the event, not of the read: the change happened in the tick that raised it, and
        // a position whose rows are out of order stops answering what stands in it.
        val timestamp = System.currentTimeMillis()
        // The block entity goes with the block, and by the read-back there is nothing left to read it
        // from: a sign that lost its fence would keep its position and lose its text.
        val payload = payloadAt(block)
        // Here and not in the read-back: the items are already in the world by then, and a note that
        // arrives after the spawn it explains is a note nobody can claim.
        if (expectsDrops) dropsOf(block, cause, by)
        // The log is still standing here, and by the read-back it is not: a wall of stripped wood burnt
        // away read as air there, and the leaves it held decayed on nobody.
        by.culprit()?.let { attribution.givingWay(block, it); attribution.felledBy(block, it) }
        plugin.server.regionScheduler.execute(plugin, block.world, block.x shr 4, block.z shr 4) {
            readBacks.done(at)
            val now = block.blockData.asString
            if (!wentAway(before.asString, now)) return@execute
            val log = logs.get(block.world.uid) ?: return@execute
            if (alreadyFiled(log, at, before.asString, now)) return@execute
            val site = Site(at, block, before, now, payload)
            log.submit(row(site, cause, by, timestamp))
            by.culprit()?.let { noteRemoval(at, now, it) }
            // A tick has passed, and a position something has since been put into is no longer this
            // read's to speak for: the note standing there was written by whoever filled it, and
            // clearing it would cost that block its form when it is broken in turn.
            if (!emptied(now)) return@execute
            val remembered = placed.formAt(at.world, at.x, at.y, at.z)
            placed.clearFormAt(at.world, at.x, at.y, at.z)
            released(site, remembered, cause, by, timestamp).takeIf { it.isNotEmpty() }?.let { sink(it) }
        }
    }

    /**
     * A block an explosion takes away also receives a physics update for that very removal, and its
     * own break already wrote the row a moment earlier. Two rows for one change would make the second
     * declare as old what the first had just made new, so the read-back asks the position what its
     * newest row says: one that already runs from this state to that one is this same change, filed
     * by a capture that knew more about it.
     *
     * A capture that filed anything at all at this position within the tick is answer enough on its
     * own, and not only the same change: a piston moving a block into a position a read-back was
     * queued for leaves that read about to write a row from a block that is no longer the one there.
     *
     * A seek per confirmed give-away, which is what keeps it affordable: nothing is asked for a block
     * that survived its physics tick.
     */
    private fun alreadyFiled(log: BlockLog, at: WorldBlock, before: String, after: String): Boolean {
        // Asked here first because a row filed in this same tick is still on its way to the journal.
        if (readBacks.settled(at)) return true
        val row = log.standingAt(at.x, at.y, at.z).row ?: return false
        // That same change, filed a moment ago: one filed an hour before is another change, after which
        // something that writes no row put the block back. A lantern CoreProtect had hung up again fell
        // under the next blast with no row, and the rollback left it down (D122).
        if (System.currentTimeMillis() - row.timestamp > FILED_MILLIS) return false
        return registries.keyOf(RegistryNamespace.BLOCK_STATE, row.stateBefore) == before &&
            registries.keyOf(RegistryNamespace.BLOCK_STATE, row.stateAfter) == after
    }

    /**
     * Who put down the thing that has just gone off. The tracker is a map lookup and covers the
     * ordinary delay between a block being noted and being collected; the journal covers the horizon
     * between a block being placed and being set off a week later, at the price of a seek, and is
     * asked about this one position only — never once per destroyed block of an explosion.
     */
    private fun placerOf(at: WorldBlock, standing: String): Attributed? =
        attribution.placerAt(at, standing) ?: attribution.journalPlacerAt(at, standing)

    // Who stands behind an entity that changed a block: the shooter of an arrow, a trident or a thrown
    // potion, whoever steers a boat, the player a trident's lightning answers to, and otherwise whoever
    // brought the entity into the world.
    private fun behindChange(entity: Entity): Attributed? {
        litBy(entity)?.let { return Attributed(it.uniqueId, Confidence.FACT) }
        (entity as? LightningStrike)?.causingPlayer?.let { return Attributed(it.uniqueId, Confidence.FACT) }
        (entity as? Vehicle)?.passengers?.firstOrNull { it is Player }?.let { return Attributed(it.uniqueId, Confidence.FACT) }
        return entities.summonerOf(firedBy(entity).uniqueId)
    }

    private fun chestPartnerOf(block: Block): Block? {
        val chest = block.blockData as? org.bukkit.block.data.type.Chest ?: return null
        if (chest.type == org.bukkit.block.data.type.Chest.Type.SINGLE) return null
        val craft = block as CraftBlock
        val at = ChestBlock.getConnectedBlockPos(craft.position, craft.blockState)
        return block.world.getBlockAt(at.x, at.y, at.z)
    }

    private fun whoSetOff(source: Entity): Attributed? {
        litBy(source)?.let { return Attributed(it.uniqueId, Confidence.FACT) }
        // Where the dynamite came from, noted when it was primed; a wither nobody lit was still built by
        // somebody, and the explosion it opens with is the first thing it does.
        entities.summonerOf(firedBy(source).uniqueId)?.let { return it }
        // Only a block that stood somewhere can be asked about, and of the entities that explode only
        // dynamite was one. A guess by where it went off, which a blast beside it may have thrown it from.
        if (source.type == EntityType.TNT) {
            placerOf(positionOf(source.location.block), TNT)?.let { return it }
        }
        // The last rung: a creeper goes off at whoever it was after. Led to a wall, that is the one who led
        // it; met by chance, the one it met. Either way only a witness, never rolled back on its own.
        return ((source as? Creeper)?.target as? Player)?.let { Attributed(it.uniqueId, Confidence.NEARBY) }
    }

    private fun shellForm(data: BlockData) = codec.shellOf((data as CraftBlockData).state.block)

    private fun shellForm(state: String) = codec.shellOf(blockBehind(state))

    // A change the server is capturing rather than applying is one it will report again, in full, in
    // the event that closes the capture. Written twice, the second row would declare as old what the
    // first had just made new.
    private fun capturing(block: Block) =
        (block.world as CraftWorld).handle.currentWorldData?.captureBlockStates == true

    internal companion object {
        const val TNT = "minecraft:tnt"
    }
}
