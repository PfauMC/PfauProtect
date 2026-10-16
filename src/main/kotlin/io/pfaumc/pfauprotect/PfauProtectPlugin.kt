package io.pfaumc.pfauprotect
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.pfaumc.pfauprotect.api.PfauProtectApi
import io.pfaumc.pfauprotect.api.PfauProtectPreLogEvent
import io.pfaumc.pfauprotect.attribution.Attributed
import io.pfaumc.pfauprotect.attribution.Attribution
import io.pfaumc.pfauprotect.capture.ChatCapture
import io.pfaumc.pfauprotect.capture.block.BlockCaptureListener
import io.pfaumc.pfauprotect.capture.block.BlockDestructionListener
import io.pfaumc.pfauprotect.capture.block.HandTouches
import io.pfaumc.pfauprotect.capture.block.PistonCycles
import io.papermc.paper.command.brigadier.ApiMirrorRootNode
import io.pfaumc.pfauprotect.capture.block.CommandBrackets
import io.pfaumc.pfauprotect.capture.entity.EntityCapture
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.ChatKind
import io.pfaumc.pfauprotect.storage.ChatLog
import io.pfaumc.pfauprotect.storage.WorldChange
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import io.pfaumc.pfauprotect.capture.block.BlockMechanismListener
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.capture.item.CommandListener
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.capture.item.ContainerCaptureListener
import io.pfaumc.pfauprotect.attribution.Energy
import io.pfaumc.pfauprotect.attribution.EntityOriginListener
import io.pfaumc.pfauprotect.attribution.EntityOrigins
import io.pfaumc.pfauprotect.capture.item.HolderListener
import io.pfaumc.pfauprotect.command.Inspector
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.capture.item.ItemUseListener
import io.pfaumc.pfauprotect.command.LookupArgument
import io.pfaumc.pfauprotect.command.LookupQuery
import io.pfaumc.pfauprotect.command.Lookups
import io.pfaumc.pfauprotect.capture.block.MechanismCaptureListener
import io.pfaumc.pfauprotect.check.Mismatch
import io.pfaumc.pfauprotect.capture.item.CopperGolemListener
import io.pfaumc.pfauprotect.capture.item.MobInventories
import io.pfaumc.pfauprotect.capture.item.MobItemListener
import io.pfaumc.pfauprotect.capture.item.NestedCaptureListener
import io.pfaumc.pfauprotect.attribution.Nudges
import io.pfaumc.pfauprotect.check.PlaneSync
import io.pfaumc.pfauprotect.capture.item.ProjectileListener
import io.pfaumc.pfauprotect.check.Reconciliation
import io.pfaumc.pfauprotect.attribution.RedstoneListener
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.check.SETTLE_MILLIS
import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.capture.item.WorldItemListener
import io.pfaumc.pfauprotect.storage.cleaningUpOnFailure
import io.pfaumc.pfauprotect.storage.fillTypeRegistries
import io.pfaumc.pfauprotect.check.heldForms
import io.pfaumc.pfauprotect.command.DIALOG_KEY
import io.pfaumc.pfauprotect.command.MAX_LIMIT
import io.pfaumc.pfauprotect.command.ROWS_KEY
import io.pfaumc.pfauprotect.command.askedBy
import io.pfaumc.pfauprotect.command.pageRows
import io.pfaumc.pfauprotect.command.readsInDialog
import io.pfaumc.pfauprotect.command.showPage
import io.pfaumc.pfauprotect.command.durationOrNull
import io.pfaumc.pfauprotect.command.lookupTargetAt
import io.pfaumc.pfauprotect.command.offThread
import io.pfaumc.pfauprotect.command.parseLookupQuery
import io.pfaumc.pfauprotect.command.targetOr
import io.pfaumc.pfauprotect.rollback.ChunkRollback
import io.pfaumc.pfauprotect.rollback.Confiscations
import io.pfaumc.pfauprotect.rollback.Rollbacks
import io.pfaumc.pfauprotect.rollback.nameOrId
import io.pfaumc.pfauprotect.capture.item.unspentDrop
import net.minecraft.server.MinecraftServer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.command.CommandSender
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import java.util.logging.Level

private const val TARGET_RANGE = 6

// What `near` covers when nobody says: enough to take in the room you are standing in, and small
// enough that a busy area still answers with the change you came to look at.
private const val NEAR_RADIUS = 5
private const val SWEEP_ENTRIES = 2000
private const val SWEEP_MINUTES = 5L
private const val SWEEP_GAPS_LOGGED = 5
private const val NOTE_MINUTES = 5L
// How long a rollback waits for the clocks to file what they held back: a tick or two, unless a region lags.
private const val HELD_WAIT_SECONDS = 2L
private const val PLANE_POSTINGS = 2000
private const val PLANE_MINUTES = 10L
private const val RECONCILE_MINUTES = 30L
private const val LOOKUP_PERMISSION = "pfauprotect.lookup"
private const val INSPECT_PERMISSION = "pfauprotect.inspect"
private const val RECONCILE_PERMISSION = "pfauprotect.reconcile"
private const val VERIFY_PERMISSION = "pfauprotect.verify"
internal const val ROLLBACK_PERMISSION = "pfauprotect.rollback"
private const val STATUS_PERMISSION = "pfauprotect.status"
private const val PURGE_PERMISSION = "pfauprotect.purge"
internal const val TELEPORT_PERMISSION = "pfauprotect.teleport"

private class Help(val permission: String, val usage: String, val typed: String, val en: String, val ru: String)

