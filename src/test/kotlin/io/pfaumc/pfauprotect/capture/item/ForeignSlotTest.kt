package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.worldStub
import io.pfaumc.pfauprotect.disabledPlugin
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.storage.MemoryRegistryStore
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.storage.Registries
import io.pfaumc.pfauprotect.NoPlacedForms
import io.pfaumc.pfauprotect.stub
import io.pfaumc.pfauprotect.Spawns
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.capture.block.Spot
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.storage.nmsView
import net.minecraft.core.registries.Registries as VanillaRegistries
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import org.bukkit.Location
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.damage.DamageSource
import org.bukkit.event.entity.PlayerDeathEvent
import net.minecraft.world.item.ItemStack as NmsItemStack
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.testCodec
import io.pfaumc.pfauprotect.Vanilla
import org.junit.jupiter.api.extension.ExtendWith
import org.bukkit.Material
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
import org.bukkit.inventory.StonecutterInventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import org.bukkit.inventory.ItemStack as BukkitItemStack

// The pass that diffs a player's window, driven through the listener's own handlers with the window
// stubbed: what the game does between a click and the pass is done by hand to the slots.
@ExtendWith(Vanilla::class)
class ForeignSlotTest {
    private val player = UUID.fromString("00000000-0000-4000-8000-0000000000f1")
    private val world = UUID.fromString("00000000-0000-4000-8000-0000000000f2")

    private val codec by lazy { testCodec() }
    private val written = ArrayList<List<Transfer>>()
    private val unspent = ArrayList<Pair<Intent, Int>>()
    private var cursor: BukkitItemStack? = null
    private val own = arrayOfNulls<BukkitItemStack>(41)
    private var tick = 100
    private val spawns = Spawns()

    private val listener by lazy {
        ContainerCaptureListener(
            disabledPlugin(), written::add, codec,
            Registries(MemoryRegistryStore()), spawns.origins, NoPlacedForms,
            unspent = { intent, qty -> unspent += intent to qty },
            currentTick = { tick },
        )
    }

    // A player at the block at 1 2 3 with its window open over their own inventory.
    private fun seated(top: Inventory, type: InventoryType): Pair<Player, InventoryView> {
        lateinit var view: InventoryView
        val clicker = stub(
            Player::class.java,
            mapOf(
                "getUniqueId" to player,
                "getName" to "clicker",
                "getOpenInventory" to { _: Array<out Any?> -> view },
                "getInventory" to stub(
                    PlayerInventory::class.java,
                    mapOf(
                        "getSize" to 41,
                        "getStorageContents" to arrayOfNulls<BukkitItemStack>(36),
                        "getItem" to { args: Array<out Any?> -> own[args[0] as Int] },
                    ),
                ),
                "getItemOnCursor" to { _: Array<out Any?> -> cursor },
                "getLocation" to { _: Array<out Any?> -> Location(worldStub(world), 1.5, 2.0, 3.5) },
            ),
        )
        view = stub(
            InventoryView::class.java,
            mapOf(
                "getTopInventory" to top,
                "getPlayer" to clicker,
                "getType" to type,
                "getInventory" to top,
                "convertSlot" to { args: Array<out Any?> -> args[0] },
            ),
        )
        return clicker to view
    }

    // A furnace fills its result slot on its own listener, under the baseline of whoever has it open.
    // Taking the ingots out has to read as taking them out of the furnace, not as ingots from nowhere
    // and a furnace that still holds them.
    @Test
    fun `taking what the furnace made is a removal from the furnace`() {
        val furnace = arrayOfNulls<BukkitItemStack>(3)
        val (clicker, view) = seated(inventoryStub(furnace, InventoryType.FURNACE, Inventory::class.java), InventoryType.FURNACE)

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
        assertEquals(Cause.CONTAINER_REMOVE, row.cause)
        assertEquals(Confidence.FACT, row.confidence)
    }

