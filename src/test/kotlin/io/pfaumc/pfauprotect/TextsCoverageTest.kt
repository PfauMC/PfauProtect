package io.pfaumc.pfauprotect

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Every message the plugin writes is read out of its sources, as `say("...")` or an error's
 * `LiteralMessage("...")`, with whatever it fills in set to a stand-in, and has to come out in Russian. A
 * message built in a variable first is not seen here; a live run on a server started with PPT_LANG=ru
 * reads those.
 */
class TextsCoverageTest {
    @AfterEach
    fun english() {
        Settings.language = "en"
    }

    @Test
    fun `every message the sources write comes out in Russian`() {
        Settings.language = "ru"
        val untranslated = Files.walk(Path.of("src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" }.toList()
        }.flatMap { file ->
            messagesIn(file.readText()).map { file.fileName.toString() to it }
        }.mapNotNull { (file, message) ->
            val out = Texts.translate(message)
            if (english(out)) "$file: $message -> $out" else null
        }.distinct()
        assertEquals(emptyList<String>(), untranslated)
    }

    private fun messagesIn(source: String): List<String> {
        val out = ArrayList<String>()
        for (call in Regex("""\b(say|sayNamed|said|LiteralMessage)\(""").findAll(source)) {
            var i = call.range.last + 1
            val text = StringBuilder()
            var whole = true
            while (true) {
                while (i < source.length && source[i].isWhitespace()) i++
                if (i >= source.length || source[i] != '"' || source.startsWith("\"\"\"", i)) break
                i = literal(source, i, text)
                var j = i
                while (j < source.length && source[j].isWhitespace()) j++
                if (j >= source.length || source[j] != '+') break
                i = j + 1
                while (i < source.length && source[i].isWhitespace()) i++
                // Joined to something that is not a string: what it ends with is not known here.
                if (source[i] != '"') whole = false
            }
            if (text.isNotEmpty() && whole && COMPOSED.none { text.startsWith(it) }) out += text.toString()
        }
        // A refusal handed back by a `when` and said by the caller.
        for (branch in Regex("""->\s*("[A-Z][^"$]* [^"$]*")""").findAll(source)) {
            val text = StringBuilder()
            literal(source, branch.groups[1]!!.range.first, text)
            out += text.toString()
        }
        return out
    }

    // Reads one string literal from its opening quote, writing what it says with each template filled by
    // the first string inside it (a choice of two words) or by a number; returns the index past its end.
    private fun literal(source: String, start: Int, into: StringBuilder): Int {
        var i = start + 1
        while (i < source.length && source[i] != '"') {
            val c = source[i]
            when {
                c == '\\' -> {
                    into.append(
                        when (source[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            else -> source[i + 1]
                        },
                    )
                    i += 2
                }
                c == '$' && source.getOrNull(i + 1) == '{' -> {
                    var depth = 1
                    var j = i + 2
                    var inner: String? = null
                    while (depth > 0) {
                        when (source[j]) {
                            '{' -> depth++
                            '}' -> depth--
                            '"' -> {
                                val sb = StringBuilder()
                                j = literal(source, j, sb) - 1
                                if (inner == null) inner = sb.toString()
                            }
                        }
                        j++
                    }
                    // A choice of words stands as its first; anything else a number fills in.
                    val choice = source.substring(i + 2).trimStart().let { it.startsWith("if ") || it.startsWith("when") }
                    into.append(if (choice) inner ?: "7" else "7")
                    i = j
                }
                c == '$' && source.getOrNull(i + 1)?.isLetter() == true -> {
                    var j = i + 1
                    while (j < source.length && (source[j].isLetterOrDigit() || source[j] == '_')) j++
                    into.append("7")
                    i = j
                }
                else -> {
                    into.append(c)
                    i++
                }
            }
        }
        return i + 1
    }

    // Made of parts that are sentences of their own, which a number cannot stand in for; T1 reads them live.
    private val COMPOSED = listOf("  would swap back ", "  swapping back ")

    // What is left once names, ids, causes, commands and parameters are taken out: a Latin word is English.
    private fun english(line: String): Boolean {
        val rest = line
            .replace(Regex("""minecraft:[a-z0-9_:/.\[\]=,-]+"""), " ")
            .replace(Regex("""/pp( [a-z]+)?"""), " ")
            .replace(Regex("""/\S+"""), " ")
            .replace(Regex("""\b[a-z0-9]+(_[a-z0-9]+)+\b"""), " ")
            .replace(Regex("""#[a-z]+"""), " ")
            .replace(Regex("""\b[a-z]+:\S*"""), " ")
            .replace(Regex("""\b(PfauProtect|MB|KB|GB|UUID|RocksDB|NBT|ru|en)\b"""), " ")
            .replace(Regex("""'[^']*'"""), " ")
        return Regex("[A-Za-z]{3,}").containsMatchIn(rest)
    }
}
