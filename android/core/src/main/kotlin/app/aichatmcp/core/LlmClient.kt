package app.aichatmcp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Streaming-Client für OpenAI-kompatible Chat-Endpunkte (LM Studio, OpenAI, OpenRouter, ...). */
class LlmException(message: String) : Exception(message)

sealed interface StreamEvent {
    data class Content(val text: String) : StreamEvent
    data class Reasoning(val text: String) : StreamEvent
    data class Usage(val promptTokens: Int) : StreamEvent
}

/** Eine Nachricht, wie sie an die Chat-API geht. */
data class WireMessage(val role: String, val content: String)

/**
 * Abbruch von außen: unterbricht die laufende Verbindung (auch beim Warten aufs erste Token).
 * cancel() ist der Stopp-Knopf des Nutzers, stop() das stille Beenden durch die Engine (Schleife, Werkzeugaufruf).
 */
class CancelToken {
    @Volatile
    var cancelled = false
        private set

    @Volatile
    var stopped = false
        private set

    @Volatile
    internal var call: Call? = null

    fun stop() {
        stopped = true
        call?.cancel()
    }

    fun cancel() {
        cancelled = true
        stop()
    }
}

class ChatClient(baseUrl: String, private val apiKey: String = "", http: OkHttpClient = OkHttpClient()) {
    private val baseUrl = baseUrl.trimEnd('/')

    // Lese-Timeout großzügig: LM Studio lädt das Modell beim ersten Request nach.
    private val client = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()

    private fun request(path: String) = Request.Builder().url("$baseUrl$path").apply {
        if (apiKey.isNotEmpty()) header("Authorization", "Bearer $apiKey")
    }

    fun listModels(): List<String> {
        val url = "$baseUrl/models"
        val response = try {
            client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build().newCall(request("/models").build()).execute()
        } catch (e: IOException) {
            throw LlmException("Server nicht erreichbar ($url): ${e.message}")
        }
        response.use {
            val body = it.body?.string().orEmpty()
            if (it.code == 401) throw LlmException("Nicht autorisiert - API-Schlüssel prüfen.")
            if (it.code != 200) throw LlmException("Serverfehler ${it.code}: ${body.take(300)}")
            val ids = try {
                ((AppJson.parseToJsonElement(body) as JsonObject)["data"] as JsonArray)
                    .map { m -> ((m as JsonObject)["id"] as JsonPrimitive).content }
            } catch (_: Exception) {
                throw LlmException("Unerwartetes Antwortformat: ${body.take(200)}")
            }
            return ids.filter { id -> "embed" !in id.lowercase() }
        }
    }

    fun streamChat(
        model: String, messages: List<WireMessage>, temperature: Double, maxTokens: Int,
        stop: List<String> = emptyList(), cancel: CancelToken = CancelToken(), extra: Map<String, Double> = emptyMap(),
    ): Flow<StreamEvent> = flow {
        val payload = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                for (m in messages) add(buildJsonObject { put("role", m.role); put("content", m.content) })
            }
            put("stream", true)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            putJsonObject("stream_options") { put("include_usage", true) }
            for ((k, v) in extra) put(k, v) // z. B. top_p, frequency_penalty, repeat_penalty
            if (stop.isNotEmpty()) putJsonArray("stop") { stop.forEach { add(JsonPrimitive(it)) } }
        }
        val url = "$baseUrl/chat/completions"
        val call = client.newCall(
            request("/chat/completions").post(payload.toString().toRequestBody("application/json".toMediaType())).build(),
        )
        cancel.call = call
        if (cancel.stopped) return@flow
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (cancel.stopped) return@flow
            throw LlmException("Server nicht erreichbar ($url): ${e.message}")
        }
        response.use { r ->
            if (r.code == 401) throw LlmException("Nicht autorisiert - API-Schlüssel prüfen.")
            if (r.code != 200) throw LlmException("Serverfehler ${r.code}: ${r.body?.string().orEmpty().take(300)}")
            val source = r.body!!.source()
            try {
                while (!cancel.stopped) {
                    val line = (source.readUtf8Line() ?: break).trim() // UTF-8 selbst lesen: SSE hat kein Charset
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    for (event in events(data)) emit(event)
                }
            } catch (e: IOException) {
                if (cancel.stopped) return@use
                throw LlmException("Verbindung während der Antwort abgebrochen: ${e.message}")
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        internal fun events(data: String): List<StreamEvent> {
            val chunk = try {
                AppJson.parseToJsonElement(data) as? JsonObject ?: return emptyList()
            } catch (_: Exception) {
                return emptyList()
            }
            chunk["error"]?.takeIf { it !is JsonNull }?.let { throw LlmException("Serverfehler: $it") }
            val out = mutableListOf<StreamEvent>()
            for (choice in (chunk["choices"] as? JsonArray).orEmpty()) {
                val delta = (choice as? JsonObject)?.obj("delta") ?: continue
                val reasoning = (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                    ?: (delta["reasoning"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                if (reasoning != null) out += StreamEvent.Reasoning(reasoning)
                (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { out += StreamEvent.Content(it) }
            }
            chunk.obj("usage")?.let { u ->
                (u["prompt_tokens"] as? JsonPrimitive)?.intOrNull?.let { out += StreamEvent.Usage(it) }
            }
            return out
        }
    }
}
