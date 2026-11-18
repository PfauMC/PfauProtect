package io.pfaumc.pfauprotect.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.pfaumc.pfauprotect.AIR
import io.pfaumc.pfauprotect.BothPlanes
import io.pfaumc.pfauprotect.Places
import io.pfaumc.pfauprotect.STONE
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.api.Api
import io.pfaumc.pfauprotect.api.allowed
import io.pfaumc.pfauprotect.command.Lookups
import io.pfaumc.pfauprotect.storage.ChatLog
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.stub
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.logging.Logger

@ExtendWith(Vanilla::class)
class McpServerTest : BothPlanes() {
    private lateinit var chat: ChatLog
    private lateinit var tokens: Tokens
    private lateinit var server: McpServer
    private var tokenHoldsMcp = true
    private val client = HttpClient.newHttpClient()

    @BeforeEach
    fun start() {
        chat = ChatLog(dir.resolve("chat"))
        tokens = Tokens(dir.resolve("mcp-tokens.yml"))
        val lookups = Lookups(stub(Plugin::class.java), shared, logs, ItemFormCodec(shared.registries, ServerRegistries.access), { null }, { null })
        // Nobody is online: what the gateway vouches for counts, and for a token's owner the server says they
        // hold everything but pfauprotect.mcp unless the test grants that too.
        val api = Api(stub(Plugin::class.java), lookups, chat, logs, { _, _, _, _ -> }, { null }, { null }) { access, permission ->
            if (access.granted != null) allowed(access, permission) { null } else permission != "pfauprotect.mcp" || tokenHoldsMcp
        }
        server = McpServer("127.0.0.1", 0, McpTools(api, "test", worlds = { listOf(stub(World::class.java, mapOf("getUID" to world, "getName" to "world", "getEnvironment" to World.Environment.NORMAL))) }), tokens, SECRET, "test", dir.resolve("audit.log"), Logger.getAnonymousLogger())
    }

    @AfterEach
    fun stop() {
        server.close()
        chat.close()
    }

