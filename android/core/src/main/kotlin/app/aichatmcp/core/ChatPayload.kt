package app.aichatmcp.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Wie ein Anbieter die Chat-Anfrage verlangt. Der "OpenAI-Dialekt" ist bei jedem etwas anders streng: Eigene Felder wie `repeat_penalty`
 * (LM Studio, Ollama, llama.cpp kennen sie) lehnen OpenAI und Gemini mit HTTP 400 ab, neuere OpenAI-Modelle wollen `max_completion_tokens` statt
 * `max_tokens` und erlauben bei den Denkmodellen weder `temperature` noch `stop`.
 */
enum class Dialect {
    /** LM Studio, Ollama, llama.cpp und andere eigene Server: alles wird gesendet. */
    STANDARD,
    OPENAI,
    ANTHROPIC,
    GEMINI,
}

object ChatPayload {
    /** OpenAI-Denkmodelle (o1, o3, o4-mini, gpt-5 …): nur die Standardtemperatur, kein `stop`, keine Sampling-Parameter. */
    fun isOpenAiReasoning(model: String): Boolean {
        val m = model.lowercase().removePrefix("ft:")
        return Regex("^(o\\d|gpt-5)").containsMatchIn(m) && !m.startsWith("gpt-5-chat")
    }

    fun build(
        dialect: Dialect, model: String, messages: List<WireMessage>, temperature: Double, maxTokens: Int,
        stop: List<String>, extra: Map<String, Double>,
    ): JsonObject = buildJsonObject {
        val reasoning = dialect == Dialect.OPENAI && isOpenAiReasoning(model)
        put("model", model)
        putJsonArray("messages") {
            for (m in messages) add(buildJsonObject { put("role", m.role); put("content", m.content) })
        }
        put("stream", true)
        if (!reasoning) put("temperature", temperature)
        put(if (dialect == Dialect.OPENAI) "max_completion_tokens" else "max_tokens", maxTokens)
        // Die Verbrauchsangabe dient nur der Kontextanzeige; Gemini und Anthropic kennen das Feld nicht verlässlich.
        if (dialect == Dialect.STANDARD || dialect == Dialect.OPENAI) putJsonObject("stream_options") { put("include_usage", true) }
        for ((k, v) in extra) {
            val allowed = when (dialect) {
                Dialect.STANDARD -> true
                Dialect.OPENAI -> !reasoning && k in setOf("top_p", "frequency_penalty", "presence_penalty")
                Dialect.ANTHROPIC -> k == "top_p"
                Dialect.GEMINI -> k in setOf("top_p", "frequency_penalty", "presence_penalty")
            }
            if (allowed) put(k, JsonPrimitive(v))
        }
        if (stop.isNotEmpty() && !reasoning) putJsonArray("stop") { stop.forEach { add(JsonPrimitive(it)) } }
    }
}
