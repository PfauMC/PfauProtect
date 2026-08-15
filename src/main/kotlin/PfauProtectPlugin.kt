package io.pfaumc.pfauprotect

import com.mojang.brigadier.Command
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

private const val TARGET_RANGE = 6
private const val LOOKUP_PERMISSION = "pfauprotect.lookup"
private const val INSPECT_PERMISSION = "pfauprotect.inspect"

class PfauProtectPlugin : JavaPlugin() {
    private var ledger: RocksItemLog? = null
    private var capture: ContainerCaptureListener? = null
    private var mechanisms: TickCoalescer? = null
    private var lookups: Lookups? = null
    private var inspector: Inspector? = null

    override fun onEnable() {
        val ledger = RocksItemLog(dataFolder.toPath().resolve("ledger"))
        this.ledger = ledger
        fillTypeRegistries(ledger.registries)
        val codec = ItemFormCodec(ledger.registries, MinecraftServer.getServer().registryAccess())
        val capture = ContainerCaptureListener(this, ledger, codec)
        this.capture = capture
        val mechanisms = TickCoalescer(ledger::submit)
        this.mechanisms = mechanisms
        val lookups = Lookups(this, ledger)
        this.lookups = lookups
        val inspector = Inspector(lookups)
        this.inspector = inspector
        server.pluginManager.registerEvents(capture, this)
        server.pluginManager.registerEvents(MechanismCaptureListener(codec, mechanisms), this)
        server.pluginManager.registerEvents(inspector, this)
        server.globalRegionScheduler.runAtFixedRate(this, { mechanisms.flush() }, 1, 1)
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
            mechanisms?.flush()
            ledger.drain()
        } catch (failure: Exception) {
            logger.log(Level.SEVERE, "the ledger lost entries while shutting down", failure)
        } finally {
            ledger.close()
            this.ledger = null
            this.capture = null
            this.mechanisms = null
            this.lookups = null
            this.inspector = null
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
        val label = "${block.type.name.lowercase()} at ${block.x} ${block.y} ${block.z}"
        lookups.run(player, LookupTarget(block.world.uid, block.x, block.y, block.z, label), query)
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
            if (now) "Inspector enabled. Click a container to read its ledger."
            else "Inspector disabled."
        )
        return Command.SINGLE_SUCCESS
    }

    private fun notReady(source: CommandSourceStack): Int {
        source.sender.sendMessage("The ledger is not open.")
        return 0
    }

    private fun fillTypeRegistries(registries: Registries) {
        for (item in BuiltInRegistries.ITEM) {
            registries.idForKey(RegistryNamespace.ITEM_TYPE, BuiltInRegistries.ITEM.getKey(item).toString())
        }
        for (type in BuiltInRegistries.DATA_COMPONENT_TYPE) {
            registries.idForKey(
                RegistryNamespace.DATA_COMPONENT_TYPE,
                BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString(),
            )
        }
    }
}
