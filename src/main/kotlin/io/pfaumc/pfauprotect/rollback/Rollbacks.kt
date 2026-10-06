package io.pfaumc.pfauprotect.rollback

import io.pfaumc.pfauprotect.say
import ca.spottedleaf.concurrentutil.util.Priority
import io.pfaumc.pfauprotect.capture.block.Difference
import io.pfaumc.pfauprotect.capture.block.ranFrom
import io.pfaumc.pfauprotect.capture.block.standingAt
import io.pfaumc.pfauprotect.capture.block.Standing
import io.pfaumc.pfauprotect.check.emptied
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.command.LookupQuery
import io.pfaumc.pfauprotect.command.LookupTarget
import io.pfaumc.pfauprotect.command.Lookups
import io.pfaumc.pfauprotect.command.RowFilter
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.PostingRef
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.PlacedForms
import io.pfaumc.pfauprotect.storage.RocksItemLog
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager
import net.minecraft.util.ProblemReporter
import net.minecraft.world.Clearable
import io.pfaumc.pfauprotect.capture.entity.VOLATILE
import io.pfaumc.pfauprotect.capture.entity.nbtOf
import io.pfaumc.pfauprotect.capture.entity.snapshotOf
import io.pfaumc.pfauprotect.capture.entity.changedBetween
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.storage.EntityChange
import io.pfaumc.pfauprotect.storage.EntityRow
import net.minecraft.world.entity.EntityProcessor
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntitySpawnRequest
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.tags.BlockTags
import org.bukkit.Location
import net.minecraft.world.phys.AABB
import org.bukkit.World
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.CreatureSpawnEvent
import net.minecraft.world.entity.LivingEntity as NmsLivingEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.CampfireBlockEntity
import net.minecraft.world.level.block.entity.LecternBlockEntity
import net.minecraft.world.level.storage.TagValueInput
import net.kyori.adventure.text.Component
import io.pfaumc.pfauprotect.Texts
import io.pfaumc.pfauprotect.Ui
import io.pfaumc.pfauprotect.tr
import io.papermc.paper.math.Position
import org.bukkit.block.data.BlockData
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import kotlin.math.abs
import net.minecraft.world.Container as NmsContainer
import net.minecraft.world.item.ItemStack as NmsItemStack

// The flags vanilla `/fill` places with: the client is told, and a container taken away takes what it
// held with it instead of spilling it on the ground. The neighbours are told once everything is in.
private const val PLACE_FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS


// How long a preview waits for `apply`. The world goes on changing under it, so apply reads it all
// again; this only bounds how stale the question can be.
private const val PENDING_MILLIS = 5 * 60_000L

// How many blocks of a preview its player is shown at most.
private const val GHOST_LIMIT = 50_000

// A chunk task that never runs — its world unloaded under it — would hold the one rollback slot for
// good. Past this a running rollback is taken as lost and the slot handed on.
private const val STALE_MILLIS = 10 * 60_000L

// How long the chunks a rollback holds get to load before it gives up on them.
private const val LOAD_MILLIS = 60_000L

// How long a rollback keeps its chunks after it is done, for the taking back queued behind it.
private const val HOLD_LINGER_TICKS = 200L

// How far from a block it put back a rollback puts fire out.
private const val DOUSE_REACH = 2

// Canvas loads an unloaded chunk for `canvas$loadOrRunAtChunksAsync` and then never calls back when no
// player keeps it loaded: the rollback waited for good and answered nothing. So a rollback holds its
// chunks itself, with a ticket of its own, each run under its own identifier so that two runs over the
// same chunk never take each other's hold away. Neither saved with the world nor timed out.
private val ROLLBACK_HOLD = TicketType<Long>(0L, TicketType.FLAG_LOADING or TicketType.FLAG_SIMULATION).apply {
    `moonrise$setIdentifierComparator`(Comparator.naturalOrder())
}
private val HOLDS = AtomicLong()

/**
 * The chunks one rollback works in, held loaded until it is done: each worked chunk and its neighbours,
 * which a block on its edge tells it changed.
 */
private class ChunkHold(private val work: List<Pair<ServerLevel, ChunkPlan>>) {
    private val id = HOLDS.incrementAndGet()
    // One step under full at the worked chunk is full one chunk around it.
    private val level = ChunkHolderManager.FULL_LOADED_TICKET_LEVEL - 1

    /** False when the chunks did not load in time; the hold is released then. */
    fun take(): Boolean {
        for ((world, chunk) in work) {
            val holders = world.`moonrise$getChunkTaskScheduler`().chunkHolderManager
            holders.addTicketAtLevel(ROLLBACK_HOLD, chunk.chunkX, chunk.chunkZ, level, id)
            holders.processTicketUpdates(chunk.chunkX, chunk.chunkZ)
        }
        val until = System.currentTimeMillis() + LOAD_MILLIS
        while (!work.all { (world, c) -> world.`moonrise$areChunksLoaded`(c.chunkX - 1, c.chunkZ - 1, c.chunkX + 1, c.chunkZ + 1) }) {
            if (System.currentTimeMillis() > until) {
                release()
                return false
            }
            Thread.sleep(50)
        }
        return true
    }

