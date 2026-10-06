package io.pfaumc.pfauprotect
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.pfaumc.pfauprotect.attribution.Attributed
import io.pfaumc.pfauprotect.attribution.Attribution
import io.pfaumc.pfauprotect.capture.block.BlockCaptureListener
import io.pfaumc.pfauprotect.capture.block.BlockDestructionListener
import io.pfaumc.pfauprotect.capture.block.HandTouches
import io.papermc.paper.command.brigadier.ApiMirrorRootNode
import io.pfaumc.pfauprotect.capture.block.CommandBrackets
import io.pfaumc.pfauprotect.capture.entity.EntityCapture
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.ChatKind
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
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
import io.pfaumc.pfauprotect.check.PlaneGap
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
import io.pfaumc.pfauprotect.storage.fillTypeRegistries
import io.pfaumc.pfauprotect.check.heldForms
import io.pfaumc.pfauprotect.attribution.inferred
import io.pfaumc.pfauprotect.command.lookupTargetAt
import io.pfaumc.pfauprotect.command.targetOr
import io.pfaumc.pfauprotect.rollback.ChunkRollback
import io.pfaumc.pfauprotect.rollback.Confiscations
import io.pfaumc.pfauprotect.rollback.Rollbacks
import io.pfaumc.pfauprotect.capture.item.unspentDrop
import net.minecraft.server.MinecraftServer
import org.bukkit.GameMode
import org.bukkit.command.CommandSender
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
private const val PLANE_POSTINGS = 2000
private const val PLANE_MINUTES = 10L
private const val RECONCILE_MINUTES = 30L
private const val LOOKUP_PERMISSION = "pfauprotect.lookup"
private const val INSPECT_PERMISSION = "pfauprotect.inspect"
private const val RECONCILE_PERMISSION = "pfauprotect.reconcile"
private const val VERIFY_PERMISSION = "pfauprotect.verify"
private const val ROLLBACK_PERMISSION = "pfauprotect.rollback"
private const val STATUS_PERMISSION = "pfauprotect.status"
private const val PURGE_PERMISSION = "pfauprotect.purge"
private const val TELEPORT_PERMISSION = "pfauprotect.teleport"

private class Help(val permission: String, val usage: String, val typed: String, val en: String, val ru: String)

