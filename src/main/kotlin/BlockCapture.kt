package io.pfaumc.pfauprotect

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.tags.EnchantmentTags
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.item.ItemStack as NmsItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.CommandBlock
import net.minecraft.world.level.block.GameMasterBlock
import net.minecraft.world.level.block.IceBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState as NmsBlockState
import net.minecraft.world.level.block.state.properties.ChestType
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Leaves
import org.bukkit.block.data.type.Piston
import org.bukkit.block.data.type.PistonHead
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.type.TrapDoor
import org.bukkit.craftbukkit.block.CraftBlock
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

// The other position a block stands in. Only the position that was clicked is announced, and the
// block itself clears its other half with no event behind it, so a break that is not followed there
// leaves the newest row of that position naming a block which is no longer standing.
//
// Being bisected is not on its own the rule: a stair and a trapdoor carry the same top-or-bottom half
// while standing in one position alone.
internal fun partnerFace(data: BlockData): BlockFace? = when {
    data is Bed -> if (data.part == Bed.Part.HEAD) data.facing.oppositeFace else data.facing
    data is PistonHead -> data.facing.oppositeFace
    data is Piston -> if (data.isExtended) data.facing else null
    data is Stairs || data is TrapDoor -> null
    data is Bisected -> if (data.half == Bisected.Half.TOP) BlockFace.DOWN else BlockFace.UP
    else -> null
}

/**
 * The other half where one is really standing. The face is arithmetic off a single block, and a half
 * left alone — a door whose upper half a plugin took away, a tall plant half a regen left behind —
 * puts an unrelated block at the end of it. Only a block that names this position back is the half
 * that goes with it.
 */
internal fun otherHalfOf(block: Block): Block? {
    val face = partnerFace(block.blockData) ?: return null
    val partner = block.getRelative(face)
    return partner.takeIf { partnerFace(it.blockData) == face.oppositeFace }
}

/**
 * What a break leaves standing, from the server's own rule for it. `Level.removeBlock` writes the
 * fluid the block was standing in back over the position, so a waterlogged stair, kelp and seagrass
 * all leave a water source and a dry block leaves air. The same rule clears the other half of a door
 * or a bed, so a partner position is read the same way.
 *
 * Ice melts over that, and the condition below is `IceBlock.afterDestroy`'s own rather than a guess
 * about it. Only ice and frosted ice carry it; packed ice and blue ice do not. It runs after the
 * removal inside the same call, and only where the break also dropped the block, which a creative
 * break does not.
 */
internal fun brokenAfter(
    state: NmsBlockState,
    below: NmsBlockState,
    tool: NmsItemStack,
    dropsBlock: Boolean,
    waterEvaporates: Boolean,
): NmsBlockState {
    val removed = state.fluidState.createLegacyBlock()
    if (state.block !is IceBlock || !dropsBlock || waterEvaporates) return removed
    if (EnchantmentHelper.hasTag(tool, EnchantmentTags.PREVENTS_ICE_MELTING)) return removed
    return if (below.blocksMotion() || below.liquid()) IceBlock.meltsInto() else removed
}

/**
 * Whether `ServerPlayerGameMode.destroyBlock` is about to return without touching the world. All
 * three refusals sit behind the event, so a creative player who may not use game-master blocks
 * punching a command block would otherwise be journalled as turning it into air while it goes on
 * standing, and an adventure-mode player left-clicking a block he may not break would be handed
 * whatever gives way nearby in the next tick.
 */
private fun brokenRefused(player: ServerPlayer, pos: BlockPos, state: NmsBlockState): Boolean {
    // A plugin that emptied the position without cancelling has already had its say.
    if (state.isAir) return true
    val block = state.block
    if (block is GameMasterBlock &&
        !player.canUseGameMasterBlocks() &&
        !(block is CommandBlock && player.isCreative && player.bukkitEntity.hasPermission("minecraft.commandblock"))
    ) {
        return true
    }
    return player.blockActionRestricted(player.level(), pos, player.gameMode.gameModeForPlayer)
}

