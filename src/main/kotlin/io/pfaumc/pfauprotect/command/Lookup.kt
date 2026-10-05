package io.pfaumc.pfauprotect.command
import io.pfaumc.pfauprotect.say
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.argument.CustomArgumentType
import io.pfaumc.pfauprotect.storage.BlockLog
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.EntityRow
import io.pfaumc.pfauprotect.capture.entity.TRANSFORMED
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.storage.BlockRow
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.storage.EntryPage
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.MenuSlot
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerEnder
import io.pfaumc.pfauprotect.model.PlayerEquip
import io.pfaumc.pfauprotect.model.PlayerHolder
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.itemTypeIdOf
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.world.level.block.entity.SignText
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import kotlin.jvm.optionals.getOrNull

const val DEFAULT_LIMIT = 10
const val MAX_LIMIT = 200

// How many postings a transaction may hold before it stops being about the position that was asked
// after. A break of a double block with its drops sits well under this.
private const val NEIGHBOURLY_TRANSACTION = 8
const val MAX_RADIUS = 200

// How many entity rows of one chunk a lookup steps over. Entities die far less often than blocks change.
private const val MAX_ENTITY_WALK = 100_000

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
    PLAYER("player", "p"),
    TIME("time", "t"),
    RADIUS("radius", "r"),
    ACTION("action", "a"),
    INCLUDE("include", "i", "item", "items", "b", "block", "blocks"),
    EXCLUDE("exclude", "e"),
    LIMIT("limit", "l", "rows"),
    PAGE("page", "pg"),
    ROLLEDBACK("rolledback", "undone"),
    AMOUNT("amount", "qty"),
    EVENT("event", "ev"),
    FILTER("filter", "f"),
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

    // One filter over every station rather than one per station: an investigator asks what became of
    // an item, not which bench it happened at, and the cause on each row already says that.
    TRANSFORM(TRANSFORM_CAUSES, "transform", "transforms", "changed"),
    USE(USE_CAUSES, "use", "used", "spent"),
    PROJECTILE(PROJECTILE_CAUSES, "projectile", "projectiles", "shot"),

    // A cause is only worth offering as a filter once something actually writes it: an empty answer
    // from a filter that sounds certain reads as "nothing happened there".
    BLOCK(BLOCK_CAUSES, "block", "blocks"),
    BLOCK_PLACE(setOf(Cause.BLK_PLAYER_PLACE), "+block", "placed", "built"),
    BLOCK_BREAK(setOf(Cause.BLK_PLAYER_BREAK), "-block", "broke", "mined"),
    EXPLOSION(
        setOf(
            Cause.BLK_TNT, Cause.BLK_CREEPER, Cause.BLK_BED_EXPLOSION,
            Cause.BLK_RESPAWN_ANCHOR, Cause.BLK_END_CRYSTAL, Cause.BLK_EXPLOSION,
        ),
        "explosion", "explosions", "tnt", "creeper",
    ),
    FIRE(setOf(Cause.BLK_FIRE_BURN, Cause.BLK_FIRE_SPREAD), "fire", "burn", "burnt"),
    LIQUID(
        setOf(Cause.BLK_LIQUID_DESTROY, Cause.BLK_LIQUID_FORM, Cause.BLK_LIQUID_FLOW, Cause.BLK_BUCKET, Cause.BLK_SPONGE),
        "liquid", "water", "lava", "bucket",
    ),
    PISTON(setOf(Cause.BLK_PISTON_EXTEND, Cause.BLK_PISTON_RETRACT), "piston", "pistons"),
    GRAVITY(setOf(Cause.BLK_FALL_START, Cause.BLK_FALL_LAND), "gravity", "fall", "falling"),
    NATURE(
        setOf(
            Cause.BLK_GROW, Cause.BLK_BONEMEAL, Cause.BLK_LEAF_DECAY,
            Cause.BLK_FADE, Cause.BLK_FORM, Cause.BLK_SCULK,
        ),
        "nature", "growth", "grow", "decay",
    ),
    MOB(
        setOf(
            Cause.BLK_ENDERMAN, Cause.BLK_WITHER, Cause.BLK_RAVAGER, Cause.BLK_SILVERFISH,
            Cause.BLK_SNOWMAN, Cause.BLK_FROST_WALKER, Cause.BLK_MOB_GRIEF,
        ),
        "mob", "mobs", "griefing",
    ),
    DISPENSER(setOf(Cause.BLK_DISPENSER), "dispenser", "dispensers"),
    PORTAL(setOf(Cause.BLK_PORTAL_CREATE, Cause.BLK_PORTAL_DESTROY), "portal", "portals"),
    SIGN(setOf(Cause.BLK_SIGN_EDIT), "sign", "signs", "edit"),
    SWITCH(setOf(Cause.BLK_PLAYER_SWITCH, Cause.BLK_ENTITY_SWITCH), "switch", "switches", "pressed"),
    INTERACT(setOf(Cause.BLK_PLAYER_USE), "interact", "clicked", "toggled"),
    COMMAND(setOf(Cause.BLK_COMMAND, Cause.BLK_PLUGIN), "command", "commands", "worldedit", "plugin"),

    // The entity plane: what died, what a player brought into the world, and everything done to an entity.
    KILL(
        setOf(Cause.ENTITY_KILLED, Cause.ENTITY_BROKEN, Cause.PLAYER_KILLED, Cause.MOB_TRANSFORM, Cause.BUCKET_CAPTURE_MOB),
        "kill", "kills", "killed", "death", "deaths",
    ),
    SPAWN(
        setOf(Cause.SPAWN_EGG_USE, Cause.MOB_BRED, Cause.BUCKET_RELEASE_MOB, Cause.PLACE_ENTITY_ITEM, Cause.MOB_TRANSFORM),
        "spawn", "spawned", "bred",
    ),
    ENTITY(ENTITY_CAUSES, "entity", "entities"),
    DROP(
        setOf(
            Cause.DROP_FROM_HAND, Cause.DROP_FROM_MENU, Cause.DROP_MENU_CLOSE, Cause.DROP_ON_DISCONNECT,
            Cause.INVENTORY_OVERFLOW_DROP, Cause.DEATH_DROP,
        ),
        "drop", "drops", "dropped", "thrown",
    ),
    PICKUP(setOf(Cause.PICKUP, Cause.PROJ_PICKUP, Cause.ITEM_PICKUP_BY_MOB, Cause.ITEM_PICKUP_BY_MOB_INV), "pickup", "picked", "pickups"),
    // What a player took out of a container somebody else put down.
    STEAL(setOf(Cause.CONTAINER_REMOVE), "steal", "stolen", "theft"),
    HOPPER(
        setOf(
            Cause.HOPPER_PULL_CONTAINER, Cause.HOPPER_PULL_GROUND, Cause.HOPPER_PUSH, Cause.HOPPER_MINECART_PULL,
            Cause.DROPPER_PUSH, Cause.DROPPER_EJECT,
        ),
        "hopper", "hoppers",
    ),

    // What a rollback did, in both planes. Naming it is also the only way to roll a rollback back.
    ROLLBACK(setOf(Cause.ROLLBACK), "rollback", "rollbacks", "undo"),
    ;

    companion object {
        private val byKey = entries.flatMap { action -> action.keys.map { it to action } }.toMap()
        val names = entries.map { it.keys.first() }
        fun of(key: String): Action? = byKey[key]
    }
}

