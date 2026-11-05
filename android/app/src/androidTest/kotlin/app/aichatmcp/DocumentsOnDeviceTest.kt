package app.aichatmcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aichatmcp.core.DocKind
import app.aichatmcp.core.Documents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Upload von Dokumenten auf dem echten Laufzeitsystem: PdfBox (PDF), Zip/XML (DOCX) und alle Regexe der
 * Aufbereitung (Android-Regex ist ICU, die JVM-Tests fangen Unterschiede nicht).
 */
@RunWith(AndroidJUnit4::class)
class DocumentsOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pdf: (ByteArray) -> String = { PdfText.extract(context, it) }

    /** Kleinste gültige PDF-Datei mit einer Seite und einer Textzeile (Helvetica, Latin-1). */
    private fun miniPdf(vararg lines: String): ByteArray {
        val content = lines.mapIndexed { i, line -> "BT /F1 14 Tf 20 ${180 - i * 20} Td (${line.replace("(", "\\(").replace(")", "\\)")}) Tj ET" }.joinToString("\n")
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { i, body ->
            offsets += out.length
            out.append("${i + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append("%010d 00000 n \n".format(it)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun docx(body: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml")); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun pdfTextIsExtracted() {
        val doc = Documents.read("probe.pdf", DocKind.PDF, miniPdf("Hallo Welt", "Zweite Zeile (mit Klammer)"), pdf)
        assertTrue(doc.text, "Hallo Welt" in doc.text && "Zweite Zeile (mit Klammer)" in doc.text)
    }

    @Test
    fun docxTextIsExtracted() {
        val xml = "<w:document><w:body><w:p><w:r><w:t>Titel &amp; Co</w:t></w:r></w:p><w:p><w:r><w:t>Zeile</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>zwei &#228;</w:t></w:r></w:p></w:body></w:document>"
        assertEquals("Titel & Co\nZeile\tzwei ä", Documents.read("probe.docx", DocKind.DOCX, docx(xml), pdf).text)
    }

    @Test
    fun textHelpersWorkOnAndroidRegex() {
        assertEquals("a\n\nb", Documents.read("a.md", DocKind.MARKDOWN, "a\r\n\r\n\r\n\r\nb  ".toByteArray(), pdf).text)
        val md = "# Überschrift\n\n- Punkt **eins**\n\nSiehe [Link](http://x.de) und _Betonung_."
        assertEquals("Überschrift. Punkt eins. Siehe Link und Betonung.", Documents.speechText(md))
        val sentence = "Dies ist ein ziemlich langer Satz zum Vorlesen. "
        val chunks = Documents.speechChunks(sentence.repeat(60), 600)
        assertTrue(chunks.size > 3 && chunks.all { it.length <= 600 })
        val fitted = Documents.fit(("Das ist ein Absatz. ".repeat(30).trim() + "\n\n").repeat(5), 300)
        assertTrue(fitted.truncated && fitted.text.length <= 900)
    }
}
