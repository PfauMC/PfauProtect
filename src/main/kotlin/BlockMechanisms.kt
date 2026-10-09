package io.pfaumc.pfauprotect

import io.papermc.paper.event.block.CompostItemEvent
import io.papermc.paper.event.block.PlayerShearBlockEvent
import io.papermc.paper.block.TileStateInventoryHolder
import io.papermc.paper.event.entity.EntityCompostItemEvent
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
import org.bukkit.event.inventory.BrewEvent
import org.bukkit.event.inventory.BrewingStandFuelEvent
import org.bukkit.event.inventory.FurnaceBurnEvent
import java.util.UUID
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
class SpawnOrigins(private val pending: TickCoalescer) {
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
    ) {
        var swept = false
    }

    private val notes = ConcurrentLinkedQueue<Note>()

    // A shulker box that falls out of a block something other than a hand broke has to carry the name
    // its contents were filed under, and the only moment to give it one is before its spawn reads its
    // form. Matched by the item and what it holds, so two boxes blown up side by side cannot trade
    // names — and by nothing else: a box used before still carries the name it was given then, on one
    // of the two stacks or on both, and that stale name must not stop it being given the right one.
    private class Box(val stack: NmsItemStack, val owner: UUID, val at: Spot) {
        var swept = false
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
    ): () -> Int {
        if (qty <= 0) return { 0 }
        val note = Note(from, cause, key, at, null, qty, actor, reach)
        notes += note
        return { qty - note.qty }
    }

    fun expect(entity: UUID, from: Holder, cause: Cause, key: ItemKey, qty: Int, actor: UUID? = null) {
        if (qty <= 0) return
        notes += Note(from, cause, key, null, entity, qty, actor)
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
    fun claim(entity: UUID, at: Spot, key: ItemKey, count: Int): Int {
        // A note that names the entity is exact, so it goes first: a note left at the same block for
        // some other reason must not take the quantity out from under it.
        val named = take(entity, key, count) { it.entity == entity }
        val nearby = take(entity, key, count - named) { it.entity == null && it.key == key && near(it.at!!, at, it.reach) }
        return named + nearby
    }

    private inline fun take(entity: UUID, key: ItemKey, count: Int, matches: (Note) -> Boolean): Int {
        var left = count
        val notes = notes.iterator()
        while (notes.hasNext() && left > 0) {
            val note = notes.next()
            if (!matches(note)) continue
            val qty = minOf(left, note.qty)
            if (note.from != null) {
                pending.add(note.from, ItemEntityRef(entity), note.cause, key, qty, note.actor)
            }
            left -= qty
            note.qty -= qty
            if (note.qty <= 0) notes.remove()
        }
        return count - left
    }

    // Two passes before dropping: a note written by a region thread while this runs would otherwise
    // go before the spawn that follows it microseconds later.
    @Synchronized
    fun sweep() {
        val notes = notes.iterator()
        while (notes.hasNext()) {
            val note = notes.next()
            if (note.swept) notes.remove() else note.swept = true
        }
        val boxes = boxes.iterator()
        while (boxes.hasNext()) {
            val box = boxes.next()
            if (box.swept) boxes.remove() else box.swept = true
        }
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

internal class SlotChange(val slot: Int, val key: ItemKey, val qty: Int, val gain: Boolean)

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
    // Runs a task on the block's own region a tick later.
    private val later: (Block, () -> Unit) -> Unit = { _, _ -> },
) : Listener {

    // Breaking a block and the drops it causes are one synchronous call on one thread. This is not a
    // cache: it is the only thing that tells that call apart from a player brushing suspicious sand,
    // which raises the drop event on its own, with no break behind it and the block still standing.
    // The other half is found here as well: by the time the drop event arrives both halves are gone
    // from the world, and looking for a partner then finds air.
    private class Broken(val at: WorldBlock, val partner: WorldBlock?)

    private val breaking = ThreadLocal<Broken?>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDispense(event: BlockDispenseEvent) {
        val block = event.block
        val item = event.item
        val key = key(item) ?: return
        val slot = slotHolding(block, item) ?: return
        val from = containerAt(block, slot)
        if (event is BlockDispenseArmorEvent) {
            val target = event.targetEntity
            val equipped = EntitySlot(target.uniqueId, equipmentSlotOf(target, item))
            bookHeld(target, equipped.slot, key.form)
            pending.add(from, equipped, Cause.DISPENSER_BEHAVIOR, key, item.amount)
            return
        }
        // An item that turns into an entity is claimed by its spawn. Everything else a dispenser does —
        // bone meal, a boat, TNT, a bucket filled, an arrow fired — spends or changes what it holds,
        // and the only way to know which is to look at the dispenser once the behaviour has run.
        val cause = if (block.type == Material.DROPPER) Cause.DROPPER_EJECT else Cause.DISPENSER_EJECT
        val ejected = origins.expect(from, cause, key, spotOf(block.location), item.amount)
        val before = contentsOf(block) ?: return
        val projectile = CraftItemStack.asNMSCopy(item).item is ProjectileItem
        later(block) { settleDispense(block, slot, before, ejected(), projectile) }
    }

    private fun settleDispense(block: Block, slot: Int, before: List<Stack?>, ejected: Int, projectile: Boolean) {
        val after = contentsOf(block) ?: return
        val changes = dispenseChanges(before, after, slot, ejected)
        if (changes.isEmpty()) return
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
            )
        })
    }

    private fun contentsOf(block: Block): List<Stack?>? {
        val inventory = (block.getState(false) as? ContainerBlock)?.inventory ?: return null
        return (0 until inventory.size).map { slot ->
            codec.encodeOrNull(inventory.getItem(slot))?.let { Stack(it.key, it.count) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        breaking.set(Broken(positionOf(event.block), otherHalfOf(event.block)?.let(::positionOf)))
    }

    // The state handed to this event is the one from before the break, so what spilled is still
    // readable slot by slot while the entities carrying it already exist.
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
        gaveBack(remembered, shellForm(state))?.let {
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
        val contents = spilled(state)
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
        if (block.type != Material.BEEHIVE && block.type != Material.BEE_NEST) return
        val actor = event.player.uniqueId
        for (drop in event.drops) {
            val key = key(drop) ?: continue
            origins.expect(Void, Cause.BEEHIVE_HARVEST, key, spotOf(block.location), drop.amount, actor)
        }
    }

    private fun brokeHere(block: Block): Broken? {
        val broken = breaking.get()
        breaking.remove()
        return broken?.takeIf { it.at == positionOf(block) }
    }

    private fun rememberedAt(at: WorldBlock) = placed.formAt(at.world, at.x, at.y, at.z)

    private fun forget(at: WorldBlock) = placed.clearFormAt(at.world, at.x, at.y, at.z)

    // A shulker keeps what it held inside the item it drops, and that move is written elsewhere.
    // A jukebox, a bookshelf, a pot, a lectern, a shelf and a campfire spill what they hold just as a
    // chest does, and their slots are the ones a click filled.
    private fun spilled(state: BlockState): Array<BukkitItemStack?> = when (state) {
        is ShulkerBox -> emptyArray()
        is ContainerBlock -> state.inventory.contents
        is TileStateInventoryHolder -> state.snapshotInventory.contents
        is Campfire -> Array(state.size) { state.getItem(it) }
        else -> emptyArray()
    }

    // What the block was made of, not what breaking it yields: a crop answers with the seed it was
    // planted from, and a block with no item form of its own — fire, a liquid, a portal — answers
    // with nothing and so is never written off.
    private fun shellForm(state: BlockState): ByteArray? {
        val data = state.blockData as CraftBlockData
        val stack = NmsItemStack(data.state.block.asItem())
        return if (stack.isEmpty) null else codec.encode(stack).form
    }

    private fun slotHolding(block: Block, item: BukkitItemStack): Int? {
        val inventory = (block.getState(false) as? ContainerBlock)?.inventory ?: return null
        return (0 until inventory.size).firstOrNull { inventory.getItem(it)?.isSimilar(item) == true }
    }

    private fun key(stack: BukkitItemStack?): ItemKey? = codec.encodeOrNull(stack)?.key
}
