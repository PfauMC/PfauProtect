package io.pfaumc.pfauprotect.capture.item
import org.bukkit.craftbukkit.block.CraftBlock
import net.minecraft.world.level.block.ShelfBlock
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.capture.block.SlotChange
import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.capture.block.spotOf
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Campfire
import org.bukkit.craftbukkit.CraftEquipmentSlot
import org.bukkit.entity.Allay
import org.bukkit.entity.Entity
import org.bukkit.entity.Piglin
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerTakeLecternBookEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryHolder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.inventory.ItemStack as BukkitItemStack

// How long after a click a mob's equipment change is still taken for the result of that click. The
// change is noticed on the mob's own tick, which can come after the click's.
private const val EQUIP_TICKS = 2

// Beyond this many unanswered clicks the stale ones are thrown out; a click on a cow with nothing
// in hand never gets an equipment change to answer it.
private const val EQUIP_PENDING_CAP = 256

// Blocks that keep items in slots of their own and are filled and emptied by a click, with no event
// that says which item or which slot.
internal fun keepsItems(type: Material): Boolean =
    type == Material.JUKEBOX || type == Material.CHISELED_BOOKSHELF || type == Material.DECORATED_POT ||
        type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE || type == Material.LECTERN ||
        type == Material.FLOWER_POT || type.name.endsWith("_SHELF")

// What a click did to a block's slots: each slot that holds more of a form than before took it in,
// each that holds less gave it out.
internal fun slotDiff(before: List<Stack?>, after: List<Stack?>): List<SlotChange> {
    val changes = ArrayList<SlotChange>()
    for (slot in 0 until maxOf(before.size, after.size)) {
        val was = before.getOrNull(slot)
        val now = after.getOrNull(slot)
        if (was != null && now != null && was.key.form.contentEquals(now.key.form)) {
            val delta = now.count - was.count
            if (delta > 0) changes += SlotChange(slot, now.key, delta, gain = true)
            if (delta < 0) changes += SlotChange(slot, was.key, -delta, gain = false)
            continue
        }
        if (was != null) changes += SlotChange(slot, was.key, was.count, gain = false)
        if (now != null) changes += SlotChange(slot, now.key, now.count, gain = true)
    }
    return changes
}

