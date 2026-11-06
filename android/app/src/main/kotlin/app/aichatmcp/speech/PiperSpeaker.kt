package app.aichatmcp.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import app.aichatmcp.core.PiperStore
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Sprachausgabe mit Piper-Stimmen (sherpa-onnx, komplett auf dem Gerät). Ein Arbeitsfaden erzeugt Satz für Satz Audio und schreibt es
 * in einen fortlaufenden [AudioTrack]; ein zweiter Faden meldet über die Abspielposition, wann ein Auftrag *gehört* wurde (nicht nur
 * erzeugt), damit der Vorleser genau dann weiterschaltet. [stop] verwirft alles Laufende.
 *
 * [onDone] und [onFailed] kommen auf Hintergrundfäden; der Aufrufer reicht sie an den Hauptthread weiter.
 */
class PiperSpeaker(
    private val store: PiperStore,
    private val threads: () -> Int,
    private val onDone: (String) -> Unit,
    private val onFailed: (String) -> Unit,
    private val volume: () -> Float = { 1f },
) {
    private class Job(val voice: String, val text: String, val id: String, val speed: Float, val generation: Int)

    @Volatile private var generation = 0
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "piper-synth").apply { isDaemon = true } }
    private val watcher: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "piper-watch").apply { isDaemon = true } }
    private val trackLock = Any()
    private var track: AudioTrack? = null
    private var written = 0L // Frames seit Anlegen des AudioTrack (Mono: Frames = Samples)
    private val ends = ConcurrentLinkedQueue<Pair<String, Long>>()
    private var engine: OfflineTts? = null
    private var engineVoice = ""

    init {
        watcher.scheduleWithFixedDelay({ checkPlayed() }, 40, 40, TimeUnit.MILLISECONDS)
    }

    /** Hängt [text] an (oder ersetzt mit [flush] alles Laufende). [onProblem] meldet Ladefehler (Text für die Statuszeile). */
    fun speak(voice: String, text: String, id: String, flush: Boolean, speed: Float, onProblem: (String) -> Unit) {
        if (flush) stop()
        val job = Job(voice, text, id, speed, generation)
        worker.execute {
            try {
                run(job)
            } catch (e: Throwable) {
                if (job.generation == generation) {
                    Log.w(TAG, "Piper-Fehler: ${e.message}", e)
                    onProblem("Piper-Stimme '$voice': ${e.message ?: e.javaClass.simpleName}")
                    onFailed(job.id)
                }
            }
        }
    }

    /** Bricht Erzeugung und Wiedergabe ab. Offene Aufträge werden nicht mehr gemeldet. */
    fun stop() {
        generation++
        ends.clear()
        synchronized(trackLock) { dropTrack() }
    }

    fun release() {
        stop()
        watcher.shutdownNow()
        worker.execute {
            engine?.release()
            engine = null
            engineVoice = ""
        }
        worker.shutdown()
    }

    /** Erzeugt Audio ohne es abzuspielen (für Tests): Samples und Abtastrate, oder null bei unvollständiger Stimme. */
    fun render(voice: String, text: String, speed: Float = 1f): Pair<FloatArray, Int>? {
        val tts = engineFor(voice) ?: return null
        val audio = tts.generate(text, sid = 0, speed = speed)
        return audio.samples to audio.sampleRate
    }

    private fun run(job: Job) {
        if (job.generation != generation) return
        val tts = engineFor(job.voice) ?: throw IllegalStateException(store.problem(job.voice) ?: "Stimme nicht gefunden")
        if (job.generation != generation) return
        val sampleRate = tts.sampleRate()
        var mine = 0L
        // Ein ausdrückliches Objekt statt einer Lambda: der native Code ruft invoke([F)Ljava/lang/Integer; auf, und diese Signatur
        // gibt es nach dem Umbau der Lambdas durch D8 nur bei einer echten Klasse (sonst Absturz mit NoSuchMethodError).
        val callback = object : (FloatArray) -> Int {
            override fun invoke(samples: FloatArray): Int {
                if (job.generation != generation) return 0
                val t = synchronized(trackLock) { trackFor(sampleRate) }
                val n = try { t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) } catch (_: IllegalStateException) { -1 }
                if (n < 0 || job.generation != generation) return 0
                synchronized(trackLock) { if (track === t) written += n }
                mine += n
                return 1
            }
        }
        tts.generateWithCallback(job.text, sid = 0, speed = job.speed, callback = callback)
        if (job.generation != generation) return
        synchronized(trackLock) { ends.add(job.id to written) }
        if (mine == 0L) checkPlayed() // nichts erzeugt (z. B. nur Satzzeichen): sofort als gehört melden
    }

    private fun engineFor(voice: String): OfflineTts? {
        if (engine != null && engineVoice == voice) return engine
        engine?.release()
        engine = null
        engineVoice = ""
        val files = store.files(voice) ?: return null
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = files.onnx.path, tokens = files.tokens.path, dataDir = files.espeakData.path),
                numThreads = threads().coerceIn(1, 8),
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        val started = System.currentTimeMillis()
        engine = OfflineTts(config = config)
        engineVoice = voice
        Log.i(TAG, "Piper-Stimme '$voice' geladen in ${System.currentTimeMillis() - started} ms, ${engine?.sampleRate()} Hz")
        return engine
    }

    /** Muss unter [trackLock] aufgerufen werden. */
    private fun trackFor(sampleRate: Int): AudioTrack {
        track?.let { return it }
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes, sampleRate * 4 * 2)) // etwa 2 s Vorrat gegen Aussetzer
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(volume())
        t.play()
        track = t
        written = 0
        return t
    }

    /** Muss unter [trackLock] aufgerufen werden. */
    private fun dropTrack() {
        track?.let {
            runCatching { it.pause(); it.flush() }
            runCatching { it.release() }
        }
        track = null
        written = 0
    }

    /** Meldet Aufträge, deren Audio bis zum Ende abgespielt wurde. */
    private fun checkPlayed() {
        val t = synchronized(trackLock) { track }
        val head = if (t != null) runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L) else Long.MAX_VALUE
        while (true) {
            val next = ends.peek() ?: return
            if (t != null && next.second > head) return
            if (ends.remove(next)) onDone(next.first)
        }
    }

    private companion object {
        const val TAG = "AiChatPiper"
    }
}
