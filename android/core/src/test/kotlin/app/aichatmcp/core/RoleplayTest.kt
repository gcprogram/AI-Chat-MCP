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
    fun detectsFirstPersonNarration() {
        assertTrue(BookCast.firstPerson("Ich ging nach Hause und mein Hund folgte mir. ".repeat(40)))
        assertFalse(BookCast.firstPerson("Er ging nach Hause und sein Hund folgte ihm. Sie lachte. ".repeat(40)))
        assertFalse(BookCast.firstPerson("Er sagte: \"Ich gehe heim, mir ist kalt.\" Sie antwortete: \"Ich bleibe.\" Er ging. ".repeat(30)))
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
}
