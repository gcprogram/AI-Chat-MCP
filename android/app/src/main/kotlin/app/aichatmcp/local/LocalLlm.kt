package app.aichatmcp.local

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import app.aichatmcp.core.AppJson
import app.aichatmcp.core.CancelToken
import app.aichatmcp.core.Config
import app.aichatmcp.core.LlmBackend
import app.aichatmcp.core.LlmException
import app.aichatmcp.core.StopFilter
import app.aichatmcp.core.StreamEvent
import app.aichatmcp.core.Thinking
import app.aichatmcp.core.WireMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

/** Nimmt die erzeugten Textstücke (UTF-8) entgegen; false beendet die Erzeugung. */
fun interface PieceSink {
    fun onPiece(bytes: ByteArray): Boolean
}

/** Brücke zu llama.cpp (libaichat_llama.so, siehe src/main/cpp). Texte gehen als UTF-8-Bytes über die Grenze. */
object LlamaNative {
    init {
        System.loadLibrary("aichat_llama")
    }

    external fun load(path: String, nCtx: Int, nThreads: Int): Long

    /** Laden über einen offenen Dateideskriptor (der Pfad einer Datei aus dem Auswahldialog ist für die App nicht lesbar). */
    external fun loadFd(fd: Int, nCtx: Int, nThreads: Int): Long
    external fun lastError(): String
    external fun free(handle: Long)
    external fun cancel(handle: Long)
    external fun contextSize(handle: Long): Int
    external fun stats(handle: Long): String

    /** Sichert den KV-Cache in eine Datei (Tokens und Zustand); liefert die Zahl der Tokens, 0 = nichts, -1 = Fehler. */
    external fun saveCache(handle: Long, path: String): Int

    /** Lädt einen gesicherten KV-Cache; liefert die Zahl der Tokens, 0 = Datei fehlt oder passt nicht. */
    external fun loadCache(handle: Long, path: String): Int
    external fun applyTemplate(handle: Long, roles: Array<String>, contents: Array<ByteArray>): ByteArray?
    external fun generate(
        handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, topP: Float, repeatPenalty: Float,
        freqPenalty: Float, presencePenalty: Float, seed: Int, sink: PieceSink,
    ): Int
}

private const val TAG = "AiChat"

/** Ab so vielen neu berechneten Tokens lohnt es sich, den KV-Cache zu sichern. */
private const val MIN_NEW_TOKENS = 200

/** Obergrenze für den Kontext auf dem Gerät: der KV-Cache braucht Arbeitsspeicher (Handys haben wenig frei). */
const val LOCAL_MAX_CONTEXT = 4096

/** Die Konfiguration, mit der die Sitzung tatsächlich arbeitet: lokal ist der Kontext begrenzt. */
fun effectiveConfig(config: Config): Config =
    if (config.backend == app.aichatmcp.core.BACKEND_LOCAL) config.copy(contextTokens = minOf(config.contextTokens, LOCAL_MAX_CONTEXT)) else config

/**
 * Modell auf dem Gerät (llama.cpp). Das Modell bleibt zwischen den Anfragen geladen; geändert wird es nur, wenn sich
 * Datei, Kontext oder Thread-Zahl ändern. Dateien aus dem Auswahldialog (content://) werden über ihren Dateideskriptor
 * eingelesen, sodass nichts kopiert werden muss (Modelle sind groß, der Gerätespeicher oft knapp).
 */
class LocalLlm(private val context: Context) {
    private var handle = 0L
    private var key = ""
    private val runLock = Any() // eine Erzeugung gleichzeitig

    @Synchronized
    private fun ensureLoaded(model: String, nCtx: Int, threads: Int): Long {
        val wanted = "$model|$nCtx|$threads"
        if (handle != 0L && wanted == key) return handle
        unload()
        if (model.isBlank()) throw LlmException("Kein lokales Modell gewählt (Einstellungen).")
        val started = SystemClock.elapsedRealtime()
        val h: Long
        if (model.startsWith("content://")) {
            val pfd = try {
                context.contentResolver.openFileDescriptor(Uri.parse(model), "r")
            } catch (e: Exception) {
                null
            } ?: throw LlmException("Die Modelldatei ist nicht mehr lesbar. Bitte in den Einstellungen neu wählen.")
            h = LlamaNative.loadFd(pfd.fd, nCtx, threads) // llama.cpp dupliziert den Deskriptor selbst
            pfd.close()
        } else {
            if (!File(model).canRead()) throw LlmException("Die Modelldatei ist nicht lesbar: $model")
            h = LlamaNative.load(model, nCtx, threads)
        }
        if (h == 0L) throw LlmException(LlamaNative.lastError())
        handle = h
        key = wanted
        Log.i(TAG, "Modell geladen in ${SystemClock.elapsedRealtime() - started} ms")
        val loadStart = SystemClock.elapsedRealtime()
        val tokens = runCatching { LlamaNative.loadCache(h, cacheFile(wanted).path) }.getOrDefault(0)
        if (tokens > 0) Log.i(TAG, "Vorab-Cache geladen: $tokens Tokens in ${SystemClock.elapsedRealtime() - loadStart} ms")
        return h
    }

