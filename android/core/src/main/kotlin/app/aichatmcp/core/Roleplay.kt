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
    fun candidates(fullText: String, max: Int = 24): List<String> {
        val text = fullText.take(300_000) // die Namen kommen früh und oft vor: ein Ausschnitt genügt und hält die Wartezeit kurz
        val word = Regex("""\p{Lu}[\p{L}'’-]{2,}""")
        val counts = HashMap<String, Int>()
        val withArticle = HashMap<String, Int>() // wie oft ein Artikel davorsteht: Namen haben selten einen, Hauptwörter (der Geist, die Tür, das Haus) fast immer
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
            var start = i
            while (start >= 0 && text[start].isLetter()) start--
            if (text.substring(start + 1, i + 1).lowercase() in ARTICLES) withArticle[w] = (withArticle[w] ?: 0) + 1
        }
        return counts.filter { it.value >= 3 && (withArticle[it.key] ?: 0) * 10 < it.value * 3 }.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }

    private val ARTICLES = setOf(
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines", "im", "am", "vom", "zum", "zur", "beim", "ins", "ans", "durchs",
        "mein", "meine", "meinen", "meinem", "meiner", "dein", "deine", "deinen", "sein", "seine", "seinen", "seinem", "seiner", "ihre", "ihren", "ihrem", "ihrer", "unser", "unsere",
        "kein", "keine", "keinen", "jeden", "jede", "jeder", "dieser", "diese", "dieses", "diesen", "the", "a", "an", "his", "her", "their", "our", "your", "my",
    )

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

    /** Die Anfrage nach dem Namen des Ich-Erzählers: [sample] ist der Anfang der Geschichte, [names] die möglichen Namen. */
    fun narratorPrompt(sample: String, names: List<String>): List<WireMessage> = listOf(
        WireMessage(
            "user",
            "This is the beginning of a novel told in the first person. Who is the narrator, the character who says \"I\"? Possible names: ${names.joinToString(", ")}. " +
                "Answer with exactly one name from this list, or NONE if the narrator is not named in the text.\n\nTEXT:\n${sample.trim().take(6000)}",
        ),
    )

    /** Der Name des Erzählers aus der Antwort; nur ein Name aus [names] zählt (nichts Erfundenes), sonst leer. */
    fun parseNarrator(answer: String, names: List<String>): String {
        val a = answer.trim()
        if (a.startsWith("NONE", ignoreCase = true)) return ""
        // der erste Name der Liste, der in der Antwort vorkommt (auch "**Lucy** (Lucy Carlyle)" oder ein ganzer Satz)
        return names.mapNotNull { n -> Regex("""\b${Regex.escape(n)}\b""").find(a)?.let { it.range.first to n } }.minByOrNull { it.first }?.second.orEmpty()
    }

    /** Erzählt das Buch in der Ich-Form? ("ich" kommt deutlich häufiger vor als "er"/"sie"; Reden in Anführungszeichen zählen nicht.) */
    fun firstPerson(text: String): Boolean {
        // Titelseite und Vorspann überspringen (bei langen Büchern); Reden in Anführungszeichen zählen nicht
        val body = if (text.length > 20000) text.drop(3000) else text
        val sample = body.take(60000).replace(Regex("""["„“»][^"“”«]{0,300}["“”«]"""), " ")
        val ich = Regex("""(?i)\b(ich|mir|mich|meine[nrms]?|i|my|me)\b""").findAll(sample).count()
        val third = Regex("""(?i)\b(er|sie|ihm|ihn|ihr|seine[nrms]?|he|she|him|his|her)\b""").findAll(sample).count()
        // ein Ich-Erzähler spricht auch über andere ("er sagte"): schon ein halb so häufiges "ich" wie "er/sie" genügt, im Dritte-Person-Buch kommt "ich" nur in Reden vor (herausgerechnet)
        return ich > 30 && ich >= third * 0.5
    }
}

