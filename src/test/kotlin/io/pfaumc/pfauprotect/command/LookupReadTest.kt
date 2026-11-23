package io.pfaumc.pfauprotect.command
import io.pfaumc.pfauprotect.AIR
import io.pfaumc.pfauprotect.STONE
import org.junit.jupiter.api.extension.ExtendWith
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.BothPlanes
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.attribution.Attributed
import io.pfaumc.pfauprotect.api.LookupRow
import io.pfaumc.pfauprotect.storage.EntityChange
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.storage.EntityRow
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.rollback.refusalOf
import org.junit.jupiter.api.Assertions.assertNotNull
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerEnder
import io.pfaumc.pfauprotect.model.PlayerEquip
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.stub
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.capture.block.payloadOf
import io.pfaumc.pfauprotect.capture.block.moved
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignTextSlot
import io.pfaumc.pfauprotect.senderInto
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import net.kyori.adventure.text.Component as AdventureComponent
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import net.minecraft.world.item.ItemStack as NmsItemStack


// Both planes are read through the one command a moderator has. A block plane nothing can read is a
// block plane nobody can check.
@ExtendWith(Vanilla::class)
class LookupReadTest : BothPlanes() {
    private val stone = byteArrayOf(3)
    private val codec by lazy { ItemFormCodec(shared.registries, ServerRegistries.access) }

    private fun said(
        query: LookupQuery = LookupQuery(),
        // Stands in for the server's player cache, which a test has none of.
        players: Map<String, UUID> = emptyMap(),
        target: LookupTarget = LookupTarget(world, 10, 64, -3, "stone at 10 64 -3"),
        asker: Class<out CommandSender> = CommandSender::class.java,
        render: (AdventureComponent) -> String = ::flat,
    ): List<String> {
        val lines = ArrayList<String>()
        val names = players.entries.associate { (name, id) -> id to name }
        Lookups(stub(Plugin::class.java), shared, logs, codec, players::get, names::get)
            .report(senderInto(lines, render, asker), target, query)
        return lines
    }

    // A line as text with what it shows on hover in braces and the game's names as their keys, so a test
    // reads what a player can find on the line without a client to translate it.
    private fun flat(component: AdventureComponent): String = buildString {
        fun walk(c: AdventureComponent) {
            when (c) {
                is TextComponent -> append(c.content())
                is TranslatableComponent -> append(c.key())
                else -> {}
            }
            c.children().forEach(::walk)
            (c.hoverEvent()?.value() as? AdventureComponent)?.let { append("{"); walk(it); append("}") }
        }
        walk(component)
    }

    // [flat], then what each click runs and each strike, which [flat] leaves out.
    private fun marked(component: AdventureComponent): String = flat(component) + buildString {
        fun walk(c: AdventureComponent) {
            c.clickEvent()?.takeIf { it.action() == ClickEvent.Action.RUN_COMMAND }?.let { append(" [run:${(it.payload() as ClickEvent.Payload.Text).value()}]") }
            if (c.decoration(TextDecoration.STRIKETHROUGH) == TextDecoration.State.TRUE) append(" [strike]")
            c.children().forEach(::walk)
        }
        walk(component)
    }

