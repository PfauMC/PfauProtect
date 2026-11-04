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
 * The words are what `/pp lookup` and `/pp rollback` take.
 *
 * A call with `asPlayer` set answers as that player would be answered: their `pfauprotect.*` permissions are
 * checked, and a future fails with [PfauProtectException] when they lack one. `asPlayer` null is for trusted
 * code only and checks nothing. No answer carries an IP address.
 */
interface PfauProtectApi {
    /** `server-name` from config.yml, empty when unset: which server of a network the answers come from. */
    val serverName: String

    /** The lines `/pp lookup <words>` would answer standing at `at`, read off the region threads. */
    fun lookup(at: Location, words: String): CompletableFuture<List<String>>

    /** A rollback preview for `sender`, as `/pp rollback <words>` at `at`; `/pp apply` runs it. */
    fun previewRollback(sender: CommandSender, at: Location, words: String)

    /** What `/pp lookup` would find at the request's place, as rows. Needs `pfauprotect.lookup`. */
    fun lookup(request: LookupRequest, asPlayer: UUID?): CompletableFuture<LookupPage>

    /**
     * Everything the players `user:` names did in every world, as `user: radius:global` reads one world.
     * The request's place is not read. Needs `pfauprotect.lookup`.
     */
    fun actorActions(request: LookupRequest, asPlayer: UUID?): CompletableFuture<LookupPage>

    /** What `/pp chat` reads, with players' deaths beside it, as lines. Needs `pfauprotect.lookup`. */
    fun chat(request: ChatRequest, asPlayer: UUID?): CompletableFuture<ChatPage>

    /**
     * A rollback preview for an online `player`, as `/pp rollback <words>` at the request's place, or where
     * they stand when it names no world. Needs their `pfauprotect.rollback`. Completes once the preview has
     * read what it would do, or with the refusal; only the player applies it, by `/pp apply`.
     */
    fun preview(player: UUID, request: LookupRequest): CompletableFuture<PreviewResult>

    /** The newest preview asked through [preview] for `player`, or status `none`. */
    fun previewStatus(player: UUID): PreviewResult

    /**
     * A block another plugin changed, on whoever it names: a row of the block plane, so a lookup shows it
     * and a rollback of that player puts it back. Called on the block's own region thread.
     */
    fun logChange(actor: UUID?, block: Block, before: BlockData, after: BlockData)
}

/**
 * Whether an offline player holds a permission, for a server whose permissions live outside Bukkit: registered in
 * the ServicesManager, asked before LuckPerms. Called off the region threads and may block. Null when it does not
 * know that player, which passes the question on.
 */
fun interface PfauProtectPermissions {
    fun has(player: UUID, permission: String): Boolean?
}

/** A refusal said in words for whoever asked; [code] is one of the constants below. */
class PfauProtectException(val code: String, message: String) : RuntimeException(message) {
    companion object {
        /** The player lacks the permission. */
        const val FORBIDDEN = "forbidden"

        /** The player is offline and nothing could say what they may, or the call needs them online. */
        const val OFFLINE = "offline"

        /** The question cannot be answered as asked: a bad word, an unknown player, a rollback refused. */
        const val REFUSED = "refused"
    }
}

/**
 * Where and what a lookup reads: `world` and the position stand for where `/pp lookup` is typed, `words` are
 * its words. `since` and `until`, epoch millis, are a window of absolute time in place of `time:`.
 */
data class LookupRequest(
    val world: UUID? = null,
    val x: Int = 0,
    val y: Int = 0,
    val z: Int = 0,
    val words: String = "",
    val since: Long? = null,
    val until: Long? = null,
)

data class PlayerRef(val uuid: UUID, val name: String?)

/**
 * One end of an item's movement. [type] is inventory, equipment, cursor, ender_chest, crafting, menu, container,
 * block, entity, ground, nested or void; [owner] is the player or entity it belongs to, the place is where it
 * stands.
 */
data class HolderRef(
    val type: String,
    val owner: PlayerRef? = null,
    val world: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val z: Int? = null,
    val slot: Int? = null,
)

/**
 * One line of a lookup. [action] is added, removed or changed; [cause] is the cause's code, the word config.yml
 * and the hover of a line use; [confidence] is fact, inferred (worked out), nearby (only stood near) or aggro (a
 * creeper went off at them). [repeats] is how many rows of the same thing within a minute the line stands for,
 * [event] the token `event:` takes to roll back that one row.
 */
data class LookupRow(
    val time: Long,
    val actor: PlayerRef?,
    val actorKind: String,
    val action: String,
    val cause: String,
    val confidence: String,
    val world: String?,
    val x: Int?,
    val y: Int?,
    val z: Int?,
    val thing: String?,
    val thingAfter: String?,
    val name: String?,
    val amount: Int,
    val from: HolderRef?,
    val to: HolderRef?,
    val container: HolderRef?,
    val slot: Int?,
    val via: String?,
    val rolledBack: Boolean,
    val repeats: Int,
    val event: String?,
)

/** A page of rows, newest first. [complete] false: the read stopped early, and an empty page clears nobody. */
data class LookupPage(val rows: List<LookupRow>, val page: Int, val more: Boolean, val complete: Boolean, val notes: List<String> = emptyList())

/**
 * What `/pp chat` takes: `words` as it takes them (`user:A&B` is the two of them, `filter:`, `action:pvp`,
 * `exclude:`, `page:`, `limit:`), the window as for a lookup. [kinds] keeps only chat, command, join, quit, hit,
 * totem and death; empty keeps all. A place keeps only lines within [radius] of it, which a chat line, having no
 * place, never is. Deaths are read only for the players `user:` names.
 */
data class ChatRequest(
    val words: String = "",
    val since: Long? = null,
    val until: Long? = null,
    val kinds: Set<String> = emptySet(),
    val world: UUID? = null,
    val x: Int = 0,
    val y: Int = 0,
    val z: Int = 0,
    val radius: Int? = null,
)

/** A blow or a death: [weapon] is what struck, the item in hand or the arrow, the dog, the TNT; a death's cause. */
data class HitInfo(val attacker: PlayerRef?, val victim: PlayerRef, val damage: Float, val weapon: String?, val confidence: String)

data class ChatEntry(
    val time: Long,
    val kind: String,
    val player: PlayerRef,
    val text: String,
    val world: String?,
    val x: Int?,
    val y: Int?,
    val z: Int?,
    val hit: HitInfo?,
    val repeats: Int,
)

data class ChatPage(val lines: List<ChatEntry>, val page: Int, val more: Boolean)

data class PreviewBlocks(val change: Int, val unchanged: Int, val conflicts: Int)

data class PreviewEntities(val back: Int, val taken: Int, val reverted: Int, val already: Int)

/**
 * A preview's state: none, running, ready or refused. Ready carries what applying it would do and when the
 * preview lapses; refused carries why.
 */
data class PreviewResult(
    val status: String,
    val reason: String? = null,
    val blocks: PreviewBlocks? = null,
    val slots: Int? = null,
    val entities: PreviewEntities? = null,
    val expiresAt: Long? = null,
)

/**
 * Raised before a row of the block or entity plane is written, on whatever thread writes it, only while
 * some plugin listens. Cancelled, the row is not written. `actor` is a player's id, or
 * [io.pfaumc.pfauprotect.model.LIGHTNING] for what a storm's bolt did.
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
