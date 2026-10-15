package io.pfaumc.pfauprotect.command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.model.Cause
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

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
        for (query in listOf("r:${Settings.maxRadius + 1}", "r:-5")) {
            assertThrows(CommandSyntaxException::class.java, { parseLookupQuery(query) }, query)
        }
    }

    @Test
    fun `a user without a radius is looked up everywhere, as CoreProtect does`() {
        assertTrue(parseLookupQuery("u:Steve t:1d").global)
        assertEquals(5, parseLookupQuery("u:Steve r:5").radius)
        assertEquals(null, parseLookupQuery("t:1d").radius)
        assertEquals(null, parseLookupQuery("player:Steve u:Alex").radius)
        assertEquals(null, parseLookupQuery("u:Steve event:4711@10,64,-42@1700000000123").radius)
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
    }

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
        fun line(secondsAgo: Long, amount: Int, undone: Boolean = false) = Line(
            1_000_000L - secondsAgo * 1000, Cause.BLK_PLAYER_BREAK, null, "break stone", amount, undone, null,
        ) { _, _, _ -> Component.empty() }
        val lines = listOf(line(0, 64), line(30, 32), line(70, 1), line(131, 5), line(161, 7, undone = true), line(191, 2), line(221, 3))
        assertEquals(listOf(3, 1, 1, 2), folded(lines, all = false).map { it.rows })
        assertEquals(listOf(97, 5, 7, 5), folded(lines, all = false).map { it.amount })
        assertEquals(List(lines.size) { 1 }, folded(lines, all = true).map { it.rows })
    }

    @Test
    fun `an event reads back as written`() {
        val event = EventRef(4711, 10, -60, -42, 1_700_000_000_123L)
        assertEquals(event, parseLookupQuery(event.toString()).event)
        assertEquals(null, EventRef.parse("4711"))
        assertEquals(null, EventRef.parse("4711@10,x,-60,-42@1700000000123"))
    }

    @Test
    fun `a page button asks the same question about the same place`() {
        val query = parseLookupQuery("u:Bob t:1h page:2 at:1,2,3")
        assertEquals(Triple(1, 2, 3), query.at)
        val command = query.pageCommand(LookupTarget(UUID(0, 0), 7, 64, -3, ""), 3)
        val asked = parseLookupQuery(command.removePrefix("/pp l "))
        assertEquals(query.copy(page = 3, at = Triple(7, 64, -3), words = asked.words), asked)
    }

    @Test
    fun `a chat page button asks the same question with no place`() {
        val query = parseLookupQuery("u:Bob f:tnt t:1h page:2")
        val command = query.pageCommand("chat", 3)
        val asked = parseLookupQuery(command.removePrefix("/pp chat "))
        assertEquals(query.copy(page = 3, words = asked.words), asked)
    }

}
