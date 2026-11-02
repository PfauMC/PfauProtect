package io.pfaumc.pfauprotect.command

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.pfaumc.pfauprotect.Ui
import io.pfaumc.pfauprotect.capture.entity.nbtOf
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.tr
import io.pfaumc.pfauprotect.say
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.`object`.ObjectContents
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.util.ProblemReporter
import net.minecraft.world.ItemStackWithSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.TagValueInput
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** What a container held, as one payload of a block or an entity kept it: its type, its shape, its filled slots. */
internal class Stored(val id: String, val columns: Int, val cells: Int, val items: Map<Int, ItemStack>)

private const val ROW = 9
private val CHEST = ROW to 27

// The shape of each container whose size is known: columns and slots. The id is the block entity's or the
// entity's, so every colour of shulker box is one "shulker_box" and a copper chest is a "chest".
private val SHAPES = mapOf(
    "minecraft:chest" to CHEST, "minecraft:trapped_chest" to CHEST, "minecraft:barrel" to CHEST,
    "minecraft:shulker_box" to CHEST, "minecraft:chest_minecart" to CHEST,
    "minecraft:hopper" to (5 to 5), "minecraft:hopper_minecart" to (5 to 5), "minecraft:brewing_stand" to (5 to 5),
    "minecraft:dispenser" to (3 to 9), "minecraft:dropper" to (3 to 9), "minecraft:crafter" to (3 to 9),
    "minecraft:furnace" to (3 to 3), "minecraft:blast_furnace" to (3 to 3), "minecraft:smoker" to (3 to 3),
    "minecraft:campfire" to (4 to 4), "minecraft:chiseled_bookshelf" to (3 to 6), "minecraft:shelf" to (3 to 3),
)

// Every wood's chest boat is an entity type of its own, all of them a chest.
private fun shapeOf(id: String) = SHAPES[id] ?: CHEST.takeIf { id.endsWith("_chest_boat") || id.endsWith("_chest_raft") }

/** Whether a payload holds any item worth a window: an emptied chest is still a payload, with nothing in it. */
internal fun holdsItems(bytes: ByteArray): Boolean =
    runCatching { nbtOf(bytes).getListOrEmpty("Items").isNotEmpty() }.getOrDefault(false)

/**
 * The slots of a payload, null when it holds nothing. Read the way the game loads a container, so a slot
 * a later version wrote and this one cannot read is left empty rather than failing the window.
 */
internal fun contentsOf(bytes: ByteArray, registries: HolderLookup.Provider): Stored? {
    val tag = runCatching { nbtOf(bytes) }.getOrNull() ?: return null
    val items = TagValueInput.create(ProblemReporter.DISCARDING, registries, tag)
        .listOrEmpty("Items", ItemStackWithSlot.CODEC)
        .filter { !it.stack().isEmpty }
        .associate { it.slot() to it.stack() }
    if (items.isEmpty()) return null
    val id = tag.getStringOr("id", "")
    // A donkey's chest, whose size goes with its strength, or anything not in the table: rows of nine, as
    // many as its last filled slot needs. A known one is never cut short by a slot beyond it either.
    val (columns, size) = shapeOf(id) ?: (ROW to 0)
    val cells = maxOf(size, (items.keys.max() / columns + 1) * columns)
    return Stored(id, columns, cells, items)
}

/**
 * The flat picture a dialog can draw an item with: a sprite of the client's own atlases, from a table
 * read off the client jar (`scripts/item-sprites.py`). An item drawn by code (a chest, a banner, a head)
 * has none there and is named instead.
 */
internal object ItemSprites {
    private val table: Map<String, Pair<String, String>> by lazy {
        val text = ItemSprites::class.java.getResourceAsStream("/item-sprites.txt")?.bufferedReader()?.use { it.readText() } ?: ""
        text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val (item, atlas, sprite) = line.split(' ')
            item to (atlas to sprite)
        }
    }

    /** Atlas and sprite of the item `minecraft:<path>`, or null. */
    fun of(key: String): Pair<String, String>? = table[key.removePrefix("minecraft:")]
}

