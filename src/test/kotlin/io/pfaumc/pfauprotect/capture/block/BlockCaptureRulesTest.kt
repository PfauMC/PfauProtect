package io.pfaumc.pfauprotect.capture.block
import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.stub
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtIo
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.item.ItemStack as NmsItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.state.BlockState as NmsBlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.Half
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

private const val SOURCE_WATER = "minecraft:water[level=0]"
private const val AIR = "minecraft:air"

@ExtendWith(Vanilla::class)
class BlockCaptureRulesTest {
    private val registries: RegistryAccess = ServerRegistries.access
    private val signState = Blocks.OAK_SIGN.defaultBlockState()

    private fun sign(at: BlockPos, line: String): SignBlockEntity =
        SignBlockEntity(at, signState).apply { setText(getText(SignTextSlot.FRONT).asMutable().setLine(0, Component.literal(line)).asImmutable(), SignTextSlot.FRONT) }

    private fun partnerOf(state: NmsBlockState) = partnerFace(state.asBlockData())

    private fun silkTouch(): NmsItemStack = NmsItemStack(Items.DIAMOND_PICKAXE).apply {
        enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1)
    }

    private fun after(
        state: NmsBlockState,
        below: NmsBlockState = Blocks.AIR.defaultBlockState(),
        tool: NmsItemStack = NmsItemStack(Items.DIAMOND_PICKAXE),
        dropsBlock: Boolean = true,
        waterEvaporates: Boolean = false,
    ): String = brokenAfter(state, below, tool, dropsBlock, waterEvaporates).asBlockData().asString

    @Test
    fun `a block standing in two positions names the other one and a block standing in one names none`() {
        val door = Blocks.SPRUCE_DOOR.defaultBlockState()
        assertEquals(BlockFace.UP, partnerOf(door))
        assertEquals(BlockFace.DOWN, partnerOf(door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)))

        val bed = Blocks.BED.white().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
        assertEquals(BlockFace.EAST, partnerOf(bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)))
        assertEquals(BlockFace.WEST, partnerOf(bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)))

        val sunflower = Blocks.SUNFLOWER.defaultBlockState()
        assertEquals(BlockFace.UP, partnerOf(sunflower))
        assertEquals(BlockFace.DOWN, partnerOf(sunflower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)))

        val piston = Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP)
        assertNull(partnerOf(piston))
        assertEquals(BlockFace.UP, partnerOf(piston.setValue(BlockStateProperties.EXTENDED, true)))
        assertEquals(
            BlockFace.DOWN,
            partnerOf(Blocks.PISTON_HEAD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP)),
        )

        assertNull(partnerOf(Blocks.STONE.defaultBlockState()))
        // Bisected and yet standing in one position, which is what stops the half of a stair or a
        // trapdoor from being read as the half of a door.
        assertNull(partnerOf(Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP)))
        assertNull(partnerOf(Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP)))
    }

    @Test
    fun `a break leaves the fluid the block stood in behind`() {
        assertEquals(AIR, after(Blocks.STONE.defaultBlockState()))
        assertEquals(AIR, after(Blocks.OAK_STAIRS.defaultBlockState()))
        assertEquals(
            SOURCE_WATER,
            after(Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)),
        )
        // Neither of these carries a waterlogged property.
        assertEquals(SOURCE_WATER, after(Blocks.KELP.defaultBlockState()))
        assertEquals(SOURCE_WATER, after(Blocks.KELP_PLANT.defaultBlockState()))
        assertEquals(SOURCE_WATER, after(Blocks.SEAGRASS.defaultBlockState()))
    }

    @Test
    fun `ice melts over that where the block's own rule says it does`() {
        val ice = Blocks.ICE.defaultBlockState()
        val stone = Blocks.STONE.defaultBlockState()

        assertEquals(SOURCE_WATER, after(ice, below = stone))
        assertEquals(SOURCE_WATER, after(ice, below = Blocks.WATER.defaultBlockState()))
        assertEquals(SOURCE_WATER, after(Blocks.FROSTED_ICE.defaultBlockState(), below = stone))
        assertEquals(AIR, after(ice, below = Blocks.AIR.defaultBlockState()))
        assertEquals(AIR, after(ice, below = stone, tool = silkTouch()))
        assertEquals(AIR, after(ice, below = stone, dropsBlock = false))
        assertEquals(AIR, after(ice, below = stone, waterEvaporates = true))

        assertEquals(AIR, after(Blocks.PACKED_ICE.defaultBlockState(), below = stone))
        assertEquals(AIR, after(Blocks.BLUE_ICE.defaultBlockState(), below = stone))
    }

    // A block in a world made of a map: whatever is not in it is air, and a neighbour is asked for by
    // position like `CraftBlock` does, so two routes to one position meet as one block.
    private fun blockIn(blocks: Map<BlockPos, NmsBlockState>, at: BlockPos): Block = stub(
        Block::class.java,
        mapOf(
            "getBlockData" to { _: Array<out Any?> -> (blocks[at] ?: Blocks.AIR.defaultBlockState()).asBlockData() },
            "getRelative" to { args: Array<out Any?> ->
                (args[0] as BlockFace).let { blockIn(blocks, at.offset(it.modX, it.modY, it.modZ)) }
            },
            "getX" to at.x,
            "getY" to at.y,
            "getZ" to at.z,
            "equals" to { args: Array<out Any?> -> (args[0] as? Block)?.let { it.x == at.x && it.y == at.y && it.z == at.z } == true },
            "hashCode" to at.hashCode(),
        ),
    )

    // Lava poured on a roof at x 0, run east over the edge, down the wall and on along the ground. From
    // the ground the walk climbs back to the bucket; a source below where it starts is never one it
    // ran from, and a source over the region border is not read.
    @Test
    fun `a liquid is walked back up to the source it runs from`() {
        val lava = { level: Int -> Blocks.LAVA.defaultBlockState().setValue(BlockStateProperties.LEVEL, level) }
        val blocks = HashMap<BlockPos, NmsBlockState>()
        blocks[BlockPos(0, 65, 0)] = lava(0)
        blocks[BlockPos(1, 65, 0)] = lava(2)
        blocks[BlockPos(1, 64, 0)] = lava(10)
        blocks[BlockPos(2, 64, 0)] = lava(2)
        blocks[BlockPos(2, 63, 0)] = lava(0)
        val start = blockIn(blocks, BlockPos(2, 64, 0))

        assertEquals(listOf(0 to 65), sourcesOf(start) { true }.map { it.x to it.y })
        assertEquals(emptyList<Block>(), sourcesOf(start) { it.x > 0 })
        assertEquals(emptyList<Block>(), sourcesOf(blockIn(blocks, BlockPos(5, 64, 0))) { true })
    }

    // A bucket on a roof at x 0, its lava run east over the edge and along the ground into a pool of lava
    // somebody else poured, with water beside. Taking the bucket away drains its run; the pool, what the
    // pool feeds and the water stay, and nothing over the region border is touched.
    @Test
    fun `a source taken away drains what it ran into and no other source`() {
        val lava = { level: Int -> Blocks.LAVA.defaultBlockState().setValue(BlockStateProperties.LEVEL, level) }
        val world = HashMap<BlockPos, NmsBlockState>()
        world[BlockPos(0, 65, 0)] = lava(0)
        world[BlockPos(1, 65, 0)] = lava(2)
        world[BlockPos(1, 64, 0)] = lava(10)
        world[BlockPos(2, 64, 0)] = lava(2)
        world[BlockPos(3, 64, 0)] = lava(0)
        world[BlockPos(4, 64, 0)] = lava(2)
        world[BlockPos(1, 64, 1)] = Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 2)
        val stateAt = { at: BlockPos -> world[at] ?: Blocks.AIR.defaultBlockState() }

        val run = ranFrom(BlockPos(0, 65, 0), stateAt) { true }.map { (at, _) -> at.x to at.y }
        assertEquals(listOf(1 to 65, 1 to 64, 2 to 64), run)
        assertEquals(listOf(1 to 65, 1 to 64), ranFrom(BlockPos(0, 65, 0), stateAt) { it.x < 2 }.map { (at, _) -> at.x to at.y })
    }

    // A log with a branch of leaves running east from it, one leaf a player placed on top of it, and
    // a stretch past the game's reach. The leaves that would die without this log are the branch as far
    // as six steps, and the placed leaf stays because the game never decays it.
    @Test
    fun `a felled log holds up the leaves within the game's reach and no placed ones`() {
        val leaf = Blocks.OAK_LEAVES.defaultBlockState()
        val blocks = HashMap<BlockPos, NmsBlockState>()
        val log = BlockPos(0, 64, 0)
        blocks[log] = Blocks.OAK_LOG.defaultBlockState()
        for (x in 1..9) blocks[BlockPos(x, 64, 0)] = leaf
        blocks[BlockPos(0, 65, 0)] = leaf.setValue(BlockStateProperties.PERSISTENT, true)

        val held = leavesHeldBy(blockIn(blocks, log)) { true }.map { it.x to it.y }

        assertEquals((1..6).map { it to 64 }, held)

        // A leaf over the region border is not read at all, and what lies behind it is not reached.
        val owned = leavesHeldBy(blockIn(blocks, log)) { it.x < 4 }.map { it.x }
        assertEquals(listOf(1, 2, 3), owned)
    }

    @Test
    fun `a position without a block entity has no payload and one with it keeps the whole tag`() {
        assertNull(payloadOf(null, registries))

        val at = BlockPos(4, 65, -9)
        val payload = payloadOf(sign(at, "первая строка"), registries)!!
        val tag = NbtIo.read(DataInputStream(ByteArrayInputStream(payload)))
        val restored = BlockEntity.loadStatic(at, signState, tag, registries) as SignBlockEntity

        assertEquals("первая строка", restored.getText(SignTextSlot.FRONT).getMessages(false)[0].string)
        assertArrayEquals(payload, payloadOf(restored, registries))
        assertNotEquals(
            payload.toList(),
            payloadOf(sign(at, "другая строка"), registries)!!.toList(),
        )
    }
}
