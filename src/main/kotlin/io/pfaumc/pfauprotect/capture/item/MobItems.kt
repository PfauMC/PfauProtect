package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.capture.block.spotOf
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.craftbukkit.CraftEquipmentSlot
import org.bukkit.inventory.ItemStack as BukkitItemStack
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Armadillo
import org.bukkit.entity.Cat
import org.bukkit.entity.Chicken
import org.bukkit.entity.Entity
import org.bukkit.entity.Goat
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Sniffer
import org.bukkit.entity.Turtle
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDispenseLootEvent
import org.bukkit.event.block.BlockShearEntityEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.EntityUnleashEvent
import org.bukkit.event.entity.PiglinBarterEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerHarvestBlockEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.bukkit.event.player.PlayerUnleashEntityEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import net.minecraft.world.entity.EquipmentSlot as NmsEquipmentSlot
import java.util.concurrent.ConcurrentHashMap

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

// A horse's window numbers its own slots — saddle, body armour, then the chest of a donkey or a llama —
// while a saddle put on with a click is booked by its equipment slot. The window is turned into the
// equipment numbering so both name one slot, and the chest goes above the entity's own item slot.
internal fun horseSlot(window: Int): Int = when (window) {
    0 -> NmsEquipmentSlot.SADDLE.ordinal
    1 -> NmsEquipmentSlot.BODY.ordinal
    else -> ENTITY_ITEM_SLOT + window - 1
}

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

/**
 * The slot a dropped form comes out of: what the entity still books, or, once it has been removed,
 * what it booked when it went. A slot taken from the removed entity's list is gone from it, so the
 * rest can be written off afterwards as what nothing dropped.
 */
internal fun claimHeld(live: Map<Int, ByteArray>, gone: MutableMap<Int, ByteArray>?, form: ByteArray): Int? {
    heldSlotOf(live, form)?.let { return it }
    val slot = gone?.let { heldSlotOf(it, form) } ?: return null
    gone.remove(slot)
    return slot
}

