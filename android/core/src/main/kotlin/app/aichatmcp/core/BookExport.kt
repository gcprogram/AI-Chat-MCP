package app.aichatmcp.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Das Buch als Markdown oder DOCX (ohne Bibliothek: ein ZIP mit einigen XML-Dateien) und als einfacher Text zum Vorlesen. */
object BookExport {
    private fun planSections(p: BookProject): List<Pair<String, String>> = buildList {
        val g = p.german
        if (p.premise.isNotBlank()) add((if (g) "Handlung" else "Plot") to p.premise)
        if (p.characters.isNotBlank()) add((if (g) "Figuren (Dramatis Personae)" else "Characters (dramatis personae)") to p.characters)
        if (p.chapters.isNotEmpty()) add((if (g) "Kapitelplan" else "Chapter plan") to p.chapters.mapIndexed { i, c -> "${i + 1}. ${c.title}: ${c.plan}" }.joinToString("\n"))
    }

    private fun chapterHeading(p: BookProject, i: Int) = (if (p.german) "Kapitel " else "Chapter ") + (i + 1) + ": " + p.chapters[i].title

    fun markdown(p: BookProject, includePlan: Boolean = false): String = buildString {
        append("# ").append(p.displayTitle).append("\n\n")
        if (includePlan) for ((title, body) in planSections(p)) append("## ").append(title).append("\n\n").append(body.trim()).append("\n\n")
        p.chapters.forEachIndexed { i, c ->
            if (c.text.isBlank()) return@forEachIndexed
            append("## ").append(chapterHeading(p, i)).append("\n\n").append(c.text.trim()).append("\n\n")
        }
    }.trimEnd() + "\n"

    /** Einfacher Text (Überschriften als Zeilen), z. B. zum Vorlesen. */
    fun plainText(p: BookProject): String = buildString {
        append(p.displayTitle).append("\n\n")
        p.chapters.forEachIndexed { i, c ->
            if (c.text.isBlank()) return@forEachIndexed
            append(chapterHeading(p, i)).append("\n\n").append(c.text.trim()).append("\n\n")
        }
    }.trimEnd() + "\n"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun para(style: String?, text: String): String =
        "<w:p>" + (if (style != null) "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>" else "") +
            "<w:r><w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r></w:p>"

    private fun body(p: BookProject, includePlan: Boolean): String = buildString {
        append(para("Title", p.displayTitle))
        if (includePlan) for ((title, text) in planSections(p)) {
            append(para("Heading1", title))
            text.split('\n').filter { it.isNotBlank() }.forEach { append(para(null, it.trim())) }
        }
        p.chapters.forEachIndexed { i, c ->
            if (c.text.isBlank()) return@forEachIndexed
            append(para("Heading1", chapterHeading(p, i)))
            c.text.split(Regex("\n+")).filter { it.isNotBlank() }.forEach { append(para(null, it.trim())) }
        }
    }

    fun docx(p: BookProject, includePlan: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                    """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                    """<Default Extension="xml" ContentType="application/xml"/>""" +
                    """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
                    """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
                    """</Types>""",
            )
            put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
                    """</Relationships>""",
            )
            put(
                "word/_rels/document.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
                    """</Relationships>""",
            )
            put(
                "word/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
                    """<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Georgia" w:hAnsi="Georgia"/><w:sz w:val="24"/></w:rPr></w:rPrDefault>""" +
                    """<w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="300" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
                    """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>""" +
                    """<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="480"/></w:pPr><w:rPr><w:b/><w:sz w:val="52"/></w:rPr></w:style>""" +
                    """<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="480" w:after="200"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style>""" +
                    """</w:styles>""",
            )
            put(
                "word/document.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""" +
                    body(p, includePlan) + """</w:body></w:document>""",
            )
        }
        return out.toByteArray()
    }
}
