package io.pfaumc.pfauprotect.storage
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.ledgerEntry
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.MenuSlot
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerEnder
import io.pfaumc.pfauprotect.model.PlayerEquip
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
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

    override fun persist(rows: List<RegistryRow>) {
        this.rows += rows
    }

    override fun nextId(ns: RegistryNamespace): Int {
        val id = counters.getOrDefault(ns, 0)
        counters[ns] = id + 1
        return id
    }
}

class CodecTest {
    private val registries = Registries(MemoryRegistryStore())

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
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10),
            ByteWriter().varLong(4294967296L).toByteArray(),
        )
    }

    @Test
    fun `zigzag round trips over int boundaries and keeps small negatives short`() {
        for (v in intSamples) {
            val encoded = ByteWriter().zigZagInt(v).toByteArray()
            assertEquals(v, ByteReader(encoded).zigZagInt())
        }
        assertEquals(1, ByteWriter().zigZagInt(-1).toByteArray().size)
        assertEquals(1, ByteWriter().zigZagInt(-64).toByteArray().size)
    }

    @Test
    fun `truncated and overlong reads fail with a clear error`() {
        assertThrows(IllegalArgumentException::class.java) { ByteReader(ByteArray(0)).byte() }
        assertThrows(IllegalArgumentException::class.java) { ByteReader(byteArrayOf(1, 2, 3)).longBE() }
        assertThrows(IllegalArgumentException::class.java) { ByteReader(byteArrayOf(1, 2, 3)).bytes(4) }
        val overlongInt = ByteArray(6) { if (it == 5) 0x01 else 0x80.toByte() }
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
        assertThrows(IllegalArgumentException::class.java) { Zcode.encode(0, 0, -134217729) }
        assertThrows(IllegalArgumentException::class.java) { Zcode.chunkPrefix(0x800000, 0) }
        assertThrows(IllegalArgumentException::class.java) { Zcode.decode(ByteArray(8)) }
    }

    @Test
    fun `entry round trips through key and value`() {
        val holders = listOf(
            PlayerInv(alice, 17),
            PlayerEquip(alice, 3),
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
                    timestamp = T0 + seed,
                    txId = 900_000L + seed,
                    ordinal = (seed % (EntryCodec.MAX_ORDINAL + 1)).toInt(),
                    kind = if (seed % 3 == 0L) Kind.TRANSFER else Kind.MUTATE,
                    cause = Cause.QUICK_MOVE,
                    confidence = if (seed % 2 == 0L) Confidence.FACT else Confidence.INFERRED,
                    counterparty = counterparty,
                    itemFormId = 1_000_000_000_000L + seed,
                    qty = if (seed % 2 == 0L) -64 else 3,
                    damage = if (seed % 4 == 0L) null else 231,
                    actor = if (seed % 3 == 0L) alice else null,
                )
                val key = EntryCodec.key(entry.holder, entry.timestamp, entry.txId, entry.ordinal, registries)
                val value = EntryCodec.value(entry, registries)
                assertEquals(entry, EntryCodec.decodeOrNull(key, value, registries))
                seed++
            }
        }
    }

    @Test
    fun `records of another version are skipped rather than decoded`() {
        val holder = PlayerInv(alice, 0)
        val key = EntryCodec.key(holder, 1L, 1L, 0, registries)
        for (version in 1..7) {
            val value = EntryCodec.value(ledgerEntry(holder, Void), registries)
            value[0] = ((value[0].toInt() and 0xF8) or version).toByte()
            assertNull(EntryCodec.decodeOrNull(key, value, registries)) { "version $version was decoded" }
        }
    }

    @Test
    fun `a record too short to read is skipped rather than throwing`() {
        val holder = PlayerInv(alice, 0)
        val key = EntryCodec.key(holder, 1L, 1L, 0, registries)
        val value = EntryCodec.value(ledgerEntry(holder, Void), registries)
        assertEveryPrefixRejected(key, value) { k, v -> EntryCodec.decodeOrNull(k, v, registries) }
    }

    // Causes are added without touching the record version, so a row from a newer build lands in a
    // scan of an older one. Throwing there would take the whole scan down over a label.
    @Test
    fun `a cause with no name in this build decodes rather than stopping the scan`() {
        val holder = PlayerInv(alice, 0)
        val key = EntryCodec.key(holder, 1L, 1L, 0, registries)
        val value = EntryCodec.value(ledgerEntry(holder, Void), registries)
        value[1] = (0..255).first { id -> Cause.entries.none { it.id == id } }.toByte()

        val decoded = EntryCodec.decodeOrNull(key, value, registries)
        assertEquals(Cause.UNKNOWN, decoded?.cause)
        assertEquals(1, decoded?.qty)
        assertEquals(12L, decoded?.itemFormId)
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

        assertEquals(0, registries.id(RegistryNamespace.WORLD, worldA))
        assertEquals(0, registries.id(RegistryNamespace.PLAYER, alice))
        val stored = LedgerEntry(
            holder = Container(worldA, 100, 64, -200, 5),
            timestamp = T0,
            txId = 7L,
            kind = Kind.TRANSFER,
            cause = Cause.CONTAINER_ADD,
            confidence = Confidence.FACT,
            counterparty = PlayerInv(alice, 9),
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
            holder = PlayerInv(alice, 9),
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
    fun `stored bytes of the other holders, an actor and a damage keep their layout`() {
        assertEquals(0, registries.id(RegistryNamespace.PLAYER, alice))
        assertEquals(1, registries.id(RegistryNamespace.PLAYER, playerB))
        val entityBytes = byteArrayOf(0, 0, 0, 0, 0, 0, 0x40, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0xC3.toByte())
        val timeAndTx = byteArrayOf(
            0x00, 0x00, 0x01, 0x8B.toByte(), 0xCF.toByte(), 0xE5.toByte(), 0x68, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07,
        )
        fun entry(holder: Holder, counterparty: Holder, qty: Int) =
            LedgerEntry(
                holder = holder, timestamp = T0, txId = 7L, ordinal = 2, kind = Kind.TRANSFER,
                cause = Cause.CONTAINER_ADD, confidence = Confidence.FACT, counterparty = counterparty,
                itemFormId = 1L, qty = qty, damage = null, actor = null,
            )
        fun assertPinned(entry: LedgerEntry, value: ByteArray) {
            assertArrayEquals(value, EntryCodec.value(entry, registries))
            val key = EntryCodec.key(entry.holder, entry.timestamp, entry.txId, entry.ordinal, registries)
            assertEquals(entry, EntryCodec.decodeOrNull(key, value, registries))
        }

        assertPinned(
            entry(PlayerEquip(playerB, 2), EntitySlot(entity, 300), 5)
                .copy(kind = Kind.MUTATE, confidence = Confidence.NEARBY, actor = playerB, damage = 231),
            byteArrayOf(
                // mutate, the high confidence bit for nearby, an actor
                0x58, 0x10, 0x02,
                0x06, *entityBytes, 0xAC.toByte(), 0x02,
                0x01, 0x0A,
                0x01,
                0xE7.toByte(), 0x01,
            ),
        )
        assertPinned(
            entry(PlayerCursor(alice), MenuSlot(9, 41), -1),
            byteArrayOf(0x00, 0x10, 0x00, 0x04, 0x09, 0x29, 0x01, 0x01),
        )
        assertPinned(
            entry(PlayerCursor(alice), MenuSlot(9, 41), -1).copy(kind = Kind.CLONE, confidence = Confidence.INFERRED),
            byteArrayOf(0xA0.toByte(), 0x10, 0x00, 0x04, 0x09, 0x29, 0x01, 0x01),
        )
        // A cursor holds one thing, so as a counterparty it is written without a slot.
        assertPinned(
            entry(PlayerInv(alice, 4), PlayerCursor(alice), 1),
            byteArrayOf(0x00, 0x10, 0x04, 0x02, 0x00, 0x01, 0x02),
        )
        // A position as a holder has no slot, and the Void as a counterparty is its type byte alone.
        assertPinned(
            entry(WorldBlock(worldA, 100, 64, -200), Void, 1),
            byteArrayOf(0x00, 0x10, 0x00, 0x09, 0x01, 0x02),
        )
        assertPinned(
            entry(PlayerEnder(playerB, 26), PlayerEnder(alice, 3), -7),
            byteArrayOf(0x00, 0x10, 0x1A, 0x03, 0x00, 0x03, 0x01, 0x0D),
        )

        assertArrayEquals(
            byteArrayOf(0x02, 0x00, *timeAndTx, 0x02),
            EntryCodec.key(PlayerCursor(alice), T0, 7L, 2, registries),
        )
        assertArrayEquals(
            byteArrayOf(0x08, *entityBytes, *timeAndTx, 0x02),
            EntryCodec.key(Nested(entity, 4), T0, 7L, 2, registries),
        )
        assertArrayEquals(
            byteArrayOf(0x07, *entityBytes, *timeAndTx, 0x02),
            EntryCodec.key(ItemEntityRef(entity), T0, 7L, 2, registries),
        )
        assertArrayEquals(
            byteArrayOf(0x03, 0x01, *timeAndTx, 0x02),
            EntryCodec.key(PlayerEnder(playerB, 26), T0, 7L, 2, registries),
        )
        assertEquals(0, registries.id(RegistryNamespace.WORLD, worldA))
        assertArrayEquals(
            byteArrayOf(
                0x0A, 0x00, 0x00,
                0x95.toByte(), 0x55, 0x55, 0x55, 0x55, 0x2D, 0x48, 0x80.toByte(), 0x40,
                *timeAndTx, 0x02,
            ),
            EntryCodec.key(WorldBlock(worldA, 100, 64, -200), T0, 7L, 2, registries),
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

    // The store sizes a prefix extractor for this length and hands every key shorter than it to a
    // plain scan, so a world number whose width followed how many worlds the server has would move
    // the chunk out from under the extractor once there are more worlds than a byte holds.
    @Test
    fun `chunk prefix catches every block of its chunk and nothing else`() {
        val fillers = List(0x100) { UUID.nameUUIDFromBytes("w$it".toByteArray()) }
        for (world in fillers) registries.id(RegistryNamespace.WORLD, world)
        assertEquals(0x100, registries.id(RegistryNamespace.WORLD, worldA))
        val chunkX = -5
        val chunkZ = 7
        val prefixes = EntryCodec.blockChunkPrefixes(worldA, chunkX, chunkZ, registries)
        assertEquals(2, prefixes.size)
        for (prefix in prefixes + EntryCodec.blockChunkPrefixes(fillers.first(), chunkX, chunkZ, registries)) {
            assertEquals(EntryCodec.CHUNK_PREFIX_SIZE, prefix.size)
        }
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
}
