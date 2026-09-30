package io.pfaumc.pfauprotect.storage
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.attribution.behind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val T0 = 1_700_000_000_000L

private const val AIR = "minecraft:air"
private const val STONE = "minecraft:stone"
private const val DIRT = "minecraft:dirt"
private const val WATER = "minecraft:water"
private const val STAIRS = "minecraft:oak_stairs[facing=north]"

class BlockStoreTest {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")

    private lateinit var root: Path
    private lateinit var shared: RocksItemLog
    private lateinit var logs: BlockLogs
    private lateinit var log: BlockLog

    @BeforeEach
    fun open(@TempDir tempDir: Path) {
        root = tempDir.resolve("blocks")
        shared = RocksItemLog(tempDir.resolve("items"))
        logs = BlockLogs(root, shared)
        log = logs.open(world)
    }

    @AfterEach
    fun closeAll() {
        logs.close()
        shared.close()
    }

    private fun placed(
        x: Int,
        y: Int,
        z: Int,
        before: String? = AIR,
        after: String? = STONE,
        ts: Long = T0,
    ) = BlockChange(
        x = x,
        y = y,
        z = z,
        before = before,
        after = after,
        cause = Cause.BLK_PLAYER_PLACE,
        timestamp = ts,
        actor = alice,
    )

    private fun stateOf(blockData: String): Int? =
        shared.registries.lookupKey(RegistryNamespace.BLOCK_STATE, blockData)

    // The only way to a row the log itself would never write. The log has to be shut for it: RocksDB
    // takes one writer per directory.
    private fun writeRawRow(key: ByteArray, value: ByteArray) {
        logs.close(world)
        val path = root.resolve(world.toString()).toAbsolutePath().toString()
        val names = Options().use { RocksDB.listColumnFamilies(it, path) }
        val rowsIndex = names.indexOfFirst { it.contentEquals("rows".toByteArray()) }
        val handles = ArrayList<ColumnFamilyHandle>()
        ColumnFamilyOptions().use { cfOptions ->
            DBOptions().use { options ->
                try {
                    RocksDB.open(options, path, names.map { ColumnFamilyDescriptor(it, cfOptions) }, handles)
                        .use { it.put(handles[rowsIndex], key, value) }
                } finally {
                    handles.forEach { it.close() }
                }
            }
        }
        log = logs.open(world)
    }

    @Test
    fun `a row carries both states, the actor and both payloads through the bytes`() {
        val row = BlockRow(
            x = 100,
            y = 64,
            z = -200,
            timestamp = T0,
            eventId = 42,
            ordinal = 3,
            cause = Cause.BLK_PLAYER_PLACE,
            stateBefore = 0x0102,
            stateAfter = 0xFFFF,
            confidence = Confidence.INFERRED,
            actor = alice,
            payloadBefore = 300,
            payloadAfter = 1,
        )
        val ids = IdResolver { _, _ -> 7 }
        val names = IdLookup { _, id -> if (id == 7) alice else null }

        val key = BlockCodec.key(row.x, row.y, row.z, row.timestamp, row.eventId, row.ordinal)
        val value = BlockCodec.value(row, ids)

        assertEquals(26, key.size)
        assertEquals(BlockCodec.KEY_SIZE, key.size)
        assertArrayEquals(Zcode.encode(100, 64, -200), key.copyOf(Zcode.SIZE))
        assertArrayEquals(BlockCodec.positionPrefix(100, 64, -200), key.copyOf(Zcode.SIZE))
        assertArrayEquals(
            ByteWriter(16)
                // version 0, inferred, actor present, both payloads present
                .byte(0x78)
                .byte(Cause.BLK_PLAYER_PLACE.id)
                .shortBE(0x0102)
                .shortBE(0xFFFF)
                .varInt(7)
                .varLong(300)
                .varLong(1)
                .toByteArray(),
            value,
        )
        assertEquals(row, BlockCodec.decode(key, value, names))
    }

