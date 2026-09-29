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
import org.bukkit.block.data.BlockData
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")

    private lateinit var registries: RegistryAccess
    private lateinit var shared: RocksItemLog
    private lateinit var logs: BlockLogs
    private lateinit var log: BlockLog
    private lateinit var attribution: Attribution

    // A getter and not a field: touching `Blocks` before the bootstrap in `open` throws out of the
    // class initializer, and a field is initialized before it.
    private val signState get() = Blocks.OAK_SIGN.defaultBlockState()

    @BeforeEach
    fun open(@TempDir tempDir: Path) {
        registries = ServerRegistries.access
        shared = RocksItemLog(tempDir.resolve("items"))
        logs = BlockLogs(tempDir.resolve("blocks"), shared)
        log = logs.open(world)
        attribution = Attribution(shared.registries, logs)
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

    // A block that is not the server's own is what turns reading anything off it into a failure rather
    // than a quiet row, so a handler that must read nothing more is caught by the stub refusing.
    private fun blockStub(at: BlockPos, data: BlockData, relative: Block? = null): Block = stub(
        Block::class.java,
        mapOf(
            "getWorld" to stub(World::class.java, mapOf("getUID" to world)),
            "getX" to at.x,
            "getY" to at.y,
            "getZ" to at.z,
            "getBlockData" to data,
            "getRelative" to relative,
        ),
    )

    private fun player(uuid: UUID) = stub(Player::class.java, mapOf("getUniqueId" to uuid))

    private fun placement(at: BlockPos, canBuild: Boolean): BlockPlaceEvent {
        val data = signState.asBlockData()
        val block = blockStub(at, data)
        return BlockPlaceEvent(
            block,
            stub(BlockState::class.java, mapOf("getBlock" to block, "getBlockData" to data)),
            block,
            CraftItemStack.asCraftMirror(NmsItemStack(Items.OAK_SIGN)),
            player(bob),
            canBuild,
            EquipmentSlot.HAND,
        )
    }

    private fun handlerOf(name: String, event: Class<*>) = BlockCaptureListener::class.java
        .getMethod(name, event)
        .getAnnotation(EventHandler::class.java)

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

    // A block in a world made of a map: whatever is not in it is air, and a neighbour is asked for by
    // position like `CraftBlock` does, so two routes to one position meet as one block.
    private fun blockIn(blocks: Map<BlockPos, NmsBlockState>, at: BlockPos): Block =
        Proxy.newProxyInstance(Block::class.java.classLoader, arrayOf(Block::class.java)) { _, method, args ->
            when (method.name) {
                "getBlockData" -> (blocks[at] ?: Blocks.AIR.defaultBlockState()).asBlockData()
                "getRelative" -> (args[0] as BlockFace).let {
                    blockIn(blocks, at.offset(it.modX, it.modY, it.modZ))
                }
                "getX" -> at.x
                "getY" -> at.y
                "getZ" -> at.z
                "equals" -> (args[0] as? Block)?.let { it.x == at.x && it.y == at.y && it.z == at.z } == true
                "hashCode" -> at.hashCode()
                else -> null
            }
        } as Block

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

        BlockCaptureListener(logs, attribution).onPlace(placement(at, canBuild = false))
        log.drain()

        assertTrue(log.at(at.x, at.y, at.z).isEmpty())
        // A refusal leaves the position as it was, so it must lend its player to nothing there either.
        assertTrue(attribution.isEmpty)
    }

    // A protection plugin cancels at NORMAL or HIGH, and both halves of this capture sit behind that
    // cancellation on purpose: a break or a placement it refused changed nothing, so there is neither
    // a row to write nor a position to attribute to anybody. Registered earlier, the notes would spend
    // their whole window offering the refused player as the answer for whatever happens there next.
    @Test
    fun `both handlers run behind the cancellation`() {
        for (handler in listOf(
            handlerOf("onPlace", BlockPlaceEvent::class.java),
            handlerOf("onBreak", BlockBreakEvent::class.java),
        )) {
            assertEquals(EventPriority.MONITOR, handler.priority)
            assertTrue(handler.ignoreCancelled)
        }
    }

    @Test
    fun `a break notes the removal in both positions the block stood in and clears the placement note`() {
        val lower = Blocks.SPRUCE_DOOR.defaultBlockState()
        val door = lower.asBlockData()
        val foot = BlockPos(5, 64, 5)
        val standing = WorldBlock(world, foot.x, foot.y, foot.z)
        attribution.placed(standing, door.asString, alice)

        val head = blockStub(
            BlockPos(5, 65, 5),
            lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER).asBlockData(),
        )
        attribution.cleared(blockStub(foot, door, relative = head), bob)

        // The position holds whatever the break left, so the player who put the door there is no
        // longer the one who answers for it and the one who took it away answers for air alone.
        assertNull(attribution.placerAt(standing, door.asString))
        assertEquals(bob, attribution.placerAt(standing, AIR)?.actor)
        assertEquals(bob, attribution.supportRemoverAt(WorldBlock(world, 5, 63, 5))?.actor)
        // The other half goes with it under no event of its own, two cells away from the first note.
        assertEquals(bob, attribution.supportRemoverAt(WorldBlock(world, 5, 66, 5))?.actor)
    }

    // Alice's TNT stands above a lone lower half of tall grass. The face is right and the block at the
    // end of it is not the other half of anything, so nothing may be noted there.
    @Test
    fun `a half standing alone lends nothing to the block on its computed face`() {
        val grass = Blocks.TALL_GRASS.defaultBlockState().asBlockData()
        val tnt = Blocks.TNT.defaultBlockState().asBlockData()
        val above = WorldBlock(world, 5, 65, 5)
        attribution.placed(above, tnt.asString, alice)

        attribution.cleared(blockStub(BlockPos(5, 64, 5), grass, relative = blockStub(BlockPos(5, 65, 5), tnt)), bob)

        assertEquals(alice, attribution.placerAt(above, tnt.asString)?.actor)
        assertNull(attribution.supportRemoverAt(WorldBlock(world, 5, 66, 5)))
        // The break itself is still noted where it happened.
        assertEquals(bob, attribution.supportRemoverAt(WorldBlock(world, 5, 63, 5))?.actor)
    }

}
