package app.aichatmcp.core

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Schein-Kalender im Speicher. */
private class FakeCalendar(var allowed: Boolean = true) : CalendarBackend {
    val added = mutableListOf<CalendarEvent>()
    override fun canRead() = allowed
    override fun add(title: String, start: LocalDateTime, minutes: Int, allDay: Boolean): String {
        if (!allowed) throw ToolError("Calendar access is not allowed.")
        added += CalendarEvent(title, start, start.plusMinutes(minutes.toLong()), allDay)
        return "Privat"
    }
    override fun events(from: LocalDate, days: Int): List<CalendarEvent> {
        if (!allowed) throw ToolError("Calendar access is not allowed.")
        return added.filter { !it.start.toLocalDate().isBefore(from) && it.start.toLocalDate().isBefore(from.plusDays(days.toLong())) }.sortedBy { it.start }
    }
}

class CalendarTest {
    // Montag, 9. November 2026, 10:00
    private val today = LocalDate.of(2026, 11, 9)
    private val now = today.atTime(10, 0)

    private fun server(calendar: CalendarBackend? = FakeCalendar()): OrganizerServer {
        val store = TodoStore(File(kotlin.io.path.createTempDirectory("todos").toFile(), "todos.json"))
        return OrganizerServer("organizer", store, calendar, now = { now }, today = { today })
    }

    private fun call(s: OrganizerServer, tool: String, vararg args: Pair<String, String>) = s.callTool(tool, args.toMap(), 5.0)

    @Test
    fun timeWordsUnderstandCommonForms() {
        assertEquals(LocalTime.of(14, 30), TimeWords.parse("14:30"))
        assertEquals(LocalTime.of(14, 30), TimeWords.parse("14.30"))
        assertEquals(LocalTime.of(9, 0), TimeWords.parse("9"))
        assertEquals(LocalTime.of(14, 0), TimeWords.parse("14 Uhr"))
        assertEquals(LocalTime.of(14, 0), TimeWords.parse("2pm"))
        assertEquals(LocalTime.of(14, 30), TimeWords.parse("2:30 PM"))
        assertEquals(LocalTime.of(0, 15), TimeWords.parse("12:15am"))
        assertEquals(LocalTime.NOON, TimeWords.parse("noon"))
        assertEquals(LocalTime.NOON, TimeWords.parse("Mittag"))
        assertEquals(LocalTime.MIDNIGHT, TimeWords.parse("midnight"))
        assertNull(TimeWords.parseOptional(""))
        assertNull(TimeWords.parseOptional("all day"))
        assertNull(TimeWords.parseOptional(null))
        assertFailsWith<ToolError> { TimeWords.parse("25:00") }
        assertTrue("not understood" in assertFailsWith<ToolError> { TimeWords.parse("später") }.message!!)
    }

    @Test
    fun serverHasNoCalendarToolsWithoutACalendar() {
        assertEquals(listOf("todo_add", "todo_list", "todo_done"), server(null).listTools(1.0).map { it.name })
        assertEquals(listOf("todo_add", "todo_list", "todo_done", "cal_add", "cal_list"), server().listTools(1.0).map { it.name })
        val tools = server().listTools(1.0).associateBy { it.name }
        assertFalse(tools.getValue("cal_add").readOnly)
        assertTrue(tools.getValue("cal_list").readOnly)
    }

    @Test
    fun addsAndListsAppointments() {
        val calendar = FakeCalendar()
        val s = server(calendar)
        assertEquals("Added to the calendar 'Privat': Dentist on 2026-11-10 at 14:00 (60 min)." to false, call(s, "cal_add", "title" to "Dentist", "date" to "tomorrow", "time" to "14:00"))
        assertEquals("Added to the calendar 'Privat': Holiday on 2026-11-13 all day." to false, call(s, "cal_add", "title" to "Holiday", "date" to "friday"))
        assertEquals("Added to the calendar 'Privat': Call on 2026-11-09 at 16:30 (15 min)." to false, call(s, "cal_add", "title" to "Call", "date" to "today", "time" to "4:30pm", "minutes" to "15"))
        assertEquals(LocalDateTime.of(2026, 11, 10, 15, 0), calendar.added[0].end)
        assertTrue(calendar.added[1].allDay)
        assertEquals("2026-11-09 16:30-16:45 Call" to false, call(s, "cal_list"))
        assertEquals("2026-11-10 14:00-15:00 Dentist" to false, call(s, "cal_list", "date" to "tomorrow"))
        assertEquals(
            "2026-11-09 16:30-16:45 Call\n2026-11-10 14:00-15:00 Dentist\n2026-11-13 all day Holiday" to false,
            call(s, "cal_list", "days" to "7"),
        )
        assertEquals("No appointments on 2026-11-11." to false, call(s, "cal_list", "date" to "2026-11-11"))
    }