// What falls out of a living mob that is not dying, and everything that falls out of one that is. The
// drops of a death never pass through the drop event — they are handed out after the death event — so
// the death has to leave its notes by where the mob fell.
class MobItemListener(
    private val codec: ItemFormCodec,
    private val pending: TickCoalescer,
    private val origins: SpawnOrigins,
    private val inventories: MobInventories? = null,
    // Runs a task on the region of the location a tick later.
    private val later: (Location, () -> Unit) -> Unit = { _, _ -> },
) : Listener {
    // A boat, a minecart, a frame is removed before it drops what it was made of: what it booked
    // stays here for that drop until the next tick. Touched on the region that owns the entity.
    private val removed = ConcurrentHashMap<UUID, MutableMap<Int, ByteArray>>()

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

    // A frame or a painting drops what it holds without the drop event every other entity raises, so
    // the hit that knocks the item out and the break leave the notes themselves, while the frame still
    // books what it held.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFrameHit(event: EntityDamageEvent) {
        val frame = event.entity as? ItemFrame ?: return
        if (frame.isFixed) return
        val actor = ((event as? EntityDamageByEntityEvent)?.damager as? Player)?.uniqueId
        expectOut(frame, listOf(0), Cause.CONTAINER_REMOVE, actor)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakEvent) {
        val actor = ((event as? HangingBreakByEntityEvent)?.remover as? Player)?.uniqueId
        expectOut(event.entity, listOf(0, ENTITY_ITEM_SLOT), Cause.ENTITY_BREAK_DROP, actor)
    }

    // The mark goes now, so a second hit or the removal after the break finds nothing left to book;
    // what no spawn claims by the next tick was not dropped and is written off.
    private fun expectOut(entity: Entity, slots: List<Int>, cause: Cause, actor: UUID?) {
        val held = heldBy(entity).filterKeys { it in slots }
        if (held.isEmpty()) return
        val spot = spotOf(entity.location)
        val framed = (entity as? ItemFrame)?.item?.let { codec.encodeOrNull(it)?.key }
        val claims = held.map { (slot, form) ->
            unbookHeld(entity, slot)
            val from = EntitySlot(entity.uniqueId, slot)
            // The item in a frame may be worn, and a spawn is matched on its damage as well.
            val key = framed?.takeIf { slot == 0 && it.form.contentEquals(form) } ?: ItemKey(form, null)
            Triple(from, key, origins.expect(from, cause, key, spot, 1, actor))
        }
        later(entity.location) {
            for ((from, key, claimed) in claims) {
                if (claimed() < 1) pending.add(from, Void, cause, key, 1, actor)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: EntityDropItemEvent) {
        val entity = event.entity
        // A player's drop is the capture's; an arrow's is the projectile listener's.
        if (entity is Player || entity is AbstractArrow) return
        val item = event.itemDrop
        val encoded = codec.encodeOrNull(item.itemStack) ?: return
        val slot = claimHeld(heldBy(entity), removed[entity.uniqueId], encoded.form)
        val booked = slot?.let {
            unbookHeld(entity, it)
            EntitySlot(entity.uniqueId, it)
        }
        // A villager sharing food, an allay handing over what it collected: out of the pocket, which
        // has already given it up.
        val pocket = if (booked == null && carriesInventory(entity)) inventories?.thrown(entity, encoded.form) else null
        val (from, cause) = when {
            // A frame, a boat, a minecart: broken, it falls out as what it was made of and what it held.
            entity !is LivingEntity -> (booked ?: Void) to Cause.ENTITY_BREAK_DROP
            sheared.now(entity) -> if (booked != null) booked to Cause.SHEAR_MOB else Void to Cause.SHEARING_DROP
            unleashed.now(entity) -> Void to Cause.LEASH_DROP
            bartered.now(entity) -> Void to Cause.PIGLIN_BARTER
            booked != null -> booked to Cause.MOB_THROW_ITEM
            pocket != null -> pocket to Cause.MOB_THROW_ITEM
            giftFrom(entity) -> Void to Cause.GIFT_DROP
            else -> Void to Cause.MOB_THROW_ITEM
        }
        // A living mob throwing what nothing booked into it and that it does not make itself: the
        // equipment it spawned with, or a way in nothing caught. The birth is real, where it came from
        // is a guess, and the uncovered tally is where a guess belongs.
        val guessed = entity is LivingEntity && from == Void && cause == Cause.MOB_THROW_ITEM
        val confidence = if (guessed) Confidence.INFERRED else Confidence.FACT
        origins.expect(item.uniqueId, from, cause, encoded.key, encoded.count, confidence = confidence)
    }

    /**
     * An entity a command summoned with things on it: armour and a sword, a chest cart's load, a frame's
     * item. Conjured from nothing and booked where the entity will give them back from. A pocket is
     * left to its own first reading.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSummoned(event: EntitySpawnEvent) {
        val entity = event.entity
        if (entity.entitySpawnReason != CreatureSpawnEvent.SpawnReason.COMMAND || entity is Item) return
        val booked = ArrayList<Pair<Int, BukkitItemStack>>()
        (entity as? LivingEntity)?.equipment?.let { gear ->
            for (slot in org.bukkit.inventory.EquipmentSlot.entries) {
                if (!entity.canUseEquipmentSlot(slot)) continue
                val stack = gear.getItem(slot)
                if (!stack.isEmpty) booked += CraftEquipmentSlot.getNMS(slot).ordinal to stack
            }
        }
        (entity as? ItemFrame)?.item?.takeIf { !it.isEmpty }?.let { booked += 0 to it }
        for ((slot, stack) in booked) {
            val encoded = codec.encodeOrNull(stack) ?: continue
            bookHeld(entity, slot, encoded.form)
            pending.add(Void, EntitySlot(entity.uniqueId, slot), Cause.CMD_SUMMON_ITEMS, encoded.key, encoded.count)
        }
        val inventory = (entity as? InventoryHolder)?.inventory
        if (inventory == null || carriesInventory(entity)) return
        val holders = containerHolders(inventory) ?: return
        for (slot in 0 until inventory.size) {
            val encoded = codec.encodeOrNull(inventory.getItem(slot)) ?: continue
            pending.add(Void, holders(slot), Cause.CMD_SUMMON_ITEMS, encoded.key, encoded.count)
        }
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
        // A piglin spills its pocket and a villager keeps it; either way the pocket booked is what
        // went, since a piglin's is already empty by the time this is raised.
        val pockets = if (carriesInventory(mob)) inventories?.booked(mob) else null
        val pocketLeft = IntArray(pockets?.size ?: 0) { pockets!![it].count }
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
                    val booked = horseSlot(slot)
                    // A saddle put on with a click is booked on the mob as well; it has fallen out here.
                    held.remove(booked)
                    val from = EntitySlot(mob.uniqueId, booked)
                    origins.expect(from, Cause.CONTAINER_BREAK_DROP, encoded.key, spot, qty, killer, DEATH_REACH)
                }
            }
            if (need <= 0) continue
            if (pockets != null) {
                for (index in pockets.indices) {
                    if (need <= 0) break
                    val pocket = pockets[index]
                    if (pocketLeft[index] <= 0 || !pocket.key.form.contentEquals(encoded.form)) continue
                    val qty = minOf(need, pocketLeft[index])
                    pocketLeft[index] -= qty
                    need -= qty
                    val from = EntitySlot(mob.uniqueId, MOB_INVENTORY_BASE + pocket.slot)
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
        pockets?.forEachIndexed { index, pocket ->
            val from = EntitySlot(mob.uniqueId, MOB_INVENTORY_BASE + pocket.slot)
            pending.add(from, Void, Cause.MOB_EQUIPMENT_LOST, pocket.key, pocketLeft[index], killer)
        }
        if (pockets != null) inventories?.forget(mob)
    }

    // A pocket that leaves the world other than by death or with its chunk: a villager struck into a
    // witch, a plugin removing a piglin. Nothing carries it on.
    private fun pocketGone(entity: Entity) {
        val inventories = inventories ?: return
        if (!carriesInventory(entity)) return
        for (pocket in inventories.booked(entity)) {
            val from = EntitySlot(entity.uniqueId, MOB_INVENTORY_BASE + pocket.slot)
            pending.add(from, Void, Cause.MOB_EQUIPMENT_LOST, pocket.key, pocket.count)
        }
        inventories.forget(entity)
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
        if (living) pocketGone(entity)
        val held = heldBy(entity)
        if (held.isEmpty()) return
        for (slot in held.keys) unbookHeld(entity, slot)
        val id = entity.uniqueId
        if (living) return writtenOff(id, held, Cause.MOB_EQUIPMENT_LOST)
        // Broken, it drops what it was right after this; what no drop took by the next tick is gone.
        removed[id] = held.toMutableMap()
        later(entity.location) { removed.remove(id)?.let { writtenOff(id, it, Cause.ENTITY_BREAK_DROP) } }
    }

    private fun writtenOff(entity: UUID, held: Map<Int, ByteArray>, cause: Cause) {
        for ((slot, form) in held) pending.add(EntitySlot(entity, slot), Void, cause, ItemKey(form, null), 1)
    }

    // A zombie drowning, a villager struck by lightning: a new entity with a new id takes over what
    // the old one was holding.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTransform(event: EntityTransformEvent) {
        val old = event.entity
        pocketGone(old)
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
