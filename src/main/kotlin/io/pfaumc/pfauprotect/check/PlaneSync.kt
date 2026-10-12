package io.pfaumc.pfauprotect.check

import io.pfaumc.pfauprotect.attribution.blockNameOf
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.BlockPostings
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.model.WorldBlock
// The states that can stand in a position without an item ever having been made into one. A block row
// stores a number standing for the whole state string, and the check has to be answerable against a
// database at rest, where there is no server to ask what a state is, so this is a set of strings
// rather than a lookup. Water belongs in it for the case worth catching rather than by oversight:
// breaking a waterlogged block leaves water standing where the block was.
private val BACKS_NO_ITEM = setOf(
    "minecraft:air",
    "minecraft:cave_air",
    "minecraft:void_air",
    "minecraft:water",
    "minecraft:lava",
    "minecraft:fire",
    "minecraft:soul_fire",
    "minecraft:nether_portal",
    "minecraft:end_portal",
    "minecraft:end_gateway",
    "minecraft:moving_piston",
    // A piston puts its head down and takes it back without either being an item, so a position it
    // stands in holds nothing, exactly as one it moved through does.
    "minecraft:piston_head",
)

/**
 * Whether a position holding this state emptied: what stands there afterwards is something no item was
 * ever made into. The question is asked from both ends — the capture decides by it whether a change is
 * a disappearance to write off or a block turning into another block, and this check decides by it
 * whether a write-off was owed — and an answer that differed between the two would read as debt in one
 * plane for every change the other let through.
 */
internal fun emptied(state: String) = blockNameOf(state) in BACKS_NO_ITEM

// The two planes are written by two writer threads and the item side goes through a tick coalescer,
// so for a while after a break the block plane already says air while the item plane still holds the
// item. A position touched inside this window is left for a later pass rather than accused.
internal const val SETTLE_MILLIS = 60_000L

data class PlaneGap(
    val at: WorldBlock,
    // The block state the block plane still shows, which no item could have come out of.
    val standing: String,
    // Kept apart so a guess cannot cancel a confirmed credit.
    val fact: Int,
    val inferred: Int,
)

data class PlaneReport(
    val checked: Int,
    val settling: Int,
    val unreadable: Int,
    // Positions the block plane holds no row for, such as everything placed before it existed: not a comparison.
    val unrecorded: Int,
    // Positions the item plane wrote off more than it was ever given: not a holding, so not compared.
    val overdrawn: Int,
    val gaps: List<PlaneGap>,
    val reachedEnd: Boolean,
)

private enum class Outcome { CHECKED, SETTLING, UNREADABLE, UNRECORDED, OVERDRAWN }

/**
 * Finds positions where the block plane says the block is gone while the item plane still books an item
 * there, a miss neither plane can see on its own. Only that direction is walked, from the item plane's
 * postings: the item plane books no liquid, fire, portal, growth or creative placement, so a block
 * standing where nothing is booked is the ordinary state of most of a world.
 */
class PlaneSync(private val ledger: RocksItemLog, private val blocks: BlockLogs) {

    fun pass(limit: Int, now: Long): PlaneReport {
        val page = ledger.blockPostings(limit)
        val gaps = ArrayList<PlaneGap>()
        val counts = page.positions.groupingBy { settle(it, now, gaps) }.eachCount()
        fun count(outcome: Outcome) = counts[outcome] ?: 0
        return PlaneReport(
            count(Outcome.CHECKED), count(Outcome.SETTLING), count(Outcome.UNREADABLE),
            count(Outcome.UNRECORDED), count(Outcome.OVERDRAWN), gaps, page.reachedEnd,
        )
    }

    private fun settle(position: BlockPostings, now: Long, gaps: MutableList<PlaneGap>): Outcome {
        val at = position.at
        if (at == null || position.unreadable > 0) return Outcome.UNREADABLE
        val fact = position.entries.filter { it.confidence == Confidence.FACT }.sumOf { it.qty }
        val inferred = position.entries.filter { it.confidence != Confidence.FACT }.sumOf { it.qty }
        val newest = position.entries.maxOfOrNull { it.timestamp } ?: 0L
        // Added into one number an inferred write-off cancels a confirmed credit and the position reads
        // as settled, which is a fact written off by a guess: each total has to stand or fall on its own.
        if (fact == 0 && inferred == 0) return Outcome.CHECKED
        if (newest > now - SETTLE_MILLIS) return Outcome.SETTLING
        val log = blocks.get(at.world) ?: return Outcome.UNREADABLE
        // A holding stands above zero. Below it the position gave up what it was never given — the debit
        // was booked here and the credit somewhere else or nowhere — and that is the opposite fault. Unless
        // a block already stood there when the block plane first saw the position: a house built before the
        // plugin gives up its planks to the first blast with no credit to set them against, and a map put
        // under it read as thousands of faults a pass.
        if (fact <= 0 && inferred <= 0) {
            val first = log.oldestAt(at.x, at.y, at.z) ?: return Outcome.OVERDRAWN
            val before = ledger.registries.keyOf(RegistryNamespace.BLOCK_STATE, first.stateBefore) ?: return Outcome.UNREADABLE
            return if (emptied(before)) Outcome.OVERDRAWN else Outcome.UNRECORDED
        }
        val standing = log.standingAt(at.x, at.y, at.z)
        if (standing.torn) return Outcome.UNREADABLE
        // A block placed before this plane existed, or by a path the block capture does not cover, is
        // not this check's business, and reporting it would drown the finding that is. It is not a
        // comparison either: there is no second plane to hold the first against.
        val row = standing.row ?: return Outcome.UNRECORDED
        if (row.timestamp > now - SETTLE_MILLIS) return Outcome.SETTLING
        val state = ledger.registries.keyOf(RegistryNamespace.BLOCK_STATE, row.stateAfter) ?: return Outcome.UNREADABLE
        if (emptied(state)) gaps += PlaneGap(at, state, fact, inferred)
        return Outcome.CHECKED
    }
}
