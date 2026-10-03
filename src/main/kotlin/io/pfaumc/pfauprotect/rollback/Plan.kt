package io.pfaumc.pfauprotect.rollback

import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerEnder
import io.pfaumc.pfauprotect.model.PlayerEquip
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.PostingRef
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.BlockRow
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.storage.RocksItemLog
import java.util.UUID

// Rows one rollback may hold, per plane. A place with more history than this in the window is more than
// one rollback should undo, and it is refused rather than cut.
internal const val MAX_ROLLBACK_ROWS = 100_000

// How many rows of the block plane the walk may step over in all, inside the window or not: a chunk's
// rows are keyed by position before time, so a window costs the whole history of every chunk it reads.
internal const val BLOCK_WALK_BUDGET = 2_000_000

// The vanilla `/fill` limit: as many positions as the server lets one command write.
internal const val MAX_ROLLBACK_POSITIONS = 32_768

// The three airs are one block to a rollback: a cave keeps its own kind of air, and a break inside it
// leaves the plain one behind.
private val AIRS = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")

/** The block a state string is of, with the properties dropped and every air read as one. */
internal fun blockOf(state: String): String = state.substringBefore('[').let { if (it in AIRS) "minecraft:air" else it }

/** One row of a position read backwards: the position went from `before` to `after`. */
class Step(val before: String, val after: String, val payloadBefore: ByteArray?)

/** What undoing a position comes to: `back` is the step whose `before` it returns to, null for none. */
class Settled(val back: Step?, val conflict: Boolean)

/**
 * Undoes the rows of one position, newest first, from what stands there now. A row whose `after` is
 * what stands is undone; one whose `before` already stands was undone before and is stepped over; any
 * other means something this rollback does not touch changed the position since, and the walk stops
 * there with whatever it had undone.
 *
 * Blocks are compared, not whole states: a fence, a wire and a leaf change their properties with
 * their neighbours and never get a row for it, and comparing strings would make every one of them a
 * conflict. What is put back is the whole state the row recorded.
 */
fun settle(standing: String, steps: List<Step>): Settled {
    var state = standing
    var back: Step? = null
    for (step in steps) {
        when (blockOf(state)) {
            blockOf(step.after) -> {
                state = step.before
                back = step
            }
            blockOf(step.before) -> continue
            else -> return Settled(back, conflict = true)
        }
    }
    return Settled(back, conflict = false)
}

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
 * Everything a rollback does at one position: its block rows newest first, its slot postings, and the
 * item plane's side of the breaks among them — what the position gave up, whose transaction also says
 * what fell out of it.
 */
class PositionPlan(val at: WorldBlock, val steps: List<Step>, val refills: List<Refill>, val breaks: List<LedgerEntry> = emptyList())

class ChunkPlan(val chunkX: Int, val chunkZ: Int, val positions: List<PositionPlan>)

sealed interface Reading

class Refused(val reason: String) : Reading

class Planned(val world: UUID, val chunks: List<ChunkPlan>, val rows: Int, val postings: Int) : Reading {
    val positions: Int get() = chunks.sumOf { it.positions.size }
}

/**
 * Reads what a rollback would undo: the block rows and the container postings in the window that the
 * filter keeps, grouped by position and by chunk. Everything the read could not see — a row it could not
 * decode, a walk that ran out of budget, a state or a payload the ledger cannot name — refuses the whole
 * rollback, because going ahead over part of the history puts back part of the place and calls it done.
 */
class RollbackReader(private val ledger: RocksItemLog, private val blocks: BlockLogs) {

