package io.pfaumc.pfauprotect

import io.papermc.paper.event.block.CompostItemEvent
import io.papermc.paper.event.block.PlayerShearBlockEvent
import io.papermc.paper.event.entity.EntityCompostItemEvent
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Campfire
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
import net.minecraft.world.item.ItemStack as NmsItemStack
import org.bukkit.block.Container as ContainerBlock
import org.bukkit.inventory.ItemStack as BukkitItemStack

private const val FURNACE_FUEL_SLOT = 1
private const val BREWING_INGREDIENT_SLOT = 3
private const val BREWING_FUEL_SLOT = 4
// The scale runs to seven; the eighth state is the composter already turned into bone meal.
private const val COMPOSTER_FULL_LEVEL = 7

// An item entity that appears next to the block it came out of, and no further than a dispenser
// throws.
private const val SPAWN_REACH = 2.0

data class Spot(val world: UUID, val x: Double, val y: Double, val z: Double)

internal fun spotOf(at: Location) = Spot(at.world.uid, at.x, at.y, at.z)

// A position gives back the item it took over, and the block alone cannot say what that was: a named
// box, an enchanted head and a plain chest all answer with the bare item, so the two rows at one
// position would name different forms. What was put down is remembered while it stands, and is
// trusted only while the position still holds that same item — a block that arrived without being
// placed, pushed in by a piston or grown out of dirt, would otherwise be given back as whatever stood
// there before.
internal fun gaveBack(remembered: ByteArray?, shell: ByteArray?): ByteArray? {
    if (shell == null || remembered == null) return shell
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
    ) {
        var swept = false
    }

    private val notes = ConcurrentLinkedQueue<Note>()

    val isEmpty: Boolean get() = notes.isEmpty()

    fun expect(from: Holder, cause: Cause, key: ItemKey, at: Spot, qty: Int, actor: UUID? = null) {
        if (qty <= 0) return
        notes += Note(from, cause, key, at, null, qty, actor)
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
        val nearby = take(entity, key, count - named) { it.entity == null && it.key == key && near(it.at!!, at) }
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
    }

    private fun near(origin: Spot, spawn: Spot) =
        origin.world == spawn.world &&
            abs(origin.x - spawn.x) <= SPAWN_REACH &&
            abs(origin.y - spawn.y) <= SPAWN_REACH &&
            abs(origin.z - spawn.z) <= SPAWN_REACH
}

class BlockMechanismListener(
    private val codec: ItemFormCodec,
    private val pending: TickCoalescer,
    private val origins: SpawnOrigins,
    private val placed: PlacedForms,
    private val sink: (List<Transfer>) -> Unit,
) : Listener {

    // Breaking a block and the drops it causes are one synchronous call on one thread. This is not a
    // cache: it is the only thing that tells that call apart from a player brushing suspicious sand,
    // which raises the drop event on its own, with no break behind it and the block still standing.
    private val breaking = ThreadLocal<WorldBlock?>()

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
            pending.add(from, equipped, Cause.DISPENSER_BEHAVIOR, key, item.amount)
            return
        }
        // Only an item that turns into an entity has left the block: a dispenser that shears, fills a
        // bucket or lights a fire keeps or transforms what it holds, and guessing which of those
        // happened would invent rows for items that never moved.
        val cause = if (block.type == Material.DROPPER) Cause.DROPPER_EJECT else Cause.DISPENSER_EJECT
        origins.expect(from, cause, key, spotOf(block.location), item.amount)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        breaking.set(positionOf(event.block))
    }

    // The state handed to this event is the one from before the break, so what spilled is still
    // readable slot by slot while the entities carrying it already exist.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockDrop(event: BlockDropItemEvent) {
        val block = event.block
        if (!brokeHere(block)) return
        val state = event.blockState
        val actor = event.player.uniqueId
        val timestamp = System.currentTimeMillis()
        // A break does not conserve anything — stone answers with cobblestone, grass with seeds or
        // with nothing — so the shell that goes and the items that arrive are not the two ends of one
        // movement and neither may name the other. Both face Void, and the shared tx_id is the only
        // thing that carries a walk of the graph across the break.
        val transaction = ArrayList<Transfer>(event.items.size + 1)
        val position = positionOf(block)
        val remembered = placed.formAt(position.world, position.x, position.y, position.z)
        // Cleared here because no other way for a block to leave says so.
        placed.clearFormAt(position.world, position.x, position.y, position.z)
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBrew(event: BrewEvent) {
        val key = key(event.contents.ingredient) ?: return
        pending.add(containerAt(event.block, BREWING_INGREDIENT_SLOT), Void, Cause.BREWING_INGREDIENT_CONSUME, key, 1)
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
        val campfire = block.getState(false) as? Campfire ?: return
        val source = event.source
        val slot = (0 until campfire.size).firstOrNull { campfire.getItem(it)?.isSimilar(source) == true } ?: return
        key(source)?.let { pending.add(containerAt(block, slot), Void, Cause.CAMPFIRE_COOK_DROP, it, source.amount) }
        val result = event.result
        key(result)?.let { origins.expect(Void, Cause.CAMPFIRE_COOK_DROP, it, spotOf(block.location), result.amount) }
    }

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

    private fun brokeHere(block: Block): Boolean {
        val broken = breaking.get()
        breaking.remove()
        return broken == positionOf(block)
    }

    // A shulker keeps what it held inside the item it drops, and that move is written elsewhere.
    private fun spilled(state: BlockState): Array<BukkitItemStack?> {
        if (state is ShulkerBox) return emptyArray()
        return (state as? ContainerBlock)?.inventory?.contents ?: emptyArray()
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
