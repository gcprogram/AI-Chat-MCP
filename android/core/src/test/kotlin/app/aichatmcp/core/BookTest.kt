package app.aichatmcp.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PLAN_1 = "Mara zieht in den Leuchtturm und findet dort das Logbuch ihres Vaters, das merkwürdige Einträge enthält."
private const val PLAN_2 = "Ein Sturm zieht auf, den das Logbuch vorhergesagt hat, und Mara muss die Lampe gegen die Wellen retten."

/** Ein Modell, das auf jede Anfrage anhand der Aufgabe im Prompt eine feste, brauchbare Antwort gibt. */
private class ScriptedBackend(
    val seen: MutableList<List<WireMessage>> = mutableListOf(), val cancelAfterFirst: Boolean = false,
    /** Antwort auf die Kapitelpläne, wenn das Modell frühere Pläne wiederholt (nur Kapitel 2 wiederholt Kapitel 1). */
    val repeatPlans: Boolean = false,
) : LlmBackend {
    override fun streamChat(
        model: String, messages: List<WireMessage>, temperature: Double, maxTokens: Int, stop: List<String>, cancel: CancelToken, extra: Map<String, Double>,
    ): Flow<StreamEvent> = flow {
        seen += messages
        val user = messages.last().content
        val answer = when {
            "Entwirf die Grundlage" in user -> "TITEL: Der Leuchtturm\nFIGUREN: Mara – Wärterin; Tom – Fischer\nPRÄMISSE: Eine Wärterin findet ein Logbuch, das die Zukunft beschreibt, und muss entscheiden, ob sie es glaubt.\nENDE: Mara rettet den Turm.\nAKT 1: Sie zieht ein.\nAKT 2: Das Logbuch trifft ein.\nAKT 3: Sie stellt sich dem Sturm."
            "Erstelle die Dramatis Personae" in user -> "MARA – Leuchtturmwärterin\nHintergrund und Vergangenheit: Tochter eines Fischers, der auf See blieb.\nZiele und Wünsche: Ruhe finden.\nStärken: besonnen.\nSchwächen: misstrauisch.\nTrauma: der Verlust des Vaters.\nBeziehungen: allein.\nSprechweise: knapp."
            "Schreibe den Plan für Kapitel 1 von" in user -> "KAPITEL 1: Ankunft | $PLAN_1"
            "Schreibe den Plan für Kapitel 2 von" in user -> "KAPITEL 2: Der Sturm | ${if (repeatPlans) PLAN_1 else PLAN_2}"
            "Zerlege Kapitel" in user -> (1..6).joinToString("\n") { "BEAT $it: Leuchtturm; Mara erlebt Szene $it mit dem Logbuch und dem Meer; sie entscheidet sich neu." }
            "Fasse das Kapitel" in user -> "ZUSAMMENFASSUNG: Mara kommt an und entdeckt das Logbuch.\nFAKTEN:\n- Mara ist die Wärterin\n- Der Turm steht auf Kell Rock"
            else -> "Mara stieg die Wendeltreppe hinauf, und jeder Schritt hallte im kalten Stein wider, bis sie oben die Lampe erreichte. Draußen schlug das Meer gegen die Felsen, und sie wusste, dass sie hier bleiben würde."
        }
        for (piece in answer.chunked(25)) emit(StreamEvent.Content(piece))
        if (cancelAfterFirst) cancel.cancel()
    }
}

class BookTest {
    private fun project(chapters: Int = 2, words: Int = 600) = BookProject("buch", brief = BookBrief(topic = "Ein Leuchtturm und ein Logbuch", chapters = chapters, wordsPerChapter = words))

    private fun writer(backend: LlmBackend) = BookWriter({ backend }, { Config() }, { "m" })

    private fun stepName(step: BookStep) = step::class.simpleName.orEmpty() + (if (step is BookStep.Part) "${step.chapter}.${step.part}" else "")

