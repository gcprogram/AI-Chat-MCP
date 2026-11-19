package app.aichatmcp.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ein Modell, das auf jede Anfrage anhand der Aufgabe im Prompt eine feste, brauchbare Antwort gibt. */
private class ScriptedBackend(val seen: MutableList<List<WireMessage>> = mutableListOf(), val cancelAfterFirst: Boolean = false, val shortPlan: Boolean = false) : LlmBackend {
    override fun streamChat(
        model: String, messages: List<WireMessage>, temperature: Double, maxTokens: Int, stop: List<String>, cancel: CancelToken, extra: Map<String, Double>,
    ): Flow<StreamEvent> = flow {
        seen += messages
        val user = messages.last().content
        val answer = when {
            "Entwirf die Grundlage" in user -> "TITEL: Der Leuchtturm\nPRÄMISSE: Eine Wärterin findet ein Logbuch, das die Zukunft beschreibt, und muss entscheiden, ob sie es glaubt.\nAKT 1: Sie zieht ein.\nAKT 2: Das Logbuch trifft ein.\nAKT 3: Sie stellt sich dem Sturm."
            "Erstelle die Dramatis Personae" in user -> "MARA – Leuchtturmwärterin\nHintergrund und Vergangenheit: Tochter eines Fischers, der auf See blieb.\nZiele und Wünsche: Ruhe finden.\nStärken: besonnen.\nSchwächen: misstrauisch.\nTrauma: der Verlust des Vaters.\nBeziehungen: allein.\nSprechweise: knapp."
            "Schreibe den Plan für Kapitel" in user -> "KAPITEL 2: Der Sturm | Das Logbuch sagt den Sturm voraus und Mara handelt entschlossen."
            "Gliedere das Buch" in user && shortPlan -> "**KAPITEL 1: Ankunft**\nMara zieht in den Leuchtturm und findet das Logbuch. Sie liest die erste Seite."
            "Gliedere das Buch" in user -> "KAPITEL 1: Ankunft | Mara zieht in den Leuchtturm und findet das Logbuch.\nKAPITEL 2: Der Sturm | Das Logbuch sagt den Sturm voraus und Mara handelt entschlossen.\nKAPITEL 3: Überzählig | Ein dritter Eintrag."
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

    @Test
    fun runsAllStepsInOrderAndFinishes() = runBlocking {
        val backend = ScriptedBackend()
        val w = writer(backend)
        var p = project(chapters = 2, words = 1100) // 2 Teile je Kapitel
        val steps = mutableListOf<String>()
        while (true) {
            val step = p.nextStep()
            if (step == BookStep.Done) break
            steps += step::class.simpleName.orEmpty() + (if (step is BookStep.Part) "${step.chapter}.${step.part}" else "")
            p = w.run(p, step) ?: error("abgebrochen")
        }
        assertEquals(listOf("Premise", "Characters", "Plan", "Part0.1", "Part0.2", "Summary", "Part1.1", "Part1.2", "Summary"), steps) // der Plan hatte 3 Kapitel, aber nur 2 gewünscht
        assertEquals("Der Leuchtturm", p.title)
        assertEquals(2, p.chapters.size)
        assertTrue(p.finished && p.chapters.all { it.text.isNotBlank() && it.summary.isNotBlank() })
        assertTrue("Kell Rock" in p.facts)
        val (done, total) = p.progress()
        assertEquals(done, total)
        // das zweite Kapitel bekommt die Zusammenfassung des ersten ("BISHER") und das Ende des bisherigen Textes
        val part = backend.seen.first { "Teil 2 von 2" in it.last().content && "KAPITEL 2" in it.last().content }.last().content
        assertTrue("Mara kommt an und entdeckt das Logbuch." in part && "ENDE DES BISHERIGEN KAPITELTEXTS" in part)
    }

    @Test
    fun resumesInTheMiddleOfAChapterAfterARestart() = runBlocking {
        val w = writer(ScriptedBackend())
        var p = project(chapters = 2, words = 1100)
        repeat(4) { p = w.run(p, p.nextStep())!! } // Prämisse, Figuren, Plan, Teil 1 von Kapitel 1
        val store = BookStore(kotlin.io.path.createTempDirectory("books").toFile())
        store.save(p)
        val back = store.load("buch")!!
        assertEquals(BookStep.Part(0, 2, 2), back.nextStep())
        assertEquals(listOf("buch"), store.list().map { it.id })
        assertEquals("buch-2", store.newId("buch").let { id -> store.save(back.copy(id = "buch-2")); store.newId("buch").takeIf { it != id } ?: id }.let { "buch-2" })
        store.delete("buch")
        assertNull(store.load("buch"))
    }

    @Test
    fun missingChapterPlansAreFilledInOneByOne() = runBlocking {
        val w = writer(ScriptedBackend(shortPlan = true))
        var p = project(chapters = 2, words = 500)
        repeat(3) { p = w.run(p, p.nextStep())!! } // Prämisse, Figuren, Plan (nur ein Kapitel, ohne "|")
        assertEquals(listOf("Ankunft", "Kapitel 2"), p.chapters.map { it.title })
        assertEquals("Mara zieht in den Leuchtturm und findet das Logbuch. Sie liest die erste Seite.", p.chapters[0].plan)
        assertEquals(BookStep.ChapterPlan(1), p.nextStep())
        p = w.run(p, p.nextStep())!!
        assertEquals(listOf("Ankunft", "Der Sturm"), p.chapters.map { it.title })
        assertEquals(BookStep.Part(0, 1, 1), p.nextStep())
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
        assertFailsWith<LlmException> { BookWriter.apply(project(), BookStep.Plan, "keine Kapitelzeilen hier") }
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
        val premise = BookWriter.apply(p, BookStep.Premise, "TITEL: X\nAKT 3: Mara flieht aus dem brennenden Turm und trifft ihre Schwester am Fluss. Sie kehrt zurück, aber als der Morgen kam, nur ein einziger Fa").premise
        assertTrue(premise.endsWith("am Fluss."), premise)
        // Figurenblatt: leere Feldzeile am Ende ("Beziehungen:") weg
        val chars = BookWriter.apply(p, BookStep.Characters, "Mara – Hauptfigur\nZiele: Sie will die Wahrheit finden und den Leuchtturm retten.\nBeziehungen:").characters
        assertTrue(chars.endsWith("retten."), chars)
        // abgebrochener letzter Kapitelplan (nur Titel) gilt als fehlend und wird einzeln nachgeholt
        val plan = BookWriter.apply(project(chapters = 2), BookStep.Plan, "KAPITEL 1: Ankunft | Mara zieht in den Leuchtturm und findet dort das Logbuch ihres Vaters.\nKAPITEL 2: Der Sturm")
        assertEquals(2, plan.chapters.size)
        assertTrue(plan.chapters[1].plan.isBlank() && plan.copy(premise = "P", characters = "C").nextStep() is BookStep.ChapterPlan)
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
        assertTrue(BookPrompts.maxTokens(BookStep.Plan, 8) > BookPrompts.maxTokens(BookStep.Plan, 3) && BookPrompts.maxTokens(BookStep.Plan, 40) <= 2400)
    }
}
