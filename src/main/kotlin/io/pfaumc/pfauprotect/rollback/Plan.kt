package io.pfaumc.pfauprotect.rollback

import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.LedgerEntry
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
 * what arrived and is taken out again.
 */
class Refill(val at: Container, val form: ByteArray, val damage: Int?, val qty: Int, val posting: PostingRef)

/** Everything a rollback does at one position: its block rows newest first, and its slot postings. */
class PositionPlan(val at: WorldBlock, val steps: List<Step>, val refills: List<Refill>)

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
            within = { it is Container && inBox(it.x, it.y, it.z) },
        )
        if (!page.complete) return tooMuch()
        return plan(world, rows, page.entries.filter(keepsEntry), unreadable + page.unreadable)
    }

    private fun plan(world: UUID, rows: List<BlockRow>, kept: List<LedgerEntry>, unreadable: Int): Reading {
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
                Refill(slot, form, entry.damage, -entry.qty, entry.ref)
        }
        if (unnamed > 0) return unreadable(unnamed)
        val positions = steps.keys + refills.keys
        if (positions.size > MAX_ROLLBACK_POSITIONS) {
            return Refused(
                "${positions.size} positions changed in that window, more than the $MAX_ROLLBACK_POSITIONS " +
                    "one rollback may write; narrow the radius or the time"
            )
        }
        val chunks = positions
            .map { PositionPlan(it, steps[it].orEmpty(), refills[it].orEmpty()) }
            .groupBy { (it.at.x shr 4) to (it.at.z shr 4) }
            .map { (chunk, plans) -> ChunkPlan(chunk.first, chunk.second, plans) }
        return Planned(world, chunks, rows.size, entries.size)
    }

    private fun state(id: Int): String? = ledger.registries.keyOf(RegistryNamespace.BLOCK_STATE, id)

    private fun tooMuch() = Refused(
        "that window holds more history than one rollback reads; narrow the radius or the time"
    )

    private fun unreadable(count: Int) = Refused(
        "$count rows in that window could not be read by this build, and a rollback over part of the " +
            "history would put back part of the place; nothing was done"
    )
}
