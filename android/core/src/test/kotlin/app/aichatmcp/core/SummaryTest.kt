package app.aichatmcp.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SummaryTest {
    private fun book(): String = buildString {
        for (c in 1..3) {
            append("Kapitel $c: Titel $c\n\n")
            repeat(if (c == 2) 3 else 1) { p -> append("Anna ging durch Teil ${p + 1} von Kapitel $c und traf Tom am Hafen. ".repeat(40)).append("\n\n") }
        }
    }

    /** Ein Modell, das auf jede Aufgabe eine kurze Antwort gibt und mitzählt. */
    private class Scripted(val seen: MutableList<String> = mutableListOf(), val fail: Boolean = false, val partAnswer: String? = null) {
        suspend fun ask(prompt: List<WireMessage>, max: Int): String? {
            val text = prompt.last().content
            seen += text
            if (fail) return null
            return when {
                "in exactly 6 sentences" in text -> "Eins. Zwei. Drei. Vier. Fünf. Sechs. Sieben. Acht."
                "about 600 to 800 words" in text -> "Ein langer Absatz über das ganze Buch."
                "Merge them" in text -> "Vereinte Zusammenfassung."
                else -> partAnswer ?: "Hier ist die Zusammenfassung:\n**Anna** trifft Tom."
            }
        }
    }

    @Test
    fun splitsABookIntoPartsWithTheirChapters() {
        val parts = Summaries.parts(book())
        assertTrue(parts.size >= 3, parts.size.toString())
        assertEquals(listOf("Kapitel 1: Titel 1", "Kapitel 2: Titel 2", "Kapitel 3: Titel 3"), Summaries.chapters(parts))
        assertEquals("Kapitel 2", Summaries.chapterOf("Kapitel 2 (1/3)"))
        // ohne Überschriften ist jeder Teil ein eigenes "Abschnitt n"
        val flat = Summaries.parts("Ein Satz über das Meer und den Wind. ".repeat(400))
        assertTrue(flat.size >= 3 && Summaries.chapters(flat).size == flat.size)
        assertEquals(Summaries.key("a.txt", "x"), Summaries.key("a.txt", "x"))
        assertFalse(Summaries.key("a.txt", "x") == Summaries.key("b.txt", "x"))
    }

    @Test
    fun runsAllLevelsAndReusesWhatIsDone() = runBlocking {
        val parts = Summaries.parts(book())
        val chapters = Summaries.chapters(parts).size
        val model = Scripted()
        val saved = mutableListOf<SummaryData>()
        var data = Summaries.run(parts, SummaryLevel.SHORT, "Deutsch", SummaryData(), 8000, model::ask, onData = { saved += it })
        assertEquals("Eins. Zwei. Drei. Vier. Fünf. Sechs.", data.short) // auf 6 Sätze gekürzt
        assertEquals(parts.size, data.parts.size)
        assertEquals(chapters, data.chapters.size)
        assertEquals("Anna trifft Tom.", data.parts.getValue(0)) // "Hier ist die Zusammenfassung:" und Markdown sind weg
        assertEquals("Vereinte Zusammenfassung.", data.chapters["Kapitel 2: Titel 2"]) // drei Teile zu einem Kapitel vereint
        assertTrue(saved.size >= parts.size) // nach jeder Anfrage wird gespeichert
        // zweite Stufe: Teile und Kapitel sind schon da, nur noch die ausführliche Anfrage
        val before = model.seen.size
        data = Summaries.run(parts, SummaryLevel.DETAILED, "Deutsch", data, 8000, model::ask)
        assertEquals(before + 1, model.seen.size)
        assertEquals("Ein langer Absatz über das ganze Buch.", data.detailed)
        // Kapitel für Kapitel braucht keine weitere Anfrage
        val third = Summaries.run(parts, SummaryLevel.CHAPTERS, "Deutsch", data, 8000, model::ask)
        assertEquals(before + 1, model.seen.size)
        assertTrue(third.perChapter.startsWith("Kapitel 1: Titel 1\nAnna trifft Tom.") && "Kapitel 3: Titel 3" in third.perChapter)
        assertEquals(0, Summaries.requests(parts, SummaryLevel.DETAILED, third))
        assertTrue(model.seen.first().contains("Answer in Deutsch"))
    }

    @Test
    fun longChapterSummariesAreCondensedBeforeTheFinalRequest() = runBlocking {
        val parts = (1..6).map { BookPart("Kapitel $it", "Text $it. ".repeat(50)) }
        val model = Scripted(partAnswer = "Anna trifft Tom und geht mit ihm zum Hafen.")
        // das Budget (240 Zeichen) ist kleiner als sechs Kapitelzusammenfassungen: es wird zuerst gruppenweise vereint
        val data = Summaries.run(parts, SummaryLevel.SHORT, "English", SummaryData(), 240, model::ask)
        assertTrue(model.seen.count { "Merge them" in it } >= 1, model.seen.map { it.take(60) }.toString())
        assertTrue(data.short.isNotBlank(), "short=" + data.short)
        assertEquals("Ein Satz. Zwei Sätze.", Summaries.limitSentences("Ein Satz. Zwei Sätze.", 6))
    }

    @Test
    fun aModelWithoutAnswerIsAnErrorButKeepsWhatIsDone() = runBlocking {
        val parts = Summaries.parts(book())
        val good = Scripted()
        val first = Summaries.run(parts.take(1), SummaryLevel.CHAPTERS, "Deutsch", SummaryData(), 8000, good::ask)
        assertEquals(1, first.parts.size)
        val bad = Scripted(fail = true)
        assertFailsWith<SummaryException> { Summaries.run(parts, SummaryLevel.SHORT, "Deutsch", first, 8000, bad::ask) }
        assertEquals(1, first.parts.size) // der Stand vor dem Fehler bleibt (das Ergebnis ist unveränderlich)
    }

    @Test
    fun cleansTheAnswersOfTheModel() {
        assertEquals("Anna reist ab.", Summaries.clean("Sure, here is the summary:\n\n**Anna** reist ab."))
        assertEquals("Satz.", Summaries.clean("Satz."))
    }

    @Test
    fun theEndOfTheBookIsSpokenInTheLanguageOfTheBook() {
        assertEquals("Ende des Buches.", BookEnd.phrase("de-DE"))
        assertEquals("End of the book.", BookEnd.phrase("en-US"))
        assertEquals("Fin du livre.", BookEnd.phrase("fr"))
        assertEquals("End of the book.", BookEnd.phrase(null))
        assertEquals("End of the book.", BookEnd.phrase("xx-XX"))
    }

    @Test
    fun aPictureIsIntroducedInTheLanguageOfTheBookAndTranslatedByAPrompt() {
        assertEquals("An dieser Stelle gibt es ein Bild, welches Folgendes zeigt:", BookPicture.intro("de-DE"))
        assertTrue(BookPicture.intro("fr-FR").startsWith("À cet endroit"))
        assertTrue(BookPicture.intro("zz").startsWith("At this point"))
        val prompt = BookPicture.translatePrompt("A big harbour with ships.", "Deutsch")[0].content
        assertTrue("into Deutsch" in prompt && "A big harbour with ships." in prompt && "translation only" in prompt)
    }
}
