package io.pfaumc.pfauprotect.capture.item
import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.capture.block.spotOf
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.EnderSignal
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.entity.Trident
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.persistence.PersistentDataType

// A projectile whose item the ledger moved into its own slot. Kept on the entity, because an arrow
// stuck in a wall outlives the server and a set in memory would forget it on the next start, after
// which picking it up would book an item out of a slot that, as far as the ledger knew, stood empty.
private val SHOT = NamespacedKey("pfauprotect", "shot")

/** What to write when a booked projectile stops existing, or null when it has not. */
internal fun projectileEnd(removal: EntityRemoveEvent.Cause): Cause? = when (removal) {
    // Taken back into an inventory or dropped as an item: those events write the move themselves.
    EntityRemoveEvent.Cause.PICKUP, EntityRemoveEvent.Cause.DROP -> null
    // Saved with the chunk and back on the next load, still holding its item.
    EntityRemoveEvent.Cause.UNLOAD, EntityRemoveEvent.Cause.PLAYER_QUIT -> null
    EntityRemoveEvent.Cause.HIT, EntityRemoveEvent.Cause.OUT_OF_WORLD -> Cause.PROJ_HIT_VOID
    else -> Cause.PROJ_DESPAWN
}

// An arrow or a trident is an item for as long as it can be picked up again, so it is booked into the
// entity's own slot and taken out of it by whatever ends it. A snowball, an egg, a pearl or a potion
// is spent the moment it is thrown: nothing of it can ever be picked up.
class ProjectileListener(
    private val capture: ContainerCaptureListener,
    private val codec: ItemFormCodec,
    private val pending: TickCoalescer,
    private val origins: SpawnOrigins,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLaunch(event: PlayerLaunchProjectileEvent) {
        if (!event.shouldConsume()) return
        val form = codec.encodeOrNull(event.itemStack)?.form ?: return
        val projectile = event.projectile
        val intent = when (projectile) {
            is Trident -> Intent(Cause.PROJ_SHOT, to = booked(projectile), form = form, qty = 1)
            is Firework -> Intent(Cause.FIREWORK_LAUNCH, to = Void, form = form, qty = 1)
            else -> Intent(Cause.THROWN_CONSUMED, to = Void, form = form, qty = 1)
        }
        capture.intend(event.player, intent)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val arrow = event.projectile as? AbstractArrow
        // A crossbow paid for its ammunition when it was loaded, and firing hands the loaded crossbow
        // back empty. The arrow is born here out of what the crossbow held.
        if (event.bow?.type == Material.CROSSBOW) {
            capture.intend(player, mutation(Cause.CROSSBOW_SHOOT))
            if (arrow != null && pickable(arrow)) {
                val key = codec.encodeOrNull(arrow.itemStack)?.key ?: return
                pending.add(Void, booked(arrow), Cause.CROSSBOW_SHOOT, key, 1, player.uniqueId)
            }
            return
        }
        if (!event.shouldConsumeItem()) return
        val form = codec.encodeOrNull(event.consumable)?.form ?: return
        val to = if (arrow != null && pickable(arrow)) booked(arrow) else Void
        capture.intend(player, Intent(Cause.PROJ_SHOT, to = to, form = form, qty = 1))
    }

    // The ammunition goes into the crossbow's own form, so loading is one item changing into another.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLoad(event: EntityLoadCrossbowEvent) {
        val player = event.entity as? Player ?: return
        capture.intend(player, mutation(Cause.CROSSBOW_LOAD))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBoost(event: PlayerElytraBoostEvent) {
        if (!event.shouldConsume()) return
        val form = codec.encodeOrNull(event.itemStack)?.form ?: return
        val player = event.player
        capture.intend(
            player,
            Intent(Cause.FIREWORK_LAUNCH, to = Void, form = form, qty = 1, holder = capture.handSlot(player, event.hand)),
        )
    }

    // An arrow nobody booked — one from a dispenser — still comes into the inventory, and out of
    // nowhere is what the ledger knows of where it was.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickup(event: PlayerPickupArrowEvent) {
        val arrow = event.arrow
        val encoded = codec.encodeOrNull(event.item.itemStack) ?: return
        val from = if (unmark(arrow)) EntitySlot(arrow.uniqueId, 0) else Void
        val returning = arrow is Trident && arrow.loyaltyLevel > 0 && arrow.hasDealtDamage()
        val cause = if (returning) Cause.TRIDENT_LOYALTY_RETURN else Cause.PROJ_PICKUP
        capture.intend(event.player, Intent(cause, from = from, form = encoded.form, qty = encoded.count))
    }

    // A loyal trident whose owner is gone falls out as an item.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: EntityDropItemEvent) {
        val arrow = event.entity as? AbstractArrow ?: return
        val item = event.itemDrop
        val encoded = codec.encodeOrNull(item.itemStack) ?: return
        val from = if (unmark(arrow)) EntitySlot(arrow.uniqueId, 0) else Void
        origins.expect(item.uniqueId, from, Cause.TRIDENT_DROP, encoded.key, encoded.count)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemove(event: EntityRemoveEvent) {
        val entity = event.entity
        // An eye of ender that survives is removed first and dropped as an item right after, so the
        // note has to be left while it is being removed.
        if (entity is EnderSignal) {
            if (event.cause != EntityRemoveEvent.Cause.DROP) return
            val key = codec.encodeOrNull(entity.item)?.key ?: return
            origins.expect(Void, Cause.EYE_SURVIVE, key, spotOf(entity.location), 1)
            return
        }
        val arrow = entity as? AbstractArrow ?: return
        val cause = projectileEnd(event.cause) ?: return
        // The server can announce one removal twice; the mark is gone after the first.
        if (!unmark(arrow)) return
        val key = codec.encodeOrNull(arrow.itemStack)?.key ?: return
        pending.add(EntitySlot(arrow.uniqueId, 0), Void, cause, key, 1)
    }

    private fun pickable(arrow: AbstractArrow) = arrow.pickupStatus == AbstractArrow.PickupStatus.ALLOWED

    private fun booked(arrow: AbstractArrow): Holder {
        arrow.persistentDataContainer.set(SHOT, PersistentDataType.BOOLEAN, true)
        return EntitySlot(arrow.uniqueId, 0)
    }

    private fun unmark(arrow: AbstractArrow): Boolean {
        val container = arrow.persistentDataContainer
        if (!container.has(SHOT)) return false
        container.remove(SHOT)
        return true
    }
}
