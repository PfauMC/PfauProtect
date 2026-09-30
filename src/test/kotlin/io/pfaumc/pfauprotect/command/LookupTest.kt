package io.pfaumc.pfauprotect.command
import com.mojang.brigadier.exceptions.CommandSyntaxException
import io.pfaumc.pfauprotect.model.Cause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LookupTest {

    @Test
    fun `an empty query keeps every default`() {
        val query = parseLookupQuery("")
        assertEquals(emptyList<String>(), query.users)
        assertNull(query.secondsBack)
        assertNull(query.radius)
        assertNull(query.causes)
        assertEquals(DEFAULT_LIMIT, query.limit)
    }

    @Test
    fun `short and long parameter names mean the same thing`() {
        assertEquals(parseLookupQuery("u:Steve"), parseLookupQuery("user:Steve"))
        assertEquals(parseLookupQuery("t:2h"), parseLookupQuery("time:2h"))
        assertEquals(parseLookupQuery("r:10"), parseLookupQuery("radius:10"))
        assertEquals(parseLookupQuery("a:container"), parseLookupQuery("action:container"))
        assertEquals(parseLookupQuery("b:diamond"), parseLookupQuery("item:diamond"))
        assertEquals(parseLookupQuery("i:diamond"), parseLookupQuery("include:diamond"))
        assertEquals(parseLookupQuery("items:diamond"), parseLookupQuery("include:diamond"))
        assertEquals(parseLookupQuery("e:dirt"), parseLookupQuery("exclude:dirt"))
        assertEquals(parseLookupQuery("l:25"), parseLookupQuery("rows:25"))
    }

    @Test
    fun `every accepted spelling reaches its own parameter`() {
        for (param in Param.entries) {
            for (key in param.keys) assertEquals(param, Param.of(key), key)
            assertEquals(param, Param.of(param.canonical.uppercase().lowercase()))
        }
    }

    // Completion lists the readable spelling of each parameter while the terse ones keep parsing, so
    // the two must stay in step: a parameter added to the table has to show up in completion too.
    @Test
    fun `completion offers the long spelling of every parameter and nothing else`() {
        assertEquals(Param.entries.size, Param.prefixes.size)
        for (prefix in Param.prefixes) {
            val key = prefix.removeSuffix(":")
            assertNotNull(Param.of(key))
            assertTrue(key.length > 1, "completion should not offer the terse alias $prefix")
        }
        assertTrue(Param.prefixes.containsAll(listOf("user:", "time:", "radius:", "action:", "include:", "exclude:", "limit:")))
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
    }

    @Test
    fun `a radius is a block count and global is kept apart from it`() {
        assertEquals(10, parseLookupQuery("r:10").radius)
        assertEquals(0, parseLookupQuery("r:0").radius)
        assertTrue(parseLookupQuery("r:global").global)
        assertTrue(parseLookupQuery("r:#global").global)
        assertTrue(parseLookupQuery("r:none").global)
        assertThrows(CommandSyntaxException::class.java) { parseLookupQuery("r:${MAX_RADIUS + 1}") }
        assertThrows(CommandSyntaxException::class.java) { parseLookupQuery("r:-5") }
    }

    @Test
    fun `row counts stay inside the readable range`() {
        assertEquals(50, parseLookupQuery("rows:50").limit)
        assertThrows(CommandSyntaxException::class.java) { parseLookupQuery("rows:0") }
        assertThrows(CommandSyntaxException::class.java) { parseLookupQuery("rows:${MAX_LIMIT + 1}") }
    }

    @Test
    fun `keys are case insensitive but values keep their case`() {
        assertEquals(listOf("Steve"), parseLookupQuery("U:Steve").users)
        assertEquals(setOf(Cause.CONTAINER_ADD), parseLookupQuery("A:DEPOSIT").causes)
    }

    @Test
    fun `a whole query parses into every field at once`() {
        val query = parseLookupQuery("u:Steve t:1d r:16 a:-container b:diamond e:cobblestone rows:25")
        assertEquals(listOf("Steve"), query.users)
        assertEquals(86_400L, query.secondsBack)
        assertEquals(16, query.radius)
        assertEquals(setOf(Cause.CONTAINER_REMOVE), query.causes)
        assertEquals(listOf("diamond"), query.included)
        assertEquals(listOf("cobblestone"), query.excluded)
        assertEquals(25, query.limit)
    }

    // The point of parsing off the caller's reader is that the game underlines the token at fault, so
    // the cursor each failure carries is the behaviour worth pinning, not just the fact it threw.
    @Test
    fun `a rejected query points at the token that caused it`() {
        val cases = mapOf(
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
    fun `malformed parameters are refused instead of ignored`() {
        assertThrows(CommandSyntaxException::class.java) { parseLookupQuery("Steve") }
        assertThrows(CommandSyntaxException::class.java) { parseLookupQuery("z:1") }
        assertThrows(CommandSyntaxException::class.java) { parseLookupQuery("u:") }
        assertThrows(CommandSyntaxException::class.java) { parseLookupQuery("a:chatter") }
        assertThrows(CommandSyntaxException::class.java) { parseLookupQuery(":10") }
    }

    // The block plane's causes only became worth offering as filters once something wrote them: an
    // empty answer from a filter that sounds certain reads as "nothing happened there".
    @Test
    fun `the block plane is reachable by name and every block cause is under one of the filters`() {
        val blockRange = Cause.entries.filter { it.id in 0xD0..0xEF }.toSet() + Cause.BLK_SIGN_EDIT +
            Cause.BLK_PLAYER_SWITCH + Cause.BLK_ENTITY_SWITCH + Cause.BLK_PLAYER_USE +
            Cause.BLK_BUCKET + Cause.BLK_SPONGE
        assertEquals(38, blockRange.size)
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

    @Test
    fun `block filters parse by every spelling they offer`() {
        for (action in listOf(Action.EXPLOSION, Action.FIRE, Action.LIQUID, Action.PISTON, Action.GRAVITY)) {
            for (key in action.keys) {
                assertEquals(action.causes, parseLookupQuery("action:$key").causes, key)
            }
        }
        assertEquals(
            Action.EXPLOSION.causes + Action.FIRE.causes,
            parseLookupQuery("a:explosion,fire").causes,
        )
    }

}
