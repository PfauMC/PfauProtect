package io.pfaumc.pfauprotect.capture.block
import io.pfaumc.pfauprotect.capture.item.Intent
import io.papermc.paper.event.block.BlockPreDispenseEvent
import io.papermc.paper.event.block.CompostItemEvent
import io.papermc.paper.event.block.PlayerShearBlockEvent
import io.papermc.paper.block.TileStateInventoryHolder
import io.papermc.paper.event.entity.EntityCompostItemEvent
import io.pfaumc.pfauprotect.attribution.Attributed
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.attribution.Energy
import io.pfaumc.pfauprotect.attribution.EntityOrigins
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.storage.PlacedForms
import io.pfaumc.pfauprotect.capture.item.Stack
import org.bukkit.block.data.Directional
import org.bukkit.Tag
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.capture.item.NestedItems
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.capture.item.bookHeld
import io.pfaumc.pfauprotect.capture.item.containerAt
import io.pfaumc.pfauprotect.attribution.energyAt
import io.pfaumc.pfauprotect.attribution.culprit
import io.pfaumc.pfauprotect.capture.item.equipmentSlotOf
import io.pfaumc.pfauprotect.storage.itemTypeIdOf
import io.pfaumc.pfauprotect.capture.item.positionOf
import io.pfaumc.pfauprotect.capture.item.stackOf
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Campfire
import org.bukkit.block.Furnace
import org.bukkit.block.ShulkerBox
import org.bukkit.block.data.Levelled
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockCookEvent
import org.bukkit.event.block.BlockDispenseArmorEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.inventory.BrewEvent
import org.bukkit.event.inventory.BrewingStandFuelEvent
import org.bukkit.event.inventory.FurnaceBurnEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ProjectileItem
import net.minecraft.world.item.ItemStack as NmsItemStack
import org.bukkit.block.Container as ContainerBlock
import org.bukkit.inventory.ItemStack as BukkitItemStack

private const val FURNACE_INPUT_SLOT = 0
private const val FURNACE_FUEL_SLOT = 1
private const val FURNACE_RESULT_SLOT = 2
private const val BREWING_BOTTLES = 3
private const val BREWING_INGREDIENT_SLOT = 3
private const val BREWING_FUEL_SLOT = 4
// The scale runs to seven; the eighth state is the composter already turned into bone meal.
private const val COMPOSTER_FULL_LEVEL = 7

// An item entity that appears next to the block it came out of, and no further than a dispenser
// throws.
internal const val SPAWN_REACH = 2.0

data class Spot(val world: UUID, val x: Double, val y: Double, val z: Double)

internal fun spotOf(at: Location) = Spot(at.world.uid, at.x, at.y, at.z)

// A position gives back the item it took over, and the block alone cannot say what that was: a named
// box, an enchanted head and a plain chest all answer with the bare item, so the two rows at one
// position would name different forms. What was put down is remembered while it stands, and is
// trusted only while the position still holds that same item — a block that arrived without being
// placed, pushed in by a piston or grown out of dirt, would otherwise be given back as whatever stood
// there before.
// A block with no item form of its own answers with nothing, and for a block nobody put down — fire,
// a liquid, a portal — that is the whole answer. Where something was put down it is not: a potted
// plant, a candle cake and a stem that has grown its fruit all back an item nowhere in the registry
// while standing on a position the ledger credited, and giving nothing back there would leave that
// credit behind for ever.
// A door, a bed, a tall plant and an extended piston stand in two positions but are paid for once,
// and the credit goes to the half the placement event names. The breaker may strike either half, and
// the debit belongs where the credit is: booked against the struck half instead, it leaves the
// credited half holding a block that is gone while the other gives up one it never got. Each plane
// stays consistent read on its own, so nothing short of the cross-check between them ever notices.
// Where neither half was paid for — a plant the world generated — there is no credit to find and the
// struck half is as good an answer as any.
internal fun paidHalf(
    struck: WorldBlock,
    partner: WorldBlock?,
    remembered: (WorldBlock) -> ByteArray?,
): WorldBlock = partner?.takeIf { remembered(struck) == null && remembered(it) != null } ?: struck

internal fun gaveBack(remembered: ByteArray?, shell: ByteArray?): ByteArray? {
    if (remembered == null) return shell
    if (shell == null) return remembered
    return if (itemTypeIdOf(remembered) == itemTypeIdOf(shell)) remembered else shell
}

// A block that puts an item into the world explains itself before that item exists: the event
// carrying the reason fires first and the entity carrying the uuid appears inside the same call. A
// note bridges the two, and a note nobody claims is dropped rather than guessed at.
// How long the items that came out of an event are kept for the event's row to name them.
private const val WITNESS_MILLIS = 10_000L

