package io.pfaumc.pfauprotect.capture.block

import ca.spottedleaf.concurrentutil.util.Priority
import ca.spottedleaf.moonrise.common.util.TickThread
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.tree.CommandNode
import io.pfaumc.pfauprotect.capture.item.CommandBirths
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.PostingRef
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.storage.PlacedForms
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.DimensionArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ComponentUtils
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.world.Container as NmsContainer
import net.minecraft.world.level.block.state.BlockState as NmsBlockState

// The commands that rewrite blocks. `/data`, `/item` and `/loot` are not registered on Canvas at all.
private val BLOCK_COMMANDS = listOf("setblock", "fill", "clone", "place")

// A physics update from a fill spills over, and the server itself loads this much around the area.
private const val HOP_BUFFER_CHUNKS = 2

// Beyond this an area is not read: a structure placed with the bounds guessed at can reach it, and
// holding millions of states to compare would cost the tick more than the command did.
private const val MAX_BRACKET_BLOCKS = 4_000_000

// How far the bounds of a `/place` are guessed, by what it places: the command does not say before it
// runs. A template is at most a structure block's 48.
private const val PLACE_FEATURE_REACH = 16
private const val PLACE_TEMPLATE_REACH = 48
private const val PLACE_STRUCTURE_REACH = 64

private val COMMAND_FIELD = CommandNode::class.java.getDeclaredField("command").apply { isAccessible = true }

/** A box of blocks in one level, both corners included. */
internal class Area(
    val level: ServerLevel,
    val minX: Int, val minY: Int, val minZ: Int,
    val maxX: Int, val maxY: Int, val maxZ: Int,
) {
    val volume: Long get() = (maxX - minX + 1).toLong() * (maxY - minY + 1) * (maxZ - minZ + 1)

    fun owned() = TickThread.isTickThreadFor(level, minX shr 4, minZ shr 4, maxX shr 4, maxZ shr 4)

    fun onRegion(task: () -> Unit) = level.`canvas$loadOrRunAtChunksAsync`(
        (minX shr 4) - HOP_BUFFER_CHUNKS, (maxX shr 4) + HOP_BUFFER_CHUNKS,
        (minZ shr 4) - HOP_BUFFER_CHUNKS, (maxZ shr 4) + HOP_BUFFER_CHUNKS,
        Priority.NORMAL,
    ) { task() }

    companion object {
        fun of(level: ServerLevel, a: BlockPos, b: BlockPos): Area = Area(
            level,
            minOf(a.x, b.x), maxOf(minOf(a.y, b.y), level.minY), minOf(a.z, b.z),
            maxOf(a.x, b.x), minOf(maxOf(a.y, b.y), level.maxY), maxOf(a.z, b.z),
        )

        fun around(level: ServerLevel, at: BlockPos, reach: Int) =
            of(level, at.offset(-reach, -reach, -reach), at.offset(reach, reach, reach))
    }
}

// What stood at one position: the state, and for a block entity the whole tag and what it held.
internal class Standing(val state: NmsBlockState, val payload: ByteArray?, val contents: List<Pair<ItemKey, Int>?>?)

internal fun standingAt(level: ServerLevel, pos: BlockPos, codec: ItemFormCodec): Standing {
    val entity = level.getBlockEntity(pos)
    val contents = (entity as? NmsContainer)?.let { container ->
        (0 until container.containerSize).map { slot ->
            val stack = container.getItem(slot)
            if (stack.isEmpty) null else codec.encode(stack).let { it.key to it.count }
        }
    }
    return Standing(level.getBlockState(pos), payloadOf(entity, level.registryAccess()), contents)
}

/**
 * What something other than a hand did to a set of positions, from a reading of each before and after:
 * a block row where the state or the tag changed, and per slot the items that went or came. A slot that
 * kept its item and changed its count moves only the difference. A position whose block was replaced is
 * collected in `replaced`, because the item a player built it from is still booked to it and has to be
 * written off, off the region thread.
 */
