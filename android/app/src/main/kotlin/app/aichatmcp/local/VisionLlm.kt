package app.aichatmcp.local

import android.content.Context
import android.net.Uri
import app.aichatmcp.core.LlmException
import java.io.File

/** Brücke zum Bildmodell (llama.cpp mtmd, siehe src/main/cpp/vision_jni.cpp). Texte gehen als UTF-8-Bytes über die Grenze. */
object VisionNative {
    init {
        System.loadLibrary("aichat_llama")
    }

    external fun load(model: String, mmproj: String, nThreads: Int): Long
    external fun lastError(): String
    external fun free(handle: Long)
    external fun cancel(handle: Long)
    external fun stats(handle: Long): String

    /** Beschreibt ein Bild (JPEG/PNG-Bytes). Rückgabe: 0 Ende, 1 Token-Limit, 2 abgebrochen, 3 Kontext voll, -1 Fehler. */
    external fun describe(handle: Long, image: ByteArray, prompt: ByteArray, maxTokens: Int, sink: PieceSink): Int
}

/**
 * Kleines Bildmodell (z. B. SmolVLM-256M), das Bilder als Text beschreibt. Das Chat-Modell bleibt reiner Text: die Beschreibung
 * geht als Text in den Chat. Modell und mmproj-Datei liegen im privaten Speicher der App (sie sind klein, daher kopiert statt
 * über einen Dateideskriptor gelesen); geladen wird nur für die Dauer einer Beschreibung, damit der Arbeitsspeicher dem
 * Chat-Modell bleibt.
 */
class VisionLlm(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "vision").apply { mkdirs() }
    val modelFile: File get() = File(dir, "model.gguf")
    val projFile: File get() = File(dir, "mmproj.gguf")

    val available: Boolean get() = modelFile.length() > 0 && projFile.length() > 0

    private val guard = Any()
    private val runLock = Any() // eine Beschreibung gleichzeitig (das Vorlesen bestellt Bilder schon vorab)
    private var running = 0L

    /** Kopiert eine Datei aus dem Auswahldialog in den privaten Speicher. Liefert die Größe in Bytes. */
    fun importFile(uri: Uri, target: File): Long {
        val temp = File(target.parentFile, target.name + ".tmp")
        val input = context.contentResolver.openInputStream(uri) ?: throw LlmException("Die Datei lässt sich nicht öffnen.")
        input.use { source -> temp.outputStream().use { source.copyTo(it, 1 shl 20) } }
        target.delete()
        if (!temp.renameTo(target)) throw LlmException("Die Datei lässt sich nicht speichern.")
        return target.length()
    }

    fun remove() {
        modelFile.delete()
        projFile.delete()
    }

    /** Beschreibt das Bild. Läuft lange (Laden, Bild verarbeiten): nicht auf dem Hauptthread aufrufen. */
    fun describe(image: ByteArray, prompt: String, threads: Int, maxTokens: Int = 320): String = synchronized(runLock) {
        if (!available) throw LlmException("Kein Bildmodell eingerichtet (Einstellungen).")
        val handle = VisionNative.load(modelFile.path, projFile.path, threads)
        if (handle == 0L) throw LlmException(VisionNative.lastError())
        synchronized(guard) { running = handle }
        try {
            val out = StringBuilder()
            val result = VisionNative.describe(handle, image, prompt.toByteArray(Charsets.UTF_8), maxTokens) { bytes ->
                out.append(String(bytes, Charsets.UTF_8))
                true
            }
            if (result == -1) throw LlmException(VisionNative.lastError())
            out.toString().trim()
        } finally {
            synchronized(guard) {
                running = 0L
                VisionNative.free(handle)
            }
        }
    }

    /** Bricht eine laufende Beschreibung ab (wirkt zwischen den Tokens; das Verarbeiten des Bildes lässt sich nicht unterbrechen). */
    fun cancel() {
        synchronized(guard) { if (running != 0L) VisionNative.cancel(running) }
    }
}
