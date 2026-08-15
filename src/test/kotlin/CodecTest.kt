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
                    kind = if (seed % 3 == 0L) Kind.TRANSFER else Kind.MUTATE,
                    cause = Cause.QUICK_MOVE,
                    confidence = if (seed % 2 == 0L) Confidence.FACT else Confidence.INFERRED,
                    counterparty = counterparty,
                    itemFormId = 1_000_000_000_000L + seed,
                    qty = if (seed % 2 == 0L) -64 else 3,
                    damage = if (seed % 4 == 0L) null else 231,
                    provenanceId = null,
                    actor = if (seed % 3 == 0L) playerA else null,
                )
                val key = EntryCodec.key(entry.holder, entry.timestamp, entry.txId, registries)
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
        val key = EntryCodec.key(holder, 7L, 8L, registries)
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
        val key = EntryCodec.key(holder, 1L, 1L, registries)
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
    // change applied to both sides, and the layout is what already sits in every database on disk.
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
            provenanceId = null,
            actor = null,
        )
        assertArrayEquals(
            byteArrayOf(
                0x05, 0x00,
                0x95.toByte(), 0x55, 0x55, 0x55, 0x55, 0x2D, 0x48, 0x80.toByte(), 0x40,
                0x00, 0x00, 0x01, 0x8B.toByte(), 0xCF.toByte(), 0xE5.toByte(), 0x68, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07,
            ),
            EntryCodec.key(stored.holder, stored.timestamp, stored.txId, registries),
        )
        assertArrayEquals(
            byteArrayOf(0x00, 0x10, 0x05, 0x00, 0x00, 0x09, 0xAC.toByte(), 0x02, 0x3F),
            EntryCodec.value(stored, registries),
        )
    }

    @Test
    fun `holders without their own rows are rejected as keys`() {
        assertThrows(IllegalArgumentException::class.java) { EntryCodec.key(Void, 1L, 1L, registries) }
        assertThrows(IllegalArgumentException::class.java) { EntryCodec.key(MenuSlot(1, 2), 1L, 1L, registries) }
        assertThrows(IllegalArgumentException::class.java) { EntryCodec.holderPrefix(Void, registries) }
    }

    @Test
    fun `holder prefix catches every entry of that holder`() {
        val holder = Container(worldA, -80, 63, 112, 4)
        val prefix = EntryCodec.holderPrefix(holder, registries)
        for (timestamp in listOf(0L, 1L, 1_700_000_000_000L, Long.MAX_VALUE)) {
            assertTrue(EntryCodec.key(holder, timestamp, timestamp, registries).startsWith(prefix))
        }
        assertFalse(EntryCodec.key(holder.copy(x = -81), 1L, 1L, registries).startsWith(prefix))
        assertTrue(EntryCodec.key(holder.copy(slot = 26), 1L, 1L, registries).startsWith(prefix))
    }

    @Test
    fun `chunk prefix catches every block of its chunk and nothing else`() {
        val chunkX = -5
        val chunkZ = 7
        val prefix = EntryCodec.containerChunkPrefix(worldA, chunkX, chunkZ, registries)
        assertEquals(2 + Zcode.CHUNK_PREFIX_SIZE, prefix.size)
        for (localX in 0..15) {
            for (localZ in 0..15) {
                for (y in listOf(-64, 0, 319)) {
                    val holder = Container(worldA, chunkX * 16 + localX, y, chunkZ * 16 + localZ, localX)
                    assertTrue(EntryCodec.key(holder, 1L, 1L, registries).startsWith(prefix)) { "$holder" }
                }
            }
        }
        for (neighbour in listOf(
            Container(worldA, chunkX * 16 - 1, 0, chunkZ * 16, 0),
            Container(worldA, chunkX * 16 + 16, 0, chunkZ * 16, 0),
            Container(worldA, chunkX * 16, 0, chunkZ * 16 - 1, 0),
            Container(worldA, chunkX * 16, 0, chunkZ * 16 + 16, 0),
            Container(worldB, chunkX * 16, 0, chunkZ * 16, 0),
        )) {
            assertFalse(EntryCodec.key(neighbour, 1L, 1L, registries).startsWith(prefix)) { "$neighbour" }
        }
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
        provenanceId = null,
        actor = null,
    )

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && copyOf(prefix.size).contentEquals(prefix)
}
