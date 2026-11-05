package app.aichatmcp

import android.app.Application
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
import app.aichatmcp.core.ChatSession
import app.aichatmcp.core.Config
import app.aichatmcp.core.DefaultServerFactory
import app.aichatmcp.core.Message
import app.aichatmcp.core.NoticeKind
import app.aichatmcp.core.Persona
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
import app.aichatmcp.local.LocalLlm
import app.aichatmcp.local.effectiveConfig
import kotlinx.serialization.json.JsonObject

enum class UiKind { USER, ASSISTANT, RESULT, RESULT_ERROR, SHOWN, INFO, ERROR, TOOL }

data class UiMessage(val kind: UiKind, val text: String)

class PendingConfirm(val call: ToolCall, val spec: ToolSpec?, val persona: String, private val answer: CompletableDeferred<Boolean>) {
    fun answer(ok: Boolean) {
        answer.complete(ok)
    }
}

data class ToolsState(val available: List<String> = emptyList(), val errors: List<String> = emptyList(), val loading: Boolean = true)

private const val RESULT_PREVIEW_CHARS = 160

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
    var mcpText by mutableStateOf("")
        private set
    var secretNames by mutableStateOf<List<String>>(emptyList())
        private set
    var certNames by mutableStateOf<List<String>>(emptyList())
        private set

    private val localLlm = LocalLlm(app)
    private var registry = ToolRegistry()
    private val session: ChatSession
    private val engine: ChatEngine
    private var turn: Job? = null

    init {
        storage.ensureDefaults()
        config = storage.loadConfig()
        personaIds = storage.personas.list()
        val id = config.persona.takeIf { it in personaIds } ?: personaIds.firstOrNull().orEmpty()
        persona = storage.personas.load(id)
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
        m.role == "user" -> UiMessage(UiKind.USER, m.content)
        else -> UiMessage(UiKind.ASSISTANT, m.content)
    }

    /** Zeichnet Begrüßung und Verlauf neu (Companion-Wechsel, neuer Chat, geladener Chat). */
    private fun renderHistory() {
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
        if (config.backend == BACKEND_LOCAL && config.localModel.isEmpty()) {
            status = "Bitte zuerst in den Einstellungen eine Modelldatei wählen."
            return
        }
        if (config.backend != BACKEND_LOCAL && config.model.isEmpty()) {
            status = "Bitte zuerst in den Einstellungen ein Modell wählen."
            return
        }
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
        engine.cancel()
    }

    private fun handle(event: ChatEvent) {
        when (event) {
            is ChatEvent.Status -> {
                status = event.text
                if (event.text.startsWith("Warte")) live = null
            }
            is ChatEvent.Live -> live = event
            is ChatEvent.Added -> {
                if (event.message.role == "assistant") live = null
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
        storage.saveConfig(config)
        session.config = effectiveConfig(config)
        updateContext()
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

    fun resetMcpJson() {
        mcpText = storage.defaultMcpJson()
    }

    fun setSecret(name: String, value: String) {
        val clean = name.trim()
        if (clean.isEmpty() || value.isEmpty()) return
        storage.saveSecrets(storage.secrets() + (clean to value))
        secretNames = storage.secrets().keys.sorted()
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
        localLlm.unload()
        registry.shutdown()
    }
}
