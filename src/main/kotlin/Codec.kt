package io.pfaumc.pfauprotect

import java.util.UUID

// A plain array rather than a ByteArrayOutputStream: every method of that class is synchronized, and
// a form is written a byte at a time, once per slot, on every inventory pass.
class ByteWriter(initialCapacity: Int = 32) {
    private var buf = ByteArray(maxOf(initialCapacity, 1))
    private var len = 0

    val size: Int get() = len

    fun byte(v: Int): ByteWriter {
        room(1)
        buf[len++] = v.toByte()
        return this
    }

    fun bytes(v: ByteArray): ByteWriter {
        room(v.size)
        v.copyInto(buf, len)
        len += v.size
        return this
    }

    private fun room(more: Int) {
        if (len + more > buf.size) buf = buf.copyOf(maxOf(len + more, buf.size * 2))
    }

    fun varInt(v: Int): ByteWriter {
        var rest = v
        while (true) {
            val chunk = rest and 0x7F
            rest = rest ushr 7
            if (rest == 0) return byte(chunk)
            byte(chunk or 0x80)
        }
    }

    fun varLong(v: Long): ByteWriter {
        var rest = v
        while (true) {
            val chunk = (rest and 0x7FL).toInt()
            rest = rest ushr 7
            if (rest == 0L) return byte(chunk)
            byte(chunk or 0x80)
        }
    }

    fun zigZagInt(v: Int): ByteWriter = varInt((v shl 1) xor (v shr 31))

    fun shortBE(v: Int): ByteWriter = byte((v ushr 8) and 0xFF).byte(v and 0xFF)

    fun longBE(v: Long): ByteWriter {
        for (shift in 56 downTo 0 step 8) byte((v ushr shift).toInt() and 0xFF)
        return this
    }

    fun uuid(v: UUID): ByteWriter = longBE(v.mostSignificantBits).longBE(v.leastSignificantBits)

    fun toByteArray(): ByteArray = buf.copyOf(len)
}

class ByteReader(private val buf: ByteArray) {
    private var pos = 0

    val remaining: Int get() = buf.size - pos

    fun byte(): Int {
        require(pos < buf.size) { "read past end of buffer at position $pos" }
        return buf[pos++].toInt() and 0xFF
    }

    fun bytes(n: Int): ByteArray {
        require(n in 0..remaining) { "cannot read $n bytes at position $pos, $remaining remaining" }
        val result = buf.copyOfRange(pos, pos + n)
        pos += n
        return result
    }

    fun varInt(): Int {
        var result = 0
        var shift = 0
        while (shift < 35) {
            val b = byte()
            result = result or ((b and 0x7F) shl shift)
            if (b < 0x80) return result
            shift += 7
        }
        throw IllegalArgumentException("varint longer than 5 bytes at position $pos")
    }

    fun varLong(): Long {
        var result = 0L
        var shift = 0
        while (shift < 70) {
            val b = byte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
        throw IllegalArgumentException("varlong longer than 10 bytes at position $pos")
    }

    fun zigZagInt(): Int {
        val v = varInt()
        return (v ushr 1) xor -(v and 1)
    }

    fun shortBE(): Int = (byte() shl 8) or byte()

    fun longBE(): Long {
        var result = 0L
        repeat(8) { result = (result shl 8) or byte().toLong() }
        return result
    }

    fun uuid(): UUID = UUID(longBE(), longBE())
}

object Zcode {
    const val SIZE = 9
    const val CHUNK_PREFIX_SIZE = 6

    private const val CHUNK_BIAS = 0x800000
    private const val MIN_CHUNK = -CHUNK_BIAS
    private const val MAX_CHUNK = CHUNK_BIAS - 1
    private const val Y_BIAS = 32768
    private const val MIN_Y = -Y_BIAS
    private const val MAX_Y = Y_BIAS - 1

    fun encode(x: Int, y: Int, z: Int): ByteArray {
        require(y in MIN_Y..MAX_Y) { "y $y outside $MIN_Y..$MAX_Y" }
        val out = ByteArray(SIZE)
        writeChunk(out, x shr 4, z shr 4)
        out[6] = (((x and 15) shl 4) or (z and 15)).toByte()
        val biasedY = y + Y_BIAS
        out[7] = (biasedY ushr 8).toByte()
        out[8] = biasedY.toByte()
        return out
    }

