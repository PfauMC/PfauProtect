package io.pfaumc.pfauprotect.rollback

import io.pfaumc.pfauprotect.attribution.AIRS
import io.pfaumc.pfauprotect.attribution.LIQUIDS
import io.pfaumc.pfauprotect.attribution.blockNameOf
import io.pfaumc.pfauprotect.attribution.flowing
import io.pfaumc.pfauprotect.capture.block.FIRES
import io.pfaumc.pfauprotect.capture.block.reshaped
import io.pfaumc.pfauprotect.command.RowFilter
import io.pfaumc.pfauprotect.command.boxAround
import io.pfaumc.pfauprotect.command.chunksAround
import io.pfaumc.pfauprotect.command.placedIn
import io.pfaumc.pfauprotect.command.stateName
import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.LIGHTNING
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.PlayerEquip
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.PostingRef
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.model.block
import io.pfaumc.pfauprotect.model.ownHolders
import io.pfaumc.pfauprotect.storage.BlockLog
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.BlockRow
import io.pfaumc.pfauprotect.storage.Window
import io.pfaumc.pfauprotect.storage.EntityRow
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.storage.RocksItemLog
import java.util.UUID
import kotlin.math.abs

// Rows one rollback may hold, per plane. A place with more history than this in the window is more than
// one rollback should undo, and it is refused rather than cut.
internal const val MAX_ROLLBACK_ROWS = 100_000

// How many rows of the block plane the walk may step over in all, inside the window or not: a chunk's
// rows are keyed by position before time, so a window costs the whole history of every chunk it reads.
internal const val BLOCK_WALK_BUDGET = 2_000_000

// The entity rows that say what an entity was before: the rest only mark an event at a place.
private val STORIES = setOf(EntityKind.REMOVED, EntityKind.CREATED, EntityKind.CHANGED, EntityKind.MOVED)

// What the world does by itself, under rows nobody answers for: grass taking the dirt a griefer put down,
// his fire going out, his lava running on. A rollback walks past them on a position it undoes, and past
// what an earlier rollback did there, which is no later change either; a player's row and a command's
// still stop it.
private val NATURE = setOf(
    Cause.BLK_GROW, Cause.BLK_FADE, Cause.BLK_FORM, Cause.BLK_LEAF_DECAY, Cause.BLK_SCULK,
    Cause.BLK_FIRE_BURN, Cause.BLK_FIRE_SPREAD, Cause.BLK_LIQUID_DESTROY, Cause.BLK_LIQUID_FORM,
)

// A storm's fire is nature too, for all that it names the bolt.
private fun passable(row: BlockRow) =
    row.cause == Cause.ROLLBACK || (row.actor == null || row.actor == LIGHTNING) && row.cause in NATURE

// How far back a position is read for what it held before the players a rollback names first touched it.
private const val ORIGIN_WALK = 64

/**
 * What a position held before the players a rollback names first touched it, from its rows older than the
 * window, newest first: back over their rows and past nature and earlier rollbacks, as far as somebody
 * else's row. Null where the rollback undoes rollbacks, which have no such beginning.
 */
internal fun originOf(older: List<BlockRow?>, keepsRow: (BlockRow) -> Boolean): Int? {
    var origin: Int? = null
    for (row in older) {
        // A row that cannot be read may be anybody's: no beginning is known past it, and the position is
        // rolled back as the window has it.
        if (row == null) return null
        if (keepsRow(row)) {
            if (row.cause == Cause.ROLLBACK) return null
            origin = row.stateBefore
        } else if (!passable(row)) break
    }
    return origin
}

private val ROW_ORDER = compareBy<BlockRow> { it.eventId }.thenBy { it.ordinal }
private val ENTITY_ROW_ORDER = compareBy<EntityRow> { it.eventId }.thenBy { it.ordinal }

internal val BlockRow.spot get() = Triple(x, y, z)

// What dies to dirt under a block put on it.
private val TURFS = setOf("minecraft:grass_block", "minecraft:dirt_path", "minecraft:mycelium", "minecraft:podzol", "minecraft:farmland")

