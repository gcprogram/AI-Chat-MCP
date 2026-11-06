package app.aichatmcp.core

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Liest einfache Tabellen (CSV, Excel .xlsx) als Zeilen von Zellen, ohne Bibliothek. Nur der erste Tabellenblatt-Inhalt zählt. */
object Spreadsheet {
    private const val MAX_ENTRY_BYTES = 20_000_000
    private const val MAX_ROWS = 50_000

    /** Liest [bytes] je nach Endung von [name] (`.xlsx`, sonst CSV/Text). Alte `.xls`-Dateien werden mit einem Hinweis abgelehnt. */
    fun rows(name: String, bytes: ByteArray): List<List<String>> {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".xls") -> throw DocumentException("Das alte Excel-Format (.xls) wird nicht gelesen: in Excel als .xlsx oder CSV speichern.")
            lower.endsWith(".xlsx") || lower.endsWith(".xlsm") || (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) -> xlsx(bytes)
            else -> csv(Documents.decodeText(bytes))
        }
    }

    /** CSV mit Trennzeichen Semikolon, Komma oder Tabulator (erkannt an der ersten Zeile, Gleichstand: Semikolon), Anführungszeichen wie in Excel. */
    fun csv(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val delimiter = listOf(';', ',', '\t').maxByOrNull { d -> firstLine.count { it == d } }?.takeIf { d -> firstLine.contains(d) } ?: ';'
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { row += cell.toString(); cell.setLength(0) }
        fun endRow() {
            endCell()
            if (row.any { it.isNotBlank() }) rows += row
            row = mutableListOf()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> cell.append(c)
                c == '"' && cell.isBlank() -> { cell.setLength(0); quoted = true }
                c == delimiter -> endCell()
                c == '\n' -> endRow()
                c == '\r' -> {}
                else -> cell.append(c)
            }
            if (rows.size > MAX_ROWS) throw DocumentException("Die Tabelle hat zu viele Zeilen.")
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** Excel: erstes Blatt laut workbook.xml; Zahlen und Text als Zellwerte, Lücken bleiben leere Zellen (Spalten behalten ihre Stelle). */
    fun xlsx(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val n = entry.name.replace('\\', '/')
                    if (entry.isDirectory || !(n.startsWith("xl/") && (n.endsWith(".xml") || n.endsWith(".rels")))) continue
                    val data = zip.readBytes()
                    if (data.size <= MAX_ENTRY_BYTES) files[n] = data
                }
            }
        } catch (e: Exception) {
            throw DocumentException("Die Excel-Datei lässt sich nicht öffnen (kein gültiges .xlsx).")
        }
        fun text(name: String) = files[name]?.toString(Charsets.UTF_8)

        val sheetName = firstSheetPath(::text) ?: files.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.minOrNull()
            ?: throw DocumentException("Die Excel-Datei enthält kein Tabellenblatt.")
        val sheet = text(sheetName) ?: throw DocumentException("Das erste Tabellenblatt fehlt in der Excel-Datei.")
        val shared = text("xl/sharedStrings.xml")?.let(::sharedStrings).orEmpty()

        val rows = mutableListOf<List<String>>()
        for (rowMatch in ROW.findAll(sheet)) {
            val cells = ArrayList<String>()
            var next = 0
            for (m in CELL.findAll(rowMatch.groupValues[2])) {
                val attrs = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
                val body = m.groupValues[3]
                val column = Html.attr("<c $attrs>", "r")?.let(::columnIndex) ?: next
                while (cells.size < column) cells += ""
                val value = when (Html.attr("<c $attrs>", "t")) {
                    "s" -> VALUE.find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> joinText(body)
                    "b" -> if (VALUE.find(body)?.groupValues?.get(1)?.trim() == "1") "TRUE" else "FALSE"
                    "e" -> ""
                    else -> Html.decodeEntities(VALUE.find(body)?.groupValues?.get(1).orEmpty()).trim().replace(WHOLE_DECIMAL, "")
                }
                if (column < cells.size) cells[column] = value else cells += value
                next = column + 1
            }
            if (cells.any { it.isNotBlank() }) rows += cells
            if (rows.size > MAX_ROWS) throw DocumentException("Die Tabelle hat zu viele Zeilen.")
        }
        return rows
    }

    private val ROW = Regex("<row\\b([^>]*)>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<c\\b([^>]*?)\\s*/>|<c\\b([^>]*)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
    private val VALUE = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
    private val WHOLE_DECIMAL = Regex("(?<=\\d)\\.0+$")
    private val SI = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
    private val T = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
    private val PHONETIC = Regex("<rPh\\b.*?</rPh>", RegexOption.DOT_MATCHES_ALL)

    private fun joinText(xml: String) = Html.decodeEntities(T.findAll(xml.replace(PHONETIC, "")).joinToString("") { it.groupValues[1] })

    private fun sharedStrings(xml: String): List<String> = SI.findAll(xml).map { joinText(it.groupValues[1]) }.toList()

    /** Spalte aus einer Zellangabe wie "B7" (A = 0, AA = 26). */
    private fun columnIndex(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return null
        return letters.fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
    }

    /** Pfad des ersten Blatts laut workbook.xml und deren Beziehungen. */
    private fun firstSheetPath(text: (String) -> String?): String? {
        val workbook = text("xl/workbook.xml") ?: return null
        val id = Regex("<sheet\\b[^>]*>").find(workbook)?.value?.let { Html.attr(it, "r:id") } ?: return null
        val rels = text("xl/_rels/workbook.xml.rels") ?: return null
        val target = Regex("<Relationship\\b[^>]*>").findAll(rels).map { it.value }.firstOrNull { Html.attr(it, "Id") == id }?.let { Html.attr(it, "Target") } ?: return null
        return if (target.startsWith("/")) target.trimStart('/') else "xl/" + target
    }
}
