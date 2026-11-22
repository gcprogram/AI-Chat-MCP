package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoleplayTest {
    private fun paragraph(n: Int) = "Absatz $n. " + "Der Wind pfiff über die Dünen und Anna ging weiter. ".repeat(12)

    @Test
    fun splitsAtChapterHeadingsAndChunksLongChapters() {
        val book = "Vorwort kurz.\n\nKapitel 1: Anfang\n\n${paragraph(1)}\n\n${paragraph(2)}\n\nKapitel 2\n\n" + (1..8).joinToString("\n\n") { paragraph(it) }
        val scenes = BookScenes.split(book, target = 1500, max = 2200)
        assertEquals("Kapitel 1: Anfang", scenes.first().title) // das Vorwort ist zu kurz für eine eigene Szene
        assertTrue(scenes.any { it.title.startsWith("Kapitel 2 (") }, scenes.map { it.title }.toString())
        assertTrue(scenes.all { it.text.length in 400..2400 }, scenes.map { it.text.length }.toString())
        assertFalse(scenes.any { "Kapitel 2" in it.text.take(20) }) // Überschriften stehen nicht im Text
    }

    @Test
    fun booksWithoutHeadingsBecomeNumberedSectionsAndHugeParagraphsAreSplit() {
        val flat = (1..12).joinToString("\n\n") { paragraph(it) }
        val a = BookScenes.split(flat, target = 1500, max = 2200)
        assertEquals("Abschnitt 1", a.first().title)
        assertTrue(a.size >= 3)
        val oneBlock = "Satz eins ist hier. ".repeat(600) // ein einziger riesiger Absatz
        val b = BookScenes.split(oneBlock, target = 1500, max = 2200)
        assertTrue(b.size >= 5 && b.all { it.text.length <= 2400 })
        assertEquals(emptyList(), BookScenes.split("   ", 1500, 2200))
    }

    @Test
    fun findsNameCandidatesFromMidSentenceCapitals() {
        val text = ("Anna sah den Turm. Dann sagte Anna leise zu Tom: Der Wald ist nah. Tom nickte und Anna lachte. Später fragte Tom den Wirt. " +
            "Der Wirt kannte Tom nicht. Sie gingen mit Anna weiter, und Anna dankte dem Wirt. wenn es regnet, ist Tom müde. ").repeat(2)
        val names = BookCast.candidates(text)
        assertTrue("Anna" in names && "Tom" in names, names.toString())
        assertFalse("Der" in names || "Dann" in names)
        assertEquals(listOf("Anna", "Tom"), BookCast.parseNames("Anna, Tom, Merlin, wirt.", listOf("Anna", "Tom", "Wirt", "Turm")).filter { it != "Wirt" }) // Merlin ist nicht in der Liste: nichts Erfundenes
    }

    @Test
    fun nounsWithAnArticleAreNotNames() {
        // "der Geist", "die Tür", "das Haus" stehen mit Artikel; Namen fast nie
        val text = ("Dann öffnete Lockwood die Tür und sah den Geist. Wir betraten das Haus, und Lockwood zeigte auf den Geist in der Tür. " +
            "Später fragte George im Haus nach dem Geist, während Lockwood die Tür schloss. George sagte, das Haus sei kalt. ").repeat(3)
        val names = BookCast.candidates(text)
        assertTrue("Lockwood" in names && "George" in names, names.toString())
        assertFalse("Geist" in names || "Tür" in names || "Haus" in names, names.toString())
    }

    @Test
    fun detectsFirstPersonNarration() {
        assertTrue(BookCast.firstPerson("Ich ging nach Hause und mein Hund folgte mir. ".repeat(40)))
        assertFalse(BookCast.firstPerson("Er ging nach Hause und sein Hund folgte ihm. Sie lachte. ".repeat(40)))
        assertFalse(BookCast.firstPerson("Er sagte: \"Ich gehe heim, mir ist kalt.\" Sie antwortete: \"Ich bleibe.\" Er ging. ".repeat(30)))
        // ein Ich-Erzähler, der viel über andere spricht (wie Lucy über Lockwood), ist trotzdem ein Ich-Erzähler
        assertTrue(BookCast.firstPerson("Ich sah Lockwood an. Er lächelte und sagte nichts. Sie, die Kundin, wartete. Mir wurde kalt, ich zog meinen Mantel enger. ".repeat(30)))
        // Titelseite vorweg zählt nicht
        assertTrue(BookCast.firstPerson("Der Verlag und seine Autoren, er und sie. ".repeat(100) + "Ich ging hinein, mir war kalt und ich wartete. ".repeat(400)))
    }

    @Test
    fun promptHoldsOnlyTheCurrentSceneAndAGlimpseOfTheNext() {
        val scenes = listOf(Scene("Kapitel 1", "Anna betritt den Leuchtturm."), Scene("Kapitel 2", "Der Sturm bricht los. " + "x".repeat(500)), Scene("Kapitel 3", "Das Ende."))
        val setup = RoleplaySetup("Das Logbuch", scenes, player = "Anna", firstPerson = false, german = true)
        val p = RoleplayPrompt.system(setup, 0, closing = false)
        assertTrue("AKTUELLE SZENE (Kapitel 1)" in p && "Anna betritt den Leuchtturm." in p && "die Figur Anna" in p)
        assertTrue("NÄCHSTE SZENE" in p && "Das Ende." !in p && p.length < 2000)
        assertTrue("zu ihrem Ende kommen" !in p && "zu ihrem Ende kommen" in RoleplayPrompt.system(setup, 0, closing = true))
        assertFalse("NÄCHSTE SZENE" in RoleplayPrompt.system(setup, 2, false))
        val ich = RoleplayPrompt.system(setup.copy(player = "", firstPerson = true), 0, false)
        assertTrue("Ich-Erzähler" in ich && "zweiten Person" in ich)
        val english = RoleplayPrompt.system(setup.copy(german = false), 1, false)
        assertTrue("CURRENT SCENE (Kapitel 2)" in english && "game master" in english)
    }

    @Test
    fun theRoleplayPersonaReplacesTheWholeSystemPrompt() {
        val setup = RoleplaySetup("Buch", listOf(Scene("S1", "Text")), "Tom", false, true)
        val persona = RoleplayPrompt.persona(setup, 0, false)
        val prompt = persona.systemPrompt("Alex")
        assertTrue(prompt.startsWith("Du bist der Spielleiter") && "Der Spieler (Alex) spielt die Figur Tom" in prompt)
        assertFalse("Rollenregeln" in prompt)
        assertTrue("Rollenregeln" in Persona(name = "Mia").systemPrompt("Alex")) // ohne Override unverändert
        assertEquals("roleplay", persona.id)
    }

    @Test
    fun theSoloNarratorNamesThePlayerCharacterAfterTheBookAndNeverTheUser() {
        val scenes = listOf(Scene("S1", "Lucy klingelt am Haus. ".repeat(20)), Scene("S2", "Drinnen ist es dunkel."))
        val setup = RoleplaySetup("Buch", scenes, "", true, true, solo = true, plots = mapOf(0 to "Lucy klingelt."), narrator = "Lucy", source = true)
        val prompt = RoleplayPrompt.persona(setup, 0, false, SoloState()).systemPrompt("Tom")
        assertTrue("Der Spieler spielt den Ich-Erzähler des Buches, Lucy" in prompt, prompt)
        assertFalse("Tom" in prompt) // der Name des Nutzers kommt im Solo-Abenteuer nicht vor
        assertTrue("Die Spielerfigur heißt Lucy" in prompt && "AUSZUG AUS DEM BUCH" in prompt && "Erfinde keine Personen" in prompt)
        assertTrue("ende genau dort, wo die Handlung endet" in prompt)
        // ohne Namen und ohne Auszug bleibt der Prompt wie zuvor
        val plain = RoleplayPrompt.system(setup.copy(narrator = "", source = false), 0, false, SoloState())
        assertTrue("den Ich-Erzähler des Buches (die Figur" in plain && "Die Spielerfigur heißt" !in plain && "AUSZUG" !in plain)
        assertEquals("Lucy", setup.hero)
        assertEquals("Anna", setup.copy(player = "Anna").hero)
        // die Entscheidungen nennen die Figur
        assertTrue("Lucy (dem Ich-Erzähler)" in RoleplayPrompt.decisions(setup, 0, "Text", SoloState()).last().content)
    }

    @Test
    fun theOriginalTextModeOnlyAsksForTheShortConsequence() {
        val book = Gamebook(key = "k", title = "T", scenes = listOf(GbScene("S1", plot = "Lucy klingelt.", place = "Vorgarten"), GbScene("S2", plot = "Es ist dunkel.")))
        val scenes = listOf(Scene("S1", "Lucy klingelt am Haus."), Scene("S2", "Drinnen war es dunkel und still. ".repeat(30)))
        val setup = RoleplaySetup("T", scenes, "", true, true, solo = true, plots = book.plots(), book = book, narrator = "Lucy", verbatim = true, source = true)
        val p = RoleplayPrompt.system(setup, 1, false, SoloState(), outcome = "Die Spielerfigur entkommt unverletzt.", previous = 0)
        assertTrue("nur die kurze Folge" in p && "50 bis 120 Wörter" in p && "Erfinde keine neuen Personen" in p, p)
        assertTrue("VORHER (S1): Lucy klingelt." in p && "ORT DER SPIELERFIGUR ZU BEGINN: Vorgarten" in p)
        assertTrue("ZULETZT GELESEN (Ende der vorigen Szene, wörtlich): … Lucy klingelt am Haus." in p)
        assertTrue("NEUE SZENE (unten) beginnt so: Drinnen war es dunkel" in p && "…" in p)
        assertTrue("Die Spielerfigur heißt Lucy" in p && "Die Spielerfigur entkommt unverletzt." in p) // die vorgegebene Folge steht dahinter
        assertTrue(p.length < 2500, "kurz: ${p.length}")
        // das nacherzählte Spiel bleibt die lange Erzählung
        assertTrue("120 bis 250 Wörter" in RoleplayPrompt.system(setup.copy(verbatim = false), 1, false, SoloState()))
    }

    @Test
    fun theNarratorNameComesOnlyFromTheListOfNames() {
        val names = listOf("Lockwood", "Lucy", "George")
        assertEquals("Lucy", BookCast.parseNarrator("Lucy", names))
        assertEquals("Lucy", BookCast.parseNarrator("**Lucy** (Lucy Carlyle)", names))
        assertEquals("", BookCast.parseNarrator("NONE", names))
        assertEquals("", BookCast.parseNarrator("Jane", names)) // nichts Erfundenes
        val prompt = BookCast.narratorPrompt("Text", names).single().content
        assertTrue("Lockwood, Lucy, George" in prompt && "NONE" in prompt)
    }
}
