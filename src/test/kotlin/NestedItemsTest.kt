package io.pfaumc.pfauprotect

import net.minecraft.core.NonNullList
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NestedItemsTest {
    private val registries = Registries(MemoryRegistryStore())
    private lateinit var codec: ItemFormCodec

    @BeforeAll
    fun loadServerRegistries() {
        val access = ServerRegistries.access
        for (key in BuiltInRegistries.ITEM.keySet()) {
            registries.idForKey(RegistryNamespace.ITEM_TYPE, key.toString())
        }
        for (key in BuiltInRegistries.DATA_COMPONENT_TYPE.keySet()) {
            registries.idForKey(RegistryNamespace.DATA_COMPONENT_TYPE, key.toString())
        }
        codec = ItemFormCodec(registries, access)
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
    fun `a bundle reports what it carries`() {
        val bag = bundle(ItemStack(Items.DIAMOND, 4), ItemStack(Items.STONE, 7))
        assertEquals(listOf(0, 1), NestedItems.contents(bag).map { it.first })
        assertEquals(7, NestedItems.contents(bag)[1].second.count)
    }

    @Test
    fun `an ordinary item carries nothing`() {
        assertTrue(NestedItems.contents(ItemStack(Items.DIAMOND, 4)).isEmpty())
    }

    // The contents are written as rows of their own, so leaving them in the form as well would make a
    // container read as a different item every time something moved inside it.
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
}
