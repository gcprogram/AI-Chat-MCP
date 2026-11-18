package app.aichatmcp

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.aichatmcp.core.CatalogEntry
import app.aichatmcp.core.DownloadCancelled
import app.aichatmcp.core.DownloadInterrupted
import app.aichatmcp.core.Downloader
import app.aichatmcp.core.DirStore
import app.aichatmcp.core.FileStore
import app.aichatmcp.core.Folders
import app.aichatmcp.core.GgufFile
import app.aichatmcp.core.HuggingFace
import app.aichatmcp.core.HuggingFaceApi
import app.aichatmcp.core.ModelCatalog
import app.aichatmcp.core.ModelHit
import app.aichatmcp.core.ModelKind
import app.aichatmcp.core.NetAction
import app.aichatmcp.core.NetKind
import app.aichatmcp.core.NetworkPolicy
import app.aichatmcp.core.PiperStore
import app.aichatmcp.core.RemoteFile
import app.aichatmcp.core.StorageCheck
import app.aichatmcp.core.StoredFile
import app.aichatmcp.download.DownloadService
import app.aichatmcp.local.VisionLlm
import app.aichatmcp.ui.tNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Ein Download, der auf die Bestätigung wartet (Größe, freier Platz, Netz). */
class PendingDownload(val title: String, val files: List<RemoteFile>, val store: FileStore, val check: StorageCheck, val onWifi: Boolean, val finish: () -> Unit) {
    val bytes: Long get() = check.neededBytes
}

/** Fortschritt des laufenden Downloads. */
data class DownloadState(val title: String, val doneBytes: Long = 0, val totalBytes: Long = 0, val file: String = "")

/** Warum ein Download angehalten wurde. */
enum class InterruptReason {
    /** Das WLAN ist weg, es gibt nur noch Mobilfunk (nicht erlaubt): der Nutzer entscheidet. */
    WIFI_LOST,

    /** Gar kein Netz: es wird gewartet, bis eines da ist. */
    OFFLINE,

    /** Die Verbindung riss trotz Netz ab (Server, Funkloch): erneut versuchen oder abbrechen. */
    FAILED,

    /** Android hat den Hintergrunddienst beendet (Zeitlimit der Systemdienste). */
    SYSTEM,
}

/**
 * Ein angehaltener Download. [ask]: dem Nutzer wird die Entscheidung gezeigt (Dialog und Benachrichtigung); nach "Auf WLAN warten" steht es auf
 * false, der Download wartet dann still und läuft von selbst weiter, sobald das WLAN zurück ist.
 */
data class Interruption(val reason: InterruptReason, val ask: Boolean = true, val detail: String = "", val remainingBytes: Long = 0)

/** Suche und Dateiliste von Hugging Face. */
data class HfState(
    val busy: Boolean = false, val error: String = "", val hits: List<ModelHit> = emptyList(),
    val repo: String = "", val files: List<GgufFile> = emptyList(),
)

/**
 * Lädt Modelle aus dem Katalog oder von Hugging Face herunter: Platz und Netz werden vorher geprüft (Bestätigung durch den Nutzer), der Download
 * läuft mit Fortschritt, Abbruch und Fortsetzen. Ein Download zur Zeit.
 *
 * **Eine Instanz pro Prozess** ([shared]): Der Download läuft in einem Vordergrunddienst ([DownloadService], Benachrichtigung mit Fortschritt) und
 * überlebt damit App-Wechsel, Bildschirmsperre und das Schließen der Oberfläche; ein neues ViewModel findet den laufenden Download wieder vor.
 * **Netzwechsel:** Fällt das WLAN weg (oder gibt es nur noch Mobilfunk, den der Nutzer nicht erlaubt hat), wird angehalten und nachgefragt
 * ([interruption]: Über Mobilfunk fortsetzen, Auf WLAN warten, Abbrechen); ohne jedes Netz wird gewartet. Kommt ein passendes Netz zurück, geht es
 * von selbst weiter, an der Stelle, wo die angefangene Datei endet. Was nach dem Laden zu tun ist (Modell wählen, Liste neu lesen), erledigt [onInstalled].
 */
