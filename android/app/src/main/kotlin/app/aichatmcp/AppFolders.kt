package app.aichatmcp

import android.content.Context
import android.net.Uri
import app.aichatmcp.core.DirStore
import app.aichatmcp.core.FileStore
import app.aichatmcp.core.Folders
import java.io.File

/**
 * Wo die Modelle liegen. Standard ist der app-eigene externe Ordner (`Android/data/<app>/files/models`, ohne Berechtigung les- und schreibbar,
 * geht bei der Deinstallation verloren). Der Nutzer kann stattdessen einen eigenen Ordner wählen (Storage Access Framework): Er bleibt bei einer
 * Neuinstallation erhalten, ist für Dateimanager und PC sichtbar und kann von anderen Apps mit denselben Modellen genutzt werden. Dafür braucht die
 * App keine Berechtigung "Zugriff auf alle Dateien" (die Google Play nur Dateimanagern und ähnlichen Apps erlaubt).
 */
class AppFolders(private val context: Context) {
    /** Der Modellordner im app-eigenen Speicher. */
    val appModels: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, Folders.MODELS)

    /** Hat die App noch Zugriff auf den gewählten Ordner? (Der Nutzer kann ihn in den Systemeinstellungen entziehen, oder der Ordner wurde gelöscht.) */
    fun treeAccessible(tree: String): Boolean {
        if (tree.isBlank()) return false
        val uri = Uri.parse(tree)
        return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }

    /** Der Speicher für Modelle: der gewählte Ordner, sonst (oder wenn der Zugriff fehlt) der app-eigene. */
    fun modelStore(tree: String): FileStore = if (treeAccessible(tree)) TreeStore(context, Uri.parse(tree)) else DirStore(appModels)

    /** Ist der gewählte Ordner in Benutzung (gewählt und erreichbar)? */
    fun usingTree(tree: String): Boolean = treeAccessible(tree)
}

/** Der App-Speicher (privat: Einstellungen, Companions, Chats). Sicherungen in einen frei gewählten Ordner macht [ChatViewModel.backupTo]. */
fun openStorage(context: Context, asset: (String) -> String? = { null }): AppStorage = AppStorage(context.filesDir, asset)