    // A player who was online before the plugin came up has no window seen yet. The event that left a
    // reason for the pass relies on it to write the row, and has to hear that it did not.
    @Test
    fun `a pass with no window seen yet hands its reasons back once and does not drop them`() {
        val (clicker, view) = seated(inventoryStub(arrayOfNulls(3), InventoryType.FURNACE, Inventory::class.java), InventoryType.FURNACE)
        val drop = Intent(Cause.DROP_FROM_MENU, to = ItemEntityRef(UUID.randomUUID()), qty = 5)
        listener.intend(clicker, drop)
        listener.intend(clicker, Intent(Cause.CURSOR_TAKE))

        listener.recompute(clicker)
        listener.onOpen(InventoryOpenEvent(view))
        listener.recompute(clicker)

        assertEquals(listOf(drop to 5), unspent)
        assertEquals(emptyList<List<Transfer>>(), written)
    }

    private fun furnaceOpen(): Player {
        val (clicker, view) = seated(inventoryStub(arrayOfNulls(3), InventoryType.FURNACE, Inventory::class.java), InventoryType.FURNACE)
        listener.onOpen(InventoryOpenEvent(view))
        return clicker
    }

    private fun lastCause(clicker: Player, onCursor: Int): Cause {
        written.clear()
        cursor = BukkitItemStack(Material.STONE, onCursor)
        listener.recompute(clicker)
        return written.single().single().cause
    }

    // A command's pass can run before the command has touched the inventory, so its reason waits for
    // a later pass, and once spent it explains nothing more.
    @Test
    fun `a command's reason waits for the pass that shows it and is spent there`() {
        val clicker = furnaceOpen()
        listener.intend(clicker, Intent(Cause.CMD_GIVE, from = Void, untilTick = 105))

        listener.recompute(clicker)
        assertEquals(emptyList<List<Transfer>>(), written)

        assertEquals(Cause.CMD_GIVE, lastCause(clicker, 3))
        assertEquals(Cause.DIRECT_NEW_ITEM, lastCause(clicker, 5))
    }

    @Test
    fun `a command's reason is dropped once its ticks are over`() {
        val clicker = furnaceOpen()
        listener.intend(clicker, Intent(Cause.CMD_GIVE, from = Void, untilTick = 105))
        listener.recompute(clicker)
        tick = 106
        listener.recompute(clicker)

        assertEquals(Cause.DIRECT_NEW_ITEM, lastCause(clicker, 3))
    }

    // A change names no end, so nothing marks it used but the pass having gathered anything at all.
    @Test
    fun `a command's change is spent by the first pass that moves anything`() {
        val clicker = furnaceOpen()
        listener.intend(clicker, mutation(Cause.CMD_ENCHANT, untilTick = 105))

        listener.recompute(clicker)
        assertEquals(emptyList<List<Transfer>>(), written)

        assertEquals(Cause.CMD_ENCHANT, lastCause(clicker, 3))
        assertEquals(Cause.DIRECT_NEW_ITEM, lastCause(clicker, 5))
    }

    // The death event is the last moment the slot of each dropped stack is known, and the pass after it
    // finds the whole inventory gone: each stack has to be booked once, out of its own slot, and the pass
    // must not write any of it off a second time.
    @Test
    fun `a death books each stack out of its own slot once and the pass adds nothing`() {
        own[0] = BukkitItemStack(Material.STONE, 64)
        own[1] = BukkitItemStack(Material.STONE, 64)
        own[2] = CraftItemStack.asBukkitCopy(NmsItemStack(Items.DIAMOND_SWORD).apply {
            enchant(ServerRegistries.access.lookupOrThrow(VanillaRegistries.ENCHANTMENT).getOrThrow(Enchantments.VANISHING_CURSE), 1)
        })
        val clicker = furnaceOpen()
        val drops = mutableListOf(BukkitItemStack(Material.STONE, 64), BukkitItemStack(Material.STONE, 64))

        listener.onDeath(PlayerDeathEvent(clicker, stub(DamageSource::class.java), drops, 0, null, false))
        own.fill(null)
        listener.recompute(clicker)
        val stone = codec.encode(nmsView(drops[0])).key
        val spot = Spot(world, 1.5, 2.0, 3.5)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        assertEquals(64, spawns.origins.claim(first, spot, stone, 64))
        assertEquals(64, spawns.origins.claim(second, spot, stone, 64))

        val rows = written.flatten()
        assertEquals(1, rows.size, "expected only the vanished sword, got ${rows.map { it.cause to it.from }}")
        val vanished = rows.single()
        assertEquals(
            Triple(Cause.DEATH_DESTROY_VANISHING, PlayerInv(player, 2), Void),
            Triple(vanished.cause, vanished.from, vanished.to),
        )
        assertEquals(1, vanished.qty)
        val dropped = spawns.rows()
        assertEquals(
            setOf(
                Triple(PlayerInv(player, 0), ItemEntityRef(first), 64),
                Triple(PlayerInv(player, 1), ItemEntityRef(second), 64),
            ),
            dropped.map { Triple(it.from, it.to, it.qty) }.toSet(),
        )
        assertEquals(listOf(Cause.DEATH_DROP, Cause.DEATH_DROP), dropped.map { it.cause })
    }

