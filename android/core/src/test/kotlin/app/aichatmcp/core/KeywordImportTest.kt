package app.aichatmcp.core

import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeywordImportTest {
    private val aliases = listOf("tv-on", "tv-off", "weather")

    private val mcp = """{"servers": {}, "aliases": {
        "tv-on": {"server": "s", "tool": "t", "keywords": ["alt"]},
        "tv-off": {"server": "s", "tool": "t"},
        "weather": {"server": "s", "tool": "t", "params": ["place"], "keywords": ["bleibt"]},
        "other": {"server": "s", "tool": "t", "keywords": ["unberührt"]}}}"""

    @Test
    fun csvWordsAreLowercasedMergedByToolAndParametersAndDeduplicated() {
        val csv = """Word;Tool;Parameter1
            Fernseher an;tv-on
            GLOTZE AN;tv-on;
            Fernseher aus;tv-off
            paderborn;weather;Paderborn
            Wetter;WEATHER;Paderborn;morgen
            Regen;weather;Paderborn
            fernseher an;tv-on
            ohne werkzeug;
            Gerät;unbekannt
            Fernseher an;tv-off
            """.trimIndent().lines().joinToString("\n") { it.trim() }
        val parsed = KeywordImport.parse(Spreadsheet.csv(csv), aliases)
        assertEquals(
            listOf(
                KeywordImport.Group("tv-on", emptyList(), listOf("fernseher an", "glotze an")),
                KeywordImport.Group("tv-off", emptyList(), listOf("fernseher aus")),
                KeywordImport.Group("weather", listOf("Paderborn"), listOf("paderborn", "regen")),
                KeywordImport.Group("weather", listOf("Paderborn", "morgen"), listOf("wetter")),
            ),
            parsed.groups,
        )
        assertEquals(listOf("tv-on", "tv-off", "weather"), parsed.tools)
        assertEquals(6, parsed.wordCount)
        val notes = parsed.notes.joinToString("\n")
        assertTrue("1 Zeilen ohne Werkzeug" in notes, notes)
        assertTrue("unbekannt" in notes, notes)
        assertTrue("1 doppelte" in notes, notes)
        assertTrue("fernseher an" in notes && "nur die erste Zeile" in notes, notes) // gleiches Wort, anderes Werkzeug
    }

    @Test
    fun csvHandlesQuotesDelimitersAndEncodings() {
        assertEquals(listOf(listOf("a;b", "c\"d", "e")), Spreadsheet.csv("\"a;b\";\"c\"\"d\";e\r\n\r\n"))
        assertEquals(listOf(listOf("x", "y"), listOf("1", "2")), Spreadsheet.csv("x,y\n1,2\n"))
        assertEquals(listOf(listOf("x", "y z")), Spreadsheet.csv("x\ty z"))
        val bytes = "Größe;tv-on\n".toByteArray(charset("windows-1252")) // altes Excel-Format ohne UTF-8
        assertEquals("größe", KeywordImport.parse(Spreadsheet.rows("liste.csv", bytes), aliases).groups.single().words.single())
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, d) in files) { z.putNextEntry(ZipEntry(n)); z.write(d.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun xlsxFirstSheetWithSharedAndInlineStringsNumbersAndGaps() {
        val workbook = """<workbook><sheets><sheet name="Liste" sheetId="2" r:id="rId5"/><sheet name="Zweites" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        val rels = """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId5" Target="worksheets/sheet2.xml"/></Relationships>"""
        val shared = """<sst><si><t>Wort</t></si><si><t>Tool</t></si><si><r><t>Fernseher</t></r><r><t xml:space="preserve"> an</t></r></si><si><t>tv-on</t></si>
            <si><t>Rock &amp; Roll</t><rPh><t>x</t></rPh></si><si><t>weather</t></si></sst>"""
        val sheet2 = """<worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
            <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2" t="s"><v>3</v></c></row>
            <row r="3"><c r="A3" t="s"><v>4</v></c><c r="B3" t="s"><v>5</v></c><c r="C3" s="1"/><c r="D3"><v>42</v></c></row>
            <row r="4"><c r="A4" t="inlineStr"><is><t>Regen</t></is></c><c r="B4" t="inlineStr"><is><t>weather</t></is></c><c r="D4"><v>7.0</v></c></row>
            <row r="5"><c r="C5" s="1"/></row></sheetData></worksheet>"""
        val file = zip(
            "xl/workbook.xml" to workbook, "xl/_rels/workbook.xml.rels" to rels, "xl/sharedStrings.xml" to shared,
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>falsch</t></is></c></row></sheetData></worksheet>",
            "xl/worksheets/sheet2.xml" to sheet2,
        )
        val rows = Spreadsheet.rows("liste.xlsx", file)
        assertEquals(
            listOf(
                listOf("Wort", "Tool"), listOf("Fernseher an", "tv-on"), listOf("Rock & Roll", "weather", "", "42"),
                listOf("Regen", "weather", "", "7"),
            ),
            rows,
        )
        val parsed = KeywordImport.parse(rows, aliases)
        // eine leere Zelle dazwischen bleibt als leerer Parameter: die Stelle zählt
        assertEquals(KeywordImport.Group("weather", listOf("", "42"), listOf("rock & roll")), parsed.groups[1])
        assertEquals(KeywordImport.Group("weather", listOf("", "7"), listOf("regen")), parsed.groups[2])
        assertFailsWith<DocumentException> { Spreadsheet.rows("alt.xls", byteArrayOf(1, 2, 3)) }
        assertFailsWith<DocumentException> { Spreadsheet.rows("kaputt.xlsx", byteArrayOf(1, 2, 3, 4, 5, 6)) }
    }

    @Test
    fun patternEscapesSpecialCharactersAndMatchesWholeWordsOnly() {
        val g = KeywordImport.Group("tv-on", emptyList(), listOf("c++", "fernseher  an", "ärger", "a.b"))
        val rule = Regex(g.pattern, RegexOption.IGNORE_CASE)
        assertTrue(rule.containsMatchIn("Bitte C++ lernen"))
        assertTrue(rule.containsMatchIn("mach den FERNSEHER an!"))
        assertTrue(rule.containsMatchIn("so ein ÄRGER"))
        assertTrue(rule.containsMatchIn("a.b"))
        assertTrue(!rule.containsMatchIn("axb")) // der Punkt ist wörtlich
        assertTrue(!rule.containsMatchIn("ärgerlich")) // nur ganze Wörter
        assertTrue(!rule.containsMatchIn("kfernseher an"))
    }

    @Test
    fun applyReplacesKeywordsOfTheListedToolsOnlyAndTheEngineUsesTheFixedArguments() {
        val csv = "Wort;Tool;P1\nfernseher an;tv-on\nglotze an;tv-on\nwetter;weather;Paderborn\nregen;weather;Paderborn\nschnee;weather;Hamburg\n"
        val result = KeywordImport.fromFile("k.csv", csv.toByteArray(), mcp)
        val root = AppJson.parseToJsonElement(result.json) as JsonObject
        val aliasJson = root["aliases"] as JsonObject
        val registry = ToolRegistry()
        registry.remote["s" to "t"] = ToolSpec(alias = "t", server = "s", remoteName = "t", params = listOf(Param("place", required = false)))
        registry.addAliases(aliasJson)
        assertEquals(emptyList(), registry.errors)
        assertEquals(listOf("unberührt"), registry.tools.getValue("other").keywords.map { it.pattern }) // nicht in der Liste
        assertEquals(emptyList(), registry.tools.getValue("tv-off").keywords)
        assertEquals(2, registry.tools.getValue("weather").keywords.size) // "bleibt" ist ersetzt
        assertEquals(1, registry.tools.getValue("tv-on").keywords.size) // "alt" ist ersetzt

        val rules = KeywordRules(registry.tools.values, listOf("tv-on", "tv-off", "weather", "other"))
        val glotze = rules.match("mach die GLOTZE an").single()
        assertEquals("tv-on" to "", glotze.alias to glotze.args)
        assertEquals("Paderborn", rules.match("Kommt Regen?").single().args)
        assertEquals("Hamburg", rules.match("Schnee heute").single().args)
        assertEquals(listOf("other"), rules.match("alt bleibt unberührt").map { it.alias }) // die alten Wörter sind weg, "other" blieb
    }

    @Test
    fun anImportWithoutUsableRowsOrAliasesFailsWithAClearMessage() {
        val e = assertFailsWith<DocumentException> { KeywordImport.fromFile("k.csv", "wort;tool\nx;\n".toByteArray(), mcp) }
        assertTrue("Keine verwertbare Zeile" in e.message.orEmpty())
        assertFailsWith<DocumentException> { KeywordImport.fromFile("k.csv", "a;tv-on".toByteArray(), "{\"servers\": {}}") }
        assertFailsWith<DocumentException> { KeywordImport.fromFile("k.csv", "a;tv-on".toByteArray(), "kein json") }
    }
}
