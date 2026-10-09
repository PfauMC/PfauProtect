package io.pfaumc.pfauprotect

import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Furnace
import org.bukkit.event.block.BlockCookEvent
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.Proxy
import java.util.UUID
import org.bukkit.inventory.ItemStack as BukkitItemStack

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

    private fun held(name: String, count: Int) = Stack(ItemKey(name.toByteArray(), null), count)

    // What came out as an entity is the spawn's to write; the dispenser only owes what it spent.
    @Test
    fun `an item thrown out of a dispenser is not written off a second time`() {
        val before = listOf(held("diamond", 5), null)
        val after = listOf(held("diamond", 4), null)
        assertTrue(dispenseChanges(before, after, 0, ejected = 1).isEmpty())
    }

    // The last item of a slot: the slot is gone empty, and the spawn took it, so nothing is left to write.
    @Test
    fun `the last item thrown out of a dispenser is not written off either`() {
        val before = listOf(held("diamond", 1), null)
        assertTrue(dispenseChanges(before, listOf(null, null), 0, ejected = 1).isEmpty())
    }

    @Test
    fun `bone meal spent by a dispenser leaves its slot`() {
        val before = listOf(null, held("bone_meal", 8))
        val after = listOf(null, held("bone_meal", 7))
        val change = dispenseChanges(before, after, 1, ejected = 0).single()
        assertEquals(1, change.slot)
        assertEquals(1, change.qty)
        assertTrue(!change.gain)
    }

    // A single bucket filled in place: the slot holds something else, which is one item changed.
    @Test
    fun `a bucket filled by a dispenser changes in its own slot`() {
        val before = listOf(held("bucket", 1))
        val after = listOf(held("water_bucket", 1))
        val changes = dispenseChanges(before, after, 0, ejected = 0)
        assertEquals(listOf(false, true), changes.map { it.gain })
        assertEquals("water_bucket", String(changes[1].key.form))
    }

    // One bottle out of a stack of them: the stack is one short and the water bottle turns up in a slot
    // that did not hold it. Another slot already holding bottles is left alone.
    @Test
    fun `a bottle filled from a stack lands in another slot as the product`() {
        val before = listOf(held("glass_bottle", 4), null, held("glass_bottle", 2))
        val after = listOf(held("glass_bottle", 3), held("potion", 1), held("glass_bottle", 2))
        val changes = dispenseChanges(before, after, 0, ejected = 0)
        assertEquals(2, changes.size)
        assertEquals(listOf(0 to false, 1 to true), changes.map { it.slot to it.gain })
    }

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

    // Fire, a liquid and a portal are the blocks nobody put down, and there is nothing to give back
    // for them because nothing was ever taken.
    @Test
    fun `a block nobody placed and with no item form of its own gives nothing back`() {
        assertNull(gaveBack(null, null))
    }

    // A potted plant, a candle cake and a stem that has grown its fruit all back an item that is
    // nowhere in the registry while standing on a position the ledger credited. Giving nothing back
    // there leaves that credit behind for ever, where no self-check reaches it.
    @Test
    fun `a position that was placed gives back what it took even where the block backs no item`() {
        val pot = form(Items.FLOWER_POT)
        assertArrayEquals(pot, gaveBack(pot, null))
    }

    // A door, a bed, a tall plant and an extended piston are paid for once, on the half the placement
    // names. Struck on the other half, the debit still belongs where the credit sits, or the credited
    // half keeps a holding for a block that is gone.
    @Test
    fun `a debit for a double block goes to the half that was paid for`() {
        val lower = WorldBlock(world, 2, -60, 22)
        val upper = WorldBlock(world, 2, -59, 22)
        val paid = form(Items.TALL_GRASS)
        val remembered = { at: WorldBlock -> paid.takeIf { at == lower } }

        assertEquals(lower, paidHalf(upper, lower, remembered))
        assertEquals(lower, paidHalf(lower, upper, remembered))
    }

    // A plant the world generated was paid for on neither half, and inventing a partner for it would
    // move the debit off the position that actually lost the block.
    @Test
    fun `a debit for a double block nobody paid for stays where it was struck`() {
        val lower = WorldBlock(world, 2, -60, 22)
        val upper = WorldBlock(world, 2, -59, 22)
        assertEquals(upper, paidHalf(upper, lower) { null })
    }

    // Most blocks have no other half at all, and the answer for them is the one position there is.
    @Test
    fun `a debit for a single block is booked where it was struck`() {
        val at = WorldBlock(world, 44, -60, -27)
        assertEquals(at, paidHalf(at, null) { form(Items.STONE) })
    }

    // The event carries a mirror of the whole input slot while the smelt takes exactly one item out of
    // it, so a furnace loaded with a stack must still book a loss of one per ingot.
    @Test
    fun `a smelt consumes one item however full the input slot is`() {
        val written = ArrayList<List<Transfer>>()
        val coalescer = TickCoalescer { }
        val listener = BlockMechanismListener(
            codec, coalescer, SpawnOrigins(coalescer), NoPlacedForms, written::add,
        )
        val at = BukkitItemStack(Material.RAW_IRON, 8)
        val out = BukkitItemStack(Material.IRON_INGOT, 1)

        listener.onCook(BlockCookEvent(furnaceBlock(), at, out))

        // One call: the two halves have to share a transaction or neither names the other.
        assertEquals(1, written.size, "$written")
        val transaction = written.single()
        assertEquals(2, transaction.size)
        assertTrue(transaction.all { it.kind == Kind.MUTATE && it.cause == Cause.SMELT }, "$transaction")
        val consumed = transaction.single { it.to == Void }
        assertEquals(1, consumed.qty, "the whole input stack was booked as smelted")
        assertEquals(Container(world, 7, 65, -12, 0), consumed.from)
        val produced = transaction.single { it.from == Void }
        assertEquals(1, produced.qty)
        assertEquals(Container(world, 7, 65, -12, 2), produced.to)
    }

    private object NoPlacedForms : PlacedForms {
        override fun formAt(world: UUID, x: Int, y: Int, z: Int): ByteArray? = null
        override fun formsAt(positions: List<WorldBlock>): Map<WorldBlock, ByteArray> = emptyMap()
        override fun setFormAt(world: UUID, x: Int, y: Int, z: Int, form: ByteArray) = Unit
        override fun clearFormAt(world: UUID, x: Int, y: Int, z: Int) = Unit
        override fun clearFormsAt(positions: List<WorldBlock>) = Unit
    }

    private fun furnaceBlock(): Block {
        val furnace = stub(Furnace::class.java)
        val worldStub = stub(World::class.java, mapOf("getUID" to world))
        return stub(
            Block::class.java,
            mapOf("getWorld" to worldStub, "getX" to 7, "getY" to 65, "getZ" to -12, "getState" to furnace),
        )
    }

    private fun <T : Any> stub(type: Class<T>, answers: Map<String, Any?> = emptyMap()): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            answers[method.name]
        } as T
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
