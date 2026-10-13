package io.pfaumc.pfauprotect.storage
import io.pfaumc.pfauprotect.BothPlanes
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import org.rocksdb.RocksDBException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID

private const val AIR = "minecraft:air"
private const val STONE = "minecraft:stone"
private const val DIRT = "minecraft:dirt"
private const val WATER = "minecraft:water"
private const val STAIRS = "minecraft:oak_stairs[facing=north]"

class BlockStoreTest : BothPlanes() {
    private val root: Path get() = dir.resolve("blocks")

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

    private fun writeRawRow(key: ByteArray, value: ByteArray) {
        logs.close(world)
        rawPut(root.resolve(world.toString()), "rows", key, value)
        log = logs.open(world)
    }

    // Rows written to the world alone, with every state and player they name already interned in the
    // ledger, still show in the ledger's cache. Payloads would be no proof: they are interned in the ledger.
    // Some megabytes of rows and index entries, against a reservation that moves in steps of a quarter of
    // one and memtable arenas of one.
    @Test
    fun `a world base keeps its memtable inside the ledger's cache`() {
        log.submit(listOf(placed(0, 64, 0)))
        log.drain()
        val before = shared.blockCache.usage

        log.submit((1..40_000).map { placed(it, 64, it, ts = T0 + it) })
        log.drain()

        val grown = shared.blockCache.usage - before
        assertTrue(grown >= 2L * 1024 * 1024, "${shared.blockCache.usage} after, $before before")
    }

