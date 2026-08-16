package io.pfaumc.pfauprotect

import com.mojang.brigadier.Command
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import org.bukkit.command.CommandSender
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import java.util.logging.Level

private const val TARGET_RANGE = 6
private const val SWEEP_ENTRIES = 2000
private const val SWEEP_MINUTES = 5L
private const val SWEEP_GAPS_LOGGED = 5
private const val RECONCILE_MINUTES = 30L
private const val LOOKUP_PERMISSION = "pfauprotect.lookup"
private const val INSPECT_PERMISSION = "pfauprotect.inspect"
private const val RECONCILE_PERMISSION = "pfauprotect.reconcile"

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

class PfauProtectPlugin : JavaPlugin() {
    private var ledger: RocksItemLog? = null
    private var uncovered: Uncovered? = null
    private var codec: ItemFormCodec? = null
    private var capture: ContainerCaptureListener? = null
    private var mechanisms: TickCoalescer? = null
    private var origins: SpawnOrigins? = null
    private var lookups: Lookups? = null
    private var inspector: Inspector? = null
    private var reconciliation: Reconciliation? = null

    override fun onEnable() {
        val ledger = RocksItemLog(dataFolder.toPath().resolve("ledger"))
        this.ledger = ledger
        fillTypeRegistries(ledger.registries)
        val uncovered = Uncovered(ledger)
        this.uncovered = uncovered
        val codec = ItemFormCodec(ledger.registries, MinecraftServer.getServer().registryAccess())
        this.codec = codec
        val mechanisms = TickCoalescer(uncovered::submit)
        this.mechanisms = mechanisms
        val origins = SpawnOrigins(mechanisms)
        this.origins = origins
        val capture = ContainerCaptureListener(this, uncovered::submit, codec, origins, ledger) { intent, qty ->
            unspentDrop(mechanisms, intent, qty)
        }
        this.capture = capture
        val lookups = Lookups(this, ledger)
        this.lookups = lookups
        val inspector = Inspector(lookups)
        this.inspector = inspector
        val reconciliation = Reconciliation(ledger)
        this.reconciliation = reconciliation
        server.pluginManager.registerEvents(capture, this)
        server.pluginManager.registerEvents(MechanismCaptureListener(codec, mechanisms), this)
        // Breaking a shulker box, the nested capture writes the owner mark onto the stack that was
        // just dropped and the block capture then reads the form of that same stack. Handlers of equal
        // priority run in the order they were registered, so turning these two around would file the
        // drop under a form that has no owner on it and the chain of custody would end at the break.
        server.pluginManager.registerEvents(NestedCaptureListener(ledger, codec, mechanisms), this)
        server.pluginManager.registerEvents(
            BlockMechanismListener(codec, mechanisms, origins, ledger, uncovered::submit),
            this,
        )
        server.pluginManager.registerEvents(WorldItemListener(codec, mechanisms, origins, capture), this)
        server.pluginManager.registerEvents(inspector, this)
        server.globalRegionScheduler.runAtFixedRate(this, {
            origins.sweep()
            mechanisms.flush()
        }, 1, 1)
        server.asyncScheduler.runAtFixedRate(
            this,
            { sweepLedger(ledger) },
            SWEEP_MINUTES,
            SWEEP_MINUTES,
            TimeUnit.MINUTES,
        )
        server.asyncScheduler.runAtFixedRate(
            this,
            { reconcileEveryone(reconciliation, codec) },
            RECONCILE_MINUTES,
            RECONCILE_MINUTES,
            TimeUnit.MINUTES,
        )
        server.asyncScheduler.runAtFixedRate(this, { reportUncovered(uncovered) }, 1, 1, TimeUnit.DAYS)
        warnAboutSilencedHoppers()
        registerCommand()
        logger.info(
            "ledger open, registry sizes: " +
                RegistryNamespace.entries.joinToString { "${it.name.lowercase()}=${ledger.registries.size(it)}" }
        )
    }

    // Players are still online here and their pending region tasks are already cancelled, so the last
    // interaction of every open view is diffed before the queue is drained.
    override fun onDisable() {
        val ledger = this.ledger ?: return
        try {
            capture?.recomputeAll()
            origins?.sweep()
            mechanisms?.flush()
            ledger.drain()
            uncovered?.let { reportUncovered(it) }
        } catch (failure: Exception) {
            logger.log(Level.SEVERE, "the ledger lost entries while shutting down", failure)
        } finally {
            ledger.close()
            this.ledger = null
            this.uncovered = null
            this.codec = null
            this.capture = null
            this.mechanisms = null
            this.origins = null
            this.lookups = null
            this.inspector = null
            this.reconciliation = null
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
        if (report.reachedEnd && report.checked > 0) {
            logger.info("ledger swept to the end, ${report.checked} entries in this pass, ${report.gaps.size} gaps")
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
            for (alias in listOf("inspect", "i")) root.then(inspectNode(alias))
            for (alias in listOf("reconcile", "r")) root.then(reconcileNode(alias))
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

    private fun inspectNode(literal: String) = Commands.literal(literal)
        .requires { it.sender.hasPermission(INSPECT_PERMISSION) }
        .executes { inspect(it.source, null) }
        .then(Commands.literal("on").executes { inspect(it.source, true) })
        .then(Commands.literal("off").executes { inspect(it.source, false) })

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

    private fun lookup(source: CommandSourceStack, query: LookupQuery): Int {
        val lookups = this.lookups ?: return notReady(source)
        val player = source.executor as? Player
        if (player == null) {
            source.sender.sendMessage("Only a player can look at a block.")
            return 0
        }
        val block = player.getTargetBlockExact(TARGET_RANGE)
        if (block == null) {
            player.sendMessage("No block within $TARGET_RANGE blocks of where you are looking.")
            return 0
        }
        lookups.run(player, lookupTargetAt(block), query)
        return Command.SINGLE_SUCCESS
    }

    private fun inspect(source: CommandSourceStack, desired: Boolean?): Int {
        val inspector = this.inspector ?: return notReady(source)
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

    private fun reconcile(source: CommandSourceStack, target: Player?): Int {
        val reconciliation = this.reconciliation ?: return notReady(source)
        val codec = this.codec ?: return notReady(source)
        if (target == null) {
            source.sender.sendMessage("Name the player to reconcile.")
            return 0
        }
        val sender = source.sender
        compare(target, codec, reconciliation) { report(sender, target, it, reconciliation) }
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
        val capture = this.capture ?: return
        val ledger = this.ledger ?: return
        player.scheduler.run(this, {
            // The pass is the only writer for a player's own slots and it runs a tick behind the
            // events that ask for it, so the slots are read here against a ledger that has not been
            // told about the last thing the player did. Running the pass first is what makes the two
            // sides the same moment; without it every recent movement reads as a difference.
            capture.recompute(player)
            val held = heldForms(player, codec)
            server.asyncScheduler.runNow(this) {
                // The writer runs on a thread of its own, and what it has not written yet is missing
                // from the balance the comparison is about to read.
                ledger.drain()
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

    private fun fillTypeRegistries(registries: Registries) {
        for (key in BuiltInRegistries.ITEM.keySet()) {
            registries.idForKey(RegistryNamespace.ITEM_TYPE, key.toString())
        }
        for (key in BuiltInRegistries.DATA_COMPONENT_TYPE.keySet()) {
            registries.idForKey(RegistryNamespace.DATA_COMPONENT_TYPE, key.toString())
        }
    }
}