// Every way one item becomes another, which is one question however many benches answer it.
private val TRANSFORM_CAUSES = setOf(
    Cause.CRAFT_CONSUME, Cause.CRAFT_RESULT, Cause.CRAFT_REMAINDER, Cause.SMELT, Cause.BREW,
    Cause.ANVIL_COMBINE, Cause.GRINDSTONE, Cause.SMITHING_TRANSFORM, Cause.SMITHING_TRIM,
    Cause.ENCHANT_APPLY, Cause.STONECUTTER, Cause.LOOM, Cause.CARTOGRAPHY, Cause.BOOK_SIGN, Cause.MAP_FILL,
    Cause.BUCKET_FILL, Cause.BUCKET_EMPTY, Cause.BUCKET_CAPTURE_MOB, Cause.BUCKET_RELEASE_MOB,
    Cause.BOTTLE_FILL, Cause.BOTTLE_EMPTY, Cause.CAULDRON_WASH, Cause.TRANSMUTE_ON_BREAK,
)

// Everything the entity plane writes, and what a player's hand does to a mob in the item plane.
private val ENTITY_CAUSES = setOf(
    Cause.ENTITY_KILLED, Cause.ENTITY_BROKEN, Cause.ENTITY_CHANGED, Cause.ENTITY_LED, Cause.PLAYER_KILLED,
    Cause.MOB_BRED, Cause.SPAWN_EGG_USE, Cause.BUCKET_CAPTURE_MOB, Cause.BUCKET_RELEASE_MOB, Cause.MOB_TRANSFORM,
    Cause.PLACE_ENTITY_ITEM, Cause.FEED_MOB, Cause.TAME_MOB, Cause.EQUIP_MOB, Cause.SHEAR_MOB, Cause.DYE_MOB,
    Cause.NAME_TAG, Cause.LEASH_ATTACH, Cause.LEASH_DROP, Cause.GIVE_ITEM_TO_MOB, Cause.ARMOR_STAND_SWAP,
)

// Everything thrown, shot or launched, and what became of it after.
private val PROJECTILE_CAUSES: Set<Cause> = Cause.entries.filter { it.id in 0x70..0x7F }.toSet() +
    setOf(Cause.CROSSBOW_LOAD, Cause.CROSSBOW_SHOOT)

// An item spent by using it on something, rather than by eating it or building with it.
private val USE_CAUSES = setOf(
    Cause.BONEMEAL_USE, Cause.SPAWN_EGG_USE, Cause.WAX_APPLY, Cause.ITEM_INTO_SINGLE_BLOCK,
    Cause.EYE_INTO_FRAME, Cause.ITEM_USED, Cause.BEACON_PAYMENT,
)

// The block plane's whole range, so a filter can name it without listing thirty-two causes.
private val BLOCK_CAUSES: Set<Cause> =
    Cause.entries.filter { it.id in 0xD0..0xEF }.toSet() + Cause.BLK_SIGN_EDIT + Cause.BLK_PLAYER_SWITCH +
        Cause.BLK_ENTITY_SWITCH + Cause.BLK_PLAYER_USE + Cause.BLK_BUCKET +
        Cause.BLK_SPONGE + Cause.BLK_COMMAND + Cause.BLK_LIQUID_FLOW + Cause.BLK_PLUGIN

private val GLOBAL_WORDS = setOf("global", "none", "off", "false", "-1")
private val TIME_EXAMPLES = listOf("10m", "1h", "6h", "1d", "3d", "1w")
private val RADIUS_EXAMPLES = listOf("0", "5", "10", "20", "50", "global")
private val LIMIT_EXAMPLES = listOf("10", "25", "50", "100")

// Rebuilt per keystroke otherwise: completion is asked for candidates on every character typed.
// Lazy because reading the material registry needs a running server, and parsing does not.
private val ITEM_NAMES: List<String> by lazy { Material.entries.filter { it.isItem }.map { it.key.key } }

// A block plane row names a block, and plenty of blocks are no item at all — fire, a liquid, a piston
// head — so completion has to offer those too or the rows about them cannot be asked for.
private val BLOCK_NAMES: List<String> by lazy {
    Material.entries.filter { it.isBlock && !it.isItem }.map { it.key.key }
}

private val DURATION = Regex("(\\d+)(mo|[ymwdhs])")

// How far a world-wide lookup of a player walks their index: the positions they touched.
private const val GLOBAL_POSITIONS = 4096

data class LookupQuery(
    val users: List<String> = emptyList(),
    // Whose own slots to read, rather than where. `users` narrows the rows of a place down to the
    // ones a player is named on; this is the other question, what went through their hands.
    val players: List<String> = emptyList(),
    val included: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val causes: Set<Cause>? = null,
    val secondsBack: Long? = null,
    // The near end of a span, `time:2h-1h`: how long ago it stops. Null is now.
    val secondsUntil: Long? = null,
    val radius: Int? = null,
    val limit: Int = DEFAULT_LIMIT,
    val page: Int = 1,
    // Only rows a rollback has since undone, or only rows it has not; null for both.
    val rolledBack: Boolean? = null,
    val amount: IntRange? = null,
    val steal: Boolean = false,
    // How many rows of each cause and player, rather than the rows.
    val count: Boolean = false,
    // One event and nothing else: a click on a lookup line fills it in.
    val event: EventRef? = null,
    // Text a chat line or a command has to hold, for /pp chat.
    val filter: String? = null,
) {
    val global: Boolean get() = radius == GLOBAL_RADIUS

    fun fromTs(now: Long = System.currentTimeMillis()): Long = secondsBack?.let { now - it * 1000 } ?: 0

    fun toTs(now: Long = System.currentTimeMillis()): Long = secondsUntil?.let { now - it * 1000 } ?: Long.MAX_VALUE

    // Rows a page needs read: every page before it as well, since a read is newest first.
    val wanted: Int get() = limit * page
}

