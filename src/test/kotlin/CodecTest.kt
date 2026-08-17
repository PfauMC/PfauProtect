package io.pfaumc.pfauprotect

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

internal class MemoryRegistryStore : RegistryStore {
    private val rows = mutableListOf<RegistryRow>()
    private val counters = mutableMapOf<RegistryNamespace, Int>()

    override fun loadAll(): List<RegistryRow> = rows.toList()

    override fun put(ns: RegistryNamespace, keyBytes: ByteArray, id: Int) {
        rows += RegistryRow(ns, keyBytes, id)
    }

    override fun nextId(ns: RegistryNamespace): Int {
        val id = counters.getOrDefault(ns, 0)
        counters[ns] = id + 1
        return id
    }
}

class CodecTest {
    private val registries = Registries(MemoryRegistryStore())

    private val playerA = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val playerB = UUID.fromString("00000000-0000-4000-8000-0000000000b2")
    private val entity = UUID.fromString("00000000-0000-4000-8000-0000000000c3")
    private val worldA = UUID.fromString("00000000-0000-4000-8000-0000000000d4")
    private val worldB = UUID.fromString("00000000-0000-4000-8000-0000000000e5")

    private val intSamples = listOf(
        0, 1, -1, 2, -2, 63, 64, 127, 128, 255, 256, 16383, 16384, 2097151, 2097152,
        Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE - 1, Int.MIN_VALUE + 1, 1234567, -1234567,
    )

    private val longSamples = listOf(
        0L, 1L, -1L, 127L, 128L, 4294967295L, 4294967296L,
        Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
        Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(),
    )

    @Test
    fun `varint round trips over int boundaries`() {
        for (v in intSamples) {
            val encoded = ByteWriter().varInt(v).toByteArray()
            assertTrue(encoded.size <= 5) { "varint $v took ${encoded.size} bytes" }
            if (v < 0) assertEquals(5, encoded.size) { "negative varint $v must take 5 bytes" }
            val reader = ByteReader(encoded)
            assertEquals(v, reader.varInt())
            assertEquals(0, reader.remaining)
        }
    }

    @Test
    fun `varlong round trips over long boundaries`() {
        for (v in longSamples) {
            val encoded = ByteWriter().varLong(v).toByteArray()
            assertTrue(encoded.size <= 10) { "varlong $v took ${encoded.size} bytes" }
            val reader = ByteReader(encoded)
            assertEquals(v, reader.varLong())
            assertEquals(0, reader.remaining)
        }
    }

    @Test
    fun `zigzag round trips over int boundaries and keeps small negatives short`() {
        for (v in intSamples) {
            val encoded = ByteWriter().zigZagInt(v).toByteArray()
            assertEquals(v, ByteReader(encoded).zigZagInt())
        }
        assertEquals(1, ByteWriter().zigZagInt(-1).size)
        assertEquals(1, ByteWriter().zigZagInt(-64).size)
    }

    @Test
    fun `longBE and uuid round trip`() {
        for (v in longSamples) assertEquals(v, ByteReader(ByteWriter().longBE(v).toByteArray()).longBE())
        val encoded = ByteWriter().uuid(playerA).toByteArray()
        assertEquals(16, encoded.size)
        assertEquals(playerA, ByteReader(encoded).uuid())
    }

    @Test
    fun `no valid varint encoding is a prefix of another`() {
        val values = (intSamples + (0..600) + (16000..16600)).distinct()
        val encodings = values.map { it to ByteWriter().varInt(it).toByteArray() }
        for ((leftValue, left) in encodings) {
            for ((rightValue, right) in encodings) {
                if (leftValue == rightValue) continue
                assertFalse(right.size >= left.size && right.copyOf(left.size).contentEquals(left)) {
                    "encoding of $leftValue is a prefix of the encoding of $rightValue"
                }
            }
        }
    }

