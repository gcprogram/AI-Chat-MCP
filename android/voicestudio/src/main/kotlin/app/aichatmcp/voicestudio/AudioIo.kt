package app.aichatmcp.voicestudio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import app.aichatmcp.core.studio.AudioMath

/** Rohaufnahme vom Mikrofon (Abtastrate des Geräts, 16 Bit, Mono). */
class Capture(val samples: ShortArray, val rate: Int, val limitReached: Boolean)

/**
 * Nimmt vom Mikrofon auf. Bevorzugt die unbearbeitete Quelle (`UNPROCESSED`), sonst `VOICE_RECOGNITION`: Rauschunterdrückung und
 * automatische Lautstärkeregelung des Telefons würden die Stimme verändern, bevor das Modell sie lernt.
 * [onLevel] meldet die Spitze (0..1) des letzten Blocks auf dem Hauptthread.
 */
class Recorder(private val context: Context, private val onLevel: (Float) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var limit = false
    private val chunks = ArrayList<ShortArray>()
    private var rate = 0

    /** Quelle, mit der aufgenommen wird (für die Anzeige und die Tests). */
    var sourceName: String = ""
        private set

    /** Startet die Aufnahme. Liefert null bei Erfolg, sonst einen Fehlertext. Die Berechtigung muss schon erteilt sein. */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        if (running) return null
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = buildList {
            if (unprocessed) add(MediaRecorder.AudioSource.UNPROCESSED to "unbearbeitet")
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "Spracherkennung")
            add(MediaRecorder.AudioSource.MIC to "Standard")
        }
        for ((source, label) in sources) {
            for (candidate in intArrayOf(44100, 48000)) {
                val minBuffer = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) continue
                val r = try {
                    AudioRecord(source, candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 4, candidate / 2))
                } catch (e: Exception) {
                    null
                }
                if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
                    r?.release()
                    continue
                }
                record = r
                rate = candidate
                sourceName = label
                chunks.clear()
                limit = false
                running = true
                r.startRecording()
                thread = Thread({ readLoop(r) }, "studio-record").also { it.start() }
                return null
            }
        }
        return "Das Mikrofon lässt sich nicht öffnen (belegt von einer anderen App?)."
    }

    private fun readLoop(r: AudioRecord) {
        val block = ShortArray(rate / 20) // 50 ms
        val maxSamples = rate * MAX_SECONDS
        var total = 0
        while (running) {
            val n = r.read(block, 0, block.size)
            if (n <= 0) continue
            synchronized(chunks) { chunks += block.copyOf(n) }
            total += n
            val peak = AudioMath.peak(block, n)
            main.post { onLevel(peak) }
            if (total >= maxSamples) {
                limit = true
                running = false
            }
        }
    }

    /** Beendet die Aufnahme und liefert, was aufgenommen wurde (der Anfang von [SKIP_START_MS] ms fällt weg: Tippgeräusch). */
    fun stop(): Capture? {
        val r = record ?: return null
        running = false
        thread?.join(1000)
        runCatching { r.stop() }
        r.release()
        record = null
        val all = synchronized(chunks) {
            val out = ShortArray(chunks.sumOf { it.size })
            var pos = 0
            for (c in chunks) { c.copyInto(out, pos); pos += c.size }
            chunks.clear()
            out
        }
        main.post { onLevel(0f) }
        val skip = minOf(all.size, rate * SKIP_START_MS / 1000)
        return Capture(all.copyOfRange(skip, all.size), rate, limit)
    }

    companion object {
        const val MAX_SECONDS = 40
        const val SKIP_START_MS = 120
    }
}

/** Spielt Aufnahmen ab. */
class Player {
    private var track: AudioTrack? = null

    /** Spielt [samples] einmal ab; [onDone] kommt auf einem Hintergrundfaden. [volume] 0 spielt stumm (für Tests). */
    fun play(samples: ShortArray, rate: Int, volume: Float = 1f, onDone: () -> Unit = {}) {
        stop()
        if (samples.isEmpty()) { onDone(); return }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(samples, 0, samples.size)
        t.setVolume(volume)
        t.setNotificationMarkerPosition(samples.size)
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack?) = onDone()
            override fun onPeriodicNotification(track: AudioTrack?) {}
        })
        track = t
        t.play()
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }
}
