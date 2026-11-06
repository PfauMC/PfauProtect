package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.storage.nmsView
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import net.minecraft.core.UUIDUtil
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.BundleContents
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.level.block.ShulkerBoxBlock
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.ShulkerBox
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockPlaceEvent
import java.util.UUID
import kotlin.jvm.optionals.getOrNull
import kotlin.math.abs
import net.minecraft.world.level.block.Block as NmsBlock

private const val OWNER_TAG = "pfauprotect_owner"

// Items inside a container item are filed under the container rather than under the slot it happens
// to sit in, so carrying it around moves nothing. That needs a name the container keeps wherever it
// goes, and the only place that travels with an item is the item itself. Writing to it is safe
// because everything that holds items — shulker box, bundle, crossbow — never stacks.
object NestedItems {
    // The codec strips these before taking a form: a box whose form moved with its contents would read
    // as a different item on every insertion and the diff would invent a movement out of it.
    val NESTING_COMPONENTS: List<DataComponentType<*>> =
        listOf(DataComponents.CONTAINER, DataComponents.BUNDLE_CONTENTS)

    fun contents(stack: ItemStack): List<Pair<Int, ItemStack>> {
        stack.get(DataComponents.CONTAINER)?.let { container ->
            return container.items.withIndex().mapNotNull { (index, item) ->
                item.getOrNull()?.let { index to it.create() }
            }
        }
        stack.get(DataComponents.BUNDLE_CONTENTS)?.let { bundle ->
            return bundle.items().mapIndexed { index, item -> index to item.create() }
        }
        return emptyList()
    }

    fun ownerOf(stack: ItemStack): UUID? =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.read(OWNER_TAG, UUIDUtil.CODEC)?.getOrNull()

    fun own(stack: ItemStack): UUID = ownerOf(stack) ?: UUID.randomUUID().also { mark(stack, it) }

    // Asked of the item rather than of a tag: tags are bound late, and a box is a box without them.
    fun isShulkerBox(stack: ItemStack) = NmsBlock.byItem(stack.item) is ShulkerBoxBlock

    fun mark(stack: ItemStack, owner: UUID) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack) { it.store(OWNER_TAG, UUIDUtil.CODEC, owner) }
    }
}

/**
 * The slots of a shulker box standing as a block, handed over under the name its contents answer to
 * as an item. However the box goes — a hand, a piston, an explosion — the contents leave the position
 * inside it, and the name the position kept is the one the item has to carry.
 */
internal fun packShulker(
    owners: RocksItemLog,
    block: Block,
    box: ShulkerBox,
    pack: (slot: Int, owner: UUID, item: ItemStack) -> Unit,
): UUID {
    val world = block.world.uid
    val owner = owners.ownerAt(world, block.x, block.y, block.z) ?: UUID.randomUUID()
    owners.clearOwnerAt(world, block.x, block.y, block.z)
    val inventory = box.inventory
    for (slot in 0 until inventory.size) {
        val item = nmsView(inventory.getItem(slot) ?: continue)
        if (!item.isEmpty) pack(slot, owner, item)
    }
    return owner
}

/** The other end of [packShulker]: a box put down as a block hands its contents to the position, under its name. */
internal fun unpackShulker(
    owners: RocksItemLog,
    block: Block,
    box: ItemStack,
    unpack: (from: Holder, to: Holder, item: ItemStack) -> Unit,
) {
    val named = NestedItems.ownerOf(box)
    val owner = named ?: UUID.randomUUID()
    owners.setOwnerAt(block.world.uid, block.x, block.y, block.z, owner)
    // A box nobody named was never filed: what it held comes into the position from nowhere.
    for ((index, child) in NestedItems.contents(box)) unpack(if (named == null) Void else Nested(owner, index), containerAt(block, index), child)
}

// ponytail: one read per container item, oldest rows first; a box or bundle with more rows than this under
// its name comes back with what the first of them booked. Page by time if one ever gets there.
private const val NESTED_ROWS = 100_000

/**
 * A row a rollback writes under a container item's name, so the rows hold what the item it gives back
 * holds: `qty` above zero comes in from nowhere, below zero goes out to nowhere.
 */
class Restock(val at: Nested, val form: ByteArray, val damage: Int?, val qty: Int) {
    fun row(timestamp: Long, actor: UUID?) =
        Transfer(Cause.ROLLBACK, if (qty > 0) Void else at, if (qty > 0) at else Void, form, damage, abs(qty), timestamp, actor = actor)
}

/** An item made to be given back, and what its rows need once it is given: see [withContents]. */
class Made(val stack: ItemStack, val restock: List<Restock> = emptyList())

