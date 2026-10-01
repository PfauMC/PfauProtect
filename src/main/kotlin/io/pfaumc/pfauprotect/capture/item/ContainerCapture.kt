package io.pfaumc.pfauprotect.capture.item
import io.canvasmc.canvas.event.PlayerPostRespawnAsyncEvent
import io.pfaumc.pfauprotect.model.Cause
import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import io.pfaumc.pfauprotect.storage.EncodedItem
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.storage.FormKey
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.MenuSlot
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.storage.PlacedForms
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerEnder
import io.pfaumc.pfauprotect.model.PlayerEquip
import io.pfaumc.pfauprotect.model.PlayerHolder
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.storage.Registries
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.capture.block.spotOf
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.block.entity.BlockEntity
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.craftbukkit.entity.CraftLivingEntity
import org.bukkit.craftbukkit.inventory.CraftInventory
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerEditBookEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.world.LootGenerateEvent
import org.bukkit.Keyed
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.CartographyInventory
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.GrindstoneInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.LoomInventory
import org.bukkit.inventory.MerchantInventory
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.SmithingInventory
import org.bukkit.inventory.SmithingTrimRecipe
import org.bukkit.inventory.StonecutterInventory
import org.bukkit.plugin.Plugin
import org.bukkit.block.Container as ContainerBlock
import org.bukkit.inventory.ItemStack as BukkitItemStack

// net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND: one past the four armour slots.
private const val OFFHAND_SLOT = 40

data class Stack(val key: ItemKey, val count: Int)

// What one pass saw. Which container items were in view is part of that and not a detail: a row filed
// under a container is only comparable against a pass that had the same container in front of it.
class Snapshot(
    val stacks: Map<Holder, Stack>,
    val containers: Set<UUID> = emptySet(),
    // Container items this very snapshot gave a name to. The name is part of the form, so on its own
    // it would read as one item going and another arriving in the same slot.
    val named: Map<Holder, Naming> = emptyMap(),
) {
    fun sees(nested: Nested) = nested.ownerId in containers
}

class Naming(val unnamed: ItemKey, val owner: UUID)

// The pair a pass compares when this snapshot named something. The named slot is compared under the
// form it had before the name, so it moves only if it really moved; and the container is taken to have
// been in view before, empty, so what was put into it in the same pass has somewhere to go.
internal fun comparable(before: Snapshot, after: Snapshot): Pair<Snapshot, Snapshot> {
    if (after.named.isEmpty()) return before to after
    val stacks = LinkedHashMap(after.stacks)
    val owners = HashSet<UUID>()
    for ((holder, naming) in after.named) {
        val stack = stacks[holder] ?: continue
        stacks[holder] = Stack(naming.unnamed, stack.count)
        if (before.stacks[holder]?.key == naming.unnamed) owners += naming.owner
    }
    return Snapshot(before.stacks, before.containers + owners) to Snapshot(stacks, after.containers)
}

// Naming changes the item where it lies, so it is written as that: the unnamed form out of the slot and
// the named one into it, as one mutation.
internal fun namingRows(after: Snapshot, timestamp: Long): List<List<Transfer>> =
    after.named.mapNotNull { (holder, naming) ->
        val stack = after.stacks[holder] ?: return@mapNotNull null
        fun row(from: Holder, to: Holder, key: ItemKey) = Transfer(
            cause = Cause.CONTAINER_NAMED,
            from = from,
            to = to,
            form = key.form,
            damage = key.damage,
            qty = stack.count,
            timestamp = timestamp,
            kind = Kind.MUTATE,
        )
        listOf(row(holder, Void, naming.unnamed), row(Void, holder, stack.key))
    }

data class Edge(
    val from: Holder,
    val to: Holder,
    val key: ItemKey,
    val qty: Int,
    val confidence: Confidence,
)

private class Delta(val holder: Holder, val form: FormKey, val damage: Int?, var count: Int) {
    var claimed = 0
    val pairable get() = count - claimed
}

private class Held(val holder: Holder, val key: ItemKey, val vanishing: Boolean, var left: Int)

object Netting {
    // Loss meets gain by form, with the wear ignored: durability is not identity, so a tool losing a
    // point of it between two passes is not a movement at all and cancels itself out where it lies.
    // Once the pass runs after every block broken rather than only while a window is open, the other
    // reading would fill the ledger with a pickaxe vanishing and a pickaxe appearing on every swing.
    // The row carries the wear of the side that ended up holding the item.
    fun diff(
        before: Snapshot,
        after: Snapshot,
        intents: List<Intent> = emptyList(),
        player: UUID? = null,
        // Slots whose losses and gains must not be married to each other. Pairing is by form and the
        // form carries no wear, so a station that hands back the same kind of item — a grindstone
        // merging two worn tools, a cartography table copying a map — looks from here like the very
        // item moving out of the station, and the transformation collapses into an ordinary move plus
        // one unexplained disappearance.
        unpaired: Set<Holder> = emptySet(),
    ): List<Edge> {
        val losses = ArrayList<Delta>()
        val gains = ArrayList<Delta>()
        val holders = LinkedHashSet(before.stacks.keys).apply { addAll(after.stacks.keys) }
        for (holder in holders) {
            // A row filed under a container item can only have moved while the container itself was
            // in view. Place the box, drop it or die holding it and its rows leave the snapshot inside
            // it without anything having moved, so they are carried across rather than netted: the
            // other reading writes the contents off at every placement and mints them back on pickup.
            if (holder is Nested && !(before.sees(holder) && after.sees(holder))) continue
            // split, shrink and consume mutate a stack in place and the game leaves the emptied object
            // in the slot, so a counter at or below zero is the only reliable sign of an empty slot.
            val was = before.stacks[holder]?.takeIf { it.count > 0 }
            val now = after.stacks[holder]?.takeIf { it.count > 0 }
            when {
                was == null -> if (now != null) gains += delta(holder, now)
                now == null -> losses += delta(holder, was)
                was.key.form.contentEquals(now.key.form) -> {
                    val delta = now.count - was.count
                    if (delta > 0) gains += delta(holder, now, delta)
                    if (delta < 0) losses += delta(holder, now, -delta)
                }

                else -> {
                    losses += delta(holder, was)
                    gains += delta(holder, now)
                }
            }
        }

        claim(losses, intents, player, loss = true)
        claim(gains, intents, player, loss = false)

        val waiting = HashMap<FormKey, ArrayDeque<Delta>>()
        for (gain in gains) {
            if (gain.pairable > 0 && gain.holder !in unpaired) waiting.getOrPut(gain.form) { ArrayDeque() }.addLast(gain)
        }

        val edges = ArrayList<Edge>()
        for (loss in losses) {
            val matching = if (loss.holder in unpaired) null else waiting[loss.form]
            while (loss.pairable > 0 && matching != null && matching.isNotEmpty()) {
                val gain = matching.first()
                val qty = minOf(loss.pairable, gain.pairable)
                edges += Edge(loss.holder, gain.holder, ItemKey(loss.form.form, gain.damage), qty, Confidence.FACT)
                loss.count -= qty
                gain.count -= qty
                if (gain.pairable == 0) matching.removeFirst()
            }
            if (loss.count > 0) edges += Edge(loss.holder, Void, key(loss), loss.count, Confidence.INFERRED)
        }
        for (gain in gains) {
            if (gain.count > 0) edges += Edge(Void, gain.holder, key(gain), gain.count, Confidence.INFERRED)
        }
        return edges
    }

