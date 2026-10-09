package io.pfaumc.pfauprotect

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.PlayerInventory
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.Proxy
import java.util.UUID
import org.bukkit.inventory.ItemStack as BukkitItemStack

// A furnace fills its result slot on its own listener, under the baseline of whoever has it open.
// Taking the ingots out has to read as taking them out of the furnace, not as ingots from nowhere
// and a furnace that still holds them.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForeignSlotTest {
    private val player = UUID.fromString("00000000-0000-4000-8000-0000000000f1")
    private val world = UUID.fromString("00000000-0000-4000-8000-0000000000f2")

    private lateinit var codec: ItemFormCodec

    @BeforeAll
    fun loadServerRegistries() {
        codec = ItemFormCodec(Registries(MemoryRegistryStore()), ServerRegistries.access)
    }

    @Test
    fun `taking what the furnace made is a removal from the furnace`() {
        val furnace = arrayOfNulls<BukkitItemStack>(3)
        var cursor: BukkitItemStack? = null
        val top = inventoryStub(furnace)
        lateinit var view: InventoryView
        val clicker = stub(
            Player::class.java,
            mapOf(
                "getUniqueId" to player,
                "getName" to "clicker",
                "getOpenInventory" to { _: Array<out Any?> -> view },
                "getInventory" to stub(
                    PlayerInventory::class.java,
                    mapOf("getSize" to 41, "getStorageContents" to arrayOfNulls<BukkitItemStack>(36)),
                ),
                "getItemOnCursor" to { _: Array<out Any?> -> cursor },
            ),
        )
        view = stub(
            InventoryView::class.java,
            mapOf(
                "getTopInventory" to top,
                "getPlayer" to clicker,
                "getType" to InventoryType.FURNACE,
                "getInventory" to top,
                "convertSlot" to { args: Array<out Any?> -> args[0] },
            ),
        )
        val written = ArrayList<List<Transfer>>()
        val listener = ContainerCaptureListener(
            stub(Plugin::class.java, mapOf("isEnabled" to false)), written::add, codec,
            Registries(MemoryRegistryStore()), SpawnOrigins(TickCoalescer { }), noPlacedForms(),
        )

        listener.onOpen(InventoryOpenEvent(view))
        furnace[2] = BukkitItemStack(Material.IRON_INGOT, 4)
        listener.onClick(
            InventoryClickEvent(view, InventoryType.SlotType.RESULT, 2, ClickType.LEFT, InventoryAction.PICKUP_ALL),
        )
        // What the game does once the click is let through; the pass follows on the next tick.
        furnace[2] = null
        cursor = BukkitItemStack(Material.IRON_INGOT, 4)
        listener.recompute(clicker)

        val rows = written.flatten()
        assertEquals(1, rows.size, "expected one movement, got $rows")
        val row = rows.single()
        assertEquals(Container(world, 1, 2, 3, 2), row.from)
        assertEquals(PlayerCursor(player), row.to)
        assertEquals(4, row.qty)
    }

    private fun inventoryStub(slots: Array<BukkitItemStack?>): Inventory {
        val block = stub(
            Block::class.java,
            mapOf("getWorld" to stub(World::class.java, mapOf("getUID" to world)), "getX" to 1, "getY" to 2, "getZ" to 3),
        )
        return stub(
            Inventory::class.java,
            mapOf(
                "getType" to InventoryType.FURNACE,
                "getSize" to slots.size,
                "getItem" to { args: Array<out Any?> -> slots[args[0] as Int] },
                "getHolder" to stub(BlockInventoryHolder::class.java, mapOf("getBlock" to block)),
            ),
        )
    }

    private fun noPlacedForms() = object : PlacedForms {
        override fun formAt(world: UUID, x: Int, y: Int, z: Int): ByteArray? = null
        override fun formsAt(positions: List<WorldBlock>): Map<WorldBlock, ByteArray> = emptyMap()
        override fun setFormAt(world: UUID, x: Int, y: Int, z: Int, form: ByteArray) = Unit
        override fun clearFormAt(world: UUID, x: Int, y: Int, z: Int) = Unit
        override fun clearFormsAt(positions: List<WorldBlock>) = Unit
    }

    // An answer may be a function of the call's arguments; a primitive nobody answered for reads as
    // zero rather than failing to unbox.
    private fun <T : Any> stub(type: Class<T>, answers: Map<String, Any?> = emptyMap()): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            when (val answer = answers[method.name]) {
                is Function1<*, *> -> (answer as (Array<out Any?>) -> Any?)(args ?: emptyArray())
                null -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
                else -> answer
            }
        } as T
    }
}