/**
 * Die Einstellungen eines laufenden Buch-Rollenspiels. [player] leer = der Ich-Erzähler. [solo]: Solo-Abenteuer mit Entscheidungen nach jeder Szene
 * (Regeln in [Solo]); [scenesPerDay]: nach so vielen Szenen vergeht eine Nacht; [known]: die Hauptfiguren, für die es Beziehungspunkte gibt.
 */
data class RoleplaySetup(
    val title: String, val scenes: List<Scene>, val player: String, val firstPerson: Boolean, val german: Boolean,
    val solo: Boolean = false, val scenesPerDay: Int = Solo.DEFAULT_SCENES_PER_DAY, val known: List<String> = emptyList(),
    /** Solo-Abenteuer: Art der Entscheidungen (Handlung/Risiko/Umweg, Stränge oder abwechselnd). */
    val mode: DecisionMode = DecisionMode.CLASSIC,
    /** Solo-Abenteuer: Zusammenfassung je Szene (Nummer -> Handlung), wird beim Spielen nach und nach gefüllt; leer = Anfang des Originaltextes. */
    val plots: Map<Int, String> = emptyMap(),
    /** Schlüssel des Zwischenspeichers der Zusammenfassungen ([Summaries.key]); leer = nicht speichern. */
    val summaryKey: String = "",
    /** Handlungsstrang je Szene aus dem vorbereiteten Spielbuch ([Gamebooks]); leer = unbekannt (dann gelten die nächsten Szenen in der Reihenfolge des Buches). */
    val threads: Map<Int, String> = emptyMap(),
    /** Erkennt dasselbe Spiel auch nach Änderungen von [plots]. */
    val id: Long = System.nanoTime(),
    /** Das vorbereitete Spielbuch: Zusammenfassungen, Orte, Stränge und fertige Entscheidungen; null = die Entscheidungen fragt das Modell nach jeder Szene ab. */
    val book: Gamebook? = null,
    /** Szenen, die nicht zur Geschichte gehören (Titelseite, Copyright, Danksagung): sie gelten als gespielt und werden nie angeboten. */
    val skip: Set<Int> = emptySet(),
    /** Der Ich-Erzähler heißt im Buch so (z. B. "Lucy"): Der Name steht in den Prompts und beschriftet die Nachrichten der Spielerfigur; leer = unbekannt. */
    val narrator: String = "",
    /** Solo-Abenteuer mit Originaltext: Der Text der Szenen wird ungekürzt angezeigt und vorgelesen, der Spielleiter erzählt nur die Folgen einer Abweichung ([RoleplayPrompt.consequence]). */
    val verbatim: Boolean = false,
    /** [Scene.text] ist der Wortlaut des Buches (nicht erst die Zusammenfassung): Dann steht ein Auszug im Prompt, damit der Spielleiter nichts erfindet. */
    val source: Boolean = false,
) {
    /** Wie die Spielerfigur im Buch heißt: die gewählte Figur, sonst der Ich-Erzähler (wenn sein Name bekannt ist), sonst leer. */
    val hero: String get() = player.ifBlank { narrator }

    /** Die Handlung der Szene [index]: die Zusammenfassung (Solo-Abenteuer), sonst oder ohne sie der Text, höchstens [max] Zeichen. */
    fun plotOf(index: Int, max: Int = 900): String {
        val scene = scenes.getOrNull(index) ?: return ""
        val plot = plots[index].orEmpty().trim()
        return if (plot.isNotEmpty()) plot else scene.text.trim().take(max)
    }

    /** Hat die Szene [index] schon eine Zusammenfassung (auch eine leere, nach einem Fehler: dann gilt der Text)? */
    fun hasPlot(index: Int): Boolean = plots.containsKey(index)
}

object RoleplayPrompt {
    /** Der Spielleiter-Prompt für die Szene [index]; [closing]: die Szene soll jetzt zum Ende kommen. */
    fun system(setup: RoleplaySetup, index: Int, closing: Boolean, solo: SoloState? = null, outcome: String = "", previous: Int? = null): String =
        (if (setup.solo && solo != null) (if (setup.verbatim) consequence(setup, index, previous) else narration(setup, index, previous)) else base(setup, index, closing)) + soloBlock(setup, solo, outcome, closing)

    private const val SOURCE_CHARS = 1800

