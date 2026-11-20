package app.aichatmcp.core

/**
 * Die Anfragen an das Modell für jede Stufe. Sie sind kurz gehalten, weil Modelle auf dem Handy nur etwa 4096 Tokens Kontext haben: Jede Stufe bekommt nur,
 * was sie braucht (Figurenblätter gekürzt, die bisherige Handlung als Zusammenfassung, vom letzten Text nur das Ende).
 *
 * Aufbau (Erfahrung mit kleinen Modellen): Die Handlung steht erst als Ganzes fest (inklusive Ende und Namen der Figuren), dann wird jedes Kapitel einzeln geplant
 * (in Kenntnis der schon geplanten, sonst kopieren die Modelle frühere Pläne), dann in Szenen-Beats zerlegt, und jeder Textteil schreibt nur seine Beats aus.
 */
object BookPrompts {
    /** Höchstzahl neuer Tokens je Stufe. */
    fun maxTokens(step: BookStep, chapters: Int = 8): Int = when (step) {
        BookStep.Premise -> 1000
        BookStep.Characters -> 1300
        BookStep.Plan -> 0
        is BookStep.ChapterPlan -> 350
        is BookStep.Beats -> 500
        is BookStep.Part -> 900
        is BookStep.Summary -> 400
        BookStep.Done -> 0
    }

    /** Wie viele Beats ein Kapitel bekommt: etwa drei je Textteil. */
    fun beatCount(p: BookProject): Int = (p.partsPerChapter * 3).coerceIn(3, 9)

    /** Die Namenszeile aus dem Figurenblatt ("Elira – Hexe; Kaelen – Wolfsfürst"): Die Blöcke werden im Prompt gekürzt, die Namen dürfen nie fehlen. */
    fun castLine(characters: String): String = characters.lines().map { it.trim() }
        .filter { Regex("""^\p{L}[^:\n]{1,40}\s[–—-]\s.{3,90}$""").matches(it) }.take(6).joinToString("; ")

    fun trim(text: String, max: Int): String = if (text.length <= max) text.trim() else text.take(max).trimEnd() + " …"

    private fun tail(text: String, max: Int): String = if (text.length <= max) text.trim() else "… " + text.takeLast(max).trimStart()

    private fun briefLines(p: BookProject): String {
        val b = p.brief
        val g = p.german
        return listOfNotNull(
            (if (g) "Thema: " else "Topic: ") + b.topic.trim(),
            b.genre.takeIf { it.isNotBlank() }?.let { (if (g) "Genre: " else "Genre: ") + it.trim() },
            (if (g) "Umfang: " else "Length: ") + b.chapters + (if (g) " Kapitel zu je etwa " else " chapters of about ") + b.wordsPerChapter + (if (g) " Wörtern" else " words"),
            b.perspective.takeIf { it.isNotBlank() }?.let { (if (g) "Perspektive: " else "Point of view: ") + it.trim() },
            b.style.takeIf { it.isNotBlank() }?.let { (if (g) "Stil: " else "Style: ") + it.trim() },
            b.constraints.takeIf { it.isNotBlank() }?.let { (if (g) "Rahmenbedingungen: " else "Constraints: ") + it.trim() },
        ).joinToString("\n")
    }

    private fun author(p: BookProject): String {
        val b = p.brief
        return if (p.german) {
            "Du bist ein erfahrener Romanautor und schreibst auf Deutsch. Du arbeitest sorgfältig, erfindest stimmige Figuren und hältst dich an die Vorgaben."
        } else {
            "You are an experienced novelist writing in ${b.language}. You work carefully, invent coherent characters and stick to the brief."
        }
    }

