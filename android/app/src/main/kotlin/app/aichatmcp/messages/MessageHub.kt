package app.aichatmcp.messages

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.aichatmcp.core.MessageInbox
import app.aichatmcp.core.MessagesServer

/** Die mitgelesenen Nachrichten des Prozesses (nur im Arbeitsspeicher) und der Schalter aus den Einstellungen. */
object MessageHub {
    val inbox = MessageInbox()

    /** Einstellung "Nachrichten mitlesen"; der Dienst liest sie beim Start, das ViewModel setzt sie beim Speichern. */
    @Volatile
    var enabled = false

    /** Hat der Nutzer der App den Benachrichtigungszugriff in den Systemeinstellungen erlaubt? (Das kann die App nicht selbst.) */
    fun accessGranted(context: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun state(context: Context): MessagesServer.State = when {
        !enabled -> MessagesServer.State.SWITCHED_OFF
        !accessGranted(context) -> MessagesServer.State.NO_ACCESS
        else -> MessagesServer.State.READY
    }
}
