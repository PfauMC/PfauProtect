package io.pfaumc.pfauprotect

import java.io.ByteArrayOutputStream
import java.util.UUID

class ByteWriter(initialCapacity: Int = 32) {
    private val out = ByteArrayOutputStream(initialCapacity)

    val size: Int get() = out.size()

    fun byte(v: Int): ByteWriter {
        out.write(v)
        return this
    }

    fun bytes(v: ByteArray): ByteWriter {
        out.write(v, 0, v.size)
        return this
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

    fun longBE(v: Long): ByteWriter {
        for (shift in 56 downTo 0 step 8) byte((v ushr shift).toInt() and 0xFF)
        return this
    }

    fun uuid(v: UUID): ByteWriter = longBE(v.mostSignificantBits).longBE(v.leastSignificantBits)

    fun toByteArray(): ByteArray = out.toByteArray()
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

    private const val VERSION_MASK = 0x07

    // The upper reserved bit flags an actor; the lower one stays next to the version so that field
    // can still grow into it without moving.
    private const val ACTOR_FLAG = 0x10

    fun header(kind: Kind, confidence: Confidence, actor: Boolean = false): Int =
        (kind.id shl 6) or (confidence.id shl 5) or (if (actor) ACTOR_FLAG else 0) or VERSION

    fun key(holder: Holder, timestamp: Long, txId: Long, ids: IdResolver): ByteArray {
        val w = ByteWriter(40)
        writeKeyIdentity(w, holder, ids)
        return w.longBE(timestamp).longBE(txId).toByteArray()
    }

    fun holderPrefix(holder: Holder, ids: IdResolver): ByteArray {
        val w = ByteWriter(24)
        writeKeyIdentity(w, holder, ids)
        return w.toByteArray()
    }

    fun containerChunkPrefix(world: UUID, chunkX: Int, chunkZ: Int, ids: IdResolver): ByteArray =
        ByteWriter(16)
            .byte(5)
            .varInt(ids.id(RegistryNamespace.WORLD, world))
            .bytes(Zcode.chunkPrefix(chunkX, chunkZ))
            .toByteArray()

    // The trailing damage varint carries no presence flag, so it is only decodable while nothing can
    // follow it. Writing provenanceId here would need a new record version with a presence flag in
    // the reserved header bits.
    fun value(entry: LedgerEntry, ids: IdResolver): ByteArray {
        val w = ByteWriter(32)
        w.byte(header(entry.kind, entry.confidence, entry.actor != null))
        w.byte(entry.cause.id)
        w.varInt(entry.holder.slot)
        writeCounterparty(w, entry.counterparty, ids)
        w.varLong(entry.itemFormId)
        w.zigZagInt(entry.qty)
        if (entry.actor != null) w.varInt(playerNo(entry.actor, ids))
        if (entry.damage != null) w.varInt(entry.damage)
        return w.toByteArray()
    }

    fun decode(key: ByteArray, value: ByteArray, names: IdLookup): LedgerEntry =
        decodeOrNull(key, value, names)
            ?: throw IllegalArgumentException("record version ${value[0].toInt() and VERSION_MASK} is not supported")

    /** Returns null for records written in another layout version, so scans can skip them. */
    fun decodeOrNull(key: ByteArray, value: ByteArray, names: IdLookup): LedgerEntry? {
        val v = ByteReader(value)
        val header = v.byte()
        if (header and VERSION_MASK != VERSION) return null
        val kindId = (header ushr 6) and 0x03
        val kind = Kind.byId(kindId) ?: throw IllegalArgumentException("unknown entry kind $kindId")
        val confidenceId = (header ushr 5) and 0x01
        val confidence = Confidence.byId(confidenceId)
            ?: throw IllegalArgumentException("unknown confidence $confidenceId")
        val causeId = v.byte()
        val cause = Cause.byId(causeId) ?: throw IllegalArgumentException("unknown cause id $causeId")
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
            kind = kind,
            cause = cause,
            confidence = confidence,
            counterparty = counterparty,
            itemFormId = itemFormId,
            qty = qty,
            damage = damage,
            provenanceId = null,
            actor = actor,
        )
    }

