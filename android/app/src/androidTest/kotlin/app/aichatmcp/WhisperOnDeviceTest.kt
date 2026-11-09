package app.aichatmcp

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aichatmcp.core.PiperStore
import app.aichatmcp.core.WhisperStore
import app.aichatmcp.core.studio.AudioMath
import app.aichatmcp.speech.SpeechOutput
import app.aichatmcp.speech.WhisperInput
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whisper auf dem echten Gerät (sherpa-onnx, JNI). Braucht ein Whisper-Modell und eine deutsche Piper-Stimme in `files/whisper` bzw. `files/piper` (auf dem S21 per `run-as … tar`); alternativ wird beides aus
 * `Android/data/app.aichatmcp/files/whisper-test/<Ordner>` bzw. `piper-test` übernommen (nur Emulator, nicht im Repo),
 * sonst steht nur ein Hinweis im Log und der Test gilt als bestanden. Er spricht einen Satz mit Piper, rechnet auf 16 kHz um, lässt
 * Whisper den Satz erkennen und misst die Zeit (Echtzeitfaktor = Rechenzeit / Audiolänge). Das Mikrofon selbst wird nicht geprüft.
 * Log-Tag: AiChatWhisper.
 */
@RunWith(AndroidJUnit4::class)
class WhisperOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val whisper = WhisperStore(File(context.filesDir, "whisper"))
    private val piper = PiperStore(File(context.filesDir, "piper"))
    private val text = "Der Hafen von Rotterdam ist der größte Seehafen Europas. Jährlich werden dort viele Millionen Tonnen Güter umgeschlagen."

    init {
        stage("whisper-test", File(context.filesDir, "whisper"))
        stage("piper-test", File(context.filesDir, "piper"))
    }

    private fun stage(external: String, dst: File) {
        File(context.getExternalFilesDir(null), external).listFiles().orEmpty()
            .filter { it.isDirectory && !File(dst, it.name).exists() }.forEach { it.copyRecursively(File(dst, it.name), overwrite = true) }
    }

    private fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.length > 3 }.toSet()

    @Test
    fun whisperRecognizesASpokenSentenceAndWeMeasureTheSpeed() {
        val model = whisper.list().firstOrNull()
        val voice = piper.list().firstOrNull { it.language == "de" }
        if (model == null || voice == null) { Log.i("AiChatWhisper", "Kein Whisper-Modell oder keine deutsche Piper-Stimme: Test übersprungen"); return }
        val out = SpeechOutput(context).apply { piperStore = piper; piperThreads = { 4 }; piperVolume = 0f }
        val input = WhisperInput(whisper) { 4 }
        try {
            val (samples, rate) = out.renderPiper(voice.name, text)!!
            val audio = AudioMath.resample(samples, rate, 16000)
            val seconds = audio.size / 16000.0
            val t0 = System.nanoTime()
            val first = input.transcribe(model, "de", audio)
            val loadAndFirst = (System.nanoTime() - t0) / 1e9
            val t1 = System.nanoTime()
            val second = input.transcribe(model, "de", audio)
            val took = (System.nanoTime() - t1) / 1e9
            Log.i("AiChatWhisper", "${model.name}: %.1f s Audio; Laden+erste Erkennung %.1f s, zweite %.1f s (Faktor %.2f)".format(seconds, loadAndFirst, took, took / seconds))
            Log.i("AiChatWhisper", "Erkannt: $first")
            val expected = words(text)
            val hit = words(first).intersect(expected).size
            assertTrue("Zu wenig erkannt ($hit von ${expected.size}): $first", hit >= expected.size * 0.6)
            assertTrue("Zweite Erkennung weicht ab: $second", words(second).intersect(expected).size >= expected.size * 0.6)
        } finally {
            input.release()
            out.shutdown()
        }
    }
}
