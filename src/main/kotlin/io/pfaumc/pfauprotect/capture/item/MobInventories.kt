package io.pfaumc.pfauprotect.capture.item

import io.papermc.paper.event.entity.EntityCompostItemEvent
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.ByteReader
import io.pfaumc.pfauprotect.storage.ByteWriter
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.storage.PlacedForms
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.Items
import org.bukkit.NamespacedKey
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityBreedEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// A mob's own pocket is booked past every number its equipment, a horse window and a placed entity use.
internal const val MOB_INVENTORY_BASE = 100

private val POCKET_KEY = NamespacedKey("pfauprotect", "pocket")

/**
 * A mob with a pocket of its own: a villager, a piglin, an allay, a pillager. What it picks up goes
 * there and not into a hand, and it spends from there with no event of its own. A horse is not one of
 * them: its inventory is a window, booked as such.
 */
internal fun carriesInventory(entity: Entity) = entity is Mob && entity is InventoryHolder && entity !is AbstractHorse

internal class Pocket(val slot: Int, val key: ItemKey, val count: Int)

internal class PocketChange(val slot: Int, val key: ItemKey, val qty: Int, val gained: Boolean)

/** Slot by slot, what went between two readings of one pocket. */
internal fun pocketChanges(before: List<Pocket>, after: List<Pocket>): List<PocketChange> {
    val was = before.associateBy { it.slot }
    val now = after.associateBy { it.slot }
    val changes = ArrayList<PocketChange>()
    for (slot in (was.keys + now.keys).sorted()) {
        val old = was[slot]
        val new = now[slot]
        if (old != null && new != null && old.key.form.contentEquals(new.key.form)) {
            val delta = new.count - old.count
            if (delta != 0) changes += PocketChange(slot, new.key, kotlin.math.abs(delta), delta > 0)
            continue
        }
        if (old != null) changes += PocketChange(slot, old.key, old.count, gained = false)
        if (new != null) changes += PocketChange(slot, new.key, new.count, gained = true)
    }
    return changes
}

/**
 * What a villager does with its pocket that raises no event, told from what the labels left
 * unexplained: three wheat baked into a bread at its composter, and food eaten to breed, which it eats
 * before it finds out there is no bed for a child. Anything else is an edit nobody saw.
 */
internal fun villagerGuess(
    changes: List<PocketChange>,
    wheat: (ByteArray) -> Boolean,
    bread: (ByteArray) -> Boolean,
    food: (ByteArray) -> Boolean,
): List<Cause> {
    val causes = MutableList(changes.size) { Cause.INVENTORY_LOAD }
    val baked = changes.filter { it.gained && bread(it.key.form) }.sumOf { it.qty }
    val spent = changes.filter { !it.gained && wheat(it.key.form) }.sumOf { it.qty }
    if (baked > 0 && spent == 3 * baked) {
        changes.forEachIndexed { i, change ->
            if (!change.gained && wheat(change.key.form)) causes[i] = Cause.CRAFT_CONSUME
            if (change.gained && bread(change.key.form)) causes[i] = Cause.CRAFT_RESULT
        }
    }
    val rest = changes.indices.filter { causes[it] == Cause.INVENTORY_LOAD }
    if (rest.isNotEmpty() && rest.all { !changes[it].gained && food(changes[it].key.form) }) {
        for (i in rest) causes[i] = Cause.CONSUME_FOOD
    }
    return causes
}

internal fun encodePockets(pockets: List<Pocket>): ByteArray {
    val w = ByteWriter()
    w.varInt(pockets.size)
    for (pocket in pockets) {
        w.varInt(pocket.slot).varInt(pocket.key.form.size).bytes(pocket.key.form).varInt(pocket.count)
        w.varInt(pocket.key.damage?.let { it + 1 } ?: 0)
    }
    return w.toByteArray()
}

