package app.aichatmcp.core

/**
 * Text einer Meldung über eine KI-Antwort (Pflicht der Google-Play-Richtlinie für Apps mit KI-erzeugten Inhalten: Nutzer müssen Antworten in der App
 * melden können). Die Meldung enthält nur die gemeldete Antwort, das Modell und die App-Version, nicht den ganzen Chat; der Nutzer sieht und ändert den
 * Text, bevor er ihn abschickt.
 */
object ReportText {
    const val MAX_ANSWER_CHARS = 2000

    fun subject(german: Boolean) = if (german) "Meldung: Antwort der KI" else "Report: AI reply"

    fun body(answer: String, model: String, appVersion: String, german: Boolean): String {
        val shown = answer.trim().let { if (it.length > MAX_ANSWER_CHARS) it.take(MAX_ANSWER_CHARS) + " […]" else it }
        return if (german) {
            "Was stimmt nicht? (bitte kurz beschreiben):\n\n\nGemeldete Antwort:\n$shown\n\nModell: ${model.ifBlank { "unbekannt" }}\nApp-Version: $appVersion\n"
        } else {
            "What is wrong? (please describe briefly):\n\n\nReported reply:\n$shown\n\nModel: ${model.ifBlank { "unknown" }}\nApp version: $appVersion\n"
        }
    }

    /** Meldung ohne bestimmte Antwort (Einstellungen: "Problem melden"). */
    fun general(model: String, appVersion: String, german: Boolean): String = body("", model, appVersion, german).replace(
        if (german) "Gemeldete Antwort:\n\n\n" else "Reported reply:\n\n\n", "",
    )
}
