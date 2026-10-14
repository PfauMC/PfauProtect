package io.pfaumc.pfauprotect.capture.item
import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.model.Cause
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Items
import org.bukkit.Material
import org.bukkit.event.entity.EntityRemoveEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import net.minecraft.world.item.ItemStack as NmsItemStack

// Which reason a use leaves, and that a reason is left only where no other event already speaks for
// the loss: a reason here would arrive first and take the loss away from the event written for it.
@ExtendWith(Vanilla::class)
class ItemUseTest {

    // A carrot plants carrots and redstone lays wire: blocks under another name, and placements all the
    // same. Powder snow's bucket puts a block down too, which is why the bucket is asked about first.
    // What only changes a block, or pours a fluid out, puts nothing down.
    @Test
    fun `an item that plants or lays a block under another name puts a block down`() {
        val down = listOf(
            Material.CARROT, Material.WHEAT_SEEDS, Material.REDSTONE, Material.STRING,
            Material.SWEET_BERRIES, Material.COCOA_BEANS, Material.OAK_SIGN, Material.POWDER_SNOW_BUCKET,
        )
        for (type in down) assertTrue(placesBlock(type), "$type")
        for (type in listOf(Material.HONEYCOMB, Material.ENDER_EYE, Material.FLINT_AND_STEEL, Material.WATER_BUCKET)) {
            assertFalse(placesBlock(type), "$type")
        }
    }

    @Test
    fun `a use on a block is named by what was used and on what`() {
        assertEquals(Cause.BONEMEAL_USE, useCause(NmsItemStack(Items.BONE_MEAL), Material.OAK_SAPLING))
        assertEquals(Cause.ITEM_INTO_SINGLE_BLOCK, useCause(NmsItemStack(Items.GLOWSTONE), Material.RESPAWN_ANCHOR))
        assertEquals(Cause.ITEM_INTO_SINGLE_BLOCK, useCause(NmsItemStack(Items.CANDLE), Material.CAKE))
        // A cushion becomes an entity, and its placement names where it went.
        assertEquals(null, useCause(NmsItemStack(Items.CUSHION.white()), Material.GRASS_BLOCK))
        assertEquals(Cause.EYE_INTO_FRAME, useCause(NmsItemStack(Items.ENDER_EYE), Material.END_PORTAL_FRAME))
        assertEquals(Cause.WAX_APPLY, useCause(NmsItemStack(Items.HONEYCOMB), Material.COPPER_BLOCK))
        assertEquals(Cause.SPAWN_EGG_USE, useCause(NmsItemStack(Items.PIG_SPAWN_EGG), Material.GRASS_BLOCK))
        assertEquals(Cause.ITEM_USED, useCause(NmsItemStack(Items.FIRE_CHARGE), Material.NETHERRACK))
        // No launch event speaks for either of these.
        assertEquals(Cause.THROWN_CONSUMED, useCause(NmsItemStack(Items.ENDER_EYE), null))
        assertEquals(Cause.FIREWORK_LAUNCH, useCause(NmsItemStack(Items.FIREWORK_ROCKET), Material.STONE))
    }

    // A booked arrow ends where it can no longer be picked up; saved with its chunk or taken back into
    // an inventory, it has not ended at all.
    @Test
    fun `a projectile ends only where it can no longer be picked up`() {
        assertEquals(Cause.PROJ_DESPAWN, projectileEnd(EntityRemoveEvent.Cause.DESPAWN))
        assertEquals(Cause.PROJ_HIT_VOID, projectileEnd(EntityRemoveEvent.Cause.HIT))
        assertEquals(Cause.PROJ_HIT_VOID, projectileEnd(EntityRemoveEvent.Cause.OUT_OF_WORLD))
        assertEquals(
            setOf(EntityRemoveEvent.Cause.PICKUP, EntityRemoveEvent.Cause.DROP, EntityRemoveEvent.Cause.UNLOAD, EntityRemoveEvent.Cause.PLAYER_QUIT),
            EntityRemoveEvent.Cause.entries.filter { projectileEnd(it) == null }.toSet(),
        )
    }

    @Test
    fun `a use another event speaks for leaves no reason of its own`() {
        // Put down as a block: BLOCK_PLACE.
        assertNull(useCause(NmsItemStack(Items.GLOWSTONE), Material.STONE))
        assertNull(useCause(NmsItemStack(Items.COBBLESTONE), Material.STONE))
        // Eaten, worn down, emptied, launched.
        assertNull(useCause(NmsItemStack(Items.APPLE), null))
        assertNull(useCause(NmsItemStack(Items.DIAMOND_SWORD), Material.STONE))
        assertNull(useCause(NmsItemStack(Items.WATER_BUCKET), Material.STONE))
        assertNull(useCause(NmsItemStack(Items.SNOWBALL), null))
        assertNull(useCause(NmsItemStack(Items.ENDER_PEARL), null))
        assertNull(useCause(NmsItemStack(Items.FIREWORK_ROCKET), null))
        // Nothing to spend it on.
        assertNull(useCause(NmsItemStack(Items.BONE_MEAL), null))
    }

    @Test
    fun `an entity names the use by what it makes of the item`() {
        assertEquals(Cause.FEED_MOB, entityUseCause(NmsItemStack(Items.WHEAT), EntityTarget(breedsOn = true)))
        assertEquals(Cause.TAME_MOB, entityUseCause(NmsItemStack(Items.BONE), EntityTarget(untamed = true)))
        assertEquals(Cause.DYE_MOB, entityUseCause(NmsItemStack(Items.DYE.pick(DyeColor.RED)), EntityTarget(dyeable = true)))
        assertEquals(Cause.NAME_TAG, entityUseCause(NmsItemStack(Items.NAME_TAG)))
        assertEquals(Cause.LEASH_ATTACH, entityUseCause(NmsItemStack(Items.LEAD)))
        // Worn or kept by the mob: its equipment change says where it went.
        assertNull(entityUseCause(NmsItemStack(Items.SADDLE)))
        assertNull(entityUseCause(NmsItemStack(Items.DIAMOND), EntityTarget(keeps = true)))
        // A lead or a name tag on an allay is spent, not handed over.
        assertEquals(Cause.LEASH_ATTACH, entityUseCause(NmsItemStack(Items.LEAD), EntityTarget(keeps = true)))
        assertEquals(Cause.NAME_TAG, entityUseCause(NmsItemStack(Items.NAME_TAG), EntityTarget(keeps = true)))
    }

    @Test
    fun `a block that keeps what is put into it has its own reckoning`() {
        assertEquals(true, keepsItems(Material.OAK_SHELF))
        assertEquals(true, keepsItems(Material.JUKEBOX))
        assertEquals(true, keepsItems(Material.CHISELED_BOOKSHELF))
        assertEquals(false, keepsItems(Material.CHEST))
    }

    @Test
    fun `on an entity nothing is built, so seeds are spent where they are fed`() {
        assertEquals(Cause.ITEM_USED, entityUseCause(NmsItemStack(Items.WHEAT_SEEDS)))
        assertEquals(Cause.ITEM_USED, entityUseCause(NmsItemStack(Items.FIRE_CHARGE)))
        assertEquals(Cause.SPAWN_EGG_USE, entityUseCause(NmsItemStack(Items.PIG_SPAWN_EGG)))
        assertNull(entityUseCause(NmsItemStack(Items.SHEARS)))
        assertNull(entityUseCause(NmsItemStack(Items.WATER_BUCKET)))
    }
}
