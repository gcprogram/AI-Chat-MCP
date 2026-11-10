package app.aichatmcp.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Tägliche Erinnerung (Push-Nachricht): Uhrzeit lesen, nächsten Zeitpunkt berechnen und den Text der Nachricht bilden.
 * Reine Logik; Alarm und Benachrichtigung selbst liegen in der App (`reminder/`). Planung: docs/ORGANIZER.md.
 */
object Reminder {
    const val DEFAULT_TIME = "08:00"

    /** Liest "8", "8:30", "08:30", "8.30", "8 Uhr", "8:30 Uhr" als Uhrzeit; sonst null. */
    fun parseTime(text: String?): LocalTime? {
        val t = (text ?: "").trim().lowercase().removeSuffix("uhr").trim()
        val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?").matchEntire(t) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }

    /** Uhrzeit wie gespeichert ("08:30"). */
    fun format(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    /** Der nächste Zeitpunkt mit dieser Uhrzeit, der echt nach [now] liegt (heute, wenn sie noch kommt, sonst morgen). */
    fun next(now: LocalDateTime, time: LocalTime): LocalDateTime {
        val today = now.toLocalDate().atTime(time)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    fun title(german: Boolean) = if (german) "Tägliches Update" else "Daily update"

    /**
     * Text der Push-Nachricht: Kurzübersicht der offenen Todos (kein Inhalt der Einträge, damit nichts Persönliches auf dem
     * Sperrbildschirm steht) und der Hinweis aufs Update.
     */
    fun text(open: List<Todo>, today: LocalDate, german: Boolean): String {
        val overdue = open.count { it.dueDate?.isBefore(today) == true }
        val dueToday = open.count { it.dueDate == today }
        val n = open.size
        val summary = if (german) {
            when (n) {
                0 -> "Keine offenen Todos."
                1 -> "1 offenes Todo" + details(overdue, dueToday, true) + "."
                else -> "$n offene Todos" + details(overdue, dueToday, true) + "."
            }
        } else {
            when (n) {
                0 -> "No open to-dos."
                1 -> "1 open to-do" + details(overdue, dueToday, false) + "."
                else -> "$n open to-dos" + details(overdue, dueToday, false) + "."
            }
        }
        return summary + if (german) " Zeit für dein Update." else " Time for your update."
    }

    private fun details(overdue: Int, today: Int, german: Boolean): String {
        val parts = buildList {
            if (overdue > 0) add(if (german) "$overdue überfällig" else "$overdue overdue")
            if (today > 0) add(if (german) "$today heute fällig" else "$today due today")
        }
        return if (parts.isEmpty()) "" else " (" + parts.joinToString(", ") + ")"
    }
}
