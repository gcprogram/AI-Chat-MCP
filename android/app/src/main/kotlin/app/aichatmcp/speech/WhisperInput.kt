package app.aichatmcp.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.aichatmcp.core.SilenceDetector
import app.aichatmcp.core.WhisperAudio
import app.aichatmcp.core.WhisperModel
import app.aichatmcp.core.WhisperNames
import app.aichatmcp.core.WhisperStore
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Spracheingabe mit einem Whisper-Modell (sherpa-onnx), komplett auf dem Gerät und ohne Google. Die App nimmt selbst auf
 * (16 kHz, Mono) und erkennt nach dem Ende der Aufnahme am Stück; Zwischenergebnisse gibt es nicht. Das Ende kommt durch erneutes
 * Tippen, durch eine Pause nach der Sprache ([SilenceDetector]) oder nach [WhisperAudio.MAX_SECONDS] (das Fenster von Whisper).
 *
 * Das Modell wird schon beim Start der Aufnahme geladen (parallel zum Sprechen), damit die Erkennung danach sofort beginnt.
 * Alle Rückmeldungen kommen auf dem Hauptthread. Die Berechtigung für das Mikrofon holt die Oberfläche vorher ein.
 */
class WhisperInput(private val store: WhisperStore, private val threads: () -> Int) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "whisper-decode").apply { isDaemon = true } }

    @Volatile private var generation = 0
    @Volatile private var recording = false
    private var recorderThread: Thread? = null

    private var recognizer: OfflineRecognizer? = null
    private var loadedKey = ""

    /**
     * Startet die Aufnahme mit [model]. [languageTag] ist die Sprache, in der Whisper schreibt ("de", "de-DE"; null = Whisper erkennt die gesprochene
     * Sprache selbst). Eine festgelegte Sprache zwingt Whisper, in ihr zu schreiben: Andere gesprochene Sprachen werden dabei übersetzt
     * (am PC mit small und medium geprüft, Deutsch gesprochen und Englisch geschrieben). [onStatus] meldet die Phasen ("Höre zu…", "Erkenne…"), [onRecognizing] den Beginn der Erkennung (die
     * Aufnahme ist dann zu Ende und nicht mehr zu stoppen), [onResult] den erkannten Text, [onError] einen Fehlertext.
     */
    fun start(
        model: WhisperModel, languageTag: String?, onStatus: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit,
        onRecognizing: () -> Unit = {},
    ) {
        stop()
        val gen = ++generation
        fun post(block: () -> Unit) = main.post { if (gen == generation) block() }
        val language = if (model.englishOnly) "en" else WhisperNames.languageOf(languageTag).orEmpty()
        recording = true
        // Laden parallel zur Aufnahme; ein Ladefehler wird nach der Aufnahme gemeldet (dann ist klar, was los ist)
        val loading = worker.submit<Result<OfflineRecognizer>> { runCatching { recognizerFor(model, language) } }
        post { onStatus("Höre zu… (Whisper, offline)") }
        val thread = Thread({
            val pcm = try {
                record(gen)
            } catch (e: Throwable) {
                Log.w(TAG, "Aufnahme fehlgeschlagen: ${e.message}", e)
                post { onError("Das Mikrofon lässt sich nicht nutzen (${e.message ?: e.javaClass.simpleName}).") }
                return@Thread
            }
            if (gen != generation) return@Thread
            recording = false
            post { onRecognizing(); onStatus("Erkenne… (Whisper)") }
            val samples = WhisperAudio.fromPcm16(pcm.toByteArray())
            if (WhisperAudio.isSilent(samples)) {
                post { onError("Keine Sprache gehört.") }
                return@Thread
            }
            worker.execute {
                if (gen != generation) return@execute
                val text = try {
                    val r = loading.get().getOrThrow()
                    decode(r, samples)
                } catch (e: Throwable) {
                    Log.w(TAG, "Whisper-Fehler: ${e.message}", e)
                    post { onError("Whisper: ${e.message ?: e.javaClass.simpleName}") }
                    return@execute
                }
                val cleaned = WhisperAudio.clean(text)
                post { if (cleaned.isEmpty()) onError("Nichts verstanden.") else onResult(cleaned) }
            }
        }, "whisper-record").apply { isDaemon = true }
        recorderThread = thread
        thread.start()
    }

    /** Beendet die Aufnahme; das bisher Gesprochene wird noch erkannt. */
    fun finish() {
        recording = false
    }

    /** Bricht Aufnahme und Erkennung ab, ohne Ergebnis. */
    fun stop() {
        generation++
        recording = false
        recorderThread?.join(500)
        recorderThread = null
    }

    /**
     * Erkennt fertige Aufnahmen (16 kHz, Mono) und wartet auf das Ergebnis; für Tests und Messungen. Nicht auf dem Hauptthread aufrufen.
     * [language] ist der Whisper-Code ("de"), leer = automatisch.
     */
    fun transcribe(model: WhisperModel, language: String, samples: FloatArray): String =
        worker.submit<String> { decode(recognizerFor(model, language), samples) }.get()

    /** Gibt das geladene Modell frei (Speicher). */
    fun release() {
        stop()
        worker.execute {
            recognizer?.release()
            recognizer = null
            loadedKey = ""
        }
    }

    @SuppressLint("MissingPermission") // die Oberfläche fragt die Berechtigung vor dem Start ab
    private fun record(gen: Int): ByteArrayOutputStream {
        val rate = WhisperAudio.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "Aufnahme mit 16 kHz nicht möglich" }
        // VOICE_RECOGNITION: Rauschunterdrückung für Sprache ist hier erwünscht (anders als beim Stimmen-Studio)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, rate / 2) * 2,
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon nicht bereit" }
        val out = ByteArrayOutputStream()
        val detector = SilenceDetector()
        val chunk = ByteArray(rate / 10 * 2) // 100 ms
        val maxBytes = WhisperAudio.MAX_SECONDS * rate * 2
        try {
            rec.startRecording()
            while (recording && gen == generation && out.size() < maxBytes) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                out.write(chunk, 0, n)
                if (detector.feed(WhisperAudio.fromPcm16(chunk, n))) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        return out
    }

    private fun recognizerFor(model: WhisperModel, language: String): OfflineRecognizer {
        val files = store.files(model.name) ?: error("Das Whisper-Modell „${model.name}“ ist unvollständig.")
        val n = threads().coerceIn(1, 8)
        val key = "${model.name}|$language|$n"
        recognizer?.let { if (key == loadedKey) return it }
        recognizer?.release()
        recognizer = null
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = WhisperAudio.SAMPLE_RATE, featureDim = WhisperNames.featureDim(model.name)),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = files.encoder.absolutePath, decoder = files.decoder.absolutePath, language = language, task = "transcribe",
                ),
                tokens = files.tokens.absolutePath,
                numThreads = n,
                provider = "cpu",
                modelType = "whisper",
            ),
        )
        return OfflineRecognizer(config = config).also {
            recognizer = it
            loadedKey = key
        }
    }

    private fun decode(r: OfflineRecognizer, samples: FloatArray): String {
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, WhisperAudio.SAMPLE_RATE)
            r.decode(stream)
            return r.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    companion object {
        private const val TAG = "AiChat"
    }
}
