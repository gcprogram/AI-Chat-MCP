package app.aichatmcp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * Werkzeuge per Textmarker im Chat (Port von tools.py). Kleine Modelle können kein zuverlässiges Function-Calling.
 * Stattdessen steht im System-Prompt, dass das Modell für einen Aufruf eine Zeile schreiben soll:
 *
 *     [[mcp:NAME|ARGUMENT]]   [[mcp:NAME|ARG1|ARG2]]   [[mcp:NAME]]
 *
 * Beim ersten vollständigen Marker wird die Antwort abgebrochen (alles danach wäre erfundenes Ergebnis), das
 * Werkzeug ausgeführt und das Ergebnis als nächste Nachricht zurückgegeben. Der Parser ist absichtlich tolerant.
 */

val CALL_RE = Regex(
    "\\[{1,2}[ \\t]*mcp[ \\t]*[:/][ \\t]*(?<name>[\\w.\\-]+)[ \\t]*(?:[|:][ \\t]*(?<args>[^\\]\\n]*?))?[ \\t]*\\]{1,2}",
    RegexOption.IGNORE_CASE,
)
private val QUOTES = mapOf('"' to '"', '\'' to '\'', '„' to '“', '“' to '”', '«' to '»', '»' to '«')

const val RESULT_MODEL = "model"
const val RESULT_SHOW = "show"
const val RESULT_DISCARD = "discard"

/** Ergebnis unverändert im Chat zeigen UND dem Modell geben, das darauf antwortet (Folgefragen klappen). */
const val RESULT_BOTH = "both"
val RESULT_MODES = listOf(RESULT_MODEL, RESULT_SHOW, RESULT_DISCARD, RESULT_BOTH)

data class ToolCall(
    val name: String,
    val args: String,
    val raw: String,
    val start: Int,
    val end: Int,
)

fun findCall(text: String): ToolCall? {
    val match = CALL_RE.find(text) ?: return null
    return ToolCall(
        match.groups["name"]!!.value, (match.groups["args"]?.value ?: "").trim(), match.value,
        match.range.first, match.range.last + 1,
    )
}

/** Zerlegt Text in ("text", ...) und ("call", "name: argumente") - für die Anzeige im Chat. */
fun splitSegments(text: String): List<Pair<String, String>> {
    val segments = mutableListOf<Pair<String, String>>()
    var pos = 0
    for (match in CALL_RE.findAll(text)) {
        if (match.range.first > pos) segments += "text" to text.substring(pos, match.range.first)
        val args = (match.groups["args"]?.value ?: "").trim()
        val name = match.groups["name"]!!.value
        segments += "call" to (if (args.isNotEmpty()) "$name: $args" else name)
        pos = match.range.last + 1
    }
    if (pos < text.length) segments += "text" to text.substring(pos)
    return segments
}

/** Marker in Werkzeug-Ergebnissen (z. B. aus Webseiten) entschärfen: Ergebnisse dürfen keine Aufrufe auslösen. */
fun neutralize(text: String): String =
    CALL_RE.replace(text) { it.value.replace('[', '(').replace(']', ')') }

fun normalizeName(name: String): String = name.trim().lowercase().replace('_', '-')

// --- Werkzeugbeschreibung und Argumente ---------------------------------------------------------------

class ToolArgError(message: String) : Exception(message)

data class Param(
    val name: String,
    val type: String = "string",
    val required: Boolean = false,
    val description: String = "",
)

private fun unquote(value: Any?): Any? {
    if (value is String) {
        val v = value.trim()
        if (v.length >= 2 && QUOTES[v[0]] == v.last()) return v.substring(1, v.length - 1).trim()
        return v
    }
    return value
}

private sealed interface ParsedArgs {
    data class Named(val values: Map<String, Any?>) : ParsedArgs
    data class Positional(val values: List<String>) : ParsedArgs
}

private val QUOTED_RE = Regex("\"([^\"]*)\"|'([^']*)'")

