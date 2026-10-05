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
    private fun cow(x: Double, health: Float, name: String?, ticks: Int, resting: Boolean = false, touched: Boolean = false): ByteArray {
        val tag = CompoundTag()
        if (touched) tag.put("BukkitValues", CompoundTag().apply { putByte("pfauprotect:touched", 1) })
        tag.putBoolean("EatingHaystack", resting)
        tag.putBoolean("Sitting", resting)
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
        // A horse stops grazing when it is mounted; that is the horse, not the hand. A pet sits down
        // only for its owner.
        assertFalse(changedBetween(before, cow(1.0, 5f, null, 100, resting = true)))
        assertTrue(changedBetween(before, cow(1.0, 5f, "Burenka", 100)))
        // The plugin's own mark of a hand is not the hand's change.
        assertFalse(changedBetween(before, cow(1.0, 5f, null, 100, touched = true)))
    }

    // A death nobody stands behind: a mob somebody had a hand in always, the place's own mob unless it is
    // one of a farm's crowd, a zombie the night spawned never.
    @Test
    fun `a death with nobody behind it is kept for a mob somebody owns`() {
        assertTrue(worthRecording(touched = true, keepsItsPlace = false, sameKindInChunk = 50))
        assertTrue(worthRecording(touched = false, keepsItsPlace = true, sameKindInChunk = 1))
        assertFalse(worthRecording(touched = false, keepsItsPlace = true, sameKindInChunk = CROWD))
        assertFalse(worthRecording(touched = false, keepsItsPlace = false, sameKindInChunk = 1))
    }
}