    fun chunkPrefix(chunkX: Int, chunkZ: Int): ByteArray {
        val out = ByteArray(CHUNK_PREFIX_SIZE)
        writeChunk(out, chunkX, chunkZ)
        return out
    }

    fun decode(buf: ByteArray, off: Int = 0): IntArray {
        require(off >= 0 && off + SIZE <= buf.size) { "position needs $SIZE bytes at $off, buffer holds ${buf.size}" }
        var morton = 0L
        for (i in 0 until CHUNK_PREFIX_SIZE) morton = (morton shl 8) or (buf[off + i].toLong() and 0xFF)
        var chunkX = 0
        var chunkZ = 0
        for (i in 0 until 24) {
            chunkZ = chunkZ or (((morton ushr (2 * i)) and 1L).toInt() shl i)
            chunkX = chunkX or (((morton ushr (2 * i + 1)) and 1L).toInt() shl i)
        }
        val local = buf[off + 6].toInt() and 0xFF
        val biasedY = ((buf[off + 7].toInt() and 0xFF) shl 8) or (buf[off + 8].toInt() and 0xFF)
        return intArrayOf(
            ((chunkX - CHUNK_BIAS) shl 4) or (local ushr 4),
            biasedY - Y_BIAS,
            ((chunkZ - CHUNK_BIAS) shl 4) or (local and 15),
        )
    }

    private fun writeChunk(out: ByteArray, chunkX: Int, chunkZ: Int) {
        require(chunkX in MIN_CHUNK..MAX_CHUNK) { "chunk x $chunkX outside $MIN_CHUNK..$MAX_CHUNK" }
        require(chunkZ in MIN_CHUNK..MAX_CHUNK) { "chunk z $chunkZ outside $MIN_CHUNK..$MAX_CHUNK" }
        val morton = interleave(chunkX + CHUNK_BIAS, chunkZ + CHUNK_BIAS)
        for (i in 0 until CHUNK_PREFIX_SIZE) out[i] = (morton ushr (40 - 8 * i)).toByte()
    }

    private fun interleave(oddBits: Int, evenBits: Int): Long {
        var morton = 0L
        for (i in 0 until 24) {
            morton = morton or ((evenBits.toLong() ushr i and 1L) shl (2 * i))
            morton = morton or ((oddBits.toLong() ushr i and 1L) shl (2 * i + 1))
        }
        return morton
    }
}

fun interface IdResolver {
    fun id(ns: RegistryNamespace, uuid: UUID): Int
}

fun interface IdLookup {
    fun uuid(ns: RegistryNamespace, id: Int): UUID?
}

object EntryCodec {
    const val VERSION = 0

    // Fixed width rather than a varint: the registry caps a world number at 0xFFFF anyway, and a
    // varint would make the length of everything after it depend on how many worlds the server has.
    const val WORLD_NO_SIZE = 2

    // Every key that addresses a position opens with the holder type, a fixed-width world number and
    // the chunk part of the position, so a chunk is a fixed-length prefix and the store can size a
    // prefix extractor for it. Widening the world number to two bytes is what buys the fixed length.
    const val CHUNK_PREFIX_SIZE = 1 + WORLD_NO_SIZE + Zcode.CHUNK_PREFIX_SIZE

    // The identity of a position ends here and the time follows it, so the rows of one position are a
    // single contiguous run of any walk that crosses them.
    const val POSITION_PREFIX_SIZE = 1 + WORLD_NO_SIZE + Zcode.SIZE

    // Postings of one transaction are numbered from zero in the order they are written, which is what
    // keeps two postings on one holder — a stack moved between two slots, both sides of a mutation —
    // in rows of their own.
    const val MAX_ORDINAL = 0xFF

    private const val VERSION_MASK = 0x07

    // The upper reserved bit flags an actor; the lower one stays next to the version so that field
    // can still grow into it without moving.
    private const val ACTOR_FLAG = 0x10

