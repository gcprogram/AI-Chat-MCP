package app.aichatmcp

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.aichatmcp.core.CatalogEntry
import app.aichatmcp.core.DownloadCancelled
import app.aichatmcp.core.Downloader
import app.aichatmcp.core.Folders
import app.aichatmcp.core.GgufFile
import app.aichatmcp.core.HuggingFace
import app.aichatmcp.core.HuggingFaceApi
import app.aichatmcp.core.ModelCatalog
import app.aichatmcp.core.ModelHit
import app.aichatmcp.core.ModelKind
import app.aichatmcp.core.PiperStore
import app.aichatmcp.core.RemoteFile
import app.aichatmcp.core.StorageCheck
import app.aichatmcp.local.VisionLlm
import app.aichatmcp.ui.tNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Ein Download, der auf die Bestätigung wartet (Größe, freier Platz, Netz). */
class PendingDownload(val title: String, val files: List<RemoteFile>, val dir: File, val check: StorageCheck, val onWifi: Boolean, val finish: () -> Unit) {
    val bytes: Long get() = check.neededBytes
}

/** Fortschritt des laufenden Downloads. */
data class DownloadState(val title: String, val doneBytes: Long = 0, val totalBytes: Long = 0, val file: String = "")

/** Suche und Dateiliste von Hugging Face. */
data class HfState(
    val busy: Boolean = false, val error: String = "", val hits: List<ModelHit> = emptyList(),
    val repo: String = "", val files: List<GgufFile> = emptyList(),
)

/**
 * Lädt Modelle aus dem Katalog oder von Hugging Face herunter: Platz und Netz werden vorher geprüft (Bestätigung durch den Nutzer), der Download
 * läuft mit Fortschritt, Abbruch und Fortsetzen. Ein Download zur Zeit. Was nach dem Laden zu tun ist (Modell wählen, Liste neu lesen), erledigt
 * der Rückruf [onInstalled].
 */
class ModelManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val storage: AppStorage,
    private val folders: AppFolders,
    private val vision: VisionLlm,
    private val token: () -> String?,
    private val onInstalled: (CatalogEntry?, File?) -> Unit,
) {
    var pending by mutableStateOf<PendingDownload?>(null)
        private set
    var active by mutableStateOf<DownloadState?>(null)
        private set
    var note by mutableStateOf("")
    var hf by mutableStateOf(HfState())
        private set

    private var job: Job? = null

    @Volatile
    private var cancelled = false
    private val downloader get() = Downloader(huggingFaceToken = token)
    private val api get() = HuggingFaceApi(token = token)

    /** Läuft das Gerät über WLAN (oder Kabel)? Mobile Daten und getaktete Verbindungen zählen nicht. */
    fun onWifi(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun isInstalled(entry: CatalogEntry): Boolean = when (entry.kind) {
        ModelKind.CHAT -> File(folders.models, entry.files.first().name).isFile
        ModelKind.WHISPER -> storage.whisper.files(entry.folder) != null
        ModelKind.VISION -> vision.available
        ModelKind.PIPER -> storage.piper.espeakReady() && storage.piper.list().any { it.name == entry.folder }
    }

    // --- Katalog ---------------------------------------------------------------------------------------

    /** Bereitet den Download eines Katalogeintrags vor (Platzprüfung) und wartet auf die Bestätigung. */
    fun ask(entry: CatalogEntry) {
        if (active != null) return
        scope.launch {
            note = ""
            try {
                val (files, dir) = withContext(Dispatchers.IO) { filesFor(entry) }
                val check = withContext(Dispatchers.IO) { downloader.checkStorage(files, dir) }
                pending = PendingDownload(entry.title, files, dir, check, onWifi()) { onInstalled(entry, null) }
            } catch (e: Exception) {
                note = e.message ?: e::class.simpleName.orEmpty()
            }
        }
    }

    private fun filesFor(entry: CatalogEntry): Pair<List<RemoteFile>, File> = when (entry.kind) {
        ModelKind.CHAT -> entry.files to folders.models
        ModelKind.VISION -> entry.files to vision.dir
        ModelKind.WHISPER -> entry.files to File(storage.whisper.root, entry.folder)
        ModelKind.PIPER -> {
            val own = entry.files.map { it.copy(name = "${entry.folder}/${it.name}") }
            // die gemeinsamen Sprachdaten (espeak-ng-data) braucht jede Stimme, einmal für alle
            val shared = if (storage.piper.espeakReady()) emptyList() else api.folder(ModelCatalog.ESPEAK_REPO, PiperStore.ESPEAK)
            (shared + own) to storage.piper.root // die Stimme selbst zuletzt: erst dann gilt sie als installiert
        }
    }

    // --- Hugging Face ----------------------------------------------------------------------------------

    /** Eingabe: Adresse, Kurzform "besitzer/repo" oder Suchwort. */
    fun hfFind(input: String) {
        val text = input.trim()
        if (text.isEmpty() || hf.busy) return
        hf = HfState(busy = true)
        scope.launch {
            val parsed = HuggingFace.parseInput(text)
            hf = runCatching {
                withContext(Dispatchers.IO) {
                    if (parsed != null) HfState(repo = parsed.first, files = HuggingFace.ggufFiles(api.files(parsed.first)))
                    else HfState(hits = api.search(text))
                }
            }.getOrElse { HfState(error = it.message ?: "Fehler") }
            if (hf.repo.isNotEmpty() && hf.files.isEmpty() && hf.error.isEmpty()) hf = hf.copy(error = "Keine GGUF-Dateien in diesem Repository.")
        }
    }

    fun hfOpen(repo: String) {
        hf = HfState(busy = true)
        scope.launch {
            hf = runCatching { withContext(Dispatchers.IO) { HfState(repo = repo, files = HuggingFace.ggufFiles(api.files(repo))) } }
                .getOrElse { HfState(error = it.message ?: "Fehler") }
            if (hf.files.isEmpty() && hf.error.isEmpty()) hf = hf.copy(error = "Keine GGUF-Dateien in diesem Repository.")
        }
    }

    fun hfClear() {
        hf = HfState()
    }

    /** Bereitet den Download einer GGUF-Datei von Hugging Face in den Modellordner vor. */
    fun askGguf(file: GgufFile) {
        if (active != null) return
        val dir = folders.models
        val files = listOf(file.file.copy(name = file.file.name.substringAfterLast('/')))
        scope.launch {
            val check = withContext(Dispatchers.IO) { downloader.checkStorage(files, dir) }
            pending = PendingDownload(files.first().name, files, dir, check, onWifi()) { onInstalled(null, File(dir, files.first().name)) }
        }
    }

    // --- Ablauf ----------------------------------------------------------------------------------------

    fun dismiss() {
        pending = null
    }

    fun confirm() {
        val p = pending ?: return
        if (!p.check.ok) return
        pending = null
        cancelled = false
        active = DownloadState(p.title, 0, p.files.sumOf { it.bytes })
        var last = 0L
        job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    downloader.download(p.files, p.dir, isCancelled = { cancelled }) { pr ->
                        val now = System.currentTimeMillis()
                        if (now - last > 250) { // nicht bei jedem Häppchen die Oberfläche neu zeichnen
                            last = now
                            active = DownloadState(p.title, pr.doneBytes, pr.totalBytes, pr.file)
                        }
                    }
                }
            }
            active = null
            result.onSuccess {
                note = tNow("\"${p.title}\" is ready.", "„${p.title}“ ist fertig.")
                p.finish()
            }.onFailure {
                note = if (it is DownloadCancelled) tNow("Cancelled (the partial file is kept; a new download continues there).", "Abgebrochen (die angefangene Datei bleibt, ein neuer Download macht dort weiter).") else (it.message ?: it::class.simpleName.orEmpty())
            }
        }
    }

    fun cancel() {
        cancelled = true
    }

    /** Freier Platz im Modellordner (für die Anzeige). */
    fun freeBytes(): Long = runCatching { folders.models.usableSpace }.getOrDefault(0L)

    /** Modelldateien im Modellordner. */
    fun localModels(): List<File> = Folders.models(folders.models)
}
