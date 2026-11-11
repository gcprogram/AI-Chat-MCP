package app.aichatmcp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/*
 * Eingebaute MCP-Server (Ports von servers/weather_server.py und servers/websearch_server.py): Auf Android gibt es
 * keine Unterprozesse, die Werkzeuge laufen deshalb im Prozess. Namen, Parameter und Ausgaben sind identisch.
 */

/** Fehler, der als Werkzeugergebnis (isError) an das Modell geht. */
class ToolError(message: String) : Exception(message)

/** HTTP-Abruf (GET, oder POST mit Formularfeldern); Netzfehler werden zu ToolError. */
fun interface Fetcher {
    fun fetch(url: String, form: Map<String, String>?): String
}

class OkHttpFetcher(http: OkHttpClient = OkHttpClient()) : Fetcher {
    private val client = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    override fun fetch(url: String, form: Map<String, String>?): String {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (compatible; ai-chat-mcp)").apply {
            if (form != null) post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
        }.build()
        try {
            client.newCall(request).execute().use { return it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw ToolError("Keine Verbindung zum Dienst (${e.message}).")
        }
    }
}

private fun fetchJson(fetcher: Fetcher, url: String): JsonObject = try {
    AppJson.parseToJsonElement(fetcher.fetch(url, null)) as JsonObject
} catch (e: ToolError) {
    throw e
} catch (_: Exception) {
    throw ToolError("Der Dienst hat keine gültige Antwort geliefert.")
}

/** Gemeinsamer Unterbau: feste Werkzeugliste, Aufrufe laufen als Funktionen im Prozess. */
abstract class LocalServer(override val name: String) : McpClient {
    protected abstract val tools: List<McpTool>
    protected abstract val handlers: Map<String, (Map<String, Any?>) -> String>

    override fun start(timeoutSec: Double) {}
    override fun close() {}
    override fun listTools(timeoutSec: Double): List<McpTool> = tools

    override fun callTool(name: String, arguments: Map<String, Any?>, timeoutSec: Double): Pair<String, Boolean> {
        val handler = handlers[name] ?: return "Unbekanntes Werkzeug: $name" to true
        return try {
            handler(arguments) to false
        } catch (e: ToolError) {
            e.message.orEmpty() to true
        }
    }

    protected fun tool(
        name: String, description: String, properties: List<Triple<String, String, String>>, required: List<String>,
        readOnly: Boolean = true,
    ) =
        McpTool(
            name, description,
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    for ((n, type, desc) in properties) putJsonObject(n) { put("type", type); put("description", desc) }
                }
                if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            },
            readOnly = readOnly,
        )
}

// --- Wetter -------------------------------------------------------------------------------------------

