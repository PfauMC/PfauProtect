package io.pfaumc.pfauprotect

import io.papermc.paper.event.block.CompostItemEvent
import io.papermc.paper.event.block.PlayerShearBlockEvent
import io.papermc.paper.event.entity.EntityCompostItemEvent
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Campfire
import org.bukkit.block.ShulkerBox
import org.bukkit.block.data.Levelled
import org.bukkit.craftbukkit.entity.CraftLivingEntity
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockCookEvent
import org.bukkit.event.block.BlockDispenseArmorEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.inventory.BrewEvent
import org.bukkit.event.inventory.BrewingStandFuelEvent
import org.bukkit.event.inventory.FurnaceBurnEvent
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
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

// A block that puts an item into the world explains itself before that item exists: the event
// carrying the reason fires first and the entity carrying the uuid appears inside the same call. A
// note bridges the two, and a note nobody claims is dropped rather than guessed at.
class SpawnOrigins(private val pending: TickCoalescer) {
    private class Note(
        val from: Holder,
        val cause: Cause,
        val key: ItemKey,
        val at: Spot,
        var qty: Int,
        val actor: UUID?,
    ) {
        var swept = false
    }

    private val notes = ConcurrentLinkedQueue<Note>()

    val isEmpty: Boolean get() = notes.isEmpty()

    fun expect(from: Holder, cause: Cause, key: ItemKey, at: Spot, qty: Int, actor: UUID? = null) {
        if (qty <= 0) return
        notes += Note(from, cause, key, at, qty, actor)
    }

