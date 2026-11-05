package app.aichatmcp.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.aichatmcp.ImagePrep
import app.aichatmcp.core.Decorative
import app.aichatmcp.core.Documents
import app.aichatmcp.core.ReadItem
import app.aichatmcp.core.ReadableDoc
import app.aichatmcp.core.ReaderOutput
import app.aichatmcp.core.ReaderPictures
import app.aichatmcp.local.VisionLlm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Die Sprachausgabe als Ausgabe des Vorlesers. */
class SpeechReaderOutput(private val speech: SpeechOutput, private val onProblem: (String) -> Unit) : ReaderOutput {
    override fun speak(text: String, id: String, language: String?, flush: Boolean) =
        speech.speakItem(text, id, language, flush, onProblem)

    override fun stopSpeaking() = speech.stopItems()
}

/**
 * Tonfokus fürs Vorlesen: Bei einem Anruf, einer Benachrichtigung oder anderem Ton wird angehalten; ist der Ton wieder frei,
 * geht es von selbst weiter. Übernimmt eine andere App den Ton dauerhaft (Musik), bleibt es angehalten.
 */
class ReaderFocus(context: Context, private val listener: Listener) {
    interface Listener {
        fun lostTemporarily()
        fun lostPermanently()
        fun gained()
    }

    enum class Result { GRANTED, DELAYED, FAILED }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null

    fun request(): Result {
        abandon()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val built = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true) // kurze Töne (Benachrichtigung) pausieren das Vorlesen, statt es leiser zu stellen
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> listener.gained()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener.lostTemporarily()
                    AudioManager.AUDIOFOCUS_LOSS -> listener.lostPermanently()
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        request = built
        return when (audio.requestAudioFocus(built)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> Result.GRANTED
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> Result.DELAYED
            else -> {
                request = null
                Result.FAILED
            }
        }
    }

    fun abandon() {
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
    }
}

/**
 * Bilder im vorgelesenen Text: Das Bildmodell beschreibt sie. Beschreibungen werden schon beim Abschnitt davor bestellt
 * (damit hinterher keine lange Pause entsteht) und zwischengespeichert, auch für Sprünge zurück.
 * Alle Rückrufe kommen auf dem Hauptthread.
 */
class DescribedPictures(
    private val scope: CoroutineScope,
    private val context: Context,
    private val vision: VisionLlm,
    private val doc: ReadableDoc?,
    private val threads: () -> Int,
    private val prompt: String,
    private val onDescribed: (String) -> Unit = {},
) : ReaderPictures {
    private val cache = HashMap<String, String?>()
    private val waiting = HashMap<String, MutableList<(String?) -> Unit>>()
    private val running = HashSet<String>()
    private var epoch = 0

    override fun describe(picture: ReadItem.Picture, done: (String?) -> Unit) {
        val ref = picture.ref
        if (ref.isEmpty() || !vision.available) return done(null)
        if (cache.containsKey(ref)) return done(cache[ref])
        waiting.getOrPut(ref) { mutableListOf() } += done
        start(ref)
    }

    override fun prefetch(picture: ReadItem.Picture) {
        val ref = picture.ref
        if (ref.isEmpty() || !vision.available || cache.containsKey(ref)) return
        start(ref)
    }

    private fun start(ref: String) {
        if (!running.add(ref)) return
        val mine = epoch
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = doc?.images?.invoke(ref) ?: error("Bild nicht ladbar")
                    val ornament = Decorative.reason(bytes)
                    if (ornament != null) {
                        Log.i("AiChat", "Bild $ref übersprungen: $ornament")
                        ReaderPictures.SKIP
                    } else {
                        Documents.completeSentences(vision.describe(ImagePrep.jpegFromBytes(bytes), prompt, threads(), 320) { mine != epoch })
                    }
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            running.remove(ref)
            if (mine != epoch) return@launch // abgebrochen (Pause oder Sprung): ein unvollständiges Ergebnis nicht merken
            cache[ref] = text
            if (text != null && text != ReaderPictures.SKIP) onDescribed(text)
            waiting.remove(ref)?.forEach { it(text) }
        }
    }

    override fun cancel() {
        epoch++
        waiting.clear()
        vision.cancel()
    }
}
