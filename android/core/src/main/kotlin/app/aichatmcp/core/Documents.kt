package app.aichatmcp.core

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Eine Datei konnte nicht gelesen werden (Text für die Anzeige). */
class DocumentException(message: String) : Exception(message)

enum class DocKind { TEXT, MARKDOWN, DOCX, PDF, IMAGE }

/** Ein eingelesener Text, bereit für die Rückfrage "Was soll ich damit machen?". */
data class DocText(val name: String, val kind: DocKind, val text: String)

/** Ein auf das Kontextfenster gekürzter Text. */
data class Fitted(val text: String, val truncated: Boolean, val totalChars: Int)

/**
 * Dateien als Text einlesen (TXT, MD, DOCX; PDF über eine von der App gelieferte Funktion, weil dafür eine
 * Android-Bibliothek nötig ist), auf das knappe Kontextfenster kürzen und für Zusammenfassung und Vorlesen aufbereiten.
 * Reine Kotlin-Logik, damit sie auf der JVM testbar ist.
 */
object Documents {
    const val MAX_BYTES = 12_000_000
    const val MAX_CHARS = 600_000
    private const val MAX_XML_BYTES = 40_000_000
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic")

    fun kindOf(name: String, mime: String? = null): DocKind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in IMAGE_EXTENSIONS || mime?.startsWith("image/") == true -> DocKind.IMAGE
            ext == "pdf" || mime == "application/pdf" -> DocKind.PDF
            ext == "docx" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocKind.DOCX
            ext == "md" || ext == "markdown" || mime == "text/markdown" || mime == "text/x-markdown" -> DocKind.MARKDOWN
            ext == "txt" || ext == "text" || mime == "text/plain" -> DocKind.TEXT
            else -> null
        }
    }

    /** Liest den Text einer Datei. [pdf] liefert den Text einer PDF-Datei (nur für DocKind.PDF aufgerufen). */
    fun read(name: String, kind: DocKind, bytes: ByteArray, pdf: (ByteArray) -> String): DocText {
        if (bytes.size > MAX_BYTES) throw DocumentException("Die Datei ist zu groß (mehr als ${MAX_BYTES / 1_000_000} MB).")
        val raw = when (kind) {
            DocKind.IMAGE -> throw DocumentException("Bilder werden vom Bildmodell beschrieben, nicht als Text gelesen.")
            DocKind.TEXT, DocKind.MARKDOWN -> decodeText(bytes)
            DocKind.DOCX -> docxText(bytes)
            DocKind.PDF -> try {
                pdf(bytes)
            } catch (e: DocumentException) {
                throw e
            } catch (e: Exception) {
                throw DocumentException("Die PDF-Datei lässt sich nicht lesen (${e.message ?: e::class.simpleName}).")
            }
        }
        val text = tidy(raw).take(MAX_CHARS)
        if (text.isBlank()) {
            throw DocumentException(
                if (kind == DocKind.PDF) "In der PDF-Datei steht kein auslesbarer Text (vielleicht ein Scan ohne Texterkennung)."
                else "Die Datei enthält keinen Text.",
            )
        }
        return DocText(name, kind, text)
    }

    /** UTF-8 (auch mit BOM); ist die Datei kein gültiges UTF-8, gilt Windows-1252 (ältere deutsche Textdateien). */
    fun decodeText(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, start, bytes.size - start, charset("windows-1252"))
        }
    }

    /** Text aus word/document.xml einer DOCX-Datei (Absätze, Tabulatoren, Zeilenumbrüche; ohne Kopf-/Fußzeilen). */
    fun docxText(bytes: ByteArray): String {
        var xml: String? = null
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            if (out.size() > MAX_XML_BYTES) throw DocumentException("Die DOCX-Datei ist zu groß.")
                        }
                        xml = out.toString(Charsets.UTF_8.name())
                        break
                    }
                }
            }
        } catch (e: DocumentException) {
            throw e
        } catch (e: Exception) {
            throw DocumentException("Die DOCX-Datei lässt sich nicht lesen (${e.message ?: "kein gültiges Archiv"}).")
        }
        val body = xml ?: throw DocumentException("Das ist keine gültige DOCX-Datei (word/document.xml fehlt).")
        return body
            .replace(Regex("<w:instrText[^>]*>.*?</w:instrText>", RegexOption.DOT_MATCHES_ALL), "") // Feldanweisungen, kein Text
            .replace(Regex("</w:p>"), "\n")
            .replace(Regex("<w:tab\\s*/>"), "\t")
            .replace(Regex("<w:(?:br|cr)\\s*/>"), "\n")
            .replace(Regex("</w:tc>"), "\t")
            .replace(Regex("<[^>]+>"), "")
            .let(::unescapeXml)
    }

    private fun unescapeXml(text: String): String =
        text.replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
            .replace(Regex("&#([0-9]+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Einheitliche Zeilenenden, keine Steuerzeichen, höchstens eine Leerzeile in Folge. */
    fun tidy(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ').replace("\u0000", "")
            .replace(Regex("[\\u0001-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    /**
     * Kürzt auf höchstens [tokenBudget] Tokens (grobe Schätzung über [charsPerToken]) an einer Absatz- oder Satzgrenze.
     * Das Kontextfenster lokaler Modelle ist klein: lieber der Anfang vollständig als alles verstümmelt.
     */
    fun fit(text: String, tokenBudget: Int, charsPerToken: Double = 3.0): Fitted {
        val limit = (tokenBudget * charsPerToken).toInt().coerceAtLeast(200)
        if (text.length <= limit) return Fitted(text, false, text.length)
        val window = text.substring(0, limit)
        val minCut = (limit * 0.7).toInt()
        val cut = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "), window.lastIndexOf("\n"))
            .firstOrNull { it >= minCut }?.let { it + 1 } ?: limit
        return Fitted(window.substring(0, cut).trimEnd(), true, text.length)
    }

    /** Text fürs Eingabefeld: Kopfzeile mit dem Dateinamen, danach der (gekürzte) Inhalt; die Frage tippt der Nutzer dahinter. */
    fun inputText(name: String, fitted: Fitted): String =
        "[Datei: $name]\n${fitted.text}" + if (fitted.truncated) "\n[… gekürzt, ${fitted.totalChars} Zeichen insgesamt]" else ""

    /**
     * Schneidet einen mitten im Satz abgebrochenen Text (Token-Grenze) am letzten vollständigen Satz ab. Ist kein Satzende im
     * hinteren Teil zu finden, bleibt der Text wie er ist.
     */
    fun completeSentences(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.last() in ".!?…") return trimmed
        val end = trimmed.lastIndexOfAny(charArrayOf('.', '!', '?', '…'))
        return if (end >= trimmed.length / 2) trimmed.substring(0, end + 1) else trimmed
    }

    /** Beschreibung eines Bildes fürs Eingabefeld: Kopfzeile mit dem Dateinamen, die Frage schreibt der Nutzer dahinter. */
    fun imageInputText(name: String, description: String): String = "[Bild: $name - Beschreibung des Bildmodells]\n${description.trim()}"

    /** Anweisung zur Zusammenfassung (englisch: kleine Modelle folgen englischen Anweisungen besser). */
    fun summaryPrompt(name: String, fitted: Fitted, language: String): String =
        "Summarize the following document in $language, in 3 to 6 short sentences. Only use what is written in it.\n\n" +
            "[Document: $name]\n${fitted.text}" +
            if (fitted.truncated) "\n[… the document continues; only the beginning of ${fitted.totalChars} characters is shown]" else ""

    /** Text zum Vorlesen: Markdown-Zeichen weg, Zeilenumbrüche als Pausen. */
    fun speechText(text: String): String =
        text.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
            .replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("[*_`~]+"), "")
            .replace(Regex("[ \\t]*\\n+[ \\t]*"), ". ")
            .replace(Regex("\\.\\s*([.!?:;,])"), "$1")
            .replace(Regex("\\s+"), " ").trim()

    /** Abschnitte zum Vorlesen: klein genug für Fortschritt und Stopp, groß genug für flüssiges Sprechen. */
    fun speechChunks(text: String, max: Int = 600): List<String> = Speech.chunks(speechText(text), max)
}
