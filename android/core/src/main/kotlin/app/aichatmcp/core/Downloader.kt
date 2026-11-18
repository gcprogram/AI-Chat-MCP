package app.aichatmcp.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Der Download wurde vom Nutzer abgebrochen (die angefangene Datei bleibt für das Fortsetzen liegen). */
class DownloadCancelled : IOException("Download abgebrochen")

/** Platzbedarf eines Downloads gegen den freien Platz. */
data class StorageCheck(val neededBytes: Long, val freeBytes: Long, val reserveBytes: Long) {
    val ok: Boolean get() = freeBytes - reserveBytes >= neededBytes
    val missingBytes: Long get() = maxOf(0L, neededBytes + reserveBytes - freeBytes)
}

/**
 * Lädt Dateien herunter: mit Fortschritt, Abbruch und Fortsetzen (die angefangene Datei heißt `….part`, ein neuer Versuch macht dort weiter, wenn der
 * Server Bereichsanfragen kann). Vollständige Dateien werden übersprungen. Ein Hugging-Face-Zugangsschlüssel (für geschützte Modelle) geht nur an
 * huggingface.co.
 */
class Downloader(
    http: OkHttpClient = OkHttpClient(),
    private val huggingFaceToken: () -> String? = { null },
) {
    private val client = http.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(true).build()

    /** Schließt laufende Verbindungen sofort (sonst bemerkt ein totes WLAN den Abbruch erst nach dem Lese-Zeitlimit). */
    fun abort() {
        running?.cancel() // der Dispatcher kennt eine synchrone Anfrage nach den Antwortköpfen nicht mehr, deshalb merken wir sie selbst
    }

    @Volatile
    private var running: okhttp3.Call? = null

    /** [doneBytes] von [totalBytes] aller Dateien; [file] ist die gerade geladene. */
    data class Progress(val doneBytes: Long, val totalBytes: Long, val file: String)

    /** Was fehlt noch? Fertige Dateien zählen nicht, angefangene nur mit dem Rest. */
    fun remainingBytes(files: List<RemoteFile>, dir: File): Long = files.sumOf { f ->
        val target = File(dir, f.name)
        when {
            target.isFile -> 0L
            else -> maxOf(0L, f.bytes - File(dir, f.name + PART).length())
        }
    }

    /** Reicht der Platz? [reserveBytes]: so viel soll danach noch frei sein (Standard 300 MB). */
    fun checkStorage(files: List<RemoteFile>, dir: File, reserveBytes: Long = 300_000_000L): StorageCheck {
        var probe: File? = dir
        while (probe != null && !probe.exists()) probe = probe.parentFile
        return StorageCheck(remainingBytes(files, dir), probe?.usableSpace ?: 0L, reserveBytes)
    }

    /**
     * Lädt [files] nach [dir]. [isCancelled] wird laufend abgefragt. Fehler als [IOException] mit verständlichem Text; bei Abbruch [DownloadCancelled].
     */
    fun download(files: List<RemoteFile>, dir: File, isCancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {}) {
        dir.mkdirs()
        val total = files.sumOf { it.bytes }.coerceAtLeast(1)
        var done = 0L
        for (f in files) {
            val target = File(dir, f.name)
            if (target.isFile) {
                done += maxOf(f.bytes, target.length())
                onProgress(Progress(done, total, f.name))
                continue
            }
            val got = fetch(f, target, isCancelled) { fileBytes -> onProgress(Progress(done + fileBytes, maxOf(total, done + fileBytes), f.name)) }
            done += got
        }
        onProgress(Progress(total.coerceAtLeast(done), total.coerceAtLeast(done), ""))
    }

    private fun fetch(f: RemoteFile, target: File, isCancelled: () -> Boolean, onBytes: (Long) -> Unit): Long {
        target.parentFile?.mkdirs()
        val part = File(target.path + PART)
        var attempt = 0
        while (true) {
            val have = part.length()
            val request = Request.Builder().url(f.url).apply {
                if (have > 0) header("Range", "bytes=$have-")
                huggingFaceToken()?.takeIf { it.isNotBlank() && "huggingface.co" in f.url }?.let { header("Authorization", "Bearer $it") }
            }.build()
            val response = try {
                client.newCall(request).also { running = it }.execute()
            } catch (e: IOException) {
                if (isCancelled()) throw DownloadCancelled()
                throw DownloadInterrupted("Keine Verbindung zu ${request.url.host}: ${e.message}")
            }
            response.use { r ->
                when (r.code) {
                    200, 206 -> {}
                    416 -> { // Bereich nicht erfüllbar: die .part-Datei ist schon vollständig oder unbrauchbar
                        if (part.length() > 0 && attempt++ == 0) {
                            val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            if (total != null && total == part.length()) {
                                part.renameTo(target)
                                return target.length()
                            }
                            part.delete()
                            return@use
                        }
                        throw IOException("Der Server hat die Anfrage abgelehnt (HTTP 416).")
                    }
                    401, 403 -> throw IOException("Zugriff verweigert (HTTP ${r.code}). Das Modell ist geschützt: Hugging-Face-Zugangsschlüssel in den Einstellungen eintragen.")
                    404 -> throw IOException("Datei nicht gefunden (HTTP 404): ${f.name}")
                    else -> throw IOException("Serverfehler (HTTP ${r.code}) bei ${f.name}")
                }
                val body = r.body ?: throw IOException("Leere Antwort von ${request.url.host}")
                val resumed = r.code == 206 && have > 0
                if (!resumed && part.length() > 0) part.delete() // der Server hat die ganze Datei geschickt
                val expected = if (resumed) have + body.contentLength() else body.contentLength()
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(if (resumed) have else 0L)
                    if (!resumed) out.setLength(0)
                    val buffer = ByteArray(64 * 1024)
                    var written = if (resumed) have else 0L
                    var lastReport = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            if (isCancelled()) throw DownloadCancelled()
                            val n = try {
                                input.read(buffer)
                            } catch (e: IOException) { // Verbindung weg (oder von abort() geschlossen)
                                if (isCancelled()) throw DownloadCancelled()
                                throw DownloadInterrupted("Verbindung unterbrochen bei ${f.name}: ${e.message}. Erneut versuchen setzt fort.")
                            }
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            if (written - lastReport >= 256 * 1024) {
                                lastReport = written
                                onBytes(written)
                            }
                        }
                    }
                    if (expected > 0 && written != expected) throw DownloadInterrupted("Download unvollständig: ${f.name} ($written von $expected Bytes). Erneut versuchen setzt fort.")
                }
                if (!part.renameTo(target)) {
                    target.writeBytes(part.readBytes())
                    part.delete()
                }
                onBytes(target.length())
                return target.length()
            }
        }
    }

    companion object {
        const val PART = ".part"
    }
}
