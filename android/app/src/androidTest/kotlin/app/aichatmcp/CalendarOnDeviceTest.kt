package app.aichatmcp

import android.Manifest
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aichatmcp.calendar.DeviceCalendar
import app.aichatmcp.core.OrganizerServer
import app.aichatmcp.core.TodoStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * Gerätekalender auf dem echten System (CalendarContract): Termine anlegen und wieder lesen, ganztägige Termine am richtigen Tag,
 * der Server `organizer` mit dem echten Kalender. Der Test legt einen eigenen lokalen Kalender an und räumt alles wieder weg;
 * er gehört auf den Emulator, nicht auf das Telefon mit den echten Terminen (`adb install -t`, `am instrument`).
 */
@RunWith(AndroidJUnit4::class)
class CalendarOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val account = "aichat-test"
    private val title = "AIChat-Test-${System.currentTimeMillis()}"
    private var calendarId = -1L

    private fun asSyncAdapter(uri: android.net.Uri) = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    @Before
    fun setUp() {
        for (p in listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, p)
        }
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "AIChatTest")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "AIChatTest")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF3366)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = resolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), values)!!
        calendarId = android.content.ContentUris.parseId(uri)
    }

    @After
    fun tearDown() {
        resolver.delete(asSyncAdapter(CalendarContract.Events.CONTENT_URI), "${CalendarContract.Events.TITLE} LIKE ?", arrayOf("AIChat-Test-%"))
        if (calendarId > 0) resolver.delete(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
    }

    @Test
    fun appointmentsRoundtripThroughTheDeviceCalendar() {
        val calendar = DeviceCalendar(context)
        assertTrue(calendar.canRead() && calendar.hasWrite())
        val tomorrow = LocalDate.now().plusDays(1)
        val name = calendar.add(title, tomorrow.atTime(14, 0), 45, allDay = false)
        assertTrue(name.isNotBlank())
        calendar.add("$title ganztägig", tomorrow.atStartOfDay(), 60, allDay = true)

        val onTomorrow = calendar.events(tomorrow, 1).filter { it.title.startsWith(title) }
        assertEquals(2, onTomorrow.size)
        val all = onTomorrow.first { it.allDay }
        assertEquals(tomorrow, all.start.toLocalDate()) // ganztägig liegt am richtigen Tag, nicht beim Nachbartag
        assertEquals("$title ganztägig", all.title)
        val timed = onTomorrow.first { !it.allDay }
        assertEquals(tomorrow.atTime(14, 0), timed.start)
        assertEquals(tomorrow.atTime(14, 45), timed.end)
        assertTrue(onTomorrow.first().allDay) // Sortierung: ganztägig vor Terminen mit Uhrzeit

        assertTrue(calendar.events(LocalDate.now(), 1).none { it.title.startsWith(title) }) // heute ist nichts
        assertEquals(2, calendar.events(LocalDate.now(), 3).count { it.title.startsWith(title) }) // drei Tage reichen bis morgen
        assertTrue(calendar.events(tomorrow.plusDays(1), 1).none { it.title.startsWith(title) }) // übermorgen: nichts, auch nicht der ganztägige Randfall
    }

    @Test
    fun organizerWritesAndReadsTheRealCalendar() {
        val store = TodoStore(File(context.cacheDir, "cal-test-todos.json"))
        val server = OrganizerServer("organizer", store, DeviceCalendar(context))
        val (added, error) = server.callTool("cal_add", mapOf("title" to title, "date" to "tomorrow", "time" to "9:30", "minutes" to 30), 10.0)
        assertFalse(added, error)
        assertTrue(added, added.contains("at 09:30 (30 min)"))
        val (listed, failed) = server.callTool("cal_list", mapOf("date" to "tomorrow"), 10.0)
        assertFalse(listed, failed)
        assertTrue(listed, listed.contains("${LocalDate.now().plusDays(1)} 09:30-10:00 $title"))
        // Vorschau für die Rückfrage
        assertTrue(server.preview("cal_add", mapOf("title" to "X", "date" to "tomorrow", "time" to "9:30"))!!.contains("09:30 Uhr"))
    }
}
