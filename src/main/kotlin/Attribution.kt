package io.pfaumc.pfauprotect

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

// How long a note stands for. It has to cover one step of a chain and no more: water reaching the
// next position and fire reaching the next block each write a fresh note where they arrived, so an
// arbitrarily long chain stays attributed while nothing is remembered for long. The long horizon
// between a block being placed and being set off is the journal's business, not this table's.
internal const val NOTE_MILLIS = 30_000L

// A block gives way in the tick the support goes or the one after it. Every removal is noted, an
// explosion included, so this window is what keeps the table the size of one tick's work.
internal const val SUPPORT_MILLIS = 100L

// Long enough for a block to fall from the build limit. An entity that never lands — one that fell
// out of the world, or that a plugin took away — leaves its note behind, and nothing else drops it.
internal const val FLIGHT_MILLIS = 60_000L

private val FACES = listOf(
    Triple(1, 0, 0),
    Triple(-1, 0, 0),
    Triple(0, 1, 0),
    Triple(0, -1, 0),
    Triple(0, 0, 1),
    Triple(0, 0, -1),
)

private val DIAGONALS = (-1..1).flatMap { dx ->
    (-1..1).flatMap { dy -> (-1..1).map { dz -> Triple(dx, dy, dz) } }
}.filter { (dx, dy, dz) -> abs(dx) + abs(dy) + abs(dz) > 1 }

// The causes under which a row is what put the block at its position. Everything else is a row about
// a block that was already standing there — a break writes a row at the position it emptied, and the
// surviving half of a double chest is rewritten to `type=single` when its partner goes — and the
// actor of such a row put nothing down.
private val PLACING_CAUSES = setOf(Cause.BLK_PLAYER_PLACE, Cause.BLK_BONEMEAL)

/**
 * Somebody worked out rather than somebody witnessed. Everything this file answers with is
 * `INFERRED`; the direct source on the event — a TNT entity whose source is a player — is the
 * caller's own rung and the only one that may carry `FACT`.
 */
data class Attributed(val actor: UUID, val confidence: Confidence = Confidence.INFERRED)

/** A block between the position it broke loose from and the one it lands in. */
// The form travels with the block rather than being fetched back at the landing: the position it left
// is free the moment it leaves, and whatever moves in there during the flight owns the note by then.
data class Falling(
    val from: WorldBlock,
    val state: String,
    val by: Attributed?,
    val form: ByteArray? = null,
)

/**
 * Who is behind a block change the event itself will not name. A player puts down TNT and walks away;
 * a week later somebody else lights it, and the explosion names nobody.
 *
 * Two rungs sit here, and they are reached for separately. The tracker covers the ordinary delay
 * between a thing being noted and being collected and costs nothing but a map lookup, so it is what
 * an ordinary call gets. The journal covers the horizon the tracker has no business holding, at the
 * price of a seek, and has a name of its own so that nobody reaches it without meaning to.
 *
 * Nothing here writes a row and nothing here edits one. A row rewritten after the fact is
 * indistinguishable from one written correctly the first time, and in a plane with no second witness
 * that is the end of the row's value.
 */