    // Pages count folded runs, so the rows are folded before they are cut: ten blasts are one run on page
    // one, and the placement before them is page two rather than lost between the pages.
    @Test
    fun `a page after a folded run shows the next run`() {
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0)))
        repeat(10) { log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0 + 1000 + it))) }
        log.drain()

        val first = said(LookupQuery(rows = 1))
        assertTrue(first.any { it.contains("block.minecraft.tnt") }, "$first")
        assertTrue(first.none { it.contains("\nblk_player_place}") }, "$first")
        val second = said(LookupQuery(rows = 1, page = 2))
        assertTrue(second.any { it.contains("\nblk_player_place}") }, "$second")
        assertTrue(second.none { it.contains("block.minecraft.tnt") }, "$second")
    }

    // The block plane's read of one place stops at a count before the filter too: forty blasts over the one placement
    // asked for leave it behind the cut, which has to be said rather than answered "nothing recorded".
    @Test
    fun `a block read cut short at one place says so`() {
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0)))
        repeat(40) { log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0 + 1000 + it))) }
        log.drain()

        val lines = said(LookupQuery(rows = 1, causes = setOf(Cause.BLK_PLAYER_PLACE)))
        assertTrue(lines.single().contains("limit:4"), "$lines")
        val counted = said(LookupQuery(rows = 1, causes = setOf(Cause.BLK_PLAYER_PLACE), count = true))
        assertTrue(counted.single().contains("the read stopped early"), "$counted")
    }

    // An area's read stops once it has its rows, and a count of them is a count of what it saw.
    @Test
    fun `an area count cut at its read says so`() {
        repeat(40) { log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0 + it))) }
        log.drain()

        val counted = said(LookupQuery(rows = 1, radius = 1, count = true))
        assertTrue(counted.last().contains("the read stopped early"), "$counted")
    }

    // A read that comes to exactly as many rows as it may take has seen them all, and its page is the last.
    @Test
    fun `a block read of exactly its size is whole`() {
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0)))
        // A page of one row reads 32: the placement and 31 blasts.
        repeat(31) { log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0 + 1000 + it))) }
        log.drain()

        val lines = said(LookupQuery(rows = 1, causes = setOf(Cause.BLK_PLAYER_PLACE)))
        assertTrue(lines.any { it.contains("\nblk_player_place}") } && lines.last().contains("page 1, the last"), "$lines")
    }

    // A clock or a griefer at work writes rows while the pages are read; « has to bring back the page that was left.
    @Test
    fun `a page asked as of a moment holds still while rows keep coming`() {
        repeat(6) { log.submit(listOf(BlockChange(10, 64, -3 - it, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0 + it * 1000L))) }
        log.drain()
        val query = LookupQuery(radius = 5, rows = 2, page = 2, all = true, asOf = T0 + 10_000)
        val before = said(query)

        // The first at the very moment asked: a row of that millisecond can land after the page was read.
        repeat(3) { log.submit(listOf(BlockChange(11, 64, -3 - it, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0 + 10_000 + it))) }
        log.drain()
        assertEquals(before, said(query))
        assertTrue(said(query.copy(asOf = null)) != before, "without the moment the page moves on")
    }

    @Test
    fun `a part reads the contents of a container or its block apart`() {
        val chest = Container(world, 10, 64, -3, 0)
        shared.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 0), chest, stone, null, 1, T0))
        shared.submit(Transfer(Cause.BLOCK_PLACE, PlayerInv(alice, 1), WorldBlock(world, 10, 64, -3), stone, null, 1, T0 + 1))
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0 + 2)))
        shared.drain()
        log.drain()

        val contents = said(LookupQuery(part = Part.CONTENTS))
        assertTrue(contents.any { it.contains("\ncontainer_add}") }, "$contents")
        assertTrue(contents.none { it.contains("\nblk_player_place}") || it.contains("\nblock_place}") }, "$contents")
        val block = said(LookupQuery(part = Part.BLOCK))
        assertTrue(block.none { it.contains("\ncontainer_add}") }, "$block")
        // The item that left the hand is the block line's own, told in its hover.
        assertTrue(block.any { it.contains("\nblk_player_place}") && it.contains("items:") }, "$block")
    }

    @Test
    fun `a position answers with both planes in the order things happened`() {
        val at = WorldBlock(world, 10, 64, -3)
        shared.submit(Transfer(Cause.BLOCK_PLACE, PlayerInv(alice, 1), at, stone, null, 1, T0))
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0)))
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0 + 1000)))
        shared.submit(moved(at, Void, stone, Cause.BLK_TNT, null, T0 + 1000))
        shared.drain()
        log.drain()

        val lines = said()

        assertTrue(lines.any { it.contains("- block.minecraft.tnt") && it.contains("minecraft:stone → minecraft:air") }, "$lines")
        assertTrue(lines.any { it.contains("\nblk_player_place}") && it.contains("minecraft:air → minecraft:stone") }, "$lines")
        // The stone written off where it stood and the one out of the hand are the block rows' own changes, and are
        // not shown twice.
        val causes = listOf("\nblk_tnt}", "\nblock_place}", "\nblk_player_place}")
        val read = lines.mapNotNull { line -> causes.firstOrNull { it in line } }.map { if (it == "\nblk_tnt}") "tnt" else "place" }
        assertEquals(listOf("tnt", "place"), read, "$lines")
    }

    // A break was three lines: the stone gone, the cobblestone on the ground and the block row, read as three things
    // done. The drop is written in an event of its own, a few milliseconds after the block row.
    @Test
    fun `a break reads as one line with what fell out in its hover`() {
        val at = WorldBlock(world, 10, 64, -3)
        val cobblestone = byteArrayOf(4)
        val diamond = byteArrayOf(5)
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0, actor = alice)))
        shared.submit(listOf(
            Transfer(Cause.BLOCK_DROP, at, Void, stone, null, 1, T0 + 30, actor = alice),
            Transfer(Cause.BLOCK_DROP, Void, ItemEntityRef(UUID.randomUUID()), cobblestone, null, 1, T0 + 30, actor = alice),
        ))
        // What a container held spills in a transaction of its own and is what gets looked for: it stays a line.
        shared.submit(Transfer(Cause.CONTAINER_BREAK_DROP, Container(world, 10, 64, -3, 0), ItemEntityRef(UUID.randomUUID()), diamond, null, 3, T0 + 30, actor = alice))
        shared.drain()
        log.drain()

        val lines = said().filter { "\n" in it }
        val broken = lines.single { it.contains("\nblk_player_break}") }
        assertTrue(broken.contains("items:") && broken.contains("fell out of a block"), broken)
        assertTrue(lines.none { it.contains("\nblock_drop}") }, "$lines")
        assertTrue(lines.any { it.contains("\ncontainer_break_drop}") && !it.contains("blk_player_break") }, "$lines")
        // A count and the API read rows, not hovers: the drop is still one of them.
        assertTrue(said(LookupQuery(count = true)).any { it.contains("{block_drop}") })
        val lookups = Lookups(stub(Plugin::class.java), shared, logs, codec, { null }) { null }
        val target = LookupTarget(world, 10, 64, -3, "stone at 10 64 -3")
        val rows = lookups.pageOf(lookups.find(target, LookupQuery()), LookupQuery())
        assertTrue(rows.rows.any { it.cause == "block_drop" && it.amount == 1 }, "${rows.rows}")
    }

    // The planes are marked apart: a break put back whose cobblestone nobody gave back is not one change any more,
    // and the drop under a struck-through line would read as given back too.
    @Test
    fun `a drop a rollback did not give back stays a line beside the block it put back`() {
        val at = WorldBlock(world, 10, 64, -3)
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0, actor = alice)))
        shared.submit(listOf(
            Transfer(Cause.BLOCK_DROP, at, Void, stone, null, 1, T0 + 30, actor = alice),
            Transfer(Cause.BLOCK_DROP, Void, ItemEntityRef(UUID.randomUUID()), byteArrayOf(4), null, 1, T0 + 30, actor = alice),
        ))
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.ROLLBACK, T0 + 5000)))
        shared.drain()
        log.drain()

        val lines = said().filter { "\n" in it }
        assertTrue(lines.single { it.contains("\nblk_player_break}") }.let { "rolled back" in it && "items:" !in it }, "$lines")
        assertTrue(lines.any { it.contains("on the ground") && "rolled back" !in it }, "$lines")
        assertTrue(lines.none { it.contains("on the ground") && it.contains("block.minecraft.stone") }, "the block's own form stays folded: $lines")
    }

    // A placement's item leaves the hand in the recompute pass after its row, so a break right after it is nearer in
    // time; the item belongs to the row that put the block there.
    @Test
    fun `a placement's item goes with the placement, not with the break after it`() {
        val at = WorldBlock(world, 10, 64, -3)
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0 + 500, actor = alice)))
        shared.submit(moved(at, Void, stone, Cause.BLOCK_DROP, Attributed(alice, Confidence.FACT), T0 + 520))
        shared.submit(Transfer(Cause.BLOCK_PLACE, PlayerInv(alice, 1), at, stone, null, 1, T0 + 600, actor = alice))
        shared.drain()
        log.drain()

        val lines = said(LookupQuery(all = true)).filter { "\n" in it }
        assertEquals(2, lines.size, "$lines")
        assertTrue(lines.single { it.contains("\nblk_player_place}") }.contains("items:"), "$lines")
        assertFalse(lines.single { it.contains("\nblk_player_break}") }.contains("items:"), "$lines")
    }

    // One transaction over a crater changes many places: a drop with no place of its own cannot be told to one of them.
    @Test
    fun `a drop of a transaction over many places stays a line`() {
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0), BlockChange(11, 64, -3, STONE, AIR, Cause.BLK_TNT, T0)))
        shared.submit(listOf(
            Transfer(Cause.BLK_TNT, WorldBlock(world, 10, 64, -3), Void, stone, null, 1, T0),
            Transfer(Cause.BLK_TNT, WorldBlock(world, 11, 64, -3), Void, stone, null, 1, T0),
            Transfer(Cause.BLK_TNT, Void, ItemEntityRef(UUID.randomUUID()), byteArrayOf(4), null, 1, T0),
        ))
        shared.drain()
        log.drain()

        val lines = said()
        assertTrue(lines.any { it.contains("on the ground") }, "$lines")
        assertTrue(lines.none { it.contains("items:") }, "$lines")
    }

    // A world-wide lookup of a player goes to every place they touched, however far apart, and at each
    // shows only their rows.
    @Test
    fun `a world-wide lookup of a player reads their places and only their rows there`() {
        log.submit(listOf(BlockChange(1000, 64, 1000, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(-2000, 70, 50, AIR, "minecraft:dirt", Cause.BLK_PLAYER_PLACE, T0 + 1000, actor = alice)))
        log.submit(listOf(BlockChange(1000, 64, 1000, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0 + 2000, actor = bob)))
        log.drain()

        val lines = said(LookupQuery(users = listOf("Alice"), radius = -1), players = mapOf("Alice" to alice, "Bob" to bob))

        assertTrue(lines.any { it.contains("minecraft:air → minecraft:stone") && it.contains("Alice") }, "$lines")
        assertTrue(lines.any { it.contains("minecraft:air → minecraft:dirt") && it.contains("Alice") }, "$lines")
        assertTrue(lines.none { it.contains("Bob") || it.contains("minecraft:stone → minecraft:air") }, "$lines")
    }

    // The index was once walked oldest first and cut at a few thousand places, so a busy player's first page
    // showed what they did months ago. The read is newest first and stops at the page.
    @Test
    fun `a world-wide lookup of a busy player starts at their newest row, rolled back or not`() {
        repeat(5000) { log.submit(listOf(BlockChange(it, 64, 0, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0 + it, actor = alice))) }
        log.submit(listOf(BlockChange(-7, 64, 0, AIR, "minecraft:dirt", Cause.BLK_PLAYER_PLACE, T0 + 9000, actor = alice)))
        log.submit(listOf(BlockChange(-7, 64, 0, "minecraft:dirt", AIR, Cause.ROLLBACK, T0 + 9500)))
        log.drain()

        val lines = said(LookupQuery(users = listOf("Alice"), radius = -1, all = true), players = mapOf("Alice" to alice))

        assertTrue(lines.first { " → " in it }.let { "minecraft:air → minecraft:dirt" in it && "rolled back" in it }, "$lines")
    }

    // Each page was cut from a read of its own depth: the drops of an earlier page's breaks and the rows under a long
    // folded run came and went with it, and whole minutes of a player fell between two pages.
    @Test
    fun `paging through a player's lookup shows every line once`() {
        val by = Attributed(alice, Confidence.FACT)
        // Two long runs, one in each plane, each longer than a first page reads of it.
        repeat(100) { shared.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 0), Container(world, 50, 64, 0, 0), stone, null, 1, T0 + 200_000 + it)) }
        repeat(30) { log.submit(listOf(BlockChange(it, 70, 0, STONE, AIR, Cause.BLK_FIRE_BURN, T0 + 100_000 + it, actor = alice))) }
        // And a blast of one millisecond in both planes, which only `#all` pages through row by row.
        repeat(10) {
            log.submit(listOf(BlockChange(it, 80, 0, STONE, AIR, Cause.BLK_TNT, T0 + 300_000, actor = alice)))
            log.submit(listOf(EntityChange(it, 80, 1, EntityKind.REMOVED, Cause.ENTITY_KILLED, "minecraft:cat", UUID.randomUUID(), T0 + 300_000, actor = alice)))
        }
        repeat(12) {
            val ts = T0 + it * 1000L
            log.submit(listOf(BlockChange(it, 64, 0, STONE, AIR, Cause.BLK_PLAYER_BREAK, ts, actor = alice)))
            shared.submit(moved(WorldBlock(world, it, 64, 0), ItemEntityRef(UUID.randomUUID()), stone, Cause.BLOCK_DROP, by, ts))
            shared.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 0), Container(world, 50, 64, 0, 0), stone, null, 1, ts + 500))
        }
        log.drain()
        shared.drain()
        val lookups = Lookups(stub(Plugin::class.java), shared, logs, codec, mapOf("Alice" to alice)::get) { null }
        fun page(query: LookupQuery) = lookups.pageOf(lookups.findAllWorlds(query), query)
        fun keys(rows: List<LookupRow>) = rows.map { listOf(it.time, it.cause, it.x, it.y, it.z, it.thing, it.repeats) }

        for (all in listOf(false, true)) {
            val whole = page(LookupQuery(users = listOf("Alice"), rows = 200, all = all))
            assertFalse(whole.more)
            val paged = ArrayList<LookupRow>()
            var n = 1
            do {
                val next = page(LookupQuery(users = listOf("Alice"), rows = 2, page = n++, all = all))
                paged += next.rows
            } while (next.more && n < 200)
            assertEquals(keys(whole.rows), keys(paged), "all $all")
        }
    }

    // A rollback puts a leaf back counting its distance anew, and a window that ended before the rollback still
    // shows the row as put back: a wither's leaves read as never rolled back.
    @Test
    fun `a row a rollback put back after the window is marked, a leaf by the state it was given`() {
        log.submit(listOf(BlockChange(10, 64, -3, "minecraft:oak_leaves[distance=3,persistent=false,waterlogged=false]", AIR, Cause.BLK_WITHER, T0, actor = alice)))
        log.submit(listOf(BlockChange(10, 64, -3, AIR, "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]", Cause.ROLLBACK, T0 + 60_000)))
        log.drain()
        val window = LookupQuery(fromMillis = T0 - 1000, untilMillis = T0 + 1000)

        val here = said(window)
        assertTrue(here.any { "\nblk_wither}" in it && "rolled back" in it }, "$here")
        val theirs = said(window.copy(users = listOf("Alice"), radius = -1), players = mapOf("Alice" to alice))
        assertTrue(theirs.any { "\nblk_wither}" in it && "rolled back" in it }, "$theirs")
        // A page pinned to a moment sees no rollback from that moment on.
        for (query in listOf(window, window.copy(users = listOf("Alice"), radius = -1))) {
            val before = said(query.copy(asOf = T0 + 60_000), players = mapOf("Alice" to alice))
            assertTrue(before.any { "\nblk_wither}" in it && "rolled back" !in it }, "$before")
            val after = said(query.copy(asOf = T0 + 60_001), players = mapOf("Alice" to alice))
            assertTrue(after.any { "\nblk_wither}" in it && "rolled back" in it }, "$after")
        }
    }

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
        assertTrue(said(LookupQuery(causes = Action.LOOT.causes)).none { it.contains("block.minecraft.anvil") })
    }

    // A cow killed where somebody is standing is part of what happened there, and so is who did it.
    @Test
    fun `an entity row reads beside the block rows and answers to its type`() {
        log.submit(
            listOf(
                EntityChange(
                    10, 64, -3, EntityKind.REMOVED, Cause.BLK_TNT, "minecraft:cow",
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
    fun `a run of the same change by two players reads as a line for each`() {
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0, actor = alice)))
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0 + 2000, actor = bob)))
        log.drain()

        val broken = said(players = mapOf("Alice" to alice, "Bob" to bob)).filter { it.contains("minecraft:stone → minecraft:air") }
        assertEquals(2, broken.size, "$broken")
        assertTrue(broken.any { it.contains("Alice") } && broken.any { it.contains("Bob") }, "$broken")
    }

    // A block row is about both states it ran between: what was broken is named by its before-state.
    @Test
    fun `a rollback's item filter names a block by either state and an item by its type`() {
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0, actor = alice)))
        log.drain()
        val chest = Container(world, 11, 64, -3, 0)
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(alice, 0), codec.encode(NmsItemStack(Items.DIAMOND)).form, null, 1, T0))
        shared.drain()
        val broken = log.at(10, 64, -3, 0, Long.MAX_VALUE, limit = 10, reverse = false).single()
        val taken = shared.holderEntries(chest, 0, Long.MAX_VALUE).single()
        fun filter(words: String) = RowFilter(shared, parseLookupQuery(words), emptyList(), emptyMap(), rollback = true)

        assertTrue(filter("include:stone").keeps(broken))
        assertFalse(filter("exclude:stone").keeps(broken))
        assertTrue(filter("exclude:stone").keeps(taken))
        assertFalse(filter("exclude:diamond").keeps(taken))
    }

    // inventory/item read the direction across a player's hands from both ends of a row, whichever side wrote it.
    @Test
    fun `crossing actions are decided per row from both ends`() {
        val diamond = codec.encode(NmsItemStack(Items.DIAMOND)).form
        val chest = Container(world, 11, 64, -3, 0)
        val drop = ItemEntityRef(UUID.randomUUID())
        val grid = EntitySlot(alice, 1)
        shared.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 0), chest, diamond, null, 1, T0))
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 0), diamond, null, 1, T0 + 1))
        shared.submit(Transfer(Cause.QUICK_MOVE, PlayerInv(alice, 0), PlayerInv(alice, 5), diamond, null, 1, T0 + 2))
        shared.submit(Transfer(Cause.BLOCK_PLACE, PlayerInv(alice, 0), WorldBlock(world, 1, 64, 1), diamond, null, 1, T0 + 3))
        shared.submit(Transfer(Cause.DROP_FROM_HAND, PlayerInv(alice, 0), drop, diamond, null, 1, T0 + 4))
        shared.submit(Transfer(Cause.PICKUP, drop, PlayerInv(bob, 1), diamond, null, 1, T0 + 5))
        shared.submit(Transfer(Cause.CRAFT_CONSUME, grid, Void, diamond, null, 1, T0 + 6))
        shared.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 0), PlayerEnder(alice, 3), diamond, null, 1, T0 + 7))
        shared.submit(Transfer(Cause.CONTAINER_ADD, PlayerInv(alice, 2), Nested(UUID.randomUUID(), 0), diamond, null, 1, T0 + 8))
        shared.drain()
        fun filter(words: String, isPlayer: (UUID) -> Boolean = { false }) = RowFilter(shared, parseLookupQuery(words), isPlayer = isPlayer)
        // A slot-0 read covers every slot, so one move can answer with both its halves.
        fun RowFilter.on(ts: Long, holder: Holder) = shared.holderEntries(holder, 0, Long.MAX_VALUE).filter { it.timestamp == ts }.any(::keeps)

        // The deposit, from the player's side and from the container's.
        assertTrue(filter("a:-inventory").on(T0, PlayerInv(alice, 0)))
        assertTrue(filter("a:-inventory").on(T0, chest))
        assertFalse(filter("a:+inventory").on(T0, chest))
        // The withdrawal comes in.
        assertTrue(filter("a:+inventory").on(T0 + 1, PlayerInv(bob, 0)))
        assertTrue(filter("a:+inventory").on(T0 + 1, chest))
        // Within one player, and a build, are not crossings.
        assertFalse(filter("a:inventory").on(T0 + 2, PlayerInv(alice, 5)))
        assertFalse(filter("a:inventory").on(T0 + 3, PlayerInv(alice, 0)))
        // `item` leaves containers out, not the ground.
        assertFalse(filter("a:-item").on(T0, PlayerInv(alice, 0)))
        assertTrue(filter("a:-item").on(T0 + 4, PlayerInv(alice, 0)))
        assertTrue(filter("a:+item").on(T0 + 5, PlayerInv(bob, 1)))
        // An ender chest is a container for `item` too, and a bundle in the inventory is no crossing at all.
        assertFalse(filter("a:-item").on(T0 + 7, PlayerInv(alice, 0)))
        assertTrue(filter("a:-inventory").on(T0 + 7, PlayerInv(alice, 0)))
        assertFalse(filter("a:-inventory").on(T0 + 8, PlayerInv(alice, 2)))
        assertFalse(filter("a:-item").on(T0 + 8, PlayerInv(alice, 2)))
        // An entity slot is a crafting grid only when a player owns it.
        assertFalse(filter("a:-inventory").on(T0 + 6, grid))
        assertTrue(filter("a:-inventory", isPlayer = { it == alice }).on(T0 + 6, grid))
        assertFalse(filter("e:-inventory").on(T0, PlayerInv(alice, 0)))
        assertTrue(filter("e:-inventory").on(T0 + 5, PlayerInv(bob, 1)))

        // Every player death is a kill; a fight between players is not a fall.
        assertTrue(Cause.PLAYER_DIED in parseLookupQuery("a:kill").causes.orEmpty())
        assertFalse(Cause.PLAYER_DIED in parseLookupQuery("a:pvp").causes.orEmpty())
        assertTrue(parseLookupQuery("a:-inv").crosses)
        assertTrue(parseLookupQuery("a:+inventory").crosses)
        assertFalse(parseLookupQuery("a:drop").crosses)
        assertNotNull(refusalOf(parseLookupQuery("t:1h r:5 a:-inventory")))
    }

    // `&` asks one row for all of a group: a block by both its states, a kill by who did it and to whom, an
    // entity by two actions at once.
    @Test
    fun `an ampersand group holds only when one row answers every word of it`() {
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0, actor = alice)))
        log.drain()
        val broken = log.at(10, 64, -3, 0, Long.MAX_VALUE, limit = 10, reverse = false).single()
        val kill = EntityRow(1, 64, 1, T0, 2, 0, EntityKind.PLAYER_DIED, Cause.PLAYER_KILLED, "minecraft:player", bob, Confidence.FACT, alice)
        val blown = EntityRow(1, 64, 1, T0, 3, 0, EntityKind.REMOVED, Cause.BLK_CREEPER, "minecraft:item_frame", UUID.randomUUID(), Confidence.FACT)
        fun filter(words: String, vararg users: Set<UUID>) = RowFilter(shared, parseLookupQuery(words), users.toList())

        assertTrue(filter("i:stone&air").keeps(broken))
        assertFalse(filter("i:stone&dirt").keeps(broken))
        assertTrue(filter("i:stone&dirt,air").keeps(broken))
        assertFalse(filter("e:stone&air").keeps(broken))
        assertTrue(filter("e:stone&dirt").keeps(broken))

        assertTrue(filter("", setOf(alice, bob)).keeps(kill))
        // Alone, the one killed is not who did it.
        assertFalse(filter("", setOf(bob)).keeps(kill))
        assertFalse(filter("", setOf(alice, bob)).keeps(broken))
        assertTrue(filter("", setOf(alice, bob), setOf(alice)).keeps(broken))

        assertTrue(filter("a:-entity&explosion").keeps(blown))
        assertFalse(filter("a:-entity&fire").keeps(blown))
        assertFalse(filter("a:+entity&explosion").keeps(blown))

        // `exclude:` takes an action as a word of its own, and beside a player.
        fun without(words: String) = RowFilter(shared, parseLookupQuery(words), excludedPlayers = mapOf("alice" to alice, "bob" to bob))
        assertFalse(without("e:explosion").keeps(blown))
        assertTrue(without("e:fire").keeps(blown))
        // A word that names a thing is the thing: leaving out TNT the item is not leaving out every explosion.
        assertTrue(without("e:tnt,creeper").keeps(blown))
        assertFalse(without("e:item_frame").keeps(blown))
        log.submit(listOf(BlockChange(12, 64, -3, STONE, AIR, Cause.BLK_FIRE_BURN, T0)))
        log.drain()
        val burnt = log.at(12, 64, -3, 0, Long.MAX_VALUE, limit = 10, reverse = false).single()
        assertFalse(without("e:burn").keeps(burnt))
        // `fire` is the block, which this row never held.
        assertTrue(without("e:fire").keeps(burnt))
        assertFalse(without("e:-block").keeps(broken))
        assertFalse(without("e:Alice&-block").keeps(broken))
        assertTrue(without("e:Bob&-block").keeps(broken))
    }

    @Test
    fun `a block row says when nobody could be named`() {
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_LIQUID_DESTROY, T0)))
        log.drain()

        val lines = said()
        assertTrue(lines.single { it.contains("minecraft:stone → minecraft:air") }.contains("nobody"), "$lines")
    }

    // A chest's own placer emptying it is no theft. Somebody else's chest is, and so is one nobody put
    // down, a chest of the world's own, since its whole history was read and nobody owns it.
    @Test
    fun `a theft is a withdrawal from a chest the taker did not put down`() {
        log.submit(listOf(BlockChange(11, 64, -3, AIR, "minecraft:chest", Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(12, 64, -3, AIR, "minecraft:chest", Cause.BLK_PLAYER_PLACE, T0, actor = bob)))
        log.drain()
        for (x in 11..13) shared.submit(Transfer(Cause.CONTAINER_REMOVE, Container(world, x, 64, -3, 0), PlayerInv(alice, 0), stone, null, 1, T0 + 10))
        shared.drain()

        val lines = said(parseLookupQuery("action:steal radius:3"))
        assertTrue(lines.none { it.contains("11 64 -3") }, "$lines")
        assertTrue(lines.any { it.contains("12 64 -3") }, "$lines")
        assertTrue(lines.any { it.contains("13 64 -3") }, "$lines")
    }

    // Ctrl+Q over somebody else's chest empties it onto the ground as surely as a click into the inventory, and a
    // griefer who throws and picks up must not slip past `steal`. A throw out of one's own slots is no container's.
    @Test
    fun `a throw out of somebody else's chest is a withdrawal and a theft, one out of the hands is not`() {
        log.submit(listOf(BlockChange(12, 64, -3, AIR, "minecraft:chest", Cause.BLK_PLAYER_PLACE, T0, actor = bob)))
        log.drain()
        val diamond = codec.encode(NmsItemStack(Items.DIAMOND)).form
        val spot = WorldBlock(world, 12, 64, -3)
        shared.submit(Transfer(Cause.CONTAINER_THROW, Container(world, 12, 64, -3, 1), ItemEntityRef(UUID.randomUUID()), diamond, null, 5, T0 + 10, actor = alice, spot = spot))
        shared.submit(Transfer(Cause.DROP_FROM_MENU, PlayerInv(alice, 0), ItemEntityRef(UUID.randomUUID()), diamond, null, 7, T0 + 20, actor = alice, spot = spot))
        shared.drain()

        for (asked in listOf("action:steal", "action:-container", "action:container")) {
            val lines = said(parseLookupQuery("$asked radius:3"))
            assertTrue(lines.any { "×5" in it }, "$asked: $lines")
            assertTrue(lines.none { "×7" in it }, "$asked: $lines")
        }
        val dropped = said(parseLookupQuery("action:drop radius:3"))
        assertTrue(dropped.any { "×5" in it } && dropped.any { "×7" in it }, "$dropped")
    }

    // A shift-click into a chest is filed as the click and a hotbar key over a chest's slot as the swap; both are as much
    // a deposit and a withdrawal as a click with the cursor, and a theft is no less one for being done by a key.
    @Test
    fun `a click filed as itself is a deposit or a withdrawal by which end the chest is`() {
        log.submit(listOf(BlockChange(12, 64, -3, AIR, "minecraft:chest", Cause.BLK_PLAYER_PLACE, T0, actor = bob)))
        log.drain()
        val diamond = codec.encode(NmsItemStack(Items.DIAMOND)).form
        shared.submit(Transfer(Cause.QUICK_MOVE, PlayerInv(alice, 0), Container(world, 12, 64, -3, 1), diamond, null, 3, T0 + 10))
        shared.submit(Transfer(Cause.HOTBAR_SWAP, Container(world, 12, 64, -3, 2), PlayerInv(alice, 1), diamond, null, 5, T0 + 20))
        shared.drain()

        for ((asked, put, taken) in listOf(
            Triple("action:+container", true, false), Triple("action:-container", false, true),
            Triple("action:steal", false, true), Triple("action:container", true, true),
        )) {
            val lines = said(parseLookupQuery("$asked radius:3"))
            assertEquals(put, lines.any { "×3" in it }, "$asked: $lines")
            assertEquals(taken, lines.any { "×5" in it }, "$asked: $lines")
        }
    }

    // A throw out of somebody's chest names the thrower only as its actor, and a player's lookup without a radius
    // reads their own slots and the places of their blocks: the actor index is what leads it to the throw.
    @Test
    fun `a player's lookup without a radius finds their throw out of a chest`() {
        val diamond = codec.encode(NmsItemStack(Items.DIAMOND)).form
        shared.submit(Transfer(Cause.CONTAINER_THROW, Container(world, 12, 64, -3, 1), ItemEntityRef(UUID.randomUUID()), diamond, null, 5, T0, actor = alice, spot = WorldBlock(world, 12, 64, -3)))
        shared.drain()
        val players = mapOf("Alice" to alice, "Bob" to bob)

        val lines = said(parseLookupQuery("user:Alice radius:global action:-container"), players = players)
        assertTrue(lines.any { "item.minecraft.diamond" in it && "×5" in it }, "$lines")
        assertTrue(said(parseLookupQuery("user:Bob radius:global"), players = players).none { "×5" in it })
    }

    // A player's slots were read to a count and filtered after: a busy afternoon filled the count with what was not
    // asked for, and the one withdrawal of a diamond behind it was answered as nothing, with the read called whole.
    @Test
    fun `a player's lookup for one item finds it behind many newer rows of others`() {
        val diamond = codec.encode(NmsItemStack(Items.DIAMOND)).form
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, Container(world, 11, 64, -3, 0), PlayerInv(alice, 0), diamond, null, 3, T0))
        repeat(200) { shared.submit(Transfer(Cause.PICKUP, ItemEntityRef(UUID.randomUUID()), PlayerInv(alice, 1), stone, null, 1, T0 + 10 + it)) }
        shared.drain()

        val lines = said(LookupQuery(users = listOf("Alice"), radius = -1, included = listOf("diamond"), rows = 1, all = true), players = mapOf("Alice" to alice))

        assertTrue(lines.any { "item.minecraft.diamond" in it && "×3" in it }, "$lines")
    }

    @Test
    fun `a span leaves out what came after its near end`() {
        val now = System.currentTimeMillis()
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, now - 2 * 86_400_000L)))
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_PLAYER_BREAK, now - 43_200_000L)))
        log.drain()

        val lines = said(parseLookupQuery("time:3d-1d"))
        assertTrue(lines.any { it.contains("air → minecraft:stone") }, "$lines")
        assertTrue(lines.none { it.contains("stone → minecraft:air") }, "$lines")
    }

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

    // A drop has no holder with a position; its spot finds it by radius, and the item entity's mirrored half
    // that the transaction pulls in must not print the same movement a second time.
    @Test
    fun `a drop with a spot is found by a radius and printed once with its place`() {
        val drop = Transfer(
            Cause.DROP_FROM_HAND, PlayerInv(alice, 3), ItemEntityRef(UUID.randomUUID()), stone, null, 1, T0,
            actor = alice, spot = WorldBlock(world, 11, 64, -4),
        )
        shared.submit(drop)
        shared.drain()

        val lines = said(LookupQuery(radius = 2, causes = setOf(Cause.DROP_FROM_HAND)))
        assertEquals(1, lines.count { it.contains("11 64 -4") }, "$lines")
        assertTrue(said(LookupQuery(radius = 2, causes = setOf(Cause.DROP_FROM_HAND), page = 1), target = LookupTarget(world, 100, 64, 100, "far")).none { it.contains("11 64 -4") })
    }

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
        val lines = said()
        assertTrue(lines.any { it.contains("10 64 -3") && it.contains("\nblk_tnt}") }, "$lines")
        assertTrue(lines.none { it.contains("11 64 -3") }, "$lines")
    }

    // Keys inside a chunk sort by position before time, so a chunk read up to a limit hands back one
    // corner of itself. The asked-for position was in the crater and not in that corner, and the
    // answer was silence about it. It is also older than everything in the corner, so a box applied
    // after the newest rows of the whole chunk were cut would find nothing left of it.
    @Test
    fun `a busy corner of the chunk does not hide the position that was asked about`() {
        val corner = WorldBlock(world, 15, 64, -15)
        val here = WorldBlock(world, 10, 64, -3)
        shared.submit(Transfer(Cause.BLK_TNT, here, ItemEntityRef(UUID.randomUUID()), stone, null, 1, T0))
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0)))
        repeat(40) { i ->
            shared.submit(Transfer(Cause.BLK_TNT, corner, ItemEntityRef(UUID.randomUUID()), stone, null, 1, T0 + 1 + i))
            log.submit(listOf(BlockChange(15, 64, -15, STONE, AIR, Cause.BLK_TNT, T0 + 1 + i)))
        }
        shared.drain()
        log.drain()

        // Two rows asked for, eight times that read: far fewer than the corner holds.
        val lines = said(LookupQuery(radius = 1, rows = 2)).filter { it.contains("10 64 -3") }

        assertTrue(lines.any { it.contains("on the ground") }, "the item plane lost it: $lines")
        assertTrue(lines.any { it.contains("event:") }, "the block plane lost it: $lines")
    }

    // The console is given an event to type; a player who may not roll back has nothing to do with one.
    @Test
    fun `a player who may not roll back is not shown the event`() {
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0)))
        log.drain()

        assertTrue(said().any { it.contains("event:") }, "the console lost it")
        val lines = said(asker = Player::class.java)
        assertTrue(lines.any { it.contains("placed") }, "$lines")
        assertTrue(lines.none { it.contains("event:") }, "$lines")
    }

    // A player without /pp tp still clicks: the arrows and the strike are theirs, the words to type are the console's.
    @Test
    fun `a player who may not teleport gets the page arrows and the strike`() {
        val chest = Container(world, 10, 64, -3, 0)
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(alice, 0), stone, null, 4, T0))
        shared.drain()
        val theft = shared.holderEntries(chest, 0, Long.MAX_VALUE).single()
        shared.submit(Transfer(Cause.ROLLBACK, Void, chest, stone, null, 4, T0 + 1000, reverts = listOf(theft.ref)))
        shared.drain()

        assertTrue(said(LookupQuery(rows = 1)).last().contains("page:2"), "the console lost the words")
        assertTrue(said().any { it.contains("(rolled back)") }, "the console lost the words")
        val paged = said(LookupQuery(rows = 1), asker = Player::class.java, render = ::marked)
        assertTrue(paged.last().let { "»" in it && Regex("\\[run:/pp l [^]]* page:2]") in it && "older rows" !in it }, "$paged")
        val lines = said(asker = Player::class.java, render = ::marked)
        assertTrue(lines.single { it.contains("\ncontainer_remove}") }.let { "[strike]" in it && "(rolled back)" !in it }, "$lines")
    }

    // One place asks its filter of each row as it reads, as a radius does, so forty rows of another kind newer
    // than the one asked for no longer use the read up and leave it behind a cut.
    @Test
    fun `one place reads past the rows its filter drops`() {
        val here = WorldBlock(world, 10, 64, -3)
        shared.submit(Transfer(Cause.BLOCK_PLACE, Void, here, stone, null, 1, T0))
        repeat(40) { i -> shared.submit(Transfer(Cause.BLK_TNT, here, Void, stone, null, 1, T0 + 1 + i)) }
        shared.drain()

        val lines = said(LookupQuery(rows = 1, causes = setOf(Cause.BLOCK_PLACE)))
        assertTrue(lines.any { it.contains("\nblock_place}") }, "$lines")
        assertTrue(lines.none { it.contains("limit:4") || it.contains("page:2") }, "$lines")
    }

    // Rows the filter keeps past what one read takes still stop it, and that has to be said rather than read as
    // the whole history of the place.
    @Test
    fun `one place read cut short by the rows it keeps says so`() {
        val here = WorldBlock(world, 10, 64, -3)
        repeat(40) { i -> shared.submit(Transfer(Cause.BLOCK_PLACE, Void, here, stone, null, 1, T0 + i)) }
        shared.drain()

        val lines = said(LookupQuery(rows = 1, causes = setOf(Cause.BLOCK_PLACE), count = true))
        assertTrue(lines.any { it.contains("the read stopped early") }, "$lines")
    }

    // A radius asks its filter of each row as it walks the index, so the rows it drops do not use up the read
    // and a match behind them is found, where a read cut at a row count would have stopped short of it.
    @Test
    fun `a radius reads past the rows its filter drops`() {
        shared.submit(Transfer(Cause.BLOCK_PLACE, Void, WorldBlock(world, 10, 64, -3), stone, null, 1, T0))
        repeat(40) { i -> shared.submit(Transfer(Cause.BLK_TNT, WorldBlock(world, 11, 64, -3), Void, stone, null, 1, T0 + 1 + i)) }
        shared.drain()

        val lines = said(LookupQuery(radius = 1, rows = 1, causes = setOf(Cause.BLOCK_PLACE)))
        assertTrue(lines.any { it.contains("\nblock_place}") }, "$lines")
        assertTrue(lines.none { it.contains("limit:4") }, "nothing was cut short: $lines")
    }

    // A filter by action drops the rollback rows themselves, and a mark read among what the filter kept
    // never saw that the kill and the burn were rolled back.
    @Test
    fun `a rolled back row keeps its mark under a filter that leaves the rollback out`() {
        val cat = UUID.randomUUID()
        log.submit(
            listOf(
                EntityChange(
                    10, 64, -3, EntityKind.REMOVED, Cause.ENTITY_KILLED, "minecraft:cat",
                    cat, T0, before = byteArrayOf(10, 0, 0, 0),
                )
            )
        )
        log.submit(listOf(BlockChange(10, 65, -3, STONE, AIR, Cause.BLK_FIRE_BURN, T0)))
        log.submit(
            listOf(
                EntityChange(
                    10, 64, -3, EntityKind.CREATED, Cause.ROLLBACK, "minecraft:cat",
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

    // A theft a rollback gave back reads as rolled back; the rollback's own posting does not.
    @Test
    fun `an item row a rollback gave back is marked and found by rolledback`() {
        val chest = Container(world, 10, 64, -3, 0)
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(alice, 0), stone, null, 4, T0))
        shared.drain()
        val theft = shared.holderEntries(chest, 0, Long.MAX_VALUE).single()
        shared.submit(Transfer(Cause.ROLLBACK, Void, chest, stone, null, 4, T0 + 1000, reverts = listOf(theft.ref)))
        shared.drain()

        val lines = said()
        assertTrue(lines.single { it.contains("\ncontainer_remove}") }.contains("rolled back"), "$lines")
        // Out of the chest into her hands reads as taken out, and the chest the lookup is about is not repeated on
        // the line, by its ends or its place: the heading has it.
        assertTrue(lines.single { it.contains("\ncontainer_remove}") }.let { "took out" in it && "→" !in it && "10 64 -3" !in it }, "$lines")
        assertTrue(lines.first().contains("(10 64 -3 "), "$lines")
        // A page alone is the last one, and says so.
        assertTrue(lines.last().let { it.startsWith("-----") && "page 1, the last" in it }, "$lines")
        assertTrue(lines.single { it.contains("\nrollback}") }.let { !it.contains("rolled back") }, "$lines")
        val back = said(LookupQuery(rolledBack = true)).drop(1).dropLast(1)
        assertTrue(back.single().contains("\ncontainer_remove}"), "$back")
    }

    // A lookup of one place leaves out the place it is about on its lines, but not the other end of a movement:
    // a hopper fed from a chest elsewhere still says where the chest stands.
    @Test
    fun `a line of a lookup of one place gives only the other end's place`() {
        shared.submit(Transfer(Cause.HOPPER_PULL_CONTAINER, Container(world, 10, 65, -3, 0), Container(world, 10, 64, -3, 0), stone, null, 1, T0))
        shared.drain()

        // radius:0 reads the one place too.
        for (query in listOf(LookupQuery(), LookupQuery(radius = 0))) {
            val line = said(query).single { it.contains("\nhopper_pull_container}") }
            assertTrue(line.trimStart().startsWith("10 65 -3") && !line.contains("10 64 -3 "), line)
        }
    }

    // What a player carried has no position, and a crafting grid is booked to them as an entity. Both
    // are read by whose they are, and only theirs.
    @Test
    fun `a player lookup reads their own slots and their crafting grid and nobody else's`() {
        val chest = Container(world, 1, 64, 1, 0)
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(alice, 3), stone, null, 5, T0))
        shared.submit(Transfer(Cause.CRAFT_CONSUME, EntitySlot(alice, 1), Void, stone, null, 8, T0 + 1))
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 0), stone, null, 2, T0 + 2))
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerEquip(alice, 5), stone, null, 1, T0 + 3))
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerCursor(alice), stone, null, 1, T0 + 4))
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerEnder(alice, 7), stone, null, 1, T0 + 5))
        shared.drain()

        val lines = said(LookupQuery(players = listOf("Alice")), players = mapOf("Alice" to alice, "Bob" to bob))

        // Her inventory, equipment and cursor read as taken out of the chest, with no ends drawn; her ender chest
        // is not her hands and keeps both.
        assertEquals(4, lines.count { it.contains("\ncontainer_remove}") }, "$lines")
        assertTrue(lines.any { it.contains("block.minecraft.crafting_table") && it.contains("Alice{crafting grid, slot 1}") }, "$lines")
        assertTrue(lines.any { it.contains("ender_chest") && it.contains("{slot 7}") }, "$lines")
        assertTrue(lines.none { it.contains("Bob") }, "$lines")
        // Refused, not answered: no header, so nothing reads as an empty area.
        val narrowed = said(LookupQuery(players = listOf("Alice"), radius = 5), players = mapOf("Alice" to alice))
        assertTrue(narrowed.single().let { !it.startsWith("PfauProtect") && "Alice" !in it }, "$narrowed")
        val unknown = said(LookupQuery(players = listOf("Carol")), players = mapOf("Alice" to alice))
        assertTrue(unknown.single().let { !it.startsWith("PfauProtect") && "Carol" in it }, "$unknown")
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

        val lines = said(target = LookupTarget(other, 1, 64, 1, "stone at 1 64 1"))

        assertTrue(lines.any { it.contains("placed") }, "$lines")
        assertEquals(1, lines.indexOfFirst { it.startsWith("-----") }, "one notice before the answer: $lines")
    }

    @Test
    fun `a named box reads as the named box and a bare one does not borrow its name`() {
        val named = NmsItemStack(Items.SHULKER_BOX).apply {
            set(DataComponents.CUSTOM_NAME, Component.literal("Bank"))
        }
        val bare = NmsItemStack(Items.SHULKER_BOX)
        val at = WorldBlock(world, 10, 64, -3)
        shared.submit(Transfer(Cause.BLOCK_PLACE, Void, at, codec.encode(named).form, null, 1, T0))
        shared.submit(Transfer(Cause.BLOCK_DROP, at, Void, codec.encode(bare).form, null, 1, T0 + 1000))
        shared.drain()

        val lines = said()

        assertTrue(lines.any { it.contains("shulker_box \"Bank\"") }, "$lines")
        assertTrue(lines.any { it.contains("shulker_box") && !it.contains("Bank") }, "the bare one stays bare: $lines")
    }
}
