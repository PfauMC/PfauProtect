package io.pfaumc.pfauprotect.mcp

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The tokens players made for themselves in the game, one per player, each bound to the player who made it. Only a
 * token's SHA-256 is kept: a copy of the file lets nobody in.
 */
internal class Tokens(private val file: Path) {
    private val players = ConcurrentHashMap<String, UUID>()

    init {
        if (Files.exists(file)) {
            val yaml = YamlConfiguration.loadConfiguration(file.toFile())
            for (hash in yaml.getKeys(false)) {
                runCatching { UUID.fromString(yaml.getString(hash)) }.getOrNull()?.let { players[hash] = it }
            }
        }
    }

    /** A new token for [player], which replaces the one they had. Shown once: it cannot be read back. */
    fun create(player: UUID): String {
        val raw = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        synchronized(this) {
            players.values.removeIf { it == player }
            players[hashOf(raw)] = player
            save()
        }
        return raw
    }

    /** Whether [player] had a token to revoke. */
    fun revoke(player: UUID): Boolean = synchronized(this) {
        players.values.removeIf { it == player }.also { if (it) save() }
    }

    fun playerOf(raw: String): UUID? = players[hashOf(raw)]

    // Written whole and moved into place, so a crash mid-write leaves the old file rather than half of one.
    private fun save() {
        val yaml = YamlConfiguration()
        yaml.options().setHeader(listOf("PfauProtect MCP tokens: SHA-256 of each token, and the player it acts for."))
        for ((hash, player) in players) yaml.set(hash, player.toString())
        Files.createDirectories(file.parent)
        val written = file.resolveSibling("${file.fileName}.tmp")
        yaml.save(written.toFile())
        Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val PREFIX = "ppt_"

        fun hashOf(raw: String): String =
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