private fun splitArgs(raw: String): ParsedArgs {
    var text = raw.trim()
    if (text.startsWith("{")) {
        try {
            val data = AppJson.parseToJsonElement(text)
            if (data is JsonObject) return ParsedArgs.Named(data.mapValues { it.value.toNative() })
        } catch (_: Exception) {
        }
        val quoted = QUOTED_RE.findAll(text).map { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }.toList()
        if (quoted.size >= 2 && quoted.size % 2 == 0) { // {"suchbegriff", "AI News"} -> Schlüssel/Wert-Paare
            return ParsedArgs.Named(quoted.chunked(2).associate { it[0] to it[1] })
        }
        if (quoted.isNotEmpty()) return ParsedArgs.Positional(quoted)
        text = text.trim { it in "{} \t" }
    }
    val parts = if (text.isNotEmpty()) text.split("|").map { it.trim() }.toMutableList() else mutableListOf()
    while (parts.isNotEmpty() && parts.last().isEmpty()) parts.removeAt(parts.size - 1)
    return ParsedArgs.Positional(parts)
}

private fun coerce(param: Param, value: Any?): Any? {
    if (value !is String) return value
    try {
        when (param.type) {
            "integer" -> return value.replace(",", ".").toDouble().toLong()
            "number" -> return value.replace(",", ".").toDouble()
            "boolean" -> return value.trim().lowercase() in listOf("true", "ja", "yes", "1", "wahr")
            "array", "object" -> return try {
                AppJson.parseToJsonElement(value).toNative()
            } catch (_: Exception) {
                if (param.type == "array") listOf(value) else mapOf("value" to value)
            }
        }
    } catch (_: NumberFormatException) {
    }
    return value
}

