package io.pfaumc.pfauprotect

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.RemoteServerCommandEvent
import org.bukkit.event.server.ServerCommandEvent

// A command's words, the way the server will read them: no slash, no namespace, lower case name.
internal fun commandWords(line: String): List<String> {
    val words = line.trim().removePrefix("/").split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return words
    return listOf(words[0].substringAfter(':').lowercase()) + words.drop(1)
}

// Which reasons a command leaves on the players it names, and who those players are, by argument.
// Only commands that reach into a player's own slots are read here; one that fills a chest or spawns an
// item on the ground has no player to explain and stays a birth or an end nobody named.
internal class CommandUse(val targets: String?, val gain: Cause?, val loss: Cause?, val change: Cause?)

internal fun commandUse(words: List<String>): CommandUse? {
    if (words.isEmpty()) return null
    val args = words.drop(1)
    return when (words[0]) {
        "give" -> CommandUse(args.getOrNull(0), gain = Cause.CMD_GIVE, loss = null, change = null)
        "clear" -> CommandUse(args.getOrNull(0), gain = null, loss = Cause.CMD_CLEAR, change = null)
        "enchant" -> CommandUse(args.getOrNull(0), gain = null, loss = null, change = Cause.CMD_ENCHANT)
        "item" -> when {
            args.getOrNull(1) != "entity" -> null
            args.getOrNull(0) == "replace" ->
                CommandUse(args.getOrNull(2), gain = Cause.CMD_ITEM_REPLACE, loss = Cause.CMD_ITEM_REPLACE, change = null)
            args.getOrNull(0) == "modify" -> CommandUse(args.getOrNull(2), gain = null, loss = null, change = Cause.CMD_ITEM_MODIFY)
            else -> null
        }
        "loot" -> if (args.getOrNull(0) == "give") CommandUse(args.getOrNull(1), gain = Cause.CMD_LOOT, loss = null, change = null) else null
        else -> null
    }
}

class CommandListener(
    private val capture: ContainerCaptureListener,
    private val codec: ItemFormCodec,
    private val origins: SpawnOrigins,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsole(event: ServerCommandEvent) = heard(event.sender, event.command)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRemote(event: RemoteServerCommandEvent) = heard(event.sender, event.command)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayer(event: PlayerCommandPreprocessEvent) = heard(event.player, event.message)

    // The command runs after this returns, and a tick later the pass sees what it did to each player.
    private fun heard(sender: CommandSender, line: String) {
        val words = commandWords(line)
        val use = commandUse(words) ?: return
        val players = targetsOf(sender, use.targets)
        if (players.isEmpty()) return
        // /give names the item, so its gain and the ghost the server drops at the player's feet for the
        // pickup animation can both be recognised by it.
        val given = if (words[0] == "give") words.getOrNull(2)?.let(::parse) else null
        val count = words.getOrNull(3)?.toIntOrNull() ?: 1
        for (player in players) {
            use.gain?.let { capture.intend(player, Intent(it, from = Void, form = given?.form)) }
            use.loss?.let { capture.intend(player, Intent(it, to = Void)) }
            use.change?.let { capture.intend(player, mutation(it)) }
            given?.let { origins.expect(Void, Cause.CMD_GIVE, it.key, spotOf(player.location), count) }
        }
    }

    private fun parse(item: String) = runCatching { codec.encodeOrNull(Bukkit.getItemFactory().createItemStack(item)) }.getOrNull()

    // A name, or a selector. A selector is not resolved here — that reads entities off the thread that
    // owns them — so anything but @s names every player online.
    // @p and @r over-name; a reason left on a player the command skipped explains nothing,
    // unless that player had an unexplained gain of their own in the same pass.
    private fun targetsOf(sender: CommandSender, target: String?): List<Player> = when {
        target == null || target == "@s" -> listOfNotNull(sender as? Player)
        target.startsWith("@") -> Bukkit.getOnlinePlayers().toList()
        else -> listOfNotNull(Bukkit.getPlayerExact(target))
    }
}
