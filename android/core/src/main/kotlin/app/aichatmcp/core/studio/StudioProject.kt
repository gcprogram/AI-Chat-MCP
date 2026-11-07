package app.aichatmcp.core.studio

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt

/** Eine gespeicherte Aufnahme: Dateiname (`0001.wav`) und der gesprochene Text. */
data class Recording(val name: String, val text: String)

/** Prüfergebnis wie `check_dataset.py`: Fehler verderben das Training, Hinweise sind Empfehlungen. */
data class DatasetReport(
    val count: Int, val minutes: Double, val spreadDb: Double, val meanLevelDb: Double, val medianSeconds: Double,
    val errors: List<String>, val notes: List<String>,
)

/**
 * Ein Aufnahme-Projekt = ein Ordner im Format des Python-Werkzeugs und von Piper: `wavs/0001.wav, …`, `metadata.csv` (`0001.wav|Text`)
 * und `sentences.txt` (noch offene Sätze, einer je Zeile). Der Ordner lässt sich unverändert auf den PC kopieren.
 */
class StudioProject(val dir: File) {
    private val wavDir = File(dir, "wavs")
    private val metaFile = File(dir, "metadata.csv")
    private val pendingFile = File(dir, "sentences.txt")

    val name: String get() = dir.name

    fun recordings(): List<Recording> {
        if (!metaFile.exists()) return emptyList()
        return metaFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map {
            val bar = it.indexOf('|')
            if (bar < 0) Recording(it.trim(), "") else Recording(it.substring(0, bar).trim(), it.substring(bar + 1).trim())
        }
    }

    fun pending(): List<String> =
        if (pendingFile.exists()) pendingFile.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

    fun wavFile(name: String): File = File(wavDir, name)

    private fun writePending(list: List<String>) {
        dir.mkdirs()
        pendingFile.writeText(list.joinToString("\n") + if (list.isEmpty()) "" else "\n", Charsets.UTF_8)
    }

    private fun writeRecordings(list: List<Recording>) {
        dir.mkdirs()
        metaFile.writeText(list.joinToString("") { "${it.name}|${it.text}\n" }, Charsets.UTF_8)
    }

    /** Hängt neue Sätze an die offene Liste (schon aufgenommene oder vorhandene fallen weg). Liefert die Anzahl der neuen. */
    fun addSentences(sentences: List<String>): Int {
        val known = HashSet<String>().apply { addAll(pending()); recordings().forEach { add(it.text) } }
        val fresh = sentences.map { it.trim() }.filter { it.isNotEmpty() && known.add(it) }
        if (fresh.isNotEmpty()) writePending(pending() + fresh)
        return fresh.size
    }

    fun nextSentence(): String? = pending().firstOrNull()

    /** Legt den aktuellen Satz ans Ende der Liste (überspringen). */
    fun skip(sentence: String) {
        val list = pending()
        if (sentence in list) writePending(list - sentence + sentence)
    }

    /** Speichert eine Aufnahme zu [sentence] (nimmt den Satz aus der offenen Liste). */
    fun save(sentence: String, samples: ShortArray, rate: Int = TARGET_RATE): Recording {
        wavDir.mkdirs()
        val existing = recordings()
        var number = (existing.maxOfOrNull { it.name.substringBefore('.').toIntOrNull() ?: 0 } ?: 0) + 1
        while (File(wavDir, "%04d.wav".format(number)).exists()) number++
        val rec = Recording("%04d.wav".format(number), sentence)
        File(wavDir, rec.name).writeBytes(Wav.encode(samples, rate))
        writeRecordings(existing + rec)
        writePending(pending() - sentence)
        return rec
    }

    /** Löscht eine Aufnahme; der Satz kommt an den Anfang der offenen Liste zurück (nochmal einsprechen). */
    fun delete(name: String) {
        val list = recordings()
        val rec = list.firstOrNull { it.name == name } ?: return
        File(wavDir, name).delete()
        writeRecordings(list - rec)
        if (rec.text.isNotEmpty() && rec.text !in pending()) writePending(listOf(rec.text) + pending())
    }

    fun minutes(): Double = recordings().sumOf { durationSeconds(it.name) } / 60.0

    fun durationSeconds(name: String): Double {
        val f = File(wavDir, name)
        if (!f.exists()) return 0.0
        // Bei den eigenen Dateien (Mono, 16 Bit) genügt die Dateigröße; Kopf = 44 Byte. Rate aus dem Kopf (Byte 24..27).
        val head = f.inputStream().use { s -> ByteArray(28).also { s.read(it) } }
        val rate = (head[24].toInt() and 0xFF) or ((head[25].toInt() and 0xFF) shl 8) or ((head[26].toInt() and 0xFF) shl 16) or ((head[27].toInt() and 0xFF) shl 24)
        return if (rate > 0) (f.length() - 44) / 2.0 / rate else 0.0
    }

