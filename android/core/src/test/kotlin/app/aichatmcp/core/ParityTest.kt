package app.aichatmcp.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Erwartungswerte stammen aus der Python-App (identische Eingaben): Die Ports müssen sich gleich verhalten. */
class ParityTest {
    // --- difflib ------------------------------------------------------------------------------------------

    private fun w(s: String) = s.split(" ")

    @Test
    fun sequenceMatcherRatioMatchesDifflib() {
        assertEquals(0.8888888888888888, Difflib.ratio(w("the quick brown fox jumps over the lazy dog"), w("the quick brown fox leaps over the lazy dog")), 1e-12)
        assertEquals(0.9411764705882353, Difflib.ratio(w("ich schaue kurz nach dem wetter für dich"), w("ich schaue kurz nach dem wetter heute für dich")), 1e-12)
        assertEquals(0.16666666666666666, Difflib.ratio(w("a b c d e f"), w("f e d c b a")), 1e-12)
        assertEquals(1.0, Difflib.ratio(w("x y z w"), w("x y z w")), 1e-12)
        assertEquals(0.0, Difflib.ratio(w("eins zwei drei vier fünf"), w("sechs sieben acht neun zehn")), 1e-12)
    }

    // --- Antwort-Bereinigung ------------------------------------------------------------------------------

    private fun clean(raw: String, previous: List<String> = emptyList(), tools: List<String> = emptyList()) =
        finalizeReply(raw, "Alex", "Mia", previous, tools)

    @Test
    fun finalizeReplyMatchesPython() {
        clean("Hallo Welt. Das ist heute ein sehr schöner Tag. Das ist heute ein sehr schöner Tag. Ende gut alles gut").let {
            assertEquals("Hallo Welt. Das ist heute ein sehr schöner Tag.", it.text)
            assertEquals(listOf("1 wiederholte Sätze entfernt"), it.notes)
        }
        clean("Mir geht es gut, danke der Nachfrage. Und wie geht es dir denn heute so?", listOf("Mir geht es gut, danke der Nachfrage. Und du?")).let {
            assertEquals("Und wie geht es dir denn heute so?", it.text)
        }
        clean("Klar. <h1>User:</h1> Hallo nochmal").let {
            assertEquals("Klar.", it.text)
            assertEquals(listOf("Fremdtext/Markup abgeschnitten"), it.notes)
        }
        assertEquals("Ich glaube das stimmt.", clean("Ich glaube das stimmt. Aber dann kommt ein abgebrochener").text)
        assertEquals("", clean("Alex: Hallo da. Wie geht's").text) // Rolle des Nutzers: alles ist Fremdtext
        clean("Moment. [[mcp:weather|Langen|tomorrow]] und erfundenes Ergebnis", tools = listOf("weather")).let {
            assertEquals("Moment. [[mcp:weather|Langen|tomorrow]]", it.text)
            assertEquals("[[mcp:weather|Langen|tomorrow]]", it.call?.raw)
            assertEquals(listOf("Text nach dem Werkzeugaufruf verworfen"), it.notes)
        }
        assertEquals("Gern geschehen, sag Bescheid wenn etwas ist.", clean("Mia: Gern geschehen, sag Bescheid wenn etwas ist.").text)
        clean("ha ha ha ha ha ha ha ha ha").let {
            assertEquals("ha ha ha", it.text)
            assertEquals(listOf("Endlosschleife gekürzt"), it.notes)
        }
        assertEquals("Das klappt.", clean("Das klappt. Das klappt. Das klappt. Das klappt.").text)
        assertNull(clean("Ich rechne [[mcp:calc|6*7]]").call) // Werkzeuge nicht freigeschaltet: nur Text
    }

    private fun guard(chunks: List<String>, tools: List<String> = emptyList()): Pair<Int, String>? {
        val g = StreamGuard("Alex", "Mia", tools)
        chunks.forEachIndexed { i, c -> g.feed(c)?.let { return i to it } }
        return null
    }

    @Test
    fun streamGuardMatchesPython() {
        assertEquals(2 to "call", guard(listOf("Ich schaue ", "kurz nach. [[mcp:", "weather|Langen]] erfunden"), listOf("weather")))
        assertEquals(0 to "junk", guard(listOf("Klar. <h1>", "User:</h1> x")))
        assertEquals(1 to "repeat", guard(listOf("Das ist ein ganz neuer Satz hier. ", "Das ist ein ganz neuer Satz hier. ", "Weiter")))
        assertNull(guard(listOf("na ja na ja na ja na ja na ja")))
        assertNull(guard(listOf("Alles ", "normal ", "hier.")))
    }