    // An intent that names a counterparty is claiming part of what the pass found, and it has to claim
    // it before anything is paired: otherwise a loss and an unrelated gain of the same form net into a
    // movement that never happened and both real movements lose their row. What the intent may claim
    // is bounded by what the diff found — the event saw what it meant to do, the pass sees what was
    // applied, and on numbers the pass wins.
    //
    // One thing this cannot recover, and must not try to: when both movements land in the same slot —
    // place a cobblestone out of the held stack and pick one straight back up into it — the slot reads
    // the same before and after and the snapshot holds no evidence that anything happened. Writing it
    // from the intent's own word instead trades a claim that holds for one that does not: an intent
    // names only its far end, so it cannot say which slot, and in creative a placement consumes
    // nothing at all, so the debit would be invented and the player would stop balancing.
    private fun claim(deltas: List<Delta>, intents: List<Intent>, player: UUID?, loss: Boolean) {
        for (intent in intents) {
            if (!intent.aims(loss)) continue
            var left = intent.qty ?: Int.MAX_VALUE
            for (delta in deltas) {
                if (left <= 0) break
                if (delta.pairable <= 0 || !intent.explains(delta.holder, delta.form.form, player)) continue
                val qty = minOf(delta.pairable, left)
                delta.claimed += qty
                left -= qty
            }
        }
    }

    private fun delta(holder: Holder, stack: Stack, count: Int = stack.count) =
        Delta(holder, FormKey(stack.key.form), stack.key.damage, count)

    private fun key(delta: Delta) = ItemKey(delta.form.form, delta.damage)
}

// Both halves of a double chest keep their own position and their own slot numbering, because a row
// has to name the block its items can be put back into.
internal fun containerHolders(inventory: Inventory): ((Int) -> Holder)? {
    if (inventory is DoubleChestInventory) {
        val left = containerAt(inventory.leftSide)
        val right = containerAt(inventory.rightSide)
        val leftSize = inventory.leftSide.size
        if (left != null && right != null) {
            return { slot ->
                if (slot < leftSize) left.copy(slot = slot) else right.copy(slot = slot - leftSize)
            }
        }
    }
    // A block entity knows where it stands. Asking the inventory for its holder reads the block state
    // out of the world, which only the thread ticking that region may do — and while the server shuts
    // down no thread does, so the last pass over an open furnace failed and took the rest of the
    // shutdown with it.
    val entity = (inventory as? CraftInventory)?.inventory as? BlockEntity
    val level = entity?.level
    if (entity != null && level != null) {
        val at = entity.blockPos
        val container = Container(level.world.uid, at.x, at.y, at.z, 0)
        return { slot -> container.copy(slot = slot) }
    }
    // Only the position is wanted, and a snapshot holder would copy the whole block state.
    val holder = inventory.getHolder(false)
    val block = (holder as? BlockInventoryHolder)?.block
    if (block != null) {
        val container = containerAt(block, 0)
        return { slot -> container.copy(slot = slot) }
    }
    // A minecart rides the rails, so only its uuid addresses it; its position is where something
    // happened, not what it is.
    val cart = holder as? Entity
    if (cart is AbstractHorse) {
        val uuid = cart.uniqueId
        return { slot -> EntitySlot(uuid, horseSlot(slot)) }
    }
    if (cart != null) {
        val uuid = cart.uniqueId
        return { slot -> EntitySlot(uuid, slot) }
    }
    return null
}

private fun containerAt(inventory: Inventory): Container? {
    val location = inventory.location ?: return null
    val world = location.world ?: return null
    return Container(world.uid, location.blockX, location.blockY, location.blockZ, 0)
}

internal fun containerAt(block: Block, slot: Int) =
    Container(block.world.uid, block.x, block.y, block.z, slot)

internal fun positionOf(block: Block) = WorldBlock(block.world.uid, block.x, block.y, block.z)

// The slot is not on any of the events that need it, so it is worked out from the item the way the
// game works it out. A mob with no room in the slot the item asks for puts it in the main hand
// instead, and it decides that before the event fires, so the slot named here can disagree with the
// one used.
internal fun equipmentSlotOf(entity: LivingEntity, item: BukkitItemStack): Int =
    (entity as CraftLivingEntity).handle.getEquipmentSlotForItem(CraftItemStack.asNMSCopy(item)).ordinal

internal fun playerHolders(uuid: UUID, inventory: PlayerInventory): (Int) -> Holder {
    val storageSize = inventory.storageContents.size
    return { slot -> if (slot < storageSize) PlayerInv(uuid, slot) else PlayerEquip(uuid, slot) }
}

// A transformation reaches the pass as ends that pair with nothing, and a `Void` on one side is what
// marks them: the ingredients go nowhere and the product comes from nowhere. Gathered under one
// transaction they can be read back from any one of them; left apart they are unrelated losses and an
// unexplained gain, which is the shape a laundered stack has too.
//
// Anything else the same pass turned up happened for its own reasons and keeps them.
// What taking the result out of a station turns one thing into another for. A station whose recipe
// only ever rearranges whole items — a workbench, and everything folded into it: dyeing, a signed
// book, a copied banner, a scaled map — consumes and produces rather than mutates, so its two sides
// carry the ordinary form. The rest hand back the very item that went in, changed.
// The special recipes that copy or recolour rather than make, named by the game's own recipe key.
// Dyeing and copying a banner are a recipe per item or per colour: `leather_chestplate_dyed`,
// `red_shulker_box`, `white_banner_duplicate`.
private val SPECIAL_CRAFTS = mapOf(
    "book_cloning" to Cause.BOOK_COPY,
    "map_cloning" to Cause.MAP_CLONE,
    "map_extending" to Cause.MAP_SCALE_LOCK,
)

private fun specialCraft(key: String): Cause? = when {
    key.endsWith("_dyed") || key.endsWith("_shulker_box") -> Cause.DYE_ITEM
    key.endsWith("_banner_duplicate") -> Cause.BANNER_DUPLICATE
    else -> SPECIAL_CRAFTS[key]
}

