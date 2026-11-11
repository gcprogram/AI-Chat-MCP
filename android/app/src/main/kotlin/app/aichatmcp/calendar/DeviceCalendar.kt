package app.aichatmcp.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import app.aichatmcp.core.CalendarBackend
import app.aichatmcp.core.CalendarEvent
import app.aichatmcp.core.ToolError
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Der Kalender des Geräts (`CalendarContract`), für cal_add/cal_list des Servers `organizer` und fürs Tagesupdate.
 * Termine kommen in den ersten beschreibbaren, sichtbaren Kalender (der Hauptkalender zuerst). Ohne die Berechtigung bekommt das Modell einen
 * Fehlertext, der den Nutzer auf die Einstellung hinweist. Planung: docs/ORGANIZER.md.
 */
class DeviceCalendar(private val context: Context, private val zone: ZoneId = ZoneId.systemDefault()) : CalendarBackend {
    fun hasRead() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun hasWrite() = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun canRead() = hasRead()

    private fun need(write: Boolean) {
        if (!hasRead() || (write && !hasWrite())) {
            throw ToolError("Calendar access is not allowed yet. Tell the user to allow it in the app: menu > Einstellungen > Kalender.")
        }
    }

    override fun add(title: String, start: LocalDateTime, minutes: Int, allDay: Boolean): String {
        need(write = true)
        val (id, name) = writableCalendar() ?: throw ToolError("There is no calendar on this phone that can take appointments.")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, id)
            put(CalendarContract.Events.TITLE, title)
            if (allDay) {
                // Ganztägige Termine stehen als UTC-Mitternacht im Kalender, sonst rutschen sie in einen anderen Tag
                val day = start.toLocalDate()
                put(CalendarContract.Events.DTSTART, day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
                put(CalendarContract.Events.DTEND, day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                val begin = start.atZone(zone).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, begin + minutes * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw ToolError("The calendar did not accept the appointment.")
        if (ContentUris.parseId(uri) <= 0) throw ToolError("The calendar did not accept the appointment.")
        return name
    }

    override fun events(from: LocalDate, days: Int): List<CalendarEvent> {
        need(write = false)
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = from.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val projection = arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY)
        val result = mutableListOf<CalendarEvent>()
        CalendarContract.Instances.query(context.contentResolver, projection, begin, end)?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(3) == 1
                val title = c.getString(2).orEmpty().ifBlank { "(ohne Titel)" }
                if (allDay) {
                    // Ganztägige Instanzen sind UTC-Tage; die Abfrage in Ortszeit streift am Rand den Nachbartag: nach dem UTC-Datum filtern
                    val day = Instant.ofEpochMilli(c.getLong(0)).atZone(ZoneOffset.UTC).toLocalDate()
                    if (day.isBefore(from) || !day.isBefore(from.plusDays(days.toLong()))) continue
                    result += CalendarEvent(title, day.atStartOfDay(), day.plusDays(1).atStartOfDay(), allDay = true)
                } else {
                    result += CalendarEvent(
                        title, LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(0)), zone), LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), zone),
                    )
                }
            }
        }
        return result.sortedWith(compareBy({ it.start.toLocalDate() }, { !it.allDay }, { it.start }))
    }

    /** (Kennung, Name) des ersten beschreibbaren, sichtbaren Kalenders; der Hauptkalender zuerst. */
    private fun writableCalendar(): Pair<Long, String>? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val order = "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, selection, arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), order,
        )?.use { c ->
            if (c.moveToFirst()) return c.getLong(0) to c.getString(1).orEmpty().ifBlank { "Kalender" }
        }
        return null
    }
}
