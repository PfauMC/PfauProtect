package io.pfaumc.pfauprotect.storage
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.EntityKind
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

/**
 * A row of the entity plane: what became of one entity, at the block position it stood in. The NBT
 * before and after are ids into the shared payload table; `drops` are the item entities that fell out of
 * it, or out of the position for a `DROPPED` row.
 */
data class EntityRow(
    val x: Int,
    val y: Int,
    val z: Int,
    val timestamp: Long,
    val eventId: Long,
    val ordinal: Int,
    val kind: EntityKind,
    val cause: Cause,
    val type: String,
    val uuid: UUID,
    val confidence: Confidence = Confidence.FACT,
    val actor: UUID? = null,
    val payloadBefore: Long? = null,
    val payloadAfter: Long? = null,
    val drops: List<UUID> = emptyList(),
    val death: String? = null,
    val via: String? = null,
)

// The key is a block row's key — position, time, event, ordinal — so a walk of a chunk or a position
// reads both planes the same way.
object EntityCodec {
    const val VERSION = 0

    // A death's damage type and the entity that dealt it, after everything version 0 has. Only a row
    // that has either is written as this version, so every other row stays as short as it was.
    const val WITH_DEATH = 1

    private const val VERSION_MASK = 0x03
    private const val NEARBY_FLAG = 0x04
    private const val CONFIDENCE_FLAG = 0x08
    private const val ACTOR_FLAG = 0x10
    private const val BEFORE_FLAG = 0x20
    private const val AFTER_FLAG = 0x40
    private const val DROPS_FLAG = 0x80

    /** `deathId` and `viaId` are the registry numbers of [EntityRow.death] and [EntityRow.via], -1 for none. */
    fun value(row: EntityRow, ids: IdResolver, typeId: Int, deathId: Int, viaId: Int): ByteArray {
        val w = ByteWriter(48)
        val death = deathId >= 0 || viaId >= 0
        w.byte(
            (if (death) WITH_DEATH else VERSION) or
                (if (row.confidence == Confidence.INFERRED) CONFIDENCE_FLAG else 0) or
                (if (row.confidence == Confidence.NEARBY) NEARBY_FLAG else 0) or
                (if (row.actor != null) ACTOR_FLAG else 0) or
                (if (row.payloadBefore != null) BEFORE_FLAG else 0) or
                (if (row.payloadAfter != null) AFTER_FLAG else 0) or
                (if (row.drops.isNotEmpty()) DROPS_FLAG else 0)
        )
        w.byte(row.kind.id)
        w.byte(row.cause.id)
        w.varInt(typeId)
        w.uuid(row.uuid)
        if (row.actor != null) w.varInt(ids.id(RegistryNamespace.PLAYER, row.actor))
        if (row.payloadBefore != null) w.varLong(row.payloadBefore)
        if (row.payloadAfter != null) w.varLong(row.payloadAfter)
        if (row.drops.isNotEmpty()) {
            w.varInt(row.drops.size)
            for (drop in row.drops) w.uuid(drop)
        }
        if (death) {
            w.varInt(deathId + 1)
            w.varInt(viaId + 1)
        }
        return w.toByteArray()
    }

    /** Null for a row this build cannot read, as a block row is. */
    fun decodeOrNull(key: ByteArray, value: ByteArray, names: Registries): EntityRow? =
        try {
            decode(key, value, names)
        } catch (failure: IllegalArgumentException) {
            null
        }

    private fun decode(key: ByteArray, value: ByteArray, names: Registries): EntityRow {
        fun typeOf(id: Int) = names.keyOf(RegistryNamespace.ENTITY_TYPE, id)
        val v = ByteReader(value)
        val header = v.byte()
        val version = header and VERSION_MASK
        require(version == VERSION || version == WITH_DEATH) { "entity record version $version is not supported" }
        val kind = EntityKind.byId(v.byte()) ?: throw IllegalArgumentException("unknown entity row kind")
        val cause = Cause.byId(v.byte()) ?: Cause.UNKNOWN
        val typeId = v.varInt()
        val type = typeOf(typeId) ?: throw IllegalArgumentException("unknown entity type number $typeId")
        val uuid = v.uuid()
        val actor = if (header and ACTOR_FLAG != 0) names.uuid(RegistryNamespace.PLAYER, v.varInt()) else null
        val before = if (header and BEFORE_FLAG != 0) v.varLong() else null
        val after = if (header and AFTER_FLAG != 0) v.varLong() else null
        val drops = if (header and DROPS_FLAG != 0) List(v.varInt()) { v.uuid() } else emptyList()
        val death = if (version == WITH_DEATH) v.varInt().takeIf { it > 0 }?.let { names.keyOf(RegistryNamespace.DAMAGE_TYPE, it - 1) } else null
        val via = if (version == WITH_DEATH) v.varInt().takeIf { it > 0 }?.let { typeOf(it - 1) } else null
        val k = ByteReader(key)
        val pos = Zcode.decode(k.bytes(Zcode.SIZE))
        return EntityRow(
            x = pos[0], y = pos[1], z = pos[2],
            timestamp = k.longBE(), eventId = k.longBE(), ordinal = k.byte(),
            kind = kind, cause = cause, type = type, uuid = uuid,
            confidence = when {
                header and NEARBY_FLAG != 0 -> Confidence.NEARBY
                header and CONFIDENCE_FLAG != 0 -> Confidence.INFERRED
                else -> Confidence.FACT
            },
            actor = actor, payloadBefore = before, payloadAfter = after, drops = drops, death = death, via = via,
        )
    }
}

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

    // Two bits of version, the third taken by the witness flag: the version has only ever been zero, so
    // every row written before the flag existed reads as it always did.
    private const val VERSION_MASK = 0x03
    private const val NEARBY_FLAG = 0x04
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
     * The player number a row names as its actor, read straight from the bytes: the index of rows by
     * actor is built from rows already on disk, before anything has to know who the number is. Null for
     * a row with no actor, and for one this build cannot read.
     */
    fun actorNumberOf(value: ByteArray): Int? = try {
        val v = ByteReader(value)
        val header = v.byte()
        if (header and VERSION_MASK != VERSION || header and ACTOR_FLAG == 0) {
            null
        } else {
            v.bytes(1 + 2 + 2)
            v.varInt()
        }
    } catch (failure: IllegalArgumentException) {
        null
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
            confidence = when {
                header and NEARBY_FLAG != 0 -> Confidence.NEARBY
                header and CONFIDENCE_FLAG != 0 -> Confidence.INFERRED
                else -> Confidence.FACT
            },
            alongside = header and ALONGSIDE_FLAG != 0,
            actor = actor,
            payloadBefore = payloadBefore,
            payloadAfter = payloadAfter,
        )
    }

    private fun header(row: BlockRow): Int =
        VERSION or
            (if (row.confidence == Confidence.INFERRED) CONFIDENCE_FLAG else 0) or
            (if (row.confidence == Confidence.NEARBY) NEARBY_FLAG else 0) or
            (if (row.actor != null) ACTOR_FLAG else 0) or
            (if (row.payloadBefore != null) PAYLOAD_BEFORE_FLAG else 0) or
            (if (row.payloadAfter != null) PAYLOAD_AFTER_FLAG else 0) or
            (if (row.alongside) ALONGSIDE_FLAG else 0)
}
