package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.model.Void
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.RemoteServerCommandEvent
import org.bukkit.event.server.ServerCommandEvent

// How many of the player's own ticks a command's reason waits for the change it explains. The server
// applies a command to a player in another region a tick or two after it is heard, and a pass can run
// in between. Counted from the moment the reason reaches the player's thread and in that region's
// ticks: a quarter of a second, counted from hearing, ran out right after a join, when the region
// takes longer than that over a single tick, and the command's change was written as nobody's.
internal const val COMMAND_LINGER_TICKS = 5

// How long the item a full inventory throws out may take to land: a teleport earlier in the batch has to
// finish first, and across a world that loads chunks.
private const val GIVE_DROP_MILLIS = 5_000L

// A command's words, the way the server will read them: no slash, no namespace, lower case name.
// `execute … run give …` runs the give; everything before the last `run` only changes who and where.
internal fun commandWords(line: String): List<String> {
    val words = line.trim().removePrefix("/").split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return words
    val named = listOf(words[0].substringAfter(':').lowercase()) + words.drop(1)
    if (named[0] != "execute") return named
    val run = named.lastIndexOf("run")
    return if (run < 0) named else commandWords(named.drop(run + 1).joinToString(" "))
}

// Whether the line runs its command through `execute`, which can make `@s` anybody.
internal fun executed(line: String) =
    line.trim().removePrefix("/").substringBefore(' ').substringAfter(':').lowercase() == "execute"

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
    // Runs a task on the player's own scheduler a tick later.
    private val later: (Player, () -> Unit) -> Unit,
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
        val players = targetsOf(sender, use.targets, executed(line))
        if (players.isEmpty()) return
        // /give names the item, so its gain and the ghost the server drops at the player's feet for the
        // pickup animation can both be recognised by it.
        val given = if (words[0] == "give") words.getOrNull(2)?.let(::parse) else null
        val count = words.getOrNull(3)?.toIntOrNull() ?: 1
        val thrownUntil = System.currentTimeMillis() + GIVE_DROP_MILLIS
        for (player in players) {
            // What does not fit is thrown at the player's feet, as the player, wherever the player is
            // by the time the command runs.
            given?.let { origins.expectThrown(player.uniqueId, Void, Cause.CMD_GIVE, it.key, count, thrownUntil) }
            val leave: () -> Unit = {
                // On the player's own thread, so the tick is the one the player's region counts.
                val until = Bukkit.getCurrentTick() + COMMAND_LINGER_TICKS
                listOfNotNull(
                    use.gain?.let { Intent(it, from = Void, form = given?.form, untilTick = until) },
                    use.loss?.let { Intent(it, to = Void, untilTick = until) },
                    use.change?.let { mutation(it, until) },
                ).forEach { capture.intend(player, it) }
            }
            // From another region the server hands the command to the player's scheduler a tick on,
            // queued behind the pass an intent left now would schedule: that pass would spend the reason
            // on an inventory the command has not touched yet. Left a tick later, the reason lands
            // before the command's action and its pass after it.
            if (Bukkit.isOwnedByCurrentRegion(player)) leave() else later(player, leave)
        }
    }

    private fun parse(item: String) = runCatching { codec.encodeOrNull(Bukkit.getItemFactory().createItemStack(item)) }.getOrNull()

    // A name, or a selector. A selector is not resolved here — that reads entities off the thread that
    // owns them — so anything but @s names every player online.
    // @p and @r over-name; a reason left on a player the command skipped explains nothing,
    // unless that player had an unexplained gain of their own in the same pass.
    private fun targetsOf(sender: CommandSender, target: String?, executed: Boolean): List<Player> = when {
        // Run through `execute as`, the command's own self is whoever that named.
        executed && (target == null || target.startsWith("@")) -> Bukkit.getOnlinePlayers().toList()
        target == null || target == "@s" -> listOfNotNull(sender as? Player)
        target.startsWith("@") -> Bukkit.getOnlinePlayers().toList()
        else -> listOfNotNull(Bukkit.getPlayerExact(target))
    }
}
