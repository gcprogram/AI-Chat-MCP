package app.aichatmcp.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** MCP-Server im Speicher mit einem Rechner und einer Uhr (wie examples/demo_mcp_server.py). */
class FakeMcp(override val name: String = "demo") : McpClient {
    val calls = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()

    override fun start(timeoutSec: Double) {}
    override fun close() {}

    override fun listTools(timeoutSec: Double) = listOf(
        McpTool(
            "calculate", "Calculates an expression.",
            AppJson.parseToJsonElement("""{"type":"object","properties":{"expression":{"type":"string"}},"required":["expression"]}""") as JsonObject,
            readOnly = true,
        ),
        McpTool("get_time", "Returns the current time.", null, readOnly = true),
    )

    override fun callTool(name: String, arguments: Map<String, Any?>, timeoutSec: Double): Pair<String, Boolean> {
        calls += name to arguments
        return when (name) {
            "get_time" -> "Monday, 12:00" to false
            "calculate" -> when (arguments["expression"]) {
                "6*7" -> "42" to false
                "1+1" -> "2" to false
                "1/0" -> "Calculation error: division by zero" to true
                else -> "?" to false
            }
            else -> "Unknown tool: $name" to true
        }
    }
}

fun aliasJson(text: String): JsonObject = AppJson.parseToJsonElement(text) as JsonObject

fun demoRegistry(aliases: String): ToolRegistry = ToolRegistry().also {
    it.addServer("demo", FakeMcp(), exposeRaw = false)
    it.addAliases(aliasJson(aliases))
}

/** Streamender OpenAI-kompatibler Server: handler bekommt die Anfrage und liefert die Textstücke (null = HTTP 500). */
class FakeLlm(private val handler: (JsonObject) -> List<String>?) {
    val requests = CopyOnWriteArrayList<JsonObject>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.createContext("/v1/models") { ex ->
            respond(ex, 200, """{"data":[{"id":"chat-model"},{"id":"text-embed-1"}]}""")
        }
        server.createContext("/v1/chat/completions") { ex ->
            val body = AppJson.parseToJsonElement(ex.requestBody.readBytes().toString(Charsets.UTF_8)) as JsonObject
            requests += body
            val chunks = handler(body)
            if (chunks == null) {
                respond(ex, 500, "kaputt")
            } else {
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { out ->
                    for (chunk in chunks) {
                        val delta = buildJsonObject { put("content", chunk) }
                        val event = """{"choices":[{"delta":$delta}]}"""
                        out.write("data: $event\n\n".toByteArray())
                        out.flush()
                    }
                    out.write("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":50}}\n\ndata: [DONE]\n\n".toByteArray())
                }
            }
        }
        server.start()
    }

    private fun respond(ex: HttpExchange, code: Int, text: String) {
        val bytes = text.toByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    fun lastUserContent(request: JsonObject): String {
        val messages = request["messages"] as JsonArray
        return ((messages.last() as JsonObject)["content"] as kotlinx.serialization.json.JsonPrimitive).content
    }

    fun stop() = server.stop(0)
}
