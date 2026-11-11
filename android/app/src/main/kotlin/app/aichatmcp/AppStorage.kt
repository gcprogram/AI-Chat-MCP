package app.aichatmcp

import app.aichatmcp.core.AppJson
import app.aichatmcp.core.ChatFileException
import app.aichatmcp.core.ChatLog
import app.aichatmcp.core.Config
import app.aichatmcp.core.LoadedChat
import app.aichatmcp.core.Persona
import app.aichatmcp.core.EmbeddedAvatar
import app.aichatmcp.core.PersonaStore
import app.aichatmcp.core.PiperStore
import app.aichatmcp.core.ReadMarks
import app.aichatmcp.core.TodoStore
import app.aichatmcp.core.WhisperStore
import app.aichatmcp.core.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream

/**
 * Alles, was die App im privaten Speicher ablegt (nur für die App lesbar): Einstellungen, Companions,
 * Werkzeugkonfiguration, Geheimnisse (Tokens), importierte Zertifikate und der laufende Chat je Companion.
 */
class AppStorage(root: File, private val asset: (String) -> String?) {
    val personasDir = File(root, "personas")
    private val chatsDir = File(root, "chats")
    private val certsDir = File(root, "certs")
    private val configFile = File(root, "config.json")
    private val mcpFile = File(root, "mcp_servers.json")
    private val secretsFile = File(root, "secrets.json")

    val personas get() = PersonaStore(personasDir)

    /** Piper-Stimmen (Modelle für die Sprachausgabe auf dem Gerät). */
    val piper = PiperStore(File(root, "piper"))

    /** Whisper-Modelle (Spracheingabe auf dem Gerät). */
    val whisper = WhisperStore(File(root, "whisper"))

    /** Todos des Organizers (Werkzeuge `todo-*` und Tagesupdate). */
    val todos = TodoStore(File(root, "todos.json"))
    private val offeredFile = File(root, "defaults_offered.txt")

    /** Beim ersten Start: Standard-Companion und Werkzeugkonfiguration aus den mitgelieferten Dateien anlegen. */
    fun ensureDefaults() {
        if (!mcpFile.exists()) asset("default_mcp_servers.json")?.let { mcpFile.writeText(it) }
        mergeNewDefaults()
        if (personas.list().isEmpty()) {
            asset("default_persona.json")?.let {
                personasDir.mkdirs()
                File(personasDir, "companion.json").writeText(it)
            }
        }
    }

    // --- Avatare (Bilder der Companions, relativ zum Companion-Ordner: "avatars/name.png") -------------------

    private val avatarCacheDir = File(root, "avatar-cache")

    /** Die Bilddatei eines Companions: das in der Companion-Datei eingebettete Bild (ausgepackt in den Zwischenspeicher), sonst die Datei aus "avatar". */
    fun avatarFile(persona: Persona): File? {
        if (persona.avatarData.isNotBlank()) {
            val bytes = EmbeddedAvatar.decode(persona.avatarData)
            if (bytes != null) {
                val file = File(avatarCacheDir, EmbeddedAvatar.cacheName(bytes))
                if (!file.isFile) {
                    avatarCacheDir.mkdirs()
                    file.writeBytes(bytes)
                }
                return file
            }
        }
        return avatarFile(persona.avatar)
    }

    /** Die Bilddatei aus dem Feld "avatar" (älteres Format), falls vorhanden. Pfade außerhalb des Companion-Ordners werden ignoriert. */
    fun avatarFile(avatar: String): File? {
        if (avatar.isBlank()) return null
        val base = personasDir.canonicalFile
        val file = File(personasDir, avatar).canonicalFile
        return file.takeIf { it.isFile && it.path.startsWith(base.path + File.separator) }
    }

    /** Legt ein Bild unter avatars/ ab (Dateiname wie am PC, damit die Companion-Dateien es wiederfinden). */
    fun importAvatar(name: String, input: InputStream): String {
        val dir = File(personasDir, "avatars").apply { mkdirs() }
        val clean = File(name).name.ifBlank { "avatar.png" }
        File(dir, clean).outputStream().use { input.copyTo(it) }
        return "avatars/$clean"
    }

    // --- Vorlese-Marken (wo das Vorlesen eines Textes stehen geblieben ist) -------------------------------

    private val marksFile = File(root, "reading_marks.json")

    fun loadMarks(): ReadMarks = ReadMarks.fromJson(marksFile.takeIf { it.exists() }?.readText())

    fun saveMarks(marks: ReadMarks) {
        val temp = File(marksFile.parentFile, marksFile.name + ".tmp")
        temp.writeText(marks.toJson())
        if (!temp.renameTo(marksFile)) marksFile.writeText(marks.toJson()) // Umbenennen kann auf manchen Geräten scheitern
    }