    fun premise(p: BookProject): List<WireMessage> = listOf(
        WireMessage("system", author(p)),
        WireMessage(
            "user",
            if (p.german) "${briefLines(p)}\n\nEntwirf die Grundlage des Buches als Inhaltsangabe des fertigen Buches, nicht als Klappentext: Erzähle sachlich, was geschieht, wer was tut und warum, und verrate Wendungen und das Ende. Nichts bleibt offen, keine Andeutungen und keine Fragen an den Leser. Schreibe konsequent in der dritten Person und im Präsens (auch wenn das Buch später in der Ich-Form erzählt wird) und erzähle konkrete Ereignisse, Handlungen und Entscheidungen statt Stimmungen.\n" +
                "Gehe zuerst alle Vorgaben des Nutzers einzeln durch: Jede muss in mindestens einem Akt als konkretes Ereignis vorkommen (was die Gegenspieler planen, wer wen verrät, was geopfert wird, wie sich die Figuren zueinander verhalten). Lege das Ende fest, bevor du die Akte schreibst: Die Akte führen widerspruchsfrei dorthin. Jeder Name bezeichnet genau eine Figur mit einer Rolle und bleibt im ganzen Text gleich; wer stirbt, tritt danach nicht mehr auf.\n" +
                "Schreibe genau diese Abschnitte:\nTITEL: (ein Arbeitstitel)\nFIGUREN: (die 3 bis 5 Hauptfiguren, jede als Name – Rolle, durch Semikolon getrennt)\nPRÄMISSE: (3 bis 4 Sätze: Ausgangslage und worum es im Kern geht)\nENDE: (2 bis 3 Sätze: wie es ausgeht, wer lebt, wer stirbt, was sich verändert hat)\nAKT 1: (4 bis 6 Sätze: Ausgangslage, Auslöser, erste Entscheidung)\nAKT 2: (4 bis 6 Sätze: konkrete Verwicklungen, Rückschläge, Wendepunkt)\nAKT 3: (4 bis 6 Sätze: Höhepunkt und Auflösung, endet wie unter ENDE)\nJeder Abschnitt höchstens 90 Wörter. Kein Markdown, keine Erklärungen."
            else "${briefLines(p)}\n\nDesign the foundation of the book as a synopsis of the finished book, not as a blurb: state plainly what happens, who does what and why, and reveal twists and the ending. Leave nothing open, no hints and no questions to the reader. Write consistently in the third person and present tense (even if the book will later be told in the first person) and tell concrete events, actions and decisions instead of moods.\n" +
                "First go through every requirement of the brief one by one: each must appear in at least one act as a concrete event (what the antagonists plan, who betrays whom, what is sacrificed, how the characters relate to each other). Fix the ending before you write the acts: the acts lead to it without contradiction. Each name stands for exactly one character with one role and stays the same throughout; whoever dies does not appear afterwards.\n" +
                "Write exactly these sections:\nTITLE: (a working title)\nCHARACTERS: (the 3 to 5 main characters, each as Name – role, separated by semicolons)\nPREMISE: (3 to 4 sentences: the setup and what the story is about)\nENDING: (2 to 3 sentences: how it ends, who lives, who dies, what has changed)\nACT 1: (4 to 6 sentences: setup, inciting incident, first decision)\nACT 2: (4 to 6 sentences: concrete complications, setbacks, turning point)\nACT 3: (4 to 6 sentences: climax and resolution, ends as under ENDING)\nEach section at most 90 words. No markdown, no explanations.",
        ),
    )

