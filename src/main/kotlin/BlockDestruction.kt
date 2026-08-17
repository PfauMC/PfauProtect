package io.pfaumc.pfauprotect

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.BucketItem
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlockContainer
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.PistonType
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.PushReaction
import org.bukkit.ExplosionResult
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.PistonHead
import org.bukkit.block.data.type.TechnicalPiston
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.CraftBlock
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.craftbukkit.inventory.CraftItemType
import org.bukkit.entity.Creeper
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPistonEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.EntityBlockFormEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
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

private fun blockNameOf(state: String) = state.substringBefore('[')

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
    block: Block,
    val before: BlockData,
    val after: String,
    val payload: ByteArray? = payloadAt(block),
    val went: WorldBlock? = null,
)

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
    else -> null
}

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

private val LIQUIDS = setOf("minecraft:water", "minecraft:lava")

private val FIRES = setOf("minecraft:fire", "minecraft:soul_fire")

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
    (data as CraftBlockData).state.pistonPushReaction == PushReaction.DESTROY

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

/**
 * Which half of a fall an entity changing a block is. A block breaking loose leaves behind whatever it
 * was standing in; a block landing puts down the very state it has been carrying, so the two halves are
 * told apart by what the position is about to become rather than by anything about the entity.
 */
internal fun isLanding(carried: String, becomes: String) = carried == becomes

/** Whether what was read back is a different block from the one the event was about. */
internal fun wentAway(before: String, now: String) = blockNameOf(before) != blockNameOf(now)

// The state a position ends up in travels as its string, and only the block behind the string says
// whether anything an item was ever made into is standing there now.
internal fun blockBehind(state: String): NmsBlock =
    BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockNameOf(state)))

