package app.aichatmcp.voicestudio

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aichatmcp.core.studio.AudioMath
import app.aichatmcp.core.studio.SentenceSplitter
import app.aichatmcp.core.studio.StudioProject
import app.aichatmcp.core.studio.TARGET_RATE
import app.aichatmcp.core.studio.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Das Studio auf dem echten Gerät: Satzzerleger mit der Regex-Laufzeit von Android (ICU), Aufnahme vom echten Mikrofon,
 * Umrechnung, Speichern, Prüfen und ZIP-Export. Die Wiedergabe läuft stumm. Braucht die Mikrofon-Berechtigung
 * (`adb shell pm grant app.aichatmcp.voicestudio android.permission.RECORD_AUDIO`), sonst wird nur der Rest geprüft. Log-Tag: AiChatStudio.
 */
@RunWith(AndroidJUnit4::class)
class StudioOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun splitterWorksOnAndroidRegex() {
        val text = "Das war Dr. Meier, wie er leibt und lebt. „Kommst du heute Abend?“, fragte sie leise. Er antwortete nicht! " +
            "Warum auch,\ndachte er; es war ohnehin alles gesagt. Am 12. Mai war es so weit. Ein Hund, z. B. ein Dackel, " +
            "bellte laut im Hof.\n\nNach der Rück-\nkehr aß man zu Abend. Ja. Nach der Rückkehr aß man zu Abend."
        assertEquals(
            listOf(
                "Das war Dr. Meier, wie er leibt und lebt.", "Kommst du heute Abend? fragte sie leise.",
                "Warum auch, dachte er; es war ohnehin alles gesagt.", "Ein Hund, z. B. ein Dackel, bellte laut im Hof.",
                "Nach der Rückkehr aß man zu Abend.",
            ),
            SentenceSplitter.split(text),
        )
        // die mitgelieferten Satzlisten sind als Assets da und enthalten Sätze
        val de = context.assets.open("sentences/sentences_de.txt").bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val en = context.assets.open("sentences/sentences_en.txt").bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(de.size > 100 && en.size > 100)
    }

    @Test
    fun recordsFromTheMicrophoneAndExports() {
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        assumeTrue("Mikrofon-Berechtigung fehlt: adb shell pm grant app.aichatmcp.voicestudio android.permission.RECORD_AUDIO", granted)
        val levels = mutableListOf<Float>()
        val recorder = Recorder(context) { levels += it }
        assertEquals(null, recorder.start())
        Thread.sleep(1800)
        val cap = recorder.stop()!!
        Log.i("AiChatStudio", "Quelle ${recorder.sourceName}, Rate ${cap.rate}, ${cap.samples.size} Samples = ${cap.samples.size.toDouble() / cap.rate} s, Spitze ${AudioMath.peak(cap.samples, cap.samples.size)}")
        assertTrue(cap.rate == 44100 || cap.rate == 48000)
        assertEquals(1.8 - Recorder.SKIP_START_MS / 1000.0, cap.samples.size.toDouble() / cap.rate, 0.4)
        assertTrue("Mikrofon liefert nur Nullen", cap.samples.any { it.toInt() != 0 })

        val started = System.nanoTime()
        val resampled = AudioMath.resample(AudioMath.toFloat(cap.samples), cap.rate, TARGET_RATE)
        val trimmed = AudioMath.trimSilence(resampled, TARGET_RATE)
        Log.i("AiChatStudio", "Umrechnen+Kürzen: %.0f ms, %d -> %d Samples".format((System.nanoTime() - started) / 1e6, cap.samples.size, trimmed.size))
        assertEquals(cap.samples.size.toDouble() * TARGET_RATE / cap.rate, resampled.size.toDouble(), 3.0)

        // Speichern im Projekt, Prüfen, Export
        val dir = File(context.cacheDir, "studio-test").apply { deleteRecursively() }
        val project = StudioProject(File(dir, "testprojekt"))
        project.addSentences(listOf("Das ist ein Testsatz für die Aufnahme."))
        val rec = project.save("Das ist ein Testsatz für die Aufnahme.", AudioMath.toShort(trimmed))
        val back = Wav.decode(project.wavFile(rec.name).readBytes())
        assertEquals(TARGET_RATE, back.rate)
        assertEquals(trimmed.size, back.samples.size)
        val report = project.check()
        Log.i("AiChatStudio", "Prüfung: ${report.errors} / ${report.notes}")
        assertTrue(report.errors.any { "zu wenig" in it }) // eine einzige Aufnahme: viel zu wenig Minuten
        val zip = File(dir, "export.zip")
        assertEquals(1, zip.outputStream().use { project.exportZip(it) })
        assertTrue(zip.length() > 1000)
        Log.i("AiChatStudio", "Export: ${zip.absolutePath} (${zip.length()} Bytes)")

        // Wiedergabe stumm: der Rückruf muss kommen
        val done = CountDownLatch(1)
        Player().play(AudioMath.toShort(trimmed), TARGET_RATE, volume = 0f) { done.countDown() }
        assertTrue("Wiedergabe meldet kein Ende", done.await(10, TimeUnit.SECONDS))
    }
}
