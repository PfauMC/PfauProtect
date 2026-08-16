package io.pfaumc.pfauprotect

import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.LootGenerateEvent
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.PlayerInventory
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.block.Container as ContainerBlock
import org.bukkit.inventory.ItemStack as BukkitItemStack

data class Stack(val key: ItemKey, val count: Int)

data class Edge(
    val from: Holder,
    val to: Holder,
    val key: ItemKey,
    val qty: Int,
    val confidence: Confidence,
)

private class Delta(val holder: Holder, val key: ItemKey, var count: Int)

object Netting {
    fun diff(before: Map<Holder, Stack>, after: Map<Holder, Stack>): List<Edge> {
        val losses = ArrayList<Delta>()
        val gains = ArrayList<Delta>()
        val holders = LinkedHashSet(before.keys).apply { addAll(after.keys) }
        for (holder in holders) {
            // split, shrink and consume mutate a stack in place and the game leaves the emptied object
            // in the slot, so a counter at or below zero is the only reliable sign of an empty slot.
            val was = before[holder]?.takeIf { it.count > 0 }
            val now = after[holder]?.takeIf { it.count > 0 }
            when {
                was == null -> if (now != null) gains += Delta(holder, now.key, now.count)
                now == null -> losses += Delta(holder, was.key, was.count)
                was.key == now.key -> {
                    val delta = now.count - was.count
                    if (delta > 0) gains += Delta(holder, now.key, delta)
                    if (delta < 0) losses += Delta(holder, was.key, -delta)
                }

                else -> {
                    losses += Delta(holder, was.key, was.count)
                    gains += Delta(holder, now.key, now.count)
                }
            }
        }

        val unclaimed = HashMap<ItemKey, ArrayDeque<Delta>>()
        for (gain in gains) unclaimed.getOrPut(gain.key) { ArrayDeque() }.addLast(gain)

        val edges = ArrayList<Edge>()
        for (loss in losses) {
            val matching = unclaimed[loss.key]
            while (loss.count > 0 && matching != null && matching.isNotEmpty()) {
                val gain = matching.first()
                val qty = minOf(loss.count, gain.count)
                edges += Edge(loss.holder, gain.holder, loss.key, qty, Confidence.FACT)
                loss.count -= qty
                gain.count -= qty
                if (gain.count == 0) matching.removeFirst()
            }
            if (loss.count > 0) edges += Edge(loss.holder, Void, loss.key, loss.count, Confidence.INFERRED)
        }
        for (gain in gains) {
            if (gain.count > 0) edges += Edge(Void, gain.holder, gain.key, gain.count, Confidence.INFERRED)
        }
        return edges
    }
}

// Both halves of a double chest keep their own position and their own slot numbering, because a row
// has to name the block its items can be put back into.
internal fun containerHolders(inventory: Inventory): ((Int) -> Holder)? {
    if (inventory is DoubleChestInventory) {
        val left = containerAt(inventory.leftSide)
        val right = containerAt(inventory.rightSide)
        val leftSize = inventory.leftSide.size
        if (left != null && right != null) {
            return { slot ->
                if (slot < leftSize) left.copy(slot = slot) else right.copy(slot = slot - leftSize)
            }
        }
    }
    // Only the position is wanted, and a snapshot holder would copy the whole block state.
    val holder = inventory.getHolder(false)
    val block = (holder as? BlockInventoryHolder)?.block
    if (block != null) {
        val container = containerAt(block, 0)
        return { slot -> container.copy(slot = slot) }
    }
    // A minecart rides the rails, so only its uuid addresses it; its position is where something
    // happened, not what it is.
    val entity = holder as? Entity
    if (entity != null) {
        val uuid = entity.uniqueId
        return { slot -> EntitySlot(uuid, slot) }
    }
    return null
}

private fun containerAt(inventory: Inventory): Container? {
    val location = inventory.location ?: return null
    val world = location.world ?: return null
    return Container(world.uid, location.blockX, location.blockY, location.blockZ, 0)
}

internal fun containerAt(block: Block, slot: Int) =
    Container(block.world.uid, block.x, block.y, block.z, slot)

