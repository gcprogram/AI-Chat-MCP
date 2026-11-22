package app.aichatmcp

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aichatmcp.core.BACKEND_LOCAL
import app.aichatmcp.core.BACKEND_SERVER
import app.aichatmcp.core.StepModel
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

/** Wie weit ein Lauf geht: nur die Handlung (zum Prüfen und Umschreiben), bis zur fertigen Planung (Figuren, Kapitelpläne, Beats) oder bis zum fertigen Buch. */
enum class BookStop { PLOT, PLAN, END }

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

    // Welches Modell welche Stufe schreibt: "" = wie im Chat eingestellt, "local" = auf dem Handy, "server" = externes Modell (URL/Schlüssel aus den Einstellungen).
    // Bewusst nicht in der Config: ChatViewModel hält eine eigene Kopie und würde sie beim Speichern der Einstellungen überschreiben.
    private val prefs = app.getSharedPreferences("book_models", Context.MODE_PRIVATE)

    /** Modell für Handlung, Figuren, Kapitelpläne, Beats und Zusammenfassungen (kurze Texte, die ein starkes Modell deutlich besser plant). */
    var planChoice by mutableStateOf(prefs.getString("plan", "").orEmpty())
        private set

    /** Modell für den Kapiteltext. */
    var writeChoice by mutableStateOf(prefs.getString("write", "").orEmpty())
        private set

    fun setModelChoices(plan: String, write: String) {
        planChoice = plan
        writeChoice = write
        prefs.edit().putString("plan", plan).putString("write", write).apply()
    }

    /** Anzeige des externen Modells aus den Einstellungen ("Modellname @ Host"), leer wenn keins eingetragen ist. */
    fun externalLabel(): String {
        val c = storage.loadConfig()
        val host = runCatching { java.net.URI(c.baseUrl).host }.getOrNull().orEmpty()
        return listOf(c.model, host).filter { it.isNotBlank() }.joinToString(" @ ")
    }

    /** Name des Modells auf dem Handy (leer, wenn keins gewählt ist). */
    fun localLabel(): String = storage.loadConfig().let { it.localModelName.ifEmpty { it.localModel.substringAfterLast('/') } }

    init {
        refresh()
    }

    fun refresh() {
        projects = store.list()
    }

    /** Aurelia, die Autorin: ihr Bild aus der mitgelieferten Datei (kein Chat-Companion, taucht in der Companion-Liste nicht auf). */
    fun aureliaImage(): File? = runCatching {
        storage.avatarFile(Persona.parse(appContext.assets.open(HERO_WRITE).bufferedReader().use { it.readText() }, "aurelia").getOrThrow())
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
            is BookStep.ChapterPlan -> p.copy(
                chapters = p.chapters.mapIndexed { i, c -> if (i == step.chapter) c.copy(plan = "", beats = "", text = "", parts = 0, summary = "") else c },
            )
            is BookStep.Beats -> p.copy(
                chapters = p.chapters.mapIndexed { i, c -> if (i == step.chapter) c.copy(beats = "", text = "", parts = 0, summary = "") else c },
            )
            is BookStep.Part -> p.copy(
                chapters = p.chapters.mapIndexed { i, c -> if (i == step.chapter) c.copy(text = "", parts = 0, summary = "") else c },
            )
            else -> p
        }
    }

    /** Das Modell für [step]: der Kapiteltext und alles andere (Planung) können verschiedene Modelle haben. */
    private fun resolve(step: BookStep): StepModel {
        val base = storage.loadConfig()
        val choice = if (step is BookStep.Part) writeChoice else planChoice
        val chosen = when (choice) {
            "local" -> base.copy(backend = BACKEND_LOCAL)
            "server" -> base.copy(backend = BACKEND_SERVER)
            else -> base
        }
        val cfg = effectiveConfig(chosen)
        val backend: LlmBackend = if (cfg.backend == BACKEND_LOCAL) localLlm.backend(cfg) else Providers.client(cfg.baseUrl, cfg.apiKey)
        return StepModel(backend, cfg, cfg.model)
    }

    /** Startet den Ablauf bis [stop]: nur die Handlung, die Planung (Figuren, Kapitelpläne, Beats) oder das ganze Buch. Nach jedem Halt kann der Nutzer prüfen, ändern und neu schreiben lassen. */
    fun start(stop: BookStop) {
        if (running || project == null) return
        val cfg = storage.loadConfig()
        val used = setOf(planChoice, writeChoice).map { choice -> if (choice.isEmpty()) cfg.backend else if (choice == "local") BACKEND_LOCAL else BACKEND_SERVER }
        if (BACKEND_LOCAL in used && cfg.localModel.isEmpty()) {
            error = tNow("Please choose a model on this phone in the settings first.", "Bitte zuerst in den Einstellungen ein Modell auf dem Handy wählen.")
            return
        }
        if (BACKEND_SERVER in used && cfg.baseUrl.isBlank()) {
            error = tNow("Please enter the external model (URL) in the settings first.", "Bitte zuerst in den Einstellungen das externe Modell (URL) eintragen.")
            return
        }
        error = ""
        running = true
        GenerationService.start(getApplication())
        job = viewModelScope.launch {
            val w = BookWriter({ resolve(BookStep.Premise).backend }, { resolve(BookStep.Premise).config }, { resolve(BookStep.Premise).model }, ::resolve)
            writer = w
            try {
                var retries = 0
                while (true) {
                    val p = project ?: break
                    val step = p.nextStep()
                    val halt = when (stop) {
                        BookStop.PLOT -> step != BookStep.Premise
                        BookStop.PLAN -> step is BookStep.Part || step is BookStep.Summary
                        BookStop.END -> false
                    }
                    if (step == BookStep.Done || halt) break
                    status = describe(p, step)
                    live = ""
                    val updated = try {
                        w.run(p, step, retries) { text -> live = text.takeLast(600) }
                    } catch (e: BookFormatException) {
                        if (++retries <= MAX_RETRIES) continue // die Antwort war unbrauchbar: gleich noch einmal
                        throw e
                    } ?: break // abgebrochen
                    retries = 0
                    project = updated
                    withContext(Dispatchers.IO) { store.save(updated) }
                }
                status = when {
                    project?.finished == true -> tNow("The book is finished.", "Das Buch ist fertig.")
                    stop == BookStop.PLOT && project?.premise?.isNotBlank() == true -> tNow("Plot ready: read it, change it or let me write it again. Only then create the characters and the plan.", "Handlung fertig: lies sie, ändere sie oder lass sie neu schreiben. Erst danach Figuren und Plan erzeugen.")
                    stop == BookStop.PLAN && project?.planReady == true -> tNow("Plan ready: check and edit it, then write the chapters.", "Planung fertig: prüfen und ändern, dann die Kapitel schreiben lassen.")
                    else -> tNow("Stopped.", "Angehalten.")
                }
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
            BookStep.Plan -> tNow("Step $n: preparing the chapters…", "Schritt $n: lege die Kapitel an…")
            is BookStep.ChapterPlan -> tNow("Step $n: planning chapter ${step.chapter + 1}…", "Schritt $n: plane Kapitel ${step.chapter + 1}…")
            is BookStep.Beats -> tNow("Step $n: splitting chapter ${step.chapter + 1} into scenes…", "Schritt $n: zerlege Kapitel ${step.chapter + 1} in Szenen…")
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