    fun release() {
        for ((world, chunk) in work) {
            world.`moonrise$getChunkTaskScheduler`().chunkHolderManager
                .removeTicketAtLevel(ROLLBACK_HOLD, chunk.chunkX, chunk.chunkZ, level, id)
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * Something a rollback gave back, and where it had gone: `qty` above zero was put back and is followed
 * from `lead` to whoever holds it now; below zero it was taken back out of what `lead` had put in, which
 * is that much of the lead's debt already settled.
 */
class Trace(val lead: Holder, val formId: Long, val qty: Int)

/** A block as a preview would put it, shown to the player who previews until it is applied or dropped. */
class Ghost(val world: UUID, val x: Int, val y: Int, val z: Int, val data: BlockData)

/** Work on a live entity, done on its own thread once every chunk has had its turn. */
class EntityJob(val entity: Entity, val run: (Tally) -> Unit)

/** What a rollback did, or in a preview would do, counted over every chunk it touched. */
class Tally {
    // Positions that stood as the rolled-back players left them when the window opened; set on the total.
    var leftBefore = 0
    // When the window opened; set on the total, for following what a thief put away since.
    var since = 0L
    var changed = 0
    var unchanged = 0
    var conflicts = 0
    var slots = 0
    var missed = 0
    var failed = 0
    // Positions of chunks a /pp cancel reached before they were put back.
    var skipped = 0
    var entitiesBack = 0
    var entitiesTaken = 0
    var entitiesReverted = 0
    var entitiesAlready = 0
    var entitiesGoneSince = 0

    // What a rollback gave back, for taking it back from whoever carried it off: positions whose block
    // came back, what moved in slots, the item entities that fell out of what came back, and the breaks
    // the plan read there.
    val restored = ArrayList<WorldBlock>()
    // Every position whose block the rollback changes: a block a thief set there from what they owe goes
    // with it, and is not owed again.
    val undone = ArrayList<WorldBlock>()
    val traces = ArrayList<Trace>()
    val piles = ArrayList<UUID>()
    val breaks = ArrayList<LedgerEntry>()
    val jobs = ArrayList<EntityJob>()

    // Players killed by those the filter names, whose drops go back to them.
    val deaths = ArrayList<EntityRow>()

    // Mobs brought back out of a bucket, whose bucket goes back to what it was.
    val buckets = ArrayList<Bucketed>()

    // What a preview would put back, for the previewing player to see.
    val ghosts = ArrayList<Ghost>()

    @Synchronized
    fun add(other: Tally) {
        changed += other.changed
        unchanged += other.unchanged
        conflicts += other.conflicts
        slots += other.slots
        missed += other.missed
        failed += other.failed
        skipped += other.skipped
        entitiesBack += other.entitiesBack
        entitiesTaken += other.entitiesTaken
        entitiesReverted += other.entitiesReverted
        entitiesAlready += other.entitiesAlready
        entitiesGoneSince += other.entitiesGoneSince
        restored += other.restored
        undone += other.undone
        traces += other.traces
        piles += other.piles
        breaks += other.breaks
        jobs += other.jobs
        deaths += other.deaths
        buckets += other.buckets
        ghosts += other.ghosts
    }
}

// How many chunks of a rollback are worked at once.
private const val CONCURRENT_CHUNKS = 8

// How far around an event's own position and either side of its time a rollback of it reads.
private const val EVENT_RADIUS = 8
private const val EVENT_MILLIS = 1_000L

// What a dead entity carries that a living one must not: it would die again on its first tick. And how
// it first came into the world: a stand summoned by a command would read as summoned again, and have what
// it holds booked to it a second time on top of what the rollback gives back.
private val DYING = listOf(
    "DeathTime", "HurtTime", "HurtByTimestamp", "Fire", "fire", "FallDistance", "fall_distance", "Paper.SpawnReason",
    // Snapshots from before passengers were left out of them.
    "Passengers",
)

// How long a campfire cooks what is put back on it: what every vanilla campfire recipe takes.
private const val CAMPFIRE_COOK_TICKS = 600

/**
 * A block's item slots, as a rollback puts things into them and takes them out. A container is its own;
 * a lectern keeps its book behind a container of its own, which sets the book and the lectern's state
 * together; a campfire keeps its food in a list no container wraps, one item a slot.
 */
internal interface Slots {
    val size: Int
    fun get(slot: Int): NmsItemStack
    fun set(slot: Int, stack: NmsItemStack)
    fun canPlace(slot: Int, stack: NmsItemStack): Boolean
    fun room(stack: NmsItemStack): Int
    fun changed()
}

internal class ContainerSlots(private val container: NmsContainer) : Slots {
    override val size: Int get() = container.containerSize
    override fun get(slot: Int): NmsItemStack = container.getItem(slot)
    override fun set(slot: Int, stack: NmsItemStack) = container.setItem(slot, stack)
    override fun canPlace(slot: Int, stack: NmsItemStack) = container.canPlaceItem(slot, stack)
    override fun room(stack: NmsItemStack) = minOf(stack.maxStackSize, container.getMaxStackSize(stack))
    override fun changed() = container.setChanged()
}

internal class CampfireSlots(private val campfire: CampfireBlockEntity) : Slots {
    override val size: Int get() = campfire.items.size
    override fun get(slot: Int): NmsItemStack = campfire.items[slot]
    override fun set(slot: Int, stack: NmsItemStack) {
        campfire.items[slot] = stack
        campfire.cookingProgress[slot] = 0
        campfire.cookingTime[slot] = CAMPFIRE_COOK_TICKS
    }
    override fun canPlace(slot: Int, stack: NmsItemStack) = true
    override fun room(stack: NmsItemStack) = 1
    override fun changed() {
        campfire.setChanged()
        campfire.level?.sendBlockUpdated(campfire.blockPos, campfire.blockState, campfire.blockState, Block.UPDATE_ALL)
    }
}

internal fun slotsOf(entity: BlockEntity?): Slots? = when (entity) {
    is NmsContainer -> ContainerSlots(entity)
    is LecternBlockEntity -> ContainerSlots(entity.bookAccess)
    is CampfireBlockEntity -> CampfireSlots(entity)
    else -> null
}

/**
 * Puts back (`qty` above zero) or takes out (below zero) one slot posting: in its own slot first, then
 * wherever else in the same block the item fits or is found. Returns how much of it moved; the rest had
 * no room or was not there.
 */
internal fun putBack(slots: Slots, slot: Int, template: NmsItemStack, qty: Int, same: (NmsItemStack) -> Boolean): Int {
    val order = listOf(slot).filter { it < slots.size } + (0 until slots.size).filter { it != slot }
    var left = abs(qty)
    for (i in order) {
        if (left == 0) break
        val here = slots.get(i)
        if (qty > 0) {
            val room = slots.room(template)
            if (here.isEmpty) {
                // The slot the row names held this item once; any other has to take it.
                if (i != slot && !slots.canPlace(i, template)) continue
                val n = minOf(left, room)
                slots.set(i, template.copyWithCount(n))
                left -= n
            } else if (NmsItemStack.isSameItemSameComponents(here, template)) {
                val n = minOf(left, room - here.count)
                if (n <= 0) continue
                slots.set(i, here.copyWithCount(here.count + n))
                left -= n
            }
        } else {
            if (here.isEmpty || !same(here)) continue
            val n = minOf(left, here.count)
            slots.set(i, if (n == here.count) NmsItemStack.EMPTY else here.copyWithCount(here.count - n))
            left -= n
        }
    }
    slots.changed()
    return abs(qty) - left
}

/**
 * One chunk of a rollback, on the thread of the region that owns it. Nothing here touches the ledger:
 * the plan was read before, and the rows go to the writers' queues.
 */
class ChunkRollback(
    private val plugin: Plugin,
    private val codec: ItemFormCodec,
    private val logs: BlockLogs,
    private val placed: PlacedForms,
    private val sink: (List<Transfer>) -> Unit,
    // The ledger's forms, from memory: nothing here may read the database.
    private val formOf: (Long) -> ByteArray? = { null },
    // Tells the entity capture that a removal is the rollback's own and written by it.
    private val forget: (UUID) -> Unit = {},
) {
    fun run(level: ServerLevel, chunk: ChunkPlan, actor: UUID?, apply: Boolean): Tally {
        val tally = Tally()
        val world = level.world.uid
        val positions = chunk.positions
        val spots = positions.map { BlockPos(it.at.x, it.at.y, it.at.z) }
        val before = spots.map { standingAt(level, it, codec) }
        val touched = BooleanArray(positions.size)
        val settles = positions.mapIndexed { i, plan -> settle(before[i].state.asBlockData().asString, plan.steps) }
        val targets = settles.map { settled -> settled.back?.before?.let { if (passing(it)) "minecraft:air" else it } }
        // What a source being taken away had run into goes with it, before anything is put back: a plank
        // put back in the middle of the flow would cut the walk off, and lava left running sets fire to
        // the house the rollback is putting back.
        val drained = if (!apply) emptyList() else positions.indices.flatMap { i ->
            val was = before[i].state
            val removed = was.block is LiquidBlock && was.fluidState.isSource &&
                targets[i].let { it != null && it != was.asBlockData().asString }
            if (!removed) emptyList()
            else ranFrom(spots[i], level::getBlockState) { Bukkit.isOwnedByCurrentRegion(level.world, it.x shr 4, it.z shr 4) }
        }
        for ((pos, _) in drained) level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACE_FLAGS)
        positions.forEachIndexed { i, plan ->
            val was = before[i]
            val standing = was.state.asBlockData().asString
            val settled = settles[i]
            if (settled.conflict) tally.conflicts++
            val back = settled.back
            val target = targets[i]
            val reshaped = target != null && target != standing
            // A container standing where it stood keeps what it holds: its contents are the slot
            // postings' business, and its tag would hand back what they already give back.
            val retagged = !reshaped && back?.payloadBefore?.contentEquals(was.payload) == false &&
                slotsOf(level.getBlockEntity(spots[i])) == null
            if (!reshaped && !retagged) {
                // One stopped by a later change is counted there, not again as already as it was.
                if (plan.steps.isNotEmpty() && !settled.conflict) tally.unchanged++
                return@forEachIndexed
            }
            tally.changed++
            if (reshaped) tally.undone += plan.at
            // A block back where one stands again is what lets the drops of its break be taken back.
            val returnsBlock = reshaped && !emptied(target!!)
            if (!apply) {
                if (returnsBlock) restored(tally, plan)
                if (reshaped) tally.ghosts += Ghost(world, spots[i].x, spots[i].y, spots[i].z, Bukkit.createBlockData(target!!))
                return@forEachIndexed
            }
            try {
                put(level, spots[i], if (reshaped) target else null, back?.payloadBefore)
                touched[i] = true
                if (returnsBlock) restored(tally, plan)
            } catch (failure: Exception) {
                tally.failed++
                plugin.logger.log(Level.WARNING, "a rollback could not put back the block at ${spots[i]}", failure)
            }
        }
        if (apply) lift(level, spots.filterIndexed { i, _ -> touched[i] })
        // Around every position of the plan, not only the ones put back: fire on a plank the griefer's lava had
        // not yet burnt stood next to nothing that came back, and burnt the house again (D80).
        val doused = if (!apply) emptyList() else douse(level, spots, spots.toSet())
        // After the blocks, so a chest that came back is there to take its contents.
        val givenBack = ArrayList<PostingRef>()
        positions.forEachIndexed { i, plan ->
            for (refill in plan.refills) {
                tally.slots++
                if (!apply) {
                    tally.traces += Trace(refill.lead, refill.formId, refill.qty)
                    continue
                }
                val container = slotsOf(level.getBlockEntity(BlockPos(refill.at.x, refill.at.y, refill.at.z)))
                // What arrived in a container this rollback took away went with it, and the reading
                // below writes it off: that is this posting given back, not one that found nothing.
                // What left it went somewhere whose own posting is undone there: a hopper the
                // griefer set under a chest is a stop on the way, and both ends of the stop are done.
                if (container == null && touched[i]) {
                    givenBack += refill.posting
                    if (refill.qty < 0) tally.traces += Trace(refill.lead, refill.formId, refill.qty)
                    continue
                }
                val moved = if (container == null) 0 else putBack(
                    container, refill.at.slot, codec.decode(refill.form, 1, refill.damage), refill.qty,
                ) { codec.encode(it).form.contentEquals(refill.form) }
                if (moved < abs(refill.qty)) tally.missed++
                if (moved > 0) givenBack += refill.posting
                if (moved > 0) tally.traces += Trace(refill.lead, refill.formId, if (refill.qty > 0) moved else -moved)
            }
        }
        // After the blocks, so a frame comes back to a wall that is there again.
        for (plan in positions) for (entity in plan.entities) entity(level, entity, actor, apply, tally)
        // What the positions that came back dropped when they were broken.
        for (plan in positions) if (plan.at in tally.restored) tally.piles += plan.dropped
        if (!apply) return tally
        val difference = Difference(world, Cause.ROLLBACK, Cause.ROLLBACK, Cause.ROLLBACK, Kind.TRANSFER, actor, System.currentTimeMillis())
        positions.forEachIndexed { i, plan ->
            difference.add(plan.at.x, plan.at.y, plan.at.z, before[i], standingAt(level, spots[i], codec), blockRow = touched[i])
        }
        for ((pos, was) in doused) difference.add(pos.x, pos.y, pos.z, was, standingAt(level, pos, codec), blockRow = true)
        if (difference.rows.isNotEmpty()) logs.get(world)?.submit(difference.rows)
        if (difference.moved) sink(difference.transfers(givenBack))
        difference.writeOff(plugin, placed, sink)
        // Last, and after the rows: what the neighbours do now is theirs, and the capture files it.
        for (i in positions.indices) if (touched[i]) level.updateNeighboursOnBlockSet(spots[i], before[i].state)
        for ((pos, was) in drained) level.updateNeighboursOnBlockSet(pos, was)
        for ((pos, was) in doused) level.updateNeighboursOnBlockSet(pos, was.state)
        return tally
    }

    /**
     * Fire within two blocks of the positions the rollback reads, put out. A house rolled back while it still
     * burns caught again from the fire that had spread between the reading and the putting back, or had
     * jumped where no row of the window reached, and a second rollback found it burnt anew. Fire that
     * stands on a block meant to burn for ever is somebody's hearth and stays.
     */
    private fun douse(level: ServerLevel, back: List<BlockPos>, planned: Set<BlockPos>): List<Pair<BlockPos, Standing>> {
        val out = LinkedHashMap<BlockPos, Standing>()
        for (spot in back) for (pos in BlockPos.betweenClosed(spot.offset(-DOUSE_REACH, -DOUSE_REACH, -DOUSE_REACH), spot.offset(DOUSE_REACH, DOUSE_REACH, DOUSE_REACH))) {
            if (pos in planned || pos in out) continue
            if (!Bukkit.isOwnedByCurrentRegion(level.world, pos.x shr 4, pos.z shr 4)) continue
            val state = level.getBlockState(pos)
            if (state.block !is BaseFireBlock) continue
            val below = level.getBlockState(pos.below())
            if (below.`is`(BlockTags.INFINIBURN_OVERWORLD) || below.`is`(BlockTags.INFINIBURN_NETHER) || below.`is`(BlockTags.INFINIBURN_END)) continue
            val at = pos.immutable()
            out[at] = standingAt(level, at, codec)
            level.setBlock(at, Blocks.AIR.defaultBlockState(), PLACE_FLAGS)
        }
        return out.toList()
    }

    /**
     * One entity, by what its oldest row in the window says it was before (SPEC-v7 §11): brought back if
     * it went, taken away if a player brought it in, changed back or brought back to where it stood. One
     * that is already as it should be is left alone, so a second rollback does nothing twice. A live
     * entity may stand in another region by now, so what is done to it waits for its own thread.
     */
    private fun entity(level: ServerLevel, plan: EntityPlan, actor: UUID?, apply: Boolean, tally: Tally) {
        val alive = Bukkit.getEntity(plan.uuid)?.takeIf { it.isValid }
        when (plan.oldest.kind) {
            EntityKind.CREATED -> {
                if (alive == null) return run { tally.entitiesAlready++ }
                tally.entitiesTaken++
                if (apply) tally.jobs += EntityJob(alive) { taken -> takeAway(alive, plan, actor, taken) }
            }
            EntityKind.REMOVED -> {
                if (alive != null) return run { tally.entitiesAlready++ }
                tally.entitiesBack++
                plan.bucket?.let { tally.buckets += it }
                if (apply) bringBack(level, plan, actor, tally)
            }
            else -> {
                // Changed or led away and then killed or broken in the same window: brought back as it
                // was before the first change.
                if (alive == null && plan.removed) {
                    tally.entitiesBack++
                    if (apply) bringBack(level, plan, actor, tally)
                    return
                }
                if (alive == null) return run { tally.entitiesGoneSince++ }
                tally.entitiesReverted++
                // Whether it already is what it was can only be read on its own thread, so the preview asks
                // there too: a second rollback counted every one as to change back and wrote a row for it.
                tally.jobs += EntityJob(alive) { reverted ->
                    if (asItWas(alive, plan, level.world)) {
                        synchronized(reverted) {
                            reverted.entitiesReverted--
                            reverted.entitiesAlready++
                        }
                    } else {
                        synchronized(reverted) { reverted.piles += plan.drops }
                        if (apply) revert(alive, plan, actor, reverted, level.world)
                    }
                }
            }
        }
    }

    // On the region that owns the place it went from.
    private fun bringBack(level: ServerLevel, plan: EntityPlan, actor: UUID?, tally: Tally) {
        val tag = nbtOf(plan.before!!)
        for (key in DYING) tag.remove(key)
        val entity = EntityType.loadEntityRecursive(tag, level, EntitySpawnRequest(EntitySpawnReason.LOAD, true), EntityProcessor.NOP)
        (entity as? NmsLivingEntity)?.let { it.health = it.maxHealth }
        if (entity == null || !level.tryAddFreshEntityWithPassengers(entity, CreatureSpawnEvent.SpawnReason.CUSTOM)) {
            tally.failed++
            plugin.logger.warning("a rollback could not bring back ${plan.type} ${plan.uuid}")
            return
        }
        slotsBack(plan, actor, tally)
        tally.piles += plan.drops
        filed(plan, EntityKind.CREATED, actor)
    }

    // On the entity's own thread. Its slots go with it, and the capture writes them off as it does for
    // any entity taken out of the world.
    private fun takeAway(entity: Entity, plan: EntityPlan, actor: UUID?, tally: Tally) {
        if (!entity.isValid) return
        val before = snapshotOf((entity as CraftEntity).handle)
        forget(entity.uniqueId)
        entity.remove()
        filed(plan, EntityKind.REMOVED, actor, before = before)
    }

    // On the entity's own thread: what a hand changed goes back, and a mob led away goes back to where it
    // stood, in the world it stood in: one taken through a portal is in another by now. What changes by
    // itself stays as it is now.
    private fun revert(entity: Entity, plan: EntityPlan, actor: UUID?, tally: Tally, home: World) {
        if (!entity.isValid) return
        val handle = (entity as CraftEntity).handle
        val now = snapshotOf(handle) ?: return
        val before = nbtOf(plan.before!!)
        if (plan.oldest.kind == EntityKind.MOVED) {
            val pos = before.getListOrEmpty("Pos")
            entity.leaveVehicle()
            // The one who rode it off gets off here; a teleport would carry them back along with it.
            entity.eject()
            (entity as? LivingEntity)?.setLeashHolder(null)
            if (pos.size == 3) {
                entity.teleportAsync(Location(home, pos.getDoubleOr(0, 0.0), pos.getDoubleOr(1, 0.0), pos.getDoubleOr(2, 0.0)))
            }
            filed(plan, EntityKind.MOVED, actor, before = now)
            return
        }
        val merged = nbtOf(now)
        for (key in before.keySet()) if (key !in VOLATILE) merged.put(key, before.get(key)!!.copy())
        for (key in merged.keySet().toList()) if (key !in VOLATILE && !before.contains(key)) merged.remove(key)
        handle.load(TagValueInput.create(ProblemReporter.DISCARDING, handle.registryAccess(), merged))
        slotsBack(plan, actor, tally)
        filed(plan, EntityKind.CHANGED, actor, before = now, after = snapshotOf(handle))
    }

    // On the entity's own thread: led back where it stood and let go, or every key a hand can change as it
    // was.
    private fun asItWas(entity: Entity, plan: EntityPlan, home: World): Boolean {
        if (!entity.isValid) return true
        val before = nbtOf(plan.before!!)
        if (plan.oldest.kind == EntityKind.MOVED) {
            val pos = before.getListOrEmpty("Pos")
            if (pos.size != 3 || entity.world != home || entity.isInsideVehicle || entity.passengers.isNotEmpty() || (entity as? LivingEntity)?.isLeashed == true) return false
            val at = entity.location
            return abs(at.x - pos.getDoubleOr(0, 0.0)) < 1 && abs(at.y - pos.getDoubleOr(1, 0.0)) < 1 && abs(at.z - pos.getDoubleOr(2, 0.0)) < 1
        }
        val now = snapshotOf((entity as CraftEntity).handle) ?: return true
        return !changedBetween(plan.before, now)
    }

    // What the entity's own slots did after the moment it went back to: the NBT already holds them as
    // they were, so here they are only written, and what left is followed to whoever has it.
    private fun slotsBack(plan: EntityPlan, actor: UUID?, tally: Tally) {
        val now = System.currentTimeMillis()
        val moves = plan.slots.mapNotNull { posting ->
            val form = formOf(posting.itemFormId) ?: return@mapNotNull null
            val qty = -posting.qty
            tally.traces += Trace(posting.counterparty, posting.itemFormId, qty)
            Transfer(
                Cause.ROLLBACK, if (qty > 0) Void else posting.holder, if (qty > 0) posting.holder else Void,
                form, posting.damage, kotlin.math.abs(qty), now, actor = actor, reverts = listOf(posting.ref),
            )
        }
        if (moves.isNotEmpty()) sink(moves)
    }

    private fun filed(plan: EntityPlan, kind: EntityKind, actor: UUID?, before: ByteArray? = null, after: ByteArray? = null) {
        logs.get(plan.at.world)?.submit(
            listOf(EntityChange(plan.at.x, plan.at.y, plan.at.z, kind, Cause.ROLLBACK, plan.type, plan.uuid, actor = actor, before = before, after = after))
        )
    }

    private fun restored(tally: Tally, plan: PositionPlan) {
        tally.restored += plan.at
        tally.breaks += plan.breaks
    }

    /**
     * A player standing where a wall came back is inside it and chokes: lifted to the first space above
     * that holds them. Only players of this region; a mob in a restored wall is pushed out by the world.
     */
    private fun lift(level: ServerLevel, put: List<BlockPos>) {
        if (put.isEmpty()) return
        // The players standing over this chunk's positions, found in its own sections: the world's list of
        // players is every region's.
        val around = AABB(put.first()).let { first -> put.fold(first) { box, pos -> box.minmax(AABB(pos)) } }.inflate(1.0)
        for (player in level.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer::class.java, around)) {
            val box = player.boundingBox
            if (put.none { box.intersects(AABB(it)) } || level.noCollision(player, box)) continue
            var up = 1.0
            while (box.minY + up < level.maxY && !level.noCollision(player, box.move(0.0, up, 0.0))) up++
            val at = player.bukkitEntity.location
            player.bukkitEntity.teleportAsync(at.clone().add(0.0, kotlin.math.floor(box.minY + up) - at.y, 0.0))
        }
    }

    private fun put(level: ServerLevel, pos: BlockPos, state: String?, payload: ByteArray?) {
        if (state != null) level.setBlock(pos, (Bukkit.createBlockData(state) as CraftBlockData).state, PLACE_FLAGS)
        if (payload == null) return
        val entity = level.getBlockEntity(pos) ?: return
        val tag = NbtIo.read(DataInputStream(ByteArrayInputStream(payload)))
        entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag))
        // The tag still holds what the block held when the row was written, and the slot postings give
        // that back; loading it as well would hand everything out twice.
        (entity as? Clearable)?.clearContent()
        entity.setChanged()
        level.chunkSource.blockChanged(pos)
    }
}

