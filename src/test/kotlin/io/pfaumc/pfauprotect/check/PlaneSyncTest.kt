package io.pfaumc.pfauprotect.check
import io.pfaumc.pfauprotect.AIR
import io.pfaumc.pfauprotect.STONE
import io.pfaumc.pfauprotect.BothPlanes
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.BlockCodec
import io.pfaumc.pfauprotect.storage.BlockRow
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.storage.EntryCodec
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.rawPut
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

private const val WATER = "minecraft:water[level=0]"
private const val WATERLOGGED = "minecraft:oak_slab[type=bottom,waterlogged=true]"

class PlaneSyncTest : BothPlanes() {
    private val unopened = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val stoneForm = byteArrayOf(1, 2, 3)

    private lateinit var sync: PlaneSync

    override fun openPlanes() {
        super.openPlanes()
        sync = PlaneSync(shared, logs)
    }

    // The store only ever clamps a stored time forward, so a row written here can never be older than
    // one written before it: within a test the calls have to run oldest first.
    private fun blockRow(x: Int, y: Int, z: Int, before: String, after: String, ts: Long = longAgo) {
        val cause = if (after == AIR || after == WATER) Cause.BLK_PLAYER_BREAK else Cause.BLK_PLAYER_PLACE
        log.submit(listOf(BlockChange(x, y, z, before, after, cause, ts, actor = alice)))
    }

    private fun standAndFall(x: Int, y: Int, z: Int, ts: Long = longAgo) {
        blockRow(x, y, z, AIR, STONE, ts)
        blockRow(x, y, z, STONE, AIR, ts)
    }

    @Test
    fun `a position the block plane calls air while the item plane still holds an item is reported`() {
        heldAt(1, 64, 1, stoneForm)
        standAndFall(1, 64, 1)
        drainBoth()

        val report = sync.pass(100, now)

        assertEquals(1, report.checked)
        assertEquals(0, report.settling)
        assertEquals(0, report.unreadable)
        assertTrue(report.reachedEnd)
        val gap = report.gaps.single()
        assertEquals(WorldBlock(world, 1, 64, 1), gap.at)
        assertEquals(AIR, gap.standing)
        assertEquals(1, gap.fact)
        assertEquals(0, gap.inferred)
    }

    @Test
    fun `a waterlogged block broken leaves water standing and is reported`() {
        heldAt(3, 64, 3, stoneForm)
        blockRow(3, 64, 3, AIR, WATERLOGGED)
        blockRow(3, 64, 3, WATERLOGGED, WATER)
        drainBoth()

        val gap = sync.pass(100, now).gaps.single()

        assertEquals(WorldBlock(world, 3, 64, 3), gap.at)
        assertEquals(WATER, gap.standing)
        assertEquals(1, gap.fact)
    }

    @Test
    fun `a position the block plane never recorded is counted apart from a position compared`() {
        heldAt(4, 64, 4, stoneForm)
        heldAt(4, 64, 4, stoneForm)
        drainBoth()

        val report = assertPlanesAgree(now)

        assertEquals(1, report.unrecorded)
        assertEquals(0, report.checked)
        assertEquals(0, report.unreadable)
    }

    @Test
    fun `a position that gave up what it never took is counted apart rather than called a holding`() {
        releasedAt(9, 64, 9, stoneForm)
        standAndFall(9, 64, 9)
        drainBoth()

        val report = assertPlanesAgree(now)

        assertEquals(1, report.overdrawn)
        assertEquals(0, report.checked)
        assertEquals(0, report.unrecorded)
    }

    @Test
    fun `a block that stood before the block plane saw it is not overdrawn when it goes`() {
        releasedAt(9, 64, 9, stoneForm)
        blockRow(9, 64, 9, STONE, AIR)
        drainBoth()

        val report = assertPlanesAgree(now)

        assertEquals(0, report.overdrawn)
        assertEquals(1, report.unrecorded)
    }

    @Test
    fun `a confirmed holding written off by a guess is a disagreement and not a settled position`() {
        heldAt(10, 64, 10, stoneForm)
        releasedAt(10, 64, 10, stoneForm, confidence = Confidence.INFERRED)
        standAndFall(10, 64, 10)
        drainBoth()

        val report = sync.pass(100, now)

        assertEquals(0, report.overdrawn)
        val gap = report.gaps.single()
        assertEquals(WorldBlock(world, 10, 64, 10), gap.at)
        assertEquals(1, gap.fact)
        assertEquals(-1, gap.inferred)
    }

    @Test
    fun `a posting landing on a settled position waits for the next cycle rather than judged alone`() {
        heldAt(1, 64, 1, stoneForm)
        blockRow(1, 64, 1, AIR, STONE)
        heldAt(2, 64, 2, stoneForm)
        blockRow(2, 64, 2, AIR, STONE)
        drainBoth()

        val first = sync.pass(1, now)
        assertEquals(1, first.checked)
        assertFalse(first.reachedEnd)

        releasedAt(1, 64, 1, stoneForm, ts = longAgo + 1)
        blockRow(1, 64, 1, STONE, AIR, ts = longAgo + 1)
        drainBoth()

        val rest = assertPlanesAgree(now)

        assertEquals(0, rest.overdrawn)
        assertEquals(1, rest.checked)
        assertTrue(rest.reachedEnd)

        val cycle = assertPlanesAgree(now)

        assertEquals(0, cycle.overdrawn)
        assertEquals(2, cycle.checked)
    }