    @Test
    fun badInputIsReportedToTheModelAndNothingIsWritten() {
        val calendar = FakeCalendar()
        val s = server(calendar)
        assertEquals("The title of the appointment is missing." to true, call(s, "cal_add", "title" to " ", "date" to "today"))
        assertEquals("The date of the appointment is missing." to true, call(s, "cal_add", "title" to "X", "date" to ""))
        assertTrue(call(s, "cal_add", "title" to "X", "date" to "someday").let { it.second && "not understood" in it.first })
        assertTrue(call(s, "cal_add", "title" to "X", "date" to "today", "time" to "9:00").let { it.second && "already passed" in it.first }) // jetzt ist 10:00
        assertTrue(call(s, "cal_add", "title" to "X", "date" to "2026-11-01").let { it.second && "already passed" in it.first })
        assertTrue(call(s, "cal_add", "title" to "X", "date" to "today", "time" to "quarter past").second)
        assertEquals(emptyList(), calendar.added)
        assertTrue(call(s, "cal_add", "title" to "Heute ganztägig", "date" to "today").let { !it.second }) // ganztägig heute ist erlaubt
    }

    @Test
    fun missingPermissionComesBackAsAnError() {
        val s = server(FakeCalendar(allowed = false))
        assertEquals("Calendar access is not allowed." to true, call(s, "cal_list"))
        assertEquals("Calendar access is not allowed." to true, call(s, "cal_add", "title" to "X", "date" to "tomorrow", "time" to "10:00"))
    }

    @Test
    fun previewShowsTheResolvedDayForTheConfirmation() {
        val s = server()
        assertEquals("Termin: Zahnarzt\nDienstag, 10.11.2026, 14:00 Uhr (60 Min.)", s.preview("cal_add", mapOf("title" to "Zahnarzt", "date" to "tomorrow", "time" to "14:00")))
        assertEquals("Termin: Urlaub\nFreitag, 13.11.2026, ganztägig", s.preview("cal_add", mapOf("title" to "Urlaub", "date" to "friday")))
        assertTrue(s.preview("cal_add", mapOf("title" to "X", "date" to "someday"))!!.contains("not understood"))
        assertNull(s.preview("cal_list", emptyMap()))
        assertNull(server(null).preview("cal_add", mapOf("title" to "X", "date" to "today")))
    }

    @Test
    fun registryAsksTheServerForThePreview() {
        val reg = ToolRegistry()
        reg.addServer("organizer", server(), exposeRaw = false)
        reg.addAliases(
            aliasJson("""{"cal-add":{"server":"organizer","tool":"cal_add","params":["title","date","time","minutes"],"confirm":true,"example":"[[mcp:cal-add|Dentist|tomorrow|14:00]]"}}"""),
        )
        assertEquals(emptyList(), reg.errors)
        val preview = reg.preview(findCall("[[mcp:cal-add|Zahnarzt|tomorrow|14:00]]")!!)
        assertEquals("Termin: Zahnarzt\nDienstag, 10.11.2026, 14:00 Uhr (60 Min.)", preview)
        assertNull(reg.preview(findCall("[[mcp:nix|a]]")!!))
        assertTrue("[[mcp:cal-add|Dentist|tomorrow|14:00]]" in reg.promptSection(listOf("cal-add")))
    }

    @Test
    fun dailyUpdateListsTodaysAppointmentsFirst() {
        val events = listOf(
            CalendarEvent("Zahnarzt", today.atTime(14, 0), today.atTime(15, 0)),
            CalendarEvent("Geburtstag Oma", today.atStartOfDay(), today.plusDays(1).atStartOfDay(), allDay = true),
        )
        val text = DailyUpdate.text(listOf(Todo(1, "Milch kaufen")), today, german = true, events = events)
        assertEquals(
            "Tagesupdate · Montag, 09.11.2026\nTermine heute (2):\n14:00 · Zahnarzt\nganztägig · Geburtstag Oma\nOffene Todos (1):\n1. Milch kaufen",
            text,
        )
        assertTrue(DailyUpdate.text(emptyList(), today, true, emptyList()).contains("Keine Termine heute."))
        assertFalse(DailyUpdate.text(emptyList(), today, true, null).contains("Termine")) // Kalender nicht lesbar: nichts erwähnen
        assertTrue(DailyUpdate.text(emptyList(), today, false, events.take(1)).contains("Appointments today (1):\n14:00 · Zahnarzt"))
        val many = (1..9).map { CalendarEvent("T$it", today.atTime(8 + it, 0), today.atTime(9 + it, 0)) }
        assertTrue(DailyUpdate.text(emptyList(), today, true, many).contains("… und 1 weitere."))
    }

    @Test
    fun reminderMentionsTheNumberOfAppointmentsButNoContent() {
        assertEquals("Keine offenen Todos. 2 Termine heute. Zeit für dein Update.", Reminder.text(emptyList(), today, true, appointments = 2))
        assertEquals("Keine offenen Todos. 1 Termin heute. Zeit für dein Update.", Reminder.text(emptyList(), today, true, appointments = 1))
        assertEquals("No open to-dos. 3 appointments today. Time for your update.", Reminder.text(emptyList(), today, false, appointments = 3))
        assertEquals("Keine offenen Todos. Zeit für dein Update.", Reminder.text(emptyList(), today, true)) // wie bisher ohne Kalender
    }

    @Test
    fun factoryPassesTheCalendarToTheOrganizer() {
        val config = aliasJson("""{"builtin":"organizer"}""")
        val store = TodoStore(File(kotlin.io.path.createTempDirectory("todos").toFile(), "todos.json"))
        val withCal = DefaultServerFactory(todos = store, calendar = FakeCalendar()).create("organizer", config)
        assertTrue(withCal.listTools(1.0).any { it.name == "cal_add" })
        assertFalse(DefaultServerFactory(todos = store).create("organizer", config).listTools(1.0).any { it.name == "cal_add" })
    }
}
