package io.pfaumc.pfauprotect.capture.item
import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.MemoryRegistryStore
import io.pfaumc.pfauprotect.storage.Registries
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.storage.fillTypeRegistries
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.stub
import io.pfaumc.pfauprotect.worldStub
import org.bukkit.block.Block
import org.bukkit.block.ShulkerBox
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.inventory.Inventory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import net.minecraft.core.NonNullList
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.BundleContents
import net.minecraft.world.item.component.ItemContainerContents
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@ExtendWith(Vanilla::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NestedItemsTest {
    private val registries = Registries(MemoryRegistryStore())
    private val codec = run {
        fillTypeRegistries(registries)
        ItemFormCodec(registries, ServerRegistries.access)
    }

    private fun shulker(vararg items: Pair<Int, ItemStack>): ItemStack {
        val box = ItemStack(Items.SHULKER_BOX)
        if (items.isEmpty()) return box
        val slots = NonNullList.withSize(27, ItemStack.EMPTY)
        for ((slot, item) in items) slots[slot] = item
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots))
        return box
    }

    private fun bundle(vararg items: ItemStack): ItemStack {
        val bag = ItemStack(Items.BUNDLE)
        bag.set(DataComponents.BUNDLE_CONTENTS, BundleContents(items.map(ItemStackTemplate::fromNonEmptyStack)))
        return bag
    }

    private fun form(stack: ItemStack) = codec.encode(stack).form

    @Test
    fun `a container keeps the name it is given`() {
        val box = shulker(0 to ItemStack(Items.DIAMOND, 4))
        assertNull(NestedItems.ownerOf(box))
        val owner = NestedItems.own(box)
        assertEquals(owner, NestedItems.own(box))
        assertEquals(owner, NestedItems.ownerOf(box))
        assertNotEquals(owner, NestedItems.own(shulker(0 to ItemStack(Items.DIAMOND, 4))))
    }

    @Test
    fun `contents come back under the slots they sit in`() {
        val box = shulker(0 to ItemStack(Items.DIAMOND, 4), 5 to ItemStack(Items.STONE, 12))
        val contents = NestedItems.contents(box)
        assertEquals(listOf(0, 5), contents.map { it.first })
        assertEquals(4, contents[0].second.count)
        assertTrue(contents[1].second.`is`(Items.STONE))
    }

    @Test
    fun `an ordinary item carries nothing`() {
        assertTrue(NestedItems.contents(ItemStack(Items.DIAMOND, 4)).isEmpty())
    }

    @Test
    fun `a container reads the same full or empty`() {
        val owner = UUID.randomUUID()
        val full = shulker(0 to ItemStack(Items.DIAMOND, 4)).also { NestedItems.mark(it, owner) }
        val empty = shulker().also { NestedItems.mark(it, owner) }
        assertArrayEquals(form(empty), form(full))
    }

    @Test
    fun `a bundle is emptied out of its form the same way`() {
        val full = bundle(ItemStack(Items.DIAMOND, 4))
        val empty = ItemStack(Items.BUNDLE)
        assertArrayEquals(form(empty), form(full))
    }

    @Test
    fun `two containers with different names are different items`() {
        val one = shulker().also { NestedItems.mark(it, UUID.randomUUID()) }
        val other = shulker().also { NestedItems.mark(it, UUID.randomUUID()) }
        assertFalse(form(one).contentEquals(form(other)))
    }

    // A shulker's loot table drops the name off the item, so the name has to survive at the position
    // from placement to break, or the contents' chain of custody ends at the first break.
    @Test
    fun `a placed box hands its contents to the position and takes them back under the same name`(@TempDir dir: Path) {
        val world = UUID.randomUUID()
        val at = { slot: Int -> Container(world, 4, 70, -2, slot) }
        val block = stub(Block::class.java, mapOf("getWorld" to worldStub(world), "getX" to 4, "getY" to 70, "getZ" to -2))
        val box = shulker(0 to ItemStack(Items.DIAMOND, 4), 5 to ItemStack(Items.STONE, 12))
        val owner = NestedItems.own(box)
        RocksItemLog(dir).use { log ->
            val placed = ArrayList<Triple<Holder, Holder, Int>>()
            unpackShulker(log, block, box) { from, to, item -> placed += Triple(from, to, item.count) }

            assertEquals(listOf(Triple(Nested(owner, 0), at(0), 4), Triple(Nested(owner, 5), at(5), 12)), placed)
            assertEquals(owner, log.ownerAt(world, 4, 70, -2))

            val slots = NestedItems.contents(box).toMap()
            val state = stub(
                ShulkerBox::class.java,
                mapOf(
                    "getInventory" to stub(
                        Inventory::class.java,
                        mapOf("getSize" to 27, "getItem" to { args: Array<out Any?> -> slots[args[0] as Int]?.let(CraftItemStack::asBukkitCopy) }),
                    ),
                ),
            )
            val breaker = stub(Player::class.java, mapOf("getUniqueId" to UUID.randomUUID()))
            val rows = ArrayList<Transfer>()
            val coalescer = TickCoalescer(rows::add)
            NestedCaptureListener(log, codec, coalescer).onBreak(BlockDropItemEvent(block, state, breaker, emptyList()))
            coalescer.flush()

            assertEquals(
                setOf(
                    Triple(at(0), Nested(owner, 0), 4) to Cause.CONTAINER_BREAK_PACK,
                    Triple(at(5), Nested(owner, 5), 12) to Cause.CONTAINER_BREAK_PACK,
                ),
                rows.map { Triple(it.from, it.to, it.qty) to it.cause }.toSet(),
            )
            assertEquals(2, rows.size)
            assertNull(log.ownerAt(world, 4, 70, -2))
        }
    }
}