    private fun writeKeyIdentity(w: ByteWriter, holder: Holder, ids: IdResolver) {
        w.byte(holder.typeId)
        when (holder) {
            is PlayerHolder -> w.varInt(playerNo(holder.uuid, ids))
            is Container -> {
                w.varInt(ids.id(RegistryNamespace.WORLD, holder.world))
                w.bytes(Zcode.encode(holder.x, holder.y, holder.z))
            }

            is EntitySlot -> w.uuid(holder.uuid)
            is ItemEntityRef -> w.uuid(holder.uuid)
            is Nested -> w.uuid(holder.ownerId)
            is MenuSlot, Void -> throw IllegalArgumentException(
                "holder type ${holder.typeId} produces no ledger rows of its own"
            )
        }
    }

    private fun readKeyIdentity(k: ByteReader, slot: Int, names: IdLookup): Holder =
        when (val typeId = k.byte()) {
            0 -> PlayerInv(playerUuid(k, names), slot)
            1 -> PlayerEquip(playerUuid(k, names), slot)
            2 -> PlayerCursor(playerUuid(k, names))
            3 -> PlayerEnder(playerUuid(k, names), slot)
            5 -> {
                val world = worldUuid(k, names)
                val pos = Zcode.decode(k.bytes(Zcode.SIZE))
                Container(world, pos[0], pos[1], pos[2], slot)
            }

            6 -> EntitySlot(k.uuid(), slot)
            7 -> ItemEntityRef(k.uuid())
            8 -> Nested(k.uuid(), slot)
            else -> throw IllegalArgumentException("holder type $typeId produces no ledger rows of its own")
        }

    private fun writeCounterparty(w: ByteWriter, holder: Holder, ids: IdResolver) {
        w.byte(holder.typeId)
        when (holder) {
            // A cursor holds one stack and has no slot number of its own.
            is PlayerCursor -> w.varInt(playerNo(holder.uuid, ids))
            is PlayerHolder -> w.varInt(playerNo(holder.uuid, ids)).varInt(holder.slot)
            is MenuSlot -> w.varInt(holder.menuType).varInt(holder.slot)
            is Container -> {
                w.varInt(ids.id(RegistryNamespace.WORLD, holder.world))
                w.bytes(Zcode.encode(holder.x, holder.y, holder.z))
                w.varInt(holder.slot)
            }

            is EntitySlot -> w.uuid(holder.uuid).varInt(holder.slot)
            is ItemEntityRef -> w.uuid(holder.uuid)
            is Nested -> w.uuid(holder.ownerId).varInt(holder.index)
            Void -> Unit
        }
    }

    private fun readCounterparty(v: ByteReader, names: IdLookup): Holder =
        when (val typeId = v.byte()) {
            0 -> PlayerInv(playerUuid(v, names), v.varInt())
            1 -> PlayerEquip(playerUuid(v, names), v.varInt())
            2 -> PlayerCursor(playerUuid(v, names))
            3 -> PlayerEnder(playerUuid(v, names), v.varInt())
            4 -> MenuSlot(v.varInt(), v.varInt())
            5 -> {
                val world = worldUuid(v, names)
                val pos = Zcode.decode(v.bytes(Zcode.SIZE))
                Container(world, pos[0], pos[1], pos[2], v.varInt())
            }

            6 -> EntitySlot(v.uuid(), v.varInt())
            7 -> ItemEntityRef(v.uuid())
            8 -> Nested(v.uuid(), v.varInt())
            9 -> Void
            else -> throw IllegalArgumentException("unknown holder type $typeId")
        }

    private fun playerNo(uuid: UUID, ids: IdResolver): Int = ids.id(RegistryNamespace.PLAYER, uuid)

    private fun playerUuid(r: ByteReader, names: IdLookup): UUID {
        val no = r.varInt()
        return names.uuid(RegistryNamespace.PLAYER, no) ?: throw IllegalArgumentException("unknown player number $no")
    }

    private fun worldUuid(r: ByteReader, names: IdLookup): UUID {
        val no = r.varInt()
        return names.uuid(RegistryNamespace.WORLD, no) ?: throw IllegalArgumentException("unknown world number $no")
    }
}
