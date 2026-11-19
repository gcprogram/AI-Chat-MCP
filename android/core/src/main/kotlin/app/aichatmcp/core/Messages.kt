package app.aichatmcp.core

import java.util.concurrent.CopyOnWriteArrayList

/** Eine mitgelesene Nachricht (aus einer Benachrichtigung von WhatsApp oder Telegram). [key] = Schlüssel der Benachrichtigung, zu der sie gehört. */
data class MessageNote(val app: String, val sender: String, val text: String, val time: Long, val key: String)

/** Die Messenger, deren Benachrichtigungen mitgelesen werden: Paketnamen und Anzeigename. */
object MessageApps {
    private val packages = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
        "org.telegram.plus" to "Telegram",
        "org.thunderdog.challegram" to "Telegram",
    )

    fun label(packageName: String): String? = packages[packageName]

    /** Passt der Filter des Nutzers ("whatsapp", "telegram", leer = alle) auf den Anzeigenamen? */
    fun matches(app: String, filter: String?): Boolean = filter.isNullOrBlank() || app.contains(filter.trim(), ignoreCase = true)
}

/**
 * Die ungelesenen Nachrichten, nur im Arbeitsspeicher und nur für kurze Zeit (höchstens [maxNotes], nicht älter als [maxAgeMs]). Nachrichten
 * verschwinden, sobald die Benachrichtigung verschwindet (der Nutzer hat den Chat gelesen): "ungelesen" bedeutet hier "Benachrichtigung steht noch".
 * Weil die App die Chats nie öffnet, bleiben die Nachrichten im Messenger selbst ungelesen.
 */
class MessageInbox(private val maxNotes: Int = 100, private val maxAgeMs: Long = 24 * 3600_000L, private val clock: () -> Long = System::currentTimeMillis) {
    private val notes = CopyOnWriteArrayList<MessageNote>()

    @Synchronized
    fun add(note: MessageNote) {
        if (notes.any { it.app == note.app && it.sender == note.sender && it.text == note.text && it.time == note.time }) return
        notes += note
        prune()
        while (notes.size > maxNotes) notes.removeAt(0)
    }

    @Synchronized
    fun removeByKey(key: String) {
        notes.removeAll { it.key == key }
    }

    @Synchronized
    fun clear() = notes.clear()

    private fun prune() {
        val limit = clock() - maxAgeMs
        notes.removeAll { it.time < limit }
    }

    /** Alle Nachrichten (ältere zuerst), optional nur einer App ("whatsapp", "telegram"). */
    @Synchronized
    fun unread(appFilter: String? = null): List<MessageNote> {
        prune()
        return notes.filter { MessageApps.matches(it.app, appFilter) }.sortedBy { it.time }
    }
}

/** Macht aus den Feldern einer Benachrichtigung Nachrichten. Reine Logik, damit sie ohne Gerät testbar ist. */
object MessageParser {
    /** Eine Nachricht aus dem MessagingStyle einer Benachrichtigung. */
    data class Line(val sender: String?, val text: String, val time: Long)

    private val COUNT_SUFFIX = Regex("""\s*\(\d+\s*(neue\s+)?(messages?|nachrichten)\)\s*$""", RegexOption.IGNORE_CASE)
    private val PLACEHOLDER = Regex(
        """^\s*(\d+\s+(neue\s+|new\s+)?(messages?|nachrichten)(\s+(from|von)\s+\d+\s+(chats?|unterhaltungen))?|checking for new messages|nach neuen nachrichten gesucht|wird nach neuen nachrichten gesucht.*)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(app: String, key: String, title: String?, text: String?, lines: List<Line>, isGroupSummary: Boolean, postTime: Long): List<MessageNote> {
        if (isGroupSummary) return emptyList() // Sammelmeldung "5 neue Nachrichten": die einzelnen Nachrichten kommen getrennt
        val chat = title.orEmpty().replace(COUNT_SUFFIX, "").trim()
        if (lines.isNotEmpty()) {
            return lines.filter { it.text.isNotBlank() && !PLACEHOLDER.matches(it.text) }.map { line ->
                val who = line.sender?.trim().orEmpty()
                val sender = when {
                    who.isEmpty() -> chat
                    chat.isNotEmpty() && !who.equals(chat, ignoreCase = true) -> "$who ($chat)" // Gruppe: Absender und Gruppenname
                    else -> who
                }
                MessageNote(app, sender.ifEmpty { "?" }, line.text.trim(), line.time.takeIf { it > 0 } ?: postTime, key)
            }
        }
        val body = text.orEmpty().trim()
        if (body.isEmpty() || PLACEHOLDER.matches(body) || chat.isEmpty()) return emptyList()
        return listOf(MessageNote(app, chat, body, postTime, key))
    }
}

object MessageFormat {
    /** Text zum Anzeigen und Vorlesen: je Absender eine Zeile, die Nachrichten eines Absenders hintereinander. */
    fun speech(notes: List<MessageNote>, german: Boolean): String {
        if (notes.isEmpty()) return if (german) "Keine neuen Nachrichten." else "No new messages."
        val bySender = notes.groupBy { it.app to it.sender }
        val head = if (german) "${notes.size} neue ${if (notes.size == 1) "Nachricht" else "Nachrichten"}:" else "${notes.size} new ${if (notes.size == 1) "message" else "messages"}:"
        val lines = bySender.map { (who, list) ->
            val texts = list.joinToString(" ") { it.text.trimEnd().let { t -> if (t.lastOrNull()?.let { c -> c in ".!?…" } == true) t else "$t." } }
            if (german) "${who.second} auf ${who.first}: $texts" else "${who.second} on ${who.first}: $texts"
        }
        return (listOf(head) + lines).joinToString("\n")
    }
}

/**
 * MCP-Server `messages`: liefert die ungelesenen Nachrichten aus dem [MessageInbox]. [state] sagt, ob das Mitlesen möglich ist
 * (Einstellung der App und Benachrichtigungszugriff des Systems); sonst erklärt das Werkzeug dem Modell, was fehlt.
 */
class MessagesServer(
    name: String, private val inbox: MessageInbox, private val state: () -> State, private val german: () -> Boolean = { true },
) : LocalServer(name) {
    enum class State { READY, SWITCHED_OFF, NO_ACCESS }

    override val tools = listOf(
        tool(
            "messages_unread", "Lists the unread WhatsApp and Telegram messages of the user (from notifications; the chats are not opened, so they stay unread).",
            listOf(Triple("app", "string", "Optional: whatsapp or telegram; default both")),
            emptyList(),
        ),
        tool("messages_clear", "Forgets the messages that were collected so far (they stay unread in the messenger).", emptyList(), emptyList(), readOnly = false),
    )
    override val handlers = mapOf<String, (Map<String, Any?>) -> String>("messages_unread" to ::unread, "messages_clear" to ::clear)

    private fun unread(arguments: Map<String, Any?>): String {
        when (state()) {
            State.SWITCHED_OFF -> throw ToolError("Reading messages is switched off in the app settings (Organizer > Messages). Tell the user.")
            State.NO_ACCESS -> throw ToolError("The app has no notification access. The user must allow it in the system settings (Organizer > Messages > Allow access).")
            State.READY -> {}
        }
        return MessageFormat.speech(inbox.unread(arguments["app"]?.toString()), german())
    }

    private fun clear(arguments: Map<String, Any?>): String {
        inbox.clear()
        return "Forgotten. The messages stay unread in the messenger."
    }
}
