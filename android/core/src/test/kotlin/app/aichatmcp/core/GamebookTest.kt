package app.aichatmcp.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GamebookTest {
    private fun scenes(n: Int) = (1..n).map { Scene("Szene $it", "Text der Szene $it. ".repeat(30)) }

    /** Ein Modell: Zusammenfassungen "Plot n", Stränge nach einer Tabelle (Szene -> Strang). */
    private class Scripted(val threadOf: (Int) -> String, val fail: Boolean = false) {
        val seen = mutableListOf<String>()
        suspend fun ask(prompt: List<WireMessage>, max: Int): String? {
            val text = prompt.last().content
            seen += text
            if (fail) return null
            return if ("storyline" in text && "<number>: <storyline>" in text) {
                Regex("""(?m)^(\d+)\. """).findAll(text).joinToString("\n") { "${it.groupValues[1]}: ${threadOf(it.groupValues[1].toInt() - 1)}" }
            } else "Hier ist die Zusammenfassung:\nZusammenfassung dieser Szene."
        }
    }

    @Test
    fun preparesPlotsAndThreadsAndSavesAfterEveryRequest() = runBlocking {
        val sc = scenes(23)
        val model = Scripted({ if (it % 2 == 0) "Detektiv" else "Bibliothek" })
        val saved = mutableListOf<SummaryData>()
        val data = Gamebooks.prepare(sc, "Deutsch", SummaryData(), model::ask, onData = { saved += it })
        assertEquals(23, data.parts.size)
        assertEquals("Zusammenfassung dieser Szene.", data.parts.getValue(0)) // Einleitung der Antwort ist weg
        assertEquals(23, data.threads.size)
        assertEquals("Detektiv", data.threads[0])
        assertEquals("Bibliothek", data.threads[21])
        assertEquals(2, Gamebooks.threadCount(data))
        assertEquals(listOf("Detektiv", "Bibliothek"), Gamebooks.threadNames(data))
        assertEquals(23 + 3, model.seen.size) // 23 Szenen + drei Gruppen (10, 10, 3)
        assertTrue(saved.size >= 26)
        assertTrue(Gamebooks.ready(23, data))
        assertEquals(0, Gamebooks.requests(23, data))
        // bekannte Stränge werden in den späteren Anfragen genannt
        assertTrue(model.seen.last().contains("Storylines so far: Detektiv; Bibliothek."))
    }

    @Test
    fun aSecondRunContinuesWhereTheFirstStopped() = runBlocking {
        val sc = scenes(12)
        val first = Scripted({ "A" })
        val partial = Gamebooks.prepare(sc.take(5), "Deutsch", SummaryData(), first::ask)
        assertEquals(5, partial.parts.size)
        val second = Scripted({ "A" })
        // 12 Szenen: 5 Zusammenfassungen sind da, 7 fehlen, dazu zwei Gruppen
        assertEquals(7 + 2, Gamebooks.requests(12, partial))
        val data = Gamebooks.prepare(sc, "Deutsch", partial, second::ask)
        assertEquals(9, second.seen.size)
        assertTrue(Gamebooks.ready(12, data))
    }

    @Test
    fun aFailureKeepsWhatIsDone() = runBlocking {
        val sc = scenes(4)
        val ok = Scripted({ "A" })
        val partial = Gamebooks.prepare(sc.take(2), "Deutsch", SummaryData(), ok::ask)
        assertFailsWith<SummaryException> { Gamebooks.prepare(sc, "Deutsch", partial, Scripted({ "A" }, fail = true)::ask) }
        assertEquals(2, partial.parts.size)
        assertFalse(Gamebooks.ready(4, partial))
    }

    @Test
    fun threadAnswersAreReadForgivingly() {
        val known = listOf("Detektiv")
        val answer = "Gern:\n1: detektiv\n**2:** \"Bibliothek\"\n3. Bibliothek.\n5: Einkauf"
        val out = Gamebooks.parseThreads(answer, listOf(0, 1, 2, 3), known, "Haupthandlung")
        assertEquals("Detektiv", out[0]) // bekannter Name, Schreibweise der Liste
        assertEquals("Bibliothek", out[1])
        assertEquals("Bibliothek", out[2])
        assertEquals("Bibliothek", out[3]) // Szene 4 hat keine Zeile: übernimmt den Strang der vorigen
        assertEquals(4, out.size) // Zeile 5 gehört nicht zur Gruppe
        // gar keine brauchbare Antwort: alles bleibt im bisherigen Strang
        val none = Gamebooks.parseThreads("Ich weiß nicht.", listOf(10, 11), listOf("X"), "Detektiv")
        assertEquals(mapOf(10 to "Detektiv", 11 to "Detektiv"), none)
    }

    @Test
    fun candidatesComeOnePerThreadAndKeepTheOrderInsideAThread() {
        // Szenen 0..5: A A B A B C
        val threads = mapOf(0 to "A", 1 to "A", 2 to "B", 3 to "A", 4 to "B", 5 to "C")
        assertEquals(listOf(1, 2, 5), Solo.candidates(6, setOf(0), 3, threads)) // von A kommt Szene 1, nicht 3
        assertEquals(listOf(1, 2), Solo.candidates(6, setOf(0), 2, threads))
        assertEquals(listOf(2, 3, 5), Solo.candidates(6, setOf(0, 1), 3, threads))
        assertEquals(listOf(1, 4, 5), Solo.candidates(6, setOf(0, 2), 3, threads)) // B wurde schon gespielt: jetzt Szene 4
        assertEquals(listOf(5), Solo.candidates(6, setOf(0, 1, 2, 3, 4), 3, threads))
        // ohne Angaben: die nächsten Szenen in der Reihenfolge des Buches
        assertEquals(listOf(1, 2, 3), Solo.candidates(6, setOf(0), 3))
        // ein einziger Strang: nur eine Möglichkeit, Stränge fallen auf klassische Entscheidungen zurück
        val single = (0..4).associateWith { "A" }
        assertEquals(listOf(1), Solo.candidates(5, setOf(0), 3, single))
        assertFalse(Solo.useStrands(DecisionMode.STRANDS, 0, Solo.candidates(5, setOf(0), 3, single).size))
    }

    @Test
    fun theStrandRequestNamesTheThreads() {
        val setup = RoleplaySetup(
            "Buch", scenes(3), "Anna", false, true, solo = true,
            plots = mapOf(1 to "Tom befragt Zeugen.", 2 to "Mara liest."), threads = mapOf(0 to "Detektiv", 1 to "Detektiv", 2 to "Bibliothek"),
        )
        val ask = RoleplayPrompt.strandDecisions(setup, 0, "x", listOf(1, 2))[1].content
        assertTrue("1. Szene 2 [Strang: Detektiv]: Tom befragt Zeugen." in ask && "2. Szene 3 [Strang: Bibliothek]: Mara liest." in ask, ask)
    }

    @Test
    fun theStoredFormatKeepsOldFilesReadable() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"key":"k","parts":{"0":"Eins"},"chapters":{},"short":"","detailed":"","perChapter":""}"""
        val data = json.decodeFromString(SummaryData.serializer(), old)
        assertEquals(emptyMap(), data.threads)
        val round = json.decodeFromString(SummaryData.serializer(), json.encodeToString(SummaryData.serializer(), data.copy(threads = mapOf(0 to "A", 3 to "B"))))
        assertEquals(mapOf(0 to "A", 3 to "B"), round.threads)
    }
}
