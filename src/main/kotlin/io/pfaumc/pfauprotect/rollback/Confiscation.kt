package io.pfaumc.pfauprotect.rollback

import io.pfaumc.pfauprotect.capture.item.ContainerCaptureListener
import io.pfaumc.pfauprotect.capture.item.Intent
import io.pfaumc.pfauprotect.capture.item.WorldItemListener
import io.pfaumc.pfauprotect.capture.item.namedContents
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.PostingRef
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.PlayerEnder
import io.pfaumc.pfauprotect.model.PlayerHolder
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.EntityRow
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.RocksItemLog
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Level

// How many hops of a dropped item are followed: merges into another pile, and the pickup. Past this the
// item has gone further than a rollback should reach for it.
private const val MAX_HOPS = 4

// A joining player's slots are taken as the starting point of their first pass on the join itself, and
// what is taken back has to come after that or the pass reads it as nothing having happened.
private const val JOIN_DELAY_TICKS = 2L

// Enough of an item entity's life to follow it: a pile is born, merges and is picked up a handful of
// times, not thousands.
private const val ENTITY_ROWS = 1000

/** Who holds what a rollback put back: a player, or a pile still lying in the world. */
sealed interface Taker

data class Carrier(val player: UUID) : Taker

data class Lying(val entity: UUID) : Taker

// Nobody: the pile burned, despawned or fell out of the world. Nothing can be taken back from it, and what a
// player is owed of it can only be given out of nothing — it exists nowhere any more.
data class Vanished(val entity: UUID) : Taker

class Owed(val taker: Taker, val formId: Long, val qty: Int)

/**
 * Where `qty` of what left through `lead` is now. A player holding it is the end; a dropped pile is
 * followed through what it did — what still lies there first, then merges and the pickup in the order
 * they happened — and anything else (a hopper, a mob, the void) is out of reach and owed by nobody.
 */
internal fun trace(
    lead: Holder,
    formId: Long,
    qty: Int,
    rowsOf: (ItemEntityRef) -> List<LedgerEntry>,
    hops: Int = 0,
): List<Owed> {
    if (qty <= 0) return emptyList()
    return when (lead) {
        is PlayerHolder -> listOf(Owed(Carrier(lead.uuid), formId, qty))
        is ItemEntityRef -> {
            if (hops >= MAX_HOPS) return emptyList()
            val rows = rowsOf(lead).filter { it.itemFormId == formId }
            val owed = ArrayList<Owed>()
            var need = qty
            val lying = rows.sumOf { it.qty }
            if (lying > 0) {
                val n = minOf(need, lying)
                owed += Owed(Lying(lead.uuid), formId, n)
                need -= n
            }
            for (out in rows.filter { it.qty < 0 }.sortedBy { it.txId }) {
                if (need <= 0) break
                val n = minOf(need, -out.qty)
                owed += if (out.counterparty == Void) listOf(Owed(Vanished(lead.uuid), formId, n)) else trace(out.counterparty, formId, n, rowsOf, hops + 1)
                need -= n
            }
            owed
        }
        else -> emptyList()
    }
}

/**
 * What is owed for what a rollback gave back: the slot postings it put back, followed to whoever holds
 * them, and what fell out of a block it put back. Only a break whose every position came back gives up
 * its drops: an explosion's drops cannot be told apart by position, and half a crater does not entitle
 * anyone to all of it. Off the region thread: it reads the ledger.
 */
internal fun owedFor(ledger: RocksItemLog, tally: Tally): List<Owed> {
    val rowsOf = { pile: ItemEntityRef -> ledger.holderEntries(pile, 0, Long.MAX_VALUE, limit = ENTITY_ROWS) }
    val owed = ArrayList<Owed>()
    for (given in tally.traces) {
        if (given.qty > 0) {
            owed += trace(given.lead, given.formId, given.qty, rowsOf)
            continue
        }
        // Taken back out of a container a player had put it into: that much of what the player owes is
        // already back, and taking it from their hands as well would take their own.
        val lead = given.lead as? PlayerHolder ?: continue
        owed += Owed(Carrier(lead.uuid), given.formId, given.qty)
    }
    // What fell out of what came back, as a whole pile each: what it was born with is what it owes. A pile
    // a slot posting already leads to is followed there and not twice.
    val led = tally.traces.mapNotNullTo(HashSet()) { (it.lead as? ItemEntityRef)?.uuid }
    for (pile in tally.piles.toSet() - led) owed += wholePile(pile, rowsOf)
    val restored = tally.restored.toHashSet()
    val seen = HashSet<Long>()
    for (loss in tally.breaks) {
        if (!seen.add(loss.txId)) continue
        val whole = ledger.transactionEntries(loss)
        val gave = whole.filter { it.holder is WorldBlock && it.qty < 0 }.map { it.holder as WorldBlock }
        if (!restored.containsAll(gave)) continue
        for (drop in whole.filter { it.holder is ItemEntityRef && it.qty > 0 }) {
            owed += trace(drop.holder, drop.itemFormId, drop.qty, rowsOf)
        }
    }
    // What nobody has any more cannot be taken back from anybody.
    return merged(owed).filter { it.qty > 0 && it.taker !is Vanished }
}