/**
 * The rows of nature and of earlier rollbacks a rollback walks past: at a position it undoes, and after
 * the first of its rows there. What grew before that is part of what the position goes back to.
 *
 * `turf` says whether a fade row is one of [TURFS] dying under what stands on it, not a fire going out.
 */
internal fun passedBy(kept: List<BlockRow>, nature: List<BlockRow>, turf: (BlockRow) -> Boolean): List<BlockRow> {
    val first = kept.groupBy { it.spot }.mapValues { (_, rows) -> rows.minWith(ROW_ORDER) }
    // What the world did after an earlier rollback had put the position back (grass under a restored wall
    // going to dirt) is the world's, not part of the grief, and is not walked past.
    val undone = (kept + nature).filter { it.cause == Cause.ROLLBACK }.groupBy { it.spot }
        .mapValues { (_, rows) -> rows.maxWith(ROW_ORDER) }
    fun after(row: BlockRow, at: Triple<Int, Int, Int>) = first[at]?.let { ROW_ORDER.compare(row, it) > 0 } == true
    fun beforeUndone(row: BlockRow, at: Triple<Int, Int, Int>) =
        row.cause == Cause.ROLLBACK || undone[at]?.let { ROW_ORDER.compare(row, it) < 0 } != false
    // Grass or a path gone to dirt under a block the rollback takes away went because of that block, and
    // comes back with its going: it would otherwise stay dirt under the air the griefer left.
    val passed = nature.filterTo(LinkedHashSet()) { row ->
        val at = row.spot
        val above = Triple(row.x, row.y + 1, row.z)
        beforeUndone(row, at) &&
            (after(row, at) || row.cause == Cause.BLK_FADE && turf(row) && after(row, above) && beforeUndone(row, above))
    }
    // What grew out of a planting the rollback takes away goes with it: the rest of a tree its sapling
    // turned into, in the same event, and a stalk of bamboo, cactus or cane above it, block by block.
    val grew = passed.filter { it.cause == Cause.BLK_GROW }.mapTo(HashSet()) { it.eventId }
    nature.filterTo(passed) { it.cause == Cause.BLK_GROW && it.eventId in grew && beforeUndone(it, it.spot) }
    val reached = (first.keys + passed.map { it.spot }).toHashSet()
    for (row in nature.filter { it.cause == Cause.BLK_GROW }.sortedBy { it.y }) {
        val at = row.spot
        val below = Triple(row.x, row.y - 1, row.z)
        if (below in reached && first[below]?.let { ROW_ORDER.compare(row, it) > 0 } != false && beforeUndone(row, at)) {
            passed += row
            reached += at
        }
    }
    return passed.toList()
}

/**
 * The block a state string is of, with the properties dropped and every air read as one. Liquid that
 * ran in is air too: its arrival has no row, and lava that filled a burnt-out house is no later change.
 * So is fire: a plank that burnt to fire and then to nothing goes back all the same.
 */
internal fun blockOf(state: String): String = if (passing(state)) "minecraft:air" else nameOf(state)

// The three airs are one block to a rollback: a cave keeps its own kind of air, and a break inside it
// leaves the plain one behind.
private fun nameOf(state: String): String = blockNameOf(state).let { if (it in AIRS) "minecraft:air" else it }

/**
 * Fire and running liquid: what a rollback reads as air and puts back as air. A fire lit again would burn
 * down what the rollback has just put back.
 */
internal fun passing(state: String): Boolean = blockNameOf(state) in FIRES || flowing(state)

/** One row of a position read backwards: the position went from `before` to `after`. */
class Step(val before: String, val after: String, val payloadBefore: ByteArray?)

/** What undoing a position comes to: `back` is the step whose `before` it returns to, null for none. */
class Settled(val back: Step?, val conflict: Boolean)

/**
 * A position the window opens on in the middle of the players' work, standing again as it did before they
 * first touched it, is done: undone from the window's start it would get back their own TNT, their own
 * burnt walls. A wall or a pane is that block only in the shape it had.
 */
fun settle(standing: String, steps: List<Step>, origin: String?): Settled =
    // The block itself, with fire and running liquid kept as what they are: air a break left is not the
    // running water the stone was put into, and taking one for the other would skip the stone's break.
    if (origin != null && nameOf(standing) == nameOf(origin) && !reshaped(standing, origin)) Settled(null, conflict = false)
    else settle(standing, steps)

