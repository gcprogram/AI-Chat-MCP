package app.aichatmcp.clock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import app.aichatmcp.core.ClockBackend
import app.aichatmcp.core.ToolError
import java.time.LocalTime

/**
 * Wecker und Timer über die Uhr-App des Telefons (`AlarmClock`-Intents mit EXTRA_SKIP_UI: die Uhr-App stellt sie ohne eigene Oberfläche).
 * Die App selbst verwaltet keine Wecker. Planung: docs/ORGANIZER.md.
 *
 * Grenze: Die Intents starten eine Aktivität der Uhr-App; das darf nur, solange die Chat-App im Vordergrund ist. Läuft ein Aufruf erst
 * nach dem Wechsel in eine andere App, blockiert Android den Start, ohne dass die App es erfährt.
 */
class DeviceClock(private val context: Context) : ClockBackend {
    override fun setAlarm(time: LocalTime, label: String) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        start(intent, "alarms")
    }

    override fun setTimer(seconds: Int, label: String) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        start(intent, "timers")
    }

    private fun start(intent: Intent, what: String) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) throw ToolError("No clock app on this phone can set $what.")
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw ToolError("No clock app on this phone can set $what.")
        } catch (e: SecurityException) {
            throw ToolError("The clock app did not accept the request (${e.message}).")
        }
    }
}
