package io.pfaumc.pfauprotect.storage
import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.OneLedger
import io.pfaumc.pfauprotect.Vanilla
import org.bukkit.event.inventory.InventoryType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@ExtendWith(Vanilla::class)
class MenuTypeRegistryTest : OneLedger() {
    private val menu = RegistryNamespace.MENU_TYPE

    // The space is minted whole at startup, so every inventory type has to get a number inside it.
    @Test
    fun `every inventory type fits the space`() {
        log.registries.batched { fillTypeRegistries(log.registries) }

        val ids = InventoryType.entries.map { log.registries.lookupKey(menu, it.name) }
        assertTrue(ids.filterNotNull().all { it <= menu.maxId }, "a number ran past the space")
    }
}
