package io.pfaumc.pfauprotect

import java.util.UUID

// TRANSFER and FACT must stay 0: the header byte of an ordinary entry is required to be 0x00.
enum class Kind(val id: Int) {
    TRANSFER(0), MUTATE(1), CLONE(2), ;

    companion object {
        private val BY_ID = entries.associateBy { it.id }
        fun byId(id: Int): Kind? = BY_ID[id]
    }
}

enum class Confidence(val id: Int) {
    FACT(0), INFERRED(1), ;

    companion object {
        private val BY_ID = entries.associateBy { it.id }
        fun byId(id: Int): Confidence? = BY_ID[id]
    }
}

// typeId values are written into every stored key and must never change.
sealed class Holder(val typeId: Int) {
    abstract val slot: Int

    // A holder nobody can address owns no rows: it only ever appears as the other end of a movement,
    // written into the value of the row that faces it.
    val addressable: Boolean get() = this !is MenuSlot && this !is Void
}

sealed class PlayerHolder(typeId: Int) : Holder(typeId) {
    abstract val uuid: UUID
}

data class PlayerInv(override val uuid: UUID, override val slot: Int) : PlayerHolder(0)

data class PlayerEquip(override val uuid: UUID, override val slot: Int) : PlayerHolder(1)

data class PlayerCursor(override val uuid: UUID) : PlayerHolder(2) {
    override val slot: Int get() = 0
}

data class PlayerEnder(override val uuid: UUID, override val slot: Int) : PlayerHolder(3)

data class MenuSlot(val menuType: Int, override val slot: Int) : Holder(4)

data class Container(
    val world: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    override val slot: Int,
) : Holder(5)

data class EntitySlot(val uuid: UUID, override val slot: Int) : Holder(6)

data class ItemEntityRef(val uuid: UUID) : Holder(7) {
    override val slot: Int get() = 0
}

data class Nested(val ownerId: UUID, val index: Int) : Holder(8) {
    override val slot: Int get() = index
}

data object Void : Holder(9) {
    override val slot: Int get() = 0
}

data class LedgerEntry(
    val holder: Holder,
    val timestamp: Long,
    val txId: Long,
    val kind: Kind,
    val cause: Cause,
    val confidence: Confidence,
    val counterparty: Holder,
    val itemFormId: Long,
    val qty: Int,
    val damage: Int?,
    val provenanceId: Long?,
    val actor: UUID?,
)

class Transfer(
    val cause: Cause,
    val from: Holder,
    val to: Holder,
    val form: ByteArray,
    val damage: Int?,
    val qty: Int,
    val timestamp: Long,
    val kind: Kind = Kind.TRANSFER,
    val confidence: Confidence = Confidence.FACT,
    // Only worth setting when neither end is a player: an ordinary transfer already names the person
    // through its holder or its counterparty, and repeating them would cost a byte on every row.
    val actor: UUID? = null,
) {
    init {
        require(qty > 0) { "transfer quantity must be positive, got $qty" }
    }
}
