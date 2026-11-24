package io.pfaumc.pfauprotect.importer

import net.minecraft.world.item.ItemStack
import org.bukkit.configuration.serialization.ConfigurationSerialization
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.util.io.BukkitObjectInputStream
import java.io.ByteArrayInputStream
import java.io.ObjectInputFilter

// Java serialisation reads whatever classes the bytes name, so only what CoreProtect writes is let through.
private val ALLOWED: ObjectInputFilter = ObjectInputFilter.Config.createFilter(
    "maxdepth=32;maxbytes=1048576;java.lang.*;java.util.*;com.google.common.collect.*;org.bukkit.**;!*",
)

/**
 * What a CoreProtect container row's metadata says of the item on top of its material: its `ItemMeta`, the first map
 * CoreProtect wrote (`ItemMetaHandler`), which holds the name, the lore, the enchantments, the damage. What CoreProtect
 * split off into lists of their own (a leather colour, a banner's patterns, a potion's custom effects) is not read.
 * Needs a running server: Bukkit makes the meta.
 */
// ponytail: the base meta only; read CoreProtect's extra lists if a moderator misses a banner's patterns.
internal fun coreProtectMeta(metadata: ByteArray, stack: ItemStack): ItemStack {
    val lists = BukkitObjectInputStream(ByteArrayInputStream(metadata)).use { input ->
        input.setObjectInputFilter(ALLOWED)
        input.readObject() as? List<*>
    } ?: return stack
    // An item with no meta of its own has only CoreProtect's own notes there: a stand's slot, a frame's facing.
    @Suppress("UNCHECKED_CAST")
    val fields = ((lists.firstOrNull() as? List<*>)?.firstOrNull() as? Map<String, Any?>)?.takeIf { "meta-type" in it } ?: return stack
    val meta = ConfigurationSerialization.deserializeObject(fields, ConfigurationSerialization.getClassByAlias("ItemMeta")!!) as? ItemMeta
        ?: return stack
    val bukkit = CraftItemStack.asBukkitCopy(stack)
    bukkit.itemMeta = meta
    return CraftItemStack.asNMSCopy(bukkit)
}
