package io.pfaumc.pfauprotect.api

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.command.CommandSender
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * PfauProtect for other plugins, from `Bukkit.getServicesManager().load(PfauProtectApi::class.java)`.
 * The words are what `/pp lookup` and `/pp rollback` take; a lookup's answer is its lines as a player
 * would read them.
 */
interface PfauProtectApi {
    /** The lines `/pp lookup <words>` would answer standing at `at`, read off the region threads. */
    fun lookup(at: Location, words: String): CompletableFuture<List<String>>

    /** A rollback preview for `sender`, as `/pp rollback <words>` at `at`; `/pp apply` runs it. */
    fun previewRollback(sender: CommandSender, at: Location, words: String)

    /**
     * A block another plugin changed, on whoever it names: a row of the block plane, so a lookup shows it
     * and a rollback of that player puts it back. Called on the block's own region thread.
     */
    fun logChange(actor: UUID?, block: Block, before: BlockData, after: BlockData)
}

/**
 * Raised before a row of the block or entity plane is written, on whatever thread writes it, only while
 * some plugin listens. Cancelled, the row is not written.
 */
class PfauProtectPreLogEvent(
    val world: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    val cause: String,
    val actor: UUID?,
    async: Boolean,
) : Event(async), Cancellable {
    private var cancelled = false

    override fun isCancelled() = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
