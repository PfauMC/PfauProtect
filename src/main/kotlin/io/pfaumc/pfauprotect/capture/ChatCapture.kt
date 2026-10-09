package io.pfaumc.pfauprotect.capture

import io.papermc.paper.event.player.AsyncChatEvent
import io.pfaumc.pfauprotect.storage.ChatKind
import io.pfaumc.pfauprotect.storage.ChatLine
import io.pfaumc.pfauprotect.storage.ChatLog
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * What players say, the commands they run, and when they come and go, for an investigation to read beside
 * the planes. A chat line comes off the region threads, where a player's position is no thing to read, so
 * it is kept without one; the rest are kept where they happened.
 */
class ChatCapture(private val log: ChatLog) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        log.submit(ChatLine(System.currentTimeMillis(), ChatKind.CHAT, event.player.uniqueId, PlainTextComponentSerializer.plainText().serialize(event.message())))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) =
        placed(event.player, ChatKind.COMMAND, shown(event.message, io.pfaumc.pfauprotect.Settings.hiddenCommands))

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = placed(event.player, ChatKind.JOIN, "")

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = placed(event.player, ChatKind.QUIT, "")

    private fun placed(player: Player, kind: ChatKind, text: String) {
        val at = player.location
        log.submit(ChatLine(System.currentTimeMillis(), kind, player.uniqueId, text, at.world.name, at.blockX, at.blockY, at.blockZ))
    }
}

// A login or a register carries a password, and the log is read by every moderator: only its name is kept.
internal fun shown(message: String, hidden: Set<String>): String {
    val name = message.removePrefix("/").substringBefore(' ')
    return if (name.substringAfter(':').lowercase() in hidden) "/$name" else message
}