private val NAMED_ARG_RE = Regex("^([A-Za-z_]\\w*)\\s*=\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)

data class ToolSpec(
    val alias: String,
    val server: String,
    val remoteName: String,
    val description: String = "",
    val params: List<Param> = emptyList(),
    val example: String = "",
    val readOnly: Boolean = false,
    // Nur bei Aliasen aus dem Block "aliases":
    val fixed: Map<String, Any?> = emptyMap(), // feste Argumente, die das Modell nicht sieht
    val defaults: Map<String, Any?> = emptyMap(), // Ersatz für fehlende Argumente
    val result: String = RESULT_MODEL, // model: Modell antwortet | show: Klartext im Chat | discard: nur Statuszeile | both
    val status: String = "", // Statuszeile bei discard
    val confirm: Boolean? = null, // null = globale Einstellung "Vor jedem Werkzeugaufruf nachfragen"
    val remember: Boolean = false, // Ergebnis bei show/discard dem Modell im Verlauf zeigen
    val keywords: List<Keyword> = emptyList(), // reguläre Ausdrücke: kommen sie in einem Text vor, läuft das Werkzeug ohne das Modell
) {
    val usage: String get() = "[[mcp:" + (listOf(alias) + params.map { "<${it.name}>" }).joinToString("|") + "]]"

    /** Marker-Text -> benannte Argumente der sichtbaren Parameter (noch ohne Standardwerte und Typumwandlung). */
    fun bind(args: String): MutableMap<String, Any?> {
        val names = params.map { it.name }.toSet()
        val given = linkedMapOf<String, Any?>()
        val positional = mutableListOf<Any?>()
        when (val parsed = splitArgs(args)) {
            is ParsedArgs.Named -> for ((key, value) in parsed.values) {
                if (key in names && key !in given) given[key] = unquote(value) else positional += unquote(value)
            }
            is ParsedArgs.Positional -> for (part in parsed.values) {
                val match = NAMED_ARG_RE.find(part)
                if (match != null && match.groupValues[1] in names && match.groupValues[1] !in given) {
                    given[match.groupValues[1]] = unquote(match.groupValues[2])
                } else {
                    positional += unquote(part)
                }
            }
        }
        val free = params.filter { it.name !in given }
        for ((param, value) in free.zip(positional)) given[param.name] = value
        val extras = positional.drop(free.size)
        if (extras.isNotEmpty() && params.size == 1 && given[params[0].name] is String) {
            given[params[0].name] = (listOf(given[params[0].name]) + extras).joinToString(" ")
        }
        return given
    }

    /** Marker-Argumente -> Argumente des MCP-Werkzeugs (Standardwerte, Typumwandlung, feste Argumente). */
    fun resolve(args: String): Map<String, Any?> {
        val given = bind(args)
        for ((name, value) in defaults) {
            if (given[name] == null || given[name] == "") given[name] = value
        }
        val missing = params.filter { it.required && (given[it.name] == null || given[it.name] == "") }.map { it.name }
        if (missing.isNotEmpty()) {
            throw ToolArgError("Es fehlt: ${missing.joinToString(", ")}. Richtiges Format: $usage")
        }
        val byName = params.associateBy { it.name }
        val arguments = linkedMapOf<String, Any?>()
        for ((k, v) in given) byName[k]?.let { arguments[k] = coerce(it, v) }
        arguments.putAll(fixed) // feste Argumente gewinnen immer
        return arguments
    }

    fun promptLine(): String {
        val desc = if (description.isNotEmpty()) description.trimEnd('.') + "." else ""
        val optional = params.filter { !it.required }.map { it.name }
        val note = if (optional.isNotEmpty()) " Optional: ${optional.joinToString(", ")}." else ""
        return "- $alias: $desc$note Example: ${example.ifEmpty { usage }}".replace("  ", " ")
    }
}

fun firstSentence(text: String, limit: Int = 140): String {
    var t = text.trim().split(Regex("\\s+")).joinToString(" ")
    Regex("[.!?](?:\\s|$)").find(t)?.let { t = t.substring(0, it.range.last + 1).trim() }
    return if (t.length <= limit) t else t.substring(0, limit - 1).trimEnd() + "…"
}

private fun paramsFromSchema(schema: JsonObject?, only: List<String>?): List<Param> {
    val props = schema?.obj("properties") ?: JsonObject(emptyMap())
    val required = (schema?.get("required") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    var order = required.filter { it in props } + props.keys.filter { it !in required }
    if (only != null) order = only.filter { it in props }
    return order.map { n ->
        val p = props[n] as? JsonObject
        Param(n, p?.string("type") ?: "string", n in required, p?.string("description").orEmpty())
    }
}

data class ToolOutcome(
    val tool: String,
    val ok: Boolean,
    val text: String,
    /** Fehler gehen immer ans Modell, damit es dem Nutzer Bescheid sagen kann. */
    val mode: String = RESULT_MODEL,
    val status: String = "",
    val remember: Boolean = false,
)

/** Text der Nachricht, mit der das Werkzeugergebnis an das Modell zurückgegeben wird. */
fun resultMessage(tool: String, text: String, ok: Boolean, userName: String): String =
    if (ok) {
        "[Ergebnis von $tool]\n$text\n[Ende des Ergebnisses]\n" +
            "Antworte $userName jetzt kurz und in deinen eigenen Worten anhand dieses Ergebnisses. " +
            "Schreibe keinen neuen Werkzeugaufruf, außer dir fehlt noch eine Information."
    } else {
        "[Fehler bei $tool] $text\n" +
            "Sage $userName kurz, dass es nicht geklappt hat, oder versuche den Aufruf im richtigen Format erneut."
    }

/** Baut einen Server aus einem Eintrag der Konfiguration (Kommando, URL oder eingebaut). */
fun interface ServerFactory {
    @Throws(McpException::class)
    fun create(name: String, entry: JsonObject): McpClient
}

private fun JsonObject.truthy(key: String): Boolean {
    val v = this[key] as? JsonPrimitive ?: return this[key] != null && this[key] !is kotlinx.serialization.json.JsonNull
    return when {
        v.booleanOrNull != null -> v.booleanOrNull == true
        v.isString -> v.content.isNotEmpty()
        else -> v.content != "0"
    }
}

/** Alle Werkzeuge der konfigurierten MCP-Server. Pro Companion wird eine Auswahl freigeschaltet. */
class ToolRegistry {
    val servers = linkedMapOf<String, McpClient>()
    val tools = linkedMapOf<String, ToolSpec>() // was das Modell benutzen darf (Aliase)
    val remote = linkedMapOf<Pair<String, String>, ToolSpec>() // alle Werkzeuge der Server, auch unsichtbare
    val errors = mutableListOf<String>()

    /** Startet den Server (blockierend) und registriert seine Werkzeuge. Mit exposeRaw=false nur über Aliase. */
    fun addServer(name: String, server: McpClient, overrides: JsonObject? = null, exposeRaw: Boolean = true): List<String> {
        server.start()
        val remoteTools = try {
            server.listTools()
        } catch (e: Exception) {
            server.close()
            throw e
        }
        servers[name] = server
        val added = mutableListOf<String>()
        for (tool in remoteTools) {
            val override = overrides?.obj(tool.name)
            var alias = normalizeName(override?.string("alias") ?: tool.name)
            if (alias in tools) alias = "${normalizeName(name)}-$alias"
            val only = (override?.get("args") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            val spec = ToolSpec(
                alias = alias, server = name, remoteName = tool.name,
                description = override?.string("hint")?.takeIf { it.isNotEmpty() } ?: firstSentence(tool.description),
                params = paramsFromSchema(tool.inputSchema, only),
                example = override?.string("example").orEmpty(), readOnly = tool.readOnly,
            )
            remote[name to tool.name] = spec
            if (exposeRaw) {
                tools[alias] = spec
                added += alias
            }
        }
        return added
    }

    /** Registriert Kurznamen aus dem Block "aliases" (Fehler landen in errors). */
    fun addAliases(aliases: JsonObject?, skipServers: Set<String> = emptySet()): List<String> {
        val added = mutableListOf<String>()
        for ((rawName, entry) in aliases.orEmpty()) {
            if (entry is JsonObject && entry.string("server") in skipServers) continue
            val alias = normalizeName(rawName)
            try {
                tools[alias] = buildAlias(alias, entry)
                added += alias
            } catch (e: IllegalArgumentException) {
                errors += "Alias '$alias': ${e.message}"
            }
        }
        return added
    }

    private fun buildAlias(alias: String, entry: Any?): ToolSpec {
        require(entry is JsonObject) { "Eintrag ist kein Objekt" }
        val server = entry.string("server").orEmpty()
        val tool = entry.string("tool").orEmpty()
        val base = remote[server to tool]
            ?: throw IllegalArgumentException("Werkzeug '$server/$tool' nicht gefunden (Server nicht gestartet?)")
        val fixedEl = entry["args"] ?: JsonObject(emptyMap())
        val defaultsEl = entry["defaults"] ?: JsonObject(emptyMap())
        require(fixedEl is JsonObject && defaultsEl is JsonObject) { "'args' und 'defaults' müssen Objekte sein" }
        val fixed = fixedEl.mapValues { it.value.toNative() }
        val defaults = defaultsEl.mapValues { it.value.toNative() }
        val byName = base.params.associateBy { it.name }
        val namesEl = entry["params"]
        val names = if (namesEl == null) {
            base.params.filter { it.name !in fixed }.map { it.name }
        } else {
            require(namesEl is JsonArray) { "'params' muss eine Liste sein" }
            namesEl.map { (it as? JsonPrimitive)?.content.orEmpty() }
        }
        val unknown = (names + defaults.keys).filter { it !in byName }
        require(unknown.isEmpty()) {
            "unbekannte Parameter ${unknown.joinToString(", ")} (das Werkzeug kennt: ${byName.keys.joinToString(", ").ifEmpty { "keine" }})"
        }
        val mode = entry.string("result") ?: RESULT_MODEL
        require(mode in RESULT_MODES) { "'result' muss ${RESULT_MODES.joinToString(", ")} sein" }
        val confirmEl = entry["confirm"]
        val confirm = when {
            confirmEl == null || confirmEl is kotlinx.serialization.json.JsonNull -> null
            confirmEl is JsonPrimitive && confirmEl.booleanOrNull != null -> confirmEl.booleanOrNull
            else -> throw IllegalArgumentException("'confirm' muss true oder false sein")
        }
        val rememberEl = entry["remember"] as? JsonPrimitive
        val remember = rememberEl?.booleanOrNull ?: (mode == RESULT_SHOW) // angezeigte Daten braucht das Modell für Folgefragen
        val params = names.map { n -> byName.getValue(n).let { it.copy(required = it.required && n !in defaults) } }
        fun keyword(el: kotlinx.serialization.json.JsonElement): Keyword = when (el) {
            is JsonObject -> Keyword(
                (el["pattern"] as? JsonPrimitive)?.content ?: throw IllegalArgumentException("ein Schlüsselwort als Objekt braucht 'pattern'"),
                when (val a = el["args"]) {
                    null, is kotlinx.serialization.json.JsonNull -> emptyList()
                    is JsonArray -> a.map { (it as? JsonPrimitive)?.content.orEmpty() }
                    is JsonPrimitive -> listOf(a.content)
                    else -> throw IllegalArgumentException("'args' eines Schlüsselworts muss eine Liste sein")
                },
            )
            is JsonPrimitive -> Keyword(el.content)
            else -> throw IllegalArgumentException("'keywords' muss eine Liste von regulären Ausdrücken sein")
        }
        val keywords = when (val el = entry["keywords"]) {
            null, is kotlinx.serialization.json.JsonNull -> emptyList()
            is JsonArray -> el.map(::keyword)
            is JsonObject, is JsonPrimitive -> listOf(keyword(el))
        }.filter { it.pattern.isNotBlank() }
        for (kw in keywords) {
            try {
                Regex(kw.pattern)
            } catch (e: Exception) {
                throw IllegalArgumentException("ungültiger Ausdruck in 'keywords': ${kw.pattern}")
            }
        }
        return ToolSpec(
            alias = alias, server = server, remoteName = tool, params = params, readOnly = base.readOnly,
            description = entry.string("hint")?.takeIf { it.isNotEmpty() } ?: base.description,
            example = entry.string("example").orEmpty(), fixed = fixed, defaults = defaults, result = mode,
            status = entry.string("status").orEmpty(), confirm = confirm, remember = remember, keywords = keywords,
        )
    }

    /** Liest mcp_servers.json (Format wie bei Claude Desktop; zusätzlich "aliases"). Fehler landen in errors. */
    fun startFromConfig(text: String, factory: ServerFactory) {
        val data: JsonObject
        val servers: JsonObject
        try {
            data = AppJson.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("Wurzelelement ist kein Objekt")
            servers = (data["servers"] ?: data["mcpServers"] ?: JsonObject(emptyMap())) as? JsonObject
                ?: throw IllegalArgumentException("'servers' ist kein Objekt")
        } catch (e: Exception) {
            errors += "mcp_servers.json nicht lesbar: ${e.message}"
            return
        }
        val failed = mutableSetOf<String>()
        for ((name, element) in servers) {
            val entry = element as? JsonObject ?: continue
            if (entry.truthy("disabled") || !(entry.truthy("command") || entry.truthy("url") || entry.truthy("builtin"))) continue
            try {
                val server = factory.create(name, entry)
                addServer(name, server, entry.obj("tools"), (entry["raw"] as? JsonPrimitive)?.booleanOrNull != false)
            } catch (e: Exception) {
                errors += "$name: ${e.message}"
                failed += name
            }
        }
        val disabled = servers.filter { (it.value as? JsonObject)?.truthy("disabled") == true }.keys
        addAliases(data.obj("aliases"), disabled + failed) // ein Fehler pro Server, nicht einer pro Alias
    }

    fun shutdown() {
        servers.values.forEach { runCatching { it.close() } }
    }

    fun available(): List<String> = tools.keys.sorted()

    fun promptSection(aliases: List<String>): String {
        val specs = aliases.map { normalizeName(it) }.distinct().mapNotNull { tools[it] }
        if (specs.isEmpty()) return ""
        return (listOf(
            "Tools:",
            "You can use tools. To call one, write exactly one line in the format [[mcp:NAME|ARGUMENT]] at the very " +
                "start of your reply and nothing after it. The program runs the tool; you do not say it is done yourself.",
            "- Never invent a result and never guess what a tool returns.",
            "- Use a tool only when you really need it; otherwise just answer normally.",
            "- Separate several arguments with |. No quotation marks around arguments.",
            "Available tools:",
        ) + specs.map { it.promptLine() }).joinToString("\n")
    }

    /** Führt einen Aufruf aus. Fehler werden als Ergebnis (ok=false) zurückgegeben, damit das Modell sie sieht. */
    fun run(call: ToolCall, allowed: List<String>, maxChars: Int = 2000, timeoutSec: Double = 60.0): ToolOutcome {
        val alias = normalizeName(call.name)
        val spec = tools[alias]
        if (spec == null || alias !in allowed) {
            return ToolOutcome(
                call.name, false,
                "Unbekanntes Werkzeug '${call.name}'. Verfügbar: ${allowed.joinToString(", ").ifEmpty { "keine" }}.",
            )
        }
        val arguments = try {
            spec.resolve(call.args)
        } catch (e: ToolArgError) {
            return ToolOutcome(spec.alias, false, e.message.orEmpty())
        }
        val (rawText, isError) = try {
            servers.getValue(spec.server).callTool(spec.remoteName, arguments, timeoutSec)
        } catch (e: McpException) {
            return ToolOutcome(spec.alias, false, e.message.orEmpty())
        }
        var text = neutralize(rawText).ifEmpty { "(leeres Ergebnis)" }
        if (text.length > maxChars) text = text.substring(0, maxChars).trimEnd() + " […gekürzt]"
        if (isError) return ToolOutcome(spec.alias, false, text)
        return ToolOutcome(spec.alias, true, text, spec.result, spec.status.ifEmpty { "${spec.alias} ausgeführt" }, spec.remember)
    }
}