internal class Difference(
    private val world: UUID,
    private val cause: Cause,
    private val emptied: Cause,
    private val filled: Cause,
    private val made: Kind,
    private val actor: UUID?,
    val timestamp: Long,
) {
    private class Move(val from: Holder, val to: Holder, val key: ItemKey, val qty: Int, val cause: Cause, val kind: Kind)

    val rows = ArrayList<BlockChange>()
    val replaced = ArrayList<WorldBlock>()
    private val moves = ArrayList<Move>()

    val moved: Boolean get() = moves.isNotEmpty()

    fun add(x: Int, y: Int, z: Int, was: Standing, now: Standing, blockRow: Boolean = true) {
        val stateChanged = was.state != now.state
        val payloadChanged = !(was.payload ?: EMPTY).contentEquals(now.payload ?: EMPTY)
        if (blockRow && (stateChanged || payloadChanged)) {
            rows += BlockChange(
                x, y, z,
                was.state.asBlockData().asString, now.state.asBlockData().asString,
                cause, timestamp, actor = actor,
                payloadBefore = was.payload, payloadAfter = now.payload,
            )
        }
        if (was.state.block != now.state.block) replaced += WorldBlock(world, x, y, z)
        val old = was.contents.orEmpty()
        val new = now.contents.orEmpty()
        for (slot in 0 until maxOf(old.size, new.size)) {
            val gone = old.getOrNull(slot)
            val come = new.getOrNull(slot)
            val at = Container(world, x, y, z, slot)
            if (gone != null && come != null && gone.first == come.first) {
                val difference = come.second - gone.second
                if (difference > 0) moves += Move(Void, at, come.first, difference, filled, made)
                if (difference < 0) moves += Move(at, Void, gone.first, -difference, emptied, Kind.TRANSFER)
                continue
            }
            gone?.let { moves += Move(at, Void, it.first, it.second, emptied, Kind.TRANSFER) }
            come?.let { moves += Move(Void, at, it.first, it.second, filled, made) }
        }
    }

    /** The slot movements as one transaction; `reverts` rides on it into the table of compensations. */
    fun transfers(reverts: List<PostingRef> = emptyList()): List<Transfer> = moves.mapIndexed { i, move ->
        Transfer(
            move.cause, move.from, move.to, move.key.form, move.key.damage, move.qty, timestamp, move.kind,
            actor = actor, reverts = if (i == 0) reverts else emptyList(),
        )
    }

    // A block a player put down still holds the item it was made from; whatever replaced it took it away.
    fun writeOff(plugin: Plugin, placed: PlacedForms, sink: (List<Transfer>) -> Unit) {
        if (replaced.isEmpty() || !plugin.isEnabled) return
        val positions = ArrayList(replaced)
        plugin.server.asyncScheduler.runNow(plugin) {
            val forms = placed.formsAt(positions)
            placed.clearFormsAt(positions)
            val gone = forms.map { (at, form) -> Transfer(emptied, at, Void, form, null, 1, timestamp, actor = actor) }
            if (gone.isNotEmpty()) sink(gone)
        }
    }
}

/**
 * The block commands, bracketed. Each one is let run on the region that owns the area it writes, with
 * the area read just before and just after, and whatever changed there is filed on whoever ran it: a
 * block row per position under one event, the contents of a container it overwrote written off, the
 * contents of one it conjured written in, and the item a placed block stood for written off where the
 * block was replaced. The node's own executor is swapped for the bracket, so the console, a player,
 * `execute … run` and a function all go through it.
 */