    @Test
    fun `truncated and overlong reads fail with a clear error`() {
        assertThrows(IllegalArgumentException::class.java) { ByteReader(ByteArray(0)).byte() }
        assertThrows(IllegalArgumentException::class.java) { ByteReader(byteArrayOf(1, 2, 3)).longBE() }
        assertThrows(IllegalArgumentException::class.java) { ByteReader(byteArrayOf(1, 2, 3)).bytes(4) }
        val overlongInt = ByteArray(6) { if (it == 5) 0x00 else 0x80.toByte() }
        assertThrows(IllegalArgumentException::class.java) { ByteReader(overlongInt).varInt() }
        val overlongLong = ByteArray(11) { if (it == 10) 0x00 else 0x80.toByte() }
        assertThrows(IllegalArgumentException::class.java) { ByteReader(overlongLong).varLong() }
    }

    @Test
    fun `position round trips at coordinate limits`() {
        val horizontal = listOf(0, 1, -1, 15, 16, -16, -17, 1234567, -1234567, 134217727, -134217728)
        val vertical = listOf(-32768, -64, -1, 0, 1, 319, 32767)
        for (x in horizontal) {
            for (z in horizontal) {
                for (y in vertical) {
                    val encoded = Zcode.encode(x, y, z)
                    assertEquals(Zcode.SIZE, encoded.size)
                    assertArrayEquals(intArrayOf(x, y, z), Zcode.decode(encoded)) { "position $x $y $z" }
                    assertArrayEquals(
                        Zcode.chunkPrefix(x shr 4, z shr 4),
                        encoded.copyOf(Zcode.CHUNK_PREFIX_SIZE),
                    )
                }
            }
        }
        val padded = ByteArray(3) + Zcode.encode(7, 8, 9)
        assertArrayEquals(intArrayOf(7, 8, 9), Zcode.decode(padded, 3))
    }

    @Test
    fun `position rejects coordinates outside the encodable range`() {
        assertThrows(IllegalArgumentException::class.java) { Zcode.encode(0, 32768, 0) }
        assertThrows(IllegalArgumentException::class.java) { Zcode.encode(0, -32769, 0) }
        assertThrows(IllegalArgumentException::class.java) { Zcode.encode(134217728, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { Zcode.encode(0, 0, -134217745) }
        assertThrows(IllegalArgumentException::class.java) { Zcode.chunkPrefix(0x800000, 0) }
        assertThrows(IllegalArgumentException::class.java) { Zcode.decode(ByteArray(8)) }
    }

    @Test
    fun `neighbouring chunks get different prefixes`() {
        val prefix = Zcode.chunkPrefix(-5, 7)
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                assertFalse(prefix.contentEquals(Zcode.chunkPrefix(-5 + dx, 7 + dz)))
            }
        }
    }

    @Test
    fun `entry round trips through key and value`() {
        val holders = listOf(
            PlayerInv(playerA, 17),
            PlayerEquip(playerA, 3),
            PlayerCursor(playerB),
            PlayerEnder(playerB, 26),
            Container(worldA, -1345, -61, 700, 5),
            Container(worldB, 134217727, 32767, -134217728, 0),
            WorldBlock(worldA, -1345, -61, 700),
            WorldBlock(worldB, 134217727, 32767, -134217728),
            EntitySlot(entity, 2),
            ItemEntityRef(entity),
            Nested(entity, 4),
        )
        val counterparties = holders + listOf(MenuSlot(9, 41), Void)
        var seed = 0L
        for (holder in holders) {
            for (counterparty in counterparties) {
                val entry = LedgerEntry(
                    holder = holder,
                    timestamp = 1_700_000_000_000L + seed,
                    txId = 900_000L + seed,
                    ordinal = (seed % (EntryCodec.MAX_ORDINAL + 1)).toInt(),
                    kind = if (seed % 3 == 0L) Kind.TRANSFER else Kind.MUTATE,
                    cause = Cause.QUICK_MOVE,
                    confidence = if (seed % 2 == 0L) Confidence.FACT else Confidence.INFERRED,
                    counterparty = counterparty,
                    itemFormId = 1_000_000_000_000L + seed,
                    qty = if (seed % 2 == 0L) -64 else 3,
                    damage = if (seed % 4 == 0L) null else 231,
                    actor = if (seed % 3 == 0L) playerA else null,
                )
                val key = EntryCodec.key(entry.holder, entry.timestamp, entry.txId, entry.ordinal, registries)
                val value = EntryCodec.value(entry, registries)
                assertEquals(entry, EntryCodec.decode(key, value, registries))
                seed++
            }
        }
    }

