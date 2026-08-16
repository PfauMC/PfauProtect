package io.pfaumc.pfauprotect

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
    ;

    internal val usesUuid: Boolean get() = this == WORLD || this == PLAYER

    companion object {
        private val BY_ID = entries.associateBy { it.id }
        fun byId(id: Int): RegistryNamespace? = BY_ID[id]
    }
}

class RegistryRow(val ns: RegistryNamespace, val keyBytes: ByteArray, val id: Int)

interface RegistryStore {
    fun loadAll(): List<RegistryRow>
    fun put(ns: RegistryNamespace, keyBytes: ByteArray, id: Int)
    fun nextId(ns: RegistryNamespace): Int
}

// Numbers are handed out from the writer thread only; every other thread reads.
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

    override fun id(ns: RegistryNamespace, uuid: UUID) = idForUuid(ns, uuid)

    override fun uuid(ns: RegistryNamespace, id: Int) = uuidOf(ns, id)

    // Checked before the counter is consumed: a rejected assignment that had already bumped the
    // persisted counter would push every later attempt further out of range.
    private fun assign(ns: RegistryNamespace, key: String): Int {
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