    // Locked because a note sits at a block and a spawn is judged by distance, so two regions either
    // side of a chunk border can reach the same note and hand out its quantity twice.
    @Synchronized
    fun claim(entity: UUID, at: Spot, key: ItemKey, count: Int) {
        var left = count
        val notes = notes.iterator()
        while (notes.hasNext() && left > 0) {
            val note = notes.next()
            if (note.key != key || !near(note.at, at)) continue
            val qty = minOf(left, note.qty)
            pending.add(note.from, ItemEntityRef(entity), note.cause, key, qty, note.actor)
            note.qty -= qty
            left -= qty
            if (note.qty <= 0) notes.remove()
        }
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
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDispense(event: BlockDispenseEvent) {
        val block = event.block
        val item = event.item
        val key = key(item) ?: return
        val slot = slotHolding(block, item) ?: return
        val from = holder(block, slot)
        if (event is BlockDispenseArmorEvent) {
            val target = event.targetEntity
            val equipped = EntitySlot(target.uniqueId, equipmentSlot(target, item))
            pending.add(from, equipped, Cause.DISPENSER_BEHAVIOR, key, item.amount)
            return
        }
        // Only an item that turns into an entity has left the block: a dispenser that shears, fills a
        // bucket or lights a fire keeps or transforms what it holds, and guessing which of those
        // happened would invent rows for items that never moved.
        val cause = if (block.type == Material.DROPPER) Cause.DROPPER_EJECT else Cause.DISPENSER_EJECT
        origins.expect(from, cause, key, spot(block.location), item.amount)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemSpawn(event: ItemSpawnEvent) {
        if (origins.isEmpty) return
        val entity = event.entity
        val stack = entity.itemStack
        val key = key(stack) ?: return
        origins.claim(entity.uniqueId, spot(entity.location), key, stack.amount)
    }

    // The state handed to this event is the one from before the break, so what spilled is still
    // readable slot by slot while the entities carrying it already exist.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockDrop(event: BlockDropItemEvent) {
        // A shulker keeps what it held inside the item it drops, and that move is written elsewhere.
        if (event.blockState is ShulkerBox) return
        val inventory = (event.blockState as? ContainerBlock)?.inventory ?: return
        val block = event.block
        val actor = event.player.uniqueId
        val left = IntArray(inventory.size) { inventory.getItem(it)?.amount ?: 0 }
        for (dropped in event.items) {
            val stack = dropped.itemStack
            val key = key(stack) ?: continue
            var need = stack.amount
            for (slot in 0 until inventory.size) {
                if (need <= 0) break
                if (left[slot] <= 0 || inventory.getItem(slot)?.isSimilar(stack) != true) continue
                val qty = minOf(need, left[slot])
                left[slot] -= qty
                need -= qty
                val entity = ItemEntityRef(dropped.uniqueId)
                pending.add(holder(block, slot), entity, Cause.CONTAINER_BREAK_DROP, key, qty, actor)
            }
        }
    }

    // A furnace eats its fuel outright, and a bucket of lava leaves the empty bucket in its place.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFurnaceBurn(event: FurnaceBurnEvent) {
        if (!event.isBurning || !event.willConsumeFuel()) return
        val fuel = event.fuel
        val key = key(fuel) ?: return
        val slot = holder(event.block, FURNACE_FUEL_SLOT)
        pending.add(slot, Void, Cause.FURNACE_FUEL_CONSUME, key, 1)
        if (fuel.amount != 1) return
        val remainder = CraftItemStack.asNMSCopy(fuel).item.craftingRemainder?.create() ?: return
        val encoded = codec.encode(remainder)
        pending.add(Void, slot, Cause.FURNACE_FUEL_REMAINDER, ItemKey(encoded.form, encoded.damage), remainder.count)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBrew(event: BrewEvent) {
        val key = key(event.contents.ingredient) ?: return
        pending.add(holder(event.block, BREWING_INGREDIENT_SLOT), Void, Cause.BREWING_INGREDIENT_CONSUME, key, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBrewingFuel(event: BrewingStandFuelEvent) {
        if (!event.isConsuming) return
        val key = key(event.fuel) ?: return
        pending.add(holder(event.block, BREWING_FUEL_SLOT), Void, Cause.BREWING_FUEL_CONSUME, key, 1)
    }

    // Every occupied slot of a crafter gives up exactly one item per craft, and the result it hands
    // out is written where it lands, by the mechanism listener.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCrafterCraft(event: CrafterCraftEvent) {
        val block = event.block
        val inventory = (block.getState(false) as? ContainerBlock)?.inventory ?: return
        for (slot in 0 until inventory.size) {
            val key = key(inventory.getItem(slot)) ?: continue
            pending.add(holder(block, slot), Void, Cause.CRAFTER_CONSUME, key, 1)
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
        key(source)?.let { pending.add(holder(block, slot), Void, Cause.CAMPFIRE_COOK_DROP, it, source.amount) }
        val result = event.result
        key(result)?.let { origins.expect(Void, Cause.CAMPFIRE_COOK_DROP, it, spot(block.location), result.amount) }
    }

    // The composter destroys what it eats, and out of nothing makes bone meal once it fills up. The
    // filling item is what earns the bone meal, but the composter only turns it out a second later
    // and no event announces that, so the bone meal is booked against the item that paid for it.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onCompost(event: CompostItemEvent) {
        val block = event.block
        val slot = holder(block, 0)
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
            origins.expect(Void, Cause.BEEHIVE_HARVEST, key, spot(block.location), drop.amount, actor)
        }
    }

    private fun spot(at: Location) = Spot(at.world.uid, at.x, at.y, at.z)

    private fun holder(block: Block, slot: Int) = Container(block.world.uid, block.x, block.y, block.z, slot)

    private fun slotHolding(block: Block, item: BukkitItemStack): Int? {
        val inventory = (block.getState(false) as? ContainerBlock)?.inventory ?: return null
        return (0 until inventory.size).firstOrNull { inventory.getItem(it)?.isSimilar(item) == true }
    }

    private fun equipmentSlot(target: org.bukkit.entity.LivingEntity, item: BukkitItemStack): Int =
        (target as CraftLivingEntity).handle.getEquipmentSlotForItem(CraftItemStack.asNMSCopy(item)).ordinal

    private fun key(stack: BukkitItemStack?): ItemKey? {
        val nms = CraftItemStack.asNMSCopy(stack ?: return null)
        if (nms.isEmpty) return null
        val encoded = codec.encode(nms)
        return ItemKey(encoded.form, encoded.damage)
    }
}
