package io.pfaumc.pfauprotect.check
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.BlockLog
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.storage.EntryCodec
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.attribution.inferred
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.DBOptions
import org.rocksdb.Options
import org.rocksdb.RocksDB
import java.nio.file.Path
import java.util.UUID

private const val AIR = "minecraft:air"
private const val STONE = "minecraft:stone"
private const val WATER = "minecraft:water[level=0]"
private const val WATERLOGGED = "minecraft:oak_slab[type=bottom,waterlogged=true]"

class PlaneSyncTest {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val unopened = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val stoneForm = byteArrayOf(1, 2, 3)

    private val now = System.currentTimeMillis()
    private val longAgo = now - 10 * SETTLE_MILLIS

    private lateinit var dir: Path
    private lateinit var shared: RocksItemLog
    private lateinit var logs: BlockLogs
    private lateinit var log: BlockLog
    private lateinit var sync: PlaneSync

    @BeforeEach
    fun open(@TempDir tempDir: Path) {
        dir = tempDir
        openAll()
    }

    @AfterEach
    fun closeAll() {
        logs.close()
        shared.close()
    }

    private fun openAll() {
        shared = RocksItemLog(dir.resolve("items"))
        logs = BlockLogs(dir.resolve("blocks"), shared)
        log = logs.open(world)
        sync = PlaneSync(shared, logs)
    }

    private fun reopen() {
        logs.close()
        shared.close()
        openAll()
    }

    // The item plane takes the position over: a placement books the item onto the block it became.
    private fun holdItem(
        x: Int,
        y: Int,
        z: Int,
        ts: Long = longAgo,
        confidence: Confidence = Confidence.FACT,
        at: UUID = world,
    ) {
        shared.submit(
            Transfer(
                cause = Cause.BLOCK_PLACE,
                from = PlayerInv(alice, 0),
                to = WorldBlock(at, x, y, z),
                form = stoneForm,
                damage = null,
                qty = 1,
                timestamp = ts,
                confidence = confidence,
            )
        )
    }

    private fun releaseItem(
        x: Int,
        y: Int,
        z: Int,
        ts: Long = longAgo,
        confidence: Confidence = Confidence.FACT,
    ) {
        shared.submit(
            Transfer(
                cause = Cause.BLOCK_DROP,
                from = WorldBlock(world, x, y, z),
                to = Void,
                form = stoneForm,
                damage = null,
                qty = 1,
                timestamp = ts,
                actor = alice,
                confidence = confidence,
            )
        )
    }

    // The store only ever clamps a stored time forward, so a row written here can never be older than
    // one written before it: within a test the calls have to run oldest first.
    private fun blockRow(x: Int, y: Int, z: Int, before: String, after: String, ts: Long = longAgo) {
        log.submit(
            listOf(
                BlockChange(
                    x = x,
                    y = y,
                    z = z,
                    before = before,
                    after = after,
                    cause = if (after == AIR || after == WATER) Cause.BLK_PLAYER_BREAK else Cause.BLK_PLAYER_PLACE,
                    timestamp = ts,
                    actor = alice,
                )
            )
        )
    }

    private fun standAndFall(x: Int, y: Int, z: Int, ts: Long = longAgo) {
        blockRow(x, y, z, AIR, STONE, ts)
        blockRow(x, y, z, STONE, AIR, ts)
    }

    private fun drainBoth() {
        log.drain()
        shared.drain()
    }

    // The only way to a row the ledger itself would never write. The ledger has to be shut for it:
    // RocksDB takes one writer per directory.
    private fun writeRawEntry(key: ByteArray, value: ByteArray) {
        logs.close()
        shared.close()
        val path = dir.resolve("items").toAbsolutePath().toString()
        val names = Options().use { RocksDB.listColumnFamilies(it, path) }
        val entriesIndex = names.indexOfFirst { it.contentEquals("entries".toByteArray()) }
        val handles = ArrayList<ColumnFamilyHandle>()
        ColumnFamilyOptions().use { cfOptions ->
            DBOptions().use { options ->
                try {
                    RocksDB.open(options, path, names.map { ColumnFamilyDescriptor(it, cfOptions) }, handles)
                        .use { it.put(handles[entriesIndex], key, value) }
                } finally {
                    handles.forEach { it.close() }
                }
            }
        }
        openAll()
    }

