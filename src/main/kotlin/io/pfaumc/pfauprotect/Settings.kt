package io.pfaumc.pfauprotect

import io.pfaumc.pfauprotect.model.BLOCK_CAUSES
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.ENTITY_PLANE_CAUSES
import org.bukkit.configuration.file.FileConfiguration
import java.util.logging.Logger

object Settings {
    @Volatile var language = "en"

    @Volatile var disabledWorlds: Set<String> = emptySet()

    @Volatile var disabledCauses: Set<Cause> = emptySet()

    @Volatile var maxRadius = 200
    @Volatile var maxRollbackPositions = 131_072
    @Volatile var freshMillis = 10 * 60 * 1000L
    @Volatile var crowd = 8

    @Volatile var hiddenCommands = setOf("login", "l", "log", "register", "reg", "changepassword", "changepass", "unregister")

    fun load(config: FileConfiguration, log: Logger) {
        language = config.getString("language", language)!!.lowercase().takeIf { it in LANGUAGES } ?: run {
            log.warning("config.yml: language must be one of $LANGUAGES; using en")
            "en"
        }
        disabledWorlds = config.getStringList("worlds.disabled").toSet()
        disabledCauses = config.getStringList("causes.disabled").mapNotNull { name ->
            val cause = BLOCK_PLANE.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (cause == null) log.warning("config.yml: '$name' is no block- or entity-plane cause; ignored")
            cause
        }.toSet()
        maxRadius = config.getInt("lookup.max-radius", maxRadius).coerceIn(1, 1_000)
        maxRollbackPositions = config.getInt("rollback.max-positions", maxRollbackPositions).coerceIn(1, 1_000_000)
        freshMillis = config.getLong("mobs.fresh-minutes", freshMillis / 60_000).coerceIn(0, 24 * 60) * 60_000
        crowd = config.getInt("mobs.crowd", crowd).coerceIn(2, 1_000)
        if (config.contains("chat.hidden-commands")) hiddenCommands = config.getStringList("chat.hidden-commands").map { it.lowercase() }.toSet()
    }

    private val LANGUAGES = setOf("ru", "en")

    // The filter sits in the block store, so turning off a cause the item plane shares (a spawn egg, a
    // mob bucket) drops only its entity row.
    private val BLOCK_PLANE: Set<Cause> = BLOCK_CAUSES + ENTITY_PLANE_CAUSES
}
