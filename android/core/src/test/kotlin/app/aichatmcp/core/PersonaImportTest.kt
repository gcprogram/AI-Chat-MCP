package app.aichatmcp.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonaImportTest {
    @Test
    fun repoPersonasParseCompletely() {
        val dir = File("../../personas").takeIf { it.isDirectory } ?: File("../personas").takeIf { it.isDirectory } ?: return
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        for (f in files) {
            val p = Persona.parse(f.readText(Charsets.UTF_8), f.nameWithoutExtension).getOrThrow()
            assertTrue(p.name.isNotBlank(), f.name)
        }
        assertTrue(files.isNotEmpty())
    }

    @Test
    fun bomAndWhitespaceAreIgnored() {
        val p = Persona.parse("\uFEFF \n" + """{"name": "Fern", "language": "English"}""", "fern").getOrThrow()
        assertEquals("Fern", p.name)
        assertEquals("English", p.language)
        assertEquals("fern", p.id)
    }

    @Test
    fun brokenFilesAreReportedInsteadOfBecomingADefault() {
        assertNotNull(Persona.parse("das ist kein json", "x").exceptionOrNull())
        assertNotNull(Persona.parse("", "x").exceptionOrNull())
        assertNotNull(Persona.parse("[1, 2]", "x").exceptionOrNull())
        assertNull(Persona.parse("{}", "x").exceptionOrNull()) // leeres Objekt ist gültig (alles Vorgabe)
        assertEquals("Mia", Persona.fromJson("kaputt").name) // fromJson (Laden eigener Dateien) bleibt tolerant
    }
}
