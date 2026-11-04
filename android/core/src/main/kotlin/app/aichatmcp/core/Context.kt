package app.aichatmcp.core

const val CHAT = "chat"
const val TOOL_RESULT = "tool_result"
const val TOOL_ERROR = "tool_error"

/** Ergebnis, das direkt im Chat angezeigt wurde (das Modell antwortet nicht darauf). */
const val TOOL_SHOWN = "tool_shown"

data class Message(
    val role: String, // "user" | "assistant"
    val content: String,
    /** Werkzeugergebnisse gehen als 'user'-Nachricht zum Modell, sind aber keine Nutzereingabe. */
    val kind: String = CHAT,
    /** Name des Werkzeugs bei kind != CHAT. */
    val tool: String = "",
) {
    val isTool: Boolean get() = kind == TOOL_RESULT || kind == TOOL_ERROR || kind == TOOL_SHOWN
}

/**
 * Kontext-Verwaltung für kleine lokale Modelle (z. B. 8192 Token): System-Prompt immer behalten, älteste Nachrichten
 * (in ganzen Runden) weglassen, bis die Anfrage samt Antwort-Reserve ins Fenster passt. Die Token-Schätzung
 * kalibriert sich an den echten Werten, die der Server zurückmeldet.
 */
class ContextManager {
    var charsPerToken = 3.0 // konservativ für Deutsch/Englisch

    fun estimate(text: String): Int = (text.length / charsPerToken).toInt() + 4 // +4: Rollen-/Template-Overhead

    /** Gemessenes Zeichen/Token-Verhältnis des Servers einrechnen (geglättet). */
    fun calibrate(chars: Int, promptTokens: Int) {
        if (chars > 0 && promptTokens > 0) {
            val observed = (chars.toDouble() / promptTokens).coerceIn(1.5, 6.0)
            charsPerToken = 0.5 * charsPerToken + 0.5 * observed
        }
    }

    /** Neueste Nachrichten, die in das Budget passen (mind. die letzte). Liefert (Nachrichten, Anzahl entfernt). */
    fun fit(system: String, history: List<Message>, budgetTokens: Int): Pair<List<Message>, Int> {
        var remaining = budgetTokens - estimate(system)
        val kept = ArrayDeque<Message>()
        for (message in history.asReversed()) {
            val cost = estimate(message.content)
            if (kept.isNotEmpty() && cost > remaining) break
            kept.addFirst(message)
            remaining -= cost
        }
        // Verlauf muss mit einer echten Nutzernachricht beginnen (kein Assistent, kein verwaistes Werkzeugergebnis)
        while (kept.size > 1 && (kept.first().role != "user" || kept.first().isTool)) kept.removeFirst()
        return kept.toList() to history.size - kept.size
    }
}
