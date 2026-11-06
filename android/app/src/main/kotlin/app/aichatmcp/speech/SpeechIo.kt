package app.aichatmcp.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.ModelDownloadListener
import androidx.annotation.RequiresApi
import android.os.Build
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

/**
 * Der Erkennungsdienst von Google (Spracherkennung und -synthese von Google bzw. Google-App), falls installiert.
 * Der separate On-Device-Erkenner mancher Geräte (createOnDeviceSpeechRecognizer) kennt oft nur wenige Sprachen und
 * die Erkennung des Herstellers ist schlechter; ohne Google-Dienst gilt der Standard des Systems.
 */
fun googleRecognitionService(context: Context): ComponentName? {
    val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
    for (pkg in GOOGLE_PACKAGES) {
        services.firstOrNull { it.serviceInfo.packageName == pkg }?.let {
            return ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)
        }
    }
    return null
}

class SpeechInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Startet die Aufnahme. Erst wird die Erkennung auf dem Gerät versucht (Audio bleibt auf dem Handy). Fehlt dafür das
     * Sprachpaket (Fehler 11 bis 13) und ist [allowOnline] gesetzt, wird einmal mit der Online-Erkennung von Google
     * wiederholt. [onMode] meldet bei jedem Versuch, womit erkannt wird (true = auf dem Gerät).
     */
    fun start(
        languageTag: String?, allowOnline: Boolean, onPartial: (String) -> Unit, onResult: (String) -> Unit,
        onError: (String) -> Unit, onMode: (offline: Boolean) -> Unit,
    ) {
        stop()
        begin(languageTag, preferOffline = true, allowOnline, onPartial, onResult, onError, onMode)
    }

    private fun begin(
        languageTag: String?, preferOffline: Boolean, allowOnline: Boolean, onPartial: (String) -> Unit,
        onResult: (String) -> Unit, onError: (String) -> Unit, onMode: (Boolean) -> Unit,
    ) {
        onMode(preferOffline)
        val google = googleRecognitionService(context)
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
                    Handler(Looper.getMainLooper()).post {
                        begin(languageTag, preferOffline = false, allowOnline, onPartial, onResult, onError, onMode)
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
private const val UNKNOWN_LANGUAGE = "?"

class SpeechOutput(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<() -> Unit>()

    private val failures = mutableListOf<() -> Unit>()

    /** Führt [action] aus, sobald die Sprachausgabe bereit ist; [onFail] läuft, wenn sie sich nicht starten lässt (z. B. keine Engine installiert). */
    private fun withEngine(onFail: () -> Unit = {}, action: () -> Unit) {
        if (ready) return action()
        pending += action
        failures += onFail
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                ready = status == TextToSpeech.SUCCESS
                val queued = pending.toList()
                val failed = failures.toList()
                pending.clear()
                failures.clear()
                if (ready) queued.forEach { it() } else failed.forEach { it() }
            }
        }
    }

    /** Stimme des aktuellen Companions (Name aus [voicesFor]); leer = Standardstimme der Sprache. */
    @Volatile var voice: String = ""
    private var appliedVoice = ""

    /**
     * Setzt Sprache und Stimme. Nach [TextToSpeech.setLanguage] gilt die Standardstimme der Sprache; die gewählte Stimme wird
     * nur genommen, wenn sie installiert ist und zur Sprache passt (sonst bleibt es bei der Standardstimme).
     */
    private fun applyLanguageAndVoice(engine: TextToSpeech, locale: Locale, name: String): Int {
        val result = engine.setLanguage(locale)
        appliedVoice = name
        if (name.isNotEmpty() && result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
            val chosen = engine.voices.orEmpty().firstOrNull { it.name == name && "notInstalled" !in it.features }
            if (chosen != null && Speech.voiceFitsLanguage(chosen.locale.language, locale.toLanguageTag())) engine.setVoice(chosen)
        }
        return result
    }

    fun speak(text: String, languageTag: String?, voiceName: String = voice, onProblem: (String) -> Unit) {
        val chunks = Speech.chunks(text)
        if (chunks.isEmpty()) return
        withEngine {
            val engine = tts ?: return@withEngine
            val locale = languageTag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
            currentLanguage = UNKNOWN_LANGUAGE // der Vorleser setzt seine Sprache beim nächsten Abschnitt neu
            val result = applyLanguageAndVoice(engine, locale, voiceName)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                onProblem("Keine Stimme für ${locale.toLanguageTag()} installiert (Einstellungen → Sprachausgabe).")
            }
            engine.setSpeechRate(rate)
            chunks.forEachIndexed { i, chunk ->
                engine.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "reply-$i")
            }
        }
    }

    fun stop() {
        tts?.stop()
        pending.clear()
    }

    // --- Vorleser: einzelne Abschnitte mit Kennung --------------------------------------------------------------

    /** Rückmeldungen zu Abschnitten, die [speakItem] gestartet hat (immer auf dem Hauptthread). */
    interface ItemSink {
        fun done(id: String)
        fun failed(id: String)
    }

    private var itemSink: ItemSink? = null
    /** Sprechgeschwindigkeit (1.0 = normal), gilt für Antworten und das Vorlesen. */
    @Volatile var rate: Float = 1.0f

    private var currentLanguage: String? = UNKNOWN_LANGUAGE // "?" = unbekannt (noch nie gesetzt oder von Antworten verändert)
    private val main = Handler(Looper.getMainLooper())

    fun setItemSink(sink: ItemSink?) {
        itemSink = sink
    }

    private fun ensureItemListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.takeIf { it.startsWith("r:") }?.let { id -> main.post { itemSink?.done(id) } }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.takeIf { it.startsWith("r:") }?.let { id -> main.post { itemSink?.failed(id) } }
            }
        })
    }

    /** Spricht einen Abschnitt in [languageTag]; [flush] ersetzt alles Laufende, sonst wird angehängt. */
    fun speakItem(text: String, id: String, languageTag: String?, flush: Boolean, onProblem: (String) -> Unit) {
        withEngine {
            val engine = tts ?: return@withEngine
            ensureItemListener(engine)
            if (languageTag != currentLanguage || voice != appliedVoice) {
                currentLanguage = languageTag
                val locale = languageTag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
                val result = applyLanguageAndVoice(engine, locale, voice)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    onProblem("Keine Stimme für ${locale.toLanguageTag()} installiert (Einstellungen → Stimmen).")
                }
            }
            engine.setSpeechRate(rate)
            engine.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    /** Bricht das Sprechen ab (auch Angehängtes). */
    fun stopItems() {
        tts?.stop()
    }

    /**
     * Für welche Sprachen eine Stimme installiert ist. Google markiert noch nicht geladene Stimmen mit dem Merkmal
     * "notInstalled"; sie würden beim Sprechen erst nachgeladen (online) oder fehlen.
     */
    fun installedVoices(tags: List<String>, onResult: (Map<String, Boolean>) -> Unit) {
        withEngine {
            val voices = tts?.voices.orEmpty()
            onResult(
                tags.associateWith { tag ->
                    val wanted = Locale.forLanguageTag(tag)
                    voices.any { it.locale.language == wanted.language && it.locale.country == wanted.country && "notInstalled" !in it.features }
                },
            )
        }
    }

    /** Eine wählbare Stimme: [name] speichert der Companion, [label] zeigt die Oberfläche. */
    data class VoiceOption(val name: String, val label: String)

    /** Die installierten Stimmen für [languageTag], nach Name sortiert und nummeriert (die Reihenfolge ist je Gerät stabil). */
    fun voicesFor(languageTag: String?, onResult: (List<VoiceOption>) -> Unit) {
        withEngine(onFail = { onResult(emptyList()) }) {
            val wanted = languageTag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
            val voices = tts?.voices.orEmpty()
                // "de-DE-language" ist nur der Platzhalter für die Standardstimme der Sprache, keine eigene Stimme
                .filter { it.locale.language == wanted.language && it.locale.country == wanted.country && "notInstalled" !in it.features && !it.name.endsWith("-language") }
                .sortedBy { it.name }
            onResult(voices.mapIndexed { i, v -> VoiceOption(v.name, Speech.voiceLabel(i + 1, v.locale.toLanguageTag(), v.isNetworkConnectionRequired)) })
        }
    }

    /** Öffnet die Stimmenverwaltung von Google (dort lassen sich Stimmen je Sprache laden). */
    fun openVoiceInstall(): Boolean = try {
        context.startActivity(
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage("com.google.android.tts").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: Exception) {
        false
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    /** Für Tests: Text in eine Datei sprechen statt abzuspielen (kein Ton). Liefert true, wenn etwas erzeugt wurde. */
    fun synthesizeToFile(text: String, languageTag: String?, file: java.io.File, timeoutMs: Long = 20_000, voiceName: String = ""): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var done = false
        withEngine {
            val engine = tts ?: return@withEngine
            applyLanguageAndVoice(engine, languageTag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault(), voiceName)
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

/**
 * Offline-Sprachpakete der Google-Spracherkennung verwalten (Android 13+): welche Sprachen installiert sind und das
 * Herunterladen weiterer. Die Oberfläche des Herstellers zeigt das oft nicht an, die Schnittstelle gibt es trotzdem.
 * Alle Rückrufe kommen auf dem Hauptthread.
 */
class SpeechPacks(private val context: Context) {
    data class Support(val installed: Set<String>, val downloadable: Set<String>, val pending: Set<String>)

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 33

    private fun newRecognizer(): SpeechRecognizer {
        val google = googleRecognitionService(context)
        return if (google != null) SpeechRecognizer.createSpeechRecognizer(context, google) else SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun baseIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

    @RequiresApi(33)
    fun check(attempt: Int = 0, onResult: (Result<Support>) -> Unit) {
        val recognizer = newRecognizer()
        recognizer.checkRecognitionSupport(baseIntent(), context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                recognizer.destroy()
                onResult(
                    Result.success(
                        Support(
                            recognitionSupport.installedOnDeviceLanguages.toSet(),
                            recognitionSupport.supportedOnDeviceLanguages.toSet(),
                            recognitionSupport.pendingOnDeviceLanguages.toSet(),
                        ),
                    ),
                )
            }

            override fun onError(error: Int) {
                recognizer.destroy()
                if (error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT && attempt < 2) { // direkt nach dem Anbinden manchmal noch nicht bereit
                    Handler(Looper.getMainLooper()).postDelayed({ check(attempt + 1, onResult) }, 1000)
                } else {
                    onResult(Result.failure(IllegalStateException("Fehler $error")))
                }
            }
        })
    }

    private var downloader: SpeechRecognizer? = null

    /** Lädt das Offline-Paket einer Sprache (z. B. "en-US"). [onProgress] liefert Prozent (ab Android 14), [onDone] das Ende. */
    @RequiresApi(33)
    fun download(tag: String, onProgress: (Int) -> Unit, onDone: (Result<String>) -> Unit) {
        downloader?.destroy()
        val recognizer = newRecognizer().also { downloader = it }
        val intent = baseIntent().putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        fun finish(result: Result<String>) {
            recognizer.destroy()
            if (downloader === recognizer) downloader = null
            onDone(result)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            recognizer.triggerModelDownload(intent, context.mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = onProgress(completedPercent)
                override fun onSuccess() = finish(Result.success("installiert"))
                override fun onScheduled() = finish(Result.success("geplant (startet bei WLAN/Ladegerät)"))
                override fun onError(error: Int) = finish(Result.failure(IllegalStateException("Fehler $error")))
            })
        } else {
            recognizer.triggerModelDownload(intent) // ohne Rückmeldung: der Aufrufer prüft später erneut
            Handler(Looper.getMainLooper()).postDelayed({ finish(Result.success("angestoßen")) }, 1500)
        }
    }

    /**
     * Öffnet Googles Download-Dialog für die Sprache. Der Erkennungsdienst versucht das beim Anstoßen selbst, wird dabei aber
     * als Hintergrundprozess blockiert (Android 15: BAL_BLOCK) - dann kommt nie eine Rückmeldung. Aus der sichtbaren App
     * heraus ist der Start erlaubt.
     */
    fun openDownloadDialog(tag: String): Boolean = try {
        context.startActivity(
            Intent("com.google.recognition.action.DOWNLOAD_LANGUAGE")
                .setPackage("com.google.android.tts")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: Exception) {
        false
    }

    fun cancelDownload() {
        downloader?.destroy()
        downloader = null
    }
}
