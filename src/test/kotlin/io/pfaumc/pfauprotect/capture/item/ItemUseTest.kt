package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.model.Void
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import org.bukkit.Material
import org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason
import org.bukkit.event.entity.EntityRemoveEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import net.minecraft.world.item.ItemStack as NmsItemStack

// Which reason a use leaves, and that a reason is left only where no other event already speaks for
// the loss: a reason here would arrive first and take the loss away from the event written for it.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemUseTest {
    private val player = UUID.fromString("00000000-0000-4000-8000-0000000000e1")

    @BeforeAll
    fun loadServerRegistries() {
        ServerRegistries.access
    }

    private fun stack(item: Item) = NmsItemStack(item)

    @Test
    fun `a use on a block is named by what was used and on what`() {
        assertEquals(Cause.BONEMEAL_USE, useCause(stack(Items.BONE_MEAL), Material.OAK_SAPLING))
        assertEquals(Cause.ITEM_INTO_SINGLE_BLOCK, useCause(stack(Items.GLOWSTONE), Material.RESPAWN_ANCHOR))
        assertEquals(Cause.ITEM_INTO_SINGLE_BLOCK, useCause(stack(Items.CANDLE), Material.CAKE))
        // A cushion becomes an entity, and its placement names where it went.
        assertEquals(null, useCause(stack(Items.CUSHION.white()), Material.GRASS_BLOCK))
        assertEquals(Cause.EYE_INTO_FRAME, useCause(stack(Items.ENDER_EYE), Material.END_PORTAL_FRAME))
        assertEquals(Cause.WAX_APPLY, useCause(stack(Items.HONEYCOMB), Material.COPPER_BLOCK))
        assertEquals(Cause.SPAWN_EGG_USE, useCause(stack(Items.PIG_SPAWN_EGG), Material.GRASS_BLOCK))
        assertEquals(Cause.ITEM_USED, useCause(stack(Items.FIRE_CHARGE), Material.NETHERRACK))
        // No launch event speaks for either of these.
        assertEquals(Cause.THROWN_CONSUMED, useCause(stack(Items.ENDER_EYE), null))
        assertEquals(Cause.FIREWORK_LAUNCH, useCause(stack(Items.FIREWORK_ROCKET), Material.STONE))
    }

    // A booked arrow ends where it can no longer be picked up; saved with its chunk or taken back into
    // an inventory, it has not ended at all.
    @Test
    fun `a projectile ends only where it can no longer be picked up`() {
        assertEquals(Cause.PROJ_DESPAWN, projectileEnd(EntityRemoveEvent.Cause.DESPAWN))
        assertEquals(Cause.PROJ_HIT_VOID, projectileEnd(EntityRemoveEvent.Cause.HIT))
        assertEquals(Cause.PROJ_HIT_VOID, projectileEnd(EntityRemoveEvent.Cause.OUT_OF_WORLD))
        assertNull(projectileEnd(EntityRemoveEvent.Cause.PICKUP))
        assertNull(projectileEnd(EntityRemoveEvent.Cause.UNLOAD))
        assertNull(projectileEnd(EntityRemoveEvent.Cause.DROP))
    }

    @Test
    fun `a use another event speaks for leaves no reason of its own`() {
        // Put down as a block: BLOCK_PLACE.
        assertNull(useCause(stack(Items.GLOWSTONE), Material.STONE))
        assertNull(useCause(stack(Items.COBBLESTONE), Material.STONE))
        // Eaten, worn down, emptied, launched.
        assertNull(useCause(stack(Items.APPLE), null))
        assertNull(useCause(stack(Items.DIAMOND_SWORD), Material.STONE))
        assertNull(useCause(stack(Items.WATER_BUCKET), Material.STONE))
        assertNull(useCause(stack(Items.SNOWBALL), null))
        assertNull(useCause(stack(Items.ENDER_PEARL), null))
        assertNull(useCause(stack(Items.FIREWORK_ROCKET), null))
        // Nothing to spend it on.
        assertNull(useCause(stack(Items.BONE_MEAL), null))
    }

    @Test
    fun `an entity names the use by what it makes of the item`() {
        assertEquals(Cause.FEED_MOB, entityUseCause(stack(Items.WHEAT), EntityTarget(breedsOn = true)))
        assertEquals(Cause.TAME_MOB, entityUseCause(stack(Items.BONE), EntityTarget(untamed = true)))
        assertEquals(Cause.DYE_MOB, entityUseCause(stack(Items.DYE.pick(DyeColor.RED)), EntityTarget(dyeable = true)))
        assertEquals(Cause.NAME_TAG, entityUseCause(stack(Items.NAME_TAG)))
        assertEquals(Cause.LEASH_ATTACH, entityUseCause(stack(Items.LEAD)))
        // Worn or kept by the mob: its equipment change says where it went.
        assertNull(entityUseCause(stack(Items.SADDLE)))
        assertNull(entityUseCause(stack(Items.DIAMOND), EntityTarget(keeps = true)))
        // A lead or a name tag on an allay is spent, not handed over.
        assertEquals(Cause.LEASH_ATTACH, entityUseCause(stack(Items.LEAD), EntityTarget(keeps = true)))
        assertEquals(Cause.NAME_TAG, entityUseCause(stack(Items.NAME_TAG), EntityTarget(keeps = true)))
    }

    // A record put in a jukebox is a gain of the slot; one taken back out, a loss. A slot that went on
    // holding the same thing moved nothing.
    @Test
    fun `a click on a block with slots is what its slots gained and lost`() {
        val disc = Stack(ItemKey("music_disc_cat".toByteArray(), null), 1)
        val book = Stack(ItemKey("book".toByteArray(), null), 1)
        val put = slotDiff(listOf(null, book), listOf(disc, book)).single()
        assertEquals(0, put.slot)
        assertEquals(true, put.gain)
        val taken = slotDiff(listOf(disc), listOf(null)).single()
        assertEquals(false, taken.gain)
        assertEquals(1, taken.qty)
    }

    @Test
    fun `a block that keeps what is put into it has its own reckoning`() {
        assertEquals(true, keepsItems(Material.JUKEBOX))
        assertEquals(true, keepsItems(Material.CHISELED_BOOKSHELF))
        assertEquals(true, keepsItems(Material.OAK_SHELF))
        assertEquals(false, keepsItems(Material.CHEST))
    }

    @Test
    fun `on an entity nothing is built, so seeds are spent where they are fed`() {
        assertEquals(Cause.ITEM_USED, entityUseCause(stack(Items.WHEAT_SEEDS)))
        assertEquals(Cause.ITEM_USED, entityUseCause(stack(Items.FIRE_CHARGE)))
        assertEquals(Cause.SPAWN_EGG_USE, entityUseCause(stack(Items.PIG_SPAWN_EGG)))
        assertNull(entityUseCause(stack(Items.SHEARS)))
        assertNull(entityUseCause(stack(Items.WATER_BUCKET)))
    }

    @Test
    fun `a cauldron names the change it made to the item in hand`() {
        assertEquals(Cause.BOTTLE_FILL, cauldronCause(ChangeReason.BOTTLE_FILL))
        assertEquals(Cause.BOTTLE_EMPTY, cauldronCause(ChangeReason.BOTTLE_EMPTY))
        assertEquals(Cause.BUCKET_FILL, cauldronCause(ChangeReason.BUCKET_FILL))
        assertEquals(Cause.CAULDRON_WASH, cauldronCause(ChangeReason.ARMOR_WASH))
        assertNull(cauldronCause(ChangeReason.EVAPORATE))
    }

    // The fire charge leaves the hand and a stone leaves another slot in the same pass. The use speaks
    // for the hand only; the stone stays what nothing explained.
    @Test
    fun `a use explains the loss from the hand and nothing else`() {
        val charge = ItemKey("fire_charge".toByteArray(), null)
        val stone = ItemKey("stone".toByteArray(), null)
        val hand = PlayerInv(player, 0)
        val edges = listOf(
            Edge(hand, Void, charge, 1, Confidence.INFERRED),
            Edge(PlayerInv(player, 5), Void, stone, 3, Confidence.INFERRED),
        )
        val used = Intent(Cause.ITEM_USED, to = Void, form = charge.form, holder = hand)

        val moves = Intents.explain(edges, listOf(used), player)

        assertEquals(listOf(Cause.ITEM_USED, Cause.ITEM_VANISHED), moves.map { it.cause })
        assertEquals(Confidence.FACT, moves[0].confidence)
    }
}
