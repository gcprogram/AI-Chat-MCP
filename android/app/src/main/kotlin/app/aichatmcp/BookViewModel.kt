package app.aichatmcp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aichatmcp.core.BACKEND_LOCAL
import app.aichatmcp.core.BookBrief
import app.aichatmcp.core.BookExport
import app.aichatmcp.core.BookFormatException
import app.aichatmcp.core.BookProject
import app.aichatmcp.core.BookStep
import app.aichatmcp.core.BookStore
import app.aichatmcp.core.BookWriter
import app.aichatmcp.core.LANGUAGE_DE
import app.aichatmcp.core.LlmBackend
import app.aichatmcp.core.LlmException
import app.aichatmcp.core.Persona
import app.aichatmcp.core.Providers
import app.aichatmcp.local.LocalLlm
import app.aichatmcp.local.effectiveConfig
import app.aichatmcp.ui.tNow
import app.aichatmcp.work.GenerationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Autorenmodus: Projekte anlegen, die Stufen (Handlung, Figuren, Kapitelplan, Kapitel) vom Modell schreiben lassen, von Hand ändern und als
 * Markdown oder DOCX speichern. Der Ablauf läuft im Hintergrund weiter (Vordergrunddienst), jedes Ergebnis wird sofort gespeichert; nach einem
 * Neustart geht es dort weiter, wo es aufgehört hat.
 */
class BookViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = openStorage(app)
    private val appContext = app
    private val store = BookStore(File(app.filesDir, "books"))
    private val localLlm = LocalLlm.shared(app)

    var projects by mutableStateOf<List<BookProject>>(emptyList())
        private set
    var project by mutableStateOf<BookProject?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    /** Der wachsende Text der laufenden Stufe (die letzten Zeichen, zur Anzeige). */
    var live by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set

    private var job: Job? = null
    private var writer: BookWriter? = null

    init {
        refresh()
    }

    fun refresh() {
        projects = store.list()
    }

    /** Aurelia, die Autorin: Name und Bild aus der mitgelieferten Companion-Datei (kein Chat-Companion, taucht in der Companion-Liste nicht auf). */
    fun aureliaImage(): File? = runCatching {
        val json = appContext.assets.open("aurelia.json").bufferedReader().use { it.readText() }
        storage.avatarFile(Persona.parse(json, "aurelia").getOrThrow())
    }.getOrNull()

    fun defaultLanguage(): String = if (storage.loadConfig().uiLanguage == LANGUAGE_DE) "Deutsch" else "English"

    fun create(brief: BookBrief) {
        val id = store.newId(brief.topic)
        val p = BookProject(id, brief = brief.copy(chapters = brief.chapters.coerceIn(1, 40), wordsPerChapter = brief.wordsPerChapter.coerceIn(200, 4000)), created = System.currentTimeMillis())
        store.save(p)
        project = p
        error = ""
        refresh()
    }

    fun open(id: String) {
        project = store.load(id)
        error = ""
    }

    fun close() {
        if (running) return
        project = null
        refresh()
    }

    fun delete(id: String) {
        if (running && project?.id == id) return
        store.delete(id)
        if (project?.id == id) project = null
        refresh()
    }

    /** Ändert das Projekt von Hand (Handlung, Figuren, Kapitelplan) und speichert es. */
    fun edit(change: (BookProject) -> BookProject) {
        val p = project ?: return
        if (running) return
        val updated = change(p)
        project = updated
        runCatching { store.save(updated) }
    }

    /** Eine Stufe neu erzeugen lassen: Sie und alles, was auf ihr aufbaut, wird geleert. */
    fun redo(step: BookStep) = edit { p ->
        when (step) {
            BookStep.Premise -> p.copy(premise = "", characters = "", chapters = emptyList(), facts = "")
            BookStep.Characters -> p.copy(characters = "", chapters = emptyList(), facts = "")
            BookStep.Plan -> p.copy(chapters = emptyList(), facts = "")
            is BookStep.ChapterPlan -> p
            is BookStep.Part -> p.copy(
                chapters = p.chapters.mapIndexed { i, c -> if (i == step.chapter) c.copy(text = "", parts = 0, summary = "") else c },
            )
            else -> p
        }
    }

    private fun backend(): Pair<LlmBackend, String> {
        val cfg = effectiveConfig(storage.loadConfig())
        return if (cfg.backend == BACKEND_LOCAL) localLlm.backend(cfg) to cfg.model else Providers.client(cfg.baseUrl, cfg.apiKey) to cfg.model
    }

    /** Startet den Ablauf. [untilPlan]: nur Handlung, Figuren und Kapitelplan (zum Prüfen und Ändern), sonst bis zum fertigen Buch. */
    fun start(untilPlan: Boolean) {
        if (running || project == null) return
        val cfg = storage.loadConfig()
        if (cfg.backend == BACKEND_LOCAL && cfg.localModel.isEmpty()) {
            error = tNow("Please choose a model in the settings first.", "Bitte zuerst in den Einstellungen ein Modell wählen.")
            return
        }
        error = ""
        running = true
        GenerationService.start(getApplication())
        job = viewModelScope.launch {
            val w = BookWriter({ backend().first }, { effectiveConfig(storage.loadConfig()) }, { backend().second })
            writer = w
            try {
                var retries = 0
                while (true) {
                    val p = project ?: break
                    val step = p.nextStep()
                    if (step == BookStep.Done || (untilPlan && step is BookStep.Part) || (untilPlan && step is BookStep.Summary)) break
                    status = describe(p, step)
                    live = ""
                    val updated = try {
                        w.run(p, step) { text -> live = text.takeLast(600) }
                    } catch (e: BookFormatException) {
                        if (++retries <= MAX_RETRIES) continue // die Antwort war unbrauchbar: gleich noch einmal
                        throw e
                    } ?: break // abgebrochen
                    retries = 0
                    project = updated
                    withContext(Dispatchers.IO) { store.save(updated) }
                }
                status = if (project?.finished == true) tNow("The book is finished.", "Das Buch ist fertig.") else if (untilPlan) tNow("Plan ready: check and edit it, then write the chapters.", "Planung fertig: prüfen und ändern, dann die Kapitel schreiben lassen.") else tNow("Stopped.", "Angehalten.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                error = e.message.orEmpty()
                status = tNow("Stopped by an error.", "Durch einen Fehler angehalten.")
            } catch (e: Throwable) {
                error = e.message ?: e::class.simpleName.orEmpty()
                status = tNow("Stopped by an error.", "Durch einen Fehler angehalten.")
            } finally {
                running = false
                live = ""
                GenerationService.stop(getApplication())
                refresh()
            }
        }
    }

    fun stop() {
        writer?.cancel()
    }

    private fun describe(p: BookProject, step: BookStep): String {
        val (done, total) = p.progress()
        val n = "${done + 1}/$total"
        return when (step) {
            BookStep.Premise -> tNow("Step $n: writing the plot…", "Schritt $n: schreibe die Handlung…")
            BookStep.Characters -> tNow("Step $n: creating the characters…", "Schritt $n: erstelle die Figuren…")
            BookStep.Plan -> tNow("Step $n: planning the chapters…", "Schritt $n: plane die Kapitel…")
            is BookStep.ChapterPlan -> tNow("Step $n: planning chapter ${step.chapter + 1}…", "Schritt $n: plane Kapitel ${step.chapter + 1}…")
            is BookStep.Part -> tNow("Step $n: chapter ${step.chapter + 1}, part ${step.part}/${step.parts}…", "Schritt $n: Kapitel ${step.chapter + 1}, Teil ${step.part}/${step.parts}…")
            is BookStep.Summary -> tNow("Step $n: summarizing chapter ${step.chapter + 1}…", "Schritt $n: fasse Kapitel ${step.chapter + 1} zusammen…")
            BookStep.Done -> ""
        }
    }

    // --- Export -------------------------------------------------------------------------------------------

    fun markdown(includePlan: Boolean): ByteArray = project?.let { BookExport.markdown(it, includePlan).toByteArray(Charsets.UTF_8) } ?: ByteArray(0)

    fun docx(includePlan: Boolean): ByteArray = project?.let { BookExport.docx(it, includePlan) } ?: ByteArray(0)

    fun fileName(extension: String): String = (project?.id ?: "buch") + "." + extension

    override fun onCleared() {
        writer?.cancel()
        GenerationService.stop(getApplication())
    }

    companion object {
        private const val MAX_RETRIES = 2
    }
}
