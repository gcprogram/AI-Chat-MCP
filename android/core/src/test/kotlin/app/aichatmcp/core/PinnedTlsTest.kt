package app.aichatmcp.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Zertifikat-Pinning: Geprüft wird der öffentliche Schlüssel. Ein neu ausgestelltes Zertifikat desselben Schlüssels
 * (die App tut das bei neuen IP-Adressen) wird akzeptiert, auch mit anderem Namen; ein anderer Schlüssel nicht.
 */
class PinnedTlsTest {
    private val dir = Files.createTempDirectory("pin").toFile()
    private var server: HttpsServer? = null
    private val password = "changeit"

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        dir.deleteRecursively()
    }

    private fun keytool(vararg args: String) {
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
        val process = ProcessBuilder(listOf(File(System.getProperty("java.home"), "bin/$exe").path) + args)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    private fun newKeystore(name: String, san: String): File {
        val file = File(dir, "$name.p12")
        keytool("-genkeypair", "-alias", "k", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Test", "-ext", "san=$san",
            "-validity", "30", "-keystore", file.path, "-storetype", "PKCS12", "-storepass", password)
        return file
    }

    private fun exportPem(keystore: File): String {
        val out = File(dir, keystore.nameWithoutExtension + ".pem")
        keytool("-exportcert", "-rfc", "-alias", "k", "-keystore", keystore.path, "-storepass", password, "-file", out.path)
        return out.readText()
    }

    private fun serve(keystore: File): String {
        val store = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, password.toCharArray()) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password.toCharArray()) }
        val https = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        https.httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) })
        https.createContext("/mcp") { exchange ->
            val id = Regex("\"id\":(\\d+)").find(exchange.requestBody.readBytes().decodeToString())?.groupValues?.get(1)
            if (id == null) {
                exchange.sendResponseHeaders(202, -1)
            } else {
                val body = """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-03-26"}}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        https.start()
        server = https
        return "https://127.0.0.1:${https.address.port}/mcp"
    }

    @Test
    fun reissuedCertificateWithTheSameKeyIsAccepted() {
        val keystore = newKeystore("app", "ip:127.0.0.1")
        val pinned = exportPem(keystore)
        // wie die App bei neuer IP-Adresse: gleicher Schlüssel, neues Zertifikat, die alte Adresse steht nicht mehr drin
        keytool("-selfcert", "-alias", "k", "-dname", "CN=Test", "-ext", "san=ip:10.9.8.7", "-validity", "30",
            "-keystore", keystore.path, "-storepass", password)
        val url = serve(keystore)
        HttpMcpClient("pin", url, certPem = pinned).apply { start(5.0) }.close()
    }

    @Test
    fun anotherKeyIsRejectedBeforeAnythingIsSent() {
        val url = serve(newKeystore("app", "ip:127.0.0.1"))
        val other = exportPem(newKeystore("other", "ip:127.0.0.1"))
        val error = assertFailsWith<McpException> {
            HttpMcpClient("pin", url, mapOf("Authorization" to "Bearer geheim"), certPem = other).start(5.0)
        }
        assertTrue("Schlüssel" in error.message.orEmpty(), error.message)
    }

    @Test
    fun unreadableCertificateIsReported() {
        val error = assertFailsWith<McpException> { HttpMcpClient("pin", "https://127.0.0.1:1/mcp", certPem = "kein pem").start(2.0) }
        assertTrue("Zertifikat" in error.message.orEmpty(), error.message)
    }
}