class Attribution(
    private val registries: Registries,
    private val blocks: BlockLogs,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Note(val actor: UUID, val at: Long, val state: String? = null)

    private class Flight(val falling: Falling, val at: Long)

    private val placements = ConcurrentHashMap<WorldBlock, Note>()
    private val removals = ConcurrentHashMap<WorldBlock, Note>()
    private val flights = ConcurrentHashMap<UUID, Flight>()

    val isEmpty: Boolean get() = placements.isEmpty() && removals.isEmpty() && flights.isEmpty()

    /**
     * `state` is what was put down, and a note answers for that block and no other. A break notes the
     * position it emptied as much as a placement does, so without it the breaker would go on to
     * answer for whatever moves into the hole while the note stands.
     */
    fun placed(at: WorldBlock, state: String, actor: UUID) {
        placements[at] = Note(actor, now(), state)
    }

    /**
     * Every removal, whoever made it: a support taken away by an explosion, a piston or water
     * attributes what follows exactly as much as one taken away by hand.
     */
    fun removed(at: WorldBlock, actor: UUID) {
        removals[at] = Note(actor, now())
    }

    /**
     * Who put down the block that stands at this position, by the tracker alone. `standing` is the
     * state of the block being asked about, and the answer is refused unless the note was written
     * about that same block: TNT that moved into a position somebody else emptied a moment ago must
     * not lend that somebody to the explosion. The comparison is by block rather than by the whole
     * state, so a re-oriented or waterlogged variant still matches itself.
     */
    fun placerAt(at: WorldBlock, standing: String): Attributed? =
        noted(placements, at, NOTE_MILLIS, blockOf(standing))?.let { Attributed(it.actor) }

    /**
     * The same question of the journal, which costs a seek into RocksDB through JNI and so has no
     * business on a region thread. It answers about ONE position — who put the thing that has just
     * gone off there — and must never be called once per destroyed block of an explosion.
     *
     * Only a row that put the block there may lend its actor, which is decided from what the row
     * says: its cause, and that the position did not already hold this block before it. The newest
     * row of a position is what stands in it today, so a row that fails either test answers with
     * nobody rather than with the row behind it.
     */
    fun journalPlacerAt(at: WorldBlock, standing: String): Attributed? {
        val row = blocks.get(at.world)?.standingAt(at.x, at.y, at.z)?.row ?: return null
        if (row.cause !in PLACING_CAUSES) return null
        val after = registries.keyOf(RegistryNamespace.BLOCK_STATE, row.stateAfter) ?: return null
        val before = registries.keyOf(RegistryNamespace.BLOCK_STATE, row.stateBefore) ?: return null
        val block = blockOf(standing)
        if (blockOf(after) != block || blockOf(before) == block) return null
        return row.actor?.let { Attributed(it) }
    }

    /**
     * The same question for a block that arrived by flowing or by catching fire, which the tracker
     * knows nothing about. A neighbour lends its actor only where its note is about the block that
     * has just arrived, and the most recent of those wins rather than whichever face is tried first.
     * The find is copied onto this position before it is handed back, so the next step of the chain
     * has a note of its own to find and the window only ever has to cover one step.
     */
    fun carriedTo(at: WorldBlock, standing: String): Attributed? {
        placerAt(at, standing)?.let { return it }
        val found = around(placements, at, FACES, NOTE_MILLIS, blockOf(standing)) ?: return null
        placed(at, standing, found.actor)
        return Attributed(found.actor)
    }

    /**
     * Who took away the support under a block that has just given way. One primitive feeds falling
     * sand, a torch or a sign losing its wall, and a hanging entity being knocked down. The six faces
     * are what carries support and are searched first; a corner neighbour counts as a hit, which is a
     * guess and reads as one. Within either ring the most recent removal is the answer.
     */
    fun supportRemoverAt(at: WorldBlock): Attributed? {
        val found = around(removals, at, FACES, SUPPORT_MILLIS)
            ?: around(removals, at, DIAGONALS, SUPPORT_MILLIS)
        return found?.let { Attributed(it.actor) }
    }

    /**
     * Who put down a block anywhere within reach of this position, whatever block it was. A wither and
     * the golems are built out of blocks and appear when the last of them is placed, so the answer is
     * the most recent placement around the shape rather than one at a position the event names.
     */
    fun builderNear(at: WorldBlock, reach: Int): Attributed? {
        var best: Note? = null
        for (dx in -reach..reach) {
            for (dy in -reach..reach) {
                for (dz in -reach..reach) {
                    val here = at.copy(x = at.x + dx, y = at.y + dy, z = at.z + dz)
                    val note = noted(placements, here, NOTE_MILLIS) ?: continue
                    if (best == null || note.at > best.at) best = note
                }
            }
        }
        return best?.let { Attributed(it.actor) }
    }

    // A falling block is attributed in two halves and positional state does not survive the flight:
    // what stands in the source position by the time the block lands is whatever took its place.
    fun tookOff(entity: UUID, falling: Falling) {
        flights[entity] = Flight(falling, now())
    }

    // The window is enforced here rather than left to the sweep, because the sweep runs minutes apart
    // and an entity id handed out again in between would otherwise collect a stranger's flight.
    fun landed(entity: UUID): Falling? =
        flights.remove(entity)?.takeIf { now() - it.at <= FLIGHT_MILLIS }?.falling

    /**
     * Belongs on the per-tick task, alongside the other sweep. The removal notes are the table worth
     * that cadence: they stand for a tenth of a second, and one tick of a vein miner can park
     * thousands of them.
     */
    fun sweepRemovals() {
        val now = now()
        removals.values.removeIf { now - it.at > SUPPORT_MILLIS }
    }

    /**
     * Every table, which reclaims heap and nothing else — each read enforces its own window, so a
     * note left standing here can never be read as a valid one. Minutes apart is often enough.
     */
    fun sweep() {
        val now = now()
        placements.values.removeIf { now - it.at > NOTE_MILLIS }
        removals.values.removeIf { now - it.at > SUPPORT_MILLIS }
        flights.values.removeIf { now - it.at > FLIGHT_MILLIS }
    }

    private fun noted(notes: Map<WorldBlock, Note>, at: WorldBlock, window: Long, block: String? = null): Note? {
        val note = notes[at] ?: return null
        if (now() - note.at > window) return null
        if (block != null && blockOf(note.state ?: return null) != block) return null
        return note
    }

    private fun around(
        notes: Map<WorldBlock, Note>,
        at: WorldBlock,
        offsets: List<Triple<Int, Int, Int>>,
        window: Long,
        block: String? = null,
    ): Note? = offsets
        .mapNotNull { (dx, dy, dz) ->
            noted(notes, at.copy(x = at.x + dx, y = at.y + dy, z = at.z + dz), window, block)
        }
        .maxByOrNull { it.at }

    private fun blockOf(state: String) = state.substringBefore('[')
}

