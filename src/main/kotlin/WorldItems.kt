package io.pfaumc.pfauprotect

import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent

// There is no cause for the end of an item that nobody can name, so the cause for its unnamed
// beginning stands on the other side of the row instead. Deliberately written as a guess: an end
// nobody explained is a path the capture still misses, and it has to be counted as one.
private val UNNAMED_END = Cause.ITEM_SPAWN to Confidence.INFERRED

// A pile never takes more than this in one merge, however high the item itself stacks.
private const val MERGE_CAP = 64

/** What to write for a removal, or null when the removal is not an end at all. */
internal fun itemEnd(
    removal: EntityRemoveEvent.Cause,
    damage: DamageCause?,
    health: Int,
): Pair<Cause, Confidence>? =
    when (removal) {
        // Both already have an event of their own that says more than this one can, and a row here
        // would book the same movement a second time.
        EntityRemoveEvent.Cause.PICKUP, EntityRemoveEvent.Cause.MERGE -> null
        // Neither is an end: the entity goes into the region file still holding its item and comes
        // back holding it, so a death written here turns into a duplicate on the next load.
        EntityRemoveEvent.Cause.UNLOAD, EntityRemoveEvent.Cause.PLAYER_QUIT -> null
        EntityRemoveEvent.Cause.DESPAWN -> Cause.ITEM_DESPAWN to Confidence.FACT
        EntityRemoveEvent.Cause.OUT_OF_WORLD -> Cause.ITEM_DESTROY_VOID to Confidence.FACT
        EntityRemoveEvent.Cause.DISCARD, EntityRemoveEvent.Cause.PLUGIN -> Cause.CMD_KILL_ITEM to Confidence.FACT
        // The one removal that runs through the damage pipeline, and the only one whose last damage
        // can belong to it at all. Even here it need not: killing an item outright removes it with
        // this cause without hurting it, and damage it survived earlier stays readable on it for
        // ever, so an item still in good health was ended by something other than what it carries.
        EntityRemoveEvent.Cause.DEATH ->
            if (health > 0) Cause.CMD_KILL_ITEM to Confidence.FACT
            else when (damage) {
                DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA ->
                    Cause.ITEM_DESTROY_FIRE to Confidence.FACT

                DamageCause.CONTACT -> Cause.ITEM_DESTROY_CACTUS to Confidence.FACT
                DamageCause.BLOCK_EXPLOSION, DamageCause.ENTITY_EXPLOSION ->
                    Cause.ITEM_DESTROY_EXPLOSION to Confidence.FACT
                // An explosion with no source behind it arrives as CUSTOM, and nothing in the damage
                // tells it apart from anything else unnamed. Calling that fire would be inventing it.
                else -> UNNAMED_END
            }

        else -> UNNAMED_END
    }

// Two piles only merge at all when the whole donor fits under the survivor's own maximum, and the
// merge that follows still stops at 64 whatever that maximum is. An item that stacks higher than 64 —
// a datapack or a plugin can take it to 99 — therefore moves in part, the donor lives on holding the
// rest, and the event comes round again for what is left of it.
internal fun mergedAmount(donor: Int, target: Int, targetMax: Int): Int =
    minOf(minOf(targetMax, MERGE_CAP) - target, donor).coerceAtLeast(0)

// A drop hands the birth of its entity over to the pass that follows: the drop event silences the
// spawn funnel so that the pass can name the slot the item left instead. A drop out of the creative
// menu empties no slot, so the pass has nothing to see and writes nothing, and the entity is never
// born at all — whatever becomes of it next is then a debit against a credit nobody made. What the
// pass could not spend is born here after all, as the row the funnel would have written.
internal fun unspentDrop(pending: TickCoalescer, intent: Intent, qty: Int) {
    val entity = intent.to as? ItemEntityRef ?: return
    val form = intent.form ?: return
    pending.add(Void, entity, Cause.ITEM_SPAWN, ItemKey(form, null), qty, confidence = Confidence.INFERRED)
}

