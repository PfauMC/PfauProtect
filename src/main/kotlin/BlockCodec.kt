package io.pfaumc.pfauprotect

import java.util.UUID

// Both states are mandatory and both are registry numbers of the full `blockData.asString`. A row
// that knows only one of its sides is a write error rather than a row with a default, so neither
// field is nullable and nothing here stands for "no state".
data class BlockRow(
    val x: Int,
    val y: Int,
    val z: Int,
    val timestamp: Long,
    val eventId: Long,
    val ordinal: Int,
    val cause: Cause,
    val stateBefore: Int,
    val stateAfter: Int,
    val confidence: Confidence = Confidence.FACT,
    // A position that changed only because the block standing in it also stood somewhere else, and
    // that other half is the one the event was actually about. Without it the two rows of a door or a
    // bed are indistinguishable and which half a player struck cannot be told from the journal.
    val alongside: Boolean = false,
    val actor: UUID? = null,
    val payloadBefore: Long? = null,
    val payloadAfter: Long? = null,
)

object BlockCodec {
    const val VERSION = 0

    // The position, then the time, then the event that changed it, then which change of that event
    // it was. No world number: the database is the world, so the position is the whole of the prefix
    // and a chunk is its first six bytes.
    const val KEY_SIZE = Zcode.SIZE + 8 + 8 + 1

    // Changes of one event are numbered from zero in the order they are written. One event can touch
    // the same position twice — liquid taking a spot a piston is pulling a block out of — and both
    // rows carry the event id and, after the clamp, the same time, so without the number the second
    // change would be filed on the key of the first and take the place of a change that happened.
    const val MAX_ORDINAL = 0xFF

    // Two bytes a side, which is what `BLOCK_STATE` is capped at. A shorter field later is allowed,
    // a longer one is not.
    private const val MAX_STATE = 0xFFFF

    private const val VERSION_MASK = 0x07
    private const val CONFIDENCE_FLAG = 0x08
    private const val ACTOR_FLAG = 0x10
    private const val PAYLOAD_BEFORE_FLAG = 0x20
    private const val PAYLOAD_AFTER_FLAG = 0x40
    private const val ALONGSIDE_FLAG = 0x80

    fun key(x: Int, y: Int, z: Int, timestamp: Long, eventId: Long, ordinal: Int): ByteArray {
        require(ordinal in 0..MAX_ORDINAL) { "change ordinal $ordinal does not fit in a byte" }
        return ByteWriter(KEY_SIZE)
            .bytes(Zcode.encode(x, y, z))
            .longBE(timestamp)
            .longBE(eventId)
            .byte(ordinal)
            .toByteArray()
    }

    fun positionPrefix(x: Int, y: Int, z: Int): ByteArray = Zcode.encode(x, y, z)

    fun value(row: BlockRow, ids: IdResolver): ByteArray {
        require(row.stateBefore in 0..MAX_STATE && row.stateAfter in 0..MAX_STATE) {
            "block states ${row.stateBefore} and ${row.stateAfter} do not both fit in two bytes"
        }
        val w = ByteWriter(16)
        w.byte(header(row))
        w.byte(row.cause.id)
        w.shortBE(row.stateBefore)
        w.shortBE(row.stateAfter)
        if (row.actor != null) w.varInt(ids.id(RegistryNamespace.PLAYER, row.actor))
        if (row.payloadBefore != null) w.varLong(row.payloadBefore)
        if (row.payloadAfter != null) w.varLong(row.payloadAfter)
        return w.toByteArray()
    }

    /**
     * Returns null for a record this build cannot read: one written in another layout version, and
     * one whose bytes stop short. A row cut off by a torn write costs its own row, not every row
     * the scan that met it would otherwise have answered with.
     */
    fun decodeOrNull(key: ByteArray, value: ByteArray, names: IdLookup): BlockRow? =
        try {
            decode(key, value, names)
        } catch (failure: IllegalArgumentException) {
            null
        }

    fun decode(key: ByteArray, value: ByteArray, names: IdLookup): BlockRow {
        val v = ByteReader(value)
        val header = v.byte()
        require(header and VERSION_MASK == VERSION) {
            "record version ${header and VERSION_MASK} is not supported"
        }
        val cause = Cause.byId(v.byte()) ?: Cause.UNKNOWN
        val stateBefore = v.shortBE()
        val stateAfter = v.shortBE()
        val actor = if (header and ACTOR_FLAG != 0) names.uuid(RegistryNamespace.PLAYER, v.varInt()) else null
        val payloadBefore = if (header and PAYLOAD_BEFORE_FLAG != 0) v.varLong() else null
        val payloadAfter = if (header and PAYLOAD_AFTER_FLAG != 0) v.varLong() else null

        val k = ByteReader(key)
        val pos = Zcode.decode(k.bytes(Zcode.SIZE))
        val timestamp = k.longBE()
        val eventId = k.longBE()
        val ordinal = k.byte()
        return BlockRow(
            x = pos[0],
            y = pos[1],
            z = pos[2],
            timestamp = timestamp,
            eventId = eventId,
            ordinal = ordinal,
            cause = cause,
            stateBefore = stateBefore,
            stateAfter = stateAfter,
            confidence = if (header and CONFIDENCE_FLAG != 0) Confidence.INFERRED else Confidence.FACT,
            alongside = header and ALONGSIDE_FLAG != 0,
            actor = actor,
            payloadBefore = payloadBefore,
            payloadAfter = payloadAfter,
        )
    }

    private fun header(row: BlockRow): Int =
        VERSION or
            (if (row.confidence == Confidence.INFERRED) CONFIDENCE_FLAG else 0) or
            (if (row.actor != null) ACTOR_FLAG else 0) or
            (if (row.payloadBefore != null) PAYLOAD_BEFORE_FLAG else 0) or
            (if (row.payloadAfter != null) PAYLOAD_AFTER_FLAG else 0) or
            (if (row.alongside) ALONGSIDE_FLAG else 0)
}
