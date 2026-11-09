package app.aichatmcp

import app.aichatmcp.core.AppJson
import app.aichatmcp.core.ChatFileException
import app.aichatmcp.core.ChatLog
import app.aichatmcp.core.Config
import app.aichatmcp.core.LoadedChat
import app.aichatmcp.core.PersonaStore
import app.aichatmcp.core.PiperStore
import app.aichatmcp.core.ReadMarks
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

    /** Beim ersten Start: Standard-Companion und Werkzeugkonfiguration aus den mitgelieferten Dateien anlegen. */
    fun ensureDefaults() {
        if (!mcpFile.exists()) asset("default_mcp_servers.json")?.let { mcpFile.writeText(it) }
        if (personas.list().isEmpty()) {
            asset("default_persona.json")?.let {
                personasDir.mkdirs()
                File(personasDir, "companion.json").writeText(it)
            }
        }
    }

    // --- Avatare (Bilder der Companions, relativ zum Companion-Ordner: "avatars/name.png") -------------------

    /** Die Bilddatei eines Companions, falls vorhanden. Pfade außerhalb des Companion-Ordners werden ignoriert. */
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
}
