package io.pfaumc.pfauprotect.mcp

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.api.Access
import io.pfaumc.pfauprotect.api.Api
import io.pfaumc.pfauprotect.api.ChatRequest
import io.pfaumc.pfauprotect.api.LookupRequest
import io.pfaumc.pfauprotect.api.PfauProtectException
import io.pfaumc.pfauprotect.command.MAX_LIMIT
import io.pfaumc.pfauprotect.rollback.PENDING_MILLIS
import org.bukkit.Bukkit
import org.bukkit.World
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Arguments a tool cannot take: a JSON-RPC error rather than a tool's own refusal. */
internal class BadArguments(message: String) : RuntimeException(message)

/** A tool's answer: what it found as JSON, or its refusal. */
internal class ToolResult(val structured: JsonElement, val isError: Boolean)

internal val GSON: Gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()

/**
 * The tools the MCP add-on offers, each a call of the [Api] made for the caller's [Access]. The words of `/pp
 * lookup` are put together from the arguments and read by its own parser, which keeps its limits.
 */
internal class McpTools(private val api: Api, private val version: String, private val worlds: () -> List<World> = Bukkit::getWorlds) {
    val list: JsonArray = JsonParser.parseString(resource("/mcp-tools.json")).asJsonArray

    val howto: String = resource("/mcp-howto.md")

    // The arguments each tool's schema names: one it does not, a typo of `radius` say, must not pass as unasked.
    private val takes: Map<String, Set<String>> = list.associate { tool ->
        val schema = tool.asJsonObject
        schema.get("name").asString to schema.getAsJsonObject("inputSchema").getAsJsonObject("properties").keySet().toSet()
    }

    /** [token]: made with a player's own token, which is theirs to use only while they still hold `pfauprotect.mcp`. */
    fun call(name: String, args: JsonObject, access: Access?, token: Boolean = false): ToolResult = try {
        val known = takes[name] ?: throw BadArguments("unknown tool: $name")
        val unknown = args.keySet() - known
        if (unknown.isNotEmpty()) throw BadArguments("$name takes no ${unknown.sorted().joinToString(", ")}")
        if (token && access != null) api.check(access, MCP_PERMISSION)
        ToolResult(GSON.toJsonTree(run(name, args, access)), false)
    } catch (refused: PfauProtectException) {
        refusal(refused.code, refused.message.orEmpty())
    }

    private fun run(name: String, args: JsonObject, access: Access?): Any = when (name) {
        "server_info" -> {
            mapOf(
                "server" to Settings.serverName,
                "version" to version,
                "worlds" to worlds().map { mapOf("name" to it.name, "uuid" to it.uid, "environment" to it.environment.name.lowercase()) },
                "limits" to mapOf(
                    "max_radius" to Settings.maxRadius,
                    "max_rows" to MAX_LIMIT,
                    "preview_minutes" to PENDING_MILLIS / 60_000,
                    "max_rollback_positions" to Settings.maxRollbackPositions,
                ),
            )
        }
        "lookup" -> wait(api.lookupAs(placed(args, words(args, LOOKUP_WORDS)), access, here = true))
        "container_history" -> {
            val radius = args.int("radius")?.takeIf { it > 0 }
            // One position reads its contents alone, which only a read without a radius keeps apart from the block;
            // an area reads the container rows of every chest in it.
            val part = if (radius == null) "part:contents" else "r:$radius a:container"
            val steal = if (args.bool("steal") == true) " a:steal" else ""
            wait(api.lookupAs(placed(args, words(args, CONTAINER_WORDS) + " $part$steal"), access, here = true))
        }
        "actor_actions" -> {
            if (args.list("users").isEmpty()) throw BadArguments("actor_actions needs users")
            wait(api.actorActionsAs(LookupRequest(words = words(args, ACTOR_WORDS), since = args.moment("since"), until = args.moment("until")), access))
        }
        "chat_pvp_log" -> {
            val world = args.string("world")?.let(::worldOf)
            val request = ChatRequest(
                words = words(args, CHAT_WORDS), since = args.moment("since"), until = args.moment("until"),
                kinds = args.list("kinds").toSet(), world = world?.uid,
                x = args.int("x") ?: 0, y = args.int("y") ?: 0, z = args.int("z") ?: 0, radius = args.int("radius"),
            )
            if (request.radius != null && (world == null || listOf("x", "y", "z").any { args.int(it) == null })) {
                throw BadArguments("radius needs world, x, y and z")
            }
            wait(api.chatAs(request, access))
        }
        "start_preview" -> {
            access ?: throw BadArguments("start_preview is made for a player")
            val world = args.string("world")?.let(::worldOf)
            if (world != null && listOf("x", "y", "z").any { args.int(it) == null }) throw BadArguments("world needs x, y and z")
            val request = LookupRequest(world?.uid, args.int("x") ?: 0, args.int("y") ?: 0, args.int("z") ?: 0, words(args, PREVIEW_WORDS), args.moment("since"), args.moment("until"))
            // A big area reads longer than a gateway waits: it is then running, and preview_status tells the rest.
            try {
                wait(api.previewAs(access, request), PREVIEW_SECONDS)
            } catch (_: TimeoutException) {
                api.previewStatusAs(access)
            }
        }
        "preview_status" -> {
            api.previewStatusAs(access ?: throw BadArguments("preview_status is asked for a player"))
        }
        else -> throw BadArguments("unknown tool: $name")
    }

