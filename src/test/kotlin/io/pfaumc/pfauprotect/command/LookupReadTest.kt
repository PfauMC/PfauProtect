package io.pfaumc.pfauprotect.command
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.BlockLog
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.attribution.behind
import io.pfaumc.pfauprotect.capture.item.mutation
import io.pfaumc.pfauprotect.capture.block.payloadOf
import io.pfaumc.pfauprotect.capture.block.wroteOff
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignTextSlot
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.AfterEach
import net.kyori.adventure.text.Component as AdventureComponent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.UUID
import net.minecraft.world.item.ItemStack as NmsItemStack

private const val T0 = 1_700_000_000_000L
private const val AIR = "minecraft:air"
private const val STONE = "minecraft:stone"

// Both planes are read through the one command a moderator has. A block plane nothing can read is a
// block plane nobody can check, which is how a whole class of capture went unverified before.
class LookupReadTest {
    // A line names the game's things through its registries, which have to be up before anything reads them.
    init {
        ServerRegistries.access
    }

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

    private fun said(
        query: LookupQuery = LookupQuery(),
        x: Int = 10,
        y: Int = 64,
        z: Int = -3,
        codec: ItemFormCodec? = null,
        // Stands in for the server's player cache, which a test has none of.
        players: Map<String, UUID> = emptyMap(),
    ): List<String> {
        val lines = ArrayList<String>()
        val sender = Proxy.newProxyInstance(
            CommandSender::class.java.classLoader,
            arrayOf(CommandSender::class.java),
        ) { _, method, args ->
            if (method.name == "sendMessage") args?.forEach { if (it is String) lines += it else if (it is AdventureComponent) lines += flat(it) }
            null
        } as CommandSender
        val names = players.entries.associate { (name, id) -> id to name }
        Lookups(stubPlugin(), shared, logs, codec, players::get, names::get)
            .report(sender, LookupTarget(world, x, y, z, "stone at $x $y $z"), query)
        return lines
    }

    // A line as text with what it shows on hover in braces and the game's names as their keys, so a test
    // reads what a player can find on the line without a client to translate it.
    private fun flat(component: AdventureComponent): String = buildString {
        fun walk(c: AdventureComponent) {
            when (c) {
                is net.kyori.adventure.text.TextComponent -> append(c.content())
                is net.kyori.adventure.text.TranslatableComponent -> append(c.key())
                else -> {}
            }
            c.children().forEach(::walk)
            (c.hoverEvent()?.value() as? AdventureComponent)?.let { append("{"); walk(it); append("}") }
        }
        walk(component)
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
        assertTrue(lines.any { it.contains("- block.minecraft.tnt") && it.contains("minecraft:stone → minecraft:air") }, "$lines")
        assertTrue(lines.any { it.contains("+ placed") && it.contains("minecraft:air → minecraft:stone") }, "$lines")
        assertTrue(lines.any { it.contains("+ placed") && it.contains("∅") }, "the item plane is still there: $lines")
        // Newest first across both planes; rows sharing an instant keep the item plane ahead of the
        // block plane, which is the order the two were read in and is stable.
        assertEquals(
            listOf("- block.minecraft.tnt{blk_tnt}", "- block.minecraft.tnt{blk_tnt}", "+ placed{block_place}", "+ placed{blk_player_place}"),
            lines.drop(1).map { line -> line.trim().split("  ")[2] },
            "$lines",
        )
    }

    // Both halves of a mutation face the Void, so a reader who is not told they are one event sees an
    // item destroyed and an unrelated item created at the same instant — the very reading the shared
    // transaction exists to deny.
    @Test
    fun `a mutation says the item changed in place`() {
        val anvil = Container(world, 10, 64, -3, 0)
        val worn = byteArrayOf(11)
        val repaired = byteArrayOf(12)
        shared.submit(
            listOf(
                Transfer(Cause.ANVIL_COMBINE, anvil, Void, worn, null, 1, T0, kind = Kind.MUTATE),
                Transfer(Cause.ANVIL_COMBINE, Void, anvil, repaired, null, 1, T0, kind = Kind.MUTATE),
            )
        )
        shared.drain()

        val lines = said(LookupQuery(causes = Action.TRANSFORM.causes))

        assertEquals(2, lines.count { it.contains("block.minecraft.anvil") }, "$lines")
        assertTrue(lines.all { !it.contains("block.minecraft.anvil") || it.contains("changed in place") }, "$lines")
    }

