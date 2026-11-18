package io.pfaumc.pfauprotect

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import org.bukkit.Bukkit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object Ui {
    // Who did it, what it was done to, and everything around them that only qualifies.
    val WHO: TextColor = NamedTextColor.AQUA
    val THING: TextColor = NamedTextColor.GOLD
    val MUTED: TextColor = NamedTextColor.GRAY
    val FAINT: TextColor = NamedTextColor.DARK_GRAY
    val LOST: TextColor = NamedTextColor.RED
    val GAINED: TextColor = NamedTextColor.GREEN
    val CHANGED: TextColor = NamedTextColor.YELLOW
    val WHERE: TextColor = NamedTextColor.DARK_AQUA

    const val DATE_PATTERN = "yyyy-MM-dd HH:mm:ss"

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("$DATE_PATTERN z").withZone(ZoneId.systemDefault())

    fun text(value: String, color: TextColor? = null): TextComponent = Component.text(value, color)

    fun hover(component: Component, vararg lines: String): Component =
        component.hoverEvent(HoverEvent.showText(Component.text(lines.joinToString("\n"))))

    /** How long ago, in its one largest unit; the date and time to the second when hovered. */
    fun ago(at: Long): Component =
        hover(text(agoText(System.currentTimeMillis() - at), MUTED), DATE.format(Instant.ofEpochMilli(at)))

    private val AGO_UNITS = listOf("y" to "г", "mo" to "мес", "d" to "д", "h" to "ч", "m" to "м")

    private fun agoText(millis: Long): String {
        val s = maxOf(0, millis / 1000)
        for ((en, ru) in AGO_UNITS) {
            val unit = SECONDS_PER_UNIT.getValue(en)
            if (s >= unit) return "${s / unit}${tr(en, ru)}"
        }
        return "$s${tr("s", "с")}"
    }

    /**
     * Where a row happened: a mark a player clicks to be taken there, the coordinates themselves for the
     * console, which can neither click nor go.
     */
    fun place(world: UUID, x: Int, y: Int, z: Int, clickable: Boolean, hide: Boolean): Component =
        place(worldName(world), world, x, y, z, clickable, hide)

    /** A place whose world is known by name only, as the chat log keeps it: a token needs the world loaded, else nothing is shown. */
    fun place(name: String, x: Int, y: Int, z: Int, clickable: Boolean, hide: Boolean): Component {
        val world = runCatching { Bukkit.getWorld(name)?.uid }.getOrNull()
        if (hide && world == null) return Component.empty()
        return place(name, world, x, y, z, clickable, hide)
    }

    private fun place(name: String, world: UUID?, x: Int, y: Int, z: Int, clickable: Boolean, hide: Boolean): Component {
        val where = if (hide) Places.text(world!!, x, y, z) else "$x $y $z $name"
        // "?" is no token (out-of-range y from a chat row, or no key): nothing to click.
        if (hide && where == UNKNOWN_PLACE) return text(where, FAINT)
        // The click is what lands in the chat log, so with the setting on it carries a token even where the text shows numbers.
        val go = if (hide || !Settings.hideCoordinates) where else world?.let { Places.text(it, x, y, z) } ?: UNKNOWN_PLACE
        if (!clickable || go == UNKNOWN_PLACE) return text(if (hide) where else "$x $y $z", FAINT)
        return teleport(text("⌖", MUTED), go, where)
    }

    /** The place a whole page is about, in its heading: written out, set apart by colour, and the way there. */
    fun coordinates(world: UUID, x: Int, y: Int, z: Int, clickable: Boolean, hide: Boolean): Component {
        val where = if (hide) Places.text(world, x, y, z) else "$x $y $z ${worldName(world)}"
        if (hide && where == UNKNOWN_PLACE) return text(where, FAINT)
        val shown = text("($where)", WHERE)
        val go = Places.forCommand(world, x, y, z, hide) ?: where
        return if (clickable && go != UNKNOWN_PLACE) teleport(shown, go) else shown
    }

    // [go] is what /pp tp takes after the verb: a token, or "x y z world".
    private fun teleport(label: Component, go: String, vararg hint: String): Component =
        hover(label, *hint, tr("Click to teleport", "Клик — телепорт")).clickEvent(ClickEvent.runCommand("/pp tp $go"))

    fun worldName(world: UUID): String = runCatching { Bukkit.getWorld(world)?.name }.getOrNull() ?: world.toString().take(8)

    // The game's own names, which the client draws in its own language: a translation of ours could only
    // drift from what the player sees in their inventory.
    fun block(key: String): Component = game(key) { BuiltInRegistries.BLOCK.getOptional(it).map { b -> b.descriptionId }.orElse(null) }

    fun item(key: String): Component = game(key) { BuiltInRegistries.ITEM.getOptional(it).map { i -> i.descriptionId }.orElse(null) }

    fun named(line: String): Component {
        val out = Component.text()
        var at = 0
        for (id in ITEM_ID.findAll(line)) {
            out.append(Component.text(line.substring(at, id.range.first))).append(item(id.value))
            at = id.range.last + 1
        }
        return out.append(Component.text(line.substring(at))).build()
    }

    // No dot: the one after an id ends the sentence.
    private val ITEM_ID = Regex("minecraft:[a-z0-9_/]+")

    fun entity(key: String): Component = game(key) { BuiltInRegistries.ENTITY_TYPE.getOptional(it).map { e -> e.descriptionId }.orElse(null) }

    /** A key that may name an entity, an item or a block — what pressed a switch, what a kill was made with. */
    fun any(key: String): Component = game(key) { id ->
        BuiltInRegistries.ENTITY_TYPE.getOptional(id).map { it.descriptionId }
            .or { BuiltInRegistries.ITEM.getOptional(id).map { it.descriptionId } }
            .or { BuiltInRegistries.BLOCK.getOptional(id).map { it.descriptionId } }.orElse(null)
    }

    private fun game(key: String, descriptionOf: (Identifier) -> String?): Component {
        val translation = runCatching { descriptionOf(Identifier.parse(key)) }.getOrNull()
            ?: return text(key.removePrefix("minecraft:"))
        return Component.translatable().key(translation).fallback(key.removePrefix("minecraft:")).build()
    }

    fun button(label: String, command: String, color: TextColor, vararg hint: String): Component =
        hover(text(label, color), *hint).clickEvent(ClickEvent.runCommand(command))
}

internal val SECONDS_PER_UNIT = mapOf("y" to 31_536_000L, "mo" to 2_592_000L, "w" to 604_800L, "d" to 86_400L, "h" to 3_600L, "m" to 60L)

/** The English or the Russian of a phrase made from parts, which no whole-line rule could translate. */
fun tr(en: String, ru: String): String = if (Settings.language == "ru") ru else en
