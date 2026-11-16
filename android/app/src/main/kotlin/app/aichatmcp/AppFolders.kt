package app.aichatmcp

import android.content.Context
import android.os.Build
import android.os.Environment
import app.aichatmcp.core.Folders
import java.io.File

/**
 * Die Standardordner `AIChatMCP/models`, `…/companions`, `…/chats`. Mit "Zugriff auf alle Dateien" liegen sie im gemeinsamen Speicher
 * (`/sdcard/AIChatMCP`, für Dateimanager und PC sichtbar, überleben eine Neuinstallation), sonst im app-eigenen externen Ordner
 * (`Android/data/<app>/files`, ohne Berechtigung les- und schreibbar, geht bei Deinstallation verloren).
 */
class AppFolders(private val context: Context) {
    /** Hat der Nutzer der App "Zugriff auf alle Dateien" erlaubt? (Ab Android 11; davor gilt der app-eigene Ordner.) */
    fun sharedAllowed(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    val root: File
        get() = if (sharedAllowed()) File(Environment.getExternalStorageDirectory(), Folders.ROOT_NAME)
        else context.getExternalFilesDir(null) ?: File(context.filesDir, Folders.ROOT_NAME)

    val models: File get() = File(root, Folders.MODELS).apply { mkdirs() }
    val companions: File get() = File(root, Folders.COMPANIONS).apply { mkdirs() }
    val chats: File get() = File(root, Folders.CHATS).apply { mkdirs() }

    /** Der Modellordner im app-eigenen Speicher (dort landen Downloads, solange der gemeinsame Ordner nicht erlaubt ist). */
    val appModels: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, Folders.MODELS)

    /** Lesbarer Pfad für die Anzeige. */
    val label: String get() = root.path
}

/** Der App-Speicher mit den richtigen Ordnern: sind die Standardordner erlaubt, liegen Companions und Chats dort, sonst im privaten Speicher. */
fun openStorage(context: Context, asset: (String) -> String? = { null }): AppStorage {
    val storage = AppStorage(context.filesDir, asset)
    val folders = AppFolders(context)
    if (folders.sharedAllowed()) storage.useFolders(folders.companions, folders.chats)
    return storage
}