    fun header(kind: Kind, confidence: Confidence, actor: Boolean = false): Int =
        (kind.id shl 6) or (confidence.id shl 5) or (if (actor) ACTOR_FLAG else 0) or VERSION

    fun key(holder: Holder, timestamp: Long, txId: Long, ordinal: Int, ids: IdResolver): ByteArray {
        require(ordinal in 0..MAX_ORDINAL) { "posting ordinal $ordinal does not fit in a byte" }
        val w = ByteWriter(40)
        writeKeyIdentity(w, holder, ids)
        return w.longBE(timestamp).longBE(txId).byte(ordinal).toByteArray()
    }

    fun holderPrefix(holder: Holder, ids: IdResolver): ByteArray {
        val w = ByteWriter(24)
        writeKeyIdentity(w, holder, ids)
        return w.toByteArray()
    }

    fun blockChunkPrefixes(world: UUID, chunkX: Int, chunkZ: Int, ids: IdResolver): List<ByteArray> {
        val worldNo = ids.id(RegistryNamespace.WORLD, world)
        val chunk = Zcode.chunkPrefix(chunkX, chunkZ)
        return HolderType.POSITIONAL.map { typeId ->
            ByteWriter(CHUNK_PREFIX_SIZE).byte(typeId).shortBE(worldNo).bytes(chunk).toByteArray()
        }
    }

    // The trailing damage varint carries no presence flag, so it is only decodable while nothing can
    // follow it. Adding another field after it would need a new record version with a presence flag
    // in the reserved header bits.
    fun value(entry: LedgerEntry, ids: IdResolver): ByteArray {
        val w = ByteWriter(32)
        w.byte(header(entry.kind, entry.confidence, entry.actor != null))
        w.byte(entry.cause.id)
        w.varInt(entry.holder.slot)
        writeHolder(w, entry.counterparty, ids, withSlot = true)
        w.varLong(entry.itemFormId)
        w.zigZagInt(entry.qty)
        if (entry.actor != null) w.varInt(playerNo(entry.actor, ids))
        if (entry.damage != null) w.varInt(entry.damage)
        return w.toByteArray()
    }

    fun decode(key: ByteArray, value: ByteArray, names: IdLookup): LedgerEntry =
        decodeEntry(key, value, names)
            ?: throw IllegalArgumentException("record version ${value[0].toInt() and VERSION_MASK} is not supported")

    // Null for anything this build cannot read, a torn or truncated row as much as another layout
    // version. A scan walks rows it did not choose, and one bad row taken as a throw ends the whole
    // pass instead of being counted and stepped over.
    fun decodeOrNull(key: ByteArray, value: ByteArray, names: IdLookup): LedgerEntry? =
        try {
            decodeEntry(key, value, names)
        } catch (failure: Exception) {
            null
        }

    private fun decodeEntry(key: ByteArray, value: ByteArray, names: IdLookup): LedgerEntry? {
        val v = ByteReader(value)
        val header = v.byte()
        if (header and VERSION_MASK != VERSION) return null
        val kindId = (header ushr 6) and 0x03
        val kind = Kind.byId(kindId) ?: throw IllegalArgumentException("unknown entry kind $kindId")
        val confidenceId = (header ushr 5) and 0x01
        val confidence = Confidence.byId(confidenceId)
            ?: throw IllegalArgumentException("unknown confidence $confidenceId")
        val causeId = v.byte()
        val cause = Cause.byId(causeId) ?: Cause.UNKNOWN
        val slot = v.varInt()
        val counterparty = readCounterparty(v, names)
        val itemFormId = v.varLong()
        val qty = v.zigZagInt()
        val actor = if (header and ACTOR_FLAG != 0) names.uuid(RegistryNamespace.PLAYER, v.varInt()) else null
        val damage = if (v.remaining > 0) v.varInt() else null

        val k = ByteReader(key)
        val holder = readKeyIdentity(k, slot, names)
        return LedgerEntry(
            holder = holder,
            timestamp = k.longBE(),
            txId = k.longBE(),
            ordinal = k.byte(),
            kind = kind,
            cause = cause,
            confidence = confidence,
            counterparty = counterparty,
            itemFormId = itemFormId,
            qty = qty,
            damage = damage,
            actor = actor,
        )
    }