private fun payloadAt(block: Block): ByteArray? {
    val level = (block as CraftBlock).level
    return payloadOf(level.getBlockEntity(block.position), level.registryAccess())
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
internal fun wroteOff(
    at: WorldBlock,
    form: ByteArray,
    cause: Cause,
    by: Attributed?,
    timestamp: Long,
) = Transfer(
    cause = cause,
    from = at,
    to = Void,
    form = form,
    damage = null,
    qty = 1,
    timestamp = timestamp,
    actor = by?.actor,
)

/**
 * The item side of a block that changed position rather than disappearing: one transfer naming both
 * ends, which is what lets a walk of the graph follow the block across the move. Written off at the
 * one end and born again at the other, one block would be two, and the position it left would hand its
 * form back a second time when whatever stands there now is broken.
 */
internal fun moved(
    from: WorldBlock,
    to: WorldBlock,
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
    actor = by?.actor,
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
    Transfer(cause, at, Void, held, null, 1, timestamp, Kind.MUTATE, actor = by?.actor),
    Transfer(cause, Void, at, taken, null, 1, timestamp, Kind.MUTATE, actor = by?.actor),
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
    private val placed: PlacedForms,
    private val sink: (List<Transfer>) -> Unit,
) : Listener {

    private val growing = GrowClaims()
    private val readBacks = ReadBacks()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
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
        val by = placerOf(positionOf(at), standing)
            ?: partner?.let { (block, half) -> placerOf(positionOf(block), half.asString) }
        val gone = ArrayList<Site>(2)
        for ((block, was) in listOfNotNull(at to data, partner)) {
            val now = block.blockData.asString
            if (wentAway(was.asString, now)) gone += Site(positionOf(block), block, was, now)
        }
        explode(event.blockList(), cause, by, gone)
    }

    // No row: an ignition puts fire where there was none and takes no block away. It is the root of
    // every fire chain all the same, and without a note here the burning and the spreading that
    // follow have no culprit to carry forward.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) {
        val player = event.player ?: return
        val block = event.block as CraftBlock
        // Over soul sand and soul soil the fire that goes down is soul fire, and a note has to name
        // what is standing there: every reader of one compares by block, so a note calling it plain
        // fire answers nothing and the whole chain off it burns unattributed.
        val lit = BaseFireBlock.getState(block.level, block.position).asBlockData().asString
        attribution.placed(positionOf(block), lit, player.uniqueId)
    }

    // The same for the other chain: emptying a bucket raises no placement, so the water it puts down
    // would otherwise flow away from a position nobody was ever noted at.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        val block = event.block as CraftBlock
        val placed = bucketPlaced(event.bucket, block.blockState) ?: return
        attribution.placed(positionOf(block), placed, event.player.uniqueId)
    }

    /**
     * What a burning block leaves is decided by a roll the server makes after the event: the position
     * becomes either fire of a fresh age or whatever the block was standing in. Both are honest
     * outcomes and neither is knowable here, so the position is read back.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        val block = event.block
        val igniting = event.ignitingBlock
        val by = igniting?.let { attribution.placerAt(positionOf(it), it.blockData.asString) }
        defer(block, block.blockData, Cause.BLK_FIRE_BURN, by)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) {
        val block = event.block
        if (capturing(block)) return
        val after = event.newState.blockData.asString
        // The find is written forward onto this position by the carry itself, so an arbitrarily long
        // chain of fire stays attributed while no note ever has to cover more than one step of it.
        changed(block, block.blockData, after, spreadCause(after), attribution.carriedTo(positionOf(block), after))
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
        val cause = (event as? EntityBlockFormEvent)
            ?.let { entityFormCause(it.entity.type) }
            ?: formCause(before.asString)
        changed(block, before, event.newState.blockData.asString, cause, by = null)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        val block = event.block
        if (capturing(block)) return
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
        changed(block, before, leftBehind(before).asString, Cause.BLK_LEAF_DECAY, by = null)
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
        val by = attribution.carriedTo(positionOf(to), from.blockData.asString)
        if (!liquidDestroys((to as CraftBlock).blockState)) return
        defer(to, to.blockData, Cause.BLK_LIQUID_DESTROY, by)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val entity = event.entity
        // A falling block is the one entity here that moves a block rather than changing one, and both
        // ends of that movement arrive on this same event.
        if (entity is FallingBlock) return fell(event, entity)
        val cause = entityBlockCause(entity.type) ?: return
        val block = event.block
        changed(block, block.blockData, event.blockData.asString, cause, by = null)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) =
        piston(event, event.blocks, Cause.BLK_PISTON_EXTEND, extending = true)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) =
        piston(event, event.blocks, Cause.BLK_PISTON_RETRACT, extending = false)

    /**
     * A flight that ended in anything but a landing: the block was destroyed in the air, fell out of
     * the world, or turned into an item. The position it left really did lose its block then, so what
     * it was holding is written off there rather than handed on. A landing takes the flight itself, so
     * what reaches here is only what nothing else claimed.
     *
     * The item a broken flight leaves behind is born unexplained: the removal is announced before the
     * drop, and by the time the drop exists there is nothing left saying the two belong together.
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
        sink(listOf(wroteOff(flight.from, form, Cause.BLK_FALL_START, flight.by, System.currentTimeMillis())))
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
        growing.claim(blocks) { grew(sites, Cause.BLK_GROW, by, timestamp) }
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
        val state = block.blockState
        if (state.isAir || state.canSurvive(block.level, block.position)) return
        // The cause dictionary has no entry of its own for a block that could no longer stand where it
        // stood, and this is the one that says the world took the block away by its own rules.
        defer(block, block.blockData, Cause.BLK_FADE, attribution.supportRemoverAt(positionOf(block)))
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
        defer(block, block.blockData, Cause.BLK_FADE, attribution.supportRemoverAt(positionOf(block)))
    }

    // Everything an explosion took away, plus the positions the exploding block itself vacated before
    // the event was raised. One submit, so every position of one explosion shares an event id.
    private fun explode(blocks: List<Block>, cause: Cause, by: Attributed?, extra: List<Site>) {
        val hit = withOtherHalves(blocks)
        val world = hit.firstOrNull()?.world?.uid ?: extra.firstOrNull()?.at?.world ?: return
        val log = logs.get(world) ?: return
        // An explosion writes plain air over every position it clears, whatever the block was standing
        // in, so the after side is not derived from the fluid the way a break's is.
        file(log, hit.map { Site(positionOf(it), it, it.blockData, AIR) } + extra, cause, by)
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
     * Nobody is named. The ladder answers who put a block somewhere and who took a support away;
     * neither is who fired this piston, and the player who last touched it is not behind every block
     * the redstone around it shifts afterwards.
     */
    private fun piston(event: BlockPistonEvent, moving: List<Block>, cause: Cause, extending: Boolean) {
        val base = event.block
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
        file(log, emptied, cause, by = null, carried = filled)
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
        if (!isLanding(carried, becomes)) {
            // Who took away what was holding it up, worked out here and kept under the entity: by the
            // time it lands, the position it left holds whatever has moved in behind it.
            val by = attribution.supportRemoverAt(at)
            attribution.tookOff(entity.uniqueId, Falling(at, carried, by, takeHeldForm(at, carried)))
            val site = Site(at, block, block.blockData, becomes)
            file(log, emptyList(), Cause.BLK_FALL_START, by, timestamp, carried = listOf(site))
            // A column comes down one block at a time, and each take-off is what the block above it
            // finds: without a note here the chain would be attributed at its first step only.
            by?.actor?.let { noteRemoval(at, becomes, it) }
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
    ) {
        val real = sites.filter { unfiled(it) }
        val rows = real + carried.filter { unfiled(it) }
        log.submit(rows.map { row(it, cause, by, timestamp) })
        for (site in rows) readBacks.filed(site.at, site.before.asString, site.after)
        val gone = real.filter { wentAway(it.before.asString, it.after) }
        if (gone.isEmpty()) return
        by?.actor?.let { actor -> for (site in gone) noteRemoval(site.at, site.after, actor) }
        // The note saying what a position took over is cleared wherever the block it was written about
        // stopped standing there: left behind, it answers for a block that is not the one there.
        val positions = gone.map { it.at }
        val remembered = placed.formsAt(positions)
        placed.clearFormsAt(positions)
        val transaction = gone.flatMap { released(it, remembered[it.at], cause, by, timestamp) }
        if (transaction.isEmpty()) return
        // A position that took another block on is holding that one now, and that is what a break
        // there has to give back, so the note follows the position rather than staying cleared.
        for (posting in transaction) {
            val at = posting.to as? WorldBlock ?: continue
            placed.setFormAt(at.world, at.x, at.y, at.z, posting.form)
        }
        sink(transaction)
    }

    // A change worth a row: one that changes something, and one the journal has not already been told
    // about this tick. Two captures reach one position — a piston clears the position it moved a block
    // out of and the physics behind it reads the same air — and the second row would declare as old
    // what the first had just made new.
    private fun unfiled(site: Site): Boolean {
        val before = site.before.asString
        return before != site.after && !readBacks.wasFiled(site.at, before, site.after)
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
        return tookOver(site.at, held, taken, cause, by, timestamp)
    }

    // The two halves of what `Attribution.cleared` does, with the state the position ends up in taken
    // from what was observed rather than derived: fire spreading leaves fire standing there, and a
    // note naming air would break the chain at its first step.
    private fun noteRemoval(at: WorldBlock, after: String, actor: UUID) {
        attribution.removed(at, actor)
        attribution.placed(at, after, actor)
    }

    // The read has to happen on the region that owns the block, and this is queued rather than run
    // inline, so it lands at the start of that region's next tick with the tick that raised the event
    // already finished.
    private fun defer(block: Block, before: BlockData, cause: Cause, by: Attributed?) {
        // A task queued against a plugin already on its way down is refused outright, and an event can
        // still reach a handler while the server is taking the plugin apart.
        if (!plugin.isEnabled) return
        val at = positionOf(block)
        // One position raises two physics events in one tick, and a second read-back of it would find
        // the same air the first did and file the disappearance again, in both planes.
        if (!readBacks.claim(at)) return
        // The time of the event, not of the read: the change happened in the tick that raised it, and
        // a position whose rows are out of order stops answering what stands in it.
        val timestamp = System.currentTimeMillis()
        // The block entity goes with the block, and by the read-back there is nothing left to read it
        // from: a sign that lost its fence would keep its position and lose its text.
        val payload = payloadAt(block)
        plugin.server.regionScheduler.execute(plugin, block.world, block.x shr 4, block.z shr 4) {
            readBacks.done(at)
            val now = block.blockData.asString
            if (!wentAway(before.asString, now)) return@execute
            val log = logs.get(block.world.uid) ?: return@execute
            if (alreadyFiled(log, at, before.asString, now)) return@execute
            val site = Site(at, block, before, now, payload)
            log.submit(listOf(row(site, cause, by, timestamp)))
            by?.actor?.let { noteRemoval(at, now, it) }
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

    private fun whoSetOff(source: Entity): Attributed? {
        litBy(source)?.let { return Attributed(it.uniqueId, Confidence.FACT) }
        // Only a block that stood somewhere can be asked about, and of the entities that explode only
        // dynamite was one. A creeper nobody lit and a crystal are answered with nobody.
        if (source.type != EntityType.TNT) return null
        return placerOf(positionOf(source.location.block), TNT)
    }

    // A block that no longer stands there leaves whatever it was standing in, which for anything dry
    // is air. This is `Level.removeBlock`'s own rule, the same one a break follows.
    private fun leftBehind(data: BlockData): BlockData =
        (data as CraftBlockData).state.fluidState.createLegacyBlock().asBlockData()

    // What the block was made of, which a block with no item form of its own — fire, a liquid, a
    // portal — answers with nothing at all.
    private fun shellForm(block: NmsBlock): ByteArray? {
        val stack = NmsItemStack(block.asItem())
        return if (stack.isEmpty) null else codec.encode(stack).form
    }

    private fun shellForm(data: BlockData) = shellForm((data as CraftBlockData).state.block)

    private fun shellForm(state: String) = shellForm(blockBehind(state))

    // A change the server is capturing rather than applying is one it will report again, in full, in
    // the event that closes the capture. Written twice, the second row would declare as old what the
    // first had just made new.
    private fun capturing(block: Block) =
        (block.world as CraftWorld).handle.currentWorldData?.captureBlockStates == true

    private companion object {
        const val TNT = "minecraft:tnt"
    }
}
