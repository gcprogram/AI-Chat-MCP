package app.aichatmcp.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun plan(vararg texts: String, paragraphStart: Boolean = true): ReadPlan =
    ReadPlan(texts.map { ReadItem.Speech(it).also { s -> s.paragraphStart = paragraphStart } }, listOf(0))

private fun parts(p: ReadPlan, i: Int = 0): List<Pair<String, Role>> = (p.items[i] as ReadItem.Speech).parts.map { it.text to it.role }

class DialogueTest {
    @Test
    fun quoteStyleIsDetectedFromTheText() {
        assertEquals("„", QuoteStyle.detect("Er sagte: „Hallo“.")!!.opens)
        assertEquals("»", QuoteStyle.detect("Er sagte: »Hallo«.")!!.opens) // nach innen gesetzt
        assertEquals("«", QuoteStyle.detect("Il dit « bonjour ».")!!.opens) // nach außen gesetzt
        assertEquals("“", QuoteStyle.detect("He said “hello”.")!!.opens)
        assertTrue(QuoteStyle.detect("He said \"hello\" and \"bye\".")!!.toggle)
        assertNull(QuoteStyle.detect("Kein einziges Anführungszeichen hier."))
    }

    @Test
    fun speakerComesFromTheDialogueTag() {
        val p = plan("„Kommst du heute Abend?“, fragte sie. „Ja“, sagte er leise. Dann gingen sie nach Hause.")
        val d = Dialogue.analyze(p)
        assertEquals(listOf(Gender.FEMALE, Gender.MALE), d.spans.map { it.gender })
        assertEquals(listOf(Source.TAG, Source.TAG), d.spans.map { it.source })
        assertEquals(
            listOf(
                "Kommst du heute Abend?" to Role.FEMALE, "fragte sie." to Role.NARRATOR, "Ja" to Role.MALE,
                "sagte er leise. Dann gingen sie nach Hause." to Role.NARRATOR,
            ),
            parts(p),
        )
    }

    @Test
    fun firstNamesAndTitlesDecideToo() {
        val p = plan("Anna sagte: „Das ist schön.“ Thomas erwiderte: „Finde ich auch.“ Herr Müller brummte: „Hm.“ Die Mutter rief: „Essen!“")
        val genders = Dialogue.analyze(p).spans.map { it.gender }
        assertEquals(listOf(Gender.FEMALE, Gender.MALE, null, Gender.FEMALE), genders) // "brummte" ist kein Verb der Liste: bleibt offen
    }

    @Test
    fun newParagraphMeansTheOtherSpeaker() {
        val p = plan("„Wie geht es dir?“, fragte Anna.\n„Gut.“\n„Und dir?“\n„Auch gut.“")
        val d = Dialogue.analyze(p)
        assertEquals(listOf(Gender.FEMALE, Gender.MALE, Gender.FEMALE, Gender.MALE), d.spans.map { it.gender })
        assertEquals(listOf(Source.TAG, Source.FLIP, Source.FLIP, Source.FLIP), d.spans.map { it.source })
    }

    @Test
    fun sameParagraphKeepsTheSpeaker() {
        val d = Dialogue.analyze(plan("„Ja“, sagte sie, „ich komme gleich.“"))
        assertEquals(listOf(Gender.FEMALE, Gender.FEMALE), d.spans.map { it.gender })
        val e = Dialogue.analyze(plan("Anna schwieg.\n„Warte“, sagte Thomas. „Ich auch.“"))
        assertEquals(listOf(Gender.MALE, Gender.MALE), e.spans.map { it.gender }) // zweite Rede gleich: derselbe Absatz, dicht dahinter
    }

    @Test
    fun answersBeforeTheQuestionAreResolvedBackwards() {
        val d = Dialogue.analyze(plan("„Heute nicht.“\n„Warum nicht?“, fragte er."))
        assertEquals(listOf(Gender.FEMALE, Gender.MALE), d.spans.map { it.gender })
        assertEquals(Source.FLIP, d.spans[0].source)
    }

    @Test
    fun pluralAndUnknownSpeakersStayWithTheNarrator() {
        val plural = Dialogue.analyze(plan("„Wir gehen“, sagten sie."))
        assertNull(plural.spans.single().gender) // "sie sagten": Mehrzahl
        val none = plan("„Hallo?“ Niemand antwortete.")
        val d = Dialogue.analyze(none)
        assertEquals(listOf(0), d.unresolved())
        assertEquals(listOf("Hallo? Niemand antwortete." to Role.NARRATOR), parts(none))
        assertEquals(Triple("", "Hallo?", " Niemand antwortete."), d.context(0))
    }