internal fun decodePockets(bytes: ByteArray?): List<Pocket> {
    if (bytes == null) return emptyList()
    val r = ByteReader(bytes)
    return List(r.varInt()) {
        val slot = r.varInt()
        val form = r.bytes(r.varInt())
        val count = r.varInt()
        val damage = r.varInt().takeIf { it > 0 }?.minus(1)
        Pocket(slot, ItemKey(form, damage), count)
    }
}

/**
 * Keeps a copy of every pocket in the mob itself and reads the pocket against it whenever something is
 * known to have reached into it: a pickup, a planting, a breeding, a composter fed. What those explain
 * is written under their causes; whatever else the pocket did since the last reading is written too, as
 * a guess — bread baked from wheat and food eaten under causes of their own, the rest as a load nobody
 * saw — rather than left for the ledger to disagree with the mob about for ever.
 */
class MobInventories(
    private val codec: ItemFormCodec,
    private val pending: TickCoalescer,
    private val placed: PlacedForms,
    private val sink: (List<Transfer>) -> Unit,
    // Runs a task on the entity's own scheduler a tick later.
    private val later: (Entity, () -> Unit) -> Unit = { _, _ -> },
) : Listener {
    // What an event said about the next reading of one mob's pocket. A null form matches any loss:
    // a planting names the block, not the seed.
    private class Label(
        val form: ByteArray?,
        val gained: Boolean,
        val other: Holder,
        val cause: Cause,
        var qty: Int,
        val actor: UUID? = null,
        val placing: WorldBlock? = null,
        // Where a pickup the pocket did not take went instead.
        val hand: Int? = null,
    ) {
        fun fits(change: PocketChange) =
            qty > 0 && gained == change.gained && (form == null || form.contentEquals(change.key.form))
    }

    private val labels = ConcurrentHashMap<UUID, MutableList<Label>>()

    internal fun booked(mob: Entity): List<Pocket> =
        decodePockets(mob.persistentDataContainer.get(POCKET_KEY, PersistentDataType.BYTE_ARRAY))

    private fun book(mob: Entity, pockets: List<Pocket>) {
        if (pockets.isEmpty()) mob.persistentDataContainer.remove(POCKET_KEY)
        else mob.persistentDataContainer.set(POCKET_KEY, PersistentDataType.BYTE_ARRAY, encodePockets(pockets))
    }

    internal fun forget(mob: Entity) = book(mob, emptyList())

    private fun live(mob: Entity): List<Pocket> {
        val inventory = (mob as InventoryHolder).inventory
        return (0 until inventory.size).mapNotNull { slot ->
            codec.encodeOrNull(inventory.getItem(slot))?.let { Pocket(slot, it.key, it.count) }
        }
    }

    private fun label(mob: Entity, label: Label) {
        labels.computeIfAbsent(mob.uniqueId) { ArrayList() }.let { synchronized(it) { it += label } }
        later(mob) { settle(mob) }
    }

    /**
     * The slot a thrown item left, read while the throw is still being raised: the pocket has already
     * given it up. The copy is brought up to date for that slot alone, so the next reading does not
     * count the same loss again.
     */
    internal fun thrown(mob: Entity, form: ByteArray): Holder? {
        val before = booked(mob)
        val now = live(mob).associateBy { it.slot }
        val left = before.firstOrNull { pocket ->
            val still = now[pocket.slot]?.takeIf { it.key.form.contentEquals(form) }?.count ?: 0
            pocket.key.form.contentEquals(form) && still < pocket.count
        } ?: return null
        book(mob, (before.filter { it.slot != left.slot } + listOfNotNull(now[left.slot])).sortedBy { it.slot })
        return EntitySlot(mob.uniqueId, MOB_INVENTORY_BASE + left.slot)
    }

    private fun settle(mob: Entity) {
        if (!mob.isValid) return
        val before = booked(mob)
        val after = live(mob)
        book(mob, after)
        val waiting = labels.remove(mob.uniqueId).orEmpty()
        val timestamp = System.currentTimeMillis()
        val rows = ArrayList<Transfer>()
        val unexplained = ArrayList<PocketChange>()
        for (change in pocketChanges(before, after)) {
            val slot = EntitySlot(mob.uniqueId, MOB_INVENTORY_BASE + change.slot)
            var left = change.qty
            for (label in waiting) {
                if (left <= 0) break
                if (!label.fits(change)) continue
                val qty = minOf(left, label.qty)
                label.qty -= qty
                left -= qty
                label.placing?.let { placed.setFormAt(it.world, it.x, it.y, it.z, change.key.form) }
                rows += row(change, slot, label.other, label.cause, qty, timestamp, Confidence.FACT, label.actor)
            }
            if (left > 0) unexplained += PocketChange(change.slot, change.key, left, change.gained)
        }
        val guessed = if (mob is Villager) villagerGuess(unexplained, ::isWheat, ::isBread, ::isVillagerFood) else null
        unexplained.forEachIndexed { i, change ->
            val slot = EntitySlot(mob.uniqueId, MOB_INVENTORY_BASE + change.slot)
            val cause = guessed?.get(i) ?: Cause.INVENTORY_LOAD
            rows += row(change, slot, Void, cause, change.qty, timestamp, Confidence.INFERRED, null)
        }
        // A pickup the pocket did not take went into a hand: a piglin's gold, a pillager's banner.
        for (label in waiting) {
            val hand = label.hand ?: continue
            val form = label.form ?: continue
            if (label.qty <= 0) continue
            bookHeld(mob, hand, form)
            pending.add(label.other, EntitySlot(mob.uniqueId, hand), Cause.ITEM_PICKUP_BY_MOB, ItemKey(form, null), label.qty)
        }
        if (rows.isNotEmpty()) sink(rows)
    }

    private fun isWheat(form: ByteArray) = codec.decode(form, 1, null).`is`(Items.WHEAT)

    private fun isBread(form: ByteArray) = codec.decode(form, 1, null).`is`(Items.BREAD)

    private fun isVillagerFood(form: ByteArray) = codec.decode(form, 1, null).has(DataComponents.VILLAGER_FOOD)

    private fun row(
        change: PocketChange,
        slot: Holder,
        other: Holder,
        cause: Cause,
        qty: Int,
        timestamp: Long,
        confidence: Confidence,
        actor: UUID?,
    ) = Transfer(
        cause = cause,
        from = if (change.gained) other else slot,
        to = if (change.gained) slot else other,
        form = change.key.form,
        damage = change.key.damage,
        qty = qty,
        timestamp = timestamp,
        confidence = confidence,
        actor = actor,
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val mob = event.entity
        if (!carriesInventory(mob)) return
        val encoded = codec.encodeOrNull(event.item.itemStack) ?: return
        val taken = encoded.count - event.remaining
        if (taken <= 0) return
        val hand = equipmentSlotOf(mob as LivingEntity, event.item.itemStack)
        label(mob, Label(encoded.form, true, ItemEntityRef(event.item.uniqueId), Cause.ITEM_PICKUP_BY_MOB_INV, taken, hand = hand))
    }

    // A farmer planting from its pocket; a harvest raises the same event and takes nothing out of it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFarm(event: EntityChangeBlockEvent) {
        val mob = event.entity
        if (!carriesInventory(mob) || event.blockData.material.isAir) return
        label(mob, Label(null, false, positionOf(event.block), Cause.BLOCK_PLACE, 1, placing = positionOf(event.block)))
    }

    // Villagers eat out of their pockets to breed.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        for (parent in listOf(event.mother, event.father)) {
            if (carriesInventory(parent)) label(parent, Label(null, false, Void, Cause.CONSUME_FOOD, Int.MAX_VALUE))
        }
    }

    // What a farmer feeds a composter comes out of its pocket and into the composter, which spends it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCompost(event: EntityCompostItemEvent) {
        val mob = event.entity
        if (!carriesInventory(mob)) return
        val form = codec.encodeOrNull(event.item)?.form ?: return
        label(mob, Label(form, false, containerAt(event.block, 0), Cause.COMPOSTER_CONSUME, 1))
    }
}
