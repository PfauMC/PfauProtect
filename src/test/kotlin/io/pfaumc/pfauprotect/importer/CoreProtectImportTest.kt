package io.pfaumc.pfauprotect.importer

import io.pfaumc.pfauprotect.AIR
import io.pfaumc.pfauprotect.BothPlanes
import io.pfaumc.pfauprotect.DIRT
import io.pfaumc.pfauprotect.STONE
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.capture.entity.nbtOf
import io.pfaumc.pfauprotect.command.LookupQuery
import io.pfaumc.pfauprotect.command.RowFilter
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.ownHolders
import io.pfaumc.pfauprotect.rollback.Planned
import io.pfaumc.pfauprotect.rollback.Refused
import io.pfaumc.pfauprotect.rollback.RollbackReader
import io.pfaumc.pfauprotect.rollback.settle
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.ChatKind
import io.pfaumc.pfauprotect.storage.ChatLog
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.RandomAccessFile
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

@ExtendWith(Vanilla::class)
class CoreProtectImportTest : BothPlanes() {
    // CoreProtect counts in seconds; the import keeps everything before UNTIL, which is where our own journal begins.
    private val t = 1_790_000_000L
    private val until = (t + 1000) * 1000

    private lateinit var chat: ChatLog

    override fun openPlanes() {
        super.openPlanes()
        chat = ChatLog(dir.resolve("chat"))
    }

    @AfterEach
    fun closeChat() = chat.close()

    // Alice replaced stone with dirt in CoreProtect's days, and in ours broke the dirt again; `#tnt` took two
    // stones in one second, `#water` ran, a sheep died. The replace is one row before ours, so rolling Alice back
    // goes all the way to the stone; the blast is one event with nobody behind it, the water stays out, a run
    // stopped and run again writes nothing twice, and a run with a later `until` takes what the first left.
    @Test
    fun `blocks come over as rows of their own, older than ours, and a second run adds nothing`() {
        val db = fixture { c ->
            block(c, 1, t, ALICE, 1, 64, 1, "minecraft:stone", action = 0)
            block(c, 2, t, ALICE, 1, 64, 1, "minecraft:dirt", action = 1)
            block(c, 3, t + 1, ALICE, 2, 64, 1, "minecraft:oak_stairs", action = 1, properties = listOf("facing=east", "half=bottom"))
            block(c, 4, t + 2, TNT, 3, 64, 1, "minecraft:stone", action = 0)
            block(c, 5, t + 2, TNT, 4, 64, 1, "minecraft:stone", action = 0)
            block(c, 6, t + 3, WATER, 5, 64, 1, "minecraft:water", action = 1)
            c.createStatement().execute("INSERT INTO co_block VALUES ($t, $ALICE, 1, 6, 64, 1, 1, 0, NULL, NULL, 3, 0)")
            block(c, 8, t + 2000, ALICE, 7, 64, 1, "minecraft:stone", action = 1)
        }
        log.submit(listOf(BlockChange(1, 64, 1, DIRT, AIR, Cause.BLK_PLAYER_BREAK, until + 5000, actor = alice)))
        log.drain()

        val job = importOf(db).also { it.run() }
        assertEquals("done", job.outcome, job.counts.toString())

        val history = log.at(1, 64, 1, 0, Long.MAX_VALUE, 10, reverse = false)
        assertEquals(listOf(Cause.BLK_CP_PLACE, Cause.BLK_PLAYER_BREAK), history.map { it.cause })
        assertTrue(history.first().eventId < 0)
        val stairs = log.at(2, 64, 1, 0, Long.MAX_VALUE, 10, reverse = false).single()
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", shared.registries.keyOf(RegistryNamespace.BLOCK_STATE, stairs.stateAfter))
        val blast = listOf(3, 4).map { log.at(it, 64, 1, 0, Long.MAX_VALUE, 10, reverse = false).single() }
        assertEquals(listOf(Cause.BLK_CP_EXPLOSION, Cause.BLK_CP_EXPLOSION), blast.map { it.cause })
        assertEquals(1, blast.map { it.eventId }.distinct().size)
        assertNull(blast.first().actor)
        assertTrue(log.at(5, 64, 1, 0, Long.MAX_VALUE, 10, reverse = false).isEmpty())
        assertTrue(log.at(7, 64, 1, 0, Long.MAX_VALUE, 10, reverse = false).isEmpty())
        val kill = log.entitiesAt(6, 64, 1, 0, Long.MAX_VALUE, 10).rows.single()
        assertEquals(Cause.CP_ENTITY_KILLED to "minecraft:sheep", kill.cause to kill.type)

        val reading = RollbackReader(shared, logs).around(world, 0, 64, 0, 15, 0, Long.MAX_VALUE, filterOf(), false)
        val planned = reading as? Planned ?: throw AssertionError((reading as Refused).reason)
        val replaced = planned.chunks.flatMap { it.positions }.single { it.at.x == 1 }
        assertEquals(STONE, settle(AIR, replaced.steps).back?.before)

        shared.setImportMark(CoreProtectImport.markOf(shared.importMark(CoreProtectImport.ORIGIN_MARK), "co_block"), 0)
        importOf(db).run()
        assertEquals(2, log.at(1, 64, 1, 0, Long.MAX_VALUE, 10, reverse = false).size)
        assertEquals(1, log.entitiesAt(6, 64, 1, 0, Long.MAX_VALUE, 10).rows.size)

        // A later `until` goes on from where the first one stopped, and takes what lay past it.
        importOf(db, until = (t + 3000) * 1000).run()
        assertEquals(Cause.BLK_CP_PLACE, log.at(7, 64, 1, 0, Long.MAX_VALUE, 10, reverse = false).single().cause)
    }