    // Written out by hand rather than taken from the encoder: a round trip cannot notice a layout
    // change applied to both sides.
    @Test
    fun `a row carries both states, the actor and both payloads through the bytes`() {
        val row = BlockRow(
            x = 100,
            y = 64,
            z = -200,
            timestamp = 0x0102030405060708L,
            eventId = 0x1112131415161718L,
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

        assertArrayEquals(
            byteArrayOf(
                0x95.toByte(), 0x55, 0x55, 0x55, 0x55, 0x2D, 0x48, 0x80.toByte(), 0x40,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
                0x03,
            ),
            key,
        )
        assertArrayEquals(
            // version 0, inferred, actor present, both payloads present
            byteArrayOf(0x78, 0xD0.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xFF.toByte(), 0x07, 0xAC.toByte(), 0x02, 0x01),
            value,
        )
        assertEquals(row, BlockCodec.decode(key, value, names))
    }

    // The actor's number, then the row key turned round so one actor's rows run in time order.
    @Test
    fun `an actor index row keeps its stored layout`() {
        shared.registries.id(RegistryNamespace.PLAYER, bob)
        log.submit(listOf(placed(100, 64, -200, ts = T0)))
        log.drain()
        val eventId = log.at(100, 64, -200, 0, Long.MAX_VALUE, limit = 100, reverse = false).single().eventId
        logs.close(world)
        val byActor = rawRowsOf(root.resolve(world.toString()), "by_actor")
        log = logs.open(world)

        val key = "00000001" + "0000018bcfe56800" + "%016x".format(eventId) + "00" + "95555555552d488040"
        assertEquals(mapOf(key to ""), byActor)
    }

    private val sheep = UUID.fromString("00000000-0000-4000-8000-0000000000c0")
    private val sheepBytes = byteArrayOf(0, 0, 0, 0, 0, 0, 0x40, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0xC0.toByte())

    // Numbers minted in an order of our own, so none of them is the zero a field left unwritten reads as.
    private fun entityNames() = Registries(MemoryRegistryStore()).apply {
        idForKey(RegistryNamespace.ENTITY_TYPE, "minecraft:pig")
        idForKey(RegistryNamespace.ENTITY_TYPE, "minecraft:sheep")
        idForKey(RegistryNamespace.ENTITY_TYPE, "minecraft:wolf")
        idForKey(RegistryNamespace.DAMAGE_TYPE, "minecraft:fall")
        idForKey(RegistryNamespace.DAMAGE_TYPE, "minecraft:mob_attack")
        id(RegistryNamespace.PLAYER, bob)
        id(RegistryNamespace.PLAYER, alice)
    }

    @Test
    fun `an entity row written before deaths were kept still reads`() {
        val names = entityNames()
        val key = BlockCodec.key(1, 2, 3, T0, 4, 0)
        val value = byteArrayOf(
            // version 0, inferred, actor present, payload before present
            0x38, 0x00, 0xD2.toByte(), 0x01, *sheepBytes, 0x01, 0xAC.toByte(), 0x02,
        )
        val row = EntityRow(
            1, 2, 3, T0, 4, 0, EntityKind.REMOVED, Cause.BLK_TNT, "minecraft:sheep", sheep,
            Confidence.INFERRED, alice, payloadBefore = 300,
        )

        assertEquals(row, EntityCodec.decodeOrNull(key, value, names))
        assertArrayEquals(value, EntityCodec.value(row, names))
    }

    private val firstDrop = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
    private val secondDrop = UUID.fromString("00000000-0000-4000-8000-0000000000d2")
    private val deathKey = BlockCodec.key(1, 2, 3, T0, 4, 0)
    private val deathValue = byteArrayOf(
        // version 1, actor present, drops present
        0x91.toByte(), 0x00, 0x97.toByte(), 0x01, *sheepBytes, 0x01,
        0x02,
        0, 0, 0, 0, 0, 0, 0x40, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0xD1.toByte(),
        0, 0, 0, 0, 0, 0, 0x40, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0xD2.toByte(),
        // damage type 1 and entity type 2, each written one up so that zero is none
        0x02, 0x03,
    )

    @Test
    fun `an entity death keeps its drops, its damage type and what dealt it through the bytes`() {
        val names = entityNames()
        val row = EntityRow(
            1, 2, 3, T0, 4, 0, EntityKind.REMOVED, Cause.ENTITY_KILLED, "minecraft:sheep", sheep,
            actor = alice, drops = listOf(firstDrop, secondDrop), death = "minecraft:mob_attack", via = "minecraft:wolf",
        )

        assertEquals(row, EntityCodec.decodeOrNull(deathKey, deathValue, names))
        assertArrayEquals(deathValue, EntityCodec.value(row, names))
    }

    @Test
    fun `an entity row whose bytes stop short or name an unknown version is skipped`() {
        val names = entityNames()
        for (kept in 0 until deathValue.size) {
            assertNull(EntityCodec.decodeOrNull(deathKey, deathValue.copyOf(kept), names)) { "$kept bytes of a row decoded" }
        }
        for (kept in 0 until deathKey.size) {
            assertNull(EntityCodec.decodeOrNull(deathKey.copyOf(kept), deathValue, names)) { "$kept bytes of a key decoded" }
        }
        for (version in 2..3) {
            val unknown = deathValue.copyOf().also { it[0] = ((it[0].toInt() and 0x03.inv()) or version).toByte() }
            assertNull(EntityCodec.decodeOrNull(deathKey, unknown, names)) { "version $version decoded" }
        }
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
    }

    @Test
    fun `a nearby row says so through the bytes and the other confidences stay as they were`() {
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
        val row = BlockRow(1, 2, 3, T0, 0, 0, Cause.BLK_GROW, 1, 2, actor = alice, payloadBefore = 300, payloadAfter = 1)
        val key = BlockCodec.key(1, 2, 3, T0, 0, 0)
        val value = BlockCodec.value(row, IdResolver { _, _ -> 0 })
        val names = IdLookup { _, _ -> alice }

        assertEquals(row, BlockCodec.decodeOrNull(key, value, names))
        for (kept in 0 until value.size) {
            assertNull(BlockCodec.decodeOrNull(key, value.copyOf(kept), names)) { "$kept bytes of a row decoded" }
        }
        for (kept in 0 until key.size) {
            assertNull(BlockCodec.decodeOrNull(key.copyOf(kept), value, names)) { "$kept bytes of a key decoded" }
        }
    }

    // A torn row costs only itself, and a rollback, which acts on everything it read, is told of it.
    @Test
    fun `a torn row is skipped by every read and counted by a rollback window`() {
        log.submit(listOf(placed(2, 64, 2, before = AIR, after = STONE, ts = T0)))
        log.submit(listOf(placed(2, 64, 2, before = STONE, after = DIRT, ts = T0 + 10)))
        log.submit(listOf(placed(9, 64, 9, ts = T0 + 20)))
        log.drain()
        writeRawRow(BlockCodec.key(2, 64, 2, T0 + 5, 999, 0), ByteArray(0))

        assertEquals(listOf(stateOf(STONE), stateOf(DIRT)), log.at(2, 64, 2, 0, Long.MAX_VALUE, limit = 100, reverse = false).map { it.stateAfter })
        assertEquals(3, log.inChunk(0, 0, 0, Long.MAX_VALUE, limit = 100, reverse = false).size)
        assertEquals(stateOf(DIRT), log.standingAt(2, 64, 2).row?.stateAfter)

        val whole = log.windowInChunk(0, 0, 0, Long.MAX_VALUE, budget = 100) { x, _, _ -> x == 2 }
        assertEquals(listOf(stateOf(STONE), stateOf(DIRT)), whole.rows.map { it.stateAfter })
        assertEquals(1, whole.unreadable)
        assertTrue(whole.complete)
        assertFalse(log.windowInChunk(0, 0, 0, Long.MAX_VALUE, budget = 3) { _, _, _ -> true }.complete)
        assertEquals(listOf(T0 + 10), log.windowAt(2, 64, 2, T0 + 6, Long.MAX_VALUE, 100).rows.map { it.timestamp })
    }

    // A block row has no ends to file it under, so the index is the only way to the positions a player
    // touched without walking the world; it keeps to the window and says when its budget ran out.
    @Test
    fun `the actor index answers where a player changed blocks in a window`() {
        log.submit(listOf(placed(1, 64, 1, ts = T0)))
        log.submit(listOf(placed(2, 64, 1, ts = T0 + 10), placed(500, 70, -500, ts = T0 + 10)))
        log.submit(listOf(placed(3, 64, 1, ts = T0 + 20).copy(actor = bob)))
        log.submit(listOf(placed(4, 64, 1, ts = T0 + 30)))
        log.drain()

        val window = log.touchedBy(alice, T0 + 5, T0 + 25, budget = 100)
        assertEquals(setOf(listOf(2, 64, 1), listOf(500, 70, -500)), window.positions)
        assertTrue(window.complete)
        assertFalse(log.touchedBy(alice, 0, Long.MAX_VALUE, budget = 2).complete)
        assertEquals(setOf(listOf(3, 64, 1)), log.touchedBy(bob, 0, Long.MAX_VALUE, 100).positions)
        assertTrue(log.touchedBy(UUID.randomUUID(), 0, Long.MAX_VALUE, 100).positions.isEmpty())
    }

    @Test
    fun `an entity row keeps everything and is found like a block row`() {
        val cow = UUID.randomUUID()
        val drop = UUID.randomUUID()
        val nbt = byteArrayOf(10, 0, 0, 0)
        log.submit(
            listOf(
                EntityChange(
                    5, 64, 6, EntityKind.REMOVED, Cause.BLK_TNT, "minecraft:cow", cow, T0,
                    Confidence.INFERRED, alice, before = nbt, drops = listOf(drop),
                ),
                placed(5, 64, 6, ts = T0),
            )
        )
        log.drain()

        val row = log.entitiesAt(5, 64, 6, 0, Long.MAX_VALUE, 100).rows.single()
        assertEquals(EntityKind.REMOVED, row.kind)
        assertEquals("minecraft:cow", row.type)
        assertEquals(cow, row.uuid)
        assertEquals(alice, row.actor)
        assertEquals(Confidence.INFERRED, row.confidence)
        assertArrayEquals(nbt, shared.payload(row.payloadBefore!!))
        assertNull(row.payloadAfter)
        assertEquals(listOf(drop), row.drops)
        assertEquals(listOf(row), log.entitiesInChunk(0, 0, 0, Long.MAX_VALUE, 100) { _, _, _ -> true }.rows)
        assertEquals(1, log.at(5, 64, 6, 0, Long.MAX_VALUE, limit = 100, reverse = false).size, "the block plane keeps its own row beside it")
        assertEquals(setOf(listOf(5, 64, 6)), log.touchedBy(alice, 0, Long.MAX_VALUE, 100).positions)
    }

    @Test
    fun `an entity death without a damage type or a dealer reads back without them`() {
        val names = entityNames()
        val key = BlockCodec.key(1, 2, 3, T0, 4, 0)
        for (row in listOf(
            EntityRow(1, 2, 3, T0, 4, 0, EntityKind.REMOVED, Cause.ENTITY_KILLED, "minecraft:sheep", sheep, death = "minecraft:fall"),
            EntityRow(1, 2, 3, T0, 4, 0, EntityKind.REMOVED, Cause.ENTITY_KILLED, "minecraft:sheep", sheep),
        )) {
            assertEquals(row, EntityCodec.decodeOrNull(key, EntityCodec.value(row, names), names))
        }
    }

    @Test
    fun `a base from before the entity plane opens and gains it`() {
        log.submit(listOf(placed(8, 64, 8, ts = T0)))
        log.drain()
        logs.close(world)
        val base = root.resolve(world.toString())
        val current = storedSchemaOf(base)
        rawEdit(base) { raw, family ->
            raw.dropColumnFamily(family("entities"))
            raw.put(family("meta"), "schema".toByteArray(), longBytes(2L))
        }
        log = logs.open(world)

        assertEquals(1, log.at(8, 64, 8, 0, Long.MAX_VALUE, limit = 100, reverse = false).size)
        assertTrue(log.entitiesAt(8, 64, 8, 0, Long.MAX_VALUE, 100).rows.isEmpty())
        logs.close(world)
        assertEquals(current, storedSchemaOf(base))
        assertTrue("entities" in familiesOf(base))
    }

    @Test
    fun `a base stamped by a newer build is refused and left as it was`() {
        log.submit(listOf(placed(8, 64, 8, ts = T0)))
        log.drain()
        logs.close(world)
        val base = root.resolve(world.toString())
        val current = storedSchemaOf(base)
        val families = familiesOf(base)
        rawPut(base, "meta", "schema".toByteArray(), longBytes(current + 1))

        assertThrows(IllegalArgumentException::class.java) { logs.open(world) }
        assertEquals(current + 1, storedSchemaOf(base))
        assertEquals(families, familiesOf(base))

        rawPut(base, "meta", "schema".toByteArray(), longBytes(current))
        log = logs.open(world)
        assertEquals(1, log.at(8, 64, 8, 0, Long.MAX_VALUE, limit = 100, reverse = false).size)
    }

    // A base from before the index has every row it ever wrote and no index of them; the first open
    // builds it, so a player's history does not start on the day of the upgrade.
    @Test
    fun `a base from before the index is indexed on its first open`() {
        log.submit(listOf(placed(7, 64, 7, ts = T0)))
        log.drain()
        logs.close(world)
        val base = root.resolve(world.toString())
        val current = storedSchemaOf(base)
        rawEdit(base) { raw, family ->
            raw.dropColumnFamily(family("by_actor"))
            raw.put(family("meta"), "schema".toByteArray(), longBytes(1L))
        }
        log = logs.open(world)

        assertEquals(setOf(listOf(7, 64, 7)), log.touchedBy(alice, 0, Long.MAX_VALUE, 100).positions)
        logs.close(world)
        assertEquals(current, storedSchemaOf(base))
    }

    // Every reopen flushes what was written since to a file of its own, enough of them for the rows to be
    // compacted, and the newest entities stay in the memtable: the purge has to walk all of it, chunk after
    // chunk, across the prefix extractor both families have.
    @Test
    fun `a purge reaches every chunk, on disk and in memory`() {
        val cutoff = T0 + 100
        val spots = (0 until 40).map { listOf(it * 112 - 2000, 64, it * 48 - 500) }
        fun reopen() {
            log.drain()
            logs.close(world)
            log = logs.open(world)
        }
        fun entity(at: List<Int>, ts: Long) =
            EntityChange(at[0], at[1] + 1, at[2], EntityKind.REMOVED, Cause.ENTITY_KILLED, "minecraft:cow", UUID.randomUUID(), ts, actor = alice)

        for (half in spots.chunked(spots.size / 2)) {
            log.submit(half.map { (x, y, z) -> placed(x, y, z, ts = T0) })
            reopen()
        }
        log.submit(spots.map { entity(it, T0) })
        reopen()
        log.submit(spots.filterIndexed { i, _ -> i % 3 != 2 }.map { (x, y, z) -> placed(x, y, z, ts = T0 + 10) })
        reopen()
        log.submit(spots.filterIndexed { i, _ -> i % 3 != 1 }.map { (x, y, z) -> placed(x, y, z, ts = T0 + 200) })
        reopen()
        log.submit(spots.filterIndexed { i, _ -> i % 3 == 2 }.map { entity(it, T0 + 200) })
        log.drain()

        // A position with a row after the cutoff loses every old row; one without keeps its newest old row.
        val purgedRows = spots.indices.sumOf { listOf(2, 1, 1)[it % 3] }
        assertEquals(purgedRows to spots.size, log.purgeBefore(cutoff, dryRun = true))
        assertEquals(purgedRows to spots.size, log.purgeBefore(cutoff, dryRun = false))
        reopen()

        for ((i, at) in spots.withIndex()) {
            val (x, y, z) = at
            val kept = if (i % 3 == 1) T0 + 10 else T0 + 200
            assertEquals(listOf(kept), log.at(x, y, z, 0, Long.MAX_VALUE, limit = 100, reverse = false).map { it.timestamp }, "rows at $at")
            val entities = if (i % 3 == 2) listOf(T0 + 200) else emptyList()
            assertEquals(entities, log.entitiesAt(x, y + 1, z, 0, Long.MAX_VALUE, 100).rows.map { it.timestamp }, "entities at $at")
        }
        // The index follows the rows: the kept old row is still found by its player, the purged ones are not.
        val keptOld = spots.filterIndexed { i, _ -> i % 3 == 1 }.toSet()
        assertEquals(keptOld, log.touchedBy(alice, 0, cutoff - 1, 1_000).positions)
        val newEntities = spots.filterIndexed { i, _ -> i % 3 == 2 }.map { (x, y, z) -> listOf(x, y + 1, z) }
        assertEquals(spots.toSet() + newEntities, log.touchedBy(alice, 0, Long.MAX_VALUE, 1_000).positions)
        assertEquals(0 to 0, log.purgeBefore(cutoff, dryRun = true))
    }

    // The ledger's writer meets a player in a transaction it then has to drop. A block writer has already
    // been handed that player's number by then, so the number has to be on disk whatever became of the
    // transaction, and after a restart still name the same player.
    @Test
    fun `a player first met in a dropped transaction keeps the number a block row names`() {
        val form = byteArrayOf(1, 2, 3)
        shared.submit(
            listOf(
                Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 0), Void, form, null, 1, T0),
                // Above any world, so this posting has no key and the whole transaction is dropped.
                Transfer(Cause.CONTAINER_ADD, Void, Container(world, 0, 99_999, 0, 0), form, null, 1, T0),
            )
        )
        shared.drain()
        log.submit(listOf(placed(3, 64, 3).copy(actor = bob)))
        log.drain()

