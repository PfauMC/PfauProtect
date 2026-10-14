package io.pfaumc.pfauprotect.storage
import io.pfaumc.pfauprotect.OneLedger
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.ledgerEntry
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.PostingRef
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.world
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class StorageTest : OneLedger() {
    private val chest = Container(world, 100, 64, -200, 0)
    private val chestUpperSlot = Container(world, 100, 64, -200, 5)
    private val aliceInv = PlayerInv(alice, 9)
    private val farSideOfChunk = Container(world, 104, 64, -200, 0)
    private val cobblestone = byteArrayOf(1, 2, 3)
    private val pickaxe = byteArrayOf(4, 5)
    private val torch = byteArrayOf(6, 7, 8, 9)
    // Above any world, so no posting to it has a key.
    private val unreachable = Container(world, 100, 1_000_000, -200, 0)

    @BeforeEach
    fun seed() {
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, cobblestone, null, 32, T0))
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chestUpperSlot, aliceInv, cobblestone, null, 5, T0 + 10))
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, pickaxe, 7, 1, T0 + 20))
        log.submit(Transfer(Cause.ITEM_DESPAWN, chest, Void, cobblestone, null, 3, T0 + 30, confidence = Confidence.INFERRED))
        log.drain()
    }

    // A holder prefix covers every slot its owner has, so this is every row of one form alice holds.
    private fun aliceRows(form: ByteArray): List<LedgerEntry> =
        log.holderEntries(PlayerInv(alice, 0), 0, Long.MAX_VALUE, limit = 1000)
            .filter { it.itemFormId == log.forms.lookup(form) }

    // A stack moved between two slots of one inventory is the commonest movement there is, and both
    // of its ends address the same player.
    @Test
    fun `a move between two slots of one player leaves two rows that cancel`() {
        fun balance() = HashMap<FormKey, Int>().apply {
            for (slots in log.slotBalances(listOf(PlayerInv(alice, 0)), Long.MAX_VALUE).values) {
                for ((form, qty) in slots) merge(form, qty, Int::plus)
            }
        }
        val before = balance()
        assertEquals(mapOf(FormKey(cobblestone) to -27, FormKey(pickaxe) to -1), before)
        log.submit(
            Transfer(Cause.QUICK_MOVE, PlayerInv(alice, 0), PlayerInv(alice, 9), cobblestone, null, 32, T0 + 40)
        )
        log.drain()

        val moved = aliceRows(cobblestone).filter { it.timestamp == T0 + 40 }
        assertEquals(listOf(-32, 32), moved.map { it.qty })
        assertEquals(listOf(PlayerInv(alice, 0), PlayerInv(alice, 9)), moved.map { it.holder })
        assertEquals(listOf(PlayerInv(alice, 9), PlayerInv(alice, 0)), moved.map { it.counterparty })
        assertEquals(1, moved.map { it.txId }.distinct().size)
        assertEquals(before, balance())
        for (half in moved) assertEquals(moved.toSet(), log.transactionEntries(half).toSet(), "from $half")
        assertEquals(emptyList<String>(), log.sweep(100).gaps)
    }

    @Test
    fun `a balance up to a moment leaves out the rows after it`() {
        assertEquals(
            mapOf(aliceInv to mapOf(FormKey(cobblestone) to -27)),
            log.slotBalances(listOf(PlayerInv(alice, 0)), T0 + 10),
        )
    }

    // An item changing in place is one holder losing the old form and taking on the new one, written
    // as two movements through the Void under a single transaction. Both of them land on the same
    // holder at the same instant, so the ordinal is the only thing keeping them from being one row.
    @Test
    fun `an item changed in place keeps a row for each form`() {
        val bench = PlayerInv(alice, 3)
        log.submit(
            listOf(
                Transfer(Cause.ANVIL_COMBINE, bench, Void, pickaxe, 7, 1, T0 + 40, kind = Kind.MUTATE),
                Transfer(Cause.ANVIL_COMBINE, Void, bench, torch, 7, 1, T0 + 40, kind = Kind.MUTATE),
            )
        )
        log.drain()

        val rows = log.holderEntries(bench, 0, Long.MAX_VALUE).filter { it.kind == Kind.MUTATE }
        assertEquals(2, rows.size)
        assertEquals(listOf(-1, 1), rows.map { it.qty })
        assertEquals(listOf(0, 1), rows.map { it.ordinal })
        assertEquals(listOf(log.forms.lookup(pickaxe), log.forms.lookup(torch)), rows.map { it.itemFormId })
        assertEquals(1, rows.map { it.txId }.distinct().size)
        assertEquals(emptyList<String>(), log.sweep(100).gaps)
    }

    // Past the first pair a posting sits at an ordinal that faces nothing, so a transaction asked
    // from one of its own halves must not come back smaller than it does from the others.
    @Test
    fun `a transaction of four postings reads the same from each of them`() {
        log.submit(
            listOf(
                Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 1, T0 + 40, actor = bob),
                Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 0), chestUpperSlot, torch, null, 2, T0 + 40, actor = bob),
            )
        )
        log.drain()

        val rows = (
            aliceRows(torch) +
                log.holderEntries(PlayerInv(bob, 0), 0, Long.MAX_VALUE) +
                log.holderEntries(chest, 0, Long.MAX_VALUE)
            ).filter { it.timestamp == T0 + 40 }
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.ordinal }.sorted())

        for (posting in rows) {
            assertEquals(rows.toSet(), log.transactionEntries(posting).toSet(), "unreachable from $posting")
        }
        assertEquals(setOf(bob), rows.map { it.actor }.toSet())
        assertEquals(emptyList<String>(), log.sweep(100).gaps)

        val intoVoid = log.holderEntries(chest, 0, Long.MAX_VALUE).single { it.counterparty === Void }
        assertEquals(listOf(intoVoid), log.transactionEntries(intoVoid))
    }

    // The sweep resumes by seeking to the key it stopped at, and under a prefix extractor a seek is
    // answered per prefix: a memtable holding none of the prefix sought is skipped whole. So a row
    // written after the pause, under a prefix of its own and not yet flushed, is exactly what a walk
    // in prefix mode stops being able to see — and the sweep going quiet is the one failure it must
    // not have. Asking for total order is what keeps it honest.
    @Test
    fun `a resumed sweep sees a row written since the pause and never flushed`() {
        val paused = log.sweep(2)
        assertFalse(paused.reachedEnd)

        // Everything above is now on disk and the memtable below is empty.
        reopen()

        // Neither end shares a prefix with the row the walk stopped at, so nothing puts that prefix
        // into the fresh memtable alongside this row.
        val position = WorldBlock(world, 900, 40, 900)
        log.submit(Transfer(Cause.BLOCK_DROP, position, Void, cobblestone, null, 1, T0 + 90, actor = alice))
        log.drain()

        assertEquals(6, sweptInSteps(100), "the rest of the seeded rows plus the one written since the pause")
        assertEquals(1, log.holderEntries(position, 0, Long.MAX_VALUE).size)
    }

    private fun sweptInSteps(step: Int): Int {
        var seen = 0
        do {
            val pass = log.sweep(step)
            seen += pass.checked
        } while (!pass.reachedEnd)
        return seen
    }

    // Versions 5 and 6 only add families, so a version 4 or 5 ledger is opened, given them and stamped
    // anew, with every row it had still there.
    @Test
    fun `a version 4 or 5 ledger is widened in place`(@TempDir older: Path) {
        var current = 0L
        reopen { current = storedSchemaOf(dir) }
        val widensFrom = mapOf(
            4L to everyColumnFamily - "compensated" - "confiscations",
            5L to everyColumnFamily - "confiscations",
        )
        for ((version, earlier) in widensFrom) {
            val base = older.resolve("v$version")
            RocksItemLog(base).use { written ->
                written.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, cobblestone, null, 7, T0))
                written.drain()
            }
            rawEdit(base) { raw, family ->
                for (added in everyColumnFamily - earlier.toSet()) raw.dropColumnFamily(family(added))
            }
            stampSchema(base, version)
            assertEquals(earlier.sorted(), familiesOf(base).sorted(), "version $version as it was left")

            RocksItemLog(base).use { widened ->
                val old = widened.holderEntries(chest, 0, Long.MAX_VALUE).single()
                assertEquals(7, old.qty, "from version $version")
                assertArrayEquals(cobblestone, widened.forms.valueOf(old.itemFormId), "from version $version")
                assertEquals(0, widened.registries.lookupUuid(RegistryNamespace.PLAYER, alice), "from version $version")

                widened.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 1, T0 + 1))
                widened.drain()
                val rows = widened.holderEntries(chest, 0, Long.MAX_VALUE)
                assertEquals(listOf(7, 1), rows.map { it.qty }, "from version $version")
                assertEquals(listOf(cobblestone, torch).map(::FormKey), rows.map { FormKey(widened.forms.valueOf(it.itemFormId)!!) })
            }
            assertEquals(everyColumnFamily.sorted(), familiesOf(base).sorted(), "from version $version")
            assertEquals(current, storedSchemaOf(base), "from version $version")
        }
    }

    // Given back once, a posting must not be given back again; but a rollback that is itself rolled
    // back owes it anew, and the undo of that owes nothing again.
    @Test
    fun `a posting given back stays given back until the giving back is given back`() {
        val theft = log.holderEntries(chest, 0, Long.MAX_VALUE).single { it.qty == -5 }
        assertEquals(emptySet<PostingRef>(), log.compensated(listOf(theft.ref)).keys)

        log.submit(Transfer(Cause.ROLLBACK, Void, chestUpperSlot, cobblestone, null, 5, T0 + 100, reverts = listOf(theft.ref)))
        log.drain()
        val givenBack = log.holderEntries(chest, T0 + 100, T0 + 100).single()
        assertEquals(mapOf(theft.ref to givenBack.ref.txId), log.compensated(listOf(theft.ref)), "named by the transaction that gave it back")

        log.submit(Transfer(Cause.ROLLBACK, chestUpperSlot, Void, cobblestone, null, 5, T0 + 200, reverts = listOf(givenBack.ref)))
        log.drain()
        assertEquals(emptySet<PostingRef>(), log.compensated(listOf(theft.ref)).keys)
        assertEquals(setOf(givenBack.ref), log.compensated(listOf(givenBack.ref)).keys)

        val undone = log.holderEntries(chest, T0 + 200, T0 + 200).single()
        log.submit(Transfer(Cause.ROLLBACK, Void, chestUpperSlot, cobblestone, null, 5, T0 + 300, reverts = listOf(undone.ref)))
        log.drain()
        assertEquals(setOf(theft.ref), log.compensated(listOf(theft.ref)).keys)
    }

    // A rollback over many positions gives them all back in one transaction, and undoing it at any one
    // of them undoes the whole of it.
    @Test
    fun `a posting is owed again when any posting of the transaction that gave it back is given back`() {
        val theft = log.holderEntries(chest, 0, Long.MAX_VALUE).single { it.qty == -5 }
        val deposit = log.holderEntries(chest, 0, Long.MAX_VALUE).single { it.itemFormId == log.forms.lookup(pickaxe) }
        log.submit(
            listOf(
                Transfer(Cause.ROLLBACK, chest, Void, pickaxe, 7, 1, T0 + 100, reverts = listOf(deposit.ref)),
                Transfer(Cause.ROLLBACK, Void, chestUpperSlot, cobblestone, null, 5, T0 + 100, reverts = listOf(theft.ref)),
            )
        )
        log.drain()
        val givenBack = log.holderEntries(chest, T0 + 100, T0 + 100).single { it.qty == 5 }
        assertEquals(1, givenBack.ordinal)
        assertEquals(setOf(theft.ref, deposit.ref), log.compensated(listOf(theft.ref, deposit.ref)).keys)

        log.submit(Transfer(Cause.ROLLBACK, chestUpperSlot, Void, cobblestone, null, 5, T0 + 200, reverts = listOf(givenBack.ref)))
        log.drain()
        assertEquals(emptySet<PostingRef>(), log.compensated(listOf(theft.ref, deposit.ref)).keys)
    }

    // Past the depth the chain is followed to, the posting counts as given back whatever the parity
    // below would say: the other answer gives the items back once more, out of nothing.
    @Test
    fun `a posting under a chain of undos deeper than is followed counts as given back`() {
        val theft = log.holderEntries(chest, 0, Long.MAX_VALUE).single { it.qty == -5 }
        var last = theft.ref
        for (i in 1..MAX_UNDO_DEPTH + 2) {
            val into = i % 2 == 1
            log.submit(
                Transfer(
                    Cause.ROLLBACK, if (into) Void else chestUpperSlot, if (into) chestUpperSlot else Void,
                    cobblestone, null, 5, T0 + 100L * i, reverts = listOf(last),
                )
            )
            log.drain()
            last = log.holderEntries(chest, T0 + 100L * i, T0 + 100L * i).single().ref
        }
        assertEquals(setOf(theft.ref), log.compensated(listOf(theft.ref)).keys, "an even number of undos, read as given back")
    }

    @Test
    fun `items owed to a rollback are kept per player until forgiven`() {
        log.owe(alice, 7, 5, bob)
        log.owe(alice, 8, 1, null)
        log.owe(bob, 7, 2, null)

        val owed = log.owedBy(alice)
        assertEquals(listOf(7L to 5, 8L to 1), owed.map { it.formId to it.qty })
        assertEquals(listOf(bob, null), owed.map { it.actor })
        log.forgive(owed.take(1))
        assertEquals(listOf(8L), log.owedBy(alice).map { it.formId })
        assertEquals(listOf(2), log.owedBy(bob).map { it.qty })

        // A player who is offline when the rollback runs pays at a join that can come after a restart.
        reopen()
        assertEquals(listOf(8L to 1), log.owedBy(alice).map { it.formId to it.qty })
        assertEquals(listOf(2), log.owedBy(bob).map { it.qty })
    }

    // A rollback goes ahead on what a region read hands it, so a row the read could not decode has to
    // be counted where the rollback can see it.
    @Test
    fun `a region read counts the rows it could not decode`() {
        val orphan = EntryCodec.key(chest, T0 + 40, 99L, 0, log.registries)
        reopen { rawPut(dir, "entries", orphan, byteArrayOf(0x07)) }

        val page = log.regionPage(world, 96, -208, 112, -192, 0, Long.MAX_VALUE, limit = 100)
        assertEquals(1, page.unreadable)
        assertEquals(4, page.entries.size)
    }

    // A refusal that has already widened the database is not a refusal: the build that wrote it can
    // no longer open it either, so one failed start of a newer jar would leave the ledger readable by
    // nothing at all.
    @Test
    fun `a database refused for its schema is left as it was found`(@TempDir older: Path) {
        val earlier = everyColumnFamily - "block_payloads"
        stampSchema(older, 2L, earlier)

        val refused = assertThrows(IllegalArgumentException::class.java) { RocksItemLog(older) }

        assertTrue(refused.message.orEmpty().contains("schema"), "the refusal has to name the reason: $refused")
        assertEquals(earlier.sorted(), familiesOf(older).sorted())
        assertEquals(2L, storedSchemaOf(older))
    }

    private val everyColumnFamily = listOf(
        "default", "entries", "item_forms", "registry", "meta", "nested_owners", "tx", "placed_forms",
        "block_payloads", "compensated", "confiscations",
    )

    // The stamp is the only thing that keeps an older build from reading a ledger whose families it
    // does not know. This is only a reminder: when a family is added or dropped and this fails, the fix
    // is a new version number, not just an edit to the list.
    @Test
    fun `a fresh ledger is stamped with the version that matches its families`() {
        reopen {
            assertEquals(6L, storedSchemaOf(dir))
            assertEquals(everyColumnFamily.sorted(), familiesOf(dir).sorted())
        }
    }

    private val aliceHex = "000000000000400080000000000000a1"
    private val bobHex = "000000000000400080000000000000b0"
    private val worldHex = "00000000000040008000000000000001"

    // Every expected row below is written out by hand: a reopen reads with the same code that wrote,
    // so it cannot notice a layout change made to both sides.
    @Test
    fun `registry rows and their counters keep their stored layout`() {
        log.registries.id(RegistryNamespace.PLAYER, bob)
        log.registries.idForKey(RegistryNamespace.BLOCK_STATE, "minecraft:air")
        log.registries.batched {
            for (i in 0L until 128L) log.registries.id(RegistryNamespace.PLAYER, UUID(0, i))
        }
        var registry = emptyMap<String, String>()
        var meta = emptyMap<String, String>()
        reopen {
            registry = rawRowsOf(dir, "registry")
            meta = rawRowsOf(dir, "meta")
        }

        assertEquals("00", registry["06$aliceHex"])
        assertEquals("01", registry["06$bobHex"])
        assertEquals("8101", registry["06" + "0000000000000000000000000000007f"], "id 129 as a varint")
        assertEquals("00", registry["05$worldHex"])
        assertEquals("00", registry["07" + "minecraft:air".toByteArray().hex()])
        assertEquals(130 + 1 + 1, registry.size)

        assertEquals("0000000000000082", meta["reg:6".toByteArray().hex()])
        assertEquals("0000000000000001", meta["reg:5".toByteArray().hex()])
        assertEquals("0000000000000001", meta["reg:7".toByteArray().hex()])
    }

    @Test
    fun `interned forms and payloads keep their stored layout`() {
        assertEquals(0L, log.payloads.idOf(byteArrayOf(0x0A, 0x0B)))
        assertEquals(1L, log.payloads.idOf(byteArrayOf(0x0C)))
        val stored = HashMap<String, Map<String, String>>()
        reopen { for (family in listOf("item_forms", "block_payloads", "meta")) stored[family] = rawRowsOf(dir, family) }

        assertEquals(mapOf("0000000000000000" to "010203", "0000000000000001" to "0405"), stored["item_forms"])
        assertEquals(mapOf("0000000000000000" to "0a0b", "0000000000000001" to "0c"), stored["block_payloads"])
        assertEquals("0000000000000002", stored.getValue("meta")["item_form_id".toByteArray().hex()])
        assertEquals("0000000000000002", stored.getValue("meta")["block_payload_id".toByteArray().hex()])
        assertEquals(0L, log.payloads.lookup(byteArrayOf(0x0A, 0x0B)), "found by its bytes after a reopen")
    }

    @Test
    fun `postings given back, debts and notes on a position keep their stored layout`() {
        val theft = log.holderEntries(chest, 0, Long.MAX_VALUE).single { it.qty == -5 }
        assertEquals(PostingRef(1, 0), theft.ref)
        log.submit(Transfer(Cause.ROLLBACK, Void, chestUpperSlot, cobblestone, null, 5, T0 + 100, reverts = listOf(theft.ref)))
        log.owe(alice, 300, 5, bob)
        log.owe(alice, 7, 1, null)
        log.owe(alice, 7, 1, null, since = 1000)
        log.setFormAt(WorldBlock(world, 100, 64, -200), pickaxe)
        log.setOwnerAt(world, 100, 64, -200, bob)
        log.drain()
        val stored = HashMap<String, Map<String, String>>()
        reopen {
            for (family in listOf("compensated", "confiscations", "placed_forms", "nested_owners")) {
                stored[family] = rawRowsOf(dir, family)
            }
        }

        // The posting's transaction and ordinal, then the transaction that gave it back.
        assertEquals(mapOf("0000000000000001" + "00" to "0000000000000004"), stored["compensated"])

        // The player, then a sequence that keeps a player's debts in the order they were made.
        val debts = stored.getValue("confiscations")
        assertEquals(3, debts.size)
        for (key in debts.keys) {
            assertEquals(48, key.length, key)
            assertTrue(key.startsWith(aliceHex), key)
        }
        val (first, second) = debts.keys.map { it.substring(aliceHex.length).toULong(16) }
        assertEquals(first + 1u, second, "the sequence is a big-endian long")
        // When the window opened trails the value, so a debt kept without it still reads.
        assertEquals(listOf("ac0205" + "01" + bobHex, "0701" + "00", "0701" + "00" + "e807"), debts.values.toList())
        assertEquals(listOf(0L, 0L, 1000L), log.owedBy(alice).map { it.since })

        val position = worldHex + "9555555555" + "2d488040"
        assertEquals(mapOf(position to "0405"), stored["placed_forms"])
        assertEquals(mapOf(position to bobHex), stored["nested_owners"])
    }

    // A transaction of more than one movement is reassembled through its tx row: the count, then each
    // posting's entry key behind its length.
    @Test
    fun `a transaction's index row and the transaction counter keep their stored layout`() {
        val txIdKey = "tx_id".toByteArray().hex()
        var entriesBefore = emptySet<String>()
        var nextBefore = 0L
        reopen {
            entriesBefore = rawRowsOf(dir, "entries").keys
            nextBefore = rawRowsOf(dir, "meta").getValue(txIdKey).toLong(16)
        }
        log.submit(
            listOf(
                Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 1, T0 + 50),
                Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 3), chestUpperSlot, torch, null, 2, T0 + 50),
            )
        )
        log.drain()
        val stored = HashMap<String, Map<String, String>>()
        reopen { for (family in listOf("entries", "tx", "meta")) stored[family] = rawRowsOf(dir, family) }

        val postings = stored.getValue("entries").keys - entriesBefore
        assertEquals(4, postings.size)
        val (txKey, packed) = stored.getValue("tx").entries.single()
        assertEquals("%016x".format(nextBefore), txKey, "the id the counter stood at")
        assertEquals("04", packed.take(2))
        val unpacked = ArrayList<String>()
        var at = 2
        while (at < packed.length) {
            val length = packed.substring(at, at + 2).toInt(16)
            unpacked += packed.substring(at + 2, at + 2 + 2 * length)
            at += 2 + 2 * length
        }
        assertEquals(postings.sorted(), unpacked.sorted())
        assertEquals("%016x".format(nextBefore + 1), stored.getValue("meta")[txIdKey], "the next id to hand out")
    }

    // A region thread sets a note and reads it back within one event, long before the writer has put
    // it in the database; a read in between has to see it, and a clear the same.
    @Test
    fun `a note is read back at once and written by the writer`() {
        val world = UUID.randomUUID()
        val here = WorldBlock(world, 1, 2, 3)
        val there = WorldBlock(world, 4, 5, 6)
        log.setFormAt(there, pickaxe)
        log.drain()

        log.setFormAt(here, pickaxe)
        assertArrayEquals(pickaxe, log.formAt(here))
        assertEquals(setOf(here, there), log.formsAt(listOf(here, there)).keys)
        log.clearFormsAt(listOf(there))
        assertEquals(setOf(here), log.formsAt(listOf(here, there)).keys)

        log.drain()
        assertArrayEquals(pickaxe, log.formAt(here))
        assertNull(log.formAt(there))
    }

    // A shulker loses its mark when it is broken, so the name has to outlive the box standing there,
    // restarts included.
    @Test
    fun `the name of a placed container outlives the session`() {
        val owner = UUID.randomUUID()
        assertNoteOutlivesSession(
            owner,
            get = { log.ownerAt(it.world, it.x, it.y, it.z) },
            set = { log.setOwnerAt(it.world, it.x, it.y, it.z, owner) },
            clear = { log.clearOwnerAt(it.world, it.x, it.y, it.z) },
        )
    }

    // A block answers with the bare item whatever was put down, so the position has to give back the
    // form it took over rather than one built from the block, restarts included.
    @Test
    fun `the form a position took over outlives the session`() {
        assertNoteOutlivesSession(
            pickaxe.toList(),
            get = { log.formAt(it)?.toList() },
            set = { log.setFormAt(it, pickaxe) },
            clear = { log.clearFormAt(it) },
        )
    }

    private fun <T : Any> assertNoteOutlivesSession(
        value: T,
        get: (WorldBlock) -> T?,
        set: (WorldBlock) -> Unit,
        clear: (WorldBlock) -> Unit,
    ) {
        val here = WorldBlock(UUID.randomUUID(), 1, 2, 3)
        assertNull(get(here))
        set(here)
        assertEquals(value, get(here))
        assertNull(get(here.copy(z = 4)))
        assertNull(get(here.copy(world = UUID.randomUUID())))

        reopen()
        assertEquals(value, get(here))

        clear(here)
        assertNull(get(here))
    }

    // An explosion asks about a few thousand positions in one event and clears them together.
    @Test
    fun `a set of written positions answers only those holding a form and clears only those named`() {
        val here = WorldBlock(world, 1, 2, 3)
        val next = WorldBlock(world, 2, 2, 3)
        val bare = WorldBlock(world, 3, 2, 3)
        val also = WorldBlock(world, 4, 2, 3)
        val elsewhere = WorldBlock(UUID.randomUUID(), 1, 2, 3)
        log.setFormAt(here, pickaxe)
        log.setFormAt(next, torch)
        log.setFormAt(also, cobblestone)
        log.drain()

        val forms = log.formsAt(listOf(here, next, bare, elsewhere))

        assertEquals(setOf(here, next), forms.keys)
        assertArrayEquals(pickaxe, forms[here])
        assertArrayEquals(torch, forms[next])
        assertEquals(emptyMap<WorldBlock, ByteArray>(), log.formsAt(emptyList()))

        log.clearFormsAt(listOf(here, bare, also))

        assertNull(log.formAt(here))
        assertNull(log.formAt(also))
        assertArrayEquals(torch, log.formAt(next))
        assertEquals(setOf(next), log.formsAt(listOf(here, next, bare, also)).keys)
    }

    // Read straight after being set, so the answers come from the notes not yet written.
    @Test
    fun `the form of a position and the name of its container are kept apart`() {
        val world = UUID.randomUUID()
        val owner = UUID.randomUUID()
        log.setOwnerAt(world, 1, 2, 3, owner)
        assertNull(log.formAt(WorldBlock(world, 1, 2, 3)))

        log.setFormAt(WorldBlock(world, 1, 2, 3), pickaxe)
        assertEquals(owner, log.ownerAt(world, 1, 2, 3))

        log.clearFormAt(WorldBlock(world, 1, 2, 3))
        assertEquals(owner, log.ownerAt(world, 1, 2, 3))
        assertNull(log.formAt(WorldBlock(world, 1, 2, 3)))
    }

    @Test
    fun `holder entries respect the time window and the limit`() {
        assertEquals(listOf(T0 + 10), log.holderEntries(aliceInv, T0 + 10, T0 + 10).map { it.timestamp })
        assertEquals(listOf(T0 + 10, T0 + 20), log.holderEntries(aliceInv, T0 + 10, Long.MAX_VALUE).map { it.timestamp })
        assertEquals(listOf(T0), log.holderEntries(aliceInv, 0, Long.MAX_VALUE, limit = 1).map { it.timestamp }, "the oldest first")
        assertEquals(-1, log.holderPage(aliceInv, 0, Long.MAX_VALUE, reverse = true, limit = 1).entries.single().qty)
        assertTrue(log.holderEntries(PlayerInv(UUID.randomUUID(), 0), 0, Long.MAX_VALUE).isEmpty())
    }

    // The old answer to this was the size of the list, which is wrong both ways: a range that ends
    // exactly on the limit looks cut off, and rows the time window dropped look like room to spare.
    @Test
    fun `a page says whether it ran out of rows or out of room`() {
        assertTrue(log.holderPage(aliceInv, 0, Long.MAX_VALUE, limit = 3).complete)
        assertFalse(log.holderPage(aliceInv, 0, Long.MAX_VALUE, limit = 2).complete)

        val window = log.holderPage(aliceInv, 0, T0, limit = 1)
        assertEquals(1, window.entries.size)
        assertTrue(window.complete)

        assertFalse(log.regionPage(world, 96, -208, 112, -192, 0, Long.MAX_VALUE, limit = 2).complete)
        assertTrue(log.regionPage(world, 96, -208, 112, -192, 0, Long.MAX_VALUE, limit = 4).complete)
        assertTrue(log.regionPage(UUID.randomUUID(), 96, -208, 112, -192, 0, Long.MAX_VALUE).complete)
    }

    // Unknown version, or torn after the header.
    @Test
    fun `rows this build cannot read are counted apart from the ones it checked`() {
        val unreadable = listOf(
            byteArrayOf(0x07),
            byteArrayOf(EntryCodec.header(Kind.TRANSFER, Confidence.FACT).toByte()),
        )
        val readable = log.sweep(1000).checked
        val orphan = EntryCodec.key(chest, T0 + 40, 99L, 0, log.registries)
        for (value in unreadable) {
            reopen { rawPut(dir, "entries", orphan, value) }

            val report = log.sweep(1000)
            assertEquals(readable, report.checked)
            assertEquals(1, report.unreadable)
            assertTrue(report.reachedEnd)
        }
    }

    @Test
    fun `a region scan answers with every block holder in the box and nothing else`() {
        val position = WorldBlock(world, 101, 65, -200)
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, Container(world, 200, 64, -200, 0), cobblestone, null, 7, T0 + 15))
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, farSideOfChunk, cobblestone, null, 2, T0 + 50))
        log.submit(Transfer(Cause.BLOCK_PLACE, aliceInv, position, cobblestone, null, 1, T0 + 70))
        log.drain()

        val inBox = log.regionPage(world, 96, -208, 112, -192, 0, Long.MAX_VALUE).entries
        assertEquals(listOf(32, -5, 1, -3, 2, 1), inBox.map { it.qty })
        assertEquals(listOf(T0, T0 + 10, T0 + 20, T0 + 30, T0 + 50, T0 + 70), inBox.map { it.timestamp })
        assertEquals(chestUpperSlot, inBox[1].holder)
        assertEquals(farSideOfChunk, inBox[4].holder)
        assertEquals(position, inBox[5].holder)

        // Read backwards it is the same rows in the other order, both holder kinds included: the two
        // kinds live under prefixes of their own and a reversed walk seeks into each one separately.
        assertEquals(inBox.reversed(), log.regionPage(world, 96, -208, 112, -192, 0, Long.MAX_VALUE, reverse = true).entries)

        val window = log.regionPage(world, 96, -208, 112, -192, T0 + 10, T0 + 30).entries
        assertEquals(listOf(T0 + 10, T0 + 20, T0 + 30), window.map { it.timestamp })
        assertTrue(log.regionPage(world, 96, -208, 112, -192, T0 + 80, Long.MAX_VALUE).entries.isEmpty())
        assertTrue(log.regionPage(world, 5000, 5000, 5016, 5016, 0, Long.MAX_VALUE).entries.isEmpty())
        assertTrue(log.regionPage(UUID.randomUUID(), 96, -208, 112, -192, 0, Long.MAX_VALUE).entries.isEmpty())
    }

    @Test
    fun `an oversized region is refused instead of scanned`() {
        assertThrows(IllegalArgumentException::class.java) {
            log.regionPage(world, 0, 0, 100_000, 0, 0, Long.MAX_VALUE).entries
        }
    }

    // A prefix extractor changes what an iterator is allowed to return, and every read below answered
    // with nothing at all the first time it met one. A scan may only be written the way these expect:
    // bounded at both ends, seeking backwards to a key that still belongs to the prefix, and asking
    // for total order wherever the prefix is too short for the extractor. This is the standing proof.
    @Test
    fun `every scan still answers with the ledger under a prefix extractor`() {
        val position = WorldBlock(world, 100, 70, -200)
        for (i in 0 until 20) {
            log.submit(Transfer(Cause.BLOCK_PLACE, aliceInv, position, cobblestone, null, 1, T0 + 100 + i))
        }
        log.drain()

        // Nothing has asked for a flush, so every one of these rows is still in the memtable.
        assertEveryScanAnswers(position)

        reopen()
        assertTrue(sstFiles() > 0, "nothing was flushed, so the table's own prefix filter never had a turn")
        assertEveryScanAnswers(position)
    }

    private fun sstFiles(): Int = dir.toFile().listFiles().orEmpty().count { it.name.endsWith(".sst") }

    private fun assertEveryScanAnswers(position: WorldBlock) {
        // A position: its prefix is exactly the width the extractor is sized for, which is the case
        // where seeking to the key just past the prefix asks the filter about the wrong prefix.
        assertEquals(20, log.holderEntries(position, 0, Long.MAX_VALUE, limit = 1000).size)
        val newestFirst = log.holderPage(position, 0, Long.MAX_VALUE, reverse = true, limit = 1000).entries
        assertEquals(20, newestFirst.size)
        assertEquals(T0 + 119, newestFirst.first().timestamp)
        assertEquals(
            listOf(T0 + 119, T0 + 118),
            log.regionPage(world, 96, -208, 112, -192, 0, Long.MAX_VALUE, reverse = true, limit = 2).entries
                .map { it.timestamp },
        )

        // A player: its prefix is shorter than the extractor, so its rows are spread over many
        // extractor prefixes and no single one of them can stand for the lot.
        val fromAlice = log.holderEntries(aliceInv, 0, Long.MAX_VALUE, limit = 1000)
        assertEquals(3 + 20, fromAlice.size, "the seeded rows plus the placements")
        assertEquals(fromAlice.reversed(), log.holderPage(aliceInv, 0, Long.MAX_VALUE, reverse = true, limit = 1000).entries)
        assertEquals(
            mapOf(aliceInv to mapOf(FormKey(cobblestone) to -32 + 5 - 20, FormKey(pickaxe) to -1)),
            log.slotBalances(listOf(aliceInv), Long.MAX_VALUE),
        )

        // The whole ledger, walked from the start and then again a few rows at a time from a cursor.
        // Counted against the prefixes rather than against another walk, or a walk that stopped early
        // would agree with itself: these three prefixes hold every row the log has been given.
        val everyRow = fromAlice.size + newestFirst.size +
            log.holderEntries(chest, 0, Long.MAX_VALUE, limit = 1000).size
        val whole = log.sweep(1000)
        assertTrue(whole.reachedEnd)
        assertEquals(everyRow, whole.checked, "the walk of the whole ledger did not see every row")
        assertEquals(emptyList<String>(), whole.gaps)
        assertEquals(whole.checked, sweptInSteps(3), "a resumed walk lost rows the one from the start could see")
    }

    @Test
    fun `a reopened log keeps entries forms and counters`() {
        val before = log.holderEntries(chest, 0, Long.MAX_VALUE)
        reopen()

        assertEquals(before, log.holderEntries(chest, 0, Long.MAX_VALUE))
        assertArrayEquals(cobblestone, log.form(before[0].itemFormId))

        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, cobblestone, null, 8, T0 + 40))
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 1, T0 + 50))
        log.drain()
        val after = log.holderEntries(chest, 0, Long.MAX_VALUE)
        assertEquals(before.size + 2, after.size)
        assertEquals(after.size, after.map { it.txId }.distinct().size)
        assertEquals(before[0].itemFormId, after[after.size - 2].itemFormId)

        val freshForm = after.last().itemFormId
        assertTrue(before.none { it.itemFormId == freshForm }, "a form first seen after the reopen took an old id")
        assertArrayEquals(torch, log.form(freshForm))
        assertArrayEquals(pickaxe, log.form(before[2].itemFormId))
        assertNull(log.form(9999))
    }

    // One movement the capture cannot encode is a bug in the capture. Letting it stop the writer would
    // turn that single bad row into a session that records nothing at all from then on, and a ledger
    // kept in order to be trusted must fail on the row rather than on everything after it.
    @Test
    fun `a movement that cannot be written costs only itself`() {
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 1, T0 + 40))
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, unreachable, torch, null, 2, T0 + 41))
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 3, T0 + 42))
        log.drain()

        val landed = log.holderEntries(chest, 0, Long.MAX_VALUE, limit = 1000)
            .filter { it.itemFormId == log.forms.lookup(torch) }
        assertEquals(listOf(1, 3), landed.map { it.qty })

        // Still alive afterwards: the next movement is written like nothing happened.
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 5, T0 + 43))
        log.drain()
        assertEquals(
            listOf(1, 3, 5),
            log.holderEntries(chest, 0, Long.MAX_VALUE, limit = 1000)
                .filter { it.itemFormId == log.forms.lookup(torch) }.map { it.qty },
        )
        assertEquals(emptyList<String>(), log.sweep(1000).gaps)
    }

    // Of the seeded rows, the two movements before the cutoff are four postings: alice's slot 9 gave 32
    // and took back 5, slot 0 of the chest took 32 and slot 5 gave 5. A damaged copy and a second form
    // moved through the same slots must each keep an opening of their own, or the purge would merge
    // items that are not interchangeable.
    @Test
    fun `a purge leaves each holder its balance at the cutoff`() {
        val cutoff = T0 + 15
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, cobblestone, 3, 2, T0 + 11))
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 4, T0 + 12))
        log.drain()
        fun rows() = listOf(chest, aliceInv).flatMap { log.holderEntries(it, 0, Long.MAX_VALUE, limit = 1000) }
        fun balance() = rows().groupBy { Triple(it.holder, it.itemFormId, it.damage) }.mapValues { (_, rows) -> rows.sumOf { it.qty } }
        val before = balance()

        assertEquals(8 to 7, log.purgeBefore(cutoff, dryRun = true))
        assertEquals(before, balance())
        assertEquals(8 to 7, log.purgeBefore(cutoff, dryRun = false))

        assertEquals(before, balance())
        val left = rows()
        assertTrue(left.none { it.timestamp < cutoff }, "$left")
        val openings = left.filter { it.cause == Cause.PURGE_OPENING }
        assertEquals(
            setOf(
                listOf(aliceInv, cobblestone.toList(), null, -27),
                listOf(chest, cobblestone.toList(), null, 32),
                listOf(chestUpperSlot, cobblestone.toList(), null, -5),
                listOf(aliceInv, cobblestone.toList(), 3, -2),
                listOf(chest, cobblestone.toList(), 3, 2),
                listOf(aliceInv, torch.toList(), null, -4),
                listOf(chest, torch.toList(), null, 4),
            ),
            openings.map { listOf(it.holder, log.form(it.itemFormId)?.toList(), it.damage, it.qty) }.toSet(),
        )
        assertEquals(7, openings.size)
        assertTrue(openings.all { it.counterparty == Void && it.timestamp == cutoff }, "$openings")
    }

    @Test
    fun `a purge keeps a posting whose form it cannot read`() {
        val formless = ledgerEntry(chest, Void).copy(timestamp = T0 + 5, txId = 99L, itemFormId = 9999L)
        val key = EntryCodec.key(chest, formless.timestamp, formless.txId, 0, log.registries)
        val value = EntryCodec.value(formless, log.registries)
        reopen { rawPut(dir, "entries", key, value) }

        log.purgeBefore(T0 + 15, dryRun = false)

        assertEquals(listOf(T0 + 5), log.holderEntries(chest, 0, Long.MAX_VALUE).filter { it.itemFormId == 9999L }.map { it.timestamp })
    }

    // A form first named by a transaction that is then dropped was rolled back out of the batch with it,
    // so a later movement of the same item has to intern it anew rather than reuse a number with no row.
    @Test
    fun `a form first named by a dropped transaction is written when it comes again`() {
        val fresh = byteArrayOf(42, 43)
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, unreachable, fresh, null, 1, T0 + 40))
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, fresh, null, 1, T0 + 41))
        log.drain()
        reopen()

        val landed = log.holderEntries(chest, T0 + 41, T0 + 41).single()
        assertArrayEquals(fresh, log.form(landed.itemFormId))
    }

    @Test
    fun `a submit after close is refused rather than taken and never written`() {
        log.drain()
        log.close()

        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, cobblestone, null, 1, T0 + 50))
        assertEquals(0, log.backlog)
    }
}