// How long a note waits for its spawn at the least, whatever the sweeps say.
private const val NOTE_MILLIS = 1_000L

class SpawnOrigins(private val pending: TickCoalescer, private val clock: () -> Long = System::currentTimeMillis) {
    private class Note(
        // Null when the movement is written by whoever filed the note. Breaking a block already
        // names both ends in one transaction, and the birth that follows must not be booked twice.
        val from: Holder?,
        val cause: Cause,
        val key: ItemKey?,
        val at: Spot?,
        // A block that hands out an item announces it before the item exists, so most notes can only
        // be matched by where they landed. A break is the exception: its entities are built before
        // the event, so the note can name the one it means and distance never enters into it.
        val entity: UUID?,
        var qty: Int,
        val actor: UUID?,
        val reach: Double = SPAWN_REACH,
        val confidence: Confidence = Confidence.FACT,
        // Matched by who threw the item rather than by where it landed, and kept until a moment
        // rather than for two sweeps: a command's drop lands wherever its player is by the time it runs.
        val thrower: UUID? = null,
        val until: Long? = null,
        // Made from a roll of the block's loot rather than from what really fell: carrots, seeds and
        // the like come out in another count each roll, so the note takes whatever of its form lands
        // near it until it is swept, and is asked only after every exact note has had its turn.
        val rolled: Boolean = false,
        // The event the drop comes out of — a dead mob, a broken frame, a block position — under which
        // the items that take this note are remembered, so the event's own row can name them.
        val tag: Any? = null,
    ) {
        // When the first sweep saw it.
        var swept: Long? = null
    }

    private val notes = ConcurrentLinkedQueue<Note>()

    private class Witnessed(val items: MutableSet<UUID>, val at: Long)

    // Which item entities came out of which event. A rollback that puts the event back takes these back
    // from whoever has them, and only these: what else lies around does not belong to it.
    private val witnessed = ConcurrentHashMap<Any, Witnessed>()

    /**
     * The items that came out of an event so far, taken once. Its notes go with it: the event is over, and a
     * note of it left standing took the piles of the next blast along the street under a name nobody would
     * ask about again, and they lay there after the rollback (D114).
     */
    @Synchronized
    fun droppedFor(tag: Any): List<UUID> {
        notes.removeIf { it.tag == tag }
        return witnessed.remove(tag)?.items?.toList() ?: emptyList()
    }

    // A shulker box that falls out of a block something other than a hand broke has to carry the name
    // its contents were filed under, and the only moment to give it one is before its spawn reads its
    // form. Matched by the item and what it holds, so two boxes blown up side by side cannot trade
    // names — and by nothing else: a box used before still carries the name it was given then, on one
    // of the two stacks or on both, and that stale name must not stop it being given the right one.
    private class Box(val stack: NmsItemStack, val owner: UUID, val at: Spot) {
        var swept: Long? = null
    }

    private val boxes = ConcurrentLinkedQueue<Box>()

    val isEmpty: Boolean get() = notes.isEmpty() && boxes.isEmpty()

    fun expectBox(stack: NmsItemStack, owner: UUID, at: Spot) {
        boxes += Box(stack.copy(), owner, at)
    }

    /** The name a box that has just appeared here has to carry, taken once. */
    @Synchronized
    fun ownerFor(stack: NmsItemStack, at: Spot): UUID? {
        val boxes = boxes.iterator()
        while (boxes.hasNext()) {
            val box = boxes.next()
            if (!near(box.at, at) || !sameBox(box.stack, stack)) continue
            boxes.remove()
            return box.owner
        }
        return null
    }

    // `reach` is how far from its block the item may land. An explosion gathers what it breaks into
    // one pile per kind and drops the pile where the first block of that kind stood, so its notes have
    // to reach across the whole crater.
    //
    // Answers how much of the note spawns have taken so far, for a caller that has to tell an item
    // that came out as an entity from one that was spent some other way.
    fun expect(
        from: Holder,
        cause: Cause,
        key: ItemKey,
        at: Spot,
        qty: Int,
        actor: UUID? = null,
        reach: Double = SPAWN_REACH,
        rolled: Boolean = false,
        tag: Any? = null,
    ): () -> Int {
        if (qty <= 0) return { 0 }
        val note = Note(from, cause, key, at, null, qty, actor, reach, rolled = rolled, tag = tag)
        notes += note
        return { qty - note.qty }
    }

    fun expect(
        entity: UUID,
        from: Holder,
        cause: Cause,
        key: ItemKey,
        qty: Int,
        actor: UUID? = null,
        confidence: Confidence = Confidence.FACT,
        tag: Any? = null,
    ) {
        if (qty <= 0) return
        notes += Note(from, cause, key, null, entity, qty, actor, confidence = confidence, tag = tag)
    }

