package io.pfaumc.pfauprotect

import com.mojang.serialization.Codec
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.bukkit.craftbukkit.inventory.CraftItemStack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.bukkit.inventory.ItemStack as BukkitItemStack

class ItemKey(val form: ByteArray, val damage: Int?) {
    override fun equals(other: Any?): Boolean =
        other is ItemKey && damage == other.damage && form.contentEquals(other.form)

    override fun hashCode(): Int = 31 * form.contentHashCode() + (damage?.hashCode() ?: 0)
}

// Identity without the wear: a pickaxe is the same pickaxe at 1523 and at 1524.
class FormKey(val form: ByteArray) {
    override fun equals(other: Any?): Boolean = other is FormKey && form.contentEquals(other.form)
    override fun hashCode(): Int = form.contentHashCode()
}

data class EncodedItem(val form: ByteArray, val count: Int, val damage: Int?) {
    val key: ItemKey get() = ItemKey(form, damage)
}

// The item type number leads every form, so a caller that only wants to name an item never has to
// decode the components behind it.
fun itemTypeIdOf(form: ByteArray): Int = ByteReader(form).varInt()

// Interning every vanilla name up front keeps the numbers the same across servers of one version,
// which is what lets a ledger be read on a machine other than the one that wrote it.
internal fun fillTypeRegistries(registries: Registries) {
    for (key in BuiltInRegistries.ITEM.keySet()) {
        registries.idForKey(RegistryNamespace.ITEM_TYPE, key.toString())
    }
    for (key in BuiltInRegistries.DATA_COMPONENT_TYPE.keySet()) {
        registries.idForKey(RegistryNamespace.DATA_COMPONENT_TYPE, key.toString())
    }
}

class ItemFormCodec(
    private val registries: Registries,
    private val registryAccess: RegistryAccess,
) {
    private val registryOps by lazy { registryAccess.createSerializationContext(NbtOps.INSTANCE) }

    // Encoding only reads, so the handle behind a CraftItemStack is taken as it stands: asNMSCopy
    // deep-copies the component map, and this runs once per slot on every inventory pass.
    fun encodeOrNull(stack: BukkitItemStack?): EncodedItem? {
        if (stack == null) return null
        val nms = (stack as? CraftItemStack)?.handle ?: CraftItemStack.asNMSCopy(stack)
        return if (nms.isEmpty) null else encode(nms)
    }

    // What a container holds is never part of what it is: those items are rows of their own, filed
    // under the container's name wherever it travels, so a box that changes hands reads as the same
    // box whether it is full or empty.
    fun encode(stack: ItemStack): EncodedItem {
        val patch = stack.componentsPatch
        var damage: Int? = null
        val drop = ArrayList<DataComponentType<*>>(NestedItems.NESTING_COMPONENTS.size + 1)
        for (entry in patch.entrySet()) {
            // Clearing an absent value would also drop an explicit-removal marker, a third state.
            val value = entry.value.orElse(null) ?: continue
            if (entry.key === DataComponents.DAMAGE) damage = value as Int
            if (entry.key === DataComponents.DAMAGE || entry.key in NestedItems.NESTING_COMPONENTS) {
                drop += entry.key
            }
        }
        val formPatch = if (drop.isEmpty()) {
            patch
        } else {
            DataComponentPatch.builder().apply {
                copy(patch)
                for (type in drop) clear(type)
            }.build()
        }
        return EncodedItem(form(stack.item, formPatch), stack.count, damage)
    }

    fun decode(form: ByteArray, count: Int, damage: Int?): ItemStack {
        val r = ByteReader(form)
        val item = itemHolder(r.varInt())
        val nAdd = r.varInt()
        val nRemove = r.varInt()
        val builder = DataComponentPatch.builder()
        repeat(nAdd) {
            val type = componentTypeOf(r.varInt())
            val value = r.bytes(r.varInt())
            val codec = type?.codec()
            if (codec != null) setDecoded(builder, type, codec, value)
        }
        repeat(nRemove) {
            componentTypeOf(r.varInt())?.let { builder.remove(it) }
        }
        if (damage != null) builder.set(DataComponents.DAMAGE, damage)
        return ItemStack(item, count, builder.build())
    }

    private fun form(item: Item, patch: DataComponentPatch): ByteArray {
        val add = ArrayList<Pair<Int, ByteArray>>(patch.size())
        val remove = ArrayList<Int>()
        for (entry in patch.entrySet()) {
            val codec = entry.key.codec() ?: continue
            val id = componentTypeId(entry.key)
            val value = entry.value
            if (value.isPresent) add += id to encodeValue(codec, value.get()) else remove += id
        }
        // Patch iteration follows insertion order, so identical items would otherwise differ in bytes.
        add.sortBy { it.first }
        remove.sort()

        val w = ByteWriter(32)
        w.varInt(itemTypeId(item))
        w.varInt(add.size)
        w.varInt(remove.size)
        for ((id, value) in add) w.varInt(id).varInt(value.size).bytes(value)
        for (id in remove) w.varInt(id)
        return w.toByteArray()
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeValue(codec: Codec<*>, value: Any): ByteArray {
        val tag = (codec as Codec<Any>).encodeStart(registryOps, value).getOrThrow()
        val out = ByteArrayOutputStream()
        NbtIo.writeAnyTag(canonical(tag), DataOutputStream(out))
        return out.toByteArray()
    }

    // A compound writes its keys in hash map iteration order, which depends on the order they were
    // inserted, so two equal items would otherwise reach different bytes and different form ids.
    private fun canonical(tag: Tag): Tag = when (tag) {
        is CompoundTag -> CompoundTag().also { out ->
            for (key in tag.keySet().sorted()) out.put(key, canonical(tag.get(key)!!))
        }

        is ListTag -> ListTag().also { out -> tag.forEach { out.add(canonical(it)) } }
        else -> tag
    }

    @Suppress("UNCHECKED_CAST")
    private fun setDecoded(
        builder: DataComponentPatch.Builder,
        type: DataComponentType<*>,
        codec: Codec<*>,
        value: ByteArray,
    ) {
        val tag = NbtIo.readAnyTag(DataInputStream(ByteArrayInputStream(value)), NbtAccounter.defaultQuota())
        builder.set(type as DataComponentType<Any>, (codec as Codec<Any>).parse(registryOps, tag).getOrThrow())
    }

    private fun itemTypeId(item: Item): Int =
        registries.idForKey(RegistryNamespace.ITEM_TYPE, BuiltInRegistries.ITEM.getKey(item).toString())

    private fun componentTypeId(type: DataComponentType<*>): Int {
        val key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)
            ?: throw IllegalArgumentException("component type $type is not registered")
        return registries.idForKey(RegistryNamespace.DATA_COMPONENT_TYPE, key.toString())
    }

    // Registry.getValue would answer air for an unregistered key, get keeps the miss visible.
    private fun itemHolder(itemTypeId: Int): Holder<Item> {
        val key = registries.keyOf(RegistryNamespace.ITEM_TYPE, itemTypeId)
            ?: throw IllegalArgumentException("unknown item type number $itemTypeId")
        return BuiltInRegistries.ITEM.get(Identifier.parse(key))
            .orElseThrow { IllegalArgumentException("item type $key is not registered") }
    }

    private fun componentTypeOf(componentTypeId: Int): DataComponentType<*>? =
        registries.keyOf(RegistryNamespace.DATA_COMPONENT_TYPE, componentTypeId)
            ?.let { BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(it)) }
}
