package app.aichatmcp.core

/**
 * Geschlecht einer Figur oder eines Companions bestimmen, soweit es sich ohne Modell sagen lässt: Anrede (Herr/Frau, Mr/Mrs/Miss), Vorname aus den
 * Namenslisten ([FirstNames]) und Pronomen hinter dem Namen im Text (er/sie, he/she, …). Es muss nicht immer stimmen; ist es unklar, kommt null und
 * die Anwendung fragt bei Bedarf das Modell ([GenderGuess]). Gebraucht für die Stimmenwahl beim Vorlesen, im Spiel und für Companions.
 */
object Genders {
    private val MALE_TITLES = setOf("mr", "mister", "herr", "sir", "lord", "monsieur", "señor", "senor", "signor", "don", "mr.", "herrn")
    private val FEMALE_TITLES = setOf("mrs", "ms", "miss", "frau", "fräulein", "fraeulein", "lady", "madame", "madam", "mme", "señora", "senora", "signora", "mrs.", "ms.")

    private const val TITLE_WORDS = "Mrs|Mr|Ms|Miss|Mister|Herr|Frau|Fräulein|Lady|Lord|Sir|Madame|Madam|Monsieur"

    /** Das Geschlecht, das eine Anrede ("Mrs", "Herr", …) verrät; null = keine Anrede. */
    fun ofTitle(word: String): Gender? {
        val w = word.trim().lowercase().trimEnd('.')
        return when {
            w in MALE_TITLES -> Gender.MALE
            w in FEMALE_TITLES -> Gender.FEMALE
            else -> null
        }
    }

    /** Aus dem Namen allein: erst eine Anrede ("Mrs Andrews"), dann der erste Vorname aus den Listen; null = unklar. */
    fun ofName(name: String): Gender? {
        val words = name.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        ofTitle(words.first())?.let { return it }
        val clean = words.map { it.trim('.', ',', ';', ':', '"', '„', '“', '”', '(', ')') }
        for (w in clean) FirstNames.genderOf(w.replaceFirstChar { it.uppercase() })?.let { return it }
        return null
    }

    private val FEMALE_PRONOUNS = setOf("she", "her", "hers", "herself", "sie", "ihr", "ihre", "ihrem", "ihren", "ihrer", "ihres", "elle", "ella")
    private val MALE_PRONOUNS = setOf("he", "him", "his", "himself", "er", "ihm", "ihn", "sein", "seine", "seinem", "seinen", "seiner", "seines", "il", "él")
    private val WORD = Regex("""\p{L}+""")