    @Test
    fun aSpeechAcrossTwoSectionsKeepsItsSpeaker() {
        val p = ReadPlan(
            listOf(
                ReadItem.Speech("Er sagte: „Das ist ein langer Satz, der"),
                ReadItem.Speech("hier weitergeht.“ Sie nickte.").also { it.paragraphStart = false },
            ),
            listOf(0),
        )
        Dialogue.analyze(p)
        assertEquals(listOf("Er sagte:" to Role.NARRATOR, "Das ist ein langer Satz, der" to Role.MALE), parts(p, 0))
        assertEquals(listOf("hier weitergeht." to Role.MALE, "Sie nickte." to Role.NARRATOR), parts(p, 1))
    }

    @Test
    fun aStrayQuoteDoesNotSwallowTheRestOfTheText() {
        val d = Dialogue.analyze(plan("Er sagte: „Hallo " + "und so weiter ".repeat(150) + "Ende. Danach fragte sie: „Wirklich?“"))
        assertEquals(1, d.spans.size) // nur die vollständige zweite Rede
        assertEquals(Gender.FEMALE, d.spans[0].gender)
    }

    @Test
    fun laterAssignmentUpdatesTheSections() {
        val p = plan("„Hallo?“ Niemand antwortete.")
        val d = Dialogue.analyze(p)
        assertTrue(d.assign(0, Gender.MALE))
        assertEquals(listOf("Hallo?" to Role.MALE, "Niemand antwortete." to Role.NARRATOR), parts(p))
        assertEquals(false, d.assign(0, Gender.FEMALE)) // schon zugeordnet
        assertEquals(Source.JUDGE, d.spans[0].source)
    }

    @Test
    fun namesAroundAUnresolvedSpeechAreOfferedForTheJudge() {
        val d = Dialogue.analyze(plan("Dann kam Zorbo näher. „Hallo?“ Zorbo lachte leise."))
        assertEquals(listOf("Zorbo"), d.namesAround(0))
    }

    @Test
    fun paragraphsKeepRealBoundaries() {
        val raw = "Erster Absatz\nmit Umbruch und Rück-\nkehr.\n\nZweiter Absatz.\n\nDritter ohne Punkt"
        assertEquals(listOf("Erster Absatz mit Umbruch und Rückkehr.", "Zweiter Absatz.", "Dritter ohne Punkt."), DialogueText.paragraphs(raw))
        // ohne Leerzeilen: jede Zeile ein Absatz (Dialoge)
        assertEquals(listOf("„Hallo.“", "„Tag.“"), DialogueText.paragraphs("„Hallo.“\n„Tag.“"))
    }

    @Test
    fun chunksJoinParagraphsWithLineBreaks() {
        val chunks = DialogueText.chunks(listOf("Eins eins.\n\nZwei zwei.\n\nDrei drei."), 25)
        assertEquals(listOf("Eins eins.\nZwei zwei." to true, "Drei drei." to true), chunks)
        val long = DialogueText.chunks(listOf("Satz eins hier. Satz zwei hier. Satz drei hier."), 20)
        assertEquals(listOf(true, false, false), long.map { it.second }) // Absatzanfang nur beim ersten Stück
    }

    @Test
    fun readPlanInDialogueModeBuildsPartsAndLeavesNormalModeAlone() {
        val text = "„Kommst du?“, fragte Anna.\n\n„Ja“, sagte Thomas.\n\n„Dann los.“"
        val normal = ReadPlan.of(DocText("t.md", DocKind.MARKDOWN, text))
        assertNull(normal.dialogue)
        assertTrue((normal.items.single() as ReadItem.Speech).parts.isEmpty())
        val withDialogue = ReadPlan.of(DocText("t.md", DocKind.MARKDOWN, text), dialogue = true)
        assertNotNull(withDialogue.dialogue)
        assertEquals(
            listOf(
                "Kommst du?" to Role.FEMALE, "fragte Anna." to Role.NARRATOR, "Ja" to Role.MALE, "sagte Thomas." to Role.NARRATOR,
                "Dann los." to Role.FEMALE,
            ),
            parts(withDialogue),
        )
    }

