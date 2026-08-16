package io.pfaumc.pfauprotect

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.argument.CustomArgumentType
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

const val DEFAULT_LIMIT = 10
const val MAX_LIMIT = 200
const val MAX_RADIUS = 200

private const val GLOBAL_RADIUS = -1
private const val VANILLA_NAMESPACE = "minecraft"

// Every filter but time and position is applied after the read, so the scan has to bring back more
// rows than the caller asked for or filtering would eat into the requested count.
private const val FETCH_FACTOR = 8
private const val MAX_FETCH = 4096

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

// Accepted spellings and completions come from one table on purpose: when they were two lists, a
// spelling the parser accepted still had to be repeated by hand to be suggested, and they drifted.
// The first key is the one that gets suggested; the rest are silent aliases, so completion stays
// readable while the short forms typed from muscle memory keep working.
internal enum class Param(vararg val keys: String) {
    USER("user", "u", "users"),
    TIME("time", "t"),
    RADIUS("radius", "r"),
    ACTION("action", "a"),
    INCLUDE("include", "i", "item", "items", "b", "block", "blocks"),
    EXCLUDE("exclude", "e"),
    LIMIT("limit", "l", "rows"),
    ;

    val canonical: String get() = keys.first()

    companion object {
        private val byKey = entries.flatMap { param -> param.keys.map { it to param } }.toMap()
        val prefixes = entries.map { "${it.canonical}:" }
        val help = prefixes.joinToString(" ")
        fun of(key: String): Param? = byKey[key]
    }
}

// Same one-table rule as Param: the first key is what completion offers, the rest are silent aliases.
internal enum class Action(val causes: Set<Cause>, vararg val keys: String) {
    CONTAINER(
        setOf(Cause.CONTAINER_ADD, Cause.CONTAINER_REMOVE),
        "container", "chest", "transaction", "transactions",
    ),
    ADD(setOf(Cause.CONTAINER_ADD), "+container", "deposit", "deposits", "deposited"),
    REMOVE(setOf(Cause.CONTAINER_REMOVE), "-container", "withdraw", "withdraws", "withdrew"),
    LOOT(setOf(Cause.LOOT_GENERATE), "loot", "loot_generate"),
    ;

    companion object {
        private val byKey = entries.flatMap { action -> action.keys.map { it to action } }.toMap()
        val names = entries.map { it.keys.first() }
        fun of(key: String): Action? = byKey[key]
    }
}

private val GLOBAL_WORDS = setOf("global", "none", "off", "false", "-1")
private val TIME_EXAMPLES = listOf("10m", "1h", "6h", "1d", "3d", "1w")
private val RADIUS_EXAMPLES = listOf("0", "5", "10", "20", "50", "global")
private val LIMIT_EXAMPLES = listOf("10", "25", "50", "100")

// Rebuilt per keystroke otherwise: completion is asked for candidates on every character typed.
// Lazy because reading the material registry needs a running server, and parsing does not.
private val ITEM_NAMES: List<String> by lazy { Material.entries.filter { it.isItem }.map { it.key.key } }

private val DURATION = Regex("(\\d+)(mo|[ymwdhs])")

data class LookupQuery(
    val users: List<String> = emptyList(),
    val included: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val causes: Set<Cause>? = null,
    val secondsBack: Long? = null,
    val radius: Int? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    val global: Boolean get() = radius == GLOBAL_RADIUS
}

data class LookupTarget(val world: UUID, val x: Int, val y: Int, val z: Int, val label: String)

fun lookupTargetAt(block: Block) = LookupTarget(
    block.world.uid,
    block.x,
    block.y,
    block.z,
    "${block.type.name.lowercase()} at ${block.x} ${block.y} ${block.z}",
)

