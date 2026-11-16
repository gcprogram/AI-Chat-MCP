package app.aichatmcp.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoldersTest {
    private fun temp(): File = kotlin.io.path.createTempDirectory("folders").toFile()

    @Test
    fun copiesOnlyMissingFilesAndKeepsExisting() {
        val a = temp()
        val b = temp()
        File(a, "x.json").writeText("neu")
        File(a, "sub").mkdirs()
        File(a, "sub/y.png").writeText("bild")
        File(a, "z.tmp").writeText("halb")
        File(b, "x.json").writeText("alt")
        assertEquals(1, Folders.copyMissing(a, b))
        assertEquals("alt", File(b, "x.json").readText())
        assertEquals("bild", File(b, "sub/y.png").readText())
        assertTrue(!File(b, "z.tmp").exists())
        assertEquals(0, Folders.copyMissing(a, b))
        assertEquals(0, Folders.copyMissing(a, a))
        a.deleteRecursively(); b.deleteRecursively()
    }

    @Test
    fun modelsListsOnlyChatGguf() {
        val d = temp()
        listOf("B.gguf", "a.GGUF", "mmproj-x.gguf", "note.txt", "c.gguf.part").forEach { File(d, it).writeText("x") }
        assertEquals(listOf("a.GGUF", "B.gguf"), Folders.models(d).map { it.name })
        d.deleteRecursively()
    }
}