    // The item writer can run ahead of the block writer: a fresh posting over a block row that is old.
    @Test
    fun `a posting made inside the settle window is left for a later pass`() {
        heldAt(5, 64, 5, stoneForm, ts = now)
        standAndFall(5, 64, 5)
        drainBoth()

        val settling = assertPlanesAgree(now)

        assertEquals(1, settling.settling)
        assertEquals(0, settling.checked)
        assertTrue(settling.reachedEnd)

        val aged = sync.pass(100, now + 2 * SETTLE_MILLIS)

        assertEquals(1, aged.checked)
        assertEquals(0, aged.settling)
        assertEquals(AIR, aged.gaps.single().standing)
    }

    // The block went just now and the item plane has not booked what fell out of it yet: the posting is
    // old, and only the block row says the position is still moving.
    @Test
    fun `a block gone just now under an old posting is left for a later pass`() {
        heldAt(5, 64, 5, stoneForm)
        standAndFall(5, 64, 5, ts = now)
        drainBoth()

        val report = assertPlanesAgree(now)

        assertEquals(1, report.settling)
        assertEquals(0, report.checked)
    }

    @Test
    fun `a disagreement made of inferred postings is counted apart from a confirmed one`() {
        heldAt(1, 64, 1, stoneForm)
        standAndFall(1, 64, 1)
        heldAt(2, 64, 2, stoneForm, confidence = Confidence.INFERRED)
        standAndFall(2, 64, 2)
        drainBoth()

        val gaps = sync.pass(100, now).gaps.associateBy { it.at }

        assertEquals(2, gaps.size)
        val confirmed = gaps.getValue(WorldBlock(world, 1, 64, 1))
        assertEquals(1, confirmed.fact)
        assertEquals(0, confirmed.inferred)
        val guessed = gaps.getValue(WorldBlock(world, 2, 64, 2))
        assertEquals(0, guessed.fact)
        assertEquals(1, guessed.inferred)
    }

    @Test
    fun `a posting that cannot be decoded leaves its position unreadable rather than accused`() {
        heldAt(6, 64, 6, stoneForm)
        standAndFall(6, 64, 6)
        drainBoth()
        val key = EntryCodec.key(WorldBlock(world, 6, 64, 6), longAgo + 1, 9999, 0, shared.registries)

        closeAll()
        rawPut(dir.resolve("items"), "entries", key, byteArrayOf(0x01))
        openPlanes()

        val report = assertPlanesAgree(now)

        assertEquals(1, report.unreadable)
        assertEquals(0, report.checked)
    }

    // A block row that cannot be read says nothing about what stands there, and comparing the position
    // anyway would report a holding against a block nobody knows.
    @Test
    fun `a block row torn or naming an unknown state leaves its position unreadable rather than accused`() {
        heldAt(8, 64, 8, stoneForm)
        standAndFall(8, 64, 8)
        heldAt(9, 64, 9, stoneForm)
        standAndFall(9, 64, 9)
        drainBoth()
        closeAll()
        val base = dir.resolve("blocks").resolve(world.toString())
        rawPut(base, "rows", BlockCodec.key(8, 64, 8, longAgo + 5, 998, 0), ByteArray(0))
        val unknown = BlockRow(9, 64, 9, longAgo + 5, 999, 0, Cause.BLK_PLAYER_BREAK, 60_000, 60_001)
        rawPut(base, "rows", BlockCodec.key(9, 64, 9, longAgo + 5, 999, 0), BlockCodec.value(unknown) { _, _ -> error("no actor") })
        openPlanes()

        val report = assertPlanesAgree(now)

        assertEquals(2, report.unreadable)
        assertEquals(0, report.checked)
    }

    @Test
    fun `a world whose block base is missing is counted unreadable rather than swept clean`() {
        heldAt(7, 64, 7, stoneForm, at = unopened)
        drainBoth()

        val report = assertPlanesAgree(now)

        assertEquals(1, report.unreadable)
        assertEquals(0, report.checked)
    }

    @Test
    fun `the cursor survives a close and reopen`() {
        for (n in 1..4) {
            heldAt(n, 64, n, stoneForm)
            standAndFall(n, 64, n)
        }
        drainBoth()
        val first = sync.pass(1, now)
        assertEquals(1, first.checked)
        assertFalse(first.reachedEnd)

        closeAll()
        openPlanes()

        val rest = sync.pass(100, now)

        assertEquals(3, rest.checked)
        assertTrue(rest.reachedEnd)
        assertFalse(rest.gaps.map { it.at }.contains(first.gaps.single().at))
        assertEquals(4, (first.gaps + rest.gaps).map { it.at }.toSet().size)
    }

    @Test
    fun `a position with more postings than any page limit is settled whole`() {
        repeat(600) { heldAt(8, 64, 8, stoneForm) }
        standAndFall(8, 64, 8)
        drainBoth()

        val report = sync.pass(10, now)

        assertEquals(1, report.checked)
        assertTrue(report.reachedEnd)
        assertEquals(600, report.gaps.single().fact)
    }
}
