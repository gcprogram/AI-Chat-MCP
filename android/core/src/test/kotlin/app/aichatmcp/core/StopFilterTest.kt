package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopFilterTest {
    private fun run(stops: List<String>, vararg pieces: String): Pair<String, Boolean> {
        val filter = StopFilter(stops)
        val out = StringBuilder()
        for (p in pieces) {
            out.append(filter.feed(p))
            if (filter.stopped) break
        }
        out.append(filter.flush())
        return out.toString() to filter.stopped
    }

    @Test
    fun cutsAtAStopSequenceThatArrivesInPieces() {
        assertEquals("Hallo du." to true, run(listOf("\nStefan:"), "Hallo", " du.\n", "Ste", "fan: Hi", " noch mehr"))
    }

    @Test
    fun holdsBackOnlyTheEndThatCouldStillBecomeAStopSequence() {
        val filter = StopFilter(listOf("\nUser:")) // 6 Zeichen: höchstens 5 am Ende zurückhalten
        val out = StringBuilder()
        out.append(filter.feed("Das ist"))
        assertEquals("Da", out.toString())
        out.append(filter.feed(" gut.\nUs"))
        assertFalse(filter.stopped)
        out.append(filter.flush()) // wurde nie zur Stop-Sequenz: kommt vollständig heraus
        assertEquals("Das ist gut.\nUs", out.toString())
    }

    @Test
    fun noStopSequencesPassesEverythingThrough() {
        assertEquals("abc" to false, run(emptyList(), "a", "b", "c"))
    }

    @Test
    fun earliestOfSeveralStopSequencesWins() {
        assertEquals("eins" to true, run(listOf("\nNutzer:", "\nUser:"), "eins\nUser: x\nNutzer: y"))
        assertTrue(StopFilter(listOf("x")).feed("abc").isNotEmpty())
    }
}
