package app.aichatmcp.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/*
 * Kalender (Phase 3 des Organizers, docs/ORGANIZER.md): Termine für den Server `organizer` (cal_add, cal_list) und das Tagesupdate.
 * Der Gerätekalender selbst (CalendarContract) liegt in der App (`DeviceCalendar`); hier steht nur die testbare Logik.
 */

data class CalendarEvent(val title: String, val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean = false)

/** Der Kalender des Geräts (in Tests ein Schein-Kalender). Fehler als [ToolError] mit englischem Text für das Modell. */
interface CalendarBackend {
    /** Darf die App den Kalender lesen? (Für das Tagesupdate: ohne Berechtigung wird einfach nichts angezeigt.) */
    fun canRead(): Boolean

    /** Legt den Termin an und liefert den Namen des Kalenders. */
    fun add(title: String, start: LocalDateTime, minutes: Int, allDay: Boolean): String

    /** Die Termine ab [from] für [days] Tage (Ganztägige am jeweiligen Tag), nach Beginn sortiert. */
    fun events(from: LocalDate, days: Int): List<CalendarEvent>
}

/** Uhrzeiten für Termine: 14:30, 14.30, 14, "14 Uhr", 2pm, 2:30 pm, noon, midnight (englisch und deutsch). */
object TimeWords {
    private val WORDS = mapOf("noon" to LocalTime.NOON, "midday" to LocalTime.NOON, "mittag" to LocalTime.NOON, "midnight" to LocalTime.MIDNIGHT, "mitternacht" to LocalTime.MIDNIGHT)

    /** Leere Eingabe und "none"/"all day"/"ganztägig" bedeuten: keine Uhrzeit (ganztägiger Termin). */
    fun parseOptional(text: String?): LocalTime? {
        val word = (text ?: "").trim().lowercase()
        return if (word.isEmpty() || word in setOf("none", "-", "no", "all day", "allday", "all-day", "ganztägig", "ganztaegig", "ohne", "null")) null else parse(text)
    }

    fun parse(text: String?): LocalTime {
        val raw = (text ?: "").trim()
        val word = raw.lowercase().removeSuffix("uhr").trim()
        WORDS[word]?.let { return it }
        Regex("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)").matchEntire(word)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h in 1..12 && min in 0..59) return LocalTime.of(h % 12 + if (m.groupValues[3] == "pm") 12 else 0, min)
        }
        Regex("(\\d{1,2})(?:[:.](\\d{2}))?").matchEntire(word)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h in 0..23 && min in 0..59) return LocalTime.of(h, min)
        }
        throw ToolError("Time '$raw' not understood. Allowed: 14:30, 9, 2pm, noon.")
    }
}

object CalendarText {
    private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

    /** Eine Zeile für das Modell: "2026-11-12 14:00-15:00 Dentist" bzw. "2026-11-12 all day Dentist". */
    fun line(e: CalendarEvent): String {
        val day = e.start.toLocalDate()
        return if (e.allDay) "$day all day ${e.title}" else "$day ${e.start.format(HOUR)}-${e.end.format(HOUR)} ${e.title}"
    }
}