    private fun post(body: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/mcp")).POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> request.header(name, value) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun gateway(vararg more: Pair<String, String>) =
        arrayOf("Authorization" to "Bearer $SECRET", "X-PfauProtect-Player" to alice.toString(), *more)

    private fun call(tool: String, arguments: String, vararg headers: Pair<String, String>): JsonObject {
        val response = post("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""", *headers)
        assertEquals(200, response.statusCode(), response.body())
        return JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("result")
    }

    @Test
    fun `a gateway secret anyone could guess turns the gateway mode off`() {
        fun loaded(secret: String) = YamlConfiguration().apply { set("mcp.gateway-secret", secret) }
            .let { Settings.load(it, Logger.getAnonymousLogger()); Settings.mcpGatewaySecret }
        try {
            assertEquals("", loaded("short"))
            assertEquals("", loaded("ppt_" + "x".repeat(40)))
            assertEquals("s".repeat(32), loaded("s".repeat(32)))
        } finally {
            Settings.mcpGatewaySecret = ""
        }
    }

    @Test
    fun `the add-on is off unless the config turns it on`() {
        val shipped = YamlConfiguration.loadConfiguration(javaClass.getResourceAsStream("/config.yml")!!.reader())
        assertFalse(shipped.getBoolean("mcp.enabled", true))
        assertFalse(Settings.mcpEnabled)
    }

    @Test
    fun `a gateway is answered with the version it asked for, or the newest one`() {
        fun initialize(version: String) = post(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
            *gateway(),
        )
        val known = JsonParser.parseString(initialize("2025-03-26").body()).asJsonObject
        assertEquals("2025-03-26", known.getAsJsonObject("result").get("protocolVersion").asString)
        val unknown = JsonParser.parseString(initialize("1999-01-01").body()).asJsonObject
        assertEquals("2025-06-18", unknown.getAsJsonObject("result").get("protocolVersion").asString)
        // A notification has no answer, and tools are listed without initialize first: there are no sessions.
        assertEquals(202, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", *gateway()).statusCode())
        val listed = JsonParser.parseString(post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", *gateway()).body()).asJsonObject
        assertTrue(listed.getAsJsonObject("result").getAsJsonArray("tools").any { it.asJsonObject.get("name").asString == "start_preview" })
    }

    @Test
    fun `whoever cannot say who they are is turned away`() {
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        assertEquals(401, post(ping).statusCode())
        assertEquals(401, post(ping, "Authorization" to "Bearer wrong", "X-PfauProtect-Player" to alice.toString()).statusCode())
        assertEquals(400, post(ping, "Authorization" to "Bearer $SECRET").statusCode())
        assertEquals(401, post(ping, "Authorization" to "Bearer ${Tokens.PREFIX}made-up").statusCode())
        val get = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/mcp")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(405, get.statusCode())
    }

    @Test
    fun `a player's own token works until it is revoked or replaced`() {
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val first = tokens.create(alice)
        assertEquals(200, post(ping, "Authorization" to "Bearer $first").statusCode())
        val second = tokens.create(alice)
        assertEquals(401, post(ping, "Authorization" to "Bearer $first").statusCode())
        // Kept across a restart, as its hash only.
        assertEquals(alice, Tokens(dir.resolve("mcp-tokens.yml")).playerOf(second))
        assertFalse(Files.readString(dir.resolve("mcp-tokens.yml")).contains(second))
        tokens.revoke(alice)
        assertEquals(401, post(ping, "Authorization" to "Bearer $second").statusCode())
    }

    // The gateway found no permission for the player: the call is refused, and the next one with it goes through.
    @Test
    fun `a call without the permission is refused as the tool's own error`() {
        val arguments = """{"world":"$world","x":0,"y":64,"z":0}"""
        val refused = call("lookup", arguments, *gateway("X-PfauProtect-Permissions" to "", "X-Request-Id" to "req-1"))
        assertTrue(refused.get("isError").asBoolean)
        assertEquals("forbidden", refused.getAsJsonObject("structuredContent").getAsJsonObject("error").get("code").asString)

        val answered = call("lookup", arguments, *gateway("X-PfauProtect-Permissions" to "pfauprotect.lookup"))
        assertFalse(answered.get("isError").asBoolean, "$answered")

        val audit = Files.readAllLines(dir.resolve("audit.log")).map { JsonParser.parseString(it).asJsonObject }
        assertEquals(listOf("forbidden", "ok"), audit.map { it.get("outcome").asString })
        assertEquals("req-1", audit.first().get("request_id").asString)
        assertEquals("lookup", audit.first().get("tool").asString)
        assertFalse(Files.readString(dir.resolve("audit.log")).contains(SECRET))
    }

    // A token is its owner's only while they still hold pfauprotect.mcp; losing it refuses every tool.
    @Test
    fun `a token whose owner lost the mcp permission is refused`() {
        val token = tokens.create(alice)
        assertFalse(call("server_info", "{}", "Authorization" to "Bearer $token").get("isError").asBoolean)
        tokenHoldsMcp = false
        val refused = call("server_info", "{}", "Authorization" to "Bearer $token").getAsJsonObject("structuredContent").getAsJsonObject("error")
        assertEquals("forbidden", refused.get("code").asString)
        assertTrue("pfauprotect.mcp" in refused.get("message").asString)
    }

    @Test
    fun `an argument the tool does not take is refused`() {
        val response = post(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"lookup","arguments":{"world":"world","x":0,"y":64,"z":0,"raduis":5}}}""",
            *gateway("X-PfauProtect-Permissions" to "pfauprotect.lookup"),
        )
        val error = JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("error")
        assertEquals(-32602, error.get("code").asInt)
        assertTrue("raduis" in error.get("message").asString)
    }

    // Unknown world: an argument the tool cannot take, which the protocol answers as invalid params.
    @Test
    fun `bad arguments are an error of the protocol`() {
        val response = post(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"lookup","arguments":{"world":"nowhere","x":0,"y":0,"z":0,"time":"1h","since":5}}}""",
            *gateway("X-PfauProtect-Permissions" to "pfauprotect.lookup"),
        )
        assertEquals(-32602, JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("error").get("code").asInt)
    }

    // What a caller without pfauprotect.coordinates reads is a token, and the token reads the same place back.
    @Test
    fun `a caller who may not read coordinates gets a place token and can pass it back`() {
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.drain()
        val before = Settings.hideCoordinates
        Places.useKey(ByteArray(32) { it.toByte() })
        Settings.hideCoordinates = true
        try {
            val blind = gateway("X-PfauProtect-Permissions" to "pfauprotect.lookup")
            val row = call("lookup", """{"world":"$world","x":10,"y":64,"z":-3}""", *blind)
                .getAsJsonObject("structuredContent").getAsJsonArray("rows")[0].asJsonObject
            val place = row.get("place").asString
            assertEquals(Places.encode(world, 10, 64, -3), place)
            assertEquals(emptyList<String>(), listOf("world", "x", "y", "z").filter { row.has(it) })

            val back = call("lookup", """{"place":"$place"}""", *blind).getAsJsonObject("structuredContent").getAsJsonArray("rows")
            assertEquals(row.get("time"), back[0].asJsonObject.get("time"))

            val unknown = JsonParser.parseString(post(
                """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"lookup","arguments":{"place":"0000-0000-0000-0000"}}}""", *blind,
            ).body()).asJsonObject.getAsJsonObject("error")
            assertEquals(-32602, unknown.get("code").asInt)
            assertEquals("unknown place", unknown.get("message").asString)

            // Whoever holds the permission reads the real position, and may use a token all the same.
            val sighted = gateway("X-PfauProtect-Permissions" to "pfauprotect.lookup,pfauprotect.coordinates")
            val plain = call("lookup", """{"place":"$place"}""", *sighted).getAsJsonObject("structuredContent").getAsJsonArray("rows")[0].asJsonObject
            assertEquals(listOf(10, 64, -3), listOf("x", "y", "z").map { plain.get(it).asInt })
            assertFalse(plain.has("place"))
        } finally {
            Settings.hideCoordinates = before
        }
    }

    private companion object {
        const val SECRET = "s3cret-of-the-gateway"
    }
}