    // The filter names every bench at once, so a row written by one of them must not be reachable
    // under a filter that means something else.
    @Test
    fun `a transformation is not answered by an unrelated filter`() {
        val anvil = Container(world, 10, 64, -3, 0)
        shared.submit(
            listOf(
                Transfer(Cause.ANVIL_COMBINE, anvil, Void, byteArrayOf(11), null, 1, T0, kind = Kind.MUTATE),
                Transfer(Cause.ANVIL_COMBINE, Void, anvil, byteArrayOf(12), null, 1, T0, kind = Kind.MUTATE),
            )
        )
        shared.drain()

        assertTrue(
            said(LookupQuery(causes = Action.LOOT.causes)).none { it.contains("block.minecraft.anvil") },
            "${said(LookupQuery(causes = Action.LOOT.causes))}",
        )
    }

    // A cow killed where somebody is standing is part of what happened there, and so is who did it.
    @Test
    fun `an entity row reads beside the block rows and answers to its type`() {
        val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        log.submit(
            listOf(
                io.pfaumc.pfauprotect.storage.EntityChange(
                    10, 64, -3, io.pfaumc.pfauprotect.model.EntityKind.REMOVED, Cause.BLK_TNT, "minecraft:cow",
                    UUID.randomUUID(), T0, actor = alice, before = byteArrayOf(10, 0, 0, 0),
                )
            )
        )
        log.drain()

        val lines = said(players = mapOf("Alice" to alice))
        assertTrue(lines.any { it.contains("entity.minecraft.cow") && it.contains("Alice") }, "$lines")
        assertTrue(said(LookupQuery(excluded = listOf("cow"))).none { it.contains("entity.minecraft.cow") })
    }

