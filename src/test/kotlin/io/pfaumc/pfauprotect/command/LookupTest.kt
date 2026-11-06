package io.pfaumc.pfauprotect.command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.storage.BookEdit
import io.pfaumc.pfauprotect.storage.ChatKind
import io.pfaumc.pfauprotect.storage.ChatLine
import io.pfaumc.pfauprotect.storage.Hit
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
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
        // The chat log has no place, no items and no rows of the planes to count.
        assertEquals(setOf("user:", "time:", "filter:", "action:", "exclude:", "page:", "#all"), offered(LookupArgument(chat = true)).toSet())
    }

    // As CoreProtect's list does: a key once said, under any spelling, leaves the list; a list of names stays,
    // as a second one adds to the first.
    @Test
    fun `a word already said is not offered again`() {
        val typed = "a:break T:1h U:Bob i:stone #count "
        val offered = LookupArgument().listSuggestions(
            CommandContext<Any>(Any(), "", emptyMap(), null, null, emptyList(), StringRange.at(0), null, null, false),
            SuggestionsBuilder(typed, 0),
        ).get().list.map { it.text }
        assertTrue(offered.none { it in listOf("action:", "time:", "#count") }, "$offered")
        assertTrue(offered.containsAll(listOf("user:", "include:", "radius:", "#all")), "$offered")
    }

    // In /pp chat only the actions it reads are offered, and `exclude:` takes them beside the players.
    @Test
    fun `chat offers its own actions`() {
        fun offered(typed: String) = LookupArgument(chat = true, names = { listOf("Test1") }).listSuggestions(
            CommandContext<Any>(Any(), "", emptyMap(), null, null, emptyList(), StringRange.at(0), null, null, false),
            SuggestionsBuilder(typed, 0),
        ).get().list.map { it.text }
        assertEquals(setOf("pvp", "book"), offered("a:").toSet())
        assertTrue(offered("e:").containsAll(listOf("pvp", "book", "Test1")), offered("e:").toString())
    }

    // Brigadier hands the typed part over lowercased, and a name with a capital in it used to drop out
    // of the list at its first letter.
    @Test
    fun `a name is offered whatever case it is typed in`() {
        val offered = LookupArgument(names = { listOf("SPY_me", "Test1") }).listSuggestions(
            CommandContext<Any>(Any(), "", emptyMap(), null, null, emptyList(), StringRange.at(0), null, null, false),
            SuggestionsBuilder("u:spy", 0),
        ).get().list.map { it.text }
        assertEquals(listOf("SPY_me"), offered)
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
    fun `an ampersand joins values into one group and every action of it is checked`() {
        assertEquals(listOf("Steve&Alex", "Bob"), parseLookupQuery("u:Steve&&Alex,Bob&").users)
        assertEquals(listOf("Steve&Alex"), parseLookupQuery("Steve&Alex").users)
        val query = parseLookupQuery("a:-entity&explosion")
        assertEquals(listOf("-entity&explosion"), query.actions)
        assertTrue(query.causes!!.containsAll(Action.EXPLOSION.causes))
        val bad = assertThrows(CommandSyntaxException::class.java) { parseLookupQuery("a:pvp&bogus") }
        assertEquals("a:pvp&".length, bad.cursor)
        // Separators alone are no value, or a rollback would read `u:&` as nobody named.
        for (words in listOf("u:&", "a:,&", "e:&,", "f:&", "t:1h &")) {
            assertThrows(CommandSyntaxException::class.java, { parseLookupQuery(words) }, words)
        }
        assertEquals(",", parseLookupQuery("f:,").filter)
    }

    @Test
    fun `chat with both players joined is the fight between them, either way round`() {
        val carol = UUID.randomUUID()
        val names = mapOf("Alice" to alice, "Bob" to bob)
        fun hit(by: UUID, on: UUID) = ChatLine(0, ChatKind.HIT, by, "minecraft:iron_sword", hit = Hit(on, 2f, Confidence.FACT))
        val keeps = chatFilter(parseLookupQuery("u:Alice&Bob a:pvp"), names::get)

        assertTrue(keeps(hit(alice, bob)) && keeps(hit(bob, alice)))
        assertFalse(keeps(hit(alice, carol)) || keeps(hit(carol, bob)) || keeps(ChatLine(0, ChatKind.CHAT, alice, "hi Bob")))
        val text = chatFilter(parseLookupQuery("f:sell&diamond"), names::get)
        assertTrue(text(ChatLine(0, ChatKind.CHAT, alice, "Sell you a diamond")))
        assertFalse(text(ChatLine(0, ChatKind.CHAT, alice, "sell")))
    }

    @Test
    fun `chat leaves out an excluded player and with pvp the hits`() {
        val carol = UUID.randomUUID()
        val names = mapOf("Alice" to alice, "Bob" to bob, "Carol" to carol)
        fun hit(by: UUID, on: UUID) = ChatLine(0, ChatKind.HIT, by, "minecraft:iron_sword", hit = Hit(on, 2f, Confidence.FACT))
        val notBob = chatFilter(parseLookupQuery("u:Alice e:Bob a:pvp"), names::get)
        assertTrue(notBob(hit(alice, carol)))
        assertFalse(notBob(hit(alice, bob)) || notBob(hit(bob, alice)))
        val talk = chatFilter(parseLookupQuery("u:Alice e:pvp"), names::get)
        assertTrue(talk(ChatLine(0, ChatKind.CHAT, alice, "hi")))
        assertFalse(talk(hit(alice, bob)))
        assertEquals(listOf("Alice", "Bob"), chatPlayers(parseLookupQuery("u:Alice e:pvp&Bob")))
        // A player called Hit is the player, not every hit.
        val notHit = chatFilter(parseLookupQuery("e:Hit"), (names + ("Hit" to carol))::get)
        assertTrue(notHit(hit(alice, bob)))
        assertFalse(notHit(hit(carol, bob)))
        assertThrows(CommandSyntaxException::class.java) { parseLookupQuery("e:dirt,steal") }
    }

    // `a:book` is the books alone, `a:pvp,book` either, and `f:` finds one book by its mark.
    @Test
    fun `chat with book is what was written in books`() {
        val names = mapOf("Alice" to alice)
        val edit = ChatLine(0, ChatKind.BOOK, alice, BookEdit("#3f2a1c0d", null, listOf(BookEdit.Page(2, "", "my base at 100 64"))).text())
        val hit = ChatLine(0, ChatKind.HIT, alice, "", hit = Hit(bob, 2f, Confidence.FACT))
        val said = ChatLine(0, ChatKind.CHAT, alice, "book")
        val books = chatFilter(parseLookupQuery("a:book"), names::get)
        assertTrue(books(edit))
        assertFalse(books(hit) || books(said))
        val either = chatFilter(parseLookupQuery("a:pvp,book"), names::get)
        assertTrue(either(edit) && either(hit))
        assertFalse(either(said))
        assertTrue(chatFilter(parseLookupQuery("f:#3f2a1c0d"), names::get)(edit))
        assertFalse(chatFilter(parseLookupQuery("e:book"), names::get)(edit))
    }

    @Test
    fun `another name is offered after an ampersand`() {
        val offered = LookupArgument(names = { listOf("SPY_me", "Test1") }).listSuggestions(
            CommandContext<Any>(Any(), "", emptyMap(), null, null, emptyList(), StringRange.at(0), null, null, false),
            SuggestionsBuilder("u:SPY_me&te", 0),
        ).get().list.map { it.text }
        assertEquals(listOf("Test1"), offered)
    }

    // What CoreProtect users type: the name with no u: in front of it.
    @Test
    fun `a bare word is a user`() {
        val query = parseLookupQuery("Ayanorie t:1d r:10")
        assertEquals(listOf("Ayanorie"), query.users)
        assertEquals(10, query.radius)
        assertEquals(listOf("Steve", "Alex"), parseLookupQuery("u:Steve Alex").users)
        assertTrue(parseLookupQuery("Steve t:1d").global)
        // The next page keeps a player whose name is a paged-away key.
        assertEquals("/pp chat page t:1h page:2", parseLookupQuery("page t:1h page:1").pageCommand("chat", 2))
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
            "u:Steve z:1" to 8,
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

    // Newest first, as the chat log reads: Alice's three sword hits fold into one run that reads at her first,
    // Bob's hit back stays its own line, and a chat line between folds nothing.
    @Test
    fun `hits fold by attacker, victim and weapon, and a hit back breaks the run`() {
        fun hit(at: Long, by: UUID, on: UUID) = ChatLine(at, ChatKind.HIT, by, "minecraft:iron_sword", hit = Hit(on, 2f, Confidence.FACT))
        val lines = listOf(
            hit(40_000, alice, bob), hit(30_000, alice, bob), hit(20_000, alice, bob),
            hit(15_000, bob, alice), ChatLine(12_000, ChatKind.CHAT, alice, "stop"), hit(10_000, alice, bob),
        )
        val runs = chatRuns(lines, all = false)
        assertEquals(listOf(3, 1, 1, 1), runs.map { it.rows })
        assertEquals(20_000L, runs[0].oldest)
        assertEquals(6f, runs[0].damage)
        assertEquals(6, chatRuns(lines, all = true).size)
        // A hit worked out (his dog) beside one seen is drawn apart: the run reads as its first.
        val dog = ChatLine(35_000, ChatKind.HIT, alice, "minecraft:iron_sword", hit = Hit(bob, 2f, Confidence.INFERRED))
        assertEquals(listOf(1, 1, 2), chatRuns(listOf(lines[0], dog) + lines.subList(1, 3), all = false).map { it.rows })
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
    fun `a time span reads clock times and dates`() {
        val now = ZonedDateTime.of(2026, 11, 4, 15, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        fun ms(d: Int, m: Int, h: Int, min: Int, s: Int = 0, y: Int = 2026) =
            ZonedDateTime.of(y, m, d, h, min, s, 0, now.zone).toInstant().toEpochMilli()
        fun span(v: String) = timeSpanOrNull(v, now)
        // Behind now: today; ahead of it: yesterday. Up to now either way.
        assertEquals(TimeSpan(fromMillis = ms(4, 11, 14, 0)), span("14:00"))
        assertEquals(TimeSpan(fromMillis = ms(3, 11, 16, 0)), span("16:00"))
        assertEquals(TimeSpan(fromMillis = ms(3, 11, 0, 0), untilMillis = ms(4, 11, 0, 0) - 1), span("03.11"))
        assertEquals(TimeSpan(fromMillis = ms(3, 11, 0, 0), untilMillis = ms(5, 11, 0, 0) - 1), span("03.11-04.11"))
        assertEquals(TimeSpan(fromMillis = ms(3, 11, 0, 0), untilMillis = ms(5, 11, 0, 0) - 1), span("04.11-03.11"))
        // A time without seconds as the near end takes its whole minute.
        val minute = 59_999L
        assertEquals(TimeSpan(fromMillis = ms(3, 11, 14, 0), untilMillis = ms(3, 11, 18, 0) + minute), span("03.11_14:00-03.11_18:00"))
        assertEquals(TimeSpan(fromMillis = ms(3, 11, 14, 0), untilMillis = ms(3, 11, 18, 0, 30)), span("03.11_14:00-03.11_18:00:30"))
        // A date still ahead is last year's.
        assertEquals(ms(5, 12, 0, 0, y = 2025), span("05.12")?.fromMillis)
        assertEquals(ms(5, 12, 0, 0, y = 2024), span("05.12.2024")?.fromMillis)
        assertEquals(TimeSpan(fromMillis = ms(4, 11, 13, 0), secondsUntil = 3600), span("13:00-1h"))
        assertEquals(TimeSpan(secondsBack = 7200, untilMillis = ms(4, 11, 14, 30) + minute), span("2h-14:30"))
        assertEquals(TimeSpan(secondsBack = 7200, secondsUntil = 3600), span("2h-1h"))
        // An end still ahead stays today after a start behind now; a start ahead of now is still yesterday's.
        assertEquals(TimeSpan(fromMillis = ms(4, 11, 14, 0), untilMillis = ms(4, 11, 15, 30) + minute), span("14:00-15:30"))
        assertEquals(TimeSpan(fromMillis = ms(3, 11, 23, 0), untilMillis = ms(4, 11, 1, 0) + minute), span("23:00-01:00"))
        for (bad in listOf("25:00", "30.02", "03.11_", "14:00_03.11", "03.11-03.11", "14:00-14:00", "1.2.3")) assertNull(span(bad), bad)
    }

    // Typed times stay put when the page buttons pin another "now".
    @Test
    fun `an absolute time window does not move with asOf`() {
        val q = parseLookupQuery("t:03.11-04.11")
        assertEquals(q.fromTs(1L), q.fromTs(Long.MAX_VALUE / 2))
        assertEquals(q.toTs(1L), q.copy(asOf = Long.MAX_VALUE).toTs())
        // A pin inside the window caps its end.
        assertEquals(4L, q.copy(asOf = 5L).toTs())
        assertTrue(q.hasFrom && q.hasUntil)
    }

    // `t:14:00` typed at 13:59 is yesterday's; the page buttons ask again at 14:01 with `asof:` pinned, in either word order.
    @Test
    fun `a clock time is resolved against asof, whatever the wall clock says`() {
        val zone = ZoneId.of("Europe/Moscow")
        val typed = ZonedDateTime.of(2026, 11, 4, 13, 59, 0, 0, zone)
        val later = typed.plusMinutes(2)
        val asOf = typed.toInstant().toEpochMilli()
        val yesterday = ZonedDateTime.of(2026, 11, 3, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        for (words in listOf("t:14:00 asof:$asOf", "asof:$asOf t:14:00")) {
            assertEquals(yesterday, parseLookupQuery(words, typed).fromMillis, words)
            assertEquals(yesterday, parseLookupQuery(words, later).fromMillis, words)
        }
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
        ) { _, _ -> Component.empty() }
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
        // Asked at a moment, which the next page keeps: rows written since would shift every page.
        val query = parseLookupQuery("u:Bob t:1h page:2 at:1,2,3").copy(asOf = 1_700_000_000_000L)
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


    @Test
    fun `a part parses and the page button keeps it`() {
        val query = parseLookupQuery("part:contents")
        assertEquals(Part.CONTENTS, query.part)
        assertThrows(CommandSyntaxException::class.java) { parseLookupQuery("part:x") }
        val command = query.copy(words = "part:contents page:1").pageCommand(LookupTarget(UUID(0, 0), 7, 64, -3, ""), 2)
        assertTrue(command.contains("part:contents") && command.split(" ").count { it.startsWith("part:") } == 1, command)
    }
}