    fun around(
        world: UUID,
        x: Int,
        y: Int,
        z: Int,
        radius: Int,
        fromTs: Long,
        toTs: Long,
        keepsRow: (BlockRow) -> Boolean,
        keepsEntry: (LedgerEntry) -> Boolean,
    ): Reading {
        val log = blocks.get(world) ?: return Refused("the block history of this world is not open")
        val inBox = { bx: Int, by: Int, bz: Int ->
            bx in (x - radius)..(x + radius) && by in (y - radius)..(y + radius) && bz in (z - radius)..(z + radius)
        }
        val rows = ArrayList<BlockRow>()
        var unreadable = 0
        var budget = BLOCK_WALK_BUDGET
        for (cx in ((x - radius) shr 4)..((x + radius) shr 4)) {
            for (cz in ((z - radius) shr 4)..((z + radius) shr 4)) {
                val window = log.windowInChunk(cx, cz, fromTs, toTs, budget, inBox)
                if (!window.complete) return tooMuch()
                budget -= window.walked
                unreadable += window.unreadable
                window.rows.filterTo(rows, keepsRow)
                if (rows.size > MAX_ROLLBACK_ROWS) return tooMuch()
            }
        }
        val page = ledger.regionPage(
            world, x - radius, z - radius, x + radius, z + radius, fromTs, toTs,
            limit = MAX_ROLLBACK_ROWS,
            within = { holder ->
                when (holder) {
                    is Container -> inBox(holder.x, holder.y, holder.z)
                    is WorldBlock -> inBox(holder.x, holder.y, holder.z)
                    else -> false
                }
            },
        )
        if (!page.complete) return tooMuch()
        val (slots, positions) = page.entries.filter(keepsEntry).partition { it.holder is Container }
        return plan(world, rows, slots, positions.filter { it.qty < 0 }, unreadable + page.unreadable)
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
            val own = listOf(PlayerInv(user, 0), PlayerEquip(user, 0), PlayerCursor(user), PlayerEnder(user, 0), EntitySlot(user, 0))
            for (holder in own) {
                val page = ledger.holderPage(holder, fromTs, toTs, limit = MAX_ROLLBACK_ROWS)
                if (!page.complete || page.unreadable > 0) return null
                for (entry in page.entries) {
                    val at = when (val far = entry.counterparty) {
                        is Container -> WorldBlock(far.world, far.x, far.y, far.z)
                        is WorldBlock -> far
                        else -> continue
                    }
                    if (blocks.get(at.world) != null) touched.getOrPut(at.world) { HashSet() } += at
                }
            }
        }
        return touched
    }

    /** What a rollback would undo at these positions of one world, read position by position. */
    fun at(
        world: UUID,
        positions: Set<WorldBlock>,
        fromTs: Long,
        toTs: Long,
        keepsRow: (BlockRow) -> Boolean,
        keepsEntry: (LedgerEntry) -> Boolean,
    ): Reading {
        val log = blocks.get(world) ?: return Refused("the block history of this world is not open")
        if (positions.size > MAX_ROLLBACK_POSITIONS) return tooMany(positions.size)
        val rows = ArrayList<BlockRow>()
        val slots = ArrayList<LedgerEntry>()
        val losses = ArrayList<LedgerEntry>()
        var unreadable = 0
        var budget = BLOCK_WALK_BUDGET
        for (at in positions) {
            val window = log.windowAt(at.x, at.y, at.z, fromTs, toTs, budget)
            if (!window.complete) return tooMuch()
            budget -= window.walked
            unreadable += window.unreadable
            window.rows.filterTo(rows, keepsRow)
            for (holder in listOf(Container(world, at.x, at.y, at.z, 0), at)) {
                val page = ledger.holderPage(holder, fromTs, toTs, limit = MAX_ROLLBACK_ROWS)
                if (!page.complete) return tooMuch()
                unreadable += page.unreadable
                for (entry in page.entries.filter(keepsEntry)) {
                    if (entry.holder is Container) slots += entry else if (entry.qty < 0) losses += entry
                }
            }
            if (rows.size + slots.size > MAX_ROLLBACK_ROWS) return tooMuch()
        }
        return plan(world, rows, slots, losses, unreadable)
    }

    private fun plan(
        world: UUID,
        rows: List<BlockRow>,
        kept: List<LedgerEntry>,
        losses: List<LedgerEntry>,
        unreadable: Int,
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
        for (row in rows.sortedWith(compareByDescending<BlockRow> { it.eventId }.thenByDescending { it.ordinal })) {
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
        if (unnamed > 0) return unreadable(unnamed)
        val positions = steps.keys + refills.keys
        if (positions.size > MAX_ROLLBACK_POSITIONS) return tooMany(positions.size)
        val breaks = losses.groupBy { it.holder as WorldBlock }
        val chunks = positions
            .map { PositionPlan(it, steps[it].orEmpty(), refills[it].orEmpty(), breaks[it].orEmpty()) }
            .groupBy { (it.at.x shr 4) to (it.at.z shr 4) }
            .map { (chunk, plans) -> ChunkPlan(chunk.first, chunk.second, plans) }
        return Planned(world, chunks, rows.size, entries.size)
    }

    private fun state(id: Int): String? = ledger.registries.keyOf(RegistryNamespace.BLOCK_STATE, id)

    internal fun tooMany(positions: Int) = Refused(
        "$positions positions changed in that window, more than the $MAX_ROLLBACK_POSITIONS one rollback " +
            "may write; narrow the radius or the time"
    )

    private fun tooMuch() = Refused(
        "that window holds more history than one rollback reads; narrow the radius or the time"
    )

    private fun unreadable(count: Int) = Refused(
        "$count rows in that window could not be read by this build, and a rollback over part of the " +
            "history would put back part of the place; nothing was done"
    )
}
