package app.aichatmcp

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import java.io.File
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aichatmcp.core.AppJson
import app.aichatmcp.core.ChatClient
import app.aichatmcp.core.ChatEngine
import app.aichatmcp.core.ChatEvent
import app.aichatmcp.core.ChatFileException
import app.aichatmcp.core.ChatLog
import app.aichatmcp.core.BACKEND_LOCAL
import app.aichatmcp.core.BACKEND_SERVER
import app.aichatmcp.core.CancelToken
import app.aichatmcp.core.DocKind
import app.aichatmcp.core.DocText
import app.aichatmcp.core.KeywordHit
import app.aichatmcp.core.KeywordImport
import app.aichatmcp.core.KeywordRules
import app.aichatmcp.core.KeywordThrottle
import app.aichatmcp.core.LanguageDetect
import app.aichatmcp.core.RESULT_BOTH
import app.aichatmcp.core.RESULT_SHOW
import app.aichatmcp.core.ReadItem
import app.aichatmcp.core.ReadMark
import app.aichatmcp.core.ReadMarks
import app.aichatmcp.core.ReadPlan
import app.aichatmcp.core.Reader
import app.aichatmcp.core.ReaderEvents
import app.aichatmcp.core.ReaderState
import app.aichatmcp.core.ToolOutcome
import app.aichatmcp.core.normalizeName
import app.aichatmcp.core.splitSegments
import app.aichatmcp.core.DocumentException
import app.aichatmcp.core.Documents
import app.aichatmcp.core.ChatSession
import app.aichatmcp.core.Config
import app.aichatmcp.core.DefaultServerFactory
import app.aichatmcp.core.Message
import app.aichatmcp.core.NoticeKind
import app.aichatmcp.core.Persona
import app.aichatmcp.core.SpeakerJudge
import app.aichatmcp.core.PiperNames
import app.aichatmcp.core.PiperStore
import app.aichatmcp.core.PiperVoice
import app.aichatmcp.core.Speech
import app.aichatmcp.core.TOOL_ERROR
import app.aichatmcp.core.TOOL_RESULT
import app.aichatmcp.core.TOOL_SHOWN
import app.aichatmcp.core.ToolCall
import app.aichatmcp.core.ToolRegistry
import app.aichatmcp.core.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import app.aichatmcp.local.LocalLlm
import app.aichatmcp.local.VisionLlm
import app.aichatmcp.local.effectiveConfig
import app.aichatmcp.speech.DescribedPictures
import app.aichatmcp.speech.ReaderFocus
import app.aichatmcp.speech.SpeechInput
import app.aichatmcp.speech.SpeechReaderOutput
import app.aichatmcp.speech.SpeechPacks
import app.aichatmcp.speech.SpeechOutput
import kotlinx.serialization.json.JsonObject

/** Eine Sprache der Offline-Spracherkennung: state = installed | available | downloading | unsupported. */
data class PackRow(val tag: String, val name: String, val state: String, val progress: Int = 0)

private val PACK_LANGUAGES = listOf(
    "de-DE" to "Deutsch", "en-US" to "English (US)", "en-GB" to "English (UK)", "ja-JP" to "Japanisch (日本語)",
    "fr-FR" to "Französisch", "es-ES" to "Spanisch", "it-IT" to "Italienisch",
)

private val VOICE_SAMPLES = mapOf(
    "de-DE" to "Hallo, ich bin deine Begleiterin. So klingt meine Stimme.",
    "en-US" to "Hello, this is how my voice sounds.",
    "en-GB" to "Hello, this is how my voice sounds.",
    "ja-JP" to "こんにちは、これが私の声です。",
    "fr-FR" to "Bonjour, voici ma voix.",
    "es-ES" to "Hola, así suena mi voz.",
    "it-IT" to "Ciao, ecco come suona la mia voce.",
)

/** Ein hochgeladenes Dokument, zu dem die App fragt, was damit geschehen soll. [tokens] grob geschätzt, [budget] = Platz im Kontext. */
data class PendingDoc(
    val name: String, val kind: DocKind, val text: String, val tokens: Int, val budget: Int, val image: Boolean = false,
    /** Woher die Datei kam (für die Vorlese-Marke; leer bei Bildbeschreibungen) und das eingelesene Dokument mit Seiten und Bildern. */
    val uri: String = "", val doc: DocText? = null,
)

/** Ein gewähltes Bild (verkleinert, als JPEG), das noch nicht beschrieben ist. */
class PendingImage(val name: String, val jpeg: ByteArray)

private sealed interface Loaded
private class LoadedDoc(val doc: app.aichatmcp.core.DocText) : Loaded
private class LoadedImage(val name: String, val jpeg: ByteArray) : Loaded

internal const val IMAGE_PROMPT = "Describe this image in 5 to 7 sentences. Start with the main subject, then the setting and background, then colors, lighting and notable details, and end with the overall impression. Describe only what is clearly visible. Do not invent people, objects or text; if something is not in the image, leave it out. Quote text only if you can actually read it. Write plain sentences only: no lists, no bullet points, no markdown, no headings."

enum class UiKind { USER, ASSISTANT, RESULT, RESULT_ERROR, SHOWN, INFO, ERROR, TOOL }

data class UiMessage(val kind: UiKind, val text: String)

class PendingConfirm(val call: ToolCall, val spec: ToolSpec?, val persona: String, private val answer: CompletableDeferred<Boolean>) {
    fun answer(ok: Boolean) {
        answer.complete(ok)
    }
}

data class ToolsState(val available: List<String> = emptyList(), val errors: List<String> = emptyList(), val loading: Boolean = true)

private const val RESULT_PREVIEW_CHARS = 160

