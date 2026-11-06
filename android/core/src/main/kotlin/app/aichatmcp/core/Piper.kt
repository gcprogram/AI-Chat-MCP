package app.aichatmcp.core

import java.io.File
import java.io.InputStream

/** Eine Stimme eines Companions, die mit diesem Präfix beginnt, ist eine Piper-Stimme (sonst: Name einer Android-Stimme). */
const val PIPER_PREFIX = "piper:"

/** Eine installierte Piper-Stimme: Ordnername und Sprache ("de"), falls erkennbar. */
data class PiperVoice(val name: String, val language: String?) {
    val id: String get() = PIPER_PREFIX + name
}

/** Dateien einer vollständigen Piper-Stimme für sherpa-onnx. */
data class PiperFiles(val onnx: File, val tokens: File, val espeakData: File)

object PiperNames {
    fun isPiper(voice: String): Boolean = voice.startsWith(PIPER_PREFIX)

    fun nameOf(voice: String): String = voice.removePrefix(PIPER_PREFIX)

    /** Ordnername ohne Sonderzeichen (Pfadtrenner, Punkte am Anfang). */
    fun safeName(name: String): String =
        name.trim().replace(Regex("[^\\p{L}\\p{N}._\\-]+"), "_").trim('_', '.').ifEmpty { "stimme" }

    /**
     * Sprache ("de") einer Stimme: aus der Datei `<modell>.onnx.json` (`language.code`, z. B. "de_DE") oder, wenn die fehlt,
     * aus dem Ordnernamen (z. B. "vits-piper-de_DE-thorsten-medium" oder "de_DE-ich").
     */
    fun languageOf(folderName: String, json: String?): String? {
        json?.let { text ->
            Regex("\"code\"\\s*:\\s*\"([A-Za-z]{2,3})[_-]?[A-Za-z]*\"").find(text)?.let { return it.groupValues[1].lowercase() }
        }
        return Regex("(?<![A-Za-z])([a-z]{2,3})_[A-Z]{2}(?![A-Za-z])").find(folderName)?.groupValues?.get(1)
    }
}

/**
 * Piper-Stimmen im App-Speicher: je Stimme ein Ordner mit `*.onnx`, `tokens.txt` (und meist `*.onnx.json`); die espeak-ng-Daten
 * (Aussprache, für alle Stimmen gleich) liegen einmal gemeinsam in `espeak-ng-data`.
 */
class PiperStore(private val root: File) {
    private val espeak = File(root, ESPEAK)

    fun espeakReady(): Boolean = File(espeak, "phontab").isFile

    private fun voiceDir(name: String) = File(root, PiperNames.safeName(name))

    private fun onnxOf(dir: File): File? = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.minByOrNull { it.name }

    /** Vollständige Stimmen (Modell + tokens.txt), nach Name sortiert. */
    fun list(): List<PiperVoice> =
        root.listFiles { f -> f.isDirectory && f.name != ESPEAK }.orEmpty()
            .filter { onnxOf(it) != null && File(it, "tokens.txt").isFile }
            .sortedBy { it.name }
            .map { dir ->
                val json = dir.listFiles { f -> f.name.endsWith(".onnx.json") }?.firstOrNull()?.let { runCatching { it.readText() }.getOrNull() }
                PiperVoice(dir.name, PiperNames.languageOf(dir.name, json))
            }

    /** Dateien zum Laden, oder null, wenn etwas fehlt. */
    fun files(name: String): PiperFiles? {
        val dir = voiceDir(name)
        val onnx = onnxOf(dir) ?: return null
        val tokens = File(dir, "tokens.txt").takeIf { it.isFile } ?: return null
        return if (espeakReady()) PiperFiles(onnx, tokens, espeak) else null
    }

    /** Fehlertext, falls die Stimme unvollständig ist, sonst null. */
    fun problem(name: String): String? {
        val dir = voiceDir(name)
        return when {
            onnxOf(dir) == null -> "Keine .onnx-Modelldatei im Ordner."
            !File(dir, "tokens.txt").isFile ->
                "tokens.txt fehlt: Das Piper-Modell muss für sherpa-onnx umgewandelt sein (siehe docs/EIGENE_STIMME.md)."
            !espeakReady() -> "Der Ordner espeak-ng-data fehlt (er liegt in jedem sherpa-onnx-Stimmenpaket, z. B. vits-piper-de_DE-…)."
            else -> null
        }
    }

    /**
     * Schreibt eine Datei des Ordners [folder] ein; `espeak-ng-data/…` geht in den gemeinsamen Ordner. Mit [skipShared] (zu Beginn des
     * Imports mit [espeakReady] bestimmt) werden die gemeinsamen Daten nicht noch einmal kopiert. Liefert false, wenn übersprungen.
     */
    fun importFile(folder: String, relativePath: String, input: InputStream, skipShared: Boolean = false): Boolean {
        val parts = relativePath.split('/', '\\').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == ".." || it == "." }) return false
        val target = if (parts[0] == ESPEAK) {
            if (skipShared) return false // schon vorhanden
            File(root, parts.joinToString("/"))
        } else {
            File(voiceDir(folder), parts.joinToString("/"))
        }
        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) return false
        target.parentFile?.mkdirs()
        target.outputStream().use { input.copyTo(it) }
        return true
    }

    fun delete(name: String) {
        voiceDir(name).deleteRecursively()
    }

    /** Löscht einen angefangenen Import (Ordner der Stimme; die gemeinsamen Daten bleiben, wenn sie vollständig sind). */
    fun discardIncomplete(name: String) {
        delete(name)
        if (!espeakReady()) espeak.deleteRecursively()
    }

    companion object {
        const val ESPEAK = "espeak-ng-data"
    }
}