    private fun placed(args: JsonObject, words: String): LookupRequest {
        val world = worldOf(args.string("world") ?: throw BadArguments("world is required"))
        val (x, y, z) = listOf("x", "y", "z").map { args.int(it) ?: throw BadArguments("$it is required") }
        return LookupRequest(world.uid, x, y, z, words, args.moment("since"), args.moment("until"))
    }

    private fun worldOf(name: String): World =
        worlds().firstOrNull { it.name == name || it.uid.toString() == name } ?: throw BadArguments("unknown world: $name")

    // Each argument a tool takes and the word it stands for; a value with a space in it would be read as two words.
    private fun words(args: JsonObject, takes: Set<String>): String {
        val out = ArrayList<String>()
        fun word(text: String): String {
            if (text.isBlank() || text.any { it.isWhitespace() }) throw BadArguments("'$text' cannot hold spaces or be empty")
            return text
        }
        fun list(key: String, prefix: String) = args.list(key).takeIf { it.isNotEmpty() }?.let { out += prefix + it.joinToString(",", transform = ::word) }
        for (key in takes) when (key) {
            "users" -> list("users", "u:")
            "actions" -> list("actions", "a:")
            "include" -> list("include", "i:")
            "exclude" -> list("exclude", "e:")
            "time" -> args.string("time")?.let { out += "t:" + word(it) }
            "radius" -> args.int("radius")?.let { out += "r:$it" }
            "amount" -> args.string("amount")?.let { out += "amount:" + word(it) }
            "rolledback" -> args.bool("rolledback")?.let { out += "rolledback:" + if (it) "yes" else "no" }
            "steal" -> if (args.bool("steal") == true) out += "a:steal"
            "text" -> args.string("text")?.let { out += "f:" + word(it) }
            "pvp" -> if (args.bool("pvp") == true) out += "a:pvp"
            "global" -> if (args.bool("global") == true) out += "a:global"
            "local" -> if (args.bool("local") == true) out += "a:local"
            "page" -> args.int("page")?.let { out += "page:$it" }
            "limit" -> args.int("limit")?.let { out += "limit:$it" }
            "all" -> if (args.bool("all") == true) out += "#all"
            "words" -> args.string("words")?.let { out += it }
        }
        return out.joinToString(" ")
    }

    private fun <T> wait(future: CompletableFuture<T>, seconds: Long = CALL_SECONDS): T = try {
        future.get(seconds, TimeUnit.SECONDS)
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    } catch (late: TimeoutException) {
        // The preview's own wait is answered by its status; any other read past this is too wide.
        if (seconds == PREVIEW_SECONDS) throw late
        throw PfauProtectException(FAILED, "The read took longer than $seconds s; narrow the time, the radius or the players.")
    }
}

internal fun refusal(code: String, message: String) =
    ToolResult(JsonObject().apply { add("error", JsonObject().apply { addProperty("code", code); addProperty("message", message) }) }, true)

private fun JsonObject.string(key: String): String? = get(key)?.takeIf { !it.isJsonNull }?.let {
    if (!it.isJsonPrimitive) throw BadArguments("$key must be a string")
    it.asString
}

private fun JsonObject.int(key: String): Int? = get(key)?.takeIf { !it.isJsonNull }?.let {
    runCatching { it.asJsonPrimitive.asNumber.toInt().takeIf { n -> n.toDouble() == it.asDouble } }.getOrNull() ?: throw BadArguments("$key must be a whole number")
}

private fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf { !it.isJsonNull }?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) throw BadArguments("$key must be true or false")
    it.asBoolean
}

private fun JsonObject.list(key: String): List<String> {
    val value = get(key)?.takeIf { !it.isJsonNull } ?: return emptyList()
    if (!value.isJsonArray) throw BadArguments("$key must be a list of strings")
    return value.asJsonArray.map { if (it.isJsonPrimitive) it.asString else throw BadArguments("$key must be a list of strings") }
}

// Epoch millis, or ISO-8601 with its offset: an incident's time is handed over as a moment of somebody's clock.
internal fun JsonObject.moment(key: String): Long? = get(key)?.takeIf { !it.isJsonNull }?.let {
    val primitive = it.takeIf { e -> e.isJsonPrimitive }?.asJsonPrimitive ?: throw BadArguments("$key must be epoch millis or ISO-8601")
    if (primitive.isNumber) return@let primitive.asLong
    primitive.asString.toLongOrNull() ?: runCatching { OffsetDateTime.parse(primitive.asString).toInstant().toEpochMilli() }.getOrNull()
        ?: throw BadArguments("$key must be epoch millis or ISO-8601 with an offset, like 2026-11-04T14:00:00+03:00")
}

private fun resource(path: String): String = McpTools::class.java.getResource(path)!!.readText()

internal const val FAILED = "failed"

private const val MCP_PERMISSION = "pfauprotect.mcp"

private const val CALL_SECONDS = 18L
private const val PREVIEW_SECONDS = 15L

private val LOOKUP_WORDS = setOf("users", "time", "radius", "actions", "include", "exclude", "amount", "rolledback", "steal", "page", "limit", "all", "words")
private val CONTAINER_WORDS = setOf("users", "time", "page", "limit", "all")
private val ACTOR_WORDS = setOf("users", "time", "actions", "include", "exclude", "amount", "rolledback", "page", "limit", "all")
private val CHAT_WORDS = setOf("users", "time", "text", "pvp", "global", "local", "exclude", "page", "limit", "all")
private val PREVIEW_WORDS = setOf("users", "time", "radius", "actions", "include", "exclude", "words")