    /**
     * Wie oft Pronomen hinter dem Namen im Text weiblich oder männlich sind: Hinter jeder Nennung zählt das erste Pronomen im Rest des Absatzes
     * (höchstens etwa 140 Zeichen). Gibt (weiblich, männlich) zurück.
     */
    fun pronounVotes(name: String, text: String, maxMentions: Int = 80): Pair<Int, Int> {
        val first = name.trim().split(Regex("""\s+""")).lastOrNull { it.isNotEmpty() && it.first().isUpperCase() } ?: return 0 to 0
        val mention = Regex("""(?<!\p{L})${Regex.escape(first)}(?!\p{L})""")
        var female = 0
        var male = 0
        var seen = 0
        for (m in mention.findAll(text)) {
            if (++seen > maxMentions) break
            val rest = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 1 + 140)).substringBefore('\n')
            val pronoun = WORD.findAll(rest).map { it.value.lowercase() }.firstOrNull { it in FEMALE_PRONOUNS || it in MALE_PRONOUNS } ?: continue
            if (pronoun in FEMALE_PRONOUNS) female++ else male++
        }
        return female to male
    }

    /**
     * Geschlecht der Figur [name] im [text]: Anrede vor dem Namen zählt am meisten, dann der Vorname aus den Listen, dann die Pronomen hinter dem Namen
     * (wenn sie deutlich überwiegen). null = unklar.
     */
    fun ofCharacter(name: String, text: String): Gender? {
        val last = name.trim().split(Regex("""\s+""")).lastOrNull { it.isNotEmpty() } ?: return null
        var score = 0.0 // positiv = weiblich
        ofName(name)?.let { score += if (it == Gender.FEMALE) 3.0 else -3.0 }
        val titled = Regex("""(?<!\p{L})($TITLE_WORDS)\.?\s+${Regex.escape(last)}(?!\p{L})""").findAll(text).take(30).toList()
        for (m in titled) ofTitle(m.groupValues[1])?.let { score += if (it == Gender.FEMALE) 5.0 else -5.0 }
        val (f, m) = pronounVotes(name, text)
        if (f + m >= 3) score += (f - m).toDouble() * 1.0 / (f + m) * 4.0
        else score += (f - m) * 0.5
        return when {
            score >= 2.0 -> Gender.FEMALE
            score <= -2.0 -> Gender.MALE
            else -> null
        }
    }

    private val FEMALE_WORDS = setOf(
        "frau", "mädchen", "mutter", "mama", "schwester", "tochter", "tante", "oma", "großmutter", "königin", "prinzessin", "hexe", "dame", "freundin", "sie", "ihr", "ihre",
        "she", "her", "woman", "girl", "mother", "sister", "daughter", "aunt", "grandma", "queen", "princess", "witch", "lady", "maid", "girlfriend", "female", "weiblich", "androidin",
    )
    private val MALE_WORDS = setOf(
        "mann", "junge", "vater", "papa", "bruder", "sohn", "onkel", "opa", "großvater", "könig", "prinz", "herr", "kapitän", "professor", "er", "sein", "seine",
        "he", "his", "him", "man", "boy", "father", "brother", "son", "uncle", "grandpa", "king", "prince", "captain", "male", "männlich", "gentleman",
    )
    private val FEMALE_ENDING = Regex("""\p{L}{3,}(?:erin|ntin|ärin|örin|ürin|ssin|trice)\b""")

    /**
     * Geschlecht eines Companions aus Name, Beschreibung und Regeln: Name (Liste), weibliche Berufsbezeichnungen auf "-in", Wörter wie Frau/Mann
     * und Pronomen. null = unklar (Standardstimme des Erzählers).
     */
    fun ofPersona(name: String, description: String, rules: List<String> = emptyList()): Gender? {
        var score = 0.0
        ofName(name)?.let { score += if (it == Gender.FEMALE) 3.0 else -3.0 }
        val text = (description + " " + rules.joinToString(" ")).take(1200)
        score += FEMALE_ENDING.findAll(text).take(3).count() * 2.0
        for (w in WORD.findAll(text.take(500))) {
            val lower = w.value.lowercase()
            if (lower in FEMALE_WORDS) score += 1.0 else if (lower in MALE_WORDS) score -= 1.0
        }
        return when {
            score >= 2.0 -> Gender.FEMALE
            score <= -2.0 -> Gender.MALE
            else -> null
        }
    }

    /** Gespeicherte Angabe eines Companions ("female"/"male"/"" = automatisch) als [Gender]. */
    fun parse(value: String): Gender? = when (value.trim().lowercase()) {
        "female", "f", "weiblich", "w" -> Gender.FEMALE
        "male", "m", "männlich", "maennlich" -> Gender.MALE
        else -> null
    }

    fun key(gender: Gender?): String = when (gender) { Gender.FEMALE -> "female"; Gender.MALE -> "male"; null -> "" }
}

/**
 * KI-Rückfall fürs Geschlecht einer Figur: ein Chat-Modell bekommt den Namen und etwas Text und antwortet mit einem Buchstaben (englische Anweisung,
 * wie bei [SpeakerJudge]). Nur wenn die Regeln in [Genders] nichts ergeben.
 */
object GenderGuess {
    const val SYSTEM = "You read texts about a person or character. Reply with exactly one character: M if the named person is a man or boy, " +
        "F if a woman or girl, ? if you cannot tell. No other text."

    fun prompt(name: String, context: String): List<WireMessage> = listOf(
        WireMessage("system", SYSTEM),
        WireMessage("user", "Text: ${context.trim().take(1500)}\n\nIs $name a man or a woman? Answer M, F or ?."),
    )

    fun parse(answer: String): Gender? = SpeakerJudge.parse(answer)

    /** Eine Textprobe um die Nennungen des Namens (für den Prompt): die ersten Absätze, in denen er vorkommt. */
    fun sample(name: String, text: String, chars: Int = 1500): String {
        val last = name.trim().split(Regex("""\s+""")).lastOrNull { it.isNotEmpty() } ?: return text.take(chars)
        val out = StringBuilder()
        for (p in text.split(Regex("""\n\s*\n"""))) {
            if (Regex("""(?<!\p{L})${Regex.escape(last)}(?!\p{L})""").containsMatchIn(p)) {
                if (out.length + p.length > chars) { if (out.isEmpty()) out.append(p.take(chars)); break }
                out.append(p).append("\n\n")
            }
        }
        return out.toString().ifBlank { text.take(chars) }
    }
}
