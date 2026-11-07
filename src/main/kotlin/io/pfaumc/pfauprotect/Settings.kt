package io.pfaumc.pfauprotect

import io.pfaumc.pfauprotect.command.MAX_LIMIT
import io.pfaumc.pfauprotect.model.BLOCK_CAUSES
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.ENTITY_PLANE_CAUSES
import io.pfaumc.pfauprotect.storage.MAX_REGION_RADIUS
import org.bukkit.configuration.file.FileConfiguration
import java.util.logging.Logger

object Settings {
    @Volatile var language = "en"

    // Which server of a network the API's and MCP's answers come from; empty names none.
    @Volatile var serverName = ""

    @Volatile var disabledWorlds: Set<String> = emptySet()

    @Volatile var disabledCauses: Set<Cause> = emptySet()

    @Volatile var maxRadius = 200
    @Volatile var dialogPages = true
    @Volatile var pageRows = 20
    @Volatile var maxRollbackPositions = 131_072
    @Volatile var freshMillis = 10 * 60 * 1000L
    @Volatile var crowd = 8

    @Volatile var hiddenCommands = setOf("login", "l", "log", "register", "reg", "changepassword", "changepass", "unregister")

    // What starts a message to the whole server where chat is local otherwise; empty where there is no local chat.
    @Volatile var globalPrefix = "!"

    // Days the chat log keeps a line; 0 keeps every line until /pp purge.
    @Volatile var chatKeepDays = 0

    // The MCP add-on: closed unless enabled, and the gateway mode off without a secret.
    @Volatile var mcpEnabled = false
    @Volatile var mcpAddress = "127.0.0.1"
    @Volatile var mcpPort = 25590
    @Volatile var mcpGatewaySecret = ""

    // 0 keeps /metrics closed.
    @Volatile var metricsPort = 0
    @Volatile var metricsAddress = "127.0.0.1"

    const val MIN_GATEWAY_SECRET = 32

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
        maxRadius = config.getInt("lookup.max-radius", maxRadius).coerceIn(1, MAX_REGION_RADIUS)
        dialogPages = config.getBoolean("lookup.dialog", dialogPages)
        pageRows = config.getInt("lookup.rows", pageRows).coerceIn(1, MAX_LIMIT)
        maxRollbackPositions = config.getInt("rollback.max-positions", maxRollbackPositions).coerceIn(1, 1_000_000)
        freshMillis = config.getLong("mobs.fresh-minutes", freshMillis / 60_000).coerceIn(0, 24 * 60) * 60_000
        crowd = config.getInt("mobs.crowd", crowd).coerceIn(2, 1_000)
        if (config.contains("chat.hidden-commands")) hiddenCommands = config.getStringList("chat.hidden-commands").map { it.lowercase() }.toSet()
        chatKeepDays = config.getInt("chat.keep-days", chatKeepDays).coerceIn(0, 100_000)
        globalPrefix = config.getString("chat.global-prefix", globalPrefix).orEmpty()
        serverName = config.getString("server-name", serverName)!!.trim()
        mcpEnabled = config.getBoolean("mcp.enabled", mcpEnabled)
        mcpAddress = config.getString("mcp.address", mcpAddress)!!
        mcpPort = config.getInt("mcp.port", mcpPort).coerceIn(1, 65_535)
        // The environment wins: a secret in a file is one more place for it to leak from.
        val secret = (System.getenv("PFAUPROTECT_MCP_SECRET")?.takeIf { it.isNotBlank() } ?: config.getString("mcp.gateway-secret", "")!!).trim()
        // A short secret is one a caller can guess; one that looks like a token would be read as a token.
        mcpGatewaySecret = if (secret.isEmpty() || secret.length >= MIN_GATEWAY_SECRET && !secret.startsWith("ppt_")) secret else {
            log.warning("MCP gateway secret ignored: it needs at least $MIN_GATEWAY_SECRET characters and must not start with ppt_")
            ""
        }
        metricsPort = config.getInt("metrics.port", metricsPort).coerceIn(0, 65_535)
        metricsAddress = config.getString("metrics.address", metricsAddress)!!
    }

    private val LANGUAGES = setOf("ru", "en")

    // The filter sits in the block store, so turning off a cause the item plane shares (a spawn egg, a
    // mob bucket) drops only its entity row.
    private val BLOCK_PLANE: Set<Cause> = BLOCK_CAUSES + ENTITY_PLANE_CAUSES
}
