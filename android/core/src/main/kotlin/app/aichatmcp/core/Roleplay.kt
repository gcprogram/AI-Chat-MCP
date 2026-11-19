package app.aichatmcp.core

/** Eine Szene des Buches: ein Stück Text (etwa 3000 Zeichen) mit Überschrift. */
data class Scene(val title: String, val text: String)

/**
 * Rollenspiel mit Buchvorlage: Das Buch wird in Szenen geteilt; der Spielleiter-Prompt enthält nur die aktuelle Szene (und einen Blick auf die nächste),
 * damit er in den 4096 Tokens eines Handy-Modells Platz hat. Die Vorlage selbst ist der Handlungsplan: Es braucht keine Vorverarbeitung durch das Modell
 * (ein ganzes Buch zu lesen dauert lokal Stunden).
 */
object BookScenes {
    private val HEADING = Regex("""(?im)^[ \t]*((?:kapitel|chapter|teil|part|akt|act)[ \t]+(?:\d{1,3}|[ivxlc]{1,7}|[a-zäöü]{3,12})\b.*)$""")

    /** Teilt [text] in Szenen von etwa [target] Zeichen (höchstens [max]); Kapitelüberschriften sind Schnitte. */
    fun split(text: String, target: Int = 3000, max: Int = 4200): List<Scene> {
        val clean = text.replace("\r\n", "\n").replace('\r', '\n')
        val marks = HEADING.findAll(clean).filter { it.value.trim().length <= 70 }.toList()
        val chapters = mutableListOf<Pair<String, String>>() // Überschrift, Text
        if (marks.isEmpty()) chapters += "" to clean
        else {
            val first = clean.substring(0, marks.first().range.first).trim()
            if (first.length >= 400) chapters += "" to first // Vorspann vor dem ersten Kapitel
            marks.forEachIndexed { i, m ->
                val end = if (i + 1 < marks.size) marks[i + 1].range.first else clean.length
                chapters += m.value.trim() to clean.substring(m.range.last + 1, end).trim()
            }
        }
        val scenes = mutableListOf<Scene>()
        var anonymous = 0
        for ((title, body) in chapters) {
            if (body.isBlank()) continue
            val parts = chunk(body, target, max)
            parts.forEachIndexed { i, part ->
                val name = when {
                    title.isEmpty() -> "Abschnitt ${++anonymous}"
                    parts.size == 1 -> title
                    else -> "$title (${i + 1}/${parts.size})"
                }
                scenes += Scene(name, part)
            }
        }
        // zu kurze Reste an die vorige Szene hängen
        val merged = mutableListOf<Scene>()
        for (s in scenes) {
            if (s.text.length < 400 && merged.isNotEmpty()) merged[merged.lastIndex] = merged.last().copy(text = merged.last().text + "\n\n" + s.text)
            else merged += s
        }
        return merged
    }

