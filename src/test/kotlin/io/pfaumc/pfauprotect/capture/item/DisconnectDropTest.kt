package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.disabledPlugin
import io.pfaumc.pfauprotect.NoPlacedForms
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.storage.MemoryRegistryStore
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.storage.Registries
import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.capture.block.Spot
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.stub
import io.pfaumc.pfauprotect.testCodec
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.InventoryView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID
import org.bukkit.inventory.ItemStack as BukkitItemStack

// A player who leaves holding something on the cursor has it dropped for them after the quit event.
// By then the pass that counts a player's slots is gone, so the drop has to be written where it is
// seen. A listener holding no line of reference for the player is exactly that state.
class DisconnectDropTest {
    private val player = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
    private val dropped = UUID.fromString("00000000-0000-4000-8000-0000000000d2")

    private val codec by lazy { testCodec() }

    @Test
    fun `a drop by a player the pass can no longer count is written where it is seen`() {
        val written = ArrayList<List<Transfer>>()
        val origins = SpawnOrigins(TickCoalescer { })
        val listener = ContainerCaptureListener(
            // Disabled, so the pass it would otherwise schedule never runs and cannot write the row instead.
            disabledPlugin(), written::add, codec,
            Registries(MemoryRegistryStore()), origins, NoPlacedForms,
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
        assertEquals(0, origins.claim(dropped, spot, ItemKey(row.form, null), 3), "the note outlived the spawn it was left for")
    }

    private fun playerStub(): Player = stub(
        Player::class.java,
        mapOf(
            "getUniqueId" to player,
            // What the pass reads to tell a drop out of a window from one out of the hand.
            "getOpenInventory" to stub(InventoryView::class.java, mapOf("getType" to InventoryType.CRAFTING)),
        ),
    )

    private fun itemStub(stack: BukkitItemStack): Item =
        stub(Item::class.java, mapOf("getUniqueId" to dropped, "getItemStack" to stack))
}
