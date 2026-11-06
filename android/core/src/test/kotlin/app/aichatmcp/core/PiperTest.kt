package app.aichatmcp.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiperTest {
    private fun tempDir(): File = kotlin.io.path.createTempDirectory("piper").toFile()

    @Test
    fun languageComesFromJsonOrFolderName() {
        assertEquals("de", PiperNames.languageOf("x", """{"language": {"code": "de_DE", "family": "de"}}"""))
        assertEquals("en", PiperNames.languageOf("x", """{"language":{"code":"en-US"}}"""))
        assertEquals("de", PiperNames.languageOf("vits-piper-de_DE-thorsten-medium-int8", null))
        assertEquals("fr", PiperNames.languageOf("fr_FR-meine", "{}"))
        assertNull(PiperNames.languageOf("meine-stimme", null))
    }

    @Test
    fun namesAreSafeAndPrefixed() {
        assertEquals("a_b", PiperNames.safeName("a/b"))
        assertEquals("stimme", PiperNames.safeName("..\\.."))
        assertTrue(PiperNames.isPiper(PiperVoice("ich", "de").id))
        assertEquals("ich", PiperNames.nameOf("piper:ich"))
        assertFalse(PiperNames.isPiper("de-de-x-nfh-local"))
    }

    @Test
    fun storeNeedsModelTokensAndSharedEspeakData() {
        val root = tempDir()
        val store = PiperStore(root)
        assertTrue(store.list().isEmpty())
        store.importFile("ich", "de_DE-ich.onnx", "m".byteInputStream())
        assertEquals("tokens.txt", store.problem("ich")?.substringBefore(" fehlt"))
        store.importFile("ich", "tokens.txt", "t".byteInputStream())
        assertTrue(store.problem("ich")!!.contains("espeak-ng-data"))
        assertNull(store.files("ich")) // ohne Aussprachedaten nicht ladbar
        assertTrue(store.importFile("ich", "espeak-ng-data/phontab", "p".byteInputStream()))
        assertTrue(store.importFile("ich", "espeak-ng-data/voices/de", "v".byteInputStream()))
        assertNull(store.problem("ich"))
        assertNotNull(store.files("ich"))
        assertEquals(listOf(PiperVoice("ich", null)), store.list()) // ohne .onnx.json und ohne Sprache im Namen: unbekannt
        store.importFile("ich", "de_DE-ich.onnx.json", """{"language": {"code": "de_DE"}}""".byteInputStream())
        assertEquals(listOf(PiperVoice("ich", "de")), store.list())
        // zweite Stimme: die gemeinsamen Daten werden nicht noch einmal kopiert
        assertFalse(store.importFile("zwei", "espeak-ng-data/phontab", "p".byteInputStream(), skipShared = store.espeakReady()))
        assertFalse(File(root, "zwei/espeak-ng-data").exists())
        store.delete("ich")
        assertTrue(store.list().isEmpty())
        assertTrue(store.espeakReady()) // gemeinsame Daten bleiben
    }

    @Test
    fun importRejectsPathsOutsideTheStore() {
        val store = PiperStore(tempDir())
        assertFalse(store.importFile("ich", "../boese.txt", "x".byteInputStream()))
        assertFalse(store.importFile("ich", "a/../../b.txt", "x".byteInputStream()))
        assertFalse(store.importFile("ich", "", "x".byteInputStream()))
    }
}
