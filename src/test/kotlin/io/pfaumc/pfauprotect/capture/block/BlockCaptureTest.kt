package io.pfaumc.pfauprotect.capture.block
import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.worldStub
import io.pfaumc.pfauprotect.BothPlanes
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.attribution.Attribution
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.stub
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.item.ItemStack as NmsItemStack
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID

private const val AIR = "minecraft:air"

@ExtendWith(Vanilla::class)
class BlockCaptureTest : BothPlanes() {
    override val world: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")

    private val attribution by lazy { Attribution(shared.registries, logs) }

    private val signState = Blocks.OAK_SIGN.defaultBlockState()

    // A block that is not the server's own turns reading anything off it, its block entity aside, into a
    // failure rather than a quiet row, so a handler that must read nothing more is caught by the stub refusing.
    private fun blockStub(at: BlockPos, data: BlockData, relative: Block? = null): Block = stub(
        Block::class.java,
        mapOf(
            "getWorld" to worldStub(world),
            "getX" to at.x,
            "getY" to at.y,
            "getZ" to at.z,
            "getBlockData" to data,
            "getRelative" to relative,
        ),
    )

    private fun doorAt(foot: BlockPos): Block {
        val lower = Blocks.SPRUCE_DOOR.defaultBlockState()
        val head = blockStub(foot.above(), lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER).asBlockData())
        return blockStub(foot, lower.asBlockData(), relative = head)
    }

    private fun placement(at: BlockPos, canBuild: Boolean, inHand: Item = Items.OAK_SIGN): BlockPlaceEvent {
        val data = signState.asBlockData()
        val block = blockStub(at, data)
        return BlockPlaceEvent(
            block,
            stub(BlockState::class.java, mapOf("getBlock" to block, "getBlockData" to data)),
            block,
            CraftItemStack.asBukkitMirror(NmsItemStack(inHand)),
            stub(Player::class.java, mapOf("getUniqueId" to bob)),
            canBuild,
            EquipmentSlot.HAND,
        )
    }

    @Test
    fun `a placement the server would revert is not journalled`() {
        val at = BlockPos(9, 70, 9)

        BlockCaptureListener(logs, attribution).onPlace(placement(at, canBuild = false))
        log.drain()

        assertTrue(rowsAt(at).isEmpty())
        // A refusal leaves the position as it was, so it must lend its player to nothing there either.
        assertNull(attribution.builderNear(WorldBlock(world, at.x, at.y, at.z), 0))
    }

    private fun rowsAt(at: BlockPos) = log.at(at.x, at.y, at.z, 0, Long.MAX_VALUE, limit = 100, reverse = false)

    // The server raises a placement for a hoe tilling or a bucket pouring too, and each is filed under
    // what the hand did. Powder snow is a block its bucket puts down, and still a bucket's.
    @ParameterizedTest
    @CsvSource("POWDER_SNOW_BUCKET, BLK_BUCKET", "OAK_SIGN, BLK_PLAYER_PLACE", "WOODEN_HOE, BLK_PLAYER_USE")
    fun `a placement is filed under what the item in hand does`(item: String, cause: Cause) {
        val at = BlockPos(9, 70, 9)

        BlockCaptureListener(logs, attribution).onPlace(placement(at, canBuild = true, inHand = Items::class.java.getField(item).get(null) as Item))
        log.drain()

        assertEquals(listOf(Triple(cause, bob, Confidence.FACT)), rowsAt(at).map { Triple(it.cause, it.actor, it.confidence) })
    }

    // A capture that filed the change since the click has the row already; a second one would be a
    // second change.
    @Test
    fun `a placement over a position already filed since its touch writes nothing`() {
        val at = BlockPos(9, 70, 9)
        val touches = HandTouches()
        touches.touch(WorldBlock(world, at.x, at.y, at.z))
        touches.filed(world, listOf(BlockChange(at.x, at.y, at.z, AIR, "minecraft:farmland[moisture=0]", Cause.BLK_PLAYER_USE, now, actor = bob)))

        BlockCaptureListener(logs, attribution, touches).onPlace(placement(at, canBuild = true, inHand = Items.WOODEN_HOE))
        log.drain()

        assertTrue(rowsAt(at).isEmpty())
    }

    @Test
    fun `a break notes the removal in both positions the block stood in and clears the placement note`() {
        val foot = BlockPos(5, 64, 5)
        val door = doorAt(foot)
        val standing = WorldBlock(world, foot.x, foot.y, foot.z)
        attribution.placed(standing, door.blockData.asString, alice)

        attribution.cleared(door, bob)

        assertNull(attribution.placerAt(standing, door.blockData.asString))
        assertEquals(bob, attribution.placerAt(standing, AIR)?.actor)
        assertEquals(bob, attribution.supportRemoverAt(WorldBlock(world, 5, 63, 5))?.actor)
        // The other half goes with it under no event of its own, two cells away from the first note.
        assertEquals(bob, attribution.supportRemoverAt(WorldBlock(world, 5, 66, 5))?.actor)
    }

    // Bob's blast took the ground from under Alice's door. The lower half gives way, and the upper half
    // goes in the same tick, before any read-back of the lower one has run.
    @Test
    fun `a block giving way is noted at once so what it holds up finds its culprit`() {
        val foot = BlockPos(5, 64, 5)
        val door = doorAt(foot)
        attribution.placed(WorldBlock(world, foot.x, foot.y, foot.z), door.blockData.asString, alice)

        attribution.givingWay(door, bob)

        assertEquals(bob, attribution.supportRemoverAt(WorldBlock(world, 5, 65, 5))?.actor)
        assertEquals(bob, attribution.supportRemoverAt(WorldBlock(world, 5, 66, 5))?.actor)
        assertEquals(alice, attribution.placerAt(WorldBlock(world, foot.x, foot.y, foot.z), door.blockData.asString)?.actor)
    }
}
