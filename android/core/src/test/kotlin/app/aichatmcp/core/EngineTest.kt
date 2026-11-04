package app.aichatmcp.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Ganze Chatrunden gegen einen Fake-LLM-Server und einen Fake-MCP-Server (Port der GUI-End-zu-End-Tests). */
class EngineTest {
    private val servers = mutableListOf<FakeLlm>()

    @AfterTest
    fun tearDown() = servers.forEach { it.stop() }

    private val aliases = """{
        "calc": {"server": "demo", "tool": "calculate", "params": ["expression"]},
        "seven": {"server": "demo", "tool": "calculate", "args": {"expression": "6*7"}, "result": "show", "confirm": false},
        "light": {"server": "demo", "tool": "calculate", "args": {"expression": "1+1"}, "result": "discard", "status": "Light on"}
    }"""

    private class Rig(val engine: ChatEngine, val llm: FakeLlm, val confirms: MutableList<String>)

    private fun rig(
        tools: List<String>, approved: List<String> = emptyList(), config: Config = Config(model = "m", userName = "Alex", confirmTools = false),
        answer: Boolean = true, handler: (FakeLlm, kotlinx.serialization.json.JsonObject) -> List<String>?,
    ): Rig {
        lateinit var llm: FakeLlm
        llm = FakeLlm { handler(llm, it) }
        servers += llm
        val session = ChatSession(config, Persona(name = "Mia", tools = tools, approvedTools = approved), demoRegistry(aliases))
        val confirms = mutableListOf<String>()
        val engine = ChatEngine(session, { ChatClient(llm.url) }) { call, _ -> confirms += call.name; answer }
        return Rig(engine, llm, confirms)
    }

    private fun Rig.ask(text: String): List<ChatEvent> = runBlocking { engine.send(text).toList() }

    private fun Rig.history() = engine.session.history

    private fun List<ChatEvent>.notices() = filterIsInstance<ChatEvent.Notice>()
    private fun List<ChatEvent>.lastStatus() = filterIsInstance<ChatEvent.Status>().last().text

    @Test
    fun plainChatIsCleanedBeforeItReachesTheHistory() {
        val r = rig(emptyList()) { _, _ -> listOf("Hallo! ", "Das ist ein sehr langer Satz hier. ", "Das ist ein sehr langer Satz hier. ", "Ende") }
        val events = r.ask("Hi")
        assertEquals(listOf("Hi", "Hallo! Das ist ein sehr langer Satz hier."), r.history().map { it.content })
        assertEquals(ChatEvent.Finished, events.last())
        assertEquals("Bereit (1 wiederholte Sätze entfernt)", events.lastStatus())
        assertTrue(events.any { it is ChatEvent.Live }) // Zwischenstände fürs Streaming
        val request = r.llm.requests.single()
        assertEquals("system", ((request["messages"] as kotlinx.serialization.json.JsonArray)[0] as kotlinx.serialization.json.JsonObject).string("role"))
    }

    @Test
    fun modelModeRunsToolAndModelAnswersOnTheResult() {
        val config = Config(model = "m", userName = "Alex", confirmTools = true)
        val r = rig(listOf("calc"), config = config) { llm, req ->
            if (llm.lastUserContent(req).startsWith("[Ergebnis von")) listOf("Das macht 42.") else listOf("Moment. [[mcp:calc|6*7]] erfunden: 99")
        }
        val events = r.ask("Was ist 6 mal 7?")
        assertEquals(listOf(CHAT, CHAT, TOOL_RESULT, CHAT), r.history().map { it.kind })
        assertEquals(listOf("Was ist 6 mal 7?", "Moment. [[mcp:calc|6*7]]", "42", "Das macht 42."), r.history().map { it.content })
        assertEquals(2, r.llm.requests.size)
        assertEquals(listOf("calc"), r.confirms) // nicht vorab genehmigt, global an: es wird gefragt
        assertTrue(r.llm.lastUserContent(r.llm.requests[1]).startsWith("[Ergebnis von calc]\n42"))
        assertTrue(events.filterIsInstance<ChatEvent.Added>().any { it.message.kind == TOOL_RESULT })
    }

