package io.pfaumc.pfauprotect.capture.entity

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import io.pfaumc.pfauprotect.Settings
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
import io.pfaumc.pfauprotect.capture.item.wornThrough
import io.papermc.paper.event.entity.EntityDamageItemEvent
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.EntityChange
import net.minecraft.nbt.NbtIo
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.storage.TagValueOutput
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.craftbukkit.damage.CraftDamageSource
import org.bukkit.damage.DamageSource
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.LightningStrike
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.EntityTransformEvent.TransformReason
import org.bukkit.event.entity.PigZapEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.entity.AbstractVillager
import org.bukkit.Bukkit
import org.bukkit.entity.ZombieVillager
import org.bukkit.persistence.PersistentDataType
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
import org.bukkit.event.entity.EntityCombustByBlockEvent
import org.bukkit.event.entity.EntityDamageByBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketEntityEvent
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
 * A mob a player has had a hand in: named, led, fed, ridden, sheared, brought in. Kept on the mob itself,
 * so it outlives a restart and an unloaded chunk, and left out of every comparison of two snapshots.
 */
internal val TOUCHED = NamespacedKey("pfauprotect", "touched")
private const val TOUCHED_TAG = "pfauprotect:touched"

// What a row's death says of a mob that became another: the reason after it, lightning, infection, cured.
internal const val TRANSFORMED = "transform/"

// As many of one kind in one chunk as a farm keeps: a cow kitchen, an iron farm, a chicken cooker.
internal const val CROWD = 8

// How long a change around a mob still answers for its death: water let in, the floor taken away, a
// magma block put down. Longer than any mob takes to drown or dry out; older than that, the place was
// simply like that.
internal const val FRESH_MILLIS = 10 * 60 * 1000L

/**
 * Whether a death nobody stands behind is still worth a row: a mob somebody had a hand in, or one that is
 * part of its place and not one of a crowd. A farm's cows dying in its lava or the zombies a night spawned
 * are nobody's grief, and a row for each would bury what is.
 */
internal fun worthRecording(touched: Boolean, keepsItsPlace: Boolean, sameKindInChunk: Int, crowd: Int = CROWD): Boolean =
    touched || keepsItsPlace && sameKindInChunk < crowd

/**
 * The whole NBT of an entity as it is now, the way the server saves it. Forced, so a mob that is dying and
 * an entity already marked removed are saved as they were and not refused. Without its passengers: each
 * is an entity of its own with rows of its own, a rider's whole player would be saved with it (every mount
 * a change), and a boat brought back with a cow that still lives would clash with the cow's UUID.
 */
internal fun snapshotOf(entity: NmsEntity): ByteArray? {
    val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess())
    if (!entity.saveAsPassenger(output, true, true, true)) return null
    val tag = output.buildResult().also { it.remove(NmsEntity.TAG_PASSENGERS) }
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { NbtIo.write(tag, it) }
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
    "Offers", "Xp", "LastRestock", "RestocksToday", "LastGossipDecay", "Gossips", "FoodLevel", "EatingHaystack",
    "Paper.Origin", "Paper.OriginWorld", "Paper.SpawnReason", "Spigot.ticksLived", "Bukkit.updateLevel",
    "Bukkit.Aware", "WorldUUIDLeast", "WorldUUIDMost",
    // A pet sat down or stood up: only its owner can, so it is never anybody else's doing.
    "Sitting",
)

internal fun nbtOf(bytes: ByteArray): CompoundTag = NbtIo.read(DataInputStream(ByteArrayInputStream(bytes)))

