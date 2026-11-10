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
    fun strictParseNeedsANameField() {
        assertEquals("Fern", Persona.parseCompanion("""{"name": "Fern"}""", "fern").getOrThrow().name)
        assertNotNull(Persona.parseCompanion("{}", "x").exceptionOrNull()) // leeres Objekt: kein Companion
        assertNotNull(Persona.parseCompanion("""{"servers": {}, "aliases": {}}""", "mcp").exceptionOrNull()) // andere JSON-Datei
        assertNotNull(Persona.parseCompanion("[1, 2]", "x").exceptionOrNull())
        assertNotNull(Persona.parseCompanion("kein json", "x").exceptionOrNull())
    }

    @Test
    fun folderImportCreatesNewSkipsExistingAndReportsInvalidFiles() {
        val dir = kotlin.io.path.createTempDirectory("personas").toFile()
        val store = PersonaStore(dir)
        assertEquals(CompanionImport.Imported("secretary"), store.importFile("secretary.json", """{"name": "Selena", "language": "English"}"""))
        assertEquals("Selena", store.load("secretary").name)
        // zweiter Import derselben Datei überschreibt nicht
        assertEquals(CompanionImport.Exists("secretary"), store.importFile("secretary.json", """{"name": "Anders"}"""))
        assertEquals("Selena", store.load("secretary").name)
        val bad = store.importFile("mcp_servers.json", """{"servers": {}}""")
        assertTrue(bad is CompanionImport.Invalid && bad.file == "mcp_servers.json")
        assertEquals(listOf("secretary"), store.list()) // die ungültige Datei wurde nicht angelegt
        val report = CompanionImport.report(
            listOf(CompanionImport.Imported("fern"), CompanionImport.Exists("secretary"), bad), avatars = 2,
        )
        assertTrue("1 Companion(s) importiert: fern" in report, report)
        assertTrue("Schon vorhanden, nicht überschrieben: secretary" in report, report)
        assertTrue("mcp_servers.json" in report && "2 Avatar-Bild(er)" in report, report)
        assertEquals("Keine Companion-Dateien (.json) im Ordner gefunden.", CompanionImport.report(emptyList(), 0))
    }

    @Test
    fun brokenFilesAreReportedInsteadOfBecomingADefault() {
        assertNotNull(Persona.parse("das ist kein json", "x").exceptionOrNull())
        assertNotNull(Persona.parse("", "x").exceptionOrNull())
        assertNotNull(Persona.parse("[1, 2]", "x").exceptionOrNull())
        assertNull(Persona.parse("{}", "x").exceptionOrNull()) // leeres Objekt ist gültig (alles Vorgabe)
        assertEquals("Mia", Persona.fromJson("kaputt").name) // fromJson (Laden eigener Dateien) bleibt tolerant
    }

    @Test
    fun promptExplainsDuAndIchPerRoleLikeThePythonApp() {
        val german = Persona(name = "Mia").systemPrompt("Alex")
        for (needle in listOf(
            "bedeutet \"ich/mir/mich/mein\" immer Mia", "\"du/dir/dich/dein\" an", "Spiegeln:", "\"Ich bin müde.\" -> \"Du klingst müde.\"",
            "Merke: \"ich\" in deinen Antworten ist immer Mia, \"du\" ist immer Alex.",
        )) assertTrue(needle in german, needle)
        assertTrue("\"Ich\" bist immer du" !in german)
        val english = Persona(name = "Fern", language = "English").systemPrompt("Alex")
        assertTrue("\"I/me/my\" always means Fern and \"you/your\" always means Alex" in english)
        assertTrue("Spiegeln:" !in english && "Merke:" !in english)
    }
}

