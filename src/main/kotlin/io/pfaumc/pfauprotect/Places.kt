package io.pfaumc.pfauprotect

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.logging.Logger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What [Places.text] prints when no token can be made. */
const val UNKNOWN_PLACE = "?"

/** A block position in a world, as a [Places] token stands for it. */
data class Place(val world: UUID, val x: Int, val y: Int, val z: Int)

/**
 * Places as opaque tokens, for players who may not see coordinates. A token is the position run through a keyed
 * permutation, not a hash: the server turns it back without any index of positions (the block log has none),
 * while nobody without the key can read a coordinate off it or make one up for a place they have not been told.
 *
 * Rotating the key means deleting `coord-key` and restarting; every token handed out before stops decoding.
 */
object Places {
    private const val KEY_FILE = "coord-key"
    private const val KEY_BYTES = 32
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val CHARS = 16
    private const val GROUP = 4
    private const val ROUNDS = 8
    private const val HALF_BITS = 40
    private const val HALF_MASK = (1L shl HALF_BITS) - 1
    private const val HALF_BYTES = 5

    private const val TAG_BITS = 16
    private const val XZ_BITS = 26
    private const val Y_BITS = 12

    @Volatile private var key: ByteArray? = null

    fun init(dataFolder: Path, log: Logger) {
        val file = dataFolder.resolve(KEY_FILE)
        if (Files.exists(file)) {
            // A damaged key is neither replaced (that would silently orphan every token already given out) nor
            // fatal: without a key hidden viewers see "?" and the journal still starts.
            key = runCatching {
                Base64.getDecoder().decode(Files.readString(file).trim()).also {
                    require(it.size == KEY_BYTES) { "expected $KEY_BYTES bytes, got ${it.size}" }
                }
            }.onFailure {
                log.severe("$file is unusable (${it.message}); places are shown as '?' to viewers who may not see coordinates. Delete the file and restart to make a new key (old tokens then stop working).")
            }.getOrNull()
            return
        }
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        // Written whole and moved into place, so a crash mid-write leaves no half a key. A failure here must not
        // keep the journal from opening: the key stays unset (viewers see "?") and the key is published only once saved.
        val written = file.resolveSibling("$KEY_FILE.tmp")
        runCatching {
            Files.createDirectories(dataFolder)
            Files.writeString(written, Base64.getEncoder().encodeToString(fresh))
            Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onSuccess { key = fresh }.onFailure {
            key = null
            runCatching { Files.deleteIfExists(written) }
            log.severe("Cannot save $file (${it.message}); places are shown as '?' to viewers who may not see coordinates until it can be written.")
        }
    }

    internal fun useKey(key: ByteArray) {
        this.key = key.copyOf()
    }

    fun encode(world: UUID, x: Int, y: Int, z: Int): String {
        require(x in signedRange(XZ_BITS) && z in signedRange(XZ_BITS)) { "x/z out of range: $x $z" }
        require(y in signedRange(Y_BITS)) { "y out of range: $y" }
        var plain = BigInteger.valueOf(tagOf(world).toLong())
        for ((value, bits) in listOf(x to XZ_BITS, y to Y_BITS, z to XZ_BITS)) {
            plain = plain.shiftLeft(bits).or(BigInteger.valueOf(value.toLong() and ((1L shl bits) - 1)))
        }
        var left = plain.shiftRight(HALF_BITS).toLong()
        var right = plain.toLong() and HALF_MASK
        for (round in 0 until ROUNDS) {
            val next = left xor round(round, right)
            left = right
            right = next
        }
        val cipher = BigInteger.valueOf(left).shiftLeft(HALF_BITS).or(BigInteger.valueOf(right))
        val text = CharArray(CHARS) { ALPHABET[cipher.shiftRight(5 * (CHARS - 1 - it)).toInt() and 31] }
        return String(text).chunked(GROUP).joinToString("-")
    }

    fun decode(token: String, worlds: Collection<UUID> = loadedWorlds()): Place? {
        val text = normalize(token) ?: return null
        if (key == null) return null
        var cipher = BigInteger.ZERO
        for (c in text) cipher = cipher.shiftLeft(5).or(BigInteger.valueOf(ALPHABET.indexOf(c).toLong()))
        var left = cipher.shiftRight(HALF_BITS).toLong()
        var right = cipher.toLong() and HALF_MASK
        for (round in ROUNDS - 1 downTo 0) {
            val previous = right xor round(round, left)
            right = left
            left = previous
        }
        val plain = BigInteger.valueOf(left).shiftLeft(HALF_BITS).or(BigInteger.valueOf(right))
        val z = signed(plain.toLong(), XZ_BITS)
        val y = signed(plain.shiftRight(XZ_BITS).toLong(), Y_BITS)
        val x = signed(plain.shiftRight(XZ_BITS + Y_BITS).toLong(), XZ_BITS)
        val tag = plain.shiftRight(XZ_BITS + Y_BITS + XZ_BITS).toInt()
        val world = worlds.firstOrNull { tagOf(it) == tag } ?: return null
        return Place(world, x, y, z)
    }

    /** Cheap syntactic check, so a parser can tell a token from "x,y,z" before asking the key. */
    fun looksLikeToken(s: String): Boolean = normalize(s) != null

    private val NUMERIC_TP = Regex("^(/?pp tp) -?\\d+ -?\\d+ -?\\d+ \\S+", RegexOption.IGNORE_CASE)

    /** A typed `/pp tp x y z world` with the position blanked, for text a hidden reader gets. */
    fun redactTeleport(text: String): String = text.replace(NUMERIC_TP, "$1 $UNKNOWN_PLACE")

    /** Whether [viewer] gets tokens in place of coordinates; the console (null too) always reads the real ones. */
    fun hidden(viewer: CommandSender?): Boolean =
        Settings.hideCoordinates && viewer != null && viewer !is ConsoleCommandSender && !viewer.hasPermission(COORDINATES_PERMISSION)

    // Typed by hand from a screen: case, dashes and the look-alikes of 1 and 0 are forgiven.
    private fun normalize(token: String): String? {
        val text = token.trim().uppercase().replace("-", "").replace('I', '1').replace('L', '1').replace('O', '0')
        return text.takeIf { it.length == CHARS && it.all { c -> c in ALPHABET } }
    }

    /** The worlds a token may name; tests that parse commands without a server put theirs here. */
    @Volatile internal var loaded: () -> Collection<UUID> = {
        runCatching { Bukkit.getWorlds().map { it.uid } }.getOrDefault(emptyList())
    }

    private fun loadedWorlds(): Collection<UUID> = loaded()

    /**
     * [encode] for text a player reads: a position outside the packed ranges (never logged) gets a placeholder,
     * not a crash. The placeholder is no valid token, so it can never decode to some real place.
     */
    fun text(world: UUID, x: Int, y: Int, z: Int): String =
        runCatching { encode(world, x, y, z) }.getOrDefault(UNKNOWN_PLACE)

    /**
     * What a command the plugin builds carries for a place. A click lands in the chat log whatever the clicker may
     * read, and `/pp chat` shows it to hidden readers, so with the setting on every such command carries a token.
     * Off, only a [pin] (the reader is hidden, or the place is in another world than the command runs in) does.
     * Null: coordinates are fine. [UNKNOWN_PLACE]: a token was due and none can be made, so the caller leaves the place out.
     */
    fun forCommand(world: UUID, x: Int, y: Int, z: Int, pin: Boolean): String? =
        if (Settings.hideCoordinates || pin) text(world, x, y, z) else null

    private fun signedRange(bits: Int) = -(1 shl (bits - 1))..((1 shl (bits - 1)) - 1)

    private fun signed(value: Long, bits: Int): Int = ((value shl (64 - bits)) shr (64 - bits)).toInt()

    private fun mac(): Mac {
        val key = checkNotNull(key) { "Places.init was not called" }
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
    }

    // First two bytes only: the tag exists to name the world, and two worlds sharing one is the price of 80 bits.
    private fun tagOf(world: UUID): Int {
        val h = mac().doFinal("world:$world".toByteArray(Charsets.UTF_8))
        return (h[0].toInt() and 0xFF shl 8) or (h[1].toInt() and 0xFF)
    }

    private fun round(i: Int, half: Long): Long {
        val input = ByteArray(1 + HALF_BYTES)
        input[0] = i.toByte()
        for (b in 0 until HALF_BYTES) input[1 + b] = (half shr (8 * (HALF_BYTES - 1 - b))).toByte()
        val h = mac().doFinal(input)
        var out = 0L
        for (b in 0 until HALF_BYTES) out = (out shl 8) or (h[b].toLong() and 0xFF)
        return out
    }
}