private fun wholePile(pile: UUID, rowsOf: (ItemEntityRef) -> List<LedgerEntry>): List<Owed> {
    val births = rowsOf(ItemEntityRef(pile)).filter { it.qty > 0 && it.cause != Cause.ITEM_MERGE }
    val formId = births.firstOrNull()?.itemFormId ?: return emptyList()
    return trace(ItemEntityRef(pile), formId, births.sumOf { it.qty }, rowsOf)
}

/**
 * Where everything that fell out of a killed player is now (SPEC-v7 §11): who picked it up, what still
 * lies there, what is gone for good. The victim's own hands are left out: what they picked up again they
 * have.
 */
internal fun restitutionFor(ledger: RocksItemLog, death: EntityRow): List<Owed> =
    merged(death.drops.filter { it !in restituted(ledger, death) }.flatMap { wholePile(it, pileRows(ledger)) })
        .filter { it.qty > 0 && it.taker != Carrier(death.uuid) }

/**
 * The births of the piles that fell out of a killed player, by pile: what a rollback that gave the
 * victim back their loss marks as given back, so a second rollback gives nothing twice.
 */
internal fun pileBirths(ledger: RocksItemLog, death: EntityRow): Map<UUID, List<PostingRef>> = death.drops.associateWith { pile ->
    pileRows(ledger)(ItemEntityRef(pile)).filter { it.qty > 0 && it.cause != Cause.ITEM_MERGE }.map { it.ref }
}

// Piles an earlier rollback already gave the victim back.
private fun restituted(ledger: RocksItemLog, death: EntityRow): Set<UUID> {
    val births = pileBirths(ledger, death)
    val given = ledger.compensated(births.values.flatten()).keys
    return births.filterValues { refs -> refs.any { it in given } }.keys
}

private fun pileRows(ledger: RocksItemLog) = { pile: ItemEntityRef -> ledger.holderEntries(pile, 0, Long.MAX_VALUE, limit = ENTITY_ROWS) }

/** The same taker and form owed more than once, as one amount. */
internal fun merged(owed: List<Owed>): List<Owed> =
    owed.groupBy { it.taker to it.formId }.map { (key, all) -> Owed(key.first, key.second, all.sumOf { it.qty }) }

/**
 * Takes back what a rollback put back from whoever carried it off (SPEC-v7 §9). A player's slots are
 * written only by their pass, so what is taken from them leaves a reason for it, the way a `/clear`
 * does; an ender chest nobody has open is not something the pass reads, and is written directly. A
 * player who is offline owes it until they join. A pile still lying is taken where it lies.
 */