class WeatherServer(name: String, private val fetcher: Fetcher, private val today: () -> LocalDate = { LocalDate.now() }) :
    LocalServer(name) {

    override val tools = listOf(
        tool(
            "forecast", "Weather forecast for a place, hour by hour (today, tomorrow or day after tomorrow).",
            listOf(
                Triple("place", "string", "City, postal code or 'latitude, longitude'"),
                Triple("day", "string", "today, tomorrow or day after tomorrow"),
                Triple("from_hour", "integer", "First hour (0-23), default 6"),
                Triple("to_hour", "integer", "Last hour, default 21"),
                Triple("step", "integer", "Hours between lines, default 3"),
            ),
            listOf("place"),
        ),
        tool(
            "historical", "Weather on a past day (temperature, precipitation, wind).",
            listOf(
                Triple("place", "string", "City, optionally with region ('Langen, Hesse') or 'latitude, longitude'"),
                Triple("date", "string", "yesterday, day before yesterday, 2020-11-25 or 25.11.2020"),
            ),
            listOf("place", "date"),
        ),
    )
    override val handlers = mapOf<String, (Map<String, Any?>) -> String>("forecast" to ::forecast, "historical" to ::historical)

    private fun forecast(arguments: Map<String, Any?>): String {
        val place = arguments["place"]?.toString().orEmpty().trim()
        if (place.isEmpty()) throw ToolError("The place is missing.")
        val key = (arguments["day"]?.toString()?.ifBlank { null } ?: "today").trim().lowercase().split(Regex("\\s+")).joinToString(" ")
        val day = DAYS[key] ?: throw ToolError("day must be today, tomorrow or day after tomorrow.")
        val start = intArg(arguments, "from_hour", 6, 0, 23)
        val end = intArg(arguments, "to_hour", 21, 1, 24)
        // wttr.in antwortet bei einem unbekannten Ort mit Klartext ("location not found"), nicht mit JSON
        val raw = fetcher.fetch("https://wttr.in/${urlEncode(place)}?format=j1", null)
        val data = try {
            AppJson.parseToJsonElement(raw) as JsonObject
        } catch (_: Exception) {
            if (raw.contains("not found", ignoreCase = true) || raw.contains("unknown location", ignoreCase = true)) {
                throw ToolError(NOT_FOUND.format(place))
            }
            throw ToolError("The weather service returned no valid answer.")
        }
        return forecastText(data, day, start, maxOf(end, start + 1), intArg(arguments, "step", 3, 1, 12))
    }

    private fun historical(arguments: Map<String, Any?>): String {
        val place = arguments["place"]?.toString().orEmpty().trim()
        if (place.isEmpty()) throw ToolError("The place is missing.")
        val day = parseDate(arguments["date"]?.toString(), today())
        if (day.isAfter(today())) throw ToolError("That date is in the future; use the forecast instead.")
        val coords = Regex("([-+]?\\d{1,2}(?:\\.\\d+)?),\\s*([-+]?\\d{1,3}(?:\\.\\d+)?)").matchEntire(place)
        val lat: String
        val lon: String
        val name: String
        if (coords != null) {
            lat = coords.groupValues[1]; lon = coords.groupValues[2]; name = place
        } else {
            val namePart = place.substringBefore(",").trim()
            val hint = if ("," in place) place.substringAfter(",") else ""
            val geo = fetchJson(fetcher, "https://geocoding-api.open-meteo.com/v1/search?name=${urlEncode(namePart)}&count=10&language=en&format=json")
            val results = (geo["results"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            if (results.isEmpty()) throw ToolError(NOT_FOUND.format(place))
            val found = pickPlace(results, hint)
            lat = found["latitude"].toString(); lon = found["longitude"].toString()
            name = listOfNotNull(found.string("name"), found.string("admin1")).filter { it.isNotEmpty() }.joinToString(", ")
        }
        val keys = "temperature_2m_max,temperature_2m_min,precipitation_sum,weathercode,windspeed_10m_max,winddirection_10m_dominant"
        val data = fetchJson(
            fetcher,
            "https://archive-api.open-meteo.com/v1/archive?latitude=$lat&longitude=$lon&start_date=$day&end_date=$day&daily=$keys&timezone=auto",
        )
        return historicalText(name, day, data.obj("daily") ?: JsonObject(emptyMap()))
    }

    companion object {
        /** Klare Anweisung an das Modell, sonst deutet es den Fehler als "kein Zugriff auf Wetterdaten". */
        private const val NOT_FOUND =
            "Place '%s' not found. Tell the user that this place was not found and ask them to check the spelling. Do not call the tool again."
        private val DIRECTIONS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        private val DAYS = mapOf(
            "today" to 0, "heute" to 0, "tomorrow" to 1, "morgen" to 1, "day after tomorrow" to 2,
            "uebermorgen" to 2, "übermorgen" to 2,
        )
        private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        private val WMO = mapOf(
            0 to "clear", 1 to "mostly clear", 2 to "partly cloudy", 3 to "overcast", 45 to "fog", 48 to "rime fog",
            51 to "light drizzle", 53 to "drizzle", 55 to "heavy drizzle", 56 to "freezing drizzle", 57 to "freezing drizzle",
            61 to "light rain", 63 to "rain", 65 to "heavy rain", 66 to "freezing rain", 67 to "freezing rain",
            71 to "light snow", 73 to "snow", 75 to "heavy snow", 77 to "snow grains", 80 to "light rain showers",
            81 to "rain showers", 82 to "violent rain showers", 85 to "light snow showers", 86 to "snow showers",
            95 to "thunderstorm", 96 to "thunderstorm with hail", 99 to "thunderstorm with hail",
        )

        private fun urlEncode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun intArg(arguments: Map<String, Any?>, key: String, default: Int, low: Int, high: Int): Int {
            val raw = arguments[key] ?: return default
            return raw.toString().replace(",", ".").toDoubleOrNull()?.toInt()?.coerceIn(low, high) ?: default
        }

        fun compass(degrees: Double): String = DIRECTIONS[Math.rint(degrees / 22.5).toInt().mod(16)]

        /** 'yesterday', 'day before yesterday', 'today', '2020-11-25', '25.11.2020', '25.11.20', '25.11.' -> Datum. */
        fun parseDate(text: String?, today: LocalDate): LocalDate {
            val word = (text ?: "").trim().lowercase().trimEnd('.').split(Regex("\\s+")).joinToString(" ")
            if (word.isEmpty() || word == "yesterday" || word == "gestern") return today.minusDays(1)
            if (word == "today" || word == "heute") return today
            if (word == "day before yesterday" || word == "vorgestern") return today.minusDays(2)
            val year: Int
            val month: Int
            val day: Int
            val iso = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matchEntire(word)
            if (iso != null) {
                year = iso.groupValues[1].toInt(); month = iso.groupValues[2].toInt(); day = iso.groupValues[3].toInt()
            } else {
                val m = Regex("(\\d{1,2})\\.\\s*(\\d{1,2})\\.?\\s*(\\d{2,4})?").matchEntire(text!!.trim().lowercase())
                    ?: throw ToolError("Date '$text' not understood. Allowed: yesterday, day before yesterday, 2020-11-25, 25.11.2020.")
                day = m.groupValues[1].toInt(); month = m.groupValues[2].toInt()
                var y = if (m.groupValues[3].isNotEmpty()) m.groupValues[3].toInt() else today.year
                if (y < 100) y += 2000
                if (m.groupValues[3].isEmpty()) {
                    try {
                        if (LocalDate.of(y, month, day).isAfter(today)) y -= 1 // '25.11.' ohne Jahr meint das letzte Mal
                    } catch (_: Exception) {
                    }
                }
                year = y
            }
            return try {
                LocalDate.of(year, month, day)
            } catch (_: Exception) {
                throw ToolError("Date '$text' does not exist.")
            }
        }

        private fun longDate(d: LocalDate) = "${WEEKDAYS[d.dayOfWeek.value - 1]}, $d"

        private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull ?: this.toString()

        fun forecastText(data: JsonObject, dayIndex: Int, start: Int, end: Int, step: Int): String {
            val area = ((data["nearest_area"] as? JsonArray)?.firstOrNull() as? JsonObject) ?: JsonObject(emptyMap())
            val parts = listOf("areaName", "region", "country").mapNotNull { k ->
                ((area[k] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("value")?.takeIf { it.isNotEmpty() }
            }
            val place = parts.distinct().joinToString(", ") // "Berlin, Berlin, Germany" -> "Berlin, Germany"
            val days = (data["weather"] as? JsonArray).orEmpty()
            if (dayIndex >= days.size) throw ToolError("There is no forecast for that day.")
            val day = days[dayIndex] as JsonObject
            val lines = mutableListOf(
                "Weather for $place, ${longDate(LocalDate.parse(day.string("date")!!))}: " +
                    "${day["mintempC"].text()} to ${day["maxtempC"].text()}°C",
            )
            for (h in (day["hourly"] as? JsonArray).orEmpty()) {
                val hour = h as JsonObject
                val clock = hour["time"].text().toInt() / 100
                if (clock < start || clock >= end || (clock - start) % step != 0) continue
                val desc = (((hour["weatherDesc"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("value") ?: "?").trim()
                lines += "%02d:00 %s, %s°C (feels like %s°C), rain %s%%, wind %s %s km/h".format(
                    clock, desc, hour["tempC"].text(), hour["FeelsLikeC"].text(), hour["chanceofrain"].text(),
                    compass(hour["winddirDegree"].text().toDouble()), hour["windspeedKmph"].text(),
                )
            }
            if (lines.size == 1) throw ToolError("There is no data for the requested hours.")
            return lines.joinToString("\n")
        }

        fun historicalText(place: String, day: LocalDate, daily: JsonObject): String {
            fun value(key: String): JsonElement? = (daily[key] as? JsonArray)?.firstOrNull()?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            if (value("temperature_2m_max") == null) {
                throw ToolError("No data for $day yet (the archive lags a few days behind).")
            }
            val code = value("weathercode")?.text()?.toDoubleOrNull()?.toInt()
            val wind = value("winddirection_10m_dominant")?.text()?.toDoubleOrNull()
            return listOf(
                "Weather in $place on ${longDate(day)}: ${WMO[code] ?: "unknown"}",
                "High ${value("temperature_2m_max").text()}°C, low ${value("temperature_2m_min").text()}°C",
                "Precipitation ${value("precipitation_sum").text()} mm",
                "Wind up to ${value("windspeed_10m_max").text()} km/h" + (if (wind != null) " from ${compass(wind)}" else ""),
            ).joinToString("\n")
        }

        /** Mehrdeutiger Ortsname ('Langen'): bei Zusatz ('Langen, Hessen') den passenden Treffer wählen, sonst den ersten. */
        fun pickPlace(results: List<JsonObject>, hint: String): JsonObject {
            val h = hint.trim().lowercase()
            if (h.isNotEmpty()) {
                for (found in results) {
                    val text = listOf("admin1", "admin2", "country").joinToString(" ") { found.string(it).orEmpty() }.lowercase()
                    if (h in text) return found
                }
            }
            return results.first()
        }
    }
}

// --- Websuche -----------------------------------------------------------------------------------------

class WebsearchServer(name: String, private val fetcher: Fetcher) : LocalServer(name) {
    override val tools = listOf(
        tool(
            "search", "Searches the web and returns the top results with a short excerpt.",
            listOf(Triple("query", "string", "What to search for"), Triple("count", "integer", "Number of results, default 5")),
            listOf("query"),
        ),
    )
    override val handlers = mapOf<String, (Map<String, Any?>) -> String>("search" to ::search)

    private fun search(arguments: Map<String, Any?>): String {
        val query = arguments["query"]?.toString().orEmpty().trim()
        if (query.isEmpty()) throw ToolError("The search query is missing.")
        val limit = arguments["count"]?.toString()?.toDoubleOrNull()?.toInt()?.coerceIn(1, 8) ?: 5
        val page = fetcher.fetch("https://html.duckduckgo.com/html/", mapOf("q" to query, "kl" to "wt-wt"))
        val results = parseResults(page, limit)
        if (results.isEmpty()) throw ToolError("No results (or the search service rejected the request).")
        return formatResults(results)
    }

    data class Result(val title: String, val url: String, val snippet: String)

    companion object {
        private val LINK_RE = Regex("<a[^>]+class=\"result__a\"[^>]+href=\"(?<url>[^\"]+)\"[^>]*>(?<title>.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        private val SNIPPET_RE = Regex("<a[^>]+class=\"result__snippet\"[^>]*>(?<text>.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        private val ENTITY_RE = Regex("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);")

        internal fun unescape(text: String): String = ENTITY_RE.replace(text) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> {
                    val code = if (e.startsWith("#x")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    if (code != null) String(Character.toChars(code)) else m.value
                }
            }
        }

        internal fun clean(fragment: String) =
            unescape(fragment.replace(Regex("<[^>]+>"), "")).trim().split(Regex("\\s+")).joinToString(" ")

        /** DuckDuckGo verpackt Links teils als //duckduckgo.com/l/?uddg=<Ziel>. */
        internal fun realUrl(raw: String): String {
            val url = unescape(raw)
            val target = Regex("[?&]uddg=([^&]*)").find(url)?.groupValues?.get(1)
            return if (target != null) URLDecoder.decode(target, "UTF-8") else url
        }

        fun parseResults(page: String, limit: Int): List<Result> {
            val links = LINK_RE.findAll(page).toList()
            return links.take(limit).mapIndexed { index, link ->
                val end = if (index + 1 < links.size) links[index + 1].range.first else page.length
                val snippet = SNIPPET_RE.find(page.substring(link.range.last + 1, end))
                Result(clean(link.groups["title"]!!.value), realUrl(link.groups["url"]!!.value), snippet?.let { clean(it.groups["text"]!!.value) }.orEmpty())
            }
        }

        fun formatResults(results: List<Result>): String =
            results.mapIndexed { i, r -> "${i + 1}. ${r.title} (${r.url})\n   ${r.snippet}".trimEnd() }.joinToString("\n")
    }
}

// --- Server aus der Konfiguration ---------------------------------------------------------------------

/**
 * Baut die Server der mcp_servers.json auf Android: "builtin" (oder ein Python-Skript der Desktop-App, das hier
 * eingebaut vorliegt) läuft im Prozess, "url" über HTTP. Eigene Kommandos (Unterprozesse) gibt es auf Android nicht.
 */
class DefaultServerFactory(
    private val fetcher: Fetcher = OkHttpFetcher(),
    /** Geheimnisse für ${NAME} in Headern (kein Zugriff auf Umgebungsvariablen des Geräts). */
    private val secret: (String) -> String? = { null },
    /** PEM-Text eines importierten Zertifikats nach Dateiname (ohne Pfad). */
    private val certificate: (String) -> String? = { null },
    private val http: OkHttpClient = OkHttpClient(),
    /** Speicher der Todos für den eingebauten Server "organizer". */
    private val todos: TodoStore? = null,
    /** Gerätekalender für cal_add und cal_list im Server "organizer" (ohne ihn gibt es diese Werkzeuge nicht). */
    private val calendar: CalendarBackend? = null,
) : ServerFactory {
    override fun create(name: String, entry: JsonObject): McpClient {
        val builtin = entry.string("builtin") ?: scriptBuiltin(entry)
        when (builtin) {
            "weather" -> return WeatherServer(name, fetcher)
            "websearch" -> return WebsearchServer(name, fetcher)
            "organizer" -> return OrganizerServer(name, todos ?: throw McpException("Der Server 'organizer' braucht einen Speicher für die Todos."), calendar = calendar)
            null -> {}
            else -> throw McpException("Eingebauter Server '$builtin' unbekannt (weather, websearch, organizer).")
        }
        val url = entry.string("url")
            ?: throw McpException("Server mit 'command' lassen sich auf Android nicht starten; 'url' oder 'builtin' verwenden.")
        val headers = (entry["headers"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }
        val certName = entry.string("cert")?.substringAfterLast('/')?.substringAfterLast('\\')
        val pem = certName?.let { certificate(it) ?: throw McpException("Zertifikat '$certName' ist nicht importiert.") }
        return HttpMcpClient(name, url, headers, pem, secret, http)
    }

    /** Die mitgelieferten Python-Server der Desktop-App erkennt man am Skriptnamen. */
    private fun scriptBuiltin(entry: JsonObject): String? {
        val args = (entry["args"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return when {
            args.any { it.endsWith("weather_server.py") } -> "weather"
            args.any { it.endsWith("websearch_server.py") } -> "websearch"
            else -> null
        }
    }
}
