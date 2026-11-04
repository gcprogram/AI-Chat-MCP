package app.aichatmcp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/*
 * MCP-Client über HTTP (Streamable HTTP; Port von mcp_http.py): JSON-RPC per POST, die Antwort kommt als JSON
 * oder als SSE-Strom. Schutz der Zugangsdaten:
 * - Header-Werte dürfen ${NAME} enthalten (der Token steht nie in der Konfigurationsdatei);
 * - http:// ist nur für lokale Adressen erlaubt (localhost, Heimnetz, .local), sonst https://;
 * - ein selbst signiertes Zertifikat wird als einziger Vertrauensanker angegeben;
 * - Weiterleitungen werden nicht verfolgt (sonst ginge der Token an einen fremden Host).
 */

const val HTTP_PROTOCOL_VERSION = "2025-03-26"
private const val MAX_RESPONSE_BYTES = 2_000_000L
private val ENV_RE = Regex("\\$\\{(\\w+)}")

private class SessionLost(message: String) : Exception(message)

fun expandSecrets(text: String, what: String, lookup: (String) -> String?): String =
    ENV_RE.replace(text) { m ->
        lookup(m.groupValues[1])?.takeIf { it.isNotEmpty() }
            ?: throw McpException("Umgebungsvariable ${m.groupValues[1]} ist nicht gesetzt (nötig für $what).")
    }

fun isLocalHost(rawHost: String): Boolean {
    val host = rawHost.trim('[', ']').lowercase()
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
    val literal = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) || ':' in host
    if (!literal) return false
    val address = try {
        InetAddress.getByName(host) // nur Zahlenadressen: keine Namensauflösung
    } catch (_: Exception) {
        return false
    }
    val ula = address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc
    return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || ula
}

fun checkUrl(url: String) {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        null
    }
    val scheme = uri?.scheme?.lowercase()
    val host = uri?.host
    if (uri == null || scheme !in listOf("http", "https") || host.isNullOrEmpty()) {
        throw McpException("Ungültige Server-URL '$url' (erwartet http:// oder https://).")
    }
    if (scheme == "http" && !isLocalHost(host)) {
        throw McpException("'$url': http:// ist nur für lokale Adressen erlaubt, sonst https:// verwenden.")
    }
}

