package app.aichatmcp.voicestudio

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aichatmcp.core.studio.AudioMath
import app.aichatmcp.core.studio.DatasetReport
import app.aichatmcp.core.studio.SentenceSplitter
import app.aichatmcp.core.studio.StudioProject
import app.aichatmcp.core.studio.StudioStore
import app.aichatmcp.core.studio.TARGET_RATE
import app.aichatmcp.core.studio.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class Screen { PROJECTS, PROJECT, RECORD, RECORDINGS, CHECK }

enum class Phase { READY, RECORDING, REVIEW }

data class ProjectInfo(val name: String, val count: Int, val minutes: Double, val pending: Int)

data class RecordingItem(val name: String, val text: String, val seconds: Double)

/** Empfohlene Menge: darunter reicht es nur knapp für eine Feinabstimmung. */
const val GOAL_MINUTES = 60
const val MIN_MINUTES = 30

fun formatMinutes(minutes: Double): String = String.format(Locale.GERMAN, "%.1f", minutes)

fun recordingsLabel(count: Int): String = if (count == 1) "1 Aufnahme" else "$count Aufnahmen"

class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val store = StudioStore(File(app.filesDir, "studio").apply { mkdirs() })
    private var project: StudioProject? = null
    private val player = Player()
    private val recorder = Recorder(app) { level = it }

    var screen by mutableStateOf(Screen.PROJECTS)
    var projects by mutableStateOf<List<ProjectInfo>>(emptyList())
        private set
    var current by mutableStateOf<ProjectInfo?>(null)
        private set
    var message by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    // Aufnahme
    var phase by mutableStateOf(Phase.READY)
        private set
    var sentence by mutableStateOf<String?>(null)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var seconds by mutableFloatStateOf(0f)
        private set
    var warnings by mutableStateOf<List<String>>(emptyList())
        private set
    var reviewSeconds by mutableFloatStateOf(0f)
        private set
    var processing by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    private var taken: ShortArray = ShortArray(0)

    var recordings by mutableStateOf<List<RecordingItem>>(emptyList())
        private set
    var report by mutableStateOf<DatasetReport?>(null)
        private set

    init {
        refresh()
    }

    // --- Projekte ------------------------------------------------------------------------------------------------

    private fun infoOf(p: StudioProject) = ProjectInfo(p.name, p.recordings().size, p.minutes(), p.pending().size)

    fun refresh() {
        viewModelScope.launch {
            val (all, cur) = withContext(Dispatchers.IO) {
                store.list().map { infoOf(store.project(it)) } to project?.let(::infoOf)
            }
            projects = all
            current = cur
        }
    }

    fun openProject(name: String) {
        project = store.project(name)
        message = ""
        screen = Screen.PROJECT
        refresh()
    }

    fun createProject(name: String) {
        val p = store.create(name)
        openProject(p.name)
    }

    fun deleteProject(name: String) {
        if (project?.name == name) project = null
        store.delete(name)
        screen = Screen.PROJECTS
        refresh()
    }

    fun back() {
        when (screen) {
            Screen.PROJECTS -> {}
            Screen.PROJECT -> { project = null; current = null; screen = Screen.PROJECTS; refresh() }
            Screen.RECORD -> { discardRecording(); screen = Screen.PROJECT; refresh() }
            Screen.RECORDINGS, Screen.CHECK -> { player.stop(); playing = false; screen = Screen.PROJECT; refresh() }
        }
    }

    // --- Sätze ---------------------------------------------------------------------------------------------------

    fun addBuiltIn(asset: String, label: String) {
        val p = project ?: return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                val lines = getApplication<Application>().assets.open("sentences/$asset").bufferedReader(Charsets.UTF_8).readLines()
                    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                p.addSentences(lines)
            }
            message = if (added > 0) "$added neue $label hinzugefügt." else "Diese $label sind schon alle in der Liste oder aufgenommen."
            refresh()
        }
    }

    fun addText(text: String) {
        val p = project ?: return
        viewModelScope.launch {
            val (found, added) = withContext(Dispatchers.IO) {
                val sentences = SentenceSplitter.split(text)
                sentences.size to p.addSentences(sentences)
            }
            message = when {
                found == 0 -> "Im Text wurden keine brauchbaren Sätze gefunden (4 bis 28 Wörter, ohne Ziffern)."
                added == 0 -> "Alle $found Sätze waren schon vorhanden."
                else -> "$added neue Sätze hinzugefügt (aus $found erkannten)."
            }
            refresh()
        }
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            busy = true
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    require(bytes.size <= 30_000_000) { "Datei zu groß (über 30 MB)" }
                    val utf8 = bytes.toString(Charsets.UTF_8)
                    if ('�' in utf8) bytes.toString(charset("windows-1252")) else utf8
                }
            }
            busy = false
            text.onSuccess { addText(it) }.onFailure { message = "Datei nicht lesbar: ${it.message}" }
        }
    }

    // --- Aufnehmen -----------------------------------------------------------------------------------------------

    fun startSession() {
        message = ""
        phase = Phase.READY
        warnings = emptyList()
        sentence = project?.nextSentence()
        screen = Screen.RECORD
    }

    /** Startet die Aufnahme (die Mikrofon-Berechtigung muss schon erteilt sein). */
    fun startRecording() {
        if (phase != Phase.READY || sentence == null) return
        player.stop(); playing = false
        val error = recorder.start()
        if (error != null) { message = error; return }
        message = ""
        phase = Phase.RECORDING
        seconds = 0f
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            while (phase == Phase.RECORDING) {
                seconds = (System.currentTimeMillis() - started) / 1000f
                if (seconds >= Recorder.MAX_SECONDS) { stopRecording(); break }
                delay(100)
            }
        }
    }

    fun stopRecording() {
        if (phase != Phase.RECORDING) return
        phase = Phase.REVIEW
        processing = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val cap = recorder.stop() ?: return@withContext null
                val trimmed = AudioMath.trimSilence(AudioMath.resample(AudioMath.toFloat(cap.samples), cap.rate, TARGET_RATE), TARGET_RATE)
                val stats = AudioMath.stats(trimmed, TARGET_RATE)
                val notes = buildList {
                    if (stats.duration < 1.0) add("sehr kurz")
                    if (stats.peak < 0.1) add("zu leise: näher ans Mikrofon")
                    if (stats.clip > 0.0005) add("übersteuert: leiser sprechen oder weiter weg")
                    if (cap.limitReached) add("die Aufnahme wurde nach ${Recorder.MAX_SECONDS} s abgebrochen")
                }
                Triple(AudioMath.toShort(trimmed), stats.duration.toFloat(), notes)
            }
            processing = false
            if (result == null) { phase = Phase.READY; return@launch }
            taken = result.first
            reviewSeconds = result.second
            warnings = result.third
        }
    }

    fun preview() {
        if (taken.isEmpty()) return
        playing = true
        player.play(taken, TARGET_RATE) { playing = false }
    }

    fun retry() {
        player.stop(); playing = false
        phase = Phase.READY
        warnings = emptyList()
    }

    fun keep() {
        val p = project ?: return
        val text = sentence ?: return
        if (taken.isEmpty()) return
        player.stop(); playing = false
        val data = taken
        taken = ShortArray(0)
        phase = Phase.READY
        warnings = emptyList()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { p.save(text, data, TARGET_RATE) }
            sentence = withContext(Dispatchers.IO) { p.nextSentence() }
            if (sentence == null) message = "Alle offenen Sätze sind aufgenommen. Weitere Sätze unter „Sätze hinzufügen“."
            refresh()
        }
    }

    fun skip() {
        val p = project ?: return
        val text = sentence ?: return
        p.skip(text)
        sentence = p.nextSentence()
    }

    private fun discardRecording() {
        player.stop(); playing = false
        if (phase == Phase.RECORDING) recorder.stop()
        phase = Phase.READY
        processing = false
        taken = ShortArray(0)
        warnings = emptyList()
    }

    /** Die App verlässt den Vordergrund: laufende Aufnahme verwerfen, Wiedergabe stoppen. */
    fun onAppStopped() {
        if (screen == Screen.RECORD) discardRecording()
        player.stop(); playing = false
    }

    // --- Aufnahmen ansehen, prüfen, exportieren ------------------------------------------------------------------

    fun openRecordings() {
        val p = project ?: return
        screen = Screen.RECORDINGS
        viewModelScope.launch {
            recordings = withContext(Dispatchers.IO) { p.recordings().map { RecordingItem(it.name, it.text, p.durationSeconds(it.name)) } }
        }
    }

    fun play(item: RecordingItem) {
        val p = project ?: return
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) { runCatching { Wav.decode(p.wavFile(item.name).readBytes()) }.getOrNull() }
            if (data == null) { message = "${item.name} ist nicht lesbar."; return@launch }
            playing = true
            player.play(data.samples, data.rate) { playing = false }
        }
    }

    fun stopPlaying() {
        player.stop(); playing = false
    }

    fun deleteRecording(item: RecordingItem) {
        val p = project ?: return
        player.stop(); playing = false
        viewModelScope.launch {
            withContext(Dispatchers.IO) { p.delete(item.name) }
            recordings = recordings.filter { it.name != item.name }
            refresh()
        }
    }

    fun openCheck() {
        val p = project ?: return
        report = null
        screen = Screen.CHECK
        viewModelScope.launch { report = withContext(Dispatchers.IO) { p.check() } }
    }

    /** Aufnahmen aus einer ZIP übernehmen (metadata.csv + wavs/, z. B. vom PC-Werkzeug). */
    fun importZip(uri: Uri) {
        val p = project ?: return
        viewModelScope.launch {
            busy = true
            message = "Übernehme Aufnahmen …"
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("Datei nicht lesbar")
                    input.use { p.importZip(it) }
                }
            }
            busy = false
            message = outcome.fold(
                { if (it == 0) "Keine neuen Aufnahmen gefunden (alle Sätze schon vorhanden oder keine metadata.csv in der ZIP)." else "$it Aufnahmen übernommen." },
                { "Import fehlgeschlagen: ${it.message}" },
            )
            refresh()
        }
    }

    fun export(uri: Uri) {
        val p = project ?: return
        viewModelScope.launch {
            busy = true
            message = "Exportiere …"
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri) ?: error("Ziel nicht beschreibbar")
                    p.exportZip(out)
                }
            }
            busy = false
            message = outcome.fold(
                { "$it Aufnahmen exportiert. Die ZIP-Datei auf den PC kopieren, entpacken und mit tools/voice/check_dataset.py prüfen." },
                { "Export fehlgeschlagen: ${it.message}" },
            )
        }
    }

    override fun onCleared() {
        discardRecording()
    }
}
