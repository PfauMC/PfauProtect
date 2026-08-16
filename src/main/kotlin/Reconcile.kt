package io.pfaumc.pfauprotect

import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.abs
import org.bukkit.inventory.ItemStack as BukkitItemStack

data class Mismatch(val form: ByteArray, val ledger: Int, val actual: Int) {
    val difference: Int get() = actual - ledger
}

// Reading a player's slots is only safe on the region that ticks them, and reading the ledger has no
// business running there, so the two halves of a reconciliation are separate calls on purpose.
fun heldForms(player: Player, codec: ItemFormCodec): Map<FormKey, Int> {
    val totals = HashMap<FormKey, Int>()
    fun count(stack: BukkitItemStack?) {
        val encoded = codec.encodeOrNull(stack) ?: return
        totals.merge(FormKey(encoded.form), encoded.count, Int::plus)
    }

    val inventory = player.inventory
    for (slot in 0 until inventory.size) count(inventory.getItem(slot))
    val ender = player.enderChest
    for (slot in 0 until ender.size) count(ender.getItem(slot))
    count(player.itemOnCursor)
    return totals
}

// The transaction invariant catches a movement that lost half of itself. It cannot see a path that
// was never captured at all, because a transaction nobody wrote breaks nothing — and that is exactly
// the class of hole this looks for: what a player is holding has to equal the sum of what the ledger
// says they were ever handed. A difference is an address rather than an accusation: which form, and
// which direction, names the path the capture is blind to.
//
// Counting runs by form and not by item key, because the world carries the current wear of every
// tool and durability is not identity; comparing by key would part company at the first swing.
class Reconciliation(private val ledger: RocksItemLog) {

    fun compare(player: UUID, held: Map<FormKey, Int>): List<Mismatch> {
        val posted = ledger.formBalance(player)
        val mismatches = ArrayList<Mismatch>()
        val accounted = HashSet<Long>()
        for ((form, actual) in held) {
            // A form the ledger has never interned cannot have a balance, and asking for its id the
            // ordinary way would mint one, so an unknown form simply stands against zero.
            val formId = ledger.formId(form.form)
            if (formId != null) accounted += formId
            val recorded = formId?.let { posted[it] } ?: 0
            if (recorded != actual) mismatches += Mismatch(form.form, recorded, actual)
        }
        for ((formId, recorded) in posted) {
            if (formId in accounted) continue
            val form = ledger.form(formId) ?: continue
            mismatches += Mismatch(form, recorded, 0)
        }
        return mismatches.sortedByDescending { abs(it.difference) }
    }

    fun name(form: ByteArray): String =
        ledger.registries.keyOf(RegistryNamespace.ITEM_TYPE, itemTypeIdOf(form)) ?: "item form"
}