// Everything that holds an item the player gave it: a block with slots and no window, a stand, a frame,
// a placed boat, a mob wearing a saddle. The player's side is the pass's to write, as always; what
// these handlers add is the far end, and where the entity has to remember what it holds, the mark.
class HolderListener(
    private val capture: ContainerCaptureListener,
    private val codec: ItemFormCodec,
    private val origins: SpawnOrigins,
    // Runs a task on the block's own region a tick later.
    private val later: (Block, () -> Unit) -> Unit = { _, _ -> },
) : Listener {

    private class Handed(val player: Player, val hand: EquipmentSlot, val form: ByteArray?, val tick: Int)

    private val handed = ConcurrentHashMap<UUID, Handed>()

    // A jukebox, a bookshelf, a shelf, a pot or a campfire is changed by the click itself. What it held
    // before is only readable now, and what it holds after only once the click has been applied.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onBlockUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock() == Event.Result.DENY) return
        val block = event.clickedBlock ?: return
        val type = block.type
        // Both have events of their own that name the item.
        if (!keepsItems(type) || type == Material.LECTERN || type == Material.FLOWER_POT) return
        // A powered shelf swaps the whole hotbar with every shelf chained to it, not the hand alone.
        val blocks = poweredChain(block) ?: listOf(block)
        val before = blocks.map { slotsOf(it) ?: return }
        val player = event.player
        // A record comes out of the jukebox onto the ground rather than into the hand.
        if (type == Material.JUKEBOX) {
            before[0].getOrNull(0)?.let {
                val spot = spotOf(block.location.add(0.5, 1.0, 0.5))
                origins.expect(containerAt(block, 0), Cause.CONTAINER_REMOVE, it.key, spot, it.count, player.uniqueId)
            }
        }
        val hand = if (blocks.size == 1 && poweredChain(block) == null) capture.handSlot(player, event.hand) else null
        val into = if (type == Material.JUKEBOX) Cause.RECORD_INTO_JUKEBOX else Cause.ITEM_INTO_SINGLE_BLOCK
        later(block) {
            blocks.forEachIndexed { index, part ->
                val after = slotsOf(part) ?: return@forEachIndexed
                for (change in slotDiff(before[index], after)) {
                    val at = containerAt(part, change.slot)
                    val intent = if (change.gain) {
                        Intent(into, to = at, form = change.key.form, qty = change.qty, holder = hand)
                    } else {
                        Intent(Cause.CONTAINER_REMOVE, from = at, form = change.key.form, qty = change.qty)
                    }
                    capture.intend(player, intent)
                }
            }
        }
    }

    private fun poweredChain(block: Block): List<Block>? {
        val craft = block as CraftBlock
        val state = craft.blockState
        val shelf = state.block as? ShelfBlock ?: return null
        if (!state.getValue(ShelfBlock.POWERED)) return null
        return shelf.getAllBlocksConnectedTo(craft.level, craft.position).map { block.world.getBlockAt(it.x, it.y, it.z) }
            .ifEmpty { null }
    }

    private fun slotsOf(block: Block): List<Stack?>? {
        val state = block.getState(false)
        val stacks: List<BukkitItemStack?> = when (state) {
            is Campfire -> (0 until state.size).map { state.getItem(it) }
            is BlockInventoryHolder -> state.inventory.contents.toList()
            else -> return null
        }
        return stacks.map { stack -> codec.encodeOrNull(stack)?.let { Stack(it.key, it.count) } }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLecternInsert(event: PlayerInsertLecternBookEvent) {
        val form = codec.encodeOrNull(event.book)?.form ?: return
        capture.intend(event.player, Intent(Cause.BOOK_ONTO_LECTERN, to = containerAt(event.block, 0), form = form, qty = 1))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLecternTake(event: PlayerTakeLecternBookEvent) {
        val form = codec.encodeOrNull(event.book)?.form ?: return
        val from = containerAt(event.lectern.block, 0)
        capture.intend(event.player, Intent(Cause.CONTAINER_REMOVE, from = from, form = form, qty = 1))
    }

    // A potted plant is a block of its own and not a pot holding an item, so the plant goes into the
    // block the way a placed block does — out of the item plane — and comes back out of it the same way.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFlowerPot(event: PlayerFlowerPotManipulateEvent) {
        val form = codec.encodeOrNull(event.item)?.form ?: return
        val intent = if (event.isPlacing) {
            Intent(Cause.ITEM_INTO_SINGLE_BLOCK, to = Void, form = form, qty = 1)
        } else {
            Intent(Cause.CONTAINER_REMOVE, from = Void, form = form, qty = 1)
        }
        capture.intend(event.player, intent)
    }

    // A stand swaps what the player holds with what it wears in that slot, one item at a time.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onArmorStand(event: PlayerArmorStandManipulateEvent) {
        val stand = event.rightClicked
        val player = event.player
        val slot = CraftEquipmentSlot.getNMS(event.slot).ordinal
        codec.encodeOrNull(event.armorStandItem)?.let { taken ->
            capture.intend(player, Intent(Cause.ARMOR_STAND_SWAP, from = takeFrom(stand, slot, taken.form), form = taken.form, qty = taken.count))
        }
        // Creative dresses the stand in a copy and takes nothing from the player.
        if (player.gameMode == GameMode.CREATIVE) return
        codec.encodeOrNull(event.playerItem)?.let { given ->
            bookHeld(stand, slot, given.form)
            val to = EntitySlot(stand.uniqueId, slot)
            capture.intend(player, Intent(Cause.ARMOR_STAND_SWAP, to = to, form = given.form, qty = 1, holder = capture.handSlot(player, event.hand)))
        }
    }

    // A boat, a minecart, a stand or an end crystal: the item becomes the entity, and breaking the
    // entity gives the item back, so the entity holds it meanwhile.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlaceEntity(event: EntityPlaceEvent) {
        val player = event.player ?: return
        placed(player, event.entity, player.inventory.getItem(event.hand), event.hand)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHang(event: HangingPlaceEvent) {
        val player = event.player ?: return
        placed(player, event.entity, event.itemStack ?: return, event.hand ?: return)
    }

    private fun placed(player: Player, entity: Entity, stack: BukkitItemStack, hand: EquipmentSlot) {
        if (player.gameMode == GameMode.CREATIVE) return
        val form = codec.encodeOrNull(stack)?.form ?: return
        bookHeld(entity, ENTITY_ITEM_SLOT, form)
        val to = EntitySlot(entity.uniqueId, ENTITY_ITEM_SLOT)
        capture.intend(player, Intent(Cause.PLACE_ENTITY_ITEM, to = to, form = form, qty = 1, holder = capture.handSlot(player, hand)))
    }

    // Taking the item back out drops it as an entity, and the drop is where it comes out of the frame.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFrame(event: PlayerItemFrameChangeEvent) {
        if (event.action != PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE) return
        val player = event.player
        if (player.gameMode == GameMode.CREATIVE) return
        val form = codec.encodeOrNull(event.itemStack)?.form ?: return
        val frame = event.itemFrame
        bookHeld(frame, 0, form)
        capture.intend(player, Intent(Cause.PLACE_ENTITY_ITEM, to = EntitySlot(frame.uniqueId, 0), form = form, qty = 1))
    }

    // Which item a player held out to a mob, until the mob's equipment says what became of it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        val form = codec.encodeOrNull(player.inventory.getItem(event.hand))?.form
        val now = Bukkit.getCurrentTick()
        if (handed.size > EQUIP_PENDING_CAP) handed.values.removeIf { now - it.tick > EQUIP_TICKS }
        handed[event.rightClicked.uniqueId] = Handed(player, event.hand, form, now)
    }

    // A saddle, a harness, horse or wolf armour put on with a click, an item handed to an allay or a
    // piglin, or taken back from one. The server only says so after the fact, from the mob's side.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onEquipment(event: EntityEquipmentChangedEvent) {
        val mob = event.entity
        if (mob is Player) return
        val click = handed.remove(mob.uniqueId)?.takeIf { Bukkit.getCurrentTick() - it.tick <= EQUIP_TICKS }
            ?: return forgetEmptied(mob, event)
        for ((slot, change) in event.equipmentChanges) {
            val index = CraftEquipmentSlot.getNMS(slot).ordinal
            val put = codec.encodeOrNull(change.newItem())
            val removed = codec.encodeOrNull(change.oldItem())
            if (put != null && click.form?.contentEquals(put.form) == true) {
                bookHeld(mob, index, put.form)
                val cause = if (mob is Allay || mob is Piglin) Cause.GIVE_ITEM_TO_MOB else Cause.EQUIP_MOB
                val hand = capture.handSlot(click.player, click.hand)
                capture.intend(click.player, Intent(cause, to = EntitySlot(mob.uniqueId, index), form = put.form, qty = put.count, holder = hand))
            } else if (put == null && removed != null && click.form == null) {
                val from = takeFrom(mob, index, removed.form)
                capture.intend(click.player, Intent(Cause.CONTAINER_REMOVE, from = from, form = removed.form, qty = removed.count))
            }
        }
    }

    // What falls out of a chest boat or a chest minecart falls straight out of its slots, with no drop
    // event of its own, right after this one.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVehicleDestroy(event: VehicleDestroyEvent) {
        val vehicle = event.vehicle
        val contents = (vehicle as? InventoryHolder)?.inventory?.contents ?: return
        val spot = spotOf(vehicle.location)
        val actor = (event.attacker as? Player)?.uniqueId
        for ((slot, stack) in contents.withIndex()) {
            val encoded = codec.encodeOrNull(stack) ?: continue
            origins.expect(EntitySlot(vehicle.uniqueId, slot), Cause.CONTAINER_BREAK_DROP, encoded.key, spot, encoded.count, actor)
        }
    }

    // Out of the slot the ledger put it in, or out of nowhere for what the entity came with.
    // A saddle taken out through the horse's window is written by the window; the mark saying it sits
    // in that slot goes, or the horse's death would write the saddle off a second time.
    private fun forgetEmptied(mob: Entity, event: EntityEquipmentChangedEvent) {
        val held = heldBy(mob)
        for ((slot, change) in event.equipmentChanges) {
            val index = CraftEquipmentSlot.getNMS(slot).ordinal
            val booked = held[index] ?: continue
            val now = codec.encodeOrNull(change.newItem())?.form
            if (now == null || !now.contentEquals(booked)) unbookHeld(mob, index)
        }
    }

    private fun takeFrom(entity: Entity, slot: Int, form: ByteArray): Holder {
        if (heldBy(entity)[slot]?.contentEquals(form) != true) return Void
        unbookHeld(entity, slot)
        return EntitySlot(entity.uniqueId, slot)
    }
}
