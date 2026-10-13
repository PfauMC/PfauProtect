package io.pfaumc.pfauprotect.storage
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.Vanilla
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.item.enchantment.ItemEnchantments
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import net.minecraft.core.registries.Registries as VanillaRegistries

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(Vanilla::class)
class ItemFormsTest {
    private val registries = Registries(MemoryRegistryStore())
    private val codec = run {
        fillTypeRegistries(registries)
        ItemFormCodec(registries, ServerRegistries.access)
    }

    @Test
    fun `damage travels outside the form`() {
        val damaged = ItemStack(Items.DIAMOND_SWORD)
        damaged.set(DataComponents.DAMAGE, 17)

        val encoded = codec.encode(damaged)
        assertEquals(17, encoded.damage)
        assertArrayEquals(codec.encode(ItemStack(Items.DIAMOND_SWORD)).form, encoded.form)
        assertRoundTrip(damaged)
    }

    @Test
    fun `explicitly removed damage stays in the form`() {
        val stripped = ItemStack(Items.DIAMOND_SWORD)
        stripped.remove(DataComponents.DAMAGE)

        val encoded = codec.encode(stripped)
        assertNull(encoded.damage)
        assertFalse(encoded.form.contentEquals(codec.encode(ItemStack(Items.DIAMOND_SWORD)).form))
        assertRoundTrip(stripped)
    }

    // A compound is an open-addressing hash map, and "a" and "f" hash to the same slot at every table size
    // up to 16, so it walks them in the order they were put in. Keys that never collide would come out in
    // one order whatever was done to them.
    @Test
    fun `the order of keys inside a component does not reach the form bytes`() {
        fun pair(first: String, second: String) = CompoundTag().apply { putInt(first, first[0].code); putInt(second, second[0].code) }
        fun withData(first: String, second: String) = ItemStack(Items.DIAMOND).apply {
            val list = ListTag().apply { add(pair(first, second)) }
            set(DataComponents.CUSTOM_DATA, CustomData.of(pair(first, second).apply { put("list", list) }))
        }
        val one = withData("a", "f")
        val other = withData("f", "a")

        assertTrue(ItemStack.isSameItemSameComponents(one, other))
        assertArrayEquals(codec.encode(one).form, codec.encode(other).form)
    }

    // A form is interned by its bytes, so a change to them splits one item into two form ids across an
    // upgrade. The numbers are minted here in an order of our own, unlike the vanilla iteration order.
    @Test
    fun `a decorated sword keeps the form bytes it was interned with`() {
        val own = Registries(MemoryRegistryStore())
        own.idForKey(RegistryNamespace.ITEM_TYPE, "minecraft:stone")
        own.idForKey(RegistryNamespace.ITEM_TYPE, "minecraft:diamond_sword")
        own.idForKey(RegistryNamespace.DATA_COMPONENT_TYPE, "minecraft:custom_data")
        own.idForKey(RegistryNamespace.DATA_COMPONENT_TYPE, "minecraft:enchantments")
        own.idForKey(RegistryNamespace.DATA_COMPONENT_TYPE, "minecraft:custom_name")

        val ownCodec = ItemFormCodec(own, ServerRegistries.access)
        val encoded = ownCodec.encode(decoratedSword())

        assertEquals(5, encoded.damage)
        val pinned = byteArrayOf(
            0x01, 0x03, 0x00,
            // custom_data: {owner: "pfau", charges: 4}, in the order the compound's hash map walks them
            0x00, 30,
            0x0A,
            0x08, 0x00, 0x05, *"owner".bytes, 0x00, 0x04, *"pfau".bytes,
            0x03, 0x00, 0x07, *"charges".bytes, 0x00, 0x00, 0x00, 0x04,
            0x00,
            // enchantments: {"minecraft:sharpness": 3}
            0x01, 28,
            0x0A,
            0x03, 0x00, 19, *"minecraft:sharpness".bytes, 0x00, 0x00, 0x00, 0x03,
            0x00,
            // custom_name: the plain string
            0x02, 12,
            0x08, 0x00, 0x09, *"Kartoffel".bytes,
        )
        assertArrayEquals(pinned, encoded.form)
        assertArrayEquals(pinned, ownCodec.encode(decoratedSwordInAnotherOrder()).form, "the order components were set in")
        assertRoundTrip(decoratedSword())
    }