/**
 * Blocks are compared, not whole states: a fence, a wire or a leaf changes its properties with its
 * neighbours and never gets a row for it.
 *
 * Standing on the `before` of the chain's oldest row means an earlier rollback already undid the chain,
 * so the walk is done rather than in conflict.
 *
 * Water and lava are one block at any level: a flow turns into a source between two sources with no row.
 */
fun settle(standing: String, steps: List<Step>): Settled {
    var state = standing
    var back: Step? = null
    for (step in steps) {
        when {
            same(state, step.after) -> {
                state = step.before
                back = step
            }
            same(state, step.before) -> continue
            same(state, steps.last().before) -> break
            else -> return Settled(back, conflict = true)
        }
    }
    return Settled(back, conflict = false)
}

private fun fluidOf(state: String): String? = blockNameOf(state).takeIf { it in LIQUIDS }

private fun same(a: String, b: String): Boolean = blockOf(a) == blockOf(b) || fluidOf(a)?.let { it == fluidOf(b) } == true

/**
 * One slot posting given back: `qty` above zero is what left the slot and goes back in, below zero is
 * what arrived and is taken out again. `lead` is where it went or came from, which is where a rollback
 * that put it back goes looking for it.
 */
class Refill(
    val at: Container,
    val form: ByteArray,
    val formId: Long,
    val damage: Int?,
    val qty: Int,
    val posting: PostingRef,
    val lead: Holder,
)

/**
 * What a rollback does to one entity. `oldest` is the first row in the window the filter keeps, and what
 * it says the entity was before — `before`, its whole NBT then — is what the entity goes back to. `slots` are what its own slots gave up
 * or took since, given back along with it; `drops` what fell out of it when it went or was changed. `removed` says it
 * went within the window even when its oldest row was a change: a wolf whose armour broke under the
 * blows that then killed it is brought back, not looked for alive to be changed back.
 */
class EntityPlan(
    val at: WorldBlock,
    val oldest: EntityRow,
    val before: ByteArray?,
    val slots: List<LedgerEntry>,
    val drops: List<UUID>,
    val removed: Boolean,
    val bucket: Bucketed?,
) {
    val uuid: UUID get() = oldest.uuid
    val type: String get() = oldest.type
}

/**
 * A mob taken up in a bucket: the bucket it went into, and the one it was before, in the slot of the
 * player who took it. Brought back, the mob would be in the world and in the bucket at once.
 */
class Bucketed(val player: UUID, val withMob: Long, val empty: Long)

// How far apart the entity row and the slot's change of one bucketing can be: the pass that books the
// slot runs a tick or so after the event that writes the row.
private const val BUCKETING_MILLIS = 5_000L

/** The change of bucket that took this mob up, from the slots of the player the row names. */
internal fun bucketOf(ledger: RocksItemLog, row: EntityRow): Bucketed? {
    if (row.kind != EntityKind.REMOVED || row.cause != Cause.BUCKET_CAPTURE_MOB) return null
    val player = row.actor ?: return null
    // The slot never enters the key: slot 0 reads every slot of its kind. Two mobs bucketed within the window
    // are told apart by time: the change nearest the row is the one that took this one.
    val rows = listOf(PlayerInv(player, 0), PlayerEquip(player, 0)).flatMap {
        ledger.holderEntries(it, row.timestamp - BUCKETING_MILLIS, row.timestamp + BUCKETING_MILLIS, limit = 100)
    }.filter { it.cause == Cause.BUCKET_CAPTURE_MOB }
    val change = rows.groupBy { it.txId }.values
        .filter { tx -> tx.any { it.qty > 0 } && tx.any { it.qty < 0 } }
        .minByOrNull { tx -> abs(tx.first().timestamp - row.timestamp) } ?: return null
    return Bucketed(player, change.first { it.qty > 0 }.itemFormId, change.first { it.qty < 0 }.itemFormId)
}

/**
 * Everything a rollback does at one position: its block rows newest first, its slot postings, and the
 * item plane's side of the breaks among them — what the position gave up, whose transaction also says
 * what fell out of it. `entities` are the entities whose story starts here; `dropped`, the items the
 * position dropped when something other than a hand broke it.
 */
