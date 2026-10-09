package io.pfaumc.pfauprotect.tools

import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.PlayerEnder
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.PostingRef
import io.pfaumc.pfauprotect.storage.EntryCodec
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.storage.RocksItemLog
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.RocksDB
import java.nio.file.Path
import java.util.UUID

/**
 * Reads a copy of the ledger offline, for what `/pp lookup` has no view of: the rows of a pile on the
 * ground, of a mob's pocket, of every holder at once. The bot's scenarios run it through
 * `scripts/ledger-rows.sh`, which takes the copy from inside the test server's container.
 *
 *     <ledger dir> since <millis> [CAUSE...]        every row since then, of these causes or all
 *     <ledger dir> holder <holder> [<from millis>]   one holder's rows: item:<uuid>, slot:<uuid>:<n>,
 *                                                    inv:<uuid>:<n>, ender:<uuid>:<n>, container:<world>:<x>:<y>:<z>:<n>
 *
 * One row a line: `ts=… cause=… holder=… qty=… item=… counterparty=… actor=… confidence=… tx=…`.
 */
fun main(args: Array<String>) {
    RocksItemLog(Path.of(args[0])).use { log ->
        val print = { e: LedgerEntry -> println(line(log, e)) }
        when (args[1]) {
            "since" -> {
                val from = args[2].toLong()
                val causes = args.drop(3).toSet()
                everyEntry(log) { e ->
                    if (e.timestamp >= from && (causes.isEmpty() || e.cause.name in causes)) print(e)
                }
            }
            "holder" -> holderRows(log, holderOf(args[2]), args.getOrNull(3)?.toLong() ?: 0, print)
            else -> error("unknown query ${args[1]}")
        }
    }
}

// A page at a time, each from the timestamp the last one ended at; the rows of that millisecond it
// already printed are skipped by their reference.
private fun holderRows(log: RocksItemLog, holder: Holder, from: Long, print: (LedgerEntry) -> Unit) {
    val printed = HashSet<PostingRef>()
    var since = from
    while (true) {
        val page = log.holderPage(holder, since, Long.MAX_VALUE, limit = PAGE)
        page.entries.filter { printed.add(it.ref) }.forEach(print)
        if (page.complete || page.entries.isEmpty()) return
        val last = page.entries.last().timestamp
        if (last == since) error("more than $PAGE rows of $holder at $since; the rest are not printed")
        since = last
    }
}

private const val PAGE = 10_000

private fun holderOf(spec: String): Holder {
    val p = spec.split(":")
    return when (p[0]) {
        "item" -> ItemEntityRef(UUID.fromString(p[1]))
        "slot" -> EntitySlot(UUID.fromString(p[1]), p[2].toInt())
        "inv" -> PlayerInv(UUID.fromString(p[1]), p[2].toInt())
        "ender" -> PlayerEnder(UUID.fromString(p[1]), p[2].toInt())
        "container" -> Container(UUID.fromString(p[1]), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt())
        else -> error("unknown holder $spec")
    }
}

// The entries family is keyed by holder, not by time, so a time window is a walk over all of it. Private
// to the log, and a test tool has no business widening it.
private fun everyEntry(log: RocksItemLog, action: (LedgerEntry) -> Unit) {
    val db = RocksItemLog::class.java.getDeclaredField("db").apply { isAccessible = true }.get(log) as RocksDB
    val cf = RocksItemLog::class.java.getDeclaredField("entriesCf").apply { isAccessible = true }.get(log) as ColumnFamilyHandle
    db.newIterator(cf).use { it ->
        it.seekToFirst()
        while (it.isValid) {
            EntryCodec.decodeOrNull(it.key(), it.value(), log.registries)?.let(action)
            it.next()
        }
    }
}

private fun line(log: RocksItemLog, e: LedgerEntry) =
    "ts=${e.timestamp} cause=${e.cause.name} holder=${e.holder} qty=${e.qty} item=${itemOf(log, e.itemFormId)} " +
        "counterparty=${e.counterparty} actor=${e.actor ?: "-"} confidence=${e.confidence} tx=${e.txId}"

// A form starts with the item's registry id as a varint.
private fun itemOf(log: RocksItemLog, formId: Long): String {
    val form = log.form(formId) ?: return "form#$formId"
    var id = 0
    var shift = 0
    var at = 0
    do {
        val b = form[at++].toInt() and 0xff
        id = id or ((b and 0x7f) shl shift)
        shift += 7
    } while (b and 0x80 != 0)
    return log.registries.keyOf(RegistryNamespace.ITEM_TYPE, id) ?: "item#$id"
}