/**
 * The item a form stands for, with what it held at [upTo] put back inside it. A form never carries the
 * contents (they are rows filed under the item's own name), so an item made from the form alone is a box
 * or a bundle emptied of everything. Each slot gets what the rows under the name booked there, with the
 * wear of the newest arrival; an item inside that holds items itself is filled the same way. What the
 * rows booked there since is set back to that by [Made.restock], to be written once the item is given.
 * Reads the ledger: off the region threads.
 */
internal fun withContents(
    ledger: RocksItemLog,
    codec: ItemFormCodec,
    form: ByteArray,
    count: Int,
    damage: Int?,
    upTo: Long,
    seen: Set<UUID> = emptySet(),
): Made {
    val stack = codec.decode(form, count, damage)
    // A name already being filled further out would be a loop in the rows, not an item inside itself.
    val owner = NestedItems.ownerOf(stack)?.takeIf { it !in seen } ?: return Made(stack)
    class Booked(var then: Int = 0, var now: Int = 0, var damage: Int? = null, var lastIn: Long = Long.MIN_VALUE)
    val slots = sortedMapOf<Int, LinkedHashMap<Long, Booked>>()
    for (row in ledger.holderEntries(Nested(owner, 0), 0, Long.MAX_VALUE, limit = NESTED_ROWS)) {
        val booked = slots.getOrPut((row.holder as Nested).index) { LinkedHashMap() }.getOrPut(row.itemFormId) { Booked() }
        booked.now += row.qty
        if (row.timestamp > upTo) continue
        booked.then += row.qty
        if (row.qty > 0) {
            booked.damage = row.damage
            booked.lastIn = row.timestamp
        }
    }
    val inside = ArrayList<Pair<Int, ItemStack>>()
    val restock = ArrayList<Restock>()
    for ((index, forms) in slots) {
        // One item a slot, and only a slot the item has. Two forms left standing in one slot is a gap in the rows:
        // what arrived there last is what it held, and the other is booked out of it below.
        val held = forms.entries.filter { it.value.then > 0 }.maxByOrNull { it.value.lastIn }?.key?.takeIf { index < ItemContainerContents.MAX_SIZE }
        for ((formId, booked) in forms) {
            val child = ledger.form(formId) ?: continue
            val kept = formId == held
            if (kept) {
                val made = withContents(ledger, codec, child, booked.then, booked.damage, upTo, seen + owner)
                inside += index to made.stack
                restock += made.restock
            }
            val gap = (if (kept) booked.then else 0) - booked.now
            if (gap != 0) restock += Restock(Nested(owner, index), child, booked.damage, gap)
        }
    }
    if (inside.isEmpty()) return Made(stack, restock)
    if (stack.has(DataComponents.BUNDLE_CONTENTS)) {
        stack.set(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY.copyWithContents(inside.stream().map { it.second }))
    } else {
        val items = MutableList(inside.last().first + 1) { ItemStack.EMPTY }
        for ((index, child) in inside) items[index] = child
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items))
    }
    return Made(stack, restock)
}

// While a shulker box stands as a block its contents are addressed by the block, and as an item they
// are addressed by its own name. Both ends of that switch are written so the chain of custody runs
// through the cycle instead of ending at it.
class NestedCaptureListener(
    private val owners: RocksItemLog,
    private val codec: ItemFormCodec,
    private val pending: TickCoalescer,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val block = event.block
        if (!Tag.SHULKER_BOXES.isTagged(block.type)) return
        unpackShulker(owners, block, nmsView(event.itemInHand)) { from, to, child ->
            move(from, to, Cause.CONTAINER_PLACE_UNPACK, child, event.player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockDropItemEvent) {
        val state = event.blockState as? ShulkerBox ?: return
        val block = event.block
        val owner = packShulker(owners, block, state) { slot, owner, item ->
            move(containerAt(block, slot), Nested(owner, slot), Cause.CONTAINER_BREAK_PACK, item, event.player)
        }
        // The loot table of a shulker copies a fixed handful of components onto the dropped item and
        // the name is not one of them, so it is written back here or the chain ends at the break.
        for (dropped in event.items) {
            val stack = CraftItemStack.asNMSCopy(dropped.itemStack)
            if (!NestedItems.isShulkerBox(stack)) continue
            NestedItems.mark(stack, owner)
            dropped.itemStack = CraftItemStack.asBukkitCopy(stack)
        }
    }

    private fun move(from: Holder, to: Holder, cause: Cause, item: ItemStack, actor: Player) {
        val encoded = codec.encode(item)
        pending.add(from, to, cause, encoded.key, encoded.count, actor.uniqueId)
    }
}
