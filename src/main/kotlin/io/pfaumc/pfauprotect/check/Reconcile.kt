package io.pfaumc.pfauprotect.check

import io.pfaumc.pfauprotect.model.ownHolders
import io.pfaumc.pfauprotect.storage.FormKey
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.storage.itemTypeIdOf
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.abs

data class Mismatch(val form: ByteArray, val ledger: Int, val actual: Int) {
    val difference: Int get() = actual - ledger
}

fun heldForms(player: Player, codec: ItemFormCodec): Map<FormKey, Int> =
    (player.inventory.contents + player.enderChest.contents + player.itemOnCursor)
        .mapNotNull { codec.encodeOrNull(it) }
        .groupingBy { FormKey(it.form) }
        .fold(0) { total, encoded -> total + encoded.count }

// Finds paths the capture never saw, which the transaction invariant cannot: what a player holds has
// to equal their ledger balance. Counted by form rather than item key, because a tool's wear changes
// with every swing and is not identity.
class Reconciliation(private val ledger: RocksItemLog) {

    fun compare(player: UUID, held: Map<FormKey, Int>): List<Mismatch> {
        val holders = ownHolders(player, craftingGrid = false)
        val posted = ledger.slotBalances(holders, Long.MAX_VALUE).values.flatMap { it.entries }
            .groupingBy { it.key }
            .fold(0) { total, slot -> total + slot.value }
        return (held.keys + posted.keys).mapNotNull { form ->
            val recorded = posted[form] ?: 0
            val actual = held[form] ?: 0
            if (recorded == actual) null else Mismatch(form.form, recorded, actual)
        }.sortedByDescending { abs(it.difference) }
    }

    fun name(form: ByteArray): String =
        ledger.registries.keyOf(RegistryNamespace.ITEM_TYPE, itemTypeIdOf(form)) ?: "item form"
}