internal fun shiftOf(top: Inventory): Shift? = when (top) {
    // Read while the click is delivered, like the smithing recipe below: the match is gone after it.
    is CraftingInventory -> (top.recipe as? Keyed)?.key?.takeIf { it.namespace == "minecraft" }?.let { specialCraft(it.key) }
        ?.let { Shift(it, it, Kind.TRANSFER, Cause.CRAFT_REMAINDER) }
        ?: Shift(Cause.CRAFT_CONSUME, Cause.CRAFT_RESULT, Kind.TRANSFER, Cause.CRAFT_REMAINDER)
    is AnvilInventory -> Shift(Cause.ANVIL_COMBINE, Cause.ANVIL_COMBINE, Kind.MUTATE)
    is GrindstoneInventory -> Shift(Cause.GRINDSTONE, Cause.GRINDSTONE, Kind.MUTATE)
    // The station itself names which of the two smithing recipes matched, and it only names it while
    // the click is still being delivered: taking the result reruns the match against the emptied
    // inputs, finds nothing and forgets the recipe. So this may be read here and never from the
    // deferred pass.
    // ponytail: a plugin may register a SmithingRecipe that is neither, and it books as a transform;
    // splitting that out needs a cause the dictionary does not have yet.
    is SmithingInventory -> {
        val cause = if (top.recipe is SmithingTrimRecipe) Cause.SMITHING_TRIM else Cause.SMITHING_TRANSFORM
        Shift(cause, cause, Kind.MUTATE)
    }
    is StonecutterInventory -> Shift(Cause.STONECUTTER, Cause.STONECUTTER, Kind.MUTATE)
    is LoomInventory -> Shift(Cause.LOOM, Cause.LOOM, Kind.MUTATE)
    is CartographyInventory -> Shift(Cause.CARTOGRAPHY, Cause.CARTOGRAPHY, Kind.MUTATE)
    // The payment is destroyed and the goods are made, both in the trader's window.
    is MerchantInventory -> Shift(Cause.TRADE_PAYMENT, Cause.TRADE_RESULT, Kind.TRANSFER)
    else -> null
}

// A block or an entity that is not the player changes its own slots: a furnace smelts, a stand brews,
// a hopper fills the chest that is open, another player takes from it. Each of those is written by its
// own listener, and the pass that saw the window change would write it a second time, as a movement out
// of nowhere, and put the container's balance out by exactly that much. Only a transformation is the
// player's own business on such a slot, and it is the one case that names a shift.
internal fun withoutForeignEnds(moves: List<Move>, shift: Shift?): List<Move> {
    if (shift != null) return moves
    return moves.filterNot { move ->
        (move.from == Void && isForeign(move.to)) || (move.to == Void && isForeign(move.from))
    }
}

private fun isForeign(holder: Holder) = holder is Container || holder is EntitySlot

internal fun transactions(moves: List<Move>, shift: Shift?): List<List<Move>> {
    if (shift == null) return moves.map { listOf(it) }
    val transformed = ArrayList<Move>()
    val rest = ArrayList<List<Move>>()
    for (move in moves) {
        when {
            move.to == Void -> transformed += move.copy(cause = shift.consumeOf(move.key.form) ?: shift.consume)
            // The product is taken to the player; what the recipe leaves behind stays in the grid.
            // A remainder that finds its grid slot still occupied — a stack of honey bottles —
            // is pushed into the inventory by the game and books as the result.
            move.from == Void -> transformed += move.copy(
                cause = if (move.to is PlayerHolder) shift.result else shift.remainder,
            )
            else -> rest += listOf(move)
        }
    }
    // The pass had to guess at these ends because neither of them pairs with anything — that is what
    // facing the Void means. Both sides together are not a guess: the event named the station and both
    // of its reasons, which is as much as any click is ever witnessed by. Left INFERRED they would be
    // counted as movements nothing could explain, and every craft would enlarge the very number that
    // measures what the capture still cannot see.
    //
    // One side alone stays a guess. A creative craft that consumes nothing has nothing to corroborate.
    if (transformed.size > 1) {
        rest += transformed.map { it.copy(confidence = Confidence.FACT) }
    } else if (transformed.isNotEmpty()) {
        rest += transformed
    }
    return rest
}

// Long enough for every row about the moment of a reading to have left the writer's queue.
private const val RECONCILE_DELAY_SECONDS = 2L

internal class LoadDifference(val holder: Holder, val form: ByteArray, val qty: Int, val gained: Boolean)

/**
 * Where a difference between a player's slots and the ledger shows. Counted by form and not by slot,
 * so an item moved between two slots unseen stands against itself and writes nothing; what is left is
 * put on the slots that hold more of the form than they were booked, or were booked more than they
 * hold.
 */
internal fun loadDifferences(
    live: Map<Holder, Map<FormKey, Int>>,
    booked: Map<Holder, Map<FormKey, Int>>,
): List<LoadDifference> {
    val forms = (live.values.flatMap { it.keys } + booked.values.flatMap { it.keys }).toSet()
    val differences = ArrayList<LoadDifference>()
    for (form in forms) {
        val excess = (live.keys + booked.keys).associateWith { holder ->
            (live[holder]?.get(form) ?: 0) - (booked[holder]?.get(form) ?: 0)
        }
        val total = excess.values.sum()
        if (total == 0) continue
        val gained = total > 0
        var left = abs(total)
        for ((holder, own) in excess.entries.sortedByDescending { if (gained) it.value else -it.value }) {
            val take = minOf(left, if (gained) own else -own)
            if (take <= 0) break
            differences += LoadDifference(holder, form.form, take, gained)
            left -= take
        }
    }
    return differences
}

internal fun transferOf(move: Move, timestamp: Long, kind: Kind = Kind.TRANSFER): Transfer = Transfer(
    cause = move.cause,
    from = move.from,
    to = move.to,
    form = move.key.form,
    damage = move.key.damage,
    qty = move.qty,
    timestamp = timestamp,
    // A creative copy leaves the stack it was copied from where it was: the copy is a second carrier,
    // not a movement, and SPEC-v1 gives it a kind of its own.
    kind = if (move.cause == Cause.CREATIVE_CLONE) Kind.CLONE else kind,
    confidence = move.confidence,
    actor = move.actor,
)

// Nothing named this end. The cause says so rather than borrowing the name of a click that never
// happened: a gain nobody explained is an item that turned up, a loss one that went. A transformation
// renames these ends afterwards; everything left under these two is what the capture could not see.
internal fun unexplainedCause(edge: Edge): Cause =
    if (edge.from == Void) Cause.DIRECT_NEW_ITEM else Cause.ITEM_VANISHED

internal fun causeOf(edge: Edge): Cause = when {
    edge.to is Nested -> Cause.BUNDLE_INSERT
    edge.from is Nested -> Cause.BUNDLE_EXTRACT
    edge.to is Container || edge.to is PlayerEnder || edge.to is EntitySlot -> Cause.CONTAINER_ADD
    edge.from is Container || edge.from is PlayerEnder || edge.from is EntitySlot -> Cause.CONTAINER_REMOVE
    edge.from is PlayerCursor -> Cause.CURSOR_PLACE
    edge.to is PlayerCursor -> Cause.CURSOR_TAKE
    else -> Cause.QUICK_MOVE
}

