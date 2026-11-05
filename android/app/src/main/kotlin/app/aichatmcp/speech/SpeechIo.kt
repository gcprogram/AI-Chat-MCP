package app.aichatmcp.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.aichatmcp.core.Speech
import java.util.Locale

/**
 * Spracheingabe mit der Spracherkennung von Google (Bordmittel, keine eigene Modelldatei). Offline wird bevorzugt: Ist das
 * Sprachpaket installiert, bleibt das Audio auf dem Gerät, sonst nutzt Google die Online-Erkennung. Hauptthread.
 */
private val GOOGLE_PACKAGES = listOf("com.google.android.tts", "com.google.android.googlequicksearchbox")

class SpeechInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Der Erkennungsdienst von Google (Spracherkennung und -synthese von Google bzw. Google-App), falls installiert.
     * Der separate On-Device-Erkenner mancher Geräte (createOnDeviceSpeechRecognizer) kennt oft nur wenige Sprachen und
     * die Erkennung des Herstellers ist schlechter; ohne Google-Dienst gilt der Standard des Systems.
     */
    private fun googleService(): ComponentName? {
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        for (pkg in GOOGLE_PACKAGES) {
            services.firstOrNull { it.serviceInfo.packageName == pkg }?.let {
                return ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)
            }
        }
        return null
    }

    /**
     * Startet die Aufnahme. Erst wird die Erkennung auf dem Gerät versucht (Audio bleibt auf dem Handy). Fehlt dafür das
     * Sprachpaket (Fehler 11 bis 13) und ist [allowOnline] gesetzt, wird einmal mit der Online-Erkennung von Google
     * wiederholt; [onNote] sagt das dann an.
     */
    fun start(
        languageTag: String?, allowOnline: Boolean, onPartial: (String) -> Unit, onResult: (String) -> Unit,
        onError: (String) -> Unit, onNote: (String) -> Unit,
    ) {
        stop()
        begin(languageTag, preferOffline = true, allowOnline, onPartial, onResult, onError, onNote)
    }

    private fun begin(
        languageTag: String?, preferOffline: Boolean, allowOnline: Boolean, onPartial: (String) -> Unit,
        onResult: (String) -> Unit, onError: (String) -> Unit, onNote: (String) -> Unit,
    ) {
        val google = googleService()
        val r = if (google != null) SpeechRecognizer.createSpeechRecognizer(context, google) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            private fun best(bundle: Bundle?) = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

            override fun onPartialResults(partialResults: Bundle?) = best(partialResults).let { if (it.isNotEmpty()) onPartial(it) }
            override fun onResults(results: Bundle?) {
                val text = best(results)
                release()
                if (text.isEmpty()) onError("Nichts verstanden.") else onResult(text)
            }

            override fun onError(error: Int) {
                release()
                if (preferOffline && allowOnline && error in 11..13) { // Sprachpaket fehlt: einmal online wiederholen
                    onNote("Kein Offline-Sprachpaket – Online-Erkennung von Google")
                    Handler(Looper.getMainLooper()).post {
                        begin(languageTag, preferOffline = false, allowOnline, onPartial, onResult, onError, onNote)
                    }
                } else {
                    onError(describe(error))
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        r.startListening(intent)
    }

    /** Beendet die Aufnahme; das bisher Verstandene kommt noch als Ergebnis. */
    fun finish() {
        recognizer?.stopListening()
    }

    fun stop() {
        recognizer?.cancel()
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun describe(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Nichts verstanden."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Keine Sprache gehört."
        SpeechRecognizer.ERROR_AUDIO -> "Das Mikrofon lässt sich nicht nutzen."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon-Berechtigung fehlt."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Die Spracherkennung ist gerade belegt."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Spracherkennung nicht erreichbar (Offline-Sprachpaket installieren?)."
        11, 12, 13 -> "Diese Sprache ist für die Erkennung nicht verfügbar (Offline-Sprachpaket installieren oder Online-Erkennung erlauben)."
        else -> "Spracherkennung fehlgeschlagen (Fehler $error)."
    }
}

/**
 * Sprachausgabe mit der Sprachsynthese von Android (Bordmittel). Die Sprache folgt der Antwortsprache des Companions.
 */
class SpeechOutput(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<() -> Unit>()

    private fun withEngine(action: () -> Unit) {
        if (ready) return action()
        pending += action
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                ready = status == TextToSpeech.SUCCESS
                val queued = pending.toList()
                pending.clear()
                if (ready) queued.forEach { it() }
            }
        }
    }

    fun speak(text: String, languageTag: String?, onProblem: (String) -> Unit) {
        val chunks = Speech.chunks(text)
        if (chunks.isEmpty()) return
        withEngine {
            val engine = tts ?: return@withEngine
            val locale = languageTag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                onProblem("Keine Stimme für ${locale.toLanguageTag()} installiert (Einstellungen → Sprachausgabe).")
            }
            chunks.forEachIndexed { i, chunk ->
                engine.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "reply-$i")
            }
        }
    }

    fun stop() {
        tts?.stop()
        pending.clear()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    /** Für Tests: Text in eine Datei sprechen statt abzuspielen (kein Ton). Liefert true, wenn etwas erzeugt wurde. */
    fun synthesizeToFile(text: String, languageTag: String?, file: java.io.File, timeoutMs: Long = 20_000): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var done = false
        withEngine {
            val engine = tts ?: return@withEngine
            engine.setLanguage(languageTag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault())
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    done = true
                    latch.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = latch.countDown()
            })
            engine.synthesizeToFile(text, Bundle(), file, "file-test")
        }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return done && file.length() > 0
    }
}