    private fun chunk(body: String, target: Int, max: Int): List<String> {
        val paragraphs = body.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { p -> if (p.length <= max) listOf(p) else splitSentences(p, target) }
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (p in paragraphs) {
            if (cur.isNotEmpty() && cur.length + p.length > max) {
                out += cur.toString().trim()
                cur.setLength(0)
            }
            cur.append(p).append("\n\n")
            if (cur.length >= target) {
                out += cur.toString().trim()
                cur.setLength(0)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    private fun splitSentences(p: String, target: Int): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (sentence in p.split(Regex("(?<=[.!?…])\\s+"))) {
            if (cur.isNotEmpty() && cur.length + sentence.length > target) {
                out += cur.toString().trim()
                cur.setLength(0)
            }
            cur.append(sentence).append(' ')
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }
}

/** Figuren eines Buches finden: Kandidaten aus der Häufigkeit großgeschriebener Wörter, danach (optional) vom Modell auf Personennamen gefiltert. */
object BookCast {
    private val STOP = setOf(
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "und", "oder", "aber", "doch", "dann", "als", "wenn", "weil", "dass",
        "ich", "er", "sie", "es", "wir", "ihr", "mit", "von", "zu", "im", "am", "auf", "aus", "bei", "nach", "vor", "für", "um", "durch", "über", "unter", "nicht",
        "kein", "keine", "mein", "dein", "sein", "wie", "was", "wer", "wo", "wohin", "hier", "dort", "jetzt", "nun", "noch", "auch", "nur", "schon", "immer", "nie",
        "the", "and", "but", "then", "when", "that", "with", "from", "this", "his", "her", "they", "you", "what", "who", "where", "not", "was", "were", "are",
        "kapitel", "chapter", "herr", "frau", "doktor", "mister", "missis",
    )

    /** Die häufigsten großgeschriebenen Wörter mitten im Satz (höchstens [max]), häufigste zuerst. Im Deutschen sind auch viele Hauptwörter dabei. */
    fun candidates(text: String, max: Int = 24): List<String> {
        val word = Regex("""\p{Lu}[\p{L}'’-]{2,}""")
        val counts = HashMap<String, Int>()
        val lower = HashSet<String>()
        Regex("""\p{Ll}[\p{L}'’-]{2,}""").findAll(text).forEach { lower += it.value.lowercase() }
        for (m in word.findAll(text)) {
            val w = m.value
            if (w.lowercase() in STOP || w.lowercase() in lower) continue // kommt auch kleingeschrieben vor: kein Name
            var i = m.range.first - 1
            while (i >= 0 && text[i] == ' ') i--
            val before = if (i >= 0) text[i] else '\n'
            if (before in ".!?\n\"„“»«:…" ) continue // Satzanfang: sagt nichts über Namen
            counts[w] = (counts[w] ?: 0) + 1
        }
        return counts.filter { it.value >= 3 }.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }

    fun filterPrompt(candidates: List<String>, german: Boolean): List<WireMessage> = listOf(
        WireMessage(
            "user",
            if (german) "Aus dieser Wortliste eines Buches: Welche sind Namen von Personen? Antworte nur mit den Namen, durch Komma getrennt, wichtigste zuerst.\n\n${candidates.joinToString(", ")}"
            else "From this word list of a book: which are names of people? Answer only with the names, comma separated, most important first.\n\n${candidates.joinToString(", ")}",
        ),
    )

    /** Die Namen aus der Antwort des Modells, nur solche, die in der Kandidatenliste stehen (nichts Erfundenes). */
    fun parseNames(answer: String, candidates: List<String>): List<String> {
        val allowed = candidates.associateBy { it.lowercase() }
        return answer.split(Regex("[,\n;]")).map { it.trim().trim('.', '*', '-', ' ') }.mapNotNull { allowed[it.lowercase()] }.distinct()
    }

    /** Erzählt das Buch in der Ich-Form? ("ich" kommt deutlich häufiger vor als "er"/"sie"; Reden in Anführungszeichen zählen nicht.) */
    fun firstPerson(text: String): Boolean {
        val sample = text.take(30000).replace(Regex("""["„“»][^"“”«]{0,300}["“”«]"""), " ")
        val ich = Regex("""(?i)\b(ich|mir|mich|meine[nrms]?|i|my|me)\b""").findAll(sample).count()
        val third = Regex("""(?i)\b(er|sie|ihm|ihn|ihr|seine[nrms]?|he|she|him|his|her)\b""").findAll(sample).count()
        return ich > 20 && ich >= third * 1.5
    }
}

/** Die Einstellungen eines laufenden Buch-Rollenspiels. [player] leer = der Ich-Erzähler. */
data class RoleplaySetup(val title: String, val scenes: List<Scene>, val player: String, val firstPerson: Boolean, val german: Boolean)

object RoleplayPrompt {
    /** Der Spielleiter-Prompt für die Szene [index]; [closing]: die Szene soll jetzt zum Ende kommen. */
    fun system(setup: RoleplaySetup, index: Int, closing: Boolean): String {
        val scene = setup.scenes[index]
        val next = setup.scenes.getOrNull(index + 1)
        val g = setup.german
        val player = if (setup.player.isBlank()) (if (g) "den Ich-Erzähler des Buches (die Figur, aus deren Sicht erzählt wird)" else "the first-person narrator of the book") else (if (g) "die Figur ${setup.player}" else "the character ${setup.player}")
        return if (g) buildString {
            append("Du bist der Spielleiter (Erzähler) eines Rollenspiels nach dem Buch „${setup.title}“. Du spielst den Erzähler und ALLE Figuren außer der Spielerfigur. Der Spieler ({user}) spielt $player.\n")
            append("Regeln:\n")
            append("- Halte dich an die Handlung der aktuellen Szene (unten). Übernimm Namen, Orte und Ereignisse aus der Vorlage, erfinde nichts, was ihr widerspricht, und ahme ihren Stil nach.\n")
            append("- Beschreibe, was die Spielerfigur sieht und hört, lasse andere Figuren sprechen und handeln und gib dem Spieler Raum für seine Antwort. Antworte in 2 bis 5 Sätzen, ohne Aufzählungen und ohne Markdown. Schreibe nie Worte oder Handlungen der Spielerfigur.\n")
            append("- Weicht der Spieler von der Handlung ab, reagiere kurz und stimmig in der Welt und lenke innerhalb von ein bis zwei Antworten zurück zu den Ereignissen der Szene (durch eine Figur, ein Ereignis, einen Hinweis).\n")
            append("- Sprich den Spieler mit „du“ an")
            if (setup.firstPerson && setup.player.isBlank()) append(" (auch wenn das Buch in der Ich-Form erzählt: der Spieler ist das Ich, du erzählst in der zweiten Person)")
            append(".\n- Verrate nichts aus späteren Szenen.\n\n")
            append("AKTUELLE SZENE (${scene.title}):\n${scene.text.trim()}\n")
            if (next != null) append("\nNÄCHSTE SZENE (nur zur Orientierung, noch nicht spielen): ${next.text.trim().take(300)} …\n")
            if (closing) append("\nDie Szene soll jetzt zu ihrem Ende kommen: führe die Ereignisse der Vorlage in dieser Antwort zum Abschluss.\n")
        } else buildString {
            append("You are the game master (narrator) of a role play based on the book \"${setup.title}\". You play the narrator and ALL characters except the player's character. The player ({user}) plays $player.\n")
            append("Rules:\n")
            append("- Stay with the plot of the current scene (below). Take names, places and events from the source, invent nothing that contradicts it, and imitate its style.\n")
            append("- Describe what the player's character sees and hears, let other characters speak and act, and leave room for the player's reply. Answer in 2 to 5 sentences, no lists, no markdown. Never write words or actions of the player's character.\n")
            append("- If the player departs from the plot, react briefly and consistently in the world and steer back to the events of the scene within one or two replies (through a character, an event, a hint).\n")
            append("- Address the player as \"you\"")
            if (setup.firstPerson && setup.player.isBlank()) append(" (even if the book is told in the first person: the player is the I, you narrate in the second person)")
            append(".\n- Reveal nothing from later scenes.\n\n")
            append("CURRENT SCENE (${scene.title}):\n${scene.text.trim()}\n")
            if (next != null) append("\nNEXT SCENE (for orientation only, do not play it yet): ${next.text.trim().take(300)} …\n")
            if (closing) append("\nThe scene should come to its end now: bring the events of the source to a close in this reply.\n")
        }
    }

    /** Der Spielleiter als Companion ([Persona.promptOverride] ersetzt den gewohnten System-Prompt). */
    fun persona(setup: RoleplaySetup, index: Int, closing: Boolean): Persona = Persona(
        id = "roleplay", name = if (setup.german) "Spielleiter" else "Game master",
        description = "Rollenspiel nach dem Buch ${setup.title}", language = if (setup.german) "Deutsch" else "English",
        promptOverride = system(setup, index, closing),
    )
}
