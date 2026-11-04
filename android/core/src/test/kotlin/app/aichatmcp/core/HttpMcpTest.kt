package app.aichatmcp.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TOKEN = "geheimer-test-token"

/** Minimaler Streamable-HTTP-MCP-Server; sse=true antwortet als Ereignisstrom (mit vorgeschalteter Benachrichtigung). */
class FakeMcpHttp {
    var sse = false
    var redirectTo = ""
    val sessions = mutableSetOf<String>()
    val sentCodes = CopyOnWriteArrayList<String>()
    val log = CopyOnWriteArrayList<Triple<String, Map<String, String>, JsonObject>>()
    private var counter = 0
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}/mcp"
    val port: Int get() = server.address.port

    init {
        server.createContext("/mcp") { ex ->
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            if (ex.requestMethod == "DELETE") {
                log += Triple("DELETE", headers, JsonObject(emptyMap()))
                sessions.remove(headers["mcp-session-id"])
                reply(ex, 200, "")
                return@createContext
            }
            val message = AppJson.parseToJsonElement(ex.requestBody.readBytes().toString(Charsets.UTF_8)) as JsonObject
            log += Triple("POST", headers, message)
            if (redirectTo.isNotEmpty()) {
                ex.responseHeaders.add("Location", redirectTo)
                ex.sendResponseHeaders(302, -1)
                ex.close()
                return@createContext
            }
            if (headers["authorization"] != "Bearer $TOKEN") return@createContext reply(ex, 401, "no")
            val method = message.string("method")
            var session: String? = null
            val result: String
            if (method == "initialize") {
                session = "s${++counter}"
                sessions += session
                result = """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"fake"}}"""
            } else if (headers["mcp-session-id"] !in sessions) {
                return@createContext reply(ex, 404, "unknown session")
            } else if ("id" !in message) {
                return@createContext reply(ex, 202, "")
            } else if (method == "tools/list") {
                result = """{"tools":[{"name":"send","description":"Sendet einen Code.","inputSchema":{"type":"object","properties":{"code":{"type":"string"}},"required":["code"]}}]}"""
            } else if (method == "tools/call") {
                val code = ((message.obj("params")!!.obj("arguments")!!)["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                sentCodes += code
                result = """{"content":[{"type":"text","text":"gesendet $code"}]}"""
            } else {
                return@createContext reply(ex, 200, """{"jsonrpc":"2.0","id":${message["id"]},"error":{"code":-32601,"message":"unbekannt"}}""")
            }
            val body = """{"jsonrpc":"2.0","id":${message["id"]},"result":$result}"""
            val extra = if (session != null) mapOf("Mcp-Session-Id" to session) else emptyMap()
            if (sse) {
                val notification = """{"jsonrpc":"2.0","method":"notifications/message","params":{"data":"hallo"}}"""
                reply(ex, 200, "event: message\ndata: $notification\n\nevent: message\ndata: $body\n\n", "text/event-stream", extra)
            } else {
                reply(ex, 200, body, "application/json", extra)
            }
        }
        server.start()
    }

    private fun reply(ex: HttpExchange, status: Int, body: String, type: String = "application/json", extra: Map<String, String> = emptyMap()) {
        ex.responseHeaders.add("Content-Type", type)
        extra.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    fun stop() = server.stop(0)
}

class HttpMcpTest {
    private val fakes = mutableListOf<FakeMcpHttp>()
    private val clients = mutableListOf<HttpMcpClient>()
    private val secrets = mapOf("FAKE_TOKEN" to TOKEN)

    @AfterTest
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        fakes.forEach { it.stop() }
    }

    private fun fake() = FakeMcpHttp().also { fakes += it }

    private fun client(f: FakeMcpHttp, token: String = "Bearer \${FAKE_TOKEN}") =
        HttpMcpClient("fake", f.url, mapOf("Authorization" to token), secret = { secrets[it] }).also { clients += it }

    @Test
    fun handshakeToolsAndCallWithJsonResponses() {
        val f = fake()
        val c = client(f).also { it.start(5.0) }
        assertEquals(listOf("send"), c.listTools().map { it.name })
        assertEquals("gesendet 0x11" to false, c.callTool("send", mapOf("code" to "0x11")))
        val methods = f.log.map { it.third.string("method") }
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), methods.take(3))
        val later = f.log[2].second // nach der Anmeldung: Sitzung und Protokollversion in jedem Header
        assertEquals("s1" to "2025-03-26", later["mcp-session-id"] to later["mcp-protocol-version"])
        assertFalse("mcp-session-id" in f.log[0].second)
    }

    @Test
    fun sseResponsesSkipNotifications() {
        val f = fake().also { it.sse = true }
        assertEquals("gesendet 03" to false, client(f).also { it.start(5.0) }.callTool("send", mapOf("code" to "03")))
    }

    @Test
    fun expiredSessionIsRenewedOnceAndTheCallRunsExactlyOnce() {
        val f = fake()
        val c = client(f).also { it.start(5.0) }
        f.sessions.clear() // Server neu gestartet
        assertEquals("gesendet 01", c.callTool("send", mapOf("code" to "01")).first)
        assertEquals(listOf("01"), f.sentCodes.toList())
        assertEquals(2, f.log.count { it.third.string("method") == "initialize" })
    }

    @Test
    fun closeEndsTheSession() {
        val f = fake()
        val c = client(f).also { it.start(5.0) }
        c.close()
        assertEquals("DELETE", f.log.last().first)
        assertTrue(f.sessions.isEmpty())
        assertFailsWith<McpException> { c.listTools() }
    }

    @Test
    fun wrongTokenGivesAClearMessage() {
        val bad = assertFailsWith<McpException> { client(fake(), "Bearer falsch").start(5.0) }
        assertTrue("HTTP 401" in bad.message.orEmpty() && "Token" in bad.message.orEmpty())
    }

    @Test
    fun unreachableServerRaisesMcpException() {
        val f = fake()
        val port = f.port
        f.stop()
        val error = assertFailsWith<McpException> {
            HttpMcpClient("weg", "http://127.0.0.1:$port/mcp").start(2.0)
        }
        assertTrue("nicht erreichbar" in error.message.orEmpty())
    }

    @Test
    fun redirectIsNotFollowedAndTheTokenNotLeaked() {
        val other = fake()
        val f = fake().also { it.redirectTo = other.url }
        assertFailsWith<McpException> { client(f).start(5.0) }
        assertTrue(other.log.isEmpty()) // der andere Server hat nie etwas gesehen
    }

    @Test
    fun missingSecretIsNamedButNeverAValueAndNothingIsSent() {
        val f = fake()
        val error = assertFailsWith<McpException> {
            HttpMcpClient("x", f.url, mapOf("Authorization" to "Bearer \${NICHT_GESETZT_XYZ}"), secret = { null }).start(2.0)
        }
        assertTrue("NICHT_GESETZT_XYZ" in error.message.orEmpty())
        assertTrue(f.log.isEmpty())
        assertEquals("Bearer $TOKEN", expandSecrets("Bearer \${FAKE_TOKEN}", "x") { secrets[it] })
    }

    @Test
    fun httpOnlyForLocalAddresses() {
        for (host in listOf("localhost", "127.0.0.1", "192.168.178.21", "10.0.0.5", "pc.local", "[::1]", "[fd00::1]")) {
            checkUrl("http://$host:8080/mcp")
        }
        for (url in listOf("http://example.org/mcp", "http://8.8.8.8/mcp")) assertFailsWith<McpException> { checkUrl(url) }
        checkUrl("https://example.org/mcp") // https überall
        for (bad in listOf("ftp://x/mcp", "mcp", "http:///mcp")) assertFailsWith<McpException> { checkUrl(bad) }
        assertFalse(isLocalHost("evil.example.org"))
    }

    @Test
    fun registryStartsUrlAndBuiltinServersWithOneErrorPerFailedServer() {
        val f = fake()
        val dead = fake()
        val deadPort = dead.port
        dead.stop()
        val config = """{
          "servers": {
            "remote": {"url": "${f.url}", "headers": {"Authorization": "Bearer ${'$'}{FAKE_TOKEN}"}, "raw": false},
            "weg": {"url": "http://127.0.0.1:$deadPort/mcp"},
            "aus": {"url": "${f.url}", "disabled": true},
            "wetter": {"command": "python", "args": ["servers/weather_server.py"], "raw": false},
            "eigen": {"command": "node", "args": ["x.js"]}
          },
          "aliases": {
            "tv-p1": {"server": "remote", "tool": "send", "args": {"code": "0x11"}, "result": "discard", "status": "TV: Program 1"},
            "tv-weg": {"server": "weg", "tool": "send"},
            "tv-aus": {"server": "aus", "tool": "send"},
            "weather": {"server": "wetter", "tool": "forecast", "params": ["place", "day"], "defaults": {"place": "Berlin", "day": "today"}}
          }
        }"""
        val registry = ToolRegistry()
        registry.startFromConfig(config, DefaultServerFactory(secret = { secrets[it] }, fetcher = { _, _ -> "{}" }))
        try {
            assertEquals(listOf("tv-p1", "weather"), registry.available())
            assertEquals(2, registry.errors.size) // 'weg' und 'eigen' - kein Fehler für deren Aliase und nicht für den deaktivierten Server
            assertTrue(registry.errors.any { it.startsWith("weg: ") })
            assertTrue(registry.errors.any { it.startsWith("eigen: ") && "Android" in it })
            val outcome = registry.run(findCall("[[mcp:tv-p1]]")!!, listOf("tv-p1"))
            assertEquals(Triple(true, "TV: Program 1", RESULT_DISCARD), Triple(outcome.ok, outcome.status, outcome.mode))
            assertEquals(listOf("0x11"), f.sentCodes.toList())
        } finally {
            registry.shutdown()
        }
        assertEquals("DELETE", f.log.last().first) // beim Beenden wird auch die HTTP-Sitzung geschlossen
    }
}
