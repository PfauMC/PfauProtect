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
import io.pfaumc.pfauprotect.Ui
import io.pfaumc.pfauprotect.tr
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
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

// How many rows of a container's position `action:steal` reads for who put it down.
private const val PLACER_READ = 200

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
    // A position of its own instead of where the command runs from: what the page buttons pin, so the
    // next page is about the same place after the reader has walked off.
    AT("at"),
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
    // Every row on a line of its own, rather than a run of the same thing as one line.
    val all: Boolean = false,
    val at: Triple<Int, Int, Int>? = null,
    // The words it was asked with, which the page buttons ask again.
    val words: String = "",
) {
    val global: Boolean get() = radius == GLOBAL_RADIUS

    fun fromTs(now: Long = System.currentTimeMillis()): Long = secondsBack?.let { now - it * 1000 } ?: 0

    fun toTs(now: Long = System.currentTimeMillis()): Long = secondsUntil?.let { now - it * 1000 } ?: Long.MAX_VALUE

    // Rows a page needs read: every page before it as well, since a read is newest first. Runs folded
    // into one line each eat rows, so a folded page reads further.
    // The factor is fixed: a page of one huge run still comes out short, and says there is more.
    val wanted: Int get() = limit * page * if (all || count) 1 else GROUPED_READ

    /** The same question about the same place, at another page. */
    fun pageCommand(target: LookupTarget, page: Int): String {
        val kept = words.split(' ').filter { it.isNotBlank() && it.substringBefore(':').lowercase() !in PAGED_AWAY }
        return (listOf("/pp", "l") + kept + "at:${target.x},${target.y},${target.z}" + "page:$page").joinToString(" ")
    }
}

private const val GROUPED_READ = 4
private val PAGED_AWAY = setOf("page", "pg", "at")