    // The key and the value write the same holder in the same layout; they differ only in the slot,
    // which the key leaves out because it lives in the value of the row it belongs to.
    private fun writeHolder(w: ByteWriter, holder: Holder, ids: IdResolver, withSlot: Boolean) {
        w.byte(holder.typeId)
        when (holder) {
            is PlayerHolder -> w.varInt(playerNo(holder.uuid, ids))
            is MenuSlot -> w.varInt(holder.menuType)
            is Container -> writePosition(w, holder.world, holder.x, holder.y, holder.z, ids)
            is WorldBlock -> writePosition(w, holder.world, holder.x, holder.y, holder.z, ids)
            is EntitySlot -> w.uuid(holder.uuid)
            is ItemEntityRef -> w.uuid(holder.uuid)
            is Nested -> w.uuid(holder.ownerId)
            Void -> Unit
        }
        if (withSlot && holder.carriesSlot) w.varInt(holder.slot)
    }

    private fun writeKeyIdentity(w: ByteWriter, holder: Holder, ids: IdResolver) {
        require(holder.addressable) { "holder type ${holder.typeId} produces no ledger rows of its own" }
        writeHolder(w, holder, ids, withSlot = false)
    }

    // `keySlot` is the slot read out of the value for a key, and null for a counterparty, which
    // carries its own slot after the identity bytes.
    private fun readHolder(r: ByteReader, names: IdLookup, keySlot: Int?): Holder {
        val typeId = r.byte()
        fun slot() = keySlot ?: r.varInt()
        return when (typeId) {
            HolderType.PLAYER_INV -> PlayerInv(playerUuid(r, names), slot())
            HolderType.PLAYER_EQUIP -> PlayerEquip(playerUuid(r, names), slot())
            HolderType.PLAYER_CURSOR -> PlayerCursor(playerUuid(r, names))
            HolderType.PLAYER_ENDER -> PlayerEnder(playerUuid(r, names), slot())
            HolderType.MENU_SLOT -> MenuSlot(r.varInt(), slot())
            HolderType.CONTAINER -> {
                val world = worldUuid(r, names)
                val pos = Zcode.decode(r.bytes(Zcode.SIZE))
                Container(world, pos[0], pos[1], pos[2], slot())
            }

            HolderType.ENTITY_SLOT -> EntitySlot(r.uuid(), slot())
            HolderType.ITEM_ENTITY -> ItemEntityRef(r.uuid())
            HolderType.NESTED -> Nested(r.uuid(), slot())
            HolderType.VOID -> Void
            HolderType.WORLD_BLOCK -> {
                val world = worldUuid(r, names)
                val pos = Zcode.decode(r.bytes(Zcode.SIZE))
                WorldBlock(world, pos[0], pos[1], pos[2])
            }

            else -> throw IllegalArgumentException("unknown holder type $typeId")
        }
    }

    private fun readKeyIdentity(k: ByteReader, slot: Int, names: IdLookup): Holder =
        readHolder(k, names, slot).also {
            require(it.addressable) { "holder type ${it.typeId} produces no ledger rows of its own" }
        }

    private fun readCounterparty(v: ByteReader, names: IdLookup): Holder = readHolder(v, names, null)

    private fun writePosition(w: ByteWriter, world: UUID, x: Int, y: Int, z: Int, ids: IdResolver) {
        w.shortBE(ids.id(RegistryNamespace.WORLD, world))
        w.bytes(Zcode.encode(x, y, z))
    }

    private fun playerNo(uuid: UUID, ids: IdResolver): Int = ids.id(RegistryNamespace.PLAYER, uuid)

    private fun playerUuid(r: ByteReader, names: IdLookup): UUID {
        val no = r.varInt()
        return names.uuid(RegistryNamespace.PLAYER, no) ?: throw IllegalArgumentException("unknown player number $no")
    }

    private fun worldUuid(r: ByteReader, names: IdLookup): UUID {
        val no = r.shortBE()
        return names.uuid(RegistryNamespace.WORLD, no) ?: throw IllegalArgumentException("unknown world number $no")
    }
}
