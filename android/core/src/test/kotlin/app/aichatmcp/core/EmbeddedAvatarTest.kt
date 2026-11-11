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
