package io.pfaumc.pfauprotect.command
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.entity.Entity
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.player.uniqueId !in enabled) return
        if (event.action != Action.LEFT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_BLOCK) return
        // A right click reports once per hand; the off-hand pass would double every lookup.
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        event.isCancelled = true
        // A right click on a face asks about the place in front of it, where something was put or broken;
        // on a chest or a sign it asks about the block itself, whose contents and text are the story.
        val state = block.state
        val target = if (event.action == Action.RIGHT_CLICK_BLOCK && state !is Container && state !is Sign) {
            block.getRelative(event.blockFace)
        } else {
            block
        }
        if (again(event.player, target.location)) return
        lookups.run(event.player, lookupTargetAt(target), LookupQuery())
    }

    private fun again(player: Player, what: Any): Boolean {
        val now = System.currentTimeMillis()
        val before = last.put(player.uniqueId, what to now)
        return before != null && before.first == what && now - before.second < REPEAT_MILLIS
    }

    // A click on an entity asks about the entity: a frame, a stand, a donkey, a villager.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.player.uniqueId !in enabled || event.hand != EquipmentSlot.HAND) return
        event.isCancelled = true
        inspect(event.player, event.rightClicked)
    }

    // A frame or a stand hit while inspecting is asked about rather than broken.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onHit(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (player.uniqueId !in enabled) return
        // A mob or a player is fought as usual: an inspector who could not hit back would be defenceless.
        if (event.entity !is org.bukkit.entity.Hanging && event.entity !is org.bukkit.entity.ArmorStand) return
        event.isCancelled = true
        inspect(player, event.entity)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onHangingHit(event: HangingBreakByEntityEvent) {
        val player = event.remover as? Player ?: return
        if (player.uniqueId !in enabled) return
        event.isCancelled = true
        // An empty frame breaks on the hit without being damaged first.
        inspect(player, event.entity)
    }

    private fun inspect(player: Player, entity: Entity) {
        if (again(player, entity.uniqueId)) return
        val at = entity.location
        lookups.entity(player, entity.uniqueId, entity.type.key.toString(), lookupTargetAt(at))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        enabled -= event.player.uniqueId
        last -= event.player.uniqueId
    }

}

private const val REPEAT_MILLIS = 1_000L
