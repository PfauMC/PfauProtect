package io.pfaumc.pfauprotect.api

import com.mojang.brigadier.exceptions.CommandSyntaxException
import io.pfaumc.pfauprotect.LOOKUP_PERMISSION
import io.pfaumc.pfauprotect.ROLLBACK_PERMISSION
import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.Ui
import io.pfaumc.pfauprotect.command.ChatRun
import io.pfaumc.pfauprotect.command.LookupQuery
import io.pfaumc.pfauprotect.command.LookupTarget
import io.pfaumc.pfauprotect.command.Lookups
import io.pfaumc.pfauprotect.command.chatPlayers
import io.pfaumc.pfauprotect.command.lookupTargetAt
import io.pfaumc.pfauprotect.command.parseLookupQuery
import io.pfaumc.pfauprotect.command.readChat
import io.pfaumc.pfauprotect.command.refuse
import io.pfaumc.pfauprotect.command.targetOr
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.rollback.PENDING_MILLIS
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.ChatKind
import io.pfaumc.pfauprotect.storage.ChatLog
import io.pfaumc.pfauprotect.storage.EntityRow
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

/** The API's calls; the MCP add-on makes the same ones with what a gateway vouched for in [Access]. */
internal class Api(
    private val plugin: Plugin,
    private val lookups: Lookups,
    private val chatLog: ChatLog,
    private val blocks: BlockLogs,
    // Rollbacks.preview and pendingSince.
    private val startPreview: (CommandSender, LookupTarget, LookupQuery, (PreviewResult) -> Unit) -> Unit,
    private val pendingSince: (UUID) -> Long?,
    private val playerOf: (UUID) -> Player? = Bukkit::getPlayer,
    private val ask: (Access, String) -> Boolean? = { access, permission -> allowed(access, permission) },
) : PfauProtectApi {
    // The newest preview asked through the API, per player, with when it was asked.
    private val previews = ConcurrentHashMap<UUID, Pair<Long, PreviewResult>>()

    // Its own threads: a wide read or an offline player's LuckPerms answer blocks, and the common pool is everyone's.
    // ponytail: a fixed pool, the calls past it wait in its queue; a cap on that queue if callers pile up.
    private val threads = Executors.newFixedThreadPool(THREADS) { Thread(it, "pfauprotect-api").apply { isDaemon = true } }

    fun close() {
        threads.shutdownNow()
    }

    /** Throws unless the caller holds [permission]. */
    fun check(access: Access, permission: String) = require(access, permission, ask)

    override val serverName: String get() = Settings.serverName

    override fun lookup(at: Location, words: String): CompletableFuture<List<String>> {
        val lines = Collections.synchronizedList(ArrayList<String>())
        val sender = Bukkit.createCommandSender { lines += PlainTextComponentSerializer.plainText().serialize(it) }
        return off {
            val query = parseLookupQuery(words)
            lookups.report(sender, lookupTargetAt(at), query)
            lines.toList()
        }
    }

    override fun previewRollback(sender: CommandSender, at: Location, words: String) =
        startPreview(sender, lookupTargetAt(at), parseLookupQuery(words)) {}

    override fun logChange(actor: UUID?, block: Block, before: BlockData, after: BlockData) {
        val log = blocks.get(block.world.uid) ?: return
        log.submit(listOf(BlockChange(block.x, block.y, block.z, before.asString, after.asString, Cause.BLK_PLUGIN, actor = actor)))
    }

    override fun lookup(request: LookupRequest, asPlayer: UUID?) = lookupAs(request, asPlayer?.let(::Access))

    override fun actorActions(request: LookupRequest, asPlayer: UUID?) = actorActionsAs(request, asPlayer?.let(::Access))

    override fun chat(request: ChatRequest, asPlayer: UUID?) = chatAs(request, asPlayer?.let(::Access))

    override fun preview(player: UUID, request: LookupRequest) = previewAs(Access(player), request)

    /**
     * [here] keeps a lookup at its place when it names players: `/pp lookup u:X` without a radius reads all X did,
     * which a tool about one place must not turn into. `r:global` said outright still is that.
     */
    fun lookupAs(request: LookupRequest, access: Access?, here: Boolean = false): CompletableFuture<LookupPage> = off {
        require(access, LOOKUP_PERMISSION, ask)
        val parsed = queryOf(request.words, request.since, request.until)
        val query = if (here && parsed.global && !GLOBAL_WORD.containsMatchIn(request.words)) parsed.copy(radius = null) else parsed
        val world = request.world ?: refuse("A lookup needs a world.")
        val target = query.targetOr(LookupTarget(world, request.x, request.y, request.z, "${request.x} ${request.y} ${request.z}"))
        lookups.pageOf(lookups.find(target, query), query)
    }

    fun actorActionsAs(request: LookupRequest, access: Access?): CompletableFuture<LookupPage> = off {
        require(access, LOOKUP_PERMISSION, ask)
        val query = queryOf(request.words, request.since, request.until)
        lookups.pageOf(lookups.findAllWorlds(query), query)
    }

    fun chatAs(request: ChatRequest, access: Access?): CompletableFuture<ChatPage> = off {
        require(access, LOOKUP_PERMISSION, ask)
        val query = queryOf(request.words, request.since, request.until)
        lookups.idsOf(chatPlayers(query))
        val kinds = request.kinds.map { it.lowercase() }.toSet()
        val unknown = kinds - CHAT_KINDS
        if (unknown.isNotEmpty()) refuse("Unknown kind: ${unknown.joinToString(", ")}; expected ${CHAT_KINDS.joinToString(", ")}.")
        val worldName = request.world?.let { Ui.worldName(it) }
        fun near(world: String, x: Int, y: Int, z: Int): Boolean {
            val radius = request.radius ?: return worldName == null || world == worldName
            return world == worldName && maxOf(abs(x - request.x), abs(y - request.y), abs(z - request.z)) <= radius
        }
        val said = lookups.chatFilter(query)
        val read = readChat(chatLog, query) { line ->
            (kinds.isEmpty() || line.kind.name.lowercase() in kinds) && said(line) && (request.world == null || near(line.world, line.x, line.y, line.z))
        }
        // A death has no text to filter on, is no chat line to exclude a player's from, and is no hit for `pvp`.
        val wantsDeaths = (kinds.isEmpty() || "death" in kinds) && query.users.isNotEmpty() && query.filter == null &&
            query.excluded.isEmpty() && !query.pvp
        val (found, deathsComplete) = if (wantsDeaths) lookups.deaths(query) else emptyList<Pair<UUID, EntityRow>>() to true
        val deaths = found.filter { (world, row) -> request.world == null || near(Ui.worldName(world), row.x, row.y, row.z) }
            .map { (world, row) -> deathOf(world, row) }
        val merged = (read.runs.map(::entryOf) + deaths).sortedByDescending { it.time }
        val lines = merged.drop(query.limit * (query.page - 1)).take(query.limit)
        // A death read cut short may have older deaths behind it.
        ChatPage(lines, query.page, merged.size > query.limit * query.page || read.cut || !deathsComplete)
    }

    fun previewAs(access: Access, request: LookupRequest): CompletableFuture<PreviewResult> {
        val result = CompletableFuture<PreviewResult>()
        off {
            require(access, ROLLBACK_PERMISSION, ask)
            val query = queryOf(request.words, request.since, request.until)
            val player = playerOf(access.player)
                ?: throw PfauProtectException(PfauProtectException.OFFLINE, "A preview is shown in the game, and the player is not online.")
            val asked = System.currentTimeMillis()
            previews[access.player] = asked to PreviewResult("running")
            val left = {
                previews.remove(access.player)
                result.completeExceptionally(PfauProtectException(PfauProtectException.OFFLINE, LEFT))
            }
            // Where the player stands is theirs to read, on their own thread; the preview reads off it from there.
            val scheduled = player.scheduler.run(plugin, {
                val target = if (request.world == null) query.targetOr(lookupTargetAt(player.location))
                else query.targetOr(LookupTarget(request.world, request.x, request.y, request.z, "${request.x} ${request.y} ${request.z}"))
                startPreview(player, target, query) { done ->
                    previews[access.player] = asked to done
                    result.complete(done)
                }
            }, { left() })
            if (scheduled == null) left()
        }.whenComplete { _, failure -> if (failure != null) result.completeExceptionally(failure.cause ?: failure) }
        return result
    }

    fun previewStatusAs(access: Access): PreviewResult {
        require(access, ROLLBACK_PERMISSION, ask)
        return previewStatus(access.player)
    }

    // A ready preview stops being one once it is applied, dropped, replaced or lapses; one still running past the
    // time it would have lapsed has failed somewhere that could not say so.
    override fun previewStatus(player: UUID): PreviewResult {
        val (asked, last) = previews[player] ?: return NONE
        if (last.status == "running") return if (System.currentTimeMillis() - asked > PENDING_MILLIS) NONE else last
        val expires = last.expiresAt ?: return last
        val pending = pendingSince(player)
        return if (pending != null && pending + PENDING_MILLIS == expires) last else NONE
    }

    private fun entryOf(run: ChatRun): ChatEntry {
        val line = run.first
        val placed = line.world.isNotEmpty()
        val hit = line.hit?.let {
            HitInfo(player(line.player), player(it.victim), run.damage, line.text.ifEmpty { null }, it.confidence.name.lowercase())
        }
        return ChatEntry(
            line.timestamp, line.kind.name.lowercase(), player(line.player), line.text,
            line.world.takeIf { placed }, line.x.takeIf { placed }, line.y.takeIf { placed }, line.z.takeIf { placed }, hit, run.rows,
        )
    }

    // A death with nobody behind it (a fall, lava, a mob) has no attacker; `via` is what killed, `death` how.
    private fun deathOf(world: UUID, row: EntityRow): ChatEntry {
        val victim = player(row.uuid)
        val hit = HitInfo(row.actor?.let(::player), victim, 0f, row.via, row.confidence.name.lowercase())
        return ChatEntry(row.timestamp, "death", victim, row.death.orEmpty(), Ui.worldName(world), row.x, row.y, row.z, hit, 1)
    }

    private fun player(uuid: UUID) = PlayerRef(uuid, lookups.nameOfPlayer(uuid))

    private fun <T> off(task: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(task, threads)
}

private val NONE = PreviewResult("none")

private const val THREADS = 4

private const val LEFT = "The player left before the preview could start."

private val GLOBAL_WORD = Regex("""\b(r|radius):global\b""", RegexOption.IGNORE_CASE)

private val CHAT_KINDS = ChatKind.entries.map { it.name.lowercase() }.toSet() + "death"

/**
 * The words as `/pp lookup` reads them, with an absolute window in place of `time:` when one is given: what an
 * investigation is handed is a moment, not how long ago it was.
 */
internal fun queryOf(words: String, since: Long?, until: Long?): LookupQuery {
    val parsed = try {
        parseLookupQuery(words)
    } catch (bad: CommandSyntaxException) {
        refuse(bad.rawMessage.string)
    }
    if (since == null && until == null) return parsed
    if (parsed.hasFrom || parsed.hasUntil) refuse("Give either time: or since and until, not both.")
    if (since != null && until != null && since > until) refuse("since comes after until.")
    return parsed.copy(fromMillis = since, untilMillis = until)
}