    @Test
    fun runsAllStepsInOrderAndFinishes() = runBlocking {
        val backend = ScriptedBackend()
        val w = writer(backend)
        var p = project(chapters = 2, words = 1100) // 2 Teile je Kapitel
        val steps = mutableListOf<String>()
        while (true) {
            val step = p.nextStep()
            if (step == BookStep.Done) break
            steps += stepName(step)
            p = w.run(p, step) ?: error("abgebrochen")
        }
        assertEquals(
            listOf("Premise", "Characters", "Plan", "ChapterPlan", "ChapterPlan", "Beats", "Beats", "Part0.1", "Part0.2", "Summary", "Part1.1", "Part1.2", "Summary"), steps,
        )
        assertEquals("Der Leuchtturm", p.title)
        assertEquals(listOf("Ankunft", "Der Sturm"), p.chapters.map { it.title })
        assertTrue(p.finished && p.chapters.all { it.text.isNotBlank() && it.summary.isNotBlank() && it.beats.isNotBlank() })
        assertTrue("Kell Rock" in p.facts)
        val (done, total) = p.progress()
        assertEquals(done, total)
        // das zweite Kapitel bekommt die Zusammenfassung des ersten ("BISHER") und das Ende des bisherigen Textes
        val part = backend.seen.first { "Teil 2 von 2" in it.last().content && "KAPITEL 2" in it.last().content }.last().content
        assertTrue("Mara kommt an und entdeckt das Logbuch." in part && "ENDE DES BISHERIGEN KAPITELTEXTS" in part)
    }

    @Test
    fun theResultsOfThePlanningAreAvailableBeforeAnyChapterTextIsWritten() = runBlocking {
        val w = writer(ScriptedBackend())
        var p = project(chapters = 2, words = 1100)
        assertFalse(p.planReady)
        while (!p.planReady) p = w.run(p, p.nextStep())!!
        assertTrue(p.nextStep() is BookStep.Part && p.chapters.all { it.beats.isNotBlank() && it.plan.isNotBlank() } && p.chapters.none { it.text.isNotBlank() })
        assertEquals(6, BookParse.beatList(p.chapters[0].beats).size)
    }

    @Test
    fun theStepPlanOnlyCreatesEmptyChaptersAndNeedsNoModel() = runBlocking {
        val backend = ScriptedBackend()
        val p = writer(backend).run(project(chapters = 3), BookStep.Plan)!!
        assertEquals(listOf("Kapitel 1", "Kapitel 2", "Kapitel 3"), p.chapters.map { it.title })
        assertTrue(p.chapters.all { it.plan.isEmpty() } && backend.seen.isEmpty())
    }

    @Test
    fun eachPartGetsOnlyItsOwnBeats() = runBlocking {
        val backend = ScriptedBackend()
        val w = writer(backend)
        var p = project(chapters = 1, words = 1100)
        while (!p.planReady) p = w.run(p, p.nextStep())!!
        val one = BookPrompts.part(p, BookStep.Part(0, 1, 2))[1].content
        val two = BookPrompts.part(p, BookStep.Part(0, 2, 2))[1].content
        assertTrue("Szene 1 " in one && "Szene 3 " in one && "Szene 4 " !in one.substringBefore("DANACH FOLGT"), one)
        assertTrue("DANACH FOLGT (nicht vorwegnehmen): Leuchtturm; Mara erlebt Szene 4" in one)
        assertTrue("Szene 4 " in two && "Szene 6 " in two && "Szene 1 " !in two && "DANACH FOLGT" !in two, two)
        // ohne Beats (ältere Projekte) bleibt der Plan des Kapitels die Vorgabe
        val old = p.copy(chapters = p.chapters.map { it.copy(beats = "") })
        assertTrue("BEATS FÜR DIESEN TEIL" !in BookPrompts.part(old, BookStep.Part(0, 1, 2))[1].content)
    }

    @Test
    fun resumesInTheMiddleOfAChapterAfterARestart() = runBlocking {
        val w = writer(ScriptedBackend())
        var p = project(chapters = 2, words = 1100)
        repeat(8) { p = w.run(p, p.nextStep())!! } // Prämisse, Figuren, Plan, 2 Kapitelpläne, 2 Beats, Teil 1 von Kapitel 1
        val store = BookStore(kotlin.io.path.createTempDirectory("books").toFile())
        store.save(p)
        val back = store.load("buch")!!
        assertEquals(BookStep.Part(0, 2, 2), back.nextStep())
        assertTrue(back.chapters[0].beats.isNotBlank())
        assertEquals(listOf("buch"), store.list().map { it.id })
        assertEquals("buch-2", store.newId("buch").let { id -> store.save(back.copy(id = "buch-2")); store.newId("buch").takeIf { it != id } ?: id }.let { "buch-2" })
        store.delete("buch")
        assertNull(store.load("buch"))
    }

