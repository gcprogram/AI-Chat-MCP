package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessagesTest {
    private fun note(sender: String, text: String, time: Long = 1000, app: String = "WhatsApp", key: String = "k1") = MessageNote(app, sender, text, time, key)

    @Test
    fun parsesASingleNotification() {
        val notes = MessageParser.parse("WhatsApp", "k", "Anna (3 neue Nachrichten)", "Bist du da?", emptyList(), false, 5000)
        assertEquals(listOf(MessageNote("WhatsApp", "Anna", "Bist du da?", 5000, "k")), notes)
    }

    @Test
    fun skipsSummariesAndPlaceholders() {
        assertEquals(emptyList(), MessageParser.parse("WhatsApp", "k", "WhatsApp", "5 neue Nachrichten aus 3 Chats", emptyList(), true, 1))
        assertEquals(emptyList(), MessageParser.parse("WhatsApp", "k", "WhatsApp", "5 new messages from 3 chats", emptyList(), false, 1))
        assertEquals(emptyList(), MessageParser.parse("WhatsApp", "k", "WhatsApp", "Checking for new messages", emptyList(), false, 1))
        assertEquals(emptyList(), MessageParser.parse("Telegram", "k", "Tom", "", emptyList(), false, 1))
    }

    @Test
    fun messagingStyleLinesKeepSenderAndGroup() {
        val lines = listOf(MessageParser.Line("Tom", "Hallo", 10), MessageParser.Line("Tom", "Bist du da?", 20), MessageParser.Line(null, "ohne Absender", 0))
        val notes = MessageParser.parse("Telegram", "k", "Familie", null, lines, false, 99)
        assertEquals(listOf("Tom (Familie)", "Tom (Familie)", "Familie"), notes.map { it.sender })
        assertEquals(listOf(10L, 20L, 99L), notes.map { it.time })
        val direct = MessageParser.parse("Telegram", "k", "Tom", null, listOf(MessageParser.Line("Tom", "Hi", 5)), false, 99)
        assertEquals("Tom", direct.single().sender)
    }

    @Test
    fun inboxKeepsDuplicatesOutDropsReadChatsAndOldMessages() {
        var now = 100_000_000L
        val inbox = MessageInbox(maxNotes = 3, maxAgeMs = 1000, clock = { now })
        inbox.add(note("A", "eins", now))
        inbox.add(note("A", "eins", now)) // dieselbe Nachricht noch einmal (Benachrichtigung wurde aktualisiert)
        inbox.add(note("B", "zwei", now, app = "Telegram", key = "k2"))
        assertEquals(2, inbox.unread().size)
        assertEquals(listOf("zwei"), inbox.unread("telegram").map { it.text })
        inbox.removeByKey("k1") // Chat gelesen: Benachrichtigung weg
        assertEquals(listOf("zwei"), inbox.unread().map { it.text })
        inbox.add(note("C", "drei", now, key = "k3"))
        inbox.add(note("D", "vier", now, key = "k4"))
        inbox.add(note("E", "fünf", now, key = "k5"))
        assertEquals(listOf("drei", "vier", "fünf"), inbox.unread().map { it.text }) // höchstens drei
        now += 5000
        assertEquals(emptyList(), inbox.unread()) // zu alt
    }

    @Test
    fun speechGroupsBySenderAndHandlesEmpty() {
        val text = MessageFormat.speech(listOf(note("Anna", "Hallo"), note("Anna", "Bist du da?", 2000), note("Tom", "Ok", 3000, app = "Telegram", key = "k2")), true)
        assertEquals("3 neue Nachrichten:\nAnna auf WhatsApp: Hallo. Bist du da?\nTom auf Telegram: Ok.", text)
        assertEquals("Keine neuen Nachrichten.", MessageFormat.speech(emptyList(), true))
        assertTrue(MessageFormat.speech(listOf(note("Ann", "Hi")), false).startsWith("1 new message:"))
    }

    @Test
    fun serverExplainsWhatIsMissingAndListsMessages() {
        val inbox = MessageInbox()
        inbox.add(note("Anna", "Hallo", System.currentTimeMillis()))
        var state = MessagesServer.State.NO_ACCESS
        val server = MessagesServer("messages", inbox, { state })
        val (noAccess, err1) = server.callTool("messages_unread", emptyMap(), 5.0)
        assertTrue(err1 && "notification access" in noAccess)
        state = MessagesServer.State.SWITCHED_OFF
        assertTrue(server.callTool("messages_unread", emptyMap(), 5.0).second)
        state = MessagesServer.State.READY
        val (text, err) = server.callTool("messages_unread", mapOf("app" to "whatsapp"), 5.0)
        assertTrue(!err && "Anna auf WhatsApp: Hallo." in text)
        assertTrue(server.callTool("messages_unread", mapOf("app" to "telegram"), 5.0).first.startsWith("Keine neuen"))
        server.callTool("messages_clear", emptyMap(), 5.0)
        assertTrue(server.callTool("messages_unread", emptyMap(), 5.0).first.startsWith("Keine neuen"))
    }

    @Test
    fun defaultKeywordsCatchRequestsToReadMessages() {
        val file = listOf("../app/src/main/assets/default_mcp_servers.json", "app/src/main/assets/default_mcp_servers.json").map { java.io.File(it) }.first { it.isFile }
        val aliases = (AppJson.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject)["aliases"] as kotlinx.serialization.json.JsonObject
        val patterns = ((aliases["messages"] as kotlinx.serialization.json.JsonObject)["keywords"] as kotlinx.serialization.json.JsonArray)
            .map { Regex((it as kotlinx.serialization.json.JsonPrimitive).content, RegexOption.IGNORE_CASE) }
        fun hit(text: String) = patterns.any { it.find(text) != null }
        for (t in listOf("Lies meine Nachrichten vor", "lies mir die neuen Nachrichten vor", "Neue Nachrichten?", "Habe ich neue Nachrichten?", "Gibt es Post")) assertTrue(hit(t), t)
        for (t in listOf("Ich schreibe gleich eine Nachricht", "Wie wird das Wetter?", "Lies das Dokument vor")) assertTrue(!hit(t), t)
    }
}
