package io.pfaumc.pfauprotect.capture.item

import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import org.bukkit.block.Block
import org.bukkit.entity.CopperGolem
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent
import org.bukkit.block.Container as ContainerBlock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Close enough to the chest to be opening it: the game's own reach for the interaction is under two
// blocks from the golem's middle.
private const val GOLEM_REACH_SQUARED = 3.0 * 3.0

// How long a chosen chest is watched for, walk included, before it is given up as never reached.
private const val GOLEM_WATCH_TICKS = 1200

private val MAINHAND = net.minecraft.world.entity.EquipmentSlot.MAINHAND.ordinal

/**
 * What a copper golem carries between chests. It takes one stack of up to sixteen from the first slot
 * that holds anything and puts it into the first slot that fits, and the server raises nothing for
 * either: only its hand changing is announced, a tick later. Which slots gave and took is read by
 * comparing the chest with a copy taken while the golem stood at it. The golem's own scheduler copies
 * the chest every tick before the server ticks the golem, and the golem announces a hand change at the
 * start of its next tick, so by then the latest copy already shows the move: the one before it is the
 * chest just before the golem reached in.
 */
class CopperGolemListener(
    private val codec: ItemFormCodec,
    private val sink: (List<Transfer>) -> Unit,
    // Runs a task every tick on the entity's own scheduler until it answers false.
    private val watch: (CopperGolem, () -> Boolean) -> Unit = { _, _ -> },
) : Listener {
    private class Target(
        val block: Block,
        @Volatile var previous: Map<Holder, Stack>? = null,
        @Volatile var seen: Map<Holder, Stack>? = null,
        @Volatile var ticks: Int = 0,
    )

    private val targets = ConcurrentHashMap<UUID, Target>()

    // Raised for every chest a golem weighs, each nearer than the last; the last one allowed is chosen.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChoose(event: ItemTransportingEntityValidateTargetEvent) {
        val golem = event.entity as? CopperGolem ?: return
        if (!event.isAllowed) return
        val first = targets.put(golem.uniqueId, Target(event.block)) == null
        if (first) watch(golem) { look(golem) }
    }

    private fun look(golem: CopperGolem): Boolean {
        val target = targets[golem.uniqueId] ?: return false
        if (!golem.isValid || ++target.ticks > GOLEM_WATCH_TICKS) {
            targets.remove(golem.uniqueId, target)
            return false
        }
        val centre = target.block.location.add(0.5, 0.5, 0.5)
        if (centre.world == golem.world && centre.distanceSquared(golem.location) <= GOLEM_REACH_SQUARED) {
            target.previous = target.seen
            target.seen = contents(target.block)
        }
        return true
    }

    private fun contents(block: Block): Map<Holder, Stack>? {
        val inventory = (block.getState(false) as? ContainerBlock)?.inventory ?: return null
        return slotsOf(inventory)
    }

    private fun slotsOf(inventory: Inventory): Map<Holder, Stack>? {
        val holders = containerHolders(inventory) ?: return null
        val stacks = HashMap<Holder, Stack>()
        for (slot in 0 until inventory.size) {
            codec.encodeOrNull(inventory.getItem(slot))?.let { stacks[holders(slot)] = Stack(it.key, it.count) }
        }
        return stacks
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onHand(event: EntityEquipmentChangedEvent) {
        val golem = event.entity as? CopperGolem ?: return
        val change = event.equipmentChanges[EquipmentSlot.HAND] ?: return
        val before = codec.encodeOrNull(change.oldItem())
        val after = codec.encodeOrNull(change.newItem())
        val target = targets[golem.uniqueId] ?: return
        val took = after != null && (before == null || !before.form.contentEquals(after.form))
        val form = (if (took) after else before)?.form ?: return
        val moved = if (took) after!!.count else before!!.count - (after?.takeIf { it.form.contentEquals(form) }?.count ?: 0)
        if (moved <= 0) return
        val now = contents(target.block) ?: return
        val previous = target.previous
        target.previous = now
        target.seen = now
        val hand = EntitySlot(golem.uniqueId, MAINHAND)
        val timestamp = System.currentTimeMillis()
        val key = (if (took) after else before)!!.key
        val rows = golemSlots(previous, now, form, took, moved, containerAt(target.block, 0))
            .map { (slot, qty, confidence) -> golemRow(took, slot, hand, key, qty, timestamp, confidence) }
        if (took) bookHeld(golem, MAINHAND, form) else if (after == null) unbookHeld(golem, MAINHAND)
        sink(rows)
    }

    private fun golemRow(took: Boolean, slot: Holder, hand: Holder, key: ItemKey, qty: Int, timestamp: Long, confidence: Confidence) =
        Transfer(
            cause = if (took) Cause.CONTAINER_REMOVE else Cause.CONTAINER_ADD,
            from = if (took) slot else hand,
            to = if (took) hand else slot,
            form = key.form,
            damage = key.damage,
            qty = qty,
            timestamp = timestamp,
            confidence = confidence,
        )
}

/**
 * The slots of a chest that gave up a form (or took it on) between two readings, and by how much.
 * Only that form is read: anything else that changed there was somebody else's doing.
 */
internal fun chestShifts(before: Map<Holder, Stack>, after: Map<Holder, Stack>, form: ByteArray, gave: Boolean): List<Pair<Holder, Int>> {
    fun countAt(slots: Map<Holder, Stack>, holder: Holder) =
        slots[holder]?.takeIf { it.key.form.contentEquals(form) }?.count ?: 0
    return (before.keys + after.keys).mapNotNull { holder ->
        val delta = countAt(after, holder) - countAt(before, holder)
        val qty = if (gave) -delta else delta
        if (qty > 0) holder to qty else null
    }.sortedBy { (it.first as? io.pfaumc.pfauprotect.model.Container)?.slot ?: 0 }
}

/**
 * The slots a golem's hand change came out of, or went into, with how sure each is: read against the
 * copy of the chest from before it reached in. What that copy cannot account for — there was none, or
 * another hand has changed the chest since — goes to the slot the game would have used, and a take is
 * looked for where the form lay before, because a slot the golem emptied holds none of it now.
 */
internal fun golemSlots(
    previous: Map<Holder, Stack>?,
    now: Map<Holder, Stack>,
    form: ByteArray,
    took: Boolean,
    moved: Int,
    fallback: Holder,
): List<Triple<Holder, Int, Confidence>> {
    val rows = ArrayList<Triple<Holder, Int, Confidence>>()
    var left = moved
    for ((slot, qty) in previous?.let { chestShifts(it, now, form, gave = took) }.orEmpty()) {
        if (left <= 0) break
        val part = minOf(left, qty)
        left -= part
        rows += Triple(slot, part, Confidence.FACT)
    }
    if (left > 0) {
        val lay = if (took) previous ?: now else now
        val guess = lay.entries.filter { it.value.key.form.contentEquals(form) }
            .minByOrNull { (it.key as? io.pfaumc.pfauprotect.model.Container)?.slot ?: 0 }?.key ?: fallback
        rows += Triple(guess, left, Confidence.INFERRED)
    }
    return rows
}
