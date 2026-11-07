package io.pfaumc.pfauprotect.command
import io.pfaumc.pfauprotect.attribution.AIRS
import io.pfaumc.pfauprotect.attribution.blockNameOf
import io.pfaumc.pfauprotect.say
import io.pfaumc.pfauprotect.Debug
import io.pfaumc.pfauprotect.api.HolderRef
import io.pfaumc.pfauprotect.api.LookupPage
import io.pfaumc.pfauprotect.api.LookupRow
import io.pfaumc.pfauprotect.api.PfauProtectException
import io.pfaumc.pfauprotect.api.PlayerRef
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.argument.CustomArgumentType
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.ChatKind
import io.pfaumc.pfauprotect.storage.ChatLine
import io.pfaumc.pfauprotect.storage.ChatLog
import io.pfaumc.pfauprotect.storage.Box
import io.pfaumc.pfauprotect.storage.Steps
import io.pfaumc.pfauprotect.storage.EntityRow
import io.pfaumc.pfauprotect.capture.entity.TRANSFORMED
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.storage.BlockRow
import io.pfaumc.pfauprotect.model.BLOCK_CAUSES
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.ENTITY_PLANE_CAUSES
import io.pfaumc.pfauprotect.model.block
import io.pfaumc.pfauprotect.rollback.spot
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.storage.EntryPage
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.LIGHTNING
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
import io.pfaumc.pfauprotect.model.ownHolders
import io.pfaumc.pfauprotect.storage.itemTypeIdOf
import net.minecraft.core.component.DataComponents
import io.pfaumc.pfauprotect.capture.entity.nbtOf
import net.minecraft.nbt.NbtOps
import net.minecraft.world.level.block.entity.SignText
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import net.minecraft.core.registries.BuiltInRegistries
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import io.pfaumc.pfauprotect.Metrics
import io.pfaumc.pfauprotect.SECONDS_PER_UNIT
import io.pfaumc.pfauprotect.Settings
import kotlin.math.abs
import io.pfaumc.pfauprotect.ROLLBACK_PERMISSION
import io.pfaumc.pfauprotect.TELEPORT_PERMISSION
import io.pfaumc.pfauprotect.Texts
import io.pfaumc.pfauprotect.Ui
import io.pfaumc.pfauprotect.tr
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.entity.Player
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
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

// Spellings and completions come from one table so they cannot drift. The first key is suggested;
// the rest are silent aliases.
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
    // The moment the first page was read, which the page buttons pin: rows written since would shift every
    // page under the reader, and « would show what was never on the page they left. Not offered to type.
    ASOF("asof"),
    // Which part of a place the inspector asks about, `block` or `contents`; the page buttons keep it. Not offered to type.
    PART("part"),
    ;

    val canonical: String get() = keys.first()

    companion object {
        private val byKey = entries.flatMap { param -> param.keys.map { it to param } }.toMap()
        val prefixes = entries.filter { it != ASOF && it != PART }.map { "${it.canonical}:" }
        val help = prefixes.joinToString(" ")
        fun of(key: String): Param? = byKey[key]
    }
}

// Same one-table rule as Param.
internal enum class Action(val causes: Set<Cause>, vararg val keys: String, val crossing: Crossing? = null) {
    // Which way an item went across a player's hands, decided per row from both ends rather than by cause.
    INVENTORY(INVENTORY_CAUSES, "inventory", "inv", "inventories", crossing = Crossing(inward = true, outward = true, containers = true)),
    INVENTORY_ADD(INVENTORY_CAUSES, "+inventory", "+inv", "inventory+", "inv+", "+inventories", crossing = Crossing(inward = true, outward = false, containers = true)),
    INVENTORY_REMOVE(INVENTORY_CAUSES, "-inventory", "-inv", "inventory-", "inv-", "-inventories", crossing = Crossing(inward = false, outward = true, containers = true)),
    ITEM(INVENTORY_CAUSES, "item", "items", crossing = Crossing(inward = true, outward = true, containers = false)),
    ITEM_ADD(INVENTORY_CAUSES, "+item", "+items", "item+", "items+", crossing = Crossing(inward = true, outward = false, containers = false)),
    ITEM_REMOVE(INVENTORY_CAUSES, "-item", "-items", "item-", "items-", crossing = Crossing(inward = false, outward = true, containers = false)),

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
    // A book written in or signed: its item rows in a lookup, and in /pp chat what was written.
    BOOK(setOf(Cause.BOOK_EDIT, Cause.BOOK_SIGN), "book", "books"),
    // The two halves of chat, for /pp chat alone: no plane has rows of them.
    GLOBAL(emptySet(), "global"),
    LOCAL(emptySet(), "local"),
    SHAPE(setOf(Cause.BLK_SHAPE), "shape", "shapes", "joined"),
    SWITCH(setOf(Cause.BLK_PLAYER_SWITCH, Cause.BLK_ENTITY_SWITCH), "switch", "switches", "pressed"),
    INTERACT(setOf(Cause.BLK_PLAYER_USE), "interact", "clicked", "toggled"),
    COMMAND(setOf(Cause.BLK_COMMAND, Cause.BLK_PLUGIN), "command", "commands", "worldedit", "plugin"),

    // The entity plane: what died, what a player brought into the world, and everything done to an entity.
    KILL(
        setOf(Cause.ENTITY_KILLED, Cause.ENTITY_BROKEN, Cause.PLAYER_KILLED, Cause.PLAYER_DIED, Cause.MOB_TRANSFORM, Cause.BUCKET_CAPTURE_MOB),
        "kill", "kills", "killed", "death", "deaths",
    ),
    // A fight between players: the kill and the totem it cost in a lookup, and in /pp chat every hit that led to it.
    // The totem by its entity row: a totem spent on a fall writes the same cause in the item plane.
    PVP(setOf(Cause.PLAYER_KILLED), "pvp", "hit", "hits", "fight"),
    SPAWN(
        setOf(Cause.SPAWN_EGG_USE, Cause.MOB_BRED, Cause.BUCKET_RELEASE_MOB, Cause.PLACE_ENTITY_ITEM, Cause.MOB_TRANSFORM),
        "spawn", "spawned", "bred",
    ),
    ENTITY(ENTITY_CAUSES, "entity", "entities"),
    ENTITY_ADD(emptySet(), "+entity", "+entities"),
    ENTITY_REMOVE(emptySet(), "-entity", "-entities"),
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

    /**
     * The entity rows it takes by what became of the entity, whatever the cause: a frame an explosion broke
     * is filed under the explosion's cause, one that fell with its wall under a break of its own, and both
     * are an entity gone.
     */
    val kinds: Set<EntityKind>
        get() = when (this) {
            ENTITY -> EntityKind.entries.toSet() - EntityKind.DROPPED
            ENTITY_ADD -> setOf(EntityKind.CREATED)
            ENTITY_REMOVE -> setOf(EntityKind.REMOVED, EntityKind.PLAYER_DIED, EntityKind.TOTEM_POPPED)
            PVP -> setOf(EntityKind.TOTEM_POPPED)
            else -> emptySet()
        }

    companion object {
        private val byKey = entries.flatMap { action -> action.keys.map { it to action } }.toMap()
        val names = entries.map { it.keys.first() }
        fun of(key: String): Action? = byKey[key]
    }
}

// A direction across a player's hands: `inward` into them, `outward` out of them; `containers` whether a container
// counts as the other side (`inventory`) or only the ground, the void and other entities (`item`).
internal class Crossing(val inward: Boolean, val outward: Boolean, val containers: Boolean)

// Everything but the block plane's causes: building is the block plane's `+block`, and a purge's opening
// balance stands for what was held before, not for anything done.
private val INVENTORY_CAUSES: Set<Cause> =
    Cause.entries.filter { !it.name.startsWith("BLK_") }.toSet() - Cause.BLOCK_PLACE - Cause.PURGE_OPENING

// The player whose hands a holder is, if it is: the crafting grid is their entity slot, which is also a
// projectile's or a frame's, hence the check. An ender chest is a container (as is a station's menu slot) in CoreProtect's sense, not a hand.
private fun handsOf(holder: Holder, isPlayer: (UUID) -> Boolean): UUID? = when (holder) {
    is PlayerInv -> holder.uuid
    is PlayerEquip -> holder.uuid
    is PlayerCursor -> holder.uuid
    is EntitySlot -> holder.uuid.takeIf(isPlayer)
    else -> null
}

// Whether the row moved the item across a player's hands in a direction [crossing] asks for, from both ends.
internal fun crosses(entry: LedgerEntry, crossing: Crossing, isPlayer: (UUID) -> Boolean): Boolean {
    if (entry.cause == Cause.BLOCK_PLACE || entry.cause == Cause.PURGE_OPENING || entry.kind == Kind.MUTATE) return false
    val (from, to) = if (entry.qty > 0) entry.counterparty to entry.holder else entry.holder to entry.counterparty
    val mine = handsOf(from, isPlayer)
    val yours = handsOf(to, isPlayer)
    // Items moved into a bundle or shulker inside an inventory never left the hands.
    if (from is Nested || to is Nested) return false
    val other = if (mine != null) to else from
    if (!crossing.containers && (other is Container || other is PlayerEnder || other is MenuSlot)) return false
    return crossing.outward && mine != null && yours != mine || crossing.inward && yours != null && mine != yours
}

// Every way one item becomes another, which is one question however many benches answer it.
private val TRANSFORM_CAUSES = setOf(
    Cause.CRAFT_CONSUME, Cause.CRAFT_RESULT, Cause.CRAFT_REMAINDER, Cause.SMELT, Cause.BREW,
    Cause.ANVIL_COMBINE, Cause.GRINDSTONE, Cause.SMITHING_TRANSFORM, Cause.SMITHING_TRIM,
    Cause.ENCHANT_APPLY, Cause.STONECUTTER, Cause.LOOM, Cause.CARTOGRAPHY, Cause.BOOK_SIGN, Cause.MAP_FILL,
    Cause.BUCKET_FILL, Cause.BUCKET_EMPTY, Cause.BUCKET_CAPTURE_MOB, Cause.BUCKET_RELEASE_MOB,
    Cause.BOTTLE_FILL, Cause.BOTTLE_EMPTY, Cause.CAULDRON_WASH, Cause.TRANSMUTE_ON_BREAK,
)