    /**
     * Whatever lands by a block the world destroyed in a form no other note names. A drop left to chance
     * is rolled again by the server, and its roll can bring what the roll a note was made from did not:
     * a vine one time in three, a sapling out of leaves, flint out of gravel. Asked after every other
     * note, so it takes only what nothing else explains.
     */
    fun expectAny(from: Holder, cause: Cause, at: Spot, actor: UUID?, reach: Double = SPAWN_REACH, tag: Any? = null) {
        notes += Note(from, cause, null, at, null, 0, actor, reach, rolled = true, tag = tag)
    }

    fun expectThrown(thrower: UUID, from: Holder, cause: Cause, key: ItemKey, qty: Int, until: Long) {
        if (qty <= 0) return
        notes += Note(from, cause, key, null, null, qty, null, thrower = thrower, until = until)
    }

    /** The birth of this entity is written by its own transaction, so the spawn must stay silent. */
    fun accounted(entity: UUID, qty: Int) {
        if (qty <= 0) return
        notes += Note(null, Cause.ITEM_SPAWN, null, null, entity, qty, null)
    }

    // Locked because a note sits at a block and a spawn is judged by distance, so two regions either
    // side of a chunk border can reach the same note and hand out its quantity twice.
    //
    // Returns how much of the spawn a note accounted for; what is left over is a birth nobody
    // explained, and the caller books it as such rather than letting it pass unrecorded.
    @Synchronized
    fun claim(entity: UUID, at: Spot, key: ItemKey, count: Int, thrower: UUID? = null): Int {
        val into = ItemEntityRef(entity)
        // A note that names the entity is exact, so it goes first: a note left at the same block for
        // some other reason must not take the quantity out from under it.
        val named = take(into, key, count) { it.entity == entity }
        val thrown = if (thrower == null) 0 else take(into, key, count - named) { it.thrower == thrower && it.key == key }
        val nearby = take(into, key, count - named - thrown) { placedFor(it, at, key) }
        val rolled = take(into, key, count - named - thrown - nearby) {
            it.rolled && it.key == key && near(it.at!!, at, it.reach)
        }
        val anyForm = take(into, key, count - named - thrown - nearby - rolled) {
            it.rolled && it.key == null && near(it.at!!, at, it.reach)
        }
        return named + thrown + nearby + rolled + anyForm
    }

    /**
     * An item a dispenser puts down as an entity of another kind — a cart on a rail, a boat on water —
     * claimed by that entity, into the slot it holds the item in, from the note the dispenser left for
     * the item to come out. Only a note naming the item at that spot will do: a block's drop is never a
     * cart.
     */
    @Synchronized
    fun claimInto(into: Holder, at: Spot, key: ItemKey): Boolean = take(into, key, 1) { placedFor(it, at, key) } == 1

    private fun placedFor(note: Note, at: Spot, key: ItemKey) =
        !note.rolled && note.entity == null && note.thrower == null && note.key == key && near(note.at!!, at, note.reach)

    private inline fun take(into: Holder, key: ItemKey, count: Int, matches: (Note) -> Boolean): Int {
        var left = count
        val notes = notes.iterator()
        while (notes.hasNext() && left > 0) {
            val note = notes.next()
            if (!matches(note)) continue
            val qty = if (note.rolled) left else minOf(left, note.qty)
            if (note.from != null) {
                pending.add(note.from, into, note.cause, key, qty, note.actor, note.confidence)
            }
            if (note.tag != null && into is ItemEntityRef) {
                witnessed.computeIfAbsent(note.tag) { Witnessed(ConcurrentHashMap.newKeySet(), System.currentTimeMillis()) }
                    .items += into.uuid
            }
            left -= qty
            if (note.rolled) continue
            note.qty -= qty
            if (note.qty <= 0) notes.remove()
        }
        return count - left
    }

    // Two passes before dropping: a note written by a region thread while this runs would otherwise
    // go before the spawn that follows it microseconds later. And NOTE_MILLIS after the first: the sweep
    // ticks on the global region, and a crater of a few hundred blocks takes its own region longer than two
    // of those ticks to break before the drops spawn — a griefer's dynamite left its loot to nobody (D114).
    @Synchronized
    fun sweep() {
        val now = clock()
        val notes = notes.iterator()
        while (notes.hasNext()) {
            val note = notes.next()
            val until = note.until
            if (until != null) {
                if (now > until) notes.remove()
                continue
            }
            val swept = note.swept
            if (swept == null) note.swept = now else if (now - swept >= NOTE_MILLIS) notes.remove()
        }
        val boxes = boxes.iterator()
        while (boxes.hasNext()) {
            val box = boxes.next()
            val swept = box.swept
            if (swept == null) box.swept = now else if (now - swept >= NOTE_MILLIS) boxes.remove()
        }
        // Asked for a tick after the event; one nobody asks about is gone well before it could matter.
        witnessed.values.removeIf { now - it.at > WITNESS_MILLIS }
    }