internal fun playerHolders(uuid: UUID, inventory: PlayerInventory): (Int) -> Holder {
    val storageSize = inventory.storageContents.size
    return { slot -> if (slot < storageSize) PlayerInv(uuid, slot) else PlayerEquip(uuid, slot) }
}

internal fun causeOf(edge: Edge): Cause = when {
    edge.to is Nested -> Cause.BUNDLE_INSERT
    edge.from is Nested -> Cause.BUNDLE_EXTRACT
    edge.to is Container || edge.to is PlayerEnder || edge.to is EntitySlot -> Cause.CONTAINER_ADD
    edge.from is Container || edge.from is PlayerEnder || edge.from is EntitySlot -> Cause.CONTAINER_REMOVE
    edge.from is PlayerCursor -> Cause.CURSOR_PLACE
    edge.to is PlayerCursor -> Cause.CURSOR_TAKE
    else -> Cause.QUICK_MOVE
}

class ContainerCaptureListener(
    private val plugin: Plugin,
    private val sink: (Transfer) -> Unit,
    private val codec: ItemFormCodec,
) : Listener {
    private class Baseline(val view: InventoryView, val stacks: Map<Holder, Stack>)

    private val baselines = ConcurrentHashMap<UUID, Baseline>()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        rebaseline(event.player)
    }

    // Scheduled work is dropped when the player's scheduler retires, which happens in the same block
    // that fires this event, so the last interaction has to be diffed here and now.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        recompute(event.player)
        baselines.remove(event.player.uniqueId)
    }

    fun recomputeAll() {
        for (player in Bukkit.getOnlinePlayers()) recompute(player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        baselines[player.uniqueId] = Baseline(event.view, snapshot(player, event.view))
    }

    // Structure loot has no earlier existence to move from, so without this every naturally generated
    // chest hands out items that were never received and can never balance.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLootGenerate(event: LootGenerateEvent) {
        if (!plugin.isEnabled) return
        val block = (event.inventoryHolder as? BlockInventoryHolder)?.block ?: return
        val generated = event.loot.mapNotNull { encode(it) }
        if (generated.isEmpty()) return
        // Vault and trial rewards roll per player on purpose, so who triggered the table is what tells
        // a legitimate second helping apart from an item appearing twice.
        val actor = (event.entity as? Player)?.uniqueId
        // The table shuffles its result into slots only after this event returns, so which slot each
        // stack landed in is knowable a tick later and from the container itself, not from the list.
        Bukkit.getRegionScheduler().run(plugin, block.location) { recordLoot(block, generated, actor) }
    }

    private fun recordLoot(block: Block, generated: List<Stack>, actor: UUID?) {
        val inventory = (block.state as? ContainerBlock)?.inventory ?: return
        val pending = HashMap<ItemKey, Int>()
        for (stack in generated) pending.merge(stack.key, stack.count, Int::plus)
        val timestamp = System.currentTimeMillis()
        for (slot in 0 until inventory.size) {
            val found = encode(inventory.getItem(slot)) ?: continue
            val left = pending[found.key] ?: continue
            val qty = minOf(left, found.count)
            if (qty <= 0) continue
            pending[found.key] = left - qty
            sink(
                Transfer(
                    cause = Cause.LOOT_GENERATE,
                    from = Void,
                    to = containerAt(block, slot),
                    form = found.key.form,
                    damage = found.key.damage,
                    qty = qty,
                    timestamp = timestamp,
                    actor = actor,
                )
            )
        }
    }

    // Putting a block down or knocking one out moves items in and out of the hand with no window open,
    // and that class of movement has no capture of its own yet. Diffing across it would report every
    // placed block as a disappearance into nothing, so the line of reference is redrawn instead.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        resync(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        resync(event.player)
    }

    private fun resync(player: Player) {
        if (!plugin.isEnabled) return
        player.scheduler.run(plugin, { rebaseline(player) }, null)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        scheduleRecompute(event.whoClicked as? Player ?: return)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        scheduleRecompute(event.whoClicked as? Player ?: return)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        if (!plugin.isEnabled) return
        player.scheduler.run(plugin, {
            recompute(player)
            rebaseline(player)
        }, null)
    }

    // The event arrives before the click is applied and may still be denied, so the snapshot that
    // decides what moved has to wait for the next tick on the region that owns the view. Scheduling
    // is refused once the plugin is disabled, and the shutdown pass covers what is left open.
    private fun scheduleRecompute(player: Player) {
        if (!plugin.isEnabled) return
        player.scheduler.run(plugin, { recompute(player) }, null)
    }

    private fun recompute(player: Player) {
        val baseline = baselines[player.uniqueId] ?: return
        val after = snapshot(player, baseline.view)
        baselines[player.uniqueId] = Baseline(baseline.view, after)
        val timestamp = System.currentTimeMillis()
        for (edge in Netting.diff(baseline.stacks, after)) {
            sink(
                Transfer(
                    cause = causeOf(edge),
                    from = edge.from,
                    to = edge.to,
                    form = edge.key.form,
                    damage = edge.key.damage,
                    qty = edge.qty,
                    timestamp = timestamp,
                    confidence = edge.confidence,
                )
            )
        }
    }

    private fun rebaseline(player: Player) {
        val view = player.openInventory
        baselines[player.uniqueId] = Baseline(view, snapshot(player, view))
    }

    private fun snapshot(player: Player, view: InventoryView): Map<Holder, Stack> {
        val stacks = LinkedHashMap<Holder, Stack>()
        val top = view.topInventory
        val topHolder = topHolders(player, top)
        if (topHolder != null) {
            for (slot in 0 until top.size) record(stacks, topHolder(slot), top.getItem(slot))
        }
        val inventory = player.inventory
        val holders = playerHolders(player.uniqueId, inventory)
        for (slot in 0 until inventory.size) record(stacks, holders(slot), inventory.getItem(slot))
        record(stacks, PlayerCursor(player.uniqueId), player.itemOnCursor)
        return stacks
    }

    // Bukkit hands out live mirrors of the server's stacks, so a snapshot has to turn every slot into
    // bytes of its own here and now; keeping the stack itself would be keeping a view of the future.
    //
    // A container item is recorded as itself plus a row per item it holds, filed under the container's
    // own name rather than the slot it sits in, so carrying it around moves nothing. One level only:
    // a container deeper down keeps its own name and its contents are already filed under it, and it
    // cannot be reached to change without being taken out first.
    private fun record(into: MutableMap<Holder, Stack>, holder: Holder, stack: BukkitItemStack?) {
        val live = (stack as? CraftItemStack)?.handle ?: CraftItemStack.asNMSCopy(stack ?: return)
        if (live.isEmpty) return
        val contents = NestedItems.contents(live)
        // Naming it has to happen before the form is taken, or the same item would read as a different
        // one on the next pass and the diff would invent a movement out of it.
        val owner = if (contents.isEmpty()) null else NestedItems.own(live)
        val encoded = codec.encode(live)
        into[holder] = Stack(encoded.key, encoded.count)
        if (owner == null) return
        for ((index, child) in contents) {
            val inside = codec.encode(child)
            into[Nested(owner, index)] = Stack(inside.key, inside.count)
        }
    }

    private fun encode(stack: BukkitItemStack?): Stack? =
        codec.encodeOrNull(stack)?.let { Stack(it.key, it.count) }

    private fun topHolders(player: Player, inventory: Inventory): ((Int) -> Holder)? {
        val viewer = player.uniqueId
        // An ender chest reports the coordinates of the block being used while its contents belong to
        // the player, so it has to be recognised before anything that trusts a location.
        if (inventory.type == InventoryType.ENDER_CHEST) return { slot -> PlayerEnder(viewer, slot) }
        if (inventory is PlayerInventory) {
            // Another player's inventory is ticked by the region that owns them, so reading it from
            // here would race with the owner and could invent an edge out of a half-applied change.
            if (inventory.holder?.uniqueId?.equals(viewer) == false) return null
            return playerHolders(viewer, inventory)
        }
        containerHolders(inventory)?.let { return it }
        // Menu types carry no number of their own yet; ordinals are stable within a server version.
        val menuType = inventory.type.ordinal
        return { slot -> MenuSlot(menuType, slot) }
    }
}
