package app.aichatmcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aichatmcp.core.Block
import app.aichatmcp.core.DocKind
import app.aichatmcp.core.KeywordRules
import app.aichatmcp.core.LanguageDetect
import app.aichatmcp.core.ReadItem
import app.aichatmcp.core.ReadPlan
import app.aichatmcp.core.ToolSpec
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
    private val pdf: (ByteArray) -> List<String> = { PdfText.extract(context, it) }

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
    fun epubHtmlLanguageAndKeywordsWorkOnAndroidRegex() {
        // EPUB: Kapitel in Spine-Reihenfolge, Bild mit aufgelöstem Pfad, Entitäten
        val opf = """<package><manifest><item id="c1" href="ch1.xhtml"/><item id="i" href="img/a%20b.png"/></manifest><spine><itemref idref="c1"/></spine></package>"""
        val ch1 = """<html><head><style>p { color: red }</style></head><body><p>Hallo &auml;&#x20AC; Welt <img src="img/a%20b.png" alt="Bild"/> Ende.</p></body></html>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, data: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
            put("META-INF/container.xml", """<rootfile full-path="OEBPS/content.opf"/>""".toByteArray())
            put("OEBPS/content.opf", opf.toByteArray())
            put("OEBPS/ch1.xhtml", ch1.toByteArray())
            put("OEBPS/img/a b.png", byteArrayOf(9, 8, 7))
        }
        val doc = Documents.read("b.epub", DocKind.EPUB, out.toByteArray(), pdf)
        assertEquals("Hallo ä€ Welt Ende.", doc.text)
        val plan = ReadPlan.of(doc)
        assertEquals(2, plan.size) // Text, danach das Bild
        assertEquals("OEBPS/img/a b.png", (plan.items[1] as ReadItem.Picture).ref)
        assertTrue(doc.readable!!.images("OEBPS/img/a b.png")!!.contentEquals(byteArrayOf(9, 8, 7)))
        // HTML mit eingebettetem Bild
        val html = Documents.read("a.html", DocKind.HTML, "<p>Eins.</p><img src=\"data:image/png;base64,AQID\"><p>Zwei.</p>".toByteArray(), pdf)
        assertTrue(html.readable!!.images((html.readable!!.pages.single()[1] as Block.Img).ref)!!.contentEquals(byteArrayOf(1, 2, 3)))
        // Spracherkennung per Buchstabenhäufigkeit
        val german = "Der Hafen von Rotterdam ist der größte Seehafen Europas. Jährlich werden dort mehr als vierhundert Millionen Tonnen Güter umgeschlagen."
        val english = "The port of Rotterdam is the largest seaport in Europe. Every year more than four hundred million tons of goods are handled there."
        assertEquals("de-DE", LanguageDetect.detect(german)?.tag)
        assertEquals("en-US", LanguageDetect.detect(english)?.tag)
        // Schlüsselwörter (ICU-Regex mit Gruppen und ohne Groß-/Kleinschreibung)
        val rules = KeywordRules(listOf(ToolSpec("weather", "s", "t", keywords = listOf(app.aichatmcp.core.Keyword("wetter in (\\w+)(?: (morgen|heute))?")))), listOf("weather"))
        assertEquals("Paris|morgen", rules.match("Wie ist das WETTER in Paris morgen?").single().args)
    }

    /** Schlüsselwort-Import (CSV und Excel) mit den Regexen der Android-Laufzeit (ICU: Lookbehind, \p{L}, Groß-/Kleinschreibung bei Umlauten). */
    @Test
    fun keywordImportWorksOnAndroidRegex() {
        val mcp = """{"servers": {}, "aliases": {"tv-on": {"server": "s", "tool": "t"}, "weather": {"server": "s", "tool": "t", "params": ["place"]}}}"""
        val sheet = """<worksheet><sheetData>
            <row r="1"><c r="A1" t="inlineStr"><is><t>Wort</t></is></c><c r="B1" t="inlineStr"><is><t>Tool</t></is></c></row>
            <row r="2"><c r="A2" t="inlineStr"><is><t>Glotze AN</t></is></c><c r="B2" t="inlineStr"><is><t>tv-on</t></is></c></row>
            <row r="3"><c r="A3" t="inlineStr"><is><t>Schnee</t></is></c><c r="B3" t="inlineStr"><is><t>weather</t></is></c><c r="D3"><v>5</v></c></row>
            </sheetData></worksheet>"""
        val xlsx = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); zip.write(sheet.toByteArray()); zip.closeEntry() }
        }.toByteArray()
        val csv = "word;tool;p1\nÄrger;tv-on\nGlotze an;tv-on\nregen;weather;Paderborn\n".toByteArray()
        for ((name, bytes) in listOf("l.xlsx" to xlsx, "l.csv" to csv)) {
            val result = app.aichatmcp.core.KeywordImport.fromFile(name, bytes, mcp)
            val registry = app.aichatmcp.core.ToolRegistry()
            registry.remote["s" to "t"] = ToolSpec(alias = "t", server = "s", remoteName = "t", params = listOf(app.aichatmcp.core.Param("place")))
            registry.addAliases((app.aichatmcp.core.AppJson.parseToJsonElement(result.json) as kotlinx.serialization.json.JsonObject)["aliases"] as kotlinx.serialization.json.JsonObject)
            assertEquals(emptyList<String>(), registry.errors)
            val rules = KeywordRules(registry.tools.values, listOf("tv-on", "weather"))
            assertEquals(name, "tv-on", rules.match("mach die GLOTZE an").single().alias)
            assertEquals(name, emptyList<String>(), rules.match("glotzen und anders").map { it.alias })
        }
        val rules = run {
            val result = app.aichatmcp.core.KeywordImport.fromFile("l.csv", csv, mcp)
            val registry = app.aichatmcp.core.ToolRegistry()
            registry.remote["s" to "t"] = ToolSpec(alias = "t", server = "s", remoteName = "t", params = listOf(app.aichatmcp.core.Param("place")))
            registry.addAliases((app.aichatmcp.core.AppJson.parseToJsonElement(result.json) as kotlinx.serialization.json.JsonObject)["aliases"] as kotlinx.serialization.json.JsonObject)
            KeywordRules(registry.tools.values, listOf("tv-on", "weather"))
        }
        assertEquals("tv-on", rules.match("so ein ÄRGER").single().alias) // Umlaut, Großschreibung
        assertEquals("Paderborn", rules.match("Regen?").single().args) // feste Argumente
        assertEquals(emptyList<String>(), rules.match("ärgerlich").map { it.alias }) // nur ganze Wörter
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
