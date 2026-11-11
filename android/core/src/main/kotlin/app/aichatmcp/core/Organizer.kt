package app.aichatmcp.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/*
 * Organizer: Todos (gemeinsamer Speicher für den MCP-Server und das Tagesupdate), Datumswörter und der Text des Tagesupdates.
 * Planung und Entscheidungen: docs/ORGANIZER.md. Das kleine Modell schreibt nur Kurzformen ("tomorrow", "monday"), gerechnet wird hier.
 */

@Serializable
data class Todo(
    val id: Int,
    val text: String,
    /** Fälligkeitsdatum (ISO) oder null. */
    val due: String? = null,
    val done: Boolean = false,
    val created: String = "",
) {
    val dueDate: LocalDate? get() = due?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/** Todos in einer JSON-Datei (Liste). Ids steigen fortlaufend und bleiben stabil, auch wenn Einträge erledigt sind. */
class TodoStore(private val file: File) {
    private val lock = Any()

    fun all(): List<Todo> = synchronized(lock) { read() }

    /** Offene Todos: überfällige zuerst, dann nach Fälligkeit, dann ohne Datum, jeweils nach Anlage. */
    fun open(): List<Todo> = all().filter { !it.done }.sortedWith(compareBy({ it.dueDate ?: LocalDate.MAX }, { it.id }))

    fun add(text: String, due: LocalDate?, today: LocalDate = LocalDate.now()): Todo = synchronized(lock) {
        val list = read()
        val todo = Todo((list.maxOfOrNull { it.id } ?: 0) + 1, text.trim(), due?.toString(), false, today.toString())
        write(list + todo)
        todo
    }

    /**
     * Markiert ein offenes Todo als erledigt. [ref] ist die Nummer oder ein Teil des Textes (ohne Groß-/Kleinschreibung, muss eindeutig sein).
     * Fehler als [ToolError] mit englischem Text für das Modell.
     */
    fun complete(ref: String): Todo = synchronized(lock) {
        val list = read()
        val open = list.filter { !it.done }
        val key = ref.trim().trimStart('#').trimEnd('.')
        if (key.isEmpty()) throw ToolError("Say which to-do is done (its number or part of its text).")
        val target = key.toIntOrNull()?.let { id -> open.firstOrNull { it.id == id } ?: throw ToolError("There is no open to-do number $id.") }
            ?: open.filter { it.text.contains(key, ignoreCase = true) }.let { hits ->
                when (hits.size) {
                    0 -> throw ToolError("No open to-do matches '$key'.")
                    1 -> hits[0]
                    else -> throw ToolError("More than one to-do matches '$key': " + hits.joinToString("; ") { "${it.id}. ${it.text}" } + ". Use the number.")
                }
            }
        val done = target.copy(done = true)
        write(list.map { if (it.id == target.id) done else it })
        done
    }

    /** Löscht ein Todo endgültig (für die Oberfläche). */
    fun remove(id: Int): Boolean = synchronized(lock) {
        val list = read()
        (list.any { it.id == id }).also { if (it) write(list.filter { t -> t.id != id }) }
    }

    private fun read(): List<Todo> = try {
        if (file.isFile) AppJson.decodeFromString(ListSerializer(Todo.serializer()), file.readText(Charsets.UTF_8)) else emptyList()
    } catch (_: Exception) {
        emptyList() // beschädigte Datei: nicht abstürzen (die nächste Änderung überschreibt sie)
    }

    private fun write(list: List<Todo>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(AppJson.encodeToString(ListSerializer(Todo.serializer()), list), Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8); tmp.delete() }
    }
}

