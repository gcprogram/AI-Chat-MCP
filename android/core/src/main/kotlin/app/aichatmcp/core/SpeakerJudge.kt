package app.aichatmcp.core

/**
 * KI-Rückfall für wörtliche Rede, die die Regeln nicht zuordnen konnten: ein Chat-Modell (z. B. LM Studio) bekommt die Rede mit etwas
 * Umgebung und antwortet mit einem einzigen Buchstaben. Englische Anweisung, weil kleine Modelle ihr besser folgen. Es wird nichts
 * gespeichert und nur der kurze Ausschnitt um die Rede gesendet.
 */
class SpeakerJudge(private val backend: LlmBackend, private val model: String) {
    suspend fun ask(before: String, quote: String, after: String): Gender? {
        val messages = listOf(WireMessage("system", SYSTEM), WireMessage("user", prompt(before, quote, after)))
        val answer = StringBuilder()
        backend.streamChat(model, messages, 0.0, 6).collect { if (it is StreamEvent.Content) answer.append(it.text) }
        return parse(answer.toString())
    }

    companion object {
        const val SYSTEM = "You read passages of fiction. Reply with exactly one character: M if the person who speaks the marked quote is a man or boy, " +
            "F if it is a woman or girl, ? if you cannot tell. No other text."

        fun prompt(before: String, quote: String, after: String): String =
            "Passage: ${before.trim()} «$quote» ${after.trim()}\n\nWho speaks the quote between « and »? Answer M, F or ?."

        /** Der erste Buchstabe der Antwort: M (männlich), F oder W (weiblich); alles andere heißt "unklar". */
        fun parse(answer: String): Gender? = when (answer.trim().firstOrNull { it.isLetter() || it == '?' }?.uppercaseChar()) {
            'M' -> Gender.MALE
            'F', 'W' -> Gender.FEMALE
            else -> null
        }
    }
}