internal fun significant(tag: CompoundTag): CompoundTag = tag.copy().also { copy ->
    for (key in VOLATILE) copy.remove(key)
    // The plugin's own mark is no hand of a player's.
    (copy.get("BukkitValues") as? CompoundTag)?.let { values ->
        values.remove(TOUCHED_TAG)
        if (values.isEmpty) copy.remove("BukkitValues")
    }
}

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
    // Runs a task off the region threads, where the journal may be read.
    private val offThread: (() -> Unit) -> Unit = { it() },
) : Listener {
    private val seen = ConcurrentHashMap<UUID, Long>()

    // Who set an entity alight, and when, until it dies or the burning is long over.
    private val alight = ConcurrentHashMap<UUID, Pair<Attributed, Long>>()

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
        if (entity is Player) return
        touch(entity)
        if (!handling.add(entity.uniqueId)) return
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
                        // The wool a pair of shears cut off: changed back, the sheep has it again, and the
                        // pile has to be taken back from whoever picked it up.
                        actor = player.uniqueId, before = before, after = after, drops = origins.droppedFor(entity.uniqueId),
                    )
                )
            )
        }
    }

    // An entity's own inventory a player has open, as the entity was when it opened: a chest boat, a cart,
    // a donkey's chest, a horse's saddle and armour.
    private val opened = ConcurrentHashMap<UUID, Pair<ByteArray, Long>>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        val entity = event.inventory.holder as? Entity ?: return
        // A villager's window trades, and what trading changes is the item plane's and its own.
        if (entity is Player || entity is AbstractVillager || logs.get(entity.world.uid) == null) return
        touch(entity)
        opened[event.player.uniqueId] = (snapshotOf((entity as CraftEntity).handle) ?: return) to System.currentTimeMillis()
    }

    /**
     * What a player took out of an entity's inventory or put into it, read whole when the window shuts:
     * a changed entity on that player, so a rollback of them puts the chest boat's or the donkey's things
     * back and takes them from whoever has them, as it does for a sheep's wool.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val (before, since) = opened.remove(event.player.uniqueId) ?: return
        val entity = event.inventory.holder as? Entity ?: return
        val player = event.player.uniqueId
        val log = logs.get(entity.world.uid) ?: return
        val block = entity.location.block
        val read = read@{
            if (!entity.isValid) return@read
            val after = snapshotOf((entity as CraftEntity).handle) ?: return@read
            if (!changedBetween(before, after)) return@read
            log.submit(
                listOf(
                    EntityChange(
                        block.x, block.y, block.z, EntityKind.CHANGED, Cause.ENTITY_CHANGED, entity.type.key.toString(), entity.uniqueId,
                        // When the window opened: what the slots did since is what goes back with it.
                        since, actor = player, before = before, after = after,
                    )
                )
            )
        }
        if (Bukkit.isOwnedByCurrentRegion(entity)) read() else laterOn(entity, read)
    }

    /**
     * Wolf armour broken by hits on the wolf: the wolf as it was with it, against whoever dealt the hit that
     * broke it, so a rollback of them puts the armour back. The rest of its wear is left as it is.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWornThrough(event: EntityDamageItemEvent) {
        val entity = event.entity
        if (entity is Player || !wornThrough(event.item, event.damage)) return
        val damager = (entity.lastDamageCause as? EntityDamageByEntityEvent)?.damager
        val by = (if (damager != null) byEntity(damager, Cause.ENTITY_CHANGED) else culpritOf(entity, Cause.ENTITY_CHANGED)).by ?: return
        val log = logs.get(entity.world.uid) ?: return
        val before = snapshotOf((entity as CraftEntity).handle) ?: return
        val block = entity.location.block
        laterOn(entity) {
            val after = snapshotOf((entity as CraftEntity).handle) ?: return@laterOn
            log.submit(
                listOf(
                    EntityChange(
                        block.x, block.y, block.z, EntityKind.CHANGED, Cause.ENTITY_CHANGED, entity.type.key.toString(), entity.uniqueId,
                        confidence = by.confidence, actor = by.culprit(), before = before, after = after,
                    )
                )
            )
        }
    }

    /**
     * A mob led away (SPEC-v6 §2.4): put on a lead, ridden off, carried in a boat or a cart with a player
     * in it; and the boat or the cart itself, ridden off. What is kept is the entity as it was, which says
     * where it stood. A player's own pet is theirs to walk.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeash(event: PlayerLeashEntityEvent) {
        // Tied to a fence, a mob passes from the player's lead to the knot: led already, by the same player.
        if (event.leashHolder !is LeashHitch) led(event.entity, event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMount(event: EntityMountEvent) {
        val rider = event.entity
        val mount = event.mount
        when {
            // A player getting into somebody's boat or cart rides off with it and with whatever already
            // sits in it: a cow that climbed into an empty boat goes where the player steers.
            rider is Player -> {
                led(mount, rider)
                for (aboard in mount.passengers) led(aboard, rider)
            }
            rider is LivingEntity -> mount.passengers.filterIsInstance<Player>().firstOrNull()?.let { led(rider, it) }
        }
    }

    private fun led(entity: Entity, player: Player) {
        if (entity is Player) return
        touch(entity)
        if ((entity as? Tameable)?.owner?.uniqueId == player.uniqueId) return
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

    /**
     * A mob's death: always when somebody stands behind it — killed by a player's hand, a player's dog, or
     * a change a player made around it a little before — and otherwise only when [worthRecording] says so.
     * A player's own death is the item plane's, slot by slot; it has nothing to put back.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity is Player) return
        val lit = alight.remove(entity.uniqueId)
        val culprit = culpritOf(entity, Cause.ENTITY_KILLED, lit)
        // A sculk catalyst nearby blooms off this death, and the sculk is whoever stands behind it.
        culprit.by.culprit()?.let { attribution.killed(positionOf(entity.location.block), it) }
        if (culprit.by.culprit() != null) return removed(entity, culprit, deathOf(event.damageSource))
        val touched = entity.persistentDataContainer.has(TOUCHED)
        // The crowd is only counted for a mob that might be one of it. Crammed to death is a crowd by
        // definition, however few of them the cramming has left by the time this one goes.
        val crowd = when {
            touched -> 0
            entity.lastDamageCause?.cause == DamageCause.CRAMMING -> Settings.crowd
            else -> entity.location.chunk.entities.count { it.type == entity.type }
        }
        val worth = worthRecording(touched, keepsItsPlace((entity as CraftEntity).handle), crowd, Settings.crowd)
        val around = aroundOf(entity, entity.lastDamageCause)
        if (!worth && around.isEmpty()) return
        removed(entity, culprit, deathOf(event.damageSource), around, worth)
    }

    // Who sheared a mooshroom, for the cow it turns into a moment later.
    private val shearers = ConcurrentHashMap<UUID, UUID>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShear(event: PlayerShearEntityEvent) {
        if (event.entity.type == EntityType.MOOSHROOM) shearers[event.entity.uniqueId] = event.player.uniqueId
    }

    /**
     * One mob becoming another under a new id: a villager struck by lightning or killed by a zombie, a
     * zombie drowned, a zombie villager cured, a pig struck, a mooshroom sheared. Written as the old one
     * gone and the new one brought in, on whoever stands behind it, so a rollback of them brings the old
     * one back and takes the new one away. With nobody behind it, the rule of a death nobody caused.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTransform(event: EntityTransformEvent) {
        val old = event.entity
        val reason = event.transformReason
        // A slime splits as it dies, and its death is written already.
        if (old is Player || reason == TransformReason.SPLIT) return
        val by = when (reason) {
            TransformReason.CURED -> (old as? ZombieVillager)?.conversionPlayer?.let { Attributed(it.uniqueId, Confidence.FACT) }
            TransformReason.SHEARED -> shearers.remove(old.uniqueId)?.let { Attributed(it, Confidence.FACT) }
            else -> culpritOf(old, Cause.MOB_TRANSFORM).by
        }
        transformed(old, event.transformedEntities, reason, by)
    }

    // A pig struck by lightning turns on an event of its own, which the one above never hears.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPigZap(event: PigZapEvent) {
        val by = culpritOf(event.entity, Cause.MOB_TRANSFORM).by
            ?: (event.lightning.causingEntity as? Player)?.let { Attributed(it.uniqueId, Confidence.FACT) }
        transformed(event.entity, listOf(event.pigZombie), TransformReason.LIGHTNING, by)
    }

    private fun transformed(old: Entity, heirs: List<Entity>, reason: TransformReason, by: Attributed?) {
        val touched = old.persistentDataContainer.has(TOUCHED)
        if (by.culprit() == null) {
            val crowd = if (touched) 0 else old.location.chunk.entities.count { it.type == old.type }
            if (!worthRecording(touched, keepsItsPlace((old as CraftEntity).handle), crowd, Settings.crowd)) return
        }
        removed(old, Culprit(Cause.MOB_TRANSFORM, by), "$TRANSFORMED${reason.name.lowercase()}" to null)
        for (heir in heirs) {
            if (touched) touch(heir)
            created(heir, Cause.MOB_TRANSFORM, by, mark = false)
        }
    }

    private fun touch(entity: Entity) {
        if (entity !is Player) entity.persistentDataContainer.set(TOUCHED, PersistentDataType.BYTE, 1)
    }

    /**
     * A mob taken up in a bucket leaves the world as surely as a killed one, into the bucket's item. A
     * rollback of whoever took it lets it out where it swam; the bucket is the item plane's to take back.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketed(event: PlayerBucketEntityEvent) =
        removed(event.entity, Culprit(Cause.BUCKET_CAPTURE_MOB, Attributed(event.player.uniqueId, Confidence.FACT)))

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakEvent) {
        val hanging = event.entity
        val remover = (event as? HangingBreakByEntityEvent)?.remover
        val culprit = when {
            remover != null -> byEntity(remover, Cause.ENTITY_BROKEN)
            // The wall behind it went, and whoever took the wall away took the frame with it. The frame
            // notices on its next look, seconds on, by when only the journal remembers the wall.
            event.cause == HangingBreakEvent.RemoveCause.PHYSICS -> {
                val wall = positionOf(hanging.location.block.getRelative(hanging.attachedFace))
                Culprit(Cause.ENTITY_BROKEN, attribution.removerAt(wall) ?: attribution.journalRemoverAt(wall))
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
        val culprit = culpritOf(victim, Cause.PLAYER_KILLED, alight.remove(victim.uniqueId))
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
            // The dispenser's own capture notes who powered it, and it hears this spawn after this does.
            SpawnReason.DISPENSE_EGG -> {
                later(entity.location) { entities.summonerOf(entity.uniqueId)?.let { created(entity, Cause.SPAWN_EGG_USE, it) } }
                return
            }
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
        // A knot is the lead's, and the lead is on the mob's own NBT.
        if (event.entity is LeashHitch) return
        created(event.entity, Cause.PLACE_ENTITY_ITEM, Attributed(player.uniqueId, Confidence.FACT))
    }

    // Nothing but its uuid and where it appeared: what a rollback does with it is take it away again.
    private fun created(entity: Entity, cause: Cause, by: Attributed?, mark: Boolean = true) {
        if (mark) touch(entity)
        val log = logs.get(entity.world.uid) ?: return
        val block = entity.location.block
        log.submit(
            listOf(
                EntityChange(
                    block.x, block.y, block.z, EntityKind.CREATED, cause, entity.type.key.toString(), entity.uniqueId,
                    confidence = by?.confidence ?: Confidence.FACT, actor = by.culprit(),
                )
            )
        )
    }

    /**
     * `around` are positions whose recent change may answer for the death, asked of the journal off the
     * region thread; `worth` says whether the row is written when none of them does.
     */
    private fun removed(
        entity: Entity,
        culprit: Culprit,
        death: Pair<String?, String?> = null to null,
        around: List<WorldBlock> = emptyList(),
        worth: Boolean = true,
    ) {
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
            val drops = origins.droppedFor(uuid)
            val write: (Attributed?) -> Unit = { by ->
                log.submit(
                    listOf(
                        EntityChange(
                            block.x, block.y, block.z, EntityKind.REMOVED, culprit.cause, type, uuid, now,
                            by?.confidence ?: Confidence.FACT, by.culprit(),
                            before = nbt, drops = drops, death = death.first, via = death.second,
                        )
                    )
                )
            }
            if (around.isEmpty()) return@later write(culprit.by)
            offThread {
                val changed = around.firstNotNullOfOrNull { attribution.journalRemoverAt(it, Settings.freshMillis) }
                if (changed != null) write(changed.copy(confidence = Confidence.INFERRED)) else if (worth) write(culprit.by)
            }
        }
    }

    /**
     * Lava or fire set it alight: whoever put that block down, kept for the burning that outlasts it. The
     * tracker alone — this fires on every tick spent in lava — and flowing lava carries its note along.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCombust(event: EntityCombustByBlockEvent) {
        val block = event.combuster ?: return
        val by = attribution.placerAt(positionOf(block), block.blockData.asString) ?: return
        val now = System.currentTimeMillis()
        if (alight.size > SEEN_CAP) alight.values.removeIf { now - it.second > BURN_MILLIS }
        alight[event.entity.uniqueId] = by to now
    }

    /**
     * Who killed it: the player the game names as killer; else what hit it last, followed to whoever stands
     * behind that; else the fire or the lava it burned in, to whoever put that down. `lit` is who set it
     * alight, taken off the table by the death.
     */
    private fun culpritOf(entity: Entity, own: Cause, lit: Pair<Attributed, Long>? = alight[entity.uniqueId]): Culprit {
        (entity as? LivingEntity)?.killer?.let { return Culprit(own, Attributed(it.uniqueId, Confidence.FACT)) }
        val damage = entity.lastDamageCause
        (damage as? EntityDamageByEntityEvent)?.damager?.let { return byEntity(it, own) }
        val at = positionOf(entity.location.block)
        val by = when (damage?.cause) {
            DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA ->
                // Lava burns from the block it touches, which is not always the block the mob stands in.
                (damage as? EntityDamageByBlockEvent)?.damager?.let { attribution.placerAt(positionOf(it), it.blockData.asString) }
                    ?: lit?.takeIf { System.currentTimeMillis() - it.second <= BURN_MILLIS }?.first
                    ?: attribution.placerAt(at, if (damage.cause == DamageCause.LAVA) "minecraft:lava" else "minecraft:fire")
            else -> null
        }
        return Culprit(own, by?.copy(confidence = Confidence.INFERRED))
    }

    /**
     * Where to look for whoever changed the place around it a little before it died of the place: let
     * water in or took it away, took the floor from under it, dropped sand on it, put down magma, a cactus,
     * a rose, powder snow. The block that hurt it first, then where it stood, its head and what was under
     * it; a fall, the floor it fell from. Whoever made the newest change there within [FRESH_MILLIS] is a
     * guess, and reads as one.
     */
    private fun aroundOf(entity: Entity, damage: EntityDamageEvent?): List<WorldBlock> {
        val cause = damage?.cause ?: return emptyList()
        if (cause !in BY_PLACE) return emptyList()
        val block = entity.location.block
        val world = entity.world.uid
        return buildList {
            (damage as? EntityDamageByBlockEvent)?.damager?.let { add(positionOf(it)) }
            for (dy in listOf(0, 1, -1)) add(WorldBlock(world, block.x, block.y + dy, block.z))
            if (cause == DamageCause.FALL) {
                // Still the height it fell from: the game clears it only once the fall has hurt.
                val floor = kotlin.math.floor(entity.location.y + entity.fallDistance).toInt() - 1
                for (dx in -1..1) for (dz in -1..1) add(WorldBlock(world, block.x + dx, floor, block.z + dz))
            }
        }
    }

    // The damage type's key, and the entity that dealt the blow when that was not a player: the dog, the arrow.
    private fun deathOf(source: DamageSource): Pair<String?, String?> =
        (source as? CraftDamageSource)?.handle?.typeHolder()?.registeredName to
            source.directEntity?.takeIf { it !is Player }?.type?.key?.toString()

    private fun byEntity(damager: Entity, own: Cause): Culprit {
        val shooter = firedBy(damager)
        val exploded = damager.type in EXPLODING
        val cause = if (exploded) explosionCause(damager.type) else own
        (damager as? Player)?.let { return Culprit(own, Attributed(it.uniqueId, Confidence.FACT)) }
        litBy(damager)?.let { return Culprit(cause, Attributed(it.uniqueId, Confidence.FACT)) }
        (shooter as? Player)?.let { return Culprit(cause, Attributed(it.uniqueId, Confidence.FACT)) }
        // A trident's lightning is the thrower's; a block that fell on it, whoever made it fall.
        ((damager as? LightningStrike)?.causingEntity as? Player)?.let { return Culprit(cause, Attributed(it.uniqueId, Confidence.FACT)) }
        (damager as? FallingBlock)?.let { attribution.flying(it.uniqueId) }?.let { return Culprit(cause, it) }
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
        // Lava sets a mob burning for fifteen seconds after it climbs out.
        const val BURN_MILLIS = 20_000L
        // Buckets that pour out something other than a mob.
        // The ways of dying that a place deals out, and that a player changing the place can therefore be behind.
        val BY_PLACE = setOf(
            DamageCause.FALL, DamageCause.DROWNING, DamageCause.DRYOUT, DamageCause.SUFFOCATION, DamageCause.FREEZE,
            DamageCause.CONTACT, DamageCause.HOT_FLOOR, DamageCause.WITHER, DamageCause.CAMPFIRE,
            DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA,
        )
        val NOT_A_MOB = setOf("water_bucket", "lava_bucket", "powder_snow_bucket", "milk_bucket", "bucket")
        val BUILT = setOf(SpawnReason.BUILD_IRONGOLEM, SpawnReason.BUILD_SNOWMAN, SpawnReason.BUILD_WITHER, SpawnReason.BUILD_COPPERGOLEM)
        val EXPLODING = setOf(
            EntityType.TNT, EntityType.CREEPER, EntityType.END_CRYSTAL, EntityType.FIREBALL,
            EntityType.SMALL_FIREBALL, EntityType.WITHER_SKULL, EntityType.WITHER, EntityType.TNT_MINECART,
        )
    }
}
