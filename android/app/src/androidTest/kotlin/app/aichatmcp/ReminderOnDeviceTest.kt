package app.aichatmcp

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aichatmcp.reminder.DailyReminder
import app.aichatmcp.reminder.ReminderReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Tägliche Erinnerung auf dem echten Gerät: Der Alarm wird gesetzt und wieder entfernt, und die Benachrichtigung erscheint mit der
 * Kurzübersicht der Todos. Den Alarm selbst auszulösen geht in einem Test nicht (Uhrzeit); die Zustellung prüft `show`.
 * Am Ende wird der Alarm wieder so gesetzt, wie ihn die Einstellungen der App verlangen (der Test soll nichts verstellen).
 */
@RunWith(AndroidJUnit4::class)
class ReminderOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun alarmExists(): Boolean = PendingIntent.getBroadcast(
        context, 1, Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    ) != null

    /** Zahl der Alarm-Einträge der App im System (`dumpsys alarm`): Das System kennt den Alarm wirklich, nicht nur unser PendingIntent. */
    private fun registeredAlarms(): Int {
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys alarm")).bufferedReader().readText()
        return text.lines().count { "walarm" in it && "app.aichatmcp/.reminder.ReminderReceiver" in it }
    }

    @Test
    fun alarmIsSetAndRemoved() {
        try {
            val now = LocalDateTime.of(2026, 11, 10, 9, 0)
            DailyReminder.cancel(context)
            val before = registeredAlarms()
            assertEquals(LocalDateTime.of(2026, 11, 11, 8, 30), DailyReminder.schedule(context, "8:30", now))
            assertTrue("Alarm fehlt", alarmExists())
            assertEquals("Das System kennt den Alarm nicht", before + 1, registeredAlarms())
            DailyReminder.cancel(context)
            assertTrue("Alarm noch da", !alarmExists())
            assertEquals("Das System hat den Alarm noch", before, registeredAlarms())
        } finally {
            DailyReminder.apply(context, AppStorage(context.filesDir) { null }.loadConfig()) // Zustand laut Einstellungen wiederherstellen
        }
    }

    @Test
    fun notificationShowsTheSummary() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(DailyReminder.NOTIFICATION_ID)
        if (!DailyReminder.canNotify(context)) return // Benachrichtigungen des Geräts aus: nichts zu prüfen
        assertTrue(DailyReminder.show(context))
        try {
            val posted = manager.activeNotifications.firstOrNull { it.id == DailyReminder.NOTIFICATION_ID }
            assertNotNull("Benachrichtigung fehlt", posted)
            val extras = posted!!.notification.extras
            val title = extras.getCharSequence("android.title").toString()
            val text = extras.getCharSequence("android.text").toString()
            assertTrue(title, title == "Tägliches Update" || title == "Daily update")
            assertTrue(text, text.contains("to-do", ignoreCase = true) || text.contains("Todo"))
            assertNotNull("Antippen öffnet nichts", posted.notification.contentIntent)
        } finally {
            manager.cancel(DailyReminder.NOTIFICATION_ID)
        }
        repeat(20) { if (manager.activeNotifications.any { it.id == DailyReminder.NOTIFICATION_ID }) Thread.sleep(100) } // Entfernen ist asynchron
        assertNull(manager.activeNotifications.firstOrNull { it.id == DailyReminder.NOTIFICATION_ID })
    }
}
