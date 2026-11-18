package app.aichatmcp.work

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.aichatmcp.MainActivity
import app.aichatmcp.ui.tNow

/**
 * Hält die App wach, solange das Modell rechnet (Antwort oder Vorbereitung): Ohne Vordergrunddienst und Wake-Lock legt Android die CPU
 * schlafen, sobald der Bildschirm ausgeht, und die Antwort bleibt mitten im Satz stehen. Der Dienst rechnet nichts selbst; das
 * ViewModel startet und beendet ihn.
 */
class GenerationService : Service() {
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { startForeground(NOTIFICATION_ID, notification(this), type) }
        if (lock == null) {
            val pm = getSystemService(PowerManager::class.java)
            // Sicherheitsgrenze: nach 30 Minuten gibt Android die Sperre selbst frei, falls der Dienst nie beendet würde
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aichat:generation").apply { acquire(30 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "generation"
        private const val NOTIFICATION_ID = 4714

        /** Startet den Dienst; nur aus dem sichtbaren Bildschirm (Hintergrundstarts verbietet Android). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, GenerationService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GenerationService::class.java)) }
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tNow("Replies", "Antworten"), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(tNow("The model is working", "Das Modell rechnet")).setContentIntent(open).setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
        }
    }
}
