package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenderTest {
    @Test
    fun titlesAndFirstNamesTellTheGender() {
        assertEquals(Gender.FEMALE, Genders.ofName("Mrs Andrews"))
        assertEquals(Gender.MALE, Genders.ofName("Mr. Lockwood"))
        assertEquals(Gender.FEMALE, Genders.ofName("Frau Berger"))
        assertEquals(Gender.MALE, Genders.ofName("Herr Müller"))
        assertEquals(Gender.FEMALE, Genders.ofName("Lucy Carlyle"))
        assertEquals(Gender.MALE, Genders.ofName("George"))
        assertNull(Genders.ofName("Kim")) // mehrdeutig
        assertNull(Genders.ofName("Xyz"))
        assertEquals(Gender.FEMALE, Genders.ofTitle("Miss"))
        assertEquals(Gender.MALE, Genders.ofTitle("Mister"))
    }

    @Test
    fun pronounsAfterTheNameDecideWhenTheNameIsUnknown() {
        val she = "Quill kam herein. Sie lächelte. Später sah Quill auf die Uhr, sie seufzte. Quill ging. Ihr Mantel war nass. Quill blieb stehen und sie sagte nichts."
        assertEquals(Gender.FEMALE, Genders.ofCharacter("Quill", she))
        val he = "Quill came in. He smiled. Later Quill looked at the clock and he sighed. Quill left, his coat was wet. Quill stopped and he said nothing."
        assertEquals(Gender.MALE, Genders.ofCharacter("Quill", he))
        assertNull(Genders.ofCharacter("Quill", "Quill kam herein.")) // keine Anhaltspunkte
        // eine Anrede im Text zählt mehr als alles andere
        assertEquals(Gender.FEMALE, Genders.ofCharacter("Zorn", "Mrs Zorn öffnete die Tür. Mrs Zorn nickte."))
        // der Ich-Erzähler Lucy wird über den Namen erkannt
        assertEquals(Gender.FEMALE, Genders.ofCharacter("Lucy", "Ich sah Lockwood an. Lucy, sagte er, komm."))
    }

    @Test
    fun companionsAreClassifiedFromNameAndDescription() {
        assertEquals(Gender.FEMALE, Genders.ofPersona("Mia", "Du bist eine freundliche, neugierige und humorvolle Gesprächspartnerin."))
        assertEquals(Gender.FEMALE, Genders.ofPersona("Zero", "Du bist eine futuristische Androidin und die Sekretärin des Nutzers."))
        assertEquals(Gender.MALE, Genders.ofPersona("Professor Quark", "Du bist ein verrückter Professor, er erfindet gern Dinge."))
        assertEquals(Gender.FEMALE, Genders.ofPersona("Oma Gerda", "Du bist eine herzliche Großmutter."))
        assertNull(Genders.ofPersona("Fern", "Du bist ein ruhiger Begleiter für Spaziergänge im Wald."))
        assertEquals(Gender.FEMALE, Genders.parse("female"))
        assertEquals(Gender.MALE, Genders.parse("male"))
        assertNull(Genders.parse(""))
        assertEquals("female", Genders.key(Gender.FEMALE))
        assertEquals("", Genders.key(null))
    }

    @Test
    fun theModelFallbackAsksForOneLetter() {
        val prompt = GenderGuess.prompt("Quill", "Quill kam herein.")
        assertTrue("Quill" in prompt.last().content && prompt.first().role == "system")
        assertEquals(Gender.FEMALE, GenderGuess.parse("F"))
        assertEquals(Gender.MALE, GenderGuess.parse("Male? M"))
        assertNull(GenderGuess.parse("?"))
        val sample = GenderGuess.sample("Quill", "Anderer Absatz ohne Namen.\n\nQuill kam herein.\n\nNoch ein Absatz.")
        assertTrue("Quill kam herein." in sample && "Anderer" !in sample)
    }

    @Test
    fun englishSpeechTagsAreUnderstoodByTheDialogueRules() {
        assertEquals(Gender.FEMALE, Dialogue.tagGender(" said Mrs Andrews.", false))
        assertEquals(Gender.MALE, Dialogue.tagGender(", he said quietly.", false))
        assertEquals(Gender.FEMALE, Dialogue.tagGender(", she whispered.", false))
        assertEquals(Gender.MALE, Dialogue.tagGender(" Mr Lockwood replied.", false))
        assertEquals(Gender.FEMALE, Dialogue.tagGender(", sagte sie leise.", false)) // Deutsch wie zuvor
    }

    @Test
    fun theDefaultVoiceFollowsLanguageAndGender() {
        val c = Config().withDefaultVoice("de-DE", "narrator", "piper:thorsten").withDefaultVoice("de-DE", "female", "piper:ramona")
        assertEquals("piper:thorsten", c.defaultVoice("de-DE", null))
        assertEquals("piper:ramona", c.defaultVoice("de-DE", Gender.FEMALE))
        assertEquals("piper:thorsten", c.defaultVoice("de-DE", Gender.MALE)) // keine eigene Männerstimme: der Erzähler
        assertEquals("", c.defaultVoice("en-US", Gender.FEMALE))
        assertEquals(c.defaultVoices, Config.fromJson(c.toJson()).defaultVoices)
        assertEquals("", c.withDefaultVoice("de-DE", "female", "").defaultVoices["de/female"].orEmpty())
        assertEquals(1.2, Config().speechRate)
    }

    @Test
    fun titlesAreSpokenForNonEnglishVoices() {
        val s = Speech.forSpeech("Mrs Andrews sagte zu Mr. Lockwood: Ms Smith ist da, MR ist kein Name.", "de-DE")
        assertEquals("Missis Andrews sagte zu Mister Lockwood: Miss Smith ist da, MR ist kein Name.", s.text)
        assertEquals("Mrs Andrews said", Speech.forSpeech("Mrs Andrews said", "en-US").text) // englische Stimmen kennen die Anreden
        assertEquals("Das Wort Mrs allein", Speech.forSpeech("Das Wort Mrs allein", "de-DE").text) // nur vor einem Namen
        // Fortschrittsstellen im gesprochenen Text werden auf den Ausgangstext zurückgerechnet
        val m = Speech.forSpeech("Mrs Andrews kam.", "de-DE")
        assertEquals("Missis Andrews kam.", m.text)
        assertEquals(0, m.original(0))
        assertEquals(4, m.original(m.text.indexOf("Andrews")))
        assertEquals("Mrs Andrews kam.".length, m.original(m.text.length))
        assertEquals("Mister Holmes", Speech.forSpeech("Mr Holmes", null).text)
    }

    @Test
    fun aSavedGameKeepsEverythingNeededToContinue() {
        val book = Gamebook(key = "k", title = "Mein Buch", scenes = listOf(GbScene("S1", plot = "Lucy klingelt.", place = "Vorgarten"), GbScene("S2", plot = "Es ist dunkel.")))
        val setup = RoleplaySetup(
            "Mein Buch", listOf(Scene("S1", "Lucy klingelt am Haus."), Scene("S2", "Drinnen war es dunkel.")), "", true, true, solo = true, plots = book.plots(), book = book,
            narrator = "Lucy", verbatim = true, source = true, skip = setOf(0), mode = DecisionMode.STRANDS,
        )
        val decisions = listOf(Decision(DecisionKind.STORY, "Ich gehe hinein.", mapOf("Lockwood" to 1), 1), Decision(DecisionKind.RISK, "Ich renne weg."))
        val save = SaveGame(
            title = setup.title, savedAt = 5, setup = SavedSetup.of(setup),
            run = SavedRun(1, 2, true, SavedSolo.of(SoloState(wounds = 1, day = 2, relations = mapOf("Lockwood" to 2), end = SoloEnd.WON)), decisions.map { SavedDecision.of(it) }, listOf(0, 1), listOf(1, 0), decisions.map { SavedDecision.of(it) }, 0, "Folge"),
            chat = "{}",
        )
        val json = SaveGame.toJson(save)
        assertTrue(SaveGame.isSaveGame(json))
        assertFalse(SaveGame.isSaveGame(Gamebooks.toJson(book)))
        val back = assertNotNull(SaveGame.parse(json))
        val s = back.setup.toSetup()
        assertEquals("Mein Buch", s.title)
        assertEquals(listOf("Lucy klingelt am Haus.", "Drinnen war es dunkel."), s.scenes.map { it.text })
        assertEquals("Lucy", s.narrator)
        assertTrue(s.verbatim && s.source && s.firstPerson && s.solo)
        assertEquals(setOf(0), s.skip)
        assertEquals(DecisionMode.STRANDS, s.mode)
        assertEquals("Vorgarten", s.book?.scenes?.first()?.place)
        assertEquals(1, back.run.solo.toState().wounds)
        assertEquals(SoloEnd.WON, back.run.solo.toState().end)
        assertEquals(mapOf("Lockwood" to 1), back.run.decisions.first().toDecision().relations)
        assertEquals(listOf(1, 0), back.run.order)
        assertEquals("Folge", back.run.outcome)
        assertNull(SaveGame.parse("{\"format\":\"anderes\"}"))
        assertNull(SaveGame.parse("kein json"))
        assertEquals("Mein Buch.savegame", SaveGame.fileName("Mein Buch"))
        assertEquals("a_b_c.game", BookFileNames.gameName("a/b:c"))
    }
}