    /** Wer die Spielerfigur ist, für die Prompts: die gewählte Figur, der benannte Ich-Erzähler oder der Ich-Erzähler ohne Namen. */
    private fun playerText(setup: RoleplaySetup): String {
        val g = setup.german
        return when {
            setup.player.isNotBlank() -> if (g) "die Figur ${setup.player}" else "the character ${setup.player}"
            setup.narrator.isNotBlank() -> if (g) "den Ich-Erzähler des Buches, ${setup.narrator}" else "the first-person narrator of the book, ${setup.narrator}"
            else -> if (g) "den Ich-Erzähler des Buches (die Figur, aus deren Sicht erzählt wird)" else "the first-person narrator of the book"
        }
    }

    private fun whoText(setup: RoleplaySetup): String = when {
        setup.player.isNotBlank() -> setup.player
        setup.narrator.isNotBlank() -> if (setup.german) "${setup.narrator} (dem Ich-Erzähler)" else "${setup.narrator} (the first-person narrator)"
        else -> if (setup.german) "(dem Ich-Erzähler)" else "(the first-person narrator)"
    }

    private fun nameRule(setup: RoleplaySetup): String = when {
        setup.hero.isBlank() -> ""
        setup.german -> "- Die Spielerfigur heißt ${setup.hero}; so nennen sie die anderen Figuren. Gib ihr keinen anderen Namen und nenne den Spieler nie bei seinem eigenen Namen.\n"
        else -> "- The player's character is called ${setup.hero}; that is what the other characters call them. Give them no other name and never call the player by their own name.\n"
    }

    /**
     * Spielleiter im Modus "Originaltext": Der Text der Szene steht schon im Chat (ungekürzt, wie im Buch). Das Modell schreibt nur noch den kurzen Abschnitt, wenn der Spieler
     * von der Handlung abweicht (Risiko oder Umweg): die von der App festgelegte Folge, Ort und Personen aus dem Buch, und er endet so, dass die neue Szene danach unverändert anschließt.
     */
    private fun consequence(setup: RoleplaySetup, index: Int, previous: Int?): String {
        val g = setup.german
        val next = setup.scenes[index].text.trim().take(350)
        // der Wortlaut vom Ende der vorigen Szene: Der Spieler hat ihn gerade gelesen, die Folge muss daran anschließen (nur die Zusammenfassung ließ das Modell Dinge erfinden)
        val tail = previous?.takeIf { it in setup.scenes.indices }?.let { setup.scenes[it].text.trim().takeLast(700) }.orEmpty()
        return if (g) buildString {
            append("Du bist der Erzähler eines Solo-Abenteuers (Spielbuch) nach dem Buch „${setup.title}“. Der Spieler spielt ${playerText(setup)}. Der Buchtext wird dem Spieler ungekürzt vorgelesen; du schreibst nur die kurze Folge, wenn der Spieler von der Handlung abweicht.\n")
            append("Regeln:\n")
            if (setup.firstPerson && setup.player.isBlank()) append("- Schreibe einen kurzen Abschnitt von 50 bis 120 Wörtern in der Ich-Form, wie das Buch („ich“: der Spieler ist das Ich). Keine Aufzählungen, kein Markdown.\n")
            else append("- Schreibe einen kurzen Abschnitt von 50 bis 120 Wörtern in der zweiten Person („du“). Keine Aufzählungen, kein Markdown.\n")
            append("- Die Folge der Entscheidung ist vorgegeben (siehe unten, verbindlich): Erzähle sie. Erfinde keine anderen Verletzungen, Heilungen oder Tode.\n")
            append("- Bleibe an dem Ort, an dem die Spielerfigur ist, und verwende nur Personen und Dinge aus ZULETZT GELESEN, VORHER und NEUE SZENE. Erfinde keine neuen Personen, keine Gegenstände und keine Gespräche mit Personen, die dort nicht vorkommen.\n")
            append("- Ende so, dass die NEUE SZENE danach anschließen kann (sie wird danach unverändert vorgelesen). Schreibe sie nicht selbst und nimm nichts aus ihr vorweg.\n")
            append(nameRule(setup))
            append("- Erwähne nie die App, Regeln oder Entscheidungen als solche.\n\n")
            previousBlock(setup, previous, true)?.let { append(it) }
            if (tail.isNotEmpty()) append("ZULETZT GELESEN (Ende der vorigen Szene, wörtlich): … $tail\n\n")
            append("NEUE SZENE (unten) beginnt so: $next …\n")
        } else buildString {
            append("You are the narrator of a solo adventure (gamebook) based on the book \"${setup.title}\". The player plays ${playerText(setup)}. The book text is read to the player in full; you only write the short consequence when the player departs from the plot.\n")
            append("Rules:\n")
            if (setup.firstPerson && setup.player.isBlank()) append("- Write a short passage of 50 to 120 words in the first person, like the book (\"I\": the player is the I). No lists, no markdown.\n")
            else append("- Write a short passage of 50 to 120 words in the second person (\"you\"). No lists, no markdown.\n")
            append("- The result of the decision is given (see below, binding): tell it. Invent no other injuries, healing or deaths.\n")
            append("- Stay at the place where the player's character is and use only people and things from LAST READ, BEFORE and NEW SCENE. Invent no new people, no objects and no conversations with people who do not appear there.\n")
            append("- End so that the NEW SCENE can follow (it is read unchanged afterwards). Do not write it yourself and anticipate nothing from it.\n")
            append(nameRule(setup))
            append("- Never mention the app, rules or decisions as such.\n\n")
            previousBlock(setup, previous, false)?.let { append(it) }
            if (tail.isNotEmpty()) append("LAST READ (end of the previous scene, verbatim): … $tail\n\n")
            append("NEW SCENE (below) begins like this: $next …\n")
        }
    }