private val HELP = listOf(
    Help(LOOKUP_PERMISSION, "/pp l [user: time: radius: action: …]", "/pp l ", "what happened here, or around; with user: everywhere", "что было здесь или вокруг; с user: — везде"),
    Help(LOOKUP_PERMISSION, "/pp near [radius]", "/pp near ", "what happened around you", "что было вокруг тебя"),
    Help(INSPECT_PERMISSION, "/pp i", "/pp i", "inspector: click a block or an entity", "инспектор: клик по блоку или сущности"),
    Help(ROLLBACK_PERMISSION, "/pp rb time: [radius:] [user:]", "/pp rb ", "preview a rollback", "предпросмотр отката"),
    Help(ROLLBACK_PERMISSION, "/pp apply · /pp cancel", "/pp apply", "run or drop the preview", "выполнить или сбросить предпросмотр"),
    Help(LOOKUP_PERMISSION, "/pp chat [user: time: f:]", "/pp chat ", "chat, commands, joins and quits", "чат, команды, входы и выходы"),
    Help(LOOKUP_PERMISSION, "/pp view [chat|dialog]", "/pp view ", "where the pages open: chat or a window", "где открываются страницы: чат или окно"),
    Help(LOOKUP_PERMISSION, "/pp view rows [N]", "/pp view rows ", "lines on a page, to fit your screen", "строк на странице, под свой экран"),
    Help(STATUS_PERMISSION, "/pp status", "/pp status", "bases, queues, what went unexplained", "базы, очереди, необъяснённое"),
    Help(VERIFY_PERMISSION, "/pp verify [recent]", "/pp verify", "run the self-checks now", "самопроверки сейчас"),
    Help(RECONCILE_PERMISSION, "/pp reconcile <player>", "/pp reconcile ", "a player's inventory against the ledger", "сверка инвентаря игрока с журналом"),
    Help(PURGE_PERMISSION, "/pp purge <age> [confirm]", "/pp purge ", "delete history older than an age", "удалить историю старше возраста"),
)

// The youngest history a purge may delete: a day, so a rollback of today always has its rows.
private const val PURGE_MIN_SECONDS = 86_400L

// A pass of each self-check is bounded so it cannot walk a years-old journal in one go, and a run
// that stops on that bound says so: a check that quietly covered a fraction of the store reads as a
// clean bill of health for the whole of it.
private const val VERIFY_ROUNDS = 50

// A row written with INFERRED is a movement nobody explained: a loss the pass found no gain for, or a
// birth no mechanism claimed. Counted by cause, that is the cheapest measure there is of how much of
// the game the capture still cannot see, and the only one that keeps working on a live server.
private class Uncovered(private val ledger: RocksItemLog) {
    private val counts = ConcurrentHashMap<Cause, LongAdder>()

    fun submit(transfer: Transfer) = submit(listOf(transfer))

    fun submit(transaction: List<Transfer>) {
        for (transfer in transaction) tally(transfer)
        ledger.submit(transaction)
    }

    fun peek(): String? = format(LongAdder::sum)

    /** Takes the tally rather than reading it: what has been reported once must not be reported again. */
    fun takeTally(): String? = format(LongAdder::sumThenReset)

    private fun format(read: (LongAdder) -> Long): String? = counts.entries
        .map { (cause, count) -> cause to read(count) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .joinToString(" ") { "${it.first.name.lowercase()}=${it.second}" }
        .takeIf { it.isNotEmpty() }

    private fun tally(transfer: Transfer) {
        if (transfer.confidence != Confidence.INFERRED) return
        counts.computeIfAbsent(transfer.cause) { LongAdder() }.increment()
        Metrics.inferred.labelValues(transfer.cause.name.lowercase()).inc()
    }
}

private fun signed(difference: Int) = if (difference > 0) "+$difference" else difference.toString()

// A world keeps its history in a database of its own, opened when the world loads and closed when it
// unloads. A base reached after it was closed is a freed native handle, so nothing may hold one of
// these past the unload.
private class WorldBaseListener(private val blocks: BlockLogs) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onLoad(event: WorldLoadEvent) {
        if (event.world.name in Settings.disabledWorlds) return
        blocks.open(event.world.uid)
    }

    // A cancelled unload leaves the world running, and closing its base then would leave it running
    // with nowhere to write.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUnload(event: WorldUnloadEvent) {
        blocks.close(event.world.uid)
    }
}

// Everything the plugin owns while it is enabled. One field rather than one per part, so there is no
// state where half of them are up: either the ledger is open and all of this stands, or none of it does.
private class Running(
    val ledger: RocksItemLog,
    val blocks: BlockLogs,
    val uncovered: Uncovered,
    val codec: ItemFormCodec,
    val capture: ContainerCaptureListener,
    val mechanisms: TickCoalescer,
    val origins: SpawnOrigins,
    val lookups: Lookups,
    val inspector: Inspector,
    val reconciliation: Reconciliation,
    val planes: PlaneSync,
    val rollbacks: Rollbacks,
    val chat: ChatLog,
    // Set once the rest stands, closed before the bases.
    var metrics: MetricsEndpoint? = null,
) {
    fun drainAll() {
        ledger.drain()
        blocks.drain()
    }
}

class PfauProtectPlugin : JavaPlugin() {
    private var running: Running? = null

    override fun onEnable() {
        saveDefaultConfig()
        Settings.load(config, logger)
        val ledger = RocksItemLog(dataFolder.toPath().resolve("ledger"))
        // onDisable closes what `running` holds, and until it is set that is nothing: the ledger would keep
        // its lock file and its writer, and the next enable could not open it.
        cleaningUpOnFailure({ if (running == null) ledger.close() }) { enable(ledger) }
    }