        logs.close()
        shared.close()
        openPlanes()

        assertEquals(bob, log.at(3, 64, 3, 0, Long.MAX_VALUE, limit = 100, reverse = false).single().actor)
    }

    // A block the checksum rejects ends a walk exactly as the last row does, and an answer cut short there
    // would pass for the whole history of the place.
    @Test
    fun `a walk over a damaged file fails rather than answering short`() {
        log.submit((0 until 200).map { placed(it, 64, 0, ts = T0 + it) })
        log.drain()
        // The reopen flushes the rows from the log into a table file.
        logs.close(world)
        logs.open(world)
        logs.close(world)
        damageTables(root.resolve(world.toString()), "rows")
        log = logs.open(world)

        assertThrows(RocksDBException::class.java) { log.inChunk(0, 0, 0, Long.MAX_VALUE, limit = 100, reverse = false) }
        assertThrows(RocksDBException::class.java) { log.at(0, 64, 0, 0, Long.MAX_VALUE, limit = 100, reverse = false) }
        assertThrows(RocksDBException::class.java) { log.purgeBefore(T0 + 1_000, dryRun = true) }
    }

    // Every writer of the block plane goes through submit, so the log is where rows become notes; a row
    // it does not keep must not become one.
    @Test
    fun `the block log shows every accepted row to its watcher`() {
        val seen = ArrayList<Pair<UUID, BlockChange>>()
        BlockLogs(
            dir.resolve("watched"),
            shared,
            watch = { world, changes -> changes.forEach { seen += world to it } },
            keeps = { _, change -> change.x != 9 },
        ).use { watched ->
            val change = BlockChange(1, 2, 3, AIR, STONE, Cause.BLK_PLAYER_PLACE, actor = alice)
            val unkept = BlockChange(9, 2, 3, AIR, STONE, Cause.BLK_PLAYER_PLACE, actor = alice)
            watched.open(world).submit(listOf(change, unkept))
            assertEquals(listOf(world to change), seen)
        }
    }

    @Test
    fun `no world base opens once the bases are closed`() {
        logs.close()

        assertThrows(IllegalStateException::class.java) { logs.open(UUID.randomUUID()) }
    }

    // The cache and the memtable budget are the ledger's, lent to every base. A base still open when the
    // ledger goes keeps reading through them, and they are freed only once that base has closed too.
    @Test
    fun `a base still open when the ledger closes keeps the cache it reads through`() {
        log.submit(listOf(placed(1, 64, 1)))
        log.drain()

        shared.close()

        assertEquals(1, log.at(1, 64, 1, 0, Long.MAX_VALUE, limit = 100, reverse = false).size)
        assertThrows(IllegalStateException::class.java) { logs.open(UUID.randomUUID()) }
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

        assertEquals(1, log.at(1, 64, 1, 0, Long.MAX_VALUE, limit = 100, reverse = false).size)
        assertTrue(log.at(2, 64, 1, 0, Long.MAX_VALUE, limit = 100, reverse = false).isEmpty())
        assertTrue(log.at(3, 64, 1, 0, Long.MAX_VALUE, limit = 100, reverse = false).isEmpty())
        assertEquals(stateOf(DIRT), log.at(4, 64, 1, 0, Long.MAX_VALUE, limit = 100, reverse = false).single().stateAfter)
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

        val here = log.at(100, 64, -200, 0, Long.MAX_VALUE, limit = 100, reverse = false)
        assertEquals(1, here.size)
        assertEquals(listOf(100, 64, -200), listOf(here[0].x, here[0].y, here[0].z))
        assertEquals(stateOf(STONE), here[0].stateAfter)
        assertEquals(alice, here[0].actor)

        // 120 is the next chunk over, so the chunk walk has to stop short of it.
        assertEquals(
            setOf(100 to 64, 104 to 70),
            log.inChunk(6, -13, 0, Long.MAX_VALUE, limit = 100, reverse = false).map { it.x to it.y }.toSet(),
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

        val rows = log.at(0, 64, 0, 0, Long.MAX_VALUE, limit = 100, reverse = false)
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

        val rows = wide.map { log.at(it.x, 64, 40, 0, Long.MAX_VALUE, limit = 100, reverse = false) }
        assertEquals(600, rows.count { it.size == 1 })
        assertEquals(setOf(0), rows.flatten().map { it.ordinal }.toSet())
    }

    @Test
    fun `a chunk answers by time and not by position`() {
        log.submit(listOf(placed(15, 64, 15, ts = T0)))
        log.submit(listOf(placed(1, 64, 1, after = DIRT, ts = T0 + 5000)))
        log.drain()

        assertEquals(listOf(T0 + 5000, T0), log.inChunk(0, 0, 0, Long.MAX_VALUE, limit = 100, reverse = true).map { it.timestamp })
        assertEquals(listOf(1 to 1), log.inChunk(0, 0, 0, Long.MAX_VALUE, limit = 1, reverse = true).map { it.x to it.z })
        assertEquals(listOf(15 to 15), log.inChunk(0, 0, 0, Long.MAX_VALUE, limit = 1, reverse = false).map { it.x to it.z })
        assertEquals(
            listOf(T0, T0 + 5000),
            log.inChunk(0, 0, T0, T0 + 5000, limit = 100, reverse = false).map { it.timestamp },
        )
    }

    @Test
    fun `one submit is one event and the next submit is another`() {
        log.submit(listOf(placed(1, 64, 1), placed(2, 64, 1), placed(3, 64, 1)))
        log.submit(listOf(placed(1, 65, 1, ts = T0 + 10)))
        log.drain()

        val event = setOf(
            log.at(1, 64, 1, 0, Long.MAX_VALUE, limit = 100, reverse = false).single().eventId,
            log.at(2, 64, 1, 0, Long.MAX_VALUE, limit = 100, reverse = false).single().eventId,
            log.at(3, 64, 1, 0, Long.MAX_VALUE, limit = 100, reverse = false).single().eventId,
        )
        assertEquals(1, event.size)
        assertNotEquals(event.single(), log.at(1, 65, 1, 0, Long.MAX_VALUE, limit = 100, reverse = false).single().eventId)
    }

    // The second change is behind the world's newest time and its position's first row is not on disk yet,
    // only in the batch being built.
    @Test
    fun `a row behind an earlier change of the same submit is clamped to it`() {
        log.submit(
            listOf(
                placed(3, 64, 3, before = AIR, after = STONE, ts = T0 + 100),
                placed(3, 64, 3, before = STONE, after = DIRT, ts = T0),
            )
        )
        log.drain()

        val rows = log.at(3, 64, 3, 0, Long.MAX_VALUE, limit = 100, reverse = false)
        assertEquals(listOf(stateOf(STONE), stateOf(DIRT)), rows.map { it.stateAfter })
        assertEquals(listOf(T0 + 100, T0 + 100), rows.map { it.timestamp })
        assertEquals(stateOf(DIRT), log.standingAt(3, 64, 3).row?.stateAfter)
    }

    @Test
    fun `a row behind its own position is clamped to that position and not to the world`() {
        log.submit(listOf(placed(3, 64, 3, before = AIR, after = STONE, ts = T0 + 100)))
        log.drain()
        log.submit(listOf(placed(9, 64, 9, after = WATER, ts = T0 + 500)))
        log.drain()
        log.submit(listOf(placed(3, 64, 3, before = STONE, after = DIRT, ts = T0)))
        log.drain()

        val rows = log.at(3, 64, 3, 0, Long.MAX_VALUE, limit = 100, reverse = false)
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

        val rows = reopened.at(8, 64, 8, 0, Long.MAX_VALUE, limit = 100, reverse = false)
        assertEquals(listOf(stateOf(STONE), stateOf(DIRT)), rows.map { it.stateAfter })
        assertEquals(listOf(T0 + 100, T0 + 100), rows.map { it.timestamp })
        assertEquals(stateOf(DIRT), reopened.standingAt(8, 64, 8).row?.stateAfter)
    }

    @Test
    fun `rows and the event counter survive a close and a reopen`() {
        log.submit(listOf(placed(5, 64, 5)))
        log.drain()
        val firstEvent = log.at(5, 64, 5, 0, Long.MAX_VALUE, limit = 100, reverse = false).single().eventId
        logs.close(world)

        val reopened = logs.open(world)
        assertEquals(stateOf(STONE), reopened.at(5, 64, 5, 0, Long.MAX_VALUE, limit = 100, reverse = false).single().stateAfter)
        reopened.submit(listOf(placed(6, 64, 5, ts = T0 + 10)))
        reopened.drain()
        assertTrue(reopened.at(6, 64, 5, 0, Long.MAX_VALUE, limit = 100, reverse = false).single().eventId > firstEvent)
    }

    @Test
    fun `a read or a write after close never reaches the handle`() {
        assertTrue(log.submit(listOf(placed(5, 64, 5))))
        log.drain()
        logs.close(world)

        assertTrue(log.at(5, 64, 5, 0, Long.MAX_VALUE, limit = 100, reverse = false).isEmpty())
        assertTrue(log.inChunk(0, 0, 0, Long.MAX_VALUE, limit = 100, reverse = false).isEmpty())
        assertNull(log.standingAt(5, 64, 5).row)
        assertFalse(log.submit(listOf(placed(9, 64, 9))))
        log.drain()
        assertTrue(logs.open(world).at(9, 64, 9, 0, Long.MAX_VALUE, limit = 100, reverse = false).isEmpty())
    }
}
