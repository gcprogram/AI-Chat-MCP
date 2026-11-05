package app.aichatmcp

import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aichatmcp.speech.SpeechOutput
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Prüft die Bordmittel für Sprache ohne Ton und ohne Mikrofon: Die Sprachsynthese schreibt in eine Datei statt
 * abzuspielen, die Spracherkennung wird nur auf Verfügbarkeit geprüft (Sprechen kann ein Test nicht).
 */
@RunWith(AndroidJUnit4::class)
class SpeechOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun synthesisWritesAudioToFileWithoutPlayingIt() {
        val output = SpeechOutput(context)
        val file = File(context.cacheDir, "tts-test.wav")
        file.delete()
        try {
            assertTrue("Die Sprachsynthese hat keine Audiodatei erzeugt", output.synthesizeToFile("Hello, this is a short test.", "en-US", file))
            Log.i("AiChatSpeech", "TTS-Datei: ${file.length()} Bytes")
            assertTrue(file.length() > 1000)
        } finally {
            output.shutdown()
            file.delete()
        }
    }

    @Test
    fun reportsVoicesAndRecognition() {
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts = TextToSpeech(context) { status = it; ready.countDown() }
        assertTrue(ready.await(15, TimeUnit.SECONDS))
        try {
            assertTrue("Sprachsynthese nicht verfügbar", status == TextToSpeech.SUCCESS)
            for (tag in listOf("de-DE", "en-US")) {
                val r = tts.isLanguageAvailable(Locale.forLanguageTag(tag))
                Log.i("AiChatSpeech", "Stimme $tag: ${if (r >= TextToSpeech.LANG_AVAILABLE) "vorhanden" else "fehlt ($r)"}")
            }
        } finally {
            tts.shutdown()
        }
        Log.i("AiChatSpeech", "Spracherkennung vorhanden: ${SpeechRecognizer.isRecognitionAvailable(context)}")
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            Log.i("AiChatSpeech", "Erkennung auf dem Gerät: ${SpeechRecognizer.isOnDeviceRecognitionAvailable(context)}")
        }
    }
}
