package io.pfaumc.pfauprotect.capture.item

import org.bukkit.Material
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import io.papermc.paper.event.entity.EntityCompostItemEvent
import io.pfaumc.pfauprotect.capture.block.SlotChange
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
import org.bukkit.craftbukkit.CraftEquipmentSlot
import org.bukkit.craftbukkit.entity.CraftVillager
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
import net.minecraft.world.entity.EquipmentSlot as NmsEquipmentSlot
import net.minecraft.world.entity.npc.villager.Villager as NmsVillager

private val MAIN_HAND = NmsEquipmentSlot.MAINHAND.ordinal
private val OFF_HAND = NmsEquipmentSlot.OFFHAND.ordinal

// A mob's own pocket is booked past every number its equipment, a horse window and a placed entity use.
internal const val MOB_INVENTORY_BASE = 100

private val POCKET_KEY = NamespacedKey("pfauprotect", "pocket")

// A villager's food points at the reading the pocket copy was taken at.
private val FOOD_KEY = NamespacedKey("pfauprotect", "pocket_food")

private val foodLevelField by lazy { NmsVillager::class.java.getDeclaredField("foodLevel").apply { isAccessible = true } }

/**
 * Whether food gone from a villager's pocket went into the villager. Eating is the only thing that raises
 * its food points, and a try at breeding — with a bed for the child or without — eats and then takes
 * twelve straight off; so the points of what went, less the rise, come to twelve for every try, and to
 * anything else when the food went some other way.
 */
internal fun ateToBreed(eaten: Int, before: Int, after: Int): Boolean {
    val digested = eaten - (after - before)
    return eaten > 0 && digested > 0 && digested % 12 == 0
}

/**
 * A mob with a pocket of its own: a villager, a piglin, an allay, a pillager. What it picks up goes
 * there and not into a hand, and it spends from there with no event of its own. A horse is not one of
 * them: its inventory is a window, booked as such.
 */
internal fun carriesInventory(entity: Entity) = entity is Mob && entity is InventoryHolder && entity !is AbstractHorse

internal class Pocket(val slot: Int, val key: ItemKey, val count: Int)

/** Slot by slot, what went between two readings of one pocket. */
internal fun pocketChanges(before: List<Pocket>, after: List<Pocket>): List<SlotChange> = slotDiff(slots(before), slots(after))

private fun slots(pockets: List<Pocket>): List<Stack?> {
    val bySlot = pockets.associate { it.slot to Stack(it.key, it.count) }
    return List((pockets.maxOfOrNull { it.slot } ?: -1) + 1) { bySlot[it] }
}

/**
 * What a villager does with its pocket that raises no event, told from what the labels left
 * unexplained: three wheat baked into a bread at its composter, and food eaten to breed, which it eats
 * before it finds out there is no bed for a child. Anything else is an edit nobody saw.
 */
internal fun villagerGuess(
    changes: List<SlotChange>,
    wheat: (ByteArray) -> Boolean,
    bread: (ByteArray) -> Boolean,
    food: (ByteArray) -> Boolean,
): List<Cause> {
    val causes = MutableList(changes.size) { Cause.INVENTORY_LOAD }
    val baked = changes.filter { it.gain && bread(it.key.form) }.sumOf { it.qty }
    val spent = changes.filter { !it.gain && wheat(it.key.form) }.sumOf { it.qty }
    if (baked > 0 && spent == 3 * baked) {
        changes.forEachIndexed { i, change ->
            if (!change.gain && wheat(change.key.form)) causes[i] = Cause.CRAFT_CONSUME
            if (change.gain && bread(change.key.form)) causes[i] = Cause.CRAFT_RESULT
        }
    }
    val rest = changes.indices.filter { causes[it] == Cause.INVENTORY_LOAD }
    if (rest.isNotEmpty() && rest.all { !changes[it].gain && food(changes[it].key.form) }) {
        for (i in rest) causes[i] = Cause.CONSUME_FOOD
    }
    return causes
}

/**
 * A pocket saved and loaded again is packed together: the game stores it as a list without slot
 * numbers, so an empty slot closes up and everything after it moves down. The same items in the same
 * counts at other slots are that, each stack from where it was to where it is now. A stack of the same
 * item that came back smaller and lower down was partly eaten as well as moved: what is left of it moved,
 * and the rest went from where it was. What is left over on either side after that changed besides, as
 * a villager eats a slot empty just before it is saved, and comes back slot by slot.
 */