    private fun enable(ledger: RocksItemLog) {
        val energy = Energy()
        val nudges = Nudges()
        val touches = HandTouches()
        val pistons = PistonCycles()
        // A row that names somebody is what an observer or a comparator next to it answers to, and any
        // row at all is what a touch waiting to be read back leaves to the capture that filed it.
        val blocks = BlockLogs(dataFolder.toPath().resolve("blocks"), ledger, { world, changes ->
            touches.filed(world, changes)
            for (change in changes) {
                val actor = change.actor ?: continue
                energy.note(WorldBlock(world, change.x, change.y, change.z), Attributed(actor, change.confidence))
            }
        }) { world, change ->
            // A clock's move held back over this position goes in first, or it would be filed behind this row.
            for (write in pistons.heldOver(WorldBlock(world, change.x, change.y, change.z))) write()
            preLog(world, change)
        }
        val attribution = Attribution(ledger.registries, blocks)
        val uncovered = Uncovered(ledger)
        val codec = ItemFormCodec(ledger.registries, MinecraftServer.getServer().registryAccess())
        val mechanisms = TickCoalescer(uncovered::submit)
        val origins = SpawnOrigins(mechanisms)
        val capture = ContainerCaptureListener(
            this, uncovered::submit, codec, ledger.registries, origins, ledger, ledger::slotBalances,
        ) { intent, qty ->
            unspentDrop(mechanisms, intent, qty) { server.getPlayer(it)?.gameMode == GameMode.CREATIVE }
        }
        val entities = EntityOrigins()
        val destruction = BlockDestructionListener(
            this, ledger.registries, blocks, attribution, codec, origins, entities, ledger, ledger, uncovered::submit,
            energy, touches, pistons, ::onRegion,
        )
        val lookups = Lookups(this, ledger, blocks, codec)
        val inspector = Inspector(lookups)
        val worldItems = WorldItemListener(codec, mechanisms, origins, capture, attribution, entities)
        val entityCapture = EntityCapture(
            blocks, origins, attribution, entities,
            later = ::onRegion,
            // A null task means the entity was gone before it could be scheduled, and neither ever runs.
            laterOn = { entity, task, retired -> entity.scheduler.run(this, { task() }, retired) ?: retired() },
            offThread = { task -> server.asyncScheduler.runNow(this) { task() } },
        )
        val confiscations = Confiscations(this, ledger, codec, capture, worldItems, uncovered::submit)
        val chunkRollback = ChunkRollback(
            this, codec, blocks, ledger, uncovered::submit, formOf = ledger::form, forget = entityCapture::forget,
        )
        val rollbacks = Rollbacks(
            this, ledger, blocks, lookups, chunkRollback, confiscations,
            holdStill = { fileHeld(pistons) }, letGo = pistons::resume,
        )
        val chat = ChatLog(dataFolder.toPath().resolve("chat"))
        val running = Running(
            ledger, blocks, uncovered, codec, capture, mechanisms, origins,
            lookups, inspector, Reconciliation(ledger), PlaneSync(ledger, blocks), rollbacks, chat,
        )
        this.running = running
        ledger.registries.batched { fillTypeRegistries(ledger.registries) }
        listen(WorldBaseListener(blocks))
        // Enabling after startup, every world is already loaded and none of them will ever raise the
        // load event again.
        for (world in server.worlds) if (world.name !in Settings.disabledWorlds) blocks.open(world.uid)
        listen(
            // A change to a world with no base open is dropped rather than journalled, so this goes after
            // the load handler and after the bases opened by hand. Against the other handlers of equal
            // priority the order is free: nothing it reads is written by any of them.
            BlockCaptureListener(blocks, attribution, touches, ::onBlock),
            destruction,
            EntityOriginListener(attribution, entities),
            RedstoneListener(energy, blocks, entities, nudges, attribution),
            // After the attribution of entities, whose notes it reads for who brought a mob in, and before the
            // item capture, which takes the marks off a dying mob's slots that its snapshot has to keep.
            entityCapture,
            capture,
            ItemUseListener(capture, codec),
            ProjectileListener(capture, codec, mechanisms, origins),
        )
        val inventories = MobInventories(codec, mechanisms, ledger, uncovered::submit, ::onEntity)
        listen(
            inventories,
            CopperGolemListener(codec, uncovered::submit) { golem, look ->
                golem.scheduler.runAtFixedRate(this, { task -> if (!look()) task.cancel() }, null, 1, 1)
            },
            MobItemListener(codec, mechanisms, origins, inventories, ::onRegion),
            CommandListener(capture, codec, origins, ::onEntity),
            HolderListener(capture, codec, origins, ::onBlock),
            MechanismCaptureListener(codec, mechanisms),
            // Breaking a shulker box, the nested capture writes the owner mark onto the stack that was
            // just dropped and the block capture then reads the form of that same stack. Handlers of equal
            // priority run in the order they were registered, so turning these two around would file the
            // drop under a form that has no owner on it and the chain of custody would end at the break.
            NestedCaptureListener(ledger, codec, mechanisms),
            BlockMechanismListener(
                codec, mechanisms, origins, ledger, uncovered::submit, energy, entities, ledger, capture::intend,
                ::onBlock,
            ),
            worldItems,
            // After the capture's own join handler, whose starting point for the player's first pass the
            // items taken back on the join have to come after.
            confiscations,
            inspector,
            rollbacks.ghosts,
            ChatCapture(chat),
        )
        server.servicesManager.register(PfauProtectApi::class.java, Api(running), this, ServicePriority.Normal)
        server.globalRegionScheduler.runAtFixedRate(this, {
            origins.sweep()
            attribution.sweepRemovals()
            mechanisms.flush()
            destruction.settle()
        }, 1, 1)
        fun every(period: Long, unit: TimeUnit = TimeUnit.MINUTES, task: () -> Unit) =
            server.asyncScheduler.runAtFixedRate(this, { task() }, period, period, unit)
        every(NOTE_MINUTES) {
            attribution.sweep()
            entities.sweep()
            energy.sweep()
            nudges.sweep()
        }
        every(SWEEP_MINUTES) { Metrics.checks.labelValues("sweep").startTimer().use { logPass { checkLedger(ledger, 1, it) } } }
        every(PLANE_MINUTES) { if (isEnabled) Metrics.checks.labelValues("planes").startTimer().use { logPass { checkPlanes(running.planes, 1, false, it) } } }
        every(RECONCILE_MINUTES) { reconcileEveryone() }
        every(1, TimeUnit.DAYS) { reportUncovered(uncovered) }
        warnAboutSilencedHoppers()
        registerCommand(CommandBrackets(this, blocks, codec, ledger, uncovered::submit))
        if (Settings.metricsPort > 0) {
            // A port already taken costs the scrape, not the history: everything else is up by now.
            running.metrics = runCatching { metricsEndpoint(running) }
                .onFailure { logger.log(Level.SEVERE, "metrics could not listen on ${Settings.metricsAddress}:${Settings.metricsPort}", it) }
                .getOrNull()
        }
        logger.info(
            "ledger open, ${blocks.worlds.size} world bases, registry sizes: " +
                RegistryNamespace.entries.joinToString { "${it.name.lowercase()}=${ledger.registries.size(it)}" }
        )
        for (warning in ledger.registries.fillWarnings()) logger.warning(warning)
    }

