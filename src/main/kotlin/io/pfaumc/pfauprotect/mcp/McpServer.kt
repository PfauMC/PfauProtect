package io.pfaumc.pfauprotect.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.pfaumc.pfauprotect.api.Access
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The MCP add-on: Streamable HTTP at `/mcp`, every message a POST answered by one JSON body, with no sessions and
 * no stream. Who calls is told by `Authorization: Bearer`: a player's own token, or a trusted gateway's secret with
 * the player it acts for in `X-PfauProtect-Player` and, for an offline one, their permissions in
 * `X-PfauProtect-Permissions`. Every call goes into the audit log, without a token or a secret.
 */
internal class McpServer(
    address: String,
    port: Int,
    private val tools: McpTools,
    private val tokens: Tokens,
    private val secret: String,
    private val version: String,
    auditFile: Path,
    private val logger: Logger,
    private val nameOf: (UUID) -> String? = { null },
) : AutoCloseable {
    private val audit: BufferedWriter = Files.newBufferedWriter(auditFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    // Bound after the log is open, and a port already taken closes the log again: neither is left behind.
    private val server: HttpServer = try {
        HttpServer.create(InetSocketAddress(address, port), 0)
    } catch (failure: Exception) {
        audit.close()
        throw failure
    }
    private val threads: ExecutorService = Executors.newFixedThreadPool(THREADS) { Thread(it, "pfauprotect-mcp").apply { isDaemon = true } }

    init {
        server.createContext("/mcp") { exchange ->
            try {
                handle(exchange)
            } catch (failure: Throwable) {
                logger.log(Level.WARNING, "an MCP request failed", failure)
                runCatching { send(exchange, 500, error(JsonNull.INSTANCE, INTERNAL_ERROR, "internal error")) }
            } finally {
                exchange.close()
            }
        }
        server.executor = threads
        server.start()
    }

    val port: Int get() = server.address.port

    override fun close() {
        server.stop(0)
        threads.shutdownNow()
        synchronized(audit) { audit.close() }
    }

    private class Caller(val access: Access, val mode: String)

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.add("Allow", "POST")
            return send(exchange, 405, null)
        }
        val caller = when (val who = callerOf(exchange)) {
            is Int -> {
                if (who == 401) exchange.responseHeaders.add("WWW-Authenticate", "Bearer")
                return send(exchange, who, null)
            }
            else -> who as Caller
        }
        val body = exchange.requestBody.readNBytes(MAX_BODY + 1)
        if (body.size > MAX_BODY) return send(exchange, 413, null)
        val message = runCatching { JsonParser.parseString(body.toString(Charsets.UTF_8)) }.getOrNull()
        if (message == null || !message.isJsonObject) {
            return send(exchange, 400, error(JsonNull.INSTANCE, if (message == null) PARSE_ERROR else INVALID_REQUEST, "expected one JSON-RPC message"))
        }
        val request = message.asJsonObject
        val method = request.get("method")?.takeIf { it.isJsonPrimitive }?.asString
        val id = request.get("id")
        // A notification, or a response to something this server never asks, wants no answer.
        if (method == null || id == null || id.isJsonNull) return send(exchange, 202, null)
        val params = request.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val answer = answer(id, method, params, caller, exchange.requestHeaders.getFirst("X-Request-Id"))
        send(exchange, 200, answer)
    }

    // A Caller, or the status that turns the request away; which credential failed is not said.
    private fun callerOf(exchange: HttpExchange): Any {
        val bearer = exchange.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
            ?: return 401
        if (bearer.startsWith(Tokens.PREFIX)) {
            return tokens.playerOf(bearer)?.let { Caller(Access(it), "token") } ?: 401
        }
        if (secret.isEmpty() || !MessageDigest.isEqual(bearer.toByteArray(Charsets.UTF_8), secret.toByteArray(Charsets.UTF_8))) return 401
        val player = exchange.requestHeaders.getFirst("X-PfauProtect-Player")?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
            ?: return 400
        // Present but empty is no permission at all: the gateway knows the player and found none.
        val granted = exchange.requestHeaders.getFirst("X-PfauProtect-Permissions")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        return Caller(Access(player, granted), "gateway")
    }

    private fun answer(id: JsonElement, method: String, params: JsonObject, caller: Caller, requestId: String?): JsonObject {
        var tool: String? = null
        var arguments: JsonObject? = null
        var outcome = "ok"
        val response = try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject())
                "tools/list" -> result(id, JsonObject().apply { add("tools", tools.list) })
                "tools/call" -> {
                    tool = params.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: throw BadArguments("tools/call needs a name")
                    arguments = params.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                    val done = tools.call(tool, arguments, caller.access, token = caller.mode == "token")
                    if (done.isError) outcome = done.structured.asJsonObject.getAsJsonObject("error").get("code").asString
                    result(id, toolResult(done))
                }
                "resources/list" -> result(id, JsonObject().apply { add("resources", JsonArray().apply { add(HOWTO_ENTRY) }) })
                "resources/read" -> {
                    val uri = params.get("uri")?.takeIf { it.isJsonPrimitive }?.asString
                    if (uri != HOWTO_URI) throw BadArguments("unknown resource: $uri")
                    result(id, JsonObject().apply {
                        add("contents", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("uri", HOWTO_URI)
                                addProperty("mimeType", "text/markdown")
                                addProperty("text", tools.howto)
                            })
                        })
                    })
                }
                else -> {
                    outcome = "unknown_method"
                    error(id, METHOD_NOT_FOUND, "unknown method: $method")
                }
            }
        } catch (bad: BadArguments) {
            outcome = "bad_arguments"
            error(id, INVALID_PARAMS, bad.message.orEmpty())
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "the MCP tool $tool failed", failure)
            outcome = FAILED
            result(id, toolResult(refusal(FAILED, "The call failed; the server log has the details.")))
        }
        record(caller, method, tool, arguments, requestId, outcome)
        return response
    }

    // The version asked for when it is one of ours, else the newest we speak, as the protocol has it.
    private fun initialize(params: JsonObject) = JsonObject().apply {
        val asked = params.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asString
        addProperty("protocolVersion", asked?.takeIf { it in PROTOCOL_VERSIONS } ?: PROTOCOL_VERSIONS.first())
        add("capabilities", JsonObject().apply {
            add("tools", JsonObject().apply { addProperty("listChanged", false) })
            add("resources", JsonObject())
        })
        add("serverInfo", JsonObject().apply {
            addProperty("name", "PfauProtect")
            addProperty("version", version)
        })
        addProperty("instructions", INSTRUCTIONS)
    }

    private fun toolResult(done: ToolResult) = JsonObject().apply {
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", GSON.toJson(done.structured))
            })
        })
        add("structuredContent", done.structured)
        addProperty("isError", done.isError)
    }

    private fun record(caller: Caller, method: String, tool: String?, arguments: JsonObject?, requestId: String?, outcome: String) {
        val line = JsonObject().apply {
            addProperty("time", Instant.now().toString())
            addProperty("mode", caller.mode)
            addProperty("player", caller.access.player.toString())
            nameOf(caller.access.player)?.let { addProperty("name", it) }
            addProperty("method", method)
            tool?.let { addProperty("tool", it) }
            arguments?.let { add("arguments", it) }
            requestId?.let { addProperty("request_id", it.take(MAX_REQUEST_ID)) }
            addProperty("outcome", outcome)
        }
        runCatching {
            synchronized(audit) {
                audit.write(GSON.toJson(line))
                audit.newLine()
                audit.flush()
            }
        }.onFailure { logger.log(Level.WARNING, "the MCP audit log could not be written", it) }
    }

    private fun send(exchange: HttpExchange, status: Int, body: JsonObject?) {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        val bytes = GSON.toJson(body).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun result(id: JsonElement, result: JsonObject) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("error", JsonObject().apply {
            add("code", JsonPrimitive(code))
            addProperty("message", message)
        })
    }
}

internal const val HOWTO_URI = "pfauprotect://howto/investigate"

private val HOWTO_ENTRY = JsonObject().apply {
    addProperty("uri", HOWTO_URI)
    addProperty("name", "investigate")
    addProperty("title", "How to investigate with PfauProtect")
    addProperty("description", "What the rows mean, the limits, and step by step: a theft, a griefing chain, a fight, a rollback.")
    addProperty("mimeType", "text/markdown")
}

private const val INSTRUCTIONS =
    "PfauProtect's history of one Minecraft server: blocks, items, entities, chat, commands and fights. Read $HOWTO_URI " +
        "before an investigation. Rollbacks are only previewed; the moderator applies them in the game."

// The newest first: what an unknown version is answered with.
private val PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26")

private const val THREADS = 4
private const val MAX_BODY = 1 shl 20
private const val MAX_REQUEST_ID = 128
private const val PARSE_ERROR = -32700
private const val INVALID_REQUEST = -32600
private const val METHOD_NOT_FOUND = -32601
private const val INVALID_PARAMS = -32602
private const val INTERNAL_ERROR = -32603