// The slot a station computes from its inputs rather than holds an item in. It is filled the moment
// the inputs match a recipe and emptied when they stop matching, without anything being moved, so
// recording it mints an item out of nothing on every match and books a loss for one that was only
// ever a preview. Taking the result is a real gain, and it shows up in the slot it is taken into.
//
// A furnace, a brewing stand and a crafter are deliberately not here: their output slots hold a real
// item that stands there until somebody takes it out.
internal fun previewSlot(top: Inventory): Int? = when (top) {
    // Ingredients first, result last, for every station built on a result inventory.
    is AnvilInventory, is GrindstoneInventory, is SmithingInventory,
    is StonecutterInventory, is LoomInventory, is CartographyInventory, is MerchantInventory,
    -> top.size - 1
    // A crafting inventory is the other way round: the result is addressed ahead of the grid.
    is CraftingInventory -> 0
    else -> null
}

class ContainerCaptureListener(
    private val plugin: Plugin,
    private val sink: (List<Transfer>) -> Unit,
    private val codec: ItemFormCodec,
    private val registries: Registries,
    private val origins: SpawnOrigins,
    private val placed: PlacedForms,
    // What the ledger books to every slot under these holders, from rows up to a moment.
    private val booked: (List<Holder>, Long) -> Map<Holder, Map<FormKey, Int>> = { _, _ -> emptyMap() },
    // What an intent asked for and the pass never found. An event that silenced a funnel of its own on
    // the promise that the pass would write the row has to hear about it when the pass could not.
    private val unspent: (Intent, Int) -> Unit = { _, _ -> },
) : Listener {
    private class Baseline(val view: InventoryView, val seen: Snapshot)

    private val baselines = ConcurrentHashMap<UUID, Baseline>()
    private val intents = PlayerIntents()

    /** The only way an event may speak: it says why, the pass that follows says how much. */
    fun intend(player: Player, intent: Intent) {
        intents.add(player.uniqueId, intent)
        scheduleRecompute(player)
    }

    /**
     * The join is the first moment the server holds a player's slots again, and whatever changed them
     * while the player was away — an editor, a plugin, a restored backup — changed them with nothing
     * watching. They are compared with the ledger here, off the region thread, and every difference is
     * written as a guess before the first pass takes the join as its starting point.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        rebaseline(player)
        val live = ownSlots(player)
        val at = System.currentTimeMillis()
        if (!plugin.isEnabled) return
        plugin.server.asyncScheduler.runNow(plugin) {
            val id = player.uniqueId
            val own = listOf(PlayerInv(id, 0), PlayerEquip(id, 0), PlayerCursor(id), PlayerEnder(id, 0))
            written(loadDifferences(live, booked(own, at)), at)
        }
    }

    private fun written(differences: List<LoadDifference>, at: Long) {
        val rows = differences.map { difference ->
            Transfer(
                cause = Cause.INVENTORY_LOAD,
                from = if (difference.gained) Void else difference.holder,
                to = if (difference.gained) difference.holder else Void,
                form = difference.form,
                damage = null,
                qty = difference.qty,
                timestamp = at,
                confidence = Confidence.INFERRED,
            )
        }
        if (rows.isNotEmpty()) sink(rows)
    }

    /**
     * A container is compared with the ledger once a player is done with it, after the pass that
     * wrote what the player did there. The one check that sees what reached into it with nobody
     * watching — a plugin, an editor, a mob the capture does not know — and what it finds is written,
     * as a guess, the way a player's own slots are at the join. The ledger is read a moment later, so
     * rows about the moment of the reading have had time to land.
     */
    private fun reconcileContainer(top: Inventory) {
        val holders = containerHolders(top) ?: return
        val live = HashMap<Holder, HashMap<FormKey, Int>>()
        val positions = LinkedHashSet<Holder>()
        for (slot in 0 until top.size) {
            val holder = holders(slot) as? Container ?: return
            positions += holder.copy(slot = 0)
            val encoded = codec.encodeOrNull(top.getItem(slot)) ?: continue
            live.getOrPut(holder) { HashMap() }.merge(FormKey(encoded.form), encoded.count, Int::plus)
        }
        val at = System.currentTimeMillis()
        if (!plugin.isEnabled) return
        plugin.server.asyncScheduler.runDelayed(plugin, {
            written(loadDifferences(live, booked(positions.toList(), at)), at)
        }, RECONCILE_DELAY_SECONDS, TimeUnit.SECONDS)
    }

    // Every slot the ledger files under the player's own name, ender chest included, by form.
    private fun ownSlots(player: Player): Map<Holder, Map<FormKey, Int>> {
        val slots = HashMap<Holder, HashMap<FormKey, Int>>()
        fun count(holder: Holder, stack: BukkitItemStack?) {
            val encoded = codec.encodeOrNull(stack) ?: return
            slots.getOrPut(holder) { HashMap() }.merge(FormKey(encoded.form), encoded.count, Int::plus)
        }
        val inventory = player.inventory
        val holders = playerHolders(player.uniqueId, inventory)
        for (slot in 0 until inventory.size) count(holders(slot), inventory.getItem(slot))
        count(PlayerCursor(player.uniqueId), player.itemOnCursor)
        val ender = player.enderChest
        for (slot in 0 until ender.size) count(PlayerEnder(player.uniqueId, slot), ender.getItem(slot))
        return slots
    }

    // Scheduled work is dropped when the player's scheduler retires, which happens in the same block
    // that fires this event, so the last interaction has to be diffed here and now.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        recompute(event.player)
        baselines.remove(event.player.uniqueId)
        intents.forget(event.player.uniqueId)
    }

    // At shutdown, one player's pass that fails must not cost everybody else theirs, nor the rest of
    // the shutdown that drains what the mechanisms were still holding.
    fun recomputeAll() {
        for (player in Bukkit.getOnlinePlayers()) {
            try {
                recompute(player)
            } catch (failure: Exception) {
                plugin.logger.log(Level.SEVERE, "the last pass for ${player.name} failed; its movements are lost", failure)
            }
        }
    }

    // A new line of reference discards everything the old one was still holding, so whatever the last
    // event changed has to be counted against the old view first or it is lost together with the
    // intent that explained it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        recompute(player)
        baselines[player.uniqueId] = Baseline(event.view, snapshot(player, event.view))
    }

    // Structure loot has no earlier existence to move from, so without this every naturally generated
    // chest hands out items that were never received and can never balance.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLootGenerate(event: LootGenerateEvent) {
        if (!plugin.isEnabled) return
        val block = (event.inventoryHolder as? BlockInventoryHolder)?.block ?: return
        val generated = event.loot.mapNotNull { encode(it) }
        if (generated.isEmpty()) return
        // Vault and trial rewards roll per player on purpose, so who triggered the table is what tells
        // a legitimate second helping apart from an item appearing twice.
        val actor = (event.entity as? Player)?.uniqueId
        // The table shuffles its result into slots only after this event returns, so which slot each
        // stack landed in is knowable a tick later and from the container itself, not from the list.
        Bukkit.getRegionScheduler().run(plugin, block.location) { recordLoot(block, generated, actor) }
    }

    private fun recordLoot(block: Block, generated: List<Stack>, actor: UUID?) {
        val inventory = (block.state as? ContainerBlock)?.inventory ?: return
        val pending = HashMap<ItemKey, Int>()
        for (stack in generated) pending.merge(stack.key, stack.count, Int::plus)
        val timestamp = System.currentTimeMillis()
        for (slot in 0 until inventory.size) {
            val found = encode(inventory.getItem(slot)) ?: continue
            val left = pending[found.key] ?: continue
            val qty = minOf(left, found.count)
            if (qty <= 0) continue
            pending[found.key] = left - qty
            sink(listOf(
                Transfer(
                    cause = Cause.LOOT_GENERATE,
                    from = Void,
                    to = containerAt(block, slot),
                    form = found.key.form,
                    damage = found.key.damage,
                    qty = qty,
                    timestamp = timestamp,
                    actor = actor,
                )
            ))
        }
    }

    // A placed block still holds what it was made of, so the position takes the item over rather than
    // the item ending up written off. A door, a bed or a double plant occupies two positions but
    // arrives here as one BlockMultiPlaceEvent and costs the player one item, so the quantity is one
    // and the position is the one the event names. The stack in hand is a live mirror of the slot and
    // is already rolled back to its pre-consumption count, so it has to be read here and now.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val inHand = event.itemInHand
        // A tool wears instead of being used up and stays in the hand afterwards, so a block put down
        // with one — fire struck from a flint and steel — was paid for with nothing. Booked as what
        // the position took over, the tool would be handed back whole when the fire goes out, writing
        // off an item that is still in somebody's inventory.
        if (inHand.type.maxDurability > 0) return
        // Powder snow out of its bucket is placed like a block and leaves the empty bucket, and the
        // snow holds no item of its own.
        if (inHand.type == Material.POWDER_SNOW_BUCKET) {
            intend(event.player, mutation(Cause.BUCKET_EMPTY))
            return
        }
        // Wax, an eye of ender, a fire charge: the server raises a placement for what they do to a
        // block, but they are not what the block is made of.
        if (!inHand.type.isBlock) return
        val form = codec.encodeOrNull(inHand)?.form ?: return
        val block = event.block
        placed.setFormAt(block.world.uid, block.x, block.y, block.z, form)
        intend(
            event.player,
            Intent(
                cause = Cause.BLOCK_PLACE,
                to = positionOf(block),
                form = form,
                qty = 1,
                holder = handSlot(event.player, event.hand),
            )
        )
    }

    // The offhand is one slot past the armour in the player's own numbering, and the snapshot walks
    // those same numbers, so a hand is nameable as a holder the moment the event says which one.
    internal fun handSlot(player: Player, hand: EquipmentSlot?): Holder? {
        val inventory = player.inventory
        val slot = when (hand) {
            EquipmentSlot.HAND -> inventory.heldItemSlot
            EquipmentSlot.OFF_HAND -> OFFHAND_SLOT
            else -> return null
        }
        return playerHolders(player.uniqueId, inventory)(slot)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        // A furnace smelting into the open window, a hopper filling it or another player at the same
        // chest changes slots under the baseline with no pass of this player's to see it, and taking
        // such an item out would read as the slot unchanged and the cursor filled from nowhere. The
        // click arrives before it is applied, so a pass here counts everything up to it — the foreign
        // part is dropped as the container's own business — and leaves the baseline where the click
        // starts from.
        recompute(player)
        // A craft arrives here and not at a handler of its own: CraftItemEvent declares no handler list
        // and is dispatched into this one, so a second listener would be a second callback for one
        // click and would leave the reason twice.
        // The creative inventory sets slots to whatever the client asks for, conjuring and deleting as
        // it goes. What it made and what it threw away is named as that rather than left unexplained.
        if (event is InventoryCreativeEvent) {
            intend(player, Intent(Cause.CREATIVE_SET, from = Void))
            intend(player, Intent(Cause.CREATIVE_SET, to = Void))
        }
        // A middle click in creative copies a stack out of any window onto the cursor, a chest's
        // included, and that window is no creative screen.
        if (event.action == InventoryAction.CLONE_STACK) intend(player, Intent(Cause.CREATIVE_CLONE, from = Void))
        val top = event.view.topInventory
        val shift = if (event.rawSlot == previewSlot(top)) shiftOf(top) else null
        if (shift != null) intend(player, Intent(shift.consume, shift = shift))
        val cause = clickCause(event)
        if (cause == null) scheduleRecompute(player) else intend(player, Intent(cause))
    }

    // The one transformation that is not a click on a result slot, and the one with a handler list of
    // its own, so it needs a handler of its own. The table hands back the same item with the
    // enchantment on it, which the pass sees as one form leaving and another arriving in that slot.
    //
    // The lapis is spent applying the enchantment just as much as the item is, and it is told apart by
    // its form on the consumed side.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        val lapis = lapisForm
        val shift = Shift(Cause.ENCHANT_APPLY, Cause.ENCHANT_APPLY, Kind.MUTATE) { form ->
            if (lapis != null && form.contentEquals(lapis)) Cause.ENCHANT_LAPIS_CONSUME else null
        }
        intend(event.enchanter, Intent(shift.consume, shift = shift))
    }

    private val lapisForm by lazy { codec.encodeOrNull(BukkitItemStack(Material.LAPIS_LAZULI))?.form }

    // The recipe book lays the ingredients into the grid by itself, with no click behind it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRecipeBook(event: PlayerRecipeBookClickEvent) {
        recompute(event.player)
        intend(event.player, Intent(Cause.RECIPE_BOOK_FILL))
    }

    // Using an empty map writes a filled one, in the hand that held it or beside it when the empty map
    // was one of a stack. It is driven by a use rather than a click, like signing a book, and it is as
    // much one item turning into another. A right click in the air is raised already denied, so the
    // item's own verdict is what says whether the use went ahead.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onUseMap(event: PlayerInteractEvent) {
        if (!event.action.isRightClick || event.item?.type != Material.MAP) return
        if (event.useItemInHand() == Event.Result.DENY) return
        val shift = Shift(Cause.MAP_FILL, Cause.MAP_FILL, Kind.MUTATE)
        intend(event.player, Intent(shift.consume, shift = shift))
    }

    // Signing turns a writable book into a written one in the slot it is held in, driven by a packet
    // rather than a click, so nothing would schedule a pass for it and the form change would be picked
    // up by whatever unrelated pass ran next and written as two strangers.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEditBook(event: PlayerEditBookEvent) {
        // A plain page edit changes the form too: the pages are part of what a book and quill is.
        val cause = if (event.isSigning) Cause.BOOK_SIGN else Cause.BOOK_EDIT
        val shift = Shift(cause, cause, Kind.MUTATE)
        intend(event.player, Intent(shift.consume, shift = shift))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        // For the same reason as a click: the drag starts from whatever the window holds now. The server
        // has already put the remainder on the cursor, though, and fills the slots only after this, so
        // the cursor is taken as it was before the drag.
        recompute(player, cursor = event.oldCursor)
        intend(player, Intent(Cause.QUICK_CRAFT_DISTRIBUTE))
    }

    // Every getter here reports the state after the swap, so nothing but the reason is worth taking.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        intend(event.player, Intent(Cause.OFFHAND_SWAP))
    }

    // A drop made out of an open window also fires a click, and both only leave a reason, so the two
    // of them cost one row rather than two.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDropItem(event: PlayerDropItemEvent) {
        val player = event.player
        val drop = event.itemDrop
        val encoded = codec.encodeOrNull(drop.itemStack) ?: return
        // A player who leaves holding something on the cursor has it dropped for them, and that happens
        // after the quit event: the pass is already gone, the intent would be left for nobody and the
        // funnel silenced on a promise nothing can keep, so the item would leave no trace at either
        // end. Whether the pass can still run is exactly whether the player still has a line of
        // reference, so the row is written here instead, naming the cursor it came off.
        if (baselines[player.uniqueId] == null) {
            sink(
                listOf(
                    Transfer(
                        cause = Cause.DROP_ON_DISCONNECT,
                        from = PlayerCursor(player.uniqueId),
                        to = ItemEntityRef(drop.uniqueId),
                        form = encoded.form,
                        damage = encoded.damage,
                        qty = encoded.count,
                        timestamp = System.currentTimeMillis(),
                    )
                )
            )
            // The birth is in the row just written, so the funnel still has to stay quiet about it.
            origins.accounted(drop.uniqueId, encoded.count)
            return
        }
        // Nothing open and the player's own inventory screen report the same type, so a drop out of
        // the survival inventory is indistinguishable from a drop out of the hand and reads as one.
        closingDrop(player, drop.uniqueId, encoded)?.let { row ->
            sink(listOf(row))
            origins.accounted(drop.uniqueId, encoded.count)
            return
        }
        val inMenu = player.openInventory.type != InventoryType.CRAFTING
        intend(
            player,
            Intent(
                cause = if (inMenu) Cause.DROP_FROM_MENU else Cause.DROP_FROM_HAND,
                to = ItemEntityRef(drop.uniqueId),
                form = encoded.form,
                qty = encoded.count,
                // Also what tells a drop the pass could not find apart: one conjured by creative.
                actor = player.uniqueId,
            )
        )
        // The entity is added to the world inside this same call and the spawn funnel writes a birth
        // for anything nobody explained. This one is explained: the pass a tick from now names the
        // slot it left and writes that birth itself, and two of them would read as a duplicated item.
        origins.accounted(drop.uniqueId, encoded.count)
    }

    // The bowl a stew leaves behind is the use remainder rather than the crafting remainder: a bowl
    // of stew has no crafting remainder at all, and reading the wrong one loses the container.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        val eaten = CraftItemStack.asNMSCopy(event.item)
        if (eaten.isEmpty) return
        val player = event.player
        val hand = handSlot(player, event.hand)
        intend(player, Intent(Cause.CONSUME_FOOD, to = Void, form = codec.encode(eaten).form, qty = 1, holder = hand))
        val remainder = eaten.get(DataComponents.USE_REMAINDER)?.convertInto()?.create() ?: return
        val returned = codec.encode(remainder)
        intend(player, Intent(Cause.CONSUME_REMAINDER, from = Void, form = returned.form, qty = returned.count))
    }

    // Fired immediately before the last of the stack is shrunk away, and the stack itself is a live
    // mirror that will read as empty by the time the pass runs.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onItemBreak(event: PlayerItemBreakEvent) {
        // A rod with bait on it wears down into the bare rod rather than into nothing: one item turned
        // into another, in the same slot.
        val type = event.brokenItem.type
        if (type == Material.CARROT_ON_A_STICK || type == Material.WARPED_FUNGUS_ON_A_STICK) {
            val shift = Shift(Cause.TRANSMUTE_ON_BREAK, Cause.TRANSMUTE_ON_BREAK, Kind.MUTATE)
            intend(event.player, Intent(shift.consume, shift = shift))
            return
        }
        val form = codec.encodeOrNull(event.brokenItem)?.form ?: return
        intend(event.player, Intent(Cause.DURABILITY_BREAK, to = Void, form = form, qty = 1))
    }

    // The event is also fired in a cancelled state when there is no totem to spend at all.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onResurrect(event: EntityResurrectEvent) {
        val player = event.entity as? Player ?: return
        val hand = event.hand ?: return
        val form = codec.encodeOrNull(player.inventory.getItem(hand))?.form ?: return
        intend(player, Intent(Cause.TOTEM_CONSUME, to = Void, form = form, qty = 1, holder = handSlot(player, hand)))
    }

    // The inventory is still whole here and the drop entities do not exist yet: they are built after
    // every listener has returned and fire no drop event of their own. So the slot each dropped stack
    // came out of is knowable only now, and only from here — a note per stack carries that origin
    // forward to the spawn that claims it and writes the row.
    //
    // An item under the curse of vanishing is dropped nowhere and announced nowhere; the snapshot
    // taken here is the only place it is ever named, so it is written out on the spot.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val slots = heldAtDeath(player)
        val spot = spotOf(player.location)
        // What the death wrote is on the ledger already, so the pass that sees the emptied slots a
        // tick later has to swallow exactly those slots and no others. That is one note per stack
        // written, naming the slot and the form it was written for: a single total swallows whichever
        // loss the pass reaches first, and where that is the wrong one the slot it was written for is
        // written off a second time. Beyond these notes the death took something nobody named — a drop
        // another plugin removed from the list — and that has to stay visible as an unexplained loss
        // rather than disappear into them. The cursor is dropped by an event of its own after this one
        // and is not in the list here, so it is left for that event to explain.
        val written = ArrayList<Intent>()
        for (dropped in event.drops) {
            val encoded = codec.encodeOrNull(dropped) ?: continue
            var need = encoded.count
            for (slot in slots) {
                if (need <= 0) break
                if (slot.left <= 0 || slot.key != encoded.key) continue
                val qty = minOf(need, slot.left)
                slot.left -= qty
                need -= qty
                origins.expect(slot.holder, Cause.DEATH_DROP, encoded.key, spot, qty)
                written += Intent(
                    cause = Cause.DEATH_DROP,
                    form = encoded.key.form,
                    qty = qty,
                    recorded = true,
                    holder = slot.holder,
                )
            }
        }
        if (!event.keepInventory) {
            val timestamp = System.currentTimeMillis()
            for (slot in slots) {
                if (slot.left <= 0 || !slot.vanishing) continue
                sink(listOf(
                    Transfer(
                        cause = Cause.DEATH_DESTROY_VANISHING,
                        from = slot.holder,
                        to = Void,
                        form = slot.key.form,
                        damage = slot.key.damage,
                        qty = slot.left,
                        timestamp = timestamp,
                    )
                ))
                written += Intent(
                    cause = Cause.DEATH_DESTROY_VANISHING,
                    form = slot.key.form,
                    qty = slot.left,
                    recorded = true,
                    holder = slot.holder,
                )
            }
        }
        for (note in written) intents.add(player.uniqueId, note)
        scheduleRecompute(player)
    }

    private fun heldAtDeath(player: Player): List<Held> {
        val inventory = player.inventory
        val holders = playerHolders(player.uniqueId, inventory)
        val held = ArrayList<Held>(inventory.size)
        for (slot in 0 until inventory.size) {
            val live = CraftItemStack.asNMSCopy(inventory.getItem(slot) ?: continue)
            if (live.isEmpty) continue
            val encoded = codec.encode(live)
            val vanishing = EnchantmentHelper.has(live, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)
            held += Held(holders(slot), encoded.key, vanishing, encoded.count)
        }
        return held
    }

    // PlayerRespawnEvent never arrives on this fork, because the respawn it is fired from throws under
    // region threading. This one is guaranteed to run on the player's own region, and without redrawing
    // the line of reference here the first click after a death charges the player for a whole inventory.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerPostRespawnAsyncEvent) {
        rebaseline(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        if (!plugin.isEnabled) return
        // The game hands back what was left in a crafting grid or a station, and the cursor with it,
        // without a click of its own. A label and not a quantity: it renames what the pass pairs up.
        // A click the game applied in this same tick with no label of its own is renamed
        // too; telling them apart needs the pass to know which snapshot each edge came from.
        intents.add(player.uniqueId, Intent(Cause.MENU_CLOSE_RETURN))
        rememberClosing(player, event.view.topInventory)
        val top = event.view.topInventory
        player.scheduler.run(plugin, {
            closing.remove(player.uniqueId)
            recompute(player)
            rebaseline(player)
            // The container stands next to the player, on the same region.
            if (top.location?.let(Bukkit::isOwnedByCurrentRegion) == true) reconcileContainer(top)
        }, null)
    }

    // What a station, a crafting grid or a beacon still held as its window closed. What does not fit
    // back into the inventory is thrown at the player's feet inside the same call, and the only end
    // that knows which slot it left is this one: the pass after it counts the window as foreign.
    private class Leaving(val holder: Holder, val form: ByteArray)

    private val closing = ConcurrentHashMap<UUID, MutableList<Leaving>>()

    private fun rememberClosing(player: Player, top: Inventory) {
        val holders = topHolders(player, top) ?: return
        val preview = previewSlot(top)
        val left = (0 until top.size).mapNotNullTo(ArrayList()) { slot ->
            val holder = holders(slot)
            if (slot == preview || holder is PlayerHolder) return@mapNotNullTo null
            encode(top.getItem(slot))?.let { Leaving(holder, it.key.form) }
        }
        if (left.isNotEmpty()) closing[player.uniqueId] = left
    }

    // The drop out of a closing window, written from the slot it left, or null where it was not one.
    private fun closingDrop(player: Player, drop: UUID, encoded: EncodedItem): Transfer? {
        val left = closing[player.uniqueId] ?: return null
        val at = left.indexOfFirst { it.form.contentEquals(encoded.form) }
        if (at < 0) return null
        return Transfer(
            cause = Cause.DROP_MENU_CLOSE,
            from = left.removeAt(at).holder,
            to = ItemEntityRef(drop),
            form = encoded.form,
            damage = encoded.damage,
            qty = encoded.count,
            timestamp = System.currentTimeMillis(),
            actor = player.uniqueId,
        )
    }

    // What the click event knows and the diff cannot work out: a swap, a hotbar key, a double-click
    // gathering from everywhere, a piece of armour going on or coming off. Anything it cannot name is
    // left unlabelled on purpose, and `causeOf` reads the reason off the holders as it always has.
    private fun clickCause(event: InventoryClickEvent): Cause? {
        // The offhand key never shows up in the action; it is only ever visible as the click itself.
        if (event.click == ClickType.SWAP_OFFHAND) return Cause.OFFHAND_SWAP
        val action = event.action
        // Equipping is invisible in the action, and only the armour slot itself reports its type: the
        // same piece shift-clicked out of the inventory arrives here as an ordinary container slot.
        if (event.slotType == InventoryType.SlotType.ARMOR) return armorCause(action)
        return when (action) {
            InventoryAction.HOTBAR_SWAP, InventoryAction.HOTBAR_MOVE_AND_READD -> Cause.HOTBAR_SWAP
            InventoryAction.COLLECT_TO_CURSOR -> Cause.COLLECT_ALL
            InventoryAction.SWAP_WITH_CURSOR -> Cause.CURSOR_SWAP
            InventoryAction.DROP_ALL_CURSOR, InventoryAction.DROP_ONE_CURSOR,
            InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT -> Cause.DROP_FROM_MENU

            InventoryAction.MOVE_TO_OTHER_INVENTORY -> ownSlot(event, Cause.QUICK_MOVE)

            InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE,
            InventoryAction.PLACE_SOME -> ownSlot(event, Cause.CURSOR_PLACE)

            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME -> ownSlot(event, Cause.CURSOR_TAKE)

            else -> null
        }
    }

    // Reaching into a container is a container movement before it is anything else, and the holders
    // already say which way it went. Naming the cursor there would empty that class of its causes
    // without adding a fact, so these labels are only worth leaving on the player's own slots.
    private fun ownSlot(event: InventoryClickEvent, cause: Cause) =
        if (event.clickedInventory is PlayerInventory) cause else null

    // A swap dresses the player in one piece and undresses them of another in the same click; the
    // piece being put on is the one the click was for.
    private fun armorCause(action: InventoryAction) = when (action) {
        InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME,
        InventoryAction.SWAP_WITH_CURSOR, InventoryAction.HOTBAR_SWAP -> Cause.EQUIP_ARMOR

        else -> Cause.UNEQUIP_ARMOR
    }

    // The event arrives before the click is applied and may still be denied, so the snapshot that
    // decides what moved has to wait for the next tick on the region that owns the view. Scheduling
    // is refused once the plugin is disabled, and the shutdown pass covers what is left open.
    private fun scheduleRecompute(player: Player) {
        if (!plugin.isEnabled) return
        player.scheduler.run(plugin, { recompute(player) }, null)
    }

    // `cursor` stands in for what the player holds on the cursor, for the one event that changes it
    // before it is raised.
    internal fun recompute(player: Player, cursor: BukkitItemStack? = null) {
        // Drained before anything can cut the pass short: a player who was already online when the
        // plugin came up has no baseline yet, and intents left behind would pile up until the first
        // one appeared and then explain a delta they had nothing to do with.
        val taken = intents.take(player.uniqueId)
        val baseline = baselines[player.uniqueId]
        if (baseline == null) {
            // Dropping them silently would strand an event that silenced a funnel of its own on the
            // promise of this pass: the entity would get an end and never a beginning.
            for (intent in taken) intent.qty?.let { unspent(intent, it) }
            return
        }
        val after = snapshot(player, baseline.view, cursor)
        baselines[player.uniqueId] = Baseline(baseline.view, after)
        val (was, now) = comparable(baseline.seen, after)
        // One item that became another is a mutation; a recipe that ate three and made one is not, so
        // the kind comes from the event and never from the size of what the pass happened to gather.
        val shift = taken.firstNotNullOfOrNull { it.shift }
        val edges = Netting.diff(was, now, taken, player.uniqueId, stationSlots(player, baseline, shift))
        val timestamp = System.currentTimeMillis()
        // A bundle the player holds is theirs to empty: a drop out of it is still their drop.
        val carried = { nested: Nested -> nested.ownerId in was.containers }
        val spent = HashSet<Intent>()
        // A transformation's reason is spent by whatever the pass gathered at all.
        if (edges.isNotEmpty()) taken.filter { it.shift != null }.forEach(spent::add)
        val moves = Intents.explain(edges, taken, player.uniqueId, unspent, carried, used = spent::add) { form ->
            after.stacks.entries.firstOrNull { (holder, stack) ->
                holder is PlayerHolder && holder.uuid == player.uniqueId && stack.key.form.contentEquals(form)
            }?.key
        }
        for (group in transactions(withoutForeignEnds(moves, shift), shift)) {
            val kind = if (shift != null && group.size > 1) shift.kind else Kind.TRANSFER
            sink(group.map { transferOf(it, timestamp, kind) })
        }
        // After the movements, so a bundle picked up unnamed arrives under the form it was carried in.
        for (rows in namingRows(after, timestamp)) sink(rows)
        val lingering = taken.filter { it.until != null && it.until > timestamp && it !in spent }
        if (lingering.isNotEmpty()) {
            for (intent in lingering) intents.add(player.uniqueId, intent)
            scheduleRecompute(player)
        }
    }

    private fun rebaseline(player: Player) {
        val view = player.openInventory
        baselines[player.uniqueId] = Baseline(view, snapshot(player, view))
    }

    // Only while a transformation is in flight, and only the station's own slots: everywhere else the
    // pairing is what turns two halves into one movement, and switching it off would write every
    // ordinary transfer as a disappearance and an arrival.
    // ponytail: a genuine move out of a station slot in the same tick as the result click joins the
    // transformation instead of keeping its own reason; that needs two clicks inside one tick.
    private fun stationSlots(player: Player, baseline: Baseline, shift: Shift?): Set<Holder> {
        if (shift == null) return emptySet()
        val top = baseline.view.topInventory
        val holders = topHolders(player, top) ?: return emptySet()
        return (0 until top.size).mapTo(HashSet()) { holders(it) }
    }

    private fun snapshot(player: Player, view: InventoryView, cursor: BukkitItemStack? = null): Snapshot {
        val stacks = LinkedHashMap<Holder, Stack>()
        val containers = HashSet<UUID>()
        val named = HashMap<Holder, Naming>()
        val top = view.topInventory
        val topHolder = topHolders(player, top)
        if (topHolder != null) {
            val preview = previewSlot(top)
            for (slot in 0 until top.size) {
                if (slot == preview) continue
                record(stacks, containers, named, topHolder(slot), top.getItem(slot))
            }
        }
        val inventory = player.inventory
        val holders = playerHolders(player.uniqueId, inventory)
        for (slot in 0 until inventory.size) record(stacks, containers, named, holders(slot), inventory.getItem(slot))
        record(stacks, containers, named, PlayerCursor(player.uniqueId), cursor ?: player.itemOnCursor)
        return Snapshot(stacks, containers, named)
    }

    // Bukkit hands out live mirrors of the server's stacks, so a snapshot has to turn every slot into
    // bytes of its own here and now; keeping the stack itself would be keeping a view of the future.
    //
    // A container item is recorded as itself plus a row per item it holds, filed under the container's
    // own name rather than the slot it sits in, so carrying it around moves nothing. One level only:
    // a container deeper down keeps its own name and its contents are already filed under it, and it
    // cannot be reached to change without being taken out first.
    private fun record(
        into: MutableMap<Holder, Stack>,
        containers: MutableSet<UUID>,
        named: MutableMap<Holder, Naming>,
        holder: Holder,
        stack: BukkitItemStack?,
    ) {
        val live = (stack as? CraftItemStack)?.handle ?: CraftItemStack.asNMSCopy(stack ?: return)
        if (live.isEmpty) return
        val contents = NestedItems.contents(live)
        // Naming it has to happen before the form is taken, or the same item would read as a different
        // one on the next pass and the diff would invent a movement out of it. A box that already
        // carries a name keeps answering to it while it stands empty, which is what lets the next pass
        // tell a container emptied in place from one carried out of view.
        val unnamed = if (contents.isNotEmpty() && NestedItems.ownerOf(live) == null) codec.encode(live).key else null
        val owner = if (contents.isEmpty()) NestedItems.ownerOf(live) else NestedItems.own(live)
        val encoded = codec.encode(live)
        into[holder] = Stack(encoded.key, encoded.count)
        if (owner == null) return
        if (unnamed != null) named[holder] = Naming(unnamed, owner)
        containers += owner
        for ((index, child) in contents) {
            val inside = codec.encode(child)
            into[Nested(owner, index)] = Stack(inside.key, inside.count)
        }
    }

    private fun encode(stack: BukkitItemStack?): Stack? =
        codec.encodeOrNull(stack)?.let { Stack(it.key, it.count) }

    private fun topHolders(player: Player, inventory: Inventory): ((Int) -> Holder)? {
        val viewer = player.uniqueId
        // An ender chest reports the coordinates of the block being used while its contents belong to
        // the player, so it has to be recognised before anything that trusts a location.
        if (inventory.type == InventoryType.ENDER_CHEST) return { slot -> PlayerEnder(viewer, slot) }
        if (inventory is PlayerInventory) {
            // Another player's inventory is ticked by the region that owns them, so reading it from
            // here would race with the owner and could invent an edge out of a half-applied change.
            if (inventory.holder?.uniqueId?.equals(viewer) == false) return null
            return playerHolders(viewer, inventory)
        }
        containerHolders(inventory)?.let { return it }
        // Every window backed by a block or an entity has been claimed above, so what is left is a
        // menu nobody owns. The number comes from the registry rather than from the ordinal, which
        // shifts whenever a game update inserts an inventory type ahead of this one.
        val menuType = registries.idForKey(RegistryNamespace.MENU_TYPE, inventory.type.name)
        return { slot -> MenuSlot(menuType, slot) }
    }
}
