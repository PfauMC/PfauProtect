package io.pfaumc.pfauprotect

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.AnaloguePowerable
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.type.TripwireHook
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.player.PlayerInteractEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// One step of a circuit: a repeater at full delay, a dispenser's own four ticks, a button held for a
// second and a half. Every step writes a fresh note where it arrived, so a chain of any length stays
// attributed while nothing is remembered for longer than a step.
internal const val ENERGY_MILLIS = 3_000L

// How far a component reaches for the energy that moved it: a block powered through a solid block,
// and a dispenser powered from the block above it, are two steps away from their source.
internal const val ENERGY_REACH = 2

// Alternate Current raises no event for the wire it updates, so a component that finds nothing around
// itself walks the wire it touches. A wire line runs at most fifteen blocks per repeater; this covers
// a few of them without letting a walk cost more than a tick can afford.
internal const val WIRE_WALK = 64

// A tripwire line is at most forty blocks between its hooks.
private const val TRIPWIRE_SPAN = 41

// A switch pressed by hand fires its own redstone change inside the same call, well within this.
private const val PRESS_NANOS = 50_000_000L

/**
 * Whose energy is running through a position: the player who pressed, placed, broke or moved
 * something a moment ago, carried from one component to the next. SPEC-v3's water and fire in
 * another medium: every step copies the freshest note around it onto itself.
 *
 * In memory only and short-lived, like every tracker in `Attribution`. A clock renews its own notes
 * as long as it ticks, which is the answer wanted for it: whoever started it.
 */
class Energy(private val now: () -> Long = System::currentTimeMillis) {
    private class Note(val by: Attributed, val at: Long)

    private val notes = ConcurrentHashMap<WorldBlock, Note>()

    val size: Int get() = notes.size

    fun note(at: WorldBlock, by: Attributed) {
        notes[at] = Note(by, now())
    }

    /**
     * The freshest note within `reach` of any of the centres, and of two equally fresh the surer one:
     * a chain is never surer than the note it started from, so the surer note is the better start.
     */
    fun near(centres: Collection<WorldBlock>, reach: Int = ENERGY_REACH): Attributed? {
        val now = now()
        var best: Note? = null
        for (centre in centres) {
            for (dx in -reach..reach) {
                for (dy in -reach..reach) {
                    for (dz in -reach..reach) {
                        val here = centre.copy(x = centre.x + dx, y = centre.y + dy, z = centre.z + dz)
                        val note = notes[here] ?: continue
                        if (now - note.at > ENERGY_MILLIS) continue
                        if (best == null || note.at > best.at ||
                            (note.at == best.at && note.by.confidence.id < best.by.confidence.id)
                        ) {
                            best = note
                        }
                    }
                }
            }
        }
        return best?.by
    }

    fun near(at: WorldBlock, reach: Int = ENERGY_REACH): Attributed? = near(listOf(at), reach)

    fun sweep() {
        val now = now()
        notes.values.removeIf { now - it.at > ENERGY_MILLIS }
    }
}

/**
 * Who set this component going: a note around it, the tripwire line a hook watches, or, where
 * Alternate Current left no notes on the wire, the wire it touches. Read on the component's own
 * region; the walks stop at the region's edge rather than reach into a chunk another thread owns.
 */
internal fun energyAt(block: Block, energy: Energy): Attributed? {
    energy.near(positionOf(block))?.let { return it }
    if (block.type == Material.TRIPWIRE_HOOK) return alongTripwire(block, energy)
    // A wire that found nothing around itself is one link of a line no event is walking; walking
    // the whole line from every link of it would cost the square of its length.
    if (block.type == Material.REDSTONE_WIRE) return null
    return overWire(block, energy)
}

private fun alongTripwire(hook: Block, energy: Energy): Attributed? {
    val facing = (hook.blockData as? TripwireHook)?.facing ?: return null
    val line = ArrayList<WorldBlock>()
    for (step in 1..TRIPWIRE_SPAN) {
        val block = readable(hook, facing, step) ?: break
        when (block.type) {
            Material.TRIPWIRE -> line += positionOf(block)
            else -> break
        }
    }
    return if (line.isEmpty()) null else energy.near(line, reach = 1)
}

