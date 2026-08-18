package io.pfaumc.pfauprotect

import org.bukkit.command.CommandSender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.UUID

private const val T0 = 1_700_000_000_000L
private const val AIR = "minecraft:air"
private const val STONE = "minecraft:stone"

// Both planes are read through the one command a moderator has. A block plane nothing can read is a
// block plane nobody can check, which is how a whole class of capture went unverified before.
class LookupReadTest {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000005")
    private val stone = byteArrayOf(3)

    private lateinit var shared: RocksItemLog
    private lateinit var logs: BlockLogs
    private lateinit var log: BlockLog

    @BeforeEach
    fun open(@TempDir dir: Path) {
        shared = RocksItemLog(dir.resolve("items"))
        logs = BlockLogs(dir.resolve("blocks"), shared)
        log = logs.open(world)
    }

    @AfterEach
    fun closeAll() {
        logs.close()
        shared.close()
    }

    private fun said(query: LookupQuery = LookupQuery(), x: Int = 10, y: Int = 64, z: Int = -3): List<String> {
        val lines = ArrayList<String>()
        val sender = Proxy.newProxyInstance(
            CommandSender::class.java.classLoader,
            arrayOf(CommandSender::class.java),
        ) { _, method, args ->
            if (method.name == "sendMessage") args?.filterIsInstance<String>()?.forEach { lines += it }
            null
        } as CommandSender
        Lookups(stubPlugin(), shared, logs).report(sender, LookupTarget(world, x, y, z, "stone at $x $y $z"), query)
        return lines
    }

    @Suppress("UNCHECKED_CAST")
    private fun stubPlugin() = Proxy.newProxyInstance(
        org.bukkit.plugin.Plugin::class.java.classLoader,
        arrayOf(org.bukkit.plugin.Plugin::class.java),
    ) { _, _, _ -> null } as org.bukkit.plugin.Plugin

    @Test
    fun `a position answers with both planes in the order things happened`() {
        val at = WorldBlock(world, 10, 64, -3)
        shared.submit(Transfer(Cause.BLOCK_PLACE, Void, at, stone, null, 1, T0))
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0)))
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0 + 1000)))
        shared.submit(wroteOff(at, stone, Cause.BLK_TNT, null, T0 + 1000))
        shared.drain()
        log.drain()

        val lines = said()

        // Newest first, so the explosion is above the placement in both planes.
        assertTrue(lines.any { it.contains("blk_tnt") && it.contains("minecraft:stone -> minecraft:air") }, "$lines")
        assertTrue(lines.any { it.contains("blk_player_place") && it.contains("minecraft:air -> minecraft:stone") }, "$lines")
        assertTrue(lines.any { it.contains("block_place") }, "the item plane is still there: $lines")
        // Newest first across both planes; rows sharing an instant keep the item plane ahead of the
        // block plane, which is the order the two were read in and is stable.
        assertEquals(
            listOf("blk_tnt", "blk_tnt", "block_place", "blk_player_place"),
            lines.drop(1).mapNotNull { line -> line.trim().split("  ").getOrNull(1) },
            "$lines",
        )
    }

    @Test
    fun `a block row says when nobody could be named`() {
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_LIQUID_DESTROY, T0)))
        log.drain()

        assertTrue(said().any { it.contains("by nobody named") }, "${said()}")
    }

    // A filter meant for the block plane must not drag the item plane's rows in behind it.
    @Test
    fun `a block filter answers with block rows alone`() {
        val at = WorldBlock(world, 10, 64, -3)
        shared.submit(Transfer(Cause.BLOCK_PLACE, Void, at, stone, null, 1, T0))
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0)))
        shared.drain()
        log.drain()

        val lines = said(LookupQuery(causes = Action.BLOCK.causes))
        assertTrue(lines.any { it.contains("blk_player_place") }, "$lines")
        assertTrue(lines.none { it.contains("block_place ") }, "$lines")
    }

    // A world whose base was never opened has no history to answer with, and answering with the item
    // plane alone would read as a position the block plane agrees about.
    @Test
    fun `a world with no open base answers without the block plane rather than failing`() {
        val other = UUID.randomUUID()
        shared.submit(
            Transfer(Cause.BLOCK_PLACE, Void, WorldBlock(other, 1, 64, 1), stone, null, 1, T0)
        )
        shared.drain()

        val lines = ArrayList<String>()
        val sender = Proxy.newProxyInstance(
            CommandSender::class.java.classLoader,
            arrayOf(CommandSender::class.java),
        ) { _, method, args ->
            if (method.name == "sendMessage") args?.filterIsInstance<String>()?.forEach { lines += it }
            null
        } as CommandSender
        Lookups(stubPlugin(), shared, logs)
            .report(sender, LookupTarget(other, 1, 64, 1, "stone at 1 64 1"), LookupQuery())

        assertTrue(lines.any { it.contains("block_place") }, "$lines")
    }
}
