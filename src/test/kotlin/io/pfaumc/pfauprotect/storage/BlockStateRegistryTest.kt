package io.pfaumc.pfauprotect.storage

import io.pfaumc.pfauprotect.OneLedger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.rocksdb.RocksDBException
import java.nio.file.Path
import kotlin.math.ceil

// Registries as a database that already holds `rows` numbers, so the far end of the space can be
// looked at without minting the numbers in front of it. The counter carries on where the rows stop,
// which is where a real store would have left it.
private class LoadedRegistryStore(private val ns: RegistryNamespace, private val rows: Int) : RegistryStore {
    var nextNumber = rows
        private set

    override fun loadAll(): List<RegistryRow> = List(rows) { RegistryRow(ns, "state $it".toByteArray(), it) }

    override fun persist(rows: List<RegistryRow>) = Unit

    override fun nextId(ns: RegistryNamespace): Int = nextNumber++
}

private fun loadedTo(rows: Int): Registries =
    Registries(LoadedRegistryStore(RegistryNamespace.BLOCK_STATE, rows)).also { it.load() }

class BlockStateRegistryTest : OneLedger() {
    private val state = RegistryNamespace.BLOCK_STATE
    private val east = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"
    private val west = "minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]"
    private val wires = List(64) { "minecraft:redstone_wire[power=$it]" }
    private val waterlogged = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=true]"

    @Test
    fun `the whole state string is the key`() {
        val id = log.registries.idForKey(state, east)

        assertEquals(id, log.registries.idForKey(state, east))
        assertEquals(east, log.registries.keyOf(state, id))
        assertNotEquals(id, log.registries.idForKey(state, west))
        assertNotEquals(id, log.registries.idForKey(state, waterlogged))
    }

    @Test
    fun `a batch persists every number it holds`() {
        log.registries.batched { for (key in wires) log.registries.idForKey(state, key) }
        reopen()

        for ((index, key) in wires.withIndex()) assertEquals(index, log.registries.lookupKey(state, key))
        assertEquals(wires.size, log.registries.idForKey(state, east))
    }

    // A number any thread can see is one a row in another database may already cite, so it has to be on
    // disk first, whether it was minted alone or in a batch.
    @Test
    fun `a number is seen by nobody until it is persisted`() {
        lateinit var registries: Registries
        val seenWhilePersisting = ArrayList<Int?>()
        registries = Registries(object : RegistryStore {
            var next = 0

            override fun loadAll() = emptyList<RegistryRow>()

            override fun nextId(ns: RegistryNamespace) = next++

            override fun persist(rows: List<RegistryRow>) {
                for (row in rows) seenWhilePersisting += registries.lookupKey(row.ns, String(row.keyBytes))
            }
        })

        registries.idForKey(state, east)
        registries.batched {
            registries.idForKey(state, west)
            assertEquals(1, registries.idForKey(state, west), "a batch sees its own numbers")
            registries.idForKey(state, waterlogged)
        }

        assertEquals(listOf(null, null, null), seenWhilePersisting)
        assertEquals(listOf(0, 1, 2), listOf(east, west, waterlogged).map { registries.lookupKey(state, it) })
    }

    // Loaded in part, the registry would mint fresh numbers over names it still holds on disk.
    @Test
    fun `a registry that cannot be read whole refuses to open`(@TempDir damaged: Path) {
        RocksItemLog(damaged).use { log -> wires.forEach { log.registries.idForKey(state, it) } }
        // The reopen flushes the registry from the log into a table file.
        RocksItemLog(damaged).close()
        damageTables(damaged, "registry")

        assertThrows(RocksDBException::class.java) { RocksItemLog(damaged) }
    }

    // The column family listing answers an empty list for a database it failed to read, which taken at
    // its word is a directory with no database in it.
    @Test
    fun `a database whose manifest cannot be named is refused rather than taken for none`(@TempDir damaged: Path) {
        RocksItemLog(damaged).close()
        damaged.resolve("CURRENT").toFile().writeText("nonsense")

        val refusal = assertThrows(IllegalStateException::class.java) { RocksItemLog(damaged) }
        assertTrue(refusal.message!!.contains("could not be read"))
    }

    @Test
    fun `the space says it is filling while it still has numbers to give`() {
        val below = loadedTo(ceil(state.maxId * REGISTRY_HIGH_WATER).toInt() - 1)
        assertTrue(below.fillWarnings().none { it.contains(state.name.lowercase()) })
        val registries = loadedTo(ceil(state.maxId * REGISTRY_HIGH_WATER).toInt())

        assertTrue(registries.fillWarnings().any { it.contains(state.name.lowercase()) })
        assertEquals(ceil(state.maxId * REGISTRY_HIGH_WATER).toInt(), registries.idForKey(state, east))
    }

    @Test
    fun `a refusal spends no number`() {
        val store = LoadedRegistryStore(state, state.maxId)
        val registries = Registries(store).also { it.load() }

        assertEquals(state.maxId, registries.idForKey(state, east))
        assertThrows(IllegalStateException::class.java) { registries.idForKey(state, west) }
        assertEquals(state.maxId + 1, store.nextNumber)
        assertTrue(registries.fillWarnings().any { it.contains(state.name.lowercase()) })
    }
}