    // --- Einstellungen ------------------------------------------------------------------------------------

    fun loadConfig(): Config = Config.fromJson(configFile.takeIf { it.exists() }?.readText())

    fun saveConfig(config: Config) = configFile.writeText(config.toJson())

    /**
     * Neue Standard-Werkzeuge (Server/Aliase, die es bei der Installation noch nicht gab) werden einmal in eine bestehende
     * mcp_servers.json eingetragen, damit Updates sie nicht verstecken. Was der Nutzer danach löscht, kommt nicht wieder
     * (`defaults_offered.txt` merkt sich, was schon angeboten wurde). Vorhandene Einträge bleiben unverändert.
     */
    private fun mergeNewDefaults() {
        val offered = offeredFile.takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        val pending = NEW_DEFAULTS.filter { it !in offered }
        if (pending.isEmpty()) return
        val current = runCatching { AppJson.parseToJsonElement(mcpFile.readText()) as? JsonObject }.getOrNull() ?: return // kaputte Datei: nicht anfassen
        val defaults = (asset("default_mcp_servers.json")?.let { runCatching { AppJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }) ?: return
        fun section(o: JsonObject, key: String) = (o[key] as? JsonObject).orEmpty()
        val servers = section(current, "servers").toMutableMap()
        val aliases = section(current, "aliases").toMutableMap()
        for (name in pending) {
            section(defaults, "servers")[name]?.let { servers.putIfAbsent(name, it) }
            section(defaults, "aliases")[name]?.let { aliases.putIfAbsent(name, it) }
        }
        val merged = JsonObject(current.toMutableMap().apply { put("servers", JsonObject(servers)); put("aliases", JsonObject(aliases)) })
        if (merged != current) mcpFile.writeText(AppJson.encodeToString(JsonObject.serializer(), merged) + "\n")
        offeredFile.writeText((offered + pending).joinToString("\n") + "\n")
    }

    // --- Werkzeuge (mcp_servers.json), Geheimnisse, Zertifikate -------------------------------------------

    fun mcpJson(): String = mcpFile.takeIf { it.exists() }?.readText() ?: asset("default_mcp_servers.json").orEmpty()

    fun saveMcpJson(text: String) = mcpFile.writeText(text)

    fun defaultMcpJson(): String = asset("default_mcp_servers.json").orEmpty()

    fun secrets(): Map<String, String> {
        val obj = try {
            AppJson.parseToJsonElement(secretsFile.takeIf { it.exists() }?.readText() ?: "{}") as? JsonObject
        } catch (_: Exception) {
            null
        }
        return obj?.keys?.mapNotNull { k -> obj.string(k)?.let { k to it } }?.toMap().orEmpty()
    }

    fun saveSecrets(secrets: Map<String, String>) {
        secretsFile.writeText(AppJson.encodeToString(JsonObject.serializer(), buildJsonObject {
            secrets.toSortedMap().forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }))
    }

    private fun safeName(name: String): String =
        File(name.trim()).name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "zertifikat.pem" }

    fun certNames(): List<String> = certsDir.listFiles { f -> f.isFile }?.map { it.name }?.sorted().orEmpty()

    fun certPem(name: String): String? = File(certsDir, safeName(name)).takeIf { it.isFile }?.readText()

    fun saveCert(name: String, pem: String) {
        certsDir.mkdirs()
        File(certsDir, safeName(name)).writeText(pem)
    }

    fun deleteCert(name: String) {
        File(certsDir, safeName(name)).delete()
    }

    // --- Chats (je Companion der laufende Chat; Dateiformat wie die Python-App) --------------------------

    private fun chatFile(personaId: String) = File(chatsDir, safeName(personaId) + ChatLog.EXTENSION)

    /** Chat-Dateien hießen früher `<companion>.json`; sie werden weiter gelesen und beim nächsten Speichern umgestellt. */
    private fun legacyChatFile(personaId: String) = File(chatsDir, safeName(personaId) + ".json")

    fun loadChat(personaId: String): LoadedChat? = try {
        (chatFile(personaId).takeIf { it.isFile } ?: legacyChatFile(personaId).takeIf { it.isFile })?.let { ChatLog.fromJson(it.readText()) }
    } catch (_: ChatFileException) {
        null
    }

    fun saveChat(personaId: String, json: String) {
        chatsDir.mkdirs()
        chatFile(personaId).writeText(json)
        legacyChatFile(personaId).delete()
    }

    fun deleteChat(personaId: String) {
        chatFile(personaId).delete()
        legacyChatFile(personaId).delete()
    }

    private companion object {
        /** Standard-Einträge, die nachträglich in bestehende Konfigurationen übernommen werden (Server und Aliase). */
        val NEW_DEFAULTS = listOf("organizer", "todo-add", "todo-list", "todo-done")
    }
}