    /** Die Datei mit dem gesicherten Anfang (System-Prompt) für dieses Modell und diesen Kontext; sie liegt im Cache-Ordner, den Android bei Platzmangel leeren darf. */
    private fun cacheFile(wanted: String): File {
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(wanted.substringBeforeLast('|').toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        return File(File(context.cacheDir, "kv"), "$name.bin")
    }

    /**
     * Sichert den Anfang des KV-Caches, wenn die letzte Berechnung viel neu rechnen musste (mindestens [MIN_NEW_TOKENS] Tokens): Ohne Sicherung braucht
     * die erste Antwort nach jedem App-Start Minuten, weil der System-Prompt neu verarbeitet wird; mit ihr dauert das Laden Sekunden. Es bleibt
     * nur die Datei des gewählten Modells (je 100 bis 200 MB). Gedacht für den Aufruf nach dem Vorwärmen.
     */
    @Synchronized
    fun saveCacheIfWorthwhile() {
        if (handle == 0L) return
        val stats = runCatching { AppJson.parseToJsonElement(LlamaNative.stats(handle)) as JsonObject }.getOrNull() ?: return
        val prompt = (stats["prompt"] as? JsonPrimitive)?.intOrNull ?: return
        val cached = (stats["cached"] as? JsonPrimitive)?.intOrNull ?: 0
        if (prompt - cached < MIN_NEW_TOKENS) return
        val file = cacheFile(key)
        file.parentFile?.mkdirs()
        val started = SystemClock.elapsedRealtime()
        val saved = synchronized(runLock) { runCatching { LlamaNative.saveCache(handle, file.path) }.getOrDefault(-1) }
        if (saved > 0) {
            Log.i(TAG, "Vorab-Cache gesichert: $saved Tokens, ${file.length() / 1_000_000} MB in ${SystemClock.elapsedRealtime() - started} ms")
            file.parentFile?.listFiles { f -> f.isFile && f != file }?.forEach { it.delete() }
        }
    }

    @Synchronized
    fun unload() {
        if (handle != 0L) LlamaNative.free(handle)
        handle = 0L
        key = ""
    }

    fun backend(config: Config): LlmBackend = object : LlmBackend {
        override fun streamChat(
            model: String, messages: List<WireMessage>, temperature: Double, maxTokens: Int,
            stop: List<String>, cancel: CancelToken, extra: Map<String, Double>,
        ): Flow<StreamEvent> = channelFlow {
            val h = ensureLoaded(config.localModel, config.contextTokens, config.localThreads)
            synchronized(runLock) {
                cancel.onStop { LlamaNative.cancel(h) }
                val prompt = LlamaNative.applyTemplate(
                    h, messages.map { it.role }.toTypedArray(), messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                ) ?: throw LlmException("Die Chat-Vorlage des Modells lässt sich nicht anwenden.")
                val filter = StopFilter(stop)
                val modelName = config.localModelName.ifEmpty { config.localModel.substringAfterLast('/') }
                val promptText = String(prompt, Charsets.UTF_8)
                val finalPrompt = Thinking.prefill(promptText, modelName, config.localThinking).let { if (it === promptText) prompt else it.toByteArray(Charsets.UTF_8) }
                val result = LlamaNative.generate(
                    h, finalPrompt, maxTokens, temperature.toFloat(), (extra["top_p"] ?: 1.0).toFloat(),
                    (extra["repeat_penalty"] ?: 1.0).toFloat(), (extra["frequency_penalty"] ?: 0.0).toFloat(),
                    (extra["presence_penalty"] ?: 0.0).toFloat(), System.nanoTime().toInt(),
                ) { bytes ->
                    if (cancel.stopped) return@generate false
                    val text = filter.feed(String(bytes, Charsets.UTF_8))
                    if (text.isNotEmpty() && trySendBlocking(StreamEvent.Content(text)).isFailure) return@generate false
                    !filter.stopped
                }
                if (result == -1) throw LlmException(LlamaNative.lastError())
                filter.flush().takeIf { it.isNotEmpty() }?.let { trySendBlocking(StreamEvent.Content(it)) }
                val stats = runCatching { AppJson.parseToJsonElement(LlamaNative.stats(h)) as JsonObject }.getOrNull()
                Log.i(TAG, "Antwort: ${LlamaNative.stats(h)}")
                (stats?.get("prompt") as? JsonPrimitive)?.intOrNull?.let { trySendBlocking(StreamEvent.Usage(it)) }
            }
        }.flowOn(Dispatchers.IO)
    }
}
