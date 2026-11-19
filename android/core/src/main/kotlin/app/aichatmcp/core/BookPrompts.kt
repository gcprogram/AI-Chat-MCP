package app.aichatmcp.core

/**
 * Die Anfragen an das Modell für jede Stufe. Sie sind kurz gehalten, weil Modelle auf dem Handy nur etwa 4096 Tokens Kontext haben: Jede Stufe bekommt nur,
 * was sie braucht (Figurenblätter gekürzt, die bisherige Handlung als Zusammenfassung, vom letzten Text nur das Ende).
 */
object BookPrompts {
    /** Höchstzahl neuer Tokens je Stufe. */
    fun maxTokens(step: BookStep): Int = when (step) {
        BookStep.Premise -> 600
        BookStep.Characters -> 1100
        BookStep.Plan -> 1100
        is BookStep.ChapterPlan -> 350
        is BookStep.Part -> 900
        is BookStep.Summary -> 400
        BookStep.Done -> 0
    }

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
            if (p.german) "${briefLines(p)}\n\nEntwirf die Grundlage des Buches. Schreibe genau diese Abschnitte:\nTITEL: (ein Arbeitstitel)\nPRÄMISSE: (3 bis 4 Sätze)\nAKT 1: (3 bis 5 Sätze: Ausgangslage, Auslöser)\nAKT 2: (3 bis 5 Sätze: Verwicklungen, Wendepunkt)\nAKT 3: (3 bis 5 Sätze: Höhepunkt, Ende)\nKein Markdown, keine Erklärungen."
            else "${briefLines(p)}\n\nDesign the foundation of the book. Write exactly these sections:\nTITLE: (a working title)\nPREMISE: (3 to 4 sentences)\nACT 1: (3 to 5 sentences: setup, inciting incident)\nACT 2: (3 to 5 sentences: complications, turning point)\nACT 3: (3 to 5 sentences: climax, ending)\nNo markdown, no explanations.",
        ),
    )

    fun characters(p: BookProject): List<WireMessage> = listOf(
        WireMessage("system", author(p)),
        WireMessage(
            "user",
            if (p.german) "${briefLines(p)}\n\nHANDLUNG:\n${trim(p.premise, 1500)}\n\nErstelle die Dramatis Personae: 4 bis 6 wichtige Figuren. Je Figur ein Block, so:\nNAME – Rolle in der Geschichte\nHintergrund und Vergangenheit: …\nZiele und Wünsche: …\nStärken: …\nSchwächen: …\nTrauma: …\nBeziehungen: …\nSprechweise: …\nKurz und konkret, kein Markdown. Beginne direkt mit der ersten Figur, ohne Einleitung."
            else "${briefLines(p)}\n\nPLOT:\n${trim(p.premise, 1500)}\n\nCreate the dramatis personae: 4 to 6 important characters. One block per character, like this:\nNAME – role in the story\nBackground and past: …\nGoals and wishes: …\nStrengths: …\nWeaknesses: …\nTrauma: …\nRelationships: …\nWay of speaking: …\nShort and concrete, no markdown. Start directly with the first character, no introduction.",
        ),
    )

    fun plan(p: BookProject): List<WireMessage> = listOf(
        WireMessage("system", author(p)),
        WireMessage(
            "user",
            if (p.german) "${briefLines(p)}\n\nHANDLUNG:\n${trim(p.premise, 1500)}\n\nFIGUREN:\n${trim(p.characters, 1800)}\n\nGliedere das Buch in genau ${p.brief.chapters} Kapitel. Schreibe je Kapitel genau eine Zeile in diesem Format (der Inhalt darf mehrere Sätze lang sein, aber ohne Zeilenumbruch):\nKAPITEL 1: Titel | Inhalt in 3 bis 5 Sätzen: Ort, beteiligte Figuren, was geschieht und was sich ändert, wie das Kapitel endet\nKein Markdown, nichts außer den Kapitelzeilen."
            else "${briefLines(p)}\n\nPLOT:\n${trim(p.premise, 1500)}\n\nCHARACTERS:\n${trim(p.characters, 1800)}\n\nDivide the book into exactly ${p.brief.chapters} chapters. Write exactly one line per chapter in this format (the content may be several sentences but without line breaks):\nCHAPTER 1: Title | Content in 3 to 5 sentences: place, characters involved, what happens and what changes, how the chapter ends\nNo markdown, nothing except the chapter lines.",
        ),
    )

    /** Der Plan eines einzelnen Kapitels, passend zu den schon vorhandenen. */
    fun chapterPlan(p: BookProject, chapter: Int): List<WireMessage> {
        val known = p.chapters.mapIndexedNotNull { i, c -> if (i != chapter && c.plan.isNotBlank()) "${i + 1}. ${c.title}: ${trim(c.plan, 220)}" else null }
        val g = p.german
        return listOf(
            WireMessage("system", author(p)),
            WireMessage(
                "user",
                if (g) "${briefLines(p)}\n\nHANDLUNG:\n${trim(p.premise, 1200)}\n\nFIGUREN:\n${trim(p.characters, 1200)}\n\nSCHON GEPLANTE KAPITEL:\n${tail(known.joinToString("\n"), 1400).ifEmpty { "(noch keine)" }}\n\nSchreibe den Plan für Kapitel ${chapter + 1} von ${p.chapters.size}, passend zu den anderen. Genau eine Zeile in diesem Format:\nKAPITEL ${chapter + 1}: Titel | Inhalt in 3 bis 5 Sätzen: Ort, beteiligte Figuren, was geschieht und was sich ändert, wie das Kapitel endet\nKein Markdown, nichts außer dieser Zeile."
                else "${briefLines(p)}\n\nPLOT:\n${trim(p.premise, 1200)}\n\nCHARACTERS:\n${trim(p.characters, 1200)}\n\nALREADY PLANNED CHAPTERS:\n${tail(known.joinToString("\n"), 1400).ifEmpty { "(none yet)" }}\n\nWrite the plan for chapter ${chapter + 1} of ${p.chapters.size}, consistent with the others. Exactly one line in this format:\nCHAPTER ${chapter + 1}: Title | Content in 3 to 5 sentences: place, characters involved, what happens and what changes, how the chapter ends\nNo markdown, nothing except this line.",
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
                "Zeige statt zu erklären: Handlung, Dialoge, Sinneseindrücke. Wiederhole keine Formulierungen. Halte dich an Namen und Fakten unten."
        } else {
            "${author(p)}\nStyle: ${b.style.ifBlank { "clear, vivid, no clichés" }}. Point of view: ${b.perspective.ifBlank { "third person, past tense" }}.\n" +
                "Write only the narrative text of the book: no headings, no lists, no markdown, no comments to the reader, no summary at the end. " +
                "Show, don't tell: action, dialogue, sensory detail. Do not repeat phrases. Stick to the names and facts below."
        }
        val words = (b.wordsPerChapter / step.parts).coerceAtLeast(150)
        val previous = if (step.part > 1) tail(c.text, 700) else ""
        val task = when {
            g && step.part == 1 -> "Beginne das Kapitel."
            g && step.part == step.parts -> "Setze nahtlos fort und führe das Kapitel zu seinem Ende laut Plan."
            g -> "Setze nahtlos fort."
            step.part == 1 -> "Begin the chapter."
            step.part == step.parts -> "Continue seamlessly and bring the chapter to its planned ending."
            else -> "Continue seamlessly."
        }
        val user = if (g) buildString {
            append("BUCH: ").append(p.displayTitle).append('\n')
            append("HANDLUNG (kurz): ").append(trim(p.premise, 700)).append('\n')
            append("FIGUREN:\n").append(trim(p.characters, 1100)).append('\n')
            append("BISHER:\n").append(recap(p, step.chapter)).append('\n')
            if (p.facts.isNotBlank()) append("FESTE FAKTEN:\n").append(tail(p.facts, 600)).append('\n')
            append("KAPITEL ${step.chapter + 1} von ${p.chapters.size}: ${c.title}\nPLAN DES KAPITELS: ${trim(c.plan, 600)}\n")
            if (previous.isNotEmpty()) append("ENDE DES BISHERIGEN KAPITELTEXTS:\n").append(previous).append('\n')
            append("AUFGABE: Schreibe Teil ${step.part} von ${step.parts} dieses Kapitels (etwa $words Wörter). $task")
        } else buildString {
            append("BOOK: ").append(p.displayTitle).append('\n')
            append("PLOT (short): ").append(trim(p.premise, 700)).append('\n')
            append("CHARACTERS:\n").append(trim(p.characters, 1100)).append('\n')
            append("SO FAR:\n").append(recap(p, step.chapter)).append('\n')
            if (p.facts.isNotBlank()) append("FIXED FACTS:\n").append(tail(p.facts, 600)).append('\n')
            append("CHAPTER ${step.chapter + 1} of ${p.chapters.size}: ${c.title}\nCHAPTER PLAN: ${trim(c.plan, 600)}\n")
            if (previous.isNotEmpty()) append("END OF THE CHAPTER TEXT SO FAR:\n").append(previous).append('\n')
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
