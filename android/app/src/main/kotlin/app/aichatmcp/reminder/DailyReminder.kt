package app.aichatmcp.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.aichatmcp.AppStorage
import app.aichatmcp.MainActivity
import app.aichatmcp.calendar.DeviceCalendar
import app.aichatmcp.core.Config
import app.aichatmcp.core.Reminder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Tägliche Push-Nachricht mit der Kurzübersicht der Todos (Planung: docs/ORGANIZER.md).
 *
 * Der Alarm ist absichtlich nicht exakt (`setAndAllowWhileIdle`): Exakte Alarme brauchen ab Android 12 eine Sonderberechtigung, die
 * Samsung/Android 14 standardmäßig verweigert. Die Nachricht kann deshalb im Ruhezustand einige Minuten später kommen. Nach jedem
 * Auslösen, nach dem Neustart des Geräts und nach dem Aktualisieren der App wird der nächste Alarm neu gesetzt.
 */
object DailyReminder {
    const val CHANNEL = "daily_update"
    const val NOTIFICATION_ID = 4711
    private const val REQUEST_ALARM = 1
    private const val REQUEST_OPEN = 2
    private const val TAG = "AiChat"

    /** Setzt den Alarm passend zur Einstellung (an: nächster Zeitpunkt, aus: Alarm entfernen). */
    fun apply(context: Context, config: Config) {
        if (config.dailyReminder) schedule(context, config.dailyReminderTime) else cancel(context)
    }

    fun schedule(context: Context, time: String, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val at = Reminder.next(now, Reminder.parseTime(time) ?: Reminder.parseTime(Reminder.DEFAULT_TIME)!!)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarmIntent(context))
        Log.i(TAG, "Tägliche Erinnerung geplant für $at")
        return at
    }

    fun cancel(context: Context) {
        val pending = alarmIntent(context)
        alarms(context).cancel(pending)
        pending.cancel() // auch das PendingIntent verwerfen, sonst bleibt es registriert
    }

    /** Darf die App Benachrichtigungen zeigen? (Ab Android 13 braucht es eine Berechtigung, davor nur die Systemeinstellung.) */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    /** Titel und Text der Nachricht aus den aktuellen Todos; die Sprache folgt dem aktiven Companion. */
    fun content(context: Context, today: LocalDate = LocalDate.now()): Pair<String, String> {
        val storage = AppStorage(context.filesDir) { null }
        val german = storage.personas.load(storage.loadConfig().persona).isGerman()
        val calendar = DeviceCalendar(context)
        val appointments = if (calendar.canRead()) runCatching { calendar.events(today, 1).size }.getOrDefault(0) else 0
        return Reminder.title(german) to Reminder.text(storage.todos.open(), today, german, appointments)
    }

    /** Zeigt die Nachricht. Liefert false, wenn sie nicht erlaubt ist (Berechtigung fehlt oder Benachrichtigungen sind aus). */
    fun show(context: Context): Boolean {
        if (!canNotify(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Tägliches Update", NotificationManager.IMPORTANCE_DEFAULT))
        val (title, text) = content(context)
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build(),
        )
        return true
    }

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_ALARM, Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Der Alarm ist da: Nachricht zeigen und den nächsten Tag planen. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val config = AppStorage(context.filesDir) { null }.loadConfig()
        if (!config.dailyReminder) return
        DailyReminder.show(context)
        DailyReminder.schedule(context, config.dailyReminderTime)
    }
}

/** Nach Neustart des Geräts und nach einem App-Update gehen Alarme verloren: neu setzen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        DailyReminder.apply(context, AppStorage(context.filesDir) { null }.loadConfig())
    }
}
