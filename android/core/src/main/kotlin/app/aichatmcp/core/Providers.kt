package app.aichatmcp.core

/**
 * Vorkonfigurierte Anbieter für das Chat-Modell im Netz. Alle sprechen den OpenAI-Chat-Dialekt (`/chat/completions`); Anthropic und Gemini bieten
 * dafür eigene Kompatibilitäts-Adressen. Der API-Schlüssel wird je Anbieter gemerkt (Geheimnisse der App, nicht in config.json).
 */
data class Provider(
    val id: String,
    val title: String,
    val baseUrl: String,
    val needsKey: Boolean,
    /** Wo der Nutzer den Schlüssel bekommt (Adresse, englisch/neutral). */
    val keyUrl: String = "",
    /** Zusätzliche Kopfzeilen außer "Authorization: Bearer" (Anthropic). */
    val extraHeaders: (String) -> Map<String, String> = { emptyMap() },
    /** Aus der Modellliste des Anbieters die Chat-Modelle (und lesbare Namen) auswählen. */
    val cleanModels: (List<String>) -> List<String> = { it },
)

object Providers {
    const val CUSTOM = "custom"
    const val LMSTUDIO = "lmstudio"

    private val NOT_CHAT = Regex("(?i)(embed|whisper|tts|dall-e|moderation|transcribe|realtime|audio|image|imagen|veo|aqa|davinci|babbage|computer-use|search-preview|omni-moderation|gemma-3n.*embed)")

    val lmstudio = Provider(LMSTUDIO, "LM Studio / Ollama (own server)", "http://192.168.0.10:1234/v1", needsKey = false, cleanModels = { ids -> ids.filter { "embed" !in it.lowercase() } })

    val openai = Provider(
        "openai", "OpenAI", "https://api.openai.com/v1", needsKey = true, keyUrl = "https://platform.openai.com/api-keys",
        cleanModels = { ids -> ids.filter { !NOT_CHAT.containsMatchIn(it) && (it.startsWith("gpt") || it.startsWith("o") || it.startsWith("chatgpt")) }.sorted() },
    )

    val anthropic = Provider(
        "anthropic", "Anthropic (Claude)", "https://api.anthropic.com/v1", needsKey = true, keyUrl = "https://console.anthropic.com/settings/keys",
        extraHeaders = { key -> mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01") },
        cleanModels = { ids -> ids.filter { it.startsWith("claude") }.sortedDescending() },
    )

    val gemini = Provider(
        "gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", needsKey = true, keyUrl = "https://aistudio.google.com/apikey",
        // die Liste nennt "models/gemini-…", Chat-Aufrufe wollen "gemini-…"
        cleanModels = { ids -> ids.map { it.removePrefix("models/") }.filter { it.startsWith("gemini") && !NOT_CHAT.containsMatchIn(it) }.sortedDescending() },
    )

    val custom = Provider(CUSTOM, "Other (OpenAI-compatible URL)", "", needsKey = false)

    val all = listOf(lmstudio, openai, anthropic, gemini, custom)

    fun byId(id: String): Provider = all.firstOrNull { it.id == id } ?: custom

    /** Der Anbieter zu einer gespeicherten Server-Adresse (damit ältere Einstellungen richtig erkannt werden). */
    fun forUrl(url: String): Provider {
        val u = url.trim().trimEnd('/').lowercase()
        return all.firstOrNull { it.baseUrl.isNotEmpty() && it.id != LMSTUDIO && u.startsWith(it.baseUrl.trimEnd('/').lowercase()) } ?: if (u.isEmpty()) lmstudio else custom
    }

    /** Warnung vor Adressen ohne Verschlüsselung außerhalb des eigenen Netzes (Schlüssel gingen im Klartext durch das Netz). */
    fun insecureWarning(url: String): Boolean {
        val u = url.trim().lowercase()
        if (!u.startsWith("http://")) return false
        val host = u.removePrefix("http://").substringBefore('/').substringBefore(':')
        val local = host == "localhost" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("172\\.(1[6-9]|2\\d|3[01])\\..*").matches(host) || host.endsWith(".local") || '.' !in host
        return !local
    }
}
