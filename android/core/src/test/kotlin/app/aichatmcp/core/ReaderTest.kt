package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderTest {
    private class Spoken(val text: String, val id: String, val language: String?, val flush: Boolean)

    private class Fakes : ReaderOutput, ReaderPictures, ReaderEvents {
        val spoken = mutableListOf<Spoken>()
        var stops = 0
        val prefetched = mutableListOf<String>()
        val pending = mutableListOf<Pair<ReadItem.Picture, (String?) -> Unit>>()
        var cancels = 0
        val states = mutableListOf<ReaderState>()
        val positions = mutableListOf<Int>()
        val texts = mutableListOf<Pair<String, Boolean>>()
        val describing = mutableListOf<Boolean>()
        val problems = mutableListOf<String>()
        var finished = 0

        override fun speak(text: String, id: String, language: String?, flush: Boolean) { spoken += Spoken(text, id, language, flush) }
        override fun stopSpeaking() { stops++ }
        override fun describe(picture: ReadItem.Picture, done: (String?) -> Unit) { pending += picture to done }
        override fun prefetch(picture: ReadItem.Picture) { prefetched += picture.ref }
        override fun cancel() { cancels++ }
        override fun onState(state: ReaderState) { states += state }
        override fun onPosition(index: Int, total: Int) { positions += index }
        override fun onSpoken(text: String, fromImage: Boolean) { texts += text to fromImage }
        override fun onDescribing(active: Boolean) { describing += active }
        override fun onProblem(message: String) { problems += message }
        override fun onFinished() { finished++ }
    }

    private fun speech(vararg texts: String) = texts.map { ReadItem.Speech(it) }

    private fun reader(items: List<ReadItem>, starts: List<Int> = listOf(0), f: Fakes = Fakes(), start: Int = 0) =
        Reader(ReadPlan(items, starts), f, f, f, textLanguage = "de-DE", startIndex = start) to f

    @Test
    fun playSpeaksTheCurrentSectionAndQueuesTheNextOne() {
        val (r, f) = reader(speech("eins", "zwei", "drei"))
        r.play()
        assertEquals(ReaderState.PLAYING, r.state)
        assertEquals(listOf("eins" to true, "zwei" to false), f.spoken.map { it.text to it.flush })
        assertEquals("de-DE", f.spoken[0].language)
        assertEquals(listOf("eins" to false), f.texts) // angesagt wird nur, was wirklich dran ist
        r.utteranceDone(f.spoken[0].id)
        assertEquals(1, r.index)
        assertEquals(listOf("eins", "zwei", "drei"), f.spoken.map { it.text }) // drei wird jetzt angehängt
        assertEquals(listOf("eins" to false, "zwei" to false), f.texts)
    }

    @Test
    fun pauseKeepsThePositionAndPlayRestartsTheSection() {
        val (r, f) = reader(speech("a", "b", "c"))
        r.play()
        r.utteranceDone(f.spoken[0].id)
        r.pause()
        assertEquals(ReaderState.PAUSED, r.state)
        assertEquals(1, r.index)
        assertTrue(f.stops >= 1)
        f.spoken.clear()
        r.play()
        assertEquals("b" to true, f.spoken[0].text to f.spoken[0].flush)
    }

    @Test
    fun staleCallbacksAreIgnored() {
        val (r, f) = reader(speech("a", "b", "c"))
        r.play()
        val oldId = f.spoken[0].id
        r.pause()
        r.play() // neue Runde
        r.utteranceDone(oldId) // alter Abschnitt meldet sich verspätet
        assertEquals(0, r.index)
        r.stop()
        r.utteranceDone(f.spoken.last().id)
        assertEquals(ReaderState.STOPPED, r.state)
    }

    @Test
    fun seekingWhilePlayingSpeaksTheTargetAndWhilePausedOnlyMovesTheMark() {
        val (r, f) = reader(speech("a", "b", "c", "d", "e"))
        r.play()
        f.spoken.clear()
        r.seekTo(3)
        assertEquals("d" to true, f.spoken[0].text to f.spoken[0].flush)
        assertEquals(3, r.index)
        r.pause()
        f.spoken.clear()
        r.previous()
        assertEquals(2, r.index)
        assertTrue(f.spoken.isEmpty())
        assertEquals(2, f.positions.last())
        r.play()
        assertEquals("c", f.spoken[0].text)
    }

    @Test
    fun jumpsGoByPagesOrTenSections() {
        val items = speech(*Array(30) { "s$it" })
        val paged = reader(items, starts = listOf(0, 8, 20)).first
        paged.seekTo(10)
        paged.jumpBack()
        assertEquals(0, paged.index) // Seite 2 -> Anfang von Seite 1
        paged.jumpForward()
        assertEquals(8, paged.index)
        paged.jumpForward()
        assertEquals(20, paged.index)
        val flat = reader(items).first
        flat.seekTo(15)
        flat.jumpBack()
        assertEquals(5, flat.index)
        flat.jumpForward()
        assertEquals(15, flat.index)
        flat.seekTo(25)
        flat.jumpForward()
        assertEquals(30, flat.index) // hinter dem Ende: fertig
        assertEquals(ReaderState.STOPPED, flat.state)
    }

    @Test
    fun theLastSectionFinishesAndReportsTheEnd() {
        val (r, f) = reader(speech("a", "b"))
        r.play()
        r.utteranceDone(f.spoken[0].id)
        r.utteranceDone(f.spoken.last { it.text == "b" }.id)
        assertEquals(ReaderState.STOPPED, r.state)
        assertEquals(1, f.finished)
        assertEquals(2, r.index)
        assertEquals(2, f.positions.last()) // Stelle hinter dem Ende: die Marke wird gelöscht
    }

    @Test
    fun pictureIsDescribedAfterTheParagraphThenTheTextContinues() {
        val items = listOf(ReadItem.Speech("vor dem Bild"), ReadItem.Picture("img1", "Hafen"), ReadItem.Speech("nach dem Bild"))
        val (r, f) = reader(items)
        r.play()
        assertEquals(listOf("img1"), f.prefetched) // schon beim Abschnitt davor bestellt
        assertEquals(listOf("vor dem Bild"), f.spoken.map { it.text }) // das Bild wird nicht angehängt
        r.utteranceDone(f.spoken[0].id)
        assertEquals(1, r.index)
        assertEquals(1, f.pending.size)
        assertEquals(listOf(true), f.describing)
        assertEquals(1, f.spoken.size) // während der Beschreibung ist Stille, nichts sonst läuft
        f.pending[0].second("Ein großer Hafen mit Schiffen.")
        assertEquals(listOf(true, false), f.describing)
        assertEquals("Ein großer Hafen mit Schiffen." to "en-US", f.spoken.last().text to f.spoken.last().language)
        assertEquals("Ein großer Hafen mit Schiffen." to true, f.texts.last())
        r.utteranceDone(f.spoken.last().id)
        assertEquals(2, r.index)
        assertEquals("nach dem Bild" to true, f.spoken.last().text to f.spoken.last().flush)
    }

    @Test
    fun decorativePictureIsSkippedWithoutDescribingOrReadingItsAltText() {
        val items = listOf(ReadItem.Speech("vor"), ReadItem.Picture("rahmen", "Rahmen", decorative = true), ReadItem.Speech("nach"))
        val (r, f) = reader(items)
        r.play()
        assertEquals(emptyList<String>(), f.prefetched)
        r.utteranceDone(f.spoken[0].id)
        assertEquals(emptyList<Pair<ReadItem.Picture, (String?) -> Unit>>(), f.pending)
        assertEquals(2, r.index)
        assertEquals("nach" to true, f.spoken.last().text to f.spoken.last().flush)
        assertTrue(f.spoken.none { it.text == "Rahmen" })
    }

    @Test
    fun aPictureRecognizedAsOrnamentLaterIsSkippedToo() {
        val (r, f) = reader(listOf(ReadItem.Picture("a", "Rahmenteil"), ReadItem.Speech("Text")))
        r.play()
        f.pending[0].second(ReaderPictures.SKIP)
        assertEquals(listOf(true, false), f.describing)
        assertEquals(listOf("Text"), f.spoken.map { it.text }) // weder SKIP noch der Ersatztext
        assertEquals(1, r.index)
    }

    @Test
    fun aRowOfOrnamentsAtTheEndFinishesReading() {
        val items = listOf(ReadItem.Speech("a"), ReadItem.Picture("x", "", true), ReadItem.Picture("y", "", true))
        val (r, f) = reader(items)
        r.play()
        r.utteranceDone(f.spoken[0].id)
        assertEquals(1, f.finished)
        assertEquals(ReaderState.STOPPED, r.state)
    }

    @Test
    fun atMostTwoPicturesAreOrderedAheadAndOrnamentsDoNotCount() {
        val items = listOf(
            ReadItem.Speech("s0"), ReadItem.Picture("o", "", true), ReadItem.Picture("p1", "A"), ReadItem.Picture("p2", "B"),
            ReadItem.Picture("p3", "C"), ReadItem.Speech("s5"),
        )
        val (r, f) = reader(items)
        r.play()
        assertEquals(listOf("p1", "p2"), f.prefetched) // p3 folgt später, nicht alle auf einmal
    }

    @Test
    fun pictureIsOrderedFiveSectionsAheadSoALargerModelIsInTime() {
        val items = (0..6).map { ReadItem.Speech("s$it") } + ReadItem.Picture("img", "Hafen")
        val (r, f) = reader(items)
        r.play()
        assertEquals(emptyList<String>(), f.prefetched) // Bild ist 7 Abschnitte entfernt
        r.utteranceDone(f.spoken.first { it.text == "s0" }.id)
        assertEquals(emptyList<String>(), f.prefetched) // noch 6
        r.utteranceDone(f.spoken.first { it.text == "s1" }.id)
        assertEquals(listOf("img"), f.prefetched) // jetzt 5 voraus
    }

    @Test
    fun withoutADescriptionTheAltTextIsReadAndWithoutThatTheImageIsSkipped() {
        val items = listOf(ReadItem.Picture("a", "Der Hafen"), ReadItem.Picture("b", ""), ReadItem.Speech("Text"))
        val (r, f) = reader(items)
        r.play()
        f.pending[0].second(null)
        assertEquals("Der Hafen" to "de-DE", f.spoken.last().text to f.spoken.last().language)
        r.utteranceDone(f.spoken.last().id)
        f.pending[1].second(null) // kein Bild lesbar, kein Ersatztext: übergehen
        assertEquals("Text", f.spoken.last().text)
        assertEquals(2, r.index)
    }

    @Test
    fun aDescriptionThatArrivesAfterPausingOrJumpingIsDropped() {
        val items = listOf(ReadItem.Picture("a", ""), ReadItem.Speech("Text"), ReadItem.Speech("mehr"))
        val (r, f) = reader(items)
        r.play()
        r.pause()
        assertTrue(f.cancels >= 1)
        f.pending[0].second("zu spät")
        assertTrue(f.spoken.none { it.text == "zu spät" })
        r.play()
        r.seekTo(1)
        f.pending.getOrNull(1)?.second?.invoke("noch später")
        assertTrue(f.spoken.none { it.text == "noch später" })
        assertEquals("Text", f.spoken.last { it.flush }.text)
    }

    @Test
    fun aCallPausesAndTheEndOfTheCallResumes() {
        val (r, f) = reader(speech("a", "b", "c"))
        r.play()
        r.utteranceDone(f.spoken[0].id)
        r.focusLostTemporarily()
        assertEquals(ReaderState.PAUSED, r.state)
        r.focusGained()
        assertEquals(ReaderState.PLAYING, r.state)
        assertEquals("b", f.spoken.last { it.flush }.text) // der unterbrochene Abschnitt beginnt von vorn
        // dauerhafter Verlust: nicht von selbst weiter
        r.focusLostPermanently()
        assertEquals(ReaderState.PAUSED, r.state)
        r.focusGained()
        assertEquals(ReaderState.PAUSED, r.state)
        // wer selbst pausiert, wird vom Tonfokus nicht gestartet
        r.play()
        r.pause()
        r.focusGained()
        assertEquals(ReaderState.PAUSED, r.state)
    }

    @Test
    fun startingWhileTheSoundIsBusyWaitsAndThenStartsByItself() {
        val (r, f) = reader(speech("a", "b"))
        r.waitForFocus()
        assertEquals(ReaderState.PAUSED, r.state)
        assertTrue(f.spoken.isEmpty())
        r.focusGained()
        assertEquals(ReaderState.PLAYING, r.state)
        assertEquals("a", f.spoken[0].text)
    }

    @Test
    fun aFailingSpeechOutputPausesWithAProblem() {
        val (r, f) = reader(speech("a", "b"))
        r.play()
        r.utteranceFailed(f.spoken[0].id)
        assertEquals(ReaderState.PAUSED, r.state)
        assertEquals(1, f.problems.size)
        assertFalse(f.states.isEmpty())
    }

    @Test
    fun startingInTheMiddleAndAnEmptyPlan() {
        val (r, f) = reader(speech("a", "b", "c"), start = 2)
        r.play()
        assertEquals("c", f.spoken[0].text)
        val (empty, g) = reader(emptyList())
        empty.play()
        assertEquals(ReaderState.STOPPED, empty.state)
        assertTrue(g.spoken.isEmpty())
        assertNull(g.problems.firstOrNull())
    }
}
