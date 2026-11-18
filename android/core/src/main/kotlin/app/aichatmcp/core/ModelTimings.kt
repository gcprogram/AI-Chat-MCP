package app.aichatmcp.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Gemessene Zeiten eines lokalen Modells auf diesem Gerät, damit die App vor dem ersten Laden sagen kann, wie lange es dauert:
 * [readyMs] = vom Start bis das Modell antworten kann (Laden plus Berechnung des Prompts, die letzte Messung), [answersMs] = die letzten
 * Antwortzeiten im geladenen Zustand (Prompt-Rest plus Erzeugung).
 */
@Serializable
data class ModelTimings(
    @SerialName("ready_ms") val readyMs: Long = 0,
    @SerialName("answers_ms") val answersMs: List<Long> = emptyList(),
) {
    fun withReady(ms: Long): ModelTimings = if (ms <= 0) this else copy(readyMs = ms)

    fun withAnswer(ms: Long): ModelTimings = if (ms <= 0) this else copy(answersMs = (answersMs + ms).takeLast(KEEP_ANSWERS))

    /** Typische Antwortzeit im geladenen Zustand (Median der letzten Messungen) in Sekunden, aufgerundet; null ohne Messung. */
    fun answerSeconds(): Int? = answersMs.sorted().let { if (it.isEmpty()) null else ((it[it.size / 2] + 999) / 1000).toInt().coerceAtLeast(1) }

    companion object {
        const val KEEP_ANSWERS = 5

        /** "m:ss" für eine Dauer, z. B. 144040 -> "2:24". */
        fun clock(ms: Long): String {
            val s = (ms + 500) / 1000
            return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }

        private val serializer = MapSerializer(String.serializer(), serializer())

        fun parseAll(text: String?): Map<String, ModelTimings> =
            if (text.isNullOrBlank()) emptyMap() else runCatching { AppJson.decodeFromString(serializer, text) }.getOrDefault(emptyMap())

        fun toJson(all: Map<String, ModelTimings>): String = AppJson.encodeToString(serializer, all)
    }
}
