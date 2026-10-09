package io.pfaumc.pfauprotect.rollback

import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.math.Position
import io.pfaumc.pfauprotect.say
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// How many blocks of a preview its player is shown at most.
private const val GHOST_LIMIT = 50_000

/** A block as a preview would put it, shown to the player who previews until it is applied or dropped. */
class Ghost(val world: UUID, val x: Int, val y: Int, val z: Int, val data: BlockData) {
    val chunk get() = world to Chunk.getChunkKey(x shr 4, z shr 4)
}

/** The ghosts a player is shown: the nearest first, those of other worlds after them, [limit] at most. */
internal fun nearest(ghosts: List<Ghost>, world: UUID, x: Double, y: Double, z: Double, limit: Int): List<Ghost> {
    fun distance(it: Ghost) = (it.x + 0.5 - x).let { d -> d * d } + (it.y + 0.5 - y).let { d -> d * d } + (it.z + 0.5 - z).let { d -> d * d }
    return ghosts.sortedWith(compareBy({ it.world != world }, ::distance)).take(limit)
}

/**
 * The previewing player sees the blocks as the rollback would put them, for them alone, until they apply,
 * cancel, preview again or the preview runs out. Nothing in the world changes. The client drops a ghost
 * whenever the server tells it about that block again: a chunk it did not have yet or sent anew after the
 * player went away and came back, a click on the block. Each time it is shown again.
 */
class Ghosts(private val plugin: Plugin, private val expiryMillis: Long) : Listener {
    // A class rather than the bare map for its identity: an expiry removes only the preview it was set
    // for, and a second preview of the same blocks is an equal map.
    private class Shown(val byChunk: Map<Pair<UUID, Long>, List<Ghost>>)

    private val shown = ConcurrentHashMap<UUID, Shown>()

    fun show(player: Player, ghosts: List<Ghost>) {
        val at = player.location
        val kept = nearest(ghosts, at.world.uid, at.x, at.y, at.z, GHOST_LIMIT)
        val mine = Shown(kept.groupBy { it.chunk })
        // The old preview's blocks are told back as they are, except those the new one shows: a restore that
        // lands after the new send would otherwise put real blocks over its ghosts.
        val old = if (kept.isEmpty()) shown.remove(player.uniqueId) else shown.put(player.uniqueId, mine)
        old?.let { restore(player, it, except = kept.mapTo(HashSet()) { g -> Triple(g.x, g.y, g.z) to g.world }) }
        if (kept.isEmpty()) return
        send(player, kept.filter { it.world == at.world.uid })
        player.say(
            if (kept.size < ghosts.size) "  you see the nearest blocks (${kept.size} of ${ghosts.size}) as they would stand; nothing changes before /pp apply."
            else "  you see the blocks as they would stand; nothing changes before /pp apply."
        )
        Bukkit.getAsyncScheduler().runDelayed(plugin, {
            if (shown.remove(player.uniqueId, mine)) restore(player, mine)
        }, expiryMillis, TimeUnit.MILLISECONDS)
    }

    fun hide(player: Player) {
        shown.remove(player.uniqueId)?.let { restore(player, it) }
    }

    @EventHandler
    fun onChunkSent(event: PlayerChunkLoadEvent) {
        val ghosts = shown[event.player.uniqueId]?.byChunk?.get(event.world.uid to event.chunk.chunkKey) ?: return
        send(event.player, ghosts)
    }

    // A click is answered with the block as the server has it, there and on the clicked face.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onClick(event: PlayerInteractEvent) {
        val clicked = event.clickedBlock ?: return
        val mine = shown[event.player.uniqueId] ?: return
        val ghosts = listOf(clicked, clicked.getRelative(event.blockFace)).mapNotNull { block ->
            mine.byChunk[block.world.uid to Chunk.getChunkKey(block.x shr 4, block.z shr 4)]
                ?.find { it.x == block.x && it.y == block.y && it.z == block.z }
        }
        if (ghosts.isEmpty()) return
        event.player.scheduler.runDelayed(plugin, { send(event.player, ghosts) }, null, 1)
    }

    private fun send(player: Player, ghosts: List<Ghost>) {
        // Not where the player stands: their client pushes them out of a ghost, and the apply then finds them
        // beside the wall instead of in it, to lift them onto it.
        val body = player.boundingBox
        val changes = ghosts.filterNot {
            body.overlaps(BoundingBox(it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), it.x + 1.0, it.y + 1.0, it.z + 1.0))
        }.associate { Position.block(it.x, it.y, it.z) to it.data }
        if (changes.isNotEmpty()) player.sendMultiBlockChange(changes)
    }

    // The client is told again what really stands there: read on each chunk's own region, sent on the
    // player's, which may be another one by now.
    private fun restore(player: Player, mine: Shown, except: Set<Pair<Triple<Int, Int, Int>, UUID>> = emptySet()) {
        for ((key, all) in mine.byChunk) {
            val group = all.filter { Triple(it.x, it.y, it.z) to it.world !in except }
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