private fun overWire(start: Block, energy: Energy): Attributed? {
    val seen = HashSet<WorldBlock>()
    val queue = ArrayDeque<Block>()
    for (next in wireAround(start)) if (seen.add(positionOf(next))) queue += next
    while (queue.isNotEmpty() && seen.size <= WIRE_WALK) {
        for (next in wireAround(queue.removeFirst())) if (seen.add(positionOf(next))) queue += next
    }
    return if (seen.isEmpty()) null else energy.near(seen, reach = 1)
}

// Wire connects to wire beside it, one block up and one block down.
private fun wireAround(from: Block): List<Block> {
    val found = ArrayList<Block>(4)
    for (face in HORIZONTAL) {
        for (dy in -1..1) {
            val block = readable(from, face, 1, dy) ?: continue
            if (block.type == Material.REDSTONE_WIRE) found += block
        }
    }
    return found
}

private val HORIZONTAL = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

// A block this region may read without loading a chunk or reaching into another region's.
private fun readable(from: Block, face: BlockFace, distance: Int, dy: Int = 0): Block? {
    val x = from.x + face.modX * distance
    val y = from.y + dy
    val z = from.z + face.modZ * distance
    if (!from.world.isChunkLoaded(x shr 4, z shr 4)) return null
    if (!Bukkit.isOwnedByCurrentRegion(from.world, x shr 4, z shr 4)) return null
    return from.world.getBlockAt(x, y, z)
}

// The state a switch goes to under the current the event names.
internal fun powered(data: BlockData, current: Int): BlockData = data.clone().also {
    when (it) {
        is AnaloguePowerable -> it.power = current.coerceIn(0, it.maximumPower)
        is Powerable -> it.isPowered = current > 0
    }
}

// What a player presses, steps on or pulls, rather than what their hand merely touches.
private fun isSwitch(type: Material) =
    Tag.BUTTONS.isTagged(type) || Tag.PRESSURE_PLATES.isTagged(type) || type == Material.LEVER

/**
 * The start of every chain a player sets off by hand, and each step of every chain after it.
 *
 * Any block a player right-clicks or steps on gets their note, whatever it is: a door, a note block,
 * a repeater's delay, a chest a comparator reads are all things an observer or a comparator answers
 * to. A switch also gets its own row, written from the redstone change it raises in the same call,
 * which is the one moment its state before and its current after are both known.
 */
class RedstoneListener(private val energy: Energy, private val logs: BlockLogs) : Listener {
    private class Pressed(val at: WorldBlock, val actor: UUID, val nanos: Long)

    private val pressing = ThreadLocal<Pressed?>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.PHYSICAL) return
        val block = event.clickedBlock ?: return
        val at = positionOf(block)
        val actor = event.player.uniqueId
        energy.note(at, Attributed(actor, Confidence.FACT))
        when {
            // A tripwire raises nothing of its own: the change is on the hook, however far along.
            block.type == Material.TRIPWIRE -> tripped(block, actor)
            isSwitch(block.type) -> pressing.set(Pressed(at, actor, System.nanoTime()))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRedstone(event: BlockRedstoneEvent) {
        if (event.oldCurrent == event.newCurrent) return
        val block = event.block
        val at = positionOf(block)
        val press = pressing.get()
        if (press != null && press.at == at && System.nanoTime() - press.nanos <= PRESS_NANOS) {
            pressing.remove()
            switched(block, powered(block.blockData, event.newCurrent), press.actor)
            return
        }
        energyAt(block, energy)?.let { energy.note(at, it) }
    }

    private fun tripped(block: Block, actor: UUID) {
        val data = block.blockData as? Powerable ?: return
        if (data.isPowered) return
        switched(block, powered(data, 1), actor)
    }

    // The log notes the energy of the row itself, like every row it takes that names somebody.
    private fun switched(block: Block, after: BlockData, actor: UUID) {
        val log = logs.get(block.world.uid) ?: return
        log.submit(
            listOf(
                BlockChange(
                    x = block.x,
                    y = block.y,
                    z = block.z,
                    before = block.blockData.asString,
                    after = after.asString,
                    cause = Cause.BLK_PLAYER_SWITCH,
                    actor = actor,
                )
            )
        )
    }
}
