package app.aichatmcp.core

import java.time.LocalTime

/*
 * Wecker und Timer (Phase 4 des Organizers, docs/ORGANIZER.md): Werkzeuge alarm_set und timer_set des Servers `organizer`.
 * Gestellt werden sie in der Uhr-App des Telefons (AlarmClock-Intents, `DeviceClock` in der App); hier steht nur die testbare Logik.
 */

/** Die Uhr-App des Geräts (in Tests ein Schein-Gerät). Fehler als [ToolError] mit englischem Text für das Modell. */
interface ClockBackend {
    /** Stellt einen Wecker auf die nächste Uhrzeit [time] (heute, sonst morgen); [label] darf leer sein. */
    fun setAlarm(time: LocalTime, label: String)

    /** Startet einen Timer über [seconds] Sekunden. */
    fun setTimer(seconds: Int, label: String)
}

/** Zeitspannen für Timer: 10, 10 min, 45 s, 1h, 1h30, 2 hours, 1:30 (Stunden:Minuten); eine Zahl ohne Einheit sind Minuten. */
object DurationWords {
    const val MAX_SECONDS = 24 * 3600
    private val TOKEN = Regex("\\s*(\\d+(?:[.,]\\d+)?)\\s*([a-zäöü]+)?")

    private fun unitOf(u: String): String? = when (u) {
        "h", "hr", "hrs", "hour", "hours", "std", "stunde", "stunden" -> "h"
        "m", "min", "mins", "minute", "minutes", "minuten" -> "m"
        "s", "sec", "secs", "sek", "second", "seconds", "sekunde", "sekunden" -> "s"
        else -> null
    }

    /** Sekunden; Fehler als [ToolError]. */
    fun parse(text: String?): Int {
        val raw = (text ?: "").trim()
        val word = raw.lowercase()
        val bad = ToolError("Duration '$raw' not understood. Allowed: 10 (minutes), 10 min, 45 s, 1h30, 1:30.")
        if (word.isEmpty()) throw bad
        Regex("(\\d{1,2}):(\\d{2})").matchEntire(word)?.let { return check(it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60) }
        var total = 0.0
        var previous = ""
        var pos = 0
        var count = 0
        while (pos < word.length) {
            val m = TOKEN.matchAt(word, pos) ?: throw bad
            val number = m.groupValues[1].replace(',', '.').toDouble()
            val given = m.groupValues[2]
            // ohne Einheit: Minuten (nach "1h" die Minuten, "1h30"); zwei Zahlen ohne Einheit nacheinander sind unklar
            val unit = if (given.isEmpty()) { if (count > 0 && previous != "h") throw bad else "m" } else unitOf(given) ?: throw bad
            total += number * when (unit) { "h" -> 3600.0; "m" -> 60.0; else -> 1.0 }
            previous = unit
            count++
            pos = m.range.last + 1
        }
        return check(Math.round(total).toInt())
    }

    private fun check(seconds: Int): Int {
        if (seconds < 1 || seconds > MAX_SECONDS) throw ToolError("A timer must last between 1 second and 24 hours.")
        return seconds
    }

    /** "10 minutes", "1 h 30 min", "45 seconds": Klartext für Antwort und Rückfrage. */
    fun describe(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            if (h > 0) "$h h" else null, if (m > 0) "$m min" else null, if (s > 0) "$s s" else null,
        ).joinToString(" ")
    }
}