/** Datumswörter für Aufgaben und Termine (Zukunft): today, tomorrow, Wochentage, 2026-11-25, 25.11., in 3 days. Englisch und Deutsch. */
object DateWords {
    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "montag" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "dienstag" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "mittwoch" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY, "donnerstag" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "freitag" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "samstag" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sonntag" to DayOfWeek.SUNDAY,
    )

    /** Leere Eingabe und "none"/"-" bedeuten: kein Datum. */
    fun parseOptional(text: String?, today: LocalDate): LocalDate? {
        val word = (text ?: "").trim().lowercase()
        return if (word.isEmpty() || word in setOf("none", "-", "no", "keins", "kein", "ohne", "null")) null else parse(text, today)
    }

    fun parse(text: String?, today: LocalDate): LocalDate {
        val raw = (text ?: "").trim()
        val word = raw.lowercase().trimEnd('.').split(Regex("\\s+")).joinToString(" ")
        when (word) {
            "today", "heute" -> return today
            "tomorrow", "morgen" -> return today.plusDays(1)
            "day after tomorrow", "übermorgen", "uebermorgen" -> return today.plusDays(2)
        }
        WEEKDAYS[word.removePrefix("on ").removePrefix("am ").removePrefix("next ").removePrefix("nächsten ").removePrefix("naechsten ")]?.let { day ->
            val ahead = (day.value - today.dayOfWeek.value + 7) % 7
            return today.plusDays(if (ahead == 0) 7L else ahead.toLong()) // der nächste solche Tag, nie heute
        }
        Regex("in (\\d{1,3}) (days?|tagen?)").matchEntire(word)?.let { return today.plusDays(it.groupValues[1].toLong()) }
        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matchEntire(word)?.let { return date(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), raw) }
        Regex("(\\d{1,2})\\.\\s*(\\d{1,2})\\.?\\s*(\\d{2,4})?").matchEntire(raw.lowercase())?.let { m ->
            val month = m.groupValues[2].toInt()
            val day = m.groupValues[1].toInt()
            if (m.groupValues[3].isNotEmpty()) {
                var y = m.groupValues[3].toInt()
                if (y < 100) y += 2000
                return date(y, month, day, raw)
            }
            val thisYear = date(today.year, month, day, raw)
            return if (thisYear.isBefore(today)) date(today.year + 1, month, day, raw) else thisYear // ohne Jahr: das nächste Mal
        }
        throw ToolError("Date '$raw' not understood. Allowed: today, tomorrow, day after tomorrow, a weekday (monday), in 3 days, 2026-11-25, 25.11.")
    }

    private fun date(y: Int, m: Int, d: Int, raw: String): LocalDate =
        try { LocalDate.of(y, m, d) } catch (_: Exception) { throw ToolError("Date '$raw' does not exist.") }
}

private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val PREVIEW_DAY = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", java.util.Locale.GERMAN)

