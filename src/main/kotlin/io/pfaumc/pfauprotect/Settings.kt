package io.pfaumc.pfauprotect

import io.pfaumc.pfauprotect.model.Cause
import org.bukkit.configuration.file.FileConfiguration
import java.util.logging.Logger

/**
 * What `config.yml` lets an owner change. Read once at enable; every default is what the plugin did before
 * there was a file, so a server without one behaves exactly as it always has.
 */
object Settings {
    // English until config.yml is read; the file a server gets asks for Russian.
    @Volatile var language = "en"

    // Worlds whose block and entity planes are not opened: nothing is written for their places. Items are
    // the players' and keep being booked wherever they go.
    @Volatile var disabledWorlds: Set<String> = emptySet()

    // Block- and entity-plane causes not written. The item plane's are not offered: a skipped posting is
    // a gap every balance would show from then on.
    @Volatile var disabledCauses: Set<Cause> = emptySet()

    @Volatile var maxRadius = 200
    @Volatile var maxRollbackPositions = 32_768
    @Volatile var freshMillis = 10 * 60 * 1000L
    @Volatile var crowd = 8

    // Commands the chat log keeps by name only: their arguments are passwords.
    @Volatile var hiddenCommands = setOf("login", "l", "log", "register", "reg", "changepassword", "changepass", "unregister")

    fun load(config: FileConfiguration, log: Logger) {
        language = config.getString("language", language)!!.lowercase().takeIf { it in LANGUAGES } ?: run {
            log.warning("config.yml: language must be one of $LANGUAGES; using ru")
            "ru"
        }
        disabledWorlds = config.getStringList("worlds.disabled").toSet()
        disabledCauses = config.getStringList("causes.disabled").mapNotNull { name ->
            val cause = Cause.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (cause == null || cause !in BLOCK_PLANE) log.warning("config.yml: '$name' is no block- or entity-plane cause; ignored")
            cause?.takeIf { it in BLOCK_PLANE }
        }.toSet()
        maxRadius = config.getInt("lookup.max-radius", maxRadius).coerceIn(1, 1_000)
        maxRollbackPositions = config.getInt("rollback.max-positions", maxRollbackPositions).coerceIn(1, 1_000_000)
        freshMillis = config.getLong("mobs.fresh-minutes", freshMillis / 60_000).coerceIn(0, 24 * 60) * 60_000
        crowd = config.getInt("mobs.crowd", crowd).coerceIn(2, 1_000)
        if (config.contains("chat.hidden-commands")) hiddenCommands = config.getStringList("chat.hidden-commands").map { it.lowercase() }.toSet()
    }

    private val LANGUAGES = setOf("ru", "en")

    // The causes rows of the block and entity planes are written with: the 0xC0..0xEF block range, the
    // entity plane's own, and the rest a block row can carry.
    private val BLOCK_PLANE: Set<Cause> = Cause.entries.filter { it.id in 0xCA..0xEF }.toSet() + setOf(
        Cause.BLK_SIGN_EDIT, Cause.BLK_LIQUID_FLOW, Cause.BLK_PLUGIN, Cause.ENTITY_KILLED, Cause.ENTITY_BROKEN, Cause.ENTITY_CHANGED,
        Cause.ENTITY_LED, Cause.MOB_BRED, Cause.PLAYER_KILLED, Cause.MOB_TRANSFORM,
    )
}