    /**
     * Solo-Abenteuer wie ein Spielbuch: Der Spielleiter erzählt je Szene einen zusammenhängenden Abschnitt von einer Viertel- bis einer halben Seite
     * (kein langer Dialog) und hält an einer Stelle an, an der entschieden wird. Die Handlung kommt als Zusammenfassung; der Wortlaut des Buches ist unwichtig.
     */
    private fun narration(setup: RoleplaySetup, index: Int, previous: Int?): String {
        val scene = setup.scenes[index]
        val g = setup.german
        val player = playerText(setup)
        return if (g) buildString {
            append("Du bist der Erzähler eines Solo-Abenteuers (Spielbuch) nach dem Buch „${setup.title}“. Der Spieler spielt $player. Du erzählst die Szene, danach entscheidet der Spieler.\n")
            append("Regeln:\n")
            append("- Erzähle die Szene als zusammenhängenden Abschnitt von etwa 120 bis 250 Wörtern, in der zweiten Person („du“")
            if (setup.firstPerson && setup.player.isBlank()) append(", auch wenn das Buch in der Ich-Form erzählt: der Spieler ist das Ich")
            append("). Keine Aufzählungen, kein Markdown.\n")
            append("- Die Handlung der Szene steht unten als Zusammenfassung. Erzähle ihre Ereignisse, Orte und Figuren in eigenen Worten und gib Gespräche nur kurz und sinngemäß wieder; der Wortlaut des Buches ist unwichtig, Namen und Ereignisse nicht. Erfinde keine Personen, Gespräche oder Ereignisse, die nicht in der Handlung oder im Auszug stehen, und nenne nur Namen, die dort vorkommen.\n")
            append(nameRule(setup))
            append("- Beschreibe, was die Spielerfigur sieht, hört und erlebt und was die anderen Figuren sagen und tun. Lege der Spielerfigur keine neuen Entscheidungen in den Mund.\n")
            append("- Höre an einer offenen Stelle auf, an der der Spieler sich entscheiden muss: Ende mit einer offenen Situation, nicht mit einer Frage an den Spieler und ohne Aufzählung von Möglichkeiten. Erwähne nie die App, Regeln oder Entscheidungen als solche.\n")
            append("- Weicht der Spieler mit seiner Eingabe von der Handlung ab, reagiere kurz und stimmig in der Welt und führe in diesem Abschnitt zu den Ereignissen der Szene zurück. Der Spieler kann nur tun, was vom Ort der Spielerfigur aus möglich ist.\n")
            append("- Verrate nichts aus späteren Szenen und ende genau dort, wo die Handlung endet: Was erst nach der nächsten Entscheidung geschieht, gehört nicht in diesen Abschnitt. Setze genau dort fort, wo die vorige Szene aufgehört hat, und wiederhole keine Sätze aus dem Verlauf.\n\n")
            previousBlock(setup, previous, true)?.let { append(it) }
            append("HANDLUNG DER SZENE (${scene.title}):\n${setup.plotOf(index)}\n")
            if (setup.source) append("\nAUSZUG AUS DEM BUCH (nur zur Orientierung: Namen, Orte, Ereignisse und Reihenfolge übernehmen, nicht den Wortlaut):\n${scene.text.trim().take(SOURCE_CHARS)}\n")
        } else buildString {
            append("You are the narrator of a solo adventure (gamebook) based on the book \"${setup.title}\". The player plays $player. You tell the scene, then the player decides.\n")
            append("Rules:\n")
            append("- Tell the scene as one continuous passage of about 120 to 250 words, in the second person (\"you\"")
            if (setup.firstPerson && setup.player.isBlank()) append(", even if the book is told in the first person: the player is the I")
            append("). No lists, no markdown.\n")
            append("- The plot of the scene is given below as a summary. Tell its events, places and characters in your own words and give conversations only briefly and in substance; the wording of the book does not matter, names and events do. Invent no people, conversations or events that are not in the plot or the excerpt, and use only names that appear there.\n")
            append(nameRule(setup))
            append("- Describe what the player's character sees, hears and experiences and what the other characters say and do. Do not put new decisions into the player character's mouth.\n")
            append("- Stop at an open moment where the player has to decide: end with an open situation, not with a question to the player and without listing options. Never mention the app, rules or decisions as such.\n")
            append("- If the player's input departs from the plot, react briefly and consistently in the world and lead back to the events of the scene within this passage. The player can only do what is possible from the player character's place.\n")
            append("- Reveal nothing from later scenes and end exactly where the plot ends: what only happens after the next decision does not belong in this passage. Continue exactly where the previous scene stopped and do not repeat sentences from the history.\n\n")
            previousBlock(setup, previous, false)?.let { append(it) }
            append("PLOT OF THE SCENE (${scene.title}):\n${setup.plotOf(index)}\n")
            if (setup.source) append("\nEXCERPT FROM THE BOOK (for orientation only: take names, places, events and their order, not the wording):\n${scene.text.trim().take(SOURCE_CHARS)}\n")
        }
    }

