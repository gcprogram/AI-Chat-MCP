package app.aichatmcp.core

import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeClock : ClockBackend {
    val alarms = mutableListOf<Pair<LocalTime, String>>()
    val timers = mutableListOf<Pair<Int, String>>()
    override fun setAlarm(time: LocalTime, label: String) { alarms += time to label }
    override fun setTimer(seconds: Int, label: String) { timers += seconds to label }
}

class ClockTest {
    // Montag, 9. November 2026, 10:00
    private val today = LocalDate.of(2026, 11, 9)
    private val now = today.atTime(10, 0)

    private fun server(clock: ClockBackend? = FakeClock()): OrganizerServer {
        val store = TodoStore(File(kotlin.io.path.createTempDirectory("todos").toFile(), "todos.json"))
        return OrganizerServer("organizer", store, clock = clock, now = { now }, today = { today })
    }

    private fun call(s: OrganizerServer, tool: String, vararg args: Pair<String, String>) = s.callTool(tool, args.toMap(), 5.0)

    @Test
    fun durationsInCommonForms() {
        assertEquals(600, DurationWords.parse("10"))
        assertEquals(600, DurationWords.parse("10 min"))
        assertEquals(600, DurationWords.parse("10 Minuten"))
        assertEquals(600, DurationWords.parse("10m"))
        assertEquals(45, DurationWords.parse("45 s"))
        assertEquals(45, DurationWords.parse("45 Sekunden"))
        assertEquals(3600, DurationWords.parse("1h"))
        assertEquals(7200, DurationWords.parse("2 hours"))
        assertEquals(5400, DurationWords.parse("1h30"))
        assertEquals(5400, DurationWords.parse("1 Std 30 min"))
        assertEquals(5400, DurationWords.parse("1:30"))
        assertEquals(90, DurationWords.parse("1,5 min"))
        assertEquals(DurationWords.MAX_SECONDS, DurationWords.parse("24h"))
    }

    @Test
    fun badDurationsAreErrorsForTheModel() {
        for (bad in listOf("", "bald", "10 parsecs", "0", "25h", "10 20", "-5")) {
            assertFailsWith<ToolError>(bad) { DurationWords.parse(bad) }
        }
        assertTrue("not understood" in assertFailsWith<ToolError> { DurationWords.parse("bald") }.message!!)
        assertTrue("between 1 second and 24 hours" in assertFailsWith<ToolError> { DurationWords.parse("0") }.message!!)
    }

    @Test
    fun describesDurations() {
        assertEquals("10 min", DurationWords.describe(600))
        assertEquals("1 h 30 min", DurationWords.describe(5400))
        assertEquals("1 h 5 s", DurationWords.describe(3605))
    }

    @Test
    fun serverHasClockToolsOnlyWithAClock() {
        assertFalse(server(null).listTools(1.0).any { it.name == "alarm_set" })
        val names = server().listTools(1.0).map { it.name }
        assertTrue("alarm_set" in names && "timer_set" in names)
        assertTrue(server().listTools(1.0).filter { it.name.endsWith("_set") }.none { it.readOnly })
    }

    @Test
    fun alarmRingsTodayIfTheTimeIsAheadElseTomorrow() {
        val clock = FakeClock()
        val s = server(clock)
        assertEquals("Alarm set for 11:30 today (2026-11-09)." to false, call(s, "alarm_set", "time" to "11:30"))
        assertEquals("Alarm set for 07:00 tomorrow (2026-11-10), named 'Wake up'." to false, call(s, "alarm_set", "time" to "7", "label" to "Wake up"))
        assertEquals("Alarm set for 10:00 tomorrow (2026-11-10)." to false, call(s, "alarm_set", "time" to "10:00")) // genau jetzt ist schon vorbei
        assertEquals(listOf(LocalTime.of(11, 30) to "", LocalTime.of(7, 0) to "Wake up", LocalTime.of(10, 0) to ""), clock.alarms)
    }

    @Test
    fun timerAndErrors() {
        val clock = FakeClock()
        val s = server(clock)
        assertEquals("Timer set for 10 min, named 'Pasta'." to false, call(s, "timer_set", "duration" to "10 min", "label" to "Pasta"))
        assertEquals("Timer set for 1 h 30 min." to false, call(s, "timer_set", "duration" to "1h30"))
        assertEquals(listOf(600 to "Pasta", 5400 to ""), clock.timers)
        assertEquals("The time of the alarm is missing." to true, call(s, "alarm_set", "time" to " "))
        assertEquals("The length of the timer is missing." to true, call(s, "timer_set", "duration" to ""))
        assertTrue(call(s, "alarm_set", "time" to "later").let { it.second && "not understood" in it.first })
        assertTrue(call(s, "timer_set", "duration" to "soon").second)
        assertEquals(2, clock.timers.size + clock.alarms.size) // Fehler stellen nichts
    }

    @Test
    fun previewForTheConfirmation() {
        val s = server()
        assertEquals("Wecker: 07:00 Uhr, morgen (Dienstag, 10.11.2026)\nName: Aufstehen", s.preview("alarm_set", mapOf("time" to "7:00", "label" to "Aufstehen")))
        assertEquals("Wecker: 18:00 Uhr, heute (Montag, 09.11.2026)", s.preview("alarm_set", mapOf("time" to "6pm")))
        assertEquals("Timer: 10 min (läuft ab um 10:10 Uhr)", s.preview("timer_set", mapOf("duration" to "10")))
        assertTrue(s.preview("timer_set", mapOf("duration" to "soon"))!!.contains("not understood"))
        assertEquals(null, server(null).preview("alarm_set", mapOf("time" to "7")))
    }

    @Test
    fun factoryPassesTheClock() {
        val config = aliasJson("""{"builtin":"organizer"}""")
        val store = TodoStore(File(kotlin.io.path.createTempDirectory("todos").toFile(), "todos.json"))
        assertTrue(DefaultServerFactory(todos = store, clock = FakeClock()).create("organizer", config).listTools(1.0).any { it.name == "timer_set" })
        assertFalse(DefaultServerFactory(todos = store).create("organizer", config).listTools(1.0).any { it.name == "timer_set" })
    }
}
