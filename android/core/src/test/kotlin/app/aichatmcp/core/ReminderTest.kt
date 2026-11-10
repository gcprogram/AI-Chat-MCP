package app.aichatmcp.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReminderTest {
    private val today = LocalDate.of(2026, 11, 10)

    @Test
    fun timesAreReadInUsualForms() {
        assertEquals(LocalTime.of(8, 0), Reminder.parseTime("8"))
        assertEquals(LocalTime.of(8, 30), Reminder.parseTime("08:30"))
        assertEquals(LocalTime.of(8, 30), Reminder.parseTime("8.30"))
        assertEquals(LocalTime.of(19, 5), Reminder.parseTime(" 19:05 Uhr "))
        assertEquals(LocalTime.of(7, 0), Reminder.parseTime("7 Uhr"))
        assertEquals(LocalTime.of(0, 0), Reminder.parseTime("0:00"))
        assertNull(Reminder.parseTime("24:00"))
        assertNull(Reminder.parseTime("8:60"))
        assertNull(Reminder.parseTime("morgens"))
        assertNull(Reminder.parseTime(""))
        assertNull(Reminder.parseTime(null))
        assertEquals("08:05", Reminder.format(LocalTime.of(8, 5)))
    }

    @Test
    fun nextIsTodayIfStillAheadElseTomorrow() {
        val time = LocalTime.of(8, 0)
        assertEquals(LocalDateTime.of(2026, 11, 10, 8, 0), Reminder.next(LocalDateTime.of(2026, 11, 10, 7, 59, 59), time))
        assertEquals(LocalDateTime.of(2026, 11, 11, 8, 0), Reminder.next(LocalDateTime.of(2026, 11, 10, 8, 0, 0), time)) // genau jetzt: erst morgen
        assertEquals(LocalDateTime.of(2026, 11, 11, 8, 0), Reminder.next(LocalDateTime.of(2026, 11, 10, 21, 0), time))
        assertEquals(LocalDateTime.of(2027, 1, 1, 8, 0), Reminder.next(LocalDateTime.of(2026, 12, 31, 12, 0), time)) // Jahreswechsel
    }

    @Test
    fun textIsAShortSummaryWithoutTheTodoTexts() {
        val todos = listOf(
            Todo(1, "Steuer", today.minusDays(3).toString()), Todo(2, "Zahnarzt", today.toString()), Todo(3, "Arzt anrufen", today.toString()),
            Todo(4, "Milch"),
        )
        assertEquals("4 offene Todos (1 überfällig, 2 heute fällig). Zeit für dein Update.", Reminder.text(todos, today, true))
        assertEquals("4 open to-dos (1 overdue, 2 due today). Time for your update.", Reminder.text(todos, today, false))
        assertEquals("1 offenes Todo. Zeit für dein Update.", Reminder.text(listOf(Todo(4, "Milch")), today, true))
        assertEquals("2 offene Todos. Zeit für dein Update.", Reminder.text(listOf(Todo(1, "a"), Todo(2, "b")), today, true))
        assertEquals("Keine offenen Todos. Zeit für dein Update.", Reminder.text(emptyList(), today, true))
        assertEquals("No open to-dos. Time for your update.", Reminder.text(emptyList(), today, false))
        assertEquals(false, Reminder.text(todos, today, true).contains("Steuer")) // nichts Persönliches auf dem Sperrbildschirm
        assertEquals("Tägliches Update", Reminder.title(true))
    }

    @Test
    fun configKeepsAValidTime() {
        assertEquals("08:00", Config().dailyReminderTime)
        assertEquals(false, Config().dailyReminder)
        assertEquals("07:30", Config(dailyReminderTime = "7.30").sanitize().dailyReminderTime)
        assertEquals("08:00", Config(dailyReminderTime = "kaputt").sanitize().dailyReminderTime)
        val back = AppJson.decodeFromString(Config.serializer(), AppJson.encodeToString(Config.serializer(), Config(dailyReminder = true, dailyReminderTime = "06:15")))
        assertEquals(true, back.dailyReminder)
        assertEquals("06:15", back.dailyReminderTime)
    }
}
