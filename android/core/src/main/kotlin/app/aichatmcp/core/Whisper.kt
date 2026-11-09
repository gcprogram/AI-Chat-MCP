package app.aichatmcp.core

import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.sqrt

const val ENGINE_GOOGLE = "google"
const val ENGINE_WHISPER = "whisper"

/** Dateien eines Whisper-Modells für sherpa-onnx (Ordner eines Pakets `sherpa-onnx-whisper-…`). */
data class WhisperFiles(val encoder: File, val decoder: File, val tokens: File)

/** Ein installiertes Whisper-Modell: Ordnername und ob es nur Englisch kann (`….en`-Modelle). */
data class WhisperModel(val name: String, val englishOnly: Boolean) {
    val title: String get() = WhisperNames.title(name)
}

object WhisperNames {
    /** Lesbarer Name: "sherpa-onnx-whisper-small" -> "Whisper small", "…-small.en" -> "Whisper small.en". */
    fun title(folder: String): String {
        val s = folder.removePrefix("sherpa-onnx-").removePrefix("whisper-").removePrefix("whisper_")
        return "Whisper " + s.replace('_', ' ').ifEmpty { folder }
    }

    /** Sprachcode für Whisper aus einem Android-Sprach-Tag ("de-DE" -> "de"); null = Whisper erkennt die Sprache selbst. */
    fun languageOf(tag: String?): String? = tag?.substringBefore('-')?.substringBefore('_')?.lowercase()?.takeIf { it.length in 2..3 }

    /** Dateien, die beim Import wegfallen: die große Fassung eines Modells, wenn es die int8-Fassung gibt (`x.onnx` neben `x.int8.onnx`). */
    fun redundant(names: List<String>): Set<String> =
        names.filter { it.endsWith(".int8.onnx") }.map { it.removeSuffix(".int8.onnx") + ".onnx" }.filter { it in names }.toSet()

    /**
     * Sprache, in der Whisper schreibt, aus der Einstellung [setting] (leer = Sprache des Companions [companionTag], "auto" oder ein Code):
     * null = Whisper erkennt die gesprochene Sprache selbst.
     */
    fun targetLanguage(setting: String, companionTag: String?): String? = when (val s = setting.trim().lowercase()) {
        "" -> languageOf(companionTag)
        "auto" -> null
        else -> languageOf(s)
    }

    /** Anzahl der Mel-Bänder: large-v3 und turbo rechnen mit 128, alle älteren Modelle mit 80. */
    fun featureDim(folder: String): Int = if (Regex("large-v3|turbo", RegexOption.IGNORE_CASE).containsMatchIn(folder)) 128 else 80

    /** Englisch-Modelle (`small.en`, `base-en`) kennen keine andere Sprache. */
    fun isEnglishOnly(folder: String): Boolean = Regex("(?<![A-Za-z])en(?![A-Za-z])").containsMatchIn(folder.removePrefix("sherpa-onnx-whisper-"))
}

/**
 * Whisper-Modelle im App-Speicher: je Modell ein Ordner mit Encoder, Decoder (je `.onnx` oder `.int8.onnx`) und Tokens
 * (`*tokens.txt`), wie in den Paketen von sherpa-onnx. Gibt es beide Fassungen, gilt die kleinere int8-Fassung.
 */
class WhisperStore(private val root: File) {
    private fun dirOf(name: String) = File(root, PiperNames.safeName(name))

    private fun pick(dir: File, part: String): File? {
        val all = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") && f.name.contains(part) }.orEmpty().sortedBy { it.name }
        return all.firstOrNull { it.name.endsWith(".int8.onnx") } ?: all.firstOrNull()
    }

    private fun tokensOf(dir: File): File? = dir.listFiles { f -> f.isFile && f.name.endsWith("tokens.txt") }?.minByOrNull { it.name }

    /** Dateien zum Laden, oder null, wenn etwas fehlt. */
    fun files(name: String): WhisperFiles? {
        val dir = dirOf(name)
        return WhisperFiles(pick(dir, "encoder") ?: return null, pick(dir, "decoder") ?: return null, tokensOf(dir) ?: return null)
    }

    /** Vollständige Modelle, nach Name sortiert. */
    fun list(): List<WhisperModel> =
        root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
            .filter { files(it.name) != null }.map { WhisperModel(it.name, WhisperNames.isEnglishOnly(it.name)) }