    @Test
    fun `a block row says when nobody could be named`() {
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_LIQUID_DESTROY, T0)))
        log.drain()

        assertTrue(said().any { it.contains("  nobody  ") }, "${said()}")
    }

    // A region scan reads whole chunks, so it answers with rows a radius does not cover. The block
    // plane filters itself to the box, and two planes disagreeing about what one radius means inside
    // one answer reads as rows appearing and vanishing for no reason.
    @Test
    fun `a radius holds the item plane to the same box as the block plane`() {
        val inside = WorldBlock(world, 11, 64, -4)
        val sameChunkOutsideBox = WorldBlock(world, 14, 64, -12)
        shared.submit(Transfer(Cause.BLOCK_PLACE, Void, inside, stone, null, 1, T0))
        shared.submit(Transfer(Cause.BLOCK_PLACE, Void, sameChunkOutsideBox, stone, null, 1, T0 + 1))
        log.submit(listOf(BlockChange(11, 64, -4, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0)))
        log.submit(listOf(BlockChange(14, 64, -12, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0 + 1)))
        shared.drain()
        log.drain()

        val lines = said(LookupQuery(radius = 2))
        assertTrue(lines.any { it.contains("11 64 -4") }, "$lines")
        assertTrue(lines.none { it.contains("14 64 -12") }, "$lines")
    }

    // One explosion is one transaction over a whole crater. Answering a question about one position
    // with every other position it took out is worse than answering with the row that was asked for.
    @Test
    fun `a transaction far too big for one position is not pulled in behind it`() {
        val here = WorldBlock(world, 10, 64, -3)
        val crater = (1..12).map { WorldBlock(world, 10 + it, 64, -3) }
        shared.submit((listOf(here) + crater).map {
            Transfer(Cause.BLK_TNT, it, Void, stone, null, 1, T0)
        })
        shared.drain()

        val byPosition = shared.holderEntries(here, 0, Long.MAX_VALUE)
        assertEquals(byPosition, wholeTransactions(shared, byPosition))
        assertTrue(said().none { it.contains("11 64 -3") }, "${said()}")
    }

    // Keys inside a chunk sort by position before time, so a chunk read up to a limit hands back one
    // corner of itself. The asked-for position was in the crater and not in that corner, and the
    // answer was silence about it. It is also older than everything in the corner, so a box applied
    // after the newest rows of the whole chunk were cut would find nothing left of it.
    @Test
    fun `a busy corner of the chunk does not hide the position that was asked about`() {
        val corner = WorldBlock(world, 15, 64, -15)
        val here = WorldBlock(world, 10, 64, -3)
        shared.submit(Transfer(Cause.BLK_TNT, here, Void, stone, null, 1, T0))
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0)))
        repeat(40) { i ->
            shared.submit(Transfer(Cause.BLK_TNT, corner, Void, stone, null, 1, T0 + 1 + i))
            log.submit(listOf(BlockChange(15, 64, -15, STONE, AIR, Cause.BLK_TNT, T0 + 1 + i)))
        }
        shared.drain()
        log.drain()

        // Two rows asked for, eight times that read: far fewer than the corner holds.
        val lines = said(LookupQuery(radius = 1, limit = 2)).filter { it.contains("10 64 -3") }

        assertTrue(lines.any { it.contains("∅") }, "the item plane lost it: $lines")
        assertTrue(lines.any { it.contains("event:") }, "the block plane lost it: $lines")
    }

    // A read that stopped early answers about what it saw, not about what is there. Saying "no
    // entries" for a position it never reached clears somebody of what the rows behind the cut say.
    @Test
    fun `an empty answer says so when the read stopped early`() {
        val nextDoor = WorldBlock(world, 11, 64, -3)
        repeat(40) { i ->
            shared.submit(Transfer(Cause.BLK_TNT, nextDoor, Void, stone, null, 1, T0 + i))
        }
        shared.drain()

        // Forty rows inside the box, more than one row's worth of read, and none of them the kind asked for.
        val lines = said(LookupQuery(radius = 1, limit = 1, causes = setOf(Cause.BLOCK_PLACE)))
        assertTrue(lines.any { it.contains("stopped before the whole area") }, "$lines")
        assertTrue(lines.none { it.contains("nothing recorded") }, "$lines")
    }

    // A filter by action drops the rollback rows themselves, and a mark read among what the filter kept
    // never saw that the kill and the burn were rolled back.
    @Test
    fun `a rolled back row keeps its mark under a filter that leaves the rollback out`() {
        val cat = UUID.randomUUID()
        log.submit(
            listOf(
                io.pfaumc.pfauprotect.storage.EntityChange(
                    10, 64, -3, io.pfaumc.pfauprotect.model.EntityKind.REMOVED, Cause.ENTITY_KILLED, "minecraft:cat",
                    cat, T0, before = byteArrayOf(10, 0, 0, 0),
                )
            )
        )
        log.submit(listOf(BlockChange(10, 65, -3, STONE, AIR, Cause.BLK_FIRE_BURN, T0)))
        log.submit(
            listOf(
                io.pfaumc.pfauprotect.storage.EntityChange(
                    10, 64, -3, io.pfaumc.pfauprotect.model.EntityKind.CREATED, Cause.ROLLBACK, "minecraft:cat",
                    cat, T0 + 1000, after = byteArrayOf(10, 0, 0, 0),
                )
            )
        )
        log.submit(listOf(BlockChange(10, 65, -3, AIR, STONE, Cause.ROLLBACK, T0 + 1000)))
        log.drain()

        val kills = said(LookupQuery(radius = 2, causes = Action.KILL.causes))
        assertTrue(kills.any { it.contains("entity.minecraft.cat") && it.contains("rolled back") }, "$kills")
        val burns = said(LookupQuery(radius = 2, causes = Action.FIRE.causes))
        assertTrue(burns.any { it.contains("minecraft:stone") && it.contains("rolled back") }, "$burns")
        assertTrue(said(LookupQuery(radius = 2, causes = Action.KILL.causes, rolledBack = false)).none { it.contains("entity.minecraft.cat") })
    }

    // A read cut short with every matched row on the page has no next page to offer: page 2 starts past
    // them and is empty. It says the read stopped and how to see further instead.
    @Test
    fun `a read cut short with nothing more matched offers no next page`() {
        val nextDoor = WorldBlock(world, 11, 64, -3)
        shared.submit(Transfer(Cause.BLOCK_PLACE, Void, WorldBlock(world, 10, 64, -3), stone, null, 1, T0 + 100))
        repeat(40) { i -> shared.submit(Transfer(Cause.BLK_TNT, nextDoor, Void, stone, null, 1, T0 + i)) }
        shared.drain()

        val lines = said(LookupQuery(radius = 1, limit = 1, causes = setOf(Cause.BLOCK_PLACE)))
        assertTrue(lines.any { it.contains("+ placed") }, "$lines")
        assertTrue(lines.none { it.contains("page:2") }, "$lines")
        assertTrue(lines.any { it.contains("stopped before the whole area") }, "$lines")
    }

    // What a player carried has no position, and a crafting grid is booked to them as an entity. Both
    // are read by whose they are, and only theirs.
    @Test
    fun `a player lookup reads their own slots and their crafting grid and nobody else's`() {
        val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        val bob = UUID.fromString("00000000-0000-4000-8000-0000000000b0")
        val chest = Container(world, 1, 64, 1, 0)
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(alice, 3), stone, null, 5, T0))
        shared.submit(Transfer(Cause.CRAFT_CONSUME, EntitySlot(alice, 1), Void, stone, null, 8, T0 + 1))
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 0), stone, null, 2, T0 + 2))
        shared.drain()

        val lines = said(LookupQuery(players = listOf("Alice")), players = mapOf("Alice" to alice, "Bob" to bob))

        assertTrue(lines.any { it.contains("Alice{inventory, slot 3}") }, "$lines")
        assertTrue(lines.any { it.contains("block.minecraft.crafting_table") && it.contains("Alice{crafting grid, slot 1}") }, "$lines")
        assertTrue(lines.none { it.contains("Bob") }, "$lines")
        assertEquals(
            listOf("Unknown player: Carol"),
            said(LookupQuery(players = listOf("Carol")), players = mapOf("Alice" to alice)),
        )
    }

    // The text of a sign is what was kept for it, and an edit reads as what it said before and after.
    // A payload that is not a sign keeps the marker it always had.
    @Test
    fun `a sign row reads out its text and an edit reads out both`() {
        val registries = ServerRegistries.access
        fun sign(line: String) = payloadOf(
            SignBlockEntity(BlockPos(10, 64, -3), Blocks.OAK_SIGN.defaultBlockState()).apply {
                setText(getText(SignTextSlot.FRONT).asMutable().setLine(0, Component.literal(line)).asImmutable(), SignTextSlot.FRONT)
            },
            registries,
        )
        val sign = "minecraft:oak_sign[rotation=0,waterlogged=false]"
        log.submit(listOf(BlockChange(10, 64, -3, AIR, sign, Cause.BLK_PLAYER_PLACE, T0, payloadAfter = sign("здесь был Боб"))))
        log.submit(
            listOf(
                BlockChange(
                    10, 64, -3, sign, sign, Cause.BLK_PLAYER_PLACE, T0 + 1000,
                    payloadBefore = sign("здесь был Боб"), payloadAfter = sign("здесь была Алиса"),
                )
            )
        )
        log.submit(listOf(BlockChange(10, 64, -2, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0 + 2000, payloadAfter = byteArrayOf(1, 2, 3))))
        log.drain()

        val lines = said(LookupQuery(radius = 1))

        assertTrue(lines.any { it.contains("  \"здесь был Боб\"  ") && it.contains("event:") }, "$lines")
        assertTrue(lines.any { it.contains("\"здесь был Боб\" → \"здесь была Алиса\"") }, "$lines")
        assertTrue(lines.any { it.contains("10 64 -2") && it.contains("with contents") }, "$lines")
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
        assertTrue(lines.any { it.contains("placed") && it.contains("event:") }, "$lines")
        assertTrue(lines.none { it.contains("∅") }, "$lines")
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
            if (method.name == "sendMessage") args?.forEach { if (it is String) lines += it else if (it is AdventureComponent) lines += flat(it) }
            null
        } as CommandSender
        Lookups(stubPlugin(), shared, logs)
            .report(sender, LookupTarget(other, 1, 64, 1, "stone at 1 64 1"), LookupQuery())

        assertTrue(lines.any { it.contains("placed") }, "$lines")
    }

    @Test
    fun `a named box reads as the named box and a bare one does not borrow its name`() {
        val codec = ItemFormCodec(shared.registries, ServerRegistries.access)
        val named = NmsItemStack(Items.SHULKER_BOX).apply {
            set(DataComponents.CUSTOM_NAME, Component.literal("Bank"))
        }
        val bare = NmsItemStack(Items.SHULKER_BOX)
        val at = WorldBlock(world, 10, 64, -3)
        shared.submit(Transfer(Cause.BLOCK_PLACE, Void, at, codec.encode(named).form, null, 1, T0))
        shared.submit(Transfer(Cause.BLOCK_DROP, at, Void, codec.encode(bare).form, null, 1, T0 + 1000))
        shared.drain()

        val lines = said(codec = codec)

        assertTrue(lines.any { it.contains("shulker_box \"Bank\"") }, "$lines")
        assertTrue(lines.any { it.contains("shulker_box") && !it.contains("Bank") }, "the bare one stays bare: $lines")
    }
}
