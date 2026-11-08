package app.aichatmcp.core

import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.Base64
import java.util.zip.ZipInputStream

/** Ein Baustein einer Seite: ein Absatz Text oder ein Bild (erst nach dem Absatz, in dem es steht). */
sealed interface Block {
    data class Para(val text: String) : Block

    /**
     * [ref] ist der Schlüssel fürs Laden der Bytes (leer = nicht ladbar), [alt] der Ersatztext der Datei,
     * [decorative] vom Dokument selbst als Zierde markiert (`role="presentation"`, `aria-hidden`).
     */
    data class Img(val ref: String, val alt: String, val decorative: Boolean = false) : Block
}

/**
 * Ein Dokument mit Seiten (PDF-Seiten, EPUB-Kapitel) und Bildern an ihrer Stelle im Text. [images] liefert die Bytes zu einem
 * Bild-Schlüssel (null, wenn nicht ladbar).
 */
class ReadableDoc(val pages: List<List<Block>>, val images: (String) -> ByteArray? = { null }) {
    /** Der reine Text aller Seiten (für Zusammenfassung und Eingabefeld). */
    val text: String
        get() = pages.flatten().filterIsInstance<Block.Para>().joinToString("\n\n") { it.text }
}

/** Ein Element beim Vorlesen: ein Abschnitt Text oder ein Bild, das erst beschrieben werden muss. */
sealed interface ReadItem {
    /**
     * [parts]: der Abschnitt in Erzähler- und Rede-Stücke zerlegt (siehe [Dialogue]); leer = alles mit der Standardstimme. Gehört nicht zur
     * Gleichheit, damit Pläne vergleichbar bleiben, und wird von der Zuordnung (auch nachträglich, durch den KI-Rückfall) gesetzt.
     */
    data class Speech(val text: String) : ReadItem {
        @Volatile var parts: List<Utterance> = emptyList()

        /** Beginnt der Abschnitt mit einem neuen Absatz? (nur im Dialogmodus gesetzt; dort steht zwischen Absätzen eines Abschnitts ein Zeilenumbruch) */
        var paragraphStart: Boolean = true
    }
    /** [decorative]: Zierde, wird weder beschrieben noch vorgelesen (auch nicht der Ersatztext). */
    data class Picture(val ref: String, val alt: String, val decorative: Boolean = false) : ReadItem
}

/**
 * Der Ablauf beim Vorlesen: Abschnitte und Bilder in Lesereihenfolge, dazu die Anfänge der Seiten (für "Seite zurück/vor").
 * Ohne echte Seiten (TXT, MD, DOCX, HTML) gibt es nur eine; dann springen die Tasten um [JUMP_ITEMS] Abschnitte.
 */
class ReadPlan(val items: List<ReadItem>, val pageStarts: List<Int>) {
    /** Im Dialogmodus: die Zuordnung der wörtlichen Reden (für den KI-Rückfall); sonst null. */
    var dialogue: Dialogue? = null

    val size: Int get() = items.size
    val hasPages: Boolean get() = pageStarts.size > 1

    fun pageOf(index: Int): Int = pageStarts.indexOfLast { it <= index }.coerceAtLeast(0)

    /** Ziel für "zurück" (<<): Anfang der vorigen Seite, ohne Seiten 10 Abschnitte zurück. */
    fun backTarget(index: Int): Int =
        if (hasPages) pageStarts[(pageOf(index) - 1).coerceAtLeast(0)] else (index - JUMP_ITEMS).coerceAtLeast(0)

    /** Ziel für "vor" (>>): Anfang der nächsten Seite, ohne Seiten 10 Abschnitte weiter; null = hinter dem Ende. */
    fun forwardTarget(index: Int): Int? {
        val target = if (hasPages) pageStarts.getOrNull(pageOf(index) + 1) ?: return null else index + JUMP_ITEMS
        return if (target >= size) null else target
    }

