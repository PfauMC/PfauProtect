package io.pfaumc.pfauprotect

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtIo
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.item.ItemStack as NmsItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.state.BlockState as NmsBlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.Half
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.UUID

private const val SOURCE_WATER = "minecraft:water[level=0]"
private const val AIR = "minecraft:air"

class BlockCaptureTest {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val bob = UUID.fromString("00000000-0000-4000-8000-0000000000b0")

    private lateinit var registries: RegistryAccess
    private lateinit var shared: RocksItemLog
    private lateinit var logs: BlockLogs
    private lateinit var log: BlockLog

    // A getter and not a field: touching `Blocks` before the bootstrap in `open` throws out of the
    // class initializer, and a field is initialized before it.
    private val signState get() = Blocks.OAK_SIGN.defaultBlockState()

    @BeforeEach
    fun open(@TempDir tempDir: Path) {
        registries = ServerRegistries.access
        shared = RocksItemLog(tempDir.resolve("items"))
        logs = BlockLogs(tempDir.resolve("blocks"), shared)
        log = logs.open(world)
    }

    @AfterEach
    fun closeAll() {
        logs.close()
        shared.close()
    }

    private fun stateOf(blockData: String): Int? =
        shared.registries.lookupKey(RegistryNamespace.BLOCK_STATE, blockData)

    private fun sign(at: BlockPos, line: String): SignBlockEntity =
        SignBlockEntity(at, signState).apply { setText(frontText.setMessage(0, Component.literal(line)), true) }

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

    private fun <T : Any> stub(type: Class<T>, answers: Map<String, Any?> = emptyMap()): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            answers[method.name]
        } as T
    }

    // A placement the listener turns away is one it reads nothing else off, and a block that is not
    // the server's own is what turns reading anything off it into a failure rather than a quiet row.
    private fun placement(at: BlockPos, canBuild: Boolean): BlockPlaceEvent {
        val data = signState.asBlockData()
        val block = stub(
            Block::class.java,
            mapOf(
                "getWorld" to stub(World::class.java, mapOf("getUID" to world)),
                "getX" to at.x,
                "getY" to at.y,
                "getZ" to at.z,
                "getBlockData" to data,
            ),
        )
        return BlockPlaceEvent(
            block,
            stub(BlockState::class.java, mapOf("getBlock" to block, "getBlockData" to data)),
            block,
            CraftItemStack.asCraftMirror(NmsItemStack(Items.OAK_SIGN)),
            stub(Player::class.java, mapOf("getUniqueId" to bob)),
            canBuild,
            EquipmentSlot.HAND,
        )
    }

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
        // Neither of these carries a waterlogged property, and both stand in a water source all the
        // same.
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

    @Test
    fun `a position without a block entity has no payload and one with it keeps the whole tag`() {
        assertNull(payloadOf(null, registries))

        val at = BlockPos(4, 65, -9)
        val payload = payloadOf(sign(at, "первая строка"), registries)!!
        val tag = NbtIo.read(DataInputStream(ByteArrayInputStream(payload)))
        val restored = BlockEntity.loadStatic(at, signState, tag, registries) as SignBlockEntity

        assertEquals("первая строка", restored.frontText.getMessage(0, false).string)
        assertArrayEquals(payload, payloadOf(restored, registries))
        assertNotEquals(
            payload.toList(),
            payloadOf(sign(at, "другая строка"), registries)!!.toList(),
        )
    }

    @Test
    fun `the rows a placement and a break submit read back with both sides, the cause, the actor and the payload`() {
        val at = BlockPos(12, 70, 34)
        val placed = payloadOf(sign(at, "поставил"), registries)!!
        val edited = payloadOf(sign(at, "отредактировал"), registries)!!
        val standing = signState.asBlockData().asString

        log.submit(
            listOf(
                BlockChange(
                    x = at.x,
                    y = at.y,
                    z = at.z,
                    before = "minecraft:air",
                    after = standing,
                    cause = Cause.BLK_PLAYER_PLACE,
                    actor = bob,
                    payloadAfter = placed,
                )
            )
        )
        log.submit(
            listOf(
                BlockChange(
                    x = at.x,
                    y = at.y,
                    z = at.z,
                    before = standing,
                    after = "minecraft:air",
                    cause = Cause.BLK_PLAYER_BREAK,
                    actor = bob,
                    payloadBefore = edited,
                )
            )
        )
        log.drain()

        val rows = log.at(at.x, at.y, at.z)
        assertEquals(2, rows.size)
        assertEquals(listOf(Cause.BLK_PLAYER_PLACE, Cause.BLK_PLAYER_BREAK), rows.map { it.cause })
        assertEquals(listOf(Confidence.FACT, Confidence.FACT), rows.map { it.confidence })
        assertEquals(listOf(bob, bob), rows.map { it.actor })
        assertEquals(
            listOf(stateOf("minecraft:air"), stateOf(standing)),
            rows.map { it.stateBefore },
        )
        assertEquals(
            listOf(stateOf(standing), stateOf("minecraft:air")),
            rows.map { it.stateAfter },
        )

        assertEquals(shared.payloadId(placed), rows[0].payloadAfter)
        assertNull(rows[0].payloadBefore)
        assertEquals(shared.payloadId(edited), rows[1].payloadBefore)
        assertNull(rows[1].payloadAfter)
        assertArrayEquals(placed, shared.payload(rows[0].payloadAfter!!))
        assertArrayEquals(edited, shared.payload(rows[1].payloadBefore!!))
        assertNotEquals(rows[0].payloadAfter, rows[1].payloadBefore)
    }

    @Test
    fun `a change that only learned one side is refused rather than filed against air`() {
        log.submit(
            listOf(
                BlockChange(
                    x = 1,
                    y = 65,
                    z = 1,
                    before = "minecraft:air",
                    after = null,
                    cause = Cause.BLK_PLAYER_PLACE,
                    actor = bob,
                ),
                BlockChange(
                    x = 2,
                    y = 65,
                    z = 1,
                    before = null,
                    after = "minecraft:air",
                    cause = Cause.BLK_PLAYER_BREAK,
                    actor = bob,
                ),
            )
        )
        log.drain()

        assertTrue(log.at(1, 65, 1).isEmpty())
        assertTrue(log.at(2, 65, 1).isEmpty())
    }

    @Test
    fun `a placement the server would revert is not journalled`() {
        val at = BlockPos(9, 70, 9)

        BlockCaptureListener(logs).onPlace(placement(at, canBuild = false))
        log.drain()

        assertTrue(log.at(at.x, at.y, at.z).isEmpty())
        // The other refusal, which the listener never sees and so can only be read off its registration.
        val handler = BlockCaptureListener::class.java
            .getMethod("onPlace", BlockPlaceEvent::class.java)
            .getAnnotation(EventHandler::class.java)
        assertTrue(handler.ignoreCancelled)
        assertEquals(EventPriority.MONITOR, handler.priority)
    }
}
