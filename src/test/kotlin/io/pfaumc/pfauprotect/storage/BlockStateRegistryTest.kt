package io.pfaumc.pfauprotect.storage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.ceil

// Registries as a database that already holds `rows` numbers, so the far end of the space can be
// looked at without minting the numbers in front of it. The counter carries on where the rows stop,
// which is where a real store would have left it.
private class LoadedRegistryStore(private val ns: RegistryNamespace, private val rows: Int) : RegistryStore {
    var nextNumber = rows
        private set

    override fun loadAll(): List<RegistryRow> = List(rows) { RegistryRow(ns, "state $it".toByteArray(), it) }

    override fun put(ns: RegistryNamespace, keyBytes: ByteArray, id: Int) = Unit

    override fun nextId(ns: RegistryNamespace): Int = nextNumber++
}

private fun loadedTo(rows: Int): Registries =
    Registries(LoadedRegistryStore(RegistryNamespace.BLOCK_STATE, rows)).also { it.load() }

class BlockStateRegistryTest {
    private val state = RegistryNamespace.BLOCK_STATE
    private val east = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"
    private val west = "minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]"
    private val waterlogged = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=true]"
    private val signText = """{"messages":["hello","","",""]}""".toByteArray()

    @Test
    fun `the whole state string is the key`(@TempDir dir: Path) {
        RocksItemLog(dir).use { log ->
            val id = log.registries.idForKey(state, east)

            assertEquals(id, log.registries.idForKey(state, east))
            assertEquals(east, log.registries.keyOf(state, id))
            // Two states of one block differing in a single property are two keys, which is the whole
            // point of the string: the property is in it.
            assertNotEquals(id, log.registries.idForKey(state, west))
            assertNotEquals(id, log.registries.idForKey(state, waterlogged))
        }
    }

    @Test
    fun `a number survives a close and a reopen`(@TempDir dir: Path) {
        val id = RocksItemLog(dir).use { it.registries.idForKey(state, east) }

        RocksItemLog(dir).use { log ->
            assertEquals(id, log.registries.lookupKey(state, east))
            assertEquals(east, log.registries.keyOf(state, id))
            assertNotEquals(id, log.registries.idForKey(state, west))
        }
    }

    @Test
    fun `a staging session persists every number and payload it holds`(@TempDir dir: Path) {
        val states = List(64) { "minecraft:redstone_wire[power=$it]" }
        val payloadId = RocksItemLog(dir).use { log ->
            log.staged {
                for (key in states) log.registries.idForKey(state, key)
                log.payloads.idOf(signText)
            }
        }

        RocksItemLog(dir).use { log ->
            for ((index, key) in states.withIndex()) assertEquals(index, log.registries.lookupKey(state, key))
            assertArrayEquals(signText, log.payload(payloadId))
            assertEquals(payloadId, log.payloads.lookup(signText))
            assertNotEquals(payloadId, log.payloads.idOf("""{"messages":["other","","",""]}""".toByteArray()))
        }
    }

    @Test
    fun `the space says it is filling while it still has numbers to give`() {
        val registries = loadedTo(ceil(state.maxId * REGISTRY_HIGH_WATER).toInt())

        assertTrue(registries.fillWarnings().any { it.contains(state.name.lowercase()) })
        assertEquals(ceil(state.maxId * REGISTRY_HIGH_WATER).toInt(), registries.idForKey(state, east))
    }

    @Test
    fun `a refusal spends no number`() {
        val store = LoadedRegistryStore(state, state.maxId)
        val registries = Registries(store).also { it.load() }

        // The last number the space has is still handed out; only the one after it has nowhere to go.
        assertEquals(state.maxId, registries.idForKey(state, east))
        assertThrows(IllegalStateException::class.java) { registries.idForKey(state, west) }
        assertEquals(state.maxId + 1, store.nextNumber)
        assertTrue(registries.fillWarnings().any { it.contains(state.name.lowercase()) })
    }
}
