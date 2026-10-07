package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import net.minecraft.core.UUIDUtil
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
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
import net.minecraft.world.level.block.Block as NmsBlock

private const val OWNER_TAG = "pfauprotect_owner"

// Items inside a container item are filed under the container rather than under the slot it happens
// to sit in, so carrying it around moves nothing. That needs a name the container keeps wherever it
// goes, and the only place that travels with an item is the item itself. Writing to it is safe
// because everything that holds items — shulker box, bundle, crossbow — never stacks.
object NestedItems {
    // What a container holds is never part of what it is, so the form has to be taken without these:
    // a box whose form moved with its contents would read as a different item on every insertion and
    // the diff would invent a movement out of it. `contents` reads exactly this set, and the codec
    // strips exactly this set, so the two have to be named in one place or a component added to one
    // and not the other silently breaks whichever side was missed.
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
        val item = CraftItemStack.asNMSCopy(inventory.getItem(slot) ?: continue)
        if (!item.isEmpty) pack(slot, owner, item)
    }
    return owner
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
        val placed = CraftItemStack.asNMSCopy(event.itemInHand)
        val owner = NestedItems.ownerOf(placed) ?: UUID.randomUUID()
        owners.setOwnerAt(block.world.uid, block.x, block.y, block.z, owner)
        for ((index, child) in NestedItems.contents(placed)) {
            move(Nested(owner, index), containerAt(block, index), Cause.CONTAINER_PLACE_UNPACK, child, event.player)
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
            if (!Tag.ITEMS_SHULKER_BOXES.isTagged(dropped.itemStack.type)) continue
            val stack = CraftItemStack.asNMSCopy(dropped.itemStack)
            NestedItems.mark(stack, owner)
            dropped.itemStack = CraftItemStack.asBukkitCopy(stack)
        }
    }

    private fun move(from: Holder, to: Holder, cause: Cause, item: ItemStack, actor: Player) {
        val encoded = codec.encode(item)
        pending.add(from, to, cause, encoded.key, encoded.count, actor.uniqueId)
    }
}