    // --- Marker und Argumente -----------------------------------------------------------------------------

    @Test
    fun findCallIsTolerant() {
        findCall("[[mcp:weather|Langen|tomorrow]]")!!.let { assertEquals(Triple("weather", "Langen|tomorrow", 31), Triple(it.name, it.args, it.end)) }
        findCall("[mcp/Wetter-Frueher:{\"ort\": \"X\"}]")!!.let { assertEquals("Wetter-Frueher" to "{\"ort\": \"X\"}", it.name to it.args) }
        findCall("[[ MCP : web_search | KI News ]]")!!.let { assertEquals("web_search" to "KI News", it.name to it.args) }
        findCall("text [[mcp:time]] mehr")!!.let { assertEquals(Triple("time", "", 5), Triple(it.name, it.args, it.start)) }
        assertNull(findCall("kein Aufruf [mcp] hier"))
    }

    @Test
    fun segmentsAndNeutralize() {
        assertEquals(listOf("text" to "Moment. ", "call" to "time", "text" to " Fertig."), splitSegments("Moment. [[mcp:time]] Fertig."))
        assertEquals("Seite: ((mcp:calc|1+1)) und (mcp/time)", neutralize("Seite: [[mcp:calc|1+1]] und [mcp/time]"))
        assertNull(findCall(neutralize("[[mcp:calc|1+1]]")))
    }

    private val weather = ToolSpec(
        "weather", "w", "forecast", params = listOf(Param("place"), Param("day")),
        defaults = mapOf("place" to "Berlin", "day" to "today"),
    )

    @Test
    fun resolveMatchesPython() {
        assertEquals(mapOf("place" to "Berlin", "day" to "today"), weather.resolve(""))
        assertEquals(mapOf("place" to "Langen", "day" to "morgen"), weather.resolve("Langen|morgen"))
        assertEquals(mapOf("place" to "Langen", "day" to "today"), weather.resolve("place=Langen"))
        assertEquals(mapOf("place" to "X", "day" to "Y"), weather.resolve("{\"place\": \"X\", \"day\": \"Y\"}"))
        assertEquals(mapOf("place" to "a", "day" to "b"), weather.resolve("a|b|c"))
        assertEquals(mapOf("place" to "Langen", "day" to "today"), weather.resolve("Langen|"))
        val single = ToolSpec("s", "x", "search", params = listOf(Param("query", required = true)))
        assertEquals(mapOf<String, Any?>("query" to "KI Nachrichten heute"), single.bind("KI Nachrichten | heute"))
        assertEquals(mapOf<String, Any?>("query" to "hallo welt"), single.bind("\"hallo welt\""))
        assertEquals(mapOf<String, Any?>("query" to "abc"), single.bind("query=abc"))
        val error = assertFailsWith<ToolArgError> { single.resolve("") }
        assertEquals("Es fehlt: query. Richtiges Format: [[mcp:s|<query>]]", error.message)
    }

    @Test
    fun typesAreCoercedAndFixedArgsWin() {
        val spec = ToolSpec(
            "x", "s", "t", params = listOf(Param("n", "integer"), Param("f", "number"), Param("b", "boolean"), Param("code", "string")),
            fixed = mapOf("code" to "0x11"),
        )
        assertEquals(mapOf("n" to 3L, "f" to 2.5, "b" to true, "code" to "0x11"), spec.resolve("3,0|2,5|ja|egal"))
    }

    // --- Kontext ------------------------------------------------------------------------------------------

    @Test
    fun contextKeepsNewestAndStartsWithUser() {
        val cm = ContextManager()
        val history = listOf(
            Message("user", "a".repeat(600)), Message("assistant", "b".repeat(600) + " [[mcp:time]]"),
            Message("user", "c".repeat(600), TOOL_RESULT, "time"), Message("assistant", "d".repeat(600)),
            Message("user", "Frage"),
        )
        val (kept, dropped) = cm.fit("sys", history, budgetTokens = 450)
        assertEquals("user", kept.first().role)
        assertTrue(!kept.first().isTool)
        assertEquals("Frage", kept.last().content)
        assertEquals(history.size - kept.size, dropped)
        assertTrue(dropped >= 2)
        assertEquals(1, cm.fit("sys", listOf(Message("user", "x".repeat(10_000))), 100).first.size) // die letzte bleibt immer
    }

