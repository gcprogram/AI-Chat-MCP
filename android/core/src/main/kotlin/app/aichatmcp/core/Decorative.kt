package app.aichatmcp.core

/**
 * Erkennt Bilder, die nur Zierde sind (Rahmenteile, Trennlinien, Symbole), damit das Bildmodell sie nicht beschreiben muss und der
 * Vorleser nicht minutenlang schweigt. Gelesen wird nur der Kopf der Bilddatei (Größe), nichts wird dekodiert.
 *
 * Merkmale: winzige Datei, kleine Kantenlänge, extrem schmale Streifen (Seitenverhältnis), im HTML als Dekoration markiert
 * (`role="presentation"`, `aria-hidden`) oder dasselbe Bild mehrfach im Dokument (Rahmen auf jeder Seite). Nicht erkannt werden
 * Rahmen als CSS-Hintergrund (kein Bild im Text) und große, nur zierende Bilder: die werden normal beschrieben.
 */
object Decorative {
    const val MIN_BYTES = 2048
    const val MIN_SIDE = 100
    const val MAX_ASPECT = 6.0

    /** So oft kann dasselbe Bild vorkommen, bevor es als Zierde gilt (Kapitelornament, Rahmenteil, Logo pro Seite). */
    const val MIN_REPEATS = 3

    /** Warum das Bild Zierde ist (kurz, für Protokoll und Tests) oder null, wenn es beschrieben werden soll. */
    fun reason(bytes: ByteArray): String? {
        if (bytes.size < MIN_BYTES) return "winzige Datei"
        val (w, h) = size(bytes) ?: return null // unbekanntes Format: im Zweifel beschreiben
        val small = minOf(w, h)
        if (small < MIN_SIDE) return "klein (${w}x$h)"
        if (maxOf(w, h).toDouble() / small > MAX_ASPECT) return "Streifen (${w}x$h)"
        return null
    }

    /** Breite und Höhe aus dem Dateikopf (PNG, GIF, JPEG, WebP); null, wenn das Format nicht erkannt wird. */
    fun size(b: ByteArray): Pair<Int, Int>? {
        fun u8(i: Int) = if (i in b.indices) b[i].toInt() and 0xFF else -1
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun le24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
        fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
        fun ascii(i: Int, s: String) = s.indices.all { u8(i + it) == s[it].code }
        val result: Pair<Int, Int>? = when {
            b.size >= 24 && u8(0) == 0x89 && ascii(1, "PNG") -> be32(16) to be32(20)
            b.size >= 10 && ascii(0, "GIF8") -> le16(6) to le16(8)
            b.size >= 4 && u8(0) == 0xFF && u8(1) == 0xD8 -> jpeg(b)
            b.size >= 30 && ascii(0, "RIFF") && ascii(8, "WEBP") -> when {
                ascii(12, "VP8 ") -> (le16(26) and 0x3FFF) to (le16(28) and 0x3FFF)
                ascii(12, "VP8L") -> {
                    val bits = u8(21) or (u8(22) shl 8) or (u8(23) shl 16) or (u8(24) shl 24)
                    ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
                }
                ascii(12, "VP8X") -> (le24(24) + 1) to (le24(27) + 1)
                else -> null
            }
            else -> null
        }
        return result?.takeIf { it.first > 0 && it.second > 0 }
    }

    /** JPEG: die Marker durchlaufen bis zum Rahmenkopf (SOFn), der Höhe und Breite enthält. */
    private fun jpeg(b: ByteArray): Pair<Int, Int>? {
        fun u8(i: Int) = if (i in b.indices) b[i].toInt() and 0xFF else -1
        var i = 2
        while (i + 8 < b.size) {
            if (u8(i) != 0xFF) { i++; continue }
            val marker = u8(i + 1)
            when {
                marker == 0xFF -> i++ // Füllbyte
                marker == 0x01 || marker in 0xD0..0xD9 -> i += 2 // Marker ohne Länge
                marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC ->
                    return ((u8(i + 7) shl 8) or u8(i + 8)) to ((u8(i + 5) shl 8) or u8(i + 6))
                else -> i += 2 + ((u8(i + 2) shl 8) or u8(i + 3)).coerceAtLeast(2)
            }
        }
        return null
    }
}