// Everything the entity plane writes, and what a player's hand does to a mob in the item plane. A totem's
// cause is the item plane's too, where it is every totem spent; its entity row is taken by kind.
private val ENTITY_CAUSES = ENTITY_PLANE_CAUSES - Cause.TOTEM_CONSUME + setOf(
    Cause.FEED_MOB, Cause.TAME_MOB, Cause.EQUIP_MOB, Cause.SHEAR_MOB, Cause.DYE_MOB,
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

private val GLOBAL_WORDS = setOf("global", "none", "off", "false", "-1")
private val TIME_EXAMPLES = listOf("10m", "1h", "6h", "1d", "3d", "1w", "12:00-13:00")
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

// Every item, block and entity by its bare name, which an `exclude:` word is read as before it is read as an action.
private val THING_NAMES: Set<String> by lazy {
    listOf(BuiltInRegistries.ITEM, BuiltInRegistries.BLOCK, BuiltInRegistries.ENTITY_TYPE)
        .flatMapTo(HashSet()) { registry -> registry.keySet().filter { it.namespace == VANILLA_NAMESPACE }.map { it.path } }
}

private val DURATION = Regex("(\\d+)(mo|[ymwdhs])")

// How far a world-wide lookup of a player walks their index, past the rows a filter dropped, before it
// answers with what it found and says the read stopped.
private const val GLOBAL_WALK = 4096

// How many positions and rows a radius lookup steps over in all, every chunk and plane together, before it
// answers with what it has and says the read stopped; a box full of history costs a seek per position.
private const val BOX_STEPS = 250_000

// How many later rows of a place a world-wide lookup reads for a rollback that gave a row back, or for what
// fell out of a container broken there. A rollback further back than that behind a busy place goes unmarked.
private const val LATER_READ = 32

/** Which part of one place a lookup reads: the inspector asks a container for its block and its contents apart. */
enum class Part(val word: String?) { ALL(null), BLOCK("block"), CONTENTS("contents") }

data class LookupQuery(
    val users: List<String> = emptyList(),
    // Whose own slots to read, rather than where. `users` narrows the rows of a place down to the
    // ones a player is named on; this is the other question, what went through their hands.
    val players: List<String> = emptyList(),
    val included: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val causes: Set<Cause>? = null,
    // Entity rows `action:` takes by their kind on top of `causes`, which is then never null.
    val kinds: Set<EntityKind> = emptySet(),
    // The words `action:` was given, for the `&` between them: `causes` and `kinds` take every one of them.
    val actions: List<String> = emptyList(),
    val secondsBack: Long? = null,
    // The near end of a span, `time:2h-1h`: how long ago it stops. Null is now.
    val secondsUntil: Long? = null,
    // The same two ends when a clock time or a date was typed: absolute, so a page asked for later
    // (with `asOf` moved) still covers the same window. They win over the seconds.
    val fromMillis: Long? = null,
    val untilMillis: Long? = null,
    val radius: Int? = null,
    // The `limit:` said, else the reader's own page size, filled in where they asked; null is the default.
    val rows: Int? = null,
    val page: Int = 1,
    // Only rows a rollback has since undone, or only rows it has not; null for both.
    val rolledBack: Boolean? = null,
    val amount: IntRange? = null,
    val steal: Boolean = false,
    val count: Boolean = false,
    // One event and nothing else: a click on a lookup line fills it in.
    val event: EventRef? = null,
    // Text a chat line or a command has to hold, for /pp chat.
    val filter: String? = null,
    val all: Boolean = false,
    val at: Triple<Int, Int, Int>? = null,
    // The words it was asked with, which the page buttons ask again.
    val words: String = "",
    // Whether ⌖ may run /pp tp for the one asking: read where they asked, as the answer is written off it.
    val teleports: Boolean = false,
    // Whether ↶ may run or type /pp rollback for them, read the same way.
    val rollbacks: Boolean = false,
    // Whether the page opens in a dialog: the asker's choice, read where they asked like `teleports`.
    val dialog: Boolean = false,
    // What "now" is for this answer: set when the first page is asked for and pinned by the page buttons.
    val asOf: Long? = null,
    // Of a single place: its block, its contents, or both. A radius or a player ignores it.
    val part: Part = Part.ALL,
) {
    val global: Boolean get() = radius == GLOBAL_RADIUS

    // Whether any action asked for is a direction across a player's hands, which has no block to roll back.
    val crosses: Boolean
        get() = (actions + excluded).any { group -> allOf(group).any { Action.of(it.lowercase())?.crossing != null } }

    val limit: Int get() = rows ?: DEFAULT_LIMIT

    val hasFrom: Boolean get() = secondsBack != null || fromMillis != null

    // Whether the span stops before now.
    val hasUntil: Boolean get() = secondsUntil != null || untilMillis != null

    fun fromTs(now: Long = asOf ?: System.currentTimeMillis()): Long = fromMillis ?: secondsBack?.let { now - it * 1000 } ?: 0

    // Pinned, the moment itself is left out: a row of that millisecond may land after the first page was read.
    fun toTs(now: Long = asOf ?: System.currentTimeMillis()): Long =
        // A typed end still ahead (`t:03.11` on that day) stops at the pin too, or later pages would shift.
        untilMillis?.let { until -> asOf?.let { minOf(until, it - 1) } ?: until }
            ?: secondsUntil?.let { now - it * 1000 } ?: asOf?.minus(1) ?: Long.MAX_VALUE

    // Rows a page needs read: every page before it as well, since a read is newest first. Runs folded
    // into one line each eat rows, so a folded page reads further.
    // The factor is fixed: a page of one huge run still comes out short, and says there is more.
    val wanted: Int get() = limit * page * if (all || count) 1 else GROUPED_READ

    // How far a read goes so that the filters applied after it still leave `wanted` rows.
    internal val fetch: Int get() = minOf(wanted * FETCH_FACTOR, MAX_FETCH)

    /** The same question about the same place, at another page. */
    fun pageCommand(target: LookupTarget, page: Int): String = pageCommand("l", page, "at:${target.x},${target.y},${target.z}")

    /** The same question to `/pp [subcommand]` at another page, with the words that pin what it is about. */
    fun pageCommand(subcommand: String, page: Int, vararg pinned: String): String {
        // A bare word is a player, even one named `page`.
        val kept = words.split(' ').filter { it.isNotBlank() && it.substringBefore(':', "").lowercase() !in PAGED_AWAY }
        return (listOf("/pp", subcommand) + kept + pinned + listOfNotNull(asOf?.let { "asof:$it" }, part.word?.let { "part:$it" }) + "page:$page").joinToString(" ")
    }
}

private const val GROUPED_READ = 4
private val PAGED_AWAY = setOf("page", "pg", "at", "asof", "part")

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
            val (x, y, z) = positionOrNull(parts[1]) ?: return null
            return EventRef(parts[0].toLongOrNull() ?: return null, x, y, z, parts[2].toLongOrNull() ?: return null)
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
private fun said(text: String) = LiteralMessage(Texts.translate(text))

private val UNKNOWN_PARAMETER = DynamicCommandExceptionType {
    said("unknown parameter '$it:', expected one of ${Param.help}")
}
private val EMPTY_VALUE = DynamicCommandExceptionType { said("'$it:' has no value") }
private val BAD_TIME = DynamicCommandExceptionType {
    said("'$it' is not a time, expected 1h, 2h-1h, 14:00, 03.11 or 03.11_14:00-03.11_18:00")
}
private val BAD_RADIUS = DynamicCommandExceptionType {
    said("'$it' is not a radius, expected 0 to ${Settings.maxRadius} blocks or 'global'")
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
private val BAD_MOMENT = DynamicCommandExceptionType { said("'$it' is not a moment; the page buttons fill it in") }
private val BAD_PART = DynamicCommandExceptionType { said("'$it' is not a part, expected part:block or part:contents") }
private val STEAL_EXCLUDED = DynamicCommandExceptionType {
    said("'$it': a theft is worked out for each withdrawal, so exclude:steal is not taken; use exclude:withdraw instead")
}
private val CHAT_ONLY_ACTION = DynamicCommandExceptionType {
    said("'$it' is a half of chat, which only /pp chat reads")
}
private val BAD_LIMIT = DynamicCommandExceptionType {
    said("'$it' is not a row count between 1 and $MAX_LIMIT")
}

fun parseLookupQuery(input: String, now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()), chat: Boolean = false): LookupQuery =
    parseLookupQuery(StringReader(input), now, chat)

// Parsing walks the caller's reader instead of a copied string so that every exception carries the
// real cursor: the client underlines the offending token rather than the whole argument.
// `chat` for /pp chat, the one reader of the halves of chat: anywhere else they are refused, since neither plane has
// rows of them, and a lookup would find nothing while a rollback, reading past them, undid more.
fun parseLookupQuery(reader: StringReader, now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()), chat: Boolean = false): LookupQuery {
    var query = LookupQuery(words = reader.remaining)
    // The `time:` value as typed: `asof:` may come after it, and the moment it names is resolved against that.
    var rawTime: String? = null
    while (true) {
        while (reader.canRead() && reader.peek() == ' ') reader.skip()
        if (!reader.canRead()) {
            // Page buttons ask again with `asof:` pinned: `t:14:00` typed at 13:59 is yesterday's, and must stay so at 14:01.
            val pinned = query.asOf?.let { Instant.ofEpochMilli(it).atZone(now.zone) } ?: now
            val timed = rawTime?.let { raw ->
                timeSpanOrNull(raw, pinned)?.let {
                    query.copy(secondsBack = it.secondsBack, secondsUntil = it.secondsUntil, fromMillis = it.fromMillis, untilMillis = it.untilMillis)
                }
            } ?: query
            return timed.userEverywhere()
        }
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
        // A bare word is a player, as CoreProtect reads `/co rollback Notch t:1d`; a misspelt
        // parameter then fails as an unknown player rather than passing as a filter.
        if (colon < 0) {
            if (values(token).isEmpty()) {
                reader.cursor = tokenStart
                throw EMPTY_VALUE.createWithContext(reader, token)
            }
            query = query.copy(users = query.users + values(token))
            continue
        }
        val key = token.substring(0, colon).lowercase()
        val value = token.substring(colon + 1)
        val param = Param.of(key)
        if (param == null) {
            reader.cursor = tokenStart
            throw UNKNOWN_PARAMETER.createWithContext(reader, key)
        }
        val valueStart = tokenStart + colon + 1
        // Separators alone are no value: `u:&` read as no list at all would leave a rollback every row of its radius.
        // In `filter:` a comma is text.
        val terms = if (param == Param.FILTER) allOf(value) else value.split(',', '&').filter { it.isNotBlank() }
        if (terms.isEmpty()) {
            reader.cursor = valueStart
            throw EMPTY_VALUE.createWithContext(reader, key)
        }
        reader.cursor = valueStart
        query = when (param) {
            Param.USER -> query.copy(users = query.users + values(value))
            Param.PLAYER -> query.copy(players = query.players + values(value))
            Param.TIME -> timeSpanOrNull(value, now)?.let {
                rawTime = value
                query.copy(secondsBack = it.secondsBack, secondsUntil = it.secondsUntil, fromMillis = it.fromMillis, untilMillis = it.untilMillis)
            } ?: fail(reader, BAD_TIME, value)
            Param.RADIUS -> query.copy(radius = radiusOrNull(value) ?: fail(reader, BAD_RADIUS, value))
            Param.ACTION -> {
                val named = actions(reader, valueStart, value, chat)
                query.copy(
                    causes = (query.causes ?: emptySet()) + named.flatMap { it.causes },
                    kinds = query.kinds + named.flatMap { it.kinds },
                    steal = query.steal || Action.STEAL in named,
                    actions = query.actions + values(value),
                )
            }
            Param.INCLUDE -> query.copy(included = query.included + values(value))
            // A theft is worked out by reading who put the container down, not from the row itself, so leaving
            // it out could only leave out every withdrawal.
            Param.EXCLUDE -> if (value.split(',', '&').any { Action.of(it.lowercase()) == Action.STEAL }) {
                fail(reader, STEAL_EXCLUDED, value)
            } else {
                query.copy(excluded = query.excluded + values(value))
            }
            Param.LIMIT -> query.copy(rows = limitOrNull(value) ?: fail(reader, BAD_LIMIT, value))
            Param.PAGE -> query.copy(page = value.toIntOrNull()?.takeIf { it >= 1 } ?: fail(reader, BAD_PAGE, value))
            Param.ROLLEDBACK -> query.copy(rolledBack = yesNoOrNull(value) ?: fail(reader, BAD_YES_NO, value))
            Param.AMOUNT -> query.copy(amount = amountOrNull(value) ?: fail(reader, BAD_AMOUNT, value))
            Param.EVENT -> query.copy(event = EventRef.parse(value) ?: fail(reader, BAD_EVENT, value))
            Param.FILTER -> query.copy(filter = value)
            Param.AT -> query.copy(at = positionOrNull(value) ?: fail(reader, BAD_POSITION, value))
            Param.ASOF -> query.copy(asOf = value.toLongOrNull()?.takeIf { it > 0 } ?: fail(reader, BAD_MOMENT, value))
            Param.PART -> query.copy(part = Part.entries.firstOrNull { it.word == value.lowercase() } ?: fail(reader, BAD_PART, value))
        }
        reader.cursor = tokenStart + token.length
    }
}

// CoreProtect reads `u:Name` without `r:` as everywhere, and admins coming from it ask that way; read as the
// one block in sight it answers "nothing recorded" and the plugin looks broken. `player:` takes no radius at
// all, and `event:` is one row already.
private fun LookupQuery.userEverywhere() =
    if (users.isNotEmpty() && radius == null && players.isEmpty() && event == null) copy(radius = GLOBAL_RADIUS) else this

private fun fail(reader: StringReader, type: DynamicCommandExceptionType, value: String): Nothing =
    throw type.createWithContext(reader, value)

// `,` is either, `&` is all of them in one row: `u:A&B,C` is a row with both A and B, or one with C. Each
// element keeps its `&`, and whoever reads the list splits it with allOf.
private fun values(value: String): List<String> =
    value.split(',').map { allOf(it).joinToString("&") }.filter { it.isNotEmpty() }

internal fun allOf(group: String): List<String> = group.split('&').filter { it.isNotBlank() }

private fun actions(reader: StringReader, valueStart: Int, value: String, chat: Boolean): List<Action> {
    val found = mutableListOf<Action>()
    var offset = valueStart
    for (name in value.split(',', '&')) {
        if (name.isNotBlank()) {
            val action = Action.of(name.lowercase())
            if (action == null || !chat && action in CHAT_ONLY) {
                reader.cursor = offset
                throw (if (action == null) BAD_ACTION else CHAT_ONLY_ACTION).createWithContext(reader, name)
            }
            found += action
        }
        offset += name.length + 1
    }
    return found
}

private fun positionOrNull(value: String): Triple<Int, Int, Int>? =
    value.split(',').takeIf { it.size == 3 }?.map { it.toIntOrNull() ?: return null }?.let { (x, y, z) -> Triple(x, y, z) }

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