/** So viele unklare Reden fragt der KI-Rückfall höchstens je Dokument ab (jede Frage kostet Zeit am Server). */
private const val MAX_JUDGED_SPEECHES = 400
private const val LONG_MESSAGE_CHARS = 900

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = AppStorage(app.filesDir) { name ->
        runCatching { app.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
    }

    var config by mutableStateOf(Config())
        private set
    var personaIds by mutableStateOf<List<String>>(emptyList())
        private set
    var persona by mutableStateOf(Persona())
        private set
    val messages = mutableStateListOf<UiMessage>()
    var live by mutableStateOf<ChatEvent.Live?>(null)
        private set
    var streaming by mutableStateOf(false)
        private set
    var status by mutableStateOf("Bereit")
        private set
    var contextLabel by mutableStateOf("")
        private set
    var input by mutableStateOf("")
    var pendingConfirm by mutableStateOf<PendingConfirm?>(null)
        private set
    var tools by mutableStateOf(ToolsState())
        private set
    var listening by mutableStateOf(false)
        private set
    var packRows by mutableStateOf<List<PackRow>>(emptyList())
        private set
    var packNote by mutableStateOf("")
        private set
    var pendingDoc by mutableStateOf<PendingDoc?>(null)
        private set
    var docBusy by mutableStateOf(false)
        private set
    var pendingImage by mutableStateOf<PendingImage?>(null)
        private set
    var imageBusy by mutableStateOf(false)
        private set
    var visionBusy by mutableStateOf(false)
        private set
    var visionNote by mutableStateOf("")
        private set
    var voiceRows by mutableStateOf<List<PackRow>>(emptyList())
        private set
    var voiceNote by mutableStateOf("")
        private set
    var piperVoices by mutableStateOf<List<PiperVoice>>(emptyList())
        private set
    var piperNote by mutableStateOf("")
        private set
    var piperBusy by mutableStateOf(false)
        private set
    var mcpText by mutableStateOf("")

    /** Vorschau eines Schlüsselwort-Imports (CSV/Excel), wartet auf "Übernehmen". */
    class KeywordImportPreview(val fileName: String, val result: KeywordImport.Result)

    var keywordImport by mutableStateOf<KeywordImportPreview?>(null)
    var keywordImportError by mutableStateOf("")
        private set
    var secretNames by mutableStateOf<List<String>>(emptyList())
        private set
    var certNames by mutableStateOf<List<String>>(emptyList())
        private set

    /** Wird bei jedem Bildimport erhöht, damit angezeigte Avatare neu geladen werden. */
    var avatarVersion by mutableStateOf(0)
        private set
    private val localLlm = LocalLlm(app)
    private val vision = VisionLlm(app)
    val visionAvailable: Boolean get() = visionVersion >= 0 && vision.available
    private var visionVersion by mutableStateOf(0) // ändert sich beim Import/Entfernen, damit die Oberfläche neu liest
    private val speechIn = SpeechInput(app)
    private val speechOut = SpeechOutput(app)
    private val packs = SpeechPacks(app)
    private var registry = ToolRegistry()
    private val session: ChatSession
    private val engine: ChatEngine
    private var turn: Job? = null


    // --- Anzeige ------------------------------------------------------------------------------------------

    private fun toUi(m: Message): UiMessage = when {
        m.kind == TOOL_SHOWN -> UiMessage(UiKind.SHOWN, m.content)
        m.kind == TOOL_RESULT || m.kind == TOOL_ERROR -> {
            val preview = m.content.split(Regex("\\s+")).joinToString(" ").let {
                if (it.length > RESULT_PREVIEW_CHARS) it.take(RESULT_PREVIEW_CHARS).trimEnd() + "…" else it
            }
            val label = if (m.kind == TOOL_RESULT) "Ergebnis" else "Fehler"
            UiMessage(if (m.kind == TOOL_RESULT) UiKind.RESULT else UiKind.RESULT_ERROR, "« $label von ${m.tool.ifEmpty { "Werkzeug" }}: $preview")
        }
        m.role == "user" -> UiMessage(UiKind.USER, shortened(m.content))
        else -> UiMessage(UiKind.ASSISTANT, m.content)
    }

    /** Lange Nutzernachrichten (z. B. ein eingefügtes Dokument) erscheinen gekürzt; das Modell bekommt den vollen Text. */
    private fun shortened(text: String): String =
        if (text.length <= LONG_MESSAGE_CHARS) text
        else text.take(300).trimEnd() + "\n[… ${text.length - 500} Zeichen ausgeblendet …]\n" + text.takeLast(200).trimStart()

    /** Zeichnet Begrüßung und Verlauf neu (Companion-Wechsel, neuer Chat, geladener Chat). */
    private fun renderHistory() {
        dismissDoc()
        messages.clear()
        live = null
        if (persona.greeting.isNotEmpty()) messages += UiMessage(UiKind.ASSISTANT, persona.greeting)
        session.history.forEach { messages += toUi(it) }
        updateContext()
    }

    private fun updateContext() {
        var text = "Kontext ≈ ${session.estimatedTokens()}/${session.config.contextTokens} Tokens"
        if (session.dropped > 0) text += " · ${session.dropped} ältere ausgeblendet"
        contextLabel = text
    }

    // --- Chat ---------------------------------------------------------------------------------------------

    private suspend fun askUser(call: ToolCall, spec: ToolSpec?): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingConfirm = PendingConfirm(call, spec, persona.name, answer)
        try {
            return answer.await()
        } finally {
            pendingConfirm = null
        }
    }

    fun send() {
        val text = input.trim()
        if (streaming || text.isEmpty()) return
        // Schlüsselwörter in der Eingabe lösen das Werkzeug direkt aus; das Modell wird dann nicht gefragt (Entlastung, deterministisch)
        if (config.keywordsEnabled && !keywordRules().isEmpty && keywordRules().match(text).isNotEmpty()) {
            input = ""
            messages += UiMessage(UiKind.USER, shortened(text))
            triggerKeywords(text, "Eingabe", throttled = false)
            return
        }
        readerPause()
        if (config.backend == BACKEND_LOCAL && config.localModel.isEmpty()) {
            status = "Bitte zuerst in den Einstellungen eine Modelldatei wählen."
            return
        }
        if (config.backend != BACKEND_LOCAL && config.model.isEmpty()) {
            status = "Bitte zuerst in den Einstellungen ein Modell wählen."
            return
        }
        speechOut.stop()
        input = ""
        session.config = effectiveConfig(config)
        streaming = true
        turn = viewModelScope.launch {
            try {
                engine.send(text).collect(::handle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) { // auch Error (z. B. ExceptionInInitializerError): die App darf nicht abstürzen
                messages += UiMessage(UiKind.ERROR, "[Fehler] ${e.message}")
                status = "Fehler"
            } finally {
                streaming = false
                live = null
                saveChat()
                updateContext()
            }
        }
    }

    fun stop() {
        android.util.Log.w("AiChat", "stop() aufgerufen", Throwable())
        speechOut.stop()
        engine.cancel()
    }

    // --- Sprache -----------------------------------------------------------------------------------------

    private fun languageTag() = Speech.languageTag(persona.language)

    /** Mikrofon-Knopf: Aufnahme starten bzw. beenden. Die Berechtigung holt die Oberfläche vorher ein. */
    fun toggleListening() {
        if (listening) {
            speechIn.finish()
            return
        }
        if (streaming) return
        if (!speechIn.isAvailable()) {
            status = "Keine Spracherkennung auf diesem Gerät."
            return
        }
        readerPause()
        speechOut.stop()
        listening = true
        speechMode = "offline"
        status = "Höre zu… (offline)"
        speechIn.start(
            languageTag(),
            config.speechOnline,
            onPartial = { input = it },
            onResult = { text ->
                listening = false
                input = text
                status = "Erkannt ($speechMode)"
                if (config.voiceAutoSend) send()
            },
            onError = { message ->
                listening = false
                status = "$message ($speechMode)"
            },
            onMode = { offline ->
                speechMode = if (offline) "offline" else "online, Google"
                status = "Höre zu… ($speechMode)"
            },
        )
    }

    // --- Offline-Sprachpakete der Spracherkennung -----------------------------------------------------------

    val packsSupported: Boolean get() = packs.isSupported

    fun refreshPacks() {
        if (!packs.isSupported) return
        packNote = "Prüfe installierte Sprachpakete…"
        packs.check { result ->
            result.onSuccess { support ->
                val downloading = packRows.filter { it.state == "downloading" }.associateBy { it.tag }
                packRows = PACK_LANGUAGES.map { (tag, name) ->
                    downloading[tag] ?: PackRow(
                        tag, name,
                        when {
                            tag in support.installed -> "installed"
                            tag in support.downloadable || tag in support.pending -> "available"
                            else -> "unsupported"
                        },
                    )
                }
                packNote = ""
            }.onFailure { packNote = "Sprachpakete lassen sich nicht abfragen (${it.message})." }
        }
    }

    // --- Dokumente hochladen: Rückfrage "Was soll ich damit machen?" --------------------------------------

    /** Liest die gewählte Datei (TXT, MD, DOCX, PDF) und stellt die Rückfrage mit den drei Möglichkeiten. */
    fun loadDocument(uri: Uri) {
        if (streaming || docBusy) return
        docBusy = true
        status = "Lese Datei…"
        viewModelScope.launch {
            try {
                // dauerhafter Zugriff, damit sich das Vorlesen nach einem Neustart fortsetzen lässt (Vorlese-Marke)
                runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val loaded = withContext(Dispatchers.IO) { readFile(uri) }
                endReading()
                pendingDoc = null
                pendingImage = null
                when (loaded) {
                    is LoadedDoc -> showDocument(loaded.doc, uri.toString())
                    is LoadedImage -> {
                        pendingImage = PendingImage(loaded.name, loaded.jpeg)
                        status = "Bild geladen: ${loaded.name}"
                    }
                }
            } catch (e: DocumentException) {
                status = e.message.orEmpty()
                messages += UiMessage(UiKind.ERROR, "[Datei] ${e.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = "Datei nicht lesbar"
                messages += UiMessage(UiKind.ERROR, "[Datei] ${e.message ?: e::class.simpleName}")
            } finally {
                docBusy = false
            }
        }
    }

    /** Liest eine Datei. [nameHint] ist der bekannte Dateiname (bei gelöschten Dateien liefert die Abfrage keinen). */
    private fun readFile(uri: Uri, nameHint: String? = null): Loaded {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val name = nameHint ?: resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Datei"
        val kind = Documents.kindOf(name, if (nameHint == null) resolver.getType(uri) else null)
            ?: throw DocumentException("Dieses Dateiformat wird nicht unterstützt (möglich: PDF, TXT, MD, DOCX, EPUB, HTML, Bilder).")
        if (kind == DocKind.IMAGE) return LoadedImage(name, ImagePrep.jpeg(app, uri))
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() <= Documents.MAX_BYTES) { // eine Stelle über dem Limit genügt, um "zu groß" zu melden
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw DocumentException("Die Datei lässt sich nicht öffnen.")
        return LoadedDoc(Documents.read(name, kind, bytes) { PdfText.extract(app, it) })
    }

    // --- Bilder: ein kleines Bildmodell beschreibt sie als Text ---------------------------------------------

    /** Das Bildmodell beschreibt das gewählte Bild; die Beschreibung wird zum Text, mit dem dieselben Möglichkeiten gelten. */
    fun describeImage() {
        val image = pendingImage ?: return
        if (imageBusy || streaming) return
        if (!vision.available) {
            status = "Für Bilder fehlt das Bildmodell (Einstellungen → Bildmodell)."
            return
        }
        imageBusy = true
        status = "Beschreibe das Bild… (das Bildmodell wird geladen)"
        viewModelScope.launch {
            try {
                val text = Documents.completeSentences(withContext(Dispatchers.IO) { vision.describe(image.jpeg, IMAGE_PROMPT, config.localThreads) })
                if (text.isBlank()) {
                    status = "Keine Beschreibung erhalten."
                } else {
                    pendingImage = null
                    val tokens = (text.length / session.context.charsPerToken).toInt()
                    pendingDoc = PendingDoc("Bild ${image.name}", DocKind.IMAGE, text, tokens, session.documentBudgetTokens(), image = true)
                    status = "Bild beschrieben."
                    triggerKeywords(text, "Bildbeschreibung")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = "Bild konnte nicht beschrieben werden"
                messages += UiMessage(UiKind.ERROR, "[Bild] ${e.message ?: e::class.simpleName}")
            } finally {
                imageBusy = false
            }
        }
    }

    fun cancelImage() = vision.cancel()

    fun dismissImage() {
        vision.cancel()
        pendingImage = null
    }

    /** Kopiert das Sprachmodell des Bildmodells (GGUF) in den privaten Speicher. */
    fun importVisionModel(uri: Uri, name: String) = importVision(uri, vision.modelFile, name, true)

    /** Kopiert die Bild-Hilfsdatei (mmproj-GGUF) in den privaten Speicher. */
    fun importVisionProj(uri: Uri, name: String) = importVision(uri, vision.projFile, name, false)

    private fun importVision(uri: Uri, target: java.io.File, name: String, model: Boolean) {
        if (visionBusy) return
        visionBusy = true
        visionNote = "Kopiere $name…"
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { vision.importFile(uri, target) }
                saveConfig(if (model) config.copy(visionModelName = name) else config.copy(visionProjName = name))
                visionNote = "$name übernommen (${bytes / 1_000_000} MB)."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                visionNote = "Import fehlgeschlagen: ${e.message ?: e::class.simpleName}"
            } finally {
                visionBusy = false
                visionVersion++
            }
        }
    }

    fun removeVision() {
        vision.remove()
        saveConfig(config.copy(visionModelName = "", visionProjName = ""))
        visionNote = "Bildmodell entfernt."
        visionVersion++
    }

    /** Alternative 1: Text ins Eingabefeld übernehmen (auf das Kontextfenster gekürzt); die Frage schreibt der Nutzer dahinter. */
    fun docAsInput() {
        val doc = pendingDoc ?: return
        val fitted = Documents.fit(doc.text, session.documentBudgetTokens(), session.context.charsPerToken)
        input = (if (doc.image) Documents.imageInputText(doc.name.removePrefix("Bild "), fitted.text) else Documents.inputText(doc.name, fitted)) + "\n\n"
        pendingDoc = null
        status = if (fitted.truncated) "Eingelesen, aber auf den Anfang gekürzt (Kontext reicht nicht für alles). Frage dahinter schreiben."
        else "Eingelesen. Frage dahinter schreiben und senden."
    }

    /** Alternative 3: Zusammenfassung durch das Modell (bei langen Texten nur der Anfang, der in den Kontext passt). */
    fun docSummarize() {
        val doc = pendingDoc ?: return
        if (streaming) return
        val fitted = Documents.fit(doc.text, session.documentBudgetTokens(), session.context.charsPerToken)
        input = Documents.summaryPrompt(doc.name, fitted, persona.language.ifBlank { "Deutsch" })
        pendingDoc = null
        send()
    }

    // --- Vorleser: Tasten, Marken, Tonfokus --------------------------------------------------------------------------

    /** Eine Stimme für die erkannte Sprache des Textes fehlt: Der Nutzer entscheidet, ob er sie lädt oder trotzdem vorlesen lässt. */
    class VoicePrompt(val tag: String, val name: String, val proceed: () -> Unit)

    private var reader: Reader? = null
    private var pictures: DescribedPictures? = null
    private var readerDoc: PendingDoc? = null
    private val marks = storage.loadMarks()

    var readerState by mutableStateOf(ReaderState.STOPPED)
        private set
    var readerIndex by mutableStateOf(0)
        private set
    var readerTotal by mutableStateOf(0)
        private set
    var readerPage by mutableStateOf(0)
        private set
    var readerPages by mutableStateOf(0)
        private set
    var readerNote by mutableStateOf("")
        private set
    var voicePrompt by mutableStateOf<VoicePrompt?>(null)
        private set
    var markList by mutableStateOf(marks.all())
        private set

    /** Läuft gerade eine Vorlese-Sitzung (spielend oder pausiert)? */
    val docReading: Boolean get() = readerState != ReaderState.STOPPED || reader != null

    private val focus: ReaderFocus = ReaderFocus(getApplication(), object : ReaderFocus.Listener {
        override fun lostTemporarily() {
            if (reader?.state == ReaderState.PLAYING) status = "Vorlesen pausiert (Anruf oder anderer Ton), es geht danach weiter."
            reader?.focusLostTemporarily()
        }

        override fun lostPermanently() {
            if (reader?.state == ReaderState.PLAYING) status = "Vorlesen pausiert (eine andere App spielt Ton)."
            reader?.focusLostPermanently()
            abandonFocus()
        }

        override fun gained() {
            reader?.focusGained()
        }
    })

    private fun abandonFocus() = focus.abandon()

    private fun languageName(tag: String): String = PACK_LANGUAGES.firstOrNull { it.first == tag }?.second ?: tag

    /**
     * Alternative 2: Vorlesen, Abschnitt für Abschnitt (ohne Modell, daher ohne Kontextgrenze). Die Sprache richtet sich nach dem
     * Text (Buchstabenhäufigkeit), nicht nach dem Companion; fehlt dafür eine Stimme, fragt ein Dialog. Mit [fromMark] geht es dort
     * weiter, wo es zuletzt stehen geblieben ist.
     */
    fun docRead(fromMark: Boolean = false) {
        val doc = pendingDoc ?: return
        val source = doc.doc ?: DocText(doc.name, doc.kind, doc.text)
        val tag = LanguageDetect.detect(source.text)?.tag ?: languageTag() ?: Locale.getDefault().toLanguageTag()
        // Wörtliche Rede mit eigenen Stimmen: nur mit Piper-Erzählerstimme und mindestens einer Stimme für männliche oder weibliche Rede
        val dialogue = (persona.voiceMale.isNotEmpty() || persona.voiceFemale.isNotEmpty()) && speechOut.piperFor(persona.voice, tag) != null
        val plan = ReadPlan.of(source, dialogue = dialogue)
        if (plan.size == 0) {
            status = "Es gibt nichts zum Vorlesen."
            return
        }
        val mark = if (fromMark) marks.get(doc.uri) else null
        val start = mark?.let { ReadMarks.restoreIndex(it, plan.size) } ?: 0
        speechOut.installedVoices(listOf(tag)) { installed ->
            if (installed[tag] == true || speechOut.piperFor(persona.voice, tag) != null) beginReading(doc, plan, tag, start)
            else voicePrompt = VoicePrompt(tag, languageName(tag)) { beginReading(doc, plan, tag, start) }
        }
    }

    fun confirmVoicePrompt() {
        val prompt = voicePrompt ?: return
        voicePrompt = null
        prompt.proceed()
    }

    fun dismissVoicePrompt() {
        voicePrompt = null
    }

    private fun beginReading(doc: PendingDoc, plan: ReadPlan, tag: String, start: Int) {
        endReading()
        readerDoc = doc
        val describer = DescribedPictures(
            viewModelScope, getApplication(), vision, doc.doc?.readable, { config.localThreads }, IMAGE_PROMPT,
        ) { description -> triggerKeywords(description, "Bildbeschreibung") }
        pictures = describer
        val events = object : ReaderEvents {
            override fun onState(state: ReaderState) {
                readerState = state
            }

            override fun onPosition(index: Int, total: Int) {
                readerIndex = index
                readerTotal = total
                reader?.let { readerPage = it.page; readerPages = it.pageCount }
                rememberPosition(doc, index, total)
            }

            override fun onSpoken(text: String, fromImage: Boolean) = triggerKeywords(text, if (fromImage) "Bildbeschreibung" else "Vorlesen")

            override fun onDescribing(active: Boolean) {
                readerNote = if (active) "Beschreibe das Bild…" else ""
            }

            override fun onProblem(message: String) {
                status = message
            }

            override fun onFinished() {
                status = "Vorlesen beendet."
                endReading()
                pendingDoc = null
            }
        }
        val r = Reader(plan, SpeechReaderOutput(speechOut) { status = it }, describer, events, tag, "en-US", start)
        reader = r
        startJudge(plan, start)
        speechOut.setItemSink(object : SpeechOutput.ItemSink {
            override fun done(id: String) = r.utteranceDone(id)
            override fun failed(id: String) = r.utteranceFailed(id)
        })
        startPlaying(r)
    }

    private fun startPlaying(r: Reader) {
        when (focus.request()) {
            ReaderFocus.Result.GRANTED -> r.play()
            ReaderFocus.Result.DELAYED -> {
                status = "Der Ton ist gerade belegt (Anruf?); das Vorlesen startet, sobald er frei ist."
                r.waitForFocus()
            }
            ReaderFocus.Result.FAILED -> status = "Der Ton ist gerade belegt (Anruf?). Bitte später noch einmal Play drücken."
        }
    }

    private fun rememberPosition(doc: PendingDoc, index: Int, total: Int) {
        if (doc.uri.isEmpty()) return
        marks.update(doc.uri, doc.name, index, total, System.currentTimeMillis())
        runCatching { storage.saveMarks(marks) }
        markList = marks.all()
    }

    private var judgeJob: Job? = null

    /**
     * KI-Rückfall: ordnet Reden, die die Regeln offen ließen, im Hintergrund über den Server zu (zuerst die ab der aktuellen Lesestelle).
     * Jede Antwort setzt die Rolle der Rede nachträglich; was schon gesprochen wurde, bleibt, wie es war.
     */
    private fun startJudge(plan: ReadPlan, startItem: Int) {
        judgeJob?.cancel()
        val dialogue = plan.dialogue ?: return
        if (!config.dialogueJudge || config.backend != BACKEND_SERVER) return
        val pending = dialogue.unresolvedFrom(plan.items.getOrNull(startItem) as? ReadItem.Speech).take(MAX_JUDGED_SPEECHES)
        if (pending.isEmpty()) return
        val judge = SpeakerJudge(ChatClient(config.baseUrl, config.apiKey), config.model)
        judgeJob = viewModelScope.launch {
            var failures = 0
            var done = 0
            for (i in pending) {
                val (before, quote, after) = dialogue.context(i)
                val gender = try {
                    withTimeout(60_000) { judge.ask(before, quote, after) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (++failures >= 3) {
                        status = "Zuordnung der Reden über den Server nicht möglich: ${e.message ?: "keine Antwort"}"
                        return@launch
                    }
                    null
                }
                if (gender != null) dialogue.assign(i, gender)
                done++
                if (done % 10 == 0) status = "Reden zugeordnet: $done von ${pending.size}"
            }
            status = "Reden zugeordnet: ${pending.size} von ${pending.size}"
        }
    }

    private fun endReading() {
        judgeJob?.cancel()
        reader?.takeIf { it.state != ReaderState.STOPPED }?.stop() // merkt die Stelle
        pictures?.cancel()
        reader = null
        pictures = null
        readerDoc = null
        focus.abandon()
        speechOut.setItemSink(null)
        readerState = ReaderState.STOPPED
        readerNote = ""
    }

    fun readerPlay() {
        val r = reader ?: return
        if (r.state != ReaderState.PLAYING) startPlaying(r)
    }

    fun readerPause() {
        val r = reader ?: return
        if (r.state == ReaderState.PLAYING) {
            r.pause()
            focus.abandon() // andere Apps dürfen wieder Ton abspielen
        }
    }

    /** Stop (Rechteck): beendet das Vorlesen, die Stelle bleibt als Marke; die Rückfrage erscheint wieder. */
    fun readerStop() {
        endReading()
    }

    fun readerPrevious() = reader?.previous()

    fun readerNext() = reader?.next()

    fun readerJumpBack() = reader?.jumpBack()

    fun readerJumpForward() = reader?.jumpForward()

    /** Die Marke zu einer Datei, die gerade zur Auswahl steht (für "Weiterlesen"). */
    fun markFor(doc: PendingDoc): ReadMark? = if (doc.uri.isEmpty()) null else markList.firstOrNull { it.uri == doc.uri }

    /** Öffnet einen Text anhand seiner Vorlese-Marke und liest ab der gemerkten Stelle weiter. Ist die Datei weg, wird die Marke gelöscht. */
    fun resumeMark(mark: ReadMark) {
        if (streaming || docBusy) return
        docBusy = true
        status = "Öffne ${mark.name}…"
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { readFile(Uri.parse(mark.uri), mark.name) }
                val doc = (loaded as? LoadedDoc)?.doc ?: throw DocumentException("Das ist kein Text.")
                endReading()
                pendingImage = null
                showDocument(doc, mark.uri)
                docRead(fromMark = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                marks.remove(mark.uri)
                runCatching { storage.saveMarks(marks) }
                markList = marks.all()
                runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(mark.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                status = "Die Datei „${mark.name}“ ist nicht mehr lesbar. Die Vorlese-Marke wurde gelöscht."
                val reason = (e.message ?: e::class.simpleName).orEmpty().substringBefore(" from pid").take(110) // ohne die langen technischen Angaben
                messages += UiMessage(UiKind.ERROR, "[Vorlesen] Die Datei „${mark.name}“ ist nicht mehr da oder nicht lesbar ($reason). Die Vorlese-Marke wurde gelöscht.")
            } finally {
                docBusy = false
            }
        }
    }

    private fun showDocument(doc: DocText, uri: String) {
        val tokens = (doc.text.length / session.context.charsPerToken).toInt()
        pendingDoc = PendingDoc(doc.name, doc.kind, doc.text, tokens, session.documentBudgetTokens(), uri = uri, doc = doc)
        status = "Datei gelesen: ${doc.name}"
    }

    fun dismissDoc() {
        endReading()
        vision.cancel()
        pendingImage = null
        pendingDoc = null
    }

    // --- Schlüsselwörter: Werkzeuge ohne das Modell auslösen -------------------------------------------------------

    private val keywordThrottle = KeywordThrottle()

    private fun keywordRules() = KeywordRules(session.registry.tools.values, session.toolNames)

    /**
     * Prüft einen Text auf die Schlüsselwörter der Werkzeuge (nur wenn in den Einstellungen eingeschaltet) und führt Treffer aus.
     * Dasselbe Werkzeug löst innerhalb von 30 Sekunden nur einmal aus ([throttled]), damit ein Stichwort in aufeinanderfolgenden
     * Vorleseabschnitten nicht mehrfach schaltet; [skip] sind Werkzeuge, die schon anderweitig liefen.
     */
    private fun triggerKeywords(text: String, source: String, skip: Set<String> = emptySet(), throttled: Boolean = true) {
        if (!config.keywordsEnabled || text.isBlank()) return
        val now = System.currentTimeMillis()
        val hits = keywordRules().match(text).filter { it.alias !in skip && (!throttled || keywordThrottle.allow(it.alias, now)) }
        if (hits.isEmpty()) return
        viewModelScope.launch { hits.forEach { runKeyword(it, source) } }
    }

    private suspend fun runKeyword(hit: KeywordHit, source: String) {
        val call = ToolCall(hit.alias, hit.args, "", 0, 0)
        val spec = session.registry.tools[hit.alias]
        var ask = spec?.confirm ?: config.confirmTools
        if (hit.alias in persona.approvedTools.map { normalizeName(it) }) ask = false // beim Companion vorab genehmigt
        if (ask) {
            if (pendingConfirm != null) {
                status = "Schlüsselwort „${hit.keyword}“ ($source): übersprungen (eine Rückfrage ist noch offen)."
                return
            }
            if (!askUser(call, spec)) {
                status = "Schlüsselwort „${hit.keyword}“ ($source): abgelehnt."
                return
            }
        }
        status = "Schlüsselwort „${hit.keyword}“ ($source): führe ${hit.alias} aus…"
        val outcome = withContext(Dispatchers.IO) {
            try {
                session.registry.run(call, session.toolNames, config.toolResultChars, config.toolTimeout.toDouble())
            } catch (e: Exception) {
                ToolOutcome(hit.alias, false, "Interner Fehler: ${e.message}")
            }
        }
        if (!outcome.ok) {
            status = "Schlüsselwort „${hit.keyword}“ ($source): ${hit.alias} fehlgeschlagen"
            messages += UiMessage(UiKind.ERROR, "[Schlüsselwort ${hit.alias}] ${outcome.text}")
            return
        }
        status = "Schlüsselwort „${hit.keyword}“ ($source): ${outcome.status}"
        if (outcome.mode == RESULT_SHOW || outcome.mode == RESULT_BOTH) messages += UiMessage(UiKind.SHOWN, outcome.text)
    }

    // --- Stimmen der Sprachausgabe ------------------------------------------------------------------------

    fun refreshVoices() {
        speechOut.installedVoices(PACK_LANGUAGES.map { it.first }) { installed ->
            voiceRows = PACK_LANGUAGES.map { (tag, name) -> PackRow(tag, name, if (installed[tag] == true) "installed" else "available") }
        }
    }

    /** Spricht einen Beispielsatz, damit sich Stimme und Lautstärke prüfen lassen. */
    fun testVoice(tag: String) {
        voiceNote = ""
        speechOut.speak(VOICE_SAMPLES[tag] ?: "Test", tag, "") { voiceNote = it } // Standardstimme der Sprache
    }

    /** Wählbare Stimmen für die Antwortsprache eines Companions (Name wie im Editor, z. B. "Deutsch"). */
    fun loadVoiceOptions(languageName: String, onResult: (List<SpeechOutput.VoiceOption>) -> Unit) {
        val tag = Speech.languageTag(languageName)
        val piper = storage.piper.list()
            .filter { it.language == null || (tag != null && Speech.voiceFitsLanguage(it.language!!, tag)) }
            .map { SpeechOutput.VoiceOption(it.id, "Piper: ${it.title} (offline)") }
        speechOut.voicesFor(tag) { onResult(it + piper) }
    }

    // --- Piper-Stimmen (eigene Modelle) ----------------------------------------------------------------------

    /**
     * Übernimmt einen Stimmenordner aus dem Auswahldialog (Modell `*.onnx`, `tokens.txt`, optional `*.onnx.json`, dazu einmalig
     * `espeak-ng-data`) in den App-Speicher. Der Ordnername wird zum Namen der Stimme.
     */
    fun importPiperFolder(tree: Uri) {
        if (piperBusy) return
        piperBusy = true
        piperNote = "Kopiere die Stimme…"
        viewModelScope.launch {
            val folder = PiperNames.safeName(DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/'))
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val skipShared = storage.piper.espeakReady()
                    var files = 0
                    fun walk(documentId: String, relative: String) {
                        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
                        val columns = arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                        )
                        // Zuerst alle Einträge lesen, dann kopieren (der Cursor soll nicht während langer Kopien offen bleiben)
                        val entries = resolver.query(children, columns, null, null, null)?.use { c ->
                            generateSequence { if (c.moveToNext()) Triple(c.getString(0), c.getString(1), c.getString(2)) else null }.toList()
                        }.orEmpty()
                        for ((id, name, mime) in entries) {
                            val path = if (relative.isEmpty()) name else "$relative/$name"
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (!(skipShared && name == PiperStore.ESPEAK && relative.isEmpty())) walk(id, path)
                            } else {
                                val input = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id)) ?: continue
                                if (input.use { storage.piper.importFile(folder, path, it, skipShared) }) files++
                            }
                        }
                    }
                    walk(DocumentsContract.getTreeDocumentId(tree), "")
                    storage.piper.problem(folder)?.let { problem ->
                        storage.piper.discardIncomplete(folder)
                        throw IllegalStateException(problem)
                    }
                    files
                }
            }
            outcome.onSuccess { piperNote = "Stimme „$folder“ übernommen. Im Companion-Editor unter „Stimme“ auswählbar." }
            outcome.onFailure { piperNote = "Import fehlgeschlagen: ${it.message}" }
            piperVoices = storage.piper.list()
            piperBusy = false
        }
    }

    fun deletePiper(voice: PiperVoice) {
        storage.piper.delete(voice.name)
        piperVoices = storage.piper.list()
        piperNote = "Stimme „${voice.name}“ gelöscht."
    }

    /** Beispielsatz mit einer Piper-Stimme (Einstellungen). */
    fun testPiper(voice: PiperVoice) {
        piperNote = ""
        val language = voice.language
        val tag = PACK_LANGUAGES.map { it.first }.firstOrNull { language != null && it.startsWith(language) } ?: language
        speechOut.speak(VOICE_SAMPLES[tag] ?: "Hello, this is how my voice sounds.", tag, voice.id) { piperNote = it }
    }

    /** Beispielsatz mit einer bestimmten Stimme (Companion-Editor, noch nicht gespeichert). */
    fun testCompanionVoice(languageName: String, voiceName: String) {
        val tag = Speech.languageTag(languageName)
        speechOut.speak(VOICE_SAMPLES[tag] ?: "Test", tag, voiceName) { status = it }
    }

    /** Die Stimme des gewählten Companions gilt für Antworten und Vorlesen. */
    private fun syncVoice() {
        speechOut.voice = persona.voice
        speechOut.voiceMale = persona.voiceMale
        speechOut.voiceFemale = persona.voiceFemale
    }

    fun installVoices() {
        voiceNote = if (speechOut.openVoiceInstall()) "Stimmen in der Google-Sprachausgabe laden, danach hier „Status aktualisieren“."
        else "Die Google-Sprachausgabe lässt sich nicht öffnen (Android-Einstellungen → Sprachausgabe)."
    }

    fun downloadPack(tag: String) {
        if (!packs.isSupported) return
        fun set(state: String, progress: Int = 0) {
            packRows = packRows.map { if (it.tag == tag) it.copy(state = state, progress = progress) else it }
        }
        set("downloading")
        packNote = "Lade Sprachpaket $tag…"
        packs.download(
            tag,
            onProgress = { set("downloading", it) },
            onDone = { result ->
                result.onFailure {
                    set("available")
                    packNote = "Download fehlgeschlagen (${it.message})"
                }
                result.onSuccess { if (it == "installiert") packNote = "Sprachpaket $tag: $it" }
                refreshPacks()
            },
        )
        packs.openDownloadDialog(tag)
        pollPack(tag, System.currentTimeMillis() + 5 * 60_000)
    }

    /** Der Dienst meldet den Fortschritt oft nicht: regelmäßig nachsehen, bis das Paket da ist oder die Zeit abläuft. */
    private fun pollPack(tag: String, until: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (packRows.none { it.tag == tag && it.state == "downloading" }) return@postDelayed
            packs.check { result ->
                when {
                    result.getOrNull()?.installed?.contains(tag) == true -> {
                        packNote = "Sprachpaket $tag: installiert"
                        refreshPacks()
                    }
                    System.currentTimeMillis() < until -> pollPack(tag, until)
                    else -> {
                        packRows = packRows.map { if (it.tag == tag) it.copy(state = "available", progress = 0) else it }
                        packNote = "Sprachpaket $tag wurde nicht installiert (Internetverbindung prüfen, dann erneut laden)."
                        refreshPacks()
                    }
                }
            }
        }, 4000)
    }

    /** Womit die letzte Spracherkennung lief (für die Statuszeile). */
    private var speechMode = "offline"

    private fun speak(reply: String) {
        if (docReading) return // ein Text wird gerade vorgelesen: die Antwort nicht dazwischenreden lassen
        val text = Speech.spokenText(reply)
        if (text.isNotEmpty()) speechOut.speak(text, languageTag()) { status = it }
    }

    private fun handle(event: ChatEvent) {
        when (event) {
            is ChatEvent.Status -> {
                status = event.text
                if (event.text.startsWith("Warte")) live = null
            }
            is ChatEvent.Live -> live = event
            is ChatEvent.Added -> {
                if (event.message.role == "assistant") {
                    live = null
                    if (config.speakReplies) speak(event.message.content)
                    val segments = splitSegments(event.message.content)
                    triggerKeywords(
                        segments.filter { it.first == "text" }.joinToString(" ") { it.second }, "Antwort",
                        skip = segments.filter { it.first == "call" }.map { normalizeName(it.second.substringBefore(':')) }.toSet(),
                    )
                }
                messages += toUi(event.message)
            }
            is ChatEvent.Notice -> messages += UiMessage(
                when (event.kind) { NoticeKind.ERROR -> UiKind.ERROR; NoticeKind.TOOL -> UiKind.TOOL; NoticeKind.INFO -> UiKind.INFO },
                event.text,
            )
            is ChatEvent.RestoreInput -> if (input.isBlank()) input = event.text
            ChatEvent.Finished -> {}
        }
    }

    fun newChat() {
        if (streaming) return
        session.reset()
        storage.deleteChat(persona.id)
        renderHistory()
        status = "Bereit"
    }

    // --- Chats speichern, laden ---------------------------------------------------------------------------

    private fun saveChat() {
        if (persona.id.isEmpty()) return
        if (session.history.isEmpty()) storage.deleteChat(persona.id)
        else storage.saveChat(persona.id, ChatLog.toJson(session.history, persona.id, config.userName))
    }

    private fun restoreChat() {
        storage.loadChat(persona.id)?.let { input = session.load(it.messages) }
        renderHistory()
    }

    fun exportChat(): String = ChatLog.toJson(session.history, persona.id, config.userName)

    /** Lädt einen Chat aus JSON (auch aus der PC-App). Es wird nichts ausgeführt. Liefert eine Fehlermeldung oder null. */
    fun importChat(text: String): String? {
        if (streaming) return "Während der Antwort nicht möglich."
        val loaded = try {
            ChatLog.fromJson(text)
        } catch (e: ChatFileException) {
            return e.message
        }
        if (loaded.persona.isNotEmpty() && loaded.persona != persona.id && loaded.persona in personaIds) selectPersona(loaded.persona)
        input = session.load(loaded.messages)
        renderHistory()
        saveChat()
        status = "Chat geladen"
        return null
    }

    // --- Einstellungen ------------------------------------------------------------------------------------

    fun saveConfig(newConfig: Config) {
        config = newConfig.sanitize()
        speechOut.rate = config.speechRate.toFloat()
        storage.saveConfig(config)
        session.config = effectiveConfig(config)
        updateContext()
        warmUp()
    }

    /** Modelldatei für die Ausführung auf dem Gerät übernehmen (URI aus dem Auswahldialog) und umschalten. */
    fun setLocalModel(uri: String, name: String) {
        saveConfig(config.copy(backend = BACKEND_LOCAL, localModel = uri, localModelName = name))
        status = "Modell: $name"
    }

    /** Modelle des Servers abfragen (für die Einstellungen); Fehlertext statt Ausnahme. */
    suspend fun fetchModels(url: String, key: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching { ChatClient(url.trim().trimEnd('/'), key).listModels() }
    }

    // --- Companions ---------------------------------------------------------------------------------------

    fun selectPersona(id: String) {
        if (streaming || id == persona.id || id !in personaIds) return
        saveChat() // der laufende Chat bleibt beim bisherigen Companion erhalten
        persona = storage.personas.load(id)
        syncVoice()
        session.persona = persona
        session.reset()
        saveConfig(config.copy(persona = id))
        restoreChat()
        status = "Bereit"
    }

    fun savePersona(edited: Persona): String {
        val id = storage.personas.save(edited)
        personaIds = storage.personas.list()
        if (id == persona.id) {
            persona = edited.copy(id = id)
            syncVoice()
            session.persona = persona
            renderHistory()
        }
        return id
    }

    fun deletePersona(id: String) {
        if (personaIds.size <= 1) return // einer muss übrig bleiben
        storage.personas.delete(id)
        storage.deleteChat(id)
        personaIds = storage.personas.list()
        if (id == persona.id) selectPersona(personaIds.first())
    }

    fun loadPersona(id: String): Persona = storage.personas.load(id)

    fun avatarFile(persona: Persona): File? = storage.avatarFile(persona.avatar)

    /** Alle Bilder eines Ordners (aus dem Auswahldialog) in den Avatar-Ordner der App kopieren. Liefert die Anzahl. */
    suspend fun importAvatarFolder(tree: Uri): Int = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var count = 0
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val (id, name, mime) = Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                if (mime?.startsWith("image/") != true) continue
                val input = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id)) ?: continue
                input.use { storage.importAvatar(name, it) }
                count++
            }
        }
        avatarVersion++
        count
    }

    /** Ein Bild als Avatar eines gespeicherten Companions übernehmen. */
    suspend fun setAvatar(personaId: String, uri: Uri, displayName: String): Boolean = withContext(Dispatchers.IO) {
        val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: return@withContext false
        val relative = input.use { storage.importAvatar(displayName, it) }
        savePersona(storage.personas.load(personaId).copy(avatar = relative))
        avatarVersion++
        true
    }

    fun removeAvatar(personaId: String) {
        savePersona(storage.personas.load(personaId).copy(avatar = ""))
        avatarVersion++
    }

    /** Companion aus einer JSON-Datei der PC-App übernehmen. Liefert die neue ID. */
    fun importPersona(text: String, fileName: String): String {
        val id = storage.personas.uniqueId(fileName.removeSuffix(".json").ifEmpty { "companion" })
        return savePersona(Persona.fromJson(text, id))
    }

    // --- Werkzeuge ----------------------------------------------------------------------------------------

    fun reloadTools() {
        tools = tools.copy(loading = true)
        viewModelScope.launch {
            val fresh = ToolRegistry()
            withContext(Dispatchers.IO) {
                try {
                    fresh.startFromConfig(storage.mcpJson(), DefaultServerFactory(
                        secret = { storage.secrets()[it] },
                        certificate = { storage.certPem(it) },
                    ))
                } catch (e: Exception) {
                    fresh.errors += "Werkzeuge konnten nicht geladen werden: ${e.message}"
                }
            }
            val old = registry
            registry = fresh
            session.registry = fresh
            withContext(Dispatchers.IO) { old.shutdown() }
            tools = ToolsState(fresh.available(), fresh.errors.toList(), false)
            status = if (fresh.errors.isNotEmpty()) "Werkzeuge: " + fresh.errors.joinToString(" | ")
            else "Werkzeuge bereit: ${fresh.available().joinToString(", ").ifEmpty { "keine" }}"
            updateContext()
            warmUp()
        }
    }

    private var warming = false

    /**
     * Lädt das lokale Modell und berechnet den bisherigen Verlauf vorab, solange noch nichts gefragt wurde: Die erste Antwort
     * nach einem App-Start braucht sonst mehrere Minuten, weil der ganze Prompt (Persona, Werkzeuge, Verlauf) neu verarbeitet
     * wird. Die spätere Anfrage nutzt den Zwischenspeicher für den gemeinsamen Anfang.
     */
    private fun warmUp() {
        if (warming || streaming || config.backend != BACKEND_LOCAL || config.localModel.isEmpty() || session.history.isEmpty()) return
        val cfg = effectiveConfig(config)
        val messages = session.requestMessages()
        val stop = session.stopSequences
        warming = true
        status = "Modell wird vorbereitet…"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    localLlm.backend(cfg).streamChat(cfg.model, messages, cfg.temperature, 1, stop, CancelToken(), cfg.samplingParams()).collect { }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) { // Vorwärmen ist nur eine Beschleunigung; Fehler zeigt die erste echte Anfrage
            } finally {
                warming = false
                if (status == "Modell wird vorbereitet…") status = "Bereit"
            }
        }
    }

    /** Speichert mcp_servers.json (muss gültiges JSON sein) und lädt die Werkzeuge neu. Liefert eine Fehlermeldung oder null. */
    fun saveMcpJson(text: String): String? {
        try {
            if (AppJson.parseToJsonElement(text) !is JsonObject) return "Das Wurzelelement muss ein Objekt sein."
        } catch (e: Exception) {
            return "Kein gültiges JSON: ${e.message}"
        }
        storage.saveMcpJson(text)
        mcpText = text
        reloadTools()
        return null
    }

    /** Liest eine Liste `Wort;Werkzeug;Parameter…` (CSV oder Excel) und zeigt vor dem Übernehmen, was sich in mcp_servers.json ändert. */
    fun previewKeywordImport(uri: Uri) {
        keywordImportError = ""
        viewModelScope.launch {
            try {
                keywordImport = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Liste"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw DocumentException("Die Datei lässt sich nicht öffnen.")
                    if (bytes.size > 20_000_000) throw DocumentException("Die Datei ist zu groß für eine Schlüsselwortliste.")
                    KeywordImportPreview(name, KeywordImport.fromFile(name, bytes, storage.mcpJson()))
                }
            } catch (e: DocumentException) {
                keywordImportError = e.message.orEmpty()
            } catch (e: Exception) {
                keywordImportError = "Die Liste lässt sich nicht lesen: ${e.message}"
            }
        }
    }

    fun applyKeywordImport() {
        val preview = keywordImport ?: return
        keywordImport = null
        keywordImportError = saveMcpJson(preview.result.json).orEmpty()
        if (keywordImportError.isEmpty()) {
            status = "Schlüsselwörter übernommen: ${preview.result.parsed.wordCount} Wörter für ${preview.result.parsed.tools.joinToString(", ")}."
        }
    }

    fun cancelKeywordImport() {
        keywordImport = null
    }

    fun resetMcpJson() {
        mcpText = storage.defaultMcpJson()
    }

    fun setSecret(name: String, value: String) {
        val clean = name.trim()
        val token = value.trim() // beim Einfügen kommen oft Leerzeichen oder ein Zeilenumbruch mit
        if (clean.isEmpty() || token.isEmpty()) return
        storage.saveSecrets(storage.secrets() + (clean to token))
        secretNames = storage.secrets().keys.sorted()
        reloadTools() // der Server, der das Geheimnis braucht, startet jetzt
    }

    fun deleteSecret(name: String) {
        storage.saveSecrets(storage.secrets() - name)
        secretNames = storage.secrets().keys.sorted()
    }

    fun saveCert(name: String, pem: String) {
        storage.saveCert(name, pem)
        certNames = storage.certNames()
    }

    fun deleteCert(name: String) {
        storage.deleteCert(name)
        certNames = storage.certNames()
    }

    suspend fun fetchCert(host: String, port: Int): Result<FetchedCert> = withContext(Dispatchers.IO) {
        runCatching { fetchServerCertificate(host.trim(), port) }
    }

    override fun onCleared() {
        android.util.Log.w("AiChat", "ViewModel onCleared")
        speechIn.stop()
        speechOut.shutdown()
        localLlm.unload()
        registry.shutdown()
    }

    // Muss am Ende der Klasse stehen: Eigenschaften werden in der Reihenfolge des Quelltextes angelegt, und renderHistory() und die
    // Werkzeuge greifen auf Eigenschaften weiter unten zu (Vorleser, Tonfokus, Marken). Steht init oben, sind sie dann noch null.
    init {
        storage.ensureDefaults()
        config = storage.loadConfig()
        speechOut.rate = config.speechRate.toFloat()
        speechOut.piperStore = storage.piper
        speechOut.piperThreads = { config.localThreads }
        piperVoices = storage.piper.list()
        personaIds = storage.personas.list()
        val id = config.persona.takeIf { it in personaIds } ?: personaIds.firstOrNull().orEmpty()
        persona = storage.personas.load(id)
        syncVoice()
        session = ChatSession(effectiveConfig(config), persona, registry)
        engine = ChatEngine(
            session,
            { if (it.backend == BACKEND_LOCAL) localLlm.backend(it) else ChatClient(it.baseUrl, it.apiKey) },
        ) { call, spec -> askUser(call, spec) }
        restoreChat()
        mcpText = storage.mcpJson()
        secretNames = storage.secrets().keys.sorted()
        certNames = storage.certNames()
        reloadTools()
    }
}