    companion object {
        const val JUMP_ITEMS = 10
        const val CHUNK_CHARS = 400 // ca. 25 Sekunden Sprechzeit: fein genug zum Springen, grob genug für flüssiges Sprechen

        /**
         * Baut den Ablauf aus einem eingelesenen Dokument. Mit [dialogue] werden die Abschnitte aus den echten Absätzen gebildet und wörtliche
         * Rede wird erkannt und Figuren zugeordnet ([Dialogue]); ohne bleibt alles wie bisher.
         */
        fun of(doc: DocText, chunkMax: Int = CHUNK_CHARS, dialogue: Boolean = false): ReadPlan {
            val pages = doc.readable?.pages ?: listOf(listOf(Block.Para(doc.text)))
            val items = mutableListOf<ReadItem>()
            val starts = mutableListOf<Int>()
            // dasselbe Bild mehrfach im Dokument (Rahmen, Ornament): Zierde
            val uses = pages.flatten().filterIsInstance<Block.Img>().filter { it.ref.isNotEmpty() }.groupingBy { it.ref }.eachCount()
            for (page in pages) {
                val before = items.size
                val buffer = mutableListOf<String>()
                fun flush() {
                    if (buffer.isNotEmpty()) {
                        if (dialogue) DialogueText.chunks(buffer, chunkMax).forEach { (text, start) -> items += ReadItem.Speech(text).also { it.paragraphStart = start } }
                        else Documents.speechChunks(buffer.joinToString("\n\n"), chunkMax).forEach { items += ReadItem.Speech(it) }
                        buffer.clear()
                    }
                }
                for (block in page) {
                    when (block) {
                        is Block.Para -> buffer += block.text
                        is Block.Img -> {
                            flush()
                            val repeated = (uses[block.ref] ?: 0) >= Decorative.MIN_REPEATS
                            items += ReadItem.Picture(block.ref, block.alt, block.decorative || repeated)
                        }
                    }
                }
                flush()
                if (items.size > before) starts += before
            }
            return ReadPlan(items, starts).also { if (dialogue) it.dialogue = Dialogue.analyze(it) }
        }
    }
}

/** Einfache, tolerante HTML-Auswertung: Absätze und Bilder, ohne Skripte und Formatierung. */
object Html {
    private val BLOCK_TAGS = setOf(
        "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "blockquote", "section", "article", "header",
        "footer", "pre", "table", "ul", "ol", "dd", "dt", "figure", "figcaption", "hr", "nav", "aside",
    )
    private val DROP = Regex("<(script|style|head|svg)\\b.*?</\\1\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAG = Regex("<!--.*?-->|<[^>]+>", RegexOption.DOT_MATCHES_ALL)
    private val TAG_NAME = Regex("^<\\s*(/?)\\s*([A-Za-z][A-Za-z0-9]*)")

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "auml" to "ä", "ouml" to "ö",
        "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß", "eacute" to "é", "egrave" to "è",
        "agrave" to "à", "ccedil" to "ç", "ecirc" to "ê", "aacute" to "á", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
        "ntilde" to "ñ", "hellip" to "…", "ndash" to "–", "mdash" to "—", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
        "rdquo" to "”", "laquo" to "«", "raquo" to "»", "copy" to "©", "shy" to "", "zwnj" to "", "zwj" to "",
    )

    fun attr(tag: String, name: String): String? {
        val m = Regex("\\b" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2] }
    }

    fun decodeEntities(text: String): String =
        text.replace(Regex("&#[xX]([0-9a-fA-F]+);")) { codePoint(it.groupValues[1].toInt(16)) }
            .replace(Regex("&#([0-9]+);")) { codePoint(it.groupValues[1].toInt()) }
            .replace(Regex("&([A-Za-z]+);")) { ENTITIES[it.groupValues[1]] ?: it.value }

    private fun codePoint(value: Int): String = if (value in 1..0x10FFFF) String(Character.toChars(value)) else ""

    /**
     * Zerlegt HTML in Absätze und Bilder. Ein Bild kommt erst nach dem Absatz, in dem es steht. [resolve] macht aus dem `src`
     * eines Bildes den Schlüssel zum Laden (null = nicht ladbar; das Bild bleibt mit seinem Ersatztext erhalten).
     */
    fun blocks(html: String, resolve: (String) -> String? = { null }): List<Block> {
        val body = html.replace(DROP, " ")
        val out = mutableListOf<Block>()
        val current = StringBuilder()
        val pending = mutableListOf<Block.Img>()

        fun flush() {
            val text = decodeEntities(current.toString()).replace(Regex("\\s+"), " ").trim()
            current.setLength(0)
            if (text.isNotEmpty()) out += Block.Para(text)
            out += pending
            pending.clear()
        }

        var last = 0
        for (match in TAG.findAll(body)) {
            current.append(body, last, match.range.first)
            last = match.range.last + 1
            val tag = match.value
            val name = TAG_NAME.find(tag)?.groupValues?.get(2)?.lowercase() ?: continue
            if (name == "img") {
                val src = attr(tag, "src").orEmpty()
                val marked = attr(tag, "role")?.trim()?.lowercase() in setOf("presentation", "none") || attr(tag, "aria-hidden")?.trim()?.lowercase() == "true"
                pending += Block.Img(if (src.isEmpty()) "" else resolve(decodeEntities(src)).orEmpty(), decodeEntities(attr(tag, "alt").orEmpty()).trim(), marked)
            } else if (name in BLOCK_TAGS) {
                flush()
            }
        }
        current.append(body, last, body.length)
        flush()
        return out
    }
}