internal fun pocketShifts(before: List<Pocket>, after: List<Pocket>): Pair<List<Pair<Pocket, Pocket>>, List<SlotChange>> {
    val left = after.toMutableList()
    val gone = ArrayList<Pocket>()
    val moves = ArrayList<Pair<Pocket, Pocket>>()
    for (was in before) {
        val now = left.firstOrNull { it.count == was.count && it.key == was.key }
        if (now == null) {
            gone += was
            continue
        }
        left.remove(now)
        if (now.slot != was.slot) moves += was to now
    }
    // Only smaller and only lower: eating takes from a stack and packing moves it down, and neither
    // can do anything else to it.
    for (was in gone.toList()) {
        val now = left.firstOrNull { it.key == was.key && it.slot < was.slot && it.count < was.count } ?: continue
        left.remove(now)
        moves += was to now
        gone[gone.indexOf(was)] = Pocket(was.slot, was.key, was.count - now.count)
    }
    return moves to pocketChanges(gone, left)
}

// What an event said about the next reading of one mob's pocket. A null form matches any loss:
// a planting names the block, not the seed.
internal class PocketLabel(
    val form: ByteArray?,
    val gained: Boolean,
    val other: Holder,
    val cause: Cause,
    var qty: Int,
    val placing: WorldBlock? = null,
    // Where a pickup the pocket did not take went instead.
    val hand: Int? = null,
) {
    fun fits(change: SlotChange) =
        qty > 0 && gained == change.gain && (form == null || form.contentEquals(change.key.form))
}

/**
 * Lays the labels over what the pocket did between two readings; a null label is what none of them
 * explains. A pickup the readings show nothing of is either in a hand now, which [held] books, or went
 * into the pocket and out again before the second reading: a farmer replants the carrot it has just
 * picked up, a villager eats it along with the rest. That is a gain and a loss through the slot such a
 * stack lies in, and the loss is explained like any other.
 */
