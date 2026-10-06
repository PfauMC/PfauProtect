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

/**
 * How the plugin draws what it says: a few colours that each mean one thing, the time as how long ago,
 * names of the game's things in the reader's own client language, and a click where a reader would
 * otherwise retype a command.
 */
object Ui {
    // Who did it, what it was done to, and everything around them that only qualifies.
    val WHO: TextColor = NamedTextColor.AQUA
    val THING: TextColor = NamedTextColor.GOLD
    val MUTED: TextColor = NamedTextColor.GRAY
    val FAINT: TextColor = NamedTextColor.DARK_GRAY
    val LOST: TextColor = NamedTextColor.RED
    val GAINED: TextColor = NamedTextColor.GREEN
    val CHANGED: TextColor = NamedTextColor.YELLOW

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())

    fun text(value: String, color: TextColor? = null): TextComponent = Component.text(value, color)

    fun hover(component: Component, vararg lines: String): Component =
        component.hoverEvent(HoverEvent.showText(Component.text(lines.joinToString("\n"))))

    /** How long ago, in its one largest unit; the date and time to the second when hovered. */
    fun ago(at: Long, now: Long = System.currentTimeMillis()): Component =
        hover(text(agoText(now - at), MUTED), DATE.format(Instant.ofEpochMilli(at)))

    fun agoText(millis: Long): String {
        val s = maxOf(0, millis / 1000)
        val (n, en, ru) = when {
            s < 60 -> Triple(s, "s", "с")
            s < 3_600 -> Triple(s / 60, "m", "м")
            s < 86_400 -> Triple(s / 3_600, "h", "ч")
            s < 2_592_000 -> Triple(s / 86_400, "d", "д")
            s < 31_536_000 -> Triple(s / 2_592_000, "mo", "мес")
            else -> Triple(s / 31_536_000, "y", "г")
        }
        return "$n${tr(en, ru)}"
    }

    /**
     * Where a row happened: a mark a player clicks to be taken there, the coordinates themselves for the
     * console, which can neither click nor go.
     */
    fun place(world: UUID, x: Int, y: Int, z: Int, clickable: Boolean): Component {
        val name = worldName(world)
        if (!clickable) return text("$x $y $z", FAINT)
        return hover(text("⌖", MUTED), "$x $y $z $name", tr("Click to teleport", "Клик — телепорт"))
            .clickEvent(ClickEvent.runCommand("/pp tp $x $y $z $name"))
    }

    fun worldName(world: UUID): String = runCatching { Bukkit.getWorld(world)?.name }.getOrNull() ?: world.toString().take(8)

    // The game's own names, which the client draws in its own language: a translation of ours could only
    // drift from what the player sees in their inventory.
    fun block(key: String): Component = game(key) { BuiltInRegistries.BLOCK.getOptional(it).map { b -> b.descriptionId }.orElse(null) }

    fun item(key: String): Component = game(key) { BuiltInRegistries.ITEM.getOptional(it).map { i -> i.descriptionId }.orElse(null) }

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

    /** Clickable words that run a command, with what they do when hovered. */
    fun button(label: String, command: String, color: TextColor, vararg hint: String): Component =
        hover(text(label, color), *hint).clickEvent(ClickEvent.runCommand(command))
}

/** The English or the Russian of a phrase made from parts, which no whole-line rule could translate. */
fun tr(en: String, ru: String): String = if (Settings.language == "ru") ru else en
