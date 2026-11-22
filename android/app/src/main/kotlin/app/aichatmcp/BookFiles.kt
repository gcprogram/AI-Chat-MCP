package app.aichatmcp

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Der Ordner `Documents/AIChatMCP/books` für Spielbücher (`*.game`) und gespeicherte Spiele (`*.savegame`): sichtbar im Dateimanager und am PC, ohne Berechtigung "Zugriff auf
 * alle Dateien" (ab Android 10 über MediaStore, davor im app-eigenen Ordner). Dateien mit demselben Namen werden immer ersetzt. Lesen kann die App ohne Dialog nur, was sie
 * selbst angelegt hat; Dateien anderer Herkunft (nach einer Neuinstallation, vom PC) wählt der Nutzer im Dateidialog.
 */
class BookFiles(private val context: Context) {
    /** Ein Eintrag im Ordner: Dateiname, Adresse zum Lesen, Änderungszeit (ms). */
    data class Entry(val name: String, val uri: String, val modified: Long) {
        val isSaveGame: Boolean get() = name.endsWith(".savegame", ignoreCase = true)
    }

    /** Wie der Ordner für den Nutzer heißt. */
    val label: String get() = if (Build.VERSION.SDK_INT >= 29) "$RELATIVE/" else (fallbackDir().path)

    private fun fallbackDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "AIChatMCP/books").apply { mkdirs() }

    private fun collection(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Schreibt [text] als [name] in den Ordner und ersetzt eine gleichnamige Datei. Gibt die Adresse zurück, null bei Fehler. */
    fun save(name: String, text: String): String? = runCatching {
        if (Build.VERSION.SDK_INT < 29) {
            val file = File(fallbackDir(), name)
            file.writeText(text, Charsets.UTF_8)
            return@runCatching Uri.fromFile(file).toString()
        }
        val resolver = context.contentResolver
        val existing = findOwn(name)
        val uri = existing ?: resolver.insert(
            collection(),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
            },
        ) ?: return@runCatching null
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: return@runCatching null
        uri.toString()
    }.getOrNull()

    private fun findOwn(name: String): Uri? {
        val resolver = context.contentResolver
        resolver.query(
            collection(), arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(name, "$RELATIVE/"), null,
        )?.use { c -> if (c.moveToFirst()) return ContentUris.withAppendedId(collection(), c.getLong(0)) }
        return null
    }

    /** Alle Spielbücher und gespeicherten Spiele im Ordner, die neuesten zuerst. */
    fun list(): List<Entry> = runCatching {
        if (Build.VERSION.SDK_INT < 29) {
            return@runCatching fallbackDir().listFiles { f -> f.isFile && isBookFile(f.name) }.orEmpty()
                .map { Entry(it.name, Uri.fromFile(it).toString(), it.lastModified()) }.sortedByDescending { it.modified }
        }
        val out = mutableListOf<Entry>()
        context.contentResolver.query(
            collection(), arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("$RELATIVE/"), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (isBookFile(name)) out += Entry(name, ContentUris.withAppendedId(collection(), c.getLong(0)).toString(), c.getLong(2) * 1000)
            }
        }
        out
    }.getOrDefault(emptyList())

    fun read(uri: String): String? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull()

    private companion object {
        const val RELATIVE = "Documents/AIChatMCP/books"

        fun isBookFile(name: String) = name.endsWith(".game", ignoreCase = true) || name.endsWith(".savegame", ignoreCase = true) || name.endsWith(".gamebook.json", ignoreCase = true)
    }
}