class ModelManager private constructor(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val storage = openStorage(app)
    private val folders = AppFolders(app)

    /** Der gewählte Modellordner (die Einstellung liest der Manager selbst, weil er auch ohne Oberfläche läuft). */
    private fun modelStore(): FileStore = folders.modelStore(storage.loadConfig().modelFolder)
    private val vision = VisionLlm(app)
    private val token: () -> String? = { storage.secrets()["huggingface"] }

    /** Wird nach einem fertigen Download aufgerufen (vom aktuellen ViewModel gesetzt, beim Schließen wieder entfernt). */
    var onInstalled: ((CatalogEntry?, String?) -> Unit)? = null

    /** Die Modelldateien im Modellordner (Zustand für die Oberfläche; [refreshLocal] liest neu, im Hintergrund). */
    var localModels by mutableStateOf<List<StoredFile>>(emptyList())
        private set

    var pending by mutableStateOf<PendingDownload?>(null)
        private set
    var active by mutableStateOf<DownloadState?>(null)
        private set
    var interruption by mutableStateOf<Interruption?>(null)
        private set
    var note by mutableStateOf("")
    var hf by mutableStateOf(HfState())
        private set

    private var job: Job? = null
    private var current: PendingDownload? = null
    private var downloader: Downloader? = null
    private var allowMobile = false
    private var lastKind: NetKind? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var autoRetries = 0

    /** Wer den laufenden Download anhält (der Downloader fragt das Flag laufend ab). */
    private enum class Stop { USER, NETWORK, SYSTEM }

    @Volatile
    private var stop: Stop? = null
    private val api get() = HuggingFaceApi(token = token)

    // --- Netz ------------------------------------------------------------------------------------------

    /** Läuft das Gerät über WLAN (oder Kabel)? Mobile Daten und getaktete Verbindungen zählen nicht. */
    fun onWifi(): Boolean = netKind() == NetKind.WIFI

    private fun netKind(): NetKind {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return NetKind.NONE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return NetKind.NONE) ?: return NetKind.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetKind.NONE
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return if (wifi && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) NetKind.WIFI else NetKind.MOBILE
    }

    /** Beobachtet das Netz, solange ein Download läuft oder angehalten ist. */
    private fun watchNetwork() {
        if (callback != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        lastKind = netKind()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
        }
        callback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onFailure { callback = null }
    }

    private fun unwatchNetwork() {
        val cb = callback ?: return
        callback = null
        runCatching { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    private fun changed() {
        scope.launch { onNetworkChanged(netKind()) }
    }

    /** Das Netz hat sich geändert (Hauptthread): anhalten, nachfragen oder fortsetzen. Auch von Tests aufrufbar. */
    internal fun onNetworkChanged(kind: NetKind) {
        if (kind == lastKind) return
        lastKind = kind
        val p = current ?: return
        val action = NetworkPolicy.action(kind, allowMobile)
        val waiting = interruption
        if (waiting == null) {
            if (action != NetAction.RUN && job?.isActive == true) {
                stop = Stop.NETWORK
                downloader?.abort() // ein totes WLAN würde sonst bis zum Lese-Zeitlimit hängen
            }
            return
        }
        when {
            action == NetAction.RUN && waiting.reason != InterruptReason.SYSTEM -> resume(p)
            action == NetAction.ASK && waiting.reason != InterruptReason.WIFI_LOST -> interrupt(p, InterruptReason.WIFI_LOST, ask = true)
            action == NetAction.WAIT && waiting.reason != InterruptReason.OFFLINE -> interrupt(p, InterruptReason.OFFLINE, ask = false)
        }
    }

    // --- Katalog ---------------------------------------------------------------------------------------

    fun isInstalled(entry: CatalogEntry): Boolean = when (entry.kind) {
        ModelKind.CHAT -> localModels.any { it.name == entry.files.first().name }
        ModelKind.WHISPER -> storage.whisper.files(entry.folder) != null
        ModelKind.VISION -> vision.available
        ModelKind.PIPER -> storage.piper.espeakReady() && storage.piper.list().any { it.name == entry.folder }
    }

    /** Bereitet den Download eines Katalogeintrags vor (Platzprüfung) und wartet auf die Bestätigung. */
    fun ask(entry: CatalogEntry) {
        if (active != null) return
        scope.launch {
            note = ""
            try {
                val (files, store) = withContext(Dispatchers.IO) { filesFor(entry) }
                val check = withContext(Dispatchers.IO) { Downloader(huggingFaceToken = token).checkStorage(files, store) }
                pending = PendingDownload(entry.title, files, store, check, onWifi()) { onInstalled?.invoke(entry, null) }
            } catch (e: Exception) {
                note = e.message ?: e::class.simpleName.orEmpty()
            }
        }
    }

    private fun filesFor(entry: CatalogEntry): Pair<List<RemoteFile>, FileStore> = when (entry.kind) {
        ModelKind.CHAT -> entry.files to modelStore()
        ModelKind.VISION -> entry.files to DirStore(vision.dir)
        ModelKind.WHISPER -> entry.files to DirStore(File(storage.whisper.root, entry.folder))
        ModelKind.PIPER -> {
            val own = entry.files.map { it.copy(name = "${entry.folder}/${it.name}") }
            // die gemeinsamen Sprachdaten (espeak-ng-data) braucht jede Stimme, einmal für alle
            val shared = if (storage.piper.espeakReady()) emptyList() else api.folder(ModelCatalog.ESPEAK_REPO, PiperStore.ESPEAK)
            (shared + own) to DirStore(storage.piper.root) // die Stimme selbst zuletzt: erst dann gilt sie als installiert
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
        val files = listOf(file.file.copy(name = file.file.name.substringAfterLast('/')))
        scope.launch {
            val (store, check) = withContext(Dispatchers.IO) {
                val st = modelStore()
                st to Downloader(huggingFaceToken = token).checkStorage(files, st)
            }
            pending = PendingDownload(files.first().name, files, store, check, onWifi()) { onInstalled?.invoke(null, files.first().name) }
        }
    }

    // --- Ablauf ----------------------------------------------------------------------------------------

    fun dismiss() {
        pending = null
    }

    /** Startet den bestätigten Download. Ohne WLAN hat der Nutzer die Warnung gesehen: Mobilfunk gilt dann als erlaubt. */
    fun confirm() {
        val p = pending ?: return
        if (!p.check.ok) return
        pending = null
        current = p
        allowMobile = !p.onWifi
        autoRetries = 0
        interruption = null
        note = ""
        active = DownloadState(p.title, 0, p.files.sumOf { it.bytes })
        DownloadService.start(app)
        watchNetwork()
        run(p)
    }

    private fun run(p: PendingDownload) {
        stop = null
        interruption = null
        val dl = Downloader(huggingFaceToken = token)
        downloader = dl
        var last = 0L
        var progressed = false
        val first = active?.doneBytes ?: 0L
        job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    dl.download(p.files, p.store, isCancelled = { stop != null }) { pr ->
                        val now = System.currentTimeMillis()
                        if (now - last > 250) { // nicht bei jedem Häppchen die Oberfläche neu zeichnen
                            last = now
                            if (pr.doneBytes > first) progressed = true
                            scope.launch {
                                if (downloader === dl) active = DownloadState(p.title, pr.doneBytes, pr.totalBytes, pr.file)
                                DownloadService.refresh(app, force = false)
                            }
                        }
                    }
                }
            }
            downloader = null
            result.onSuccess { finished(p) }.onFailure { handleFailure(p, it, progressed) }
        }
    }

    private fun handleFailure(p: PendingDownload, e: Throwable, progressed: Boolean) {
        when {
            e is DownloadCancelled && stop == Stop.USER -> end(tNow("Cancelled (the partial file is kept; a new download continues there).", "Abgebrochen (die angefangene Datei bleibt, ein neuer Download macht dort weiter)."))
            e is DownloadCancelled && stop == Stop.SYSTEM -> interrupt(p, InterruptReason.SYSTEM, ask = true)
            e is DownloadCancelled || e is DownloadInterrupted -> {
                val kind = netKind()
                lastKind = kind
                when (NetworkPolicy.action(kind, allowMobile)) {
                    NetAction.ASK -> interrupt(p, InterruptReason.WIFI_LOST, ask = true)
                    NetAction.WAIT -> interrupt(p, InterruptReason.OFFLINE, ask = false)
                    NetAction.RUN -> {
                        // das Netz ist da, der Server oder ein Funkloch hat die Verbindung gekappt: ein paar Mal von selbst wieder versuchen
                        if (progressed) autoRetries = 0
                        if (autoRetries < MAX_AUTO_RETRIES) {
                            autoRetries++
                            scope.launch {
                                delay(RETRY_DELAY_MS)
                                if (current === p && interruption == null && job?.isActive != true) run(p)
                            }
                        } else {
                            interrupt(p, InterruptReason.FAILED, ask = true, detail = e.message.orEmpty())
                        }
                    }
                }
            }
            else -> end(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun interrupt(p: PendingDownload, reason: InterruptReason, ask: Boolean, detail: String = "") {
        interruption = Interruption(reason, ask, detail, 0)
        DownloadService.refresh(app)
        scope.launch { // den Rest im Hintergrund zählen (ein gewählter Ordner des Systems antwortet langsamer als ein Pfad)
            val left = withContext(Dispatchers.IO) { runCatching { Downloader(huggingFaceToken = token).remainingBytes(p.files, p.store) }.getOrDefault(0L) }
            interruption?.takeIf { it.reason == reason && current === p }?.let {
                interruption = it.copy(remainingBytes = left)
                DownloadService.refresh(app)
            }
        }
    }

    /** Das Netz ist zurück oder der Nutzer hat entschieden: dort weitermachen, wo die angefangene Datei endet. */
    private fun resume(p: PendingDownload) {
        if (job?.isActive == true) return
        DownloadService.start(app)
        run(p)
    }

    private fun finished(p: PendingDownload) {
        val title = p.title
        end(tNow("\"$title\" is ready.", "„$title“ ist fertig."))
        DownloadService.finished(app, title)
        scope.launch {
            localModels = withContext(Dispatchers.IO) { listLocal() } // erst die Liste neu lesen, dann das Modell wählen lassen
            p.finish()
        }
    }

    /** Beendet den Download (fertig, abgebrochen, Fehler): Zustand leeren, Dienst und Netzbeobachtung beenden. */
    private fun end(message: String) {
        current = null
        active = null
        interruption = null
        stop = null
        unwatchNetwork()
        DownloadService.stop(app)
        note = message
    }

    // --- Entscheidungen des Nutzers ---------------------------------------------------------------------

    /** Nach "WLAN weg": ab jetzt auch über Mobilfunk laden (das Datenvolumen zahlt der Nutzer). */
    fun continueOnMobile() {
        val p = current ?: return
        allowMobile = true
        if (netKind() != NetKind.NONE) resume(p) else interrupt(p, InterruptReason.OFFLINE, ask = false)
    }

    /** Nach "WLAN weg": nichts laden, bis das WLAN zurück ist (dann geht es von selbst weiter). */
    fun waitForWifi() {
        val i = interruption ?: return
        interruption = i.copy(ask = false)
        DownloadService.refresh(app)
    }

    /** Nach einem Verbindungsabbruch: sofort noch einmal versuchen. */
    fun retry() {
        val p = current ?: return
        autoRetries = 0
        resume(p)
    }

    /** Android hat die Zeit des Hintergrunddienstes beendet (Android 15: 6 Stunden): anhalten, der Nutzer setzt später fort. */
    fun pauseBySystem() {
        if (job?.isActive == true) {
            stop = Stop.SYSTEM
            downloader?.abort()
        } else current?.let { interrupt(it, InterruptReason.SYSTEM, ask = true) }
    }

    /** Bricht ab (laufend oder angehalten). Die angefangene Datei bleibt liegen, ein neuer Download macht dort weiter. */
    fun cancel() {
        if (job?.isActive == true) {
            stop = Stop.USER
            downloader?.abort()
        } else if (current != null) {
            end(tNow("Cancelled (the partial file is kept; a new download continues there).", "Abgebrochen (die angefangene Datei bleibt, ein neuer Download macht dort weiter)."))
        }
    }

    /** Liest die Modelldateien des Modellordners neu (im Hintergrund). */
    fun refreshLocal() {
        scope.launch { localModels = withContext(Dispatchers.IO) { listLocal() } }
    }

    /** Für die Oberfläche: Liste im Hintergrund lesen und danach mit [setLocal] übernehmen. */
    fun listLocalNow(): List<StoredFile> = listLocal()

    fun setLocal(list: List<StoredFile>) {
        localModels = list
    }

    private fun listLocal(): List<StoredFile> =
        runCatching { modelStore().list().filter { Folders.isModelName(it.name) }.sortedBy { it.name.lowercase() } }.getOrDefault(emptyList())

    /** Löscht eine Modelldatei aus dem Modellordner (im Hintergrund) und liest die Liste neu. */
    fun deleteLocal(name: String) {
        scope.launch {
            localModels = withContext(Dispatchers.IO) {
                modelStore().delete(name)
                listLocal()
            }
        }
    }

    /** Der gewählte Ordner (Anzeige): Pfad im Speicher oder der app-eigene Ordner. */
    fun folderLabel(): String = modelStore().label

    companion object {
        private const val MAX_AUTO_RETRIES = 3
        private const val RETRY_DELAY_MS = 2500L

        @Volatile
        private var instance: ModelManager? = null

        /** Die eine Instanz des Prozesses (der Dienst und jedes ViewModel greifen auf dieselbe zu). */
        fun shared(app: Application): ModelManager = instance ?: synchronized(this) { instance ?: ModelManager(app).also { instance = it } }

        /** Die Instanz, falls es sie schon gibt (der Dienst legt keine neue an). */
        fun existing(): ModelManager? = instance
    }
}
