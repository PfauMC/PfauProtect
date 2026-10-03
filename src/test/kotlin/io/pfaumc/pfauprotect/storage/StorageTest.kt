package io.pfaumc.pfauprotect.storage
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.PostingRef
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.capture.block.cleared
import io.pfaumc.pfauprotect.capture.block.moved
import io.pfaumc.pfauprotect.command.namesUser
import io.pfaumc.pfauprotect.command.wholeTransactions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.DBOptions
import org.rocksdb.Options
import org.rocksdb.RocksDB
import java.nio.file.Path
import java.util.UUID

private const val T0 = 1_700_000_000_000L

class StorageTest {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val bob = UUID.fromString("00000000-0000-4000-8000-0000000000b0")
    private val chest = Container(world, 100, 64, -200, 0)
    private val chestUpperSlot = Container(world, 100, 64, -200, 5)
    private val aliceInv = PlayerInv(alice, 9)
    private val farSideOfChunk = Container(world, 104, 64, -200, 0)
    private val cobblestone = byteArrayOf(1, 2, 3)
    private val pickaxe = byteArrayOf(4, 5)
    private val torch = byteArrayOf(6, 7, 8, 9)

    private lateinit var dir: Path
    private lateinit var log: RocksItemLog

    @BeforeEach
    fun openAndSeed(@TempDir tempDir: Path) {
        dir = tempDir
        log = RocksItemLog(dir)
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, cobblestone, null, 32, T0))
        log.submit(Transfer(Cause.CONTAINER_REMOVE, chestUpperSlot, aliceInv, cobblestone, null, 5, T0 + 10))
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, pickaxe, 7, 1, T0 + 20))
        log.submit(
            Transfer(
                cause = Cause.ITEM_DESPAWN,
                from = chest,
                to = Void,
                form = cobblestone,
                damage = null,
                qty = 3,
                timestamp = T0 + 30,
                confidence = Confidence.INFERRED,
            )
        )
        log.drain()
    }

    @AfterEach
    fun closeLog() {
        log.close()
    }

    // A holder prefix covers every slot its owner has, so this is every row of one form alice holds.
    private fun aliceRows(form: ByteArray): List<LedgerEntry> =
        log.holderEntries(PlayerInv(alice, 0), 0, Long.MAX_VALUE, limit = 1000)
            .filter { it.itemFormId == log.formId(form) }

    @Test
    fun `a sound ledger sweeps without a word`() {
        val report = log.sweep(100)
        assertTrue(report.reachedEnd)
        assertEquals(emptyList<String>(), report.gaps)
        assertTrue(report.checked > 0)
    }

    // The slot lives in the value and not in the key, so both ends of a movement inside one holder
    // would address the same key were the posting ordinal not there to part them. Neither of them
    // may be given a timestamp of its own for it: that would be a claim about when it happened.
    @Test
    fun `a movement whose ends share a holder keeps both rows`() {
        log.submit(Transfer(Cause.CONTAINER_ADD, chest, chest, torch, null, 4, T0 + 40))
        log.drain()

        val moved = log.holderEntries(chest, 0, Long.MAX_VALUE).filter { it.itemFormId == log.formId(torch) }
        assertEquals(listOf(-4, 4), moved.map { it.qty })
        assertEquals(listOf(T0 + 40, T0 + 40), moved.map { it.timestamp })
        assertEquals(listOf(0, 1), moved.map { it.ordinal })
        assertEquals(emptyList<String>(), log.sweep(100).gaps)
    }

    // A stack moved between two slots of one inventory is the commonest movement there is, and both
    // of its ends address the same player.
    @Test
    fun `a move between two slots of one player leaves two rows that cancel`() {
        log.submit(Transfer(Cause.QUICK_MOVE, PlayerInv(alice, 0), PlayerInv(alice, 9), torch, null, 32, T0 + 40))
        log.drain()

        val moved = aliceRows(torch)
        assertEquals(listOf(-32, 32), moved.map { it.qty })
        assertEquals(listOf(PlayerInv(alice, 0), PlayerInv(alice, 9)), moved.map { it.holder })
        assertEquals(listOf(PlayerInv(alice, 9), PlayerInv(alice, 0)), moved.map { it.counterparty })
        assertEquals(listOf(T0 + 40, T0 + 40), moved.map { it.timestamp })
        assertEquals(1, moved.map { it.txId }.distinct().size)
        assertEquals(0, moved.sumOf { it.qty })
    }

    @Test
    fun `shuffling a stack between slots leaves the balance alone`() {
        val before = log.formBalance(alice)
        log.submit(
            Transfer(Cause.QUICK_MOVE, PlayerInv(alice, 0), PlayerInv(alice, 9), cobblestone, null, 32, T0 + 40)
        )
        log.drain()
        assertEquals(before, log.formBalance(alice))
    }

    @Test
    fun `a move inside one inventory is whole from either half`() {
        log.submit(Transfer(Cause.QUICK_MOVE, PlayerInv(alice, 0), PlayerInv(alice, 9), torch, null, 32, T0 + 40))
        log.drain()

        val (debit, credit) = aliceRows(torch)
        val whole = log.transactionEntries(debit)
        assertEquals(2, whole.size)
        assertEquals(0, whole.sumOf { it.qty })
        assertEquals(setOf(PlayerInv(alice, 0), PlayerInv(alice, 9)), whole.map { it.holder }.toSet())
        assertEquals(whole.toSet(), log.transactionEntries(credit).toSet())
        assertEquals(emptyList<String>(), log.sweep(100).gaps)
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
        assertEquals(listOf(log.formId(pickaxe), log.formId(torch)), rows.map { it.itemFormId })
        assertEquals(1, rows.map { it.txId }.distinct().size)

        for (half in rows) {
            assertEquals(rows.toSet(), log.transactionEntries(half).toSet(), "unreachable from $half")
        }
        assertEquals(emptyList<String>(), log.sweep(100).gaps)
    }

    // A craft names more ends than a mutation does: several ingredients leave and one result arrives,
    // every one of them facing the Void. Nothing pairs, so the index is the only way back — and the
    // whole point is that it answers the same from whichever end the question is asked.
    @Test
    fun `a craft reads the same from any of its three postings`() {
        val grid = { slot: Int -> EntitySlot(alice, slot) }
        log.submit(
            listOf(
                Transfer(Cause.CRAFT_CONSUME, grid(1), Void, cobblestone, null, 8, T0 + 50),
                Transfer(Cause.CRAFT_CONSUME, grid(2), Void, pickaxe, 7, 1, T0 + 50),
                Transfer(Cause.CRAFT_RESULT, Void, PlayerCursor(alice), torch, null, 1, T0 + 50),
            )
        )
        log.drain()

        // The slot never enters the key, so one prefix answers with every grid slot at once.
        val consumed = log.holderEntries(grid(0), 0, Long.MAX_VALUE)
        val produced = log.holderEntries(PlayerCursor(alice), 0, Long.MAX_VALUE)
        val whole = consumed + produced
        assertEquals(3, whole.size)
        assertEquals(listOf(-8, -1, 1), whole.map { it.qty })
        assertEquals(listOf(0, 1, 2), whole.map { it.ordinal })
        assertEquals(1, whole.map { it.txId }.distinct().size)

        for (posting in whole) {
            assertEquals(whole.toSet(), log.transactionEntries(posting).toSet(), "unreachable from $posting")
        }
        assertEquals(emptyList<String>(), log.sweep(100).gaps)
    }

    // Two holder types cannot share a key, so the two ends of this movement are told apart by more
    // than their ordinals and it is still an ordinary pair.
    @Test
    fun `a cursor to slot move of one player stays an ordinary pair`() {
        log.submit(Transfer(Cause.CURSOR_PLACE, PlayerCursor(alice), PlayerInv(alice, 3), torch, null, 4, T0 + 40))
        log.drain()

        val fromCursor = log.holderEntries(PlayerCursor(alice), 0, Long.MAX_VALUE).single()
        assertEquals(-4, fromCursor.qty)
        val pair = log.transactionEntries(fromCursor)
        assertEquals(listOf(PlayerCursor(alice), PlayerInv(alice, 3)), pair.map { it.holder })
        assertEquals(listOf(T0 + 40, T0 + 40), pair.map { it.timestamp })
        assertEquals(0, pair.sumOf { it.qty })
        assertEquals(emptyList<String>(), log.sweep(100).gaps)
    }

    // A block break submits several movements at once, and two of them landing in one container
    // collide exactly as the two ends of a single movement do.
    @Test
    fun `two movements of one transaction into the same container keep their own rows`() {
        log.submit(
            listOf(
                Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 1, T0 + 40),
                Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 0), chestUpperSlot, torch, null, 2, T0 + 40),
            )
        )
        log.drain()

        val landed = log.holderEntries(chest, 0, Long.MAX_VALUE).filter { it.itemFormId == log.formId(torch) }
        assertEquals(listOf(1, 2), landed.map { it.qty })
        assertEquals(listOf(chest, chestUpperSlot), landed.map { it.holder })

        val fromBob = log.holderEntries(PlayerInv(bob, 0), 0, Long.MAX_VALUE).single()
        val whole = log.transactionEntries(fromBob)
        assertEquals(0, whole.sumOf { it.qty })
        assertTrue(whole.any { it.holder == chestUpperSlot && it.qty == 2 }, "bob's half went to the wrong slot")
        assertEquals(emptyList<String>(), log.sweep(100).gaps)
    }

    // Past the first pair a posting sits at an ordinal that faces nothing, so a transaction asked
    // from one of its own halves must not come back smaller than it does from the others.
    @Test
    fun `a transaction of four postings reads the same from each of them`() {
        log.submit(
            listOf(
                Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 1, T0 + 40),
                Transfer(Cause.CONTAINER_ADD, PlayerInv(bob, 0), chestUpperSlot, torch, null, 2, T0 + 40),
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
        assertEquals(emptyList<String>(), log.sweep(100).gaps)
    }

    @Test
    fun `sweeping continues where the last pass stopped`() {
        val first = log.sweep(2)
        assertEquals(2, first.checked)
        assertFalse(first.reachedEnd)
        var passes = 0
        var seen = first.checked
        while (passes++ < 10) {
            val next = log.sweep(2)
            seen += next.checked
            if (next.reachedEnd) break
        }
        assertEquals(log.sweep(100).checked, seen)
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

        // Reopening flushes, so everything above is now on disk and the memtable below is empty.
        log.close()
        log = RocksItemLog(dir)

        // Neither end shares a prefix with the row the walk stopped at, so nothing puts that prefix
        // into the fresh memtable alongside this row.
        val position = WorldBlock(world, 900, 40, 900)
        log.submit(Transfer(Cause.BLOCK_DROP, position, Void, cobblestone, null, 1, T0 + 90, actor = alice))
        log.drain()

        var seen = 0
        do {
            val pass = log.sweep(100)
            seen += pass.checked
        } while (!pass.reachedEnd)
        assertEquals(6, seen, "the rest of the seeded rows plus the one written since the pause")
        assertEquals(1, log.holderEntries(position, 0, Long.MAX_VALUE).size)
    }

    // The record version in the value cannot speak for the key: rows of an older key layout carry a
    // record version this build accepts, so nothing in the value would stop it reading a world number
    // and a posting ordinal out of bytes that were never written. The layout version in `meta` is the
    // only guard, and it has to refuse rather than let the misreading start.
    @Test
    fun `a database of an older key layout refuses to open`() {
        log.close()
        stampSchemaVersion(1L)

        val refused = assertThrows(IllegalArgumentException::class.java) { RocksItemLog(dir) }
        assertTrue(refused.message.orEmpty().contains("schema"), "the refusal has to name the reason: $refused")

        stampSchemaVersion(6L)
        log = RocksItemLog(dir)
        assertEquals(4, log.holderEntries(chest, 0, Long.MAX_VALUE).size)
    }

    // Versions 5 and 6 only add families, so a version 4 ledger is opened, given them and stamped
    // anew, with every row it had still there.
    @Test
    fun `a version 4 ledger is widened in place`(@TempDir older: Path) {
        val earlier = everyColumnFamily - "compensated" - "confiscations"
        openWith(older, earlier) { raw, handles ->
            raw.put(handles[earlier.indexOf("meta")], "schema".toByteArray(), ByteWriter(8).longBE(4L).toByteArray())
        }

        RocksItemLog(older).use { widened ->
            widened.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, cobblestone, null, 1, T0))
            widened.drain()
        }
        val families = Options().use { RocksDB.listColumnFamilies(it, older.toAbsolutePath().toString()).map { String(it) } }
        assertTrue("compensated" in families && "confiscations" in families, "$families")
        RocksItemLog(older).use { assertEquals(1, it.holderEntries(chest, 0, Long.MAX_VALUE).size) }
    }

    // Given back once, a posting must not be given back again; but a rollback that is itself rolled
    // back owes it anew, and the undo of that owes nothing again.
    @Test
    fun `a posting given back stays given back until the giving back is given back`() {
        val theft = log.holderEntries(chest, 0, Long.MAX_VALUE).single { it.qty == -5 }
        assertEquals(emptySet<PostingRef>(), log.compensated(listOf(theft.ref)).keys)

        log.submit(Transfer(Cause.ROLLBACK, Void, chestUpperSlot, cobblestone, null, 5, T0 + 100, reverts = listOf(theft.ref)))
        log.drain()
        assertEquals(setOf(theft.ref), log.compensated(listOf(theft.ref)).keys)

        val givenBack = log.holderEntries(chest, T0 + 100, T0 + 100).single()
        log.submit(Transfer(Cause.ROLLBACK, chestUpperSlot, Void, cobblestone, null, 5, T0 + 200, reverts = listOf(givenBack.ref)))
        log.drain()
        assertEquals(emptySet<PostingRef>(), log.compensated(listOf(theft.ref)).keys)
        assertEquals(setOf(givenBack.ref), log.compensated(listOf(givenBack.ref)).keys)

        val undone = log.holderEntries(chest, T0 + 200, T0 + 200).single()
        log.submit(Transfer(Cause.ROLLBACK, Void, chestUpperSlot, cobblestone, null, 5, T0 + 300, reverts = listOf(undone.ref)))
        log.drain()
        assertEquals(setOf(theft.ref), log.compensated(listOf(theft.ref)).keys)
    }

    // Owed by a player who was offline, kept until it is taken, and only that player's.
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
    }

    // A rollback goes ahead on what a region read hands it, so a row the read could not decode has to
    // be counted where the rollback can see it.
    @Test
    fun `a region read counts the rows it could not decode`() {
        val orphan = EntryCodec.key(chest, T0 + 40, 99L, 0, log.registries)
        log.close()
        writeRawEntry(dir, orphan, byteArrayOf(0x07))
        log = RocksItemLog(dir)

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
        openWith(older, earlier) { raw, handles ->
            raw.put(handles[earlier.indexOf("meta")], "schema".toByteArray(), ByteWriter(8).longBE(2L).toByteArray())
        }

        assertThrows(IllegalArgumentException::class.java) { RocksItemLog(older) }

        val left = org.rocksdb.Options().use { probe ->
            org.rocksdb.RocksDB.listColumnFamilies(probe, older.toAbsolutePath().toString()).map { String(it) }
        }
        assertEquals(earlier.sorted(), left.sorted())
    }

    // Opening has to name every column family the log created, or RocksDB refuses the database.
    private val everyColumnFamily = listOf(
        "default", "entries", "item_forms", "registry", "meta", "nested_owners", "tx", "placed_forms",
        "block_payloads", "compensated", "confiscations",
    )

    private fun stampSchemaVersion(version: Long) {
        openWith(dir, everyColumnFamily) { raw, handles ->
            raw.put(
                handles[everyColumnFamily.indexOf("meta")],
                "schema".toByteArray(),
                ByteWriter(8).longBE(version).toByteArray(),
            )
        }
    }

    private fun openWith(
        at: Path,
        families: List<String>,
        body: (org.rocksdb.RocksDB, List<org.rocksdb.ColumnFamilyHandle>) -> Unit,
    ) {
        org.rocksdb.RocksDB.loadLibrary()
        val handles = ArrayList<org.rocksdb.ColumnFamilyHandle>()
        org.rocksdb.ColumnFamilyOptions().use { cfOptions ->
            org.rocksdb.DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true).use { dbOptions ->
                val descriptors = families.map { org.rocksdb.ColumnFamilyDescriptor(it.toByteArray(), cfOptions) }
                org.rocksdb.RocksDB.open(dbOptions, at.toAbsolutePath().toString(), descriptors, handles)
                    .use { raw ->
                        body(raw, handles)
                        handles.forEach { it.close() }
                    }
            }
        }
    }

    // A region thread sets a note and reads it back within one event, long before the writer has put
    // it in the database; a read in between has to see it, and a clear the same.
    @Test
    fun `a note is read back at once and written by the writer`() {
        val world = UUID.randomUUID()
        val here = WorldBlock(world, 1, 2, 3)
        val there = WorldBlock(world, 4, 5, 6)
        log.setFormAt(world, 4, 5, 6, pickaxe)
        log.drain()

        log.setFormAt(world, 1, 2, 3, pickaxe)
        assertArrayEquals(pickaxe, log.formAt(world, 1, 2, 3))
        assertEquals(setOf(here, there), log.formsAt(listOf(here, there)).keys)
        log.clearFormsAt(listOf(there))
        assertEquals(setOf(here), log.formsAt(listOf(here, there)).keys)

        log.drain()
        assertArrayEquals(pickaxe, log.formAt(world, 1, 2, 3))
        assertNull(log.formAt(world, 4, 5, 6))
    }

    // A shulker loses its mark when it is broken, so the name has to outlive the box standing there,
    // restarts included.
    @Test
    fun `the name of a placed container outlives the session`() {
        val world = UUID.randomUUID()
        val owner = UUID.randomUUID()
        assertNull(log.ownerAt(world, 1, 2, 3))
        log.setOwnerAt(world, 1, 2, 3, owner)
        assertEquals(owner, log.ownerAt(world, 1, 2, 3))
        assertNull(log.ownerAt(world, 1, 2, 4))
        assertNull(log.ownerAt(UUID.randomUUID(), 1, 2, 3))

        log.close()
        log = RocksItemLog(dir)
        assertEquals(owner, log.ownerAt(world, 1, 2, 3))

        log.clearOwnerAt(world, 1, 2, 3)
        assertNull(log.ownerAt(world, 1, 2, 3))
    }

    // A block answers with the bare item whatever was put down, so the position has to give back the
    // form it took over rather than one built from the block, restarts included.
    @Test
    fun `the form a position took over outlives the session`() {
        val world = UUID.randomUUID()
        assertNull(log.formAt(world, 1, 2, 3))
        log.setFormAt(world, 1, 2, 3, pickaxe)
        assertArrayEquals(pickaxe, log.formAt(world, 1, 2, 3))
        assertNull(log.formAt(world, 1, 2, 4))
        assertNull(log.formAt(UUID.randomUUID(), 1, 2, 3))

        log.close()
        log = RocksItemLog(dir)
        assertArrayEquals(pickaxe, log.formAt(world, 1, 2, 3))

        log.clearFormAt(world, 1, 2, 3)
        assertNull(log.formAt(world, 1, 2, 3))
    }

    // Two positions one block apart must not read as one, and a form put down where another stood
    // replaces it rather than joining it.
    @Test
    fun `each position keeps its own form`() {
        val world = UUID.randomUUID()
        log.setFormAt(world, 1, 2, 3, pickaxe)
        log.setFormAt(world, 2, 2, 3, torch)
        assertArrayEquals(pickaxe, log.formAt(world, 1, 2, 3))
        assertArrayEquals(torch, log.formAt(world, 2, 2, 3))

        log.setFormAt(world, 1, 2, 3, cobblestone)
        assertArrayEquals(cobblestone, log.formAt(world, 1, 2, 3))

        log.clearFormAt(world, 1, 2, 3)
        assertNull(log.formAt(world, 1, 2, 3))
        assertArrayEquals(torch, log.formAt(world, 2, 2, 3))
    }

    // An explosion clears a few thousand positions in one event, on the thread ticking the region, so
    // it asks about the whole set at once and clears it in one write rather than a trip through JNI
    // per position.
    @Test
    fun `a whole set of positions is asked and cleared in one call`() {
        val here = WorldBlock(world, 1, 2, 3)
        val next = WorldBlock(world, 2, 2, 3)
        val bare = WorldBlock(world, 3, 2, 3)
        val also = WorldBlock(world, 4, 2, 3)
        val elsewhere = WorldBlock(UUID.randomUUID(), 1, 2, 3)
        log.setFormAt(world, 1, 2, 3, pickaxe)
        log.setFormAt(world, 2, 2, 3, torch)
        log.setFormAt(world, 4, 2, 3, cobblestone)

        val forms = log.formsAt(listOf(here, next, bare, elsewhere))

        // A position holding nothing is left out rather than answered with a hole, and every answer
        // belongs to the position it was asked about.
        assertEquals(setOf(here, next), forms.keys)
        assertArrayEquals(pickaxe, forms[here])
        assertArrayEquals(torch, forms[next])
        assertEquals(emptyMap<WorldBlock, ByteArray>(), log.formsAt(emptyList()))

        // Every position named goes, not merely the first of them, and one holding nothing costs the
        // rest of the set nothing.
        log.clearFormsAt(listOf(here, bare, also))

        assertNull(log.formAt(world, 1, 2, 3))
        assertNull(log.formAt(world, 4, 2, 3))
        assertArrayEquals(torch, log.formAt(world, 2, 2, 3))
        assertEquals(setOf(next), log.formsAt(listOf(here, next, bare, also)).keys)
    }

    // The two tables are addressed by the same position and must not be able to read each other.
    @Test
    fun `the form of a position and the name of its container are kept apart`() {
        val world = UUID.randomUUID()
        val owner = UUID.randomUUID()
        log.setOwnerAt(world, 1, 2, 3, owner)
        assertNull(log.formAt(world, 1, 2, 3))

        log.setFormAt(world, 1, 2, 3, pickaxe)
        assertEquals(owner, log.ownerAt(world, 1, 2, 3))

        log.clearFormAt(world, 1, 2, 3)
        assertEquals(owner, log.ownerAt(world, 1, 2, 3))
    }

    @Test
    fun `holder entries read forwards and backwards`() {
        val forwards = log.holderEntries(aliceInv, 0, Long.MAX_VALUE)
        assertEquals(listOf(-32, 5, -1), forwards.map { it.qty })
        assertEquals(listOf(T0, T0 + 10, T0 + 20), forwards.map { it.timestamp })
        assertEquals(listOf(chest, chestUpperSlot, chest), forwards.map { it.counterparty })
        assertEquals(Cause.CONTAINER_REMOVE, forwards[1].cause)
        assertEquals(7, forwards[2].damage)

        val backwards = log.holderEntries(aliceInv, 0, Long.MAX_VALUE, reverse = true)
        assertEquals(forwards.reversed(), backwards)
    }

    @Test
    fun `holder entries respect the time window and the limit`() {
        assertEquals(1, log.holderEntries(aliceInv, T0 + 10, T0 + 10).size)
        assertEquals(2, log.holderEntries(aliceInv, T0 + 10, Long.MAX_VALUE).size)
        assertEquals(1, log.holderEntries(aliceInv, 0, Long.MAX_VALUE, limit = 1).size)
        assertEquals(-1, log.holderEntries(aliceInv, 0, Long.MAX_VALUE, reverse = true, limit = 1).single().qty)
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

    // A row this build cannot decode is not a row without gaps, and a sweep that folds the two
    // together reports a clean pass over a ledger it can no longer read.
    @Test
    fun `rows this build cannot read are counted apart from the ones it checked`() {
        val readable = log.sweep(1000).checked
        val orphan = EntryCodec.key(chest, T0 + 40, 99L, 0, log.registries)
        log.close()
        // A version this build does not know, which is the one thing decoding rejects before it has
        // read anything else.
        writeRawEntry(dir, orphan, byteArrayOf(0x07))
        log = RocksItemLog(dir)

        val report = log.sweep(1000)
        assertEquals(readable, report.checked)
        assertEquals(1, report.unreadable)
        assertTrue(report.reachedEnd)
    }

    // A row torn by a half-written page reads as a version this build knows and then runs out of
    // bytes. The sweep already has somewhere to put a row it cannot read, and a throw would instead
    // end the pass on the spot and leave every row after it unswept for as long as the row is there.
    @Test
    fun `a row torn in half is counted rather than ending the pass`() {
        val readable = log.sweep(1000).checked
        val torn = EntryCodec.key(chest, T0 + 40, 99L, 0, log.registries)
        log.close()
        // Nothing but the header: the version is this build's, and the cause byte after it is gone.
        writeRawEntry(dir, torn, byteArrayOf(EntryCodec.header(Kind.TRANSFER, Confidence.FACT).toByte()))
        log = RocksItemLog(dir)

        val report = log.sweep(1000)
        assertEquals(readable, report.checked)
        assertEquals(1, report.unreadable)
        assertTrue(report.reachedEnd)
    }

    private fun writeRawEntry(dir: Path, key: ByteArray, value: ByteArray) {
        RocksDB.loadLibrary()
        val path = dir.toAbsolutePath().toString()
        val names = Options().use { RocksDB.listColumnFamilies(it, path) }
        val handles = ArrayList<ColumnFamilyHandle>()
        DBOptions().use { options ->
            RocksDB.open(options, path, names.map { ColumnFamilyDescriptor(it) }, handles).use { db ->
                val entries = handles[names.indexOfFirst { it.contentEquals("entries".toByteArray()) }]
                db.put(entries, key, value)
                handles.forEach { it.close() }
            }
        }
    }

    @Test
    fun `region entries cover the block box and nothing else`() {
        val inBox = log.regionEntries(world, 96, -208, 112, -192, 0, Long.MAX_VALUE)
        assertEquals(listOf(32, -5, 1, -3), inBox.map { it.qty })
        assertTrue(inBox.all { it.holder is Container })
        assertEquals(5, (inBox[1].holder as Container).slot)

        assertTrue(log.regionEntries(world, 5000, 5000, 5016, 5016, 0, Long.MAX_VALUE).isEmpty())
        assertTrue(log.regionEntries(UUID.randomUUID(), 96, -208, 112, -192, 0, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `region entries see the far half of a chunk and respect the time window`() {
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, farSideOfChunk, cobblestone, null, 2, T0 + 50))
        log.drain()

        val whole = log.regionEntries(world, 96, -208, 112, -192, 0, Long.MAX_VALUE)
        assertEquals(5, whole.size)
        assertEquals(1, whole.count { it.holder == farSideOfChunk })

        val window = log.regionEntries(world, 96, -208, 112, -192, T0 + 10, T0 + 30)
        assertEquals(listOf(T0 + 10, T0 + 20, T0 + 30), window.map { it.timestamp })
        assertTrue(log.regionEntries(world, 96, -208, 112, -192, T0 + 60, Long.MAX_VALUE).isEmpty())
    }

    // A capped region read has to keep the newest rows, not the oldest, or every lookup of a busy
    // area answers with its earliest history.
    @Test
    fun `a reversed region read keeps the newest rows`() {
        val forwards = log.regionEntries(world, 96, -208, 112, -192, 0, Long.MAX_VALUE, limit = 2)
        assertEquals(listOf(T0, T0 + 10), forwards.map { it.timestamp })

        val backwards = log.regionEntries(world, 96, -208, 112, -192, 0, Long.MAX_VALUE, reverse = true, limit = 2)
        assertEquals(listOf(T0 + 30, T0 + 20), backwards.map { it.timestamp })
    }

    @Test
    fun `an oversized region is refused instead of scanned`() {
        assertThrows(IllegalArgumentException::class.java) {
            log.regionEntries(world, 0, 0, 100_000, 0, 0, Long.MAX_VALUE)
        }
    }

    @Test
    fun `transaction entries find the other half of the pair`() {
        val fromChest = log.holderEntries(chest, 0, Long.MAX_VALUE)
        val paired = log.transactionEntries(fromChest.first())
        assertEquals(2, paired.size)
        assertEquals(1, paired.map { it.txId }.distinct().size)
        assertEquals(0, paired.sumOf { it.qty })
        assertEquals(setOf(chest, aliceInv), paired.map { it.holder }.toSet())

        val intoVoid = fromChest.single { it.counterparty === Void }
        assertEquals(listOf(intoVoid), log.transactionEntries(intoVoid))
    }

    @Test
    fun `every transaction that avoids the void sums to zero`() {
        val stored = log.holderEntries(aliceInv, 0, Long.MAX_VALUE, limit = 1000) +
            log.holderEntries(chest, 0, Long.MAX_VALUE, limit = 1000)
        val byTransaction = stored.distinctBy { it.txId to it.holder }.groupBy { it.txId }
        assertEquals(4, byTransaction.size)

        var balanced = 0
        for ((txId, legs) in byTransaction) {
            if (legs.any { it.holder === Void || it.counterparty === Void }) continue
            assertEquals(2, legs.size, "transaction $txId lost a leg")
            assertEquals(0, legs.sumOf { it.qty }, "transaction $txId does not balance")
            assertEquals(1, legs.map { it.itemFormId }.distinct().size, "transaction $txId split across forms")
            balanced++
        }
        assertEquals(3, balanced)
    }

    // Breaking a block is not conservative: the position gives up stone and the world receives
    // cobblestone, so both halves face the Void and neither names the other. Without the index the
    // graph walk stops at the first mined block.
    @Test
    fun `a transaction whose halves both face the void is reassembled through the index`() {
        val position = WorldBlock(world, 100, 65, -200)
        val dropped = ItemEntityRef(UUID.randomUUID())
        log.submit(
            listOf(
                Transfer(Cause.BLOCK_DROP, position, Void, cobblestone, null, 1, T0 + 60, actor = alice),
                Transfer(Cause.BLOCK_DROP, Void, dropped, torch, null, 1, T0 + 60, actor = alice),
            )
        )
        log.drain()

        val fromPosition = log.holderEntries(position, 0, Long.MAX_VALUE).single()
        val whole = log.transactionEntries(fromPosition)
        assertEquals(2, whole.size)
        assertEquals(1, whole.map { it.txId }.distinct().size)
        assertEquals(setOf(position, dropped), whole.map { it.holder }.toSet())
        assertEquals(listOf(-1, 1), whole.map { it.qty })
        assertEquals(setOf(alice), whole.map { it.actor }.toSet())

        // Reachable from either end, not just the one the investigation happened to start at.
        val fromEntity = log.holderEntries(dropped, 0, Long.MAX_VALUE).single()
        assertEquals(whole.toSet(), log.transactionEntries(fromEntity).toSet())
    }

    // A pair already names itself, so an index row for it would be a second write buying nothing.
    @Test
    fun `an ordinary pair is reassembled without the index`() {
        val paired = log.holderEntries(chest, 0, Long.MAX_VALUE).first()
        assertEquals(2, log.transactionEntries(paired).size)
        val lone = log.holderEntries(chest, 0, Long.MAX_VALUE).single { it.counterparty === Void }
        assertEquals(listOf(lone), log.transactionEntries(lone))
    }

    @Test
    fun `a region scan answers with both block holders`() {
        val position = WorldBlock(world, 101, 65, -200)
        log.submit(Transfer(Cause.BLOCK_PLACE, aliceInv, position, cobblestone, null, 1, T0 + 70))
        log.drain()

        val inBox = log.regionEntries(world, 96, -208, 112, -192, 0, Long.MAX_VALUE)
        assertEquals(1, inBox.count { it.holder is WorldBlock })
        assertEquals(4, inBox.count { it.holder is Container })
        assertEquals(position, inBox.single { it.holder is WorldBlock }.holder)
        assertEquals(listOf(T0, T0 + 10, T0 + 20, T0 + 30, T0 + 70), inBox.map { it.timestamp })

        // Read backwards it is the same rows in the other order, both holder kinds included: the two
        // kinds live under prefixes of their own and a reversed walk seeks into each one separately.
        val backwards = log.regionEntries(world, 96, -208, 112, -192, 0, Long.MAX_VALUE, reverse = true)
        assertEquals(inBox.reversed(), backwards)
    }

    // Breaking a block writes the position losing what it was made of, and the person who swung is in
    // the actor column and at neither end. Read back where it happened, an investigation of that
    // player has to find it there.
    @Test
    fun `a broken block is found at its position and belongs to the breaker`() {
        val position = WorldBlock(world, 60, 12, 60)
        log.submit(Transfer(Cause.BLOCK_DROP, position, Void, cobblestone, null, 1, T0 + 80, actor = alice))
        log.drain()

        val broken = log.holderEntries(position, 0, Long.MAX_VALUE).single()
        assertEquals(-1, broken.qty)
        assertEquals(Void, broken.counterparty)
        assertTrue(namesUser(broken, setOf(alice)))
        assertFalse(namesUser(broken, setOf(bob)))
        assertEquals(emptyList<String>(), log.sweep(100).gaps)
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

        log.close()
        log = RocksItemLog(dir)
        assertTrue(sstFiles() > 0, "nothing was flushed, so the table's own prefix filter never had a turn")
        assertEveryScanAnswers(position)
    }

    private fun sstFiles(): Int = dir.toFile().listFiles().orEmpty().count { it.name.endsWith(".sst") }

    private fun assertEveryScanAnswers(position: WorldBlock) {
        // A position: its prefix is exactly the width the extractor is sized for, which is the case
        // where seeking to the key just past the prefix asks the filter about the wrong prefix.
        assertEquals(20, log.holderEntries(position, 0, Long.MAX_VALUE, limit = 1000).size)
        val newestFirst = log.holderEntries(position, 0, Long.MAX_VALUE, reverse = true, limit = 1000)
        assertEquals(20, newestFirst.size)
        assertEquals(T0 + 119, newestFirst.first().timestamp)
        assertEquals(
            listOf(T0 + 119, T0 + 118),
            log.regionEntries(world, 96, -208, 112, -192, 0, Long.MAX_VALUE, reverse = true, limit = 2)
                .map { it.timestamp },
        )

        // A player: its prefix is shorter than the extractor, so its rows are spread over many
        // extractor prefixes and no single one of them can stand for the lot.
        val fromAlice = log.holderEntries(aliceInv, 0, Long.MAX_VALUE, limit = 1000)
        assertEquals(3 + 20, fromAlice.size, "the seeded rows plus the placements")
        assertEquals(fromAlice.reversed(), log.holderEntries(aliceInv, 0, Long.MAX_VALUE, reverse = true, limit = 1000))
        assertTrue(log.formBalance(alice).isNotEmpty())

        // The whole ledger, walked from the start and then again a few rows at a time from a cursor.
        // Counted against the prefixes rather than against another walk, or a walk that stopped early
        // would agree with itself: these three prefixes hold every row the log has been given.
        val everyRow = fromAlice.size + newestFirst.size +
            log.holderEntries(chest, 0, Long.MAX_VALUE, limit = 1000).size
        val whole = log.sweep(1000)
        assertTrue(whole.reachedEnd)
        assertEquals(everyRow, whole.checked, "the walk of the whole ledger did not see every row")
        assertEquals(emptyList<String>(), whole.gaps)
        var resumed = 0
        do {
            val pass = log.sweep(3)
            resumed += pass.checked
        } while (!pass.reachedEnd)
        assertEquals(whole.checked, resumed, "a resumed walk lost rows the one from the start could see")
    }

    @Test
    fun `equal forms are interned and readable by id`() {
        val fromChest = log.holderEntries(chest, 0, Long.MAX_VALUE)
        assertEquals(fromChest[0].itemFormId, fromChest[1].itemFormId)
        assertNotEquals(fromChest[0].itemFormId, fromChest[2].itemFormId)
        assertArrayEquals(cobblestone, log.form(fromChest[0].itemFormId))
        assertArrayEquals(pickaxe, log.form(fromChest[2].itemFormId))
        assertEquals(null, log.form(9999))
    }

    @Test
    fun `a reopened log keeps entries forms and counters`() {
        val before = log.holderEntries(chest, 0, Long.MAX_VALUE)
        log.close()
        log = RocksItemLog(dir)

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
        assertArrayEquals(cobblestone, log.form(before[0].itemFormId))
        assertArrayEquals(pickaxe, log.form(before[2].itemFormId))
    }

    // One movement the capture cannot encode is a bug in the capture. Letting it stop the writer would
    // turn that single bad row into a session that records nothing at all from then on, and a ledger
    // kept in order to be trusted must fail on the row rather than on everything after it.
    @Test
    fun `a movement that cannot be written costs only itself`() {
        val unreachable = Container(world, 100, 1_000_000, -200, 0)
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 1, T0 + 40))
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, unreachable, torch, null, 2, T0 + 41))
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 3, T0 + 42))
        log.drain()

        val landed = log.holderEntries(chest, 0, Long.MAX_VALUE, limit = 1000)
            .filter { it.itemFormId == log.formId(torch) }
        assertEquals(listOf(1, 3), landed.map { it.qty })

        // Still alive afterwards: the next movement is written like nothing happened.
        log.submit(Transfer(Cause.CONTAINER_ADD, aliceInv, chest, torch, null, 5, T0 + 43))
        log.drain()
        assertEquals(
            listOf(1, 3, 5),
            log.holderEntries(chest, 0, Long.MAX_VALUE, limit = 1000)
                .filter { it.itemFormId == log.formId(torch) }.map { it.qty },
        )
        assertEquals(emptyList<String>(), log.sweep(1000).gaps)
    }

    // A lookup reads by position, and the item that came out of the break is held by no position at
    // all, so on its own the reader sees a debit whose other half is nowhere. Pulling the whole
    // transaction is what puts the two back on one screen.
    @Test
    fun `expanding a position row brings back the item the break produced`() {
        val position = WorldBlock(world, 12, 65, 34)
        val dropped = ItemEntityRef(UUID.randomUUID())
        log.submit(
            listOf(
                Transfer(Cause.BLOCK_DROP, position, Void, cobblestone, null, 1, T0 + 70, actor = alice),
                Transfer(Cause.BLOCK_DROP, Void, dropped, torch, null, 1, T0 + 70, actor = alice),
            )
        )
        log.drain()

        val byPosition = log.holderEntries(position, 0, Long.MAX_VALUE)
        assertEquals(listOf(position), byPosition.map { it.holder }, "the drop is not indexed by position")

        val shown = wholeTransactions(log, byPosition)
        assertEquals(setOf(position, dropped), shown.map { it.holder }.toSet())

        // Both halves already in hand must not double the answer: a break of a double block puts two
        // positions of one transaction on the same screen.
        assertEquals(shown.toSet(), wholeTransactions(log, shown).toSet())
        assertEquals(shown.size, wholeTransactions(log, shown).size)
    }

    // An ordinary movement already names both ends on the one row the position holds, so pulling its
    // mirror in would print the same movement twice, once from each side of it.
    @Test
    fun `expanding an ordinary movement does not add its mirror half`() {
        val position = WorldBlock(world, 14, 65, 34)
        log.submit(Transfer(Cause.BLOCK_PLACE, aliceInv, position, cobblestone, null, 1, T0 + 90, actor = alice))
        log.drain()

        val byPosition = log.holderEntries(position, 0, Long.MAX_VALUE)
        assertEquals(listOf(aliceInv), byPosition.map { it.counterparty }, "the movement names its far end")
        assertEquals(byPosition, wholeTransactions(log, byPosition))
    }

    // Nothing came out, so there is nothing to bring back and no second read to pay for.
    @Test
    fun `expanding a break that dropped nothing adds no rows`() {
        val position = WorldBlock(world, 13, 65, 34)
        log.submit(Transfer(Cause.BLOCK_DROP, position, Void, cobblestone, null, 1, T0 + 80, actor = alice))
        log.drain()

        val byPosition = log.holderEntries(position, 0, Long.MAX_VALUE)
        assertEquals(byPosition, wholeTransactions(log, byPosition))
    }
}