internal fun explainPocket(
    before: List<Pocket>,
    after: List<Pocket>,
    waiting: List<PocketLabel>,
    held: (PocketLabel) -> Boolean,
): List<Pair<SlotChange, PocketLabel?>> {
    val out = ArrayList<Pair<SlotChange, PocketLabel?>>()
    fun explain(change: SlotChange) {
        var left = change.qty
        for (label in waiting) {
            if (left <= 0) break
            if (!label.fits(change)) continue
            val qty = minOf(left, label.qty)
            label.qty -= qty
            left -= qty
            out += SlotChange(change.slot, change.key, qty, change.gain) to label
        }
        if (left > 0) out += SlotChange(change.slot, change.key, left, change.gain) to null
    }
    pocketChanges(before, after).forEach(::explain)
    for (label in waiting) {
        val form = label.form ?: continue
        if (label.hand == null || label.qty <= 0 || held(label)) continue
        val slot = (after + before).firstOrNull { it.key.form.contentEquals(form) }?.slot
            ?: generateSequence(0) { it + 1 }.first { free -> after.none { it.slot == free } }
        val through = SlotChange(slot, ItemKey(form, null), label.qty, gain = true)
        out += through to label
        label.qty = 0
        explain(SlotChange(slot, through.key, through.qty, gain = false))
    }
    return out
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
    private val later: (Entity, () -> Unit) -> Unit,
) : Listener {
    private val labels = ConcurrentHashMap<UUID, MutableList<PocketLabel>>()

    internal fun booked(mob: Entity): List<Pocket> =
        decodePockets(mob.persistentDataContainer.get(POCKET_KEY, PersistentDataType.BYTE_ARRAY))

    private fun book(mob: Entity, pockets: List<Pocket>) {
        if (pockets.isEmpty()) mob.persistentDataContainer.remove(POCKET_KEY)
        else mob.persistentDataContainer.set(POCKET_KEY, PersistentDataType.BYTE_ARRAY, encodePockets(pockets))
        foodLevelOf(mob)?.let { mob.persistentDataContainer.set(FOOD_KEY, PersistentDataType.INTEGER, it) }
    }

    private fun foodLevelOf(mob: Entity): Int? = (mob as? CraftVillager)?.let { foodLevelField.getInt(it.handle) }

    private fun bookedFood(mob: Entity): Int? = mob.persistentDataContainer.get(FOOD_KEY, PersistentDataType.INTEGER)

    private fun foodPoints(change: SlotChange) =
        (codec.decode(change.key.form, 1, null).get(DataComponents.VILLAGER_FOOD)?.nutrition() ?: 0) * change.qty

    internal fun forget(mob: Entity) = book(mob, emptyList())

    private fun live(mob: Entity): List<Pocket> {
        val inventory = (mob as InventoryHolder).inventory
        return (0 until inventory.size).mapNotNull { slot ->
            codec.encodeOrNull(inventory.getItem(slot))?.let { Pocket(slot, it.key, it.count) }
        }
    }

    private fun label(mob: Entity, label: PocketLabel) {
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
        val foodBefore = bookedFood(mob)
        val after = live(mob)
        book(mob, after)
        val waiting = labels.remove(mob.uniqueId).orEmpty()
        val timestamp = System.currentTimeMillis()
        val explained = explainPocket(before, after, waiting) { held(mob, it) }
        val rows = ArrayList<Transfer>()
        for ((change, label) in explained) {
            if (label == null) continue
            val slot = EntitySlot(mob.uniqueId, MOB_INVENTORY_BASE + change.slot)
            label.placing?.let { placed.setFormAt(it.world, it.x, it.y, it.z, change.key.form) }
            rows += row(change, slot, label.other, label.cause, timestamp, Confidence.FACT)
        }
        // A breeding names what both parents ate, and their food points moved for that as well.
        val fed = explained.filter { it.second?.cause == Cause.CONSUME_FOOD }.sumOf { foodPoints(it.first) }
        rows += guessed(mob, explained.filter { it.second == null }.map { it.first }, timestamp, foodBefore, fed)
        if (rows.isNotEmpty()) sink(rows)
    }

    /**
     * What no event explains, as a guess: a villager's baking and eating under causes of their own. Eating
     * to breed raises no event, but it moves the villager's own food points, and food the points show was
     * eaten is written as eaten rather than guessed at.
     */
    private fun guessed(
        mob: Entity,
        unexplained: List<SlotChange>,
        timestamp: Long,
        foodBefore: Int?,
        fed: Int = 0,
    ): List<Transfer> {
        val causes = if (mob is Villager) villagerGuess(unexplained, ::isWheat, ::isBread, ::isVillagerFood) else null
        val eaten = unexplained.indices.filter { causes?.get(it) == Cause.CONSUME_FOOD }.sumOf { foodPoints(unexplained[it]) }
        val foodAfter = foodLevelOf(mob)
        val ate = foodBefore != null && foodAfter != null && ateToBreed(fed + eaten, foodBefore, foodAfter)
        return unexplained.mapIndexed { i, change ->
            val slot = EntitySlot(mob.uniqueId, MOB_INVENTORY_BASE + change.slot)
            val cause = causes?.get(i) ?: Cause.INVENTORY_LOAD
            val confidence = if (ate && cause == Cause.CONSUME_FOOD) Confidence.FACT else Confidence.INFERRED
            row(change, slot, Void, cause, timestamp, confidence)
        }
    }

    // A pickup the pocket did not take, held now in a hand: a piglin's gold, a pillager's banner.
    private fun held(mob: Entity, label: PocketLabel): Boolean {
        val form = label.form ?: return false
        val hand = handHolding(mob, form, label.hand ?: return false) ?: return false
        bookHeld(mob, hand, form)
        pending.add(label.other, EntitySlot(mob.uniqueId, hand), Cause.ITEM_PICKUP_BY_MOB, ItemKey(form, null), label.qty)
        return true
    }

    // Where a picked-up item is held now: the slot the game would equip it to, unless the mob put it
    // somewhere else — a piglin admires gold in its off hand and keeps its sword in the main one. None
    // when no hand holds it beyond what was booked there already.
    private fun handHolding(mob: Entity, form: ByteArray, guess: Int): Int? {
        val equipment = (mob as? LivingEntity)?.equipment ?: return null
        val booked = heldBy(mob)
        for (slot in listOf(guess, OFF_HAND, MAIN_HAND).distinct()) {
            val bukkit = CraftEquipmentSlot.getSlot(NmsEquipmentSlot.entries[slot])
            if (booked[slot]?.contentEquals(form) == true) continue
            if (codec.encodeOrNull(equipment.getItem(bukkit))?.form?.contentEquals(form) == true) return slot
        }
        return null
    }

    private fun isWheat(form: ByteArray) = codec.decode(form, 1, null).`is`(Items.WHEAT)

    private fun isBread(form: ByteArray) = codec.decode(form, 1, null).`is`(Items.BREAD)

    private fun isVillagerFood(form: ByteArray) = codec.decode(form, 1, null).has(DataComponents.VILLAGER_FOOD)

    private fun row(change: SlotChange, slot: Holder, other: Holder, cause: Cause, timestamp: Long, confidence: Confidence) =
        Transfer(
            cause = cause,
            from = if (change.gain) other else slot,
            to = if (change.gain) slot else other,
            key = change.key,
            qty = change.qty,
            timestamp = timestamp,
            confidence = confidence,
        )

    // A pocket loaded with its chunk comes back packed; the slots it moved between are written before
    // anything reads the pocket against its copy. Whatever else it did since the copy, as a villager
    // eating just before it was saved, is written at once as well, or the next event is read against a
    // stale copy.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onLoad(event: EntityAddToWorldEvent) {
        val mob = event.entity
        if (!carriesInventory(mob)) return
        later(mob) { packed(mob) }
    }

    private fun packed(mob: Entity) {
        if (!mob.isValid) return
        val before = booked(mob)
        val foodBefore = bookedFood(mob)
        val after = live(mob)
        val (moves, rest) = pocketShifts(before, after)
        if (moves.isEmpty() && rest.isEmpty()) return
        book(mob, after)
        val timestamp = System.currentTimeMillis()
        sink(guessed(mob, rest, timestamp, foodBefore) + moves.map { (was, now) ->
            Transfer(
                cause = Cause.INVENTORY_LOAD,
                from = EntitySlot(mob.uniqueId, MOB_INVENTORY_BASE + was.slot),
                to = EntitySlot(mob.uniqueId, MOB_INVENTORY_BASE + now.slot),
                key = now.key,
                qty = now.count,
                timestamp = timestamp,
            )
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val mob = event.entity
        if (!carriesInventory(mob)) return
        val encoded = codec.encodeOrNull(event.item.itemStack) ?: return
        val taken = encoded.count - event.remaining
        if (taken <= 0) return
        val hand = equipmentSlotOf(mob as LivingEntity, event.item.itemStack)
        label(mob, PocketLabel(encoded.form, true, ItemEntityRef(event.item.uniqueId), Cause.ITEM_PICKUP_BY_MOB_INV, taken, hand = hand))
    }

    // A farmer planting from its pocket; a harvest raises the same event and takes nothing out of it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFarm(event: EntityChangeBlockEvent) {
        val mob = event.entity
        val before = (event.block as org.bukkit.craftbukkit.block.CraftBlock).blockState
        val after = (event.blockData as org.bukkit.craftbukkit.block.data.CraftBlockData).state
        if (!carriesInventory(mob) || !planting(before, after)) return
        label(mob, PocketLabel(null, false, positionOf(event.block), Cause.BLOCK_PLACE, 1, placing = positionOf(event.block)))
    }

    // Villagers eat out of their pockets to breed.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        for (parent in listOf(event.mother, event.father)) {
            if (carriesInventory(parent)) label(parent, PocketLabel(null, false, Void, Cause.CONSUME_FOOD, Int.MAX_VALUE))
        }
    }

    // What a farmer feeds a composter comes out of its pocket and into the composter, which spends it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCompost(event: EntityCompostItemEvent) {
        val mob = event.entity
        if (!carriesInventory(mob)) return
        val form = codec.encodeOrNull(event.item)?.form ?: return
        label(mob, PocketLabel(form, false, containerAt(event.block, 0), Cause.COMPOSTER_CONSUME, 1))
    }
}

/**
 * A seed set down into the empty cell over farmland. A composter filling up under a farmer's seeds raises
 * the same event, and booked as a planting every seed it took landed on the composter's position as well
 * as in its slot, never to leave it (D84).
 */
internal fun planting(before: net.minecraft.world.level.block.state.BlockState, after: net.minecraft.world.level.block.state.BlockState): Boolean =
    before.isAir && !after.isAir
