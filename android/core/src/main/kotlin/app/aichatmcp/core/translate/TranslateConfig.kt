package app.aichatmcp.core.translate

const val MIN_CHUNK_CHARS = 500
const val MAX_CHUNK_CHARS = 20000
const val DEFAULT_CHUNK_CHARS = 3500

/** {source}/{target} werden beim Senden ersetzt. Wird nur für Nicht-TranslateGemma-Modelle benutzt. */
const val DEFAULT_PROMPT = "Translate the following text from {source} to {target}. " +
    "Output only the translation, without comments. Preserve Markdown formatting."

/**
 * Einstellungen einer Übersetzung. [model] ist der Modellname (Server) oder der Dateiname des Modells auf dem Gerät;
 * daran erkennt die Pipeline TranslateGemma.
 */
data class TranslateConfig(
    val model: String = "",
    val langSource: String = "en",
    val langTarget: String = "de",
    val translationPrompt: String = DEFAULT_PROMPT,
    val maxChunkChars: Int = DEFAULT_CHUNK_CHARS,
    val useContext: Boolean = true,
) {
    val isTranslateGemma: Boolean get() = "translategemma" in model.lowercase()

    fun sanitized(): TranslateConfig = copy(
        langSource = Languages.normalizeCode(langSource).ifEmpty { "en" },
        langTarget = Languages.normalizeCode(langTarget).ifEmpty { "de" },
        maxChunkChars = maxChunkChars.coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS),
        translationPrompt = translationPrompt.ifBlank { DEFAULT_PROMPT },
    )
}