    @Test
    fun `the half that went along with the struck one says so through the bytes`() {
        val row = BlockRow(
            x = 1,
            y = 2,
            z = 3,
            timestamp = T0,
            eventId = 1,
            ordinal = 0,
            cause = Cause.BLK_PLAYER_BREAK,
            stateBefore = 1,
            stateAfter = 2,
            alongside = true,
        )
        val key = BlockCodec.key(row.x, row.y, row.z, row.timestamp, row.eventId, row.ordinal)
        val value = BlockCodec.value(row, IdResolver { _, _ -> 0 })
        val names = IdLookup { _, _ -> null }

        assertEquals(0x80.toByte(), value[0])
        assertEquals(row, BlockCodec.decode(key, value, names))
        // A row written before the flag existed leaves the bit clear, so it reads back as the struck
        // half rather than as a half that went along with one.
        assertFalse(BlockCodec.decode(key, BlockCodec.value(row.copy(alongside = false), IdResolver { _, _ -> 0 }), names).alongside)
    }

    // The witness flag took the top bit of the version field, which every row so far has clear.
    @Test
    fun `a witness row says so through the bytes and the other confidences stay as they were`() {
        val names = IdLookup { _, _ -> alice }
        for (confidence in Confidence.entries) {
            val row = BlockRow(1, 2, 3, T0, 0, 0, Cause.BLK_ENTITY_SWITCH, 1, 2, confidence = confidence, actor = alice)
            val key = BlockCodec.key(1, 2, 3, T0, 0, 0)
            val value = BlockCodec.value(row, IdResolver { _, _ -> 0 })
            assertEquals(confidence == Confidence.NEARBY, value[0].toInt() and 0x04 != 0)
            assertEquals(row, BlockCodec.decode(key, value, names))
        }
    }

    @Test
    fun `a row of an unknown layout version is skipped rather than guessed at`() {
        val row = BlockRow(1, 2, 3, T0, 0, 0, Cause.BLK_GROW, 1, 2)
        val key = BlockCodec.key(1, 2, 3, T0, 0, 0)
        val value = BlockCodec.value(row, IdResolver { _, _ -> 0 })
        value[0] = (value[0].toInt() or 0x01).toByte()
        assertNull(BlockCodec.decodeOrNull(key, value, IdLookup { _, _ -> null }))
    }

    @Test
    fun `a row whose bytes stop short is skipped rather than thrown out of the scan`() {
        val row = BlockRow(1, 2, 3, T0, 0, 0, Cause.BLK_GROW, 1, 2)
        val key = BlockCodec.key(1, 2, 3, T0, 0, 0)
        val value = BlockCodec.value(row, IdResolver { _, _ -> 0 })
        val names = IdLookup { _, _ -> null }

        assertNull(BlockCodec.decodeOrNull(key, ByteArray(0), names))
        assertNull(BlockCodec.decodeOrNull(key, value.copyOf(2), names))
        assertNull(BlockCodec.decodeOrNull(key.copyOf(4), value, names))
    }

    @Test
    fun `a torn row costs its own row and not the rows around it`() {
        log.submit(listOf(placed(2, 64, 2, before = AIR, after = STONE, ts = T0)))
        log.submit(listOf(placed(2, 64, 2, before = STONE, after = DIRT, ts = T0 + 10)))
        log.drain()
        writeRawRow(BlockCodec.key(2, 64, 2, T0 + 5, 999, 0), ByteArray(0))

        assertEquals(listOf(stateOf(STONE), stateOf(DIRT)), log.at(2, 64, 2).map { it.stateAfter })
        assertEquals(2, log.inChunk(0, 0).size)
        assertEquals(stateOf(DIRT), log.standingAt(2, 64, 2).row?.stateAfter)
    }

    @Test
    fun `a change missing either side is refused without taking the rest of the submit with it`() {
        log.submit(
            listOf(
                placed(1, 64, 1),
                placed(2, 64, 1, before = null),
                placed(3, 64, 1, after = null),
                placed(4, 64, 1, after = DIRT),
            )
        )
        log.drain()

        assertEquals(1, log.at(1, 64, 1).size)
        assertTrue(log.at(2, 64, 1).isEmpty())
        assertTrue(log.at(3, 64, 1).isEmpty())
        assertEquals(stateOf(DIRT), log.at(4, 64, 1).single().stateAfter)
    }

