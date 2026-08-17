package io.pfaumc.pfauprotect

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerPlayer
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
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Piston
import org.bukkit.block.data.type.PistonHead
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.type.TrapDoor
import org.bukkit.craftbukkit.block.CraftBlock
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
 * Whether `ServerPlayerGameMode.destroyBlock` is about to return without touching the world. Both
 * refusals sit behind the event, so a creative player who may not use game-master blocks punching a
 * command block would otherwise be journalled as turning it into air while it goes on standing.
 */
private fun brokenRefused(player: ServerPlayer, pos: BlockPos, state: NmsBlockState): Boolean {
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

class BlockCaptureListener(private val logs: BlockLogs) : Listener {
    // On MONITOR the block of a placement is already the new one, so the block answers for the after
    // side of the row while the event carries the side it replaced.
    //
    // Both sit on MONITOR with ignoreCancelled because region protection cancels at NORMAL or HIGH
    // and an action that was refused must not be journalled.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        // A second refusal that is not the cancelled flag and so is not covered by ignoreCancelled:
        // the item puts the old block back on either of the two. This is where vanilla spawn
        // protection lands, and where a plugin lands that said setBuild(false).
        if (!event.canBuild()) return
        val log = logs.get(event.block.world.uid) ?: return
        // A door, a bed and a double plant stand in two positions and arrive as one event. Both
        // positions are the same placement, so they go in one submit and share its event id.
        val replaced = (event as? BlockMultiPlaceEvent)?.replacedBlockStates ?: listOf(event.blockReplacedState)
        val actor = event.player.uniqueId
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
        // One submit for the whole break, so every position of it shares an event id and they are
        // found together.
        val changes = ArrayList<BlockChange>(3)
        brokenRow(block, state, player, actor)?.let { changes += it }
        // The other half is a direct neighbour, so the region ticking this block owns it too.
        partnerFace(state.asBlockData())?.let { face ->
            val partner = block.getRelative(face) as CraftBlock
            brokenRow(partner, partner.blockState, player, actor)?.let { changes += it }
        }
        singledChestHalf(block, state, actor)?.let { changes += it }
        log.submit(changes)
    }

    private fun brokenRow(
        block: CraftBlock,
        state: NmsBlockState,
        player: ServerPlayer,
        actor: UUID,
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