    fun characters(p: BookProject): List<WireMessage> = listOf(
        WireMessage("system", author(p)),
        WireMessage(
            "user",
            if (p.german) "${briefLines(p)}\n\nHANDLUNG:\n${trim(p.premise, 1800)}\n\nErstelle die Dramatis Personae: 4 bis 5 wichtige Figuren, mit denselben Namen und Rollen wie in der Handlung (FIGUREN). Je Figur ein Block, so:\nNAME – Rolle in der Geschichte\nHintergrund und Vergangenheit: …\nZiele und Wünsche: …\nStärken: …\nSchwächen: …\nTrauma: …\nBeziehungen: …\nSprechweise: …\nKonkreter Plan oder Geheimnis: …\nJede Figur höchstens 100 Wörter, in der dritten Person, konkret statt poetisch, kein Markdown. Beginne direkt mit der ersten Figur, ohne Einleitung."
            else "${briefLines(p)}\n\nPLOT:\n${trim(p.premise, 1800)}\n\nCreate the dramatis personae: 4 to 5 important characters, with the same names and roles as in the plot (CHARACTERS). One block per character, like this:\nNAME – role in the story\nBackground and past: …\nGoals and wishes: …\nStrengths: …\nWeaknesses: …\nTrauma: …\nRelationships: …\nWay of speaking: …\nConcrete plan or secret: …\nEach character at most 100 words, in the third person, concrete rather than poetic, no markdown. Start directly with the first character, no introduction.",
        ),
    )

    private fun knownPlans(p: BookProject, chapter: Int): String =
        p.chapters.mapIndexedNotNull { i, c -> if (i != chapter && c.plan.isNotBlank()) "${i + 1}. ${c.title}: ${trim(c.plan, 230)}" else null }.joinToString("\n")

    /** Der Plan eines einzelnen Kapitels, in Kenntnis aller schon geplanten (nichts davon wiederholen) und des Aktes, zu dem es gehört. */
    fun chapterPlan(p: BookProject, chapter: Int): List<WireMessage> {
        val n = p.chapters.size
        val act = p.actOf(chapter, n)
        val actText = BookParse.act(p.premise, act)
        val known = tail(knownPlans(p, chapter), 1800).ifEmpty { if (p.german) "(noch keine)" else "(none yet)" }
        val g = p.german
        val position = when {
            n == 1 -> if (g) "Dies ist das einzige Kapitel: es erzählt die ganze Handlung bis zum Ende." else "This is the only chapter: it tells the whole plot up to the ending."
            chapter == 0 -> if (g) "Dies ist das erste Kapitel: Ausgangslage und Auslöser." else "This is the first chapter: setup and inciting incident."
            chapter == n - 1 -> if (g) "Dies ist das letzte Kapitel: Höhepunkt und Auflösung, es endet wie unter ENDE in der Handlung." else "This is the last chapter: climax and resolution, it ends as under ENDING in the plot."
            else -> if (g) "Dieses Kapitel liegt mitten in Akt $act." else "This chapter lies in the middle of act $act."
        }
        return listOf(
            WireMessage("system", author(p)),
            WireMessage(
                "user",
                if (g) "${briefLines(p)}\n\nHANDLUNG:\n${trim(p.premise, 1800)}\n\nFIGUREN:\n${trim(p.characters, 900)}\n\n" +
                    (if (actText.isNotEmpty()) "DIESES KAPITEL GEHÖRT ZU AKT $act:\n${trim(actText, 700)}\n\n" else "") +
                    "SCHON GEPLANTE KAPITEL (nichts davon wiederholen):\n$known\n\n" +
                    "Schreibe den Plan für Kapitel ${chapter + 1} von $n. $position Das Kapitel bringt ein NEUES Ereignis, das in den geplanten Kapiteln noch nicht vorkommt, und führt die Handlung ein Stück weiter. Die Kapitel führen der Reihe nach durch alle Ereignisse der Handlung bis zum Ende. Genau eine Zeile in diesem Format:\n" +
                    "KAPITEL ${chapter + 1}: Titel | Inhalt in 3 bis 5 Sätzen: Ort, beteiligte Figuren (mit Namen), was geschieht und was sich ändert, wie das Kapitel endet\n" +
                    "Der Inhalt ist eine sachliche Zusammenfassung in der dritten Person und im Präsens, höchstens 60 Wörter, ohne Stimmung und ohne Auszug aus dem Text. Kein Markdown, nichts außer dieser Zeile."
                else "${briefLines(p)}\n\nPLOT:\n${trim(p.premise, 1800)}\n\nCHARACTERS:\n${trim(p.characters, 900)}\n\n" +
                    (if (actText.isNotEmpty()) "THIS CHAPTER BELONGS TO ACT $act:\n${trim(actText, 700)}\n\n" else "") +
                    "ALREADY PLANNED CHAPTERS (do not repeat any of it):\n$known\n\n" +
                    "Write the plan for chapter ${chapter + 1} of $n. $position The chapter brings a NEW event that does not yet appear in the planned chapters and moves the plot forward. The chapters lead in order through all events of the plot to the end. Exactly one line in this format:\n" +
                    "CHAPTER ${chapter + 1}: Title | Content in 3 to 5 sentences: place, characters involved (by name), what happens and what changes, how the chapter ends\n" +
                    "The content is a plain summary in the third person and present tense, at most 60 words, without mood and without an excerpt of the text. No markdown, nothing except this line.",
            ),
        )
    }

