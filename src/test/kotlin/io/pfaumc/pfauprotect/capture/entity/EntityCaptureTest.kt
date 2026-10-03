package io.pfaumc.pfauprotect.capture.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class EntityCaptureTest {
    private fun cow(x: Double, health: Float, name: String?, ticks: Int): ByteArray {
        val tag = CompoundTag()
        tag.put("Pos", ListTag().apply { add(DoubleTag.valueOf(x)); add(DoubleTag.valueOf(64.0)); add(DoubleTag.valueOf(0.0)) })
        tag.putFloat("Health", health)
        tag.putInt("Spigot.ticksLived", ticks)
        if (name != null) tag.putString("CustomName", name)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { NbtIo.write(tag, it) }
        return bytes.toByteArray()
    }

    // A cow that walked a step, healed and lived another tick is the cow it was. A cow somebody named is not.
    @Test
    fun `only what a hand can change counts as a change`() {
        val before = cow(1.0, 5f, null, 100)
        assertFalse(changedBetween(before, cow(1.7, 10f, null, 101)))
        assertTrue(changedBetween(before, cow(1.0, 5f, "Burenka", 100)))
    }
}