/** An event of a world's block plane, with where and when it happened, which is all a rollback of it reads. */
data class EventRef(val id: Long, val x: Int, val y: Int, val z: Int, val at: Long) {
    override fun toString() = "event:$id@$x,$y,$z@$at"

    companion object {
        fun parse(value: String): EventRef? {
            val parts = value.split('@')
            if (parts.size != 3) return null
            val xyz = parts[1].split(',').mapNotNull { it.toIntOrNull() }
            if (xyz.size != 3) return null
            return EventRef(parts[0].toLongOrNull() ?: return null, xyz[0], xyz[1], xyz[2], parts[2].toLongOrNull() ?: return null)
        }
    }
}

data class LookupTarget(val world: UUID, val x: Int, val y: Int, val z: Int, val label: String)

fun lookupTargetAt(block: Block) = LookupTarget(
    block.world.uid,
    block.x,
    block.y,
    block.z,
    "${block.type.name.lowercase()} at ${block.x} ${block.y} ${block.z}",
)

// Where the command was run from rather than what it was pointed at. The console, a command block and
// `/execute positioned` all have a position and no line of sight, and neither does a player looking
// at the sky.
fun lookupTargetAt(at: Location) =
    LookupTarget(at.world.uid, at.blockX, at.blockY, at.blockZ, "${at.blockX} ${at.blockY} ${at.blockZ}")

// Brigadier tells the player these itself, past say(): translated as they are made.
private fun said(text: String) = LiteralMessage(io.pfaumc.pfauprotect.Texts.translate(text))

