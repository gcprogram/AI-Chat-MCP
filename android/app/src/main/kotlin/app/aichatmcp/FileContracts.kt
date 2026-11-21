package app.aichatmcp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

/**
 * Datei speichern mit Vorschlag für den Ordner: Eingabe = Dateiname und Adresse einer Datei, in deren Ordner der Dialog öffnen soll (leer = Standard). So landet ein
 * Spielbuch neben dem Buch, aus dem es stammt.
 */
class CreateDocumentAt(private val mime: String) : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, input.first).also { intent ->
            if (input.second.isNotEmpty()) runCatching { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(input.second)) }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Datei öffnen mit Vorschlag für den Ordner (Eingabe: Adresse einer Datei, in deren Ordner der Dialog öffnen soll; leer = Standard). */
class OpenDocumentAt : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").also { intent ->
            if (input.isNotEmpty()) runCatching { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(input)) }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}
