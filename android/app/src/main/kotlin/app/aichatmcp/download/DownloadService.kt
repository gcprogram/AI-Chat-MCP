package app.aichatmcp.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.aichatmcp.InterruptReason
import app.aichatmcp.MainActivity
import app.aichatmcp.ModelManager
import app.aichatmcp.ui.formatBytes
import app.aichatmcp.ui.tNow

/**
 * Hält große Downloads am Leben: Ein Vordergrunddienst mit Benachrichtigung (Fortschritt, Abbrechen) sorgt dafür, dass Android den Prozess nicht
 * beendet, wenn die App im Hintergrund ist oder der Bildschirm ausgeht. Geladen wird nicht hier, sondern im [ModelManager]; der Dienst zeigt nur
 * dessen Zustand und gibt die Knöpfe der Benachrichtigung weiter. Fällt das WLAN weg, fragt die Benachrichtigung nach (Über Mobilfunk
 * fortsetzen / Auf WLAN warten / Abbrechen), auch wenn die App gerade nicht offen ist.
 */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = ModelManager.existing()
        when (intent?.action) {
            ACTION_CANCEL -> manager?.cancel()
            ACTION_MOBILE -> manager?.continueOnMobile()
            ACTION_WAIT -> manager?.waitForWifi()
            ACTION_RETRY -> manager?.retry()
        }
        val notification = manager?.let { build(this, it) }
        if (notification == null) { // nichts zu laden (Prozess neu gestartet, Download schon beendet)
            runCatching { startForeground(NOTIFICATION_ID, idle(this), type()) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startForeground(NOTIFICATION_ID, notification, type()) }
        return START_NOT_STICKY
    }

    /** Android 15 begrenzt Datensynchronisierungsdienste auf 6 Stunden: den Download anhalten, der Nutzer setzt per Antippen fort. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ModelManager.existing()?.pauseBySystem()
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun type() = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    companion object {
        const val ACTION_CANCEL = "app.aichatmcp.download.CANCEL"
        const val ACTION_MOBILE = "app.aichatmcp.download.MOBILE"
        const val ACTION_WAIT = "app.aichatmcp.download.WAIT"
        const val ACTION_RETRY = "app.aichatmcp.download.RETRY"
        private const val CHANNEL = "downloads"
        private const val CHANNEL_ASK = "downloads_ask"
        private const val NOTIFICATION_ID = 4712
        private const val NOTIFICATION_DONE = 4713
        private const val UPDATE_EVERY_MS = 1000L

        @Volatile
        private var lastUpdate = 0L

        /** Startet den Dienst (aus dem sichtbaren Bildschirm: Hintergrundstarts verbietet Android). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }

        /** Aktualisiert die Benachrichtigung; Fortschritt höchstens einmal pro Sekunde ([force] = Zustandswechsel, sofort). */
        fun refresh(context: Context, force: Boolean = true) {
            val now = System.currentTimeMillis()
            if (!force && now - lastUpdate < UPDATE_EVERY_MS) return
            lastUpdate = now
            val manager = ModelManager.existing() ?: return
            val notification = build(context, manager) ?: return
            runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
        }

        /** Meldung "fertig" (ohne Ton), damit man es auch bei gesperrtem Bildschirm sieht. */
        fun finished(context: Context, title: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            ensureChannels(context)
            val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(tNow("Download finished", "Download fertig")).setContentText(title).setAutoCancel(true)
                .setContentIntent(openApp(context)).build()
            runCatching { nm.notify(NOTIFICATION_DONE, n) }
        }

        private fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tNow("Downloads", "Downloads"), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ASK, tNow("Download needs a decision", "Download braucht eine Entscheidung"), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun action(context: Context, code: Int, label: String, action: String): NotificationCompat.Action {
            val intent = PendingIntent.getService(
                context, code, Intent(context, DownloadService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Action.Builder(0, label, intent).build()
        }

        /** Notbehelf-Benachrichtigung, falls der Dienst ohne Download gestartet wurde (Android verlangt sie nach startForegroundService). */
        fun idle(context: Context): Notification {
            ensureChannels(context)
            return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Download").build()
        }

        /** Baut die Benachrichtigung aus dem Zustand des Managers; null, wenn nichts geladen wird. */
        fun build(context: Context, manager: ModelManager): Notification? {
            val active = manager.active ?: return null
            ensureChannels(context)
            val interruption = manager.interruption
            val asking = interruption?.ask == true
            val b = NotificationCompat.Builder(context, if (asking) CHANNEL_ASK else CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(active.title)
                .setContentIntent(openApp(context)).setOnlyAlertOnce(!asking).setOngoing(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            val fraction = if (active.totalBytes > 0) (active.doneBytes * 100 / active.totalBytes).toInt().coerceIn(0, 100) else 0
            val left = formatBytes(interruption?.remainingBytes ?: 0)
            val cancel = action(context, 1, tNow("Cancel", "Abbrechen"), ACTION_CANCEL)
            if (interruption == null) {
                b.setContentText("${formatBytes(active.doneBytes)} / ${formatBytes(active.totalBytes)} ($fraction %)").setProgress(100, fraction, false).addAction(cancel)
            } else {
                b.setProgress(100, fraction, false)
                when (interruption.reason) {
                    InterruptReason.WIFI_LOST -> {
                        b.setContentText(
                            if (asking) tNow("Wi-Fi lost. Continue over mobile data? $left left.", "WLAN weg. Über Mobilfunk fortsetzen? Noch $left.")
                            else tNow("Waiting for Wi-Fi ($left left).", "Warte auf WLAN (noch $left)."),
                        )
                        b.addAction(action(context, 2, tNow("Mobile data", "Mobilfunk"), ACTION_MOBILE))
                        if (asking) b.addAction(action(context, 3, tNow("Wait for Wi-Fi", "Auf WLAN warten"), ACTION_WAIT))
                        b.addAction(cancel)
                    }
                    InterruptReason.OFFLINE -> b.setContentText(tNow("No connection. Continues when a network is back ($left left).", "Keine Verbindung. Geht weiter, sobald ein Netz da ist (noch $left).")).addAction(cancel)
                    InterruptReason.FAILED -> b.setContentText(tNow("Connection interrupted ($left left).", "Verbindung unterbrochen (noch $left).")).addAction(action(context, 4, tNow("Try again", "Erneut versuchen"), ACTION_RETRY)).addAction(cancel)
                    InterruptReason.SYSTEM -> b.setContentText(tNow("Paused by Android. Tap to continue ($left left).", "Von Android angehalten. Zum Fortsetzen antippen (noch $left).")).addAction(action(context, 4, tNow("Continue", "Fortsetzen"), ACTION_RETRY)).addAction(cancel)
                }
            }
            return b.build()
        }
    }
}