    @Test
    fun `a position answers with its own rows and a chunk with every position in it`() {
        log.submit(
            listOf(
                placed(100, 64, -200),
                placed(104, 70, -200, after = STAIRS),
                placed(120, 64, -200, after = DIRT),
            )
        )
        log.drain()

        val here = log.at(100, 64, -200)
        assertEquals(1, here.size)
        assertEquals(listOf(100, 64, -200), listOf(here[0].x, here[0].y, here[0].z))
        assertEquals(stateOf(STONE), here[0].stateAfter)
        assertEquals(alice, here[0].actor)

        // 120 is the next chunk over, so the chunk walk has to stop short of it.
        assertEquals(6, 100 shr 4)
        assertEquals(7, 120 shr 4)
        assertEquals(
            setOf(100 to 64, 104 to 70),
            log.inChunk(6, -13).map { it.x to it.y }.toSet(),
        )
    }

    @Test
    fun `one event changing one position twice keeps both changes`() {
        log.submit(
            listOf(
                BlockChange(0, 64, 0, AIR, WATER, Cause.BLK_LIQUID_FORM, T0),
                BlockChange(0, 64, 0, WATER, STONE, Cause.BLK_PISTON_EXTEND, T0),
            )
        )
        log.drain()

        val rows = log.at(0, 64, 0)
        assertEquals(2, rows.size)
        assertEquals(listOf(stateOf(AIR), stateOf(WATER)), rows.map { it.stateBefore })
        assertEquals(listOf(stateOf(WATER), stateOf(STONE)), rows.map { it.stateAfter })
        assertEquals(listOf(0, 1), rows.map { it.ordinal })
        assertEquals(1, rows.map { it.eventId }.toSet().size)
        assertEquals(stateOf(STONE), log.standingAt(0, 64, 0).row?.stateAfter)
    }

    // The ordinal is one byte, and an explosion is thousands of positions in one event. Counted
    // across the event it would run out partway through and the rest of the blast would go unwritten.
    @Test
    fun `an event wider than the ordinal fits still writes every position`() {
        val wide = (0 until 600).map { placed(it, 64, 40, ts = T0) }
        log.submit(wide)
        log.drain()

        assertEquals(600, wide.count { log.at(it.x, 64, 40).size == 1 })
        assertEquals(setOf(0), (0 until 600).flatMap { log.at(it, 64, 40) }.map { it.ordinal }.toSet())
    }

    @Test
    fun `a chunk answers by time and not by position`() {
        log.submit(listOf(placed(15, 64, 15, ts = T0)))
        log.submit(listOf(placed(1, 64, 1, after = DIRT, ts = T0 + 5000)))
        log.drain()

        assertEquals(listOf(T0 + 5000, T0), log.inChunk(0, 0, reverse = true).map { it.timestamp })
        assertEquals(listOf(1 to 1), log.inChunk(0, 0, limit = 1, reverse = true).map { it.x to it.z })
        assertEquals(listOf(15 to 15), log.inChunk(0, 0, limit = 1).map { it.x to it.z })
        assertEquals(
            listOf(T0, T0 + 5000),
            log.inChunk(0, 0, fromTs = T0, toTs = T0 + 5000).map { it.timestamp },
        )
    }

    @Test
    fun `the newest row of a position is what stands there`() {
        log.submit(listOf(placed(10, 64, 10, before = AIR, after = STONE, ts = T0)))
        log.submit(listOf(placed(10, 64, 10, before = STONE, after = DIRT, ts = T0 + 10)))
        log.drain()

        val latest = log.standingAt(10, 64, 10).row
        assertEquals(stateOf(DIRT), latest?.stateAfter)
        assertEquals(T0 + 10, latest?.timestamp)
        assertEquals(2, log.at(10, 64, 10).size)
    }

    @Test
    fun `one submit is one event and the next submit is another`() {
        log.submit(listOf(placed(1, 64, 1), placed(2, 64, 1), placed(3, 64, 1)))
        log.submit(listOf(placed(1, 65, 1, ts = T0 + 10)))
        log.drain()

        val event = setOf(
            log.at(1, 64, 1).single().eventId,
            log.at(2, 64, 1).single().eventId,
            log.at(3, 64, 1).single().eventId,
        )
        assertEquals(1, event.size)
        assertNotEquals(event.single(), log.at(1, 65, 1).single().eventId)
    }

