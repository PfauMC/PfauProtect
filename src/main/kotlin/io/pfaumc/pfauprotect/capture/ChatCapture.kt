package io.pfaumc.pfauprotect.capture

import io.papermc.paper.event.player.AsyncChatEvent
import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.capture.item.commandName
import io.pfaumc.pfauprotect.storage.BookEdit
import io.pfaumc.pfauprotect.storage.ChatKind
import io.pfaumc.pfauprotect.storage.ChatLine
import io.pfaumc.pfauprotect.storage.ChatLog
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerEditBookEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

internal val BOOK_MARK = NamespacedKey("pfauprotect", "book")

/**
 * A chat line comes off the region threads, where a player's position is no thing to read, so
 * it is kept without one; the rest are kept where they happened.
 */
class ChatCapture(private val log: ChatLog) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        log.submit(ChatLine(System.currentTimeMillis(), ChatKind.CHAT, event.player.uniqueId, PlainTextComponentSerializer.plainText().serialize(event.message())))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) =
        placed(event.player, ChatKind.COMMAND, shown(event.message, Settings.hiddenCommands))

    // A book is marked the first time it is written in, so every later edit of it reads as the same book, and so
    // does every copy of it once signed: the mark is part of the item and travels with it. The game takes the
    // edited book from the event after every handler, so the mark set here is on it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBook(event: PlayerEditBookEvent) {
        val after = event.newBookMeta
        val mark = markOf(after) ?: (markOf(event.previousBookMeta) ?: "#" + UUID.randomUUID().toString().take(8)).also {
            after.persistentDataContainer.set(BOOK_MARK, PersistentDataType.STRING, it)
            event.newBookMeta = after
        }
        val pages = BookEdit.changed(pagesOf(event.previousBookMeta), pagesOf(after))
        val title = if (event.isSigning) after.title.orEmpty() else null
        if (pages.isEmpty() && title == null) return
        placed(event.player, ChatKind.BOOK, BookEdit(mark, title, pages).text())
    }

    private fun markOf(meta: BookMeta): String? = meta.persistentDataContainer.get(BOOK_MARK, PersistentDataType.STRING)

    private fun pagesOf(meta: BookMeta): List<String> = meta.pages().map(PlainTextComponentSerializer.plainText()::serialize)

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
    if (commandName(message) !in hidden) return message
    return "/" + message.removePrefix("/").substringBefore(' ')
}
