package app.aichatmcp

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Zertifikat eines Servers (PEM) samt SHA-256-Fingerabdruck zum Vergleichen. */
data class FetchedCert(val pem: String, val fingerprint: String, val subject: String)

/**
 * Holt das Zertifikat, das ein Server beim TLS-Aufbau vorzeigt (z. B. die Fernbedienungs-App im Heimnetz), damit der
 * Nutzer es nach Prüfung des Fingerabdrucks als einzigen Vertrauensanker speichern kann. Es werden keine Daten
 * gesendet und nichts wird als vertrauenswürdig angenommen - die Verbindung dient nur dem Auslesen.
 */
fun fetchServerCertificate(host: String, port: Int): FetchedCert {
    var chain: Array<X509Certificate>? = null
    val reader = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {
            chain = c
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(reader), null) }
    (context.socketFactory.createSocket(host, port) as SSLSocket).use { socket ->
        socket.soTimeout = 8000
        socket.startHandshake()
    }
    val cert = chain?.firstOrNull() ?: error("Der Server hat kein Zertifikat vorgezeigt.")
    val der = cert.encoded
    val pem = "-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END CERTIFICATE-----\n"
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }
    return FetchedCert(pem, fingerprint, cert.subjectX500Principal.name)
}