    /** ZIP mit `metadata.csv` und `wavs/…` (Wellen werden kaum kleiner, deshalb schnelle Kompression). Liefert die Anzahl Aufnahmen. */
    fun exportZip(out: OutputStream): Int {
        val list = recordings().filter { File(wavDir, it.name).exists() }
        ZipOutputStream(out.buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry("metadata.csv"))
            zip.write(list.joinToString("") { "${it.name}|${it.text}\n" }.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (rec in list) {
                zip.putNextEntry(ZipEntry("wavs/${rec.name}"))
                File(wavDir, rec.name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return list.size
    }

    /**
     * Übernimmt Aufnahmen aus einer ZIP (`metadata.csv` + `wavs/…`, z. B. vom Python-Werkzeug oder aus einem Export). Schon vorhandene
     * Sätze werden übersprungen, andere Abtastraten umgerechnet; die Nummern werden neu vergeben. Liefert die Anzahl neu übernommener.
     */
    fun importZip(input: InputStream): Int {
        val temp = File(dir, ".import").apply { deleteRecursively(); mkdirs() }
        try {
            var rows = emptyList<Pair<String, String>>()
            ZipInputStream(input.buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    if (e.isDirectory) return@forEach
                    val base = e.name.substringAfterLast('/').substringAfterLast('\\') // nur der Dateiname: kein Pfad aus der ZIP gilt
                    when {
                        base.equals("metadata.csv", ignoreCase = true) -> rows = zip.readBytes().toString(Charsets.UTF_8).lines()
                            .filter { it.isNotBlank() }.map { it.substringBefore('|').trim() to it.substringAfter('|', "").trim() }
                        base.endsWith(".wav", ignoreCase = true) && base.isNotEmpty() -> File(temp, base).outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            val known = HashSet<String>().apply { recordings().forEach { add(it.text) } }
            var count = 0
            for ((entry, text) in rows) {
                if (text.isEmpty() || text in known) continue
                val file = File(temp, if (entry.endsWith(".wav", ignoreCase = true)) entry else "$entry.wav")
                if (!file.exists()) continue
                val data = runCatching { Wav.decode(file.readBytes()) }.getOrNull() ?: continue
                val samples = if (data.rate == TARGET_RATE) data.samples
                else AudioMath.toShort(AudioMath.resample(AudioMath.toFloat(data.samples), data.rate, TARGET_RATE))
                save(text, samples)
                known += text
                count++
            }
            return count
        } finally {
            temp.deleteRecursively()
        }
    }

    /** Prüft das Projekt wie `check_dataset.py` (gleiche Schwellen und Texte). */
    fun check(): DatasetReport {
        val errors = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val levels = mutableListOf<Double>()
        val durations = mutableListOf<Double>()
        val rows = recordings()
        val seen = HashSet<String>()
        for ((name, text) in rows) {
            if (!seen.add(name)) errors += "$name: kommt mehrfach in metadata.csv vor"
            if (text.isEmpty()) errors += "$name: kein Text"
            val file = File(wavDir, name)
            if (!file.exists()) { errors += "$name: Datei fehlt in wavs/"; continue }
            val data = try { Wav.decode(file.readBytes()) } catch (e: Exception) { errors += "$name: nicht lesbar (${e.message})"; continue }
            if (data.rate != TARGET_RATE) errors += "$name: Abtastrate ${data.rate} Hz statt $TARGET_RATE Hz"
            val s = AudioMath.stats(AudioMath.toFloat(data.samples), data.rate)
            durations += s.duration
            levels += s.rmsDb
            if (s.duration < 0.8) notes += "$name: sehr kurz (${"%.1f".format(s.duration)} s)"
            if (s.duration > 15) notes += "$name: sehr lang (${"%.1f".format(s.duration)} s), besser in zwei Sätze teilen"
            if (s.peak < 0.1) notes += "$name: sehr leise (Spitze ${"%.0f".format(AudioMath.db(s.peak))} dBFS)"
            if (s.clip > 0.0005) errors += "$name: übersteuert (${"%.2f".format(s.clip * 100)} % der Samples am Anschlag)"
            if (s.lead > 1.2 || s.tail > 1.5) notes += "$name: lange Stille (vorn ${"%.1f".format(s.lead)} s, hinten ${"%.1f".format(s.tail)} s)"
        }
        val minutes = durations.sum() / 60
        val mean = if (levels.isEmpty()) 0.0 else levels.average()
        val spread = if (levels.size > 1) sqrt(levels.sumOf { (it - mean) * (it - mean) } / levels.size) else 0.0
        if (spread > 5.0) errors += "Die Lautstärke schwankt stark zwischen den Aufnahmen (${"%.1f".format(spread)} dB Streuung): gleichen Abstand und Pegel halten"
        else if (spread > 3.0) notes += "Die Lautstärke schwankt etwas (${"%.1f".format(spread)} dB Streuung)"
        when {
            minutes < 15 -> errors += "Nur ${"%.1f".format(minutes)} Minuten Sprache: zu wenig, auch für eine Feinabstimmung"
            minutes < 30 -> notes += "${"%.1f".format(minutes)} Minuten: knapp, reicht nur für eine Feinabstimmung. Noch etwa ${"%.0f".format(30 - minutes)} " +
                "Minuten bis zur Mindestempfehlung (30), besser ${"%.0f".format(60 - minutes)} bis 60. Nimm NEUE Sätze auf, nicht dieselben noch einmal."
            minutes < 60 -> notes += "${"%.1f".format(minutes)} Minuten: reicht für eine Feinabstimmung; mehr Aufnahmen verbessern das Ergebnis"
        }
        val sorted = durations.sorted()
        val median = if (sorted.isEmpty()) 0.0 else if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        return DatasetReport(rows.size, minutes, spread, mean, median, errors, notes)
    }
}

/** Alle Projekte im App-Speicher (je Ordner eins). */
class StudioStore(private val root: File) {
    fun list(): List<String> = root.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted().orEmpty()

    fun project(name: String): StudioProject = StudioProject(File(root, safe(name)))

    /** Legt ein Projekt an (oder öffnet das gleichnamige) und liefert seinen Ordnernamen. */
    fun create(name: String): StudioProject = project(name).also { it.dir.mkdirs() }

    fun delete(name: String) {
        File(root, safe(name)).deleteRecursively()
    }

    companion object {
        fun safe(name: String): String = name.trim().replace(Regex("[^\\p{L}\\p{N}._\\-]+"), "_").trim('_', '.').ifEmpty { "stimme" }
    }
}
