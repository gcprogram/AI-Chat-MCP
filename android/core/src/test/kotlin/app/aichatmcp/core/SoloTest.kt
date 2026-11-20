package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoloTest {
    private val story = Decision(DecisionKind.STORY, "Ich folge dem Pfad zum Turm.")
    private val risk = Decision(DecisionKind.RISK, "Ich springe über die Schlucht.")
    private val detour = Decision(DecisionKind.DETOUR, "Ich nehme den Weg über den Markt.")

    private fun play(state: SoloState, d: Decision, roll: Int = 99, perDay: Int = 3) = Solo.resolve(state, d, roll, perDay)

    @Test
    fun followingTheStoryOrTakingADetourHurtsNobody() {
        val a = play(SoloState(), story)
        assertEquals(Consequence.NONE, a.consequence)
        assertEquals(SoloState(scenesPlayed = 1), a.state)
        val b = play(SoloState(), detour, roll = 0) // auch ein Würfel von 0 ändert bei einem Umweg nichts
        assertEquals(Consequence.NONE, b.consequence)
        assertEquals(0, b.state.wounds)
        assertEquals(0, b.state.wrongToday) // ein Umweg ist keine falsche Entscheidung
        assertNull(b.state.end)
    }

    @Test
    fun aRiskyDecisionCanKillWoundOrBeSurvivedUnharmed() {
        val killed = play(SoloState(), risk, roll = 5)
        assertEquals(Consequence.KILLED, killed.consequence)
        assertEquals(SoloEnd.DEAD, killed.state.end)
        val wounded = play(SoloState(), risk, roll = 30)
        assertEquals(Consequence.WOUNDED, wounded.consequence)
        assertEquals(1, wounded.state.wounds)
        assertEquals(1, wounded.state.wrongToday)
        val escaped = play(SoloState(), risk, roll = 80)
        assertEquals(Consequence.NONE, escaped.consequence)
        assertEquals(0, escaped.state.wounds)
        assertEquals(1, escaped.state.wrongToday) // auch das Entkommen war eine falsche Entscheidung
    }

    @Test
    fun theThirdWoundKills() {
        val s = SoloState(wounds = 2, scenesPlayed = 0)
        val r = play(s, risk, roll = 30)
        assertEquals(Consequence.DIED_OF_WOUNDS, r.consequence)
        assertEquals(SoloEnd.DEAD, r.state.end)
        assertEquals(3, r.state.wounds)
    }

    @Test
    fun threeWrongDecisionsInOneDayLoseTheStory() {
        var s = SoloState()
        s = play(s, risk, roll = 80, perDay = 5).state
        s = play(s, risk, roll = 80, perDay = 5).state
        assertNull(s.end)
        val third = play(s, risk, roll = 80, perDay = 5)
        assertEquals(Consequence.LOST, third.consequence)
        assertEquals(SoloEnd.LOST, third.state.end)
    }

    @Test
    fun deathWinsOverALostStory() {
        val s = SoloState(wrongToday = 2)
        assertEquals(Consequence.KILLED, play(s, risk, roll = 3).consequence)
    }

    @Test
    fun aNightHealsOneWoundAndStartsTheCountOfWrongDecisionsAgain() {
        // drei Szenen je Tag: nach der dritten Szene vergeht eine Nacht
        var r = play(SoloState(wounds = 2, wrongToday = 1, scenesPlayed = 2), story)
        assertTrue(r.night && r.healed)
        assertEquals(1, r.state.wounds)
        assertEquals(0, r.state.wrongToday)
        assertEquals(2, r.state.day)
        // ohne Verwundung: Nacht, aber nichts zu heilen
        r = play(SoloState(scenesPlayed = 5), story)
        assertTrue(r.night && !r.healed)
        assertEquals(0, r.state.wounds)
        // keine Nacht zwischen den Tagen
        r = play(SoloState(wounds = 1, scenesPlayed = 0), story)
        assertFalse(r.night)
        assertEquals(1, r.state.wounds)
        // ein einziges Pech am Tag: die Zählung der falschen Entscheidungen beginnt am nächsten Tag von vorn
        var s = play(SoloState(), risk, roll = 80).state // Szene 1
        s = play(s, risk, roll = 80).state // Szene 2: zwei falsche
        s = play(s, story).state // Szene 3: Nacht
        assertEquals(0, s.wrongToday)
        assertEquals(2, s.day)
        s = play(s, risk, roll = 80).state
        assertNull(s.end)
    }

    @Test
    fun noNightAfterTheStoryEnded() {
        val r = play(SoloState(wounds = 2, scenesPlayed = 2), risk, roll = 30) // dritte Verwundung, zugleich die dritte Szene
        assertEquals(SoloEnd.DEAD, r.state.end)
        assertFalse(r.night)
    }

    @Test
    fun relationshipPointsAddUpAndStayWithinFive() {
        var s = SoloState()
        val kind = Decision(DecisionKind.STORY, "Ich teile mein Brot mit Kaelen.", mapOf("Kaelen" to 2, "Seraphine" to -1))
        repeat(4) { s = play(s, kind).state }
        assertEquals(5, s.relations["Kaelen"]) // höchstens +5
        assertEquals(-4, s.relations["Seraphine"])
        val rude = Decision(DecisionKind.DETOUR, "Ich beschimpfe Seraphine vor allen.", mapOf("Seraphine" to -2))
        repeat(3) { s = play(s, rude).state }
        assertEquals(-5, s.relations["Seraphine"])
    }

    @Test
    fun parsesTheProposalsInVariousShapes() {
        val text = "Hier die Entscheidungen:\nSTORY: Ich folge dem Pfad zum Turm. [Kaelen +1]\n**RISIKO:** Ich springe über die Schlucht, statt die Brücke zu nehmen. [Kaelen -1, Fremder +2]\n3. UMWEG: Ich schleiche über den Markt und höre mich um.\nUMWEG: noch ein zweiter Umweg"
        val list = Solo.parseDecisions(text, listOf("Kaelen", "Seraphine"))
        assertEquals(listOf(DecisionKind.STORY, DecisionKind.RISK, DecisionKind.DETOUR), list.map { it.kind })
        assertEquals("Ich folge dem Pfad zum Turm.", list[0].text)
        assertEquals(mapOf("Kaelen" to 1), list[0].relations)
        assertEquals(mapOf("Kaelen" to -1), list[1].relations) // "Fremder" ist keine bekannte Hauptfigur
        assertTrue(list[2].text.startsWith("Ich schleiche über den Markt"))
        assertEquals(1, list.count { it.kind == DecisionKind.DETOUR }) // höchstens eine je Art
        // englische Namen der Arten, Reihenfolge der Antwort egal, zu kurze Einträge fallen weg
        val en = Solo.parseDecisions("DETOUR: I go around the market place.\nRISK: no\nSTORY: I follow the path to the tower.", emptyList())
        assertEquals(listOf(DecisionKind.STORY, DecisionKind.DETOUR), en.map { it.kind })
        assertEquals(emptyList(), Solo.parseDecisions("keine Zeilen hier", emptyList()))
    }

    @Test
    fun theWayOfTheStoryIsAlwaysOffered() {
        val only = Solo.parseDecisions("RISIKO: Ich springe über die Schlucht.", emptyList())
        val offered = Solo.withStory(only, german = true)
        assertEquals(listOf(DecisionKind.STORY, DecisionKind.RISK), offered.map { it.kind })
        assertEquals(1, Solo.withStory(emptyList(), german = false).size)
        assertEquals(only, Solo.withStory(only + Solo.fallbackStory(true), true).filter { it.kind == DecisionKind.RISK })
    }

    @Test
    fun theOutcomeNoteFixesWhatTheNarratorMustTell() {
        val wounded = play(SoloState(wounds = 1), risk, roll = 30)
        val note = Solo.outcomeNote(risk, wounded, german = true)
        assertTrue("verwundet" in note && "2 von 3" in note && "zurück in die Handlung" in note, note)
        val dead = play(SoloState(wounds = 2), risk, roll = 30)
        assertTrue("stirbt" in Solo.outcomeNote(risk, dead, true) && "keine neue Szene" in Solo.outcomeNote(risk, dead, true))
        val lost = play(SoloState(wrongToday = 2), risk, roll = 80)
        assertTrue("verloren" in Solo.outcomeNote(risk, lost, true))
        val night = play(SoloState(wounds = 1, scenesPlayed = 2), story)
        assertTrue("Nacht" in Solo.outcomeNote(story, night, true) && "heilt" in Solo.outcomeNote(story, night, true))
        val rel = Decision(DecisionKind.STORY, "Ich danke Kaelen vor allen Wölfen.", mapOf("Kaelen" to 1))
        assertTrue("Kaelen +1" in Solo.outcomeNote(rel, play(SoloState(), rel), true))
        assertTrue("wounded" in Solo.outcomeNote(risk, wounded, false))
        assertEquals("Verwundet (2 von 3) · Kaelen +1", Solo.resultLine(risk.copy(relations = mapOf("Kaelen" to 1)), play(SoloState(wounds = 1), risk.copy(relations = mapOf("Kaelen" to 1)), roll = 30), true))
    }

    @Test
    fun theGameMasterPromptCarriesTheStateAndTheBindingOutcome() {
        val scenes = listOf(Scene("Kapitel 1", "Anna betritt den Leuchtturm."), Scene("Kapitel 2", "Der Sturm bricht los."), Scene("Kapitel 3", "Das Ende."))
        val setup = RoleplaySetup("Das Logbuch", scenes, player = "Anna", firstPerson = false, german = true, solo = true, known = listOf("Tom", "Mara"))
        val state = SoloState(wounds = 1, day = 2, relations = mapOf("Tom" to 3, "Mara" to -1))
        val p = RoleplayPrompt.system(setup, 1, closing = true, solo = state, outcome = "Anna wird verwundet.")
        assertTrue("SOLO-ABENTEUER" in p && "Verwundungen: 1 von 3" in p && "Tom +3 (vertraut der Spielerfigur)" in p && "Mara -1 (misstrauisch)" in p, p)
        assertTrue("FOLGE DER LETZTEN ENTSCHEIDUNG (verbindlich): Anna wird verwundet." in p && "vor einer Entscheidung steht" in p)
        // ohne Solo-Schalter bleibt der Prompt unverändert
        assertEquals(RoleplayPrompt.system(setup.copy(solo = false), 1, false), RoleplayPrompt.system(setup.copy(solo = false), 1, false, state, "x"))
        val ask = RoleplayPrompt.decisions(setup, 0, "Anna steht im Leuchtturm und lauscht.", state)[1].content
        assertTrue("STORY:" in ask && "RISIKO:" in ask && "UMWEG:" in ask && "Tom, Mara" in ask && "Das Ende" !in ask && "Der Sturm bricht los" in ask, ask)
        assertTrue("[Name +1]" in ask)
        assertEquals("Verwundungen ●○○ · Tag 2 · falsche Entscheidungen heute 0/3 · Tom +3 · Mara -1", Solo.statusLine(state, true))
    }
}