private val HELP = listOf(
    Help(LOOKUP_PERMISSION, "/pp l [user: time: radius: action: …]", "/pp l ", "what happened here, or around", "что было здесь или вокруг"),
    Help(LOOKUP_PERMISSION, "/pp near [radius]", "/pp near ", "what happened around you", "что было вокруг тебя"),
    Help(INSPECT_PERMISSION, "/pp i", "/pp i", "inspector: click a block or an entity", "инспектор: клик по блоку или сущности"),
    Help(ROLLBACK_PERMISSION, "/pp rb time: radius: [user:]", "/pp rb ", "preview a rollback", "предпросмотр отката"),
    Help(ROLLBACK_PERMISSION, "/pp apply · /pp cancel", "/pp apply", "run or drop the preview", "выполнить или сбросить предпросмотр"),
    Help(LOOKUP_PERMISSION, "/pp chat [user: time: f:]", "/pp chat ", "chat, commands, joins and quits", "чат, команды, входы и выходы"),
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

    fun submit(transfer: Transfer) {
        tally(transfer)
        ledger.submit(transfer)
    }

    fun submit(transaction: List<Transfer>) {
        for (transfer in transaction) tally(transfer)
        ledger.submit(transaction)
    }

    /** The tally as it stands, left for the next report. */
    fun peek(): String? = counts.entries.map { (cause, count) -> cause to count.sum() }.filter { it.second > 0 }
        .sortedByDescending { it.second }.joinToString(" ") { "${it.first.name.lowercase()}=${it.second}" }.takeIf { it.isNotEmpty() }

    /** Takes the tally rather than reading it: what has been reported once must not be reported again. */
    fun takeTally(): String? {
        val named = counts.entries
            .map { (cause, count) -> cause to count.sumThenReset() }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .joinToString(" ") { "${it.first.name.lowercase()}=${it.second}" }
        return named.takeIf { it.isNotEmpty() }
    }

    private fun tally(transfer: Transfer) {
        if (transfer.confidence != Confidence.INFERRED) return
        counts.computeIfAbsent(transfer.cause) { LongAdder() }.increment()
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

// Everything the plugin owns while it is enabled. One field rather than nine, so there is no state
// where half of them are up: either the ledger is open and all of this stands, or none of it does.
private class Running(
    val ledger: RocksItemLog,
    val blocks: BlockLogs,
    val attribution: Attribution,
    val uncovered: Uncovered,
    val codec: ItemFormCodec,
    val capture: ContainerCaptureListener,
    val destruction: BlockDestructionListener,
    val mechanisms: TickCoalescer,
    val origins: SpawnOrigins,
    val lookups: Lookups,
    val inspector: Inspector,
    val reconciliation: Reconciliation,
    val planes: PlaneSync,
    val rollbacks: Rollbacks,
    val chat: io.pfaumc.pfauprotect.storage.ChatLog,
)

class PfauProtectPlugin : JavaPlugin() {
    private var running: Running? = null

    override fun onEnable() {
        saveDefaultConfig()
        Settings.load(config, logger)
        val ledger = RocksItemLog(dataFolder.toPath().resolve("ledger"))
        val energy = Energy()
        val nudges = Nudges()
        val touches = HandTouches()
        // A row that names somebody is what an observer or a comparator next to it answers to, and any
        // row at all is what a touch waiting to be read back leaves to the capture that filed it.
        val blocks = BlockLogs(dataFolder.toPath().resolve("blocks"), ledger, { world, changes ->
            touches.filed(world, changes)
            for (change in changes) {
                val actor = change.actor ?: continue
                energy.note(WorldBlock(world, change.x, change.y, change.z), Attributed(actor, change.confidence))
            }
        }) { world, change -> preLog(world, change) }
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
            energy, touches,
        ) { at, task -> server.regionScheduler.run(this, at) { task() } }
        val lookups = Lookups(this, ledger, blocks, codec)
        val inspector = Inspector(lookups)
        // Held before anything that can fail, so a failure on the way up still closes the ledger on
        // the way back down.
        val worldItems = WorldItemListener(codec, mechanisms, origins, capture, attribution, entities)
        val entityCapture = EntityCapture(
            blocks, origins, attribution, entities,
            later = { at, task -> server.regionScheduler.run(this, at) { task() } },
            laterOn = { entity, task -> entity.scheduler.run(this, { task() }, null) },
            offThread = { task -> server.asyncScheduler.runNow(this) { task() } },
        )
        val confiscations = Confiscations(this, ledger, codec, capture, worldItems, uncovered::submit)
        val chunkRollback = ChunkRollback(
            this, codec, blocks, ledger, uncovered::submit, formOf = ledger::form, forget = entityCapture::forget,
        )
        val rollbacks = Rollbacks(this, ledger, blocks, lookups, chunkRollback, confiscations)
        val chat = io.pfaumc.pfauprotect.storage.ChatLog(dataFolder.toPath().resolve("chat"))
        val running = Running(
            ledger, blocks, attribution, uncovered, codec, capture, destruction, mechanisms, origins,
            lookups, inspector, Reconciliation(ledger), PlaneSync(ledger, blocks), rollbacks, chat,
        )
        this.running = running
        ledger.staged { fillTypeRegistries(ledger.registries) }
        server.pluginManager.registerEvents(WorldBaseListener(blocks), this)
        // Enabling after startup, every world is already loaded and none of them will ever raise the
        // load event again.
        for (world in server.worlds) if (world.name !in Settings.disabledWorlds) blocks.open(world.uid)
        // A change to a world with no base open is dropped rather than journalled, so this goes after
        // the load handler and after the bases opened by hand. Against the other handlers of equal
        // priority the order is free: nothing it reads is written by any of them.
        server.pluginManager.registerEvents(
            BlockCaptureListener(blocks, attribution, touches) { block, task -> server.regionScheduler.run(this, block.location) { task() } },
            this,
        )
        server.pluginManager.registerEvents(destruction, this)
        server.pluginManager.registerEvents(EntityOriginListener(attribution, entities), this)
        server.pluginManager.registerEvents(RedstoneListener(energy, blocks, entities, nudges), this)
        // After the attribution of entities, whose notes it reads for who brought a mob in, and before the
        // item capture, which takes the marks off a dying mob's slots that its snapshot has to keep.
        server.pluginManager.registerEvents(entityCapture, this)
        server.pluginManager.registerEvents(capture, this)
        server.pluginManager.registerEvents(ItemUseListener(capture, codec), this)
        server.pluginManager.registerEvents(ProjectileListener(capture, codec, mechanisms, origins), this)
        val inventories = MobInventories(codec, mechanisms, ledger, uncovered::submit) { entity, task ->
            entity.scheduler.run(this, { task() }, null)
        }
        server.pluginManager.registerEvents(inventories, this)
        server.pluginManager.registerEvents(
            CopperGolemListener(codec, uncovered::submit) { golem, look ->
                golem.scheduler.runAtFixedRate(this, { task -> if (!look()) task.cancel() }, null, 1, 1)
            },
            this,
        )
        server.pluginManager.registerEvents(
            MobItemListener(codec, mechanisms, origins, inventories) { at, task -> server.regionScheduler.run(this, at) { task() } },
            this,
        )
        server.pluginManager.registerEvents(
            CommandListener(capture, codec, origins) { player, task -> player.scheduler.run(this, { task() }, null) },
            this,
        )
        server.pluginManager.registerEvents(
            HolderListener(capture, codec, origins) { block, task -> server.regionScheduler.run(this, block.location) { task() } },
            this,
        )
        server.pluginManager.registerEvents(MechanismCaptureListener(codec, mechanisms), this)
        // Breaking a shulker box, the nested capture writes the owner mark onto the stack that was
        // just dropped and the block capture then reads the form of that same stack. Handlers of equal
        // priority run in the order they were registered, so turning these two around would file the
        // drop under a form that has no owner on it and the chain of custody would end at the break.
        server.pluginManager.registerEvents(NestedCaptureListener(ledger, codec, mechanisms), this)
        server.pluginManager.registerEvents(
            BlockMechanismListener(
                codec, mechanisms, origins, ledger, uncovered::submit, energy, entities, ledger, capture::intend,
            ) { block, task ->
                server.regionScheduler.run(this, block.location) { task() }
            },
            this,
        )
        server.pluginManager.registerEvents(worldItems, this)
        // After the capture's own join handler, whose starting point for the player's first pass the
        // items taken back on the join have to come after.
        server.pluginManager.registerEvents(confiscations, this)
        server.pluginManager.registerEvents(inspector, this)
        server.pluginManager.registerEvents(io.pfaumc.pfauprotect.capture.ChatCapture(chat), this)
        server.servicesManager.register(io.pfaumc.pfauprotect.api.PfauProtectApi::class.java, Api(running), this, org.bukkit.plugin.ServicePriority.Normal)
        server.globalRegionScheduler.runAtFixedRate(this, {
            origins.sweep()
            attribution.sweepRemovals()
            mechanisms.flush()
            destruction.settleGrowth()
        }, 1, 1)
        server.asyncScheduler.runAtFixedRate(
            this,
            {
                attribution.sweep()
                entities.sweep()
                energy.sweep()
                nudges.sweep()
            },
            NOTE_MINUTES,
            NOTE_MINUTES,
            TimeUnit.MINUTES,
        )
        server.asyncScheduler.runAtFixedRate(
            this,
            { sweepLedger(ledger) },
            SWEEP_MINUTES,
            SWEEP_MINUTES,
            TimeUnit.MINUTES,
        )
        server.asyncScheduler.runAtFixedRate(
            this,
            { syncPlanes(running.planes) },
            PLANE_MINUTES,
            PLANE_MINUTES,
            TimeUnit.MINUTES,
        )
        server.asyncScheduler.runAtFixedRate(
            this,
            { reconcileEveryone(running.reconciliation, codec) },
            RECONCILE_MINUTES,
            RECONCILE_MINUTES,
            TimeUnit.MINUTES,
        )
        server.asyncScheduler.runAtFixedRate(this, { reportUncovered(uncovered) }, 1, 1, TimeUnit.DAYS)
        warnAboutSilencedHoppers()
        registerCommand(CommandBrackets(this, blocks, codec, ledger, uncovered::submit))
        logger.info(
            "ledger open, ${blocks.size} world bases, registry sizes: " +
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
            // Closing a world base stops its writer before it frees its handles, and that writer
            // interns state numbers and payloads in the item database, so every base goes before the
            // ledger it writes through. A base that fails to close must not take the ledger down with
            // it: an unflushed log and a held lock file leave a plugin that cannot be enabled again
            // without restarting the server, which is a worse outcome than whatever the base hit.
            closeReporting("the world block bases") { running.blocks.close() }
            closeReporting("the ledger") { running.ledger.close() }
            closeReporting("the chat log") { running.chat.close() }
            this.running = null
        }
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

    // A gap means one end of a movement was written and the other was not, which is a hole in the
    // capture rather than anything a player did. Reported as it is found, not once the ledger is
    // already being read in anger.
    private fun sweepLedger(ledger: RocksItemLog) {
        val report = ledger.sweep(SWEEP_ENTRIES)
        for (gap in report.gaps.take(SWEEP_GAPS_LOGGED)) logger.warning("ledger gap: $gap")
        val unlisted = report.gaps.size - SWEEP_GAPS_LOGGED
        if (unlisted > 0) logger.warning("and $unlisted more ledger gaps in this pass")
        // Rows this build cannot decode are not rows without gaps. Left unsaid, a ledger that has
        // become unreadable would keep reporting clean passes.
        if (report.unreadable > 0) {
            logger.warning("${report.unreadable} ledger rows in this pass could not be read by this build")
        }
        if (report.reachedEnd && report.checked > 0) {
            logger.info("ledger swept to the end, ${report.checked} entries in this pass, ${report.gaps.size} gaps")
        }
    }

    // A position where the two planes disagree is a change that reached one of them and not the other,
    // which from inside either plane on its own reads as perfectly consistent. That is a hole in the
    // capture rather than anything a player did.
    private fun syncPlanes(planes: PlaneSync) {
        if (!isEnabled) return
        val report = planes.pass(PLANE_POSTINGS)
        for (gap in report.gaps.take(SWEEP_GAPS_LOGGED)) {
            val world = server.getWorld(gap.at.world)?.name ?: gap.at.world.toString()
            logger.warning(
                "plane gap in $world at ${gap.at.x} ${gap.at.y} ${gap.at.z}: the block plane says " +
                    "${gap.standing} stands there, the item plane still holds ${gap.fact} confirmed " +
                    "and ${gap.inferred} inferred"
            )
        }
        val unlisted = report.gaps.size - SWEEP_GAPS_LOGGED
        if (unlisted > 0) logger.warning("and $unlisted more plane gaps in this pass")
        // Positions this build cannot read are not positions that agree. Left unsaid, a base that has
        // become unreadable would keep reporting clean passes.
        if (report.unreadable > 0) {
            logger.warning("${report.unreadable} positions in this pass could not be compared by this build")
        }
        // The opposite of a standing debt and just as much a hole in the capture: the item plane took
        // items out of a position it was never told held any.
        if (report.overdrawn > 0) {
            logger.warning(
                "${report.overdrawn} positions in this pass gave up more than the item plane " +
                    "ever booked to them"
            )
        }
        // A last page of nothing but positions too fresh to judge is still the end of the cycle; a
        // busy piston clock keeps its own positions fresh for ever, and staying quiet then read as a
        // cursor that never came round.
        if (report.reachedEnd && (report.checked > 0 || report.unrecorded > 0 || report.settling > 0)) {
            logger.info(
                "planes compared to the end of the item plane, ${report.checked} positions in this pass, " +
                    "${report.unrecorded} the block plane never recorded, ${report.settling} too recent " +
                    "to judge, ${report.gaps.size} gaps, " +
                    "${report.gaps.count { it.fact == 0 }} of them with nothing confirmed standing"
            )
        }
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
                Commands.literal("purge")
                    .requires { it.sender.hasPermission(PURGE_PERMISSION) }
                    .then(
                        Commands.argument("age", StringArgumentType.word())
                            .executes { purge(it.source, StringArgumentType.getString(it, "age"), confirm = false) }
                            .then(Commands.literal("confirm").executes { purge(it.source, StringArgumentType.getString(it, "age"), confirm = true) })
                    )
            )
            root.then(
                Commands.literal("chat")
                    .requires { it.sender.hasPermission(LOOKUP_PERMISSION) }
                    .executes { chat(it.source, "") }
                    .then(Commands.argument("words", StringArgumentType.greedyString()).executes { chat(it.source, StringArgumentType.getString(it, "words")) })
            )
            root.then(
                Commands.literal("status")
                    .requires { it.sender.hasPermission(STATUS_PERMISSION) }
                    .executes { status(it.source) }
            )
            root.then(teleportNode())
            root.then(
                Commands.literal("apply")
                    .requires { it.sender.hasPermission(ROLLBACK_PERMISSION) }
                    .executes { rollbacks(it.source) { rollbacks, sender -> rollbacks.applyPreview(sender) } }
            )
            root.then(
                Commands.literal("cancel")
                    .requires { it.sender.hasPermission(ROLLBACK_PERMISSION) }
                    .executes { rollbacks(it.source) { rollbacks, sender -> rollbacks.cancelPreview(sender) } }
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

    private fun lookupNode(literal: String) = Commands.literal(literal)
        .requires { it.sender.hasPermission(LOOKUP_PERMISSION) }
        .executes { lookup(it.source, LookupQuery()) }
        .then(
            Commands.argument("query", LookupArgument())
                .executes { lookup(it.source, it.getArgument("query", LookupQuery::class.java)) }
        )

    // Centred on the player rather than on the block they are looking at, which is what makes it the
    // command to reach for when something has just happened around you and there is nothing left
    // standing to point at.
    private fun nearNode(literal: String) = Commands.literal(literal)
        .requires { it.sender.hasPermission(LOOKUP_PERMISSION) }
        .executes { near(it.source, NEAR_RADIUS) }
        .then(
            Commands.argument("radius", IntegerArgumentType.integer(0, Settings.maxRadius))
                .executes { near(it.source, it.getArgument("radius", Integer::class.java).toInt()) }
        )

    private fun inspectNode(literal: String) = Commands.literal(literal)
        .requires { it.sender.hasPermission(INSPECT_PERMISSION) }
        .executes { inspect(it.source, null) }
        .then(Commands.literal("on").executes { inspect(it.source, true) })
        .then(Commands.literal("off").executes { inspect(it.source, false) })

    // The self-checks run on their own schedules, which are minutes apart on purpose and far too slow
    // to work against by hand. This is the same passes, now, from the beginning to the end of what
    // they walk.
    private fun verifyNode(literal: String) = Commands.literal(literal)
        .requires { it.sender.hasPermission(VERIFY_PERMISSION) }
        .executes { verify(it.source, judgeRecent = false) }
        .then(Commands.literal("recent").executes { verify(it.source, judgeRecent = true) })

    // Centred where the command was run from, like `near`: what a rollback covers is the place around
    // whoever runs it, not the one block they happen to be looking at.
    private fun rollbackNode(literal: String) = Commands.literal(literal)
        .requires { it.sender.hasPermission(ROLLBACK_PERMISSION) }
        .executes { context ->
            rollbacks(context.source) { rollbacks, sender ->
                rollbacks.preview(sender, lookupTargetAt(context.source.location), LookupQuery())
            }
        }
        .then(
            Commands.argument("query", LookupArgument(rollback = true))
                .executes { context ->
                    val query = context.getArgument("query", LookupQuery::class.java)
                    rollbacks(context.source) { rollbacks, sender ->
                        rollbacks.preview(sender, query.targetOr(lookupTargetAt(context.source.location)), query)
                    }
                }
        )

    // What the mark on a lookup line runs: to where the row happened, in the world it happened in.
    private fun teleportNode() = Commands.literal("tp")
        .requires { it.sender.hasPermission(TELEPORT_PERMISSION) && it.executor is Player }
        .then(
            Commands.argument("x", IntegerArgumentType.integer()).then(
                Commands.argument("y", IntegerArgumentType.integer()).then(
                    Commands.argument("z", IntegerArgumentType.integer())
                        .executes { teleport(it, null) }
                        .then(
                            Commands.argument("world", StringArgumentType.word())
                                .suggests { _, builder -> server.worlds.forEach { builder.suggest(it.name) }; builder.buildFuture() }
                                .executes { teleport(it, StringArgumentType.getString(it, "world")) }
                        )
                )
            )
        )

    private fun teleport(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, worldName: String?): Int {
        val player = context.source.executor as? Player ?: return 0
        val world = if (worldName == null) player.world else server.getWorld(worldName)
        if (world == null) {
            context.source.sender.say("Unknown world: $worldName")
            return 0
        }
        val x = IntegerArgumentType.getInteger(context, "x")
        val y = IntegerArgumentType.getInteger(context, "y")
        val z = IntegerArgumentType.getInteger(context, "z")
        player.teleportAsync(org.bukkit.Location(world, x + 0.5, y.toDouble(), z + 0.5, player.location.yaw, player.location.pitch))
        return Command.SINGLE_SUCCESS
    }

    private fun rollbacks(source: CommandSourceStack, action: (Rollbacks, CommandSender) -> Unit): Int {
        val rollbacks = running?.rollbacks ?: return notReady(source)
        action(rollbacks, source.sender)
        return Command.SINGLE_SUCCESS
    }

    private fun reconcileNode(literal: String) = Commands.literal(literal)
        .requires { it.sender.hasPermission(RECONCILE_PERMISSION) }
        .executes { reconcile(it.source, it.source.executor as? Player) }
        .then(
            Commands.argument("player", ArgumentTypes.player())
                .executes { context ->
                    val resolver = context.getArgument("player", PlayerSelectorArgumentResolver::class.java)
                    reconcile(context.source, resolver.resolve(context.source).first())
                }
        )

    // A player pointing at a block means that block; anything else means the position the command was
    // run from, which is what makes the command answerable from the console and from `/execute
    // positioned`, and what stops a player who looked past the last block from being told off.
    private fun lookup(source: CommandSourceStack, query: LookupQuery): Int {
        val lookups = running?.lookups ?: return notReady(source)
        // Only where the command runs from the player themselves: `/execute positioned` names a place of its
        // own, and the block the player happens to look at took over from it (D79).
        val player = (source.executor as? Player)?.takeIf { it.world == source.location.world && it.location.distanceSquared(source.location) < 1e-6 }
        val aimed = player?.getTargetBlockExact(TARGET_RANGE)
        lookups.run(source.sender, query.targetOr(aimed?.let(::lookupTargetAt) ?: lookupTargetAt(source.location)), query)
        return Command.SINGLE_SUCCESS
    }

    private fun near(source: CommandSourceStack, radius: Int): Int {
        val lookups = running?.lookups ?: return notReady(source)
        lookups.run(source.sender, lookupTargetAt(source.location), LookupQuery(radius = radius, words = "r:$radius"))
        return Command.SINGLE_SUCCESS
    }

    private fun inspect(source: CommandSourceStack, desired: Boolean?): Int {
        val inspector = running?.inspector ?: return notReady(source)
        val player = source.executor as? Player
        if (player == null) {
            source.sender.say("Only a player can use the inspector.")
            return 0
        }
        val now = inspector.toggle(player, desired)
        // Above the hotbar rather than in chat: it is a state, not a message, and chat is where the answers go.
        player.sendActionBar(
            if (now) Ui.text(tr("Inspector on: left click — the block, right click — the place in front, click an entity — the entity", "Инспектор включён: ЛКМ — блок, ПКМ — место перед гранью, клик по сущности — сущность"), Ui.GAINED)
            else Ui.text(tr("Inspector off", "Инспектор выключен"), Ui.MUTED)
        )
        return Command.SINGLE_SUCCESS
    }

    /**
     * Both store-side self-checks, run to the end rather than on their own schedules. The writer runs
     * on a thread of its own, so anything it has not written yet is missing from what a check would
     * read, which on a hand-run check is the difference between a real finding and the last thing the
     * tester did.
     *
     * `judgeRecent` gives up the settle window the periodic pass keeps. The window is there because
     * the two planes are written by two threads and a position read between them disagrees with itself
     * for a moment; giving it up is what makes a check worth running straight after an action, at the
     * price of the odd race reported as a finding.
     */
    /**
     * Deletes history older than the age given, in both planes, after a preview that counts it. What each
     * holder held at the cutoff is written as an opening balance, so the self-checks still add up; the
     * newest row of each position stays, so the attribution can still ask who put a block there.
     */
    private fun purge(source: CommandSourceStack, age: String, confirm: Boolean): Int {
        val running = this.running ?: return notReady(source)
        val sender = source.sender
        val seconds = io.pfaumc.pfauprotect.command.durationOrNull(age)
        if (seconds == null || seconds < PURGE_MIN_SECONDS) {
            sender.say("Purge refused: give an age of at least a day, for example 90d.")
            return 0
        }
        if (running.rollbacks.runningSince() != null) {
            sender.say("Purge refused: a rollback is running.")
            return 0
        }
        sender.say(if (confirm) "Purging everything older than $age; this walks the whole journal." else "Counting what a purge of everything older than $age would delete.")
        server.asyncScheduler.runNow(this) {
            try {
                val cutoff = System.currentTimeMillis() - seconds * 1000
                running.ledger.drain()
                for (world in running.blocks.worlds) running.blocks.get(world)?.drain()
                val (entries, openings) = running.ledger.purgeBefore(cutoff, dryRun = !confirm)
                var rows = 0
                var entities = 0
                for (world in running.blocks.worlds) {
                    val (r, e) = running.blocks.get(world)?.purgeBefore(cutoff, dryRun = !confirm) ?: continue
                    rows += r
                    entities += e
                }
                if (confirm) {
                    for (opening in openings) running.ledger.submit(opening)
                    running.ledger.drain()
                }
                val counts = "$entries item rows, $rows block rows, $entities entity rows; ${openings.size} opening balances"
                sender.say(if (confirm) "Purged $counts written." else "A purge would delete $counts to write. /pp purge $age confirm runs it.")
            } catch (failure: Throwable) {
                logger.log(Level.SEVERE, "the purge failed", failure)
                sender.say("The purge failed; the server log has the details.")
            }
        }
        return Command.SINGLE_SUCCESS
    }

    // Another plugin may veto a row before it is written; nobody listening, nothing is asked.
    private fun preLog(world: UUID, change: io.pfaumc.pfauprotect.storage.WorldChange): Boolean {
        if (io.pfaumc.pfauprotect.api.PfauProtectPreLogEvent.getHandlerList().registeredListeners.isEmpty()) return true
        val cause = when (change) {
            is io.pfaumc.pfauprotect.storage.BlockChange -> change.cause
            is io.pfaumc.pfauprotect.storage.EntityChange -> change.cause
            else -> return true
        }
        val event = io.pfaumc.pfauprotect.api.PfauProtectPreLogEvent(world, change.x, change.y, change.z, cause.name, change.actor, !server.isPrimaryThread)
        server.pluginManager.callEvent(event)
        return !event.isCancelled
    }

    /** What players said, ran, and when they came and went: `user:`, `time:` and `f:` narrow it. */
    private fun chat(source: CommandSourceStack, words: String): Int {
        val running = this.running ?: return notReady(source)
        val sender = source.sender
        val query = try {
            io.pfaumc.pfauprotect.command.parseLookupQuery(words)
        } catch (failure: com.mojang.brigadier.exceptions.CommandSyntaxException) {
            // The message alone: the rest is Brigadier's "at position" in English, about a line not typed here.
            sender.sendMessage(failure.rawMessage.string)
            return 0
        }
        server.asyncScheduler.runNow(this) {
            val users = running.lookups.resolveAll(sender, query.users) ?: return@runNow
            val text = query.filter?.lowercase()
            val lines = running.chat.read(query.fromTs(), query.toTs(), query.wanted) { line ->
                (users.isEmpty() || line.player in users) && (text == null || text in line.text.lowercase())
            }.drop(query.limit * (query.page - 1))
            if (lines.isEmpty()) return@runNow sender.say("Nothing said or run matches.")
            sender.sendMessage(Ui.text(tr("PfauProtect · chat and commands", "PfauProtect · чат и команды"), Ui.FAINT))
            for (line in lines) {
                val who = Ui.text(server.getOfflinePlayer(line.player).name ?: line.player.toString(), Ui.WHO)
                val what = when (line.kind) {
                    ChatKind.CHAT -> Component.text().append(who).append(Ui.text(": " + line.text)).build()
                    ChatKind.COMMAND -> Component.text().append(who).append(Ui.text(" " + line.text, Ui.CHANGED)).build()
                    ChatKind.JOIN -> Component.text().append(who).append(Ui.text(tr(" joined", " · вход"), Ui.GAINED)).build()
                    ChatKind.QUIT -> Component.text().append(who).append(Ui.text(tr(" left", " · выход"), Ui.MUTED)).build()
                }
                val out = Component.text().append(Ui.text(" ")).append(Ui.ago(line.timestamp)).append(Ui.text("  ")).append(what)
                if (line.world.isNotEmpty()) out.append(Ui.text("  ")).append(Ui.place(line.world, line.x, line.y, line.z, sender is Player))
                sender.sendMessage(out.build())
            }
        }
        return Command.SINGLE_SUCCESS
    }

    /** How the plugin is doing: how big its bases are, what waits to be written, what it could not explain. */
    private fun status(source: CommandSourceStack): Int {
        val running = this.running ?: return notReady(source)
        val sender = source.sender
        server.asyncScheduler.runNow(this) {
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
            sender.say("  rollback: " + if (since == null) "none running" else "one running for ${(System.currentTimeMillis() - since) / 1000} s")
        }
        return Command.SINGLE_SUCCESS
    }

    private fun megabytes(dir: java.nio.file.Path): String {
        if (!java.nio.file.Files.exists(dir)) return "0 MB"
        val bytes = java.nio.file.Files.walk(dir).use { paths -> paths.filter { java.nio.file.Files.isRegularFile(it) }.mapToLong { runCatching { java.nio.file.Files.size(it) }.getOrDefault(0) }.sum() }
        return "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_048_576.0)
    }

    private fun verify(source: CommandSourceStack, judgeRecent: Boolean): Int {
        val running = this.running ?: return notReady(source)
        val sender = source.sender
        sender.say("Running both self-checks to the end; this reads the whole journal.")
        server.asyncScheduler.runNow(this) {
            try {
                running.ledger.drain()
                reportSweep(sender, running.ledger)
                reportPlanes(sender, running.planes, judgeRecent)
            } catch (failure: Throwable) {
                logger.log(Level.SEVERE, "the self-checks failed", failure)
                sender.say("The self-checks failed; the server log has the details.")
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun reportSweep(sender: CommandSender, ledger: RocksItemLog) {
        var checked = 0
        var unreadable = 0
        val gaps = ArrayList<String>()
        var rounds = 0
        var reachedEnd = false
        while (rounds < VERIFY_ROUNDS && !reachedEnd) {
            val report = ledger.sweep(SWEEP_ENTRIES)
            checked += report.checked
            unreadable += report.unreadable
            gaps += report.gaps
            reachedEnd = report.reachedEnd
            rounds++
        }
        sender.say("Transaction invariant: $checked entries, ${gaps.size} gaps, $unreadable unreadable.")
        for (gap in gaps.take(SWEEP_GAPS_LOGGED)) sender.say("  gap: $gap")
        if (gaps.size > SWEEP_GAPS_LOGGED) sender.say("  ... and ${gaps.size - SWEEP_GAPS_LOGGED} more")
        if (!reachedEnd) sender.say("  stopped on the round limit; run it again to cover the rest.")
    }

    private fun reportPlanes(sender: CommandSender, planes: PlaneSync, judgeRecent: Boolean) {
        // Reading as though a settle window's worth of time had already passed is what lets a position
        // touched a moment ago be judged at all.
        val now = System.currentTimeMillis() + if (judgeRecent) SETTLE_MILLIS else 0
        var checked = 0
        var unrecorded = 0
        var settling = 0
        var unreadable = 0
        var overdrawn = 0
        val gaps = ArrayList<PlaneGap>()
        var rounds = 0
        var reachedEnd = false
        while (rounds < VERIFY_ROUNDS && !reachedEnd) {
            val report = planes.pass(PLANE_POSTINGS, now)
            checked += report.checked
            unrecorded += report.unrecorded
            settling += report.settling
            unreadable += report.unreadable
            overdrawn += report.overdrawn
            gaps += report.gaps
            reachedEnd = report.reachedEnd
            rounds++
        }
        sender.say(
            "Two planes: $checked positions compared, ${gaps.size} gaps, $overdrawn overdrawn, " +
                "$unrecorded the block plane never recorded, $settling too recent to judge, " +
                "$unreadable unreadable."
        )
        for (gap in gaps.take(SWEEP_GAPS_LOGGED)) {
            val world = server.getWorld(gap.at.world)?.name ?: gap.at.world.toString()
            sender.say(
                "  gap in $world at ${gap.at.x} ${gap.at.y} ${gap.at.z}: ${gap.standing} stands there, " +
                    "the item plane holds ${gap.fact} confirmed and ${gap.inferred} inferred"
            )
        }
        if (gaps.size > SWEEP_GAPS_LOGGED) sender.say("  ... and ${gaps.size - SWEEP_GAPS_LOGGED} more")
        if (settling > 0 && !judgeRecent) {
            sender.say("  $settling positions were touched too recently; 'verify recent' judges them too.")
        }
        if (!reachedEnd) sender.say("  stopped on the round limit; run it again to cover the rest.")
    }

    private fun reconcile(source: CommandSourceStack, target: Player?): Int {
        val running = this.running ?: return notReady(source)
        if (target == null) {
            source.sender.say("Name the player to reconcile.")
            return 0
        }
        val sender = source.sender
        val reconciliation = running.reconciliation
        compare(target, running.codec, reconciliation) { report(sender, target, it, reconciliation) }
        return Command.SINGLE_SUCCESS
    }

    // The slots may only be read on the region that ticks the player, and RocksDB has no business
    // running out there, so the two halves of a reconciliation are two hops instead of one task.
    private fun compare(
        player: Player,
        codec: ItemFormCodec,
        reconciliation: Reconciliation,
        andThen: (List<Mismatch>) -> Unit,
    ) {
        val running = this.running ?: return
        player.scheduler.run(this, {
            // The pass is the only writer for a player's own slots and it runs a tick behind the
            // events that ask for it, so the slots are read here against a ledger that has not been
            // told about the last thing the player did. Running the pass first is what makes the two
            // sides the same moment; without it every recent movement reads as a difference.
            running.capture.recompute(player)
            val held = heldForms(player, codec)
            server.asyncScheduler.runNow(this) {
                // The writer runs on a thread of its own, and what it has not written yet is missing
                // from the balance the comparison is about to read.
                running.ledger.drain()
                andThen(reconciliation.compare(player.uniqueId, held))
            }
        }, null)
    }

    // Whatever a player was already carrying on the day the ledger was opened is a difference that
    // never goes away and never grows: a constant offset, not a leak. What is worth reading is a
    // difference that moves.
    private fun report(
        sender: CommandSender,
        player: Player,
        mismatches: List<Mismatch>,
        reconciliation: Reconciliation,
    ) {
        if (mismatches.isEmpty()) {
            sender.say("${player.name} matches the ledger; nothing differs.")
            return
        }
        sender.say("${player.name} differs from the ledger on ${mismatches.size} forms:")
        for (mismatch in mismatches) {
            sender.say("  ${reconciliation.name(mismatch.form)} ${signed(mismatch.difference)}")
        }
        sender.say(
            "Anything held before the ledger was opened differs by exactly that much for ever; " +
                "a difference that stays put is that constant rather than a leak."
        )
    }

    // A hole in the capture gives itself away by being systematic: the same form, the same direction,
    // on many players at once. That shape only shows on the flow of live data, so the pass runs on
    // everyone and says nothing about the players who balance.
    private fun reconcileEveryone(reconciliation: Reconciliation, codec: ItemFormCodec) {
        if (!isEnabled) return
        for (player in server.onlinePlayers) {
            compare(player, codec, reconciliation) { mismatches ->
                if (mismatches.isNotEmpty()) {
                    logger.info(
                        "reconcile ${player.name}: " +
                            mismatches.joinToString { "${reconciliation.name(it.form)} ${signed(it.difference)}" }
                    )
                }
            }
        }
    }

    private fun notReady(source: CommandSourceStack): Int {
        source.sender.say("The ledger is not open.")
        return 0
    }
}

/** The service other plugins load; see [io.pfaumc.pfauprotect.api.PfauProtectApi]. */
private class Api(private val running: Running) : io.pfaumc.pfauprotect.api.PfauProtectApi {
    override fun lookup(at: org.bukkit.Location, words: String): java.util.concurrent.CompletableFuture<List<String>> {
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        val sender = org.bukkit.Bukkit.createCommandSender { lines += net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it) }
        val query = io.pfaumc.pfauprotect.command.parseLookupQuery(words)
        return java.util.concurrent.CompletableFuture.supplyAsync {
            running.lookups.report(sender, io.pfaumc.pfauprotect.command.lookupTargetAt(at), query)
            lines.toList()
        }
    }

    override fun previewRollback(sender: CommandSender, at: org.bukkit.Location, words: String) =
        running.rollbacks.preview(sender, io.pfaumc.pfauprotect.command.lookupTargetAt(at), io.pfaumc.pfauprotect.command.parseLookupQuery(words))

    override fun logChange(actor: UUID?, block: org.bukkit.block.Block, before: org.bukkit.block.data.BlockData, after: org.bukkit.block.data.BlockData) {
        val log = running.blocks.get(block.world.uid) ?: return
        log.submit(listOf(io.pfaumc.pfauprotect.storage.BlockChange(block.x, block.y, block.z, before.asString, after.asString, Cause.BLK_PLUGIN, actor = actor)))
    }
}
