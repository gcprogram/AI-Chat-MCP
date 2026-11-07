package app.aichatmcp.core.studio

/**
 * Zerlegt einen Fließtext (z. B. ein Buch) in Sätze, die sich gut einsprechen lassen. Gleiche Regeln wie `split_sentences` im
 * Python-Werkzeug (`tools/voice/voicelib.py`), die Erwartungswerte der Tests stammen von dort.
 *
 * Behalten werden Sätze mit [minWords] bis [maxWords] Wörtern ohne Ziffern. Anführungszeichen am Rand fallen weg, Silbentrennung am
 * Zeilenende wird zusammengesetzt, Abkürzungen (z. B., Dr., usw.) und Ordnungszahlen ("am 12. Mai") beenden keinen Satz, Dubletten
 * fallen weg. Zu lange Sätze werden an Semikolon oder Doppelpunkt geteilt, sonst verworfen.
 *
 * Android-Regex ist ICU: Unicode-Buchstaben darum als `\p{L}`, nicht `\w`.
 */
object SentenceSplitter {
    private val ABBREVIATIONS = listOf(
        "z. B.", "z.B.", "d. h.", "d.h.", "u. a.", "u.a.", "usw.", "bzw.", "ca.", "Dr.", "Prof.", "Nr.", "Hr.", "Fr.", "Mr.", "Mrs.", "Ms.",
        "St.", "etc.", "vgl.", "evtl.", "ggf.", "inkl.", "zzgl.", "Str.", "Abb.", "Kap.", "s. o.", "s. u.", "i. d. R.", "e. g.", "i. e.",
    )

    private val HYPHEN_BREAK = Regex("(\\p{L})-\\n(\\p{L})")
    private val PARAGRAPH = Regex("\\n\\s*\\n")
    private val SPACES = Regex("\\s+")
    private val ORDINAL = Regex("(\\p{N})\\.(\\s)")
    private val SENTENCE_END = Regex("(?<=[.!?…])[\"'“”„»«)\\]]*\\s+(?=[\"'“”„»«(\\[]*[A-ZÄÖÜ0-9])")
    private val QUOTES = Regex("[„“”\"»«]")
    private val COMMA_AFTER_QUESTION = Regex("([?!])\\s*,")
    private val CLAUSE_SPLIT = Regex("(?<=[;:])\\s+")
    private val DIGIT = Regex("\\p{N}")
    private val TRAILING_PUNCT = Regex("[;:,]$")
    private val ENDS_SENTENCE = Regex("[.!?…]$")
    private val EDGE = charArrayOf(' ', '\t', '\'', '‚', '‘', '’', '–', '—', '-', '_', '*', '#')

    fun split(text: String, minWords: Int = 4, maxWords: Int = 28): List<String> {
        var t = text.replace("\r\n", "\n").replace("\r", "\n")
        t = HYPHEN_BREAK.replace(t, "$1$2") // Silbentrennung am Zeilenende
        val paragraphs = PARAGRAPH.split(t).map { SPACES.replace(it, " ").trim() }
        val keys = ABBREVIATIONS.mapIndexed { i, abbr -> "\u0001$i\u0001" to abbr }
        val sentences = mutableListOf<String>()
        for (paragraphText in paragraphs) {
            if (paragraphText.isEmpty()) continue
            var paragraph = paragraphText
            for ((key, abbr) in keys) paragraph = paragraph.replace(abbr, key)
            paragraph = ORDINAL.replace(paragraph) { "${it.groupValues[1]}\u0002${it.groupValues[2]}" } // "am 12. Mai": kein Satzende
            for (partText in SENTENCE_END.split(paragraph)) {
                var part = partText
                for ((key, abbr) in keys) part = part.replace(key, abbr)
                sentences += part.replace("\u0002", ".").trim()
            }
        }
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        for (raw in sentences) {
            var sentence = QUOTES.replace(raw, "")
            sentence = COMMA_AFTER_QUESTION.replace(sentence, "$1") // aus „…?“, sagte sie wird: …? sagte sie
            sentence = SPACES.replace(sentence, " ").trim(*EDGE)
            val pieces = if (wordCount(sentence) > maxWords) CLAUSE_SPLIT.split(sentence).map { it.trim() } else listOf(sentence)
            for (pieceText in pieces) {
                var piece = pieceText
                val words = wordCount(piece)
                if (words < minWords || words > maxWords || DIGIT.containsMatchIn(piece) || piece in seen) continue
                piece = TRAILING_PUNCT.replace(piece, "")
                if (!ENDS_SENTENCE.containsMatchIn(piece)) piece += "."
                seen += piece
                out += piece
            }
        }
        return out
    }

    private fun wordCount(s: String): Int = s.split(' ', '\t', '\n').count { it.isNotEmpty() }
}
