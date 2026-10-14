package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.Vanilla
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(Vanilla::class)
class MobItemsTest {
    // A golem dies holding three copper ingots and its loot is one more: the one falls first and is the
    // loot, the three after it are the hand.
    @Test
    fun `a mob's own loot of the form it holds is not taken for what it held`() {
        assertEquals(false, heldDrop(need = 1, holding = 3, later = listOf(3)))
        assertEquals(true, heldDrop(need = 3, holding = 3, later = emptyList()))
        assertEquals(true, heldDrop(need = 1, holding = null, later = listOf(3)))
        assertEquals(true, heldDrop(need = 1, holding = 3, later = listOf(2)))
    }

    // Wolf armour one point from breaking breaks under a hit of one; a fresh piece does not under one
    // of three, and what has no durability never wears through.
    @Test
    fun `gear breaks on the wear that takes its last point`() {
        fun mirror(stack: ItemStack) = CraftItemStack.asBukkitMirror(stack)
        val armor = ItemStack(Items.WOLF_ARMOR)
        assertEquals(false, wornThrough(mirror(armor), 3))
        armor.damageValue = armor.maxDamage - 1
        assertEquals(true, wornThrough(mirror(armor), 1))
        assertEquals(false, wornThrough(mirror(ItemStack(Items.SADDLE)), 100))
    }

    // A boat is removed before it drops: its drop still finds the slot, once, and the rest is left to
    // be written off.
    @Test
    fun `a removed entity's drop takes its slot out of what it held when it went`() {
        val gone = mutableMapOf(16 to "oak_boat".toByteArray(), 0 to "chest".toByteArray())
        assertEquals(16, claimHeld(emptyMap(), gone, "oak_boat".toByteArray()))
        assertNull(claimHeld(emptyMap(), gone, "oak_boat".toByteArray()))
        assertEquals(listOf(0), gone.keys.toList())
        assertNull(claimHeld(emptyMap(), null, "oak_boat".toByteArray()))

        val boat = mutableMapOf(16 to "oak_boat".toByteArray())
        assertEquals(2, claimHeld(mapOf(2 to "oak_boat".toByteArray()), boat, "oak_boat".toByteArray()))
        assertEquals(listOf(16), boat.keys.toList())
    }
}
