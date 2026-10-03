package io.pfaumc.pfauprotect.rollback

import ca.spottedleaf.concurrentutil.util.Priority
import io.pfaumc.pfauprotect.capture.block.Difference
import io.pfaumc.pfauprotect.capture.block.standingAt
import io.pfaumc.pfauprotect.check.emptied
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.command.LookupQuery
import io.pfaumc.pfauprotect.command.LookupTarget
import io.pfaumc.pfauprotect.command.Lookups
import io.pfaumc.pfauprotect.command.RowFilter
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.PostingRef
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.PlacedForms
import io.pfaumc.pfauprotect.storage.RocksItemLog
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.ProblemReporter
import net.minecraft.world.Clearable
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.storage.TagValueInput
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import kotlin.math.abs
import net.minecraft.world.Container as NmsContainer
import net.minecraft.world.item.ItemStack as NmsItemStack

// The flags vanilla `/fill` places with: the client is told, and a container taken away takes what it
// held with it instead of spilling it on the ground. The neighbours are told once everything is in.
private const val PLACE_FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS

// How long a preview waits for `apply`. The world goes on changing under it, so apply reads it all
// again; this only bounds how stale the question can be.
private const val PENDING_MILLIS = 5 * 60_000L

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/** What a rollback did, or in a preview would do, counted over every chunk it touched. */
class Tally {
    var changed = 0
    var unchanged = 0
    var conflicts = 0
    var slots = 0
    var missed = 0
    var failed = 0

    // What a rollback gave back, for taking it back from whoever carried it off: positions whose block
    // came back, slot postings with how much of each moved (put back above zero, taken out below), and
    // the breaks the plan read there.
    val restored = ArrayList<WorldBlock>()
    val returned = ArrayList<Pair<Refill, Int>>()
    val breaks = ArrayList<LedgerEntry>()

    @Synchronized
    fun add(other: Tally) {
        changed += other.changed
        unchanged += other.unchanged
        conflicts += other.conflicts
        slots += other.slots
        missed += other.missed
        failed += other.failed
        restored += other.restored
        returned += other.returned
        breaks += other.breaks
    }
}

/**
 * Puts back (`qty` above zero) or takes out (below zero) one slot posting: in its own slot first, then
 * wherever else in the same block the item fits or is found. Returns how much of it moved; the rest had
 * no room or was not there.
 */
internal fun putBack(container: NmsContainer, slot: Int, template: NmsItemStack, qty: Int, same: (NmsItemStack) -> Boolean): Int {
    val order = listOf(slot).filter { it < container.containerSize } + (0 until container.containerSize).filter { it != slot }
    var left = abs(qty)
    for (i in order) {
        if (left == 0) break
        val here = container.getItem(i)
        if (qty > 0) {
            val room = minOf(template.maxStackSize, container.getMaxStackSize(template))
            if (here.isEmpty) {
                // The slot the row names held this item once; any other has to take it.
                if (i != slot && !container.canPlaceItem(i, template)) continue
                val n = minOf(left, room)
                container.setItem(i, template.copyWithCount(n))
                left -= n
            } else if (NmsItemStack.isSameItemSameComponents(here, template)) {
                val n = minOf(left, room - here.count)
                if (n <= 0) continue
                container.setItem(i, here.copyWithCount(here.count + n))
                left -= n
            }
        } else {
            if (here.isEmpty || !same(here)) continue
            val n = minOf(left, here.count)
            container.setItem(i, if (n == here.count) NmsItemStack.EMPTY else here.copyWithCount(here.count - n))
            left -= n
        }
    }
    container.setChanged()
    return abs(qty) - left
}

/**
 * One chunk of a rollback, on the thread of the region that owns it. Nothing here touches the ledger:
 * the plan was read before, and the rows go to the writers' queues.
 */
