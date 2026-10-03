package io.pfaumc.pfauprotect.capture.entity

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import io.pfaumc.pfauprotect.attribution.Attributed
import io.pfaumc.pfauprotect.attribution.Attribution
import io.pfaumc.pfauprotect.attribution.EntityOrigins
import io.pfaumc.pfauprotect.attribution.culprit
import io.pfaumc.pfauprotect.capture.block.BlockDestructionListener
import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.capture.block.explosionCause
import io.pfaumc.pfauprotect.capture.block.firedBy
import io.pfaumc.pfauprotect.capture.block.litBy
import io.pfaumc.pfauprotect.capture.item.positionOf
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.EntityChange
import net.minecraft.nbt.NbtIo
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.storage.TagValueOutput
import org.bukkit.Location
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.entity.Boat
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Hanging
import org.bukkit.entity.LeashHitch
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.world.entity.Entity as NmsEntity

// A frame broken by a hit is seen by the break and then once more by its removal, and only the first
// knows why. Long enough to cover the two, short enough to forget a uuid that will not come back.
private const val SEEN_MILLIS = 10_000L

// The two ways of leaving the world that are an end: killed, and taken out. The rest are the entity
// saved with its chunk, gone with its player or to another dimension.
private val GONE = setOf(NmsEntity.RemovalReason.KILLED, NmsEntity.RemovalReason.DISCARDED)

/**
 * The whole NBT of an entity as it is now, the way the server saves it, passengers and all. Forced, so a
 * mob that is dying and an entity already marked removed are saved as they were and not refused.
 */
internal fun snapshotOf(entity: NmsEntity): ByteArray? {
    val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess())
    if (!entity.saveAsPassenger(output, true, true, true)) return null
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { NbtIo.write(output.buildResult(), it) }
    return bytes.toByteArray()
}

/**
 * Whether an entity is part of the place it stands in. A monster nobody named and nothing holds would
 * despawn by itself, and putting it back would add to the place rather than restore it; an animal, a
 * villager, a golem, a pet, anything named or held is the place.
 */
internal fun keepsItsPlace(entity: NmsEntity): Boolean =
    entity !is Mob || entity.isPersistenceRequired || entity.requiresCustomPersistence() || !entity.removeWhenFarAway(Double.MAX_VALUE)

/** Why an entity went and who stands behind it. */
internal class Culprit(val cause: Cause, val by: Attributed?)

/**
 * The entity plane's side of an entity leaving the world: killed, broken, blown up. Its whole NBT is
 * taken while the event still has it, and the row is written a tick on, once the items that fell out of
 * it have spawned and can be named (SPEC-v6 §2.1, §2.5).
 */
