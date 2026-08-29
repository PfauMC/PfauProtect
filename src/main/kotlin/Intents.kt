package io.pfaumc.pfauprotect

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

// The recompute is the only writer for a player's own slots, and that is not a matter of taste. The
// pass runs a tick after the event, on the player's own queue, so two events in one tick are two
// tasks whose order the player decides and we do not: whichever runs first sees both changes already
// applied. An event that wrote its own row would therefore be counted twice whenever the game
// happened to run them in the other order — rare, unreproducible, and shaped exactly like a dupe.
//
// So an event leaves an intent instead: what is about to happen and why. The recompute counts, the
// event explains. An intent nobody needed is simply dropped when the pass ends.

// What the pass sees of a transformation is a handful of ends that pair with nothing: the ingredients
// leave for the Void and the product arrives out of it, in whatever quantities the snapshot says. Only
// the event knows those ends are one event, and it says so by naming both sides and how they relate.
class Shift(val consume: Cause, val result: Cause, val kind: Kind)

class Intent(
    val cause: Cause,
    // The end that is not the player. A gain names `from`, a loss names `to`, and an intent that
    // names neither only lends its cause to whatever the pass pairs up by itself.
    val from: Holder? = null,
    val to: Holder? = null,
    // Both optional and both only ever a hint: the pass looks at the state that was actually applied,
    // the event only at what it intended, so on numbers the pass always wins.
    val form: ByteArray? = null,
    val qty: Int? = null,
    val actor: UUID? = null,
    // Death is written where it happens, because only the death event still knows which slot each
    // dropped stack came out of. The pass that follows has to swallow what it finds rather than book
    // the same loss a second time.
    val recorded: Boolean = false,
    // The player's own end, named only by an event that still knew which slot it was. A movement
    // already written has to swallow that slot's loss and no other, or the loss it was written for
    // reaches the pass unexplained and is written off a second time.
    val holder: Holder? = null,
    // Set by an event that turned one form into another. It names no end and no amount, so it is spent
    // on nothing here; the pass reads it after the fact to gather its own unpaired ends into one
    // transaction.
    val shift: Shift? = null,
) {
    // An intent is about the slots of the player who left it, so the end that is not its counterparty
    // has to be one of theirs. Matching on form alone lets an open container's own unpaired loss take
    // the player's drop intent, because the top inventory is snapshotted first and is therefore
    // offered every intent ahead of the player's own slots.
    internal fun explains(end: Holder, form: ByteArray, player: UUID?) =
        end is PlayerHolder && end.uuid == player && (holder == null || holder == end) && matches(form)

    // A recorded movement that names neither end is still not a label: read as one it would emit the
    // very row it was left to suppress. Anything else has to name the far end to have something to
    // say about a loss or a gain at all.
    internal fun aims(loss: Boolean) =
        if (recorded && from == null && to == null) true else (if (loss) to else from) != null

    internal fun matches(form: ByteArray) = this.form == null || this.form.contentEquals(form)
}

data class Move(
    val from: Holder,
    val to: Holder,
    val key: ItemKey,
    val qty: Int,
    val cause: Cause,
    val confidence: Confidence,
    val actor: UUID? = null,
)

// Intents belong to one player and are read on that player's own region thread a tick after they are
// written, so nothing here contends beyond the handover between the two.
class PlayerIntents {
    private val byPlayer = ConcurrentHashMap<UUID, ConcurrentLinkedQueue<Intent>>()

    fun add(player: UUID, intent: Intent) {
        byPlayer.computeIfAbsent(player) { ConcurrentLinkedQueue() }.add(intent)
    }

    /** Drains: an intent survives exactly one pass, and the pass it survives into is its last. */
    fun take(player: UUID): List<Intent> {
        val queue = byPlayer[player] ?: return emptyList()
        return generateSequence(queue::poll).toList()
    }

    fun forget(player: UUID) {
        byPlayer.remove(player)
    }
}