class ChunkRollback(
    private val plugin: Plugin,
    private val codec: ItemFormCodec,
    private val logs: BlockLogs,
    private val placed: PlacedForms,
    private val sink: (List<Transfer>) -> Unit,
) {
    fun run(level: ServerLevel, chunk: ChunkPlan, actor: UUID?, apply: Boolean): Tally {
        val tally = Tally()
        val world = level.world.uid
        val positions = chunk.positions
        val spots = positions.map { BlockPos(it.at.x, it.at.y, it.at.z) }
        val before = spots.map { standingAt(level, it, codec) }
        val touched = BooleanArray(positions.size)
        positions.forEachIndexed { i, plan ->
            val was = before[i]
            val standing = was.state.asBlockData().asString
            val settled = settle(standing, plan.steps)
            if (settled.conflict) tally.conflicts++
            val back = settled.back
            val target = back?.before
            val reshaped = target != null && target != standing
            // A container standing where it stood keeps what it holds: its contents are the slot
            // postings' business, and its tag would hand back what they already give back.
            val retagged = !reshaped && back?.payloadBefore?.contentEquals(was.payload) == false &&
                level.getBlockEntity(spots[i]) !is NmsContainer
            if (!reshaped && !retagged) {
                if (plan.steps.isNotEmpty()) tally.unchanged++
                return@forEachIndexed
            }
            tally.changed++
            // A block back where one stands again is what lets the drops of its break be taken back.
            val returnsBlock = reshaped && !emptied(target!!)
            if (!apply) {
                if (returnsBlock) restored(tally, plan)
                return@forEachIndexed
            }
            try {
                put(level, spots[i], if (reshaped) target else null, back?.payloadBefore)
                touched[i] = true
                if (returnsBlock) restored(tally, plan)
            } catch (failure: Exception) {
                tally.failed++
                plugin.logger.log(Level.WARNING, "a rollback could not put back the block at ${spots[i]}", failure)
            }
        }
        // After the blocks, so a chest that came back is there to take its contents.
        val givenBack = ArrayList<PostingRef>()
        positions.forEachIndexed { i, plan ->
            for (refill in plan.refills) {
                tally.slots++
                if (!apply) {
                    tally.returned += refill to refill.qty
                    continue
                }
                val container = level.getBlockEntity(BlockPos(refill.at.x, refill.at.y, refill.at.z)) as? NmsContainer
                // What arrived in a container this rollback took away went with it, and the reading
                // below writes it off: that is this posting given back, not one that found nothing.
                if (container == null && refill.qty < 0 && touched[i]) {
                    givenBack += refill.posting
                    tally.returned += refill to refill.qty
                    continue
                }
                val moved = if (container == null) 0 else putBack(
                    container, refill.at.slot, codec.decode(refill.form, 1, refill.damage), refill.qty,
                ) { codec.encode(it).form.contentEquals(refill.form) }
                if (moved < abs(refill.qty)) tally.missed++
                if (moved > 0) givenBack += refill.posting
                if (moved > 0) tally.returned += refill to if (refill.qty > 0) moved else -moved
            }
        }
        if (!apply) return tally
        val difference = Difference(world, Cause.ROLLBACK, Cause.ROLLBACK, Cause.ROLLBACK, Kind.TRANSFER, actor, System.currentTimeMillis())
        positions.forEachIndexed { i, plan ->
            difference.add(plan.at.x, plan.at.y, plan.at.z, before[i], standingAt(level, spots[i], codec), blockRow = touched[i])
        }
        if (difference.rows.isNotEmpty()) logs.get(world)?.submit(difference.rows)
        if (difference.moved) sink(difference.transfers(givenBack))
        difference.writeOff(plugin, placed, sink)
        // Last, and after the rows: what the neighbours do now is theirs, and the capture files it.
        for (i in positions.indices) if (touched[i]) level.updateNeighboursOnBlockSet(spots[i], before[i].state)
        return tally
    }

    private fun restored(tally: Tally, plan: PositionPlan) {
        tally.restored += plan.at
        tally.breaks += plan.breaks
    }

    private fun put(level: ServerLevel, pos: BlockPos, state: String?, payload: ByteArray?) {
        if (state != null) level.setBlock(pos, (Bukkit.createBlockData(state) as CraftBlockData).state, PLACE_FLAGS)
        if (payload == null) return
        val entity = level.getBlockEntity(pos) ?: return
        val tag = NbtIo.read(DataInputStream(ByteArrayInputStream(payload)))
        entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag))
        // The tag still holds what the block held when the row was written, and the slot postings give
        // that back; loading it as well would hand everything out twice.
        (entity as? Clearable)?.clearContent()
        entity.setChanged()
        level.chunkSource.blockChanged(pos)
    }
}

