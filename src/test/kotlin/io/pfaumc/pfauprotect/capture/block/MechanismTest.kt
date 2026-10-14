package io.pfaumc.pfauprotect.capture.block
import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.Spawns
import io.pfaumc.pfauprotect.worldStub
import io.pfaumc.pfauprotect.key
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.NoPlacedForms
import io.pfaumc.pfauprotect.capture.item.Stack
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.stub
import io.pfaumc.pfauprotect.testCodec
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Furnace
import org.bukkit.event.block.BlockCookEvent
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import org.bukkit.inventory.ItemStack as BukkitItemStack

@ExtendWith(Vanilla::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MechanismTest {
    private val world = UUID.randomUUID()

    private val codec = testCodec()

    private fun chest(size: Int = 27, fill: Map<Int, ItemStack> = emptyMap()) =
        SimpleContainer(size).apply {
            maxStackSize = 64
            for ((slot, stack) in fill) setItem(slot, stack)
        }

    private fun stone(count: Int = 1) = ItemStack(Items.STONE, count)
    private fun dirt(count: Int = 1) = ItemStack(Items.DIRT, count)

    private fun held(name: String, count: Int) = Stack(key(name), count)

    private fun List<SlotChange>.described() =
        map { "${it.slot} ${if (it.gain) "+" else "-"}${it.qty} ${String(it.key.form)}" }.toSet()

    @Test
    fun `a dispense that placed its item is told apart from one that spent it`() {
        val box = key("shulker_box")
        val spent = listOf(SlotChange(4, box, 1, gain = false))
        val air = "minecraft:air"
        val shulker = "minecraft:shulker_box[facing=east]"

        assertTrue(placedInFront(spent, air, shulker, Material.SHULKER_BOX, Material.SHULKER_BOX))
        // Each case breaks exactly one of the conditions.
        val refused = mapOf(
            "front unchanged" to placedInFront(spent, shulker, shulker, Material.SHULKER_BOX, Material.SHULKER_BOX),
            "another block" to placedInFront(spent, air, "minecraft:stone", Material.STONE, Material.SHULKER_BOX),
            "two changes" to placedInFront(spent + SlotChange(5, box, 1, gain = false), air, shulker, Material.SHULKER_BOX, Material.SHULKER_BOX),
            "a gain" to placedInFront(listOf(SlotChange(4, box, 1, gain = true)), air, shulker, Material.SHULKER_BOX, Material.SHULKER_BOX),
            "two spent" to placedInFront(listOf(SlotChange(4, box, 2, gain = false)), air, shulker, Material.SHULKER_BOX, Material.SHULKER_BOX),
        )
        assertEquals(emptyList<String>(), refused.filterValues { it }.keys.toList())
    }

    // What came out as an entity is the spawn's to write; the dispenser only owes what it spent. The
    // last item of a slot leaves it empty, and the spawn took that one too.
    @Test
    fun `an item thrown out of a dispenser is not written off a second time`() {
        assertTrue(dispenseChanges(listOf(held("diamond", 5), null), listOf(held("diamond", 4), null), 0, ejected = 1).isEmpty())
        assertTrue(dispenseChanges(listOf(held("diamond", 1), null), listOf(null, null), 0, ejected = 1).isEmpty())
    }

    @Test
    fun `what a dispenser spends or fills changes only the slots it touched`() {
        data class Case(val name: String, val before: List<Stack?>, val after: List<Stack?>, val slot: Int, val expected: Set<String>)
        val cases = listOf(
            Case(
                "bone meal spent leaves its slot",
                listOf(null, held("bone_meal", 8)), listOf(null, held("bone_meal", 7)), 1,
                setOf("1 -1 bone_meal"),
            ),
            Case(
                "a bucket filled changes in its own slot",
                listOf(held("bucket", 1)), listOf(held("water_bucket", 1)), 0,
                setOf("0 -1 bucket", "0 +1 water_bucket"),
            ),
            Case(
                "a bottle filled from a stack lands in another slot as the product",
                listOf(held("glass_bottle", 4), null, held("glass_bottle", 2)),
                listOf(held("glass_bottle", 3), held("potion", 1), held("glass_bottle", 2)), 0,
                setOf("0 -1 glass_bottle", "1 +1 potion"),
            ),
        )
        for (case in cases) {
            assertEquals(case.expected, dispenseChanges(case.before, case.after, case.slot, ejected = 0).described(), case.name)
        }
    }

    @Test
    fun `a container takes a stack where its form fits and counts only the room there is`() {
        val cases = listOf(
            Triple("an empty container takes the whole stack into its first slot", chest() to stone(8), Fitting(0, 8)),
            Triple(
                "a stack merges into the first slot already holding its form",
                chest(fill = mapOf(0 to dirt(1), 1 to stone(60))) to stone(4), Fitting(1, 4),
            ),
            Triple(
                "a container with no room for the form takes nothing",
                chest(size = 3, fill = mapOf(0 to dirt(64), 1 to dirt(64), 2 to dirt(64))) to stone(1), null,
            ),
            Triple(
                "only the room summed over every slot the form can reach counts as moved",
                chest(size = 3, fill = mapOf(0 to stone(60), 1 to dirt(64), 2 to stone(61))) to stone(20), Fitting(0, 7),
            ),
        )
        for ((name, input, expected) in cases) {
            assertEquals(expected, Placement.fit(input.first, input.second, null), name)
        }
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

    // Who moved it and how sure that is are part of the movement: merged, one player's hopper would be
    // filed on another, or a guess passed off as a fact.
    @Test
    fun `movements sharing both ends, the form, the actor and the confidence leave as one`() {
        val spawns = Spawns()
        val coalescer = spawns.coalescer
        val from = Container(world, 1, 64, 1, 0)
        val to = Container(world, 1, 63, 1, 0)
        repeat(5) { coalescer.add(from, to, Cause.HOPPER_PUSH, key("stone"), 1) }
        coalescer.add(from, to, Cause.HOPPER_PUSH, key("dirt"), 2)
        coalescer.add(from, to, Cause.DROPPER_PUSH, key("stone"), 3)
        coalescer.add(to, from, Cause.HOPPER_PUSH, key("stone"), 4)
        coalescer.add(from, to, Cause.HOPPER_PUSH, key("stone"), 1, alice)
        coalescer.add(from, to, Cause.HOPPER_PUSH, key("stone"), 1, bob)
        coalescer.add(from, to, Cause.HOPPER_PUSH, key("stone"), 1, bob, Confidence.INFERRED)
        val written = spawns.rows()

        assertEquals(
            setOf(
                listOf(from, to, Cause.HOPPER_PUSH, "stone", 5, null, Confidence.FACT),
                listOf(from, to, Cause.HOPPER_PUSH, "dirt", 2, null, Confidence.FACT),
                listOf(from, to, Cause.DROPPER_PUSH, "stone", 3, null, Confidence.FACT),
                listOf(to, from, Cause.HOPPER_PUSH, "stone", 4, null, Confidence.FACT),
                listOf(from, to, Cause.HOPPER_PUSH, "stone", 1, alice, Confidence.FACT),
                listOf(from, to, Cause.HOPPER_PUSH, "stone", 1, bob, Confidence.FACT),
                listOf(from, to, Cause.HOPPER_PUSH, "stone", 1, bob, Confidence.INFERRED),
            ),
            written.map { listOf(it.from, it.to, it.cause, String(it.form), it.qty, it.actor, it.confidence) }.toSet(),
        )
        assertEquals(7, written.size)
    }

    private fun form(item: net.minecraft.world.item.Item, name: String? = null) =
        codec.encode(
            ItemStack(item).apply {
                if (name != null) set(DataComponents.CUSTOM_NAME, Component.literal(name))
            }
        ).form

    @Test
    fun `a position gives back what it was credited with while the block standing there is still that item`() {
        val namedBox = form(Items.SHULKER_BOX, "Ender Storage")
        val cobblestone = form(Items.COBBLESTONE)
        val grass = form(Items.GRASS_BLOCK)
        val pot = form(Items.FLOWER_POT)
        // remembered, the block's own shell, what is given back
        for ((remembered, shell, expected) in listOf(
            Triple(namedBox, form(Items.SHULKER_BOX), namedBox),
            Triple(null, cobblestone, cobblestone),
            // Dirt turned to grass, or a piston pushed something else in, under a record left by a
            // placement no longer standing there: given back, it would be an item never at that position.
            Triple(form(Items.DIRT, "Sod"), grass, grass),
            // A potted plant backs no item of its own and still gives back the pot that was put down.
            Triple(pot, null, pot),
            Triple(null, null, null),
        )) {
            assertEquals(expected?.toList(), gaveBack(remembered, shell)?.toList())
        }
    }

    // Most blocks have no other half at all, and the answer for them is the one position there is.
    @Test
    fun `a debit for a block goes to the half that was paid for, else where it was struck`() {
        val lower = WorldBlock(world, 2, -60, 22)
        val upper = WorldBlock(world, 2, -59, 22)
        val single = WorldBlock(world, 44, -60, -27)
        val paidLower = { at: WorldBlock -> form(Items.TALL_GRASS).takeIf { at == lower } }
        assertEquals(lower, paidHalf(upper, lower, paidLower))
        assertEquals(lower, paidHalf(lower, upper, paidLower))
        assertEquals(upper, paidHalf(upper, lower) { null })
        assertEquals(single, paidHalf(single, null) { form(Items.STONE) })
    }

    // The event carries a mirror of the whole input slot while the smelt takes exactly one item out of
    // it, so a furnace loaded with a stack must still book a loss of one per ingot.
    @Test
    fun `a smelt consumes one item however full the input slot is`(@TempDir dir: Path) = RocksItemLog(dir).use { owners ->
        val written = ArrayList<List<Transfer>>()
        val spawns = Spawns()
        val listener = BlockMechanismListener(
            codec, spawns.coalescer, spawns.origins, NoPlacedForms, written::add,
            owners = owners, intend = { _, _ -> }, later = { _, _ -> },
        )
        val at = BukkitItemStack(Material.RAW_IRON, 8)
        val out = BukkitItemStack(Material.IRON_INGOT, 1)

        listener.onCook(BlockCookEvent(furnaceBlock(), at, out))

        // One call: the two halves have to share a transaction or neither names the other.
        val transaction = written.single()
        assertEquals(2, transaction.size)
        assertTrue(transaction.all { it.kind == Kind.MUTATE && it.cause == Cause.SMELT }, "$transaction")
        val consumed = transaction.single { it.to == Void }
        assertEquals(1, consumed.qty, "the whole input stack was booked as smelted")
        assertEquals(Container(world, 7, 65, -12, 0), consumed.from)
        assertArrayEquals(form(Items.RAW_IRON), consumed.form)
        val produced = transaction.single { it.from == Void }
        assertEquals(1, produced.qty)
        assertArrayEquals(form(Items.IRON_INGOT), produced.form)
        assertEquals(Container(world, 7, 65, -12, 2), produced.to)
    }

    private fun furnaceBlock(): Block {
        val furnace = stub(Furnace::class.java)
        return stub(
            Block::class.java,
            mapOf("getWorld" to worldStub(world), "getX" to 7, "getY" to 65, "getZ" to -12, "getState" to furnace),
        )
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