/**
 * `/pp rollback`, `apply` and `cancel`. A rollback is previewed first and applied on a second word; the
 * preview is the same reading run against the world without touching it, and apply reads it all again.
 */
class Rollbacks(
    private val plugin: Plugin,
    private val ledger: RocksItemLog,
    private val blocks: BlockLogs,
    private val lookups: Lookups,
    private val chunks: ChunkRollback,
    private val confiscations: Confiscations,
) {
    private class Pending(val target: LookupTarget, val query: LookupQuery, val at: Long)

    private val pending = ConcurrentHashMap<String, Pending>()
    // When the rollback running now started, or zero.
    private val running = AtomicLong()
    // Asked by /pp cancel while one runs: the chunks not yet begun are left as they are.
    private val stopping = java.util.concurrent.atomic.AtomicBoolean()
    private val reader = RollbackReader(ledger, blocks)
    // The blocks each player sees as a preview of theirs would put them.
    private val shown = ConcurrentHashMap<UUID, List<Ghost>>()

    fun preview(sender: CommandSender, target: LookupTarget, query: LookupQuery) {
        refusalOf(query)?.let {
            sender.say(it)
            return
        }
        pending[keyOf(sender)] = Pending(target, query, System.currentTimeMillis())
        run(sender, target, query, apply = false)
    }

    fun applyPreview(sender: CommandSender) {
        val key = keyOf(sender)
        val asked = pending.remove(key)?.takeIf { System.currentTimeMillis() - it.at <= PENDING_MILLIS }
        if (asked == null) {
            sender.say("Nothing to apply: preview a rollback with /pp rollback first.")
            return
        }
        // Two rollbacks over one place would each read the other's work as not done yet.
        val started = running.get()
        val now = System.currentTimeMillis()
        if (started != 0L && now - started < STALE_MILLIS || !running.compareAndSet(started, now)) {
            pending[key] = asked
            sender.say("Another rollback is still running; apply again once it has reported.")
            return
        }
        // Cleared here, not when the chunks start: a /pp cancel while it still reads must hold.
        stopping.set(false)
        hide(sender)
        run(sender, asked.target, asked.query, apply = true, startedAt = now)
    }

    /** When the rollback running now began, or null. */
    fun runningSince(): Long? = running.get().takeIf { it != 0L }

    fun cancelPreview(sender: CommandSender) {
        if (pending.remove(keyOf(sender)) != null) {
            hide(sender)
            return sender.say("Rollback preview dropped.")
        }
        if (running.get() != 0L && stopping.compareAndSet(false, true)) {
            return sender.say("Stopping the rollback that runs now: the chunks it has not reached yet stay as they are.")
        }
        sender.say("No rollback preview to drop.")
    }

    private fun refusalOf(query: LookupQuery): String? = when {
        query.event != null -> null
        query.secondsBack == null -> "A rollback needs time: how far back to undo, for example time:1h."
        query.players.isNotEmpty() -> "player: reads what a player carries and has no place to roll back; use user:."
        query.radius == null -> "A rollback needs radius: the blocks around you it covers, or global with user:."
        query.global && query.users.isEmpty() -> "radius:global undoes what named players did; give user: as well."
        else -> null
    }

    private fun keyOf(sender: CommandSender) = (sender as? Player)?.uniqueId?.toString() ?: sender.name

    private fun run(sender: CommandSender, target: LookupTarget, query: LookupQuery, apply: Boolean, startedAt: Long = 0) {
        // Only its own slot: a run taken as lost that finishes after all must not free the next one's.
        val release = { if (apply) running.compareAndSet(startedAt, 0) }
        Bukkit.getAsyncScheduler().runNow(plugin) {
            try {
                val users = lookups.resolveAll(sender, query.users)
                if (users == null) {
                    release()
                    return@runNow
                }
                val keeps = lookups.rowFilter(query, users, rollback = true)
                // The writers run on threads of their own, and what they still hold is history the
                // rollback would not see.
                ledger.drain()
                for (world in blocks.worlds) blocks.get(world)?.drain()
                val now = System.currentTimeMillis()
                val event = query.event
                // One event: a second either side of it, around where it happened. What came after is a
                // later change and stops it, as in any window that ends in the past.
                val from = event?.let { it.at - EVENT_MILLIS } ?: (now - query.secondsBack!! * 1000)
                // A span that stops in the past: what came after is somebody's later change and stops it.
                val to = event?.let { it.at + EVENT_MILLIS } ?: query.secondsUntil?.let { now - it * 1000 } ?: now
                val radius = if (event != null) query.radius ?: EVENT_RADIUS else query.radius
                val at = if (event != null) LookupTarget(target.world, event.x, event.y, event.z, "event ${event.id} at ${event.x} ${event.y} ${event.z}") else target
                val since = TIME_FORMAT.format(Instant.ofEpochMilli(from)) +
                    if (query.secondsUntil != null) " until ${TIME_FORMAT.format(Instant.ofEpochMilli(to))}" else ""
                val readings = if (query.global) {
                    everywhere(users, from, to, keeps)
                } else {
                    listOf(reader.around(at.world, at.x, at.y, at.z, radius!!, from, to, keeps::keeps, keeps::keeps, keeps::keeps, column = users.isNotEmpty()))
                }
                val where = when {
                    event != null -> at.label
                    query.global -> "everything ${query.users.joinToString(", ")} did since $since"
                    else -> "${query.radius} blocks around ${target.label} since $since"
                }
                val refused = readings.filterIsInstance<Refused>().firstOrNull()
                val plans = readings.filterIsInstance<Planned>()
                val positions = plans.sumOf { it.positions }
                when {
                    refused != null -> {
                        sender.say("Rollback refused: ${refused.reason}.")
                        release()
                    }
                    positions > io.pfaumc.pfauprotect.Settings.maxRollbackPositions -> {
                        sender.say("Rollback refused: ${reader.tooMany(positions).reason}.")
                        release()
                    }
                    else -> {
                        val leftBefore = if (apply || users.isEmpty()) 0 else reader.leftByThemBefore(plans, users, from)
                        dispatch(sender, plans, where, apply, release, rows = rowsOf(query, target), leftBefore = leftBefore, since = from)
                    }
                }
            } catch (failure: Throwable) {
                plugin.logger.log(Level.SEVERE, "the rollback at ${target.label} failed", failure)
                sender.say("The rollback failed; the server log has the details.")
                release()
            }
        }
    }

    // Every loaded world, read at the positions the players' rows stand at.
    private fun everywhere(users: Set<UUID>, from: Long, now: Long, keeps: RowFilter): List<Reading> {
        val touched = reader.touchedBy(users, from, now)
            ?: return listOf(Refused("those players did more in that window than one rollback reads; narrow the time"))
        return touched.map { (world, positions) -> reader.at(world, positions, from, now, keeps::keeps, keeps::keeps, keeps::keeps) }
    }

    private fun dispatch(
        sender: CommandSender,
        plans: List<Planned>,
        where: String,
        apply: Boolean,
        release: () -> Unit,
        rows: String?,
        leftBefore: Int = 0,
        since: Long = 0,
    ) {
        val work = plans.mapNotNull { plan -> (Bukkit.getWorld(plan.world) as? CraftWorld)?.handle?.let { it to plan } }
            .flatMap { (level, plan) -> plan.chunks.map { level to it } }
        val read = "${plans.sumOf { it.rows }} block rows, ${plans.sumOf { it.postings }} slot rows read"
        if (work.isEmpty() && plans.all { it.deaths.isEmpty() }) {
            sender.say("Nothing to roll back: $where.")
            release()
            return
        }
        val actor = (sender as? Player)?.uniqueId
        val total = Tally()
        total.leftBefore = leftBefore
        total.since = since
        total.deaths += plans.flatMap { it.deaths }
        // Only deaths to give back for, and no place to touch: straight on to them.
        if (work.isEmpty()) return finishing(sender, total, read, where, apply, actor, rows, release)
        val hold = ChunkHold(work)
        if (!hold.take()) {
            sender.say("Rollback refused: the chunks it works in did not load within ${LOAD_MILLIS / 1000} s.")
            release()
            return
        }
        // Held through the taking back too: the piles it takes back lie in these chunks, and taking them is
        // queued on the global region and then on the pile's own, after this returns. Let go at once, the
        // chunks unloaded under it and the piles read as gone while they still lay there.
        val freed = {
            release()
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { hold.release() }, HOLD_LINGER_TICKS)
            Unit
        }
        val left = AtomicInteger(work.size)
        val progress = Progress(sender as? Player, work.size, apply)
        val done = { val n = left.decrementAndGet(); progress.step(work.size - n); if (n == 0) entityJobs(total) { finishing(sender, total, read, where, apply, actor, rows, freed) } }
        // A few chunks at a time, each starting the next as it ends: a /pp cancel then reaches the chunks not
        // begun yet, and a big rollback does not land on every region in the same tick.
        val queue = java.util.concurrent.ConcurrentLinkedQueue(work)
        fun next() {
            while (true) {
                val (level, chunk) = queue.poll() ?: return
                if (apply && stopping.get()) {
                    total.add(Tally().apply { skipped = chunk.positions.size })
                    done()
                    continue
                }
                // Loaded by the hold, so this runs on the chunk's region rather than waiting for a load.
                level.`canvas$loadOrRunAtChunksAsync`(
                    chunk.chunkX - 1, chunk.chunkX + 1, chunk.chunkZ - 1, chunk.chunkZ + 1, Priority.NORMAL,
                ) {
                    try {
                        total.add(chunks.run(level, chunk, actor, apply))
                    } catch (failure: Throwable) {
                        plugin.logger.log(Level.SEVERE, "a rollback chunk at ${chunk.chunkX} ${chunk.chunkZ} failed", failure)
                        total.add(Tally().apply { failed = chunk.positions.size })
                    } finally {
                        done()
                        next()
                    }
                }
                return
            }
        }
        repeat(minOf(CONCURRENT_CHUNKS, work.size)) { next() }
    }

    // Live entities are worked on their own threads once every chunk is done; the rest waits for them.
    private fun entityJobs(total: Tally, then: () -> Unit) {
        val jobs = synchronized(total) { total.jobs.toList() }
        if (jobs.isEmpty()) return then()
        val left = AtomicInteger(jobs.size)
        val done = { if (left.decrementAndGet() == 0) then() }
        for (job in jobs) {
            job.entity.scheduler.run(plugin, {
                try {
                    job.run(total)
                } catch (failure: Throwable) {
                    plugin.logger.log(Level.SEVERE, "a rollback could not change ${job.entity.uniqueId}", failure)
                    synchronized(total) { total.failed++ }
                } finally {
                    done()
                }
            }, done)
        }
    }

    // Following what was given back to whoever holds it reads the ledger, which a region thread may not.
    private fun finishing(sender: CommandSender, total: Tally, read: String, where: String, apply: Boolean, actor: UUID?, rows: String?, release: () -> Unit) {
        Bukkit.getAsyncScheduler().runNow(plugin) {
            try {
                finish(sender, total, read, where, apply, actor, rows)
            } catch (failure: Throwable) {
                plugin.logger.log(Level.SEVERE, "taking back what the rollback at $where gave back failed", failure)
                sender.say("Taking back what the rollback gave back failed; the server log has the details.")
            } finally {
                release()
            }
        }
    }

    private fun finish(sender: CommandSender, total: Tally, read: String, where: String, apply: Boolean, actor: UUID?, rows: String?) {
        val owed = confiscations.owedFor(total)
        report(sender, total, read, where, apply, rows)
        if (!apply) show(sender, total.ghosts)
        // A killed player gets back what fell out of them, wherever it went.
        for (death in total.deaths.distinctBy { it.eventId to it.uuid }) {
            val back = restitutionFor(ledger, death)
            if (back.isEmpty()) continue
            val victim = Bukkit.getOfflinePlayer(death.uuid).name ?: death.uuid.toString()
            sender.say("  ${if (apply) "giving back" else "would give back"} to $victim what they lost: ${confiscations.describe(back)}")
            if (apply) confiscations.restore(death.uuid, back, actor, sender, pileBirths(ledger, death).values.flatten(), fellFrom(ledger, death))
        }
        // A mob let out of a bucket is no longer in it: the bucket with the mob for the one it was before.
        for (bucket in total.buckets) {
            val who = Bukkit.getOfflinePlayer(bucket.player).name ?: bucket.player.toString()
            val swap = "${confiscations.name(bucket.withMob)} from $who for ${confiscations.name(bucket.empty)}"
            if (!apply) {
                sender.say("  would swap back $swap.")
                continue
            }
            sender.say("  swapping back $swap:")
            confiscations.exchange(bucket.player, bucket.withMob, bucket.empty, actor, sender)
        }
        if (owed.isEmpty()) return
        val whom = confiscations.describe(owed)
        if (!apply) {
            sender.say("  would take back from $whom.")
            return
        }
        sender.say("  taking back from $whom:")
        confiscations.take(owed, actor, sender)
    }

    /**
     * The previewing player sees the blocks as the rollback would put them, for them alone, until they apply,
     * cancel, preview again or the preview runs out. Nothing in the world changes.
     */
    private fun show(sender: CommandSender, ghosts: List<Ghost>) {
        val player = sender as? Player ?: return
        hide(player)
        val world = player.world.uid
        // The first ones in plan order, not the nearest: a rollback bigger than the limit shows only part.
        // Not where the player stands: their client pushes them out of a ghost, and the apply then finds them
        // beside the wall instead of in it, to lift them onto it.
        val body = player.boundingBox
        val mine = ghosts.filter {
            it.world == world && !body.overlaps(org.bukkit.util.BoundingBox(it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), it.x + 1.0, it.y + 1.0, it.z + 1.0))
        }.take(GHOST_LIMIT)
        if (mine.isEmpty()) return
        shown[player.uniqueId] = mine
        player.sendMultiBlockChange(mine.associate { Position.block(it.x, it.y, it.z) to it.data })
        sender.say("  you see the blocks as they would stand; nothing changes before /pp apply.")
        Bukkit.getAsyncScheduler().runDelayed(plugin, {
            if (shown.remove(player.uniqueId, mine)) restore(player, mine)
        }, PENDING_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun hide(sender: CommandSender) {
        val player = sender as? Player ?: return
        shown.remove(player.uniqueId)?.let { restore(player, it) }
    }

    // The client is told again what really stands there, read on each chunk's own region.
    private fun restore(player: Player, ghosts: List<Ghost>) {
        val world = Bukkit.getWorld(ghosts.first().world) ?: return
        for ((chunk, group) in ghosts.groupBy { (it.x shr 4) to (it.z shr 4) }) {
            Bukkit.getRegionScheduler().execute(plugin, world, chunk.first, chunk.second) {
                if (!player.isOnline || !world.isChunkLoaded(chunk.first, chunk.second)) return@execute
                player.sendMultiBlockChange(group.associate { Position.block(it.x, it.y, it.z) to world.getBlockData(it.x, it.y, it.z) })
            }
        }
    }

    // The counts that are not zero, a line to each kind of thing, so a preview reads at a glance.
    private fun report(sender: CommandSender, total: Tally, read: String, where: String, apply: Boolean, rows: String?) {
        fun parts(vararg counts: Pair<Int, String>) = counts.filter { it.first > 0 }.joinToString(" · ") { "${it.second} ${it.first}" }
        val blocks = parts(
            total.changed to (if (apply) tr("put back", "возвращено") else tr("to change", "изменится")),
            total.unchanged to tr("already as they were", "уже как были"),
            total.conflicts to tr("stopped by a later change", "остановлено поздней переменой"),
        )
        val slots = parts(total.slots to (if (apply) tr("given back", "возвращено") else tr("to give back", "вернуть")))
        val entities = parts(
            total.entitiesBack to (if (apply) tr("brought back", "возвращено") else tr("to bring back", "вернуть")),
            total.entitiesTaken to (if (apply) tr("taken away", "убрано") else tr("to take away", "убрать")),
            total.entitiesReverted to (if (apply) tr("changed back", "изменено обратно") else tr("to change back", "изменить обратно")),
            total.entitiesAlready to tr("already as they were", "уже как были"),
        )
        val title = if (apply) tr("Rolled back", "Откачено") else tr("Rollback preview", "Предпросмотр отката")
        sender.sendMessage(Component.text().append(Ui.text("PfauProtect · ", Ui.FAINT)).append(Ui.text("$title: ")).append(Ui.text(Texts.translate(where), Ui.MUTED)).build())
        for ((label, counts) in listOf(tr("blocks", "блоки") to blocks, tr("slots", "слоты") to slots, tr("entities", "сущности") to entities)) {
            if (counts.isNotEmpty()) sender.sendMessage(Component.text().append(Ui.text("  $label: ", Ui.MUTED)).append(Ui.text(counts)).build())
        }
        if (!apply) {
            sender.sendMessage(Ui.text("  " + Texts.translate(read), Ui.FAINT))
            if (total.leftBefore > 0) sender.say(
                "  the window may be shorter than a full rollback needs: ${total.leftBefore} of these positions stood " +
                    "as the same player had left them when it opened, and go back to that; a longer time: reaches further.",
            )
            val minutes = PENDING_MILLIS / 60_000
            if (sender !is Player) {
                // A world-wide lookup has no index to read by, so it cannot show the rows of a global one.
                sender.say("  /pp apply within $minutes minutes runs it, /pp cancel drops it${if (rows == null) "" else "; /pp lookup with the same words shows the rows"}.")
                return
            }
            val buttons = Component.text().append(Ui.text("  "))
                .append(Ui.button(tr("[apply]", "[применить]"), "/pp apply", Ui.GAINED, tr("Run this rollback", "Выполнить откат"), "/pp apply"))
                .append(Ui.text(" "))
                .append(Ui.button(tr("[cancel]", "[отменить]"), "/pp cancel", Ui.LOST, tr("Drop the preview", "Сбросить предпросмотр"), "/pp cancel"))
            if (rows != null) buttons.append(Ui.text(" ")).append(
                Ui.hover(Ui.text(tr("[rows]", "[строки]"), Ui.WHO), tr("The rows it undoes", "Строки, которые он отменяет"), rows)
                    .clickEvent(ClickEvent.runCommand(rows)),
            )
            buttons.append(Ui.text(tr("  expires in $minutes min", "  действует $minutes мин"), Ui.FAINT))
            sender.sendMessage(buttons.build())
            return
        }
        if (total.entitiesGoneSince > 0) sender.say("  ${total.entitiesGoneSince} entities to change back are gone since.")
        if (total.missed > 0) sender.say("  ${total.missed} slot postings found no room or nothing left to take out.")
        if (total.failed > 0) sender.say("  ${total.failed} positions failed; the server log has the details.")
        if (total.skipped > 0) sender.say("  stopped by /pp cancel: ${total.skipped} positions in chunks it had not reached are left as they were.")
    }

    // The lookup that shows what a rollback undoes, about the place it covers; a world-wide one has no place.
    private fun rowsOf(query: LookupQuery, target: LookupTarget): String? =
        if (query.global || query.event != null) null else query.pageCommand(target, 1)
}

/** How far an apply has got, above the hotbar of whoever ran it, at most a few times a second. */
private class Progress(private val player: Player?, private val chunks: Int, private val apply: Boolean) {
    private val shown = AtomicLong()

    fun step(done: Int) {
        if (player == null || !apply) return
        val now = System.currentTimeMillis()
        val last = shown.get()
        if (done < chunks && (now - last < PROGRESS_MILLIS || !shown.compareAndSet(last, now))) return
        val percent = done * 100 / chunks
        player.sendActionBar(Ui.text(tr("Rollback: $percent% ($done/$chunks chunks)", "Откат: $percent% ($done/$chunks чанков)"), Ui.CHANGED))
    }
}

private const val PROGRESS_MILLIS = 250L