    // A stonecutter hands back the stone it was given as slabs: one item turned into another rather
    // than a loss and a gain nobody can tie together.
    @Test
    fun `taking a stonecutter's result is one transformation of what went in`() {
        val cutter = arrayOfNulls<BukkitItemStack>(2)
        cutter[0] = BukkitItemStack(Material.STONE, 1)
        val (clicker, view) = seated(
            inventoryStub(cutter, InventoryType.STONECUTTER, StonecutterInventory::class.java),
            InventoryType.STONECUTTER,
        )

        listener.onOpen(InventoryOpenEvent(view))
        cutter[1] = BukkitItemStack(Material.STONE_SLAB, 2)
        listener.onClick(
            InventoryClickEvent(view, InventoryType.SlotType.RESULT, 1, ClickType.LEFT, InventoryAction.PICKUP_ALL),
        )
        cutter[0] = null
        cutter[1] = null
        cursor = BukkitItemStack(Material.STONE_SLAB, 2)
        listener.recompute(clicker)

        val transaction = written.single()
        assertEquals(
            setOf(Container(world, 1, 2, 3, 0) to Void to 1, Void to PlayerCursor(player) to 2),
            transaction.map { it.from to it.to to it.qty }.toSet(),
        )
        assertEquals(2, transaction.size)
        assertEquals(listOf(Kind.MUTATE, Kind.MUTATE), transaction.map { it.kind })
        assertEquals(listOf(Cause.STONECUTTER, Cause.STONECUTTER), transaction.map { it.cause })
        assertEquals(listOf(Confidence.FACT, Confidence.FACT), transaction.map { it.confidence })
    }

    // In creative the input stays where it was, so the result has no item it could have changed from.
    @Test
    fun `a station result with nothing consumed is a movement and not a mutation`() {
        val cutter = arrayOfNulls<BukkitItemStack>(2)
        cutter[0] = BukkitItemStack(Material.STONE, 1)
        val (clicker, view) = seated(
            inventoryStub(cutter, InventoryType.STONECUTTER, StonecutterInventory::class.java),
            InventoryType.STONECUTTER,
        )

        listener.onOpen(InventoryOpenEvent(view))
        cutter[1] = BukkitItemStack(Material.STONE_SLAB, 2)
        listener.onClick(
            InventoryClickEvent(view, InventoryType.SlotType.RESULT, 1, ClickType.LEFT, InventoryAction.PICKUP_ALL),
        )
        cutter[1] = null
        cursor = BukkitItemStack(Material.STONE_SLAB, 2)
        listener.recompute(clicker)

        val row = written.single().single()
        assertEquals(Void to PlayerCursor(player), row.from to row.to)
        assertEquals(Kind.TRANSFER, row.kind)
    }

    @Test
    fun `a closed chest hands back only the cursor, a grid hands back its slots`() {
        assertFalse(returnsOnClose("CHEST", cursorEmpty = true))
        assertTrue(returnsOnClose("CHEST", cursorEmpty = false))
        assertTrue(returnsOnClose("WORKBENCH", cursorEmpty = true))
        assertTrue(returnsOnClose("CRAFTING", cursorEmpty = true))
    }

    private fun <T : Inventory> inventoryStub(slots: Array<BukkitItemStack?>, type: InventoryType, kind: Class<T>): T {
        val block = stub(
            Block::class.java,
            mapOf("getWorld" to worldStub(world), "getX" to 1, "getY" to 2, "getZ" to 3),
        )
        return stub(
            kind,
            mapOf(
                "getType" to type,
                "getSize" to slots.size,
                "getItem" to { args: Array<out Any?> -> slots[args[0] as Int] },
                "getHolder" to stub(BlockInventoryHolder::class.java, mapOf("getBlock" to block)),
            ),
        )
    }
}
