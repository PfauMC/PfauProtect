package io.pfaumc.pfauprotect.importer

import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.capture.block.payloadOf
import io.pfaumc.pfauprotect.capture.shown
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.BlockLog
import io.pfaumc.pfauprotect.storage.ChatKind
import io.pfaumc.pfauprotect.storage.ChatLine
import io.pfaumc.pfauprotect.storage.ChatLog
import io.pfaumc.pfauprotect.storage.EntityChange
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.LOGGER
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.storage.WorldChange
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.level.block.state.BlockState
import java.nio.file.Path
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.LongAdder

/** What became of the rows an import read, by what was done with them or why not. */
class ImportCounts {
    private val counts = ConcurrentSkipListMap<String, LongAdder>()

    fun add(what: String, n: Long = 1) {
        if (n != 0L) counts.computeIfAbsent(what) { LongAdder() }.add(n)
    }

    operator fun get(what: String): Long = counts[what]?.sum() ?: 0

    fun sumOf(vararg what: String): Long = what.sumOf { get(it) }

    override fun toString() = counts.entries.joinToString(", ") { "${it.key} ${it.value.sum()}" }
}

/**
 * Brings a CoreProtect database's history over, as rows of their own causes (`BLK_CP_*`, `CP_*`, the `CP_` chat
 * kinds), up to [until]: everything older than this plugin's own journal, so no row is here twice. Blocks a player or
 * CoreProtect's `#tnt`-like agents changed, kills, sign texts, a player's deposits and withdrawals, chat, commands,
 * joins and quits; what the world does by itself (`#water`, `#vine`, `#decay` and the like), clicks, ender chests,
 * drops and pickups and what hoppers moved are left where they are.
 *
 * Every row is numbered from where it came from, below every number the writers hand out, so a run stopped and run
 * again writes the same keys with the same bytes, and the marks it leaves in the ledger let it go on from where it got
 * to. Runs on a thread of its own and never on a region's; [busy] holds it back while the live journal has work
 * waiting, and too little room on the disk ([freeBytes]) stops it, since a full disk stops the live journal too.
 */
