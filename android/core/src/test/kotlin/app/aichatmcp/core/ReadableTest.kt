package app.aichatmcp.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadableTest {
    private val noPdf: (ByteArray) -> List<String> = { error("kein PDF erwartet") }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in files) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun epub(): ByteArray {
        val container = """<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<package><manifest>
            <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
            <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml"/>
            <item id="img" href="images/a%20b.png" media-type="image/png"/></manifest>
            <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="nav" linear="no"/></spine></package>"""
        val ch1 = """<html><head><title>x</title><style>p { color: red }</style></head><body><h1>Kapitel 1</h1>
            <p>Erster Absatz mit Bild <img src="images/a%20b.png" alt="Ein Hafen"/> und &auml;&#x20AC; Rest.</p><p>Zweiter Absatz.</p></body></html>"""
        val ch2 = """<html><body><p>Kapitel zwei beginnt hier.</p><div><img src="../OEBPS/images/a%20b.png"/></div></body></html>"""
        return zip(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(),
            "OEBPS/ch1.xhtml" to ch1.toByteArray(),
            "OEBPS/ch2.xhtml" to ch2.toByteArray(),
            "OEBPS/nav.xhtml" to "<html><body><p>Inhaltsverzeichnis</p></body></html>".toByteArray(),
            "OEBPS/images/a b.png" to byteArrayOf(1, 2, 3),
        )
    }

    @Test
    fun htmlBecomesParagraphsAndImagesComeAfterTheirParagraph() {
        val html = """<html><head><script>var a = 1 < 2;</script><title>T</title></head><body>
            <h1>Titel</h1><p>Text <b>fett</b> mit<br>Umbruch und <img src="x.png" alt="Bild A"> Bild.</p>
            <!-- Kommentar --><p>&Uuml;ber &amp; unter &#8211; &unbekannt;</p></body></html>"""
        val blocks = Html.blocks(html) { "res:$it" }
        assertEquals(
            listOf(
                Block.Para("Titel"), Block.Para("Text fett mit"), Block.Para("Umbruch und Bild."),
                Block.Img("res:x.png", "Bild A"), Block.Para("Über & unter – &unbekannt;"),
            ),
            blocks,
        )
    }

    @Test
    fun epubReadsChaptersInSpineOrderWithImages() {
        val doc = Epub.read(epub())
        assertEquals(2, doc.pages.size) // nav.xhtml hat linear="no"
        assertEquals(Block.Para("Kapitel 1"), doc.pages[0][0])
        assertEquals(Block.Para("Erster Absatz mit Bild und ä€ Rest."), doc.pages[0][1])
        val img = doc.pages[0][2] as Block.Img
        assertEquals("OEBPS/images/a b.png", img.ref)
        assertEquals("Ein Hafen", img.alt)
        assertContentEquals(byteArrayOf(1, 2, 3), doc.images(img.ref))
        assertEquals(Block.Para("Kapitel zwei beginnt hier."), doc.pages[1][0])
        assertEquals("OEBPS/images/a b.png", (doc.pages[1][1] as Block.Img).ref)
        assertNull(doc.images("gibt/es/nicht.png"))
        assertFalse("Inhaltsverzeichnis" in doc.text)
    }

    @Test
    fun brokenEpubFilesGiveClearErrors() {
        assertTrue(assertFailsWith<DocumentException> { Epub.read("kein zip".toByteArray()) }.message!!.contains("container.xml"))
        assertTrue(assertFailsWith<DocumentException> { Epub.read(zip("a.txt" to byteArrayOf(1))) }.message!!.contains("container.xml"))
        val noSpine = zip(
            "META-INF/container.xml" to """<rootfile full-path="c.opf"/>""".toByteArray(),
            "c.opf" to "<package><manifest></manifest><spine></spine></package>".toByteArray(),
        )
        assertTrue(assertFailsWith<DocumentException> { Epub.read(noSpine) }.message!!.contains("Kapitel"))
    }

    @Test
    fun documentsRecognizeEpubAndHtmlFiles() {
        assertEquals(DocKind.EPUB, Documents.kindOf("buch.EPUB"))
        assertEquals(DocKind.HTML, Documents.kindOf("seite.html"))
        assertEquals(DocKind.HTML, Documents.kindOf("seite", "text/html"))
        val doc = Documents.read("buch.epub", DocKind.EPUB, epub(), noPdf)
        assertTrue(doc.text.startsWith("Kapitel 1\n\nErster Absatz"))
        val html = "<p>Eins.</p><img src=\"data:image/png;base64,AQID\" alt=\"x\"><img src=\"rel.png\" alt=\"y\"><p>Zwei.</p>"
        val page = Documents.read("a.html", DocKind.HTML, html.toByteArray(), noPdf)
        val blocks = page.readable!!.pages.single()
        assertContentEquals(byteArrayOf(1, 2, 3), page.readable!!.images((blocks[1] as Block.Img).ref))
        assertEquals("", (blocks[2] as Block.Img).ref) // relative Bilder einer einzelnen HTML-Datei sind nicht ladbar
    }

    @Test
    fun planKeepsReadingOrderAndPageStarts() {
        val doc = Documents.read("buch.epub", DocKind.EPUB, epub(), noPdf)
        val plan = ReadPlan.of(doc)
        val kinds = plan.items.map { if (it is ReadItem.Speech) "S" else "P" }
        assertEquals(listOf("S", "P", "S", "S", "P"), kinds) // Kapitel 1: Text, Bild, Text; Kapitel 2: Text, Bild
        assertEquals(listOf(0, 3), plan.pageStarts)
        assertTrue(plan.hasPages)
        assertEquals(0, plan.pageOf(2))
        assertEquals(1, plan.pageOf(3))
        assertEquals(0, plan.backTarget(4)) // Seite zurück: Anfang der vorigen Seite
        assertEquals(0, plan.backTarget(0))
        assertEquals(3, plan.forwardTarget(0))
        assertNull(plan.forwardTarget(4)) // hinter dem Ende
    }

    @Test
    fun withoutPagesTheJumpIsTenChunks() {
        val sentence = "Dies ist ein Satz, der etwas länger ist. "
        val doc = DocText("a.txt", DocKind.TEXT, sentence.repeat(300))
        val plan = ReadPlan.of(doc)
        assertFalse(plan.hasPages)
        assertTrue(plan.size > 25)
        assertTrue(plan.items.all { it is ReadItem.Speech && it.text.length <= ReadPlan.CHUNK_CHARS })
        assertEquals(5, plan.backTarget(15))
        assertEquals(0, plan.backTarget(4))
        assertEquals(25, plan.forwardTarget(15))
        assertNull(plan.forwardTarget(plan.size - 3))
    }
}
