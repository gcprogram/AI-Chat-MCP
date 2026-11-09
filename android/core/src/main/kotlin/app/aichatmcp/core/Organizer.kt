package app.aichatmcp.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
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

/** MCP-Server `organizer`: Todos. Namen und Beschreibungen englisch (kleine Modelle folgen englischen Anweisungen besser). */
class OrganizerServer(name: String, private val todos: TodoStore, private val today: () -> LocalDate = { LocalDate.now() }) : LocalServer(name) {
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
    )
    override val handlers = mapOf<String, (Map<String, Any?>) -> String>("todo_add" to ::add, "todo_list" to ::list, "todo_done" to ::done)

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
    private const val MAX_LISTED = 8
    private val DE_DAYS = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
    private val EN_DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    fun text(open: List<Todo>, today: LocalDate, german: Boolean): String {
        val day = (if (german) DE_DAYS else EN_DAYS)[today.dayOfWeek.value - 1]
        val date = if (german) today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) else today.toString()
        val sorted = open.sortedWith(compareBy({ it.dueDate ?: LocalDate.MAX }, { it.id }))
        val lines = mutableListOf(if (german) "Tagesupdate · $day, $date" else "Daily update · $day, $date")
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
