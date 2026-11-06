package app.aichatmcp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Schlüsselwörter aus einer Tabelle (CSV oder Excel) in die Werkzeug-Konfiguration übernehmen. Aufbau je Zeile:
 * `Wort;Werkzeug;Parameter1;Parameter2;…`. Das Wort wird klein geschrieben, doppelte Wörter fallen weg (die erste Zeile gilt),
 * Zeilen ohne Werkzeug werden ignoriert, und Wörter mit demselben Werkzeug und denselben Parametern werden zu einem einzigen
 * regulären Ausdruck zusammengefasst. Die Parameter sind feste Argumente (siehe [Keyword.args]).
 */
object KeywordImport {
    /** Wörter, die zu demselben Werkzeug mit denselben Parametern gehören. */
    data class Group(val tool: String, val args: List<String>, val words: List<String>) {
        /** Ein Ausdruck für alle Wörter: ganze Wörter, ohne Rücksicht auf Groß-/Kleinschreibung (das setzt [KeywordRules]). */
        val pattern: String
            get() = "(?<![\\p{L}\\p{N}])(?:" + words.joinToString("|") { escape(it) } + ")(?![\\p{L}\\p{N}])"

        val keyword: Keyword get() = Keyword(pattern, args)
    }

    class Parsed(val groups: List<Group>, val notes: List<String>) {
        val tools: List<String> get() = groups.map { it.tool }.distinct()
        val wordCount: Int get() = groups.sumOf { it.words.size }
    }

    class Result(val json: String, val parsed: Parsed)

    private val HEADER_WORDS = setOf("word", "wort", "keyword", "stichwort", "schlüsselwort")

    /** Wort als regulärer Ausdruck: Sonderzeichen entschärft, Leerraum zwischen Wörtern beliebig lang. */
    fun escape(word: String): String = buildString {
        var space = false
        for (c in word) {
            if (c.isWhitespace()) {
                if (!space) append("\\s+")
                space = true
                continue
            }
            space = false
            if (c in "\\.[]{}()*+?^$|") append('\\')
            append(c)
        }
    }

    /** Wertet die Zeilen aus. [aliases] sind die Namen der Werkzeuge (Aliase) aus mcp_servers.json. */
    fun parse(rows: List<List<String>>, aliases: Collection<String>): Parsed {
        val byName = aliases.associateBy { normalizeName(it) }
        val seen = HashMap<String, Pair<String, List<String>>>()
        val order = LinkedHashMap<Pair<String, List<String>>, MutableList<String>>()
        val unknown = linkedSetOf<String>()
        val conflicts = mutableListOf<String>()
        val badParams = mutableListOf<String>()
        var withoutTool = 0
        var duplicates = 0

        val body = rows.withIndex().filterNot { (i, row) ->
            i == 0 && row.firstOrNull()?.trim()?.lowercase() in HEADER_WORDS && row.getOrNull(1)?.trim()?.lowercase() in setOf("tool", "werkzeug")
        }
        for ((index, row) in body) {
            val cells = row.map { it.trim() }
            val word = cells.getOrNull(0).orEmpty().lowercase()
            if (word.isEmpty()) continue
            val toolName = cells.getOrNull(1).orEmpty()
            if (toolName.isEmpty()) { withoutTool++; continue }
            val tool = byName[normalizeName(toolName)] ?: run { unknown += toolName; null } ?: continue
            val args = cells.drop(2).dropLastWhile { it.isEmpty() }
            if (args.any { '|' in it }) { badParams += "Zeile ${index + 1} („$word“)"; continue }
            val key = tool to args
            val before = seen[word]
            if (before != null) {
                if (before == key) duplicates++ else conflicts += word
                continue
            }
            seen[word] = key
            order.getOrPut(key) { mutableListOf() } += word
        }

        val notes = mutableListOf<String>()
        if (withoutTool > 0) notes += "$withoutTool Zeilen ohne Werkzeug ignoriert."
        if (unknown.isNotEmpty()) notes += "Unbekannte Werkzeuge (kein Alias in mcp_servers.json), Zeilen ignoriert: ${unknown.joinToString(", ")}."
        if (duplicates > 0) notes += "$duplicates doppelte Wörter entfernt."
        if (conflicts.isNotEmpty()) notes += "Mit unterschiedlichem Werkzeug oder Parametern mehrfach genannt (nur die erste Zeile gilt): ${conflicts.joinToString(", ")}."
        if (badParams.isNotEmpty()) notes += "Parameter dürfen kein | enthalten, ignoriert: ${badParams.joinToString(", ")}."
        return Parsed(order.map { (key, words) -> Group(key.first, key.second, words) }, notes)
    }

    /** Setzt die Schlüsselwörter der betroffenen Werkzeuge in [mcpJson] (ersetzt deren bisherige Liste, andere Werkzeuge bleiben). */
    fun apply(mcpJson: String, parsed: Parsed): String {
        val root = try {
            AppJson.parseToJsonElement(mcpJson) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw DocumentException("mcp_servers.json ist kein gültiges JSON-Objekt.")
        val aliases = root["aliases"] as? JsonObject ?: throw DocumentException("mcp_servers.json hat keinen Block \"aliases\".")
        val updated = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for ((name, entry) in aliases) {
            val groups = parsed.groups.filter { it.tool == name }
            updated[name] = if (groups.isEmpty() || entry !is JsonObject) entry else {
                val keywords = JsonArray(groups.map { g ->
                    if (g.args.isEmpty()) JsonPrimitive(g.pattern)
                    else JsonObject(mapOf("pattern" to JsonPrimitive(g.pattern), "args" to JsonArray(g.args.map { JsonPrimitive(it) })))
                })
                JsonObject(LinkedHashMap(entry).also { it["keywords"] = keywords })
            }
        }
        return AppJson.encodeToString(JsonObject.serializer(), JsonObject(LinkedHashMap(root).also { it["aliases"] = JsonObject(updated) }))
    }

    /** Alles in einem Schritt: Datei lesen, auswerten und in die Konfiguration einsetzen. Fehler als [DocumentException]. */
    fun fromFile(fileName: String, bytes: ByteArray, mcpJson: String): Result {
        val aliasNames = try {
            ((AppJson.parseToJsonElement(mcpJson) as? JsonObject)?.get("aliases") as? JsonObject)?.keys.orEmpty()
        } catch (e: Exception) {
            emptySet()
        }
        if (aliasNames.isEmpty()) throw DocumentException("In mcp_servers.json gibt es keine Werkzeuge (Block \"aliases\").")
        val parsed = parse(Spreadsheet.rows(fileName, bytes), aliasNames)
        if (parsed.groups.isEmpty()) throw DocumentException("Keine verwertbare Zeile gefunden. ${parsed.notes.joinToString(" ")}".trim())
        return Result(apply(mcpJson, parsed), parsed)
    }
}
