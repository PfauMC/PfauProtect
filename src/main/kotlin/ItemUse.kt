package io.pfaumc.pfauprotect

import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent
import io.papermc.paper.event.player.PlayerPickItemEvent
import io.papermc.paper.event.player.PlayerSwapWithEquipmentSlotEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BoneMealItem
import net.minecraft.world.item.BottleItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.BundleItem
import net.minecraft.world.item.EmptyMapItem
import net.minecraft.world.item.EnderEyeItem
import net.minecraft.world.item.EnderpearlItem
import net.minecraft.world.item.FireChargeItem
import net.minecraft.world.item.HoneycombItem
import net.minecraft.world.item.Items
import net.minecraft.world.item.MobBucketItem
import net.minecraft.world.item.ProjectileItem
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.item.WritableBookItem
import org.bukkit.Material
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.CauldronLevelChangeEvent
import org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketEntityEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import net.minecraft.world.item.ItemStack as NmsItemStack
import org.bukkit.inventory.ItemStack as BukkitItemStack

// What using an item on a block costs, when it costs anything. The use itself is applied by the game
// in the same tick as the click, so the pass a tick later sees the hand one short, and without a reason
// it books that as an item that simply went.
//
// Everything that has an event of its own is left to that event: a block put down is BLOCK_PLACE, food
// is eaten, a tool wears, a bucket fills, a projectile is launched. A reason left here as well would be
// the first to arrive and would take the loss those events were written for.
internal fun useCause(item: NmsItemStack, target: Material?): Cause? {
    val kind = item.item
    return when {
        kind is BoneMealItem -> if (target != null) Cause.BONEMEAL_USE else null
        kind is SpawnEggItem -> Cause.SPAWN_EGG_USE
        kind is HoneycombItem -> if (target != null) Cause.WAX_APPLY else null
        // Thrown when it is not going into a frame, and a throw has an event of its own.
        kind is EnderEyeItem -> if (target == Material.END_PORTAL_FRAME) Cause.EYE_INTO_FRAME else null
        target == Material.RESPAWN_ANCHOR && item.`is`(Items.GLOWSTONE) -> Cause.ITEM_INTO_SINGLE_BLOCK
        spentElsewhere(item) -> null
        else -> Cause.ITEM_USED
    }
}

// On an entity nothing is put down as a block, so seeds fed to a chicken are spent right here.
internal fun entityUseCause(item: NmsItemStack): Cause? {
    val kind = item.item
    return when {
        kind is SpawnEggItem -> Cause.SPAWN_EGG_USE
        item.isDamageableItem || kind is BucketItem || kind is MobBucketItem -> null
        else -> Cause.ITEM_USED
    }
}

private fun spentElsewhere(item: NmsItemStack): Boolean {
    val kind = item.item
    return kind is BlockItem || item.isDamageableItem || item.has(DataComponents.CONSUMABLE) ||
        item.has(DataComponents.EQUIPPABLE) || kind is BucketItem || kind is MobBucketItem ||
        (kind is ProjectileItem && kind !is FireChargeItem) || kind is EnderpearlItem ||
        kind is BottleItem || kind is EmptyMapItem || kind is BundleItem || kind is WritableBookItem
}

// A cauldron changes the item in hand into another one; it spends and hands back in the same slot.
internal fun cauldronCause(reason: ChangeReason): Cause? = when (reason) {
    ChangeReason.BUCKET_FILL -> Cause.BUCKET_FILL
    ChangeReason.BUCKET_EMPTY -> Cause.BUCKET_EMPTY
    ChangeReason.BOTTLE_FILL -> Cause.BOTTLE_FILL
    ChangeReason.BOTTLE_EMPTY -> Cause.BOTTLE_EMPTY
    ChangeReason.BANNER_WASH, ChangeReason.ARMOR_WASH, ChangeReason.SHULKER_WASH -> Cause.CAULDRON_WASH
    else -> null
}

// A changed item carries every reason on both of its sides: the empty bucket went and the full one came,
// and neither happened without the other.
private fun mutation(cause: Cause) = Intent(cause, shift = Shift(cause, cause, Kind.MUTATE))

class ItemUseListener(
    private val capture: ContainerCaptureListener,
    private val codec: ItemFormCodec,
) : Listener {
    // A right click in the air is raised already denied, so the item's own verdict is what says whether
    // the use went ahead — the same test the map uses.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onUse(event: PlayerInteractEvent) {
        if (!event.action.isRightClick || event.useItemInHand() == Event.Result.DENY) return
        val stack = event.item ?: return
        val live = CraftItemStack.asNMSCopy(stack)
        if (live.isEmpty) return
        val player = event.player
        // Water from a source or a hive, a bottle at a time, beside the stack the bottle came from.
        if (live.item is BottleItem) {
            capture.intend(player, mutation(Cause.BOTTLE_FILL))
            return
        }
        val cause = useCause(live, event.clickedBlock?.type) ?: return
        spend(player, event.hand, stack, cause)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUseOnEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        val stack = player.inventory.getItem(event.hand)
        val live = CraftItemStack.asNMSCopy(stack)
        if (live.isEmpty) return
        val cause = entityUseCause(live) ?: return
        spend(player, event.hand, stack, cause)
    }

    // A label and not an amount: whether anything was spent at all is up to the game, and a use that
    // cost nothing leaves no loss for it to explain.
    private fun spend(player: Player, hand: EquipmentSlot?, stack: BukkitItemStack, cause: Cause) {
        val form = codec.encodeOrNull(stack)?.form ?: return
        capture.intend(player, Intent(cause, to = Void, form = form, holder = capture.handSlot(player, hand)))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        val release = CraftItemStack.asNMSCopy(event.player.inventory.getItem(event.hand)).item is MobBucketItem
        capture.intend(event.player, mutation(if (release) Cause.BUCKET_RELEASE_MOB else Cause.BUCKET_EMPTY))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        capture.intend(event.player, mutation(Cause.BUCKET_FILL))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketEntity(event: PlayerBucketEntityEvent) {
        capture.intend(event.player, mutation(Cause.BUCKET_CAPTURE_MOB))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCauldron(event: CauldronLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        capture.intend(player, mutation(cauldronCause(event.reason) ?: return))
    }

    // The payment sits in the beacon's own slot, and the beacon eats it when the effect is confirmed.
    // A loss on a block's slot is otherwise taken for the block's own business and dropped, so the
    // payment has to be named as a transformation for the pass to keep it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBeacon(event: PlayerChangeBeaconEffectEvent) {
        if (!event.willConsumeItem()) return
        capture.intend(event.player, mutation(Cause.BEACON_PAYMENT))
    }

    // Both move an item between the player's own slots with no click to name them.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPick(event: PlayerPickItemEvent) {
        capture.intend(event.player, Intent(Cause.HOTBAR_SWAP))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSwapEquipment(event: PlayerSwapWithEquipmentSlotEvent) {
        capture.intend(event.player, Intent(Cause.EQUIP_ARMOR))
    }
}
