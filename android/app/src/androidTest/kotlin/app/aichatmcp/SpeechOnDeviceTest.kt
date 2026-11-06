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

    /** Dauer einer WAV-Datei der Sprachausgabe in Sekunden (Kopf: Byte-Rate ab Byte 28, Daten ab Byte 44). */
    private fun wavSeconds(file: File): Double {
        val bytes = file.readBytes()
        val byteRate = (bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0xFF) shl 8) or ((bytes[30].toInt() and 0xFF) shl 16) or ((bytes[31].toInt() and 0xFF) shl 24)
        return (bytes.size - 44).toDouble() / byteRate
    }

    /** Misst, wie lange die Stimmen für gleichen Inhalt brauchen (nur Protokoll im Log, Tag AiChatSpeech). */
    @Test
    fun measuresSpeakingSpeedPerLanguage() {
        val texts = listOf(
            "de-DE" to "Der Hafen von Rotterdam ist der größte Seehafen Europas. Jährlich werden dort mehr als vierhundert Millionen Tonnen Güter umgeschlagen.",
            "en-US" to "The port of Rotterdam is the largest seaport in Europe. Every year more than four hundred million tons of goods are handled there.",
        )
        val output = SpeechOutput(context)
        try {
            for ((tag, text) in texts) {
                val file = File(context.cacheDir, "speed-$tag.wav")
                file.delete()
                assertTrue("Keine Audiodatei für $tag", output.synthesizeToFile(text, tag, file))
                val seconds = wavSeconds(file)
                val words = text.split(" ").size
                Log.i("AiChatSpeech", "Tempo $tag: ${text.length} Zeichen, $words Wörter, %.2f s => %.1f Zeichen/s, %.2f Wörter/s".format(seconds, text.length / seconds, words / seconds))
                file.delete()
            }
        } finally {
            output.shutdown()
        }
    }

    /**
     * Stimmenwahl pro Companion: Es gibt mehrere Stimmen, eine gewählte klingt anders als die Standardstimme, und danach
     * (leere Wahl) kommt wieder genau die Standardstimme. Verglichen wird die erzeugte Audiodatei, ohne Ton.
     */
    @Test
    fun chosenVoiceDiffersFromDefaultAndDefaultComesBack() {
        val output = SpeechOutput(context)
        try {
            val latch = CountDownLatch(1)
            var options = emptyList<SpeechOutput.VoiceOption>()
            output.voicesFor("de-DE") { options = it; latch.countDown() }
            assertTrue(latch.await(15, TimeUnit.SECONDS))
            Log.i("AiChatSpeech", "Stimmen de-DE: ${options.joinToString { "${it.label} = ${it.name}" }}")
            val english = CountDownLatch(1)
            output.voicesFor("en-US") { Log.i("AiChatSpeech", "Stimmen en-US: ${it.joinToString { o -> "${o.label} = ${o.name}" }}"); english.countDown() }
            english.await(15, TimeUnit.SECONDS)
            if (options.size < 2) {
                Log.i("AiChatSpeech", "Nur ${options.size} Stimme(n) für de-DE: Wechsel nicht prüfbar")
                return
            }
            val text = "Der Hafen von Rotterdam ist der größte Seehafen Europas."
            fun render(voice: String): ByteArray {
                val file = File(context.cacheDir, "voice-test.wav")
                file.delete()
                assertTrue("Keine Audiodatei für '$voice'", output.synthesizeToFile(text, "de-DE", file, voiceName = voice))
                return file.readBytes().also { file.delete() }
            }
            val default1 = render("")
            val others = options.map { it.name to render(it.name) }
            others.forEach { (name, bytes) -> Log.i("AiChatSpeech", "$name: ${bytes.size} Bytes, gleich wie Standard: ${bytes.contentEquals(default1)}") }
            val default2 = render("")
            assertTrue("Standardstimme nach der Wahl einer anderen nicht wieder die gleiche", default1.contentEquals(default2))
            assertTrue("Keine der gewählten Stimmen klingt anders als die Standardstimme", others.any { !it.second.contentEquals(default1) })
            assertTrue("Die Stimmen unterscheiden sich untereinander nicht", others.map { it.second.size }.distinct().size > 1 || others.map { it.second.toList() }.distinct().size > 1)
        } finally {
            output.shutdown()
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