class PositionPlan(
    val at: WorldBlock,
    val steps: List<Step>,
    val refills: List<Refill>,
    val breaks: List<LedgerEntry>,
    val entities: List<EntityPlan>,
    val dropped: List<UUID>,
    // What the position held before the players first touched it, where the window opens on their work.
    val origin: String?,
)

class ChunkPlan(val chunkX: Int, val chunkZ: Int, val positions: List<PositionPlan>)

sealed interface Reading

// `positions` is set where the refusal is the position limit, which a smaller radius gets under.
class Refused(val reason: String, val positions: Int? = null) : Reading

class Planned(
    val world: UUID,
    val chunks: List<ChunkPlan>,
    val rows: Int,
    val postings: Int,
    val deaths: List<EntityRow>,
) : Reading {
    val positions: Int get() = chunks.sumOf { it.positions.size }
}

// The block and entity rows of one rollback's read, gathered window by window against one walk budget:
// the rows it undoes (those it keeps, except a liquid's flow), the nature it walks past, and the changes
// somebody else made.
private class Collected(private val keeps: RowFilter) {
    val rows = ArrayList<BlockRow>()
    val nature = ArrayList<BlockRow>()
    val foreign = ArrayList<BlockRow>()
    val entityRows = ArrayList<EntityRow>()
    var unreadable = 0
    var budget = BLOCK_WALK_BUDGET

    /** False when a walk ran out of budget, which refuses the rollback; the entity walk gets what is left. */
    fun collect(window: Window<BlockRow>, entities: (Int) -> Window<EntityRow>): Boolean {
        if (!window.complete) return false
        budget -= window.walked
        unreadable += window.unreadable
        for (row in window.rows) when {
            keeps.keeps(row) -> if (row.cause != Cause.BLK_LIQUID_FLOW) rows += row
            passable(row) -> nature += row
            else -> foreign += row
        }
        val read = entities(budget)
        if (!read.complete) return false
        budget -= read.walked
        unreadable += read.unreadable
        read.rows.filterTo(entityRows) { keeps.keeps(it) }
        return true
    }
}

/**
 * Reads what a rollback would undo: the block rows and the container postings in the window that the
 * filter keeps, grouped by position and by chunk. Everything the read could not see — a row it could not
 * decode, a walk that ran out of budget, a state or a payload the ledger cannot name — refuses the whole
 * rollback, because going ahead over part of the history puts back part of the place and calls it done.
 */
class RollbackReader(private val ledger: RocksItemLog, private val blocks: BlockLogs) {
    private fun turf(row: BlockRow): Boolean =
        stateName(ledger, row.stateBefore) in TURFS

    internal fun around(
        world: UUID,
        x: Int,
        y: Int,
        z: Int,
        radius: Int,
        fromTs: Long,
        toTs: Long,
        keeps: RowFilter,
        // The whole height of the square: a rollback of named players touches only their rows, and lava
        // they poured thirty blocks over the house is part of what burnt it. Without names it stays a
        // cube, so somebody else's build high above is not undone with it.
        column: Boolean = false,
    ): Reading {
        val log = blocks.get(world) ?: return closed()
        val inBox = boxAround(x, y, z, radius, column)
        val read = Collected(keeps)
        for ((cx, cz) in chunksAround(x, z, radius)) {
            val window = log.windowInChunk(cx, cz, fromTs, toTs, read.budget, inBox)
            if (!read.collect(window) { log.entitiesInChunk(cx, cz, fromTs, toTs, it, inBox) }) return tooMuch()
            if (read.rows.size + read.entityRows.size > MAX_ROLLBACK_ROWS) return tooMuch()
        }
        val page = ledger.regionPage(
            world, x - radius, z - radius, x + radius, z + radius, fromTs, toTs,
            limit = MAX_ROLLBACK_ROWS,
            within = { it.placedIn(inBox, unplaced = false) },
        )
        if (!page.complete) return tooMuch()
        read.unreadable += page.unreadable
        val (slots, positions) = page.entries.filter { keeps.keeps(it) }.partition { it.holder is Container }
        return planOf(log, world, read, slots, positions.filter { it.qty < 0 }, fromTs, toTs, keeps)
    }

