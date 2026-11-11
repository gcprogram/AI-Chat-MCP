package app.aichatmcp.core

import java.security.MessageDigest
import java.util.Base64

/**
 * Das Avatar-Bild steckt (verkleinert, als JPEG in Base64) im Feld `avatar_data` der Companion-Datei: Companion und Bild bleiben
 * beisammen, auch beim Export, beim Import und beim Weitergeben. Gleiches Format wie am PC (`chat_app/avatar.py`).
 */
object EmbeddedAvatar {
    /**
     * Das eingebettete Bild dient der kleinen Anzeige und der Vollbildansicht. Es wird so groß wie möglich gespeichert, aber höchstens
     * [BUDGET_BYTES] als Base64 in der Companion-Datei: erst die größte Kantenlänge mit gutem JPEG, dann kleiner, dann schlechtere Qualität.
     */
    val SIZES = listOf(768, 704, 640, 576, 512, 448, 384, 320, 256)
    val QUALITIES = listOf(75, 70, 65, 60, 55)
    const val BUDGET_BYTES = 100 * 1024

    /**
     * Sucht die erste Stufe (Kantenlänge, Qualität), deren Ergebnis als Base64 ins Budget passt. [maxSide] = kürzere Seite des Bildes (nichts
     * wird hochgerechnet). [render] liefert die JPEG-Bytes für (Kantenlänge, Qualität). Passt nichts, kommt die kleinste Stufe.
     */
    fun fit(maxSide: Int, budgetBytes: Int = BUDGET_BYTES, render: (Int, Int) -> ByteArray): ByteArray {
        val sizes = SIZES.filter { it <= maxSide }.ifEmpty { listOf(maxSide) }
        var last = ByteArray(0)
        for (size in sizes) {
            for (quality in QUALITIES) {
                last = render(size, quality)
                if (base64Length(last.size) <= budgetBytes) return last
            }
        }
        return last
    }

    /** Länge des Base64-Textes zu [bytes] Bytes (mit Auffüllung). */
    fun base64Length(bytes: Int): Int = (bytes + 2) / 3 * 4

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
