package app.aichatmcp.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GamebookTest {
    private fun scenes(n: Int) = (1..n).map { Scene("Kapitel ${(it + 1) / 2} (${2 - it % 2}/2)", "Text der Szene $it. ".repeat(30)) }

    /** Ein Modell: antwortet auf Karten mit allen Zeilen, auf Strang-Anfragen nach einer Tabelle. */
    private class Scripted(val threadOf: (Int) -> String = { "Lucy" }, val fail: Boolean = false, val cardFormat: (Int) -> String = { n ->
        "SUMMARY: Zusammenfassung $n.\nPLACE: Treppe im Erdgeschoss\nENTER: Ich gehe weiter die Treppe hoch.\nRISK: Ich springe vom Geländer.\nDETOUR: Ich schaue durch die Tür neben der Treppe."
    }) {
        val seen = mutableListOf<String>()
        suspend fun ask(prompt: List<WireMessage>, max: Int): String? {
            val text = prompt.last().content
            seen += text
            if (fail) return null
            return if ("<number>: <storyline>" in text) {
                Regex("""(?m)^(\d+)\. """).findAll(text).joinToString("\n") { "${it.groupValues[1]}: ${threadOf(it.groupValues[1].toInt() - 1)}" }
            } else if ("RISK: -" in text) { // die letzte Szene: keine Alternativen verlangt
                "SUMMARY: Zusammenfassung letzte.\nPLACE: Keller\nENTER: Ich steige hinab.\nRISK: -\nDETOUR: -"
            } else cardFormat(Regex("""Text der Szene (\d+)\.""").find(text.substringAfter("PASSAGE"))?.groupValues?.get(1)?.toInt() ?: 0)
        }
    }

    private fun skeleton(n: Int) = Gamebooks.skeleton(scenes(n), "Das Buch", "Deutsch", "k1", false, listOf("Lucy", "Lockwood"))

    @Test
    fun preparesOneCardPerSceneAndThreadsAndSavesAfterEveryRequest() = runBlocking {
        val sc = scenes(23)
        val model = Scripted({ if (it < 12) "Lucy" else "Holly" })
        val saved = mutableListOf<Gamebook>()
        val book = Gamebooks.prepare(sc, skeleton(23), model::ask, onData = { saved += it })
        assertEquals(23, book.scenes.count { it.plot.isNotBlank() && it.enter.isNotBlank() })
        assertEquals("Zusammenfassung 1.", book.scenes[0].plot)
        assertEquals("Treppe im Erdgeschoss", book.scenes[0].place)
        assertEquals("Ich gehe weiter die Treppe hoch.", book.scenes[0].enter)
        assertEquals("Lucy", book.scenes[0].thread)
        assertEquals("Holly", book.scenes[22].thread)
        assertEquals(listOf("Lucy", "Holly"), Gamebooks.threadNames(book))
        assertEquals(23 + 3, model.seen.size) // 23 Karten + drei Gruppen (10, 10, 3)
        assertTrue(saved.size >= 26)
        assertTrue(Gamebooks.ready(book))
        assertEquals(0, Gamebooks.requests(book))
        assertEquals("Kapitel 1", book.scenes[0].chapter)
        assertEquals("Kapitel 12", book.scenes[22].chapter)
        // die letzte Szene hat keine Alternativen (nach ihr ist das Buch zu Ende), die vorletzte schon
        assertEquals("", book.scenes[22].risk)
        assertTrue(book.scenes[21].risk.isNotBlank())
        // die Anfrage nennt die Regeln: Richtung statt Ergebnis, Alternativen vom Ort aus
        assertTrue("nie ihr Ergebnis" in model.seen.first() && "genau dem Ort in PLACE" in model.seen.first() && "SUMMARY:" in model.seen.first())
        // der Anfang der nächsten Szene steht als NEXT in der Anfrage
        assertTrue("NEXT" in model.seen.first() && "Text der Szene 2." in model.seen.first())
    }

    @Test
    fun cardsCanBePreparedInParallelWithTheSameResult() = runBlocking {
        val sc = scenes(30)
        val seq = Gamebooks.prepare(sc, skeleton(30), Scripted()::ask)
        val saved = mutableListOf<Int>()
        val par = Gamebooks.prepare(sc, skeleton(30), Scripted()::ask, onData = { saved += Gamebooks.requests(it) }, parallel = 4)
        assertEquals(seq, par)
        assertTrue(Gamebooks.ready(par))
        assertTrue(saved.zipWithNext().all { (x, y) -> y <= x }) // die Zahl der fehlenden Anfragen sinkt, auch wenn die Karten durcheinander fertig werden
        // fällt eine Anfrage aus, bricht der Lauf ab und das Fertige bleibt
        var calls = 0
        val flaky: suspend (List<WireMessage>, Int) -> String? = { p, m -> if (++calls >= 5) null else Scripted().ask(p, m) }
        val done = mutableListOf<Gamebook>()
        assertFailsWith<SummaryException> { Gamebooks.prepare(sc, skeleton(30), flaky, onData = { done += it }, parallel = 3, retryDelayMs = 0) }
        assertTrue(done.isNotEmpty() && done.last().scenes.count { Gamebooks.sceneDone(it) } >= 1)
        // ein einzelner Aussetzer wird wiederholt und beendet den Lauf nicht
        var hiccups = 0
        val once: suspend (List<WireMessage>, Int) -> String? = { p, m -> if (hiccups++ == 3) null else Scripted().ask(p, m) }
        assertTrue(Gamebooks.ready(Gamebooks.prepare(sc, skeleton(30), once, parallel = 1, retryDelayMs = 0)))
    }

    @Test
    fun theCatalogOffersAGermanFemaleVoice() {
        val german = ModelCatalog.piper.filter { it.language == "de" }
        assertTrue(german.any { "Ramona" in it.title } && german.any { "Kerstin" in it.title })
        assertTrue(german.all { e -> e.files.size == 3 && e.files.all { it.url.startsWith("https://huggingface.co/csukuangfj/vits-piper-de_DE-") } })
    }

    @Test
    fun aSecondRunContinuesWhereTheFirstStopped() = runBlocking {
        val sc = scenes(12)
        val first = Scripted()
        val partial = Gamebooks.prepare(sc, skeleton(12).let { it.copy(scenes = it.scenes) }, first::ask, onProgress = { _, _, _ -> })
        assertTrue(Gamebooks.ready(partial))
        // ein halb fertiger Stand: fünf Karten fehlen und alle Stränge
        val half = partial.copy(scenes = partial.scenes.mapIndexed { i, s -> if (i < 7) s.copy(thread = "") else s.copy(plot = "", enter = "", thread = "") })
        val second = Scripted()
        assertEquals(5 + 2, Gamebooks.requests(half))
        val done = Gamebooks.prepare(sc, half, second::ask)
        assertEquals(7, second.seen.size)
        assertTrue(Gamebooks.ready(done))
        assertTrue(Gamebooks.compatible(done, sc))
        assertFalse(Gamebooks.compatible(done, sc.take(5)))
    }

    @Test
    fun aFailureKeepsWhatIsDone() = runBlocking {
        val sc = scenes(4)
        val saved = mutableListOf<Gamebook>()
        assertFailsWith<SummaryException> { Gamebooks.prepare(sc, skeleton(4), Scripted(fail = true)::ask, onData = { saved += it }, retryDelayMs = 0) }
        assertTrue(saved.isEmpty())
        // erste Szene gelingt, danach fällt das Modell aus: die erste Karte bleibt
        var calls = 0
        val flaky: suspend (List<WireMessage>, Int) -> String? = { p, m -> if (calls++ < 1) Scripted().ask(p, m) else null }
        assertFailsWith<SummaryException> { Gamebooks.prepare(sc, skeleton(4), flaky, onData = { saved += it }, retryDelayMs = 0) }
        assertEquals(1, saved.last().scenes.count { Gamebooks.sceneDone(it) })
    }

    @Test
    fun aCardWithoutFormatGetsASecondTryAndThenAFallback() = runBlocking {
        val sc = scenes(3)
        var n = 0
        val model = Scripted(cardFormat = { n++; "Ich weiß nicht." })
        val book = Gamebooks.prepare(sc, skeleton(3), model::ask)
        assertEquals(4, n) // zwei Szenen ohne Format, je zwei Versuche (die letzte Szene hat ein eigenes Muster)
        assertTrue(book.scenes[0].plot.startsWith("Text der Szene 1."))
        assertEquals("", book.scenes[0].enter) // Notlösung: kein Eingang, kein Ort
        assertFalse(Gamebooks.sceneDone(book.scenes[0]))
        assertEquals(2, Gamebooks.roughScenes(book)) // die nächste Vorbereitung erzeugt diese Karten erneut
        assertEquals(2, Gamebooks.requests(book.copy(scenes = book.scenes.map { it.copy(thread = "A") })))
        assertTrue(Gamebooks.playable(book)) // spielbar, aber nur mit der Handlung und "Weiter."
    }

    @Test
    fun cardsAreReadForgivingly() {
        val answer = "Gern:\n**SUMMARY:** Lucy geht hoch.\nSie hört etwas.\n**PLACE:** Treppe, 1. Stock\nENTER: \"Ich gehe die Treppe hinauf.\"\nRISK: -\nDETOUR: Ich bleibe stehen."
        val c = Gamebooks.parseCard(answer)
        assertEquals("Lucy geht hoch. Sie hört etwas.", c.summary)
        assertEquals("Treppe, 1. Stock", c.place)
        assertEquals("Ich gehe die Treppe hinauf.", c.enter)
        assertEquals("", c.risk)
        assertEquals("Ich bleibe stehen.", c.detour)
        // zu lange Zeilen werden am Satzende gekürzt
        val long = Gamebooks.parseCard("ENTER: Ich gehe hinauf und dann weiter. " + "Das Ergebnis ist ein langer Text. ".repeat(20))
        assertEquals("Ich gehe hinauf und dann weiter.", long.enter)
    }

    @Test
    fun aFirstPersonBookHasNoThreadChoice() {
        val scenes = listOf(GbScene("S1", thread = "Lucy", plot = "P", enter = "E", place = "O"), GbScene("S2", thread = "Lucy und Kollegen", plot = "P", enter = "E", place = "O"))
        assertEquals(2, Gamebook(key = "k", scenes = scenes).threads().size)
        // ein Ich-Erzähler hat nur eine Sicht: die "Stränge" des Modells wären nur Figurengruppen
        assertTrue(Gamebook(key = "k", firstPerson = true, scenes = scenes).threads().isEmpty())
    }

    @Test
    fun aCardInTheWrongLanguageIsPreparedAgain() {
        val german = GbScene("S1", plot = "Lucy steigt die Treppe hinauf und hört einen Schrei aus dem Keller.", place = "Treppe", enter = "Ich gehe weiter.", risk = "Ich öffne die Tür.", detour = "Ich warte.")
        val english = GbScene("S2", plot = "The main character and Lockwood investigate a wall in the study, suspecting that it hides something.", place = "Study", enter = "I examine the books on the shelves.", risk = "I open the bag at once.", detour = "I listen to the wall.")
        val book = Gamebook(key = "k", title = "T", language = "Deutsch", scenes = listOf(german, english))
        assertFalse(Gamebooks.wrongLanguage(german, "Deutsch"))
        assertTrue(Gamebooks.wrongLanguage(english, "Deutsch"))
        assertFalse(Gamebooks.wrongLanguage(english, "English"))
        assertTrue(Gamebooks.wrongLanguage(german, "English"))
        assertFalse(Gamebooks.wrongLanguage(english, "Français")) // andere Sprachen werden nicht geprüft
        assertTrue(Gamebooks.cardDone(book, 0))
        assertFalse(Gamebooks.cardDone(book, 1))
        assertEquals(1, Gamebooks.roughScenes(book))
        assertEquals(1 + 1, Gamebooks.requests(book)) // eine Karte und eine Gruppe für die Stränge
        // kurze deutsche Sätze mit einzelnen englischen Namen oder Wörtern gelten nicht als Englisch
        assertFalse(Gamebooks.wrongLanguage(GbScene("S", plot = "Lockwood & Co. untersucht den Fall; der Sprecher und Mrs Hope reden über the Ghost Lamp.", place = "Küche"), "Deutsch"))
    }

    @Test
    fun aCardWithoutLabelsIsReadByOrder() {
        // so schrieb das 8B die Karte: fünf Zeilen ohne Beschriftung (Zusammenfassung zuerst, dann Ort, Eingang, Risiko, Umweg)
        val answer = "Lena setzt vorsichtig einen Fuß auf die nächste Stufe.\nTreppenabsatz im ersten Stock.\nIch gehe weiter die Treppe hinauf.\nIch öffne die Tür, obwohl ich nicht weiß, was dahinter ist.\nIch schließe die Tür und lausche."
        val c = Gamebooks.parseCard(answer)
        assertEquals("Lena setzt vorsichtig einen Fuß auf die nächste Stufe.", c.summary)
        assertEquals("Treppenabsatz im ersten Stock.", c.place)
        assertEquals("Ich gehe weiter die Treppe hinauf.", c.enter)
        assertEquals("Ich öffne die Tür, obwohl ich nicht weiß, was dahinter ist.", c.risk)
        assertEquals("Ich schließe die Tür und lausche.", c.detour)
        // eine mehrzeilige Zusammenfassung davor bleibt zusammen
        val multi = Gamebooks.parseCard("Erster Satz.\nZweiter Satz.\nKeller\nIch steige hinab.\n-\n-")
        assertEquals("Erster Satz. Zweiter Satz.", multi.summary)
        assertEquals("Ich steige hinab.", multi.enter)
        assertEquals("", multi.risk)
        // zu wenig Zeilen ohne Beschriftung: nichts erfinden
        assertEquals("", Gamebooks.parseCard("Ich weiß nicht.\nVielleicht.").summary)
        // eine Beschriftung allein in der Zeile, der Wert darunter
        val lone = Gamebooks.parseCard("SUMMARY:\nLena geht hoch.\nPLACE:\nTreppe\nENTER: Ich gehe.\nRISK: Ich springe.\nDETOUR: Ich warte.")
        assertEquals("Lena geht hoch.", lone.summary)
        assertEquals("Treppe", lone.place)
    }

    @Test
    fun theCardPromptAsksForLabelsInTheLanguageOfTheBook() {
        val sc = scenes(2)
        val de = Gamebooks.cardPrompt("Buch", sc[0], sc[1], "Deutsch")[0].content
        assertTrue("SUMMARY: <Zusammenfassung" in de && "Ich gehe weiter die Treppe hinauf." in de && "must be written in Deutsch" in de && "Begin every line with SUMMARY:" in de)
        val en = Gamebooks.cardPrompt("Book", sc[0], sc[1], "English")[0].content
        assertTrue("SUMMARY: <3 to 5 sentences in English" in en && "I go on up the stairs." in en)
        assertTrue("RISK: -" in Gamebooks.cardPrompt("Buch", sc[1], null, "Deutsch")[0].content)
    }

    @Test
    fun threadAnswersAreReadForgivingly() {
        val known = listOf("Detektiv")
        val answer = "Gern:\n1: detektiv\n**2:** \"Bibliothek\"\n3. Bibliothek.\n5: Einkauf"
        val out = Gamebooks.parseThreads(answer, listOf(0, 1, 2, 3), known, "Haupthandlung")
        assertEquals("Detektiv", out[0])
        assertEquals("Bibliothek", out[1])
        assertEquals("Bibliothek", out[2])
        assertEquals("Bibliothek", out[3])
        assertEquals(4, out.size)
        val none = Gamebooks.parseThreads("Ich weiß nicht.", listOf(10, 11), listOf("X"), "Detektiv")
        assertEquals(mapOf(10 to "Detektiv", 11 to "Detektiv"), none)
        // das Modell wiederholt die Nummer im Namen ("96: 96: Alarm"): sie bleibt nicht im Strang
        assertEquals("Geisterjäger-Alarm", Gamebooks.parseThreads("1: 96: Geisterjäger-Alarm", listOf(0), emptyList(), "x")[0])
    }

    @Test
    fun frontAndBackMatterIsNotPartOfTheStory() {
        val title = Scene("Abschnitt 1", "DIE SEUFZENDE WENDELTREPPE Aus dem Englischen. Originalausgabe bei Doubleday. Copyright 2013. ISBN 978-3-570. Alle Rechte vorbehalten.")
        val story = (1..10).map { Scene("Kapitel $it", "Lucy ging die Treppe hinauf und hörte etwas. ".repeat(40)) }
        val thanks = Scene("Abschnitt 12", "Danksagung: Mein Dank gilt allen, die dieses Buch möglich gemacht haben.")
        val all = listOf(title) + story + listOf(thanks)
        assertEquals(setOf(0, 11), Gamebooks.frontMatter(all))
        assertEquals(10, Gamebooks.storyScenes(all).size)
        // mitten im Buch zählt ein Wort wie "Copyright" nicht
        val middle = all.toMutableList().also { it[6] = Scene("Kapitel 6", "Auf dem Schild stand Copyright und Originalausgabe, ISBN. ".repeat(3)) }
        assertFalse(6 in Gamebooks.frontMatter(middle))
    }

    @Test
    fun decisionsComeFromTheBookWithoutAModel() {
        fun scene(i: Int, thread: String, chapter: String) = GbScene("S$i", chapter, thread, "Plot $i", "Ort $i", "Ich gehe zu $i.", "Risiko $i", "Umweg $i")
        val book = Gamebook(key = "k", title = "T", scenes = listOf(scene(0, "A", "K1"), scene(1, "A", "K1"), scene(2, "B", "K1"), scene(3, "A", "K2"), scene(4, "B", "K2")))
        // klassisch: Handlung = Eingang der nächsten Szene, Risiko und Umweg aus der aktuellen
        val classic = Gamebooks.options(book, 0, setOf(0), DecisionMode.CLASSIC, 0, true)
        assertEquals(listOf(DecisionKind.STORY, DecisionKind.RISK, DecisionKind.DETOUR), classic.map { it.kind })
        assertEquals(listOf("Ich gehe zu 1.", "Risiko 0", "Umweg 0"), classic.map { it.text })
        // Stränge: je Strang die nächste Szene, aber nur im selben Kapitel (Szene 3 und 4 sind in Kapitel 2)
        val strands = Gamebooks.options(book, 0, setOf(0), DecisionMode.STRANDS, 0, true)
        assertEquals(listOf(1, 2), strands.map { it.scene })
        assertTrue(strands.all { it.kind == DecisionKind.STRAND })
        assertEquals(listOf("Ich gehe zu 1.", "Ich gehe zu 2."), strands.map { it.text })
        // ist das Kapitel durch, gibt es dort nur noch eine Möglichkeit: klassisch statt Stränge
        val later = Gamebooks.options(book, 3, setOf(0, 1, 2, 3), DecisionMode.STRANDS, 3, true)
        assertEquals(listOf(DecisionKind.STORY, DecisionKind.RISK, DecisionKind.DETOUR), later.map { it.kind }.take(3))
        // gemischt: erste Runde klassisch, zweite Stränge
        assertEquals(DecisionKind.STORY, Gamebooks.options(book, 0, setOf(0), DecisionMode.MIXED, 0, true).first().kind)
        assertEquals(DecisionKind.STRAND, Gamebooks.options(book, 0, setOf(0), DecisionMode.MIXED, 1, true).first().kind)
        // alles gespielt: keine Entscheidung mehr
        assertEquals(emptyList(), Gamebooks.options(book, 4, setOf(0, 1, 2, 3, 4), DecisionMode.CLASSIC, 4, true))
        // fehlt ein Eingang oder sind Alternativen leer: "Weiter." und nur die Handlung
        val sparse = book.copy(scenes = book.scenes.map { it.copy(enter = "", risk = "", detour = "") })
        assertEquals(listOf("Weiter."), Gamebooks.options(sparse, 0, setOf(0), DecisionMode.CLASSIC, 0, true).map { it.text })
        assertEquals("Continue.", Gamebooks.enterOf(sparse, 1, false))
    }

    @Test
    fun theFileCanBeEditedAndLoadedAgain() {
        val book = Gamebook(
            key = "k", title = "Mein Buch", firstPerson = true, characters = listOf("Lucy"),
            scenes = listOf(GbScene("Kapitel 1 (1/2)", "", "Lucy", "Plot", "Ort", "Ich gehe weiter.", "Risiko", "Umweg"), GbScene("Kapitel 1 (2/2)", plot = "Plot 2", enter = "Ich öffne die Tür.")),
        )
        val json = Gamebooks.toJson(book)
        assertTrue("\"format\": \"aichat-gamebook\"" in json && "Ich gehe weiter." in json && "\n" in json)
        val back = assertNotNull(Gamebooks.parse(json))
        assertEquals("Mein Buch", back.title)
        assertEquals("Kapitel 1", back.scenes[0].chapter) // fehlendes Kapitel wird aus dem Titel gebildet
        assertEquals("Ich öffne die Tür.", back.scenes[1].enter)
        // von Hand bearbeitet: eine Entscheidung geändert, ein unbekanntes Feld dazu
        val edited = json.replace("Ich gehe weiter.", "Ich steige die Stufen hinauf.").replace("\"title\": \"Mein Buch\",", "\"title\": \"Mein Buch\", \"notiz\": \"x\",")
        assertEquals("Ich steige die Stufen hinauf.", assertNotNull(Gamebooks.parse(edited)).scenes[0].enter)
        assertEquals(listOf("Plot", "Plot 2"), back.sceneList().map { it.text })
        assertEquals(mapOf(0 to "Plot", 1 to "Plot 2"), back.plots())
        assertTrue(back.threads().isEmpty()) // nicht alle Szenen haben einen Strang
        // andere JSON-Dateien und kaputter Text sind kein Spielbuch
        assertNull(Gamebooks.parse("""{"name":"Nyx"}"""))
        assertNull(Gamebooks.parse("kein json"))
        assertNull(Gamebooks.parse(json.replace("aichat-gamebook", "etwas-anderes")))
        assertNull(Gamebooks.parse(Gamebooks.toJson(book.copy(scenes = emptyList()))))
    }

    @Test
    fun aBookWithoutThreadsOrWithFewGapsIsPlayableOnlyWhenEverySceneHasPlotAndEntry() {
        val full = Gamebook(key = "k", title = "T", scenes = listOf(GbScene("S1", plot = "P1", enter = "Ich gehe."), GbScene("S2", plot = "P2", enter = "Ich öffne.")))
        assertTrue(Gamebooks.playable(full)) // von Hand geschrieben, ohne Stränge: spielbar (nur ohne Strang-Wahl)
        assertFalse(Gamebooks.ready(full)) // "fertig vorbereitet" meint auch die Stränge
        val partial = full.copy(scenes = full.scenes + GbScene("S3"))
        assertFalse(Gamebooks.playable(partial))
        assertEquals(1, Gamebooks.missingScenes(partial))
        assertFalse(Gamebooks.playable(full.copy(scenes = emptyList())))
    }

    @Test
    fun theNarratorKnowsWhereThePreviousSceneEnded() {
        val book = Gamebook(
            key = "k", title = "T",
            scenes = listOf(GbScene("S1", plot = "Lucy steigt die Treppe hinauf.", place = "Treppe im 1. Stock", enter = "Ich gehe weiter."), GbScene("S2", plot = "Oben wartet jemand.", enter = "Ich öffne die Tür.")),
        )
        val setup = RoleplaySetup("T", book.sceneList(), "Lucy", false, true, solo = true, plots = book.plots(), book = book)
        val p = RoleplayPrompt.system(setup, 1, false, SoloState(), previous = 0)
        assertTrue("VORHER (S1): Lucy steigt die Treppe hinauf." in p && "ORT DER SPIELERFIGUR ZU BEGINN: Treppe im 1. Stock" in p, p)
        assertTrue("führe in diesem Abschnitt zu den Ereignissen der Szene zurück" in p && "wiederhole keine Sätze" in p)
        assertFalse("VORHER" in RoleplayPrompt.system(setup, 1, false, SoloState()))
    }

    @Test
    fun strandCandidatesStayInTheChapter() {
        val threads = mapOf(0 to "A", 1 to "B", 2 to "A", 3 to "B")
        val chapters = listOf("K1", "K1", "K2", "K2")
        assertEquals(listOf(1), Solo.candidates(4, setOf(0), 3, threads, chapters)) // Kapitel 1 hat nur noch Strang B
        assertEquals(listOf(2, 3), Solo.candidates(4, setOf(0, 1), 3, threads, chapters))
        assertEquals(listOf(1, 2), Solo.candidates(4, setOf(0), 3, threads)) // ohne Kapitel: je Strang die nächste, auch über Kapitel hinweg
        assertEquals("Weiter.", Solo.fallbackStory(true).text)
    }

    @Test
    fun namesWithATitleThatDoNotBelongInTheCardAreFound() {
        val passage = "Mrs Andrews brachte die Heimsuchung. " + "Der Nebel lag über der Straße. ".repeat(60) + "Lucy klingelte. Mrs Martin öffnete die Tür und sprach mit Lockwood."
        val card = Gamebooks.Card("Lucy und Lockwood treffen Mrs Martin.", "Vor dem Haus", "Ich klingele.", "Ich sehe durch den Briefschlitz, ob Mrs. Andrews da ist.", "Ich warte auf Mrs Martin.")
        // Mrs Andrews steht nur am Anfang (Rückschau), nicht am Ende: für das Risiko ist sie nicht anwesend
        val found = Gamebooks.invented(card, passage, "Im Haus ist es dunkel.")
        assertEquals(listOf("Andrews"), found.risk)
        assertTrue(found.summary.isEmpty() && found.detour.isEmpty() && found.enter.isEmpty())
        // ganz unbekannt: auch in der Zusammenfassung
        val fresh = Gamebooks.invented(card.copy(summary = "Lucy trifft Mrs Hudson."), passage, null)
        assertEquals(listOf("Hudson"), fresh.summary)
        // der Eingang darf nur Personen nennen, die der Spieler aus früheren Szenen kennt
        val early = card.copy(enter = "Ich warte, während Mrs Martin die Treppe hinaufkommt.", risk = "", detour = "")
        assertEquals(listOf("Martin"), Gamebooks.invented(early, passage, null) { "Frühere Szenen: Lockwood klingelt." }.enter)
        assertEquals(emptyList(), Gamebooks.invented(early, passage, null) { "Mrs Martin war schon da." }.enter)
        assertEquals(emptyList(), Gamebooks.invented(Gamebooks.Card("Mrs Hopes Tochter kommt.", "x", "Weiter.", "", ""), "Mrs Hope ist krank.", null).summary) // Genitiv
        // gesäubert: Satz, Eingang, Risiko fallen weg
        val bad = Gamebooks.Invented(summary = listOf("Hudson"), enter = listOf("Martin"), risk = listOf("Andrews"))
        val clean = Gamebooks.scrub(card.copy(summary = "Lucy trifft Mrs Hudson. Lockwood wartet.", enter = "Ich rufe nach Mrs Martin."), bad, passage, true)
        assertEquals("Lockwood wartet.", clean.summary)
        assertEquals("Weiter.", clean.enter)
        assertEquals("", clean.risk)
        assertEquals("Ich warte auf Mrs Martin.", clean.detour)
        assertFalse(Gamebooks.invented(clean, passage, null) { "Mrs Martin war schon da." }.any)
    }

    @Test
    fun aCardWithAnInventedPersonIsAskedAgainAndThenCleaned() = runBlocking {
        val sc = listOf(Scene("Kapitel 1 (1/2)", "Lucy klingelt am Haus. Lockwood wartet."), Scene("Kapitel 1 (2/2)", "Drinnen ist es dunkel."))
        val prompts = mutableListOf<String>()
        val model: suspend (List<WireMessage>, Int) -> String? = { p, _ ->
            val text = p.last().content
            prompts += text
            if ("<number>: <storyline>" in text) "1: Lucy\n2: Lucy" else "SUMMARY: Lucy trifft Mrs Andrews vor dem Haus.\nPLACE: Vorgarten\nENTER: Ich klingele.\nRISK: Ich rufe nach Mrs Andrews.\nDETOUR: Ich warte."
        }
        val book = Gamebooks.prepare(sc, Gamebooks.skeleton(sc, "Das Buch", "Deutsch", "k", true, emptyList()), model, retryDelayMs = 0)
        assertTrue(prompts.any { "do not use them: Andrews" in it }) // die zweite Anfrage nennt den verbotenen Namen
        assertTrue(book.scenes.none { "Andrews" in it.plot + it.enter + it.risk + it.detour }) // und was bleibt, ist entfernt
        assertTrue(book.scenes[0].plot.isNotBlank() && Gamebooks.cardDone(book, 0))
        assertTrue(Gamebooks.suspect(book, sc).isEmpty()) // ist nicht mehr verdächtig: wird nicht bei jedem Lauf neu erzeugt
    }

    @Test
    fun existingCardsWithInventedNamesCountAsOpenRequests() {
        val sc = listOf(Scene("K (1/2)", "Lucy klingelt."), Scene("K (2/2)", "Es ist dunkel."))
        val book = Gamebook(
            key = "k", title = "T", language = "Deutsch",
            scenes = listOf(
                GbScene("K (1/2)", plot = "Mrs Andrews erscheint.", place = "Garten", enter = "Ich klingele.", thread = "A"),
                GbScene("K (2/2)", plot = "Es ist dunkel.", place = "Diele", enter = "Ich trete ein.", thread = "A"),
            ),
        )
        assertEquals(0, Gamebooks.requests(book))
        assertEquals(1, Gamebooks.requests(book, sc)) // eine Karte mit erfundener Person
        assertEquals(setOf(0), Gamebooks.suspect(book, sc))
    }
}
