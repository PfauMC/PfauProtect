package io.pfaumc.pfauprotect

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Armadillo
import org.bukkit.entity.Cat
import org.bukkit.entity.Chicken
import org.bukkit.entity.Entity
import org.bukkit.entity.Goat
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Sniffer
import org.bukkit.entity.Turtle
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDispenseLootEvent
import org.bukkit.event.block.BlockShearEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.EntityUnleashEvent
import org.bukkit.event.entity.PiglinBarterEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerHarvestBlockEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.bukkit.event.player.PlayerUnleashEntityEvent
import org.bukkit.persistence.PersistentDataType

// A mob's drops scatter around where it died, and a big one is wider than a block.
private const val DEATH_REACH = 3.0

// Where a vault or a trial spawner throws its reward, a little in front of the block.
private const val REWARD_REACH = 3.0

// The slots of a mob the ledger booked an item into, each with the form it booked. Kept on the entity
// for the same reason an arrow keeps its mark: a zombie holding a picked-up sword is saved with its
// chunk, and what it drops after the next start still has to come out of the slot it went into.
// The form and not the count. A mob picks up one stack into one slot and gives it up whole;
// a villager's stacked seeds are the case where a partial give-up is written as the whole of it.
private fun heldKey(slot: Int) = NamespacedKey("pfauprotect", "held_$slot")

// The item an entity was placed from — a boat, a stand, a frame — kept apart from anything it wears.
internal const val ENTITY_ITEM_SLOT = 16

private val HELD_SLOTS = 0..ENTITY_ITEM_SLOT

internal fun bookHeld(entity: Entity, slot: Int, form: ByteArray) {
    entity.persistentDataContainer.set(heldKey(slot), PersistentDataType.BYTE_ARRAY, form)
}

internal fun heldBy(entity: Entity): Map<Int, ByteArray> {
    val data = entity.persistentDataContainer
    return HELD_SLOTS.mapNotNull { slot -> data.get(heldKey(slot), PersistentDataType.BYTE_ARRAY)?.let { slot to it } }
        .toMap()
}

internal fun unbookHeld(entity: Entity, slot: Int) {
    entity.persistentDataContainer.remove(heldKey(slot))
}

// Laid, dug up, shed or brought as a present: the animal made it, nobody put it in.
internal fun giftFrom(entity: Entity) =
    entity is Chicken || entity is Cat || entity is Sniffer || entity is Armadillo || entity is Turtle || entity is Goat

/** Which booked slot a dropped form came out of, and whether it is the whole of what was booked there. */
internal fun heldSlotOf(held: Map<Int, ByteArray>, form: ByteArray): Int? =
    held.entries.firstOrNull { it.value.contentEquals(form) }?.key

