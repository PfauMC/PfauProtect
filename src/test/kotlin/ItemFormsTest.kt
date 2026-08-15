package io.pfaumc.pfauprotect

import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import net.minecraft.core.registries.Registries as VanillaRegistries

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemFormsTest {
    private val registries = Registries(MemoryRegistryStore())

    private lateinit var registryAccess: RegistryAccess
    private lateinit var codec: ItemFormCodec

    @BeforeAll
    fun loadServerRegistries() {
        registryAccess = ServerRegistries.access
        for (key in BuiltInRegistries.ITEM.keySet()) {
            registries.idForKey(RegistryNamespace.ITEM_TYPE, key.toString())
        }
        for (key in BuiltInRegistries.DATA_COMPONENT_TYPE.keySet()) {
            registries.idForKey(RegistryNamespace.DATA_COMPONENT_TYPE, key.toString())
        }
        codec = ItemFormCodec(registries, registryAccess)
    }

    @Test
    fun `stack without components round trips`() {
        assertRoundTrip(ItemStack(Items.DIAMOND, 3))
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

    @Test
    fun `two removed components reach the form in the same order either way`() {
        val stripped = ItemStack(Items.DIAMOND_SWORD).apply {
            remove(DataComponents.DAMAGE)
            remove(DataComponents.ENCHANTMENTS)
        }
        val strippedInAnotherOrder = ItemStack(Items.DIAMOND_SWORD).apply {
            remove(DataComponents.ENCHANTMENTS)
            remove(DataComponents.DAMAGE)
        }

        val form = codec.encode(stripped).form
        val reader = ByteReader(form)
        reader.varInt()
        assertEquals(0, reader.varInt(), "expected no added components")
        assertEquals(2, reader.varInt(), "expected both removals to reach the form")
        assertArrayEquals(form, codec.encode(strippedInAnotherOrder).form)
    }

    @Test
    fun `the order of keys inside a component does not reach the form bytes`() {
        val one = ItemStack(Items.DIAMOND)
        one.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putInt("a", 1); putInt("f", 2) }))
        val other = ItemStack(Items.DIAMOND)
        other.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putInt("f", 2); putInt("a", 1) }))

        assertTrue(ItemStack.isSameItemSameComponents(one, other))
        assertArrayEquals(codec.encode(one).form, codec.encode(other).form)
    }

    @Test
    fun `custom name round trips`() {
        val named = ItemStack(Items.DIAMOND_SWORD)
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Kartoffel"))
        assertRoundTrip(named)
    }

    @Test
    fun `enchantments round trip`() {
        val enchanted = ItemStack(Items.DIAMOND_SWORD)
        enchanted.set(DataComponents.ENCHANTMENTS, sharpness(3))
        assertRoundTrip(enchanted)
    }

    @Test
    fun `custom data round trips`() {
        val tagged = ItemStack(Items.DIAMOND_SWORD)
        tagged.set(DataComponents.CUSTOM_DATA, CustomData.of(customData()))
        assertRoundTrip(tagged)
    }

    @Test
    fun `stack larger than the vanilla codec range round trips`() {
        val big = ItemStack(Items.DIAMOND, 500)
        assertEquals(500, codec.encode(big).count)
        assertRoundTrip(big)
    }

    @Test
    fun `component order does not reach the form bytes`() {
        val sword = decoratedSword()
        assertArrayEquals(codec.encode(sword).form, codec.encode(decoratedSwordInAnotherOrder()).form)
        assertRoundTrip(sword)
    }

    @Test
    fun `items differing only in component order share one form id across a reopen`(@TempDir dir: Path) {
        val holder = PlayerInv(UUID.fromString("00000000-0000-4000-8000-0000000000f1"), 0)
        val interned = RocksItemLog(dir).use { log ->
            store(log, holder, decoratedSword(), 1L)
            store(log, holder, decoratedSwordInAnotherOrder(), 2L)
            val ids = log.holderEntries(holder, 0, Long.MAX_VALUE).map { it.itemFormId }
            assertEquals(2, ids.size)
            assertEquals(1, ids.distinct().size)
            ids.first()
        }
        RocksItemLog(dir).use { log ->
            store(log, holder, decoratedSword(), 3L)
            assertEquals(interned, log.holderEntries(holder, 0, Long.MAX_VALUE).last().itemFormId)
            assertArrayEquals(codec.encode(decoratedSword()).form, log.form(interned))
        }
    }

    private fun store(log: RocksItemLog, holder: Holder, stack: ItemStack, timestamp: Long) {
        val encoded = codec.encode(stack)
        log.submit(Transfer(Cause.PICKUP, Void, holder, encoded.form, encoded.damage, encoded.count, timestamp))
        log.drain()
    }

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

    @Test
    fun `unknown component is skipped by its length`() {
        val form = ByteWriter()
            .varInt(registries.idForKey(RegistryNamespace.ITEM_TYPE, "minecraft:diamond"))
            .varInt(1)
            .varInt(0)
            .varInt(999_999)
            .varInt(3)
            .bytes(byteArrayOf(1, 2, 3))
            .toByteArray()

        assertTrue(ItemStack.isSameItemSameComponents(ItemStack(Items.DIAMOND), codec.decode(form, 1, null)))
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
        val holder = registryAccess.lookupOrThrow(VanillaRegistries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS)
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
