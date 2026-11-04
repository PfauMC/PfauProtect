package io.pfaumc.pfauprotect.api

import io.pfaumc.pfauprotect.alice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccessTest {
    @Test
    fun `a node is granted by itself or a wildcard over it`() {
        assertTrue(grants(setOf("pfauprotect.lookup"), "pfauprotect.lookup"))
        assertTrue(grants(setOf("pfauprotect.*"), "pfauprotect.lookup"))
        assertTrue(grants(setOf("*"), "pfauprotect.rollback"))
        assertFalse(grants(setOf("pfauprotect.lookup"), "pfauprotect.rollback"))
        assertFalse(grants(setOf("pfauprotect.look"), "pfauprotect.lookup"))
        assertFalse(grants(setOf("pfauprotect.lookup.*"), "pfauprotect.lookup"))
    }

    // A gateway that sends the header empty found no permission at all; nobody else is asked after it.
    @Test
    fun `what a gateway vouched for an offline player is the whole answer`() {
        assertEquals(false, allowed(Access(alice, emptySet()), "pfauprotect.lookup") { null })
        assertEquals(true, allowed(Access(alice, setOf("pfauprotect.lookup")), "pfauprotect.lookup") { null })
        assertEquals(false, allowed(Access(alice, setOf("pfauprotect.lookup")), "pfauprotect.rollback") { null })
    }
}