    @Test
    fun showModePrintsResultWithoutSecondRoundAndModelSeesItLater() {
        val r = rig(listOf("seven")) { llm, req -> if (llm.lastUserContent(req).contains("Danke")) listOf("Gern.") else listOf("Ich schaue nach. [[mcp:seven]] erfunden") }
        r.ask("Was ist 6 mal 7?")
        assertEquals(1, r.llm.requests.size) // keine zweite Runde
        assertEquals(listOf(CHAT, CHAT, TOOL_SHOWN), r.history().map { it.kind })
        assertEquals("42", r.history().last().content)
        r.ask("Danke dir")
        val last = r.llm.lastUserContent(r.llm.requests[1])
        assertTrue("schon angezeigt]\n42" in last)
        assertTrue(last.endsWith("Danke dir"))
    }

    @Test
    fun discardModeShowsStatusOnlyAndPreApprovalSkipsConfirmation() {
        val config = Config(model = "m", userName = "Alex", confirmTools = true)
        val r = rig(listOf("light"), approved = listOf("light"), config = config) { _, _ -> listOf("Mache ich. [[mcp:light]]") }
        val events = r.ask("Licht an")
        assertTrue(r.confirms.isEmpty()) // vorab genehmigt: keine Rückfrage trotz globaler Einstellung
        assertEquals(1, r.llm.requests.size)
        assertEquals(listOf(CHAT, CHAT), r.history().map { it.kind }) // nichts gemerkt
        assertEquals(ChatEvent.Notice("✔ Light on", NoticeKind.TOOL), events.notices().single())
    }

    @Test
    fun declinedConfirmationGoesBackToTheModel() {
        val config = Config(model = "m", userName = "Alex", confirmTools = true)
        val r = rig(listOf("light"), config = config, answer = false) { llm, req ->
            if (llm.lastUserContent(req).startsWith("[Fehler bei")) listOf("Dann eben nicht.") else listOf("Mache ich. [[mcp:light]]")
        }
        r.ask("Licht an")
        assertEquals(2, r.llm.requests.size)
        assertEquals(TOOL_ERROR, r.history()[2].kind)
        assertTrue("abgelehnt" in r.history()[2].content)
        assertTrue("abgelehnt" in r.llm.lastUserContent(r.llm.requests[1]))
    }

    @Test
    fun markerForToolNotEnabledForCompanionIsReported() {
        val r = rig(emptyList()) { _, _ -> listOf("Ich rechne [[mcp:calc|6*7]]") }
        val events = r.ask("Was ist 6 mal 7?")
        assertTrue("'calc' ist für Mia nicht freigeschaltet" in events.notices().single().text)
        assertEquals(2, r.history().size)
        assertEquals(1, r.llm.requests.size)
    }

    @Test
    fun serverErrorPutsTheQuestionBackIntoTheInputField() {
        val r = rig(emptyList()) { _, _ -> null }
        val events = r.ask("Hallo")
        assertTrue(events.notices().single().text.contains("Serverfehler 500"))
        assertEquals(ChatEvent.RestoreInput("Hallo"), events.filterIsInstance<ChatEvent.RestoreInput>().single())
        assertTrue(r.history().isEmpty())
        assertEquals("Fehler", events.lastStatus())
    }

    @Test
    fun toolCallLimitStopsLoops() {
        val config = Config(model = "m", userName = "Alex", confirmTools = false, maxToolCalls = 2)
        val r = rig(listOf("calc"), config = config) { _, _ -> listOf("Moment. [[mcp:calc|6*7]]") }
        val events = r.ask("Mach weiter")
        assertEquals(2, r.history().count { it.kind == TOOL_RESULT })
        assertTrue(events.notices().any { "Limit für Werkzeugaufrufe" in it.text })
    }

    @Test
    fun streamIsCutAtTheFirstToolCall() {
        val r = rig(listOf("seven")) { _, _ -> listOf("Ich schaue ", "nach. [[mcp:seven]]", " und erfunden: 99.", " Noch mehr Text.") }
        r.ask("Was ist 6 mal 7?")
        assertEquals("Ich schaue nach. [[mcp:seven]]", r.history()[1].content)
    }

    @Test
    fun modelListSkipsEmbeddingsAndErrorsAreReadable() {
        val llm = FakeLlm { null }.also { servers += it }
        assertEquals(listOf("chat-model"), ChatClient(llm.url).listModels())
        val error = assertFailsWith<LlmException> { ChatClient("http://127.0.0.1:1/v1").listModels() }
        assertTrue("nicht erreichbar" in error.message.orEmpty())
    }
}
