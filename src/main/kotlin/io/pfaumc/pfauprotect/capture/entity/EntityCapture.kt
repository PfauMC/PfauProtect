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
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.entity.EntityMountEvent
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import net.minecraft.nbt.CompoundTag
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
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

/**
 * What of an entity's NBT changes by itself from one tick to the next: where it is and how it moves, how
 * hurt or hungry or old it is, what it remembers, the server's own bookkeeping. A player's hand is not
 * behind any of it, so two snapshots differing only here are the same entity, and a rollback putting an
 * entity back keeps these as they are now. What it carries is the item plane's and stays significant.
 */
internal val VOLATILE = setOf(
    "Pos", "Motion", "Rotation", "FallDistance", "fall_distance", "Fire", "fire", "Air", "OnGround",
    "PortalCooldown", "Health", "HurtTime", "HurtByTimestamp", "DeathTime", "AbsorptionAmount", "Brain",
    "InLove", "LoveCause", "Age", "ForcedAge", "TicksFrozen", "active_effects", "attributes", "Leash", "leash",
    "Offers", "Xp", "LastRestock", "RestocksToday", "LastGossipDecay", "Gossips", "FoodLevel",
    "Paper.Origin", "Paper.OriginWorld", "Paper.SpawnReason", "Spigot.ticksLived", "Bukkit.updateLevel",
    "Bukkit.Aware", "WorldUUIDLeast", "WorldUUIDMost",
)

internal fun nbtOf(bytes: ByteArray): CompoundTag = NbtIo.read(DataInputStream(ByteArrayInputStream(bytes)))

internal fun significant(tag: CompoundTag): CompoundTag = tag.copy().also { copy -> for (key in VOLATILE) copy.remove(key) }