private val NOT_A_PARAMETER = DynamicCommandExceptionType {
    LiteralMessage("'$it' is not a parameter, expected one of ${Param.help}")
}
private val UNKNOWN_PARAMETER = DynamicCommandExceptionType {
    LiteralMessage("unknown parameter '$it:', expected one of ${Param.help}")
}
private val EMPTY_VALUE = DynamicCommandExceptionType { LiteralMessage("'$it:' has no value") }
private val BAD_TIME = DynamicCommandExceptionType {
    LiteralMessage("'$it' is not a time span, expected something like 30m, 2h or 1d6h")
}
private val BAD_RADIUS = DynamicCommandExceptionType {
    LiteralMessage("'$it' is not a radius, expected 0 to $MAX_RADIUS blocks or 'global'")
}
private val BAD_ACTION = DynamicCommandExceptionType {
    LiteralMessage("'$it' is not an action, expected one of ${Action.names.joinToString(" ")}")
}
private val BAD_LIMIT = DynamicCommandExceptionType {
    LiteralMessage("'$it' is not a row count between 1 and $MAX_LIMIT")
}

fun parseLookupQuery(input: String): LookupQuery = parseLookupQuery(StringReader(input))

// Parsing walks the caller's reader instead of a copied string so that every exception carries the
// real cursor: the client underlines the offending token rather than the whole argument.
fun parseLookupQuery(reader: StringReader): LookupQuery {
    var query = LookupQuery()
    while (true) {
        while (reader.canRead() && reader.peek() == ' ') reader.skip()
        if (!reader.canRead()) return query
        val tokenStart = reader.cursor
        while (reader.canRead() && reader.peek() != ' ') reader.skip()
        val token = reader.string.substring(tokenStart, reader.cursor)
        val colon = token.indexOf(':')
        if (colon <= 0) {
            reader.cursor = tokenStart
            throw NOT_A_PARAMETER.createWithContext(reader, token)
        }
        val key = token.substring(0, colon).lowercase()
        val value = token.substring(colon + 1)
        val param = Param.of(key)
        if (param == null) {
            reader.cursor = tokenStart
            throw UNKNOWN_PARAMETER.createWithContext(reader, key)
        }
        val valueStart = tokenStart + colon + 1
        if (value.isEmpty()) {
            reader.cursor = valueStart
            throw EMPTY_VALUE.createWithContext(reader, key)
        }
        reader.cursor = valueStart
        query = when (param) {
            Param.USER -> query.copy(users = query.users + values(value))
            Param.TIME -> query.copy(secondsBack = durationOrNull(value) ?: fail(reader, BAD_TIME, value))
            Param.RADIUS -> query.copy(radius = radiusOrNull(value) ?: fail(reader, BAD_RADIUS, value))
            Param.ACTION -> query.copy(causes = (query.causes ?: emptySet()) + causes(reader, valueStart, value))
            Param.INCLUDE -> query.copy(included = query.included + values(value))
            Param.EXCLUDE -> query.copy(excluded = query.excluded + values(value))
            Param.LIMIT -> query.copy(limit = limitOrNull(value) ?: fail(reader, BAD_LIMIT, value))
        }
        reader.cursor = tokenStart + token.length
    }
}

private fun fail(reader: StringReader, type: DynamicCommandExceptionType, value: String): Nothing =
    throw type.createWithContext(reader, value)

private fun values(value: String): List<String> = value.split(',').filter { it.isNotBlank() }

private fun causes(reader: StringReader, valueStart: Int, value: String): Set<Cause> {
    val found = mutableSetOf<Cause>()
    var offset = valueStart
    for (name in value.split(',')) {
        if (name.isNotBlank()) {
            val mapped = Action.of(name.lowercase())
            if (mapped == null) {
                reader.cursor = offset
                throw BAD_ACTION.createWithContext(reader, name)
            }
            found += mapped.causes
        }
        offset += name.length + 1
    }
    return found
}

fun durationOrNull(value: String): Long? {
    val text = value.lowercase()
    var total = 0L
    var covered = 0
    for (match in DURATION.findAll(text)) {
        covered += match.value.length
        total += match.groupValues[1].toLong() * when (match.groupValues[2]) {
            "y" -> 31_536_000L
            "mo" -> 2_592_000L
            "w" -> 604_800L
            "d" -> 86_400L
            "h" -> 3_600L
            "m" -> 60L
            else -> 1L
        }
    }
    return if (covered == text.length && total > 0) total else null
}