    /** Die Szenen-Beats eines Kapitels: konkrete Ereignisse in zeitlicher Reihenfolge, aus denen die Textteile geschrieben werden. */
    fun beats(p: BookProject, chapter: Int): List<WireMessage> {
        val c = p.chapters[chapter]
        val n = p.chapters.size
        val count = beatCount(p)
        val g = p.german
        val prev = p.chapters.getOrNull(chapter - 1)?.takeIf { it.plan.isNotBlank() }
        val next = p.chapters.getOrNull(chapter + 1)?.takeIf { it.plan.isNotBlank() }
        val cast = castLine(p.characters)
        return listOf(
            WireMessage("system", author(p)),
            WireMessage(
                "user",
                if (g) buildString {
                    append("${briefLines(p)}\n\nHANDLUNG (kurz):\n${trim(p.premise, 900)}\n")
                    if (cast.isNotEmpty()) append("NAMEN: $cast\n")
                    append("\nKAPITEL ${chapter + 1} von $n: ${c.title}\nPLAN DES KAPITELS: ${trim(c.plan, 600)}\n")
                    if (prev != null) append("VORHERIGES KAPITEL (schon erzählt): ${trim(prev.plan, 260)}\n")
                    if (next != null) append("NÄCHSTES KAPITEL (nicht vorwegnehmen): ${trim(next.plan, 260)}\n")
                    append("\nZerlege Kapitel ${chapter + 1} in genau $count Beats: konkrete Szenen in zeitlicher Reihenfolge. Jeder Beat steht in genau einer Zeile:\n")
                    append("BEAT 1: Ort; wer handelt (mit Namen); was geschieht (Handlung oder Gespräch, körperlich und konkret); wie es ausgeht oder was sich ändert\n")
                    append("Höchstens 35 Wörter je Beat. Die Beats decken den Plan des Kapitels vollständig ab, jeder bringt ein neues Ereignis, keiner beschreibt nur Stimmung, nichts stammt aus anderen Kapiteln. Kein Markdown, nichts außer den Beat-Zeilen.")
                } else buildString {
                    append("${briefLines(p)}\n\nPLOT (short):\n${trim(p.premise, 900)}\n")
                    if (cast.isNotEmpty()) append("NAMES: $cast\n")
                    append("\nCHAPTER ${chapter + 1} of $n: ${c.title}\nCHAPTER PLAN: ${trim(c.plan, 600)}\n")
                    if (prev != null) append("PREVIOUS CHAPTER (already told): ${trim(prev.plan, 260)}\n")
                    if (next != null) append("NEXT CHAPTER (do not anticipate): ${trim(next.plan, 260)}\n")
                    append("\nBreak chapter ${chapter + 1} into exactly $count beats: concrete scenes in chronological order. Each beat is exactly one line:\n")
                    append("BEAT 1: Place; who acts (by name); what happens (action or conversation, physical and concrete); how it ends or what changes\n")
                    append("At most 35 words per beat. The beats cover the chapter plan completely, each brings a new event, none only describes mood, nothing comes from other chapters. No markdown, nothing except the beat lines.")
                },
            ),
        )
    }