    @Test
    fun `ordinary entry header byte is zero`() {
        assertEquals(0x00, EntryCodec.header(Kind.TRANSFER, Confidence.FACT))
        val value = EntryCodec.value(entry(PlayerInv(playerA, 0), Void), registries)
        assertEquals(0x00, value[0].toInt())
        assertEquals(0x20, EntryCodec.header(Kind.TRANSFER, Confidence.INFERRED))
        assertEquals(0x40, EntryCodec.header(Kind.MUTATE, Confidence.FACT))
        assertEquals(0xA0, EntryCodec.header(Kind.CLONE, Confidence.INFERRED))
        assertEquals(0x10, EntryCodec.header(Kind.TRANSFER, Confidence.FACT, actor = true))
    }

    // The flag has to live above the version field, which keeps rows written before the field existed
    // decodable: their reserved bits are zero, so they simply carry no actor.
    @Test
    fun `an actor is written only when present and leaves older rows readable`() {
        val holder = Container(worldA, 4, 70, 9, 2)
        val withActor = entry(holder, Void).copy(cause = Cause.LOOT_GENERATE, qty = 3, actor = playerA)
        val key = EntryCodec.key(holder, 7L, 8L, 0, registries)
        val value = EntryCodec.value(withActor, registries)
        assertEquals(0x10, value[0].toInt() and 0x10)
        assertEquals(playerA, EntryCodec.decode(key, value, registries).actor)

        val without = EntryCodec.value(withActor.copy(actor = null), registries)
        assertEquals(0x00, without[0].toInt() and 0x10)
        assertNull(EntryCodec.decode(key, without, registries).actor)
        assertEquals(value.size - without.size, 1)
    }

    @Test
    fun `records of another version are skipped rather than decoded`() {
        val holder = PlayerInv(playerA, 0)
        val key = EntryCodec.key(holder, 1L, 1L, 0, registries)
        for (version in 1..7) {
            val value = EntryCodec.value(entry(holder, Void), registries)
            value[0] = ((value[0].toInt() and 0xF8) or version).toByte()
            assertNull(EntryCodec.decodeOrNull(key, value, registries)) { "version $version was decoded" }
            assertThrows(IllegalArgumentException::class.java) { EntryCodec.decode(key, value, registries) }
        }
    }

    @Test
    fun `cause ids are unique and answer to byId`() {
        assertEquals(Cause.entries.size, Cause.entries.map { it.id }.distinct().size)
        for (cause in Cause.entries) {
            assertTrue(cause.id in 0..255) { "$cause has id ${cause.id}" }
            assertEquals(cause, Cause.byId(cause.id))
        }
    }

    // Written out by hand rather than taken from the encoder: a round trip cannot notice a layout
    // change applied to both sides, and a silent change to the layout is a database that reads back
    // as something other than what was written.
    @Test
    fun `stored bytes keep the layout the database was written with`() {
        assertArrayEquals(
            byteArrayOf(0x95.toByte(), 0x55, 0x55, 0x55, 0x55, 0x2D, 0x48, 0x80.toByte(), 0x40),
            Zcode.encode(100, 64, -200),
        )
        assertArrayEquals(
            byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80.toByte(), 0x00),
            Zcode.encode(0, 0, 0),
        )
        assertArrayEquals(
            byteArrayOf(0x3F, -1, -1, -1, -1, -1, -1, 0x7F, 0xC0.toByte()),
            Zcode.encode(-1, -64, -1),
        )