    /**
     * Where the players' rows in the window stand, world by world: the block plane's index of rows by
     * actor, and the positions they traded items with — the far ends of the rows under their own slots.
     * Only worlds whose history is open are seen; a world that is not loaded cannot be rolled back.
     */
    fun touchedBy(users: Set<UUID>, fromTs: Long, toTs: Long): Map<UUID, Set<WorldBlock>>? {
        val touched = HashMap<UUID, MutableSet<WorldBlock>>()
        for (world in blocks.worlds) {
            val log = blocks.get(world) ?: continue
            for (user in users) {
                val found = log.touchedBy(user, fromTs, toTs, MAX_ROLLBACK_ROWS)
                if (!found.complete) return null
                for ((x, y, z) in found.positions) touched.getOrPut(world) { HashSet() } += WorldBlock(world, x, y, z)
            }
        }
        for (user in users) {
            for (holder in ownHolders(user)) {
                val page = ledger.holderPage(holder, fromTs, toTs, limit = MAX_ROLLBACK_ROWS)
                if (!page.complete || page.unreadable > 0) return null
                for (entry in page.entries) {
                    val at = entry.counterparty.block ?: continue
                    if (blocks.get(at.world) != null) touched.getOrPut(at.world) { HashSet() } += at
                }
            }
        }
        return touched
    }

    /**
     * How many of the positions a rollback of these players would undo already stood as one of them had
     * left it when the window opened. Each goes back to that, which may be their own lava or their own
     * dirt: the window starts in the middle of what they did there, and only the admin can say whether a
     * longer one is what was meant.
     */
    fun leftByThemBefore(plans: List<Planned>, users: Set<UUID>, fromTs: Long): Int = plans.sumOf { plan ->
        val log = blocks.get(plan.world) ?: return@sumOf 0
        plan.chunks.flatMap { it.positions }.filter { it.steps.isNotEmpty() }.count { position ->
            val row = log.standingBefore(position.at.x, position.at.y, position.at.z, fromTs)
            row != null && row.cause != Cause.ROLLBACK && row.actor?.let { it in users } == true
        }
    }

    /** What a rollback would undo at these positions of one world, read position by position. */
    internal fun at(world: UUID, positions: Set<WorldBlock>, fromTs: Long, toTs: Long, keeps: RowFilter): Reading {
        val log = blocks.get(world) ?: return closed()
        if (positions.size > Settings.maxRollbackPositions) return tooMany(positions.size)
        val read = Collected(keeps)
        val slots = ArrayList<LedgerEntry>()
        val losses = ArrayList<LedgerEntry>()
        for (at in positions) {
            val window = log.windowAt(at.x, at.y, at.z, fromTs, toTs, read.budget)
            if (!read.collect(window) { log.entitiesAt(at.x, at.y, at.z, fromTs, toTs, it) }) return tooMuch()
            for (holder in listOf(Container(world, at.x, at.y, at.z, 0), at)) {
                val page = ledger.holderPage(holder, fromTs, toTs, limit = MAX_ROLLBACK_ROWS)
                if (!page.complete) return tooMuch()
                read.unreadable += page.unreadable
                for (entry in page.entries.filter { keeps.keeps(it) }) {
                    if (entry.holder is Container) slots += entry else if (entry.qty < 0) losses += entry
                }
            }
            if (read.rows.size + slots.size > MAX_ROLLBACK_ROWS) return tooMuch()
        }
        return planOf(log, world, read, slots, losses, fromTs, toTs, keeps)
    }

    private fun planOf(
        log: BlockLog, world: UUID, read: Collected, slots: List<LedgerEntry>, losses: List<LedgerEntry>,
        fromTs: Long, toTs: Long, keeps: RowFilter,
    ): Reading {
        // An item row carries no event of its own: one event's are those at the places the event changed.
        val spots = keeps.event?.let { read.rows.mapTo(HashSet()) { it.spot } + read.entityRows.map { Triple(it.x, it.y, it.z) } }
        fun ofEvent(entry: LedgerEntry) = spots == null || entry.holder.block?.let { Triple(it.x, it.y, it.z) } in spots
        return plan(
            world, read.rows + passedBy(read.rows, read.nature, ::turf), slots.filter(::ofEvent), losses.filter(::ofEvent),
            read.entityRows, toTs, read.unreadable, origins(log, world, read.rows, read.foreign, fromTs, keeps),
        )
    }

