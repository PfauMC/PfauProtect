package io.pfaumc.pfauprotect

import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.logging.Logger

class PlacesTest {
    private val overworld = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val nether = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val worlds = listOf(overworld, nether)
    private val keyA = ByteArray(32) { it.toByte() }
    private val keyB = ByteArray(32) { (it + 100).toByte() }

    @BeforeEach
    fun key() = Places.useKey(keyA)

    @AfterEach
    fun reset() {
        Settings.hideCoordinates = false
    }

    @Test
    fun `a place survives the round trip at the edges of its ranges`() {
        val xz = listOf(0, 1, -1, 29_999_999, -29_999_999, (1 shl 25) - 1, -(1 shl 25))
        val ys = listOf(-64, 319, -2048, 2047, 0)
        for (world in worlds) for (x in xz) for (y in ys) for (z in xz) {
            val token = Places.encode(world, x, y, z)
            assertEquals(Place(world, x, y, z), Places.decode(token, worlds), "$world $x $y $z")
        }
    }

    @Test
    fun `a token is four groups of four characters`() {
        assertTrue(Regex("([0-9A-HJKMNP-TV-Z]{4}-){3}[0-9A-HJKMNP-TV-Z]{4}").matches(Places.encode(overworld, 1, 2, 3)))
    }

    @Test
    fun `coordinates out of the packed ranges are refused`() {
        for ((x, y, z) in listOf(Triple(1 shl 25, 0, 0), Triple(-(1 shl 25) - 1, 0, 0), Triple(0, 2048, 0), Triple(0, -2049, 0), Triple(0, 0, 1 shl 25))) {
            assertThrows(IllegalArgumentException::class.java) { Places.encode(overworld, x, y, z) }
        }
    }

    @Test
    fun `the same coordinates give another token in another world or under another key`() {
        val token = Places.encode(overworld, 10, 64, -10)
        assertNotEquals(token, Places.encode(nether, 10, 64, -10))
        Places.useKey(keyB)
        assertNotEquals(token, Places.encode(overworld, 10, 64, -10))
    }

    @Test
    fun `neighbouring places have unrelated tokens`() {
        assertNotEquals(Places.encode(overworld, 10, 64, 10).take(4), Places.encode(overworld, 11, 64, 10).take(4))
    }

    @Test
    fun `garbage and wrong lengths decode to nothing`() {
        for (bad in listOf("", "hello", "0000-0000-0000", "0000-0000-0000-0000-0", "UUUU-UUUU-UUUU-UUUU", "1,2,3", "0000 0000 0000 0000")) {
            assertNull(Places.decode(bad, worlds), bad)
            assertFalse(Places.looksLikeToken(bad), bad)
        }
    }

    @Test
    fun `a token under another key or for an unlisted world decodes to nothing`() {
        val token = Places.encode(overworld, 5, 70, 5)
        assertNull(Places.decode(token, listOf(nether)))
        Places.useKey(keyB)
        assertNull(Places.decode(token, worlds))
    }

    @Test
    fun `typing a token by hand is forgiven`() {
        val token = Places.encode(overworld, -300, 12, 4096)
        val expected = Place(overworld, -300, 12, 4096)
        assertEquals(expected, Places.decode(token.lowercase(), worlds))
        assertEquals(expected, Places.decode(token.replace("-", ""), worlds))
        assertEquals(expected, Places.decode("  $token  ", worlds))
        // 1 and 0 may be typed as I, L and O.
        assertEquals(expected, Places.decode(token.replace('1', 'l').replace('0', 'O'), worlds))
        assertEquals(expected, Places.decode(token.replace('1', 'I'), worlds))
        assertTrue(Places.looksLikeToken(token.lowercase()))
    }

    @Test
    fun `a bad key file neither throws nor changes, and leaves places unreadable`(@TempDir dir: Path) {
        val token = Places.encode(overworld, 1, 2, 3)
        for (bad in listOf("not base64 !!", Base64.getEncoder().encodeToString(ByteArray(5)))) {
            val file = dir.resolve("coord-key")
            Files.writeString(file, bad)
            Places.init(dir, Logger.getAnonymousLogger())
            assertEquals("?", Places.text(overworld, 1, 2, 3))
            assertNull(Places.decode(token, worlds))
            assertEquals(bad, Files.readString(file))
        }
    }

    @Test
    fun `only a player without the permission is hidden, and only while the setting is on`() {
        fun player(permitted: Boolean): Player =
            stub(Player::class.java, mapOf("hasPermission" to { args: Array<out Any?> -> permitted && args[0] == "pfauprotect.coordinates" }))
        assertFalse(Places.hidden(player(false)))
        Settings.hideCoordinates = true
        assertTrue(Places.hidden(player(false)))
        assertFalse(Places.hidden(player(true)))
        assertFalse(Places.hidden(null))
    }
}
