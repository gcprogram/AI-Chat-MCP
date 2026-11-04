package app.aichatmcp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Was die Oberfläche von einer Runde mitbekommt (in Python die Queue zwischen Streaming-Thread und GUI). */
sealed interface ChatEvent {
    data class Status(val text: String) : ChatEvent

    /** Live-Text der laufenden Antwort (Rohtext), bis die bereinigte Fassung als [Added] kommt. */
    data class Live(val reasoning: String, val content: String) : ChatEvent

    /** Eine Nachricht ist in den Verlauf gekommen (Nutzer, Companion, Werkzeugergebnis). */
    data class Added(val message: Message) : ChatEvent

    data class Notice(val text: String, val kind: NoticeKind) : ChatEvent

    /** Frage ohne Antwort: zurück ins Eingabefeld. */
    data class RestoreInput(val text: String) : ChatEvent

    data object Finished : ChatEvent
}

enum class NoticeKind { INFO, ERROR, TOOL }

/**
 * Eine Nutzernachricht samt Antwort und Werkzeugrunden (Port der Abläufe aus gui.py): streamen, Marker erkennen,
 * bestätigen, ausführen und je nach Ergebnis-Modus (model | show | discard) weitermachen.
 */
class ChatEngine(
    val session: ChatSession,
    private val clientFor: (Config) -> ChatClient = { ChatClient(it.baseUrl, it.apiKey) },
    /** Rückfrage vor einem Werkzeugaufruf; true = ausführen. */
    private val confirm: suspend (ToolCall, ToolSpec?) -> Boolean = { _, _ -> true },
) {
    @Volatile
    private var round = CancelToken() // pro Runde neu: das stille Stoppen durch den Wächter gilt nur für diese Runde

    @Volatile
    private var userCancelled = false

    /** Stopp-Knopf: bricht die laufende Antwort ab (die Teilantwort bleibt im Verlauf). */
    fun cancel() {
        userCancelled = true
        round.cancel()
    }

    fun send(text: String): Flow<ChatEvent> = flow {
        userCancelled = false
        session.addUser(text)
        emit(ChatEvent.Added(session.history.last()))
        var rounds = 0
        while (true) {
            val config = session.config
            val persona = session.persona
            val toolNames = session.toolNames
            val messages = session.requestMessages()
            val cancelToken = CancelToken().also { round = it }
            if (userCancelled) cancelToken.cancel()
            emit(ChatEvent.Status("Warte auf Modell…"))
            val guard = StreamGuard(config.userName, persona.name, toolNames)
            val reply = StringBuilder()
            val reasoning = StringBuilder()
            var usage: Int? = null
            try {
                clientFor(config).streamChat(
                    config.model, messages, config.temperature, config.maxReplyTokens, session.stopSequences,
                    cancelToken, config.samplingParams(),
                ).collect { event ->
                    if (cancelToken.stopped) return@collect // schon abgebrochen: was noch im Puffer liegt, zählt nicht
                    when (event) {
                        is StreamEvent.Content -> {
                            reply.append(event.text)
                            emit(ChatEvent.Live(reasoning.toString(), reply.toString()))
                            if (guard.feed(event.text) != null) cancelToken.stop() // Schleife/Markup/fertiger Aufruf
                        }
                        is StreamEvent.Reasoning -> {
                            reasoning.append(event.text)
                            emit(ChatEvent.Live(reasoning.toString(), reply.toString()))
                        }
                        is StreamEvent.Usage -> usage = event.promptTokens
                    }
                }
            } catch (e: LlmException) {
                restoreInput()
                emit(ChatEvent.Notice("[Fehler] ${e.message}", NoticeKind.ERROR))
                emit(ChatEvent.Status("Fehler"))
                emit(ChatEvent.Finished)
                return@flow
            }
            val result = finalizeReply(reply.toString(), config.userName, persona.name, session.previousReplies(), toolNames)
            val cancelled = userCancelled
            val answer = result.text.trim()
            if (answer.isEmpty()) {
                restoreInput()
                emit(ChatEvent.Notice("[Keine Antwort erhalten]", NoticeKind.INFO))
                emit(ChatEvent.Status(if (cancelled) "Abgebrochen" else "Leere Antwort"))
                emit(ChatEvent.Finished)
                return@flow
            }
            session.addAssistant(answer) // nur der bereinigte Antworttext, nie das Reasoning
            emit(ChatEvent.Added(session.history.last()))
            usage?.let { session.recordUsage(messages, it) }
            val note = if (result.notes.isNotEmpty()) " (${result.notes.joinToString("; ")})" else ""
            val call = result.call
            if (call == null || cancelled) {
                if (call == null) warnDisabledTool(answer)?.let { emit(ChatEvent.Notice(it, NoticeKind.INFO)) }
                emit(ChatEvent.Status((if (cancelled) "Abgebrochen (Teilantwort gespeichert)" else "Bereit") + note))
                emit(ChatEvent.Finished)
                return@flow
            }

            rounds++
            if (rounds > config.maxToolCalls) {
                emit(ChatEvent.Notice("[Limit für Werkzeugaufrufe erreicht]", NoticeKind.INFO))
                emit(ChatEvent.Status("Bereit (Werkzeug-Limit erreicht)$note"))
                emit(ChatEvent.Finished)
                return@flow
            }
            val alias = normalizeName(call.name)
            val spec = session.registry.tools[alias]
            var ask = spec?.confirm ?: config.confirmTools
            if (alias in persona.approvedTools.map { normalizeName(it) }) ask = false // beim Companion vorab genehmigt
            val outcome = if (alias in toolNames && ask && !confirm(call, spec)) {
                ToolOutcome(call.name, false, "Der Nutzer hat die Ausführung abgelehnt.")
            } else {
                emit(ChatEvent.Status("Führe Werkzeug '${call.name}' aus…$note"))
                withContext(Dispatchers.IO) {
                    try {
                        session.registry.run(call, toolNames, config.toolResultChars, config.toolTimeout.toDouble())
                    } catch (e: Exception) { // ein Werkzeugfehler darf die Oberfläche nie blockieren
                        ToolOutcome(call.name, false, "Interner Fehler: ${e.message}")
                    }
                }
            }
            if (userCancelled) {
                emit(ChatEvent.Status("Abgebrochen"))
                emit(ChatEvent.Finished)
                return@flow
            }
            if (outcome.ok && outcome.mode == RESULT_SHOW) { // Klartext in den Chat, keine weitere Modellrunde
                val shown = if (outcome.remember) session.addToolShown(outcome.tool, outcome.text)
                else Message("user", outcome.text, TOOL_SHOWN, outcome.tool)
                emit(ChatEvent.Added(shown))
                emit(ChatEvent.Status("Bereit"))
                emit(ChatEvent.Finished)
                return@flow
            }
            if (outcome.ok && outcome.mode == RESULT_DISCARD) { // Ergebnis verwerfen, nur Statuszeile
                if (outcome.remember) session.addToolShown(outcome.tool, outcome.status)
                emit(ChatEvent.Notice("✔ ${outcome.status}", NoticeKind.TOOL))
                emit(ChatEvent.Status("Bereit"))
                emit(ChatEvent.Finished)
                return@flow
            }
            emit(ChatEvent.Added(session.addToolResult(outcome.tool, outcome.text, outcome.ok))) // Modell antwortet darauf
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatEvent>.restoreInput() {
        val text = session.discardLastUser()
        if (text.isNotEmpty()) emit(ChatEvent.RestoreInput(text))
    }

    /** Marker für ein vorhandenes, aber nicht freigeschaltetes Werkzeug: sichtbar melden statt still ignorieren. */
    private fun warnDisabledTool(text: String): String? {
        val name = findCall(text)?.let { normalizeName(it.name) } ?: return null
        if (name in session.registry.tools && name !in session.toolNames) {
            return "[Werkzeug '$name' ist für ${session.persona.name} nicht freigeschaltet – " +
                "unter Companions ankreuzen und einen neuen Chat beginnen]"
        }
        return null
    }
}
