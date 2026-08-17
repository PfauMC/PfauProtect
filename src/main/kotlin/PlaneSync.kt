package io.pfaumc.pfauprotect

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
internal fun emptied(state: String) = state.substringBefore('[') in BACKS_NO_ITEM

// The two planes are written by two writer threads and the item side goes through a tick coalescer,
// so for a while after a break the block plane already says air while the item plane still holds the
// item. A position touched inside this window is left for a later pass rather than accused.
internal const val SETTLE_MILLIS = 60_000L

data class PlaneGap(
    val at: WorldBlock,
    // The state the block plane says stands in the position, which is one no item could have come out
    // of: the position lost its block without the item plane ever being told, so it still holds the
    // form of a block that is not there.
    val standing: String,
    // Kept apart because where nothing can be confirmed both planes hold an inference, and a total
    // that mixes the two cannot tell a hole in the capture from an honest guess.
    val fact: Int,
    val inferred: Int,
)

data class PlaneReport(
    val checked: Int,
    val settling: Int,
    val unreadable: Int,
    // A position the block plane holds no row for was held against nothing, and counted as a comparison
    // it would report a clean bill of health for a pass that read one plane alone. Every position
    // placed before the block plane existed is one of these.
    val unrecorded: Int,
    // The other direction: the item plane wrote off at the position more than it was ever given there.
    // No reading of the block plane can settle that, and it is not a holding, so it is neither compared
    // nor reported as one.
    val overdrawn: Int,
    val gaps: List<PlaneGap>,
    val reachedEnd: Boolean,
)

private enum class Outcome { CHECKED, SETTLING, UNREADABLE, UNRECORDED, OVERDRAWN }

/**
 * The third self-check. The transaction invariant catches a movement that lost half of itself and
 * reconciliation catches what a player is holding that the ledger never heard of; neither can see a
 * change recorded in one plane and absent from the other, which from inside each plane on its own is
 * perfectly consistent.
 *
 * The debt this looks for has one shape: a position that lost its block while the item plane was never
 * told, so the item plane still holds the form of a block that is not there. The side holding the debt
 * is the item plane, so that is the side walked, and the block plane is only ever asked about a
 * position the item plane has something to say about. The other direction is not a question worth
 * asking: the item plane deliberately books no liquid, no fire, no portal, no growth and no creative
 * placement, so a block standing where it holds nothing is the ordinary state of most of a world.
 */
class PlaneSync(private val ledger: RocksItemLog, private val blocks: BlockLogs) {

    fun pass(limit: Int, now: Long = System.currentTimeMillis()): PlaneReport {
        val page = ledger.blockPostings(limit)
        val gaps = ArrayList<PlaneGap>()
        var checked = 0
        var settling = 0
        var unreadable = 0
        var unrecorded = 0
        var overdrawn = 0
        for (position in page.positions) {
            when (settle(position, now, gaps)) {
                Outcome.CHECKED -> checked++
                Outcome.SETTLING -> settling++
                Outcome.UNREADABLE -> unreadable++
                Outcome.UNRECORDED -> unrecorded++
                Outcome.OVERDRAWN -> overdrawn++
            }
        }
        return PlaneReport(checked, settling, unreadable, unrecorded, overdrawn, gaps, page.reachedEnd)
    }

    private fun settle(position: BlockPostings, now: Long, gaps: MutableList<PlaneGap>): Outcome {
        val at = position.at
        if (at == null || position.unreadable > 0) return Outcome.UNREADABLE
        var fact = 0
        var inferred = 0
        var newest = 0L
        for (entry in position.entries) {
            if (entry.confidence == Confidence.FACT) fact += entry.qty else inferred += entry.qty
            newest = maxOf(newest, entry.timestamp)
        }
        // Added into one number an inferred write-off cancels a confirmed credit and the position reads
        // as settled, which is a fact written off by a guess: each total has to stand or fall on its own.
        if (fact == 0 && inferred == 0) return Outcome.CHECKED
        if (newest > now - SETTLE_MILLIS) return Outcome.SETTLING
        // A holding stands above zero. Below it the position gave up what it was never given — the debit
        // was booked here and the credit somewhere else or nowhere — and that is the opposite fault.
        if (fact <= 0 && inferred <= 0) return Outcome.OVERDRAWN
        val log = blocks.get(at.world) ?: return Outcome.UNREADABLE
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
