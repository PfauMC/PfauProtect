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

    private fun shown(key: String) = slot.ready(key, slot.preview(key, here, LookupQuery()))

    private fun started(key: String): Taking.Started {
        shown(key)
        return slot.take(key) as Taking.Started
    }

    @Test
    fun `a preview older than the wait is not applied`() {
        shown("alice")
        now += PENDING_MILLIS
        shown("bob")
        now += 1
        assertSame(Taking.NothingPending, slot.take("alice"))
        assertTrue(slot.take("bob") is Taking.Started)
    }

    // A wide window reads for minutes; the wait for apply starts once it is shown.
    @Test
    fun `a preview read longer than the wait gets the whole wait once ready`() {
        val asked = slot.preview("alice", here, LookupQuery())
        now += PENDING_MILLIS + 60_000
        assertEquals(now, slot.ready("alice", asked))
        now += PENDING_MILLIS
        assertEquals(now - PENDING_MILLIS, slot.pendingAt("alice"))
        assertTrue(slot.take("alice") is Taking.Started)
    }

    @Test
    fun `a preview still reading is not applied and stays`() {
        val asked = slot.preview("alice", here, LookupQuery())
        assertSame(Taking.Reading, slot.take("alice"))
        assertNull(slot.pendingAt("alice"))
        slot.ready("alice", asked)
        assertTrue(slot.take("alice") is Taking.Started)
    }

    @Test
    fun `a preview refused while it read leaves nothing to apply, but not a newer one's`() {
        slot.failed("alice", slot.preview("alice", here, LookupQuery()))
        assertSame(Taking.NothingPending, slot.take("alice"))

        val older = slot.preview("bob", here, LookupQuery())
        slot.preview("bob", here, LookupQuery())
        slot.failed("bob", older)
        assertSame(Taking.Reading, slot.take("bob"))
    }

    @Test
    fun `a preview dropped or replaced while it read does not come back when ready`() {
        val dropped = slot.preview("alice", here, LookupQuery())
        slot.drop("alice")
        assertNull(slot.ready("alice", dropped))
        assertNull(slot.pendingAt("alice"))

        val older = slot.preview("bob", here, LookupQuery())
        val newer = slot.preview("bob", here, LookupQuery())
        assertNull(slot.ready("bob", older))
        assertEquals(now, slot.ready("bob", newer))
    }

    @Test
    fun `a second apply while one runs is refused and its preview kept`() {
        val first = started("alice")
        shown("bob")
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