    private fun sameBox(expected: NmsItemStack, spawned: NmsItemStack) =
        NmsItemStack.isSameItem(expected, spawned) &&
            expected.get(DataComponents.CONTAINER) == spawned.get(DataComponents.CONTAINER)

    private fun near(origin: Spot, spawn: Spot, reach: Double = SPAWN_REACH) =
        origin.world == spawn.world &&
            abs(origin.x - spawn.x) <= reach &&
            abs(origin.y - spawn.y) <= reach &&
            abs(origin.z - spawn.z) <= reach
}

// Which bottles the brew actually changed. A stand runs with slots empty and with bottles the recipe
// has nothing to say about, and the results list is only as long as the game made it, so a slot counts
// only when it held something before, holds something after, and the two are not the same thing.
internal fun brewed(
    before: List<ItemKey?>,
    after: List<ItemKey?>,
): List<Triple<Int, ItemKey, ItemKey>> = before.indices.mapNotNull { slot ->
    val was = before[slot] ?: return@mapNotNull null
    val became = after.getOrNull(slot) ?: return@mapNotNull null
    if (was == became) null else Triple(slot, was, became)
}

// A shulker keeps what it held inside the item it drops, and that move is written elsewhere.
// A jukebox, a bookshelf, a pot, a lectern, a shelf and a campfire spill what they hold just as a
// chest does, and their slots are the ones a click filled.
internal fun spilled(state: BlockState?): Array<BukkitItemStack?> = when (state) {
    is ShulkerBox -> emptyArray()
    // One half of a double chest: the inventory of a live chest is both halves together.
    is ContainerBlock -> state.snapshotInventory.contents
    is TileStateInventoryHolder -> state.snapshotInventory.contents
    is Campfire -> Array(state.size) { state.getItem(it) }
    else -> emptyArray()
}

// A dispense spawns what it spawns inside its own call, a tick at most after the pre-dispense event,
// and in front of the dispenser.
private const val DISPENSE_NANOS = 50_000_000L
private const val DISPENSE_REACH = 2.5

internal class SlotChange(val slot: Int, val key: ItemKey, val qty: Int, val gain: Boolean)

/**
 * A dispense that put its item down as the block in front of it — a shulker box, a carved pumpkin, a
 * skull: one item gone from the slot, nothing gained in its place, and the block in front changed into
 * one that places as that item. A bucket emptied gains a bucket back and is a mutation instead, and a
 * pumpkin that became a golem left no block to hold it.
 */
internal fun placedInFront(changes: List<SlotChange>, frontBefore: String, frontNow: String, placesAs: Material?, spent: Material) =
    changes.size == 1 && !changes[0].gain && changes[0].qty == 1 && frontBefore != frontNow && placesAs == spent

// What a dispense did to the dispenser beyond what came out of it as an entity. The slot it fired
// from is one short or holds something else — a bucket filled, a bottle filled — and a transformation
// that leaves a remainder puts the product in another slot, as a form that slot did not hold before.
// A hopper feeding the same dispenser in the same tick with a new form is read as part of the
// dispense; telling them apart needs the move event's own slot, which it does not carry.
internal fun dispenseChanges(before: List<Stack?>, after: List<Stack?>, slot: Int, ejected: Int): List<SlotChange> {
    val was = before.getOrNull(slot) ?: return emptyList()
    val now = after.getOrNull(slot)
    val changes = ArrayList<SlotChange>()
    val same = now != null && now.key.form.contentEquals(was.key.form)
    val lost = (if (same) was.count - now!!.count else was.count) - ejected
    if (lost > 0) changes += SlotChange(slot, was.key, lost, gain = false)
    if (now != null && !same) changes += SlotChange(slot, now.key, now.count, gain = true)
    for (other in after.indices) {
        if (other == slot) continue
        val gained = after[other] ?: continue
        val held = before.getOrNull(other)
        if (gained.key.form.contentEquals(was.key.form)) continue
        val qty = if (held != null && held.key.form.contentEquals(gained.key.form)) gained.count - held.count else gained.count
        if (qty > 0) changes += SlotChange(other, gained.key, qty, gain = true)
    }
    return changes
}

