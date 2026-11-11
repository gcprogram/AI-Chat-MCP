package app.aichatmcp.core

import java.security.MessageDigest
import java.util.Base64

/**
 * Das Avatar-Bild steckt (verkleinert, als JPEG in Base64) im Feld `avatar_data` der Companion-Datei: Companion und Bild bleiben
 * beisammen, auch beim Export, beim Import und beim Weitergeben. Gleiches Format wie am PC (`chat_app/avatar.py`).
 */
object EmbeddedAvatar {
    /** Kantenlänge des eingebetteten Bildes (quadratisch). */
    const val SIZE = 256

    fun encode(jpeg: ByteArray): String = Base64.getEncoder().encodeToString(jpeg)

    /** Die Bildbytes oder null bei leeren/kaputten Daten (Zeilenumbrüche in der Datei sind erlaubt). */
    fun decode(data: String): ByteArray? = try {
        Base64.getDecoder().decode(data.filter { !it.isWhitespace() }).takeIf { it.isNotEmpty() }
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Dateiname für die ausgepackte Kopie: aus dem Inhalt, damit ein geändertes Bild nie ein altes aus dem Zwischenspeicher zeigt. */
    fun cacheName(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(20) + ".img"
}