class CommandBrackets(
    private val plugin: Plugin,
    private val logs: BlockLogs,
    private val codec: ItemFormCodec,
    private val placed: PlacedForms,
    private val sink: (List<Transfer>) -> Unit,
) {
    private enum class Writes(val filled: Cause, val emptied: Cause) {
        WRITE(Cause.CMD_SETBLOCK_FILL_FILL, Cause.CMD_SETBLOCK_FILL_VOID),
        CLONE(Cause.CMD_CLONE_CONTAINER, Cause.CMD_CLONE_MOVE_VOID),
    }

    private inner class Bracketed(val original: Command<CommandSourceStack>, val name: String) : Command<CommandSourceStack> {
        override fun run(context: CommandContext<CommandSourceStack>): Int {
            val areas = runCatching { areasOf(name, context) }.getOrNull()
            if (areas.isNullOrEmpty() || areas.any { it.volume > MAX_BRACKET_BLOCKS }) return original.run(context)
            val source = context.source
            val actor = (source.entity as? ServerPlayer)?.uuid
            val kind = if (name == "clone") Writes.CLONE else Writes.WRITE
            // Run here when nothing has to hop: the count of a fill is what `execute store` and a
            // conditional read, and a syntax failure belongs to the dispatcher. Only a hop has to answer
            // before the command has run.
            if (areas.all { it.owned() }) {
                var result = 0
                bracket(areas, kind, actor, source, rethrow = true) { original.run(context).also { result = it } }
                return result
            }
            areas.first().onRegion { bracket(areas, kind, actor, source) { original.run(context) } }
            return Command.SINGLE_SUCCESS
        }

        private fun bracket(
            areas: List<Area>,
            kind: Writes,
            actor: UUID?,
            source: CommandSourceStack,
            rethrow: Boolean = false,
            run: () -> Int,
        ) {
            val before = ConcurrentHashMap<Int, Array<Standing>>()
            // An area this region does not own is read on its own region, queued now so it runs before
            // the command's own hop there, which is queued behind it.
            areas.forEachIndexed { index, area ->
                if (area.owned()) before[index] = read(area) else area.onRegion { before[index] = read(area) }
            }
            val first = areas.first()
            val births = CommandBirths.Area(
                first.level.world.uid, first.minX, first.minY, first.minZ, first.maxX, first.maxY, first.maxZ,
                kind.filled, actor,
            )
            try {
                CommandBirths.during(births) { run() }
            } catch (failure: CommandSyntaxException) {
                if (rethrow) throw failure
                // Thrown on the region rather than to the dispatcher, so it is reported the way the
                // server reports a failure once it has hopped.
                source.sendFailure(ComponentUtils.fromMessage(failure.rawMessage))
                return
            }
            areas.forEachIndexed { index, area -> area.onRegion { settle(area, before[index], kind, actor, retries = 1) } }
        }
    }

    private fun areasOf(name: String, context: CommandContext<CommandSourceStack>): List<Area>? {
        val source = context.source
        val level = source.level
        return when (name) {
            "setblock" -> BlockPosArgument.getBlockPos(context, "pos").let { listOf(Area.of(level, it, it)) }
            "fill" -> listOf(Area.of(level, BlockPosArgument.getBlockPos(context, "from"), BlockPosArgument.getBlockPos(context, "to")))
            "clone" -> {
                val from = runCatching { DimensionArgument.getDimension(context, "sourceDimension") }.getOrNull() ?: level
                val to = runCatching { DimensionArgument.getDimension(context, "targetDimension") }.getOrNull() ?: from
                val begin = BlockPosArgument.getBlockPos(context, "begin")
                val end = BlockPosArgument.getBlockPos(context, "end")
                val copied = Area.of(from, begin, end)
                val destination = BlockPosArgument.getBlockPos(context, "destination")
                val target = Area.of(
                    to, destination,
                    destination.offset(copied.maxX - copied.minX, copied.maxY - copied.minY, copied.maxZ - copied.minZ),
                )
                listOf(copied, target)
            }
            "place" -> {
                val what = context.nodes.getOrNull(1)?.node?.name ?: return null
                val at = runCatching { BlockPosArgument.getBlockPos(context, if (what == "jigsaw") "position" else "pos") }
                    .getOrNull() ?: BlockPos.containing(source.position)
                val reach = when (what) {
                    "feature" -> PLACE_FEATURE_REACH
                    "template" -> PLACE_TEMPLATE_REACH
                    else -> PLACE_STRUCTURE_REACH
                }
                listOf(Area.around(level, at, reach))
            }
            else -> null
        }
    }

    private fun read(area: Area): Array<Standing> {
        val cursor = BlockPos.MutableBlockPos()
        val out = ArrayList<Standing>(area.volume.toInt())
        for (x in area.minX..area.maxX) for (y in area.minY..area.maxY) for (z in area.minZ..area.maxZ) {
            out += standingAt(area.level, cursor.set(x, y, z), codec)
        }
        return out.toTypedArray()
    }

    private fun settle(area: Area, before: Array<Standing>?, kind: Writes, actor: UUID?, retries: Int) {
        if (before == null) return
        val after = read(area)
        val world = area.level.world.uid
        val made = if (kind == Writes.CLONE) Kind.CLONE else Kind.TRANSFER
        val difference = Difference(world, Cause.BLK_COMMAND, kind.emptied, kind.filled, made, actor, System.currentTimeMillis())
        var index = 0
        for (x in area.minX..area.maxX) for (y in area.minY..area.maxY) for (z in area.minZ..area.maxZ) {
            difference.add(x, y, z, before[index], after[index])
            index++
        }
        // The server may finish the write a tick after the call that asked for it; read again once.
        if (difference.rows.isEmpty()) {
            if (retries > 0 && plugin.isEnabled) {
                Bukkit.getRegionScheduler().runDelayed(plugin, area.level.world, area.minX shr 4, area.minZ shr 4, {
                    settle(area, before, kind, actor, retries - 1)
                }, 1)
            }
            return
        }
        logs.get(world)?.submit(difference.rows)
        if (difference.moved) sink(difference.transfers())
        difference.writeOff(plugin, placed, sink)
    }

    /** Swaps every executor under the block commands for its bracket; a reload builds them anew. */
    fun wrap(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CommandNode<CommandSourceStack>, Boolean>())
        for (name in BLOCK_COMMANDS) {
            for (literal in listOfNotNull(dispatcher.root.getChild(name), dispatcher.root.getChild("minecraft:$name"))) {
                walk(literal, seen, name)
            }
        }
    }

    private fun walk(node: CommandNode<CommandSourceStack>, seen: MutableSet<CommandNode<CommandSourceStack>>, name: String) {
        if (!seen.add(node)) return
        val command = node.command
        if (command != null && command !is Bracketed) COMMAND_FIELD.set(node, Bracketed(command, name))
        for (child in node.children) walk(child, seen, name)
    }
}

private val EMPTY = ByteArray(0)