/**
 * `/pp rollback`, `apply` and `cancel`. A rollback is previewed first and applied on a second word; the
 * preview is the same reading run against the world without touching it, and apply reads it all again.
 */
class Rollbacks(
    private val plugin: Plugin,
    private val ledger: RocksItemLog,
    private val blocks: BlockLogs,
    private val lookups: Lookups,
    private val chunks: ChunkRollback,
    private val confiscations: Confiscations,
) {
    private class Pending(val target: LookupTarget, val query: LookupQuery, val at: Long)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val running = AtomicBoolean()
    private val reader = RollbackReader(ledger, blocks)

    fun preview(sender: CommandSender, target: LookupTarget, query: LookupQuery) {
        refusalOf(query)?.let {
            sender.sendMessage(it)
            return
        }
        pending[keyOf(sender)] = Pending(target, query, System.currentTimeMillis())
        run(sender, target, query, apply = false)
    }

    fun applyPreview(sender: CommandSender) {
        val key = keyOf(sender)
        val asked = pending.remove(key)?.takeIf { System.currentTimeMillis() - it.at <= PENDING_MILLIS }
        if (asked == null) {
            sender.sendMessage("Nothing to apply: preview a rollback with /pp rollback first.")
            return
        }
        // Two rollbacks over one place would each read the other's work as not done yet.
        if (!running.compareAndSet(false, true)) {
            pending[key] = asked
            sender.sendMessage("Another rollback is still running; apply again once it has reported.")
            return
        }
        run(sender, asked.target, asked.query, apply = true)
    }

    fun cancelPreview(sender: CommandSender) {
        sender.sendMessage(if (pending.remove(keyOf(sender)) != null) "Rollback preview dropped." else "No rollback preview to drop.")
    }

    private fun refusalOf(query: LookupQuery): String? = when {
        query.secondsBack == null -> "A rollback needs time: how far back to undo, for example time:1h."
        query.players.isNotEmpty() -> "player: reads what a player carries and has no place to roll back; use user:."
        query.radius == null -> "A rollback needs radius: the blocks around you it covers, or global with user:."
        query.global && query.users.isEmpty() -> "radius:global undoes what named players did; give user: as well."
        else -> null
    }

    private fun keyOf(sender: CommandSender) = (sender as? Player)?.uniqueId?.toString() ?: sender.name

    private fun run(sender: CommandSender, target: LookupTarget, query: LookupQuery, apply: Boolean) {
        val release = { if (apply) running.set(false) }
        Bukkit.getAsyncScheduler().runNow(plugin) {
            try {
                val users = lookups.resolveAll(sender, query.users)
                if (users == null) {
                    release()
                    return@runNow
                }
                val keeps = lookups.rowFilter(query, users, rollback = true)
                // The writers run on threads of their own, and what they still hold is history the
                // rollback would not see.
                ledger.drain()
                for (world in blocks.worlds) blocks.get(world)?.drain()
                val now = System.currentTimeMillis()
                val from = now - query.secondsBack!! * 1000
                val since = TIME_FORMAT.format(Instant.ofEpochMilli(from))
                val readings = if (query.global) {
                    everywhere(users, from, now, keeps)
                } else {
                    listOf(reader.around(target.world, target.x, target.y, target.z, query.radius!!, from, now, keeps::keeps, keeps::keeps))
                }
                val where = if (query.global) "everything ${query.users.joinToString(", ")} did since $since"
                else "${query.radius} blocks around ${target.label} since $since"
                val refused = readings.filterIsInstance<Refused>().firstOrNull()
                val plans = readings.filterIsInstance<Planned>()
                val positions = plans.sumOf { it.positions }
                when {
                    refused != null -> {
                        sender.sendMessage("Rollback refused: ${refused.reason}.")
                        release()
                    }
                    positions > MAX_ROLLBACK_POSITIONS -> {
                        sender.sendMessage("Rollback refused: ${reader.tooMany(positions).reason}.")
                        release()
                    }
                    else -> dispatch(sender, plans, where, apply, release, global = query.global)
                }
            } catch (failure: Throwable) {
                plugin.logger.log(Level.SEVERE, "the rollback at ${target.label} failed", failure)
                sender.sendMessage("The rollback failed; the server log has the details.")
                release()
            }
        }
    }

    // Every loaded world, read at the positions the players' rows stand at.
    private fun everywhere(users: Set<UUID>, from: Long, now: Long, keeps: RowFilter): List<Reading> {
        val touched = reader.touchedBy(users, from, now)
            ?: return listOf(Refused("those players did more in that window than one rollback reads; narrow the time"))
        return touched.map { (world, positions) -> reader.at(world, positions, from, now, keeps::keeps, keeps::keeps) }
    }

    private fun dispatch(
        sender: CommandSender,
        plans: List<Planned>,
        where: String,
        apply: Boolean,
        release: () -> Unit,
        global: Boolean,
    ) {
        val work = plans.mapNotNull { plan -> (Bukkit.getWorld(plan.world) as? CraftWorld)?.handle?.let { it to plan } }
            .flatMap { (level, plan) -> plan.chunks.map { level to it } }
        val read = "${plans.sumOf { it.rows }} block rows, ${plans.sumOf { it.postings }} slot rows read"
        if (work.isEmpty()) {
            sender.sendMessage("Nothing to roll back: $where.")
            release()
            return
        }
        val actor = (sender as? Player)?.uniqueId
        val total = Tally()
        val left = AtomicInteger(work.size)
        for ((level, chunk) in work) {
            // A chunk's neighbours are loaded with it, so a block on its edge can tell them it changed.
            level.`canvas$loadOrRunAtChunksAsync`(
                chunk.chunkX - 1, chunk.chunkX + 1, chunk.chunkZ - 1, chunk.chunkZ + 1, Priority.NORMAL,
            ) {
                try {
                    total.add(chunks.run(level, chunk, actor, apply))
                } catch (failure: Throwable) {
                    plugin.logger.log(Level.SEVERE, "a rollback chunk at ${chunk.chunkX} ${chunk.chunkZ} failed", failure)
                    total.add(Tally().apply { failed = chunk.positions.size })
                } finally {
                    // Following what was given back to whoever holds it reads the ledger, which the
                    // region thread may not.
                    if (left.decrementAndGet() == 0) Bukkit.getAsyncScheduler().runNow(plugin) {
                        try {
                            finish(sender, total, read, where, apply, actor, global)
                        } catch (failure: Throwable) {
                            plugin.logger.log(Level.SEVERE, "taking back what the rollback at $where gave back failed", failure)
                            sender.sendMessage("Taking back what the rollback gave back failed; the server log has the details.")
                        } finally {
                            release()
                        }
                    }
                }
            }
        }
    }

    private fun finish(sender: CommandSender, total: Tally, read: String, where: String, apply: Boolean, actor: UUID?, global: Boolean) {
        val owed = confiscations.owedFor(total)
        report(sender, total, read, where, apply, global)
        if (owed.isEmpty()) return
        val whom = confiscations.describe(owed)
        if (!apply) {
            sender.sendMessage("  would take back from $whom.")
            return
        }
        sender.sendMessage("  taking back from $whom:")
        confiscations.take(owed, actor, sender)
    }

    private fun report(sender: CommandSender, total: Tally, read: String, where: String, apply: Boolean, global: Boolean) {
        val blocks = "${total.changed} blocks ${if (apply) "put back" else "would change"}, " +
            "${total.unchanged} already as they were, ${total.conflicts} stopped by a later change"
        val slots = "${total.slots} slot postings ${if (apply) "given back" else "to give back"}"
        if (!apply) {
            sender.sendMessage("Rollback preview for $where: $blocks; $slots ($read).")
            // A world-wide lookup has no index to read by, so it cannot show the rows of a global one.
            val rows = if (global) "" else "; /pp lookup with the same words shows the rows"
            sender.sendMessage("  /pp apply within 5 minutes runs it, /pp cancel drops it$rows.")
            return
        }
        sender.sendMessage("Rolled back $where: $blocks; $slots.")
        if (total.missed > 0) sender.sendMessage("  ${total.missed} slot postings found no room or nothing left to take out.")
        if (total.failed > 0) sender.sendMessage("  ${total.failed} positions failed; the server log has the details.")
    }
}