class HttpMcpClient(
    override val name: String,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    /** Zertifikat (PEM) des Servers als einziger Vertrauensanker; null = System-Zertifikate. */
    private val certPem: String? = null,
    private val secret: (String) -> String? = { System.getenv(it) },
    private val base: OkHttpClient = OkHttpClient(),
) : McpClient {
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var ready = false
    private var protocol = HTTP_PROTOCOL_VERSION
    private var resolved: Map<String, String> = emptyMap()
    private val ids = AtomicInteger(0)
    private val lock = Any()

    // --- Lebenszyklus -------------------------------------------------------------------------------------

    override fun start(timeoutSec: Double) {
        checkUrl(url)
        resolved = headers.mapValues { expandSecrets(it.value, "den Header '${it.key}'", secret) }
        val builder = base.newBuilder().followRedirects(false).followSslRedirects(false)
        certPem?.let { pem ->
            try {
                val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray()))
                val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("server", certificate) }
                val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
                val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
                val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
                builder.sslSocketFactory(context.socketFactory, manager)
            } catch (e: Exception) {
                throw McpException("Zertifikat nicht lesbar: ${e.message}", e)
            }
        }
        client = builder.build()
        try {
            handshake(timeoutSec)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun close() {
        val http = client
        val session = sessionId
        client = null
        ready = false
        sessionId = null
        if (http == null || session == null) return
        try { // Sitzung beenden; Server dürfen das ablehnen (405), egal
            val request = Request.Builder().url(url).delete()
            resolved.forEach { (k, v) -> request.header(k, v) }
            http.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
                .newCall(request.header("Mcp-Session-Id", session).build()).execute().close()
        } catch (_: Exception) {
        }
    }

    private fun handshake(timeoutSec: Double) = synchronized(lock) {
        sessionId = null
        ready = false
        val reply = post(
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", ids.incrementAndGet()); put("method", "initialize")
                put("params", buildJsonObject {
                    put("protocolVersion", HTTP_PROTOCOL_VERSION)
                    put("capabilities", JsonObject(emptyMap()))
                    put("clientInfo", buildJsonObject { put("name", "ai-chat-mcp"); put("version", "1.0") })
                })
            },
            timeoutSec,
        )
        (result(reply)["protocolVersion"] as? JsonPrimitive)?.contentOrNull?.let { protocol = it }
        ready = true // ab hier mit MCP-Protocol-Version-Header
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }, timeoutSec)
        Unit
    }

    // --- Protokoll ----------------------------------------------------------------------------------------

    private fun request(method: String, params: JsonObject, timeoutSec: Double): JsonObject {
        if (client == null) throw McpException("Server '$name' ist nicht gestartet.")
        val message = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", ids.incrementAndGet()); put("method", method); put("params", params)
        }
        val reply = try {
            post(message, timeoutSec)
        } catch (_: SessionLost) {
            handshake(timeoutSec) // Sitzung abgelaufen (Server neu gestartet): einmal neu anmelden
            post(message, timeoutSec)
        }
        return result(reply)
    }

    private fun result(reply: JsonObject?): JsonObject {
        val error = reply?.get("error")
        if (error != null && error !is JsonNull) {
            throw McpException(((error as? JsonObject)?.string("message")) ?: error.toString())
        }
        return reply?.obj("result") ?: JsonObject(emptyMap())
    }

    /** Sendet eine Nachricht. Anfragen (mit id) liefern die passende Antwort, Benachrichtigungen null. */
    private fun post(message: JsonObject, timeoutSec: Double): JsonObject? {
        val http = client ?: throw McpException("Server '$name' ist nicht gestartet.")
        val sentSession = sessionId
        val builder = Request.Builder().url(url)
        resolved.forEach { (k, v) -> builder.header(k, v) }
        builder.header("Accept", "application/json, text/event-stream")
        if (sentSession != null) builder.header("Mcp-Session-Id", sentSession)
        if (ready) builder.header("MCP-Protocol-Version", protocol)
        builder.post(message.toString().toRequestBody("application/json".toMediaType()))
        val timed = http.newBuilder().callTimeout((timeoutSec * 1000).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout((timeoutSec * 1000).toLong(), TimeUnit.MILLISECONDS).build()
        val isRequest = "id" in message
        try {
            timed.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return httpError(response, sentSession)
                if (message.string("method") == "initialize") response.header("Mcp-Session-Id")?.let { sessionId = it }
                if (!isRequest) return null // Benachrichtigung: 202 ohne Inhalt
                return readReply(response, message["id"]!!)
            }
        } catch (e: SSLException) {
            val hint = if (certPem == null) " Zertifikat nicht vertrauenswürdig: Zertifikat der App importieren." else ""
            throw McpException("Server '$name' nicht erreichbar: ${e.message}.$hint", e)
        } catch (e: IOException) { // Zeitüberschreitung, Verbindungsabbruch
            throw McpException("Server '$name' nicht erreichbar: ${e.message}", e)
        }
    }

    private fun httpError(response: Response, sentSession: String?): JsonObject {
        val body = response.body?.source()?.let { s -> s.request(4096); s.buffer.readUtf8(minOf(s.buffer.size, 4096)) }.orEmpty()
        try { // manche Server melden Fehler als JSON-RPC-Antwort mit HTTP-Fehlercode
            val parsed = AppJson.parseToJsonElement(body)
            if (parsed is JsonObject && "error" in parsed) return parsed
        } catch (_: Exception) {
        }
        if (response.code == 404 && sentSession != null) throw SessionLost("Sitzung bei '$name' abgelaufen.")
        val hint = mapOf(401 to " (Token falsch oder fehlt?)", 403 to " (Zugriff verweigert)", 404 to " (falsche URL?)")[response.code].orEmpty()
        throw McpException("Server '$name': HTTP ${response.code}$hint.")
    }

    private fun readReply(response: Response, requestId: JsonElement): JsonObject {
        val body = response.body ?: throw McpException("Server '$name' hat keine gültige Antwort geliefert.")
        val source = body.source()
        if (response.header("Content-Type").orEmpty().lowercase().startsWith("text/event-stream")) {
            val data = mutableListOf<String>()
            var total = 0L
            while (true) {
                val line = source.readUtf8Line() ?: break
                total += line.length + 1
                if (total > MAX_RESPONSE_BYTES) throw McpException("Antwort von '$name' zu groß.")
                if (line.startsWith("data:")) {
                    data += line.substring(5).let { if (it.startsWith(" ")) it.substring(1) else it }
                } else if (line.isEmpty() && data.isNotEmpty()) {
                    val found = match(data.joinToString("\n"), requestId)
                    data.clear()
                    if (found != null) return found
                }
            }
            if (data.isNotEmpty()) match(data.joinToString("\n"), requestId)?.let { return it }
            throw McpException("Server '$name' hat nicht geantwortet (Strom ohne Ergebnis).")
        }
        source.request(MAX_RESPONSE_BYTES + 1)
        if (source.buffer.size > MAX_RESPONSE_BYTES) throw McpException("Antwort von '$name' zu groß.")
        return match(source.buffer.readUtf8(), requestId)
            ?: throw McpException("Server '$name' hat keine gültige Antwort geliefert.")
    }

    /** Findet die Antwort auf requestId (einzelne Nachricht oder Batch); Benachrichtigungen werden übergangen. */
    private fun match(text: String, requestId: JsonElement): JsonObject? {
        val payload = try {
            AppJson.parseToJsonElement(text)
        } catch (_: Exception) {
            return null
        }
        for (message in if (payload is JsonArray) payload else listOf(payload)) {
            if (message is JsonObject && message["id"] == requestId && ("result" in message || "error" in message)) return message
        }
        return null
    }

    // --- Werkzeuge ----------------------------------------------------------------------------------------

    override fun listTools(timeoutSec: Double): List<McpTool> {
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        repeat(20) { // Seitenlimit gegen Endlosschleifen
            val result = request("tools/list", if (cursor != null) buildJsonObject { put("cursor", cursor) } else JsonObject(emptyMap()), timeoutSec)
            for (t in (result["tools"] as? JsonArray).orEmpty()) {
                val o = t as? JsonObject ?: continue
                val n = o.string("name") ?: continue
                tools += McpTool(
                    n, o.string("description").orEmpty(), o.obj("inputSchema"),
                    (o.obj("annotations")?.get("readOnlyHint") as? JsonPrimitive)?.contentOrNull == "true",
                )
            }
            cursor = result.string("nextCursor")
            if (cursor.isNullOrEmpty()) return tools
        }
        return tools
    }

    override fun callTool(name: String, arguments: Map<String, Any?>, timeoutSec: Double): Pair<String, Boolean> =
        toolResultText(request("tools/call", buildJsonObject {
            put("name", name); put("arguments", arguments.toJsonElement())
        }, timeoutSec))
}