object Intents {
    // The pass counts and the event explains. An edge the pass could not pair by itself is missing one
    // end, and an intent that names that end supplies it; what nothing explains stays facing Void
    // with the confidence it came with, because that row is the measure of the paths the capture does
    // not cover yet and losing it would mean losing the only sign that they exist.
    fun explain(
        edges: List<Edge>,
        intents: List<Intent>,
        player: UUID,
        unspent: (Intent, Int) -> Unit = { _, _ -> },
        // Any slot of this player's that is holding the form, for a netted pair neither intent gave a
        // slot of its own. The pass can see that and this cannot.
        carrying: (ByteArray) -> Holder? = { null },
    ): List<Move> {
        // An intent that names no quantity is a reason rather than an amount and stays available for
        // the whole pass; one that names a number gives out that much in total and no more, however
        // many edges reach for it.
        val left = IntArray(intents.size) { intents[it].qty ?: Int.MAX_VALUE }
        val moves = ArrayList<Move>(edges.size)
        for (edge in edges) {
            if (edge.from != Void && edge.to != Void) {
                // One click can spread a stack over a dozen slots and that is still one reason, so an
                // intent used as a label is never used up by the edges it names.
                val label = intents.firstOrNull { it.labels(edge, player) }
                moves += Move(edge.from, edge.to, edge.key, edge.qty, label?.cause ?: causeOf(edge), Confidence.FACT)
                continue
            }
            val loss = edge.to == Void
            val end = if (loss) edge.from else edge.to
            var remaining = edge.qty
            while (remaining > 0) {
                val index = pick(intents, left, end, edge.key.form, player, loss)
                if (index < 0) {
                    moves += Move(edge.from, edge.to, edge.key, remaining, causeOf(edge), edge.confidence)
                    break
                }
                val intent = intents[index]
                val qty = minOf(remaining, left[index])
                left[index] -= qty
                remaining -= qty
                // Already in the journal, written where the slot it came out of was still known.
                // Writing it a second time here is what an intent like this exists to prevent.
                if (intent.recorded) continue
                moves += if (loss) {
                    Move(edge.from, intent.to!!, edge.key, qty, intent.cause, Confidence.FACT, intent.actor)
                } else {
                    Move(intent.from!!, edge.to, edge.key, qty, intent.cause, Confidence.FACT, intent.actor)
                }
            }
        }
        // Two opposite movements of one form into one slot cancel before the pass can see them: the
        // slot reads the same on both sides and there is no delta for either intent to be spent on.
        // Neither half may be written alone — an intent names its far end and not its slot, and a
        // placement in creative consumes nothing, so a lone one would invent a debit that was never
        // paid. The pair is safe where the half is not: run through one and the same holder the two
        // rows move the player's own total by zero whichever holder that turns out to be, while the
        // two far ends each get the row they are owed.
        for (out in intents.indices) {
            val outgoing = intents[out]
            val to = outgoing.to ?: continue
            // An intent that would match any form must not be married to an unrelated one.
            val form = outgoing.form ?: continue
            if (!nettable(outgoing, left[out])) continue
            for (into in intents.indices) {
                if (left[out] <= 0) break
                // An intent that named both of its ends would otherwise marry itself and spend its
                // remainder twice over.
                if (into == out) continue
                val incoming = intents[into]
                val from = incoming.from ?: continue
                if (incoming.form?.contentEquals(form) != true || !nettable(incoming, left[into])) continue
                val holder = outgoing.holder ?: incoming.holder ?: carrying(form) ?: continue
                val qty = minOf(left[out], left[into])
                left[out] -= qty
                left[into] -= qty
                val key = ItemKey(form, null)
                moves += Move(holder, to, key, qty, outgoing.cause, Confidence.FACT, outgoing.actor)
                moves += Move(from, holder, key, qty, incoming.cause, Confidence.FACT, incoming.actor)
            }
        }
        // An intent that asked for a number the pass never found is the one case where the event knew
        // something the snapshot cannot show, and an event that silenced a funnel of its own on the
        // promise of this pass has to be told so it can fall back to writing the row itself.
        for (index in intents.indices) {
            if (intents[index].qty != null && left[index] > 0) unspent(intents[index], left[index])
        }
        return moves
    }

    // An intent that named no number is a reason and not an amount, and netting one would net an
    // unbounded quantity; one already written has its row and must not be given a second.
    private fun nettable(intent: Intent, left: Int) = left > 0 && intent.qty != null && !intent.recorded

    // Order of arrival decides between equals, so the pass is reproducible from the events alone. The
    // one exception is an intent whose movement is already written: it wins over an intent that would
    // merely explain the same remainder, because a row written twice is worse than a row named loosely.
    private fun pick(
        intents: List<Intent>,
        left: IntArray,
        end: Holder,
        form: ByteArray,
        player: UUID,
        loss: Boolean,
    ): Int {
        var found = -1
        for (index in intents.indices) {
            val intent = intents[index]
            if (left[index] <= 0 || !intent.aims(loss) || !intent.explains(end, form, player)) continue
            if (intent.recorded) return index
            if (found < 0) found = index
        }
        return found
    }

    // A label carries no counterparty, so it only has to be about a movement this player made: an
    // edge between two slots neither of which is theirs happened for some other reason.
    private fun Intent.labels(edge: Edge, player: UUID) =
        shift == null && from == null && to == null && !recorded && matches(edge.key.form) &&
            (edge.from.heldBy(player) || edge.to.heldBy(player))

    private fun Holder.heldBy(player: UUID) = this is PlayerHolder && uuid == player
}