// How long an origin stands for. An entity outlives every other note in this file: a wither built in
// the morning is still a wither in the evening. The origin is dropped when the entity goes, and this
// window only catches the ones whose going nobody saw.
internal const val ORIGIN_MILLIS = 6 * 60 * 60 * 1000L

/**
 * Which player an entity owes its existence to, and so who answers for the blocks it takes away. A
 * wither, a golem and a silverfish are summoned rather than born, and the server names none of them
 * on the event that destroys a block: without this rung every one of them reads as nobody.
 *
 * In memory only, and deliberately. An entity that outlived a restart is answered with nobody rather
 * than with a guess: reading an origin back would need a plane for entities the ledger does not have,
 * and deriving one from the block journal would name the wrong wither the first time two of them
 * stood in one world. A summon is also not a placement — nothing is written down at the moment an
 * entity appears — so there is nothing here for the journal rung to fall back on.
 */
class EntityOrigins(private val now: () -> Long = System::currentTimeMillis) {
    private class Note(val actor: UUID, val at: Long)

    private val origins = ConcurrentHashMap<UUID, Note>()

    val isEmpty: Boolean get() = origins.isEmpty()

    fun appeared(entity: UUID, actor: UUID) {
        origins[entity] = Note(actor, now())
    }

    fun gone(entity: UUID) {
        origins.remove(entity)
    }

    /** Worked out from what a player did, never witnessed on the event that destroyed the block. */
    fun summonerOf(entity: UUID): Attributed? =
        origins[entity]?.takeIf { now() - it.at <= ORIGIN_MILLIS }?.let { Attributed(it.actor) }

    fun sweep() {
        val cutoff = now() - ORIGIN_MILLIS
        origins.values.removeIf { it.at < cutoff }
    }
}