// The whole life of an item lying in the world: one funnel it is born through, one net that catches
// every way it stops existing. Both ends are written without exception, which is what makes the
// balance of an item entity closed by construction — an entity that does not balance is then a hole
// in the capture and not a rounding error, and the holes can be counted.
class WorldItemListener(
    private val codec: ItemFormCodec,
    private val pending: TickCoalescer,
    private val origins: SpawnOrigins,
    private val capture: ContainerCaptureListener,
) : Listener {

    // Every path that adds an entity to a world comes through here, so this is the only place a birth
    // can be written and the only place one can be missed. Whatever no note explained is still
    // written, as a guess, rather than passed over.
    //
    // The ghost item /give makes is not filtered out. Nothing distinguishes it while the event runs —
    // the server marks it fake only after the handler returns — and it despawns on the next tick, so
    // its birth and its end cancel each other out.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpawn(event: ItemSpawnEvent) {
        val entity = event.entity
        val encoded = codec.encodeOrNull(entity.itemStack) ?: return
        val at = entity.location
        val spot = spotOf(at)
        val unexplained = encoded.count - origins.claim(entity.uniqueId, spot, encoded.key, encoded.count)
        pending.add(
            Void,
            ItemEntityRef(entity.uniqueId),
            Cause.ITEM_SPAWN,
            encoded.key,
            unexplained,
            confidence = Confidence.INFERRED,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemove(event: EntityRemoveEvent) {
        val item = event.entity as? Item ?: return
        // The server fires this before it checks whether the entity is already gone, so one removal
        // can arrive twice. The duplicate is the arrival that finds the entity already dead.
        if (item.isDead) return
        val (cause, confidence) = itemEnd(event.cause, item.lastDamageCause?.cause, item.health) ?: return
        val encoded = codec.encodeOrNull(item.itemStack) ?: return
        pending.add(ItemEntityRef(item.uniqueId), Void, cause, encoded.key, encoded.count, confidence = confidence)
    }

    // The smaller pile is always the one that gives way, so which of two items survives can come out
    // differently from one tick to the next; the server names the donor first and the survivor
    // second. The stacks are still untouched here, and how much of the donor actually crosses is
    // decided after the event by how much room the survivor has left.
    //
    // The row looks like bookkeeping between two entities holding the same thing, and it is written
    // anyway because the donor's thrower is not carried across. Afterwards this row is the only thing
    // left saying where half the pile came from.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMerge(event: ItemMergeEvent) {
        val donor = event.entity
        val encoded = codec.encodeOrNull(donor.itemStack) ?: return
        val into = event.target.itemStack
        pending.add(
            ItemEntityRef(donor.uniqueId),
            ItemEntityRef(event.target.uniqueId),
            Cause.ITEM_MERGE,
            encoded.key,
            mergedAmount(encoded.count, into.amount, into.maxStackSize),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val item = event.item
        val stack = item.itemStack
        val encoded = codec.encodeOrNull(stack) ?: return
        val picker = event.entity
        // The two sides count differently. For a player the pile on the ground was trimmed to what
        // the inventory can hold before the event fired, so what it holds now is what was taken. For
        // a mob it was not trimmed, and only the difference against what stays behind says how much
        // actually moved.
        if (picker is Player) {
            capture.intend(
                picker,
                Intent(
                    cause = Cause.PICKUP,
                    from = ItemEntityRef(item.uniqueId),
                    form = encoded.form,
                    qty = encoded.count,
                ),
            )
            return
        }
        pending.add(
            ItemEntityRef(item.uniqueId),
            EntitySlot(picker.uniqueId, equipmentSlotOf(picker, stack)),
            Cause.ITEM_PICKUP_BY_MOB,
            encoded.key,
            encoded.count - event.remaining,
        )
    }
}