// The whole tag, byte for byte. An edit to a sign has to be reproducible from what was kept, and a
// display serialisation keeps the letters while losing the styling, the dye, the glow and everything
// a plugin left in the container underneath them.
internal fun payloadOf(entity: BlockEntity?, registries: HolderLookup.Provider): ByteArray? {
    val tag = entity?.saveWithFullMetadata(registries) ?: return null
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { NbtIo.write(tag, it) }
    return bytes.toByteArray()
}

private fun payloadAt(block: Block): ByteArray? {
    val level = (block as CraftBlock).level
    return payloadOf(level.getBlockEntity(block.position), level.registryAccess())
}

// What the removal writes back over the position, by the fluid rule `brokenAfter` states in full. The
// ice melt that rule also carries is left out: a note naming air where water ends up answers nobody,
// which is the direction to be wrong in.
private fun leftBy(data: BlockData) =
    (data as CraftBlockData).state.fluidState.createLegacyBlock().asBlockData().asString

/**
 * What every capture that takes a block away seeds, a hand as much as an explosion, a piston or water.
 * The removal note is what a block giving way in the next tick or two finds; the placement note over
 * the same position is overwritten in the same breath and names what the removal leaves rather than
 * what stood there, so that whoever put the old block there stops answering for the position and
 * whoever emptied it never answers for what moves into the hole.
 *
 * A block standing in two positions is cleared in both, since the other half goes with it under no
 * event of its own.
 */
internal fun Attribution.cleared(block: Block, actor: UUID) {
    for (standing in listOfNotNull(block, otherHalfOf(block))) {
        val at = positionOf(standing)
        removed(at, actor)
        placed(at, leftBy(standing.blockData), actor)
    }
    felledBy(block, actor)
}

// How far a leaf reaches for a log, counted in steps through other leaves. The game's own number.
private const val LEAF_REACH = 6

/**
 * The leaves a log may have been holding up: every leaf that would decay by itself, reachable from the
 * log within the game's reach, stepping through leaves only. Whether each still has another log in
 * reach is the game's to decide later; a note on a leaf that stays simply runs out.
 *
 * `owned` keeps the walk inside the region ticking this block. A leaf over the border belongs to
 * another thread, and it is left without a note rather than read from the wrong one.
 */
internal fun leavesHeldBy(log: Block, owned: (Block) -> Boolean = Bukkit::isOwnedByCurrentRegion): List<Block> {
    val found = LinkedHashSet<Block>()
    var edge = listOf(log)
    repeat(LEAF_REACH) {
        val next = ArrayList<Block>()
        for (block in edge) {
            for (face in LEAF_FACES) {
                val near = block.getRelative(face)
                if (near in found || !owned(near)) continue
                val leaves = near.blockData as? Leaves ?: continue
                if (leaves.isPersistent) continue
                found += near
                next += near
            }
        }
        edge = next
    }
    return found.toList()
}

private val LEAF_FACES = listOf(
    BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN,
)

/** Notes the leaves a log was holding up, when the block going is a log. */
internal fun Attribution.felledBy(block: Block, actor: UUID) {
    if ((block.blockData as CraftBlockData).state.`is`(BlockTags.LOGS)) {
        felled(leavesHeldBy(block).map(::positionOf), actor)
    }
}

// A door, a bed and a double plant stand in two positions and arrive as one event.
private fun replacedBy(event: BlockPlaceEvent) =
    (event as? BlockMultiPlaceEvent)?.replacedBlockStates ?: listOf(event.blockReplacedState)

/**
 * The attribution notes are seeded from the same handlers that write the rows, and behind the same
 * cancellation, which is the opposite of what an event needs whose observation outlives its own
 * refusal. Placing and breaking are not such events: a region protection plugin cancelling at NORMAL
 * or HIGH leaves the world exactly as it was, so there is nothing at that position to attribute to
 * anybody, and a note seeded ahead of the cancellation would spend its whole window offering the
 * refused player as the answer for whatever happens there next. Getting in early only earns its keep
 * where the thing being noted has already happened by the time the event can be refused.
 *
 * The server's own refusals come after it raises the event and are checked for the same reason: an
 * action that leaves the world as it was must neither be journalled nor lend its player to anything.
 */