    // Read off the region threads with the rest of the plan; a window from the beginning has nothing before it.
    // A position somebody else changed inside the window does not stand as the players found it, whatever it
    // holds, so it gets no origin.
    private fun origins(
        log: BlockLog, world: UUID, rows: List<BlockRow>, foreign: List<BlockRow>, fromTs: Long, keeps: RowFilter,
    ): Map<WorldBlock, String> {
        if (fromTs <= 0) return emptyMap()
        val touched = foreign.mapTo(HashSet()) { it.spot }
        return rows.mapTo(HashSet()) { it.spot }.filter { it !in touched }.mapNotNull { (x, y, z) ->
            originOf(log.rowsBefore(x, y, z, fromTs, ORIGIN_WALK)) { keeps.keeps(it) }?.let(::state)?.let { WorldBlock(world, x, y, z) to it }
        }.toMap()
    }

    private fun plan(
        world: UUID,
        rows: List<BlockRow>,
        kept: List<LedgerEntry>,
        losses: List<LedgerEntry>,
        entityRows: List<EntityRow>,
        toTs: Long,
        unreadable: Int,
        origins: Map<WorldBlock, String>,
    ): Reading {
        if (unreadable > 0) return unreadable(unreadable)
        // Given back once already, a posting is skipped — unless what gave it back is being rolled back
        // in this same run. Undoing a rollback together with its own undo has to leave both halves in,
        // or the slots come out of it as the first rollback left them while the blocks do not.
        val undoing = kept.mapTo(HashSet()) { it.txId }
        val givenBack = ledger.compensated(kept.map { it.ref }).filterValues { it !in undoing }
        val entries = kept.filter { it.ref !in givenBack }
        val steps = HashMap<WorldBlock, MutableList<Step>>()
        var unnamed = 0
        // Newest first by the counters, not by the clock: within a position the time never goes back,
        // but the event number is what the store promises.
        for (row in rows.sortedWith(ROW_ORDER.reversed())) {
            val before = state(row.stateBefore)
            val after = state(row.stateAfter)
            val payload = row.payloadBefore?.let { ledger.payload(it) }
            if (before == null || after == null || row.payloadBefore != null && payload == null) {
                unnamed++
                continue
            }
            steps.getOrPut(WorldBlock(world, row.x, row.y, row.z)) { ArrayList() } += Step(before, after, payload)
        }
        val refills = HashMap<WorldBlock, MutableList<Refill>>()
        for (entry in entries.sortedWith(compareByDescending<LedgerEntry> { it.txId }.thenByDescending { it.ordinal })) {
            val slot = entry.holder as Container
            val form = ledger.form(entry.itemFormId)
            if (form == null) {
                unnamed++
                continue
            }
            refills.getOrPut(WorldBlock(world, slot.x, slot.y, slot.z)) { ArrayList() } +=
                Refill(slot, form, entry.itemFormId, entry.damage, -entry.qty, entry.ref, entry.counterparty)
        }
        val entities = HashMap<WorldBlock, MutableList<EntityPlan>>()
        val dropped = HashMap<WorldBlock, MutableList<UUID>>()
        val deaths = ArrayList<EntityRow>()
        val (stories, marks) = entityRows.partition { it.kind in STORIES }
        for (row in marks) {
            when (row.kind) {
                EntityKind.DROPPED -> dropped.getOrPut(WorldBlock(world, row.x, row.y, row.z)) { ArrayList() } += row.drops
                EntityKind.PLAYER_DIED -> deaths += row
                else -> Unit
            }
        }
        for ((uuid, story) in stories.groupBy { it.uuid }) {
            val oldest = story.minWith(ENTITY_ROW_ORDER)
            val before = oldest.payloadBefore?.let { ledger.payload(it) }
            if (before == null && (oldest.payloadBefore != null || oldest.kind != EntityKind.CREATED)) {
                unnamed++
                continue
            }
            // Whatever its own slots did after the moment it goes back to has to be undone with it, whoever
            // did it: the NBT it goes back to already holds them as they were then.
            val page = ledger.holderPage(EntitySlot(uuid, 0), oldest.timestamp, toTs, limit = MAX_ROLLBACK_ROWS)
            if (!page.complete) return tooMuch()
            if (page.unreadable > 0) return unreadable(page.unreadable)
            val owed = ledger.compensated(page.entries.map { it.ref })
            // An earlier rollback's postings put the slots back as the NBT already holds them; only a run that
            // undoes that rollback, and so keeps its entity row, undoes them too.
            val undoingRollback = story.any { it.cause == Cause.ROLLBACK }
            val slots = page.entries.filter {
                it.ref !in owed && it.cause != Cause.PURGE_OPENING && (undoingRollback || it.cause != Cause.ROLLBACK)
            }
            val at = WorldBlock(world, oldest.x, oldest.y, oldest.z)
            val removals = story.filter { it.kind == EntityKind.REMOVED }
            // What fell out of it when it went, and what a hand took off it in passing: shorn wool.
            entities.getOrPut(at) { ArrayList() } +=
                EntityPlan(
                    at, oldest, before, slots, story.flatMap { it.drops }, removals.isNotEmpty(),
                    // Changed or led first and bucketed after: the bucket is the removal's, not the first row's.
                    bucketOf(ledger, removals.maxByOrNull { it.eventId } ?: oldest),
                )
        }
        if (unnamed > 0) return unreadable(unnamed)
        val positions = steps.keys + refills.keys + entities.keys
        if (positions.size > Settings.maxRollbackPositions) return tooMany(positions.size)
        val breaks = losses.groupBy { it.holder as WorldBlock }
        val chunks = positions
            .map {
                PositionPlan(
                    it, steps[it].orEmpty(), refills[it].orEmpty(), breaks[it].orEmpty(),
                    entities[it].orEmpty(), dropped[it].orEmpty(), origins[it],
                )
            }
            .groupBy { (it.at.x shr 4) to (it.at.z shr 4) }
            .map { (chunk, plans) -> ChunkPlan(chunk.first, chunk.second, plans) }
        return Planned(world, chunks, rows.size + entityRows.size, entries.size, deaths)
    }