private const val CELL_WIDTH = 34
private const val CARD_WIDTH = 160

private fun keyOf(stack: ItemStack) = BuiltInRegistries.ITEM.getKey(stack.item).toString()

/** A cell's face: the sprite, or the item's name when it has no sprite, and how many. */
private fun face(stack: ItemStack): Component {
    val picture = ItemSprites.of(keyOf(stack))?.let { (atlas, sprite) -> Component.`object`(ObjectContents.sprite(Key.key(atlas), Key.key(sprite))) }
        ?: CraftItemStack.asBukkitCopy(stack).effectiveName()
    val count = if (stack.count > 1) Ui.text(" ${stack.count}") else Component.empty()
    return Component.text().append(picture).append(count).build()
}

private fun name(stack: ItemStack): Component =
    Component.text().append(CraftItemStack.asBukkitCopy(stack).effectiveName()).append(Ui.text(" ×${stack.count}", Ui.MUTED)).build()

private fun heading(contents: Stored): Component =
    Component.text().append(Ui.text("PfauProtect · ", Ui.FAINT)).append(Ui.any(contents.id).color(Ui.THING)).build()

/**
 * What a container held, as a grid of its slots, for the click on a lookup line. A cell opens that one
 * item, drawn by the game itself with its whole tooltip; an empty one opens the grid again, as a button
 * without an action would close it.
 */
internal fun showContents(player: Player, payload: Long, contents: Stored) {
    val grid = "/pp contents $payload"
    val cells = (0 until contents.cells).map { slot ->
        val stack = contents.items[slot]
        if (stack == null) ActionButton.create(Component.empty(), null, CELL_WIDTH, DialogAction.staticAction(ClickEvent.runCommand(grid)))
        else ActionButton.create(face(stack), name(stack), CELL_WIDTH, DialogAction.staticAction(ClickEvent.runCommand("$grid $slot")))
    }
    val close = ActionButton.create(Ui.text(tr("Close", "Закрыть")), null, CELL_WIDTH * 3, null)
    val base = DialogBase.builder(heading(contents)).build()
    val type = DialogType.multiAction(cells).exitAction(close).columns(contents.columns).build()
    player.showDialog(Dialog.create { it.empty().base(base).type(type) })
}

/** One item of a container: the game's own picture of it, its tooltip on the hover, and the way back to the grid. */
internal fun showItem(player: Player, payload: Long, contents: Stored, slot: Int) {
    val stack = contents.items[slot] ?: return showContents(player, payload, contents)
    val about = DialogBody.plainMessage(
        Component.text().append(name(stack)).append(Ui.text(" · ${tr("slot", "слот")} $slot", Ui.MUTED)).build(), CARD_WIDTH,
    )
    val item = DialogBody.item(CraftItemStack.asBukkitCopy(stack)).description(about).showDecorations(true).showTooltip(true).build()
    val base = DialogBase.builder(heading(contents)).body(listOf(item)).build()
    val back = ActionButton.create(Ui.text(tr("Back", "Назад")), null, 100, DialogAction.staticAction(ClickEvent.runCommand("/pp contents $payload")))
    val close = ActionButton.create(Ui.text(tr("Close", "Закрыть")), null, 100, null)
    // Escape runs the second button of a confirmation, which is Close.
    player.showDialog(Dialog.create { it.empty().base(base).type(DialogType.confirmation(back, close)) })
}

/** `/pp contents <payload> [slot]`: the payload is read off the player's thread, as every lookup is. */
internal fun Plugin.openContents(player: Player, ledger: RocksItemLog, payload: Long, slot: Int?) {
    offThread(player, "contents of payload $payload failed", "The lookup failed; the server log has the details.") {
        val contents = ledger.payload(payload)?.let { contentsOf(it, MinecraftServer.getServer().registryAccess()) }
        when {
            contents == null -> player.say("Nothing was inside.")
            slot == null -> showContents(player, payload, contents)
            else -> showItem(player, payload, contents, slot)
        }
    }
}