    @Test
    fun readerSpeaksPartsWithTheirRoles() {
        class Out : ReaderOutput {
            val spoken = mutableListOf<Pair<String, List<Utterance>>>()
            override fun speak(text: String, id: String, language: String?, flush: Boolean) { spoken += id to emptyList() }
            override fun speakParts(parts: List<Utterance>, id: String, language: String?, flush: Boolean) { spoken += id to parts }
            override fun stopSpeaking() {}
        }
        val pictures = object : ReaderPictures {
            override fun describe(picture: ReadItem.Picture, done: (String?) -> Unit) = done(null)
            override fun prefetch(picture: ReadItem.Picture) {}
            override fun cancel() {}
        }
        val p = ReadPlan.of(DocText("t.md", DocKind.MARKDOWN, "„Ja“, sagte er.\n\nDas war alles."), dialogue = true)
        val out = Out()
        val reader = Reader(p, out, pictures, object : ReaderEvents {}, "de-DE")
        reader.play()
        assertEquals(1, out.spoken.size)
        assertEquals(listOf(Role.MALE, Role.NARRATOR), out.spoken[0].second.map { it.role })
        // ohne Dialogmodus bleibt es beim bisherigen Weg
        val plain = Out()
        Reader(ReadPlan.of(DocText("t.md", DocKind.MARKDOWN, "Nur Text hier.")), plain, pictures, object : ReaderEvents {}, "de-DE").play()
        assertEquals(emptyList(), plain.spoken[0].second)
    }

    @Test
    fun judgeAnswerIsParsedLeniently() {
        assertEquals(Gender.MALE, SpeakerJudge.parse("M"))
        assertEquals(Gender.MALE, SpeakerJudge.parse("  male"))
        assertEquals(Gender.FEMALE, SpeakerJudge.parse("F."))
        assertEquals(Gender.FEMALE, SpeakerJudge.parse("w"))
        assertNull(SpeakerJudge.parse("?"))
        assertNull(SpeakerJudge.parse(""))
        assertNull(SpeakerJudge.parse("Ich weiß es nicht"))
    }

    @Test
    fun judgeAsksTheServerAndFillsTheGap() = runBlocking {
        val asked = mutableListOf<List<WireMessage>>()
        val backend = object : LlmBackend {
            override fun streamChat(
                model: String, messages: List<WireMessage>, temperature: Double, maxTokens: Int,
                stop: List<String>, cancel: CancelToken, extra: Map<String, Double>,
            ): Flow<StreamEvent> {
                asked += messages
                return flowOf(StreamEvent.Reasoning("denke nach"), StreamEvent.Content("F"), StreamEvent.Content("emale"))
            }
        }
        val p = plan("„Hallo?“ Niemand antwortete.")
        val d = Dialogue.analyze(p)
        val (before, quote, after) = d.context(d.unresolved().single())
        val gender = SpeakerJudge(backend, "m").ask(before, quote, after)
        assertEquals(Gender.FEMALE, gender)
        assertTrue("«Hallo?»" in asked.single()[1].content && "Niemand antwortete" in asked.single()[1].content)
        assertTrue(d.assign(0, gender!!))
        assertEquals(listOf("Hallo?" to Role.FEMALE, "Niemand antwortete." to Role.NARRATOR), parts(p))
    }

    @Test
    fun unresolvedFromPutsTheCurrentPositionFirst() {
        val items = listOf(ReadItem.Speech("„Eins?“ Stille."), ReadItem.Speech("„Zwei?“ Stille."), ReadItem.Speech("„Drei?“ Stille."))
        val d = Dialogue.analyze(ReadPlan(items, listOf(0)))
        assertEquals(listOf(0, 1, 2), d.unresolved())
        assertEquals(listOf(1, 2, 0), d.unresolvedFrom(items[1]))
    }

    @Test
    fun personaKeepsRoleVoices() {
        val persona = Persona.fromJson("""{"name": "Fern", "voice": "piper:a", "voice_male": "piper:b", "voice_female": "piper:c"}""", "fern")
        assertEquals(Triple("piper:a", "piper:b", "piper:c"), Triple(persona.voice, persona.voiceMale, persona.voiceFemale))
        assertEquals(persona.voiceMale, Persona.fromJson(persona.toJson()).voiceMale)
        assertEquals("", Persona.fromJson("""{"name": "Alt"}""").voiceFemale)
    }
}

