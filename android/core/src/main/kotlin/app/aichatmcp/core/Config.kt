package app.aichatmcp.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val BACKEND_SERVER = "server"
const val BACKEND_LOCAL = "local"

/** Konfiguration (gleiche JSON-Schlüssel wie config.json der Python-App). Defekte Daten führen zu Defaults. */
@Serializable
data class Config(
    /** Voller Basis-URL inkl. /v1 - dadurch passt auch jeder OpenAI-kompatible Online-Dienst. */
    @SerialName("base_url") val baseUrl: String = "http://127.0.0.1:1234/v1",
    @SerialName("api_key") val apiKey: String = "",
    val model: String = "",
    val models: List<String> = emptyList(),
    val persona: String = "companion",
    @SerialName("user_name") val userName: String = "Nutzer",
    val temperature: Double = 0.7,
    @SerialName("max_reply_tokens") val maxReplyTokens: Int = 600,
    @SerialName("context_tokens") val contextTokens: Int = 8192,
    /** Gegen Wiederholungen bei kleinen Modellen; repeat_penalty 0 = nicht senden (Online-Dienste lehnen ihn ab). */
    @SerialName("top_p") val topP: Double = 0.9,
    @SerialName("frequency_penalty") val frequencyPenalty: Double = 0.3,
    @SerialName("presence_penalty") val presencePenalty: Double = 0.2,
    @SerialName("repeat_penalty") val repeatPenalty: Double = 1.1,
    @SerialName("confirm_tools") val confirmTools: Boolean = true,
    @SerialName("max_tool_calls") val maxToolCalls: Int = 3,
    @SerialName("tool_result_chars") val toolResultChars: Int = 2000,
    @SerialName("tool_timeout") val toolTimeout: Int = 60,
    /** "server" (LM Studio, OpenAI, ...) oder "local" (llama.cpp auf dem Gerät, nur Android). */
    val backend: String = BACKEND_SERVER,
    /** Lokales Modell: Pfad zu einer GGUF-Datei oder eine content://-URI (vom Dateiauswahldialog). */
    @SerialName("local_model") val localModel: String = "",
    @SerialName("local_model_name") val localModelName: String = "",
    @SerialName("local_threads") val localThreads: Int = 4,
    /** Bildmodell (beschreibt Bilder als Text): nur die Anzeigenamen, die Dateien liegen im privaten Speicher der App. */
    @SerialName("vision_model_name") val visionModelName: String = "",
    @SerialName("vision_proj_name") val visionProjName: String = "",
    /** Antworten des Companions vorlesen (Sprachausgabe des Geräts). */
    @SerialName("speak_replies") val speakReplies: Boolean = false,
    /** Nach der Spracheingabe die Nachricht sofort senden. */
    @SerialName("voice_auto_send") val voiceAutoSend: Boolean = false,
    /** Fehlt das Offline-Sprachpaket, die Online-Erkennung von Google benutzen (das Audio geht dann an Google). */
    @SerialName("speech_online") val speechOnline: Boolean = true,
    /** Schlüsselwörter aus mcp_servers.json (`keywords` je Alias) lösen Werkzeuge aus. Standard: aus. */
    @SerialName("keywords_enabled") val keywordsEnabled: Boolean = false,
    /** Sprechgeschwindigkeit der Sprachausgabe (1.0 = normal; die deutsche Stimme spricht etwa 20 % langsamer als die englische). */
    @SerialName("speech_rate") val speechRate: Double = 1.0,
) {
    fun sanitize(): Config = copy(
        baseUrl = baseUrl.trim().trimEnd('/').ifEmpty { "http://127.0.0.1:1234/v1" },
        userName = userName.trim().ifEmpty { "Nutzer" },
        temperature = temperature.coerceIn(0.0, 2.0),
        speechRate = speechRate.coerceIn(0.5, 2.5),
        maxReplyTokens = maxReplyTokens.coerceIn(16, 8192),
        contextTokens = contextTokens.coerceIn(1024, 1_000_000),
        topP = topP.coerceIn(0.05, 1.0),
        frequencyPenalty = frequencyPenalty.coerceIn(-2.0, 2.0),
        presencePenalty = presencePenalty.coerceIn(-2.0, 2.0),
        repeatPenalty = repeatPenalty.coerceIn(0.0, 2.0),
        maxToolCalls = maxToolCalls.coerceIn(0, 10),
        toolResultChars = toolResultChars.coerceIn(200, 20000),
        toolTimeout = toolTimeout.coerceIn(5, 600),
        backend = if (backend == BACKEND_LOCAL) BACKEND_LOCAL else BACKEND_SERVER,
        localThreads = localThreads.coerceIn(1, 16),
    )

    /** Zusätzliche Sampling-Parameter für die Anfrage; neutrale Werte werden weggelassen. */
    fun samplingParams(): Map<String, Double> = buildMap {
        if (topP < 1.0) put("top_p", topP)
        if (frequencyPenalty != 0.0) put("frequency_penalty", frequencyPenalty)
        if (presencePenalty != 0.0) put("presence_penalty", presencePenalty)
        if (repeatPenalty != 0.0 && repeatPenalty != 1.0) put("repeat_penalty", repeatPenalty)
    }

    fun toJson(): String = AppJson.encodeToString(serializer(), sanitize())

    companion object {
        fun fromJson(text: String?): Config {
            if (text.isNullOrBlank()) return Config()
            return try {
                AppJson.decodeFromString(serializer(), text).sanitize()
            } catch (_: Exception) {
                Config()
            }
        }
    }
}
