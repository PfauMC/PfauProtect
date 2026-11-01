package io.pfaumc.pfauprotect.rollback

import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.math.Position
import io.pfaumc.pfauprotect.capture.item.positionOf
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.say
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.craftbukkit.block.data.CraftBlockData
import net.kyori.adventure.text.Component
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType
import io.pfaumc.pfauprotect.tr
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val GHOST_LIMIT = 50_000

/** A block as a preview would put it, shown to the player who previews until it is applied or dropped. */
class Ghost(val at: WorldBlock, val data: BlockData) {
    val chunk get() = chunkOf(at)
}

/** What a container would hold after the rollback, and the block it would stand in. */
class Contents(val data: BlockData, val items: List<ItemStack>)

// The window a preview's contents open in: nothing in it can be taken or put.
private class Peek : InventoryHolder {
    lateinit var window: Inventory
    override fun getInventory() = window
}

private fun chunkOf(at: WorldBlock) = at.world to Chunk.getChunkKey(at.x shr 4, at.z shr 4)

/** The ghosts a player is shown: the nearest first, those of other worlds after them, [limit] at most. */
internal fun nearest(ghosts: List<Ghost>, world: UUID, x: Double, y: Double, z: Double, limit: Int): List<Ghost> {
    fun distance(it: Ghost) = (it.at.x + 0.5 - x).let { d -> d * d } + (it.at.y + 0.5 - y).let { d -> d * d } + (it.at.z + 0.5 - z).let { d -> d * d }
    return ghosts.sortedWith(compareBy({ it.at.world != world }, ::distance)).take(limit)
}

/**
 * The previewing player sees the blocks as the rollback would put them, for them alone, until they apply,
 * cancel, preview again or the preview runs out. The client drops a ghost whenever the server tells it about
 * that block again: a chunk it did not have yet or sent anew after the player went away and came back, a
 * click on the block.
 */
class Ghosts(private val plugin: Plugin) : Listener {
    // A class rather than the bare map for its identity: an expiry removes only the preview it was set
    // for, and a second preview of the same blocks is an equal map.
    private class Shown(val byChunk: Map<Pair<UUID, Long>, List<Ghost>>, val contents: Map<WorldBlock, Contents>)

    private val shown = ConcurrentHashMap<UUID, Shown>()

    fun show(player: Player, ghosts: List<Ghost>, contents: Map<WorldBlock, Contents> = emptyMap()) {
        val at = player.location
        val kept = nearest(ghosts, at.world.uid, at.x, at.y, at.z, GHOST_LIMIT)
        val mine = Shown(kept.groupBy { it.chunk }, contents)
        // The old preview's blocks are told back as they are, except those the new one shows: a restore that
        // lands after the new send would otherwise put real blocks over its ghosts.
        val empty = kept.isEmpty() && contents.isEmpty()
        val old = if (empty) shown.remove(player.uniqueId) else shown.put(player.uniqueId, mine)
        old?.let { restore(player, it, except = kept.mapTo(HashSet()) { it.at }) }
        if (empty) return
        if (kept.isNotEmpty()) {
            send(player, kept.filter { it.at.world == at.world.uid })
            player.say(
                if (kept.size < ghosts.size) "  you see the nearest blocks (${kept.size} of ${ghosts.size}) as they would stand; nothing changes before /pp apply."
                else "  you see the blocks as they would stand; nothing changes before /pp apply."
            )
        }
        if (contents.isNotEmpty()) player.say("  right-click a container it gives back to, to see what it would hold.")
        Bukkit.getAsyncScheduler().runDelayed(plugin, {
            if (shown.remove(player.uniqueId, mine)) restore(player, mine)
        }, PENDING_MILLIS, TimeUnit.MILLISECONDS)
    }

    fun hide(player: Player) {
        shown.remove(player.uniqueId)?.let { restore(player, it) }
    }

    @EventHandler
    fun onChunkSent(event: PlayerChunkLoadEvent) {
        val ghosts = shown[event.player.uniqueId]?.byChunk?.get(event.world.uid to event.chunk.chunkKey) ?: return
        send(event.player, ghosts)
    }

