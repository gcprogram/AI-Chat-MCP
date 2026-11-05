package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecorativeTest {
    private fun pad(b: ByteArray, to: Int = 4096) = b + ByteArray((to - b.size).coerceAtLeast(0))
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le24(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte())

    private fun png(w: Int, h: Int, size: Int = 4096) =
        pad(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13) + "IHDR".toByteArray() + be32(w) + be32(h), size)

    private fun gif(w: Int, h: Int) = pad("GIF89a".toByteArray() + le16(w) + le16(h))

    private fun jpeg(w: Int, h: Int) =
        pad(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16) + ByteArray(14) +
            byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8) + be16(h) + be16(w) + byteArrayOf(3))

    private fun webpX(w: Int, h: Int) = pad("RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + ByteArray(4) + ByteArray(4) + le24(w - 1) + le24(h - 1))

    private fun webpLossy(w: Int, h: Int) =
        pad("RIFF".toByteArray() + ByteArray(4) + "WEBPVP8 ".toByteArray() + ByteArray(4) + ByteArray(3) + byteArrayOf(0x9D.toByte(), 1, 0x2A) + le16(w) + le16(h))

    private fun webpLossless(w: Int, h: Int): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14)
        return pad("RIFF".toByteArray() + ByteArray(4) + "WEBPVP8L".toByteArray() + ByteArray(4) + byteArrayOf(0x2F) +
            byteArrayOf(bits.toByte(), (bits ushr 8).toByte(), (bits ushr 16).toByte(), (bits ushr 24).toByte()))
    }

    @Test
    fun sizeIsReadFromTheHeaderOfPngGifJpegAndWebp() {
        assertEquals(640 to 480, Decorative.size(png(640, 480)))
        assertEquals(31 to 7, Decorative.size(gif(31, 7)))
        assertEquals(1024 to 768, Decorative.size(jpeg(1024, 768)))
        assertEquals(300 to 200, Decorative.size(webpX(300, 200)))
        assertEquals(321 to 123, Decorative.size(webpLossy(321, 123)))
        assertEquals(400 to 90, Decorative.size(webpLossless(400, 90)))
        assertNull(Decorative.size(ByteArray(3000) { 7 }))
        assertNull(Decorative.size(png(0, 0)))
        assertNull(Decorative.size(ByteArray(5))) // zu kurz, kein Absturz
    }

    @Test
    fun jpegMarkersBeforeTheFrameHeaderAreSkipped() {
        // Kommentar (FE) und Huffman-Tabelle (C4) vor dem Rahmenkopf: C4 darf nicht für den Rahmenkopf gehalten werden
        val b = pad(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0, 6, 1, 2, 3, 4, 0xFF.toByte(), 0xC4.toByte(), 0, 5, 9, 9, 9) +
                byteArrayOf(0xFF.toByte(), 0xC2.toByte(), 0, 17, 8) + be16(500) + be16(700) + byteArrayOf(3),
        )
        assertEquals(700 to 500, Decorative.size(b))
    }

    @Test
    fun ornamentsAreRecognizedBySizeShapeAndFileSize() {
        assertNull(Decorative.reason(png(800, 600)))
        assertNull(Decorative.reason(jpeg(1200, 900)))
        assertNull(Decorative.reason(png(100, 100))) // genau an der Grenze: wird beschrieben
        assertNotNull(Decorative.reason(png(64, 64))) // Symbol, Rahmenecke
        assertNotNull(Decorative.reason(gif(12, 600))) // Rahmenseite
        assertTrue(Decorative.reason(png(1200, 150))!!.startsWith("Streifen"))
        assertTrue(Decorative.reason(png(800, 600, size = 500))!!.startsWith("winzige"))
        assertNull(Decorative.reason(ByteArray(5000) { 7 })) // unbekanntes Format: im Zweifel beschreiben
    }
}