    @Test
    fun `a position the block plane calls air while the item plane still holds an item is reported`() {
        holdItem(1, 64, 1)
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
    fun `positions the two planes agree on are not reported`() {
        holdItem(1, 64, 1)
        blockRow(1, 64, 1, AIR, STONE)
        holdItem(2, 64, 2)
        releaseItem(2, 64, 2)
        standAndFall(2, 64, 2)
        drainBoth()

        val report = sync.pass(100, now)

        assertEquals(emptyList<PlaneGap>(), report.gaps)
        assertEquals(2, report.checked)
        assertEquals(0, report.unreadable)
    }

    @Test
    fun `a waterlogged block broken leaves water standing and is reported`() {
        holdItem(3, 64, 3)
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
        holdItem(4, 64, 4)
        holdItem(4, 64, 4)
        drainBoth()

        val report = sync.pass(100, now)

        assertEquals(emptyList<PlaneGap>(), report.gaps)
        assertEquals(1, report.unrecorded)
        assertEquals(0, report.checked)
        assertEquals(0, report.unreadable)
    }

    @Test
    fun `a position that gave up what it never took is counted apart rather than called a holding`() {
        releaseItem(9, 64, 9)
        standAndFall(9, 64, 9)
        drainBoth()

        val report = sync.pass(100, now)

        assertEquals(emptyList<PlaneGap>(), report.gaps)
        assertEquals(1, report.overdrawn)
        assertEquals(0, report.checked)
        assertEquals(0, report.unrecorded)
    }

    // A house that stood before the plugin gives up its planks to the first blast with nothing booked to set
    // against them; on a real map that was thousands of positions a pass (O24). Not a fault of the capture.
    @Test
    fun `a block that stood before the block plane saw it is not overdrawn when it goes`() {
        releaseItem(9, 64, 9)
        blockRow(9, 64, 9, STONE, AIR)
        drainBoth()

        val report = sync.pass(100, now)

        assertEquals(0, report.overdrawn)
        assertEquals(1, report.unrecorded)
        assertEquals(emptyList<PlaneGap>(), report.gaps)
    }

    @Test
    fun `a confirmed holding written off by a guess is a disagreement and not a settled position`() {
        holdItem(10, 64, 10)
        releaseItem(10, 64, 10, confidence = Confidence.INFERRED)
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
        holdItem(1, 64, 1)
        blockRow(1, 64, 1, AIR, STONE)
        holdItem(2, 64, 2)
        blockRow(2, 64, 2, AIR, STONE)
        drainBoth()

        val first = sync.pass(1, now)
        assertEquals(1, first.checked)
        assertFalse(first.reachedEnd)

        releaseItem(1, 64, 1, ts = longAgo + 1)
        blockRow(1, 64, 1, STONE, AIR, ts = longAgo + 1)
        drainBoth()

        val rest = sync.pass(100, now)

        assertEquals(emptyList<PlaneGap>(), rest.gaps)
        assertEquals(0, rest.overdrawn)
        assertEquals(1, rest.checked)
        assertTrue(rest.reachedEnd)

        val cycle = sync.pass(100, now)

        assertEquals(emptyList<PlaneGap>(), cycle.gaps)
        assertEquals(0, cycle.overdrawn)
        assertEquals(2, cycle.checked)
    }

    @Test
    fun `a position touched inside the settle window is left for a later pass`() {
        holdItem(5, 64, 5, ts = now)
        standAndFall(5, 64, 5, ts = now)
        drainBoth()

        val settling = sync.pass(100, now)

        assertEquals(emptyList<PlaneGap>(), settling.gaps)
        assertEquals(1, settling.settling)
        assertEquals(0, settling.checked)
        assertTrue(settling.reachedEnd)

        val aged = sync.pass(100, now + 2 * SETTLE_MILLIS)

        assertEquals(1, aged.checked)
        assertEquals(0, aged.settling)
        assertEquals(AIR, aged.gaps.single().standing)
    }

    @Test
    fun `a disagreement made of inferred postings is counted apart from a confirmed one`() {
        holdItem(1, 64, 1)
        standAndFall(1, 64, 1)
        holdItem(2, 64, 2, confidence = Confidence.INFERRED)
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
        holdItem(6, 64, 6)
        standAndFall(6, 64, 6)
        drainBoth()
        val key = EntryCodec.key(WorldBlock(world, 6, 64, 6), longAgo + 1, 9999, 0, shared.registries)

        writeRawEntry(key, byteArrayOf(0x01))

        val report = sync.pass(100, now)

        assertEquals(emptyList<PlaneGap>(), report.gaps)
        assertEquals(1, report.unreadable)
        assertEquals(0, report.checked)
    }

    @Test
    fun `a world whose block base is missing is counted unreadable rather than swept clean`() {
        holdItem(7, 64, 7, at = unopened)
        drainBoth()

        val report = sync.pass(100, now)

        assertEquals(emptyList<PlaneGap>(), report.gaps)
        assertEquals(1, report.unreadable)
        assertEquals(0, report.checked)
    }

    @Test
    fun `small passes cover what one pass covers, repeating none of it and dropping no tail`() {
        for (n in 1..4) {
            holdItem(n, 64, n)
            standAndFall(n, 64, n)
        }
        drainBoth()

        val whole = sync.pass(100, now)
        assertEquals(4, whole.checked)
        assertTrue(whole.reachedEnd)

        val covered = ArrayList<WorldBlock>()
        var passes = 0
        while (passes < 10) {
            passes++
            val part = sync.pass(1, now)
            covered += part.gaps.map { it.at }
            if (part.reachedEnd) break
        }

        assertEquals(4, passes)
        assertEquals(covered.size, covered.toSet().size)
        assertEquals(whole.gaps.map { it.at }.toSet(), covered.toSet())
    }

    @Test
    fun `the cursor survives a close and reopen`() {
        for (n in 1..4) {
            holdItem(n, 64, n)
            standAndFall(n, 64, n)
        }
        drainBoth()
        val first = sync.pass(1, now)
        assertEquals(1, first.checked)
        assertFalse(first.reachedEnd)

        reopen()

        val rest = sync.pass(100, now)

        assertEquals(3, rest.checked)
        assertTrue(rest.reachedEnd)
        assertFalse(rest.gaps.map { it.at }.contains(first.gaps.single().at))
        assertEquals(4, (first.gaps + rest.gaps).map { it.at }.toSet().size)
    }

    @Test
    fun `a position with more postings than any page limit is settled whole`() {
        repeat(600) { holdItem(8, 64, 8) }
        standAndFall(8, 64, 8)
        drainBoth()

        val report = sync.pass(10, now)

        assertEquals(1, report.checked)
        assertTrue(report.reachedEnd)
        assertEquals(600, report.gaps.single().fact)
    }
}