private fun radiusOrNull(value: String): Int? {
    val text = value.lowercase().removePrefix("#")
    if (text in GLOBAL_WORDS) return GLOBAL_RADIUS
    return text.toIntOrNull()?.takeIf { it in 0..MAX_RADIUS }
}

private fun limitOrNull(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }

class LookupArgument : CustomArgumentType<LookupQuery, String> {
    override fun getNativeType(): ArgumentType<String> = StringArgumentType.greedyString()

    override fun parse(reader: StringReader): LookupQuery = parseLookupQuery(reader)

    override fun <S : Any> listSuggestions(
        context: CommandContext<S>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val whole = builder.remaining
        val tokenStart = whole.lastIndexOf(' ') + 1
        val token = whole.substring(tokenStart)
        val colon = token.indexOf(':')
        if (colon < 0) return offer(builder, tokenStart, Param.prefixes)
        val comma = token.lastIndexOf(',')
        val valueStart = if (comma > colon) comma + 1 else colon + 1
        return offer(builder, tokenStart + valueStart, valuesFor(Param.of(token.substring(0, colon).lowercase())))
    }

    private fun offer(
        builder: SuggestionsBuilder,
        offset: Int,
        candidates: List<String>,
    ): CompletableFuture<Suggestions> {
        val out = builder.createOffset(builder.start + offset)
        val typed = out.remainingLowerCase
        for (candidate in candidates) if (candidate.startsWith(typed)) out.suggest(candidate)
        return out.buildFuture()
    }

    private fun valuesFor(param: Param?): List<String> = when (param) {
        Param.USER, Param.EXCLUDE -> Bukkit.getOnlinePlayers().map { it.name }
        Param.TIME -> TIME_EXAMPLES
        Param.RADIUS -> RADIUS_EXAMPLES
        Param.ACTION -> Action.names
        Param.INCLUDE -> ITEM_NAMES
        Param.LIMIT -> LIMIT_EXAMPLES
        null -> emptyList()
    }
}

class Lookups(private val plugin: Plugin, private val ledger: RocksItemLog) {

    // Reading hits RocksDB through JNI, which has no business running on a region thread, and a task
    // that dies out there would otherwise leave the player staring at a command that answered nothing.
    fun run(sender: CommandSender, target: LookupTarget, query: LookupQuery) {
        Bukkit.getAsyncScheduler().runNow(plugin) {
            try {
                report(sender, target, query)
            } catch (failure: Throwable) {
                plugin.logger.log(Level.SEVERE, "lookup at ${target.label} failed", failure)
                sender.sendMessage("The lookup failed; the server log has the details.")
            }
        }
    }

    private fun report(sender: CommandSender, target: LookupTarget, query: LookupQuery) {
        if (query.global) {
            sender.sendMessage("A world-wide lookup needs the analytical backend; give a radius instead.")
            return
        }
        val named = query.users.associateWith { resolve(it) }
        val unknown = named.filterValues { it == null }.keys
        if (unknown.isNotEmpty()) {
            sender.sendMessage("Unknown player: ${unknown.joinToString(", ")}")
            return
        }
        val entries = filter(read(target, query), query, named.values.filterNotNull().toSet())
        val where = if (query.radius == null) target.label else "${query.radius} blocks around ${target.label}"
        if (entries.isEmpty()) {
            sender.sendMessage("No ledger entries for $where.")
            return
        }
        sender.sendMessage("Last ${entries.size} ledger entries for $where:")
        for (entry in entries) sender.sendMessage("  " + describe(entry))
        // A truncated view that says nothing about being truncated reads as the whole history, and an
        // investigator would conclude the item came from nowhere.
        if (entries.size == query.limit) {
            sender.sendMessage("  ... older entries are cut off; ask for more with limit:${query.limit * 4}")
        }
    }