class Confiscations(
    private val plugin: Plugin,
    private val ledger: RocksItemLog,
    private val codec: ItemFormCodec,
    private val capture: ContainerCaptureListener,
    private val items: WorldItemListener,
    private val sink: (List<Transfer>) -> Unit,
) : Listener {

    fun owedFor(tally: Tally): List<Owed> = owedFor(ledger, tally)

    // Players one by one; piles together, since there can be dozens of them and none has a name.
    fun describe(owed: List<Owed>): String {
        val players = owed.filter { it.taker is Carrier }.groupBy { it.taker as Carrier }.map { (taker, all) ->
            val who = (Bukkit.getOfflinePlayer(taker.player).name ?: taker.player.toString()) +
                if (Bukkit.getPlayer(taker.player) == null) " (offline, at their next join)" else ""
            "$who: ${amounts(all)}"
        }
        val piles = owed.filter { it.taker is Lying }
        val gone = owed.filter { it.taker is Vanished }
        val lying = if (piles.isEmpty()) emptyList() else {
            listOf("${piles.map { it.taker }.distinct().size} items lying in the world: ${amounts(piles)}")
        }
        val vanished = if (gone.isEmpty()) emptyList() else listOf("gone for good: ${amounts(gone)}")
        return (players + lying + vanished).joinToString("; ")
    }

    private fun amounts(owed: List<Owed>) =
        owed.groupBy { it.formId }.entries.joinToString(", ") { (form, all) -> "${all.sumOf { it.qty }} ${name(form)}" }

    /**
     * Gives a killed player back what fell out of them: taken from whoever has it, as it is taken, and
     * what is gone for good out of nothing.
     */
    fun restore(victim: UUID, owed: List<Owed>, actor: UUID?, sender: CommandSender, births: List<PostingRef> = emptyList()) {
        // A pile that went between the read and the take is gone for good like a burned one, and given out
        // of nothing the same way, so every birth marked below was given back one way or the other.
        take(
            owed.filter { it.taker !is Vanished }, actor, sender,
            gone = { formId, n -> give(victim, formId, n, actor, sender) },
        ) { formId, n -> give(victim, formId, n, actor, sender) }
        for (gone in owed.filter { it.taker is Vanished }) give(victim, gone.formId, gone.qty, actor, sender)
        // The piles' births marked as given back. Nothing moves, so no posting is written: only the mark.
        val form = owed.firstNotNullOfOrNull { ledger.form(it.formId) } ?: return
        if (births.isNotEmpty()) sink(listOf(Transfer(Cause.ROLLBACK, Void, Void, form, null, 1, System.currentTimeMillis(), actor = actor, reverts = births)))
    }

    // `taken` hears of every amount actually taken, or owed by a player who will hand it over on joining;
    // `gone`, of what was lying in the world at the read and was not there to take.
    fun take(
        owed: List<Owed>,
        actor: UUID?,
        sender: CommandSender,
        gone: (Long, Int) -> Unit = { _, _ -> },
        taken: (Long, Int) -> Unit = { _, _ -> },
    ) {
        for ((taker, all) in owed.groupBy { it.taker }) {
            when (taker) {
                is Carrier -> {
                    val player = Bukkit.getPlayer(taker.player)
                    if (player == null) {
                        for (item in all) {
                            ledger.owe(taker.player, item.formId, item.qty, actor)
                            taken(item.formId, item.qty)
                        }
                        continue
                    }
                    // A player who leaves between the two is owed it instead.
                    player.scheduler.run(plugin, { fromPlayer(player, all, actor, sender, taken) }) {
                        Bukkit.getAsyncScheduler().runNow(plugin) {
                            for (item in all) {
                                ledger.owe(taker.player, item.formId, item.qty, actor)
                                taken(item.formId, item.qty)
                            }
                        }
                    }
                }
                // Looking an entity up by its uuid is a tick thread's business, and the pile may lie in any
                // region; the global one may ask, and the pile's own scheduler does the rest.
                is Lying -> Bukkit.getGlobalRegionScheduler().execute(plugin) {
                    val pile = Bukkit.getEntity(taker.entity) as? Item
                    val missing = { all.forEach { gone(it.formId, it.qty) } }
                    if (pile == null) {
                        sender.sendMessage("  ${all.sumOf { it.qty }} ${name(all.first().formId)} were lying in the world and are gone since.")
                        missing()
                    } else {
                        pile.scheduler.run(plugin, { all.forEach { fromPile(pile, it, actor, sender, gone, taken) } }, missing)
                            ?: missing()
                    }
                }
                is Vanished -> Unit
            }
        }
    }

    // Out of nothing into a player's hands: what fits goes in now, through the pass as a reason for the
    // gain; what does not, at their next join.
    private fun give(player: UUID, formId: Long, qty: Int, actor: UUID?, sender: CommandSender?) {
        val online = Bukkit.getPlayer(player)
        if (online == null) {
            Bukkit.getAsyncScheduler().runNow(plugin) { ledger.owe(player, formId, -qty, actor) }
            return
        }
        online.scheduler.run(plugin, { toPlayer(online, formId, qty, actor, sender) }) {
            Bukkit.getAsyncScheduler().runNow(plugin) { ledger.owe(player, formId, -qty, actor) }
        }
    }

    // On the player's own thread.
    private fun toPlayer(player: Player, formId: Long, qty: Int, actor: UUID?, sender: CommandSender?) {
        val form = ledger.form(formId) ?: return
        val left = player.inventory.addItem(CraftItemStack.asBukkitCopy(codec.decode(form, qty, null))).values.sumOf { it.amount }
        val added = qty - left
        if (added > 0) capture.intend(player, Intent(Cause.ROLLBACK, from = Void, form = form, qty = added, actor = actor))
        if (left > 0) Bukkit.getAsyncScheduler().runNow(plugin) { ledger.owe(player.uniqueId, formId, -left, actor) }
        val message = "  gave back $added ${name(formId)} to ${player.name}" + if (left > 0) "; $left more at their next join." else "."
        if (sender != null) sender.sendMessage(message) else plugin.logger.info("rollback at join:$message")
    }

    // On the player's own thread.
    private fun fromPlayer(player: Player, owed: List<Owed>, actor: UUID?, sender: CommandSender?, taken: (Long, Int) -> Unit = { _, _ -> }) {
        val direct = ArrayList<Transfer>()
        val now = System.currentTimeMillis()
        val enderOpen = player.openInventory.topInventory.type == InventoryType.ENDER_CHEST
        for (item in owed) {
            val form = ledger.form(item.formId) ?: continue
            var left = item.qty
            var seen = 0
            fun drain(stack: ItemStack?, put: (ItemStack?) -> Unit): Int {
                if (left == 0 || stack == null || codec.encodeOrNull(stack)?.form?.contentEquals(form) != true) return 0
                val n = minOf(left, stack.amount)
                put(if (n == stack.amount) null else stack.clone().apply { amount -= n })
                left -= n
                return n
            }
            val inventory = player.inventory
            for (slot in 0 until inventory.size) seen += drain(inventory.getItem(slot)) { inventory.setItem(slot, it) }
            seen += drain(player.itemOnCursor) { player.setItemOnCursor(it) }
            val ender = player.enderChest
            for (slot in 0 until ender.size) {
                val stack = ender.getItem(slot)
                val damage = codec.encodeOrNull(stack)?.damage
                val n = drain(stack) { ender.setItem(slot, it) }
                // The pass reads an ender chest only while it is open; shut, nothing would write this.
                if (n == 0) continue
                if (enderOpen) seen += n
                else direct += Transfer(Cause.ROLLBACK, PlayerEnder(player.uniqueId, slot), Void, form, damage, n, now, actor = actor)
            }
            if (seen > 0) capture.intend(player, Intent(Cause.ROLLBACK, to = Void, form = form, qty = seen, actor = actor))
            val got = item.qty - left
            if (got > 0) taken(item.formId, got)
            val message = if (left == 0) "  took back $got ${name(item.formId)} from ${player.name}."
            else "  ${player.name} held only $got of ${item.qty} ${name(item.formId)}; the rest is beyond reach."
            if (sender != null) sender.sendMessage(message) else plugin.logger.info("rollback at join:$message")
        }
        if (direct.isNotEmpty()) sink(direct)
    }

    // On the thread of the region the pile lies in.
    private fun fromPile(
        pile: Item,
        owed: Owed,
        actor: UUID?,
        sender: CommandSender,
        gone: (Long, Int) -> Unit,
        taken: (Long, Int) -> Unit,
    ) {
        if (!pile.isValid) {
            sender.sendMessage("  ${owed.qty} ${name(owed.formId)} were lying in the world and are gone since.")
            gone(owed.formId, owed.qty)
            return
        }
        val stack = pile.itemStack
        val encoded = codec.encodeOrNull(stack) ?: return gone(owed.formId, owed.qty)
        val n = minOf(owed.qty, stack.amount)
        if (n < owed.qty) gone(owed.formId, owed.qty - n)
        val now = System.currentTimeMillis()
        val rows = arrayListOf(Transfer(Cause.ROLLBACK, ItemEntityRef(pile.uniqueId), Void, encoded.form, encoded.damage, n, now, actor = actor))
        if (n == stack.amount) {
            // The removal is written here, contents and all, and the capture of item ends stands aside.
            for ((inside, child) in namedContents(CraftItemStack.asNMSCopy(stack), codec)) {
                rows += Transfer(Cause.ROLLBACK, inside, Void, child.form, child.damage, child.count, now, actor = actor)
            }
            items.forgetEnd(pile.uniqueId)
            pile.remove()
        } else {
            pile.itemStack = stack.clone().apply { amount -= n }
        }
        sink(rows)
        taken(owed.formId, n)
        sender.sendMessage("  took back $n ${name(owed.formId)} lying in the world.")
    }

    /** What was owed by a player while they were offline, taken on their join. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!plugin.isEnabled) return
        Bukkit.getAsyncScheduler().runNow(plugin) {
            val owedItems = ledger.owedBy(player.uniqueId)
            if (owedItems.isEmpty()) return@runNow
            player.scheduler.runDelayed(plugin, {
                try {
                    for ((actor, group) in owedItems.groupBy { it.actor }) {
                        val (taking, giving) = group.partition { it.qty > 0 }
                        fromPlayer(player, taking.map { Owed(Carrier(player.uniqueId), it.formId, it.qty) }, actor, null)
                        for (item in giving) toPlayer(player, item.formId, -item.qty, actor, null)
                    }
                    Bukkit.getAsyncScheduler().runNow(plugin) { ledger.forgive(owedItems) }
                } catch (failure: Exception) {
                    plugin.logger.log(Level.SEVERE, "taking back what ${player.name} owed a rollback failed", failure)
                }
            }, null, JOIN_DELAY_TICKS)
        }
    }

    private fun name(formId: Long): String = io.pfaumc.pfauprotect.command.itemKey(ledger, formId) ?: "item form $formId"
}