// What falls out of a living mob that is not dying, and everything that falls out of one that is. The
// drops of a death never pass through the drop event — they are handed out after the death event — so
// the death has to leave its notes by where the mob fell.
class MobItemListener(
    private val codec: ItemFormCodec,
    private val pending: TickCoalescer,
    private val origins: SpawnOrigins,
) : Listener {
    // Shearing and bartering throw their results through the same drop event as anything else a mob
    // lets go of, inside the same call as their own event. Marked for the tick it happened in.
    private class Marked(val entity: java.util.UUID, val tick: Int)

    private val sheared = ThreadLocal<Marked?>()
    private val bartered = ThreadLocal<Marked?>()
    private val unleashed = ThreadLocal<Marked?>()

    private fun ThreadLocal<Marked?>.now(entity: Entity) = get()?.let { it.entity == entity.uniqueId && it.tick == Bukkit.getCurrentTick() } == true

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShear(event: PlayerShearEntityEvent) {
        sheared.set(Marked(event.entity.uniqueId, Bukkit.getCurrentTick()))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDispenserShear(event: BlockShearEntityEvent) {
        sheared.set(Marked(event.entity.uniqueId, Bukkit.getCurrentTick()))
    }

    // The gold was taken off the ground into the piglin's hand and is spent here.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBarter(event: PiglinBarterEvent) {
        val piglin = event.entity
        bartered.set(Marked(piglin.uniqueId, Bukkit.getCurrentTick()))
        val key = codec.encodeOrNull(event.input)?.key ?: return
        val slot = heldSlotOf(heldBy(piglin), key.form) ?: return
        unbookHeld(piglin, slot)
        pending.add(EntitySlot(piglin.uniqueId, slot), Void, Cause.PIGLIN_BARTER, key, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: EntityDropItemEvent) {
        val entity = event.entity
        // A player's drop is the capture's; an arrow's is the projectile listener's.
        if (entity is Player || entity is AbstractArrow) return
        val item = event.itemDrop
        val encoded = codec.encodeOrNull(item.itemStack) ?: return
        val slot = heldSlotOf(heldBy(entity), encoded.form)
        val booked = slot?.let {
            unbookHeld(entity, it)
            EntitySlot(entity.uniqueId, it)
        }
        val (from, cause) = when {
            // A frame, a boat, a minecart: broken, it falls out as what it was made of and what it held.
            entity !is LivingEntity -> (booked ?: Void) to Cause.ENTITY_BREAK_DROP
            sheared.now(entity) -> if (booked != null) booked to Cause.SHEAR_MOB else Void to Cause.SHEARING_DROP
            unleashed.now(entity) -> Void to Cause.LEASH_DROP
            bartered.now(entity) -> Void to Cause.PIGLIN_BARTER
            booked != null -> booked to Cause.MOB_THROW_ITEM
            giftFrom(entity) -> Void to Cause.GIFT_DROP
            else -> Void to Cause.MOB_THROW_ITEM
        }
        origins.expect(item.uniqueId, from, cause, encoded.key, encoded.count)
    }

    // The lead comes off the mob as an item in the same call.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUnleash(event: EntityUnleashEvent) {
        if (event.isDropLeash) unleashed.set(Marked(event.entity.uniqueId, Bukkit.getCurrentTick()))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerUnleash(event: PlayerUnleashEntityEvent) {
        if (event.isDropLeash) unleashed.set(Marked(event.entity.uniqueId, Bukkit.getCurrentTick()))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        val mob = event.entity
        // The player's death is written by the capture, slot by slot.
        if (mob is Player) return
        val held = heldBy(mob).toMutableMap()
        val spot = spotOf(mob.location)
        val killer = mob.killer?.uniqueId
        // A horse, a donkey or a llama spills its own inventory — saddle, armour, chest — slot by slot,
        // and those slots are the ones its window booked.
        val inventory = (mob as? AbstractHorse)?.inventory?.contents
        val left = IntArray(inventory?.size ?: 0) { inventory?.get(it)?.amount ?: 0 }
        for (dropped in event.drops) {
            val encoded = codec.encodeOrNull(dropped) ?: continue
            var need = encoded.count
            if (inventory != null) {
                for (slot in inventory.indices) {
                    if (need <= 0) break
                    if (left[slot] <= 0 || inventory[slot]?.isSimilar(dropped) != true) continue
                    val qty = minOf(need, left[slot])
                    left[slot] -= qty
                    need -= qty
                    val from = EntitySlot(mob.uniqueId, slot)
                    origins.expect(from, Cause.CONTAINER_BREAK_DROP, encoded.key, spot, qty, killer, DEATH_REACH)
                }
            }
            if (need <= 0) continue
            val slot = heldSlotOf(held, encoded.form)
            if (slot != null) {
                held.remove(slot)
                // A stand broken gives back the stand it was placed from, out of the slot that booked it.
                val cause = if (slot == ENTITY_ITEM_SLOT) Cause.ENTITY_BREAK_DROP else Cause.MOB_EQUIPMENT_DROP
                origins.expect(EntitySlot(mob.uniqueId, slot), cause, encoded.key, spot, need, killer, DEATH_REACH)
            } else {
                origins.expect(Void, Cause.MOB_DROP, encoded.key, spot, need, killer, DEATH_REACH)
            }
        }
        // Equipment drops by chance; what the ledger booked and the death did not drop went with it.
        for ((slot, form) in held) {
            pending.add(EntitySlot(mob.uniqueId, slot), Void, Cause.MOB_EQUIPMENT_LOST, ItemKey(form, null), 1, killer)
        }
        for (slot in HELD_SLOTS) unbookHeld(mob, slot)
    }

    // A mob that despawns takes what it held with it. One that picked something up is kept alive for
    // it, so this is rare — but a plugin removing it is not.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemove(event: EntityRemoveEvent) {
        val entity = event.entity
        if (entity is Player) return
        val living = entity is LivingEntity
        when (event.cause) {
            // Saved with the chunk, or already written by the death or the drop that ended it.
            EntityRemoveEvent.Cause.UNLOAD, EntityRemoveEvent.Cause.PLAYER_QUIT, EntityRemoveEvent.Cause.DROP,
            EntityRemoveEvent.Cause.TRANSFORMATION, EntityRemoveEvent.Cause.PICKUP, EntityRemoveEvent.Cause.MERGE -> return
            EntityRemoveEvent.Cause.DEATH -> if (living) return
            else -> Unit
        }
        val cause = if (living) Cause.MOB_EQUIPMENT_LOST else Cause.ENTITY_BREAK_DROP
        for ((slot, form) in heldBy(entity)) {
            pending.add(EntitySlot(entity.uniqueId, slot), Void, cause, ItemKey(form, null), 1)
            unbookHeld(entity, slot)
        }
    }

    // A zombie drowning, a villager struck by lightning: a new entity with a new id takes over what
    // the old one was holding.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTransform(event: EntityTransformEvent) {
        val old = event.entity
        val held = heldBy(old)
        if (held.isEmpty()) return
        val heir = event.transformedEntity
        for ((slot, form) in held) {
            pending.add(EntitySlot(old.uniqueId, slot), EntitySlot(heir.uniqueId, slot), Cause.MOB_TRANSFORM, ItemKey(form, null), 1)
            bookHeld(heir, slot, form)
            unbookHeld(old, slot)
        }
    }

    // The catch exists before the event and joins the world after it, so it can be named by its id.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val caught = event.caught as? Item ?: return
        val encoded = codec.encodeOrNull(caught.itemStack) ?: return
        origins.expect(caught.uniqueId, Void, Cause.FISHING_CATCH, encoded.key, encoded.count, event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onReward(event: BlockDispenseLootEvent) {
        val block = event.block
        val cause = if (block.type == Material.VAULT) Cause.VAULT_REWARD else Cause.TRIAL_SPAWNER_REWARD
        val spot = spotOf(block.location.add(0.5, 0.5, 0.5))
        val actor = event.player?.uniqueId
        for (stack in event.dispensedLoot) {
            val encoded = codec.encodeOrNull(stack) ?: continue
            origins.expect(Void, cause, encoded.key, spot, encoded.count, actor, REWARD_REACH)
        }
    }

    // Berries, glow berries: picked off the block, which stays where it is.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHarvest(event: PlayerHarvestBlockEvent) {
        val spot = spotOf(event.harvestedBlock.location.add(0.5, 0.5, 0.5))
        for (stack in event.itemsHarvested) {
            val encoded = codec.encodeOrNull(stack) ?: continue
            origins.expect(Void, Cause.BLOCK_INTERACT_DROP, encoded.key, spot, encoded.count, event.player.uniqueId)
        }
    }
}
