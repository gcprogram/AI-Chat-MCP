package app.aichatmcp.core

/**
 * Hilfen für Spracheingabe und -ausgabe (Port-unabhängig, damit auf der JVM testbar).
 */
object Speech {
    private val NAMES = mapOf(
        "de" to "de-DE", "deutsch" to "de-DE", "german" to "de-DE", "deutsche" to "de-DE",
        "en" to "en-US", "english" to "en-US", "englisch" to "en-US",
        "fr" to "fr-FR", "français" to "fr-FR", "francais" to "fr-FR", "french" to "fr-FR", "französisch" to "fr-FR",
        "es" to "es-ES", "español" to "es-ES", "espanol" to "es-ES", "spanish" to "es-ES", "spanisch" to "es-ES",
        "it" to "it-IT", "italiano" to "it-IT", "italian" to "it-IT", "italienisch" to "it-IT",
        "ja" to "ja-JP", "japanese" to "ja-JP", "japanisch" to "ja-JP", "日本語" to "ja-JP",
        "english (uk)" to "en-GB", "englisch (uk)" to "en-GB", "british english" to "en-GB",
    )

    /** Auswahl für die Antwortsprache eines Companions (jeder Eintrag hat einen Sprach-Tag). */
    val RESPONSE_LANGUAGES = listOf("Deutsch", "English", "English (UK)", "Français", "Español", "Italiano", "Japanese")

    /**
     * Sprach-Tag (BCP 47) für die Antwortsprache eines Companions ("Deutsch" -> "de-DE", "en_GB" -> "en-GB").
     * null = unbekannt: dann gilt die Sprache des Geräts.
     */
    fun languageTag(language: String): String? {
        val name = language.trim().lowercase()
        if (name.isEmpty()) return null
        NAMES[name]?.let { return it }
        val tag = Regex("^([a-z]{2,3})[-_]([a-z]{2}|\\d{3})$").matchEntire(name)
        if (tag != null) return tag.groupValues[1] + "-" + tag.groupValues[2].uppercase()
        return null
    }

    /**
     * Anzeigename einer Stimme. Android nennt weder Geschlecht noch Vornamen (nur Kürzel wie "de-de-x-nfh-local"),
     * deshalb wird nummeriert; angehört wird nach Gehör. "online" = die Stimme braucht eine Internetverbindung.
     */
    fun voiceLabel(number: Int, tag: String, network: Boolean): String =
        "Stimme $number ($tag, ${if (network) "online" else "offline"})"

    /** Eine gespeicherte Stimme gilt nur für ihre Sprache (Französisch mit deutscher Stimme zu lesen klänge falsch). */
    fun voiceFitsLanguage(voiceLanguage: String, wantedTag: String?): Boolean =
        wantedTag != null && voiceLanguage.equals(wantedTag.substringBefore('-'), ignoreCase = true)

    /** Der Text, der vorgelesen wird: ohne Werkzeugaufrufe, Zeilenumbrüche als Pausen. */
    fun spokenText(reply: String): String =
        splitSegments(reply).filter { it.first == "text" }.joinToString(" ") { it.second }
            .replace(Regex("[ \\t]*\\n+[ \\t]*"), ". ").replace(Regex("\\.\\s*\\.+"), ".")
            .replace(Regex("\\s+"), " ").trim()

    /**
     * Ein für die Sprachausgabe aufbereiteter Text. [original] rechnet eine Stelle im aufbereiteten Text auf die Stelle im Ausgangstext zurück
     * (die Systemstimme meldet ihren Fortschritt als Zeichenstelle; der Vorleser setzt danach mitten im Abschnitt fort).
     */
    class Spoken(val text: String, private val starts: IntArray?) {
        fun original(index: Int): Int = if (starts == null) index else starts[index.coerceIn(0, starts.size - 1)]
    }

    private val TITLE = Regex("""(?<!\p{L})(Mrs|Mr|Ms|MRS|MR|MS)\.?(?=\s+\p{Lu})""")
    private val TITLE_SPOKEN = mapOf("mrs" to "Missis", "mr" to "Mister", "ms" to "Miss")

    /**
     * Setzt Anreden, die eine nicht englische Stimme buchstabiert ("Mrs" wird zu M-R-S), so, wie sie gesprochen werden: Mrs -> Missis, Mr -> Mister,
     * Ms -> Miss. Englische Stimmen kennen sie. Nur vor einem Namen (Großbuchstabe), damit Kürzel wie "MR" in anderem Zusammenhang bleiben.
     */
    fun forSpeech(text: String, languageTag: String?): Spoken {
        if (languageTag != null && languageTag.startsWith("en", ignoreCase = true)) return Spoken(text, null)
        val matches = TITLE.findAll(text).toList()
        if (matches.isEmpty()) return Spoken(text, null)
        val out = StringBuilder()
        val starts = ArrayList<Int>(text.length + 8)
        var pos = 0
        for (m in matches) {
            for (i in pos until m.range.first) { out.append(text[i]); starts += i }
            val spoken = TITLE_SPOKEN.getValue(m.groupValues[1].lowercase())
            for (c in spoken) { out.append(c); starts += m.range.first }
            pos = m.range.last + 1
        }
        for (i in pos until text.length) { out.append(text[i]); starts += i }
        starts += text.length
        return Spoken(out.toString(), starts.toIntArray())
    }

    /**
     * Teilt langen Text in Stücke (an Satzgrenzen), weil Sprachausgaben nur begrenzt viele Zeichen pro Aufruf
     * annehmen (Android: höchstens 4000).
     */
    fun chunks(text: String, max: Int = 3500): List<String> {
        if (text.length <= max) return if (text.isBlank()) emptyList() else listOf(text)
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (sentence in Regex("(?<=[.!?…])\\s+").split(text)) {
            val parts = if (sentence.length > max) sentence.chunked(max) else listOf(sentence)
            for (part in parts) {
                if (current.isNotEmpty() && current.length + part.length + 1 > max) {
                    out += current.toString()
                    current = StringBuilder()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(part)
            }
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }
}
