package io.pfaumc.pfauprotect.storage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class RegistryNamespace(val id: Int, val maxId: Int) {
    ITEM_TYPE(0, 0xFFFF),
    ENTITY_TYPE(1, 0xFFFF),
    ENCHANTMENT(2, 0xFFFF),
    DATA_COMPONENT_TYPE(3, 0xFFFF),
    POTION(4, 0xFFFF),

    // One short of the two bytes a world number is stored in. A scan for a world nobody registered
    // has to find nothing, and it says so by putting -1 through those two bytes, which lands on
    // 0xFFFF; handing that number to a real world would point such a scan at that world's rows.
    WORLD(5, 0xFFFE),
    PLAYER(6, Int.MAX_VALUE),

    // Keyed by the whole `blockData.asString`, not by the vanilla state number: the numbers are
    // renumbered from one game version to the next while the string survives the update, and the
    // string is itself what `Bukkit.createBlockData` takes to put the state back. Nothing is reserved
    // for "no state": a block row that knows only one of its two sides is a write error rather than a
    // row with a default, so the case never arises and all of 0..0xFFFF is available.
    BLOCK_STATE(7, 0xFFFF),

    // Keyed by the name of the InventoryType constant, not by its ordinal: a new type added anywhere
    // but the end renumbers every constant after it, and rows already on disk would then quietly name
    // a different menu than the one they were written for.
    MENU_TYPE(8, 0xFFFF),

    // What killed a mob, by the damage type's registry key: drowned, dried out, fell, crammed.
    DAMAGE_TYPE(9, 0xFFFF),
    ;

    internal val usesUuid: Boolean get() = this == WORLD || this == PLAYER

    companion object {
        private val BY_ID = entries.associateBy { it.id }
        fun byId(id: Int): RegistryNamespace? = BY_ID[id]
    }
}

// A number is never handed out twice, so a capped space only ever fills, and one game update that
// changes the properties of a family of blocks can spend a slice of the ceiling in a single day.
// Reported at this level, moving to a wider field is a conversation with years in hand; left to be
// discovered by `assign` refusing, it is a plugin that no longer starts on a database that by then
// nobody can widen any more.
internal const val REGISTRY_HIGH_WATER = 0.8

class RegistryRow(val ns: RegistryNamespace, val keyBytes: ByteArray, val id: Int)

interface RegistryStore {
    fun loadAll(): List<RegistryRow>
    fun put(ns: RegistryNamespace, keyBytes: ByteArray, id: Int)
    fun nextId(ns: RegistryNamespace): Int
}

// Lookups run on any thread. Minting does not: it is a read-modify-write of a persisted counter, and
// two threads reaching a key neither has seen would hand out one number twice and file two different
// things under it, so every mint goes through one lock.
class Registries(private val store: RegistryStore) : IdResolver, IdLookup {
    private val ids = RegistryNamespace.entries.associateWith { ConcurrentHashMap<String, Int>() }
    private val keys = RegistryNamespace.entries.associateWith { ConcurrentHashMap<Int, String>() }

    fun load() {
        for (row in store.loadAll()) remember(row.ns, decodeKey(row.ns, row.keyBytes), row.id)
    }

    fun idForKey(ns: RegistryNamespace, key: String): Int = ids.getValue(ns)[key] ?: assign(ns, key)

    fun idForUuid(ns: RegistryNamespace, uuid: UUID): Int = idForKey(ns, uuid.toString())

    fun lookupKey(ns: RegistryNamespace, key: String): Int? = ids.getValue(ns)[key]

    fun keyOf(ns: RegistryNamespace, id: Int): String? = keys.getValue(ns)[id]

    fun uuidOf(ns: RegistryNamespace, id: Int): UUID? = keyOf(ns, id)?.let(UUID::fromString)

    fun size(ns: RegistryNamespace): Int = ids.getValue(ns).size

    fun fillWarnings(): List<String> = RegistryNamespace.entries.mapNotNull { ns ->
        val used = size(ns)
        if (used < ns.maxId * REGISTRY_HIGH_WATER) null
        else "registry ${ns.name.lowercase()} has handed out $used of its ${ns.maxId} numbers and " +
            "reuses none of them: a wider field has to be in place before the last one is gone"
    }

    override fun id(ns: RegistryNamespace, uuid: UUID) = idForUuid(ns, uuid)

    override fun uuid(ns: RegistryNamespace, id: Int) = uuidOf(ns, id)

    // Checked before the counter is consumed: a rejected assignment that had already bumped the
    // persisted counter would push every later attempt further out of range.
    @Synchronized
    private fun assign(ns: RegistryNamespace, key: String): Int {
        ids.getValue(ns)[key]?.let { return it }
        val used = size(ns)
        if (used > ns.maxId) {
            throw IllegalStateException("registry $ns is full: $used ids used, max ${ns.maxId}")
        }
        val id = store.nextId(ns)
        if (id < 0 || id > ns.maxId) {
            throw IllegalStateException("registry $ns is full: id $id exceeds max ${ns.maxId}, size $used")
        }
        store.put(ns, keyBytes(ns, key), id)
        remember(ns, key, id)
        return id
    }

    private fun remember(ns: RegistryNamespace, key: String, id: Int) {
        ids.getValue(ns)[key] = id
        keys.getValue(ns)[id] = key
    }

    private fun keyBytes(ns: RegistryNamespace, key: String): ByteArray =
        if (ns.usesUuid) ByteWriter(16).uuid(UUID.fromString(key)).toByteArray() else key.toByteArray()

    private fun decodeKey(ns: RegistryNamespace, keyBytes: ByteArray): String =
        if (ns.usesUuid) ByteReader(keyBytes).uuid().toString() else String(keyBytes)
}