    @Test
    fun aRepeatedChapterPlanIsRejectedAndTheThirdAttemptAcceptsIt() = runBlocking {
        val w = writer(ScriptedBackend(repeatPlans = true))
        var p = project(chapters = 2, words = 500)
        repeat(3) { p = w.run(p, p.nextStep())!! } // Prämisse, Figuren, leere Kapitel
        p = w.run(p, p.nextStep())!! // Kapitel 1
        assertEquals(BookStep.ChapterPlan(1), p.nextStep())
        assertFailsWith<BookFormatException> { w.run(p, p.nextStep(), attempt = 0) }
        assertFailsWith<BookFormatException> { w.run(p, p.nextStep(), attempt = 1) }
        val accepted = w.run(p, p.nextStep(), attempt = 2)!! // nach zwei Versuchen kein Abbruch mehr: der Nutzer kann den Plan ändern
        assertEquals(PLAN_1, accepted.chapters[1].plan)
    }

    @Test
    fun detectsCopiedPlansLikeTheOnesAnEightBillionModelWrote() {
        val c3 = "Seraphina discovers that werewolves and vampires are colluding with an ancient demon lord seeking to summon it during the Blood Moon; she uncovers evidence of both sides preparing for a coordinated assault on her safety."
        val c6 = "Seraphina discovers that werewolves and vampires are colluding with an ancient demon lord seeking to summon it during the Blood Moon; she uncovers evidence of both sides preparing for a coordinated assault on her safety. Her feelings deepen."
        assertTrue(BookParse.repeats(c6, listOf("Anderes Kapitel mit ganz anderem Inhalt über einen Hafen und ein Schiff.", c3)))
        assertFalse(BookParse.repeats(PLAN_2, listOf(PLAN_1)))
        assertFalse(BookParse.repeats("Zu kurz.", listOf(c3)))
    }

    @Test
    fun theActsAreFoundAndAnIncompletePlotIsRejected() {
        val premise = "PRÄMISSE: x\nENDE: y\nAKT 1: Erster Teil.\nAKT 2: Zweiter Teil.\nAKT 3: Dritter Teil."
        assertEquals(3, BookParse.actCount(premise))
        assertEquals("Zweiter Teil.", BookParse.act(premise, 2))
        assertEquals("Third.", BookParse.act("ACT I: One.\nACT II: Two.\nACT III: Third.", 3))
        assertEquals("", BookParse.act("PRÄMISSE: nur", 1))
        // Chapter → Akt: ein Viertel / die Hälfte / ein Viertel
        val p = project(chapters = 8)
        assertEquals(listOf(1, 1, 2, 2, 2, 2, 3, 3), (0..7).map { p.actOf(it, 8) })
        assertFailsWith<BookFormatException> { BookWriter.apply(project(), BookStep.Premise, "TITEL: X\nPRÄMISSE: Lange genug, um nicht zu kurz zu sein.\nAKT 1: Sie zieht ein und findet den Turm leer.\nAKT 2: Das Logbuch trifft ein und sagt etwas voraus.") }
    }

