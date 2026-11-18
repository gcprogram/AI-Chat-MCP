package app.aichatmcp.core

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatPayloadTest {
    private val messages = listOf(WireMessage("system", "s"), WireMessage("user", "hi"))
    private val extra = mapOf("top_p" to 0.9, "frequency_penalty" to 0.3, "presence_penalty" to 0.2, "repeat_penalty" to 1.1)

    private fun build(dialect: Dialect, model: String, stop: List<String> = listOf("\nTom:")): JsonObject =
        ChatPayload.build(dialect, model, messages, 0.7, 300, stop, extra)

    @Test
    fun ownServersGetEverything() {
        val p = build(Dialect.STANDARD, "qwen3")
        assertTrue(listOf("temperature", "max_tokens", "stream_options", "repeat_penalty", "top_p", "frequency_penalty", "stop").all { it in p })
        assertFalse("max_completion_tokens" in p)
    }

    @Test
    fun openAiChatModelsGetMaxCompletionTokensAndNoOwnFields() {
        val p = build(Dialect.OPENAI, "gpt-4o-mini")
        assertTrue("max_completion_tokens" in p && "max_tokens" !in p)
        assertFalse("repeat_penalty" in p) // OpenAI lehnt unbekannte Felder mit HTTP 400 ab
        assertTrue("temperature" in p && "stop" in p && "top_p" in p && "stream_options" in p)
    }

    @Test
    fun openAiReasoningModelsGetOnlyWhatTheyAllow() {
        for (model in listOf("o3-mini", "o4-mini", "gpt-5", "gpt-5-mini", "gpt-5.1", "ft:gpt-5-mini:org")) {
            val p = build(Dialect.OPENAI, model)
            assertFalse("temperature" in p || "stop" in p || "top_p" in p || "frequency_penalty" in p, model)
            assertTrue("max_completion_tokens" in p, model)
        }
        assertTrue("temperature" in build(Dialect.OPENAI, "gpt-5-chat-latest")) // das Chat-Modell von gpt-5 kann es
        assertTrue("temperature" in build(Dialect.OPENAI, "gpt-4.1"))
        assertTrue("temperature" in build(Dialect.OPENAI, "chatgpt-4o-latest"))
    }

    @Test
    fun anthropicAndGeminiKeepToStandardFields() {
        val a = build(Dialect.ANTHROPIC, "claude-sonnet-5-5")
        assertEquals(setOf("model", "messages", "stream", "temperature", "max_tokens", "top_p", "stop"), a.keys)
        val g = build(Dialect.GEMINI, "gemini-2.5-flash")
        assertFalse("repeat_penalty" in g || "stream_options" in g)
        assertTrue("frequency_penalty" in g && "max_tokens" in g)
    }

    @Test
    fun providersMapToDialects() {
        assertEquals(Dialect.OPENAI, Providers.forUrl("https://api.openai.com/v1").dialect)
        assertEquals(Dialect.ANTHROPIC, Providers.forUrl("https://api.anthropic.com/v1").dialect)
        assertEquals(Dialect.GEMINI, Providers.forUrl("https://generativelanguage.googleapis.com/v1beta/openai").dialect)
        assertEquals(Dialect.STANDARD, Providers.forUrl("http://192.168.0.10:1234/v1").dialect)
        assertEquals(Dialect.STANDARD, Providers.forUrl("https://irgendein.server.example/v1").dialect)
    }

    @Test
    fun httpErrorsAreExplainedAndShowTheProvidersMessage() {
        val body = """{"error":{"message":"Unsupported parameter: 'max_tokens'","type":"invalid_request_error"}}"""
        assertEquals("Serverfehler 400: Unsupported parameter: 'max_tokens'", ChatClient.httpError(400, body))
        assertTrue(ChatClient.httpError(401, "").startsWith("Nicht autorisiert"))
        assertTrue(ChatClient.httpError(404, """{"error":{"message":"The model `x` does not exist"}}""").contains("does not exist"))
        assertTrue(ChatClient.httpError(429, "").startsWith("Zu viele Anfragen"))
        assertEquals("Serverfehler 502: <html>bad gateway</html>", ChatClient.httpError(502, "<html>bad gateway</html>"))
    }
}
