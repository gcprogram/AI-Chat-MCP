package app.aichatmcp.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.aichatmcp.BuildConfig
import app.aichatmcp.ui.tNow

/**
 * Schickt eine Meldung an den Entwickler. Wohin, bestimmt die Build-Konfiguration (`android/report.properties`, nicht im Repo):
 * eine E-Mail-Adresse (das Mailprogramm öffnet sich mit fertigem Text) oder eine Webseite (der Text wird in die Zwischenablage kopiert, die Seite
 * öffnet sich). Ohne beides öffnet sich die Auswahl "Teilen", damit die Meldung trotzdem losgeht. Nichts wird ohne Zutun des Nutzers gesendet.
 */
object ReportAction {
    /** Liefert einen Hinweis für den Nutzer (Toast) oder null. */
    fun send(context: Context, subject: String, body: String): String? {
        val email = BuildConfig.REPORT_EMAIL.trim()
        val url = BuildConfig.REPORT_URL.trim()
        return try {
            when {
                email.isNotEmpty() -> {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                            .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body),
                    )
                    null
                }
                url.isNotEmpty() -> {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(subject, body))
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    tNow("The report text was copied. Paste it into the form.", "Der Meldetext wurde kopiert. Füge ihn in das Formular ein.")
                }
                else -> {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body),
                            subject,
                        ),
                    )
                    null
                }
            }
        } catch (e: Exception) {
            tNow("No app found to send the report.", "Keine App zum Senden der Meldung gefunden.")
        }
    }
}