    private fun read(target: LookupTarget, query: LookupQuery): List<LedgerEntry> {
        val fromTs = query.secondsBack?.let { System.currentTimeMillis() - it * 1000 } ?: 0
        val fetch = minOf(query.limit * FETCH_FACTOR, MAX_FETCH)
        val radius = query.radius ?: return ledger.holderEntries(
            holder = Container(target.world, target.x, target.y, target.z, 0),
            fromTs = fromTs,
            toTs = Long.MAX_VALUE,
            reverse = true,
            limit = fetch,
        )
        return ledger.regionEntries(
            world = target.world,
            minX = target.x - radius,
            minZ = target.z - radius,
            maxX = target.x + radius,
            maxZ = target.z + radius,
            fromTs = fromTs,
            toTs = Long.MAX_VALUE,
            reverse = true,
            limit = fetch,
        ).filter { entry ->
            val holder = entry.holder
            holder !is Container || holder.y in (target.y - radius)..(target.y + radius)
        }
    }

    private fun filter(entries: List<LedgerEntry>, query: LookupQuery, users: Set<UUID>): List<LedgerEntry> {
        val included = query.included.map(::normalizeItem).toSet()
        val excludedItems = query.excluded.map(::normalizeItem).toSet()
        val excludedUsers = query.excluded.mapNotNull(::resolve).toSet()
        return entries.asSequence()
            .filter { query.causes == null || it.cause in query.causes }
            .filter { users.isEmpty() || playerOf(it.holder) in users || playerOf(it.counterparty) in users }
            .filter { entry ->
                val item = itemKey(entry.itemFormId)
                (included.isEmpty() || item in included) && item !in excludedItems
            }
            .filter { playerOf(it.holder) !in excludedUsers && playerOf(it.counterparty) !in excludedUsers }
            .take(query.limit)
            .toList()
    }

    private fun resolve(name: String): UUID? =
        Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(name, ignoreCase = true) }?.uniqueId
            ?: Bukkit.getOfflinePlayerIfCached(name)?.uniqueId

    private fun normalizeItem(name: String): String =
        if (name.contains(':')) name.lowercase() else "$VANILLA_NAMESPACE:${name.lowercase()}"

    private fun playerOf(holder: Holder): UUID? = (holder as? PlayerHolder)?.uuid

    private fun describe(entry: LedgerEntry): String {
        val amount = if (entry.qty > 0) "+${entry.qty}" else entry.qty.toString()
        val direction = if (entry.qty > 0) "from" else "to"
        val by = entry.actor?.let { "  by ${playerName(it)}" } ?: ""
        return "${TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestamp))}  " +
            "${entry.cause.name.lowercase()}  " +
            "$amount ${itemKey(entry.itemFormId) ?: "item form ${entry.itemFormId}"}  " +
            "slot ${entry.holder.slot}  " +
            "$direction ${describe(entry.counterparty)}$by"
    }

    private fun itemKey(itemFormId: Long): String? {
        val form = ledger.form(itemFormId) ?: return null
        return ledger.registries.keyOf(RegistryNamespace.ITEM_TYPE, itemTypeIdOf(form))
    }

    private fun describe(holder: Holder): String = when (holder) {
        is PlayerInv -> "${playerName(holder.uuid)} slot ${holder.slot}"
        is PlayerEquip -> "${playerName(holder.uuid)} equipment slot ${holder.slot}"
        is PlayerCursor -> "${playerName(holder.uuid)} cursor"
        is PlayerEnder -> "${playerName(holder.uuid)} ender chest slot ${holder.slot}"
        is MenuSlot -> "menu ${holder.menuType} slot ${holder.slot}"
        is Container -> "container ${holder.x} ${holder.y} ${holder.z} slot ${holder.slot}"
        is EntitySlot -> "entity ${holder.uuid} slot ${holder.slot}"
        is ItemEntityRef -> "dropped item ${holder.uuid}"
        is Nested -> "inside ${holder.ownerId} at ${holder.index}"
        Void -> "nowhere"
    }

    private fun playerName(uuid: UUID): String = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
}
