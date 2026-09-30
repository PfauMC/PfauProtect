package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.storage.MemoryRegistryStore
import io.pfaumc.pfauprotect.storage.PlacedForms
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.storage.Registries
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.capture.block.Spot
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.WorldBlock
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.InventoryView
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.Proxy
import java.util.UUID
import org.bukkit.inventory.ItemStack as BukkitItemStack

// A player who leaves holding something on the cursor has it dropped for them after the quit event.
// By then the pass that counts a player's slots is gone, so the drop has to be written where it is
// seen. A listener holding no line of reference for the player is exactly that state.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DisconnectDropTest {
    private val player = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
    private val dropped = UUID.fromString("00000000-0000-4000-8000-0000000000d2")

    private lateinit var codec: ItemFormCodec

    @BeforeAll
    fun loadServerRegistries() {
        codec = ItemFormCodec(Registries(MemoryRegistryStore()), ServerRegistries.access)
    }

    @Test
    fun `a drop by a player the pass can no longer count is written where it is seen`() {
        val written = ArrayList<List<Transfer>>()
        val origins = SpawnOrigins(TickCoalescer { })
        val listener = ContainerCaptureListener(
            // Disabled, so the old path's scheduling is a no-op and its silence is the only symptom.
            stub(Plugin::class.java, mapOf("isEnabled" to false)), written::add, codec,
            Registries(MemoryRegistryStore()), origins, noPlacedForms(),
        )

        listener.onDropItem(PlayerDropItemEvent(playerStub(), itemStub(BukkitItemStack(Material.DIAMOND, 3))))

        assertEquals(1, written.size, "the drop left no row at all: $written")
        val row = written.single().single()
        assertEquals(Cause.DROP_ON_DISCONNECT, row.cause)
        assertEquals(PlayerCursor(player), row.from)
        assertEquals(ItemEntityRef(dropped), row.to)
        assertEquals(3, row.qty)
        assertEquals(Kind.TRANSFER, row.kind)
        // The row is itself the birth of the entity, so the spawn funnel has to stay quiet about it or
        // the same three diamonds are born twice.
        val spot = Spot(UUID.randomUUID(), 0.0, 0.0, 0.0)
        assertEquals(3, origins.claim(dropped, spot, ItemKey(row.form, null), 3))
        assertTrue(origins.isEmpty, "the note outlived the spawn it was left for")
    }

    private fun playerStub(): Player = stub(
        Player::class.java,
        mapOf(
            "getUniqueId" to player,
            // What the old path reads to tell a drop out of a window from one out of the hand.
            "getOpenInventory" to stub(InventoryView::class.java, mapOf("getType" to InventoryType.CRAFTING)),
        ),
    )

    private fun itemStub(stack: BukkitItemStack): Item =
        stub(Item::class.java, mapOf("getUniqueId" to dropped, "getItemStack" to stack))

    private fun noPlacedForms() = object : PlacedForms {
        override fun formAt(world: UUID, x: Int, y: Int, z: Int): ByteArray? = null
        override fun formsAt(positions: List<WorldBlock>): Map<WorldBlock, ByteArray> = emptyMap()
        override fun setFormAt(world: UUID, x: Int, y: Int, z: Int, form: ByteArray) = Unit
        override fun clearFormAt(world: UUID, x: Int, y: Int, z: Int) = Unit
        override fun clearFormsAt(positions: List<WorldBlock>) = Unit
    }

    private fun <T : Any> stub(type: Class<T>, answers: Map<String, Any?> = emptyMap()): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            answers[method.name]
        } as T
    }
}