class CoreProtectImport(
    private val file: Path,
    private val copy: Boolean,
    val until: Long,
    private val ledger: RocksItemLog,
    private val worldOf: (String) -> UUID?,
    private val logOf: (UUID) -> BlockLog?,
    private val chat: ChatLog,
    private val registries: HolderLookup.Provider,
    private val codec: ItemFormCodec,
    // What a container row's metadata adds to the bare item: a server's Bukkit reads it, a test has none.
    private val meta: (ByteArray, ItemStack) -> ItemStack = { _, stack -> stack },
    private val busy: () -> Boolean = { false },
    private val freeBytes: () -> Long = { Long.MAX_VALUE },
) {
    val counts = ImportCounts()

    @Volatile
    var table: String = ""
        private set

    @Volatile
    var at: Long = 0
        private set

    /** Null while it runs; then how it ended. */
    @Volatile
    var outcome: String? = null
        private set

    @Volatile
    private var stopping = false

    fun stop() {
        stopping = true
    }

    private lateinit var source: CoreProtectSource
    private lateinit var users: Map<Int, CpUser>
    private lateinit var worlds: Map<Int, String>
    private lateinit var materials: Map<Int, String>
    private lateinit var properties: Map<Int, String>
    private lateinit var entities: Map<Int, String>
    private var origin = 0L

    fun run() {
        outcome = try {
            CoreProtectSource(file, copy).use { source ->
                this.source = source
                users = source.all("co_user", "id, user, uuid") { it.getInt(1) to CpUser(it.getString(2), it.getString(3)?.let(::uuidOrNull)) }
                worlds = source.all("co_world", "id, world") { it.getInt(1) to it.getString(2) }
                materials = source.all("co_material_map", "id, material") { it.getInt(1) to it.getString(2) }
                properties = source.all("co_blockdata_map", "id, data") { it.getInt(1) to it.getString(2) }
                entities = source.all("co_entity_map", "id, entity") { it.getInt(1) to it.getString(2) }
                // A world with no base here would see its rows passed over while the marks moved past them for good.
                val missing = worlds.values.filter { name -> worldOf(name)?.let(logOf) == null }
                check(missing.isEmpty()) { "CoreProtect's worlds ${missing.joinToString()} are not loaded here" }
                // The marks belong to this database: another one starts from its own beginning. Its creation second is
                // the one thing of it that neither a backup nor `/co purge` changes; a copy of it that went on apart
                // shares it, and every row up to the copy too, so nothing in the file could tell those apart.
                // ponytail: two databases made in the same second pass as one; an operator's own stamp if that happens.
                origin = checkNotNull(source.one("SELECT time FROM co_version ORDER BY rowid LIMIT 1", emptyList()) { it.getLong(1) }) {
                    "co_version does not read, so this database cannot be told from another"
                }
                // Imported keys are numbered from the source's rowids, which a second database would repeat: one per journal.
                val before = ledger.importMark(ORIGIN_MARK)
                check(before == 0L || before == origin) { "this journal already holds an import from another CoreProtect database" }
                if (before == 0L) ledger.setImportMark(ORIGIN_MARK, origin)
                importBlocks()
                importSigns()
                importContainers()
                importChat("co_chat", ChatKind.CP_CHAT, CHAT_TAG)
                importChat("co_command", ChatKind.CP_COMMAND, COMMAND_TAG)
                importSessions()
                when {
                    stopping -> "stopped"
                    else -> "done"
                }
            }
        } catch (failure: Throwable) {
            LOGGER.log(java.util.logging.Level.SEVERE, "the CoreProtect import failed", failure)
            "failed: ${failure.message}"
        }
        LOGGER.info("CoreProtect import $outcome: $counts")
    }

    // ---- the walk shared by every table ----

    private interface CpRow {
        val rowid: Long
        val time: Long
    }

    /**
     * Pages through [name] from its mark on. [write] gets the rows of a page older than [until] and whether the page is
     * the table's last, and answers with the rowid up to which it took them, which becomes the mark.
     */
    private fun <T : CpRow> walk(name: String, columns: String, read: (ResultSet) -> T, write: (List<T>, Boolean) -> Long?) {
        table = name
        val mark = markOf(origin, name)
        var after = ledger.importMark(mark)
        var limit = PAGE
        while (!stopping) {
            if (freeBytes() < MIN_FREE_BYTES) {
                stopping = true
                LOGGER.severe("the CoreProtect import stopped: less than ${MIN_FREE_BYTES shr 30} GiB left on the disk")
                break
            }
            while (busy() && !stopping) Thread.sleep(BUSY_WAIT_MILLIS)
            if (stopping) break
            at = after
            val page = source.page(name, columns, after, limit, read)
            if (page.rows.isEmpty()) {
                if (!page.broken) break
                val next = source.skipFrom(name, after)
                if (next == null) {
                    counts.add("$name stopped on damage")
                    LOGGER.warning("CoreProtect import: $name does not read past row $after; the rest of it is not imported")
                    break
                }
                // A row that reads by itself but not in a page is stepped over alone, or the walk would stand still.
                val past = maxOf(next, after + 1)
                counts.add("$name unreadable", past - after)
                LOGGER.warning("CoreProtect import: $name rows ${after + 1}..$past do not read and were skipped")
                after = past
                ledger.setImportMark(mark, after)
                continue
            }
            // The table ends at its first row past `until`, and the mark stops short of it: a later import with a later
            // `until` starts there, grouping the rows as this one would have. A row CoreProtect filed a moment late,
            // older than `until` but behind that first one, is left to such a later import.
            val end = page.rows.indexOfFirst { it.time * 1000 >= until }
            val ended = end >= 0
            val kept = if (ended) page.rows.subList(0, end) else page.rows
            val last = ended || page.broken || page.rows.size < limit
            val took = write(kept, last)
            if (took == MORE) {
                limit *= 2
                continue
            }
            limit = PAGE
            // What the write held back is read again.
            after = when {
                took != null && took != kept.lastOrNull()?.rowid -> took
                ended -> page.rows[end].rowid - 1
                else -> page.rows.last().rowid
            }
            ledger.setImportMark(mark, after)
            if (ended) break
        }
        at = after
    }

    /**
     * The world of a CoreProtect world id, null for an id `co_world` does not know. A world loaded at the start and
     * gone since fails the import: its rows would be passed over while the mark moved past them for good.
     */
    private fun worldAt(wid: Int): UUID? {
        val name = worlds[wid] ?: return null
        return checkNotNull(worldOf(name)) { "world $name was unloaded during the import" }
    }

    private fun logAt(world: UUID): BlockLog = checkNotNull(logOf(world)) { "world $world was unloaded during the import" }

    private fun userOf(id: Int): CpUser? = users[id]

    private fun player(id: Int): UUID? = userOf(id)?.takeIf { !it.agent }?.uuid

    // ---- blocks, kills ----

    private class CpBlock(
        override val rowid: Long,
        override val time: Long,
        val user: Int,
        val wid: Int,
        val x: Int,
        val y: Int,
        val z: Int,
        val type: Int,
        val blockdata: String?,
        val action: Int,
        val rolledBack: Int,
    ) : CpRow

    private fun importBlocks() = walk(
        "co_block", "time, user, wid, x, y, z, type, blockdata, action, rolled_back",
        { CpBlock(it.getLong(1), it.getLong(2), it.getInt(3), it.getInt(4), it.getInt(5), it.getInt(6), it.getInt(7), it.getInt(8), it.getBytes(9)?.toString(Charsets.UTF_8), it.getInt(10), it.getInt(11)) },
    ) { rows, last ->
        // One user's rows of one second in one world are one event: an explosion, a replace.
        val groups = ArrayList<List<CpBlock>>()
        for (row in rows) {
            val open = groups.lastOrNull()?.first()
            if (open != null && open.user == row.user && open.time == row.time && open.wid == row.wid) {
                (groups[groups.size - 1] as MutableList) += row
            } else {
                groups += mutableListOf(row)
            }
        }
        // The page may have cut the last event; read again from its start, and a page that holds nothing else again larger.
        if (!last && groups.size == 1) return@walk MORE
        val done = if (!last) groups.dropLast(1) else groups
        val byWorld = HashMap<UUID, MutableList<Pair<Long, List<WorldChange>>>>()
        for (group in done) {
            val first = group.first()
            val world = worldAt(first.wid)
            if (world == null) {
                counts.add("co_block unknown world", group.size.toLong())
                continue
            }
            val changes = changesOf(group)
            if (changes.isNotEmpty()) byWorld.getOrPut(world) { ArrayList() } += idOf(BLOCK_TAG, first.time, first.rowid) to changes
        }
        for ((world, events) in byWorld) {
            val dropped = logAt(world).importEvents(events)
            counts.add("co_block not written", dropped.size.toLong())
            val written = events.flatMap { it.second }
            counts.add("blocks", (written.count { it is BlockChange } - dropped.count { it is BlockChange }).toLong())
            counts.add("kills", (written.count { it is EntityChange } - dropped.count { it is EntityChange }).toLong())
        }
        done.lastOrNull()?.last()?.rowid
    }

    private fun changesOf(group: List<CpBlock>): List<WorldChange> {
        val out = ArrayList<WorldChange>()
        var lastBreak: CpBlock? = null
        for (row in group) {
            val user = userOf(row.user)
            if (user == null || !user.agent && user.uuid == null) {
                counts.add("co_block unknown user")
                continue
            }
            if (row.rolledBack != 0) counts.add("co_block rolled back in CoreProtect")
            val actor = if (user.agent) null else user.uuid
            when (row.action) {
                KILL -> {
                    val type = entities[row.type]
                    if (type == null) {
                        counts.add("co_block unknown entity")
                        continue
                    }
                    out += EntityChange(
                        row.x, row.y, row.z, EntityKind.REMOVED, Cause.CP_ENTITY_KILLED, "minecraft:$type",
                        UUID.nameUUIDFromBytes("coreprotect kill ${row.rowid}".toByteArray()),
                        timestamp = row.time * 1000, actor = actor,
                    )
                }
                BREAK, PLACE -> {
                    if (user.name in NATURE) {
                        counts.add("co_block nature")
                        continue
                    }
                    val state = stateOf(row.type, row.blockdata)
                    if (state == null) {
                        counts.add("co_block unknown block")
                        continue
                    }
                    val previous = out.lastOrNull() as? BlockChange
                    val replaced = lastBreak?.takeIf { row.action == PLACE && it.x == row.x && it.y == row.y && it.z == row.z }
                    if (replaced != null && previous != null && previous.after != null) {
                        // CoreProtect files a replace as a break and a place of the same second; this journal, as one row.
                        out[out.size - 1] = previous.copy(after = state, cause = causeOf(user, PLACE))
                        lastBreak = null
                        continue
                    }
                    out += if (row.action == BREAK) {
                        BlockChange(
                            row.x, row.y, row.z, state, leftBehind(state), causeOf(user, BREAK),
                            timestamp = row.time * 1000, actor = actor,
                            payloadBefore = if (isSign(state)) brokenSignText(row, state) else null,
                        )
                    } else {
                        BlockChange(row.x, row.y, row.z, AIR, state, causeOf(user, PLACE), timestamp = row.time * 1000, actor = actor)
                    }
                    lastBreak = row.takeIf { it.action == BREAK }
                    continue
                }
                else -> counts.add("co_block clicks")
            }
            lastBreak = null
        }
        return out
    }

    private fun causeOf(user: CpUser, action: Int): Cause = when {
        !user.agent -> if (action == PLACE) Cause.BLK_CP_PLACE else Cause.BLK_CP_BREAK
        user.name == "#piston" -> Cause.BLK_CP_PISTON
        user.name in EXPLOSIONS -> Cause.BLK_CP_EXPLOSION
        user.name in FIRES -> Cause.BLK_CP_FIRE
        else -> Cause.BLK_CP_OTHER
    }

    // Keyed by the material and its property ids as CoreProtect wrote them; a few tens of thousands at most.
    private val states = HashMap<Pair<Int, String?>, String?>()

    private fun stateOf(type: Int, blockdata: String?): String? {
        val key = type to blockdata
        if (key in states) return states[key]
        val material = materials[type]
        val properties = blockdata?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.let(properties::get) }?.filter { '=' in it }.orEmpty()
        val state = material?.let { blockStateOf(it, properties) { counts.add("block states approximated") } }
        states[key] = state
        return state
    }

    private val leftBehind = HashMap<String, String>()

    // What a break leaves: water for a waterlogged block, air for a dry one, as `BlockCapture.leftBehind` has it.
    private fun leftBehind(state: String): String = leftBehind.getOrPut(state) {
        parsed(state)?.fluidState?.createLegacyBlock()?.let(BlockStateParser::serialize) ?: AIR
    }

    // ---- signs ----

    private class CpSign(
        override val rowid: Long,
        override val time: Long,
        val user: Int,
        val wid: Int,
        val x: Int,
        val y: Int,
        val z: Int,
        val action: Int,
        val color: Int,
        val colorBack: Int,
        val glow: Int,
        val waxed: Int,
        val lines: List<String?>,
    ) : CpRow

    private fun readSign(it: ResultSet) = CpSign(
        it.getLong(1), it.getLong(2), it.getInt(3), it.getInt(4), it.getInt(5), it.getInt(6), it.getInt(7),
        it.getInt(8), it.getInt(9), it.getInt(10), it.getInt(11), it.getInt(12), (13..20).map(it::getString),
    )

    private fun importSigns() = walk("co_sign", SIGN_COLUMNS, ::readSign) { rows, _ ->
        val byWorld = HashMap<UUID, MutableList<Pair<Long, List<WorldChange>>>>()
        for (row in rows) {
            // A break is the block row's; its text goes on that row as what was there before.
            if (row.action == 0) continue
            val user = userOf(row.user)
            val world = worldAt(row.wid)
            if (world == null) {
                counts.add("co_sign unknown world")
                continue
            }
            val state = standingSign(row)
            val after = state?.let { signPayload(it, row) }
            if (after == null) {
                counts.add("co_sign no sign block")
                continue
            }
            val before = signTextAt(row.wid, row.x, row.y, row.z, row.time, row.rowid, state)
            val change = BlockChange(
                row.x, row.y, row.z, state, state, Cause.BLK_CP_SIGN, timestamp = row.time * 1000,
                actor = user?.takeIf { !it.agent }?.uuid, payloadBefore = before, payloadAfter = after,
            )
            byWorld.getOrPut(world) { ArrayList() } += idOf(SIGN_TAG, row.time, row.rowid) to listOf(change)
        }
        for ((world, events) in byWorld) {
            val dropped = logAt(world).importEvents(events).size.toLong()
            counts.add("co_sign not written", dropped)
            counts.add("signs", events.size - dropped)
        }
        null
    }

    /**
     * The sign a text went on, as the newest block row of its position up to then names it: a placement. A break in
     * the same second leaves no way to tell whether the text came first, and a text written after it would stand the
     * sign up again, or land on a sign put there after the break; so within the text's own second a break wins over a
     * placement, and the break keeps the text as what was there before it. In an earlier second the newest row wins.
     */
    private fun standingSign(row: CpSign): String? = source.one(
        "SELECT type, blockdata, action FROM co_block WHERE wid = ? AND x = ? AND z = ? AND y = ? AND time <= ? AND action IN (0, 1) " +
            "ORDER BY time DESC, CASE WHEN time = ? THEN action ELSE 0 END, rowid DESC LIMIT 1",
        listOf(row.wid, row.x, row.z, row.y, row.time, row.time),
    ) { if (it.getInt(3) == PLACE) stateOf(it.getInt(1), it.getBytes(2)?.toString(Charsets.UTF_8)) else null }?.takeIf(::isSign)

    /**
     * The text a sign at a position had at a moment, from the newest sign row before it; null when it had none. A
     * sign's break row is CoreProtect's snapshot of its text as it broke: for an edit it means the sign before was gone.
     */
    private fun signTextAt(wid: Int, x: Int, y: Int, z: Int, time: Long, beforeRowid: Long, state: String): ByteArray? = source.one(
        "SELECT rowid, $SIGN_COLUMNS FROM co_sign WHERE wid = ? AND x = ? AND z = ? AND y = ? AND time <= ? AND rowid < ? ORDER BY time DESC, rowid DESC LIMIT 1",
        listOf(wid, x, z, y, time, beforeRowid),
        ::readSign,
    )?.takeIf { it.action != 0 }?.let { signPayload(state, it) }

    // What a sign said as it broke: CoreProtect's snapshot of it, filed up to a few seconds before the break and
    // never after it. A text of any other row may be a sign put there since.
    private fun brokenSignText(row: CpBlock, state: String): ByteArray? = source.one(
        "SELECT rowid, $SIGN_COLUMNS FROM co_sign WHERE wid = ? AND x = ? AND z = ? AND y = ? AND time BETWEEN ? AND ? AND action = 0 ORDER BY time DESC, rowid DESC LIMIT 1",
        listOf(row.wid, row.x, row.z, row.y, row.time - SIGN_BREAK_SECONDS, row.time),
        ::readSign,
    )?.let { signPayload(state, it) }

    private fun signPayload(state: String, row: CpSign): ByteArray? {
        val blockState = parsed(state) ?: return null
        val pos = BlockPos(row.x, row.y, row.z)
        val sign = (blockState.block as? EntityBlock)?.newBlockEntity(pos, blockState) as? SignBlockEntity ?: return null
        sign.setText(signText(row.lines.subList(0, 4), row.color, row.glow and 1 != 0), SignTextSlot.FRONT)
        sign.setText(signText(row.lines.subList(4, 8), row.colorBack, row.glow and 2 != 0), SignTextSlot.BACK)
        sign.setWaxed(row.waxed != 0)
        return payloadOf(sign, registries)
    }

    private fun signText(lines: List<String?>, color: Int, glowing: Boolean): SignText {
        val messages = List<Component>(4) { Component.literal(lines[it].orEmpty()) }
        // CoreProtect keeps the dye's own colour; black is a sign's default.
        val dye = DyeColor.entries.firstOrNull { it.textureDiffuseColor and 0xFFFFFF == color and 0xFFFFFF } ?: DyeColor.BLACK
        return SignText(messages, messages, dye, glowing)
    }

    private fun isSign(state: String) = state.substringBefore('[').endsWith("sign")

    // ---- containers ----

    private class CpItem(
        override val rowid: Long,
        override val time: Long,
        val user: Int,
        val wid: Int,
        val x: Int,
        val y: Int,
        val z: Int,
        val type: Int,
        val amount: Int,
        val metadata: ByteArray?,
        val action: Int,
        val rolledBack: Int,
    ) : CpRow

    private fun importContainers() = walk(
        "co_container", "time, user, wid, x, y, z, type, amount, metadata, action, rolled_back",
        { CpItem(it.getLong(1), it.getLong(2), it.getInt(3), it.getInt(4), it.getInt(5), it.getInt(6), it.getInt(7), it.getInt(8), it.getInt(9), it.getBytes(10), it.getInt(11), it.getInt(12)) },
    ) { rows, _ ->
        val transfers = ArrayList<Pair<Long, Transfer>>()
        for (row in rows) {
            // A hopper's or a dropper's: CoreProtect never knew whose machine it was.
            val player = player(row.user)
            if (player == null) {
                counts.add("co_container not a player")
                continue
            }
            val world = worldAt(row.wid)
            val item = materials[row.type]?.let(Identifier::tryParse)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }
            if (world == null || item == null || row.amount <= 0) {
                counts.add("co_container unknown world or item")
                continue
            }
            if (row.rolledBack != 0) counts.add("co_container rolled back in CoreProtect")
            val bare = ItemStack(item, row.amount)
            if (bare.isEmpty) {
                counts.add("co_container unknown world or item")
                continue
            }
            val stack = row.metadata?.let { runCatching { meta(it, bare) }.getOrNull() ?: bare.also { counts.add("item meta lost") } } ?: bare
            if (stack !== bare && !ItemStack.isSameItemSameComponents(stack, bare)) counts.add("items with meta")
            val encoded = codec.encode(stack)
            val box = Container(world, row.x, row.y, row.z, 0)
            val (cause, from, to) = if (row.action == 1) Triple(Cause.CP_CONTAINER_ADD, Void, box) else Triple(Cause.CP_CONTAINER_REMOVE, box, Void)
            transfers += idOf(CONTAINER_TAG, row.time, row.rowid) to
                Transfer(cause, from, to, encoded.form, encoded.damage, row.amount, row.time * 1000, actor = player)
        }
        val dropped = ledger.importTransfers(transfers).toLong()
        counts.add("co_container not written", dropped)
        counts.add("container rows", transfers.size - dropped)
        null
    }

    // ---- chat, commands, sessions ----

    private class CpLine(
        override val rowid: Long,
        override val time: Long,
        val user: Int,
        val wid: Int,
        val x: Int,
        val y: Int,
        val z: Int,
        val text: String,
    ) : CpRow

    private fun importChat(name: String, kind: ChatKind, tag: Int) = walk(
        name, "time, user, wid, x, y, z, message",
        { CpLine(it.getLong(1), it.getLong(2), it.getInt(3), it.getInt(4), it.getInt(5), it.getInt(6), it.getInt(7), it.getString(8).orEmpty()) },
    ) { rows, _ ->
        writeLines(name, rows.map { it to kind }, tag)
        null
    }

    private fun importSessions() = walk(
        "co_session", "time, user, wid, x, y, z, action",
        { CpLine(it.getLong(1), it.getLong(2), it.getInt(3), it.getInt(4), it.getInt(5), it.getInt(6), it.getInt(7), it.getInt(8).toString()) },
    ) { rows, _ ->
        writeLines("co_session", rows.map { it to if (it.text == "1") ChatKind.CP_JOIN else ChatKind.CP_QUIT }, SESSION_TAG)
        null
    }

    private fun writeLines(name: String, rows: List<Pair<CpLine, ChatKind>>, tag: Int) {
        val lines = rows.mapNotNull { (row, kind) ->
            val player = player(row.user) ?: return@mapNotNull null.also { counts.add("$name not a player") }
            val text = when (kind) {
                ChatKind.CP_JOIN, ChatKind.CP_QUIT -> ""
                // A login carries a password, as it does when the capture takes it.
                ChatKind.CP_COMMAND -> shown(row.text, Settings.hiddenCommands)
                else -> row.text
            }
            ChatLine(row.time * 1000, kind, player, text, worlds[row.wid].orEmpty(), row.x, row.y, row.z) to sequenceOf(tag, row.rowid)
        }
        chat.importLines(lines)
        counts.add("chat lines", lines.size.toLong())
    }

    private fun parsed(state: String): BlockState? =
        runCatching { BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState() }.getOrNull()

    private class CpUser(val name: String, val uuid: UUID?) {
        // `#tnt`, `#piston`, `#water`: what CoreProtect files under a name that is nobody's.
        val agent = name.startsWith("#")
    }

    companion object {
        private const val PAGE = 10_000

        private const val BUSY_WAIT_MILLIS = 500L
        private const val MIN_FREE_BYTES = 20L shl 30

        private const val BREAK = 0
        private const val PLACE = 1
        private const val KILL = 3

        private const val BLOCK_TAG = 0
        private const val SIGN_TAG = 1
        private const val CONTAINER_TAG = 2
        private const val CHAT_TAG = 0
        private const val COMMAND_TAG = 1
        private const val SESSION_TAG = 2

        private const val AIR = "minecraft:air"

        // How far before the block's break CoreProtect may file the snapshot of a sign's text (`SignTextLogger`).
        private const val SIGN_BREAK_SECONDS = 5L

        private const val SIGN_COLUMNS =
            "time, user, wid, x, y, z, action, color, color_secondary, data, waxed, line_1, line_2, line_3, line_4, line_5, line_6, line_7, line_8"

        // What the world does by itself: neither a rollback nor an investigation asks for it, and it is half the table.
        private val NATURE = setOf(
            "#water", "#lava", "#flow", "#vine", "#bamboo", "#decay", "#amethyst", "#dryout", "#bonemeal", "#tree",
            "#mushroom", "#chorus", "#sculk_catalyst", "#turtle",
        )
        private val EXPLOSIONS = setOf(
            "#tnt", "#creeper", "#bed", "#explosion", "#end_crystal", "#respawn_anchor", "#wither", "#wither_effect",
            "#enderdragon", "#ghast", "#primedtnt", "#windcharge", "#breezewindcharge",
        )
        private val FIRES = setOf("#fire", "#lightning")

        /**
         * An imported event's or transaction's number: below zero, so below every number a writer hands out, and
         * growing with the time and then the rowid, so imported rows keep their order among themselves wherever the
         * number orders them. 31 bits of seconds (to 2038), the table's tag, 30 bits of rowid.
         */
        internal fun idOf(tag: Int, timeSeconds: Long, rowid: Long): Long {
            require(timeSeconds in 0 until (1L shl 31) && rowid in 0 until (1L shl 30)) { "row $rowid at $timeSeconds has no number" }
            return Long.MIN_VALUE + (timeSeconds shl 32) + (tag.toLong() shl 30) + rowid
        }

        // A chat line's sequence within its millisecond: positive, as the chat log keeps them, and one per source row.
        internal fun sequenceOf(tag: Int, rowid: Long): Int = (tag shl 28) or (rowid and 0x0FFFFFFF).toInt()

        // CoreProtect stamps its database with the time it made it, which tells one database from another.
        internal fun markOf(origin: Long, table: String) = "coreprotect:$origin:$table"

        internal const val ORIGIN_MARK = "coreprotect"

        // What a write answers when its page held only part of one event: read it again, larger.
        private const val MORE = Long.MIN_VALUE

        /**
         * The state CoreProtect wrote as a material and its properties, written the way this journal writes one
         * (`BlockData.asString`). A property this game no longer has costs the properties, not the block:
         * [approximated] hears of it. Null for a block this game does not know.
         */
        internal fun blockStateOf(material: String, properties: List<String>, approximated: () -> Unit = {}): String? {
            fun parse(text: String) = runCatching { BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState() }.getOrNull()
            val exact = if (properties.isEmpty()) parse(material) else parse("$material[${properties.joinToString(",")}]")
            val state = exact ?: parse(material)?.also { approximated() } ?: return null
            return BlockStateParser.serialize(state)
        }

        private fun uuidOrNull(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
    }
}
