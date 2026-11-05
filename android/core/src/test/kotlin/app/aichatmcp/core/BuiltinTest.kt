package app.aichatmcp.core

import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BuiltinTest {
    private val today = LocalDate.of(2026, 11, 1)

    @Test
    fun parseDateVariants() {
        fun p(text: String?) = WeatherServer.parseDate(text, today)
        assertEquals(LocalDate.of(2026, 10, 31), p("yesterday"))
        assertEquals(LocalDate.of(2026, 10, 31), p("gestern")) // deutsche Wörter gehen auch
        assertEquals(LocalDate.of(2026, 10, 30), p("Day before Yesterday"))
        assertEquals(LocalDate.of(2026, 10, 31), p(""))
        assertEquals(LocalDate.of(2020, 11, 25), p("2020-11-25"))
        assertEquals(LocalDate.of(2020, 11, 25), p("25.11.2020"))
        assertEquals(LocalDate.of(2020, 11, 25), p("25.11.20"))
        assertEquals(LocalDate.of(2025, 11, 25), p("25.11.")) // sonst läge es in der Zukunft
        assertEquals(LocalDate.of(2026, 10, 24), p("24.10."))
        for (bad in listOf("recently", "31.02.2020", "2020-13-01", "31.02.")) assertFailsWith<ToolError> { p(bad) }
    }

    private val forecastJson = """{
      "nearest_area": [{"areaName": [{"value": "Berlin"}], "region": [{"value": "Berlin"}], "country": [{"value": "Germany"}]}],
      "weather": [{"date": "2026-11-01", "mintempC": "3", "maxtempC": "12", "hourly": [
         {"time": "0", "tempC": "0", "FeelsLikeC": "-1", "chanceofrain": "10", "winddirDegree": "225", "windspeedKmph": "7", "weatherDesc": [{"value": "Sunny "}]},
         {"time": "600", "tempC": "6", "FeelsLikeC": "5", "chanceofrain": "10", "winddirDegree": "225", "windspeedKmph": "7", "weatherDesc": [{"value": "Sunny "}]},
         {"time": "900", "tempC": "9", "FeelsLikeC": "8", "chanceofrain": "10", "winddirDegree": "225", "windspeedKmph": "7", "weatherDesc": [{"value": "Sunny "}]},
         {"time": "1200", "tempC": "12", "FeelsLikeC": "11", "chanceofrain": "10", "winddirDegree": "225", "windspeedKmph": "7", "weatherDesc": [{"value": "Sunny "}]}]}]
    }"""

    @Test
    fun forecastAndHistoricalTextMatchPython() {
        val data = AppJson.parseToJsonElement(forecastJson) as JsonObject
        val lines = WeatherServer.forecastText(data, 0, 6, 15, 3).lines()
        assertEquals("Weather for Berlin, Germany, Sunday, 2026-11-01: 3 to 12°C", lines[0])
        assertEquals(listOf("06:00", "09:00", "12:00"), lines.drop(1).map { it.take(5) })
        assertEquals("06:00 Sunny, 6°C (feels like 5°C), rain 10%, wind SW 7 km/h", lines[1])
        assertFailsWith<ToolError> { WeatherServer.forecastText(data, 1, 6, 15, 3) } // morgen fehlt

        val daily = AppJson.parseToJsonElement(
            """{"temperature_2m_max": [6.1], "temperature_2m_min": [0.3], "precipitation_sum": [0.0], "weathercode": [3],
                "windspeed_10m_max": [10.7], "winddirection_10m_dominant": [157]}""",
        ) as JsonObject
        val text = WeatherServer.historicalText("Langen", LocalDate.of(2020, 11, 25), daily)
        assertTrue("on Wednesday, 2020-11-25: overcast" in text)
        assertTrue("High 6.1°C, low 0.3°C" in text)
        assertTrue("Precipitation 0.0 mm" in text)
        assertTrue("Wind up to 10.7 km/h from SSE" in text)
        assertFailsWith<ToolError> {
            WeatherServer.historicalText("Langen", today, AppJson.parseToJsonElement("""{"temperature_2m_max": [null]}""") as JsonObject)
        }
    }

    @Test
    fun pickPlaceUsesTheRegionHint() {
        val results = listOf(
            AppJson.parseToJsonElement("""{"name": "Langen", "admin1": "Lower Saxony", "country": "Germany"}""") as JsonObject,
            AppJson.parseToJsonElement("""{"name": "Langen", "admin1": "Hesse", "country": "Germany"}""") as JsonObject,
        )
        assertEquals("Hesse", WeatherServer.pickPlace(results, " Hesse").string("admin1"))
        assertEquals("Lower Saxony", WeatherServer.pickPlace(results, "").string("admin1")) // ohne Zusatz: erster Treffer
        assertEquals("Lower Saxony", WeatherServer.pickPlace(results, "Bavaria").string("admin1"))
    }

    @Test
    fun weatherServerReportsErrorsWithoutNetworkAndUsesTheFetcher() {
        val urls = mutableListOf<String>()
        val server = WeatherServer("weather", { url, _ -> urls += url; forecastJson }, { today })
        val (history, isError) = server.callTool("historical", mapOf("place" to "Berlin", "date" to "recently"))
        assertTrue(isError && "not understood" in history)
        assertTrue(server.callTool("forecast", mapOf("place" to " ")).second)
        assertTrue(server.callTool("forecast", mapOf("place" to "Berlin", "day" to "nextweek")).second)
        val (text, error) = server.callTool("forecast", mapOf("place" to "Langen, Hessen", "day" to "today", "from_hour" to 9, "to_hour" to 13))
        assertTrue(!error)
        assertEquals(listOf("09:00", "12:00"), text.lines().drop(1).map { it.take(5) })
        assertEquals("https://wttr.in/Langen%2C%20Hessen?format=j1", urls.single())
        assertEquals(listOf("forecast", "historical"), server.listTools().map { it.name })
        assertTrue(server.listTools().all { it.readOnly })
        assertEquals("Unbekanntes Werkzeug: x" to true, server.callTool("x", emptyMap()))
    }

    @Test
    fun unknownPlaceGivesAClearInstructionInsteadOfAParseError() {
        val server = WeatherServer("weather", { _, _ -> "location not found: location not found" }, { today })
        val (text, isError) = server.callTool("forecast", mapOf("place" to "Padaborn"))
        assertTrue(isError)
        assertTrue("'Padaborn' not found" in text && "check the spelling" in text, text)
        val (other, otherError) = WeatherServer("weather", { _, _ -> "<html>oops</html>" }, { today }).callTool("forecast", mapOf("place" to "Berlin"))
        assertTrue(otherError && "no valid answer" in other)
    }

    private val page = """
    <div class="result"><h2 class="result__title"><a rel="nofollow" class="result__a"
      href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fa%3Fx%3D1&amp;rut=abc">Titel <b>A</b> &amp; Co</a></h2>
      <a class="result__snippet" href="x">Auszug mit <b>Fett</b> und &quot;Zitat&quot;</a></div>
    <div class="result"><h2><a class="result__a" href="https://example.org/b">Titel B</a></h2></div>
    <div class="result"><h2><a class="result__a" href="https://example.org/c">Titel C</a></h2>
      <a class="result__snippet" href="y">Auszug C</a></div>
    """

    @Test
    fun websearchParsesAndFormatsResults() {
        val results = WebsearchServer.parseResults(page, 5)
        assertEquals(WebsearchServer.Result("Titel A & Co", "https://example.org/a?x=1", "Auszug mit Fett und \"Zitat\""), results[0])
        assertEquals(WebsearchServer.Result("Titel B", "https://example.org/b", ""), results[1]) // Snippet gehört nicht zum Nachbarn
        assertEquals("Auszug C", results[2].snippet)
        assertEquals(2, WebsearchServer.parseResults(page, 2).size)
        assertTrue(WebsearchServer.formatResults(results).startsWith("1. Titel A & Co (https://example.org/a?x=1)\n   Auszug"))

        val forms = mutableListOf<Map<String, String>?>()
        val server = WebsearchServer("websearch") { _, form -> forms += form; page }
        assertEquals(3, server.callTool("search", mapOf("query" to "KI Nachrichten", "count" to 3)).first.lines().count { it[0].isDigit() })
        assertEquals(mapOf("q" to "KI Nachrichten", "kl" to "wt-wt"), forms.single())
        assertTrue(server.callTool("search", mapOf("query" to " ")).second)
        assertTrue(WebsearchServer("w") { _, _ -> "<html></html>" }.callTool("search", mapOf("query" to "x")).second)
    }

    @Test
    fun factoryMapsDesktopScriptsAndRejectsProcesses() {
        val factory = DefaultServerFactory(fetcher = { _, _ -> "{}" }, certificate = { if (it == "cert-usb.pem") "PEM" else null })

        fun entry(text: String) = AppJson.parseToJsonElement(text) as JsonObject
        assertTrue(factory.create("w", entry("""{"command": "python", "args": ["servers/weather_server.py"]}""")) is WeatherServer)
        assertTrue(factory.create("s", entry("""{"command": "python", "args": ["C:/x/servers/websearch_server.py"]}""")) is WebsearchServer)
        assertTrue(factory.create("b", entry("""{"builtin": "weather"}""")) is WeatherServer)
        assertTrue("Android" in assertFailsWith<McpException> { factory.create("n", entry("""{"command": "node", "args": ["x.js"]}""")) }.message.orEmpty())
        assertTrue("unbekannt" in assertFailsWith<McpException> { factory.create("n", entry("""{"builtin": "nix"}""")) }.message.orEmpty())
        assertTrue(
            "nicht importiert" in assertFailsWith<McpException> {
                factory.create("r", entry("""{"url": "https://127.0.0.1:8443/mcp", "cert": "~/.broadcast-remote/anders.pem"}"""))
            }.message.orEmpty(),
        )
    }
}