        assertEquals(0, registries.idForUuid(RegistryNamespace.WORLD, worldA))
        assertEquals(0, registries.idForUuid(RegistryNamespace.PLAYER, playerA))
        val stored = LedgerEntry(
            holder = Container(worldA, 100, 64, -200, 5),
            timestamp = 1_700_000_000_000L,
            txId = 7L,
            kind = Kind.TRANSFER,
            cause = Cause.CONTAINER_ADD,
            confidence = Confidence.FACT,
            counterparty = PlayerInv(playerA, 9),
            itemFormId = 300L,
            qty = -32,
            damage = null,
            actor = null,
        )
        assertArrayEquals(
            byteArrayOf(
                0x05, 0x00, 0x00,
                0x95.toByte(), 0x55, 0x55, 0x55, 0x55, 0x2D, 0x48, 0x80.toByte(), 0x40,
                0x00, 0x00, 0x01, 0x8B.toByte(), 0xCF.toByte(), 0xE5.toByte(), 0x68, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07,
                0x00,
            ),
            EntryCodec.key(stored.holder, stored.timestamp, stored.txId, stored.ordinal, registries),
        )
        assertArrayEquals(
            byteArrayOf(0x00, 0x10, 0x05, 0x00, 0x00, 0x09, 0xAC.toByte(), 0x02, 0x3F),
            EntryCodec.value(stored, registries),
        )

