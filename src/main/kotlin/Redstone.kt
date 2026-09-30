package io.pfaumc.pfauprotect

import io.papermc.paper.entity.Leashable
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.AnaloguePowerable
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.type.TripwireHook
import org.bukkit.entity.Boat
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockReceiveGameEvent
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.entity.EntityBreedEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.entity.EntityKnockbackByEntityEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.event.weather.LightningStrikeEvent
import org.bukkit.util.BoundingBox
import java.util.Locale
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
// A player seen pressing is a fact; that the press is what moved this component is worked out.
internal fun energyAt(block: Block, energy: Energy): Attributed? = found(block, energy)?.inferred()

private fun found(block: Block, energy: Energy): Attributed? {
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

// How long a push, a pull or a knock stays behind the entity it moved: a mob stops within seconds, a
// cart or a boat coasts on for a while.
private const val NUDGE_MILLIS = 10_000L
private const val VEHICLE_NUDGE_MILLIS = 60_000L

// How far a player may stand from a switch and still be named as having been there.
internal const val WITNESS_REACH = 16.0

// A stack of riders is a handful of entities; this only stops a loop the server should never build.
private const val MAX_STACK = 8

/** Who stands behind what set a switch off, and how far away they were when they only stood near. */
class Behind(val by: Attributed?, val distance: Double? = null)

// A step taken is worked out, whatever was seen at the start of it.
internal fun Attributed.inferred(): Attributed =
    if (confidence == Confidence.FACT) copy(confidence = Confidence.INFERRED) else this

/**
 * The last player who moved an entity that goes on moving by itself: a knock, a wind charge, a
 * fishing rod pulling it in, a cart or a boat shoved by hand. Refreshed by every new push.
 */
class Nudges(private val now: () -> Long = System::currentTimeMillis) {
    private class Note(val by: Attributed, val until: Long)

    private val notes = ConcurrentHashMap<UUID, Note>()

    val size: Int get() = notes.size

    fun nudged(entity: Entity, by: Attributed) {
        val window = if (entity is Minecart || entity is Boat) VEHICLE_NUDGE_MILLIS else NUDGE_MILLIS
        notes[entity.uniqueId] = Note(by.inferred(), now() + window)
    }

    fun of(entity: UUID): Attributed? = notes[entity]?.takeIf { now() <= it.until }?.by

    fun sweep() {
        val now = now()
        notes.values.removeIf { now > it.until }
    }
}

/**
 * The player behind an entity, first rung that answers: the entity itself, whoever rides with it,
 * whoever threw or shot it, whoever holds its lead, whoever last pushed it, whoever brought it into the
 * world, whoever tamed it. Null when none of them is a player.
 */
internal fun behind(entity: Entity, nudges: Nudges, entities: EntityOrigins, depth: Int = 0): Attributed? {
    if (entity is Player) return Attributed(entity.uniqueId, Confidence.FACT)
    riderWith(entity)?.let { return Attributed(it.uniqueId, Confidence.FACT) }
    val thrower = when (entity) {
        is Item -> entity.thrower
        is TNTPrimed -> (entity.source as? Player)?.uniqueId
        is Projectile -> (entity.shooter as? Player)?.uniqueId
        else -> null
    }
    thrower?.let { return Attributed(it, Confidence.FACT) }
    // A skeleton's arrow is the skeleton's, and a skeleton somebody led there is theirs.
    val shooter = (entity as? Projectile)?.shooter as? Entity
    if (shooter != null && depth < 2) behind(shooter, nudges, entities, depth + 1)?.let { return it.inferred() }
    val leashed = (entity as? Leashable)?.takeIf { it.isLeashed }?.leashHolder as? Player
    leashed?.let { return Attributed(it.uniqueId, Confidence.FACT) }
    nudges.of(entity.uniqueId)?.let { return it }
    entities.summonerOf(entity.uniqueId)?.let { return it }
    (entity as? Tameable)?.ownerUniqueId?.let { return Attributed(it, Confidence.INFERRED) }
    return null
}

// Anybody riding anywhere in the stack the entity is part of: the pig under the player, the boat
// under the pig.
private fun riderWith(entity: Entity): Player? {
    var root = entity
    for (step in 0 until MAX_STACK) root = root.vehicle ?: break
    val queue = ArrayDeque(listOf(root))
    var seen = 0
    while (queue.isNotEmpty() && seen++ < MAX_STACK * 4) {
        val next = queue.removeFirst()
        if (next is Player) return next
        queue += next.passengers
    }
    return null
}

// The nearest player within reach of the switch, named as a witness and nothing more.
private fun witness(block: Block): Behind {
    val centre = block.location.add(0.5, 0.5, 0.5)
    val nearest = block.world.getNearbyPlayers(centre, WITNESS_REACH).minByOrNull { it.location.distance(centre) }
        ?: return Behind(null)
    return Behind(Attributed(nearest.uniqueId, Confidence.NEARBY), nearest.location.distance(centre))
}

/**
 * The start of every chain a player sets off by hand, and each step of every chain after it.
 *
 * Any block a player right-clicks or steps on gets their note, whatever it is: a door, a note block,
 * a repeater's delay, a chest a comparator reads are all things an observer or a comparator answers
 * to. A switch also gets its own row, written from the redstone change it raises in the same call,
 * which is the one moment its state before and its current after are both known.
 */
class RedstoneListener(
    private val energy: Energy,
    private val logs: BlockLogs,
    private val entities: EntityOrigins,
    private val nudges: Nudges,
) : Listener {
    private class Pressed(val at: WorldBlock, val actor: UUID, val nanos: Long)

    // An entity on a switch, and who stands behind it, for the redstone change it raises in the same
    // call.
    private class Stepped(val at: WorldBlock, val type: String, val behind: Behind, val nanos: Long)

    private val pressing = ThreadLocal<Pressed?>()
    private val stepping = ThreadLocal<Stepped?>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.PHYSICAL) return
        val block = event.clickedBlock ?: return
        val at = positionOf(block)
        val by = Attributed(event.player.uniqueId, Confidence.FACT)
        energy.note(at, by)
        when {
            // A tripwire raises nothing of its own: the change is on the hook, however far along.
            block.type == Material.TRIPWIRE -> tripped(block, Cause.BLK_PLAYER_SWITCH, Behind(by), null)
            isSwitch(block.type) -> pressing.set(Pressed(at, by.actor, System.nanoTime()))
        }
    }

    // Pressure plates, tripwire and a button an arrow hits raise this for anything but a player.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityInteract(event: EntityInteractEvent) {
        val block = event.block
        if (!isSwitch(block.type) && block.type != Material.TRIPWIRE) return
        val entity = event.entity
        val behind = behindSwitch(entity, block)
        behind.by?.let { energy.note(positionOf(block), it) }
        if (block.type == Material.TRIPWIRE) return tripped(block, Cause.BLK_ENTITY_SWITCH, behind, typeOf(entity))
        stepping.set(Stepped(positionOf(block), typeOf(entity), behind, System.nanoTime()))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRedstone(event: BlockRedstoneEvent) {
        if (event.oldCurrent == event.newCurrent) return
        val block = event.block
        val at = positionOf(block)
        val after = powered(block.blockData, event.newCurrent)
        val now = System.nanoTime()
        val press = pressing.get()
        if (press != null && press.at == at && now - press.nanos <= PRESS_NANOS) {
            pressing.remove()
            switched(block, after, Cause.BLK_PLAYER_SWITCH, Behind(Attributed(press.actor, Confidence.FACT)), null)
            return
        }
        val step = stepping.get()
        if (step != null && step.at == at && now - step.nanos <= PRESS_NANOS) {
            stepping.remove()
            switched(block, after, Cause.BLK_ENTITY_SWITCH, step.behind, step.type)
            return
        }
        // A detector rail raises nothing that names the cart on it, so the cart is looked for.
        if (block.type == Material.DETECTOR_RAIL && event.newCurrent > 0) {
            val cart = block.world.getNearbyEntities(BoundingBox.of(block)) { it is Minecart }.firstOrNull()
            if (cart != null) {
                switched(block, after, Cause.BLK_ENTITY_SWITCH, behindSwitch(cart, block), typeOf(cart))
                return
            }
        }
        energyAt(block, energy)?.let { energy.note(at, it) }
    }

    // A vibration names what made it; the sensor answers some ticks later, and finds this note then.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVibration(event: BlockReceiveGameEvent) {
        val type = event.block.type
        if (type != Material.SCULK_SENSOR && type != Material.CALIBRATED_SCULK_SENSOR) return
        val entity = event.entity ?: return
        behindSwitch(entity, event.block).by?.let { energy.note(positionOf(event.block), it.inferred()) }
    }

    // A target block, and anything else a projectile strikes, answers to whoever shot it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHit(event: ProjectileHitEvent) {
        val block = event.hitBlock ?: return
        behind(event.entity)?.let { energy.note(positionOf(block), it.inferred()) }
    }

    // The explosion is announced before it presses the buttons and flips the levers, doors and
    // trapdoors in its reach, so each of them finds the note of whoever set it off. A wind charge is
    // the explosion a player sets off on purpose.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) {
        val by = behind(event.entity)?.inferred() ?: return
        energy.note(positionOf(event.location.block), by)
        for (block in event.blockList()) energy.note(positionOf(block), by)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLightning(event: LightningStrikeEvent) {
        val player = event.lightning.causingPlayer ?: return
        val struck = event.lightning.location.block
        val by = Attributed(player.uniqueId, Confidence.INFERRED)
        energy.note(positionOf(struck), by)
        energy.note(positionOf(struck.getRelative(BlockFace.DOWN)), by)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onKnockback(event: EntityKnockbackByEntityEvent) {
        behind(event.sourceEntity)?.let { nudges.nudged(event.entity, it) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_ENTITY) return
        val caught = event.caught ?: return
        nudges.nudged(caught, Attributed(event.player.uniqueId, Confidence.INFERRED))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCollide(event: VehicleEntityCollisionEvent) {
        val player = event.entity as? Player ?: return
        nudges.nudged(event.vehicle, Attributed(player.uniqueId, Confidence.INFERRED))
    }

    // A cart sped along by powered rails carries whoever powered them. Every moving vehicle raises
    // this every tick, so all it costs off a powered rail is one comparison.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRoll(event: VehicleMoveEvent) {
        val rail = event.to.block
        if (rail.type != Material.POWERED_RAIL) return
        val cart = event.vehicle as? Minecart ?: return
        energy.near(positionOf(rail), reach = 1)?.let { nudges.nudged(cart, it) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        val breeder = event.breeder as? Player ?: return
        entities.appeared(event.entity.uniqueId, breeder.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: EntityPlaceEvent) {
        val player = event.player ?: return
        entities.appeared(event.entity.uniqueId, player.uniqueId)
    }

    private fun behind(entity: Entity): Attributed? = behind(entity, nudges, entities)

    // The ladder, then whatever set the mechanisms around the switch going — a piston that shoved the
    // entity, a rail that sped the cart — and last the nearest player.
    private fun behindSwitch(entity: Entity, block: Block): Behind {
        behind(entity)?.let { return Behind(it) }
        energy.near(positionOf(block))?.let { return Behind(it.inferred()) }
        return witness(block)
    }

    private fun tripped(block: Block, cause: Cause, behind: Behind, type: String?) {
        val data = block.blockData as? Powerable ?: return
        if (data.isPowered) return
        switched(block, powered(data, 1), cause, behind, type)
    }

    // The log notes the energy of the row itself, like every row it takes that names somebody. The
    // payload of an entity's row is the entity's type, and the witness's distance when there is one.
    private fun switched(block: Block, after: BlockData, cause: Cause, behind: Behind, type: String?) {
        val log = logs.get(block.world.uid) ?: return
        val by = behind.by
        val distance = behind.distance?.let { " " + String.format(Locale.ROOT, "%.1f", it) } ?: ""
        log.submit(
            listOf(
                BlockChange(
                    x = block.x,
                    y = block.y,
                    z = block.z,
                    before = block.blockData.asString,
                    after = after.asString,
                    cause = cause,
                    confidence = by?.confidence ?: Confidence.FACT,
                    actor = by?.actor,
                    payloadAfter = type?.let { (it + distance).toByteArray(Charsets.UTF_8) },
                )
            )
        )
    }
}

private fun typeOf(entity: Entity) = entity.type.key.toString()