class BlockCaptureListener(
    private val logs: BlockLogs,
    private val attribution: Attribution,
) : Listener {
    // On MONITOR the block of a placement is already the new one, so the block answers for the after
    // side of the row while the event carries the side it replaced.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        // A second refusal that is not the cancelled flag and so is not covered by ignoreCancelled:
        // the item puts the old block back on either of the two. This is where vanilla spawn
        // protection lands, and where a plugin lands that said setBuild(false).
        if (!event.canBuild()) return
        val log = logs.get(event.block.world.uid) ?: return
        // Both positions are the same placement, so they go in one submit and share its event id.
        val replaced = replacedBy(event)
        val actor = event.player.uniqueId
        // The block already stands where it was put, and it is that block the note answers for.
        for (was in replaced) attribution.placed(positionOf(was.block), was.block.blockData.asString, actor)
        log.submit(
            replaced.map { was ->
                val now = was.block
                BlockChange(
                    x = now.x,
                    y = now.y,
                    z = now.z,
                    before = was.blockData.asString,
                    after = now.blockData.asString,
                    cause = Cause.BLK_PLAYER_PLACE,
                    actor = actor,
                    payloadAfter = payloadAt(now),
                )
            }
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block as CraftBlock
        val log = logs.get(block.world.uid) ?: return
        val player = (event.player as CraftPlayer).handle
        val state = block.blockState
        if (brokenRefused(player, block.position, state)) return
        val actor = event.player.uniqueId
        attribution.cleared(block, actor)
        // One submit for the whole break, so every position of it shares an event id and they are
        // found together.
        val changes = ArrayList<BlockChange>(3)
        brokenRow(block, state, player, actor)?.let { changes += it }
        // The other half is a direct neighbour, so the region ticking this block owns it too.
        (otherHalfOf(block) as CraftBlock?)?.let { partner ->
            brokenRow(partner, partner.blockState, player, actor, alongside = true)?.let { changes += it }
        }
        singledChestHalf(block, state, actor)?.let { changes += it }
        log.submit(changes)
    }

    private fun brokenRow(
        block: CraftBlock,
        state: NmsBlockState,
        player: ServerPlayer,
        actor: UUID,
        alongside: Boolean = false,
    ): BlockChange? {
        val level = block.level
        val pos = block.position
        val after = brokenAfter(
            state = state,
            below = level.getBlockState(pos.below()),
            tool = player.mainHandItem,
            dropsBlock = !player.preventsBlockDrops() && player.hasCorrectToolForDrops(state),
            waterEvaporates = level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos),
        )
        val was = state.asBlockData().asString
        val now = after.asBlockData().asString
        // A plugin that set the position to air without cancelling has already had its say, and the
        // break the server is about to refuse over it leaves nothing more to file. The refusal reads
        // the position as air whichever of the three airs stands there, while the derived side only
        // ever names the plain one, so comparing the two strings is not enough on its own.
        if (now == was || state.isAir) return null
        return BlockChange(
            x = pos.x,
            y = pos.y,
            z = pos.z,
            before = was,
            after = now,
            cause = Cause.BLK_PLAYER_BREAK,
            alongside = alongside,
            actor = actor,
            // The block entity is only there while the event runs.
            payloadBefore = payloadOf(level.getBlockEntity(pos), level.registryAccess()),
        )
    }

    // Breaking one half of a double chest leaves the other standing but rewritten to `type=single`,
    // through a neighbour update that raises no event of its own, so without this row that position
    // keeps naming a chest that is no longer the one there.
    private fun singledChestHalf(block: CraftBlock, state: NmsBlockState, actor: UUID): BlockChange? {
        if (state.block !is ChestBlock || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null
        val pos = ChestBlock.getConnectedBlockPos(block.position, state)
        val other = block.level.getBlockState(pos)
        if (other.block !is ChestBlock || other.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null
        return BlockChange(
            x = pos.x,
            y = pos.y,
            z = pos.z,
            before = other.asBlockData().asString,
            after = other.setValue(ChestBlock.TYPE, ChestType.SINGLE).asBlockData().asString,
            cause = Cause.BLK_PLAYER_BREAK,
            actor = actor,
        )
    }
}