class BlockMechanismListener(
    private val codec: ItemFormCodec,
    private val pending: TickCoalescer,
    private val origins: SpawnOrigins,
    private val placed: PlacedForms,
    private val sink: (List<Transfer>) -> Unit,
    private val energy: Energy = Energy(),
    private val entities: EntityOrigins = EntityOrigins(),
    private val owners: RocksItemLog? = null,
    // Leaves a reason for the next pass over a player's slots, which are the pass's alone to write.
    private val intend: (Player, Intent) -> Unit = { _, _ -> },
    // Runs a task on the block's own region a tick later.
    private val later: (Block, () -> Unit) -> Unit = { _, _ -> },
) : Listener {

    // Breaking a block and the drops it causes are one synchronous call on one thread. This is not a
    // cache: it is the only thing that tells that call apart from a player brushing suspicious sand,
    // which raises the drop event on its own, with no break behind it and the block still standing.
    // The other half is found here as well: by the time the drop event arrives both halves are gone
    // from the world, and looking for a partner then finds air. So is what the block held: the state
    // the drop event carries comes with the inventory of a jukebox, a bookshelf, a pot, a shelf or a
    // campfire already empty.
    private class Broken(val at: WorldBlock, val partner: WorldBlock?, val contents: Array<BukkitItemStack?>)

    private val breaking = ThreadLocal<Broken?>()

    // The dispense event is no anchor on its own: the default behaviour splits the item off its slot
    // before raising it, so the last one leaves no slot to find, and a block the sulfur cube could
    // swallow raises it twice for one dispense. The pre-dispense event comes once, before anything
    // moved, with the slot, and the dispense that follows in the same call takes what it saw.
    private class Loaded(val at: WorldBlock, val slot: Int, val before: List<Stack?>, val actor: UUID?)

    private val loaded = ThreadLocal<Loaded?>()

    // Whoever set the dispenser going, for the entities its behaviour spawns inside the same call: an
    // item thrown out, primed TNT, an arrow, a boat. None of them names anybody on its own.
    private class Dispensing(val at: WorldBlock, val by: Attributed, val nanos: Long)

    private val dispensing = ThreadLocal<Dispensing?>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPreDispense(event: BlockPreDispenseEvent) {
        val block = event.block
        val before = contentsOf(block) ?: return
        val at = positionOf(block)
        val by = energyAt(block, energy)
        by?.let { energy.note(at, it) }
        loaded.set(Loaded(at, event.slot, before, by.culprit()))
        dispensing.set(by?.let { Dispensing(at, it, System.nanoTime()) })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpawn(event: EntitySpawnEvent) {
        val source = dispensing.get() ?: return
        if (System.nanoTime() - source.nanos > DISPENSE_NANOS) return dispensing.remove()
        val spot = event.location
        if (spot.world.uid != source.at.world) return
        if (abs(spot.x - source.at.x - 0.5) > DISPENSE_REACH || abs(spot.y - source.at.y - 0.5) > DISPENSE_REACH ||
            abs(spot.z - source.at.z - 0.5) > DISPENSE_REACH
        ) {
            return
        }
        entities.appeared(event.entity.uniqueId, source.by.actor, source.by.confidence)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDispense(event: BlockDispenseEvent) {
        val block = event.block
        val item = event.item
        val key = key(item) ?: return
        val at = positionOf(block)
        // A carved pumpkin and a skull raise the plain event first and then, from inside the same call,
        // the armour event when they end up on a head. The second takes over the dispense the first
        // began, and the first's reading of the dispenser a tick later stands down.
        val begun = if (event is BlockDispenseArmorEvent) dispensed.get()?.takeIf { it.at == at } else null
        begun?.equipped = true
        val load = begun?.load ?: loaded.get()?.takeIf { it.at == at } ?: return
        loaded.remove()
        val slot = load.slot
        val from = containerAt(block, slot)
        if (event is BlockDispenseArmorEvent) {
            val target = event.targetEntity
            // Armour put on a player lands in a slot of the player's own, written by the pass.
            if (target is Player) {
                intend(target, Intent(Cause.DISPENSER_BEHAVIOR, from = from, form = key.form, qty = item.amount, actor = load.actor))
                return
            }
            val equipped = EntitySlot(target.uniqueId, equipmentSlotOf(target, item))
            bookHeld(target, equipped.slot, key.form)
            pending.add(from, equipped, Cause.DISPENSER_BEHAVIOR, key, item.amount, load.actor)
            return
        }
        // An item that turns into an entity is claimed by its spawn. Everything else a dispenser does —
        // bone meal, a boat, TNT, a bucket filled, an arrow fired — spends or changes what it holds,
        // and the only way to know which is to look at the dispenser once the behaviour has run.
        val cause = if (block.type == Material.DROPPER) Cause.DROPPER_EJECT else Cause.DISPENSER_EJECT
        val ejected = origins.expect(from, cause, key, spotOf(block.location), item.amount, load.actor)
        val stack = CraftItemStack.asNMSCopy(item)
        val projectile = stack.item is ProjectileItem
        val front = (block.blockData as? Directional)?.facing?.let(block::getRelative)
        val frontBefore = front?.blockData?.asString
        val started = Dispensed(at, load)
        dispensed.set(started)
        later(block) {
            if (started.equipped) return@later
            settleDispense(block, slot, load.before, ejected(), projectile, load.actor, front, frontBefore, stack)
        }
    }

    private class Dispensed(val at: WorldBlock, val load: Loaded) {
        @Volatile var equipped = false
    }

    private val dispensed = ThreadLocal<Dispensed?>()

    private fun settleDispense(
        block: Block,
        slot: Int,
        before: List<Stack?>,
        ejected: Int,
        projectile: Boolean,
        actor: UUID?,
        front: Block?,
        frontBefore: String?,
        spent: NmsItemStack,
    ) {
        val after = contentsOf(block) ?: return
        val changes = dispenseChanges(before, after, slot, ejected)
        if (changes.isEmpty()) return
        if (front != null && frontBefore != null) {
            val now = front.blockData
            val material = CraftItemStack.asBukkitCopy(spent).type
            if (placedInFront(changes, frontBefore, now.asString, now.placementMaterial, material)) {
                return placedFromDispenser(block, changes.single(), front, spent, actor)
            }
        }
        // One item spent is a movement; one turned into another is a mutation of both sides together.
        val mutated = changes.any { it.gain }
        val cause = if (projectile && !mutated) Cause.DISPENSED_PROJECTILE else Cause.DISPENSER_BEHAVIOR
        val timestamp = System.currentTimeMillis()
        sink(changes.map { change ->
            val at = containerAt(block, change.slot)
            Transfer(
                cause = cause,
                from = if (change.gain) Void else at,
                to = if (change.gain) at else Void,
                form = change.key.form,
                damage = change.key.damage,
                qty = change.qty,
                timestamp = timestamp,
                kind = if (mutated) Kind.MUTATE else Kind.TRANSFER,
                actor = actor,
            )
        })
    }

    // What a hand's placement writes, from the dispenser's slot: the position takes the item over, and a
    // shulker box's contents move from the item's name into the block's slots.
    private fun placedFromDispenser(block: Block, change: SlotChange, front: Block, spent: NmsItemStack, actor: UUID?) {
        val at = positionOf(front)
        val timestamp = System.currentTimeMillis()
        placed.setFormAt(at.world, at.x, at.y, at.z, change.key.form)
        val moves = arrayListOf(
            Transfer(
                cause = Cause.DISPENSER_BEHAVIOR,
                from = containerAt(block, change.slot),
                to = at,
                form = change.key.form,
                damage = change.key.damage,
                qty = 1,
                timestamp = timestamp,
                actor = actor,
            )
        )
        val owners = owners
        if (owners != null && Tag.SHULKER_BOXES.isTagged(front.type)) {
            val owner = NestedItems.ownerOf(spent) ?: UUID.randomUUID()
            owners.setOwnerAt(at.world, at.x, at.y, at.z, owner)
            for ((index, child) in NestedItems.contents(spent)) {
                val encoded = codec.encode(child)
                moves += Transfer(
                    cause = Cause.CONTAINER_PLACE_UNPACK,
                    from = Nested(owner, index),
                    to = containerAt(front, index),
                    form = encoded.key.form,
                    damage = encoded.key.damage,
                    qty = encoded.count,
                    timestamp = timestamp,
                    actor = actor,
                )
            }
        }
        sink(moves)
    }

    private fun contentsOf(block: Block): List<Stack?>? {
        val inventory = (block.getState(false) as? ContainerBlock)?.inventory ?: return null
        return (0 until inventory.size).map { slot ->
            codec.stackOf(inventory.getItem(slot))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        // Copies: the stacks read here mirror the live slots, which the break empties before the drop.
        val contents = spilled(block.getState(false)).map { it?.clone() }.toTypedArray()
        breaking.set(Broken(positionOf(block), otherHalfOf(block)?.let(::positionOf), contents))
    }

    // What spilled is read slot by slot from the break, while the entities carrying it already exist.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockDrop(event: BlockDropItemEvent) {
        val block = event.block
        val broken = brokeHere(block) ?: return revealed(event)
        val state = event.blockState
        val actor = event.player.uniqueId
        val timestamp = System.currentTimeMillis()
        // A break does not conserve anything — stone answers with cobblestone, grass with seeds or
        // with nothing — so the shell that goes and the items that arrive are not the two ends of one
        // movement and neither may name the other. Both face Void, and the shared tx_id is the only
        // thing that carries a walk of the graph across the break.
        val transaction = ArrayList<Transfer>(event.items.size + 1)
        val position = paidHalf(broken.at, broken.partner, ::rememberedAt)
        val remembered = rememberedAt(position)
        // Cleared here because no other way for a block to leave says so, and on both halves because
        // a half left alone would go on naming a block that stands nowhere.
        forget(broken.at)
        broken.partner?.let(::forget)
        gaveBack(remembered, codec.shellOf((state.blockData as CraftBlockData).state.block))?.let {
            transaction += Transfer(
                cause = Cause.BLOCK_DROP,
                from = position,
                to = Void,
                form = it,
                damage = null,
                qty = 1,
                timestamp = timestamp,
                actor = actor,
            )
        }
        // Read once: every getItem call builds a fresh mirror of the slot, and the inner loop runs
        // for each dropped entity.
        val contents = broken.contents
        val left = IntArray(contents.size) { contents[it]?.amount ?: 0 }
        for (dropped in event.items) {
            val stack = dropped.itemStack
            val key = key(stack) ?: continue
            val entity = ItemEntityRef(dropped.uniqueId)
            var unspilled = stack.amount
            for (slot in contents.indices) {
                if (unspilled <= 0) break
                if (left[slot] <= 0 || contents[slot]?.isSimilar(stack) != true) continue
                val qty = minOf(unspilled, left[slot])
                left[slot] -= qty
                unspilled -= qty
                pending.add(containerAt(block, slot), entity, Cause.CONTAINER_BREAK_DROP, key, qty, actor)
            }
            if (unspilled > 0) {
                transaction += Transfer(
                    cause = Cause.BLOCK_DROP,
                    from = Void,
                    to = entity,
                    form = key.form,
                    damage = key.damage,
                    qty = unspilled,
                    timestamp = timestamp,
                    actor = actor,
                )
            }
            // The birth of each of these is written right here, so the spawn that follows inside this
            // same call has nothing left to say.
            origins.accounted(dropped.uniqueId, stack.amount)
        }
        sink(transaction)
    }

    // Brushing raises the drop event with no break behind it: the find comes out of the loot table and
    // the block turns plain. The entities already exist, so their births are written here.
    private fun revealed(event: BlockDropItemEvent) {
        val type = event.blockState.type
        if (type != Material.SUSPICIOUS_SAND && type != Material.SUSPICIOUS_GRAVEL) return
        for (dropped in event.items) {
            val key = key(dropped.itemStack) ?: continue
            val amount = dropped.itemStack.amount
            pending.add(Void, ItemEntityRef(dropped.uniqueId), Cause.BRUSHABLE_REVEAL, key, amount, event.player.uniqueId)
            origins.accounted(dropped.uniqueId, amount)
        }
    }

    // A furnace eats its fuel outright, and a bucket of lava leaves the empty bucket in its place.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFurnaceBurn(event: FurnaceBurnEvent) {
        if (!event.isBurning || !event.willConsumeFuel()) return
        val fuel = event.fuel
        val key = key(fuel) ?: return
        val slot = containerAt(event.block, FURNACE_FUEL_SLOT)
        pending.add(slot, Void, Cause.FURNACE_FUEL_CONSUME, key, 1)
        if (fuel.amount != 1) return
        val remainder = CraftItemStack.asNMSCopy(fuel).item.craftingRemainder?.create() ?: return
        val encoded = codec.encode(remainder)
        pending.add(Void, slot, Cause.FURNACE_FUEL_REMAINDER, encoded.key, remainder.count)
    }

    // The ingredient is spent outright, while each bottle comes back as something else in the slot it
    // stood in. Both halves of every bottle are readable here — the event carries the stand as it was
    // and the results side by side — and the whole brew is one transaction, so a potion traced back
    // names the water bottle it was made from and the brew that made it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBrew(event: BrewEvent) {
        val block = event.block
        key(event.contents.ingredient)?.let {
            pending.add(containerAt(block, BREWING_INGREDIENT_SLOT), Void, Cause.BREWING_INGREDIENT_CONSUME, it, 1)
        }
        val timestamp = System.currentTimeMillis()
        val transaction = ArrayList<Transfer>(BREWING_BOTTLES * 2)
        val before = List(BREWING_BOTTLES) { key(event.contents.getItem(it)) }
        val after = List(BREWING_BOTTLES) { slot -> event.results.getOrNull(slot)?.let { key(it) } }
        for ((slot, was, became) in brewed(before, after)) {
            val bottle = containerAt(block, slot)
            transaction += mutation(bottle, Void, Cause.BREW, was, 1, timestamp)
            transaction += mutation(Void, bottle, Cause.BREW, became, 1, timestamp)
        }
        if (transaction.isNotEmpty()) sink(transaction)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBrewingFuel(event: BrewingStandFuelEvent) {
        if (!event.isConsuming) return
        val key = key(event.fuel) ?: return
        pending.add(containerAt(event.block, BREWING_FUEL_SLOT), Void, Cause.BREWING_FUEL_CONSUME, key, 1)
    }

    // Every occupied slot of a crafter gives up exactly one item per craft, and the result it hands
    // out is written where it lands, by the mechanism listener.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCrafterCraft(event: CrafterCraftEvent) {
        val block = event.block
        val inventory = (block.getState(false) as? ContainerBlock)?.inventory ?: return
        for (slot in 0 until inventory.size) {
            val key = key(inventory.getItem(slot)) ?: continue
            pending.add(containerAt(block, slot), Void, Cause.CRAFTER_CONSUME, key, 1)
        }
    }

    // This event covers furnaces as well, and a furnace turning ore into an ingot is a transformation
    // rather than a mechanism; only the campfire drops its result into the world.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCook(event: BlockCookEvent) {
        val block = event.block
        val campfire = block.getState(false) as? Campfire
        if (campfire == null) {
            smelted(event)
            return
        }
        val source = event.source
        val slot = (0 until campfire.size).firstOrNull { campfire.getItem(it)?.isSimilar(source) == true } ?: return
        key(source)?.let { pending.add(containerAt(block, slot), Void, Cause.CAMPFIRE_COOK_DROP, it, source.amount) }
        val result = event.result
        key(result)?.let { origins.expect(Void, Cause.CAMPFIRE_COOK_DROP, it, spotOf(block.location), result.amount) }
    }

    // A furnace changes one item into another with nobody watching, so there is no pass to count it
    // and no intent to leave: both halves are known here and are written on the spot. They face the
    // Void and share a transaction, which is what says the ingot is what became of the ore rather
    // than an arrival that happens to follow a disappearance.
    private fun smelted(event: BlockCookEvent) {
        val block = event.block
        if (block.getState(false) !is Furnace) return
        val source = key(event.source) ?: return
        val result = key(event.result) ?: return
        val timestamp = System.currentTimeMillis()
        sink(
            listOf(
                // The event carries a mirror of the whole input slot, while the smelt takes exactly one
                // item out of it. Booking the stack would write a loss of sixty-four every time a full
                // furnace finished a single ingot.
                mutation(containerAt(block, FURNACE_INPUT_SLOT), Void, Cause.SMELT, source, 1, timestamp),
                mutation(Void, containerAt(block, FURNACE_RESULT_SLOT), Cause.SMELT, result, event.result.amount, timestamp),
            )
        )
    }

    private fun mutation(from: Holder, to: Holder, cause: Cause, key: ItemKey, qty: Int, timestamp: Long) =
        Transfer(
            cause = cause,
            from = from,
            to = to,
            form = key.form,
            damage = key.damage,
            qty = qty,
            timestamp = timestamp,
            kind = Kind.MUTATE,
        )

    // The composter destroys what it eats, and out of nothing makes bone meal once it fills up. The
    // filling item is what earns the bone meal, but the composter only turns it out a second later
    // and no event announces that, so the bone meal is booked against the item that paid for it.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onCompost(event: CompostItemEvent) {
        val block = event.block
        val slot = containerAt(block, 0)
        val actor = ((event as? EntityCompostItemEvent)?.entity as? Player)?.uniqueId
        key(event.item)?.let { pending.add(slot, Void, Cause.COMPOSTER_CONSUME, it, 1, actor) }
        if (!event.willRaiseLevel()) return
        if ((block.blockData as? Levelled)?.level != COMPOSTER_FULL_LEVEL - 1) return
        val bonemeal = key(BukkitItemStack(Material.BONE_MEAL)) ?: return
        pending.add(Void, slot, Cause.COMPOSTER_BONEMEAL, bonemeal, 1, actor)
    }

    // Shearing a pumpkin or a nest both land here, and only the hive belongs to this class of causes.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShearBlock(event: PlayerShearBlockEvent) {
        val block = event.block
        // A hive gives its honeycomb, a pumpkin its seeds as it is carved.
        val hive = block.type == Material.BEEHIVE || block.type == Material.BEE_NEST
        val cause = if (hive) Cause.BEEHIVE_HARVEST else Cause.BLOCK_INTERACT_DROP
        val actor = event.player.uniqueId
        for (drop in event.drops) {
            val key = key(drop) ?: continue
            origins.expect(Void, cause, key, spotOf(block.location), drop.amount, actor)
        }
    }

    private fun brokeHere(block: Block): Broken? {
        val broken = breaking.get()
        breaking.remove()
        return broken?.takeIf { it.at == positionOf(block) }
    }

    private fun rememberedAt(at: WorldBlock) = placed.formAt(at.world, at.x, at.y, at.z)

    private fun forget(at: WorldBlock) = placed.clearFormAt(at.world, at.x, at.y, at.z)

    private fun key(stack: BukkitItemStack?): ItemKey? = codec.encodeOrNull(stack)?.key
}
