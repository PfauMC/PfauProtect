package io.pfaumc.pfauprotect.model
import io.pfaumc.pfauprotect.storage.ItemKey
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
    FACT(0),
    INFERRED(1),
    // A player who was near when something set a mechanism off, and no more: nothing tied them to it.
    NEARBY(2),
    ;

    companion object {
        private val BY_ID = entries.associateBy { it.id }
        fun byId(id: Int): Confidence? = BY_ID[id]
    }
}

// What an entity row says became of the entity it names. Written into every such row, so a number is
// never changed or reused.
enum class EntityKind(val id: Int) {
    // Gone — killed, broken, destroyed — with its whole NBT as it was.
    REMOVED(0),
    // Brought into the world by a player.
    CREATED(1),
    // Changed by a player's hand: named, sheared, dyed, its frame turned.
    CHANGED(2),
    // Led away by a player: on a lead, ridden, put in a boat.
    MOVED(3),
    // Not an entity at all: the items a block position dropped when something other than a hand broke it.
    DROPPED(4),
    // A player died there, and the row names who did it and what fell out.
    PLAYER_DIED(5),
    ;

    companion object {
        private val BY_ID = entries.associateBy { it.id }
        fun byId(id: Int): EntityKind? = BY_ID[id]
    }
}

// These numbers are written into every stored key and must never change. They are named here rather
// than spelled out at each holder because the decoder and the region scan both have to switch on
// them by value, where the compiler cannot check that the set is complete.
object HolderType {
    const val PLAYER_INV = 0
    const val PLAYER_EQUIP = 1
    const val PLAYER_CURSOR = 2
    const val PLAYER_ENDER = 3
    const val MENU_SLOT = 4
    const val CONTAINER = 5
    const val ENTITY_SLOT = 6
    const val ITEM_ENTITY = 7
    const val NESTED = 8
    const val VOID = 9
    const val WORLD_BLOCK = 10

    // The holders that address a block position. Their keys differ only in this leading byte, so a
    // scan that names one of them answers with half the rows and looks complete doing it.
    val POSITIONAL = listOf(CONTAINER, WORLD_BLOCK)
}

sealed class Holder(
    val typeId: Int,
    // A holder nobody can address owns no rows: it only ever appears as the other end of a movement,
    // written into the value of the row that faces it.
    val addressable: Boolean = true,
    // Whether the slot is a number of its own. A cursor, a dropped item, a block position and the
    // void each hold exactly one thing, so their slot is always zero and is never written down.
    val carriesSlot: Boolean = true,
) {
    abstract val slot: Int
}

sealed class PlayerHolder(typeId: Int, carriesSlot: Boolean = true) :
    Holder(typeId, carriesSlot = carriesSlot) {
    abstract val uuid: UUID
}

data class PlayerInv(override val uuid: UUID, override val slot: Int) :
    PlayerHolder(HolderType.PLAYER_INV)

data class PlayerEquip(override val uuid: UUID, override val slot: Int) :
    PlayerHolder(HolderType.PLAYER_EQUIP)

data class PlayerCursor(override val uuid: UUID) :
    PlayerHolder(HolderType.PLAYER_CURSOR, carriesSlot = false) {
    override val slot: Int get() = 0
}

data class PlayerEnder(override val uuid: UUID, override val slot: Int) :
    PlayerHolder(HolderType.PLAYER_ENDER)

data class MenuSlot(val menuType: Int, override val slot: Int) :
    Holder(HolderType.MENU_SLOT, addressable = false)

data class Container(
    val world: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    override val slot: Int,
) : Holder(HolderType.CONTAINER)

// A placed block still holds the item it was made from. Writing the placement as a loss into Void
// instead would cut the chain in two at every build: the stolen blocks laid into a floor would
// vanish from the graph when placed and reappear from nowhere hours later when mined back out.
data class WorldBlock(val world: UUID, val x: Int, val y: Int, val z: Int) :
    Holder(HolderType.WORLD_BLOCK, carriesSlot = false) {
    override val slot: Int get() = 0
}

data class EntitySlot(val uuid: UUID, override val slot: Int) : Holder(HolderType.ENTITY_SLOT)

data class ItemEntityRef(val uuid: UUID) : Holder(HolderType.ITEM_ENTITY, carriesSlot = false) {
    override val slot: Int get() = 0
}

data class Nested(val ownerId: UUID, val index: Int) : Holder(HolderType.NESTED) {
    override val slot: Int get() = index
}

data object Void : Holder(HolderType.VOID, addressable = false, carriesSlot = false) {
    override val slot: Int get() = 0
}

data class LedgerEntry(
    val holder: Holder,
    val timestamp: Long,
    val txId: Long,
    // Where this posting sits among the postings of its transaction. It is the tail of the stored key
    // and the only thing keeping two postings on one holder apart, since the slot lives in the value.
    val ordinal: Int = 0,
    val kind: Kind,
    val cause: Cause,
    val confidence: Confidence,
    val counterparty: Holder,
    val itemFormId: Long,
    val qty: Int,
    val damage: Int?,
    val actor: UUID?,
) {
    val ref: PostingRef get() = PostingRef(txId, ordinal)
}

// One posting of the ledger by where it sits: its transaction and its place among the postings of it.
data class PostingRef(val txId: Long, val ordinal: Int)

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
    // The postings a rollback gives back by this movement. They are filed against the transaction this
    // lands in, in the same batch, so a second rollback can tell they were already given back.
    val reverts: List<PostingRef> = emptyList(),
) {
    constructor(
        cause: Cause,
        from: Holder,
        to: Holder,
        key: ItemKey,
        qty: Int,
        timestamp: Long,
        kind: Kind = Kind.TRANSFER,
        confidence: Confidence = Confidence.FACT,
        actor: UUID? = null,
    ) : this(cause, from, to, key.form, key.damage, qty, timestamp, kind, confidence, actor)

    init {
        require(qty > 0) { "transfer quantity must be positive, got $qty" }
    }
}
