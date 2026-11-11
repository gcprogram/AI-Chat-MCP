package app.aichatmcp.core

private const val OLD_RESULT_CHARS = 300 // Werkzeugergebnisse früherer Runden werden gekürzt gesendet (kleiner Kontext)
private const val DOCUMENT_MARGIN_TOKENS = 150 // Platz für Anweisung, Rollenmarken und die Abweichung der Schätzung

/**
 * Chat-Verlauf einer Unterhaltung (Port von session.py, Textmarker-Modus). Der Client ist zustandslos: jede Anfrage
 * enthält System-Prompt und (gekürzten) Verlauf vollständig - der Server speichert nichts mit.
 */
class ChatSession(var config: Config, var persona: Persona, var registry: ToolRegistry = ToolRegistry()) {
    val history = mutableListOf<Message>()
    val context = ContextManager()

    /** Nachrichten, die bei der letzten Anfrage nicht mehr mitgesendet wurden. */
    var dropped = 0
        private set

    /** Für diesen Companion freigeschaltete UND tatsächlich verfügbare Werkzeuge. */
    val toolNames: List<String>
        get() {
            if (config.maxToolCalls <= 0) return emptyList()
            return persona.tools.map { normalizeName(it) }.distinct().filter { it in registry.tools }
        }

    val systemPrompt: String
        get() = persona.systemPrompt(config.userName, registry.promptSection(toolNames, persona.toolChoices))

    val stopSequences: List<String> get() = persona.stopSequences(config.userName)

    // --- Verlauf ------------------------------------------------------------------------------------------

    fun addUser(text: String) {
        history += Message("user", text)
    }

    fun addAssistant(text: String) {
        history += Message("assistant", text)
    }

    fun addToolResult(tool: String, text: String, ok: Boolean): Message =
        Message("user", text, if (ok) TOOL_RESULT else TOOL_ERROR, tool).also { history += it }

    /** Ergebnis, das im Chat angezeigt wurde. Das Modell bekommt es erst mit der nächsten Nutzernachricht. */
    fun addToolShown(tool: String, text: String): Message =
        Message("user", text, TOOL_SHOWN, tool).also { history += it }

    /** Nimmt die letzte 'user'-Nachricht zurück (nach Fehler/Abbruch). Text nur für echte Nutzereingaben. */
    fun discardLastUser(): String {
        if (history.isNotEmpty() && history.last().role == "user") {
            val message = history.removeAt(history.size - 1)
            return if (message.isTool) "" else message.content
        }
        return ""
    }

    /** Frühere Antworten des Companions - Vergleichsbasis gegen Wiederholungen. */
    fun previousReplies(): List<String> = history.filter { it.role == "assistant" }.map { it.content }.takeLast(HISTORY_REPLIES)

    fun reset() {
        history.clear()
        dropped = 0
    }

    /**
     * Ersetzt den Verlauf durch einen geladenen Chat. Es wird NICHTS ausgeführt. Eine unbeantwortete letzte
     * Nutzernachricht wird zurückgegeben (fürs Eingabefeld).
     */
    fun load(messages: List<Message>): String {
        reset()
        history += messages
        // Ein Ergebnis ohne Antwort des Modells ist verwaist. Ein angezeigtes Ergebnis (show) ist dagegen der gewollte
        // Endzustand und geht mit der nächsten Nutzernachricht ans Modell - es bleibt (anders als in der Python-App).
        while (history.isNotEmpty() && history.last().isTool && history.last().kind != TOOL_SHOWN) history.removeAt(history.size - 1)
        if (history.isNotEmpty() && history.last().role == "user" && !history.last().isTool) {
            return history.removeAt(history.size - 1).content
        }
        return ""
    }

    // --- Anfrage ------------------------------------------------------------------------------------------

    /** Verlauf, wie er an das Modell geht: Werkzeugergebnisse verpackt, ältere gekürzt. */
    internal fun wireHistory(): List<Message> {
        val lastQuestion = history.indexOfLast { it.role == "user" && !it.isTool }
        val wire = mutableListOf<Message>()
        val shown = mutableListOf<String>() // angezeigte Ergebnisse gehen mit der nächsten Nutzernachricht mit
        history.forEachIndexed { i, original ->
            var m = original
            if (m.isTool) {
                var text = m.content
                if (i < lastQuestion && text.length > OLD_RESULT_CHARS) text = text.substring(0, OLD_RESULT_CHARS).trimEnd() + " […]"
                if (m.kind == TOOL_SHOWN) {
                    shown +="[Ergebnis von ${m.tool}, ${config.userName} schon angezeigt]\n$text\n[Ende des Ergebnisses]"
                    return@forEachIndexed
                }
                val lines = if (m.kind != TOOL_RESULT && i > lastQuestion && isRetryableError(text)) // nur in der laufenden Runde: ältere Fehler blähen den Kontext nicht auf
                     registry.promptLines(toolNames, persona.toolChoices) else emptyList()
                m = m.copy(content = resultMessage(m.tool, text, m.kind == TOOL_RESULT, config.userName, lines))
            } else if (shown.isNotEmpty()) {
                if (m.role == "user") m = m.copy(content = shown.joinToString("\n") + "\n\n" + m.content)
                shown.clear()
            }
            wire += m
        }
        return wire
    }

    fun requestMessages(): List<WireMessage> {
        val budget = config.contextTokens - config.maxReplyTokens
        val system = systemPrompt
        val (kept, droppedCount) = context.fit(system, wireHistory(), budget)
        dropped = droppedCount
        return listOf(WireMessage("system", system)) + kept.map { WireMessage(it.role, it.content) }
    }

    fun recordUsage(sent: List<WireMessage>, promptTokens: Int) = context.calibrate(sent.sumOf { it.content.length }, promptTokens)

    fun estimatedTokens(): Int = requestMessages().sumOf { context.estimate(it.content) }

    /**
     * Wie viele Tokens ein eingefügtes Dokument höchstens haben darf: Fenster minus Antwort minus System-Prompt (Persona,
     * Werkzeuge). Älterer Verlauf wird bei Bedarf ausgeblendet, das Dokument soll aber ganz hineinpassen.
     */
    fun documentBudgetTokens(): Int =
        (config.contextTokens - config.maxReplyTokens - context.estimate(systemPrompt) - DOCUMENT_MARGIN_TOKENS).coerceAtLeast(150)
}