    private fun recap(p: BookProject, upTo: Int): String {
        val parts = p.chapters.take(upTo).mapIndexedNotNull { i, c -> c.summary.takeIf { it.isNotBlank() }?.let { "${i + 1}. ${it.trim()}" } }
        return if (parts.isEmpty()) (if (p.german) "(Anfang des Buches)" else "(start of the book)") else tail(parts.joinToString("\n"), 1100)
    }

    fun part(p: BookProject, step: BookStep.Part): List<WireMessage> {
        val c = p.chapters[step.chapter]
        val b = p.brief
        val g = p.german
        val system = if (g) {
            "${author(p)}\nStil: ${b.style.ifBlank { "klar, bildhaft, ohne Floskeln" }}. Perspektive: ${b.perspective.ifBlank { "dritte Person, Vergangenheit" }}.\n" +
                "Schreibe ausschließlich den Erzähltext des Buches: keine Überschriften, keine Listen, kein Markdown, keine Kommentare an den Leser, keine Zusammenfassung am Ende. " +
                "Zeige statt zu erklären: Handlung, Dialoge zwischen Figuren, körperliche Reaktionen, Sinneseindrücke. Nenne die Figuren beim Namen. Vermeide abstrakte Gedanken über Bedeutung, Erinnerung oder Schicksal und schließe nicht mit einem Merksatz oder einer Erkenntnis. " +
                "Wiederhole keine Sätze, Bilder oder Refrains aus dem bisherigen Text: Jeder Teil bringt neue Ereignisse. Halte dich an Namen und Fakten unten."
        } else {
            "${author(p)}\nStyle: ${b.style.ifBlank { "clear, vivid, no clichés" }}. Point of view: ${b.perspective.ifBlank { "third person, past tense" }}.\n" +
                "Write only the narrative text of the book: no headings, no lists, no markdown, no comments to the reader, no summary at the end. " +
                "Show, don't tell: action, dialogue between characters, physical reactions, sensory detail. Name the characters. Avoid abstract thoughts about meaning, memory or fate and do not end with a moral or a realization. " +
                "Do not repeat sentences, images or refrains from the text so far: every part brings new events. Stick to the names and facts below."
        }
        val words = (b.wordsPerChapter / step.parts).coerceAtLeast(150)
        val previous = if (step.part > 1) tail(c.text, 700) else ""
        val mine = BookParse.beatsForPart(c.beats, step.part, step.parts)
        val after = BookParse.beatsForPart(c.beats, step.part + 1, step.parts).firstOrNull()
        val task = when {
            mine.isNotEmpty() && g && step.part == 1 -> "Beginne das Kapitel."
            mine.isNotEmpty() && g && step.part == step.parts -> "Setze nahtlos fort und schließe mit dem letzten Beat."
            mine.isNotEmpty() && g -> "Setze nahtlos fort."
            mine.isNotEmpty() && step.part == 1 -> "Begin the chapter."
            mine.isNotEmpty() && step.part == step.parts -> "Continue seamlessly and finish with the last beat."
            mine.isNotEmpty() -> "Continue seamlessly."
            g && step.part == 1 -> "Beginne das Kapitel."
            g && step.part == step.parts -> "Setze nahtlos fort und führe das Kapitel zu seinem Ende laut Plan."
            g -> "Setze nahtlos fort."
            step.part == 1 -> "Begin the chapter."
            step.part == step.parts -> "Continue seamlessly and bring the chapter to its planned ending."
            else -> "Continue seamlessly."
        }
        val cast = castLine(p.characters)
        val user = if (g) buildString {
            append("BUCH: ").append(p.displayTitle).append('\n')
            append("HANDLUNG (kurz): ").append(trim(p.premise, 700)).append('\n')
            if (cast.isNotEmpty()) append("NAMEN: ").append(cast).append('\n')
            append("FIGUREN:\n").append(trim(p.characters, 1100)).append('\n')
            append("BISHER:\n").append(recap(p, step.chapter)).append('\n')
            if (p.facts.isNotBlank()) append("FESTE FAKTEN:\n").append(tail(p.facts, 600)).append('\n')
            append("KAPITEL ${step.chapter + 1} von ${p.chapters.size}: ${c.title}\nPLAN DES KAPITELS: ${trim(c.plan, if (mine.isEmpty()) 600 else 350)}\n")
            if (mine.isNotEmpty()) {
                append("BEATS FÜR DIESEN TEIL (schreibe genau diese Ereignisse in dieser Reihenfolge aus, mit Handlung und Dialog; erfinde keine weiteren Ereignisse dazu):\n")
                mine.forEachIndexed { i, beat -> append("${i + 1}. ").append(beat).append('\n') }
                if (after != null) append("DANACH FOLGT (nicht vorwegnehmen): ").append(trim(after, 200)).append('\n')
            }
            if (previous.isNotEmpty()) append("ENDE DES BISHERIGEN KAPITELTEXTS (nur anknüpfen, nicht wiederholen):\n").append(previous).append('\n')
            append("AUFGABE: Schreibe Teil ${step.part} von ${step.parts} dieses Kapitels (etwa $words Wörter). $task")
        } else buildString {
            append("BOOK: ").append(p.displayTitle).append('\n')
            append("PLOT (short): ").append(trim(p.premise, 700)).append('\n')
            if (cast.isNotEmpty()) append("NAMES: ").append(cast).append('\n')
            append("CHARACTERS:\n").append(trim(p.characters, 1100)).append('\n')
            append("SO FAR:\n").append(recap(p, step.chapter)).append('\n')
            if (p.facts.isNotBlank()) append("FIXED FACTS:\n").append(tail(p.facts, 600)).append('\n')
            append("CHAPTER ${step.chapter + 1} of ${p.chapters.size}: ${c.title}\nCHAPTER PLAN: ${trim(c.plan, if (mine.isEmpty()) 600 else 350)}\n")
            if (mine.isNotEmpty()) {
                append("BEATS FOR THIS PART (write out exactly these events in this order, with action and dialogue; do not invent further events):\n")
                mine.forEachIndexed { i, beat -> append("${i + 1}. ").append(beat).append('\n') }
                if (after != null) append("FOLLOWED BY (do not anticipate): ").append(trim(after, 200)).append('\n')
            }
            if (previous.isNotEmpty()) append("END OF THE CHAPTER TEXT SO FAR (only continue from it, do not repeat it):\n").append(previous).append('\n')
            append("TASK: Write part ${step.part} of ${step.parts} of this chapter (about $words words). $task")
        }
        return listOf(WireMessage("system", system), WireMessage("user", user))
    }

    fun summary(p: BookProject, chapter: Int): List<WireMessage> {
        val c = p.chapters[chapter]
        return listOf(
            WireMessage("system", author(p)),
            WireMessage(
                "user",
                if (p.german) "KAPITEL ${chapter + 1}: ${c.title}\n\n${tail(c.text, 3500)}\n\nFasse das Kapitel in 3 bis 4 Sätzen zusammen und nenne danach neue feste Fakten (Namen, Orte, Ereignisse, Entscheidungen) als Stichpunkte. Format:\nZUSAMMENFASSUNG: …\nFAKTEN:\n- …"
                else "CHAPTER ${chapter + 1}: ${c.title}\n\n${tail(c.text, 3500)}\n\nSummarize the chapter in 3 to 4 sentences, then list new fixed facts (names, places, events, decisions) as bullet points. Format:\nSUMMARY: …\nFACTS:\n- …",
            ),
        )
    }
}