    // Minted so that the ids run against the names: sorted by name the two removals would swap.
    @Test
    fun `removed components keep the form bytes they were interned with`() {
        val own = Registries(MemoryRegistryStore())
        own.idForKey(RegistryNamespace.ITEM_TYPE, "minecraft:stone")
        own.idForKey(RegistryNamespace.ITEM_TYPE, "minecraft:diamond_sword")
        own.idForKey(RegistryNamespace.DATA_COMPONENT_TYPE, "minecraft:enchantments")
        own.idForKey(RegistryNamespace.DATA_COMPONENT_TYPE, "minecraft:damage")
        val ownCodec = ItemFormCodec(own, ServerRegistries.access)
        val stripped = ItemStack(Items.DIAMOND_SWORD).apply {
            remove(DataComponents.DAMAGE)
            remove(DataComponents.ENCHANTMENTS)
        }
        val strippedInAnotherOrder = ItemStack(Items.DIAMOND_SWORD).apply {
            remove(DataComponents.ENCHANTMENTS)
            remove(DataComponents.DAMAGE)
        }

        // The item, nothing added, two removed, then their ids in order.
        val pinned = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x01)
        assertArrayEquals(pinned, ownCodec.encode(stripped).form)
        assertArrayEquals(pinned, ownCodec.encode(strippedInAnotherOrder).form)
    }

    private val String.bytes: ByteArray get() = toByteArray(Charsets.US_ASCII)

    private fun decoratedSword() = ItemStack(Items.DIAMOND_SWORD).apply {
        set(DataComponents.CUSTOM_NAME, Component.literal("Kartoffel"))
        set(DataComponents.ENCHANTMENTS, sharpness(3))
        set(DataComponents.CUSTOM_DATA, CustomData.of(customData()))
        set(DataComponents.DAMAGE, 5)
    }

    private fun decoratedSwordInAnotherOrder() = ItemStack(Items.DIAMOND_SWORD).apply {
        set(DataComponents.CUSTOM_DATA, CustomData.of(customData()))
        set(DataComponents.DAMAGE, 9)
        set(DataComponents.ENCHANTMENTS, sharpness(3))
        set(DataComponents.CUSTOM_NAME, Component.literal("Kartoffel"))
    }

    // The unknown component goes first, so a decoder that misreads its length or stops at it loses what follows.
    @Test
    fun `unknown component is skipped by its length`() {
        val named = ItemStack(Items.DIAMOND).apply {
            set(DataComponents.CUSTOM_NAME, Component.literal("Kartoffel"))
            remove(DataComponents.RARITY)
        }
        val known = ByteReader(codec.encode(named).form)
        val item = known.varInt()
        val adds = known.varInt()
        val removes = known.varInt()
        val form = ByteWriter()
            .varInt(item)
            .varInt(adds + 1)
            .varInt(removes)
            .varInt(999_999)
            .varInt(3)
            .bytes(byteArrayOf(1, 2, 3))
            .bytes(known.bytes(known.remaining))
            .toByteArray()

        assertTrue(ItemStack.isSameItemSameComponents(named, codec.decode(form, 1, null)))
    }

    private fun assertRoundTrip(stack: ItemStack) {
        val encoded = codec.encode(stack)
        val decoded = codec.decode(encoded.form, encoded.count, encoded.damage)
        assertTrue(ItemStack.isSameItemSameComponents(stack, decoded)) {
            "expected ${stack.componentsPatch}, got ${decoded.componentsPatch}"
        }
        assertEquals(stack.count, decoded.count)
    }

    private fun sharpness(level: Int): ItemEnchantments {
        val holder = ServerRegistries.access.lookupOrThrow(VanillaRegistries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS)
        val enchantments = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
        enchantments.set(holder, level)
        return enchantments.toImmutable()
    }

    private fun customData(): CompoundTag {
        val tag = CompoundTag()
        tag.putString("owner", "pfau")
        tag.putInt("charges", 4)
        return tag
    }
}
