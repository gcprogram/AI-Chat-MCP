package app.aichatmcp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class ChatFileException(message: String) : Exception(message)

data class LoadedChat(
    val messages: List<Message>,
    /** ID des Companions, mit dem gechattet wurde. */
    val persona: String = "",
)

/**
 * Chats als JSON (Port von chatlog.py, gleiches Dateiformat wie die Python-App). Die Datei enthält den vollständigen
 * Verlauf inkl. Werkzeugaufrufen und -ergebnissen. Beim Laden wird nichts ausgeführt.
 */
object ChatLog {
    private const val FORMAT_VERSION = 1

    /** Endung der Chat-Dateien (Inhalt JSON). Eigene Endung, damit ein Chat nie eine Companion-Datei (*.json) überschreibt. */
    const val EXTENSION = ".aichat"

    /** Vorgeschlagener Dateiname beim Speichern: `<companion>_<Datum>_<Uhrzeit>.aichat` (wie in der Python-App). */
    fun fileName(personaId: String, now: LocalDateTime = LocalDateTime.now()): String =
        "${personaId.ifEmpty { "chat" }}_${now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))}$EXTENSION"

    fun toJson(messages: List<Message>, personaId: String, userName: String, saved: LocalDateTime = LocalDateTime.now()): String {
        val data = buildJsonObject {
            put("format", "ai-chat-mcp")
            put("version", FORMAT_VERSION)
            put("saved", saved.truncatedTo(ChronoUnit.SECONDS).toString())
            put("persona", personaId)
            put("user_name", userName)
            put("messages", buildJsonArray {
                for (m in messages) add(buildJsonObject {
                    put("role", m.role)
                    put("content", m.content)
                    if (m.kind != CHAT) {
                        put("kind", m.kind)
                        put("tool", m.tool)
                    }
                })
            })
        }
        return AppJson.encodeToString(JsonObject.serializer(), data) + "\n"
    }

    /** Leere Nachrichten entfernen, aufeinanderfolgende gleiche Sprecher (handgeschriebene Dateien) zusammenführen. */
    private fun normalize(messages: List<Message>): List<Message> {
        val result = mutableListOf<Message>()
        for (m in messages) {
            if (m.content.isBlank()) continue
            val last = result.lastOrNull()
            if (last != null && !m.isTool && !last.isTool && last.role == m.role) {
                result[result.size - 1] = Message(m.role, last.content + "\n\n" + m.content.trim())
            } else {
                result += Message(m.role, m.content.trim(), m.kind, m.tool)
            }
        }
        return result
    }

    fun fromJson(text: String): LoadedChat {
        val data = try {
            AppJson.parseToJsonElement(text.removePrefix("﻿"))
        } catch (e: Exception) {
            throw ChatFileException("Keine gültige JSON-Datei: ${e.message}")
        }
        val raw = if (data is JsonObject) data["messages"] else data // auch eine nackte Liste akzeptieren
        if (raw !is JsonArray) throw ChatFileException("Die Datei enthält keinen Chat (Feld 'messages' fehlt).")
        val messages = raw.map { item ->
            val obj = item as? JsonObject ?: throw ChatFileException("Ungültiger Eintrag in 'messages'.")
            val role = obj.string("role")
            val content = (obj["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (role != "user" && role != "assistant" || content == null) {
                throw ChatFileException("Ungültige Nachricht (role/content) in der Datei.")
            }
            val kind = obj.string("kind")?.takeIf { it in listOf(TOOL_RESULT, TOOL_ERROR, TOOL_SHOWN) && role == "user" } ?: CHAT
            Message(role, content, kind, if (kind != CHAT) (obj["tool"] as? JsonPrimitive)?.contentOrNull.orEmpty() else "")
        }
        val persona = (data as? JsonObject)?.string("persona").orEmpty()
        return LoadedChat(normalize(messages), persona)
    }
}
