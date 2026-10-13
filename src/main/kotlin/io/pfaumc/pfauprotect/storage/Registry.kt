package io.pfaumc.pfauprotect.storage
import io.pfaumc.pfauprotect.model.idIndex
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
    // string is itself what `Bukkit.createBlockData` takes to put the state back.
    BLOCK_STATE(7, 0xFFFF),

    // Keyed by the name of the InventoryType constant, not by its ordinal: a new type added anywhere
    // but the end renumbers every constant after it, and rows already on disk would then quietly name
    // a different menu than the one they were written for.
    MENU_TYPE(8, 0xFFFF),

    // What killed a mob, by the damage type's registry key: drowned, dried out, fell, crammed.
    DAMAGE_TYPE(9, 0xFFFF),
    ;

    internal val usesUuid: Boolean get() = this == WORLD || this == PLAYER

    companion object { val byId = idIndex(entries) { it.id } }
}

// Numbers are never reused, so a namespace only fills and must be widened before it does; this is the
// fill level at which that is reported, early enough to act on.
internal const val REGISTRY_HIGH_WATER = 0.8

class RegistryRow(val ns: RegistryNamespace, val keyBytes: ByteArray, val id: Int)

interface RegistryStore {
    fun loadAll(): List<RegistryRow>

    // The next number of the namespace, held in memory only; `persist` is what makes it stick.
    fun nextId(ns: RegistryNamespace): Int

    // The rows and the counters past them, durable before this returns.
    fun persist(rows: List<RegistryRow>)
}

// Lookups run on any thread. Minting does not: it is a read-modify-write of a persisted counter, and
// two threads reaching a key neither has seen would hand out one number twice and file two different
// things under it, so every mint goes through one lock.
class Registries(private val store: RegistryStore) : IdResolver, IdLookup {
    private val ids = RegistryNamespace.entries.associateWith { ConcurrentHashMap<String, Int>() }
    private val keys = RegistryNamespace.entries.associateWith { ConcurrentHashMap<Int, String>() }

    // The uuid namespaces again, by UUID: every row names a player or a world, and going through the
    // string would format or parse one per row.
    private val idsByUuid = RegistryNamespace.entries.filter { it.usesUuid }.associateWith { ConcurrentHashMap<UUID, Int>() }
    private val uuidsById = RegistryNamespace.entries.filter { it.usesUuid }.associateWith { ConcurrentHashMap<Int, UUID>() }

    fun load() {
        for (row in store.loadAll()) remember(row.ns, decodeKey(row.ns, row.keyBytes), row.id)
    }

    fun idForKey(ns: RegistryNamespace, key: String): Int = ids.getValue(ns)[key] ?: assign(ns, key)

    fun lookupKey(ns: RegistryNamespace, key: String): Int? = ids.getValue(ns)[key]

    fun keyOf(ns: RegistryNamespace, id: Int): String? = keys.getValue(ns)[id]

    fun lookupUuid(ns: RegistryNamespace, uuid: UUID): Int? = idsByUuid[ns]?.get(uuid)

    fun size(ns: RegistryNamespace): Int = ids.getValue(ns).size

    fun fillWarnings(): List<String> = RegistryNamespace.entries.mapNotNull { ns ->
        val used = size(ns)
        if (used < ns.maxId * REGISTRY_HIGH_WATER) null
        else "registry ${ns.name.lowercase()} has handed out $used of its ${ns.maxId} numbers and " +
            "reuses none of them: a wider field has to be in place before the last one is gone"
    }

    override fun id(ns: RegistryNamespace, uuid: UUID) = idsByUuid[ns]?.get(uuid) ?: idForKey(ns, uuid.toString())

    override fun uuid(ns: RegistryNamespace, id: Int): UUID? = uuidsById[ns]?.get(id)

    // The numbers a batch has minted and not yet written, seen only by the thread running it: every other
    // thread misses them in `ids` and waits in `assign` until the batch is on disk.
    private var batch: LinkedHashMap<Pair<RegistryNamespace, String>, Int>? = null

    /**
     * Mints whatever `body` asks for in one synced write instead of one apiece, which is what a fill of
     * thousands of keys needs. The lock is held throughout, so nothing else mints meanwhile. What was
     * minted is written even when `body` fails: every number in it is whole.
     */
    @Synchronized
    fun <T> batched(body: () -> T): T {
        val minted = LinkedHashMap<Pair<RegistryNamespace, String>, Int>()
        batch = minted
        return try {
            body()
        } finally {
            batch = null
            publish(minted)
        }
    }

    private fun publish(minted: Map<Pair<RegistryNamespace, String>, Int>) {
        if (minted.isEmpty()) return
        store.persist(minted.map { (name, id) -> RegistryRow(name.first, keyBytes(name.first, name.second), id) })
        for ((name, id) in minted) remember(name.first, name.second, id)
    }

    // Checked before the counter is consumed: a rejected assignment that had already bumped the
    // persisted counter would push every later attempt further out of range.
    @Synchronized
    private fun assign(ns: RegistryNamespace, key: String): Int {
        ids.getValue(ns)[key]?.let { return it }
        batch?.get(ns to key)?.let { return it }
        val used = size(ns)
        if (used > ns.maxId) {
            throw IllegalStateException("registry $ns is full: $used ids used, max ${ns.maxId}")
        }
        val id = store.nextId(ns)
        if (id < 0 || id > ns.maxId) {
            throw IllegalStateException("registry $ns is full: id $id exceeds max ${ns.maxId}, size $used")
        }
        batch?.let {
            it[ns to key] = id
            return id
        }
        publish(mapOf((ns to key) to id))
        return id
    }

    private fun remember(ns: RegistryNamespace, key: String, id: Int) {
        ids.getValue(ns)[key] = id
        keys.getValue(ns)[id] = key
        if (ns.usesUuid) {
            val uuid = UUID.fromString(key)
            idsByUuid.getValue(ns)[uuid] = id
            uuidsById.getValue(ns)[id] = uuid
        }
    }

    private fun keyBytes(ns: RegistryNamespace, key: String): ByteArray =
        if (ns.usesUuid) ByteWriter(16).uuid(UUID.fromString(key)).toByteArray() else key.toByteArray()

    private fun decodeKey(ns: RegistryNamespace, keyBytes: ByteArray): String =
        if (ns.usesUuid) ByteReader(keyBytes).uuid().toString() else String(keyBytes)
}
