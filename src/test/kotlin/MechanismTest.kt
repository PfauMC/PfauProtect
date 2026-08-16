package io.pfaumc.pfauprotect

import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MechanismTest {
    private val world = UUID.randomUUID()

    private lateinit var codec: ItemFormCodec

    @BeforeAll
    fun loadServerRegistries() {
        codec = ItemFormCodec(Registries(MemoryRegistryStore()), ServerRegistries.access)
    }

    private fun chest(size: Int = 27, fill: Map<Int, ItemStack> = emptyMap()) =
        SimpleContainer(size).apply {
            maxStackSize = 64
            for ((slot, stack) in fill) setItem(slot, stack)
        }

    private fun stone(count: Int = 1) = ItemStack(Items.STONE, count)
    private fun dirt(count: Int = 1) = ItemStack(Items.DIRT, count)

    @Test
    fun `an empty container takes the whole stack into its first slot`() {
        assertEquals(Fitting(0, 8), Placement.fit(chest(), stone(8), null))
    }

    @Test
    fun `a stack merges into the first slot already holding its form`() {
        val destination = chest(fill = mapOf(0 to dirt(1), 1 to stone(60)))
        assertEquals(Fitting(1, 4), Placement.fit(destination, stone(4), null))
    }

    // A sorter hopper stands loaded against a container that cannot take from it, and the game keeps
    // firing the event as long as it holds an item: without this the ledger fills with movements that
    // never happened.
    @Test
    fun `a container with no room for the form takes nothing`() {
        val full = chest(size = 3, fill = mapOf(0 to dirt(64), 1 to dirt(64), 2 to dirt(64)))
        assertNull(Placement.fit(full, stone(1), null))
    }

    @Test
    fun `only the part that fits counts as moved`() {
        val nearlyFull = chest(size = 2, fill = mapOf(0 to stone(62), 1 to dirt(64)))
        assertEquals(Fitting(0, 2), Placement.fit(nearlyFull, stone(10), null))
    }

    @Test
    fun `room is summed over every slot the form can reach`() {
        val spread = chest(size = 3, fill = mapOf(0 to stone(60), 1 to dirt(64), 2 to stone(61)))
        assertEquals(Fitting(0, 7), Placement.fit(spread, stone(20), null))
    }

    @Test
    fun `a container with faces is entered through the face that looks at the sender`() {
        val furnace = FacedContainer()
        assertEquals(Fitting(0, 1), Placement.fit(furnace, stone(1), Direction.UP))
        assertEquals(Fitting(1, 1), Placement.fit(furnace, stone(1), Direction.NORTH))
        assertNull(Placement.fit(furnace, stone(1), Direction.DOWN))
    }

    @Test
    fun `the source slot is the first one holding the moved form`() {
        val source = chest(fill = mapOf(0 to dirt(3), 2 to stone(5), 4 to stone(5)))
        assertEquals(2, Placement.holdingSlot(source, stone(1), null))
        assertEquals(-1, Placement.holdingSlot(source, ItemStack(Items.DIAMOND), null))
    }

    @Test
    fun `a faced source gives up only what the face reaches`() {
        val furnace = FacedContainer()
        furnace.setItem(0, stone(1))
        furnace.setItem(2, dirt(1))
        assertEquals(0, Placement.holdingSlot(furnace, stone(1), Direction.UP))
        assertEquals(-1, Placement.holdingSlot(furnace, stone(1), Direction.DOWN))
    }

    @Test
    fun `movements sharing both ends and the form leave as one`() {
        val written = ArrayList<Transfer>()
        val coalescer = TickCoalescer(written::add)
        val from = Container(world, 1, 64, 1, 0)
        val to = Container(world, 1, 63, 1, 0)
        val key = ItemKey("stone".toByteArray(), null)
        repeat(5) { coalescer.add(from, to, Cause.HOPPER_PUSH, key, 1) }
        coalescer.add(from, to, Cause.HOPPER_PUSH, ItemKey("dirt".toByteArray(), null), 2)
        coalescer.add(to, from, Cause.HOPPER_PULL_CONTAINER, key, 3)
        coalescer.flush()

        assertEquals(3, written.size)
        val push = written.single { it.cause == Cause.HOPPER_PUSH && it.form.contentEquals(key.form) }
        assertEquals(5, push.qty)
        assertEquals(from, push.from)
        assertEquals(to, push.to)
        assertEquals(setOf(5, 2, 3), written.map { it.qty }.toSet())
    }

    @Test
    fun `a flushed tick starts empty`() {
        val written = ArrayList<Transfer>()
        val coalescer = TickCoalescer(written::add)
        val holder = Container(world, 0, 0, 0, 0)
        coalescer.add(holder, Void, Cause.HOPPER_PUSH, ItemKey("stone".toByteArray(), null), 1)
        coalescer.flush()
        coalescer.flush()
        assertEquals(1, written.size)
    }

    private fun form(item: net.minecraft.world.item.Item, name: String? = null) =
        codec.encode(
            ItemStack(item).apply {
                if (name != null) set(DataComponents.CUSTOM_NAME, Component.literal(name))
            }
        ).form

    @Test
    fun `a position gives back the named box it took over and not a bare one`() {
        val named = form(Items.SHULKER_BOX, "Ender Storage")
        val bare = form(Items.SHULKER_BOX)
        assertArrayEquals(named, gaveBack(named, bare))
    }

    @Test
    fun `a position nobody placed gives back what the block itself is made of`() {
        val cobblestone = form(Items.COBBLESTONE)
        assertArrayEquals(cobblestone, gaveBack(null, cobblestone))
    }

    // Dirt turned to grass, or a piston pushed something else in, under a record left by a placement
    // that is no longer standing there. Given back it would be an item that was never at that position.
    @Test
    fun `a form remembered for another item is not given back`() {
        val grass = form(Items.GRASS_BLOCK)
        assertArrayEquals(grass, gaveBack(form(Items.DIRT, "Sod"), grass))
    }

    @Test
    fun `a block with no item form of its own gives nothing back`() {
        assertNull(gaveBack(form(Items.COBBLESTONE), null))
    }

    // Stands in for a furnace: reachable slots depend on the face, and the game asks the container
    // itself rather than reading its inventory.
    private class FacedContainer : SimpleContainer(3), WorldlyContainer {
        override fun getSlotsForFace(direction: Direction): IntArray = when (direction) {
            Direction.UP -> intArrayOf(0)
            Direction.DOWN -> intArrayOf(2)
            else -> intArrayOf(1)
        }

        override fun canPlaceItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction?): Boolean =
            slot != 2

        override fun canTakeItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction): Boolean = true
    }
}
