package io.pfaumc.pfauprotect.command
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Inspector(private val lookups: Lookups) : Listener {
    private val enabled = ConcurrentHashMap.newKeySet<UUID>()

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
        lookups.run(event.player, lookupTargetAt(block), LookupQuery())
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        enabled -= event.player.uniqueId
    }

}
