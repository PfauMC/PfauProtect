package io.pfaumc.pfauprotect.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InspectorTest {
    @Test
    fun `a click picks its place and part by button, mode and block`() {
        fun pick(left: Boolean, spectator: Boolean, kind: HitKind) = choose(left, spectator, kind)
        val chest = HitKind.CONTAINER
        assertEquals(Choice(false, Part.BLOCK), pick(true, false, chest))
        assertEquals(Choice(false, Part.BLOCK), pick(true, true, chest))
        assertEquals(Choice(false, Part.ALL), pick(true, false, HitKind.OTHER))
        assertEquals(Choice(false, Part.CONTENTS), pick(false, false, chest))
        assertEquals(Choice(false, Part.ALL), pick(false, true, chest))
        assertEquals(Choice(false, Part.ALL), pick(false, false, HitKind.SIGN))
        assertEquals(Choice(true, Part.ALL), pick(false, false, HitKind.OTHER))
        assertEquals(Choice(false, Part.ALL), pick(false, true, HitKind.OTHER))
    }
}
