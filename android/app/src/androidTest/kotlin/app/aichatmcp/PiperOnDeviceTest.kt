package app.aichatmcp

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aichatmcp.core.DocKind
import app.aichatmcp.core.DocText
import app.aichatmcp.core.PiperStore
import app.aichatmcp.core.ReadItem
import app.aichatmcp.core.ReadPlan
import app.aichatmcp.core.Reader
import app.aichatmcp.core.ReaderEvents
import app.aichatmcp.core.ReaderPictures
import app.aichatmcp.speech.SpeechReaderOutput
import app.aichatmcp.core.Role
import app.aichatmcp.core.Utterance
import app.aichatmcp.speech.SpeechOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Piper-Stimmen auf dem echten Gerät (sherpa-onnx, JNI, AudioTrack). Braucht eine installierte Stimme in `files/piper`
 * (per `run-as app.aichatmcp cp -r` hineinkopiert, nicht im Repo); ohne sie wird nur ein Hinweis ins Log geschrieben.
 * Die Wiedergabe läuft mit Lautstärke 0, damit der Test nichts hörbar abspielt. Log-Tag: AiChatPiper.
 */
@RunWith(AndroidJUnit4::class)
class PiperOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PiperStore(File(context.filesDir, "piper"))
    private val text = "Der Hafen von Rotterdam ist der größte Seehafen Europas. Jährlich werden dort mehr als vierhundert Millionen Tonnen Güter umgeschlagen."

    private fun output() = SpeechOutput(context).apply { piperStore = store; piperThreads = { 4 }; piperVolume = 0f }

    /**
     * Geräte ohne `run-as` (Emulator): Stimmen aus `Android/data/app.aichatmcp/files/piper-test` (per adb dorthin kopiert, Ordner wie
     * `files/piper`) übernehmen, solange noch keine installiert ist.
     */
    private fun stageFromExternal() {
        if (store.list().isNotEmpty()) return
        val src = File(context.getExternalFilesDir(null), "piper-test")
        if (src.isDirectory) src.copyRecursively(File(context.filesDir, "piper"), overwrite = true)
    }

    init {
        stageFromExternal()
    }

    @Test
    fun piperVoiceSynthesizesSpeechAndMatchesLanguage() {
        val voice = store.list().firstOrNull()
        if (voice == null) { Log.i("AiChatPiper", "Keine Piper-Stimme in files/piper: Test übersprungen"); return }
        assertNull(store.problem(voice.name))
        val out = output()
        try {
            assertEquals(voice.name, out.piperFor(voice.id, "de-DE"))
            assertNull(out.piperFor(voice.id, "fr-FR")) // andere Sprache: Systemstimme
            assertNull(out.piperFor("de-de-x-nfh-local", "de-DE")) // keine Piper-Stimme
            assertNull(out.piperFor("piper:gibt-es-nicht", "de-DE"))
            val t0 = System.nanoTime()
            val first = out.renderPiper(voice.name, "Hallo.")
            val loadAndFirst = (System.nanoTime() - t0) / 1e6
            assertNotNull(first)
            val t1 = System.nanoTime()
            val (samples, rate) = out.renderPiper(voice.name, text)!!
            val seconds = samples.size.toDouble() / rate
            val took = (System.nanoTime() - t1) / 1e9
            Log.i("AiChatPiper", "Laden+erster Satz: %.0f ms; %d Zeichen => %.2f s Audio (%d Hz) in %.2f s, Echtzeitfaktor %.2f".format(loadAndFirst, text.length, seconds, rate, took, took / seconds))
            assertTrue("Zu kurz: $seconds s", seconds > 5)
            assertTrue("Stille", samples.maxOf { kotlin.math.abs(it) } > 0.05f)
        } finally {
            out.shutdown()
        }
    }

    /** Jede installierte Stimme lädt und liefert hörbares Audio; Laden und Tempo je Stimme stehen im Log. */
    @Test
    fun everyInstalledVoiceSynthesizes() {
        val voices = store.list()
        if (voices.isEmpty()) { Log.i("AiChatPiper", "Keine Piper-Stimme in files/piper: Test übersprungen"); return }
        val out = output()
        try {
            for (voice in voices) {
                val t0 = System.nanoTime()
                val (samples, rate) = out.renderPiper(voice.name, text) ?: throw AssertionError("Stimme ${voice.name} nicht ladbar: ${store.problem(voice.name)}")
                val took = (System.nanoTime() - t0) / 1e9
                val seconds = samples.size.toDouble() / rate
                Log.i("AiChatPiper", "%s (%s, %d Hz): %.2f s Audio in %.2f s (inkl. Laden), Faktor %.2f".format(voice.name, voice.language, rate, seconds, took, took / seconds))
                assertTrue("${voice.name}: zu kurz ($seconds s)", seconds > 4)
                assertTrue("${voice.name}: Stille", samples.maxOf { kotlin.math.abs(it) } > 0.05f)
            }
        } finally {
            out.shutdown()
        }
    }

    /** Der Vorleser-Weg: Abschnitte anhängen, Rückmeldungen kommen der Reihe nach und erst nach dem Abspielen. */
    @Test
    fun playbackReportsEachItemInOrderAfterItWasPlayed() {
        val voice = store.list().firstOrNull()
        if (voice == null) { Log.i("AiChatPiper", "Keine Piper-Stimme in files/piper: Test übersprungen"); return }
        val out = output()
        out.voice = voice.id
        val done = CopyOnWriteArrayList<String>()
        val times = CopyOnWriteArrayList<Long>()
        val all = CountDownLatch(2)
        out.setItemSink(object : SpeechOutput.ItemSink {
            override fun done(id: String) { done += id; times += System.nanoTime(); all.countDown() }
            override fun failed(id: String) { done += "FAILED:$id"; all.countDown() }
        })
        try {
            val start = System.nanoTime()
            out.speakItem("Das ist der erste Abschnitt, er hat zwei kurze Sätze. Und hier der zweite Satz.", "r:1:0", "de-DE", true) { Log.w("AiChatPiper", it) }
            out.speakItem("Hier folgt der zweite Abschnitt mit einem weiteren Satz.", "r:1:1", "de-DE", false) { Log.w("AiChatPiper", it) }
            assertTrue("Keine Rückmeldung für beide Abschnitte: $done", all.await(60, TimeUnit.SECONDS))
            assertEquals(listOf("r:1:0", "r:1:1"), done.toList())
            Log.i("AiChatPiper", "Abschnitt 1 nach %.1f s, Abschnitt 2 nach %.1f s gehört".format((times[0] - start) / 1e9, (times[1] - start) / 1e9))
            assertTrue("Abschnitt 2 nicht nach Abschnitt 1", times[1] > times[0])
        } finally {
            out.shutdown()
        }
    }

    /** Stopp verwirft Laufendes: danach kommt keine Rückmeldung mehr. */
    @Test
    fun stopDiscardsPendingItems() {
        val voice = store.list().firstOrNull()
        if (voice == null) { Log.i("AiChatPiper", "Keine Piper-Stimme in files/piper: Test übersprungen"); return }
        val out = output()
        out.voice = voice.id
        val done = CopyOnWriteArrayList<String>()
        out.setItemSink(object : SpeechOutput.ItemSink {
            override fun done(id: String) { done += id }
            override fun failed(id: String) { done += "FAILED:$id" }
        })
        try {
            out.speakItem(text, "r:2:0", "de-DE", true) { }
            Thread.sleep(1500)
            out.stopItems()
            Thread.sleep(6000)
            assertTrue("Nach dem Stopp kam noch eine Rückmeldung: $done", done.isEmpty())
        } finally {
            out.shutdown()
        }
    }

    /**
     * Mehrere Stimmen in einem Abschnitt (Erzähler, männliche und weibliche Rede): die Rückmeldung kommt einmal, erst nach dem letzten Stück, und
     * auch eine Stimme mit anderer Abtastrate (low = 16 kHz) läuft im selben AudioTrack durch.
     */
    @Test
    fun partsWithSeveralVoicesReportOnceAfterTheLastPart() {
        val voices = store.list()
        if (voices.size < 2) { Log.i("AiChatPiper", "Weniger als zwei Piper-Stimmen: Test übersprungen"); return }
        val narrator = voices.first()
        val other = voices.firstOrNull { it.name.contains("low") && it.name != narrator.name } ?: voices[1]
        val out = output()
        out.voice = narrator.id
        out.voiceMale = other.id
        out.voiceFemale = "" // leer: der Erzähler spricht auch die weibliche Rede
        val done = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        out.setItemSink(object : SpeechOutput.ItemSink {
            override fun done(id: String) { done += id; latch.countDown() }
            override fun failed(id: String) { done += "FAILED:$id"; latch.countDown() }
        })
        try {
            val start = System.nanoTime()
            out.speakItemParts(
                listOf(
                    Utterance("Das sagte der Erzähler.", Role.NARRATOR), Utterance("Hallo, wie geht es dir?", Role.MALE),
                    Utterance("Antwortete sie leise.", Role.NARRATOR), Utterance("Gut, danke.", Role.FEMALE),
                ),
                "r:4:0", "de-DE", true,
            ) { Log.w("AiChatPiper", it) }
            assertTrue("Keine Rückmeldung: $done", latch.await(90, TimeUnit.SECONDS))
            assertEquals(listOf("r:4:0"), done.toList())
            Log.i("AiChatPiper", "Mehrstimmig (%s + %s): gehört nach %.1f s".format(narrator.name, other.name, (System.nanoTime() - start) / 1e9))
            assertTrue("Zu schnell fertig: Abschnitt nicht abgespielt?", (System.nanoTime() - start) / 1e9 > 4)
        } finally {
            out.shutdown()
        }
    }

    /** Die ganze Kette wie im Betrieb: Dialogdokument -> Ablauf mit Rollen -> Vorleser -> Sprachausgabe mit zwei Stimmen -> Ende. */
    @Test
    fun readerReadsADialogueDocumentWithTwoVoicesToTheEnd() {
        val voices = store.list()
        if (voices.size < 2) { Log.i("AiChatPiper", "Weniger als zwei Piper-Stimmen: Test übersprungen"); return }
        val out = output()
        out.voice = voices[0].id
        out.voiceMale = voices[1].id
        out.voiceFemale = voices[0].id
        val text = "„Kommst du heute Abend?“, fragte Anna.\n\n„Ja, gern“, sagte Thomas.\n\nSie gingen gemeinsam los."
        val plan = ReadPlan.of(DocText("t.md", DocKind.MARKDOWN, text), dialogue = true)
        val roles = (plan.items.single() as ReadItem.Speech).parts.map { it.role.name }
        Log.i("AiChatPiper", "Rollen: $roles")
        assertEquals(listOf("FEMALE", "NARRATOR", "MALE", "NARRATOR"), roles)
        val finished = CountDownLatch(1)
        val pictures = object : ReaderPictures {
            override fun describe(picture: ReadItem.Picture, done: (String?) -> Unit) = done(null)
            override fun prefetch(picture: ReadItem.Picture) {}
            override fun cancel() {}
        }
        val events = object : ReaderEvents { override fun onFinished() { finished.countDown() } }
        lateinit var reader: Reader
        InstrumentationRegistry.getInstrumentation().runOnMainSync { reader = Reader(plan, SpeechReaderOutput(out) { Log.w("AiChatPiper", it) }, pictures, events, "de-DE") }
        out.setItemSink(object : SpeechOutput.ItemSink {
            override fun done(id: String) = reader.utteranceDone(id)
            override fun failed(id: String) = reader.utteranceFailed(id)
        })
        try {
            val start = System.nanoTime()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { reader.play() }
            assertTrue("Vorlesen wurde nicht fertig", finished.await(120, TimeUnit.SECONDS))
            Log.i("AiChatPiper", "Dialogdokument gelesen in %.1f s".format((System.nanoTime() - start) / 1e9))
        } finally {
            out.shutdown()
        }
    }
}

