package io.pfaumc.pfauprotect.rollback

import io.pfaumc.pfauprotect.Places
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.command.LookupQuery
import io.pfaumc.pfauprotect.command.LookupTarget
import io.pfaumc.pfauprotect.command.parseLookupQuery
import io.pfaumc.pfauprotect.stub
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.UUID

class PreviewAskTest {
    // Rollbacks.kt's statics register a ticket type: run first in a fresh JVM, an unbooted registry breaks every
    // later test of that JVM.
    init {
        ServerRegistries.access
    }

    private val world = UUID(0, 5)

    @AfterEach
    fun reset() {
        Settings.hideCoordinates = false
    }

    private fun sender(permitted: Boolean): CommandSender =
        stub(CommandSender::class.java, mapOf("hasPermission" to { _: Array<out Any?> -> permitted }))

    @Test
    fun `a rollback heading names a token for a sender who may not read coordinates`() {
        Places.useKey(ByteArray(32) { it.toByte() })
        Settings.hideCoordinates = true
        val there = LookupTarget(world, 123, 64, -456, "123 64 -456")
        val (query, target) = previewAsk(sender(false), there, parseLookupQuery("r:5"))
        assertEquals(true, query.hide)
        assertEquals(Places.encode(world, 123, 64, -456), target.label)
        assertFalse(target.label.contains("123"))

        val (reader, plain) = previewAsk(sender(true), there, LookupQuery())
        assertEquals(false, reader.hide)
        assertEquals("123 64 -456", plain.label)
    }
}