    // What Alice put into a chest and took out of it faces the void, so it never counts towards what she holds; the
    // sign she wrote on carries its text, and so does its break, from the snapshot CoreProtect took of it a second
    // early; her chat is marked as CoreProtect's, and her login keeps no password.
    @Test
    fun `containers, a sign's text and chat come over`() {
        val db = fixture { c ->
            c.createStatement().execute("INSERT INTO co_container VALUES ($t, $ALICE, 1, 9, 64, 9, $DIAMOND, 0, 5, NULL, 1, 0)")
            c.createStatement().execute("INSERT INTO co_container VALUES (${t + 1}, $ALICE, 1, 9, 64, 9, $DIAMOND, 0, 2, NULL, 0, 0)")
            c.createStatement().execute("INSERT INTO co_container VALUES (${t + 1}, $HOPPER, 1, 9, 63, 9, $DIAMOND, 0, 1, NULL, 1, 0)")
            block(c, 1, t, ALICE, 8, 64, 8, "minecraft:oak_sign", action = 1)
            c.createStatement().execute(
                "INSERT INTO co_sign VALUES (${t + 1}, $ALICE, 1, 8, 64, 8, 1, 1908001, 1908001, 1, 0, 0, 'hello', 'there', '', '', NULL, NULL, NULL, NULL)",
            )
            c.createStatement().execute(
                "INSERT INTO co_sign VALUES (${t + 3}, $ALICE, 1, 8, 64, 8, 0, 1908001, 1908001, 1, 0, 0, 'hello', 'there', '', '', NULL, NULL, NULL, NULL)",
            )
            block(c, 2, t + 4, ALICE, 8, 64, 8, "minecraft:oak_sign", action = 0)
            // Written in the second it broke and another went up: on which of the two is unknown, so it is not imported.
            block(c, 3, t + 4, ALICE, 8, 64, 8, "minecraft:oak_sign", action = 1)
            c.createStatement().execute(
                "INSERT INTO co_sign VALUES (${t + 4}, $ALICE, 1, 8, 64, 8, 1, 1908001, 1908001, 1, 0, 0, 'late', '', '', '', NULL, NULL, NULL, NULL)",
            )
            c.createStatement().execute("INSERT INTO co_chat VALUES ($t, $ALICE, 1, 0, 64, 0, 'hi all')")
            c.createStatement().execute("INSERT INTO co_command VALUES ($t, $ALICE, 1, 0, 64, 0, '/login secret')")
            c.createStatement().execute("INSERT INTO co_session VALUES ($t, $ALICE, 1, 0, 64, 0, 1)")
        }

        assertEquals("done", importOf(db).also { it.run() }.outcome)

        val box = shared.holderEntries(Container(world, 9, 64, 9, 0), 0, Long.MAX_VALUE)
        assertEquals(listOf(Cause.CP_CONTAINER_ADD to 5, Cause.CP_CONTAINER_REMOVE to -2), box.map { it.cause to it.qty })
        assertTrue(box.all { it.actor == alice && it.txId < 0 })
        assertEquals(2, shared.actorPage(alice, 0, Long.MAX_VALUE, 10).entries.size)
        assertTrue(shared.holderEntries(Container(world, 9, 63, 9, 0), 0, Long.MAX_VALUE).isEmpty())
        assertTrue(shared.slotBalances(ownHolders(alice), Long.MAX_VALUE).values.all { it.isEmpty() })

        val signRows = log.at(8, 64, 8, 0, Long.MAX_VALUE, 10, reverse = false)
        assertEquals(listOf(Cause.BLK_CP_PLACE, Cause.BLK_CP_SIGN, Cause.BLK_CP_PLACE), signRows.map { it.cause })
        val sign = signRows[1]
        val text = nbtOf(shared.payload(sign.payloadAfter!!)!!).toString()
        assertTrue("hello" in text && "there" in text, text)
        assertNull(sign.payloadBefore)
        assertEquals(text, nbtOf(shared.payload(signRows[2].payloadBefore!!)!!).toString())

        val lines = chat.read(0, Long.MAX_VALUE, 10) { true }
        assertEquals(
            setOf(ChatKind.CP_CHAT to "hi all", ChatKind.CP_COMMAND to "/login", ChatKind.CP_JOIN to ""),
            lines.map { it.kind to it.text }.toSet(),
        )
    }

