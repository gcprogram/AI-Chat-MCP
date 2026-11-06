package app.aichatmcp

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aichatmcp.core.PiperStore
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
}
