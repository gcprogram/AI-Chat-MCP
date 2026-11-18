package app.aichatmcp.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Der Download wurde vom Nutzer abgebrochen (die angefangene Datei bleibt für das Fortsetzen liegen). */
class DownloadCancelled : IOException("Download abgebrochen")

/** Platzbedarf eines Downloads gegen den freien Platz. */
data class StorageCheck(val neededBytes: Long, val freeBytes: Long, val reserveBytes: Long) {
    /** Ist der freie Platz nicht zu ermitteln (-1, z. B. bei einem Cloud-Ordner), versucht der Download es trotzdem. */
    val known: Boolean get() = freeBytes >= 0
    val ok: Boolean get() = !known || freeBytes - reserveBytes >= neededBytes
    val missingBytes: Long get() = if (!known) 0L else maxOf(0L, neededBytes + reserveBytes - freeBytes)
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
    fun remainingBytes(files: List<RemoteFile>, store: FileStore): Long = files.sumOf { f ->
        when {
            store.exists(f.name) -> 0L
            else -> maxOf(0L, f.bytes - maxOf(0L, store.size(f.name + PART)))
        }
    }

    fun remainingBytes(files: List<RemoteFile>, dir: File): Long = remainingBytes(files, DirStore(dir))

    /** Reicht der Platz? [reserveBytes]: so viel soll danach noch frei sein (Standard 300 MB). */
    fun checkStorage(files: List<RemoteFile>, store: FileStore, reserveBytes: Long = 300_000_000L): StorageCheck =
        StorageCheck(remainingBytes(files, store), store.freeBytes(), reserveBytes)

    fun checkStorage(files: List<RemoteFile>, dir: File, reserveBytes: Long = 300_000_000L): StorageCheck = checkStorage(files, DirStore(dir), reserveBytes)

    fun download(files: List<RemoteFile>, dir: File, isCancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {}) =
        download(files, DirStore(dir), isCancelled, onProgress)

    /**
     * Lädt [files] in [store]. [isCancelled] wird laufend abgefragt. Fehler als [IOException] mit verständlichem Text; bei Abbruch [DownloadCancelled].
     */
    fun download(files: List<RemoteFile>, store: FileStore, isCancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {}) {
        val total = files.sumOf { it.bytes }.coerceAtLeast(1)
        var done = 0L
        for (f in files) {
            if (store.exists(f.name)) {
                done += maxOf(f.bytes, store.size(f.name))
                onProgress(Progress(done, total, f.name))
                continue
            }
            val got = fetch(f, store, isCancelled) { fileBytes -> onProgress(Progress(done + fileBytes, maxOf(total, done + fileBytes), f.name)) }
            done += got
        }
        onProgress(Progress(total.coerceAtLeast(done), total.coerceAtLeast(done), ""))
    }

    private fun fetch(f: RemoteFile, store: FileStore, isCancelled: () -> Boolean, onBytes: (Long) -> Unit): Long {
        val partName = f.name + PART
        var attempt = 0
        while (true) {
            val have = maxOf(0L, store.size(partName))
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
                        if (have > 0 && attempt++ == 0) {
                            val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            if (total != null && total == have) {
                                finish(store, partName, f.name)
                                return store.size(f.name)
                            }
                            store.delete(partName)
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
                if (!resumed && have > 0) store.delete(partName) // der Server hat die ganze Datei geschickt
                val expected = if (resumed) have + body.contentLength() else body.contentLength()
                store.write(partName, append = resumed).use { out ->
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
                finish(store, partName, f.name)
                val size = store.size(f.name)
                onBytes(size)
                return size
            }
        }
    }

    /** Macht aus der angefangenen Datei die fertige (Umbenennen, sonst Kopieren). */
    private fun finish(store: FileStore, partName: String, name: String) {
        if (store.rename(partName, name)) return
        val input = store.read(partName) ?: throw IOException("Die angefangene Datei fehlt: $partName")
        input.use { src -> store.write(name, append = false).use { src.copyTo(it) } }
        store.delete(partName)
    }

    companion object {
        const val PART = ".part"
    }
}