    /** Wo die Geschichte vorher stand: Zusammenfassung der vorigen Szene und der Ort der Spielerfigur am Ende (nur mit Spielbuch). */
    private fun previousBlock(setup: RoleplaySetup, previous: Int?, german: Boolean): String? {
        if (previous == null || previous !in setup.scenes.indices) return null
        val place = setup.book?.scenes?.getOrNull(previous)?.place?.takeIf { it.isNotBlank() }
        return buildString {
            append(if (german) "VORHER (${setup.scenes[previous].title}): ${setup.plotOf(previous, 400)}\n" else "BEFORE (${setup.scenes[previous].title}): ${setup.plotOf(previous, 400)}\n")
            if (place != null) append(if (german) "ORT DER SPIELERFIGUR ZU BEGINN: $place\n" else "THE PLAYER CHARACTER'S PLACE AT THE START: $place\n")
            append('\n')
        }
    }

    /** Zusatz im Solo-Abenteuer: Spielstand, Regeln (die App legt die Folgen fest) und die verbindliche Folge der letzten Entscheidung. */
    private fun soloBlock(setup: RoleplaySetup, solo: SoloState?, outcome: String, closing: Boolean): String {
        if (!setup.solo || solo == null) return ""
        val g = setup.german
        return buildString {
            append(if (g) "\nSOLO-ABENTEUER: Nach jeder Szene entscheidet der Spieler. Die Folgen sind vorgegeben (siehe unten): Erfinde nie selbst Verletzungen, Heilungen oder Tode.\n" else "\nSOLO ADVENTURE: After every scene the player decides. The consequences are given (see below): never invent injuries, healing or deaths yourself.\n")
            append(if (g) "SPIELSTAND: " else "GAME STATE: ").append(Solo.stateText(solo, g)).append('\n')
            if (outcome.isNotBlank()) append(if (g) "FOLGE DER LETZTEN ENTSCHEIDUNG (verbindlich): " else "RESULT OF THE LAST DECISION (binding): ").append(outcome).append('\n')
            if (closing) append(if (g) "Beende die Szene so, dass der Spieler vor einer Entscheidung steht. Schreibe keine Möglichkeiten auf und erwähne die App nicht.\n" else "End the scene so that the player faces a decision. Do not list options and do not mention the app.\n")
        }
    }