/** Where a lookup asked with `at:` reads, in the world it was asked from. */
fun LookupQuery.targetOr(fallback: LookupTarget): LookupTarget {
    val (x, y, z) = at ?: return fallback
    return LookupTarget(fallback.world, x, y, z, "$x $y $z")
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

// `block` is what stood there when asked, for the header to name it.
data class LookupTarget(val world: UUID, val x: Int, val y: Int, val z: Int, val label: String, val block: String? = null)

fun lookupTargetAt(block: Block) = LookupTarget(
    block.world.uid,
    block.x,
    block.y,
    block.z,
    "${block.type.name.lowercase()} at ${block.x} ${block.y} ${block.z}",
    block.type.key.toString(),
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
private val UNKNOWN_FLAG = DynamicCommandExceptionType { said("unknown flag '$it', expected #count, #sum or #all") }
private val BAD_POSITION = DynamicCommandExceptionType { said("'$it' is not a position, expected x,y,z") }
private val BAD_LIMIT = DynamicCommandExceptionType {
    said("'$it' is not a row count between 1 and $MAX_LIMIT")
}

fun parseLookupQuery(input: String): LookupQuery = parseLookupQuery(StringReader(input))

// Parsing walks the caller's reader instead of a copied string so that every exception carries the
// real cursor: the client underlines the offending token rather than the whole argument.
fun parseLookupQuery(reader: StringReader): LookupQuery {
    var query = LookupQuery(words = reader.remaining)
    while (true) {
        while (reader.canRead() && reader.peek() == ' ') reader.skip()
        if (!reader.canRead()) return query
        val tokenStart = reader.cursor
        while (reader.canRead() && reader.peek() != ' ') reader.skip()
        val token = reader.string.substring(tokenStart, reader.cursor)
        if (token.startsWith("#")) {
            query = when (token.lowercase()) {
                "#count", "#sum", "#summary" -> query.copy(count = true)
                "#all", "#nogroup" -> query.copy(all = true)
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
            Param.AT -> query.copy(at = positionOrNull(value) ?: fail(reader, BAD_POSITION, value))
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

private fun positionOrNull(value: String): Triple<Int, Int, Int>? =
    value.split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }?.let { (x, y, z) -> Triple(x, y, z) }

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

// A rollback reads by place and time, so the words that only shape a lookup's answer are not offered for it.
class LookupArgument(private val rollback: Boolean = false) : CustomArgumentType<LookupQuery, String> {
    override fun getNativeType(): ArgumentType<String> = StringArgumentType.greedyString()

    override fun parse(reader: StringReader): LookupQuery = parseLookupQuery(reader)

    private val starts: List<String> =
        if (rollback) Param.entries.filter { it !in ANSWER_ONLY }.map { "${it.canonical}:" }
        else Param.prefixes + listOf("#count", "#all")

    override fun <S : Any> listSuggestions(
        context: CommandContext<S>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val whole = builder.remaining
        val tokenStart = whole.lastIndexOf(' ') + 1
        val token = whole.substring(tokenStart)
        val colon = token.indexOf(':')
        if (colon < 0) return offer(builder, tokenStart, starts)
        val comma = token.lastIndexOf(',')
        val param = Param.of(token.substring(0, colon).lowercase())
        // A position is one value with commas in it, not a list.
        if (param == Param.AT) {
            val at = (context.source as? io.papermc.paper.command.brigadier.CommandSourceStack)?.location
            return offer(builder, tokenStart + colon + 1, listOfNotNull(at?.let { "${it.blockX},${it.blockY},${it.blockZ}" }))
        }
        val valueStart = if (comma > colon) comma + 1 else colon + 1
        return offer(builder, tokenStart + valueStart, valuesFor(param))
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
        Param.USER, Param.PLAYER -> Bukkit.getOnlinePlayers().map { it.name }
        // Takes players and items in one list.
        Param.EXCLUDE -> Bukkit.getOnlinePlayers().map { it.name } + ITEM_NAMES + BLOCK_NAMES
        Param.TIME -> TIME_EXAMPLES
        Param.RADIUS -> RADIUS_EXAMPLES
        Param.ACTION -> Action.names
        Param.INCLUDE -> ITEM_NAMES + BLOCK_NAMES
        Param.LIMIT -> LIMIT_EXAMPLES
        Param.PAGE -> listOf("2", "3")
        Param.ROLLEDBACK -> listOf("no", "yes")
        Param.AMOUNT -> listOf(">=8", ">=64", "<4")
        Param.EVENT, Param.FILTER, Param.AT -> emptyList()
        null -> emptyList()
    }
}

private val ANSWER_ONLY = setOf(Param.PLAYER, Param.LIMIT, Param.PAGE, Param.FILTER)

// One line of an answer, from either plane, so the two can be shown in the order things happened
// rather than as two lists a reader has to interleave in their head. `same` is what a run of lines folds
// on: who did what to which thing, which way. `amount` is what a folded line adds up — items, or one per
// block. `undone`, whether a rollback has since undone it. `draw` makes the line for the amount and the
// number of rows it stands for.
private class Line(
    val timestamp: Long,
    val cause: Cause,
    val who: UUID?,
    val same: String,
    val amount: Int,
    val undone: Boolean,
    val event: EventRef?,
    val draw: (amount: Int, rows: Int, clickable: Boolean) -> Component,
)

// A run of the same thing within this long of each other reads as one line: forty blocks of one wall
// broken in a minute are one act, and forty lines of it push everything else off the screen.
private const val FOLD_MILLIS = 60_000L

private class Folded(val first: Line, var rows: Int, var amount: Int, var oldest: Long)

private fun folded(lines: List<Line>, all: Boolean): List<Folded> {
    val out = ArrayList<Folded>()
    for (line in lines) {
        val run = out.lastOrNull()
        if (!all && run != null && run.first.same == line.same && run.first.undone == line.undone && run.oldest - line.timestamp <= FOLD_MILLIS) {
            run.rows++
            run.amount += line.amount
            run.oldest = line.timestamp
        } else {
            out += Folded(line, 1, line.amount, line.timestamp)
        }
    }
    return out
}

// Where a movement went to or came from, without the slot: forty stacks out of one chest are one act.
private fun slotless(holder: Holder): String = when (holder) {
    is PlayerInv -> "inv ${holder.uuid}"
    is PlayerEquip -> "equip ${holder.uuid}"
    is PlayerEnder -> "ender ${holder.uuid}"
    is Container -> "box ${holder.world} ${holder.x} ${holder.y} ${holder.z}"
    is MenuSlot -> "menu ${holder.menuType}"
    is EntitySlot -> "entity ${holder.uuid}"
    is ItemEntityRef -> "ground"
    is Nested -> "nested ${holder.ownerId}"
    else -> holder.toString()
}

private val AIRS = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")

private fun sign(text: String) = Ui.text(text, when (text) {
    "-" -> Ui.LOST
    "+" -> Ui.GAINED
    else -> Ui.CHANGED
})

private fun times(n: Int): Component = if (n > 1) Ui.text(" ×$n", Ui.MUTED) else Component.empty()

// The parts of a line, two spaces apart, which is what keeps a line readable without columns.
private fun row(vararg parts: Component?): Component {
    val out = Component.text()
    var first = true
    for (part in parts) {
        if (part == null || part == Component.empty()) continue
        if (!first) out.append(Component.text("  "))
        out.append(part)
        first = false
    }
    return out.build()
}

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
    fun entity(sender: CommandSender, entity: UUID, type: String, at: LookupTarget) {
        val label = "$type ${entity.toString().take(8)}"
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
                val lines = items + markedEntities(rows).map { (row, back) -> lineOf(at.world, row, back) }
                val where = Component.text().append(Ui.entity(type)).append(Ui.text(" ${entity.toString().take(8)}", Ui.MUTED)).build()
                answer(sender, lines, slots.complete, where, query, at, pages = false)
            } catch (failure: Throwable) {
                plugin.logger.log(Level.SEVERE, "lookup of $label failed", failure)
                sender.say("The lookup failed; the server log has the details.")
            }
        }
    }

    internal fun report(sender: CommandSender, target: LookupTarget, query: LookupQuery) {
        if (query.players.isNotEmpty()) return reportPlayers(sender, target, query)
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
        val xyz = "${target.x} ${target.y} ${target.z}"
        val where = when {
            query.radius != null -> Ui.text(tr("radius ${query.radius} around $xyz", "радиус ${query.radius} вокруг $xyz"))
            target.block != null -> Component.text().append(Ui.block(target.block)).append(Ui.text(" $xyz")).build()
            else -> Ui.text(xyz)
        }
        answer(sender, items + changes, page.complete, where, query, target)
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
            marked(rows).map { (row, back) -> lineOf(target.world, row, back) } +
            markedEntities(entities).map { (row, back) -> lineOf(target.world, row, back) }
        val who = query.users.joinToString(", ")
        answer(sender, lines, touches.all { it.complete }, Ui.text(tr("everything by $who", "всё от $who")), query, target)
    }

    /**
     * What went through a player's own hands: their inventory, equipment, cursor, ender chest and the
     * crafting grid, which the server books to the player as an entity. None of it has a position, so
     * no radius reaches it, and the block plane has nothing to say about it.
     */
    private fun reportPlayers(sender: CommandSender, target: LookupTarget, query: LookupQuery) {
        // Answering anyway would read as narrowed to the area while nothing was narrowed.
        if (query.radius != null) {
            sender.say("player: reads what went through a player's hands, which has no position; drop the radius.")
            return
        }
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
        val who = query.players.joinToString(", ")
        answer(sender, items, pages.all { it.complete }, Ui.text(tr("what $who carried", "вещи $who")), query, target)
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
            // A slot posting names its player as a holder rather than as its actor.
            val who = entry.actor ?: (entry.holder as? PlayerHolder)?.uuid ?: (entry.counterparty as? PlayerHolder)?.uuid
            val same = "i ${entry.cause} $who ${entry.itemFormId} ${entry.qty > 0} ${slotless(entry.holder)} ${slotless(entry.counterparty)}"
            Line(entry.timestamp, entry.cause, who, same, kotlin.math.abs(entry.qty), entry.ref in given, null) { amount, _, clickable ->
                draw(entry, who, amount, clickable)
            }
        }
    }

    private fun lineOf(world: UUID, row: BlockRow, back: Boolean): Line {
        val (sign, thing) = blockChange(row)
        val same = "b ${row.cause} ${row.actor} ${row.confidence} $sign $thing"
        return Line(row.timestamp, row.cause, row.actor, same, 1, back, EventRef(row.eventId, row.x, row.y, row.z, row.timestamp)) { _, rows, clickable ->
            draw(world, row, rows, clickable)
        }
    }

    private fun lineOf(world: UUID, row: EntityRow, back: Boolean): Line {
        val same = "e ${row.cause} ${row.actor} ${row.confidence} ${row.kind} ${row.type}"
        return Line(row.timestamp, row.cause, row.actor, same, 1, back, EventRef(row.eventId, row.x, row.y, row.z, row.timestamp)) { _, rows, clickable ->
            draw(world, row, rows, clickable)
        }
    }

    private fun answer(
        sender: CommandSender,
        found: List<Line>,
        complete: Boolean,
        where: Component,
        query: LookupQuery,
        target: LookupTarget,
        pages: Boolean = true,
    ) {
        val shown = query.rolledBack?.let { wanted -> found.filter { it.undone == wanted } } ?: found
        if (query.count) return summary(sender, shown, complete, where)
        val matched = shown.sortedByDescending { it.timestamp }
        val runs = folded(matched, query.all)
        val lines = runs.drop(query.limit * (query.page - 1)).take(query.limit)
        // Nothing matched can mean two very different things, and telling them apart is the whole
        // difference between "nothing happened here" and "I did not get far enough to see". A read
        // that stopped early inside a busy chunk hands back rows from one corner of it, and answering
        // that with silence would clear a position the reader is standing in the crater of.
        if (lines.isEmpty()) {
            // Only a limit the parser takes is worth suggesting; at the top already, only a smaller area helps.
            val wider = minOf(query.limit * 4, MAX_LIMIT)
            val why = if (complete) tr("nothing recorded", "ничего не записано")
            else if (wider == query.limit) tr(
                "nothing matched, but the read stopped before the whole area was seen; narrow the radius",
                "ничего не найдено, но чтение остановилось раньше, чем увидело всю область; сузь радиус",
            )
            else tr(
                "nothing matched, but the read stopped before the whole area was seen; narrow the radius or ask for more with limit:$wider",
                "ничего не найдено, но чтение остановилось раньше, чем увидело всю область; сузь радиус или запроси больше через limit:$wider",
            )
            sender.sendMessage(header(where, target).append(Ui.text(" — $why", Ui.MUTED)))
            return
        }
        val clickable = sender is Player
        // ⌖ runs /pp tp, which a player without the permission does not have: for them it is only shown.
        val teleports = clickable && sender.hasPermission(io.pfaumc.pfauprotect.TELEPORT_PERMISSION)
        sender.sendMessage(header(where, target))
        for (run in lines) {
            val line = run.first
            var out = line.draw(run.amount, run.rows, teleports)
            val event = line.event?.takeIf { run.rows == 1 }
            // Struck through for the eye, said in words for whoever cannot see the line drawn.
            val back = if (line.undone) arrayOf(tr("rolled back", "откачено")) else emptyArray()
            if (line.undone) out = if (clickable) out.decorate(TextDecoration.STRIKETHROUGH) else out.append(Ui.text("  (${back[0]})", Ui.FAINT))
            // A player clicks a single event to have its rollback typed out for them; the console cannot
            // click and is given the event to type. A folded line is many events and has no one to give.
            out = when {
                run.rows > 1 -> Ui.hover(out, *back, tr("${run.rows} in a row; #all shows each", "${run.rows} подряд; #all покажет каждое"))
                event == null -> if (line.undone && clickable) Ui.hover(out, *back) else out
                clickable -> Ui.hover(out, *back, tr("Click to roll back this one event", "Клик — откатить это событие"))
                    .clickEvent(ClickEvent.suggestCommand("/pp rollback $event"))
                else -> out.append(Ui.text("  $event", Ui.FAINT))
            }
            sender.sendMessage(Component.text(" ").append(out))
        }
        // A truncated view that says nothing about being truncated reads as the whole history, and an
        // investigator would conclude the item came from nowhere. The read itself stops early too, and
        // it stops before the filter runs, so a page cut short says so even when few rows matched.
        val more = runs.size > query.limit * query.page || matched.size > query.wanted || !complete
        // An entity's own story has no command to ask for its next page by; it can only say there is more.
        if (!pages) {
            if (more) sender.sendMessage(Ui.text(tr("  … older rows are cut off", "  … старые строки обрезаны"), Ui.MUTED))
        } else if (query.page > 1 || more) {
            sender.sendMessage(footer(query, target, more, clickable))
        }
    }

    private fun header(where: Component, target: LookupTarget): Component =
        Component.text().append(Ui.text("PfauProtect · ", Ui.FAINT)).append(where)
            .append(Ui.text(" · ${Ui.worldName(target.world)}", Ui.MUTED)).build()

    // « page 2 » with the arrows that go somewhere clickable; the console is told the words to type.
    private fun footer(query: LookupQuery, target: LookupTarget, more: Boolean, clickable: Boolean): Component {
        val page = Ui.text(" ${tr("page", "стр.")} ${query.page} ", Ui.MUTED)
        if (!clickable) {
            return if (more) Ui.text(tr("  … older rows: page:${query.page + 1}", "  … старые строки: page:${query.page + 1}"), Ui.MUTED)
            else Ui.text(tr("  page ${query.page}, the last", "  стр. ${query.page}, последняя"), Ui.MUTED)
        }
        fun arrow(label: String, to: Int, live: Boolean) =
            if (live) Ui.button(label, query.pageCommand(target, to), Ui.WHO, tr("Page $to", "Стр. $to"))
            else Ui.text(label, Ui.FAINT)
        return Component.text().append(Ui.text("  ")).append(arrow("«", query.page - 1, query.page > 1)).append(page)
            .append(arrow("»", query.page + 1, more)).build()
    }

    // How many rows of each cause and player, the most first: where a griefer was busiest, before the rows.
    private fun summary(sender: CommandSender, found: List<Line>, complete: Boolean, where: Component) {
        if (found.isEmpty()) {
            sender.sendMessage(Component.text().append(where).append(Ui.text(" — ${tr("nothing recorded", "ничего не записано")}", Ui.MUTED)).build())
            return
        }
        sender.sendMessage(Component.text().append(Ui.text(tr("Count of ${found.size} rows: ", "Сводка, строк ${found.size}: "), Ui.FAINT)).append(where).build())
        for ((key, n) in found.groupingBy { it.cause to it.who }.eachCount().entries.sortedByDescending { it.value }) {
            sender.sendMessage(row(Ui.text("  $n", Ui.MUTED), Ui.hover(verb(key.first), key.first.name.lowercase()), who(key.second, Confidence.FACT)))
        }
        if (!complete) sender.sendMessage(Ui.text(tr("  … the read stopped early; these are counts of what it saw", "  … чтение остановилось раньше; это сводка прочитанного"), Ui.MUTED))
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
        // Not cut to the page: runs fold only afterwards, and a cut before them shifts every later page.
        val kept = rows.filter(keeps::keeps)
        val blockLines = marked(kept).map { (row, back) -> lineOf(target.world, row, back) }
        return blockLines + entityLines(log, target, query, keeps, fromTs, toTs)
    }

    /**
     * Each row with a mark when a rollback since put back what it took: a later rollback row at its position
     * that returned the state it replaced. Read among the rows at hand, so a rollback outside the window is
     * not seen.
     */
    private fun marked(rows: List<BlockRow>): List<Pair<BlockRow, Boolean>> {
        val rollbacks = rows.filter { it.cause == Cause.ROLLBACK }.groupBy { Triple(it.x, it.y, it.z) }
        return rows.map { row ->
            val undone = row.cause != Cause.ROLLBACK && rollbacks[Triple(row.x, row.y, row.z)].orEmpty().any {
                it.timestamp >= row.timestamp && it.stateAfter == row.stateBefore
            }
            row to undone
        }
    }

    // An entity's row is undone by a later rollback row of the same entity.
    private fun markedEntities(rows: List<EntityRow>): List<Pair<EntityRow, Boolean>> {
        val rollbacks = rows.filter { it.cause == Cause.ROLLBACK }.groupBy { it.uuid }
        return rows.map { row ->
            val undone = row.cause != Cause.ROLLBACK && rollbacks[row.uuid].orEmpty().any { it.timestamp >= row.timestamp }
            row to undone
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
        val kept = rows.filter(keeps::keeps).sortedByDescending { it.timestamp }
        return markedEntities(kept).map { (row, back) -> lineOf(target.world, row, back) }
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
        val placers = HashMap<Container, Placer>()
        return entries.asSequence()
            .filter(keeps::keeps)
            .filter { !query.steal || stolen(it, placers) }
            .toList()
    }

    // Who put a container down; `known` false when the read stopped before it reached the placement.
    private class Placer(val known: Boolean, val player: UUID?)

    // Taken out of a container somebody else put down, or one nobody did: a chest of the world's own. A busy
    // position whose placement lies past the read says nothing either way, and its withdrawals are not
    // counted as theft: the owner's own would be.
    private fun stolen(entry: LedgerEntry, placers: MutableMap<Container, Placer>): Boolean {
        if (entry.cause != Cause.CONTAINER_REMOVE || entry.qty >= 0) return false
        val chest = (entry.holder as? Container)?.copy(slot = 0) ?: return false
        val placer = placers.getOrPut(chest) {
            val rows = blocks.get(chest.world)?.at(chest.x, chest.y, chest.z, 0, Long.MAX_VALUE, limit = PLACER_READ, reverse = true).orEmpty()
            val placed = rows.firstOrNull { it.cause == Cause.BLK_PLAYER_PLACE }
            Placer(placed != null || rows.size < PLACER_READ, placed?.actor)
        }
        if (!placer.known) return false
        // A withdrawal names whoever took it as where it went rather than as its actor.
        val taker = entry.actor ?: (entry.counterparty as? PlayerHolder)?.uuid ?: return false
        return taker != placer.player
    }

    // `exclude:` takes items and players in one list, so a name that is a player is a player excluded.
    internal fun rowFilter(query: LookupQuery, users: Set<UUID>, rollback: Boolean = false) =
        RowFilter(ledger, query, users, query.excluded.mapNotNull(idOf).toSet(), rollback)

    /** Who a row is on, and how sure: a name seen, a name worked out, a witness, or nobody. */
    private fun who(actor: UUID?, confidence: Confidence, away: String? = null): Component = when {
        // A row with nobody on it is not a row worth less: an unfound culprit is no reason to leave a
        // disappearance unrecorded, so it simply says so.
        actor == null -> Ui.text(tr("nobody", "никто"), Ui.FAINT)
        confidence == Confidence.INFERRED ->
            Ui.hover(Ui.text("${playerName(actor)}?", Ui.WHO), tr("worked out, not seen", "вычислено, не видели напрямую"))
        confidence == Confidence.NEARBY -> Ui.hover(
            Ui.text("(${playerName(actor)})", Ui.MUTED),
            tr("only nearby", "был только рядом") + (away?.let { tr(", $it blocks away", ", в $it блоках") } ?: ""),
        )
        else -> Ui.text(playerName(actor), Ui.WHO)
    }

    // The cause's own name waits on the hover: what config.yml and action: filters are written in.
    private fun act(sign: String, cause: Cause, vararg more: String): Component = Ui.hover(
        Component.text().append(sign(sign)).append(Component.text(" ")).append(verb(cause)).build(),
        cause.name.lowercase(), *more,
    )

    private fun draw(entry: LedgerEntry, who: UUID?, amount: Int, clickable: Boolean): Component {
        val gained = entry.qty > 0
        // Both halves of a mutation face the Void, so without this they read as an item destroyed and
        // an unrelated item created at the same instant, which is the very thing they exist to deny.
        val act = if (entry.kind == Kind.MUTATE) act(if (gained) "+" else "-", entry.cause, tr("changed in place", "изменён на месте"))
        else act(if (gained) "+" else "-", entry.cause)
        val (from, to) = if (gained) entry.counterparty to entry.holder else entry.holder to entry.counterparty
        val flow = Component.text().append(holder(from)).append(Ui.text(" → ", Ui.FAINT)).append(holder(to)).build()
        val at = listOf(entry.holder, entry.counterparty).firstNotNullOfOrNull {
            when (it) {
                is Container -> Ui.place(it.world, it.x, it.y, it.z, clickable)
                is WorldBlock -> Ui.place(it.world, it.x, it.y, it.z, clickable)
                else -> null
            }
        }
        return row(Ui.ago(entry.timestamp), who(who, entry.confidence), act, item(entry.itemFormId, amount), flow, at)
    }

    // What a block row did: put a thing there, took one away, or turned one into another.
    private fun blockChange(row: BlockRow): Pair<String, String> {
        val before = stateName(ledger, row.stateBefore)
        val after = stateName(ledger, row.stateAfter)
        return when {
            before in AIRS && after !in AIRS -> "+" to after
            after in AIRS && before !in AIRS -> "-" to before
            else -> "~" to if (before == after) before else "$before>$after"
        }
    }

    private fun draw(world: UUID, row: BlockRow, rows: Int, clickable: Boolean): Component {
        val (sign, named) = blockChange(row)
        val thing = Component.text().color(Ui.THING)
        if (">" in named) {
            thing.append(Ui.block(named.substringBefore('>'))).append(Ui.text(" → ", Ui.FAINT)).append(Ui.block(named.substringAfter('>')))
        } else {
            thing.append(Ui.block(named))
        }
        thing.append(times(rows))
        // The whole state is what makes a row restorable and is noise in a list, so it waits for a hover,
        // with which half of a door or a bed it was: two rows of one are otherwise the same row twice.
        val details = listOfNotNull(
            "${stateOf(row.stateBefore)} → ${stateOf(row.stateAfter)}",
            tr("the other half", "вторая половина").takeIf { row.alongside },
            tr("with contents", "с содержимым").takeIf { (row.payloadBefore != null || row.payloadAfter != null) && signOf(row) == null },
        )
        val pressed = if (row.cause == Cause.BLK_ENTITY_SWITCH) row.payloadAfter?.let(::pressedBy) else null
        val extra = when {
            pressed != null -> Component.text().color(Ui.MUTED).append(Ui.text("← ")).append(Ui.any(pressed.first)).build()
            else -> signOf(row)?.let { Ui.text(it, Ui.MUTED) }
        }
        return row(
            Ui.ago(row.timestamp), who(row.actor, row.confidence, pressed?.second), act(sign, row.cause),
            Ui.hover(thing.build(), *details.toTypedArray()), extra, Ui.place(world, row.x, row.y, row.z, clickable),
        )
    }

    private fun draw(world: UUID, row: EntityRow, rows: Int, clickable: Boolean): Component {
        val sign = when (row.kind) {
            EntityKind.CREATED -> "+"
            EntityKind.CHANGED, EntityKind.MOVED -> "~"
            else -> "-"
        }
        val subject = when (row.kind) {
            EntityKind.PLAYER_DIED -> Ui.text(playerName(row.uuid), Ui.WHO)
            EntityKind.DROPPED -> Ui.text(tr("${row.drops.size} items fell out", "выпало предметов: ${row.drops.size}"), Ui.THING)
            else -> Component.text().color(Ui.THING).append(Ui.entity(row.type)).append(times(rows)).build()
        }
        // How it died says what nobody named cannot: dried out on land, crammed, fell.
        val details = listOfNotNull(
            "${row.type} ${row.uuid}",
            when (row.kind) {
                EntityKind.REMOVED -> tr("gone", "исчезновение")
                EntityKind.CREATED -> tr("brought in", "появление")
                EntityKind.CHANGED -> tr("changed", "изменение")
                EntityKind.MOVED -> tr("led away", "увод")
                else -> null
            },
            row.death?.let {
                if (it.startsWith(TRANSFORMED)) tr("turned (${it.removePrefix(TRANSFORMED)})", "превращение (${it.removePrefix(TRANSFORMED)})")
                else tr("died of ${it.removePrefix("minecraft:")}", "причина смерти: ${it.removePrefix("minecraft:")}")
            },
            tr("${row.drops.size} items fell out", "выпало предметов: ${row.drops.size}").takeIf { row.kind != EntityKind.DROPPED && row.drops.isNotEmpty() },
        )
        // A dog's kill is put on its owner, which reads as the owner's blow without the dog named.
        val via = row.via?.let { Component.text().color(Ui.MUTED).append(Ui.text("← ")).append(Ui.any(it)).build() }
        return row(
            Ui.ago(row.timestamp), who(row.actor, row.confidence), act(sign, row.cause),
            Ui.hover(subject, *details.toTypedArray()), via, Ui.place(world, row.x, row.y, row.z, clickable),
        )
    }

    // A sign is what a payload is most often asked about, and its text is the whole of what was written.
    private fun signOf(row: BlockRow): String? {
        val before = row.payloadBefore?.let(::signText)
        val after = row.payloadAfter?.let(::signText)
        return when {
            before == null && after == null -> null
            before != null && after != null && before != after -> "$before → $after"
            else -> after ?: before
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
     * somebody's things turns on. Hovered, the item shows as the game shows it.
     */
    private fun item(itemFormId: Long, amount: Int): Component {
        val key = itemKey(ledger, itemFormId) ?: return Ui.text("item form $itemFormId", Ui.THING)
        val out = Component.text().color(Ui.THING).append(Ui.item(key))
        customName(itemFormId)?.let { out.append(Ui.text(" \"$it\"")) }
        out.append(times(amount))
        return out.build().hoverEvent(runCatching { HoverEvent.showItem(Key.key(key), amount.coerceIn(1, 99)) }.getOrNull())
    }

    private fun customName(itemFormId: Long): String? {
        val decoder = codec ?: return null
        val form = ledger.form(itemFormId) ?: return null
        // A form that will not decode still names its type, which is worth more than an error.
        return runCatching { decoder.decode(form, 1, null).get(DataComponents.CUSTOM_NAME)?.string }.getOrNull()
    }

    // The short name of each end of a movement; where exactly in it — the slot, the coordinates — on hover.
    private fun holder(holder: Holder): Component = when (holder) {
        is PlayerInv -> Ui.hover(Ui.text(playerName(holder.uuid), Ui.WHO), tr("inventory, slot ${holder.slot}", "инвентарь, слот ${holder.slot}"))
        is PlayerEquip -> Ui.hover(Ui.text(playerName(holder.uuid), Ui.WHO), tr("equipment, slot ${holder.slot}", "снаряжение, слот ${holder.slot}"))
        is PlayerCursor -> Ui.hover(Ui.text(playerName(holder.uuid), Ui.WHO), tr("cursor", "курсор"))
        is PlayerEnder -> Ui.hover(
            Component.text().append(Ui.text(playerName(holder.uuid), Ui.WHO)).append(Ui.text(" · ", Ui.FAINT))
                .append(Ui.block("minecraft:ender_chest").color(Ui.MUTED)).build(),
            tr("slot ${holder.slot}", "слот ${holder.slot}"),
        )
        is MenuSlot -> Ui.hover(Ui.text(menuName(holder.menuType), Ui.MUTED), tr("menu slot ${holder.slot}", "слот меню ${holder.slot}"))
        is Container -> Ui.hover(Ui.text(tr("container", "контейнер"), Ui.MUTED), "${holder.x} ${holder.y} ${holder.z}", tr("slot ${holder.slot}", "слот ${holder.slot}"))
        is WorldBlock -> Ui.hover(Ui.text(tr("block", "блок"), Ui.MUTED), "${holder.x} ${holder.y} ${holder.z}")
        // A crafting grid is the player's own entity slot, and a name reads better than their uuid.
        is EntitySlot -> nameOf(holder.uuid)?.let { Ui.hover(Ui.text(it, Ui.WHO), tr("crafting grid, slot ${holder.slot}", "сетка крафта, слот ${holder.slot}")) }
            ?: Ui.hover(Ui.text(tr("entity", "сущность"), Ui.MUTED), "${holder.uuid}", tr("slot ${holder.slot}", "слот ${holder.slot}"))
        is ItemEntityRef -> Ui.hover(Ui.text(tr("on the ground", "на земле"), Ui.MUTED), "${holder.uuid}")
        is Nested -> Ui.hover(Ui.text(tr("inside", "внутри"), Ui.MUTED), "${holder.ownerId} #${holder.index}")
        Void -> Ui.hover(Ui.text("∅", Ui.FAINT), tr("nowhere: made or used up here", "нигде: появилось или израсходовано"))
    }

    // An offline player's name can come off disk, once per printed row and holder otherwise.
    // ponytail: never evicted, so a rename shows after a restart; a timed cache if that matters.
    private val names = ConcurrentHashMap<UUID, String>()

    private fun playerName(uuid: UUID): String =
        names[uuid] ?: nameOf(uuid)?.also { names[uuid] = it } ?: uuid.toString()
}
