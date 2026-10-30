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
import io.pfaumc.pfauprotect.storage.EntityChange
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.model.Kind
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
import org.bukkit.plugin.Plugin
import net.kyori.adventure.text.Component as AdventureComponent
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
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
    ): List<String> {
        val lines = ArrayList<String>()
        val names = players.entries.associate { (name, id) -> id to name }
        Lookups(stub(Plugin::class.java), shared, logs, codec, players::get, names::get)
            .report(senderInto(lines, ::flat), target, query)
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

    // Pages count folded runs, so the rows are folded before they are cut: ten blasts are one run on page
    // one, and the placement before them is page two rather than lost between the pages.
    @Test
    fun `a page after a folded run shows the next run`() {
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0)))
        repeat(10) { log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0 + 1000 + it))) }
        log.drain()

        val first = said(LookupQuery(rows = 1))
        assertTrue(first.any { it.contains("block.minecraft.tnt") }, "$first")
        assertTrue(first.none { it.contains("{blk_player_place}") }, "$first")
        val second = said(LookupQuery(rows = 1, page = 2))
        assertTrue(second.any { it.contains("{blk_player_place}") }, "$second")
        assertTrue(second.none { it.contains("block.minecraft.tnt") }, "$second")
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
    fun `a position answers with both planes in the order things happened`() {
        val at = WorldBlock(world, 10, 64, -3)
        shared.submit(Transfer(Cause.BLOCK_PLACE, Void, at, stone, null, 1, T0))
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0)))
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0 + 1000)))
        shared.submit(moved(at, Void, stone, Cause.BLK_TNT, null, T0 + 1000))
        shared.drain()
        log.drain()

        val lines = said()

        assertTrue(lines.any { it.contains("- block.minecraft.tnt") && it.contains("minecraft:stone → minecraft:air") }, "$lines")
        assertTrue(lines.any { it.contains("{blk_player_place}") && it.contains("minecraft:air → minecraft:stone") }, "$lines")
        assertTrue(lines.any { it.contains("{block_place}") && it.contains("∅") }, "the item plane is still there: $lines")
        val causes = listOf("{blk_tnt}", "{block_place}", "{blk_player_place}")
        val read = lines.mapNotNull { line -> causes.firstOrNull { it in line } }.map { if (it == "{blk_tnt}") "tnt" else "place" }
        assertEquals(listOf("tnt", "tnt", "place", "place"), read, "$lines")
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
        fun filter(words: String) = RowFilter(shared, parseLookupQuery(words), emptySet(), emptySet(), rollback = true)

        assertTrue(filter("include:stone").keeps(broken))
        assertFalse(filter("exclude:stone").keeps(broken))
        assertTrue(filter("exclude:stone").keeps(taken))
        assertFalse(filter("exclude:diamond").keeps(taken))
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
        assertTrue(lines.any { it.contains("10 64 -3") && it.contains("{blk_tnt}") }, "$lines")
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
        shared.submit(Transfer(Cause.BLK_TNT, here, Void, stone, null, 1, T0))
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0)))
        repeat(40) { i ->
            shared.submit(Transfer(Cause.BLK_TNT, corner, Void, stone, null, 1, T0 + 1 + i))
            log.submit(listOf(BlockChange(15, 64, -15, STONE, AIR, Cause.BLK_TNT, T0 + 1 + i)))
        }
        shared.drain()
        log.drain()

        // Two rows asked for, eight times that read: far fewer than the corner holds.
        val lines = said(LookupQuery(radius = 1, rows = 2)).filter { it.contains("10 64 -3") }

        assertTrue(lines.any { it.contains("∅") }, "the item plane lost it: $lines")
        assertTrue(lines.any { it.contains("event:") }, "the block plane lost it: $lines")
    }

    // A read cut short must say so rather than answer "no entries", which would clear somebody of the
    // rows behind the cut, and it offers no page 2: that page starts past every matched row and is empty.
    @Test
    fun `a read that stopped early says so and offers no next page`() {
        val here = WorldBlock(world, 10, 64, -3)
        repeat(40) { i -> shared.submit(Transfer(Cause.BLK_TNT, here, Void, stone, null, 1, T0 + i)) }
        shared.drain()
        // Forty rows of the position, more than one row's worth of read, and none of them the kind asked for.
        val query = LookupQuery(rows = 1, causes = setOf(Cause.BLOCK_PLACE))

        val empty = said(query)
        assertTrue(empty.single().contains("limit:4"), "$empty")

        shared.submit(Transfer(Cause.BLOCK_PLACE, Void, here, stone, null, 1, T0 + 100))
        shared.drain()

        val lines = said(query)
        assertTrue(lines.any { it.contains("{block_place}") }, "$lines")
        assertTrue(lines.none { it.contains("page:2") }, "$lines")
        // Said inside the page, above its closing rule.
        assertTrue(lines.dropLast(1).last().contains("limit:4"), "$lines")
    }

    // A radius asks its filter of each row as it walks the index, so the rows it drops do not use up the read
    // and a match behind them is found, where a read cut at a row count would have stopped short of it.
    @Test
    fun `a radius reads past the rows its filter drops`() {
        shared.submit(Transfer(Cause.BLOCK_PLACE, Void, WorldBlock(world, 10, 64, -3), stone, null, 1, T0))
        repeat(40) { i -> shared.submit(Transfer(Cause.BLK_TNT, WorldBlock(world, 11, 64, -3), Void, stone, null, 1, T0 + 1 + i)) }
        shared.drain()

        val lines = said(LookupQuery(radius = 1, rows = 1, causes = setOf(Cause.BLOCK_PLACE)))
        assertTrue(lines.any { it.contains("{block_place}") }, "$lines")
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
        assertTrue(lines.single { it.contains("{container_remove}") }.contains("rolled back"), "$lines")
        // Out of the chest into her hands reads as taken out, and the chest the lookup is about is not repeated on
        // the line, by its ends or its place: the heading has it.
        assertTrue(lines.single { it.contains("{container_remove}") }.let { "took out" in it && "→" !in it && "10 64 -3" !in it }, "$lines")
        assertTrue(lines.first().contains("(10 64 -3 "), "$lines")
        assertTrue(lines.single { it.contains("{rollback}") }.let { !it.contains("rolled back") }, "$lines")
        val back = said(LookupQuery(rolledBack = true)).drop(1).dropLast(1)
        assertTrue(back.single().contains("{container_remove}"), "$back")
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
        assertEquals(4, lines.count { it.contains("{container_remove}") }, "$lines")
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
