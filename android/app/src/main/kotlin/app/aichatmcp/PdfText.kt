package app.aichatmcp

import android.content.Context
import app.aichatmcp.core.DocumentException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/** Text aus PDF-Dateien (PdfBox für Android), seitenweise. Scans ohne Textebene liefern leeren Text - das meldet der Aufrufer. */
object PdfText {
    private const val MAX_PAGES = 300

    fun extract(context: Context, bytes: ByteArray): List<String> {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(bytes).use { document ->
            if (document.isEncrypted) throw DocumentException("Die PDF-Datei ist verschlüsselt.")
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            return (1..minOf(document.numberOfPages, MAX_PAGES)).map { page ->
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(document)
            }
        }
    }
}
