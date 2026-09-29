package io.pfaumc.pfauprotect

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.player.PlayerInteractEvent
import java.util.UUID

// The shape a golem or a wither is built out of, measured from where the mob appears. Three reaches
// past the two the tallest of those shapes needs, which costs a few hundred map lookups on the rare
// event of one being built and nothing at all otherwise.
private const val BUILD_REACH = 3

// The reasons that name a player if anything does. Everything else — a raid, a natural spawn, a
// breeding pair, a spawner — has no person behind it, and asking after one would put a name on the
// nearest builder for something they had nothing to do with.
private val BUILT = setOf(
    SpawnReason.BUILD_WITHER,
    SpawnReason.BUILD_SNOWMAN,
    SpawnReason.BUILD_IRONGOLEM,
    SpawnReason.BUILD_COPPERGOLEM,
)

/**
 * Where the entities that destroy blocks came from. A dispenser is not asked to lend its placer, for
 * the same reason a piston is not asked to lend its builder: a machine somebody left running is not
 * the same as somebody acting, and the answer would be wrong in exactly the cases an investigation
 * cares about.
 */
class EntityOriginListener(
    private val attribution: Attribution,
    private val origins: EntityOrigins,
) : Listener {

    // A spawn egg names no player on the spawn event. The spawn happens inside the click that used
    // the egg, on the same thread, so the click leaves the player here and the mob that comes out
    // takes it. Taken once and cleared, because a note left standing would be read by whatever spawns
    // near that player next.
    private val clicking = ThreadLocal<UUID?>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val item = event.item
        clicking.set(if (item != null && item.type.key.value().endsWith("_spawn_egg")) event.player.uniqueId else null)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpawn(event: CreatureSpawnEvent) {
        val at = positionOf(event.location.block)
        val actor = when (event.spawnReason) {
            SpawnReason.SPAWNER_EGG -> clicking.get().also { clicking.set(null) }
            // A silverfish comes out of the block somebody has just broken, and the break is already
            // noted where it happened.
            SpawnReason.SILVERFISH_BLOCK -> attribution.removerAt(at)?.actor
            in BUILT -> attribution.builderNear(at, BUILD_REACH)?.actor
            else -> null
        } ?: return
        origins.appeared(event.entity.uniqueId, actor)
    }

    // An origin is about one entity and outlives it by nothing. An unload is not an end: the entity
    // goes into the region file and comes back out of it holding the same id, and forgetting where it
    // came from would cost the answer to every chunk that has been walked away from.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemove(event: EntityRemoveEvent) {
        if (event.cause == EntityRemoveEvent.Cause.UNLOAD) return
        origins.gone(event.entity.uniqueId)
    }
}
