package io.pfaumc.pfauprotect.storage
import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.OneLedger
import io.pfaumc.pfauprotect.Vanilla
import org.bukkit.event.inventory.InventoryType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@ExtendWith(Vanilla::class)
class MenuTypeRegistryTest : OneLedger() {
    private val menu = RegistryNamespace.MENU_TYPE

    @Test
    fun `a menu is keyed by the name of its type and not by its ordinal`() {
        val workbench = log.registries.idForKey(menu, InventoryType.WORKBENCH.name)

        assertEquals(workbench, log.registries.idForKey(menu, InventoryType.WORKBENCH.name))
        assertEquals(InventoryType.WORKBENCH.name, log.registries.keyOf(menu, workbench))
        assertNotEquals(workbench, log.registries.idForKey(menu, InventoryType.ANVIL.name))
    }

    @Test
    fun `a menu number survives a close and a reopen`() {
        val stored = InventoryType.entries.associate { it.name to log.registries.idForKey(menu, it.name) }
        reopen()

        for ((name, id) in stored) {
            assertEquals(id, log.registries.lookupKey(menu, name), "menu $name was renumbered")
            assertEquals(name, log.registries.keyOf(menu, id))
        }
    }

    // The space is minted whole at startup, so it has to fit whole, and a number handed out later for
    // a type this build has never seen must not collide with one already on disk.
    @Test
    fun `every inventory type fits the space and gets a number of its own`() {
        log.registries.batched { fillTypeRegistries(log.registries) }

        val ids = InventoryType.entries.map { log.registries.lookupKey(menu, it.name) }
        assertTrue(ids.none { it == null }, "an inventory type went unregistered")
        assertEquals(InventoryType.entries.size, ids.toSet().size, "two types share one number")
        assertTrue(ids.filterNotNull().all { it <= menu.maxId }, "a number ran past the space")
    }
}