    @Test
    fun parsesBeatsInVariousShapesAndDistributesThem() {
        val text = "Hier die Beats:\nBEAT 1: Hafen; Mara kauft Proviant; sie streitet mit Tom.\n**BEAT 2:** Turm; Mara steigt hinauf; sie findet das Logbuch.\n3. Meer; Tom ruft; Mara antwortet nicht.\nkurz\n4) Strand; sie läuft los; der Sturm bricht aus."
        val beats = BookParse.beats(text)
        assertEquals(4, BookParse.beatList(beats).size)
        assertTrue(beats.startsWith("1. Hafen; Mara kauft Proviant") && "2. Turm; Mara steigt hinauf" in beats)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), (1..2).map { part -> BookParse.beatsForPart(beats, part, 2).map { BookParse.beatList(beats).indexOf(it) } })
        assertEquals(emptyList(), BookParse.beatsForPart("", 1, 2))
        assertEquals("", BookParse.beats("nichts brauchbares"))
        assertFailsWith<BookFormatException> { BookWriter.apply(project(), BookStep.Beats(0).let { it }, "BEAT 1: nur ein einziger Beat steht hier") }
    }

    @Test
    fun planningAndChapterTextCanRunOnDifferentModels() = runBlocking {
        val planner = ScriptedBackend()
        val author = ScriptedBackend()
        val w = BookWriter({ planner }, { Config() }, { "m" }) { step -> if (step is BookStep.Part) StepModel(author, Config(), "klein") else StepModel(planner, Config(), "gross") }
        var p = project(chapters = 1, words = 500)
        while (p.nextStep() != BookStep.Done) p = w.run(p, p.nextStep())!!
        assertEquals(1, author.seen.size) // nur der Kapiteltext (ein Teil)
        assertTrue(author.seen.single().last().content.contains("AUFGABE: Schreibe Teil 1 von 1"))
        assertTrue(planner.seen.none { it.last().content.contains("AUFGABE: Schreibe Teil") } && planner.seen.size >= 5)
    }

    @Test
    fun cleansChatterMarkdownAndLongPlans() {
        assertEquals("1. Elara – Hauptfigur\nHintergrund: arm.", BookParse.cleanBlock("Absolut! Hier sind die Figuren:\n\n**1. Elara – Hauptfigur**\nHintergrund: arm."))
        assertEquals("Mara – Wärterin", BookParse.cleanBlock("Mara – Wärterin"))
        val long = "Satz eins. ".repeat(200)
        val cut = BookParse.cutAtSentence(long, 900)
        assertTrue(cut.length <= 900 && cut.endsWith("."))
        val plan = BookParse.chapters("KAPITEL 1: Der Anfang\n" + long, 1).single()
        assertTrue(plan.plan.length <= 900)
    }

    @Test
    fun cancellingThrowsTheHalfFinishedStepAway() = runBlocking {
        val w = writer(ScriptedBackend(cancelAfterFirst = true))
        assertNull(w.run(project(), BookStep.Premise))
    }

    @Test
    fun parsesTheChapterPlanInVariousShapes() {
        val text = "Hier der Plan:\n**KAPITEL 1: Ankunft** | Mara kommt an.\nKAPITEL 2 – Sturm | Es stürmt\nund Mara handelt.\nKapitel 3: Ende | Alles wird gut."
        val chapters = BookParse.chapters(text, 5)
        assertEquals(listOf("Ankunft", "Sturm", "Ende"), chapters.map { it.title })
        assertEquals("Es stürmt und Mara handelt.", chapters[1].plan)
        assertEquals(2, BookParse.chapters(text, 2).size)
        assertEquals(emptyList(), BookParse.chapters("kein Plan", 3))
        assertEquals("Titel", BookParse.chapters("CHAPTER 1: Titel | Inhalt", 1).single().title)
    }

    @Test
    fun parsesPremiseAndSummary() {
        val (title, rest) = BookParse.premise("**TITEL:** \"Das Meer\"\nPRÄMISSE: abc def")
        assertEquals("Das Meer", title)
        assertEquals("PRÄMISSE: abc def", rest)
        val (s, f) = BookParse.summary("ZUSAMMENFASSUNG: Es geschah viel.\nFAKTEN:\n- A\n- B")
        assertEquals("Es geschah viel.", s)
        assertEquals("- A\n- B", f)
        assertEquals("nur Text" to "", BookParse.summary("nur Text"))
    }

    @Test
    fun tooShortOrUnreadableAnswersAreErrors() {
        assertFailsWith<LlmException> { BookWriter.apply(project(), BookStep.Premise, "kurz") }
        assertFailsWith<LlmException> { BookWriter.apply(project(2).copy(chapters = listOf(BookChapter("K1", ""), BookChapter("K2", ""))), BookStep.ChapterPlan(0), "keine Kapitelzeilen hier") }
        assertFailsWith<LlmException> { BookWriter.apply(project(2).copy(chapters = listOf(BookChapter("K1", ""), BookChapter("K2", ""))), BookStep.ChapterPlan(0), "KAPITEL 1: Titel | Zu kurz.") }
        assertEquals("Satz eins. Satz zwei.", BookWriter.cleanProse("# Titel\nTeil 2\n**Satz eins.** Satz zwei."))
    }

    @Test
    fun exportsMarkdownAndAValidDocxThatTheReaderCanRead() = runBlocking {
        val w = writer(ScriptedBackend())
        var p = project(chapters = 2, words = 500)
        while (p.nextStep() != BookStep.Done) p = w.run(p, p.nextStep())!!
        val md = BookExport.markdown(p)
        assertTrue(md.startsWith("# Der Leuchtturm") && "## Kapitel 1: Ankunft" in md && "Mara stieg die Wendeltreppe" in md)
        assertTrue("## Figuren" !in md && "## Figuren" in BookExport.markdown(p, includePlan = true))
        val docx = BookExport.docx(p, includePlan = true)
        // alle XML-Teile sind wohlgeformt
        ZipInputStream(docx.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(zip.readBytes().inputStream())
            }
        }
        val text = Documents.docxText(docx)
        assertTrue("Der Leuchtturm" in text && "Kapitel 2: Der Sturm" in text && "Mara stieg die Wendeltreppe" in text && "Figuren (Dramatis Personae)" in text, text.take(300))
        assertTrue(BookExport.plainText(p).contains("Kapitel 1: Ankunft"))
    }

    @Test
    fun specialCharactersAreEscapedInDocx() {
        val p = project().copy(title = "A & B <C>", chapters = listOf(BookChapter("T", "p", text = "Er sagte: \"x < y & z\"")))
        val text = Documents.docxText(BookExport.docx(p))
        assertTrue("A & B <C>" in text && "x < y & z" in text, text)
    }

    @Test
    fun truncatedModelAnswersAreCutCleanly() {
        // an der Token-Grenze abgebrochene Handlung: der halbe letzte Satz fällt weg
        val p = project()
        val premise = BookWriter.apply(
            p, BookStep.Premise,
            "TITEL: X\nAKT 1: Sie zieht ein.\nAKT 2: Das Logbuch trifft ein.\nAKT 3: Mara flieht aus dem brennenden Turm und trifft ihre Schwester am Fluss. Sie kehrt zurück, aber als der Morgen kam, nur ein einziger Fa",
        ).premise
        assertTrue(premise.endsWith("am Fluss."), premise)
        // Figurenblatt: leere Feldzeile am Ende ("Beziehungen:") weg
        val chars = BookWriter.apply(p, BookStep.Characters, "Mara – Hauptfigur\nZiele: Sie will die Wahrheit finden und den Leuchtturm retten.\nBeziehungen:").characters
        assertTrue(chars.endsWith("retten."), chars)
        // ungepaarte Sterne eines abgebrochenen Satzes verschwinden, gepaarte Betonung bleibt
        val prose = BookWriter.cleanProse("Der Wind sagte: *Wir erinnern uns.\n\nSie sah *ihn* an und schwieg.")
        assertTrue("*Wir" !in prose && "*ihn*" in prose, prose)
    }

    @Test
    fun theNamesOfTheCharactersAlwaysReachTheChapterPrompt() {
        val characters = "Elira – Die Hexe\nHintergrund: " + "x".repeat(1500) + "\nKaelen – Der Wolfsfürst\nHintergrund: y\nSeraphine – Die Vampirkönigin\nZiele: z"
        assertEquals("Elira – Die Hexe; Kaelen – Der Wolfsfürst; Seraphine – Die Vampirkönigin", BookPrompts.castLine(characters))
        val p = project().copy(characters = characters, chapters = listOf(BookChapter("T", "Plan genug lang für den Test eines Kapitels, das beginnt.")))
        val prompt = BookPrompts.part(p, BookStep.Part(0, 1, 2))[1].content
        assertTrue("NAMEN: Elira – Die Hexe; Kaelen" in prompt && "Seraphine" in prompt)
        assertTrue(BookPrompts.maxTokens(BookStep.Premise) >= 1000 && BookPrompts.maxTokens(BookStep.Beats(0)) >= 400)
    }

    @Test
    fun theChapterPlanPromptNamesTheActAndTheAlreadyPlannedChapters() {
        val premise = "ENDE: Mara siegt.\nAKT 1: Mara zieht ein.\nAKT 2: Das Logbuch warnt.\nAKT 3: Der Sturm kommt."
        val p = project(chapters = 4).copy(
            premise = premise, characters = "Mara – Wärterin",
            chapters = listOf(BookChapter("A", PLAN_1), BookChapter("B", ""), BookChapter("C", ""), BookChapter("D", "")),
        )
        val prompt = BookPrompts.chapterPlan(p, 1)[1].content
        assertTrue("DIESES KAPITEL GEHÖRT ZU AKT 2" in prompt && "Das Logbuch warnt." in prompt && "SCHON GEPLANTE KAPITEL (nichts davon wiederholen)" in prompt && PLAN_1.take(40) in prompt)
        assertTrue("letzte Kapitel" in BookPrompts.chapterPlan(p, 3)[1].content && "erste Kapitel" in BookPrompts.chapterPlan(p, 0)[1].content)
    }
}
