package app.aichatmcp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Liest die Werkzeuge (und bei einer Fernbedienung die Tasten) eines laufenden MCP-Servers und macht daraus Aliase für
 * mcp_servers.json: dann muss niemand Codes oder Namen abschreiben. Gleiche Regeln wie chat_app/server_import.py.
 */

/** Ergebnis des Einfügens: die neue Konfiguration und was dazukam bzw. schon vorhanden war (und nicht verändert wurde). */
data class ServerImportResult(val config: JsonObject, val added: List<String>, val skipped: List<String>)

object ServerImport {
    fun slug(text: String): String = text.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "x" }

    /** Die Tastenliste als Text zurück in Tasten (Code, Beschriftung); leer, wenn der Text keine Liste ist. */
    fun parseButtons(text: String): List<Pair<Int, String>>? = try {
        (AppJson.parseToJsonElement(text) as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val code = (o["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            code to o.string("label").orEmpty().trim()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Aliaseinträge für alle Werkzeuge des Servers. Mit Tastenliste (Werkzeuge "buttons" und "send"): EIN Alias mit "buttons": true
     * (die Taste ist das Argument, die Beschriftungen kommen vom Server; "off" ist eingeschlossen), "send"/"buttons"/"off" selbst
     * entfallen. Sonst ein Eintrag je Werkzeug.
     */
    fun aliasEntries(server: String, tools: List<McpTool>, buttons: List<Pair<Int, String>>?, prefix: String): Map<String, JsonObject> {
        val names = tools.map { it.name }.toSet()
        val remote = buttons != null && names.containsAll(listOf("buttons", "send"))
        val out = linkedMapOf<String, JsonObject>()
        if (remote) {
            val first = buttons.orEmpty().firstOrNull()?.let { (code, label) -> label.ifEmpty { "0x" + code.toString(16).padStart(2, '0') } } ?: "P1"
            val name = normalizeName(prefix.trim('-').ifEmpty { server })
            out[name] = JsonObject(
                mapOf(
                    "server" to JsonPrimitive(server), "buttons" to JsonPrimitive(true),
                    "hint" to JsonPrimitive("Remote control $server: press a button"), "example" to JsonPrimitive("[[mcp:$name|$first]]"),
                ),
            )
        }
        for (tool in tools) {
            if (remote && tool.name in listOf("send", "buttons", "off")) continue
            out[normalizeName(prefix + slug(tool.name))] =
                obj("server" to server, "tool" to tool.name, "hint" to firstSentence(tool.description).ifEmpty { tool.name })
        }
        return out
    }

    /** Fügt die neuen Aliase ein (vorhandene bleiben unangetastet) und setzt "raw": false beim Server, damit nur die Aliase sichtbar sind. */
    fun merge(config: JsonObject, server: String, tools: List<McpTool>, buttons: List<Pair<Int, String>>?, prefix: String): ServerImportResult {
        val aliases = LinkedHashMap(config.obj("aliases") ?: JsonObject(emptyMap()))
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for ((name, entry) in aliasEntries(server, tools, buttons, prefix)) {
            if (name in aliases) skipped += name else { aliases[name] = entry; added += name }
        }
        val result = LinkedHashMap(config)
        result["aliases"] = JsonObject(aliases)
        val key = if ("servers" in config) "servers" else if ("mcpServers" in config) "mcpServers" else null
        val servers = key?.let { config.obj(it) }
        val serverEntry = servers?.obj(server)
        if (key != null && servers != null && serverEntry != null && added.isNotEmpty() && "raw" !in serverEntry) {
            result[key] = JsonObject(servers + (server to JsonObject(serverEntry + ("raw" to JsonPrimitive(false)))))
        }
        return ServerImportResult(JsonObject(result), added, skipped)
    }

    /** Liest Werkzeuge und (falls es "buttons" gibt) Tasten eines gestarteten Servers. */
    fun read(client: McpClient): Pair<List<McpTool>, List<Pair<Int, String>>?> {
        val tools = client.listTools()
        val buttons = if (tools.any { it.name == "buttons" }) {
            val (text, isError) = client.callTool("buttons", emptyMap(), 15.0)
            if (isError) null else parseButtons(text)
        } else {
            null
        }
        return tools to buttons
    }

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })
}
