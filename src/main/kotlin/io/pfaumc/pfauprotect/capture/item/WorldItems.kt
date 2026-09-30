package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.attribution.Attribution
import io.pfaumc.pfauprotect.capture.block.BlockDestructionListener
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.storage.EncodedItem
import io.pfaumc.pfauprotect.attribution.EntityOrigins
import io.pfaumc.pfauprotect.attribution.culprit
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.capture.block.Spot
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.capture.block.firedBy
import io.pfaumc.pfauprotect.capture.block.litBy
import io.pfaumc.pfauprotect.capture.block.spotOf
import java.util.UUID
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import net.minecraft.world.item.ItemStack as NmsItemStack

// There is no cause for the end of an item that nobody can name, so the cause for its unnamed
// beginning stands on the other side of the row instead. Deliberately written as a guess: an end
// nobody explained is a path the capture still misses, and it has to be counted as one.
private val UNNAMED_END = Cause.ITEM_SPAWN to Confidence.INFERRED

/**
 * Who answers for the end of an item. An explosion is whoever set it off; everything else, and an
 * explosion nobody can be named for, is whoever put the item where it ended — the player who threw
 * it, or the one who set off the dispenser that threw it out. The thrower is never lost either way:
 * their name is on the row the item was born with.
 */
internal fun endedBy(cause: Cause, blaster: UUID?, thrower: UUID?, dispensedBy: UUID?): UUID? =
    (if (cause == Cause.ITEM_DESTROY_EXPLOSION) blaster else null) ?: thrower ?: dispensedBy

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

// The game's own arithmetic: health is whole, and what the hit leaves is cut down to a whole number.
internal fun lethal(health: Int, damage: Double) = (health.toFloat() - damage.toFloat()).toInt() <= 0

// An item that ran out of health spills what it held on the spot, before it is removed; any other end
// takes the contents with it.
internal fun spills(removal: EntityRemoveEvent.Cause, health: Int) =
    removal == EntityRemoveEvent.Cause.DEATH && health <= 0

// What a box or a bundle held, each item under the name it is filed under. A container nobody ever
// named was never filed, and there is nothing of it to account for.
internal fun namedContents(stack: NmsItemStack, codec: ItemFormCodec): List<Pair<Nested, EncodedItem>> {
    val owner = NestedItems.ownerOf(stack) ?: return emptyList()
    return NestedItems.contents(stack).map { (index, child) -> Nested(owner, index) to codec.encode(child) }
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
internal fun unspentDrop(pending: TickCoalescer, intent: Intent, qty: Int, creative: (UUID) -> Boolean = { false }) {
    val entity = intent.to as? ItemEntityRef ?: return
    val form = intent.form ?: return
    // Out of the creative menu the item was made by the drop itself, and that is known, not guessed.
    if (intent.actor?.let(creative) == true) {
        pending.add(Void, entity, Cause.CREATIVE_SET, ItemKey(form, null), qty, intent.actor)
        return
    }
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
    private val attribution: Attribution? = null,
    private val entities: EntityOrigins = EntityOrigins(),
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
        val spot = spotOf(entity.location)
        nameBox(entity, spot)
        val encoded = codec.encodeOrNull(entity.itemStack) ?: return
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

    // The same ladder an explosion climbs for the blocks it takes, short of the journal: one explosion
    // ends a pile of items, and a seek per item has no business on the region thread. The damage the
    // item died of is still on it when it is removed.
    private fun blaster(item: Item): UUID? {
        val source = (item.lastDamageCause as? EntityDamageByEntityEvent)?.damager ?: return null
        litBy(source)?.let { return it.uniqueId }
        if (source.type == EntityType.TNT) {
            val at = positionOf(source.location.block)
            attribution?.placerAt(at, BlockDestructionListener.TNT)?.let { return it.actor }
        }
        return entities.summonerOf(firedBy(source).uniqueId).culprit()
    }

    // A box that fell out of a block something other than a hand broke is given the name its contents
    // were packed under before its form is read, or it would not match the form its drop was expected
    // under and its contents would belong to no item at all. A name it already carries is overwritten:
    // it is the one from its last life, and the contents were packed under the position's.
    private fun nameBox(entity: Item, spot: Spot) {
        val stack = CraftItemStack.asNMSCopy(entity.itemStack)
        if (!NestedItems.isShulkerBox(stack)) return
        val owner = origins.ownerFor(stack, spot) ?: return
        if (NestedItems.ownerOf(stack) == owner) return
        NestedItems.mark(stack, owner)
        entity.itemStack = CraftItemStack.asBukkitCopy(stack)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemove(event: EntityRemoveEvent) {
        val item = event.entity as? Item ?: return
        // The server fires this before it checks whether the entity is already gone, so one removal
        // can arrive twice. The duplicate is the arrival that finds the entity already dead.
        if (item.isDead) return
        val (cause, confidence) = itemEnd(event.cause, item.lastDamageCause?.cause, item.health) ?: return
        val encoded = codec.encodeOrNull(item.itemStack) ?: return
        // Whoever threw it is who put it where it ended: into the lava, onto the cactus, over the edge.
        // The server keeps that on the entity, so it is read rather than worked out.
        val by = endedBy(
            cause,
            blaster = if (cause == Cause.ITEM_DESTROY_EXPLOSION) blaster(item) else null,
            thrower = item.thrower,
            dispensedBy = entities.summonerOf(item.uniqueId).culprit(),
        )
        pending.add(ItemEntityRef(item.uniqueId), Void, cause, encoded.key, encoded.count, by, confidence)
        // What spilled has already been claimed by its own spawn; everything else goes down with the box.
        if (spills(event.cause, item.health)) return
        for ((inside, child) in namedContents(CraftItemStack.asNMSCopy(item.itemStack), codec)) {
            pending.add(inside, Void, cause, child.key, child.count, by, confidence)
        }
    }

    /**
     * A box or a bundle about to be destroyed spills what it holds as new items, and the game does it
     * before the removal event, inside the hit that kills it. This is the last event before that, so
     * the contents are expected here, as coming out of the box's own name. They were not destroyed:
     * the box broke and they fell out, which is what a container breaking already says.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val item = event.entity as? Item ?: return
        if (!lethal(item.health, event.finalDamage)) return
        val contents = namedContents(CraftItemStack.asNMSCopy(item.itemStack), codec)
        if (contents.isEmpty()) return
        val spot = spotOf(item.location)
        for ((inside, child) in contents) {
            origins.expect(inside, Cause.CONTAINER_BREAK_DROP, child.key, spot, child.count, item.thrower)
        }
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
        val slot = equipmentSlotOf(picker, stack)
        // Whatever it does with the item later — drops it, dies holding it, trades it — has to come
        // out of this slot, so the mob remembers what went in.
        bookHeld(picker, slot, encoded.form)
        pending.add(
            ItemEntityRef(item.uniqueId),
            EntitySlot(picker.uniqueId, slot),
            Cause.ITEM_PICKUP_BY_MOB,
            encoded.key,
            encoded.count - event.remaining,
        )
    }
}