    private fun state(id: Int): String? = ledger.registries.keyOf(RegistryNamespace.BLOCK_STATE, id)

    internal fun tooMany(positions: Int) = Refused(
        "$positions positions changed in that window, more than the ${Settings.maxRollbackPositions} one rollback " +
            "may write; narrow the radius or the time",
        positions,
    )

    private fun closed() = Refused("the block history of this world is not open")

    private fun tooMuch() = Refused(
        "that window holds more history than one rollback reads; narrow the radius or the time"
    )

    private fun unreadable(count: Int) = Refused(
        "$count rows in that window could not be read by this build, and a rollback over part of the " +
            "history would put back part of the place; nothing was done"
    )
}

private val SIGNAL_POWER = Regex("(?<=[\\[,])power=\\d+")

private val LEAF_DISTANCE = Regex("(?<=[\\[,])distance=\\d+")

/**
 * A block as it stands with no signal in it: put back lit, a comparator, a wire or an observer fired whatever
 * stood beside it before it settled, and a machine the rollback put together set off again. The
 * neighbours' pass after the blocks powers what is really powered. A lever, a button and a plate are on or off
 * by themselves, and their own rows put that back; a dispenser's trigger is left too, or one beside a lever an
 * owner left on would fire.
 */
internal fun quiet(state: String): String {
    val name = blockNameOf(state)
    if (name == "minecraft:lever" || name.endsWith("_button") || name.endsWith("_pressure_plate")) return state
    // A leaf's distance is its neighbours' to count, as a signal is: a leaf that decayed is written down at
    // distance 7, and put back so it decayed again. Put back at 1, it counts anew on its next tick.
    if (name.endsWith("_leaves") && "persistent=false" in state) return state.replace(LEAF_DISTANCE, "distance=1")
    val torch = name == "minecraft:redstone_torch" || name == "minecraft:redstone_wall_torch"
    return state.replace("powered=true", "powered=false").replace(SIGNAL_POWER, "power=0")
        .let { if (torch) it.replace("lit=false", "lit=true") else it }
}