private val NOT_A_PARAMETER = DynamicCommandExceptionType {
    said("'$it' is not a parameter, expected one of ${Param.help}")
}
private val UNKNOWN_PARAMETER = DynamicCommandExceptionType {
    said("unknown parameter '$it:', expected one of ${Param.help}")
}
private val EMPTY_VALUE = DynamicCommandExceptionType { said("'$it:' has no value") }
private val BAD_TIME = DynamicCommandExceptionType {
    said("'$it' is not a time span, expected something like 30m, 2h or 1d6h")
}
private val BAD_RADIUS = DynamicCommandExceptionType {
    said("'$it' is not a radius, expected 0 to ${io.pfaumc.pfauprotect.Settings.maxRadius} blocks or 'global'")
}
private val BAD_ACTION = DynamicCommandExceptionType {
    said("'$it' is not an action, expected one of ${Action.names.joinToString(" ")}")
}
private val BAD_PAGE = DynamicCommandExceptionType { said("'$it' is not a page number") }
private val BAD_YES_NO = DynamicCommandExceptionType { said("'$it' is neither yes nor no") }
private val BAD_AMOUNT = DynamicCommandExceptionType { said("'$it' is not an amount, expected 5, >=5, <10 or 5-10") }
private val BAD_EVENT = DynamicCommandExceptionType { said("'$it' is not an event, click a lookup line to fill one in") }
private val UNKNOWN_FLAG = DynamicCommandExceptionType { said("unknown flag '$it', expected #count or #sum") }
private val BAD_LIMIT = DynamicCommandExceptionType {
    said("'$it' is not a row count between 1 and $MAX_LIMIT")
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
        if (token.startsWith("#")) {
            query = when (token.lowercase()) {
                "#count", "#sum", "#summary" -> query.copy(count = true)
                else -> {
                    reader.cursor = tokenStart
                    throw UNKNOWN_FLAG.createWithContext(reader, token)
                }
            }
            continue
        }
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
            Param.PLAYER -> query.copy(players = query.players + values(value))
            Param.TIME -> spanOrNull(value)?.let { (back, until) -> query.copy(secondsBack = back, secondsUntil = until) } ?: fail(reader, BAD_TIME, value)
            Param.RADIUS -> query.copy(radius = radiusOrNull(value) ?: fail(reader, BAD_RADIUS, value))
            Param.ACTION -> query.copy(
                causes = (query.causes ?: emptySet()) + causes(reader, valueStart, value),
                steal = query.steal || values(value).any { Action.of(it.lowercase()) == Action.STEAL },
            )
            Param.INCLUDE -> query.copy(included = query.included + values(value))
            Param.EXCLUDE -> query.copy(excluded = query.excluded + values(value))
            Param.LIMIT -> query.copy(limit = limitOrNull(value) ?: fail(reader, BAD_LIMIT, value))
            Param.PAGE -> query.copy(page = value.toIntOrNull()?.takeIf { it >= 1 } ?: fail(reader, BAD_PAGE, value))
            Param.ROLLEDBACK -> query.copy(rolledBack = yesNoOrNull(value) ?: fail(reader, BAD_YES_NO, value))
            Param.AMOUNT -> query.copy(amount = amountOrNull(value) ?: fail(reader, BAD_AMOUNT, value))
            Param.EVENT -> query.copy(event = EventRef.parse(value) ?: fail(reader, BAD_EVENT, value))
            Param.FILTER -> query.copy(filter = value)
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

private fun yesNoOrNull(value: String): Boolean? = when (value.lowercase()) {
    "yes", "true", "y", "1" -> true
    "no", "false", "n", "0" -> false
    else -> null
}

/** `5`, `>=5`, `>5`, `<=10`, `<10` or `5-10`, as the range of amounts it allows. */
internal fun amountOrNull(value: String): IntRange? {
    fun n(text: String) = text.toIntOrNull()?.takeIf { it >= 0 }
    return when {
        value.startsWith(">=") -> n(value.drop(2))?.let { it..Int.MAX_VALUE }
        value.startsWith(">") -> n(value.drop(1))?.let { it + 1..Int.MAX_VALUE }
        value.startsWith("<=") -> n(value.drop(2))?.let { 0..it }
        value.startsWith("<") -> n(value.drop(1))?.let { 0 until it }
        '-' in value -> value.split('-').takeIf { it.size == 2 }?.let { (a, b) -> n(a)?.let { x -> n(b)?.let { y -> x..y } } }
        else -> n(value)?.let { it..it }
    }
}

/** A time span: `2h` back to now, or `2h-1h` (either way round) from two hours ago to one. */
internal fun spanOrNull(value: String): Pair<Long, Long?>? {
    val ends = value.split('-')
    if (ends.size == 1) return durationOrNull(value)?.let { it to null }
    if (ends.size != 2) return null
    val a = durationOrNull(ends[0]) ?: return null
    val b = durationOrNull(ends[1]) ?: return null
    return if (a == b) null else maxOf(a, b) to minOf(a, b)
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
    return text.toIntOrNull()?.takeIf { it in 0..io.pfaumc.pfauprotect.Settings.maxRadius }
}

private fun limitOrNull(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }

private fun playerOf(holder: Holder): UUID? = (holder as? PlayerHolder)?.uuid

// A row names a player in three columns, not two: holding one end, standing at the other, or as the
// actor behind a movement between two things that are neither. Breaking a block writes the block
// losing its item to nowhere and the breaker only as the actor, so a filter that reads the two ends
// alone hides exactly the rows an investigation of that player is looking for.
internal fun namesUser(entry: LedgerEntry, users: Set<UUID>): Boolean =
    playerOf(entry.holder) in users || playerOf(entry.counterparty) in users || entry.actor in users

private fun normalizeItem(name: String): String =
    if (name.contains(':')) name.lowercase() else "$VANILLA_NAMESPACE:${name.lowercase()}"

internal fun itemKey(ledger: RocksItemLog, itemFormId: Long): String? {
    val form = ledger.form(itemFormId) ?: return null
    return ledger.registries.keyOf(RegistryNamespace.ITEM_TYPE, itemTypeIdOf(form))
}

// The full state string is what the registry keeps, properties and all, which is what makes a row
// restorable; the properties are noise in a list, so only the block itself is shown.
internal fun stateName(ledger: RocksItemLog, id: Int): String = stateOf(ledger, id).substringBefore('[')

internal fun stateOf(ledger: RocksItemLog, id: Int): String =
    ledger.registries.keyOf(RegistryNamespace.BLOCK_STATE, id) ?: "block state $id"

/**
 * What a query keeps of the rows it read. A lookup and a rollback are asked with the same words and
 * have to mean the same rows by them: the lookup is how a rollback is read before it is run.
 *
 * A rollback holds two more rules. A player named by `user:` answers for what they did or what was
 * worked out to be theirs, never for having stood near it. And a rollback's own rows are left alone
 * unless asked for by name: a second, narrower rollback would otherwise undo the first one.
 */
internal class RowFilter(
    private val ledger: RocksItemLog,
    query: LookupQuery,
    private val users: Set<UUID>,
    private val excludedUsers: Set<UUID>,
    private val rollback: Boolean = false,
) {
    private val causes = query.causes
    private val amount = query.amount
    private val event = query.event?.id
    private val included = query.included.map(::normalizeItem).toSet()
    private val excluded = query.excluded.map(::normalizeItem).toSet()

    fun keeps(entry: LedgerEntry): Boolean {
        if (!caused(entry.cause)) return false
        if (amount != null && kotlin.math.abs(entry.qty) !in amount) return false
        if (users.isNotEmpty() && !namesUser(entry, users)) return false
        val item = itemKey(ledger, entry.itemFormId)
        return (included.isEmpty() || item in included) && item !in excluded && !namesUser(entry, excludedUsers)
    }

    // An entity row answers to its entity type for `include:` and `exclude:`, the way a block row answers
    // to its blocks.
    fun keeps(row: EntityRow): Boolean {
        if (!caused(row.cause) || amount != null || event != null && row.eventId != event) return false
        if (users.isNotEmpty() && (row.actor !in users || rollback && row.confidence == Confidence.NEARBY)) return false
        if (row.actor != null && row.actor in excludedUsers) return false
        return (included.isEmpty() || row.type in included) && row.type !in excluded
    }

    // A row names a block rather than an item, so what the filter is about is either state it ran
    // between; naming an item that no block is made of therefore hides the whole plane, which is what
    // a reader asking for one item wants.
    fun keeps(row: BlockRow): Boolean {
        if (!caused(row.cause) || amount != null || event != null && row.eventId != event) return false
        if (users.isNotEmpty() && (row.actor !in users || rollback && row.confidence == Confidence.NEARBY)) return false
        if (row.actor != null && row.actor in excludedUsers) return false
        val named = setOf(stateName(ledger, row.stateBefore), stateName(ledger, row.stateAfter))
        return (included.isEmpty() || named.any { it in included }) && named.none { it in excluded }
    }

    private fun caused(cause: Cause) = causes?.contains(cause) ?: (!rollback || cause != Cause.ROLLBACK)
}

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
        Param.USER, Param.PLAYER, Param.EXCLUDE -> Bukkit.getOnlinePlayers().map { it.name }
        Param.TIME -> TIME_EXAMPLES
        Param.RADIUS -> RADIUS_EXAMPLES
        Param.ACTION -> Action.names
        Param.INCLUDE -> ITEM_NAMES + BLOCK_NAMES
        Param.LIMIT -> LIMIT_EXAMPLES
        Param.PAGE -> listOf("2", "3")
        Param.ROLLEDBACK -> listOf("no", "yes")
        Param.AMOUNT -> listOf(">=8", ">=64", "<4")
        Param.EVENT, Param.FILTER -> emptyList()
        null -> emptyList()
    }
}

// One line of an answer, from either plane, so the two can be shown in the order things happened
// rather than as two lists a reader has to interleave in their head.
// `key` is what a #count groups by, the cause and who; `undone`, whether a rollback has since undone it.
private class Line(val timestamp: Long, val text: String, val key: String = "", val undone: Boolean = false, val event: EventRef? = null)

// A break says two things at once: the position gave up what it was made of, and an item came out of
// it. Only the position end carries coordinates, so a reader standing there sees the debit and
// nothing of what it turned into. The transaction is what joins the two, and asking for it costs a
// point read per shown row instead of a second copy of every drop in the position index — which
// would also be a lie a second later, once the item has drifted, merged or been picked up.
internal fun wholeTransactions(ledger: RocksItemLog, entries: List<LedgerEntry>): List<LedgerEntry> {
    val shown = LinkedHashSet(entries)
    // An ordinary movement names both of its ends on the single row the position holds, so its other
    // half would print the same movement a second time, mirrored. What is worth pulling in is the
    // posting no row on screen names at all.
    val mirrors = entries.mapTo(HashSet()) { it.counterparty to it.holder }
    for (entry in entries) {
        val whole = ledger.transactionEntries(entry)
        // A break is a handful of postings about one position and belongs on screen together. An
        // explosion is one transaction over a whole crater, and pulling it in would answer a question
        // about this position with thousands of rows about every other one.
        if (whole.size > NEIGHBOURLY_TRANSACTION) continue
        for (posting in whole) {
            if ((posting.holder to posting.counterparty) in mirrors) continue
            shown += posting
        }
    }
    return shown.toList()
}