    /**
     * Die Anfrage nach den Entscheidungen am Ende einer Szene: höchstens eine je Art (Handlung, Risiko, Umweg), jede in einer Zeile, optional mit Beziehungspunkten
     * für Hauptfiguren. [lastReply] ist das Ende der Szene.
     */
    fun decisions(setup: RoleplaySetup, index: Int, lastReply: String, solo: SoloState, played: Set<Int> = setOf(index)): List<WireMessage> {
        val scene = setup.scenes[index]
        val nextIndex = Solo.candidates(setup.scenes.size, played, 1).firstOrNull()
        val next = nextIndex?.let { setup.scenes[it] }
        val nextPlot = nextIndex?.let { setup.plotOf(it, 400) }.orEmpty()
        val g = setup.german
        val names = (setup.known + setup.player).filter { it.isNotBlank() }.distinct()
        val cast = setup.known.filter { it != setup.player }.take(8).joinToString(", ")
        val user = if (g) buildString {
            append("Buch: „${setup.title}“. Die Szene „${scene.title}“ ist zu Ende gespielt. Die Handlung der Szene:\n${setup.plotOf(index, 900)}\n\n")
            append("Zuletzt erzählt:\n${lastReply.trim().takeLast(700)}\n\n")
            if (next != null) append("Danach folgt (Handlung des Buches):\n$nextPlot …\n\n")
            append("Schlage der Spielerfigur ${whoText(setup)} jetzt Entscheidungen vor, jeweils ein Satz in der Ich-Form der Spielerfigur, passend zur Situation:\n")
            append("STORY: eine Entscheidung, die der Handlung des Buches folgt: nenne nur die Richtung oder Handlung, mit der die nächste Szene beginnt (zum Beispiel „Ich gehe die Treppe weiter hinauf.“), nie ihr Ergebnis und nichts, was erst später enthüllt wird\n")
            append("RISIKO: eine Entscheidung, die von der Handlung abweicht und gefährlich werden kann (Verletzung oder Tod)\n")
            append("UMWEG: eine Variante, die anders verläuft, aber zur Handlung zurückführt\n")
            if (cast.isNotEmpty()) append("Wirkt eine Entscheidung auf das Verhältnis zu einer Hauptfigur ($cast), hänge ans Zeilenende [Name +1] oder [Name -1].\n")
            append("Genau diese Zeilen, nichts anderes, kein Markdown.")
        } else buildString {
            append("Book: \"${setup.title}\". The scene \"${scene.title}\" has been played to its end. The plot of the scene:\n${setup.plotOf(index, 900)}\n\n")
            append("Last told:\n${lastReply.trim().takeLast(700)}\n\n")
            if (next != null) append("What follows (plot of the book):\n$nextPlot …\n\n")
            append("Now propose decisions to the player's character ${whoText(setup)}, each one sentence in the first person of the player's character, fitting the situation:\n")
            append("STORY: a decision that follows the plot of the book: name only the direction or action the next scene begins with (for example \"I go on up the stairs.\"), never its result and nothing that is revealed later\n")
            append("RISK: a decision that departs from the plot and can become dangerous (injury or death)\n")
            append("DETOUR: a variant that goes differently but leads back to the plot\n")
            if (cast.isNotEmpty()) append("If a decision affects the relationship with a main character ($cast), append [Name +1] or [Name -1] at the end of the line.\n")
            append("Exactly these lines, nothing else, no markdown.")
        }
        return listOf(
            WireMessage("system", if (g) "Du bist der Spielleiter eines Rollenspiels nach einem Buch. Du schreibst nur die verlangten Zeilen." else "You are the game master of a role play based on a book. You write only the requested lines."),
            WireMessage("user", user),
        )
    }

