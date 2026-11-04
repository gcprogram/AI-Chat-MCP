package app.aichatmcp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Ein Werkzeug, wie ein MCP-Server es meldet (tools/list). */
data class McpTool(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject? = null,
    val readOnly: Boolean = false,
)

/** Ein MCP-Server, gleich ob über HTTP erreicht oder als eingebauter Server im Prozess. */
interface McpClient {
    val name: String
    fun start(timeoutSec: Double = 30.0)
    fun close()
    fun listTools(timeoutSec: Double = 30.0): List<McpTool>

    /** Liefert (Text, ist_Fehler). */
    fun callTool(name: String, arguments: Map<String, Any?>, timeoutSec: Double = 60.0): Pair<String, Boolean>
}

/** Text aus dem Ergebnis von tools/call (content-Liste, sonst structuredContent). */
fun toolResultText(result: JsonObject): Pair<String, Boolean> {
    val parts = mutableListOf<String>()
    for (item in (result["content"] as? JsonArray).orEmpty()) {
        val obj = item as? JsonObject ?: continue
        when (val kind = obj.string("type")) {
            "text" -> parts += obj.string("text").orEmpty()
            null -> {}
            else -> parts += "[$kind-Inhalt, nicht darstellbar]"
        }
    }
    if (parts.isEmpty() && result["structuredContent"] != null) parts += result["structuredContent"].toString()
    val isError = (result["isError"] as? JsonPrimitive)?.content == "true"
    return parts.joinToString("\n").trim() to isError
}