    @Test
    fun calibrationSmoothsTowardObservedRatio() {
        val cm = ContextManager()
        cm.calibrate(chars = 1000, promptTokens = 200) // beobachtet 5.0 -> 0.5*3 + 0.5*5 = 4.0
        assertEquals(4.0, cm.charsPerToken, 1e-9)
        cm.calibrate(0, 100)
        assertEquals(4.0, cm.charsPerToken, 1e-9)
    }

    // --- Konfiguration und Persona (Dateiformat der Python-App) -------------------------------------------

    @Test
    fun configAndPersonaReadPythonFiles() {
        val config = Config.fromJson(
            """{"base_url": "http://192.168.1.5:1234/v1/", "api_key": "k", "model": "m", "persona": "fern", "user_name": " Stefan ",
               "temperature": 9, "max_tool_calls": 3, "unbekannt": 1}""",
        )
        assertEquals("http://192.168.1.5:1234/v1", config.baseUrl)
        assertEquals(2.0, config.temperature) // geklemmt
        assertEquals("Stefan", config.userName)
        assertEquals(Config(), Config.fromJson("{"))
        assertEquals(mapOf("top_p" to 0.9, "frequency_penalty" to 0.3, "presence_penalty" to 0.2, "repeat_penalty" to 1.1), Config().samplingParams())
        assertEquals(emptyMap(), Config(topP = 1.0, frequencyPenalty = 0.0, presencePenalty = 0.0, repeatPenalty = 0.0).samplingParams())

        val persona = Persona.fromJson(
            """{"name": "Fern", "description": "Maid.", "language": "English", "greeting": "Hi!", "rules": [],
               "avatar": "avatars/x.png", "tools": ["websearch", "tv-p1"], "approved_tools": ["tv-p1"], "tool_mode": "magisch"}""",
            "fern",
        )
        assertEquals(Triple("fern", listOf("tv-p1"), TOOL_MODE_MARKER), Triple(persona.id, persona.approvedTools, persona.toolMode))
        val prompt = persona.systemPrompt("Stefan", "Tools:\n- x")
        for (needle in listOf("Du bist Fern. Maid.", "Antwortsprache: English.", "Wiederhole dich nicht", "Tools:\n- x", "Begrüßung begonnen: \"Hi!\"")) {
            assertTrue(needle in prompt, needle)
        }
        assertEquals(listOf("\nFern:", "\nNutzer:", "\nStefan:", "\nUser:"), persona.stopSequences("Stefan"))
        assertEquals(persona.copy(id = ""), Persona.fromJson(persona.toJson()))
        assertEquals("tom_müller", Persona.slugify("Tom Müller"))
    }

    @Test
    fun sessionPutsShownResultsInFrontOfNextUserMessageAndRolesAlternate() {
        val session = ChatSession(Config(userName = "Alex"), Persona(name = "Mia"))
        session.addUser("Wie wird das Wetter?")
        session.addAssistant("Ich schaue nach. [[mcp:weather]]")
        session.addToolShown("weather", "06:00 sonnig, 10°C")
        session.addUser("Und was ziehe ich an?")
        val messages = session.requestMessages()
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.role })
        assertTrue(messages.last().content.startsWith("[Ergebnis von weather, Alex schon angezeigt]\n06:00 sonnig, 10°C"))
        assertTrue(messages.last().content.endsWith("Und was ziehe ich an?"))
        assertTrue("Antworte Alex jetzt" !in messages.last().content)
        // geladener Chat: verwaistes Ergebnis am Ende wird entfernt, nichts zurückgegeben (Antwort des Modells ist letzte)
        val orphan = session.load(listOf(Message("user", "Hi"), Message("assistant", "[[mcp:time]]"), Message("user", "Mo", TOOL_RESULT, "time")))
        assertEquals("", orphan)
        assertEquals(2, session.history.size)
        // unbeantwortete letzte Frage kommt zurück (fürs Eingabefeld)
        assertEquals("Noch da?", session.load(listOf(Message("user", "Hi"), Message("assistant", "Hallo!"), Message("user", "Noch da?"))))
        assertEquals(2, session.history.size)
        // angezeigtes Ergebnis am Ende bleibt (Endzustand von show) und wird nicht als Frage zurückgegeben
        val shown = listOf(Message("user", "Wetter?"), Message("assistant", "[[mcp:weather]]"), Message("user", "sonnig", TOOL_SHOWN, "weather"))
        assertEquals("", session.load(shown))
        assertEquals(shown, session.history)
        assertNotNull(session.toolNames)
    }
}
