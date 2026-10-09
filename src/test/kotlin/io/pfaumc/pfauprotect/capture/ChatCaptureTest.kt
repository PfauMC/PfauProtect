package io.pfaumc.pfauprotect.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatCaptureTest {
    private val hidden = setOf("login", "register")

    // A password typed into a login is not kept for every moderator to read; the command itself is.
    @Test
    fun `a command that carries a password is kept by name only`() {
        assertEquals("/login", shown("/login hunter2", hidden))
        assertEquals("/authme:REGISTER", shown("/authme:REGISTER pw pw", hidden))
        assertEquals("/give Alice diamond 64", shown("/give Alice diamond 64", hidden))
    }
}