/** EPUB: ein Zip-Archiv mit XHTML-Kapiteln (Reihenfolge laut OPF-Datei) und Bildern. */
object Epub {
    private const val MAX_ENTRY_BYTES = 20_000_000

    fun read(bytes: ByteArray): ReadableDoc {
        val texts = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || !isText(name)) continue
                    val data = zip.readBytes()
                    if (data.size <= MAX_ENTRY_BYTES) texts[name] = data
                }
            }
        } catch (e: Exception) {
            throw DocumentException("Die EPUB-Datei lässt sich nicht lesen (${e.message ?: "kein gültiges Archiv"}).")
        }
        val container = texts["META-INF/container.xml"]?.let { Documents.decodeText(it) }
            ?: throw DocumentException("Das ist keine gültige EPUB-Datei (META-INF/container.xml fehlt).")
        val opfPath = Html.attr(Regex("<rootfile\\b[^>]*>", RegexOption.IGNORE_CASE).find(container)?.value.orEmpty(), "full-path")
            ?: throw DocumentException("Die EPUB-Datei nennt keine Paketdatei (OPF).")
        val opf = texts[opfPath]?.let { Documents.decodeText(it) } ?: throw DocumentException("Die Paketdatei der EPUB-Datei fehlt ($opfPath).")
        val opfDir = opfPath.substringBeforeLast('/', "")

        val manifest = HashMap<String, String>()
        for (item in Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)) {
            val id = Html.attr(item.value, "id") ?: continue
            val href = Html.attr(item.value, "href") ?: continue
            manifest[id] = resolvePath(opfDir, href)
        }
        val spine = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)
            .filter { !Html.attr(it.value, "linear").equals("no", ignoreCase = true) }
            .mapNotNull { Html.attr(it.value, "idref") }.mapNotNull { manifest[it] }.toList()
        if (spine.isEmpty()) throw DocumentException("Die EPUB-Datei enthält keine Kapitel.")

        val pages = spine.mapNotNull { path ->
            val data = texts[path] ?: return@mapNotNull null
            val dir = path.substringBeforeLast('/', "")
            Html.blocks(Documents.decodeText(data)) { src -> if (src.startsWith("data:")) src else resolvePath(dir, src) }.takeIf { it.isNotEmpty() }
        }
        if (pages.isEmpty()) throw DocumentException("Die EPUB-Datei enthält keinen Text.")
        return ReadableDoc(pages) { ref -> imageBytes(bytes, ref) }
    }

    private fun isText(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xml") || n.endsWith(".opf")
    }

    /** Pfad [src] relativ zu [dir] im Archiv ("../" und %20 aufgelöst). */
    fun resolvePath(dir: String, src: String): String {
        val clean = try {
            URLDecoder.decode(src.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            src
        }
        val parts = ArrayDeque<String>()
        val raw = if (clean.startsWith("/")) clean.removePrefix("/") else (if (dir.isEmpty()) clean else "$dir/$clean")
        for (p in raw.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(p)
            }
        }
        return parts.joinToString("/")
    }

    /** Bytes eines Bildes: aus einer data:-URI oder (sehr selten gebraucht) per Durchlauf durchs Archiv. */
    fun imageBytes(archive: ByteArray, ref: String): ByteArray? {
        if (ref.isEmpty()) return null
        if (ref.startsWith("data:")) return dataUri(ref)
        return try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == ref) return zip.readBytes().takeIf { it.size <= MAX_ENTRY_BYTES }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun dataUri(ref: String): ByteArray? {
        val comma = ref.indexOf(',')
        if (comma < 0 || !ref.substring(0, comma).contains("base64", ignoreCase = true)) return null
        return try {
            Base64.getMimeDecoder().decode(ref.substring(comma + 1))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