/** MCP-Server `organizer`: Todos. Namen und Beschreibungen englisch (kleine Modelle folgen englischen Anweisungen besser). */
class OrganizerServer(
    name: String, private val todos: TodoStore,
    /** Gerätekalender; ohne ihn gibt es die Werkzeuge cal_add und cal_list nicht. */
    private val calendar: CalendarBackend? = null,
    /** Uhr-App des Geräts; ohne sie gibt es die Werkzeuge alarm_set und timer_set nicht. */
    private val clock: ClockBackend? = null,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val today: () -> LocalDate = { now().toLocalDate() }, // zuletzt, damit ein Lambda am Ende (Tests) das Datum setzt
) : LocalServer(name) {
    override val tools = listOf(
        tool(
            "todo_add", "Adds a to-do to the user's list, optionally with a due date.",
            listOf(
                Triple("text", "string", "What has to be done"),
                Triple("due", "string", "Optional due date: today, tomorrow, a weekday (monday), 2026-11-25 or 25.11."),
            ),
            listOf("text"), readOnly = false,
        ),
        tool("todo_list", "Lists the open to-dos with their numbers and due dates.", emptyList(), emptyList()),
        tool(
            "todo_done", "Marks a to-do as done.",
            listOf(Triple("item", "string", "The number of the to-do, or a part of its text")),
            listOf("item"), readOnly = false,
        ),
    ) + (if (calendar == null) emptyList() else listOf(
        tool(
            "cal_add", "Adds an appointment to the user's calendar. Without a time it is an all-day appointment.",
            listOf(
                Triple("title", "string", "What the appointment is"),
                Triple("date", "string", "today, tomorrow, a weekday (monday), 2026-11-25 or 25.11."),
                Triple("time", "string", "Optional start time, e.g. 14:30 or 2pm"),
                Triple("minutes", "integer", "Optional length in minutes, default 60"),
            ),
            listOf("title", "date"), readOnly = false,
        ),
        tool(
            "cal_list", "Lists the appointments of a day (or the next days) from the user's calendar.",
            listOf(
                Triple("date", "string", "Optional first day, default today"),
                Triple("days", "integer", "Optional number of days, default 1"),
            ),
            emptyList(),
        ),
    )) + (if (clock == null) emptyList() else listOf(
        tool(
            "alarm_set", "Sets an alarm clock on the user's phone for the next time it is that time (today, or tomorrow if it has passed).",
            listOf(
                Triple("time", "string", "When it should ring, e.g. 7:30 or 6pm"),
                Triple("label", "string", "Optional name of the alarm"),
            ),
            listOf("time"), readOnly = false,
        ),
        tool(
            "timer_set", "Starts a countdown timer on the user's phone.",
            listOf(
                Triple("duration", "string", "How long, e.g. 10 (minutes), 10 min, 45 s, 1h30"),
                Triple("label", "string", "Optional name of the timer"),
            ),
            listOf("duration"), readOnly = false,
        ),
    ))
    override val handlers = mapOf<String, (Map<String, Any?>) -> String>("todo_add" to ::add, "todo_list" to ::list, "todo_done" to ::done) +
        (if (calendar == null) emptyMap() else mapOf("cal_add" to ::calAdd, "cal_list" to ::calList)) +
        (if (clock == null) emptyMap() else mapOf("alarm_set" to ::alarmSet, "timer_set" to ::timerSet))

    /** Der Tag, an dem ein Wecker auf [time] das nächste Mal klingelt: heute, wenn die Zeit noch kommt, sonst morgen. */
    private fun alarmDay(time: LocalTime): LocalDate {
        val n = now()
        return if (n.toLocalDate().atTime(time).isAfter(n)) n.toLocalDate() else n.toLocalDate().plusDays(1)
    }

    private fun label(arguments: Map<String, Any?>) = arguments["label"]?.toString().orEmpty().trim().take(60)

    private fun alarmTime(arguments: Map<String, Any?>): LocalTime {
        if (arguments["time"]?.toString().isNullOrBlank()) throw ToolError("The time of the alarm is missing.")
        return TimeWords.parse(arguments["time"]?.toString())
    }

    private fun alarmSet(arguments: Map<String, Any?>): String {
        val time = alarmTime(arguments)
        val label = label(arguments)
        clock!!.setAlarm(time, label)
        val day = alarmDay(time)
        val word = if (day == now().toLocalDate()) "today" else "tomorrow"
        return "Alarm set for ${time.format(HOUR)} $word ($day)" + (if (label.isNotEmpty()) ", named '$label'" else "") + "."
    }

    private fun timerSet(arguments: Map<String, Any?>): String {
        if (arguments["duration"]?.toString().isNullOrBlank()) throw ToolError("The length of the timer is missing.")
        val seconds = DurationWords.parse(arguments["duration"]?.toString())
        val label = label(arguments)
        clock!!.setTimer(seconds, label)
        return "Timer set for ${DurationWords.describe(seconds)}" + (if (label.isNotEmpty()) ", named '$label'" else "") + "."
    }

    /** Eine geplante Eintragung mit aufgelösten Werten (Datum und Uhrzeit sind schon geprüft). */
    private class Planned(val title: String, val start: LocalDateTime, val minutes: Int, val allDay: Boolean)

    private fun plan(arguments: Map<String, Any?>): Planned {
        val title = arguments["title"]?.toString().orEmpty().trim()
        if (title.isEmpty()) throw ToolError("The title of the appointment is missing.")
        val dateText = arguments["date"]?.toString().orEmpty()
        if (dateText.isBlank()) throw ToolError("The date of the appointment is missing.")
        val date = DateWords.parse(dateText, today())
        val time = TimeWords.parseOptional(arguments["time"]?.toString())
        val minutes = arguments["minutes"]?.toString()?.toDoubleOrNull()?.toInt()?.coerceIn(1, 24 * 60) ?: 60
        val start = date.atTime(time ?: LocalTime.MIDNIGHT)
        if (time != null && start.isBefore(now().minusMinutes(5))) {
            throw ToolError("That time has already passed ($date ${time.format(HOUR)}). Check the date and ask the user.")
        }
        if (time == null && date.isBefore(today())) throw ToolError("That day has already passed ($date). Check the date and ask the user.")
        return Planned(title, start, minutes, time == null)
    }

    /** Was cal_add täte, in Klartext mit Wochentag und Datum: damit der Nutzer ein falsch verstandenes "morgen" in der Rückfrage sieht. */
    override fun preview(name: String, arguments: Map<String, Any?>): String? {
        if (clock != null && name == "alarm_set") {
            val time = try { alarmTime(arguments) } catch (e: ToolError) { return e.message }
            val day = alarmDay(time)
            val word = if (day == now().toLocalDate()) "heute" else "morgen"
            return "Wecker: ${time.format(HOUR)} Uhr, $word (${day.format(PREVIEW_DAY)})" + label(arguments).let { if (it.isEmpty()) "" else "\nName: $it" }
        }
        if (clock != null && name == "timer_set") {
            val seconds = try { DurationWords.parse(arguments["duration"]?.toString()) } catch (e: ToolError) { return e.message }
            val ends = now().plusSeconds(seconds.toLong())
            return "Timer: ${DurationWords.describe(seconds)} (läuft ab um ${ends.format(HOUR)} Uhr)" + label(arguments).let { if (it.isEmpty()) "" else "\nName: $it" }
        }
        if (name != "cal_add" || calendar == null) return null
        val p = try { plan(arguments) } catch (e: ToolError) { return e.message }
        val day = p.start.toLocalDate().format(PREVIEW_DAY)
        return if (p.allDay) "Termin: ${p.title}\n$day, ganztägig" else "Termin: ${p.title}\n$day, ${p.start.format(HOUR)} Uhr (${p.minutes} Min.)"
    }

    private fun calAdd(arguments: Map<String, Any?>): String {
        val p = plan(arguments)
        val name = calendar!!.add(p.title, p.start, p.minutes, p.allDay)
        val when_ = if (p.allDay) "all day" else "at ${p.start.format(HOUR)} (${p.minutes} min)"
        return "Added to the calendar '$name': ${p.title} on ${p.start.toLocalDate()} $when_."
    }

    private fun calList(arguments: Map<String, Any?>): String {
        val from = DateWords.parseOptional(arguments["date"]?.toString(), today()) ?: today()
        val days = arguments["days"]?.toString()?.toDoubleOrNull()?.toInt()?.coerceIn(1, 31) ?: 1
        val events = calendar!!.events(from, days)
        if (events.isEmpty()) return if (days == 1) "No appointments on $from." else "No appointments from $from for $days days."
        return events.joinToString("\n") { CalendarText.line(it) }
    }

    private fun add(arguments: Map<String, Any?>): String {
        val text = arguments["text"]?.toString().orEmpty().trim()
        if (text.isEmpty()) throw ToolError("The text of the to-do is missing.")
        val due = DateWords.parseOptional(arguments["due"]?.toString(), today())
        val todo = todos.add(text, due, today())
        return "Added to-do ${todo.id}: ${todo.text}" + (due?.let { " (due ${describe(it)})" } ?: "") + "."
    }

    private fun list(@Suppress("UNUSED_PARAMETER") arguments: Map<String, Any?>): String {
        val open = todos.open()
        if (open.isEmpty()) return "No open to-dos."
        return open.joinToString("\n") { "${it.id}. ${it.text}" + (it.dueDate?.let { d -> " (due ${describe(d)})" } ?: "") }
    }

    private fun done(arguments: Map<String, Any?>): String {
        val todo = todos.complete(arguments["item"]?.toString().orEmpty())
        return "Marked as done: ${todo.id}. ${todo.text}."
    }

    private fun describe(due: LocalDate): String {
        val now = today()
        val days = java.time.temporal.ChronoUnit.DAYS.between(now, due)
        return when {
            days == 0L -> "today"
            days == 1L -> "tomorrow"
            days < 0 -> "$due, overdue"
            else -> due.toString()
        }
    }
}