    @Test
    fun `a clock that steps backwards does not reorder a position`() {
        log.submit(listOf(placed(7, 64, 7, before = AIR, after = STONE, ts = T0 + 100)))
        log.submit(listOf(placed(7, 64, 7, before = STONE, after = DIRT, ts = T0)))
        log.drain()

        val rows = log.at(7, 64, 7)
        assertEquals(listOf(stateOf(STONE), stateOf(DIRT)), rows.map { it.stateAfter })
        assertEquals(listOf(T0 + 100, T0 + 100), rows.map { it.timestamp })
        assertEquals(stateOf(DIRT), log.standingAt(7, 64, 7).row?.stateAfter)
    }

    @Test
    fun `a row behind the world but not behind its own position keeps its own time`() {
        log.submit(listOf(placed(1, 64, 1, ts = T0 + 1000)))
        log.drain()
        log.submit(listOf(placed(2, 64, 2, after = DIRT, ts = T0)))
        log.drain()

        assertEquals(T0, log.at(2, 64, 2).single().timestamp)
        assertEquals(T0 + 1000, log.at(1, 64, 1).single().timestamp)
    }

    @Test
    fun `a row behind its own position is clamped to that position and not to the world`() {
        log.submit(listOf(placed(3, 64, 3, before = AIR, after = STONE, ts = T0 + 100)))
        log.drain()
        log.submit(listOf(placed(9, 64, 9, after = WATER, ts = T0 + 500)))
        log.drain()
        log.submit(listOf(placed(3, 64, 3, before = STONE, after = DIRT, ts = T0)))
        log.drain()

        val rows = log.at(3, 64, 3)
        assertEquals(listOf(stateOf(STONE), stateOf(DIRT)), rows.map { it.stateAfter })
        assertEquals(listOf(T0 + 100, T0 + 100), rows.map { it.timestamp })
        assertEquals(stateOf(DIRT), log.standingAt(3, 64, 3).row?.stateAfter)
    }

    // Without the world mark on disk the reopened log would take every time as ahead of everything
    // it holds and file the older row first.
    @Test
    fun `a clock that steps backwards over a reopen does not reorder a position`() {
        log.submit(listOf(placed(8, 64, 8, before = AIR, after = STONE, ts = T0 + 100)))
        log.drain()
        logs.close(world)

        val reopened = logs.open(world)
        reopened.submit(listOf(placed(8, 64, 8, before = STONE, after = DIRT, ts = T0)))
        reopened.drain()

        val rows = reopened.at(8, 64, 8)
        assertEquals(listOf(stateOf(STONE), stateOf(DIRT)), rows.map { it.stateAfter })
        assertEquals(listOf(T0 + 100, T0 + 100), rows.map { it.timestamp })
        assertEquals(stateOf(DIRT), reopened.standingAt(8, 64, 8).row?.stateAfter)
    }

    @Test
    fun `rows and the event counter survive a close and a reopen`() {
        log.submit(listOf(placed(5, 64, 5)))
        log.drain()
        val firstEvent = log.at(5, 64, 5).single().eventId
        logs.close(world)

        val reopened = logs.open(world)
        assertEquals(stateOf(STONE), reopened.at(5, 64, 5).single().stateAfter)
        reopened.submit(listOf(placed(6, 64, 5, ts = T0 + 10)))
        reopened.drain()
        assertEquals(firstEvent + 1, reopened.at(6, 64, 5).single().eventId)
    }

    @Test
    fun `a read or a write after close never reaches the handle`() {
        assertTrue(log.submit(listOf(placed(5, 64, 5))))
        log.drain()
        log.close()

        assertTrue(log.at(5, 64, 5).isEmpty())
        assertTrue(log.inChunk(0, 0).isEmpty())
        assertNull(log.standingAt(5, 64, 5).row)
        assertFalse(log.submit(listOf(placed(9, 64, 9))))
        log.drain()
    }

    @Test
    fun `a submit after close is refused rather than taken and never written`() {
        logs.close(world)

        assertFalse(log.submit(listOf(placed(9, 64, 9))))
        assertTrue(logs.open(world).at(9, 64, 9).isEmpty())
    }

    @Test
    fun `deleting a world takes its directory and leaves the next open empty`() {
        log.submit(listOf(placed(5, 64, 5)))
        log.drain()
        val dir = root.resolve(world.toString())
        assertTrue(Files.isDirectory(dir))

        logs.delete(world)
        assertFalse(Files.exists(dir))
        assertNull(logs.get(world))

        assertTrue(logs.open(world).at(5, 64, 5).isEmpty())
    }
}
