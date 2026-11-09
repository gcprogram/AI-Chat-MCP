package app.aichatmcp.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatLogTest {
    private val messages = listOf(
        Message("user", "Wie wird das Wetter?"),
        Message("assistant", "Ich schaue nach. [[mcp:weather]]"),
        Message("user", "06:00 sonnig", TOOL_SHOWN, "weather"),
        Message("assistant", "Es bleibt sonnig."),
    )

    @Test
    fun jsonRoundtripKeepsToolMessages() {
        val text = ChatLog.toJson(messages, "fern", "Stefan", LocalDateTime.of(2026, 11, 4, 12, 0, 0))
        assertTrue("\"format\": \"ai-chat-mcp\"" in text)
        assertTrue("\"saved\": \"2026-11-04T12:00\"" in text)
        val loaded = ChatLog.fromJson(text)
        assertEquals(messages, loaded.messages)
        assertEquals("fern", loaded.persona)
    }

    @Test
    fun chatFilesGetTheirOwnExtensionAndNeverTheBareCompanionName() {
        assertEquals("fern_2026-11-04_1205.aichat", ChatLog.fileName("fern", LocalDateTime.of(2026, 11, 4, 12, 5, 33)))
        assertEquals("chat_2026-11-04_1205.aichat", ChatLog.fileName("", LocalDateTime.of(2026, 11, 4, 12, 5)))
        assertTrue(!ChatLog.fileName("fern").endsWith(".json"))
    }

    @Test
    fun readsTheFileFormatOfThePythonApp() {
        val python = """{"format": "ai-chat-mcp", "version": 1, "saved": "2026-11-01T10:00:00", "persona": "secretary", "user_name": "Stefan",
            "messages": [{"role": "user", "content": "Hi"}, {"role": "user", "content": "noch eins"},
                         {"role": "assistant", "content": "[[mcp:time]]"},
                         {"role": "user", "content": "Montag", "kind": "tool_result", "tool": "time"},
                         {"role": "assistant", "content": "  "}]}"""
        val loaded = ChatLog.fromJson("﻿$python")
        assertEquals("secretary", loaded.persona)
        assertEquals(
            listOf(Message("user", "Hi\n\nnoch eins"), Message("assistant", "[[mcp:time]]"), Message("user", "Montag", TOOL_RESULT, "time")),
            loaded.messages,
        )
        assertEquals(1, ChatLog.fromJson("""[{"role": "user", "content": "nackte Liste"}]""").messages.size)
    }

    @Test
    fun invalidFilesGiveReadableErrors() {
        for (bad in listOf("{", """{"x": 1}""", """{"messages": [1]}""", """{"messages": [{"role": "system", "content": "x"}]}""")) {
            assertFailsWith<ChatFileException> { ChatLog.fromJson(bad) }
        }
    }
}