class Lookups(
    private val plugin: Plugin,
    private val ledger: RocksItemLog,
    private val blocks: BlockLogs,
    private val codec: ItemFormCodec? = null,
    private val idOf: (String) -> UUID? = { name ->
        Bukkit.getPlayerExact(name)?.uniqueId ?: Bukkit.getOfflinePlayerIfCached(name)?.uniqueId
    },
    private val nameOf: (UUID) -> String? = { Bukkit.getOfflinePlayer(it).name },
) {

    // Reading hits RocksDB through JNI, which has no business running on a region thread, and a task
    // that dies out there would otherwise leave the player staring at a command that answered nothing.
    fun run(sender: CommandSender, target: LookupTarget, query: LookupQuery) {
        Bukkit.getAsyncScheduler().runNow(plugin) {
            try {
                report(sender, target, query)
            } catch (failure: Throwable) {
                plugin.logger.log(Level.SEVERE, "lookup at ${target.label} failed", failure)
                sender.say("The lookup failed; the server log has the details.")
            }
        }
    }

    /**
     * One entity's own story, for the inspector's click on it: what went in and out of its slots — a
     * frame's item, a donkey's chest, a stand's armour — and its rows in the chunks around where it stands.
     */
    fun entity(sender: CommandSender, entity: UUID, label: String, at: LookupTarget) {
        Bukkit.getAsyncScheduler().runNow(plugin) {
            try {
                val query = LookupQuery()
                val slots = ledger.holderPage(EntitySlot(entity, 0), 0, Long.MAX_VALUE, reverse = true, limit = MAX_FETCH)
                val items = entryLines(wholeTransactions(ledger, slots.entries))
                val log = blocks.get(at.world)
                val rows = if (log == null) emptyList() else {
                    ((at.x shr 4) - 1..(at.x shr 4) + 1).flatMap { cx ->
                        ((at.z shr 4) - 1..(at.z shr 4) + 1).flatMap { cz ->
                            log.entitiesInChunk(cx, cz, 0, Long.MAX_VALUE, MAX_ENTITY_WALK) { _, _, _ -> true }.rows.filter { it.uuid == entity }
                        }
                    }
                }
                val lines = items + markedEntities(rows).map { (row, back) -> lineOf(row, back) }
                answer(sender, lines, slots.complete, label, query)
            } catch (failure: Throwable) {
                plugin.logger.log(Level.SEVERE, "lookup of $label failed", failure)
                sender.say("The lookup failed; the server log has the details.")
            }
        }
    }

    internal fun report(sender: CommandSender, target: LookupTarget, query: LookupQuery) {
        if (query.players.isNotEmpty()) return reportPlayers(sender, query)
        val users = resolveAll(sender, query.users) ?: return
        if (query.global) {
            if (users.isEmpty()) {
                sender.say("A world-wide lookup needs a player, user:<name>, or a radius.")
                return
            }
            return reportEverywhere(sender, target, query, users)
        }
        val page = read(target, query)
        val items = entryLines(wholeTransactions(ledger, filter(page.entries, query, users)))
        // The two planes are read apart and shown together: a position that was placed, blown up and
        // flowed over has a row in each, and read as two lists the order they happened in is lost.
        val changes = blockLines(target, query, users)
        val where = if (query.radius == null) target.label else "${query.radius} blocks around ${target.label}"
        answer(sender, items + changes, page.complete, where, query)
    }

    /**
     * Everything the named players did in this world, by the index of rows that name them: the positions
     * they touched, and at each the block, entity and container rows, the way a rollback of them finds it.
     */
    private fun reportEverywhere(sender: CommandSender, target: LookupTarget, query: LookupQuery, users: Set<UUID>) {
        val log = blocks.get(target.world) ?: return sender.say("This world's block log is not open.")
        val fromTs = query.fromTs()
        val toTs = query.toTs()
        val touches = users.map { log.touchedBy(it, fromTs, toTs, GLOBAL_POSITIONS) }
        val positions = touches.flatMap { it.positions }.toSet()
        val keeps = rowFilter(query, users)
        val fetch = minOf(query.wanted * FETCH_FACTOR, MAX_FETCH)
        val rows = positions.flatMap { (x, y, z) -> log.at(x, y, z, fromTs, toTs, limit = fetch, reverse = true) }.filter(keeps::keeps)
        val entities = positions.flatMap { (x, y, z) -> log.entitiesAt(x, y, z, fromTs, toTs, MAX_ENTITY_WALK).rows }.filter(keeps::keeps)
        val entries = positions.flatMap { (x, y, z) ->
            listOf(Container(target.world, x, y, z, 0), WorldBlock(target.world, x, y, z)).flatMap {
                ledger.holderPage(it, fromTs, toTs, reverse = true, limit = fetch).entries
            }
        }.sortedByDescending { it.timestamp }
        val lines = entryLines(wholeTransactions(ledger, filter(entries, query, users))) +
            marked(rows).map { (row, back) -> lineOf(row, back) } +
            markedEntities(entities).map { (row, back) -> lineOf(row, back) }
        answer(sender, lines, touches.all { it.complete }, "everything ${query.users.joinToString(", ")} did in this world", query)
    }

    /**
     * What went through a player's own hands: their inventory, equipment, cursor, ender chest and the
     * crafting grid, which the server books to the player as an entity. None of it has a position, so
     * no radius reaches it, and the block plane has nothing to say about it.
     */
    private fun reportPlayers(sender: CommandSender, query: LookupQuery) {
        val players = resolveAll(sender, query.players) ?: return
        val users = resolveAll(sender, query.users) ?: return
        val fromTs = query.fromTs()
        val fetch = minOf(query.wanted * FETCH_FACTOR, MAX_FETCH)
        // The slot never enters the key, so slot 0 stands for every slot of its kind.
        val pages = players.flatMap { player ->
            listOf(
                PlayerInv(player, 0), PlayerEquip(player, 0), PlayerCursor(player),
                PlayerEnder(player, 0), EntitySlot(player, 0),
            )
        }.map { ledger.holderPage(it, fromTs, query.toTs(), reverse = true, limit = fetch) }
        val entries = pages.flatMap { it.entries }.sortedByDescending { it.timestamp }
        val items = entryLines(wholeTransactions(ledger, filter(entries, query, users)))
        answer(sender, items, pages.all { it.complete }, "player ${query.players.joinToString(", ")}", query)
    }

    // Every name has to be known: dropping the one that was misspelt would answer about the rest as
    // if it were the whole question.
    internal fun resolveAll(sender: CommandSender, names: List<String>): Set<UUID>? {
        val named = names.associateWith { idOf(it) }
        val unknown = named.filterValues { it == null }.keys
        if (unknown.isNotEmpty()) {
            sender.say("Unknown player: ${unknown.joinToString(", ")}")
            return null
        }
        return named.values.filterNotNull().toSet()
    }

    // Item rows marked when a rollback has since given them back.
    private fun entryLines(entries: List<LedgerEntry>): List<Line> {
        val given = ledger.compensated(entries.map { it.ref }).keys
        return entries.map { entry ->
            val undone = entry.ref in given
            // A slot posting names its player as a holder rather than as its actor.
            val who = entry.actor ?: (entry.holder as? PlayerHolder)?.uuid ?: (entry.counterparty as? PlayerHolder)?.uuid
            Line(entry.timestamp, describe(entry) + if (undone) "  (rolled back)" else "", keyOf(entry.cause, who), undone)
        }
    }

    private fun lineOf(row: BlockRow, back: String) =
        Line(row.timestamp, describe(row) + back, keyOf(row.cause, row.actor), back.isNotEmpty(), EventRef(row.eventId, row.x, row.y, row.z, row.timestamp))

    private fun lineOf(row: EntityRow, back: String) =
        Line(row.timestamp, describe(row) + back, keyOf(row.cause, row.actor), back.isNotEmpty(), EventRef(row.eventId, row.x, row.y, row.z, row.timestamp))

    private fun keyOf(cause: Cause, actor: UUID?) = "${cause.name.lowercase()}  ${actor?.let { "by ${playerName(it)}" } ?: "by nobody named"}"

    private fun answer(sender: CommandSender, found: List<Line>, complete: Boolean, where: String, query: LookupQuery) {
        val shown = query.rolledBack?.let { wanted -> found.filter { it.undone == wanted } } ?: found
        if (query.count) return summary(sender, shown, complete, where)
        val matched = shown.sortedByDescending { it.timestamp }
        val lines = matched.drop(query.limit * (query.page - 1)).take(query.limit)
        // Nothing matched can mean two very different things, and telling them apart is the whole
        // difference between "nothing happened here" and "I did not get far enough to see". A read
        // that stopped early inside a busy chunk hands back rows from one corner of it, and answering
        // that with silence would clear a position the reader is standing in the crater of.
        if (lines.isEmpty()) {
            sender.say(
                if (complete) "No ledger entries for $where."
                else "Nothing matched for $where, but the read stopped before the whole area was " +
                    "seen. Narrow the radius or ask for more with limit:${query.limit * 4}."
            )
            return
        }
        val page = if (query.page > 1) " (page ${query.page})" else ""
        sender.say("Last ${lines.size} ledger entries for $where$page:")
        for (line in lines) {
            val event = line.event
            // A player can click a row of either plane's event to have its rollback typed out for them.
            // The console cannot click: it is given the event to type.
            if (sender !is Player || event == null) sender.say("  " + line.text + if (event != null) "  $event" else "")
            else sender.sendMessage(
                Component.text(io.pfaumc.pfauprotect.Texts.translate("  " + line.text))
                    .hoverEvent(HoverEvent.showText(Component.text(if (io.pfaumc.pfauprotect.Settings.language == "ru") "Клик — откатить это событие" else "Click to roll back this one event")))
                    .clickEvent(ClickEvent.suggestCommand("/pp rollback $event")),
            )
        }
        // A truncated view that says nothing about being truncated reads as the whole history, and an
        // investigator would conclude the item came from nowhere. The read itself stops early too, and
        // it stops before the filter runs, so a page cut short says so even when few rows matched.
        if (matched.size > query.wanted || !complete) {
            sender.say("  ... older entries are cut off; page:${query.page + 1} shows the next ones")
        }
    }

    // How many rows of each cause and player, the most first: where a griefer was busiest, before the rows.
    private fun summary(sender: CommandSender, found: List<Line>, complete: Boolean, where: String) {
        if (found.isEmpty()) return sender.say(if (complete) "No ledger entries for $where." else "Nothing matched for $where in what was read.")
        sender.say("Count of ${found.size} ledger entries for $where:")
        for ((key, n) in found.groupingBy { it.key }.eachCount().entries.sortedByDescending { it.value }) sender.say("  $n  $key")
        if (!complete) sender.say("  ... the read stopped early; these are counts of what it saw")
    }

    /**
     * The block plane's side of the same question. A world whose base is not open answers with nothing
     * rather than with silence dressed as an answer: the caller is told, because a position whose
     * history simply is not loaded looks exactly like a position nothing ever happened at.
     */
    private fun blockLines(target: LookupTarget, query: LookupQuery, users: Set<UUID>): List<Line> {
        val log = blocks.get(target.world) ?: return emptyList()
        val fromTs = query.fromTs()
        val toTs = query.toTs()
        val fetch = minOf(query.wanted * FETCH_FACTOR, MAX_FETCH)
        val radius = query.radius
        val rows = if (radius == null) {
            log.at(target.x, target.y, target.z, fromTs, toTs, limit = fetch, reverse = true)
        } else {
            val chunkX = ((target.x - radius) shr 4)..((target.x + radius) shr 4)
            val chunkZ = ((target.z - radius) shr 4)..((target.z + radius) shr 4)
            val inBox = boxAround(target, radius)
            chunkX.flatMap { cx -> chunkZ.map { cz -> cx to cz } }
                .flatMap { (cx, cz) ->
                    log.inChunk(cx, cz, fromTs, toTs, limit = fetch, reverse = true) { inBox(it.x, it.y, it.z) }
                }
                // Each chunk came back newest first; together they have to be again.
                .sortedByDescending { it.timestamp }
        }
        val keeps = rowFilter(query, users)
        val kept = rows.asSequence().filter(keeps::keeps).take(query.wanted + 1).toList()
        val blockLines = marked(kept).map { (row, back) -> lineOf(row, back) }
        return blockLines + entityLines(log, target, query, keeps, fromTs, toTs)
    }

    /**
     * Each row with a mark when a rollback since put back what it took: a later rollback row at its position
     * that returned the state it replaced. Read among the rows at hand, so a rollback outside the window is
     * not seen.
     */
    private fun marked(rows: List<BlockRow>): List<Pair<BlockRow, String>> {
        val rollbacks = rows.filter { it.cause == Cause.ROLLBACK }.groupBy { Triple(it.x, it.y, it.z) }
        return rows.map { row ->
            val undone = row.cause != Cause.ROLLBACK && rollbacks[Triple(row.x, row.y, row.z)].orEmpty().any {
                it.timestamp >= row.timestamp && it.stateAfter == row.stateBefore
            }
            row to if (undone) "  (rolled back)" else ""
        }
    }

    // An entity's row is undone by a later rollback row of the same entity.
    private fun markedEntities(rows: List<EntityRow>): List<Pair<EntityRow, String>> {
        val rollbacks = rows.filter { it.cause == Cause.ROLLBACK }.groupBy { it.uuid }
        return rows.map { row ->
            val undone = row.cause != Cause.ROLLBACK && rollbacks[row.uuid].orEmpty().any { it.timestamp >= row.timestamp }
            row to if (undone) "  (rolled back)" else ""
        }
    }

    // What became of the entities at the same place: killed, put down, changed, led away.
    private fun entityLines(log: BlockLog, target: LookupTarget, query: LookupQuery, keeps: RowFilter, fromTs: Long, toTs: Long): List<Line> {
        val radius = query.radius
        val rows = if (radius == null) {
            log.entitiesAt(target.x, target.y, target.z, fromTs, toTs, MAX_ENTITY_WALK).rows
        } else {
            val inBox = boxAround(target, radius)
            (((target.x - radius) shr 4)..((target.x + radius) shr 4)).flatMap { cx ->
                (((target.z - radius) shr 4)..((target.z + radius) shr 4)).flatMap { cz ->
                    log.entitiesInChunk(cx, cz, fromTs, toTs, MAX_ENTITY_WALK, inBox).rows
                }
            }
        }
        val kept = rows.filter(keeps::keeps).sortedByDescending { it.timestamp }.take(query.wanted + 1)
        return markedEntities(kept).map { (row, back) -> lineOf(row, back) }
    }

    private fun read(target: LookupTarget, query: LookupQuery): EntryPage {
        val fromTs = query.fromTs()
        val toTs = query.toTs()
        val fetch = minOf(query.wanted * FETCH_FACTOR, MAX_FETCH)
        val radius = query.radius ?: run {
            val pages = listOf(
                Container(target.world, target.x, target.y, target.z, 0),
                WorldBlock(target.world, target.x, target.y, target.z),
            ).map { holder ->
                ledger.holderPage(holder, fromTs, toTs, reverse = true, limit = fetch)
            }
            return EntryPage(
                pages.flatMap { it.entries }.sortedByDescending { it.timestamp },
                pages.all { it.complete },
            )
        }
        val inBox = boxAround(target, radius)
        return ledger.regionPage(
            world = target.world,
            minX = target.x - radius,
            minZ = target.z - radius,
            maxX = target.x + radius,
            maxZ = target.z + radius,
            fromTs = fromTs,
            toTs = toTs,
            reverse = true,
            limit = fetch,
            // The scan reads whole chunks, so it comes back with rows the radius does not cover. The
            // block plane keeps to the box, and two planes disagreeing about what one radius means
            // inside one answer reads as rows appearing and vanishing for no reason.
            within = { holder ->
                when (holder) {
                    is Container -> inBox(holder.x, holder.y, holder.z)
                    is WorldBlock -> inBox(holder.x, holder.y, holder.z)
                    else -> true
                }
            },
        )
    }

    private fun boxAround(target: LookupTarget, radius: Int) = { x: Int, y: Int, z: Int ->
        x in (target.x - radius)..(target.x + radius) &&
            y in (target.y - radius)..(target.y + radius) &&
            z in (target.z - radius)..(target.z + radius)
    }

    // Keeps one row more than asked for: the caller shows `limit` of them and needs the extra one to
    // know whether saying so would be a lie.
    private fun filter(entries: List<LedgerEntry>, query: LookupQuery, users: Set<UUID>): List<LedgerEntry> {
        val keeps = rowFilter(query, users)
        val placers = HashMap<Container, UUID?>()
        return entries.asSequence()
            .filter(keeps::keeps)
            .filter { !query.steal || stolen(it, placers) }
            .take(query.wanted + 1)
            .toList()
    }

    // Taken out of a container somebody else put down, or one nobody did: a chest of the world's own.
    private fun stolen(entry: LedgerEntry, placers: MutableMap<Container, UUID?>): Boolean {
        if (entry.cause != Cause.CONTAINER_REMOVE || entry.qty >= 0) return false
        val chest = (entry.holder as? Container)?.copy(slot = 0) ?: return false
        val placer = placers.getOrPut(chest) {
            blocks.get(chest.world)?.at(chest.x, chest.y, chest.z, 0, Long.MAX_VALUE, limit = 200, reverse = true)
                ?.firstOrNull { it.cause == Cause.BLK_PLAYER_PLACE }?.actor
        }
        // A withdrawal names whoever took it as where it went rather than as its actor.
        val taker = entry.actor ?: (entry.counterparty as? PlayerHolder)?.uuid ?: return false
        return taker != placer
    }

    // `exclude:` takes items and players in one list, so a name that is a player is a player excluded.
    internal fun rowFilter(query: LookupQuery, users: Set<UUID>, rollback: Boolean = false) =
        RowFilter(ledger, query, users, query.excluded.mapNotNull(idOf).toSet(), rollback)

    private fun describe(entry: LedgerEntry): String {
        val amount = if (entry.qty > 0) "+${entry.qty}" else entry.qty.toString()
        val direction = if (entry.qty > 0) "from" else "to"
        val by = entry.actor?.let { "  by ${playerName(it)}" } ?: ""
        // Both halves of a mutation face the Void, so without this they read as an item destroyed and
        // an unrelated item created at the same instant, which is the very thing they exist to deny.
        val changed = if (entry.kind == Kind.MUTATE) "  (changed in place)" else ""
        return "${TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestamp))}  " +
            "${entry.cause.name.lowercase()}  " +
            "$amount ${itemLabel(entry.itemFormId)}  " +
            "${describe(entry.holder)}  " +
            "$direction ${describe(entry.counterparty)}$changed$by"
    }

    /**
     * A block row reads as what the position went between, and it says plainly when the name on it was
     * worked out rather than witnessed. A row with nobody on it is not a row worth less — an unfound
     * culprit is no reason to leave a disappearance unrecorded — so it simply says so.
     */
    private fun describe(row: BlockRow): String {
        val pressed = if (row.cause == Cause.BLK_ENTITY_SWITCH) row.payloadAfter?.let(::pressedBy) else null
        val by = when {
            row.actor == null -> "  by nobody named"
            row.confidence == Confidence.INFERRED -> "  by ${playerName(row.actor)} (worked out)"
            row.confidence == Confidence.NEARBY ->
                "  ${playerName(row.actor)} was nearby${pressed?.second?.let { " ($it blocks away)" } ?: ""}"
            else -> "  by ${playerName(row.actor)}"
        }
        val payload = if (pressed != null) "  pressed by ${pressed.first}" else payloadLabel(row)
        // Two rows of a door or a bed are otherwise the same row twice, and which half was struck is
        // the whole of what an investigator is asking.
        val half = if (row.alongside) "  (other half)" else ""
        return "${TIME_FORMAT.format(Instant.ofEpochMilli(row.timestamp))}  " +
            "${row.cause.name.lowercase()}  " +
            "${stateOf(row.stateBefore)} -> ${stateOf(row.stateAfter)}  " +
            "block ${row.x} ${row.y} ${row.z}$by$payload$half"
    }

    private fun describe(row: EntityRow): String {
        val what = when (row.kind) {
            EntityKind.REMOVED -> "gone"
            EntityKind.CREATED -> "brought in"
            EntityKind.CHANGED -> "changed"
            EntityKind.MOVED -> "led away"
            EntityKind.DROPPED -> "dropped ${row.drops.size} items"
            EntityKind.PLAYER_DIED -> "died: ${playerName(row.uuid)}"
        }
        val subject = if (row.kind == EntityKind.DROPPED || row.kind == EntityKind.PLAYER_DIED) "" else
            "  ${row.type} ${row.uuid.toString().take(8)}"
        val by = when {
            row.actor == null -> "  by nobody named"
            row.confidence == Confidence.INFERRED -> "  by ${playerName(row.actor)} (worked out)"
            row.confidence == Confidence.NEARBY -> "  ${playerName(row.actor)} was nearby"
            else -> "  by ${playerName(row.actor)}"
        }
        val drops = if (row.kind != EntityKind.DROPPED && row.drops.isNotEmpty()) "  (${row.drops.size} items fell out)" else ""
        // How it died says what nobody named cannot: dried out on land, crammed, fell. And a dog's kill
        // is put on its owner, which reads as the owner's blow without the dog named.
        val death = row.death?.let {
            if (it.startsWith(TRANSFORMED)) "  turned (${it.removePrefix(TRANSFORMED)})" else "  died of ${it.removePrefix("minecraft:")}"
        } ?: ""
        val via = row.via?.let { "  via ${it.removePrefix("minecraft:")}" } ?: ""
        return "${TIME_FORMAT.format(Instant.ofEpochMilli(row.timestamp))}  " +
            "${row.cause.name.lowercase()}  $what$subject  at ${row.x} ${row.y} ${row.z}$by$via$death$drops"
    }

    // A sign is what a payload is most often asked about, and its text is the whole of what was
    // written. Anything else keeps the marker: what a container held is already rows of the item plane.
    private fun payloadLabel(row: BlockRow): String {
        if (row.payloadBefore == null && row.payloadAfter == null) return ""
        val before = row.payloadBefore?.let(::signText)
        val after = row.payloadAfter?.let(::signText)
        return when {
            before == null && after == null -> "  +contents"
            before != null && after != null && before != after -> "  text $before -> $after"
            else -> "  text ${after ?: before}"
        }
    }

    // What pressed a switch, and how far the player named on the row stood from it when that player
    // was only a witness: the payload of an entity switch row is that text and nothing else.
    private fun pressedBy(payloadId: Long): Pair<String, String?>? {
        val text = ledger.payload(payloadId)?.toString(Charsets.UTF_8) ?: return null
        return text.substringBefore(' ') to text.substringAfter(' ', "").ifEmpty { null }
    }

    // A payload is kept byte for byte, so reading it can fail on a tag a later game version wrote; the
    // row then says what it always said rather than failing the whole answer.
    private fun signText(payloadId: Long): String? {
        val bytes = ledger.payload(payloadId) ?: return null
        val tag = runCatching { NbtIo.read(DataInputStream(ByteArrayInputStream(bytes))) }.getOrNull() ?: return null
        val sides = listOf("front_text", "back_text").mapNotNull { side ->
            val text = tag.get(side) ?: return@mapNotNull null
            SignText.CODEC.parse(NbtOps.INSTANCE, text).result().getOrNull()
                ?.getMessages(false)?.map { it.string }?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }?.joinToString(" | ", "\"", "\"")
        }
        return sides.takeIf { it.isNotEmpty() }?.joinToString(" / ")
    }

    private fun stateOf(id: Int): String = stateOf(ledger, id)

    private fun menuName(id: Int): String =
        ledger.registries.keyOf(RegistryNamespace.MENU_TYPE, id)?.lowercase() ?: "type $id"

    /**
     * The item type plus the name written on it, when one is. A named box and a bare one are the same
     * type and read as the same row without it, which is exactly the difference a shulker box full of
     * somebody's things turns on.
     */
    internal fun itemLabel(itemFormId: Long): String {
        val type = itemKey(ledger, itemFormId) ?: return "item form $itemFormId"
        val decoder = codec ?: return type
        val form = ledger.form(itemFormId) ?: return type
        // A form that will not decode still names its type, which is worth more than an error.
        val named = runCatching { decoder.decode(form, 1, null).get(DataComponents.CUSTOM_NAME) }.getOrNull()
        return if (named == null) type else "$type \"${named.string}\""
    }

    private fun describe(holder: Holder): String = when (holder) {
        is PlayerInv -> "${playerName(holder.uuid)} slot ${holder.slot}"
        is PlayerEquip -> "${playerName(holder.uuid)} equipment slot ${holder.slot}"
        is PlayerCursor -> "${playerName(holder.uuid)} cursor"
        is PlayerEnder -> "${playerName(holder.uuid)} ender chest slot ${holder.slot}"
        is MenuSlot -> "menu ${menuName(holder.menuType)} slot ${holder.slot}"
        is Container -> "container ${holder.x} ${holder.y} ${holder.z} slot ${holder.slot}"
        is WorldBlock -> "block ${holder.x} ${holder.y} ${holder.z}"
        // A crafting grid is the player's own entity slot, and a name reads better than their uuid.
        is EntitySlot -> "entity ${playerName(holder.uuid)} slot ${holder.slot}"
        is ItemEntityRef -> "dropped item ${holder.uuid}"
        is Nested -> "inside ${holder.ownerId} at ${holder.index}"
        Void -> "nowhere"
    }

    private fun playerName(uuid: UUID): String = nameOf(uuid) ?: uuid.toString()
}
