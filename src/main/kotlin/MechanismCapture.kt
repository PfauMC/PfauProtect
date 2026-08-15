package io.pfaumc.pfauprotect

import net.minecraft.core.Direction
import net.minecraft.world.WorldlyContainer
import org.bukkit.craftbukkit.inventory.CraftInventory
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.world.Container as NmsContainer
import net.minecraft.world.item.ItemStack as NmsItemStack

data class Fitting(val slot: Int, val qty: Int)

object Placement {
    // A mechanism event arrives before the game tries to place anything, and how much lands is
    // decided afterwards by exactly this test. A hopper aimed at a container that cannot take its
    // item fires for as long as it holds it, so believing the event alone would write a transfer per
    // attempt for a movement that never happens.
    fun fit(destination: NmsContainer, stack: NmsItemStack, face: Direction?): Fitting? {
        var slot = -1
        var room = 0
        forEachSlot(destination, face) { candidate ->
            val capacity = capacity(destination, candidate, stack, face)
            if (capacity > 0) {
                if (slot < 0) slot = candidate
                room += capacity
            }
            room < stack.count
        }
        return if (slot < 0) null else Fitting(slot, minOf(room, stack.count))
    }

    // A mechanism empties one slot at a time, so the first slot holding the moved form is the one it
    // came out of.
    fun holdingSlot(source: NmsContainer, stack: NmsItemStack, face: Direction?): Int {
        var found = -1
        forEachSlot(source, face) { candidate ->
            if (NmsItemStack.isSameItemSameComponents(source.getItem(candidate), stack)) found = candidate
            found < 0
        }
        return found
    }

    private fun capacity(destination: NmsContainer, slot: Int, stack: NmsItemStack, face: Direction?): Int {
        if (!destination.canPlaceItem(slot, stack)) return 0
        if (destination is WorldlyContainer && face != null &&
            !destination.canPlaceItemThroughFace(slot, stack, face)
        ) {
            return 0
        }
        val current = destination.getItem(slot)
        if (current.isEmpty) return destination.maxStackSize
        if (!NmsItemStack.isSameItemSameComponents(current, stack)) return 0
        return minOf(stack.maxStackSize, destination.maxStackSize) - current.count
    }

    // A container with faces takes and gives through the slots of one face only: a hopper under a
    // furnace reaches its output, one at its side reaches its fuel.
    private inline fun forEachSlot(container: NmsContainer, face: Direction?, keepGoing: (Int) -> Boolean) {
        val faceSlots = if (container is WorldlyContainer && face != null) container.getSlotsForFace(face) else null
        val size = faceSlots?.size ?: container.containerSize
        for (i in 0 until size) if (!keepGoing(faceSlots?.get(i) ?: i)) return
    }
}

// Mechanisms move a single item at a time: a minecart hopper crossing a line of hoppers, or a
// dropper on a clock, would otherwise write a transaction per item. Movements sharing both ends and
// the form are one movement within the tick they happen in.
class TickCoalescer(private val sink: (Transfer) -> Unit) {
    private data class Pending(val from: Holder, val to: Holder, val cause: Cause, val item: ItemKey)

    private val pending = ConcurrentHashMap<Pending, Int>()

    fun add(from: Holder, to: Holder, cause: Cause, item: ItemKey, qty: Int) {
        if (qty <= 0) return
        pending.merge(Pending(from, to, cause, item), qty, Int::plus)
    }

    // Removal hands back everything the region threads had merged under the key, so a movement added
    // while this runs either leaves with the rest or waits for the next tick, and is never halved.
    fun flush() {
        if (pending.isEmpty()) return
        val timestamp = System.currentTimeMillis()
        for (key in pending.keys) {
            val qty = pending.remove(key) ?: continue
            sink(
                Transfer(
                    cause = key.cause,
                    from = key.from,
                    to = key.to,
                    form = key.item.form,
                    damage = key.item.damage,
                    qty = qty,
                    timestamp = timestamp,
                )
            )
        }
    }
}

class MechanismCaptureListener(
    private val codec: ItemFormCodec,
    private val pending: TickCoalescer,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: InventoryMoveItemEvent) {
        // Touching the item is what keeps the event alive: when no handler reads or writes it, the
        // region stops firing it for the rest of the hopper pass and those movements are lost.
        val moved = CraftItemStack.asNMSCopy(event.item)
        val source = event.source
        val destination = event.destination
        val pulled = event.initiator == destination
        val cause = when {
            pulled && heldByEntity(destination) -> Cause.HOPPER_MINECART_PULL
            pulled -> Cause.HOPPER_PULL_CONTAINER
            source.type == InventoryType.CRAFTER -> Cause.CRAFTER_EMIT
            source.type == InventoryType.DROPPER -> Cause.DROPPER_PUSH
            else -> Cause.HOPPER_PUSH
        }
        record(source, destination, moved, cause)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickup(event: InventoryPickupItemEvent) {
        val destination = event.inventory
        val into = containerHolders(destination) ?: return
        val container = nms(destination) ?: return
        val entity = event.item
        val stack = CraftItemStack.asNMSCopy(entity.itemStack)
        if (stack.isEmpty) return
        val fitting = Placement.fit(container, stack, null) ?: return
        val cause = if (heldByEntity(destination)) Cause.HOPPER_MINECART_PULL else Cause.HOPPER_PULL_GROUND
        submit(ItemEntityRef(entity.uniqueId), into(fitting.slot), cause, stack, fitting.qty)
    }

    private fun record(source: Inventory, destination: Inventory, moved: NmsItemStack, cause: Cause) {
        if (moved.isEmpty) return
        val into = containerHolders(destination) ?: return
        val from = nms(source) ?: return
        val to = nms(destination) ?: return
        val fitting = Placement.fit(to, moved, faceTowards(to, from)) ?: return
        val origin = origin(source, from, to, moved, cause) ?: return
        submit(origin, into(fitting.slot), cause, moved, fitting.qty)
    }

    // A crafter hands out what it has just made, and the result never sat in a slot to be taken from:
    // the ingredients it consumed are a transformation, which nothing captures yet.
    private fun origin(
        source: Inventory,
        from: NmsContainer,
        to: NmsContainer,
        moved: NmsItemStack,
        cause: Cause,
    ): Holder? {
        if (cause == Cause.CRAFTER_EMIT) return Void
        val out = containerHolders(source) ?: return null
        val slot = Placement.holdingSlot(from, moved, faceTowards(from, to))
        return if (slot < 0) null else out(slot)
    }

    private fun submit(from: Holder, to: Holder, cause: Cause, stack: NmsItemStack, qty: Int) {
        val encoded = codec.encode(stack)
        pending.add(from, to, cause, ItemKey(encoded.form, encoded.damage), qty)
    }

    private fun nms(inventory: Inventory): NmsContainer? = (inventory as? CraftInventory)?.inventory

    // Two neighbours meet through the face between them, and that face is what decides which slots of
    // a furnace or a brewing stand are in play at all.
    private fun faceTowards(container: NmsContainer, other: NmsContainer): Direction? {
        val here = container.location ?: return null
        val there = other.location ?: return null
        if (here.world != there.world) return null
        return Direction.getNearest(
            there.blockX - here.blockX,
            there.blockY - here.blockY,
            there.blockZ - here.blockZ,
            null,
        )
    }

    private fun heldByEntity(inventory: Inventory) = inventory.getHolder(false) is Entity
}