    /**
     * Die Anfrage nach den Strang-Entscheidungen: [candidates] sind die Szenen, zwischen denen der Spieler wählt (jede ein eigener Handlungsstrang oder ein
     * eigenes Erlebnis des Buches). Das Modell schreibt je Szene einen Satz, der in sie hineinführt, ohne zu verraten, was dort geschieht.
     */
    fun strandDecisions(setup: RoleplaySetup, index: Int, lastReply: String, candidates: List<Int>): List<WireMessage> {
        val g = setup.german
        val who = whoText(setup)
        val list = candidates.mapIndexed { i, c ->
            val thread = setup.threads[c]?.takeIf { it.isNotBlank() }?.let { " [${if (g) "Strang" else "storyline"}: $it]" }.orEmpty()
            "${i + 1}. ${setup.scenes[c].title}$thread: ${setup.plotOf(c, 450)}"
        }.joinToString("\n")
        val user = if (g) buildString {
            append("Buch: „${setup.title}“. Die Szene „${setup.scenes[index].title}“ ist zu Ende gespielt. Zuletzt erzählt:\n${lastReply.trim().takeLast(600)}\n\n")
            append("Die Spielerfigur $who kann jetzt wählen, womit es weitergeht. Das sind die Möglichkeiten (jede ein eigener Handlungsstrang des Buches):\n$list\n\n")
            append("Schreibe zu jeder Nummer genau eine Entscheidung als ein Satz in der Ich-Form der Spielerfigur, der in diesen Strang führt (zum Beispiel „Ich besuche die Zeugen.“ oder „Ich folge dem Bachlauf.“). ")
            append("Verrate nicht, was dort geschieht, und nenne keine Figuren, die die Spielerfigur noch nicht kennt.\n")
            append("Genau diese Form, nichts anderes, kein Markdown:\n" + candidates.indices.joinToString("\n") { "${it + 1}: …" })
        } else buildString {
            append("Book: \"${setup.title}\". The scene \"${setup.scenes[index].title}\" has been played to its end. Last told:\n${lastReply.trim().takeLast(600)}\n\n")
            append("The player's character $who can now choose what happens next. These are the options (each a separate strand of the book):\n$list\n\n")
            append("For each number write exactly one decision as a sentence in the first person of the player's character that leads into that strand (for example \"I visit the witnesses.\" or \"I follow the stream.\"). ")
            append("Do not reveal what happens there and do not name characters the player's character does not know yet.\n")
            append("Exactly this form, nothing else, no markdown:\n" + candidates.indices.joinToString("\n") { "${it + 1}: …" })
        }
        return listOf(
            WireMessage("system", if (g) "Du bist der Spielleiter eines Rollenspiels nach einem Buch. Du schreibst nur die verlangten Zeilen." else "You are the game master of a role play based on a book. You write only the requested lines."),
            WireMessage("user", user),
        )
    }

    private fun base(setup: RoleplaySetup, index: Int, closing: Boolean): String {
        val scene = setup.scenes[index]
        val next = setup.scenes.getOrNull(index + 1)
        val g = setup.german
        val player = playerText(setup)
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
    fun persona(setup: RoleplaySetup, index: Int, closing: Boolean, solo: SoloState? = null, outcome: String = "", previous: Int? = null): Persona = Persona(
        id = "roleplay", name = if (setup.german) "Spielleiter" else "Game master",
        description = "Rollenspiel nach dem Buch ${setup.title}", language = if (setup.german) "Deutsch" else "English",
        promptOverride = system(setup, index, closing, solo, outcome, previous),
    )
}
