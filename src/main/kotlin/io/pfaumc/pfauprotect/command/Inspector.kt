package io.pfaumc.pfauprotect.command
import io.pfaumc.pfauprotect.attribution.typeOf
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import io.pfaumc.pfauprotect.TARGET_RANGE
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.util.RayTraceResult
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Hanging
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Inspector(private val lookups: Lookups) : Listener {
    private val enabled = ConcurrentHashMap.newKeySet<UUID>()
    // What each player last asked about and when: a held click repeats, and each repeat was a whole answer.
    private val last = ConcurrentHashMap<UUID, Pair<Any, Long>>()

    fun toggle(player: Player, desired: Boolean?): Boolean {
        val next = desired ?: (player.uniqueId !in enabled)
        if (next) enabled += player.uniqueId else enabled -= player.uniqueId
        return next
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (player.uniqueId !in enabled) return
        val action = event.action
        val spectator = player.gameMode == GameMode.SPECTATOR
        val left = when (action) {
            Action.LEFT_CLICK_BLOCK -> true
            Action.RIGHT_CLICK_BLOCK -> false
            // Air actions only count in spectator mode: a survival swing at a mob fires LEFT_CLICK_AIR and would
            // flood lookups, and a survival right click in air is eating.
            Action.LEFT_CLICK_AIR -> if (spectator) true else return
            Action.RIGHT_CLICK_AIR -> if (spectator) false else return
            else -> return
        }
        val onBlock = action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK
        // A block click is ours even when the ray finds nothing: else the torch meant for inspection gets broken.
        if (onBlock) event.isCancelled = true
        // A right click reports once per hand; the off-hand pass would double every lookup.
        if (!left && event.hand != EquipmentSlot.HAND) return
        // Our own ray rather than the event's block: a spectator inside terrain gets the block that holds the
        // eye from the vanilla one, and may produce only AIR actions at all.
        val hit = sight(player) ?: return
        val block = hit.hitBlock ?: return
        val state = block.state
        val kind = when (state) {
            is Container -> HitKind.CONTAINER
            is Sign -> HitKind.SIGN
            else -> HitKind.OTHER
        }
        val choice = choose(left, spectator, kind)
        val target = if (choice.inFront) block.getRelative(hit.hitBlockFace ?: return) else block
        event.isCancelled = true
        if (again(player, target.location to choice.part)) return
        lookups.run(player, lookupTargetAt(target), LookupQuery(part = choice.part), dialog = false)
    }

    // A spectator sees through the block the camera is in, so the ray starts where it leaves that one block;
    // the next solid block, a chest against the wall, is what they look at.
    private fun sight(player: Player): RayTraceResult? {
        val direction = player.eyeLocation.direction
        val start = player.eyeLocation.clone()
        var travelled = 0.0
        val inside = start.block
        if (!inside.isPassable) {
            while (start.block == inside && travelled < TARGET_RANGE) {
                start.add(direction.clone().multiply(STEP))
                travelled += STEP
            }
        }
        return player.world.rayTraceBlocks(start, direction, TARGET_RANGE - travelled, FluidCollisionMode.NEVER, false)
    }

    private fun again(player: Player, what: Any): Boolean {
        val now = System.currentTimeMillis()
        val before = last.put(player.uniqueId, what to now)
        return before != null && before.first == what && now - before.second < REPEAT_MILLIS
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.player.uniqueId !in enabled || event.hand != EquipmentSlot.HAND) return
        event.isCancelled = true
        inspect(event.player, event.rightClicked)
    }

    // A frame or a stand hit while inspecting is asked about rather than broken.
    @EventHandler(priority = EventPriority.LOWEST)
    fun onHit(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (player.uniqueId !in enabled) return
        // A mob or a player is fought as usual: an inspector who could not hit back would be defenceless.
        if (event.entity !is Hanging && event.entity !is ArmorStand) return
        event.isCancelled = true
        inspect(player, event.entity)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onHangingHit(event: HangingBreakByEntityEvent) {
        val player = event.remover as? Player ?: return
        if (player.uniqueId !in enabled) return
        event.isCancelled = true
        // An empty frame breaks on the hit without being damaged first.
        inspect(player, event.entity)
    }

    private fun inspect(player: Player, entity: Entity) {
        if (again(player, entity.uniqueId)) return
        lookups.entity(player, entity.uniqueId, typeOf(entity), lookupTargetAt(entity.location))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        enabled -= event.player.uniqueId
        last -= event.player.uniqueId
    }

}

private const val REPEAT_MILLIS = 1_000L

private const val STEP = 0.05

enum class HitKind { CONTAINER, SIGN, OTHER }

/** What a click asks about: the block hit or the place in front of its face, and which part of it. */
data class Choice(val inFront: Boolean, val part: Part)

fun choose(left: Boolean, spectator: Boolean, kind: HitKind): Choice = when {
    left -> Choice(false, if (kind == HitKind.CONTAINER) Part.BLOCK else Part.ALL)
    spectator -> Choice(false, Part.ALL)
    kind == HitKind.CONTAINER -> Choice(false, Part.CONTENTS)
    kind == HitKind.SIGN -> Choice(false, Part.ALL)
    else -> Choice(true, Part.ALL)
}