    /** Fehlertext, falls das Modell unvollständig ist, sonst null. */
    fun problem(name: String): String? {
        val dir = dirOf(name)
        return when {
            pick(dir, "encoder") == null -> "Keine Encoder-Datei (…encoder….onnx) im Ordner."
            pick(dir, "decoder") == null -> "Keine Decoder-Datei (…decoder….onnx) im Ordner."
            tokensOf(dir) == null -> "Die Datei …tokens.txt fehlt (sie liegt in jedem sherpa-onnx-Whisper-Paket)."
            else -> null
        }
    }

    /** Wählt das Modell: das benannte, sonst das erste installierte (null, wenn keines da ist). */
    fun resolve(preferred: String): WhisperModel? = list().let { models -> models.firstOrNull { it.name == preferred } ?: models.firstOrNull() }

    /**
     * Schreibt eine Datei des Ordners [folder] ein. Übernommen werden nur Modelldateien (`*.onnx`), `*tokens.txt` und `*.json` aus dem
     * obersten Ordner (Testaufnahmen und Unterordner bleiben weg). Liefert false, wenn übersprungen oder der Pfad unzulässig ist.
     * Welche Dateien eines Ordners überflüssig sind, sagt [WhisperNames.redundant].
     */
    fun importFile(folder: String, relativePath: String, input: InputStream): Boolean {
        val parts = relativePath.split('/', '\\').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == ".." || it == "." }) return false
        val name = parts.last()
        val wanted = name.endsWith(".onnx") || name.endsWith("tokens.txt") || name.endsWith(".json")
        if (!wanted || parts.size > 1) return false
        val target = File(dirOf(folder), name)
        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) return false
        target.parentFile?.mkdirs()
        target.outputStream().use { input.copyTo(it) }
        return true
    }

    fun delete(name: String) {
        dirOf(name).deleteRecursively()
    }
}

/**
 * Erkennt das Ende einer Äußerung: nachdem Sprache zu hören war, beendet eine längere Pause die Aufnahme (wie die Google-Erkennung),
 * damit nicht jedes Mal ein zweites Tippen nötig ist. Wird mit aufeinanderfolgenden Stücken der Aufnahme gefüttert.
 */
class SilenceDetector(
    private val sampleRate: Int = WhisperAudio.SAMPLE_RATE,
    private val speechRms: Float = 0.015f,
    private val endAfterSeconds: Float = 1.6f,
) {
    private var heardSpeech = false
    private var quietSamples = 0L

    /** Liefert true, sobald nach Sprache lange genug Stille war. */
    fun feed(chunk: FloatArray): Boolean {
        if (chunk.isEmpty()) return false
        if (WhisperAudio.rms(chunk) >= speechRms) {
            heardSpeech = true
            quietSamples = 0
        } else if (heardSpeech) {
            quietSamples += chunk.size
        }
        return heardSpeech && quietSamples >= endAfterSeconds * sampleRate
    }
}

/** Kleine Prüfungen an der Aufnahme vor der Erkennung. */
object WhisperAudio {
    /** Whisper rechnet immer über ein 30-Sekunden-Fenster; längere Aufnahmen würden abgeschnitten. */
    const val MAX_SECONDS = 29
    const val SAMPLE_RATE = 16000

    /** Effektivwert der Aufnahme (0.0 bis 1.0). */
    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size).toFloat()
    }

    /** Aufnahme ohne erkennbare Sprache (Stille, nur Rauschen): Whisper würde dann Text erfinden ("Untertitel …"). */
    fun isSilent(samples: FloatArray): Boolean {
        if (samples.size < SAMPLE_RATE / 4) return true // unter 0,25 s
        var peak = 0f
        for (s in samples) peak = maxOf(peak, abs(s))
        return peak < 0.02f || rms(samples) < 0.003f
    }

    /** 16-Bit-PCM (little endian, Mono) als Gleitkommazahlen -1..1. */
    fun fromPcm16(bytes: ByteArray, length: Int = bytes.size): FloatArray {
        val n = length / 2
        return FloatArray(n) { i ->
            val v = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
            v.toShort() / 32768f
        }
    }

    /** Whisper erfindet bei kaum hörbarer Sprache gern Standardsätze; diese bekannten Fälle werden verworfen. */
    fun clean(text: String): String {
        val t = text.trim()
        val lower = t.lowercase()
        val ghosts = listOf("untertitel", "vielen dank fürs zuschauen", "thanks for watching", "subtitles by", "amara.org")
        return if (ghosts.any { lower.contains(it) } && t.length < 80) "" else t
    }
}
