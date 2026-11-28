package app.aichatmcp

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aichatmcp.core.BACKEND_LOCAL
import app.aichatmcp.core.LANGUAGE_DE
import app.aichatmcp.core.StoredFile
import app.aichatmcp.core.translate.CONNECTION_RETRY_DELAYS_MS
import app.aichatmcp.core.translate.Chunk
import app.aichatmcp.core.translate.Context as PreviousContext
import app.aichatmcp.core.translate.DEFAULT_CHUNK_CHARS
import app.aichatmcp.core.translate.ImageCollector
import app.aichatmcp.core.translate.Languages
import app.aichatmcp.core.translate.LmStudioBackend
import app.aichatmcp.core.translate.TranslateBackend
import app.aichatmcp.core.translate.TranslateConfig
import app.aichatmcp.core.translate.TranslationException
import app.aichatmcp.core.translate.TranslationPipeline
import app.aichatmcp.core.translate.buildContext
import app.aichatmcp.core.translate.etaText
import app.aichatmcp.core.translate.formatDuration
import app.aichatmcp.core.translate.parseBlocks
import app.aichatmcp.core.translate.parseGlossary
import app.aichatmcp.core.translate.toPlainText
import app.aichatmcp.core.translate.splitIntoChunks
import app.aichatmcp.local.LocalLlm
import app.aichatmcp.translate.DocumentReader
import app.aichatmcp.translate.ExportFormat
import app.aichatmcp.translate.Exporters
import app.aichatmcp.translate.ImageStore
import app.aichatmcp.translate.SavedSection
import app.aichatmcp.translate.SavedSession
import app.aichatmcp.translate.TranslateSessionStore
import app.aichatmcp.ui.tNow
import app.aichatmcp.work.GenerationService
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Ein Abschnitt in der Oberfläche. [live] = wird gerade übersetzt. */
data class TranslateSection(
    val index: Int, val source: String, val translation: String, val warnings: List<String> = emptyList(), val live: Boolean = false,
)

/** Wohin fertige Abschnitte zum Mitlesen gehen (der Vorleser des Chats); gesetzt von der Oberfläche. */
interface LiveReadHook {
    fun start()
    fun text(name: String, language: String, text: String)
    fun end()
}

/** Das gewählte Dokument: entweder eine Datei ([uri]) oder ein schon eingelesener Text (aus der Dokumentkarte im Chat). */
data class TranslateInput(val name: String, val uri: String = "", val text: String = "")

/**
 * Übersetzt Dokumente Abschnitt für Abschnitt (der Algorithmus des AI-Translators: Absätze bleiben zusammen, der Vorgänger dient als
 * Kontext, Glossar, Überschriften und Bilder bleiben erhalten). Läuft im Vordergrunddienst weiter; nach jedem Abschnitt wird gesichert,
 * nach einem Abbruch, Fehler oder Neustart geht es mit "Fortsetzen" beim ersten offenen Abschnitt weiter.
 * Die Einstellungen liegen bewusst in eigenen SharedPreferences, nicht in der Config (ChatViewModel hält eine eigene Kopie davon).
 */
class TranslateViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = openStorage(app)
    private val prefs = app.getSharedPreferences("translate", Context.MODE_PRIVATE)
    private val localLlm = LocalLlm.shared(app)
    private val images = ImageStore(File(app.filesDir, "translate_images"))
    private val sessionStore = TranslateSessionStore(File(app.filesDir, "translate_session.json"))

    // --- Einstellungen ---------------------------------------------------------------------------

    var langSource by mutableStateOf(prefs.getString("source", "en").orEmpty().ifEmpty { "en" })
        private set
    // Vorgabe: Englisch nach Deutsch (die Oberflächensprache sagt nichts darüber, was man übersetzen will)
    var langTarget by mutableStateOf(prefs.getString("target", "de").orEmpty().ifEmpty { "de" })
        private set

    /** true = Modell auf dem Handy, false = Server (LM Studio usw. aus den Einstellungen). Vorgabe: wie im Chat. */
    var onDevice by mutableStateOf(prefs.getBoolean("on_device", storage.loadConfig().backend == BACKEND_LOCAL))
        private set
    var serverModel by mutableStateOf(prefs.getString("server_model", "").orEmpty())
        private set
    var localRef by mutableStateOf(prefs.getString("local_ref", "").orEmpty())
        private set
    var localName by mutableStateOf(prefs.getString("local_name", "").orEmpty())
        private set
    var useContext by mutableStateOf(prefs.getBoolean("use_context", true))
        private set
    /** Fertige Abschnitte gleich vorlesen, während die nächsten übersetzt werden. */
    var readLive by mutableStateOf(prefs.getBoolean("read_when_done", false))
        private set

    var liveHook: LiveReadHook? = null
    var glossary by mutableStateOf(prefs.getString("glossary", "").orEmpty())
        private set

    fun setLanguages(source: String, target: String) {
        langSource = source
        langTarget = target
        prefs.edit().putString("source", source).putString("target", target).apply()
    }

    fun swapLanguages() = setLanguages(langTarget, langSource)

    fun chooseOnDevice(value: Boolean) {
        onDevice = value
        prefs.edit().putBoolean("on_device", value).apply()
    }

    fun chooseServerModel(name: String) {
        serverModel = name
        prefs.edit().putString("server_model", name).apply()
    }

    fun setLocalModel(file: StoredFile) {
        localRef = file.ref
        localName = file.name
        prefs.edit().putString("local_ref", file.ref).putString("local_name", file.name).apply()
    }

    fun changeUseContext(value: Boolean) {
        useContext = value
        prefs.edit().putBoolean("use_context", value).apply()
    }

    /**
     * Mitlesen ein- oder ausschalten, auch mitten in einer Übersetzung. Aus: der Vorleser liest das schon Übergebene fertig und wartet nicht mehr;
     * die Übersetzung läuft weiter. Wieder an: er macht mit den Abschnitten weiter, die inzwischen fertig wurden.
     */
    fun changeReadLive(value: Boolean) {
        readLive = value
        prefs.edit().putBoolean("read_when_done", value).apply()
        if (value) {
            liveHook?.start()
            sections.filter { !it.live && it.index > liveSent }.sortedBy { it.index }.forEach { sendToReader(it) }
        } else {
            liveHook?.end()
        }
    }

    private fun sendToReader(section: TranslateSection) {
        liveSent = section.index
        liveHook?.text(documentName, targetLanguage(), toPlainText(parseBlocks(section.translation)))
    }

    /**
     * Geschätzte Wartezeit bis zum nächsten übersetzten Abschnitt ("ca. 3 Min."), nach dem bisherigen Tempo dieses Laufs abzüglich der Zeit seit dem
     * letzten fertigen Abschnitt; leer, solange es keine Grundlage gibt.
     */
    fun waitHint(): String {
        if (!running || etaDoneChars <= 0) return ""
        val next = pending.firstOrNull()?.text?.length ?: return ""
        val nowNs = System.nanoTime()
        val perChar = (nowNs - etaStartNanos) / 1_000_000.0 / etaDoneChars
        val sinceMs = (nowNs - lastSectionNanos) / 1_000_000.0
        val remainingMs = maxOf(30_000.0, perChar * next - sinceMs).toLong()
        val text = formatDuration(remainingMs)
        return if (text.startsWith("unter")) text else "ca. $text"
    }

    fun changeGlossary(text: String) {
        glossary = text
        prefs.edit().putString("glossary", text).apply()
    }

    /** Die Modelle des Servers aus den Einstellungen (zuletzt geladene Liste), TranslateGemma zuerst. */
    fun serverModels(): List<String> {
        val models = storage.loadConfig().models.ifEmpty { listOfNotNull(storage.loadConfig().model.takeIf { it.isNotBlank() }) }
        return models.sortedBy { if ("translategemma" in it.lowercase()) 0 else 1 }
    }

    /** Die Modelldateien auf dem Handy (Modellordner), TranslateGemma zuerst. */
    suspend fun localFiles(): List<StoredFile> = withContext(Dispatchers.IO) {
        runCatching { ModelManager.shared(getApplication()).listLocalNow() }.getOrDefault(emptyList())
            .sortedBy { if ("translategemma" in it.name.lowercase()) 0 else 1 }
    }

    // --- Zustand ----------------------------------------------------------------------------------

    var input by mutableStateOf<TranslateInput?>(null)
        private set
    var documentName by mutableStateOf("")
        private set
    var sections by mutableStateOf<List<TranslateSection>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var progress by mutableStateOf(0f)
        private set
    var error by mutableStateOf("")
        private set
    var pending by mutableStateOf<List<Chunk>>(emptyList())
        private set

    /** Einmalige Meldung für die Oberfläche (Toast); die Oberfläche setzt sie mit [consumeMessage] zurück. */
    var message by mutableStateOf("")
        private set

    private var job: Job? = null
    private var saveJob: Job? = null
    private var runConfig = TranslateConfig()
    private var liveSent = 0 // der letzte Abschnitt, der zum Mitlesen weitergegeben wurde (beim Wiedereinschalten wird dahinter weitergemacht)
    private var lastSectionNanos = 0L

    // Restzeit: bisheriges Tempo (Zeichen pro Zeit) auf den Rest hochgerechnet
    private var etaStartNanos = 0L
    private var etaTotalChars = 0L
    private var etaDoneChars = 0L

    @Volatile
    private var etaSuffix = ""

    init {
        sessionStore.load()?.takeIf { it.sections.isNotEmpty() || it.pending.isNotEmpty() }?.let { s ->
            documentName = s.documentName
            sections = s.sections.map { TranslateSection(it.index, it.source, it.translation, it.warnings) }
            pending = s.pending
            total = s.total.takeIf { it > 0 } ?: (s.sections.size + s.pending.size)
            progress = s.sections.size.toFloat() / total.coerceAtLeast(1)
            error = s.error
            runConfig = runConfig.copy(langSource = s.langSource, langTarget = s.langTarget)
            status = tNow("Last translation restored.", "Letzte Übersetzung wiederhergestellt.")
        }
    }

    fun consumeMessage() {
        message = ""
    }

    // --- Dokument wählen ---------------------------------------------------------------------------

    fun selectFile(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment.orEmpty()
        input = TranslateInput(name, uri = uri.toString())
    }

    /** Ein Dokument aus der Dokumentkarte im Chat: die Datei, wenn bekannt und lesbar, sonst der eingelesene Text. */
    fun selectDocument(name: String, uri: String, text: String) {
        val supported = DocumentReader.extensionOf(name) in DocumentReader.SUPPORTED_EXTENSIONS
        input = if (uri.isNotEmpty() && supported) TranslateInput(name, uri = uri, text = text) else TranslateInput(name, text = text)
    }

    fun clearInput() {
        input = null
    }

    // --- Übersetzung -------------------------------------------------------------------------------

    private fun currentConfig(): TranslateConfig {
        val model = if (onDevice) localName else serverModel
        return TranslateConfig(model = model, langSource = langSource, langTarget = langTarget, maxChunkChars = DEFAULT_CHUNK_CHARS, useContext = useContext).sanitized()
    }

    /** Fehlt etwas Nötiges, die Meldung dazu; sonst null. */
    private fun problem(): String? = when {
        onDevice && localRef.isEmpty() -> tNow("Please choose a model on this phone first (TranslateGemma 4B: Setup → Models).", "Bitte zuerst ein Modell auf dem Handy wählen (TranslateGemma 4B: Setup → Modelle).")
        !onDevice && serverModel.isEmpty() -> tNow("Please choose a server model first (load the list in Setup).", "Bitte zuerst ein Server-Modell wählen (Liste in den Einstellungen laden).")
        else -> null
    }

    private fun backend(): TranslateBackend =
        if (onDevice) localLlm.translateBackend(localRef, storage.loadConfig().localThreads)
        else storage.loadConfig().let { LmStudioBackend(it.baseUrl, it.apiKey) }

    /** Eine Pipeline; bei einer Verbindungsstörung wird der halb angezeigte Text des Abschnitts zurückgesetzt. */
    private fun newPipeline(config: TranslateConfig) = TranslationPipeline(backend(), config, onRetry = { attempt, reason ->
        status = tNow("Connection lost (retry $attempt/${CONNECTION_RETRY_DELAYS_MS.size}): $reason", "Verbindung gestört (Wiederholung $attempt/${CONNECTION_RETRY_DELAYS_MS.size}): $reason")
        sections = sections.map { if (it.live) it.copy(translation = "") else it }
    })

    /** Übersetzt das gewählte Dokument von vorn. */
    fun start() {
        val doc = input ?: return
        if (running) return
        problem()?.let { message = it; return }
        val config = currentConfig().also { runConfig = it }
        job = viewModelScope.launch { runDocument(doc, config) }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Setzt eine unterbrochene Übersetzung beim ersten noch offenen Abschnitt fort. */
    fun resume() {
        if (running || pending.isEmpty()) return
        problem()?.let { message = it; return }
        val config = runConfig.copy(model = currentConfig().model, useContext = useContext)
        runConfig = config
        val chunks = pending
        job = viewModelScope.launch {
            guarded(tNow("Resuming translation…", "Setze Übersetzung fort…")) {
                val previous = sections.lastOrNull { it.index < chunks.first().index }
                val context = if (config.useContext && previous != null) buildContext(previous.source, previous.translation) else null
                translateChunks(newPipeline(config), chunks, parseGlossary(glossary), context)
            }
        }
    }

    /** Führt einen Lauf aus und sorgt für Statusanzeige, Fehlertext, Sicherung und Aufräumen (Modell freigeben). */
    private suspend fun guarded(startStatus: String, block: suspend () -> Unit) {
        running = true
        status = startStatus
        error = ""
        if (readLive) liveHook?.start()
        GenerationService.start(getApplication())
        try {
            block()
            status = tNow("Translation finished.", "Übersetzung abgeschlossen.")
        } catch (e: CancellationException) {
            markLiveIncomplete(tNow("incomplete (cancelled)", "unvollständig (abgebrochen)"))
            status = tNow("Cancelled.", "Abgebrochen.")
            throw e
        } catch (e: Exception) {
            val section = pending.firstOrNull()?.index
            val detail = (if (section != null) tNow("Section $section: ", "Abschnitt $section: ") else "") + (e.message ?: e.toString())
            markLiveIncomplete(tNow("incomplete (error)", "unvollständig (Fehler)"))
            status = tNow("Error", "Fehler")
            error = detail
            message = detail
        } finally {
            running = false
            liveHook?.end() // der Vorleser liest das Vorhandene fertig und hört dann auf
            GenerationService.stop(getApplication())
            withContext(NonCancellable) {
                saveNow()
                if (onDevice) localLlm.unload() // der Arbeitsspeicher gehört wieder dem Chat
            }
        }
    }

    private suspend fun translateChunks(pipeline: TranslationPipeline, chunks: List<Chunk>, glossary: Map<String, String>, initialContext: PreviousContext?) {
        etaStartNanos = System.nanoTime()
        etaTotalChars = chunks.sumOf { it.text.length.toLong() }
        etaDoneChars = 0
        etaSuffix = ""
        lastSectionNanos = etaStartNanos
        pipeline.translate(chunks, glossary, initialContext) { chunk, delta -> appendDelta(chunk, delta) }.collect { result ->
            etaDoneChars += result.source.length
            val elapsedMs = (System.nanoTime() - etaStartNanos) / 1_000_000
            etaSuffix = etaText(elapsedMs, etaDoneChars, etaTotalChars)?.let { " · $it" }.orEmpty()
            val section = TranslateSection(result.index, result.source, result.translation, result.warnings)
            val position = sections.indexOfFirst { it.index == result.index }
            sections = if (position < 0) sections + section else sections.toMutableList().also { it[position] = section }
            pending = pending.filter { it.index != result.index }
            progress = sections.size.toFloat() / total.coerceAtLeast(1)
            status = tNow("Section ${result.index}/$total translated$etaSuffix", "Abschnitt ${result.index}/$total übersetzt$etaSuffix")
            lastSectionNanos = System.nanoTime()
            if (readLive) sendToReader(section)
            scheduleSave()
        }
    }

    private suspend fun runDocument(doc: TranslateInput, config: TranslateConfig) = guarded(tNow("Reading the file…", "Lese die Datei…")) {
        val (name, text, collected) = withContext(Dispatchers.IO) { readInput(doc) }
        if (text.isEmpty()) throw IllegalStateException(tNow("The file could not be read or is empty.", "Die Datei konnte nicht gelesen werden oder ist leer."))
        val chunks = splitIntoChunks(text, config.maxChunkChars)
        // Erst jetzt ist sicher, dass es etwas Neues gibt: die bisherige Übersetzung wird ersetzt
        liveSent = 0
        documentName = name
        total = chunks.size
        sections = emptyList()
        pending = chunks
        progress = 0f
        withContext(Dispatchers.IO) { images.replaceAll(collected.images) }
        val terms = parseGlossary(glossary)
        status = tNow("${chunks.size} sections, ${terms.size} glossary terms", "${chunks.size} Abschnitte, ${terms.size} Glossarbegriffe")
        translateChunks(newPipeline(config), chunks, terms, initialContext = null)
    }

    private fun readInput(doc: TranslateInput): Triple<String, String, ImageCollector> {
        val app = getApplication<Application>()
        PDFBoxResourceLoader.init(app)
        val collector = ImageCollector()
        val fromFile = doc.uri.isNotEmpty() && DocumentReader.extensionOf(doc.name) in DocumentReader.SUPPORTED_EXTENSIONS
        if (fromFile) {
            val bytes = runCatching { app.contentResolver.openInputStream(Uri.parse(doc.uri))?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) return Triple(doc.name, DocumentReader.read(doc.name, bytes, collector), collector)
            if (doc.text.isEmpty()) throw IllegalStateException(tNow("The file can no longer be opened.", "Die Datei lässt sich nicht mehr öffnen."))
        }
        // Eingelesener Text (z. B. HTML): als Markdown-artiger Text, Absätze durch Leerzeilen
        return Triple(doc.name, DocumentReader.read("text.txt", doc.text.toByteArray(Charsets.UTF_8), collector), collector)
    }

    /** Neues Textstück der laufenden Übersetzung ans Ende des Abschnitts hängen (der Abschnitt entsteht beim ersten Stück). */
    private fun appendDelta(chunk: Chunk, delta: String) {
        val position = sections.indexOfFirst { it.index == chunk.index }
        if (position < 0) {
            sections = sections + TranslateSection(chunk.index, chunk.text, delta, live = true)
            status = tNow("Section ${chunk.index}/$total is being translated…$etaSuffix", "Abschnitt ${chunk.index}/$total wird übersetzt…$etaSuffix")
        } else {
            sections = sections.toMutableList().also { it[position] = it[position].copy(translation = it[position].translation + delta) }
        }
    }

    /** Ein unterbrochener Abschnitt bleibt stehen, wird aber in den Hinweisen markiert; beim Fortsetzen wird er neu übersetzt. */
    private fun markLiveIncomplete(reason: String) {
        sections = sections.map { if (it.live) it.copy(live = false, warnings = it.warnings + reason) else it }
    }

    // --- Ergebnis ----------------------------------------------------------------------------------

    /** Die Übersetzung als ein Text (Absätze durch Leerzeilen, Überschriften als Markdown, Bilder als Platzhalter). */
    fun translationText(): String = sections.joinToString("\n\n") { it.translation }.trim()

    /** Der Text zum Vorlesen: ohne Markdown-Zeichen und ohne Bildplatzhalter. */
    fun readableText(): String = toPlainText(parseBlocks(translationText()))

    fun thumbnail(id: Int): ByteArray? = images.load(id)?.bytes

    fun suggestedFileName(format: ExportFormat): String {
        val base = documentName.substringBeforeLast('.').ifEmpty { tNow("Translation", "Übersetzung") }
        return "$base (${Languages.normalizeCode(runConfig.langTarget)}).${format.extension}"
    }

    fun export(format: ExportFormat, uri: Uri) {
        val content = translationText()
        if (content.isEmpty()) {
            message = tNow("There is no translation yet.", "Es gibt noch keine Übersetzung.")
            return
        }
        val title = documentName.substringBeforeLast('.').ifEmpty { tNow("Translation", "Übersetzung") }
        val language = runConfig.langTarget
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    PDFBoxResourceLoader.init(getApplication())
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException(tNow("The target cannot be written.", "Ziel kann nicht geschrieben werden."))
                    out.use { Exporters.export(format, content, title, language, it, images::load) }
                    tNow("Saved.", "Gespeichert.")
                } catch (e: Exception) {
                    tNow("Saving failed: ", "Speichern fehlgeschlagen: ") + (e.message ?: e.toString())
                }
            }
            message = result
        }
    }

    /** Sprachcode der Übersetzung (für die Stimme beim Vorlesen). */
    fun targetLanguage(): String = Languages.normalizeCode(runConfig.langTarget.ifEmpty { langTarget })

    fun discard() {
        if (running) return
        sections = emptyList()
        pending = emptyList()
        total = 0
        progress = 0f
        error = ""
        documentName = ""
        status = ""
        viewModelScope.launch { withContext(Dispatchers.IO) { sessionStore.clear(); images.clear() } }
    }

    // --- Sitzung sichern ---------------------------------------------------------------------------

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            saveNow()
        }
    }

    private suspend fun saveNow() = withContext(Dispatchers.IO) {
        if (sections.isEmpty() && pending.isEmpty()) {
            sessionStore.clear()
        } else {
            sessionStore.save(
                SavedSession(
                    documentName = documentName, langSource = runConfig.langSource, langTarget = runConfig.langTarget,
                    sections = sections.map { SavedSection(it.index, it.source, it.translation, it.warnings) },
                    pending = pending, total = total, error = error,
                ),
            )
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 600L
    }
}