    // A right click on a container the preview gives back to shows what it would hold instead of opening it, with
    // either hand: denied in the main one, the server tries the off hand next and would open the real one.
    @EventHandler(priority = EventPriority.LOWEST)
    fun onUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val clicked = event.clickedBlock ?: return
        val mine = shown[event.player.uniqueId] ?: return
        val at = positionOf(clicked)
        val contents = mine.contents[at] ?: return
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        if (event.hand == EquipmentSlot.HAND) open(event.player, clicked, at, contents, mine)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPeekClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder is Peek) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPeekDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Peek) event.isCancelled = true
    }

    // A half of a double chest opens with its other half, as the game opens it: the right half on top. The other
    // half holds what the preview says it would, or nothing where the preview puts it back empty, or what it
    // holds now.
    private fun open(player: Player, clicked: Block, at: WorldBlock, contents: Contents, mine: Shown) {
        val state = (contents.data as CraftBlockData).state
        var items = contents.items
        if (state.block is ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            val pos = ChestBlock.getConnectedBlockPos(BlockPos(at.x, at.y, at.z), state)
            val partnerAt = at.copy(x = pos.x, y = pos.y, z = pos.z)
            val ghost = mine.byChunk[chunkOf(partnerAt)]?.find { it.at == partnerAt }
            val real = clicked.world.getBlockAt(pos.x, pos.y, pos.z)
            val other = mine.contents[partnerAt]?.items
                ?: if (ghost != null) List(items.size) { ItemStack.empty() }.takeIf { ghost.data.material == contents.data.material }
                else (real.state as? Chest)?.blockInventory?.contents?.map { it ?: ItemStack.empty() }?.takeIf { real.type == contents.data.material }
            if (other != null) items = if (state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) items + other else other + items
        }
        val peek = Peek()
        val title = Component.text(tr("after the rollback · ", "после отката · ")).append(Component.translatable(contents.data.material))
        peek.window = Bukkit.createInventory(peek, ((items.size + 8) / 9 * 9).coerceIn(9, 54), title)
        items.take(54).forEachIndexed { i, item -> peek.window.setItem(i, item) }
        player.openInventory(peek.window)
    }

    // A click makes the client show the real block, there and on the clicked face, so the ghost is sent again.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onClick(event: PlayerInteractEvent) {
        val clicked = event.clickedBlock ?: return
        val mine = shown[event.player.uniqueId] ?: return
        val ghosts = listOf(clicked, clicked.getRelative(event.blockFace)).mapNotNull { block ->
            val at = positionOf(block)
            mine.byChunk[chunkOf(at)]?.find { it.at == at }
        }
        if (ghosts.isEmpty()) return
        event.player.scheduler.runDelayed(plugin, { send(event.player, ghosts) }, null, 1)
    }

    private fun send(player: Player, ghosts: List<Ghost>) {
        // Not where the player stands: their client pushes them out of a ghost, and the apply then finds them
        // beside the wall instead of in it, to lift them onto it.
        val body = player.boundingBox
        val changes = ghosts.filterNot { ghost ->
            val (_, x, y, z) = ghost.at
            body.overlaps(BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0))
        }.associate { Position.block(it.at.x, it.at.y, it.at.z) to it.data }
        if (changes.isNotEmpty()) player.sendMultiBlockChange(changes)
    }

    // The client is told again what really stands there: read on each chunk's own region, sent on the
    // player's, which may be another one by now.
    private fun restore(player: Player, mine: Shown, except: Set<WorldBlock> = emptySet()) {
        for ((key, all) in mine.byChunk) {
            val group = all.map { it.at }.filter { it !in except }
            if (group.isEmpty()) continue
            val world = Bukkit.getWorld(key.first) ?: continue
            val chunkX = group.first().x shr 4
            val chunkZ = group.first().z shr 4
            Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) return@execute
                val real = group.associate { Position.block(it.x, it.y, it.z) to world.getBlockData(it.x, it.y, it.z) }
                player.scheduler.run(plugin, {
                    // A client in another world would take these for blocks of its own.
                    if (player.world == world) player.sendMultiBlockChange(real)
                }, null)
            }
        }
    }
}
