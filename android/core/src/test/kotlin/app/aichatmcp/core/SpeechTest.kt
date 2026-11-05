package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechTest {
    @Test
    fun languageNamesBecomeTags() {
        assertEquals("de-DE", Speech.languageTag("Deutsch"))
        assertEquals("en-US", Speech.languageTag(" English "))
        assertEquals("en-GB", Speech.languageTag("en_gb"))
        assertEquals("fr-FR", Speech.languageTag("Français"))
        assertNull(Speech.languageTag("Klingonisch")) // unbekannt: Gerätesprache
        assertNull(Speech.languageTag(""))
    }

    @Test
    fun spokenTextDropsToolCallsAndMarkup() {
        assertEquals(
            "Ich schaue nach. Fertig.",
            Speech.spokenText("Ich schaue nach. [[mcp:weather|Berlin|tomorrow]] Fertig."),
        )
        assertEquals("", Speech.spokenText("[[mcp:tv-p1]]")) // reiner Aufruf: nichts vorlesen
        assertEquals("Eins. Zwei.", Speech.spokenText("Eins\n\nZwei."))
    }

    @Test
    fun longTextIsSplitAtSentenceBoundaries() {
        val text = (1..40).joinToString(" ") { "Das ist Satz Nummer $it." }
        val chunks = Speech.chunks(text, max = 200)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 200 })
        assertTrue(chunks.all { it.endsWith(".") }) // nie mitten im Satz
        assertEquals(text, chunks.joinToString(" "))
        assertEquals(listOf("kurz."), Speech.chunks("kurz."))
        assertEquals(emptyList(), Speech.chunks("  "))
    }
}