class EntityCapture(
    private val logs: BlockLogs,
    private val origins: SpawnOrigins,
    private val attribution: Attribution,
    private val entities: EntityOrigins,
    // Runs a task on the region of the location a tick later.
    private val later: (Location, () -> Unit) -> Unit,
) : Listener {
    private val seen = ConcurrentHashMap<UUID, Long>()

    // A player's own death is the item plane's, slot by slot; it has nothing to put back.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity is Player || !keepsItsPlace((entity as CraftEntity).handle)) return
        removed(entity, culpritOf(entity, Cause.ENTITY_KILLED))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakEvent) {
        val hanging = event.entity
        val remover = (event as? HangingBreakByEntityEvent)?.remover
        val culprit = when {
            remover != null -> byEntity(remover, Cause.ENTITY_BROKEN)
            // The wall behind it went, and whoever took the wall away took the frame with it.
            event.cause == HangingBreakEvent.RemoveCause.PHYSICS -> {
                val wall = hanging.location.block.getRelative(hanging.attachedFace)
                Culprit(Cause.ENTITY_BROKEN, attribution.supportRemoverAt(positionOf(wall)))
            }
            else -> Culprit(Cause.ENTITY_BROKEN, null)
        }
        removed(hanging, culprit)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVehicleDestroy(event: VehicleDestroyEvent) {
        val attacker = event.attacker
        removed(event.vehicle, if (attacker == null) Culprit(Cause.ENTITY_BROKEN, null) else byEntity(attacker, Cause.ENTITY_BROKEN))
    }

    // What leaves without a break of its own: a crystal blown up, a frame or a cart taken by `/kill`. A
    // frame's `/kill` raises no removal event with a cause at all, so the world's own removal is asked, and
    // the server's reason for it says whether it was an end.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemoveFromWorld(event: EntityRemoveFromWorldEvent) {
        val entity = event.entity
        if (entity is LivingEntity) return
        if (entity !is Hanging && entity !is Minecart && entity !is Boat && entity !is EnderCrystal) return
        if ((entity as CraftEntity).handle.removalReason !in GONE) return
        removed(entity, culpritOf(entity, Cause.ENTITY_BROKEN))
    }

    // A lead's knot goes by itself the moment its last lead does, and the lead is on the mob's own NBT.
    private fun removed(entity: Entity, culprit: Culprit) {
        if (entity is LeashHitch) return
        val now = System.currentTimeMillis()
        if (seen.size > SEEN_CAP) seen.values.removeIf { now - it > SEEN_MILLIS }
        if (seen.putIfAbsent(entity.uniqueId, now) != null) return
        val log = logs.get(entity.world.uid) ?: return
        val nbt = snapshotOf((entity as CraftEntity).handle) ?: return
        val block = entity.location.block
        val uuid = entity.uniqueId
        val type = entity.type.key.toString()
        later(entity.location) {
            log.submit(
                listOf(
                    EntityChange(
                        block.x, block.y, block.z, EntityKind.REMOVED, culprit.cause, type, uuid, now,
                        culprit.by?.confidence ?: Confidence.FACT, culprit.by.culprit(),
                        before = nbt, drops = origins.droppedFor(uuid),
                    )
                )
            )
        }
    }

    /**
     * Who killed it: the player the game names as killer; else what hit it last, followed to whoever stands
     * behind that; else the fire or the lava it burned in, to whoever put that down.
     */
    private fun culpritOf(entity: Entity, own: Cause): Culprit {
        (entity as? LivingEntity)?.killer?.let { return Culprit(own, Attributed(it.uniqueId, Confidence.FACT)) }
        val damage = entity.lastDamageCause
        (damage as? EntityDamageByEntityEvent)?.damager?.let { return byEntity(it, own) }
        val at = positionOf(entity.location.block)
        val by = when (damage?.cause) {
            DamageCause.FIRE, DamageCause.FIRE_TICK -> attribution.placerAt(at, "minecraft:fire")
            DamageCause.LAVA -> attribution.placerAt(at, "minecraft:lava")
            else -> null
        }
        return Culprit(own, by?.copy(confidence = Confidence.INFERRED))
    }

    private fun byEntity(damager: Entity, own: Cause): Culprit {
        val shooter = firedBy(damager)
        val exploded = damager.type in EXPLODING
        val cause = if (exploded) explosionCause(damager.type) else own
        (damager as? Player)?.let { return Culprit(own, Attributed(it.uniqueId, Confidence.FACT)) }
        litBy(damager)?.let { return Culprit(cause, Attributed(it.uniqueId, Confidence.FACT)) }
        (shooter as? Player)?.let { return Culprit(cause, Attributed(it.uniqueId, Confidence.FACT)) }
        if (damager.type == EntityType.TNT) {
            attribution.placerAt(positionOf(damager.location.block), BlockDestructionListener.TNT)?.let { return Culprit(cause, it) }
        }
        entities.summonerOf(shooter.uniqueId)?.let { return Culprit(cause, it) }
        // A dog set on somebody's cow is its owner's doing, as far as anything can say.
        ((shooter as? Tameable)?.owner as? Player)?.let { return Culprit(cause, Attributed(it.uniqueId, Confidence.INFERRED)) }
        return Culprit(cause, null)
    }

    private companion object {
        const val SEEN_CAP = 1024
        val EXPLODING = setOf(
            EntityType.TNT, EntityType.CREEPER, EntityType.END_CRYSTAL, EntityType.FIREBALL,
            EntityType.SMALL_FIREBALL, EntityType.WITHER_SKULL, EntityType.WITHER, EntityType.TNT_MINECART,
        )
    }
}
