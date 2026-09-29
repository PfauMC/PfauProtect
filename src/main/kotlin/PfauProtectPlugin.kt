package io.pfaumc.pfauprotect

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.minecraft.server.MinecraftServer
import org.bukkit.command.CommandSender
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.java.JavaPlugin
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
)

class PfauProtectPlugin : JavaPlugin() {
    private var running: Running? = null

    override fun onEnable() {
        val ledger = RocksItemLog(dataFolder.toPath().resolve("ledger"))
        val blocks = BlockLogs(dataFolder.toPath().resolve("blocks"), ledger)
        val attribution = Attribution(ledger.registries, blocks)
        val uncovered = Uncovered(ledger)
        val codec = ItemFormCodec(ledger.registries, MinecraftServer.getServer().registryAccess())
        val mechanisms = TickCoalescer(uncovered::submit)
        val origins = SpawnOrigins(mechanisms)
        val capture = ContainerCaptureListener(
            this, uncovered::submit, codec, ledger.registries, origins, ledger,
        ) { intent, qty ->
            unspentDrop(mechanisms, intent, qty)
        }
        val entities = EntityOrigins()
        val destruction = BlockDestructionListener(
            this, ledger.registries, blocks, attribution, codec, origins, entities, ledger, ledger, uncovered::submit,
        )
        val lookups = Lookups(this, ledger, blocks, codec)
        val inspector = Inspector(lookups)
        // Held before anything that can fail, so a failure on the way up still closes the ledger on
        // the way back down.
        val running = Running(
            ledger, blocks, attribution, uncovered, codec, capture, destruction, mechanisms, origins,
            lookups, inspector, Reconciliation(ledger), PlaneSync(ledger, blocks),
        )
        this.running = running
        ledger.staged { fillTypeRegistries(ledger.registries) }
        server.pluginManager.registerEvents(WorldBaseListener(blocks), this)
        // Enabling after startup, every world is already loaded and none of them will ever raise the
        // load event again.
        for (world in server.worlds) blocks.open(world.uid)
        // A change to a world with no base open is dropped rather than journalled, so this goes after
        // the load handler and after the bases opened by hand. Against the other handlers of equal
        // priority the order is free: nothing it reads is written by any of them.
        server.pluginManager.registerEvents(BlockCaptureListener(blocks, attribution), this)
        server.pluginManager.registerEvents(destruction, this)
        server.pluginManager.registerEvents(EntityOriginListener(attribution, entities), this)
        server.pluginManager.registerEvents(capture, this)
        server.pluginManager.registerEvents(ItemUseListener(capture, codec), this)
        server.pluginManager.registerEvents(MechanismCaptureListener(codec, mechanisms), this)
        // Breaking a shulker box, the nested capture writes the owner mark onto the stack that was
        // just dropped and the block capture then reads the form of that same stack. Handlers of equal
        // priority run in the order they were registered, so turning these two around would file the
        // drop under a form that has no owner on it and the chain of custody would end at the break.
        server.pluginManager.registerEvents(NestedCaptureListener(ledger, codec, mechanisms), this)
        server.pluginManager.registerEvents(
            BlockMechanismListener(codec, mechanisms, origins, ledger, uncovered::submit) { block, task ->
                server.regionScheduler.run(this, block.location) { task() }
            },
            this,
        )
        server.pluginManager.registerEvents(WorldItemListener(codec, mechanisms, origins, capture), this)
        server.pluginManager.registerEvents(inspector, this)
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
        registerCommand()
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
        if (report.reachedEnd && (report.checked > 0 || report.unrecorded > 0)) {
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

    private fun registerCommand() {
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val root = Commands.literal("pfauprotect")
            for (alias in listOf("lookup", "l")) root.then(lookupNode(alias))
            for (alias in listOf("near", "n")) root.then(nearNode(alias))
            for (alias in listOf("inspect", "i")) root.then(inspectNode(alias))
            for (alias in listOf("reconcile", "r")) root.then(reconcileNode(alias))
            for (alias in listOf("verify", "v")) root.then(verifyNode(alias))
            event.registrar().register(root.build(), "Item ledger lookup and inspector", listOf("pp"))
        }
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
            Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
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
        val aimed = (source.executor as? Player)?.getTargetBlockExact(TARGET_RANGE)
        lookups.run(source.sender, aimed?.let(::lookupTargetAt) ?: lookupTargetAt(source.location), query)
        return Command.SINGLE_SUCCESS
    }

    private fun near(source: CommandSourceStack, radius: Int): Int {
        val lookups = running?.lookups ?: return notReady(source)
        lookups.run(source.sender, lookupTargetAt(source.location), LookupQuery(radius = radius))
        return Command.SINGLE_SUCCESS
    }

    private fun inspect(source: CommandSourceStack, desired: Boolean?): Int {
        val inspector = running?.inspector ?: return notReady(source)
        val player = source.executor as? Player
        if (player == null) {
            source.sender.sendMessage("Only a player can use the inspector.")
            return 0
        }
        val now = inspector.toggle(player, desired)
        player.sendMessage(
            if (now) "Inspector enabled. Click a block to read its ledger."
            else "Inspector disabled."
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
    private fun verify(source: CommandSourceStack, judgeRecent: Boolean): Int {
        val running = this.running ?: return notReady(source)
        val sender = source.sender
        sender.sendMessage("Running both self-checks to the end; this reads the whole journal.")
        server.asyncScheduler.runNow(this) {
            try {
                running.ledger.drain()
                reportSweep(sender, running.ledger)
                reportPlanes(sender, running.planes, judgeRecent)
            } catch (failure: Throwable) {
                logger.log(Level.SEVERE, "the self-checks failed", failure)
                sender.sendMessage("The self-checks failed; the server log has the details.")
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
        sender.sendMessage("Transaction invariant: $checked entries, ${gaps.size} gaps, $unreadable unreadable.")
        for (gap in gaps.take(SWEEP_GAPS_LOGGED)) sender.sendMessage("  gap: $gap")
        if (gaps.size > SWEEP_GAPS_LOGGED) sender.sendMessage("  ... and ${gaps.size - SWEEP_GAPS_LOGGED} more")
        if (!reachedEnd) sender.sendMessage("  stopped on the round limit; run it again to cover the rest.")
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
        sender.sendMessage(
            "Two planes: $checked positions compared, ${gaps.size} gaps, $overdrawn overdrawn, " +
                "$unrecorded the block plane never recorded, $settling too recent to judge, " +
                "$unreadable unreadable."
        )
        for (gap in gaps.take(SWEEP_GAPS_LOGGED)) {
            val world = server.getWorld(gap.at.world)?.name ?: gap.at.world.toString()
            sender.sendMessage(
                "  gap in $world at ${gap.at.x} ${gap.at.y} ${gap.at.z}: ${gap.standing} stands there, " +
                    "the item plane holds ${gap.fact} confirmed and ${gap.inferred} inferred"
            )
        }
        if (gaps.size > SWEEP_GAPS_LOGGED) sender.sendMessage("  ... and ${gaps.size - SWEEP_GAPS_LOGGED} more")
        if (settling > 0 && !judgeRecent) {
            sender.sendMessage("  $settling positions were touched too recently; 'verify recent' judges them too.")
        }
        if (!reachedEnd) sender.sendMessage("  stopped on the round limit; run it again to cover the rest.")
    }

    private fun reconcile(source: CommandSourceStack, target: Player?): Int {
        val running = this.running ?: return notReady(source)
        if (target == null) {
            source.sender.sendMessage("Name the player to reconcile.")
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
            sender.sendMessage("${player.name} matches the ledger; nothing differs.")
            return
        }
        sender.sendMessage("${player.name} differs from the ledger on ${mismatches.size} forms:")
        for (mismatch in mismatches) {
            sender.sendMessage("  ${reconciliation.name(mismatch.form)} ${signed(mismatch.difference)}")
        }
        sender.sendMessage(
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
        source.sender.sendMessage("The ledger is not open.")
        return 0
    }
}