/** Both ends of a `time:` value, each end either relative (seconds back) or absolute (epoch millis). */
internal data class TimeSpan(
    val secondsBack: Long? = null,
    val secondsUntil: Long? = null,
    val fromMillis: Long? = null,
    val untilMillis: Long? = null,
)

// One end as typed: where it falls (a date alone: its start), and what it stands for as the near end.
// [today] is the same clock time taken today, kept when it was ahead of now and so taken as yesterday.
private class End(val at: Long, val relative: Long?, val nearAt: Long, val today: End? = null, val wholeDay: Boolean = false)

private val DATE = Regex("""(\d{1,2})\.(\d{1,2})(?:\.(\d{4}))?""")
private val CLOCK = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")

private fun endOrNull(text: String, now: ZonedDateTime): End? {
    durationOrNull(text)?.let { return End(now.toInstant().toEpochMilli() - it * 1000, it, 0) }
    val parts = text.split('_')
    if (parts.size > 2) return null
    val date = parts.firstOrNull()?.takeIf { '.' in it }
    val clock = parts.getOrNull(if (date != null) 1 else 0)
    if (date == null && clock == null || parts.size == 2 && (date == null || clock == null)) return null
    try {
        val time = if (clock == null) null else CLOCK.matchEntire(clock)?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].ifEmpty { "0" }.toInt()) } ?: return null
        val zone = now.zone
        fun at(day: LocalDate) = day.atTime(time ?: LocalTime.MIDNIGHT).atZone(zone)
        val day = if (date == null) now.toLocalDate() else DATE.matchEntire(date)?.let { m ->
            // No year: this year, or last if that lands ahead of now.
            m.groupValues[3].takeIf { it.isNotEmpty() }?.let { LocalDate.of(it.toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
                ?: LocalDate.of(now.year, m.groupValues[2].toInt(), m.groupValues[1].toInt()).let { d ->
                    if (at(d).isAfter(now)) LocalDate.of(now.year - 1, d.monthValue, d.dayOfMonth) else d
                }
        } ?: return null
        // A clock time alone: today, or yesterday if that is still ahead.
        val ahead = date == null && at(day).isAfter(now)
        val moment = at(if (ahead) day.minusDays(1) else day)
        val start = moment.toInstant().toEpochMilli()
        // A date without a time is the whole day, a time without seconds its whole minute: as the near end it
        // stops at their last millisecond, so `t:14:00-14:05` takes what happened at 14:05:30.
        val whole = when {
            time == null -> 24 * 3600_000L
            CLOCK.matchEntire(clock!!)!!.groupValues[3].isEmpty() -> 60_000L
            else -> 1L
        }
        val nearAt = start + whole - 1
        val today = at(day).toInstant().toEpochMilli().takeIf { ahead }?.let { End(it, null, it + whole - 1) }
        return End(start, null, nearAt, today, wholeDay = time == null)
    } catch (_: DateTimeException) {
        return null
    }
}

/**
 * A `time:` value: a span of durations as [spanOrNull] reads it, or clock times and dates (`14:00`, `03.11`,
 * `03.11_14:00`) in the server's zone, alone or mixed with durations. A lone date is its whole day, a lone
 * time runs up to now.
 */
internal fun timeSpanOrNull(value: String, now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): TimeSpan? {
    spanOrNull(value)?.let { (back, until) -> return TimeSpan(secondsBack = back, secondsUntil = until) }
    val ends = value.split('-').map { endOrNull(it, now) ?: return null }
    if (ends.size == 1) {
        val end = ends[0]
        // A lone date is its day; a lone time runs up to now.
        return TimeSpan(fromMillis = end.at, untilMillis = end.nearAt.takeIf { end.wholeDay })
    }
    if (ends.size != 2) return null
    // `t:00:24-00:29` at 00:27 runs to a minute still ahead, not from yesterday's 00:29: an end typed second that
    // is ahead of now stays today when that keeps it after the first. `t:23:00-01:00` at 02:00 is unaffected,
    // the one taken as yesterday being the first.
    val second = ends[1].today?.takeIf { it.at > ends[0].at } ?: ends[1]
    if (ends[0].at == second.at) return null
    val (far, near) = listOf(ends[0], second).sortedBy { it.at }
    return TimeSpan(
        secondsBack = far.relative, fromMillis = far.at.takeIf { far.relative == null },
        secondsUntil = near.relative, untilMillis = near.nearAt.takeIf { near.relative == null },
    )
}

fun durationOrNull(value: String): Long? {
    val text = value.lowercase()
    var total = 0L
    var covered = 0
    for (match in DURATION.findAll(text)) {
        covered += match.value.length
        total += match.groupValues[1].toLong() * (SECONDS_PER_UNIT[match.groupValues[2]] ?: 1L)
    }
    return if (covered == text.length && total > 0) total else null
}