    // A copy taken off a running server has pages that do not read: the import steps over them, counts what it lost
    // and goes on to the end of the table.
    @Test
    fun `a damaged page is stepped over and counted`() {
        val rows = 4000
        val db = fixture { c ->
            c.autoCommit = false
            c.prepareStatement("INSERT INTO co_chat VALUES (?, $ALICE, 1, 0, 64, 0, ?)").use { insert ->
                for (i in 1..rows) {
                    insert.setLong(1, t)
                    insert.setString(2, "line $i " + "x".repeat(200))
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            c.commit()
        }
        RandomAccessFile(db.toFile(), "rw").use { file ->
            val pages = file.length() / PAGE_SIZE
            file.seek(pages * 2 / 3 * PAGE_SIZE)
            file.write(ByteArray(PAGE_SIZE) { 0x5A })
        }

        val job = importOf(db).also { it.run() }

        assertEquals("done", job.outcome, job.counts.toString())
        val lost = job.counts["co_chat unreadable"]
        val read = chat.read(0, Long.MAX_VALUE, rows) { true }.size
        assertTrue(lost > 0 && read > rows / 2, job.counts.toString())
        assertEquals(rows.toLong(), read + lost, job.counts.toString())
    }

    private fun importOf(db: Path, until: Long = this.until) = CoreProtectImport(
        db, copy = true, until = until, ledger = shared,
        worldOf = { if (it == "world") world else null },
        logOf = { logs.get(it) },
        chat = chat,
        registries = ServerRegistries.access,
        codec = ItemFormCodec(shared.registries, ServerRegistries.access),
    )

    private fun filterOf() = RowFilter(shared, LookupQuery(), listOf(setOf(alice)), rollback = true)

    // A CoreProtect database with CoreProtect's own tables, its maps filled with what the tests name.
    private fun fixture(fill: (Connection) -> Unit): Path {
        val path = dir.resolve("coreprotect.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { s ->
                for (ddl in DDL) s.execute(ddl)
                s.execute("INSERT INTO co_version VALUES ($ORIGIN, '2.24.0')")
                s.execute("INSERT INTO co_world VALUES (1, 'world')")
                s.execute("INSERT INTO co_user VALUES ($ALICE, 0, 'Alice', '$alice')")
                s.execute("INSERT INTO co_user VALUES ($TNT, 0, '#tnt', NULL)")
                s.execute("INSERT INTO co_user VALUES ($WATER, 0, '#water', NULL)")
                s.execute("INSERT INTO co_user VALUES ($HOPPER, 0, '#hopper', NULL)")
                s.execute("INSERT INTO co_entity_map VALUES (1, 'sheep')")
                s.execute("INSERT INTO co_material_map VALUES ($DIAMOND, 'minecraft:diamond')")
            }
            fill(c)
            if (!c.autoCommit) c.commit()
        }
        return path
    }

    private val materials = HashMap<String, Int>()
    private val properties = HashMap<String, Int>()

    private fun block(
        c: Connection, rowid: Long, time: Long, user: Int, x: Int, y: Int, z: Int, material: String,
        action: Int, properties: List<String> = emptyList(),
    ) {
        val type = materials.getOrPut(material) {
            (materials.size + 100).also { c.createStatement().execute("INSERT INTO co_material_map VALUES ($it, '$material')") }
        }
        val ids = properties.map { p ->
            this.properties.getOrPut(p) { (this.properties.size + 1).also { c.createStatement().execute("INSERT INTO co_blockdata_map VALUES ($it, '$p')") } }
        }
        c.prepareStatement("INSERT INTO co_block (rowid, time, user, wid, x, y, z, type, data, meta, blockdata, action, rolled_back) VALUES (?, ?, ?, 1, ?, ?, ?, ?, 0, NULL, ?, ?, 0)").use {
            it.setLong(1, rowid)
            it.setLong(2, time)
            it.setInt(3, user)
            it.setInt(4, x)
            it.setInt(5, y)
            it.setInt(6, z)
            it.setInt(7, type)
            it.setBytes(8, ids.takeIf { l -> l.isNotEmpty() }?.joinToString(",")?.toByteArray())
            it.setInt(9, action)
            it.executeUpdate()
        }
    }

    private companion object {
        const val ALICE = 1
        const val TNT = 2
        const val WATER = 3
        const val HOPPER = 4
        const val DIAMOND = 7
        const val PAGE_SIZE = 4096
        const val ORIGIN = 1_783_226_854L

        // CoreProtect's SQLite tables as `Database.createDatabaseTables` makes them, the ones an import reads.
        val DDL = listOf(
            "CREATE TABLE co_block (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data INTEGER, meta BLOB, blockdata BLOB, action INTEGER, rolled_back INTEGER)",
            "CREATE INDEX block_index ON co_block(wid,x,z,time)",
            "CREATE TABLE co_container (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data INTEGER, amount INTEGER, metadata BLOB, action INTEGER, rolled_back INTEGER)",
            "CREATE TABLE co_sign (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER, color INTEGER, color_secondary INTEGER, data INTEGER, waxed INTEGER, face INTEGER, line_1 TEXT, line_2 TEXT, line_3 TEXT, line_4 TEXT, line_5 TEXT, line_6 TEXT, line_7 TEXT, line_8 TEXT)",
            "CREATE INDEX sign_index ON co_sign(wid,x,z,time)",
            "CREATE TABLE co_chat (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, message TEXT)",
            "CREATE TABLE co_command (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, message TEXT)",
            "CREATE TABLE co_session (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER)",
            "CREATE TABLE co_user (id INTEGER PRIMARY KEY ASC, time INTEGER, user TEXT, uuid TEXT)",
            "CREATE TABLE co_world (id INTEGER, world TEXT)",
            "CREATE TABLE co_version (time INTEGER, version TEXT)",
            "CREATE TABLE co_material_map (id INTEGER, material TEXT)",
            "CREATE TABLE co_blockdata_map (id INTEGER, data TEXT)",
            "CREATE TABLE co_entity_map (id INTEGER, entity TEXT)",
        )
    }
}
