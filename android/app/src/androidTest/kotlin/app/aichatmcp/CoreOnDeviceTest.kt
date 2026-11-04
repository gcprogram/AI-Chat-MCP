package app.aichatmcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aichatmcp.core.AppJson
import app.aichatmcp.core.ChatLog
import app.aichatmcp.core.Message
import app.aichatmcp.core.Param
import app.aichatmcp.core.Persona
import app.aichatmcp.core.StreamGuard
import app.aichatmcp.core.TOOL_SHOWN
import app.aichatmcp.core.ToolSpec
import app.aichatmcp.core.WeatherServer
import app.aichatmcp.core.WebsearchServer
import app.aichatmcp.core.finalizeReply
import app.aichatmcp.core.findCall
import app.aichatmcp.core.neutralize
import app.aichatmcp.core.splitSegments
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Dieselbe Logik wie in den JVM-Tests des Kerns, aber auf dem echten Android-Laufzeitsystem. Androids Regex-Bibliothek
 * (ICU) unterscheidet sich von der JVM (z. B. kennt sie das Flag (?U) nicht) - das fällt nur hier auf.
 */
@RunWith(AndroidJUnit4::class)
class CoreOnDeviceTest {
    private fun clean(raw: String, previous: List<String> = emptyList(), tools: List<String> = emptyList()) =
        finalizeReply(raw, "Alex", "Mia", previous, tools)

    @Test
    fun replyCleanupMatchesPython() {
        assertEquals(
            "Hallo Welt. Das ist heute ein sehr schöner Tag.",
            clean("Hallo Welt. Das ist heute ein sehr schöner Tag. Das ist heute ein sehr schöner Tag. Ende gut alles gut").text,
        )
        assertEquals(
            "Und wie geht es dir denn heute so?",
            clean("Mir geht es gut, danke der Nachfrage. Und wie geht es dir denn heute so?", listOf("Mir geht es gut, danke der Nachfrage. Und du?")).text,
        )
        assertEquals("Klar.", clean("Klar. <h1>User:</h1> Hallo nochmal").text)
        assertEquals("Ich glaube das stimmt.", clean("Ich glaube das stimmt. Aber dann kommt ein abgebrochener").text)
        assertEquals("", clean("Alex: Hallo da. Wie geht's").text)
        clean("Moment. [[mcp:weather|Langen|tomorrow]] und erfundenes Ergebnis", tools = listOf("weather")).let {
            assertEquals("Moment. [[mcp:weather|Langen|tomorrow]]", it.text)
            assertEquals("[[mcp:weather|Langen|tomorrow]]", it.call?.raw)
        }
        assertEquals("Gern geschehen, sag Bescheid wenn etwas ist.", clean("Mia: Gern geschehen, sag Bescheid wenn etwas ist.").text)
        assertEquals("ha ha ha", clean("ha ha ha ha ha ha ha ha ha").text)
        assertEquals("Das klappt.", clean("Das klappt. Das klappt. Das klappt. Das klappt.").text)
    }

    @Test
    fun streamGuardMatchesPython() {
        fun run(chunks: List<String>, tools: List<String> = emptyList()): Pair<Int, String>? {
            val g = StreamGuard("Alex", "Mia", tools)
            chunks.forEachIndexed { i, c -> g.feed(c)?.let { return i to it } }
            return null
        }
        assertEquals(2 to "call", run(listOf("Ich schaue ", "kurz nach. [[mcp:", "weather|Langen]] erfunden"), listOf("weather")))
        assertEquals(0 to "junk", run(listOf("Klar. <h1>", "User:</h1> x")))
        assertEquals(1 to "repeat", run(listOf("Das ist ein ganz neuer Satz hier. ", "Das ist ein ganz neuer Satz hier. ", "Weiter")))
        assertNull(run(listOf("Alles ", "normal ", "hier.")))
    }

    @Test
    fun markersAndArguments() {
        assertEquals("weather" to "Langen|tomorrow", findCall("[[mcp:weather|Langen|tomorrow]]")!!.let { it.name to it.args })
        assertEquals("Wetter-Frueher" to "{\"ort\": \"X\"}", findCall("[mcp/Wetter-Frueher:{\"ort\": \"X\"}]")!!.let { it.name to it.args })
        assertEquals(listOf("text" to "Moment. ", "call" to "time", "text" to " Fertig."), splitSegments("Moment. [[mcp:time]] Fertig."))
        assertNull(findCall(neutralize("[[mcp:calc|1+1]]")))
        val spec = ToolSpec("weather", "w", "forecast", params = listOf(Param("place"), Param("day")), defaults = mapOf("place" to "Berlin", "day" to "today"))
        assertEquals(mapOf("place" to "Langen", "day" to "morgen"), spec.resolve("Langen|morgen"))
        assertEquals(mapOf("place" to "X", "day" to "Y"), spec.resolve("{\"place\": \"X\", \"day\": \"Y\"}"))
    }

    @Test
    fun personaPromptChatLogAndSlug() {
        assertEquals("tom_müller", Persona.slugify("Tom Müller"))
        val persona = Persona.fromJson("""{"name": "Fern", "language": "English", "tools": ["tv-p1"], "approved_tools": ["tv-p1"]}""", "fern")
        assertTrue("Antwortsprache: English." in persona.systemPrompt("Stefan"))
        val log = listOf(Message("user", "Hi"), Message("assistant", "[[mcp:weather]]"), Message("user", "sonnig", TOOL_SHOWN, "weather"))
        assertEquals(log, ChatLog.fromJson(ChatLog.toJson(log, "fern", "Stefan")).messages)
    }

    @Test
    fun builtinServersParse() {
        val today = LocalDate.of(2026, 11, 1)
        assertEquals(LocalDate.of(2025, 11, 25), WeatherServer.parseDate("25.11.", today))
        assertEquals(LocalDate.of(2020, 11, 25), WeatherServer.parseDate("2020-11-25", today))
        val data = AppJson.parseToJsonElement(
            """{"nearest_area": [{"areaName": [{"value": "Berlin"}], "country": [{"value": "Germany"}]}],
                "weather": [{"date": "2026-11-01", "mintempC": "3", "maxtempC": "12", "hourly": [
                {"time": "600", "tempC": "6", "FeelsLikeC": "5", "chanceofrain": "10", "winddirDegree": "225", "windspeedKmph": "7", "weatherDesc": [{"value": "Sunny "}]}]}]}""",
        ) as JsonObject
        assertEquals("06:00 Sunny, 6°C (feels like 5°C), rain 10%, wind SW 7 km/h", WeatherServer.forecastText(data, 0, 6, 15, 3).lines()[1])
        val page = """<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fa&amp;rut=x">Titel <b>A</b> &amp; Co</a>
            <a class="result__snippet" href="x">Auszug &quot;Zitat&quot;</a>"""
        assertEquals(WebsearchServer.Result("Titel A & Co", "https://example.org/a", "Auszug \"Zitat\""), WebsearchServer.parseResults(page, 3)[0])
    }
}
