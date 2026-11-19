package app.aichatmcp.messages

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.aichatmcp.core.MessageApps
import app.aichatmcp.core.MessageParser
import app.aichatmcp.openStorage

/**
 * Liest die Benachrichtigungen von WhatsApp und Telegram mit (nur diese Pakete; alle anderen werden sofort verworfen) und legt Absender und Text
 * im Arbeitsspeicher ab ([MessageHub]). Die Chats werden nie geöffnet, die Nachrichten bleiben deshalb im Messenger ungelesen. Verschwindet die
 * Benachrichtigung (Chat gelesen), verschwinden auch die Nachrichten. Der Nutzer muss den Benachrichtigungszugriff selbst erlauben.
 */
class NotificationCatcher : NotificationListenerService() {
    override fun onListenerConnected() {
        MessageHub.enabled = runCatching { openStorage(this).loadConfig().catchMessages }.getOrDefault(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!MessageHub.enabled) return
        val app = MessageApps.label(sbn.packageName) ?: return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val lines = runCatching { messagingLines(extras) }.getOrDefault(emptyList())
        val notes = MessageParser.parse(
            app, sbn.key, extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            lines, (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0, sbn.postTime,
        )
        notes.forEach(MessageHub.inbox::add)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (MessageApps.label(sbn.packageName) != null) MessageHub.inbox.removeByKey(sbn.key)
    }

    @Suppress("DEPRECATION")
    private fun messagingLines(extras: Bundle): List<MessageParser.Line> {
        val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw).mapNotNull { m ->
            val text = m.text?.toString() ?: return@mapNotNull null
            val sender = if (Build.VERSION.SDK_INT >= 28) m.senderPerson?.name?.toString() else m.sender?.toString()
            MessageParser.Line(sender, text, m.timestamp)
        }
    }
}
