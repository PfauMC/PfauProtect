package io.pfaumc.pfauprotect.rollback

import io.pfaumc.pfauprotect.command.LookupQuery
import io.pfaumc.pfauprotect.command.LookupTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class RollbackSlotTest {
    private var now = 1_000L
    private val slot = RollbackSlot { now }
    private val here = LookupTarget(UUID.randomUUID(), 0, 64, 0, "here")

    private fun started(key: String): Taking.Started {
        slot.preview(key, here, LookupQuery())
        return slot.take(key) as Taking.Started
    }

    @Test
    fun `a preview older than the wait is not applied`() {
        slot.preview("alice", here, LookupQuery())
        now += PENDING_MILLIS
        slot.preview("bob", here, LookupQuery())
        now += 1
        assertSame(Taking.NothingPending, slot.take("alice"))
        assertTrue(slot.take("bob") is Taking.Started)
    }

    @Test
    fun `a second apply while one runs is refused and its preview kept`() {
        val first = started("alice")
        slot.preview("bob", here, LookupQuery())
        assertSame(Taking.Busy, slot.take("bob"))

        slot.release(first.at)
        assertTrue(slot.take("bob") is Taking.Started, "the preview waited for the slot")
    }

    @Test
    fun `a run gone stale hands its slot on and its late release frees only its own`() {
        val lost = started("alice")
        now += STALE_MILLIS
        val next = started("bob")

        slot.release(lost.at)
        assertEquals(next.at, slot.runningSince())
        slot.release(next.at)
        assertNull(slot.runningSince())
    }

    @Test
    fun `a cancel while the rollback still reads holds until the next one starts`() {
        assertFalse(slot.stop(), "nothing runs to stop")
        val first = started("alice")
        assertTrue(slot.stop())
        assertTrue(slot.stopRequested)

        slot.release(first.at)
        started("bob")
        assertFalse(slot.stopRequested)
    }
}