/** Whether a player's hand changed anything about the entity between two snapshots of it. */
internal fun changedBetween(before: ByteArray, after: ByteArray): Boolean = significant(nbtOf(before)) != significant(nbtOf(after))

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
    // Runs a task on the entity's own scheduler a tick later.
    private val laterOn: (Entity, () -> Unit) -> Unit = { _, _ -> },
) : Listener {
    private val seen = ConcurrentHashMap<UUID, Long>()

    // One reading of an entity a player is handling at a time: a click raises two events on an armour stand.
    private val handling = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * A player's hand on an entity (SPEC-v6 §2.3). The events come before the hand acts, so this is the
     * entity as it was; a tick later, on its own thread, it is read again, and a row is written only if
     * anything besides what changes by itself has changed.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) = handled(event.rightClicked, event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteractAt(event: PlayerInteractAtEntityEvent) = handled(event.rightClicked, event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFrame(event: PlayerItemFrameChangeEvent) = handled(event.itemFrame, event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStand(event: PlayerArmorStandManipulateEvent) = handled(event.rightClicked, event.player)

    private fun handled(entity: Entity, player: Player) {
        if (entity is Player || !handling.add(entity.uniqueId)) return
        val log = logs.get(entity.world.uid)
        val before = log?.let { snapshotOf((entity as CraftEntity).handle) }
        if (before == null) {
            handling.remove(entity.uniqueId)
            return
        }
        val block = entity.location.block
        val (x, y, z) = Triple(block.x, block.y, block.z)
        laterOn(entity) {
            handling.remove(entity.uniqueId)
            // Killed by the hand, it is a removal and the death writes it.
            if (!entity.isValid) return@laterOn
            val after = snapshotOf((entity as CraftEntity).handle) ?: return@laterOn
            if (!changedBetween(before, after)) return@laterOn
            log.submit(
                listOf(
                    EntityChange(
                        x, y, z, EntityKind.CHANGED, Cause.ENTITY_CHANGED, entity.type.key.toString(), entity.uniqueId,
                        actor = player.uniqueId, before = before, after = after,
                    )
                )
            )
        }
    }

    /**
     * A mob led away (SPEC-v6 §2.4): put on a lead, ridden off, carried in a boat or a cart with a player
     * in it. What is kept is the mob as it was, which says where it stood. A player's own pet is theirs
     * to walk.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeash(event: PlayerLeashEntityEvent) = led(event.entity, event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMount(event: EntityMountEvent) {
        val rider = event.entity
        val mount = event.mount
        when {
            rider is Player && mount is LivingEntity -> led(mount, rider)
            rider is LivingEntity && rider !is Player -> mount.passengers.filterIsInstance<Player>().firstOrNull()?.let { led(rider, it) }
        }
    }

    private fun led(entity: Entity, player: Player) {
        if (entity is Player || ((entity as? Tameable)?.owner?.uniqueId == player.uniqueId)) return
        val log = logs.get(entity.world.uid) ?: return
        val before = snapshotOf((entity as CraftEntity).handle) ?: return
        val block = entity.location.block
        log.submit(
            listOf(
                EntityChange(
                    block.x, block.y, block.z, EntityKind.MOVED, Cause.ENTITY_LED, entity.type.key.toString(), entity.uniqueId,
                    actor = player.uniqueId, before = before,
                )
            )
        )
    }

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

    /**
     * A player killed by another (SPEC-v6 §2.6): where, by whom, and what fell out of them. The drops are
     * the item plane's, slot by slot; this row is how a rollback of the killer finds the victim. A death
     * nobody stands behind — a fall, a zombie — is no grief between players and is left to the item plane.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val victim = event.entity
        val culprit = culpritOf(victim, Cause.PLAYER_KILLED)
        val killer = culprit.by.culprit() ?: return
        if (killer == victim.uniqueId) return
        val log = logs.get(victim.world.uid) ?: return
        val block = victim.location.block
        val now = System.currentTimeMillis()
        later(victim.location) {
            log.submit(
                listOf(
                    EntityChange(
                        block.x, block.y, block.z, EntityKind.PLAYER_DIED, culprit.cause, "minecraft:player", victim.uniqueId,
                        now, culprit.by?.confidence ?: Confidence.FACT, killer, drops = origins.droppedFor(victim.uniqueId),
                    )
                )
            )
        }
    }

    /** A removal some other part of the plugin writes itself — a rollback taking an entity away. */
    fun forget(entity: UUID) {
        seen[entity] = System.currentTimeMillis()
    }

    // A lead's knot goes by itself the moment its last lead does, and the lead is on the mob's own NBT.
    // A mob let out of a bucket names no player on its spawn; the emptying that lets it out comes first, on
    // the same thread, and leaves the player here for the spawn to take.
    private val emptying = ThreadLocal<UUID?>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (event.bucket.key.value() !in NOT_A_MOB) emptying.set(event.player.uniqueId)
    }

    /**
     * A mob a player brought into the world (SPEC-v6 §2.2): out of an egg or a bucket, bred, built. Who did
     * it is what the attribution already noted for the entity, or the bucket's player.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpawn(event: CreatureSpawnEvent) {
        val entity = event.entity
        val bucket = emptying.get().also { emptying.set(null) }
        val (cause, by) = when (event.spawnReason) {
            SpawnReason.SPAWNER_EGG -> Cause.SPAWN_EGG_USE to entities.summonerOf(entity.uniqueId)?.copy(confidence = Confidence.FACT)
            SpawnReason.BREEDING -> Cause.MOB_BRED to entities.summonerOf(entity.uniqueId)?.copy(confidence = Confidence.FACT)
            SpawnReason.BUCKET -> Cause.BUCKET_RELEASE_MOB to bucket?.let { Attributed(it, Confidence.FACT) }
            in BUILT -> Cause.BLK_FORM to entities.summonerOf(entity.uniqueId)
            else -> return
        }
        created(entity, cause, by ?: return)
    }

    // An armour stand, a boat, a cart or a crystal put down from the hand.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: EntityPlaceEvent) {
        val player = event.player ?: return
        created(event.entity, Cause.PLACE_ENTITY_ITEM, Attributed(player.uniqueId, Confidence.FACT))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHang(event: HangingPlaceEvent) {
        val player = event.player ?: return
        created(event.entity, Cause.PLACE_ENTITY_ITEM, Attributed(player.uniqueId, Confidence.FACT))
    }

    // Nothing but its uuid and where it appeared: what a rollback does with it is take it away again.
    private fun created(entity: Entity, cause: Cause, by: Attributed) {
        val log = logs.get(entity.world.uid) ?: return
        val block = entity.location.block
        log.submit(
            listOf(
                EntityChange(
                    block.x, block.y, block.z, EntityKind.CREATED, cause, entity.type.key.toString(), entity.uniqueId,
                    confidence = by.confidence, actor = by.culprit(),
                )
            )
        )
    }

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
        // Buckets that pour out something other than a mob.
        val NOT_A_MOB = setOf("water_bucket", "lava_bucket", "powder_snow_bucket", "milk_bucket", "bucket")
        val BUILT = setOf(SpawnReason.BUILD_IRONGOLEM, SpawnReason.BUILD_SNOWMAN, SpawnReason.BUILD_WITHER, SpawnReason.BUILD_COPPERGOLEM)
        val EXPLODING = setOf(
            EntityType.TNT, EntityType.CREEPER, EntityType.END_CRYSTAL, EntityType.FIREBALL,
            EntityType.SMALL_FIREBALL, EntityType.WITHER_SKULL, EntityType.WITHER, EntityType.TNT_MINECART,
        )
    }
}
