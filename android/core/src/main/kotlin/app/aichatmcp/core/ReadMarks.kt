package app.aichatmcp.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Vorlese-Marke: wo das Vorlesen eines Textes stehen geblieben ist. Der Text wird über [uri] (dauerhafter Dateizugriff) wiedergefunden.
 * [chunk] ist der Abschnitt, bei dem es weitergeht, [total] die Zahl der Abschnitte damals (falls sich die Einteilung ändert).
 */
@Serializable
data class ReadMark(val uri: String, val name: String, val chunk: Int, val total: Int, val updated: Long = 0)

/**
 * Die gemerkten Stellen aller Texte (ein Eintrag je Text; ein Wechsel zu einem anderen Text lässt die anderen Marken stehen).
 * Regeln: Ganz am Anfang oder am Ende des Textes gibt es keine Marke (sie wird gelöscht).
 */
class ReadMarks(initial: List<ReadMark> = emptyList()) {
    private val marks = LinkedHashMap<String, ReadMark>().apply { initial.forEach { put(it.uri, it) } }

    /** Alle Marken, die zuletzt gelesene zuerst. */
    fun all(): List<ReadMark> = marks.values.sortedByDescending { it.updated }

    fun get(uri: String): ReadMark? = marks[uri]

    /** Merkt die Stelle. Am Anfang ([chunk] <= 0) oder beim Ende ([finished]) wird die Marke stattdessen gelöscht. */
    fun update(uri: String, name: String, chunk: Int, total: Int, now: Long, finished: Boolean = false) {
        if (finished || chunk <= 0 || chunk >= total) {
            marks.remove(uri)
            return
        }
        marks[uri] = ReadMark(uri, name, chunk, total, now)
        while (marks.size > MAX_MARKS) marks.remove(all().last().uri)
    }

    fun remove(uri: String) {
        marks.remove(uri)
    }

    fun toJson(): String = AppJson.encodeToString(ListSerializer(ReadMark.serializer()), marks.values.toList())

    companion object {
        const val MAX_MARKS = 50

        /** Liest gespeicherte Marken; kaputte Dateien ergeben eine leere Liste statt eines Fehlers. */
        fun fromJson(text: String?): ReadMarks = try {
            ReadMarks(if (text.isNullOrBlank()) emptyList() else AppJson.decodeFromString(ListSerializer(ReadMark.serializer()), text))
        } catch (_: Exception) {
            ReadMarks()
        }

        /** Abschnitt, bei dem es weitergeht, falls der Text jetzt [newTotal] Abschnitte hat (sonst entsprechend umgerechnet). */
        fun restoreIndex(mark: ReadMark, newTotal: Int): Int {
            if (newTotal <= 0) return 0
            val index = if (mark.total == newTotal || mark.total <= 0) mark.chunk else (mark.chunk.toLong() * newTotal / mark.total).toInt()
            return index.coerceIn(0, newTotal - 1)
        }
    }
}