/** Text des Tagesupdates (von der App zusammengestellt, ohne Modell). Sprache nach der Antwortsprache des Companions. */
object DailyUpdate {
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    private const val MAX_LISTED = 8

    /** Wurde das Tagesupdate heute schon gezeigt (von irgendeinem Companion)? Dann zeigen es die anderen nicht noch einmal. */
    fun alreadyShown(shownDays: Map<String, String>, today: LocalDate): Boolean = today.toString() in shownDays.values
    private val DE_DAYS = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
    private val EN_DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    /** [events]: die heutigen Termine; null = Kalender nicht lesbar (dann steht nichts dazu im Update). */
    fun text(open: List<Todo>, today: LocalDate, german: Boolean, events: List<CalendarEvent>? = null): String {
        val day = (if (german) DE_DAYS else EN_DAYS)[today.dayOfWeek.value - 1]
        val date = if (german) today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) else today.toString()
        val sorted = open.sortedWith(compareBy({ it.dueDate ?: LocalDate.MAX }, { it.id }))
        val lines = mutableListOf(if (german) "Tagesupdate · $day, $date" else "Daily update · $day, $date")
        if (events != null) {
            if (events.isEmpty()) {
                lines += if (german) "Keine Termine heute." else "No appointments today."
            } else {
                lines += if (german) "Termine heute (${events.size}):" else "Appointments today (${events.size}):"
                events.take(MAX_LISTED).forEach { e ->
                    val time = if (e.allDay) (if (german) "ganztägig" else "all day") else e.start.format(CLOCK)
                    lines += "$time · ${e.title}"
                }
                if (events.size > MAX_LISTED) lines += if (german) "… und ${events.size - MAX_LISTED} weitere." else "… and ${events.size - MAX_LISTED} more."
            }
        }
        if (sorted.isEmpty()) {
            lines += if (german) "Keine offenen Todos." else "No open to-dos."
        } else {
            lines += if (german) "Offene Todos (${sorted.size}):" else "Open to-dos (${sorted.size}):"
            sorted.take(MAX_LISTED).forEach { t ->
                val d = t.dueDate
                val mark = when {
                    d == null -> ""
                    d.isBefore(today) -> if (german) " · überfällig seit ${d.format(DateTimeFormatter.ofPattern("dd.MM."))}" else " · overdue since $d"
                    d == today -> if (german) " · heute" else " · today"
                    d == today.plusDays(1) -> if (german) " · morgen" else " · tomorrow"
                    else -> " · " + (if (german) d.format(DateTimeFormatter.ofPattern("dd.MM.")) else d.toString())
                }
                lines += "${t.id}. ${t.text}$mark"
            }
            if (sorted.size > MAX_LISTED) lines += if (german) "… und ${sorted.size - MAX_LISTED} weitere." else "… and ${sorted.size - MAX_LISTED} more."
        }
        return lines.joinToString("\n")
    }
}