        // The same position as a counterparty: the world number is the same two fixed bytes in the
        // value as it is in the key, and the ordinal is the last byte of the key whatever the holder.
        val facingBack = stored.copy(
            holder = PlayerInv(playerA, 9),
            ordinal = 1,
            cause = Cause.CONTAINER_REMOVE,
            counterparty = Container(worldA, 100, 64, -200, 5),
            qty = 32,
        )
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00,
                0x00, 0x00, 0x01, 0x8B.toByte(), 0xCF.toByte(), 0xE5.toByte(), 0x68, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07,
                0x01,
            ),
            EntryCodec.key(facingBack.holder, facingBack.timestamp, facingBack.txId, facingBack.ordinal, registries),
        )
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x11, 0x09,
                0x05, 0x00, 0x00,
                0x95.toByte(), 0x55, 0x55, 0x55, 0x55, 0x2D, 0x48, 0x80.toByte(), 0x40,
                0x05, 0xAC.toByte(), 0x02, 0x40,
            ),
            EntryCodec.value(facingBack, registries),
        )
    }

    @Test
    fun `holders without their own rows are rejected as keys`() {
        assertThrows(IllegalArgumentException::class.java) { EntryCodec.key(Void, 1L, 1L, 0, registries) }
        assertThrows(IllegalArgumentException::class.java) { EntryCodec.key(MenuSlot(1, 2), 1L, 1L, 0, registries) }
        assertThrows(IllegalArgumentException::class.java) { EntryCodec.holderPrefix(Void, registries) }
    }

    @Test
    fun `holder prefix catches every entry of that holder`() {
        val holder = Container(worldA, -80, 63, 112, 4)
        val prefix = EntryCodec.holderPrefix(holder, registries)
        for (timestamp in listOf(0L, 1L, 1_700_000_000_000L, Long.MAX_VALUE)) {
            assertTrue(EntryCodec.key(holder, timestamp, timestamp, 0, registries).startsWith(prefix))
        }
        assertFalse(EntryCodec.key(holder.copy(x = -81), 1L, 1L, 0, registries).startsWith(prefix))
        assertTrue(EntryCodec.key(holder.copy(slot = 26), 1L, 1L, 0, registries).startsWith(prefix))
    }

    @Test
    fun `chunk prefix catches every block of its chunk and nothing else`() {
        val chunkX = -5
        val chunkZ = 7
        val prefixes = EntryCodec.blockChunkPrefixes(worldA, chunkX, chunkZ, registries)
        assertEquals(2, prefixes.size)
        for (prefix in prefixes) assertEquals(EntryCodec.CHUNK_PREFIX_SIZE, prefix.size)
        for (localX in 0..15) {
            for (localZ in 0..15) {
                for (y in listOf(-64, 0, 319)) {
                    val x = chunkX * 16 + localX
                    val z = chunkZ * 16 + localZ
                    for (holder in listOf(Container(worldA, x, y, z, localX), WorldBlock(worldA, x, y, z))) {
                        val key = EntryCodec.key(holder, 1L, 1L, 0, registries)
                        assertTrue(prefixes.any { key.startsWith(it) }) { "$holder" }
                    }
                }
            }
        }
        for (neighbour in listOf(
            Container(worldA, chunkX * 16 - 1, 0, chunkZ * 16, 0),
            Container(worldA, chunkX * 16 + 16, 0, chunkZ * 16, 0),
            Container(worldA, chunkX * 16, 0, chunkZ * 16 - 1, 0),
            Container(worldA, chunkX * 16, 0, chunkZ * 16 + 16, 0),
            Container(worldB, chunkX * 16, 0, chunkZ * 16, 0),
            WorldBlock(worldA, chunkX * 16 - 1, 0, chunkZ * 16),
            WorldBlock(worldB, chunkX * 16, 0, chunkZ * 16),
        )) {
            val key = EntryCodec.key(neighbour, 1L, 1L, 0, registries)
            assertFalse(prefixes.any { key.startsWith(it) }) { "$neighbour" }
        }
    }

    // The store sizes a prefix extractor for this length and hands every key shorter than it to a
    // plain scan, so a world number whose width followed how many worlds the server has would move
    // the chunk out from under the extractor on the second world.
    @Test
    fun `the chunk part of a position key is the same length whatever the world or the position`() {
        val worlds = List(300) { UUID.nameUUIDFromBytes("w$it".toByteArray()) }
        for (world in worlds) registries.idForUuid(RegistryNamespace.WORLD, world)
        assertTrue(registries.idForUuid(RegistryNamespace.WORLD, worlds.last()) > 0xFF)

        for (world in listOf(worlds.first(), worlds[200], worlds.last())) {
            for (position in listOf(intArrayOf(0, 0, 0), intArrayOf(-1, -64, -1), intArrayOf(134217727, 32767, -134217728))) {
                val (x, y, z) = position.toList()
                val chunk = EntryCodec.blockChunkPrefixes(world, x shr 4, z shr 4, registries)
                for (prefix in chunk) assertEquals(EntryCodec.CHUNK_PREFIX_SIZE, prefix.size)
                for (holder in listOf(Container(world, x, y, z, 3), WorldBlock(world, x, y, z))) {
                    val key = EntryCodec.key(holder, 1L, 1L, 0, registries)
                    assertTrue(chunk.any { key.startsWith(it) }) { "$holder in world $world" }
                }
            }
        }
    }

    // Both holders address a position and their keys differ only in the leading byte, so a scan for
    // one that also matched the other would answer a question about chest contents with block placements.
    @Test
    fun `a block and a container at one position keep separate keys`() {
        val container = Container(worldA, 100, 64, -200, 0)
        val block = WorldBlock(worldA, 100, 64, -200)
        val containerPrefix = EntryCodec.holderPrefix(container, registries)
        val blockPrefix = EntryCodec.holderPrefix(block, registries)
        assertFalse(containerPrefix.contentEquals(blockPrefix))
        assertFalse(EntryCodec.key(block, 1L, 1L, 0, registries).startsWith(containerPrefix))
        assertFalse(EntryCodec.key(container, 1L, 1L, 0, registries).startsWith(blockPrefix))
        assertTrue(EntryCodec.key(block, 1L, 1L, 0, registries).startsWith(blockPrefix))
    }

    private fun entry(holder: Holder, counterparty: Holder) = LedgerEntry(
        holder = holder,
        timestamp = 1_700_000_000_000L,
        txId = 7L,
        kind = Kind.TRANSFER,
        cause = Cause.PICKUP,
        confidence = Confidence.FACT,
        counterparty = counterparty,
        itemFormId = 12L,
        qty = 1,
        damage = null,
        actor = null,
    )

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && copyOf(prefix.size).contentEquals(prefix)
}
