package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedAvatarTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun encodeAndDecodeRoundtrip() {
        assertEquals(jpeg.toList(), EmbeddedAvatar.decode(EmbeddedAvatar.encode(jpeg))!!.toList())
        assertTrue(EmbeddedAvatar.encode(jpeg).startsWith("/9g")) // JPEG-Kopf in Base64
    }

    @Test
    fun lineBreaksInTheFileAreAllowedAndBrokenDataIsNull() {
        val broken = EmbeddedAvatar.encode(jpeg).chunked(4).joinToString("\n")
        assertEquals(jpeg.toList(), EmbeddedAvatar.decode(broken)!!.toList())
        assertNull(EmbeddedAvatar.decode("!!!kein base64!!!"))
        assertNull(EmbeddedAvatar.decode(""))
    }

    @Test
    fun cacheNameFollowsTheContent() {
        val a = EmbeddedAvatar.cacheName(jpeg)
        assertEquals(a, EmbeddedAvatar.cacheName(jpeg.copyOf()))
        assertNotEquals(a, EmbeddedAvatar.cacheName(jpeg + byteArrayOf(9)))
        assertTrue(a.endsWith(".img") && a.length == 24)
    }

    @Test
    fun personaCarriesTheImageInItsFile() {
        val data = EmbeddedAvatar.encode(jpeg)
        val p = Persona(name = "Nyx", avatarData = data, id = "nyx")
        assertTrue("\"avatar_data\"" in p.toJson())
        assertEquals(data, Persona.parse(p.toJson(), "nyx").getOrThrow().avatarData)
        // eine Datei vom PC mit dem Feld laedt, ohne das Feld bleibt es leer
        assertEquals(data, Persona.parse("""{"name":"X","avatar_data":"$data"}""", "x").getOrThrow().avatarData)
        assertEquals("", Persona.parse("""{"name":"X"}""", "x").getOrThrow().avatarData)
    }
}

class EmbeddedAvatarFitTest {
    /** Schein-Kodierer: Größe wächst mit Kantenlänge² und Qualität. */
    private fun render(size: Int, quality: Int) = ByteArray(size * size * quality / 1000)

    @Test
    fun takesTheLargestSizeThatFitsTheBudget() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val out = EmbeddedAvatar.fit(1000) { s, q -> calls += s to q; render(s, q) }
        // 768 px, Qualität 75: 768*768*75/1000 = 44236 Bytes -> Base64 59 kB: passt gleich
        assertEquals(listOf(768 to 75), calls)
        assertTrue(EmbeddedAvatar.base64Length(out.size) <= EmbeddedAvatar.BUDGET_BYTES)
    }

    @Test
    fun lowersQualityBeforeSizeAndNeverScalesUp() {
        val calls = mutableListOf<Pair<Int, Int>>()
        EmbeddedAvatar.fit(600, budgetBytes = 20_000) { s, q -> calls += s to q; render(s, q) }
        assertEquals(576 to 75, calls.first()) // 600 px Quelle: größte Stufe darunter
        assertTrue(calls.all { it.first <= 600 })
        val sizes = calls.map { it.first }.distinct()
        assertEquals(sizes.sortedDescending(), sizes) // erst alle Qualitäten einer Größe, dann die nächste
    }

    @Test
    fun smallestStepIsUsedWhenNothingFits() {
        var last = 0 to 0
        val out = EmbeddedAvatar.fit(2000, budgetBytes = 10) { s, q -> last = s to q; render(s, q) }
        assertEquals(256 to 55, last)
        assertEquals(256 * 256 * 55 / 1000, out.size)
        assertEquals(120 to 55, EmbeddedAvatar.fit(120, budgetBytes = 10) { s, q -> Pair(s, q).also { last = it }; ByteArray(100) }.let { last })
    }
}