private fun radiusOrNull(value: String): Int? {
    val text = value.lowercase().removePrefix("#")
    if (text in GLOBAL_WORDS) return GLOBAL_RADIUS
    return text.toIntOrNull()?.takeIf { it in 0..Settings.maxRadius }
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
internal fun stateName(ledger: RocksItemLog, id: Int): String = blockNameOf(stateOf(ledger, id))

internal fun stateOf(ledger: RocksItemLog, id: Int): String =
    ledger.registries.keyOf(RegistryNamespace.BLOCK_STATE, id) ?: "block state $id"

/**
 * What a query keeps of the rows it read. A lookup and a rollback are asked with the same words and
 * have to mean the same rows by them: the lookup is how a rollback is read before it is run.
 *
 * A rollback holds two more rules. A player named by `user:` answers for what they did or what was
 * worked out to be theirs, never for having stood near it. And a rollback's own rows are left alone
 * unless asked for by name: a second, narrower rollback would otherwise undo the first one. A purge's
 * opening balances are never rolled back: they stand for what a holder held before the purge, not for
 * anything done in the window.
 */
internal class RowFilter(
    private val ledger: RocksItemLog,
    query: LookupQuery,
    // `user:`, a group for each of its `,` elements: a row is kept when it names every player of one.
    private val users: List<Set<UUID>> = emptyList(),
    // Which of `exclude:`'s words are players, by the word lowercased.
    excludedPlayers: Map<String, UUID> = emptyMap(),
    private val rollback: Boolean = false,
    // Whether a uuid is a player: the crafting grid is an entity slot, like a frame's or a projectile's.
    private val isPlayer: (UUID) -> Boolean = { false },
) {
    internal val namesUsers: Boolean get() = users.isNotEmpty()
    private val causes = query.causes
    private val kinds = query.kinds
    private val all = query.all
    private val amount = query.amount
    internal val event = query.event?.id
    // Read only when some group joins actions with `&` or names a crossing, which a cause cannot tell:
    // otherwise `causes` and `kinds` already say it all.
    private val actions = query.actions.map { group -> allOf(group).mapNotNull { Action.of(it.lowercase()) } }
        .takeIf { groups -> groups.any { it.size > 1 || it.any { action -> action.crossing != null } } }
    private val included = query.included.map { group -> allOf(group).map(::normalizeItem) }
    private val excluded = query.excluded.map { group ->
        allOf(group).map { word ->
            val player = excludedPlayers[word.lowercase()]
            Word(player, normalizeItem(word), Action.of(word.lowercase()).takeIf { player == null && word.lowercase() !in THING_NAMES })
        }
    }
    // Nothing but `about` narrows a row: most reads ask nothing of it, and it is asked of every row they walk.
    private val plain = users.isEmpty() && actions == null && included.isEmpty() && excluded.isEmpty()

    // A word of `exclude:`: a player when one goes by it, else an item or a block, and an action only when it names
    // no player and no thing. `e:tnt` leaves out the block and the item; every explosion would leave grief behind in
    // a rollback, and `e:explosion` asks for that by a name nothing else has.
    private class Word(val player: UUID?, val item: String, val action: Action?)

    // A row as the word lists read it. `names` is asked about one player of a group with the group beside it,
    // which an entity row needs: it names the entity only beside the one who did it.
    private class Seen(val items: Set<String?>, val acts: (Action) -> Boolean, val names: (UUID, Collection<UUID>) -> Boolean)

    fun keeps(entry: LedgerEntry): Boolean {
        if (!caused(entry.cause)) return false
        if (amount != null && abs(entry.qty) !in amount) return false
        if (plain) return true
        val acts = { action: Action -> action.crossing?.let { crosses(entry, it, isPlayer) } ?: (entry.cause in action.causes) }
        return about(Seen(setOf(itemKey(ledger, entry.itemFormId)), acts) { player, _ -> namesUser(entry, setOf(player)) })
    }

    // `u:A&B` is what A did to B, a kill above all; `u:B` alone stays what B did.
    fun keeps(row: EntityRow): Boolean {
        // A rollback's own rows stay `action:rollback`'s alone, whatever they did to an entity.
        val countsKind = row.cause != Cause.ROLLBACK && row.cause != Cause.PURGE_OPENING
        if (!keepsActed(row.cause, row.eventId, row.confidence, row.kind in kinds && countsKind)) return false
        if (plain) return true
        return about(Seen(setOf(row.type), { it.crossing == null && (row.cause in it.causes || countsKind && row.kind in it.kinds) }) { player, group ->
            player == row.actor || player == row.uuid && row.actor in group
        })
    }

    // A row names a block rather than an item, so what the filter is about is either state it ran
    // between; naming an item that no block is made of therefore hides the whole plane, which is what
    // a reader asking for one item wants. `i:grass_block&dirt` is the one turned into the other.
    fun keeps(row: BlockRow): Boolean {
        if (!keepsActed(row.cause, row.eventId, row.confidence)) return false
        if (plain) return true
        val named = setOf(stateName(ledger, row.stateBefore), stateName(ledger, row.stateAfter))
        return about(Seen(named, { it.crossing == null && row.cause in it.causes }) { player, _ -> player == row.actor }, shape = row.cause == Cause.BLK_SHAPE)
    }

    private fun keepsActed(cause: Cause, eventId: Long, confidence: Confidence, kind: Boolean = false): Boolean {
        if (!(kind || caused(cause)) || amount != null || event != null && eventId != event) return false
        return users.isEmpty() || !(rollback && confidence == Confidence.NEARBY)
    }

    // A group of each list holds when all of its words do. The shape rows a rollback takes along are not
    // asked about `&` of actions: they ride with whatever went back beside them.
    private fun about(seen: Seen, shape: Boolean = false): Boolean {
        if (users.isNotEmpty() && users.none { group -> group.all { seen.names(it, group) } }) return false
        if (actions != null && !(rollback && shape) && actions.none { group -> group.all(seen.acts) }) return false
        if (included.isNotEmpty() && included.none { group -> group.all { it in seen.items } }) return false
        return excluded.none { group ->
            val players = group.mapNotNull { it.player }
            group.all { word ->
                word.player?.let { seen.names(it, players) } == true || word.item in seen.items || word.action?.let(seen.acts) == true
            }
        }
    }

    // A neighbour's shape goes back with whatever went back beside it, so a rollback narrowed to fire or to
    // explosions takes the shape rows too; undoing a rollback is the one reading that leaves them, since the
    // rollback wrote none. A lookup shows them only when asked: there is one for every fence beside every
    // block that changed.
    private fun caused(cause: Cause): Boolean {
        if (cause == Cause.BLK_SHAPE) {
            return if (rollback) causes?.contains(Cause.ROLLBACK) != true else all || causes?.contains(cause) == true
        }
        return causes?.contains(cause) ?: (!rollback || cause != Cause.ROLLBACK && cause != Cause.PURGE_OPENING)
    }
}

// A rollback reads by place and time, so the words that only shape a lookup's answer are not offered for it.
// `chat` offers only what `/pp chat` reads: without it the client showed a bare <words>, which reads as asking for
// the text of a message.
class LookupArgument(
    private val rollback: Boolean = false,
    private val chat: Boolean = false,
    private val names: () -> List<String> = ::knownNames,
) : CustomArgumentType<LookupQuery, String> {
    override fun getNativeType(): ArgumentType<String> = StringArgumentType.greedyString()

    override fun parse(reader: StringReader): LookupQuery = parseLookupQuery(reader, chat = chat)

    private val starts: List<String> = when {
        chat -> CHAT_PARAMS.map { "${it.canonical}:" } + "#all"
        rollback -> Param.entries.filter { it !in ANSWER_ONLY }.map { "${it.canonical}:" }
        else -> Param.prefixes + listOf("#count", "#all")
    }

    override fun <S : Any> listSuggestions(
        context: CommandContext<S>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val whole = builder.remaining
        val tokenStart = whole.lastIndexOf(' ') + 1
        val token = whole.substring(tokenStart)
        val colon = token.indexOf(':')
        if (colon < 0) {
            // What is already said, under any of its spellings, is not offered again: the list shrinks to what is left.
            // A list of names stays offered, as another of it adds to the first.
            val said = whole.substring(0, tokenStart).split(' ').map { it.lowercase() }
            val keys = said.mapNotNullTo(HashSet()) { word -> Param.of(word.substringBefore(':')).takeIf { ':' in word && it !in REPEATABLE } }
            return offer(builder, tokenStart, starts.filter { it !in said && Param.of(it.removeSuffix(":")) !in keys })
        }
        // After `&` as after `,`: another value of the same list.
        val comma = maxOf(token.lastIndexOf(','), token.lastIndexOf('&'))
        val param = Param.of(token.substring(0, colon).lowercase())
        // A position is one value with commas in it, not a list.
        if (param == Param.AT) {
            val at = (context.source as? CommandSourceStack)?.location
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
        // Typed is lowercased and a name is not: Brigadier's own matching is case-blind too.
        for (candidate in candidates) if (candidate.lowercase().startsWith(typed)) out.suggest(candidate)
        return out.buildFuture()
    }

    private fun valuesFor(param: Param?): List<String> = when (param) {
        Param.USER, Param.PLAYER -> names()
        // Takes players, actions and items in one list; the chat log has only players and hits.
        Param.EXCLUDE -> if (chat) names() + CHAT_ACTIONS.keys.map { it.keys.first() } else names() + EXCLUDABLE + ITEM_NAMES + BLOCK_NAMES
        Param.TIME -> TIME_EXAMPLES
        Param.RADIUS -> RADIUS_EXAMPLES
        Param.ACTION -> if (chat) CHAT_ACTIONS.keys.map { it.keys.first() } else Action.names.filter { Action.of(it) !in CHAT_ONLY }
        Param.INCLUDE -> ITEM_NAMES + BLOCK_NAMES
        Param.LIMIT -> LIMIT_EXAMPLES
        Param.PAGE -> listOf("2", "3")
        Param.ROLLEDBACK -> listOf("no", "yes")
        Param.AMOUNT -> listOf(">=8", ">=64", "<4")
        Param.EVENT, Param.FILTER, Param.AT, Param.ASOF, Param.PART, null -> emptyList()
    }
}

// A griefer is looked up after they have gone, so everyone who ever played is offered, not only who is on.
// Listing the player files on every keystroke costs a big server, so the names who have been off are read
// once a minute at most; who is on now is always offered.
private const val NAMES_MILLIS = 60_000L

@Volatile private var offlineNames: Pair<Long, List<String>> = 0L to emptyList()
private val namesLock = Any()

private fun knownNames(): List<String> {
    fun fresh() = offlineNames.takeIf { System.currentTimeMillis() - it.first < NAMES_MILLIS }?.second
    // Two completions that find the list stale at once read the files once: the second waits and takes it.
    val offline = fresh() ?: synchronized(namesLock) {
        fresh() ?: Bukkit.getOfflinePlayers().mapNotNull { it.name }.also { offlineNames = System.currentTimeMillis() to it }
    }
    return (Bukkit.getOnlinePlayers().map { it.name } + offline).distinct()
}

private val CHAT_PARAMS = listOf(Param.USER, Param.TIME, Param.FILTER, Param.ACTION, Param.EXCLUDE, Param.PAGE)

// An `exclude:` word that is also an item, block or entity is read as the thing, so each action is offered under
// the first of its names no thing has: `burn`, `pistons`, `hoppers`.
private val EXCLUDABLE: List<String> by lazy {
    Action.entries.filter { it != Action.STEAL }.mapNotNull { action -> action.keys.firstOrNull { it !in THING_NAMES } }
}

private val REPEATABLE = setOf(Param.USER, Param.PLAYER, Param.INCLUDE, Param.EXCLUDE)

private val ANSWER_ONLY = setOf(Param.PLAYER, Param.LIMIT, Param.PAGE, Param.FILTER, Param.AMOUNT, Param.ROLLEDBACK, Param.ASOF, Param.PART)

// The clicks of a window, which `verb` words "moved", and the container causes themselves: what a line between a
// player's hands and a container may be drawn as put in or taken out.
private val BOXED = setOf(
    Cause.CURSOR_PLACE, Cause.CURSOR_TAKE, Cause.CURSOR_SWAP, Cause.QUICK_MOVE, Cause.HOTBAR_SWAP, Cause.OFFHAND_SWAP,
    Cause.COLLECT_ALL, Cause.QUICK_CRAFT_DISTRIBUTE, Cause.CONTAINER_ADD, Cause.CONTAINER_REMOVE,
)

// One line of an answer, from either plane, so the two read in the order things happened. `same` is what
// a run of lines folds on: who did what to which thing, which way. `amount` is what a folded line adds
// up — items, or one per block. `places` are where it happened, both ends of a movement, drawn by the answer
// rather than the line, as a lookup of one place says it once in its heading.
internal class Line(
    val timestamp: Long,
    val cause: Cause,
    val who: UUID?,
    val same: String,
    val amount: Int,
    val undone: Boolean,
    val event: EventRef?,
    val places: List<WorldBlock> = emptyList(),
    // The row it was drawn from, a LedgerEntry, BlockRow or EntityRow, for the API's rows.
    val source: Any? = null,
    val draw: (amount: Int, rows: Int) -> Component,
)

// A run of the same thing within this long of each other reads as one line: forty blocks of one wall
// broken in a minute are one act, and forty lines of it push everything else off the screen.
private const val FOLD_MILLIS = 60_000L

internal class Folded(val first: Line, var rows: Int, var amount: Int, var oldest: Long)

internal fun folded(lines: List<Line>, all: Boolean): List<Folded> {
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

/** What a lookup found, before it is drawn: its lines, whether the read saw them all, its heading, a word aside. */
internal class Found(val lines: List<Line>, val complete: Boolean, val where: Component, val note: String? = null)

internal fun refuse(reason: String): Nothing = throw PfauProtectException(PfauProtectException.REFUSED, reason)

internal fun uuidOrNull(word: String): UUID? = if (word.length == 36) runCatching { UUID.fromString(word) }.getOrNull() else null

/** The runs of a page and whether there is a next one, of the lines `rolledback:` keeps. */
internal class Paged(val runs: List<Folded>, val more: Boolean)

internal fun paged(found: List<Line>, query: LookupQuery): Paged {
    val shown = query.rolledBack?.let { wanted -> found.filter { it.undone == wanted } } ?: found
    val matched = shown.sortedByDescending { it.timestamp }
    val runs = folded(matched, query.all)
    // The read stops before the filter runs, so a cut-short page must say so even when few rows matched.
    val more = runs.size > query.limit * query.page || matched.size > query.wanted
    return Paged(runs.drop(query.limit * (query.page - 1)).take(query.limit), more)
}

internal class ChatRun(val first: ChatLine, var rows: Int, var damage: Float, var oldest: Long)

// The actions /pp chat reads, each as the lines it is.
internal val CHAT_ACTIONS: Map<Action, (ChatLine) -> Boolean> = linkedMapOf(
    Action.PVP to { line -> line.kind.struck },
    Action.BOOK to { line -> line.kind == ChatKind.BOOK },
    Action.GLOBAL to { line -> line.kind == ChatKind.CHAT_GLOBAL },
    Action.LOCAL to { line -> line.kind == ChatKind.CHAT_LOCAL },
)

// The halves of chat, which no plane has rows of: a lookup is not offered them.
private val CHAT_ONLY = setOf(Action.GLOBAL, Action.LOCAL)

/**
 * The groups of `action:` as /pp chat reads them, `,` either and `&` all of a group; empty when it names none.
 * Any other action names nothing in the chat log, and a group holding one never holds.
 */
internal fun chatActionsOf(query: LookupQuery): List<List<(ChatLine) -> Boolean>> =
    query.actions.map { group -> allOf(group).map { CHAT_ACTIONS[Action.of(it.lowercase())] ?: { _: ChatLine -> false } } }

/**
 * What `/pp chat` keeps of the lines it reads. A hit is the victim's line as much as the attacker's: who hit Bob
 * is asked as `user:Bob`, and `user:A&B` is the fight between the two, whoever struck.
 */

internal fun chatFilter(query: LookupQuery, idOf: (String) -> UUID?): (ChatLine) -> Boolean {
    fun named(player: UUID?): (ChatLine) -> Boolean = { player != null && (player == it.player || player == it.hit?.victim) }
    val users = query.users.map { group -> allOf(group).map { named(idOf(it)) } }
    val texts = query.filter?.lowercase()?.let(::allOf)
    // `exclude:` takes players here, and the actions of CHAT_ACTIONS; any other action names nothing in the chat log,
    // as in `action:`, and a group holding one never holds. A player called `Hit` is still the player.
    val excluded = query.excluded.map { group ->
        allOf(group).map { word ->
            val player = idOf(word)
            when {
                player != null -> named(player)
                else -> CHAT_ACTIONS[Action.of(word.lowercase())] ?: { _: ChatLine -> false }
            }
        }
    }
    val kinds = chatActionsOf(query)
    return { line ->
        (users.isEmpty() || users.any { group -> group.all { it(line) } }) &&
            (kinds.isEmpty() || kinds.any { group -> group.all { it(line) } }) &&
            (texts == null || line.text.lowercase().let { text -> texts.all { it in text } }) &&
            excluded.none { group -> group.all { it(line) } }
    }
}

/** The words of a `/pp chat` asking that have to be players: an action among `exclude:`'s may be one or not. */
internal fun chatPlayers(query: LookupQuery): List<String> =
    query.users + query.excluded.flatMap(::allOf).filter { Action.of(it.lowercase()) == null }

// Hit after hit by one player on another with one thing folds like a lookup's run; a hit back breaks it,
// which keeps who answered whom in the order it went.
internal fun chatRuns(lines: List<ChatLine>, all: Boolean): List<ChatRun> {
    val out = ArrayList<ChatRun>()
    for (line in lines) {
        val run = out.lastOrNull()
        val hit = line.hit
        // The run is drawn as its first hit, so one worked out and one seen do not share a line.
        val first = run?.first?.hit
        if (!all && hit != null && first != null && first.victim == hit.victim && first.confidence == hit.confidence && run.first.kind == line.kind &&
            run.first.player == line.player && run.first.text == line.text && run.oldest - line.timestamp <= FOLD_MILLIS
        ) {
            run.rows++
            run.damage += hit.damage
            run.oldest = line.timestamp
        } else {
            out += ChatRun(line, 1, hit?.damage ?: 0f, line.timestamp)
        }
    }
    return out
}

private const val MAX_CHAT_READ = 4096

/** The runs `/pp chat` read for a page and the ones before it; [cut] when the read stopped at its size, not the log's end. */
internal class ChatRead(val runs: List<ChatRun>, val cut: Boolean)

// A one-sided fight folds a whole read into a line or two, so the read goes on until the runs fill this page
// and start the next, or the log runs out. Past the cap a run that long is the last page. `wanted` reads past
// this page, which is how it knows there is a next one.
internal fun readChat(chat: ChatLog, query: LookupQuery, keeps: (ChatLine) -> Boolean): ChatRead {
    // The first read is held to the cap as well: a far page would otherwise read the whole log in one go.
    var reading = query.wanted.coerceIn(1, MAX_CHAT_READ)
    var read = chat.read(query.fromTs(), query.toTs(), reading, keeps)
    var runs = chatRuns(read, query.all)
    while (runs.size <= query.limit * query.page && read.size == reading && reading < MAX_CHAT_READ) {
        reading = minOf(reading * 4, MAX_CHAT_READ)
        read = chat.read(query.fromTs(), query.toTs(), reading, keeps)
        runs = chatRuns(read, query.all)
    }
    return ChatRead(runs, read.size == reading)
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

private fun sign(text: String) = Ui.text(text, when (text) {
    "-" -> Ui.LOST
    "+" -> Ui.GAINED
    else -> Ui.CHANGED
})

private fun times(n: Int): Component = if (n > 1) Ui.text(" ×$n", Ui.MUTED) else Component.empty()

// The parts of a line, two spaces apart, which is what keeps a line readable without columns.
private fun row(vararg parts: Component?): Component =
    Component.join(JoinConfiguration.separator(Component.text("  ")), parts.filterNotNull().filter { it != Component.empty() })

// A break debits the position and credits an item, but only the position end carries coordinates.
// The transaction joins the two at a point read per shown row; a copy of every drop in the position
// index would go stale once the item drifts, merges or is picked up.
internal fun wholeTransactions(ledger: RocksItemLog, entries: List<LedgerEntry>): List<LedgerEntry> {
    val shown = LinkedHashSet(entries)
    // An ordinary movement names both of its ends on the single row the position holds, so its other
    // half would print the same movement a second time, mirrored. What is worth pulling in is the
    // posting no row on screen names at all.
    val mirrors = entries.mapTo(HashSet()) { it.counterparty to it.holder }
    val asked = HashSet<Long>()
    for (entry in entries) {
        if (!asked.add(entry.txId)) continue
        // A break is a handful of postings about one position and belongs on screen together. An
        // explosion is one transaction over a whole crater, and pulling it in would answer a question
        // about this position with thousands of rows about every other one.
        val whole = ledger.transactionEntries(entry, NEIGHBOURLY_TRANSACTION) ?: continue
        whole.filterNotTo(shown) { (it.holder to it.counterparty) in mirrors }
    }
    return shown.toList()
}

/**
 * Runs [task] off the region threads, where RocksDB may be read. A task that dies out there would leave
 * the sender staring at a command that answered nothing, so a failure is logged and told to them.
 */
fun Plugin.offThread(sender: CommandSender, logged: String, told: String, task: () -> Unit) {
    Bukkit.getAsyncScheduler().runNow(this) {
        try {
            Debug.request(task)
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, logged, failure)
            sender.say(told)
        }
    }
}

internal fun chunksAround(x: Int, z: Int, radius: Int): List<Pair<Int, Int>> =
    (((x - radius) shr 4)..((x + radius) shr 4)).flatMap { cx ->
        (((z - radius) shr 4)..((z + radius) shr 4)).map { cz -> cx to cz }
    }

/** The cube of `radius` around where a lookup stands, as both planes read it. */
internal fun LookupTarget.box(radius: Int) = Box(x - radius, x + radius, y - radius, y + radius, z - radius, z + radius)

// A permission is the Bukkit API's, read on the thread that asked rather than in the lookup off it. ⌖ stays
// clickable in any mode: /pp tp itself refuses outside spectator, with its own message.
internal fun mayTeleport(sender: CommandSender) = sender.hasPermission(TELEPORT_PERMISSION)

internal fun canTeleport(sender: CommandSender) = sender is Player && mayTeleport(sender)

internal val DIALOG_KEY = NamespacedKey("pfauprotect", "dialog")

/** Whether [sender] reads pages in a dialog: their own `/pp view`, else the config's. Read on their thread. */
internal fun readsInDialog(sender: CommandSender): Boolean =
    sender is Player && (sender.persistentDataContainer.get(DIALOG_KEY, PersistentDataType.BOOLEAN) ?: Settings.dialogPages)

internal val ROWS_KEY = NamespacedKey("pfauprotect", "rows")

/** How many lines a page of [sender]'s holds when no `limit:` is said: their own `/pp view rows`, else the config's. */
internal fun pageRows(sender: CommandSender): Int =
    (sender as? Player)?.persistentDataContainer?.get(ROWS_KEY, PersistentDataType.INTEGER) ?: Settings.pageRows

/**
 * The query as [sender] asked it, with what is read on their thread rather than off it, where the answer is
 * drawn, and with the moment it was asked. [dialog] false is the inspector's: chat, and the short default
 * page, as it answers every click.
 */
internal fun LookupQuery.askedBy(sender: CommandSender, dialog: Boolean = true): LookupQuery = copy(
    teleports = canTeleport(sender),
    rollbacks = sender is Player && sender.hasPermission(ROLLBACK_PERMISSION),
    dialog = dialog && readsInDialog(sender),
    rows = rows ?: pageRows(sender).takeIf { dialog },
    asOf = asOf ?: System.currentTimeMillis(),
)

// How wide a dialog draws a line, in GUI pixels: a lookup line seldom wraps at it, and it still fits a
// 1080p screen at GUI scale 4, which is 480 wide. The knob to turn if lines wrap or get cut.
private const val DIALOG_WIDTH = 460
private const val BUTTON_WIDTH = 100

/**
 * One page of an answer. A player who reads pages in a dialog gets a window that stays up until closed,
 * where chat would scroll the page away, with the arrows as its buttons. Anyone else gets it line by line
 * in chat between two rules, the arrows in the lower. [back] and [forward] run the pages either side, null where there is none;
 * [place] is the one place the page is about.
 */
internal fun showPage(
    sender: CommandSender,
    query: LookupQuery,
    title: Component,
    lines: List<Component>,
    notes: List<Component>,
    back: String?,
    forward: String?,
    place: Component? = null,
    more: Boolean = forward != null,
) {
    val paged = back != null || forward != null
    if (!query.dialog || sender !is Player) {
        // A rule above and below, so a page stands apart from the chat around it; the place after the rule, where
        // the eye finds it first.
        val top = Component.text().append(Ui.hover(RULE, "PfauProtect")).append(Ui.text(" ")).append(title).append(Ui.text(" ")).append(RULE)
        if (place != null) top.append(Ui.text(" ")).append(place)
        sender.sendMessage(top.build())
        lines.forEach(sender::sendMessage)
        notes.forEach(sender::sendMessage)
        sender.sendMessage(footer(query.page, back, forward, query.teleports, more))
        return
    }
    fun arrow(mark: String, to: Int, command: String?) = command?.let {
        ActionButton.create(Ui.text(mark, Ui.WHO), Ui.text(tr("Page $to", "Стр. $to")), BUTTON_WIDTH, DialogAction.staticAction(ClickEvent.runCommand(it)))
    }
    val arrows = listOfNotNull(arrow("«", query.page - 1, back), arrow("»", query.page + 1, forward))
    val close = ActionButton.create(Ui.text(tr("Close", "Закрыть")), null, BUTTON_WIDTH, null)
    val heading = Component.text().append(Ui.text("PfauProtect · ", Ui.FAINT)).append(title)
    if (paged) heading.append(Ui.text(" · ${tr("page", "стр.")} ${query.page}", Ui.MUTED))
    // A window's title takes no click, so the place opens the body, where it does.
    // One block rather than one per line: each block of a body is set apart by a gap that halves what a screen
    // holds, and the arrows, which come right under the body, would be scrolled out of sight.
    val body = DialogBody.plainMessage(Component.join(JoinConfiguration.newlines(), listOfNotNull(place) + lines + notes), DIALOG_WIDTH)
    val base = DialogBase.builder(heading.build()).body(listOf(body)).build()
    // Escape runs the exit button, so Close is the exit: a confirmation's pinned pair would turn the page on Escape.
    // A multi-action dialog needs a button besides its exit; a single page has nothing else to offer.
    val type = if (arrows.isEmpty()) DialogType.notice(close) else DialogType.multiAction(arrows).exitAction(close).columns(arrows.size).build()
    sender.showDialog(Dialog.create { it.empty().base(base).type(type) })
}

// A mark of its own after the line: a hover and a click put on the whole line show only between its parts, which
// have their own. A window has no chat line to type the rollback into, so there it runs, which is only a preview
// until /pp apply; in chat it is typed out to be looked at first.
private fun undo(event: EventRef, dialog: Boolean): Component {
    val command = "/pp rollback $event"
    return Ui.hover(Ui.text("↶", Ui.MUTED), tr("Roll back this one event: a preview first", "Откатить это событие: сначала предпросмотр"))
        .decoration(TextDecoration.STRIKETHROUGH, false)
        .clickEvent(if (dialog) ClickEvent.runCommand(command) else ClickEvent.suggestCommand(command))
}

private val RULE = Ui.text("-----", Ui.FAINT)

// The rule under a page, with the page in it, the arrows that go somewhere clickable, and the last page said to be
// the last: no count of them is kept, as that would read every row. [more] is whether rows are left past this page,
// which a page with no command for the next one can still have. The console is told the words to type.
private fun footer(page: Int, back: String?, forward: String?, clickable: Boolean, more: Boolean): Component {
    val last = if (more) "" else tr(", the last", ", последняя")
    val said = when {
        clickable && (back != null || forward != null) -> {
            fun arrow(mark: String, to: Int, command: String?) =
                if (command != null) Ui.button(mark, command, Ui.WHO, tr("Page $to", "Стр. $to")) else Ui.text(mark, Ui.FAINT)
            Component.text().append(arrow("«", page - 1, back)).append(Ui.text(" ${tr("page", "стр.")} $page$last ", Ui.MUTED))
                .append(arrow("»", page + 1, forward)).build()
        }
        forward != null -> Ui.text(tr("page $page · older rows: page:${page + 1}", "стр. $page · старые строки: page:${page + 1}"), Ui.MUTED)
        else -> Ui.text("${tr("page", "стр.")} $page$last", Ui.MUTED)
    }
    return Component.text().append(RULE).append(Ui.text(" ")).append(said).append(Ui.text(" ")).append(RULE).build()
}

internal fun offlineName(uuid: UUID): String? = Bukkit.getOfflinePlayer(uuid).name

class Lookups(
    private val plugin: Plugin,
    private val ledger: RocksItemLog,
    private val blocks: BlockLogs,
    private val codec: ItemFormCodec,
    idOfName: (String) -> UUID? = { name ->
        Bukkit.getPlayerExact(name)?.uniqueId ?: Bukkit.getOfflinePlayerIfCached(name)?.uniqueId
    },
    private val nameOf: (UUID) -> String? = ::offlineName,
) {
    // A player is named by their uuid as well, which is how the API's callers know them.
    // Only a uuid this server knows a player by, as a name has to be: a mistyped one must not read as "nothing recorded".
    private val idOf: (String) -> UUID? = { word -> uuidOrNull(word)?.takeIf { knownName(it) != null } ?: idOfName(word) }

    /** [dialog] false keeps the answer in chat whatever the reader chose: the inspector's, which a window on every click would get in the way of. */
    fun run(sender: CommandSender, target: LookupTarget, query: LookupQuery, dialog: Boolean = true) {
        val asked = query.askedBy(sender, dialog)
        val kind = when {
            query.players.isNotEmpty() -> "player"
            query.global -> "user"
            query.event != null -> "event"
            query.radius != null -> "radius"
            else -> "position"
        }
        plugin.offThread(sender, "lookup at ${target.label} failed", "The lookup failed; the server log has the details.") {
            Metrics.lookups.labelValues(kind).startTimer().use { report(sender, target, asked) }
        }
    }

    /**
     * One entity's own story, for the inspector's click on it: what went in and out of its slots — a
     * frame's item, a donkey's chest, a stand's armour — and its rows in the chunks around where it stands.
     */
    fun entity(sender: CommandSender, entity: UUID, type: String, at: LookupTarget) {
        val short = entity.toString().take(8)
        val label = "$type $short"
        val query = LookupQuery(teleports = canTeleport(sender), rollbacks = sender is Player && sender.hasPermission(ROLLBACK_PERMISSION))
        plugin.offThread(sender, "lookup of $label failed", "The lookup failed; the server log has the details.") {
            val slots = ledger.holderPage(EntitySlot(entity, 0), 0, Long.MAX_VALUE, reverse = true, limit = MAX_FETCH)
            val items = entryLines(wholeTransactions(ledger, slots.entries))
            val log = blocks.get(at.world)
            // The chunk it stands in and the eight around it.
            val rows = if (log == null) emptyList() else chunksAround(at.x, at.z, 16).flatMap { (cx, cz) ->
                log.entitiesInChunk(cx, cz, 0, Long.MAX_VALUE, MAX_ENTITY_WALK) { _, _, _ -> true }.rows.filter { it.uuid == entity }
            }
            val lines = items + markedEntities(rows).map { (row, back) -> lineOf(at.world, row, back) }
            val where = Component.text().append(Ui.entity(type)).append(Ui.text(" $short", Ui.MUTED)).build()
            answer(sender, lines, slots.complete, where, query, at, pages = false)
        }
    }

    internal fun report(sender: CommandSender, target: LookupTarget, query: LookupQuery) {
        Debug.log { "lookup by ${sender.name} at ${target.label}: $query, window ${query.fromTs()}..${query.toTs()}" }
        val found = try {
            find(target, query)
        } catch (refused: PfauProtectException) {
            return sender.say(refused.message!!)
        }
        found.note?.let(sender::say)
        answer(sender, found.lines, found.complete, found.where, query, target)
    }

    /** What a lookup finds, before it is drawn; a question it cannot answer throws [PfauProtectException]. */
    internal fun find(target: LookupTarget, query: LookupQuery): Found {
        if (query.players.isNotEmpty()) return findPlayers(query)
        val users = idsOf(query.users)
        if (query.global) {
            if (users.isEmpty()) refuse("A world-wide lookup needs a player, user:<name>, or a radius.")
            return findEverywhere(target.world, query, users) ?: refuse("This world's block log is not open.")
        }
        val steps = Steps(BOX_STEPS)
        val page = read(target, query, steps)
        val items = itemLines(page.entries, query)
        // The two planes are read apart and shown together: a position that was placed, blown up and
        // flowed over has a row in each, and read as two lists the order they happened in is lost.
        val (changes, planesComplete) = blockLines(target, query, steps)
        Debug.log {
            "  ${page.entries.size} postings (complete ${page.complete}), ${items.size} item and ${changes.size} block lines " +
                "(complete $planesComplete), steps ${steps.used} of $BOX_STEPS${if (steps.ranOut) " (ran out)" else ""}"
        }
        // The coordinates come after, set apart by the heading.
        val place = when {
            query.radius != null -> Ui.text(tr("radius ${query.radius} around", "радиус ${query.radius} вокруг"))
            target.block != null -> Ui.block(target.block)
            else -> Ui.text(tr("place", "место"))
        }
        val where = if (query.radius == null && query.part == Part.CONTENTS) {
            Component.text().append(place).append(Ui.text(tr(", contents", ", содержимое"), Ui.MUTED)).build()
        } else {
            place
        }
        val note = "This world's block log is not open.".takeIf { blocks.get(target.world) == null }
        return Found(items + changes, page.complete && planesComplete && !steps.ranOut, where, note)
    }

    /**
     * What the named players did in every world whose log is open, as [findEverywhere] reads one. An item row with
     * no position is in each world's answer, and kept once.
     */
    internal fun findAllWorlds(query: LookupQuery): Found {
        val users = idsOf(query.users)
        if (users.isEmpty()) refuse("A world-wide lookup needs a player, user:<name>, or a radius.")
        // A world closed between the list and its read is a world not read, which a whole answer cannot claim.
        val worlds = blocks.worlds.toList()
        val found = worlds.mapNotNull { findEverywhere(it, query, users) }
        val lines = found.flatMap { it.lines }.distinctBy { line -> (line.source as? LedgerEntry)?.ref ?: line }
        val complete = found.isNotEmpty() && found.size == worlds.size && found.all { it.complete }
        return Found(lines, complete, Ui.text(tr("everything by ${query.users.joinToString(", ")}", "всё от ${query.users.joinToString(", ")}")))
    }

    /**
     * Everything the named players did in this world, newest first: their own block and entity rows by the
     * index of rows that name them, the containers they traded with by their own slots, and only as many
     * of either as the page needs. The rest of each place's history is read only to see what a rollback
     * has since given back. Item rows with no position (eating, crafting, rows from before drops had a spot)
     * have no world, so they show in the answer of each world.
     */
    private fun findEverywhere(world: UUID, query: LookupQuery, users: Set<UUID>): Found? {
        val log = blocks.get(world) ?: return null
        val fromTs = query.fromTs()
        val toTs = query.toTs()
        val keeps = rowFilter(query)
        // One row past the page, which is how the answer knows there is a next one.
        val wanted = query.wanted + 1
        val own = users.map { log.byActor(it, fromTs, toTs, wanted, GLOBAL_WALK, keeps::keeps, keeps::keeps) }
        val rows = own.flatMap { it.blocks }
        val entities = own.flatMap { it.entities }
        // Each place once, from the oldest of their rows there, and only the places of the rows this page can
        // show: the earlier pages' rows are read for the page to start where they stopped, and following each
        // up is a seek per plane. Pages that folded long runs end deeper than this guess, and the rows that
        // pushed past it go without their mark.
        fun <T> placesOf(found: List<T>, at: (T) -> Triple<Int, Int, Int>, ts: (T) -> Long, of: (T) -> Boolean = { true }) =
            found.sortedByDescending(ts).drop(query.limit * (query.page - 1)).take(query.limit * GROUPED_READ)
                .filter(of).groupBy(at).mapValues { (_, here) -> here.minOf(ts) }
        val places = placesOf(rows, { Triple(it.x, it.y, it.z) }, { it.timestamp })
        val entityPlaces = placesOf(entities, { Triple(it.x, it.y, it.z) }, { it.timestamp })
        // A rollback row names whoever ran it, so the index of the one rolled back never leads to it.
        val undoes = places.flatMap { (at, ts) ->
            log.at(at.first, at.second, at.third, ts, toTs, limit = LATER_READ, reverse = true).filter { it.cause == Cause.ROLLBACK }
        }
        val entityUndoes = entityPlaces.flatMap { (at, ts) ->
            log.entitiesAt(at.first, at.second, at.third, ts, toTs, MAX_ENTITY_WALK).rows.filter { it.cause == Cause.ROLLBACK }
        }
        // What they put in or took out names them on their own slots; what fell out of a container they broke
        // names them only as its actor, on the container, so the places of their rows are read too.
        // Drops, pickups and eating have no position at all, so they are kept when they left a player's own
        // slots, as CoreProtect shows them; a click between two of one player's own slots is not that.
        fun own(holder: Holder) = (holder as? PlayerHolder)?.uuid ?: (holder as? EntitySlot)?.uuid?.takeIf { it in users }
        val traded = holderRows(users.flatMap { ownHolders(it) }, fromTs, toTs, query).entries.filter {
            // A drop or pickup from this version on says where it happened; the world decides, as for a block.
            val at = it.spot ?: it.counterparty.block
            if (at != null) at.world == world else own(it.counterparty).let { mine -> mine == null || mine != own(it.holder) }
        }
        // A block put down drops nothing, so only the places they changed otherwise are read for what fell out.
        val broken = placesOf(rows, { Triple(it.x, it.y, it.z) }, { it.timestamp }) { it.cause != Cause.BLK_PLAYER_PLACE }
        val dropped = broken.flatMap { (at, ts) ->
            holdersAt(world, at.first, at.second, at.third).flatMap { ledger.holderPage(it, ts, toTs, reverse = true, limit = LATER_READ).entries }
        }
        val lines = itemLines((traded + dropped).distinctBy { it.ref }, query) +
            planeLines(world, (rows + undoes).distinct(), (entities + entityUndoes).distinct(), keeps)
        val who = query.users.joinToString(", ")
        return Found(lines, own.all { it.complete }, Ui.text(tr("everything by $who", "всё от $who")))
    }

    /**
     * What went through a player's own hands: their inventory, equipment, cursor, ender chest and the
     * crafting grid, which the server books to the player as an entity. None of it has a position, so
     * no radius reaches it, and the block plane has nothing to say about it.
     */
    private fun findPlayers(query: LookupQuery): Found {
        // Answering anyway would read as narrowed to the area while nothing was narrowed.
        if (query.radius != null) refuse("player: reads what went through a player's hands, which has no position; drop the radius.")
        val players = idsOf(query.players)
        idsOf(query.users)
        val holders = players.flatMap { ownHolders(it) }
        val page = holderRows(holders, query.fromTs(), query.toTs(), query)
        // `p:A&B` is what went between the two of them, the one's slots at one end and the other's at the other.
        // The crafting grid is booked to its player as their entity slot.
        fun owner(holder: Holder) = playerOf(holder) ?: (holder as? EntitySlot)?.uuid
        val groups = groupsOf(query.players)
        val between = page.entries.filter { entry ->
            groups.any { group -> group.all { it == owner(entry.holder) || it == owner(entry.counterparty) } }
        }
        // Their deaths are no slot's business: each world's log files them under the victim.
        val fromTs = query.fromTs()
        val toTs = query.toTs()
        val keeps = rowFilter(query)
        var complete = page.complete
        // Walking a player's whole index per world for `a:pickup` would find nothing and could only mark the
        // answer cut short. A kill is filed under its culprit's cause, an explosion or a fire too, so any cause
        // of the entity or block plane may name a death; `a:-entity` names deaths by kind with no cause.
        val wantsDeaths = query.causes?.any { it in ENTITY_PLANE_CAUSES || it in BLOCK_CAUSES } ?: true ||
            query.kinds.any { it == EntityKind.PLAYER_DIED || it == EntityKind.TOTEM_POPPED }
        val deaths = if (!wantsDeaths) emptyList() else blocks.worlds.flatMap { world ->
            val log = blocks.get(world) ?: return@flatMap emptyList()
            players.flatMap { victim ->
                val found = log.byActor(victim, fromTs, toTs, query.wanted + 1, GLOBAL_WALK, { false }) {
                    (it.kind == EntityKind.PLAYER_DIED || it.kind == EntityKind.TOTEM_POPPED) && it.uuid == victim && keeps.keeps(it)
                }
                complete = complete && found.complete
                planeLines(world, emptyList(), found.entities, keeps)
            }
        }
        val items = itemLines(between, query) + deaths
        val who = query.players.joinToString(", ")
        return Found(items, complete, Ui.text(tr("what $who carried", "вещи $who")))
    }

    internal fun resolveAll(sender: CommandSender, names: List<String>): Set<UUID>? = try {
        idsOf(names)
    } catch (refused: PfauProtectException) {
        sender.say(refused.message!!)
        null
    }

    // Every name has to be known: dropping the one that was misspelt would answer about the rest as
    // if it were the whole question.
    internal fun idsOf(names: List<String>): Set<UUID> {
        val named = names.flatMap(::allOf).associateWith { idOf(it) }
        val unknown = named.filterValues { it == null }.keys
        if (unknown.isNotEmpty()) refuse("Unknown player: ${unknown.joinToString(", ")}")
        return named.values.filterNotNull().toSet()
    }

    // A read for a page is several times the page, and pulling in each row's other half is a read of its
    // own, so only the rows the page can reach get theirs: one past it, which is how it knows there is a
    // next one. A count is of everything read.
    private fun itemLines(entries: List<LedgerEntry>, query: LookupQuery): List<Line> {
        val kept = filter(entries, query)
        val reachable = if (query.count) kept else kept.sortedByDescending { it.timestamp }.take(query.wanted + 1)
        return entryLines(wholeTransactions(ledger, reachable))
    }

    // Item rows marked when a rollback has since given them back.
    private fun entryLines(entries: List<LedgerEntry>): List<Line> {
        val given = ledger.compensated(entries.map { it.ref }).keys
        return entries.map { entry ->
            // A slot posting names its player as a holder rather than as its actor.
            val who = entry.actor ?: playerOf(entry.holder) ?: playerOf(entry.counterparty)
            val same = "i ${entry.cause} $who ${entry.itemFormId} ${entry.qty > 0} ${slotless(entry.holder)} ${slotless(entry.counterparty)}"
            val places = listOfNotNull(entry.holder.block, entry.counterparty.block, entry.spot)
            Line(entry.timestamp, entry.cause, who, same, abs(entry.qty), entry.ref in given, null, places, entry) { amount, _ ->
                draw(entry, who, amount)
            }
        }
    }

    private fun lineOf(world: UUID, row: BlockRow, back: Boolean): Line {
        val change = blockChange(row)
        val same = "b ${row.cause} ${row.actor} ${row.confidence} ${change.sign} ${change.from} ${change.to}"
        return Line(row.timestamp, row.cause, row.actor, same, 1, back, EventRef(row.eventId, row.x, row.y, row.z, row.timestamp), listOf(WorldBlock(world, row.x, row.y, row.z)), row) { _, rows ->
            draw(row, change, rows)
        }
    }

    private fun lineOf(world: UUID, row: EntityRow, back: Boolean): Line {
        val same = "e ${row.cause} ${row.actor} ${row.confidence} ${row.kind} ${row.type}"
        return Line(row.timestamp, row.cause, row.actor, same, 1, back, EventRef(row.eventId, row.x, row.y, row.z, row.timestamp), listOf(WorldBlock(world, row.x, row.y, row.z)), row) { _, rows ->
            draw(row, rows)
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
        // A page about a place gives its coordinates in the heading, apart, in their own colour and the way there.
        // One read world-wide, or an entity's story around where it stands, only names its world; a player's hands
        // are read in every world and name none.
        val place = if (query.global || query.players.isNotEmpty() || !pages) null
        else Ui.coordinates(target.world, target.x, target.y, target.z, query.teleports)
        val title = if (place != null || query.players.isNotEmpty()) where else where.append(Ui.text(" · ${Ui.worldName(target.world)}", Ui.MUTED))
        val placed = place?.let { title.append(Ui.text(" ")).append(it) } ?: title
        if (query.count) return summary(sender, query.rolledBack?.let { wanted -> found.filter { it.undone == wanted } } ?: found, complete, placed)
        val page = paged(found, query)
        val lines = page.runs
        // Only a limit the parser takes is worth suggesting; at the top already, only a smaller area helps.
        val wider = minOf(query.limit * 4, MAX_LIMIT)
        val stopped = tr(
            "the read stopped before the whole area was seen; narrow the radius",
            "чтение остановилось раньше, чем увидело всю область; сузь радиус",
        ) + if (wider == query.limit) "" else tr(" or ask for more with limit:$wider", " или запроси больше через limit:$wider")
        if (lines.isEmpty()) {
            val why = if (complete) tr("nothing recorded", "ничего не записано") else tr("nothing matched, but ", "ничего не найдено, но ") + stopped
            sender.sendMessage(Component.text().append(Ui.text("PfauProtect · ", Ui.FAINT)).append(placed).append(Ui.text(" — $why", Ui.MUTED)).build())
            return
        }
        val clickable = query.teleports
        // Every line of a lookup of one place happened there, which the heading already says; a line's own mark is
        // left only where it happened somewhere else, as the other end of a movement can.
        val here = WorldBlock(target.world, target.x, target.y, target.z).takeIf { place != null && (query.radius ?: 0) == 0 }
        val drawn = lines.map { run ->
            val line = run.first
            var out = line.draw(run.amount, run.rows)
            val event = line.event?.takeIf { run.rows == 1 }
            // Struck through for the eye, said in words for whoever cannot see the line drawn.
            val back = if (line.undone) arrayOf(tr("rolled back", "откачено")) else emptyArray()
            if (line.undone) out = if (clickable) out.decorate(TextDecoration.STRIKETHROUGH) else out.append(Ui.text("  (${back[0]})", Ui.FAINT))
            out = when {
                run.rows > 1 -> Ui.hover(out, *back, tr("${run.rows} in a row; #all shows each", "${run.rows} подряд; #all покажет каждое"))
                line.undone && clickable -> Ui.hover(out, *back)
                else -> out
            }
            // A player who may roll back clicks a single event's ↶ for it; the console cannot click and is given
            // the event to type. A folded line is many events and has no one to give.
            val mark = when {
                event == null -> null
                query.rollbacks -> undo(event, query.dialog)
                !clickable -> Ui.text("$event", Ui.FAINT)
                else -> null
            }
            if (mark != null) out = out.append(Ui.text("  ")).append(mark)
            // First on the line, where it is found in the same place on every line and a wrap cannot leave it alone
            // on a line of its own; and outside the strike and the hover, which are the row's.
            val spot = line.places.firstOrNull { it != here }?.let { Ui.place(it.world, it.x, it.y, it.z, clickable) }
            val start = Component.text().append(Ui.text(" "))
            if (spot != null) start.append(spot).append(Ui.text(" "))
            start.append(out).build()
        }
        val more = page.more
        val notes = when {
            // An entity's own story has no command to ask for its next page by; it can only say there is more.
            !pages -> if (more || !complete) listOf(Ui.text(tr("  … older rows are cut off", "  … старые строки обрезаны"), Ui.MUTED)) else emptyList()
            // The next page starts past every row matched so far and would be empty: only a narrower or a
            // bigger read sees what the cut hid.
            !more && !complete -> listOf(Ui.text("  … $stopped", Ui.MUTED))
            else -> emptyList()
        }
        showPage(
            sender, query, title, drawn, notes,
            back = query.pageCommand(target, query.page - 1).takeIf { pages && query.page > 1 },
            forward = query.pageCommand(target, query.page + 1).takeIf { pages && more },
            place = place,
            more = more || !complete,
        )
    }

    // How many rows of each cause and player, the most first: where a griefer was busiest, before the rows.
    private fun summary(sender: CommandSender, found: List<Line>, complete: Boolean, where: Component) {
        if (found.isEmpty()) {
            // A read cut short may have stopped before the rows asked for; "nothing" would clear whoever they name.
            val why = if (complete) tr("nothing recorded", "ничего не записано")
            else tr("nothing matched, but the read stopped early", "ничего не найдено, но чтение остановилось раньше")
            sender.sendMessage(Component.text().append(where).append(Ui.text(" — $why", Ui.MUTED)).build())
            return
        }
        sender.sendMessage(Component.text().append(Ui.text(tr("Count of ${found.size} rows: ", "Сводка, строк ${found.size}: "), Ui.FAINT)).append(where).build())
        for ((key, n) in found.groupingBy { it.cause to it.who }.eachCount().entries.sortedByDescending { it.value }) {
            sender.sendMessage(row(Ui.text("  $n", Ui.MUTED), Ui.hover(verb(key.first), key.first.name.lowercase()), who(key.second, Confidence.FACT)))
        }
        if (!complete) sender.sendMessage(Ui.text(tr("  … the read stopped early; these are counts of what it saw", "  … чтение остановилось раньше; это сводка прочитанного"), Ui.MUTED))
    }

    /**
     * Block and entity plane lines, and whether the read saw all of its rows: one place's stops at a count before the
     * filter runs, an area's once its page is full or [steps] run out, and a page cut there is not the last. Empty when
     * the world's log is not open, which report() tells the reader.
     */
    private fun blockLines(target: LookupTarget, query: LookupQuery, steps: Steps): Pair<List<Line>, Boolean> {
        val log = blocks.get(target.world) ?: return emptyList<Line>() to true
        val fromTs = query.fromTs()
        val toTs = query.toTs()
        val radius = query.radius
        // What a container holds is the item plane alone; the block and entity planes are about the block.
        if (radius == null && query.part == Part.CONTENTS) return emptyList<Line>() to true
        val keeps = rowFilter(query)
        val rows: List<BlockRow>
        val entities: List<EntityRow>
        var complete = true
        if (radius == null) {
            // One row past the read, which is how it knows whether it saw them all.
            val read = log.at(target.x, target.y, target.z, fromTs, toTs, limit = query.fetch + 1, reverse = true)
            rows = read.take(query.fetch)
            val window = log.entitiesAt(target.x, target.y, target.z, fromTs, toTs, MAX_ENTITY_WALK)
            entities = window.rows
            complete = read.size <= query.fetch && window.complete
        } else {
            val found = log.newestInBox(target.box(radius), fromTs, toTs, boxWanted(query), steps, keeps::keeps, keeps::keeps)
            rows = found.blocks
            entities = found.entities
            complete = found.complete
        }
        // Not cut to the page: runs fold only afterwards, and a cut before them shifts every later page.
        return planeLines(target.world, rows, entities, keeps) to complete
    }

    private fun planeLines(world: UUID, rows: List<BlockRow>, entities: List<EntityRow>, keeps: RowFilter): List<Line> =
        marked(rows).filter { keeps.keeps(it.first) }.map { (row, back) -> lineOf(world, row, back) } +
            markedEntities(entities).filter { keeps.keeps(it.first) }.map { (row, back) -> lineOf(world, row, back) }

    /**
     * Each row with a mark when a rollback since put back what it took: a later rollback row at its position
     * that returned the state it replaced. Read among the rows at hand, so a rollback outside the window is
     * not seen; and before the filter, which under action: would have dropped the rollback rows.
     */
    private fun marked(rows: List<BlockRow>): List<Pair<BlockRow, Boolean>> {
        val rollbacks = rows.filter { it.cause == Cause.ROLLBACK }.groupBy { it.spot }
        return rows.map { row ->
            val undone = row.cause != Cause.ROLLBACK && rollbacks[row.spot].orEmpty().any {
                it.timestamp >= row.timestamp && it.stateAfter == row.stateBefore
            }
            row to undone
        }
    }

    private fun markedEntities(rows: List<EntityRow>): List<Pair<EntityRow, Boolean>> {
        val rollbacks = rows.filter { it.cause == Cause.ROLLBACK }.groupBy { it.uuid }
        return rows.map { row ->
            val undone = row.cause != Cause.ROLLBACK && rollbacks[row.uuid].orEmpty().any { it.timestamp >= row.timestamp }
            row to undone
        }
    }

    private fun read(target: LookupTarget, query: LookupQuery, steps: Steps): EntryPage {
        val fromTs = query.fromTs()
        val toTs = query.toTs()
        val radius = query.radius ?: return holderRows(holdersAt(target.world, target.x, target.y, target.z, query.part), fromTs, toTs, query)
        val keeps = rowFilter(query)
        return ledger.boxPage(target.world, target.box(radius), fromTs, toTs, boxWanted(query), steps, keeps::keeps)
    }

    // The rows a radius reads, kept by the filter: one past the page, which is how it knows there is a next
    // one; a count is of everything the read reaches.
    private fun boxWanted(query: LookupQuery) = if (query.count) query.fetch else query.wanted + 1

    private fun holdersAt(world: UUID, x: Int, y: Int, z: Int, part: Part = Part.ALL): List<Holder> = listOfNotNull(
        Container(world, x, y, z, 0).takeIf { part != Part.BLOCK },
        WorldBlock(world, x, y, z).takeIf { part != Part.CONTENTS },
    )

    private fun holderRows(holders: List<Holder>, fromTs: Long, toTs: Long, query: LookupQuery): EntryPage {
        val pages = holders.map { ledger.holderPage(it, fromTs, toTs, reverse = true, limit = query.fetch) }
        return EntryPage(pages.flatMap { it.entries }, pages.all { it.complete })
    }

    private fun filter(entries: List<LedgerEntry>, query: LookupQuery): List<LedgerEntry> {
        val keeps = rowFilter(query)
        val placers = HashMap<Container, Placer>()
        return entries.filter { keeps.keeps(it) && (!query.steal || stolen(it, placers)) }
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
        val taker = entry.actor ?: playerOf(entry.counterparty) ?: return false
        return taker != placer.player
    }

    // `exclude:` takes items and players in one list, so a name that is a player is a player excluded.
    internal fun rowFilter(query: LookupQuery, rollback: Boolean = false) = RowFilter(
        ledger, query, groupsOf(query.users),
        query.excluded.flatMap(::allOf).mapNotNull { word -> idOf(word)?.let { word.lowercase() to it } }.toMap(),
        rollback,
        isPlayer = ConcurrentHashMap<UUID, Boolean>().let { seen -> { uuid -> seen.computeIfAbsent(uuid) { knownName(it) != null } } },
    )

    internal fun chatFilter(query: LookupQuery): (ChatLine) -> Boolean = chatFilter(query, idOf)

    /**
     * Players' deaths in every world, with their world, newest first, for the groups of `user:`: a death names its
     * victim and whoever killed them, and `A&B` is a death of the one at the hands of the other. A death is filed
     * under both, so each player's index finds it.
     */
    internal fun deaths(query: LookupQuery): Pair<List<Pair<UUID, EntityRow>>, Boolean> {
        val groups = groupsOf(query.users)
        val players = groups.flatten().toSet()
        var complete = true
        val found = blocks.worlds.flatMap { world ->
            val log = blocks.get(world) ?: return@flatMap emptyList()
            players.flatMap { player ->
                val read = log.byActor(player, query.fromTs(), query.toTs(), query.wanted + 1, GLOBAL_WALK, { false }) { row ->
                    row.kind == EntityKind.PLAYER_DIED && groups.any { group -> group.all { it == row.uuid || it == row.actor } }
                }
                complete = complete && read.complete
                read.entities.map { world to it }
            }
        }
        return found.distinctBy { (_, row) -> row.eventId to row.uuid }.sortedByDescending { it.second.timestamp } to complete
    }

    internal fun nameOfPlayer(uuid: UUID): String? = knownName(uuid)

    // The players of each `&` group; resolveAll has made sure by now that every name is known.
    private fun groupsOf(names: List<String>): List<Set<UUID>> = names.map { group -> allOf(group).mapNotNullTo(HashSet(), idOf) }

    /** Who a row is on, and how sure: a name seen, a name worked out, a witness, or nobody. */
    private fun who(actor: UUID?, confidence: Confidence, away: String? = null): Component = when {
        actor == null -> Ui.text(tr("nobody", "никто"), Ui.FAINT)
        actor == LIGHTNING ->
            Ui.hover(Ui.text(tr("lightning", "молния"), Ui.MUTED), tr("weather: a natural strike, no player", "гроза: природный удар, игрок ни при чём"))
        confidence == Confidence.INFERRED ->
            Ui.hover(Ui.text("${playerName(actor)}?", Ui.WHO), tr("worked out, not seen", "вычислено, не видели напрямую"))
        confidence == Confidence.NEARBY -> Ui.hover(
            Ui.text("(${playerName(actor)})", Ui.MUTED),
            tr("only nearby", "был только рядом") + away?.let { tr(", $it blocks away", ", в $it блоках") }.orEmpty(),
        )
        confidence == Confidence.AGGRO -> Ui.hover(
            Ui.text("${playerName(actor)}*", Ui.WHO),
            tr("a creeper went off at them; they did not light it", "крипер взорвался на нём, сам не поджигал"),
        )
        else -> Ui.text(playerName(actor), Ui.WHO)
    }

    // The cause's own name waits on the hover: what config.yml and action: filters are written in. [worded] is
    // the cause whose words the line shows, when they say it better than the cause's own.
    private fun act(sign: String, cause: Cause, vararg more: String, worded: Cause = cause): Component = Ui.hover(
        Component.text().append(sign(sign)).append(Component.text(" ")).append(verb(worded)).build(),
        cause.name.lowercase(), *more,
    )

    private fun draw(entry: LedgerEntry, who: UUID?, amount: Int): Component {
        val gained = entry.qty > 0
        // Both halves of a mutation face the Void, so without this they read as an item destroyed and
        // an unrelated item created at the same instant, which is the very thing they exist to deny.
        val inPlace = if (entry.kind == Kind.MUTATE) arrayOf(tr("changed in place", "изменён на месте")) else emptyArray()
        val (from, to) = if (gained) entry.counterparty to entry.holder else entry.holder to entry.counterparty
        // Between a player's hands and a container, whichever click did it, the line says only which way, as
        // CoreProtect's added and removed do: the player is already named before it and the container by its
        // place, and the click waits on the hover. Anything else keeps both of its ends.
        val boxed = entry.cause in BOXED && (to is Container && hands(from) || from is Container && hands(to))
        val worded = when {
            !boxed -> entry.cause
            to is Container -> Cause.CONTAINER_ADD
            else -> Cause.CONTAINER_REMOVE
        }
        val act = act(if (gained) "+" else "-", entry.cause, *inPlace, worded = worded)
        val flow = if (boxed) null else Component.text().append(holder(from)).append(Ui.text(" → ", Ui.FAINT)).append(holder(to)).build()
        return row(Ui.ago(entry.timestamp), who(who, entry.confidence), act, item(entry.itemFormId, amount), flow)
    }

    private fun hands(holder: Holder) = holder is PlayerInv || holder is PlayerEquip || holder is PlayerCursor

    /** [to] is set only when a block turned into another one, rather than appearing, going or changing state. */
    private class BlockChange(val sign: String, val from: String, val to: String? = null)

    private fun blockChange(row: BlockRow): BlockChange {
        val before = stateName(ledger, row.stateBefore)
        val after = stateName(ledger, row.stateAfter)
        return when {
            before in AIRS && after !in AIRS -> BlockChange("+", after)
            after in AIRS && before !in AIRS -> BlockChange("-", before)
            else -> BlockChange("~", before, after.takeIf { it != before })
        }
    }

    private fun draw(row: BlockRow, change: BlockChange, rows: Int): Component {
        val thing = Component.text().color(Ui.THING).append(Ui.block(change.from))
        if (change.to != null) thing.append(Ui.text(" → ", Ui.FAINT)).append(Ui.block(change.to))
        thing.append(times(rows))
        // The whole state is what makes a row restorable and is noise in a list, so it waits for a hover,
        // with which half of a door or a bed it was: two rows of one are otherwise the same row twice.
        val details = listOfNotNull(
            "${stateOf(ledger, row.stateBefore)} → ${stateOf(ledger, row.stateAfter)}",
            tr("the other half", "вторая половина").takeIf { row.alongside },
            tr("with contents", "с содержимым").takeIf { (row.payloadBefore != null || row.payloadAfter != null) && signOf(row) == null },
        )
        val pressed = if (row.cause == Cause.BLK_ENTITY_SWITCH) row.payloadAfter?.let(::pressedBy) else null
        val extra = when {
            pressed != null -> Component.text().color(Ui.MUTED).append(Ui.text("← ")).append(Ui.any(pressed.first)).build()
            else -> signOf(row)?.let { Ui.text(it, Ui.MUTED) }
        }
        return row(
            Ui.ago(row.timestamp), who(row.actor, row.confidence, pressed?.second), act(change.sign, row.cause),
            opens(Ui.hover(thing.build(), *details.toTypedArray()), row.payloadBefore, row.payloadAfter, change.sign == "-", rows), extra,
        )
    }

    /**
     * The name of a container a click on which shows what was inside: what it held as it went for a
     * line of its going, what it held after for any other, and the other side when that one held nothing
     * (a filled chest swapped for a sign). The hover keeps its lines and gains one. A folded ×N line stands
     * for N containers and would show one of them as if it were all, so, like the rollback mark, it waits
     * for #all.
     */
    private fun opens(subject: Component, before: Long?, after: Long?, gone: Boolean, rows: Int): Component {
        if (rows > 1) return subject
        val payload = (if (gone) listOf(before, after) else listOf(after, before)).filterNotNull()
            .firstOrNull { ledger.payload(it)?.let(::holdsItems) == true } ?: return subject
        val hover = subject.hoverEvent()?.value() as? Component ?: Component.empty()
        val hint = Ui.text(tr("Click: what was inside", "Клик: что было внутри"), Ui.MUTED)
        return subject.decorate(TextDecoration.UNDERLINED)
            .hoverEvent(HoverEvent.showText(Component.text().append(hover).append(Component.newline()).append(hint)))
            .clickEvent(ClickEvent.runCommand("/pp contents $payload"))
    }

    private fun draw(row: EntityRow, rows: Int): Component {
        val sign = when (row.kind) {
            EntityKind.CREATED -> "+"
            EntityKind.CHANGED, EntityKind.MOVED -> "~"
            else -> "-"
        }
        val subject = when (row.kind) {
            EntityKind.PLAYER_DIED, EntityKind.TOTEM_POPPED -> Ui.text(playerName(row.uuid), Ui.WHO)
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
                EntityKind.TOTEM_POPPED -> tr("a totem took the death", "тотем спас от смерти")
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
            opens(Ui.hover(subject, *details.toTypedArray()), row.payloadBefore, row.payloadAfter, sign == "-", rows), via,
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
        val tag = runCatching { nbtOf(bytes) }.getOrNull() ?: return null
        val sides = listOf("front_text", "back_text").mapNotNull { side ->
            val text = tag.get(side) ?: return@mapNotNull null
            SignText.CODEC.parse(NbtOps.INSTANCE, text).result().getOrNull()
                ?.getMessages(false)?.map { it.string }?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }?.joinToString(" | ", "\"", "\"")
        }
        return sides.takeIf { it.isNotEmpty() }?.joinToString(" / ")
    }

    private fun menuName(id: Int): String =
        ledger.registries.keyOf(RegistryNamespace.MENU_TYPE, id)?.lowercase() ?: "type $id"

    /** Type plus custom name: a named shulker box and a bare one are otherwise the same row. */
    private fun item(itemFormId: Long, amount: Int): Component {
        val key = itemKey(ledger, itemFormId) ?: return Ui.text("item form $itemFormId", Ui.THING)
        val out = Component.text().color(Ui.THING).append(Ui.item(key))
        customName(itemFormId)?.let { out.append(Ui.text(" \"$it\"")) }
        out.append(times(amount))
        return out.build().hoverEvent(runCatching { HoverEvent.showItem(Key.key(key), amount.coerceIn(1, 99)) }.getOrNull())
    }

    private fun customName(itemFormId: Long): String? {
        val form = ledger.form(itemFormId) ?: return null
        // A form that will not decode still names its type, which is worth more than an error.
        return runCatching { codec.decode(form, 1, null).get(DataComponents.CUSTOM_NAME)?.string }.getOrNull()
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
        is EntitySlot -> knownName(holder.uuid)?.let { Ui.hover(Ui.text(it, Ui.WHO), tr("crafting grid, slot ${holder.slot}", "сетка крафта, слот ${holder.slot}")) }
            ?: Ui.hover(Ui.text(tr("entity", "сущность"), Ui.MUTED), "${holder.uuid}", tr("slot ${holder.slot}", "слот ${holder.slot}"))
        is ItemEntityRef -> Ui.hover(Ui.text(tr("on the ground", "на земле"), Ui.MUTED), "${holder.uuid}")
        is Nested -> Ui.hover(Ui.text(tr("inside", "внутри"), Ui.MUTED), "${holder.ownerId} #${holder.index}")
        Void -> Ui.hover(Ui.text("∅", Ui.FAINT), tr("nowhere: made or used up here", "нигде: появилось или израсходовано"))
    }

    /** A page of [found] as the API's rows, folded and cut as a player's page is. */
    internal fun pageOf(found: Found, query: LookupQuery): LookupPage {
        val page = paged(found.lines, query)
        return LookupPage(page.runs.map(::rowOf), query.page, page.more, found.complete, listOfNotNull(found.note))
    }

    private fun rowOf(run: Folded): LookupRow {
        val line = run.first
        val actor = line.who?.takeIf { it != LIGHTNING }?.let(::playerRef)
        val actorKind = when (line.who) {
            null -> "none"
            LIGHTNING -> "lightning"
            else -> "player"
        }
        val place = line.places.firstOrNull()
        val row = LookupRow(
            time = line.timestamp, actor = actor, actorKind = actorKind, action = "changed", cause = line.cause.name.lowercase(),
            confidence = "fact", world = place?.let { Ui.worldName(it.world) }, x = place?.x, y = place?.y, z = place?.z,
            thing = null, thingAfter = null, name = null, amount = run.amount, from = null, to = null, container = null, slot = null,
            via = null, rolledBack = line.undone, repeats = run.rows, event = line.event?.takeIf { run.rows == 1 }?.toString(),
        )
        return when (val source = line.source) {
            is LedgerEntry -> {
                val (from, to) = if (source.qty > 0) source.counterparty to source.holder else source.holder to source.counterparty
                val container = (from as? Container) ?: (to as? Container)
                row.copy(
                    action = if (source.kind == Kind.MUTATE) "changed" else if (source.qty > 0) "added" else "removed",
                    confidence = source.confidence.name.lowercase(), thing = itemKey(ledger, source.itemFormId),
                    name = customName(source.itemFormId), from = holderRef(from), to = holderRef(to),
                    container = container?.let(::holderRef), slot = container?.slot,
                )
            }
            is BlockRow -> {
                val change = blockChange(source)
                val pressed = if (source.cause == Cause.BLK_ENTITY_SWITCH) source.payloadAfter?.let(::pressedBy) else null
                row.copy(action = actionOf(change.sign), confidence = source.confidence.name.lowercase(), thing = change.from, thingAfter = change.to, via = pressed?.first)
            }
            is EntityRow -> {
                val sign = when (source.kind) {
                    EntityKind.CREATED -> "+"
                    EntityKind.CHANGED, EntityKind.MOVED -> "~"
                    else -> "-"
                }
                row.copy(
                    action = actionOf(sign), confidence = source.confidence.name.lowercase(), thing = source.type,
                    // Only a player's uuid is asked for a name: a mob's is looked up on disk and never found.
                    to = HolderRef("entity", if (source.type == PLAYER_TYPE) playerRef(source.uuid) else PlayerRef(source.uuid, null)),
                    via = source.via ?: source.death,
                )
            }
            else -> row
        }
    }

    private val PLAYER_TYPE = "minecraft:player"

    private fun actionOf(sign: String) = when (sign) {
        "+" -> "added"
        "-" -> "removed"
        else -> "changed"
    }

    private fun playerRef(uuid: UUID) = PlayerRef(uuid, knownName(uuid))

    private fun holderRef(holder: Holder): HolderRef = when (holder) {
        is PlayerInv -> HolderRef("inventory", playerRef(holder.uuid), slot = holder.slot)
        is PlayerEquip -> HolderRef("equipment", playerRef(holder.uuid), slot = holder.slot)
        is PlayerCursor -> HolderRef("cursor", playerRef(holder.uuid))
        is PlayerEnder -> HolderRef("ender_chest", playerRef(holder.uuid), slot = holder.slot)
        is MenuSlot -> HolderRef("menu", slot = holder.slot)
        is Container -> HolderRef("container", null, Ui.worldName(holder.world), holder.x, holder.y, holder.z, holder.slot)
        is WorldBlock -> HolderRef("block", null, Ui.worldName(holder.world), holder.x, holder.y, holder.z)
        // A crafting grid is the player's own entity slot.
        is EntitySlot -> HolderRef(if (knownName(holder.uuid) != null) "crafting" else "entity", playerRef(holder.uuid), slot = holder.slot)
        is ItemEntityRef -> HolderRef("ground", PlayerRef(holder.uuid, null))
        is Nested -> HolderRef("nested", slot = holder.index)
        Void -> HolderRef("void")
    }

    // An offline player's name can come off disk, once per printed row and holder otherwise.
    // ponytail: never evicted, so a rename shows after a restart; a timed cache if that matters.
    private val names = ConcurrentHashMap<UUID, String>()

    private fun knownName(uuid: UUID): String? = names[uuid] ?: nameOf(uuid)?.also { names[uuid] = it }

    private fun playerName(uuid: UUID): String = knownName(uuid) ?: uuid.toString()
}
