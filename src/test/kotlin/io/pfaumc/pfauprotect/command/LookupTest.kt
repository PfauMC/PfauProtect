package io.pfaumc.pfauprotect.command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.pfaumc.pfauprotect.model.Cause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LookupTest {

    // What only shapes a lookup's answer — whose hands, how many rows, which page — means nothing to a
    // rollback, and offering it there reads as if it narrowed what is undone.
    @Test
    fun `a rollback is offered only the words that choose what it undoes`() {
        fun offered(argument: LookupArgument) = argument.listSuggestions(
            CommandContext<Any>(Any(), "", emptyMap(), null, null, emptyList(), StringRange.at(0), null, null, false),
            SuggestionsBuilder("", 0),
        ).get().list.map { it.text }
        val answerOnly = listOf("player:", "limit:", "page:", "filter:")

        assertTrue(offered(LookupArgument()).containsAll(answerOnly + "#count"))
        val rollback = offered(LookupArgument(rollback = true))
        assertTrue(rollback.containsAll(listOf("user:", "time:", "radius:", "action:", "include:", "exclude:")), "$rollback")
        assertTrue(rollback.none { it in answerOnly || it.startsWith("#") }, "$rollback")
    }

    @Test
    fun `comma separated values accumulate and repeated parameters append`() {
        assertEquals(listOf("Steve", "Alex"), parseLookupQuery("u:Steve,Alex").users)
        assertEquals(listOf("Steve", "Alex"), parseLookupQuery("u:Steve u:Alex").users)
        assertEquals(listOf("Steve"), parseLookupQuery("player:Steve").players)
        assertEquals(listOf("Steve", "Alex"), parseLookupQuery("p:Steve,Alex").players)
        assertEquals(listOf("diamond", "emerald"), parseLookupQuery("b:diamond,emerald").included)
    }

    @Test
    fun `time spans add up across units`() {
        assertEquals(1800L, durationOrNull("30m"))
        assertEquals(7200L, durationOrNull("2h"))
        assertEquals(86_400L, durationOrNull("1d"))
        assertEquals(604_800L, durationOrNull("1w"))
        assertEquals(86_400L + 21_600L, durationOrNull("1d6h"))
        assertEquals(2L * 3600 + 30 * 60, parseLookupQuery("t:2h30m").secondsBack)
    }

    // Months and minutes share their first letter, and reading 3mo as three minutes would silently
    // shrink the window by four orders of magnitude.
    @Test
    fun `months are not read as minutes`() {
        assertEquals(3L * 2_592_000, durationOrNull("3mo"))
        assertEquals(3L * 60, durationOrNull("3m"))
    }

    @Test
    fun `a time span has to be fully consumed`() {
        for (bad in listOf("2hours", "h", "2", "2x", "1d 6h", "")) assertNull(durationOrNull(bad), bad)
    }

    @Test
    fun `actions map onto the causes the capture emits`() {
        assertEquals(setOf(Cause.CONTAINER_ADD, Cause.CONTAINER_REMOVE), parseLookupQuery("a:container").causes)
        assertEquals(setOf(Cause.CONTAINER_ADD), parseLookupQuery("a:+container").causes)
        assertEquals(setOf(Cause.CONTAINER_REMOVE), parseLookupQuery("a:-container").causes)
        assertEquals(setOf(Cause.CONTAINER_ADD), parseLookupQuery("a:deposit").causes)
        assertEquals(setOf(Cause.CONTAINER_REMOVE), parseLookupQuery("a:withdraw").causes)
        assertEquals(
            setOf(Cause.CONTAINER_ADD, Cause.CONTAINER_REMOVE),
            parseLookupQuery("a:+container,-container").causes,
        )
        val explosion = parseLookupQuery("a:explosion").causes!!
        assertTrue(explosion.containsAll(listOf(Cause.BLK_TNT, Cause.BLK_CREEPER)), "$explosion")
        assertTrue(Cause.BLK_FIRE_BURN !in explosion && Cause.BLK_PLAYER_BREAK !in explosion, "$explosion")
        val fire = parseLookupQuery("a:fire").causes!!
        assertTrue(Cause.BLK_FIRE_BURN in fire, "$fire")
        assertEquals(explosion + fire, parseLookupQuery("a:explosion,fire").causes)
    }

    @Test
    fun `a radius is a block count and global is kept apart from it`() {
        assertEquals(10, parseLookupQuery("r:10").radius)
        assertEquals(0, parseLookupQuery("r:0").radius)
        assertTrue(parseLookupQuery("r:global").global)
        assertTrue(parseLookupQuery("r:#global").global)
        assertTrue(parseLookupQuery("r:none").global)
        for (query in listOf("r:${io.pfaumc.pfauprotect.Settings.maxRadius + 1}", "r:-5")) {
            assertThrows(CommandSyntaxException::class.java, { parseLookupQuery(query) }, query)
        }
    }

    @Test
    fun `row counts stay inside the readable range`() {
        assertEquals(50, parseLookupQuery("rows:50").limit)
        for (query in listOf("rows:0", "rows:${MAX_LIMIT + 1}")) {
            assertThrows(CommandSyntaxException::class.java, { parseLookupQuery(query) }, query)
        }
    }

    @Test
    fun `keys are case insensitive but values keep their case`() {
        assertEquals(listOf("Steve"), parseLookupQuery("U:Steve").users)
        assertEquals(setOf(Cause.CONTAINER_ADD), parseLookupQuery("A:DEPOSIT").causes)
    }

    // The point of parsing off the caller's reader is that the game underlines the token at fault, so
    // the cursor each failure carries is the behaviour worth pinning, not just the fact it threw.
    @Test
    fun `a rejected query points at the token that caused it`() {
        val cases = mapOf(
            "Steve" to 0,
            "u:Steve z:1" to 8,
            "u:Steve Steve" to 8,
            ":10" to 0,
            "u:" to 2,
            "u:Steve t:2hours" to 10,
            "r:notaradius" to 2,
            "limit:0" to 6,
            "a:container,chatter" to 12,
        )
        for ((query, cursor) in cases) {
            val failure = assertThrows(CommandSyntaxException::class.java, { parseLookupQuery(query) }, query)
            assertEquals(cursor, failure.cursor, "$query -> ${failure.message}")
        }
    }

    @Test
    fun `the block plane is reachable by name and every block cause is under one of the filters`() {
        val blockRange = Cause.entries.filter { it.name.startsWith("BLK_") }.toSet()
        assertEquals(blockRange, Action.of("block")?.causes)

        val named = Action.entries.filter { it != Action.BLOCK }.flatMap { it.causes }.toSet()
        val unreachable = blockRange - named
        assertEquals(emptySet<Cause>(), unreachable, "no filter names these block causes")
    }

    // The two planes share one dictionary, so a filter meant for one must not quietly answer with the
    // other's rows: an investigator asking about chests would be handed explosions.
    @Test
    fun `no filter mixes the two planes`() {
        for (action in Action.entries) {
            if (action == Action.BLOCK) continue
            val block = action.causes.count { it in Action.BLOCK.causes }
            assertTrue(
                block == 0 || block == action.causes.size,
                "${action.keys.first()} names causes from both planes",
            )
        }
    }

    // A span runs from the far end to the near one whichever is written first; a span of nothing is no span.
    @Test
    fun `a time span reads both ends`() {
        assertEquals(7200L to 3600L, spanOrNull("2h-1h"))
        assertEquals(7200L to 3600L, spanOrNull("1h-2h"))
        assertEquals(600L to null, spanOrNull("10m"))
        assertEquals(null, spanOrNull("1h-1h"))
        val query = parseLookupQuery("time:3d-1d page:2 limit:20")
        assertEquals(259_200L, query.secondsBack)
        assertEquals(86_400L, query.secondsUntil)
    }

    // The filters a lookup narrows by besides place and time.
    @Test
    fun `amounts, undone rows, theft and a count are understood`() {
        assertEquals(5..Int.MAX_VALUE, amountOrNull(">=5"))
        assertEquals(6..Int.MAX_VALUE, amountOrNull(">5"))
        assertEquals(0 until 10, amountOrNull("<10"))
        assertEquals(5..10, amountOrNull("5-10"))
        assertEquals(64..64, amountOrNull("64"))
        assertEquals(null, amountOrNull("lots"))
        val query = parseLookupQuery("#count action:steal rolledback:no amount:>=8")
        assertTrue(query.count && query.steal)
        assertEquals(false, query.rolledBack)
        assertEquals(8..Int.MAX_VALUE, query.amount)
    }

    // Newest first, as an answer is read. A run grows gap by gap, so a wall broken over two minutes at a
    // steady pace is one line; a pause of more than a minute starts another, and so does a rollback mark.
    @Test
    fun `runs fold gap by gap and keep undone rows apart`() {
        fun line(secondsAgo: Long, undone: Boolean = false) = Line(
            1_000_000L - secondsAgo * 1000, Cause.BLK_PLAYER_BREAK, null, "break stone", 1, undone, null,
        ) { _, _, _ -> net.kyori.adventure.text.Component.empty() }
        val lines = listOf(line(0), line(30), line(70), line(131), line(161, undone = true), line(191), line(221))
        assertEquals(listOf(3, 1, 1, 2), folded(lines, all = false).map { it.rows })
        assertEquals(List(lines.size) { 1 }, folded(lines, all = true).map { it.rows })
    }

    // A clicked line fills an event in with where and when, and it reads back as it was written.
    @Test
    fun `an event reads back as written`() {
        val event = EventRef(4711, 10, -60, -42, 1_700_000_000_123L)
        assertEquals(event, parseLookupQuery(event.toString()).event)
        assertEquals(null, EventRef.parse("4711"))
        assertEquals(null, EventRef.parse("4711@10,x,-60,-42@1700000000123"))
    }

    // The page buttons ask the same words again about the same place, at the page they go to.
    @Test
    fun `a page button asks the same question about the same place`() {
        val query = parseLookupQuery("u:Bob t:1h page:2 at:1,2,3")
        assertEquals(Triple(1, 2, 3), query.at)
        val command = query.pageCommand(LookupTarget(java.util.UUID(0, 0), 7, 64, -3, ""), 3)
        val asked = parseLookupQuery(command.removePrefix("/pp l "))
        assertEquals(query.copy(page = 3, at = Triple(7, 64, -3), words = asked.words), asked)
    }

}