    // Players are still online here and their pending region tasks are already cancelled, so the last
    // interaction of every open view is diffed before the queue is drained.
    override fun onDisable() {
        val running = this.running ?: return
        try {
            running.capture.recomputeAll()
            running.origins.sweep()
            running.mechanisms.flush()
            running.ledger.drain()
            reportUncovered(running.uncovered)
            // Throwable and not Exception: a jar swapped under a running server turns an unloaded
            // class into a NoClassDefFoundError here, and the diagnostic is worth more on exactly the
            // shutdowns that go wrong.
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "the ledger lost entries while shutting down", failure)
        } finally {
            // The bases go first: their writers intern state numbers and payloads through the ledger. A
            // base that fails to close must not take the ledger down with it, or a held lock file keeps
            // the plugin from being enabled again without a server restart.
            closeReporting("the metrics endpoint") { running.metrics?.close() }
            closeReporting("the world block bases") { running.blocks.close() }
            closeReporting("the ledger") { running.ledger.close() }
            closeReporting("the chat log") { running.chat.close() }
            this.running = null
        }
    }

    // Each base by name: the ledger, the chat log and every world whose history is open.
    private fun metricsEndpoint(running: Running): MetricsEndpoint {
        val folder = dataFolder.toPath()
        return MetricsEndpoint(Settings.metricsAddress, Settings.metricsPort, {
            val worlds = running.blocks.worlds.mapNotNull { uid ->
                val log = running.blocks.get(uid) ?: return@mapNotNull null
                "world:${server.getWorld(uid)?.name ?: uid}" to
                    MetricsEndpoint.Base(folder.resolve("blocks").resolve(uid.toString()), log.backlog, log.estimatedRows(), log.compactionDebt())
            }
            mapOf(
                "ledger" to MetricsEndpoint.Base(folder.resolve("ledger"), running.ledger.backlog, running.ledger.estimatedRows(), running.ledger.compactionDebt()),
                "chat" to MetricsEndpoint.Base(folder.resolve("chat"), 0, emptyMap()),
            ) + worlds
        }) { running.rollbacks.runningSince() != null }
    }

    private fun closeReporting(what: String, close: () -> Unit) {
        try {
            close()
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "$what could not be closed", failure)
        }
    }

    private fun reportUncovered(uncovered: Uncovered) {
        val tally = uncovered.takeTally() ?: return
        logger.info("movements the capture could not explain: $tally")
    }

    // A periodic pass that found something is a warning; a clean one is only the news that it came round.
    private fun logPass(pass: ((String) -> Unit) -> Boolean) {
        val lines = ArrayList<String>()
        val level = if (pass { lines += it }) Level.WARNING else Level.INFO
        for (line in lines) logger.log(level, line)
    }

    // A gap means one end of a movement was written and the other was not, which is a hole in the
    // capture rather than anything a player did. The periodic pass is one round, reported as it is found
    // rather than once the ledger is already being read in anger; /pp verify runs rounds to the end.
    private fun checkLedger(ledger: RocksItemLog, rounds: Int, say: (String) -> Unit): Boolean {
        val reports = inRounds(rounds, { it.reachedEnd }) { ledger.sweep(SWEEP_ENTRIES) }
        val checked = reports.sumOf { it.checked }
        val unreadable = reports.sumOf { it.unreadable }
        val gaps = reports.flatMap { it.gaps }
        val reachedEnd = reports.last().reachedEnd
        // Rows this build cannot decode are not rows without gaps. Left unsaid, a ledger that has
        // become unreadable would keep reporting clean passes.
        val found = gaps.isNotEmpty() || unreadable > 0
        val whole = rounds > 1
        if (!whole && !found && !(reachedEnd && checked > 0)) return false
        say("Transaction invariant: $checked entries, ${gaps.size} gaps, $unreadable unreadable.")
        sayGaps(gaps, say) { "  gap: $it" }
        if (whole && !reachedEnd) say("  stopped on the round limit; run it again to cover the rest.")
        return found
    }

    // A position where the two planes disagree is a change that reached one of them and not the other,
    // which from inside either plane on its own reads as perfectly consistent.
    private fun checkPlanes(planes: PlaneSync, rounds: Int, judgeRecent: Boolean, say: (String) -> Unit): Boolean {
        // Reading as though a settle window's worth of time had already passed is what lets a position
        // touched a moment ago be judged at all.
        val now = System.currentTimeMillis() + if (judgeRecent) SETTLE_MILLIS else 0
        val reports = inRounds(rounds, { it.reachedEnd }) { planes.pass(PLANE_POSTINGS, now) }
        val checked = reports.sumOf { it.checked }
        val unrecorded = reports.sumOf { it.unrecorded }
        val settling = reports.sumOf { it.settling }
        val unreadable = reports.sumOf { it.unreadable }
        val overdrawn = reports.sumOf { it.overdrawn }
        val gaps = reports.flatMap { it.gaps }
        val reachedEnd = reports.last().reachedEnd
        // Positions this build cannot read are not positions that agree, and overdrawn ones — the item
        // plane took items out of a position it was never told held any — are as much a hole as a gap.
        val found = gaps.isNotEmpty() || unreadable > 0 || overdrawn > 0
        val whole = rounds > 1
        // A last page of nothing but positions too fresh to judge is still the end of the cycle; a
        // busy piston clock keeps its own positions fresh for ever, and staying quiet then read as a
        // cursor that never came round.
        if (!whole && !found && !(reachedEnd && (checked > 0 || unrecorded > 0 || settling > 0))) return false
        say(
            "Two planes: $checked positions compared, ${gaps.size} gaps, $overdrawn overdrawn, " +
                "$unrecorded the block plane never recorded, $settling too recent to judge, " +
                "$unreadable unreadable."
        )
        sayGaps(gaps, say) { gap ->
            val world = server.getWorld(gap.at.world)?.name ?: gap.at.world.toString()
            "  gap in $world at ${gap.at.x} ${gap.at.y} ${gap.at.z}: ${gap.standing} stands there, " +
                "the item plane holds ${gap.fact} confirmed and ${gap.inferred} inferred"
        }
        if (whole && settling > 0 && !judgeRecent) {
            say("  $settling positions were touched too recently; 'verify recent' judges them too.")
        }
        if (whole && !reachedEnd) say("  stopped on the round limit; run it again to cover the rest.")
        return found
    }

    private inline fun <R> inRounds(rounds: Int, reachedEnd: (R) -> Boolean, round: () -> R): List<R> =
        buildList { do add(round()) while (size < rounds && !reachedEnd(last())) }

    private inline fun <T> sayGaps(gaps: List<T>, say: (String) -> Unit, line: (T) -> String) {
        for (gap in gaps.take(SWEEP_GAPS_LOGGED)) say(line(gap))
        if (gaps.size > SWEEP_GAPS_LOGGED) say("  ... and ${gaps.size - SWEEP_GAPS_LOGGED} more")
    }

    // With the move event switched off the server stops telling anyone that a hopper moved anything,
    // and the ledger goes quiet about automated transfers without a single error to show for it.
    private fun warnAboutSilencedHoppers() {
        val silenced = server.worlds.filter { (it as CraftWorld).handle.paperConfig().hopper.disableMoveEvent }
        if (silenced.isEmpty()) return
        logger.warning(
            "hopper transfers are not logged in ${silenced.joinToString { it.name }}: " +
                "hopper.disable-move-event is on in the paper world config"
        )
    }

    private fun registerCommand(brackets: CommandBrackets) {
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val root = Commands.literal("pfauprotect").executes { help(it.source) }
            root.then(Commands.literal("help").executes { help(it.source) })
            for (alias in listOf("lookup", "l")) root.then(lookupNode(alias))
            for (alias in listOf("near", "n")) root.then(nearNode(alias))
            for (alias in listOf("inspect", "i")) root.then(inspectNode(alias))
            for (alias in listOf("reconcile", "r")) root.then(reconcileNode(alias))
            for (alias in listOf("verify", "v")) root.then(verifyNode(alias))
            for (alias in listOf("rollback", "rb")) root.then(rollbackNode(alias))
            root.then(
                gated("purge", PURGE_PERMISSION)
                    .then(
                        Commands.argument("age", StringArgumentType.word())
                            .executes { purge(it.source, StringArgumentType.getString(it, "age"), confirm = false) }
                            .then(Commands.literal("confirm").executes { purge(it.source, StringArgumentType.getString(it, "age"), confirm = true) })
                    )
            )
            root.then(
                gated("chat", LOOKUP_PERMISSION)
                    .executes { chat(it.source, "") }
                    .then(Commands.argument("words", StringArgumentType.greedyString()).executes { chat(it.source, StringArgumentType.getString(it, "words")) })
            )
            root.then(gated("status", STATUS_PERMISSION).executes { status(it.source) })
            root.then(
                Commands.literal("view")
                    .requires { it.sender.hasPermission(LOOKUP_PERMISSION) && it.executor is Player }
                    .executes { view(it.source, null) }
                    .then(Commands.literal("chat").executes { view(it.source, false) })
                    .then(Commands.literal("dialog").executes { view(it.source, true) })
                    .then(
                        Commands.literal("rows").executes { rows(it.source, null) }
                            .then(Commands.argument("rows", IntegerArgumentType.integer(1, MAX_LIMIT)).executes { rows(it.source, IntegerArgumentType.getInteger(it, "rows")) })
                    )
            )
            root.then(teleportNode())
            root.then(
                gated("apply", ROLLBACK_PERMISSION)
                    .executes { withRunning(it.source) { running, sender -> running.rollbacks.applyPreview(sender) } }
            )
            root.then(
                gated("cancel", ROLLBACK_PERMISSION)
                    .executes { withRunning(it.source) { running, sender -> running.rollbacks.cancelPreview(sender) } }
            )
            event.registrar().register(root.build(), "Item ledger lookup and inspector", listOf("pp"))
            // The API hands out a mirror of the dispatcher; the nodes the server executes are behind it.
            (event.registrar().dispatcher.root as? ApiMirrorRootNode)?.dispatcher?.let(brackets::wrap)
        }
    }

    /** What each subcommand is for, only the ones the sender may run; a click types the command in. */
    private fun help(source: CommandSourceStack): Int {
        val sender = source.sender
        sender.sendMessage(Ui.text("PfauProtect", Ui.WHO))
        for (entry in HELP) {
            if (!sender.hasPermission(entry.permission)) continue
            val line = Component.text().append(Ui.text("  "))
                .append(Ui.hover(Ui.text(entry.usage), tr("Click to type it", "Клик — подставить")).clickEvent(ClickEvent.suggestCommand(entry.typed)))
                .append(Ui.text("  " + tr(entry.en, entry.ru), Ui.MUTED))
            sender.sendMessage(line.build())
        }
        return Command.SINGLE_SUCCESS
    }

    private fun gated(literal: String, permission: String) =
        Commands.literal(literal).requires { it.sender.hasPermission(permission) }

    private fun lookupNode(literal: String) = gated(literal, LOOKUP_PERMISSION)
        .executes { lookup(it.source, LookupQuery()) }
        .then(
            Commands.argument("query", LookupArgument())
                .executes { lookup(it.source, it.getArgument("query", LookupQuery::class.java)) }
        )

    // Centred on the player rather than on the block they are looking at, which is what makes it the
    // command to reach for when something has just happened around you and there is nothing left
    // standing to point at.
    private fun nearNode(literal: String) = gated(literal, LOOKUP_PERMISSION)
        .executes { near(it.source, NEAR_RADIUS) }
        .then(
            Commands.argument("radius", IntegerArgumentType.integer(0, Settings.maxRadius))
                .executes { near(it.source, it.getArgument("radius", Integer::class.java).toInt()) }
        )

    private fun inspectNode(literal: String) = gated(literal, INSPECT_PERMISSION)
        .executes { inspect(it.source, null) }
        .then(Commands.literal("on").executes { inspect(it.source, true) })
        .then(Commands.literal("off").executes { inspect(it.source, false) })

    // The scheduled passes run minutes apart, too slow to wait on by hand; this runs them now, to the end.
    private fun verifyNode(literal: String) = gated(literal, VERIFY_PERMISSION)
        .executes { verify(it.source, judgeRecent = false) }
        .then(Commands.literal("recent").executes { verify(it.source, judgeRecent = true) })

    // Centred like `near`.
    private fun rollbackNode(literal: String) = gated(literal, ROLLBACK_PERMISSION)
        .executes { context ->
            withRunning(context.source) { running, sender ->
                running.rollbacks.preview(sender, lookupTargetAt(context.source.location), LookupQuery())
            }
        }
        .then(
            Commands.argument("query", LookupArgument(rollback = true))
                .executes { context ->
                    val query = context.getArgument("query", LookupQuery::class.java)
                    withRunning(context.source) { running, sender ->
                        running.rollbacks.preview(sender, query.targetOr(lookupTargetAt(context.source.location)), query)
                    }
                }
        )

    // What the mark on a lookup line runs: to where the row happened, in the world it happened in.
    private fun teleportNode() = Commands.literal("tp")
        .requires { it.sender.hasPermission(TELEPORT_PERMISSION) && it.executor is Player }
        .then(
            Commands.argument("position", ArgumentTypes.blockPosition())
                .executes { teleport(it, null) }
                .then(
                    Commands.argument("world", StringArgumentType.word())
                        .suggests { _, builder -> server.worlds.forEach { builder.suggest(it.name) }; builder.buildFuture() }
                        .executes { teleport(it, StringArgumentType.getString(it, "world")) }
                )
        )

    private fun teleport(context: CommandContext<CommandSourceStack>, worldName: String?): Int {
        val player = context.source.executor as? Player ?: return 0
        val world = if (worldName == null) player.world else server.getWorld(worldName)
        if (world == null) {
            context.source.sender.say("Unknown world: $worldName")
            return 0
        }
        val at = context.getArgument("position", BlockPositionResolver::class.java).resolve(context.source)
        player.teleportAsync(Location(world, at.blockX() + 0.5, at.blockY().toDouble(), at.blockZ() + 0.5, player.location.yaw, player.location.pitch))
        return Command.SINGLE_SUCCESS
    }

    // Inline so that a refusal inside `action` can return its own 0 from the handler.
    private inline fun withRunning(source: CommandSourceStack, action: (Running, CommandSender) -> Unit): Int {
        val running = this.running ?: run {
            source.sender.say("The ledger is not open.")
            return 0
        }
        action(running, source.sender)
        return Command.SINGLE_SUCCESS
    }

    private fun reconcileNode(literal: String) = gated(literal, RECONCILE_PERMISSION)
        .executes { reconcile(it.source, it.source.executor as? Player) }
        .then(
            Commands.argument("player", ArgumentTypes.player())
                .executes { context ->
                    val resolver = context.getArgument("player", PlayerSelectorArgumentResolver::class.java)
                    reconcile(context.source, resolver.resolve(context.source).first())
                }
        )

    private fun lookup(source: CommandSourceStack, query: LookupQuery): Int = withRunning(source) { running, sender ->
        // The aimed-at block wins only when run from the player's own position: `/execute positioned` and the
        // console name a place of their own, and looking past the last block falls back to where one stands.
        val player = (source.executor as? Player)?.takeIf { it.world == source.location.world && it.location.distanceSquared(source.location) < 1e-6 }
        val aimed = player?.getTargetBlockExact(TARGET_RANGE)
        running.lookups.run(sender, query.targetOr(aimed?.let(::lookupTargetAt) ?: lookupTargetAt(source.location)), query)
    }

    private fun near(source: CommandSourceStack, radius: Int): Int = withRunning(source) { running, sender ->
        running.lookups.run(sender, lookupTargetAt(source.location), LookupQuery(radius = radius, words = "r:$radius"))
    }

    private fun inspect(source: CommandSourceStack, desired: Boolean?): Int = withRunning(source) { running, sender ->
        val player = source.executor as? Player
        if (player == null) {
            sender.say("Only a player can use the inspector.")
            return 0
        }
        val now = running.inspector.toggle(player, desired)
        // Above the hotbar rather than in chat: it is a state, not a message, and chat is where the answers go.
        player.sendActionBar(
            if (now) Ui.text(tr("Inspector on: left click — the block, right click — the place in front, click an entity — the entity", "Инспектор включён: ЛКМ — блок, ПКМ — место перед гранью, клик по сущности — сущность"), Ui.GAINED)
            else Ui.text(tr("Inspector off", "Инспектор выключен"), Ui.MUTED)
        )
    }

    private fun purge(source: CommandSourceStack, age: String, confirm: Boolean): Int = withRunning(source) { running, sender ->
        val seconds = durationOrNull(age)
        if (seconds == null || seconds < PURGE_MIN_SECONDS) {
            sender.say("Purge refused: give an age of at least a day, for example 90d.")
            return 0
        }
        if (running.rollbacks.runningSince() != null) {
            sender.say("Purge refused: a rollback is running.")
            return 0
        }
        if (confirm) sender.say("Purging everything older than $age; this walks the whole journal.")
        else sender.say("Counting what a purge of everything older than $age would delete.")
        offThread(sender, "the purge failed", "The purge failed; the server log has the details.") {
            val cutoff = System.currentTimeMillis() - seconds * 1000
            running.drainAll()
            val (entries, openings) = running.ledger.purgeBefore(cutoff, dryRun = !confirm)
            var rows = 0
            var entities = 0
            for (world in running.blocks.worlds) {
                val (r, e) = running.blocks.get(world)?.purgeBefore(cutoff, dryRun = !confirm) ?: continue
                rows += r
                entities += e
            }
            val counts = "$entries item rows, $rows block rows, $entities entity rows; $openings opening balances"
            if (confirm) sender.say("Purged $counts written.")
            else sender.say("A purge would delete $counts to write. /pp purge $age confirm runs it.")
        }
    }

    // A clock's moves held back are filed on the region that owns the piston, and a reader waits for them a
    // moment: a chunk unloaded meanwhile never runs its task, and its piston's journal stays a move behind.
    // False when some did not come in time.
    private fun fileHeld(pistons: PistonCycles): Boolean {
        val bases = pistons.pause()
        val done = CountDownLatch(bases.size)
        for (base in bases) {
            val world = server.getWorld(base.world)
            if (world == null) {
                done.countDown()
                continue
            }
            server.regionScheduler.execute(this, world, base.x shr 4, base.z shr 4) {
                try {
                    for (write in pistons.takeHeld(base)) write()
                } finally {
                    done.countDown()
                }
            }
        }
        if (done.await(HELD_WAIT_SECONDS, TimeUnit.SECONDS)) return true
        logger.warning("${done.count} of ${bases.size} pistons did not file the moves they held back in time")
        return false
    }

    // Another plugin may veto a row before it is written; nobody listening, nothing is asked.
    private fun preLog(world: UUID, change: WorldChange): Boolean {
        if (PfauProtectPreLogEvent.getHandlerList().registeredListeners.isEmpty()) return true
        val event = PfauProtectPreLogEvent(world, change.x, change.y, change.z, change.cause.name, change.actor, !server.isPrimaryThread)
        server.pluginManager.callEvent(event)
        return !event.isCancelled
    }

    private fun chat(source: CommandSourceStack, words: String): Int = withRunning(source) { running, sender ->
        val query = try {
            parseLookupQuery(words)
        } catch (failure: CommandSyntaxException) {
            // The message alone: the rest is Brigadier's "at position" in English, about a line not typed here.
            sender.sendMessage(failure.rawMessage.string)
            return 0
        }
        // Read here: the lines are drawn off the region threads, where the Bukkit API is not asked.
        val asked = query.askedBy(sender)
        offThread(sender, "the chat lookup failed", "The chat lookup failed; the server log has the details.") {
            val users = running.lookups.resolveAll(sender, asked.users) ?: return@offThread
            val text = asked.filter?.lowercase()
            // `wanted` reads past this page, which is how it knows there is a next one.
            val read = running.chat.read(asked.fromTs(), asked.toTs(), asked.wanted) { line ->
                (users.isEmpty() || line.player in users) && (text == null || text in line.text.lowercase())
            }
            val lines = read.drop(asked.limit * (asked.page - 1)).take(asked.limit)
            if (lines.isEmpty()) return@offThread sender.say("Nothing said or run matches.")
            val drawn = lines.map { line ->
                val who = Ui.text(nameOrId(line.player), Ui.WHO)
                val what = when (line.kind) {
                    ChatKind.CHAT -> Ui.text(": " + line.text)
                    ChatKind.COMMAND -> Ui.text(" " + line.text, Ui.CHANGED)
                    ChatKind.JOIN -> Ui.text(tr(" joined", " · вход"), Ui.GAINED)
                    ChatKind.QUIT -> Ui.text(tr(" left", " · выход"), Ui.MUTED)
                }
                val out = Component.text().append(Ui.text(" ")).append(Ui.ago(line.timestamp)).append(Ui.text("  ")).append(who).append(what)
                if (line.world.isNotEmpty()) out.append(Ui.text("  ")).append(Ui.place(line.world, line.x, line.y, line.z, asked.teleports))
                out.build()
            }
            showPage(
                sender, asked, Ui.text(tr("PfauProtect · chat and commands", "PfauProtect · чат и команды"), Ui.FAINT), drawn, emptyList(),
                back = asked.pageCommand("chat", asked.page - 1).takeIf { asked.page > 1 },
                forward = asked.pageCommand("chat", asked.page + 1).takeIf { read.size > asked.limit * asked.page },
            )
        }
    }

    // How many lines this player's pages hold when no limit: is said, kept with them; bare, the config's again.
    private fun rows(source: CommandSourceStack, rows: Int?): Int {
        val player = source.executor as? Player ?: return 0
        if (rows == null) player.persistentDataContainer.remove(ROWS_KEY) else player.persistentDataContainer.set(ROWS_KEY, PersistentDataType.INTEGER, rows)
        val now = pageRows(player)
        player.sendActionBar(Ui.text(tr("Lines on a page: $now", "Строк на странице: $now"), Ui.MUTED))
        return Command.SINGLE_SUCCESS
    }

    // Where this player's pages open, kept with them across restarts; bare, it switches to the other.
    private fun view(source: CommandSourceStack, dialog: Boolean?): Int {
        val player = source.executor as? Player ?: return 0
        val now = dialog ?: !readsInDialog(player)
        player.persistentDataContainer.set(DIALOG_KEY, PersistentDataType.BOOLEAN, now)
        // Above the hotbar like the inspector's: a state, not an answer.
        player.sendActionBar(Ui.text(if (now) tr("Pages open in a window", "Страницы открываются в окне") else tr("Pages go to chat", "Страницы идут в чат"), Ui.MUTED))
        return Command.SINGLE_SUCCESS
    }

    private fun status(source: CommandSourceStack): Int = withRunning(source) { running, sender ->
        offThread(sender, "the status failed", "The status failed; the server log has the details.") {
            val folder = dataFolder.toPath()
            sender.sendMessage(Ui.text(tr("PfauProtect · status", "PfauProtect · состояние"), Ui.FAINT))
            sender.say("  ledger ${megabytes(folder.resolve("ledger"))}, ${running.ledger.backlog} transactions waiting to be written")
            for (world in server.worlds) {
                val log = running.blocks.get(world.uid)
                sender.say(
                    "  ${world.name}: " + if (log == null) "not recorded (config.yml)"
                    else "${megabytes(folder.resolve("blocks").resolve(world.uid.toString()))}, ${log.backlog} changes waiting to be written"
                )
            }
            sender.say("  unexplained since the last report: ${running.uncovered.peek() ?: "nothing"}")
            for (warning in running.ledger.registries.fillWarnings()) sender.say("  $warning")
            val since = running.rollbacks.runningSince()
            if (since == null) sender.say("  rollback: none running")
            else sender.say("  rollback: one running for ${(System.currentTimeMillis() - since) / 1000} s")
        }
    }

    private fun megabytes(dir: Path): String = "%.1f MB".format(Locale.ROOT, bytesUnder(dir) / 1_048_576.0)

    // Waits for every writer first, so a hand-run check does not report the last thing done as a finding.
    private fun verify(source: CommandSourceStack, judgeRecent: Boolean): Int = withRunning(source) { running, sender ->
        sender.say("Running both self-checks to the end; this reads the whole journal.")
        offThread(sender, "the self-checks failed", "The self-checks failed; the server log has the details.") {
            running.drainAll()
            checkLedger(running.ledger, VERIFY_ROUNDS, sender::say)
            checkPlanes(running.planes, VERIFY_ROUNDS, judgeRecent, sender::say)
        }
    }

    private fun reconcile(source: CommandSourceStack, target: Player?): Int = withRunning(source) { running, sender ->
        if (target == null) {
            sender.say("Name the player to reconcile.")
            return 0
        }
        compare(target) { mismatches ->
            if (mismatches.isEmpty()) return@compare sender.say("${target.name} matches the ledger; nothing differs.")
            sender.say("${target.name} differs from the ledger on ${mismatches.size} forms:")
            for (mismatch in mismatches) sender.say("  ${running.reconciliation.name(mismatch.form)} ${signed(mismatch.difference)}")
            sender.say(
                "Anything held before the ledger was opened differs by exactly that much for ever; " +
                    "a difference that stays put is that constant rather than a leak."
            )
        }
    }

    // The slots may only be read on the region that ticks the player, and RocksDB has no business
    // running out there, so the two halves of a reconciliation are two hops instead of one task.
    private fun compare(player: Player, andThen: (List<Mismatch>) -> Unit) {
        val running = this.running ?: return
        player.scheduler.run(this, {
            // The pass is the only writer for a player's own slots and it runs a tick behind the
            // events that ask for it, so the slots are read here against a ledger that has not been
            // told about the last thing the player did. Running the pass first is what makes the two
            // sides the same moment; without it every recent movement reads as a difference.
            running.capture.recompute(player)
            val held = heldForms(player, running.codec)
            server.asyncScheduler.runNow(this) {
                // The writer runs on a thread of its own, and what it has not written yet is missing
                // from the balance the comparison is about to read.
                running.ledger.drain()
                andThen(running.reconciliation.compare(player.uniqueId, held))
            }
        }, null)
    }

    // A hole in the capture gives itself away by being systematic: the same form, the same direction,
    // on many players at once. That shape only shows on the flow of live data, so the pass runs on
    // everyone and says nothing about the players who balance.
    private fun reconcileEveryone() {
        val reconciliation = running?.reconciliation ?: return
        if (!isEnabled) return
        for (player in server.onlinePlayers) {
            compare(player) { mismatches ->
                if (mismatches.isNotEmpty()) {
                    logger.info(
                        "reconcile ${player.name}: " +
                            mismatches.joinToString { "${reconciliation.name(it.form)} ${signed(it.difference)}" }
                    )
                }
            }
        }
    }

    private fun onRegion(at: Location, task: () -> Unit) {
        server.regionScheduler.run(this, at) { task() }
    }

    private fun onBlock(block: Block, task: () -> Unit) = onRegion(block.location, task)

    private fun onEntity(entity: Entity, task: () -> Unit) {
        entity.scheduler.run(this, { task() }, null)
    }

    private fun listen(vararg listeners: Listener) = listeners.forEach { server.pluginManager.registerEvents(it, this) }
}

private class Api(private val running: Running) : PfauProtectApi {
    override fun lookup(at: Location, words: String): CompletableFuture<List<String>> {
        val lines = Collections.synchronizedList(ArrayList<String>())
        val sender = Bukkit.createCommandSender { lines += PlainTextComponentSerializer.plainText().serialize(it) }
        return CompletableFuture.supplyAsync {
            val query = parseLookupQuery(words)
            running.lookups.report(sender, lookupTargetAt(at), query)
            lines.toList()
        }
    }

    override fun previewRollback(sender: CommandSender, at: Location, words: String) =
        running.rollbacks.preview(sender, lookupTargetAt(at), parseLookupQuery(words))

    override fun logChange(actor: UUID?, block: Block, before: BlockData, after: BlockData) {
        val log = running.blocks.get(block.world.uid) ?: return
        log.submit(listOf(BlockChange(block.x, block.y, block.z, before.asString, after.asString, Cause.BLK_PLUGIN, actor = actor)))
    }
}
