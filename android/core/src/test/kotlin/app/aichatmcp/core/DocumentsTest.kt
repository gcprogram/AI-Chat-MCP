package app.aichatmcp.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentsTest {
    private val noPdf: (ByteArray) -> List<String> = { error("kein PDF erwartet") }

    private fun docx(body: String, entry: String = "word/document.xml"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml")); zip.write("<Types/>".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(entry)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun fileTypesAreRecognized() {
        assertEquals(DocKind.PDF, Documents.kindOf("Bericht.PDF"))
        assertEquals(DocKind.DOCX, Documents.kindOf("a.docx"))
        assertEquals(DocKind.MARKDOWN, Documents.kindOf("README.md"))
        assertEquals(DocKind.TEXT, Documents.kindOf("notiz.txt"))
        assertEquals(DocKind.TEXT, Documents.kindOf("ohne-endung", "text/plain"))
        assertEquals(DocKind.IMAGE, Documents.kindOf("bild.PNG"))
        assertEquals(DocKind.IMAGE, Documents.kindOf("foto", "image/jpeg"))
        assertNull(Documents.kindOf("film.mp4", "video/mp4"))
        assertTrue(assertFailsWith<DocumentException> { Documents.read("a.jpg", DocKind.IMAGE, ByteArray(3), noPdf) }.message!!.contains("Bildmodell"))
        assertEquals("[Bild: a.jpg - Beschreibung des Bildmodells]\nEine Katze.", Documents.imageInputText("a.jpg", " Eine Katze.\n"))
    }

    @Test
    fun plainTextHandlesBomAndOldEncodings() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Grüße".toByteArray(Charsets.UTF_8)
        assertEquals("Grüße", Documents.read("a.txt", DocKind.TEXT, utf8, noPdf).text)
        val latin = "Größe".toByteArray(charset("windows-1252")) // kein gültiges UTF-8
        assertEquals("Größe", Documents.read("b.txt", DocKind.TEXT, latin, noPdf).text)
        assertEquals("a\n\nb", Documents.read("c.md", DocKind.MARKDOWN, "a\r\n\r\n\r\n\r\nb  ".toByteArray(), noPdf).text)
    }

    @Test
    fun docxParagraphsTabsAndEntities() {
        val xml = """<w:document><w:body>
            <w:p><w:r><w:t>Titel &amp; Co</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Zeile</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>zwei</w:t></w:r><w:r><w:br/></w:r><w:r><w:t>drei &#228;&#x00F6;</w:t></w:r></w:p>
            <w:p><w:r><w:instrText> PAGEREF _Toc1 </w:instrText></w:r><w:r><w:t>Ende</w:t></w:r></w:p>
            </w:body></w:document>"""
        val text = Documents.read("x.docx", DocKind.DOCX, docx(xml), noPdf).text
        assertEquals("Titel & Co\nZeile\tzwei\ndrei äö\nEnde", text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
        assertFalse("PAGEREF" in text)
    }

    @Test
    fun brokenOrEmptyFilesGiveClearErrors() {
        assertTrue(assertFailsWith<DocumentException> { Documents.read("x.docx", DocKind.DOCX, "kein zip".toByteArray(), noPdf) }.message!!.contains("DOCX"))
        assertTrue(assertFailsWith<DocumentException> { Documents.read("x.docx", DocKind.DOCX, docx("<a/>", "word/other.xml"), noPdf) }.message!!.contains("document.xml"))
        assertTrue(assertFailsWith<DocumentException> { Documents.read("l.txt", DocKind.TEXT, "  \n ".toByteArray(), noPdf) }.message!!.contains("keinen Text"))
        assertTrue(assertFailsWith<DocumentException> { Documents.read("s.pdf", DocKind.PDF, ByteArray(10)) { listOf("", "  ") } }.message!!.contains("Scan"))
        assertTrue(assertFailsWith<DocumentException> { Documents.read("k.pdf", DocKind.PDF, ByteArray(10)) { error("kaputt") } }.message!!.contains("PDF"))
        assertTrue(assertFailsWith<DocumentException> { Documents.read("g.txt", DocKind.TEXT, ByteArray(Documents.MAX_BYTES + 1), noPdf) }.message!!.contains("zu groß"))
    }

    @Test
    fun completeSentencesCutsOffAnInterruptedLastSentence() {
        assertEquals("Eine Katze sitzt. Sie ist grau.", Documents.completeSentences("Eine Katze sitzt. Sie ist grau. Im Hintergrund sind Qua"))
        assertEquals("Fertig.", Documents.completeSentences(" Fertig. "))
        assertEquals("Ein sehr langer Satz ohne Ende und", Documents.completeSentences("Ein sehr langer Satz ohne Ende und")) // kein Satzende: unverändert
        assertEquals("Kurz. Und dann ein sehr langer abgebrochener zweiter Teil ohne Punkt", Documents.completeSentences("Kurz. Und dann ein sehr langer abgebrochener zweiter Teil ohne Punkt"))
        assertEquals("", Documents.completeSentences("  "))
    }

    @Test
    fun pdfTextComesFromTheProvidedFunction() {
        val doc = Documents.read("a.pdf", DocKind.PDF, ByteArray(3)) { listOf("Seite eins", "Zeile a\nzweite Zeile\n\nAbsatz zwei") }
        // harte Zeilenumbrüche mitten im Satz werden zu Leerzeichen, jede Seite bleibt eine Seite
        assertEquals("Seite eins\n\nZeile a zweite Zeile\n\nAbsatz zwei", doc.text)
        assertEquals(2, doc.readable!!.pages.size)
    }

    @Test
    fun fitCutsAtParagraphOrSentenceBoundary() {
        val paragraph = "Das ist ein Satz. ".repeat(20).trim() // 359 Zeichen
        val text = "$paragraph\n\n$paragraph\n\n$paragraph"
        val whole = Documents.fit(text, 10_000)
        assertFalse(whole.truncated)
        assertEquals(text, whole.text)
        val cut = Documents.fit(text, 250) // 750 Zeichen: nach dem zweiten Absatz ist Schluss
        assertTrue(cut.truncated)
        assertEquals(text.length, cut.totalChars)
        assertEquals("$paragraph\n\n$paragraph", cut.text)
        val noBoundary = Documents.fit("x".repeat(5000), 100)
        assertEquals(300, noBoundary.text.length)
    }

    @Test
    fun promptsAndInputMentionTruncation() {
        val short = Fitted("Inhalt", false, 6)
        val long = Fitted("Anfang", true, 9000)
        assertEquals("[Datei: a.txt]\nInhalt", Documents.inputText("a.txt", short))
        assertTrue("9000 Zeichen" in Documents.inputText("a.txt", long))
        val prompt = Documents.summaryPrompt("a.txt", long, "Deutsch")
        assertTrue(prompt.startsWith("Summarize the following document in Deutsch"))
        assertTrue("Anfang" in prompt && "9000" in prompt)
        assertFalse("continues" in Documents.summaryPrompt("a.txt", short, "Deutsch"))
    }

    @Test
    fun speechTextDropsMarkdownAndSplitsIntoChunks() {
        val md = "# Überschrift\n\n- Punkt **eins**\n- Punkt `zwei`\n\nSiehe [Link](http://x.de) und _Betonung_."
        assertEquals("Überschrift. Punkt eins. Punkt zwei. Siehe Link und Betonung.", Documents.speechText(md))
        val sentence = "Dies ist ein ziemlich langer Satz zum Vorlesen. "
        val chunks = Documents.speechChunks(sentence.repeat(60), 600)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.length <= 600 })
        assertEquals(Documents.speechText(sentence.repeat(60)).replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun documentBudgetLeavesRoomForSystemPromptAndReply() {
        val session = ChatSession(Config(contextTokens = 4096, maxReplyTokens = 600), Persona(name = "Mia"))
        val budget = session.documentBudgetTokens()
        assertTrue(budget in 1500..3400, "Budget $budget")
        assertEquals(150, ChatSession(Config(contextTokens = 700, maxReplyTokens = 600), Persona(name = "Mia")).documentBudgetTokens())
    }
}
