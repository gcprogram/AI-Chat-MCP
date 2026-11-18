package app.aichatmcp

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import app.aichatmcp.core.FileStore
import app.aichatmcp.core.StoredFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Ein vom Nutzer gewählter Ordner des Systems (Storage Access Framework, `OpenDocumentTree`) als [FileStore]. Dafür braucht die App keine Berechtigung
 * "Zugriff auf alle Dateien": Der Nutzer wählt den Ordner einmal, die App behält den Zugriff (`takePersistableUriPermission`). Andere Apps können
 * denselben Ordner wählen und so dieselben Modelle nutzen.
 *
 * Nur flache Dateien (und Unterordner über [sub]); Namen enthalten keinen "/".
 */
class TreeStore(
    private val context: Context,
    private val tree: Uri,
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(tree),
) : FileStore {
    private val resolver = context.contentResolver

    private data class Child(val docId: String, val name: String, val mime: String, val size: Long, val modified: Long) {
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    override val label: String
        get() {
            val id = rootDocId
            val volume = id.substringBefore(':')
            val path = id.substringAfter(':', "")
            return if (volume == "primary") "/" + path else "$volume/$path"
        }

    private fun children(): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootDocId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return runCatching {
            resolver.query(uri, columns, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(Child(c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    private fun find(name: String): Child? = children().firstOrNull { it.name == name && !it.isDir }

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    override fun size(name: String): Long = find(name)?.size ?: -1L

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    override fun write(name: String, append: Boolean): OutputStream {
        val existing = find(name)
        val uri = existing?.let { docUri(it.docId) } ?: create(name)
        // "wa" hängt an, "wt" schreibt neu; ohne "t" bliebe bei einer kürzeren Datei der alte Rest stehen
        return resolver.openOutputStream(uri, if (append && existing != null) "wa" else "wt") ?: throw IOException("Datei lässt sich nicht öffnen: $name")
    }

    /** Legt eine Datei an. Manche Anbieter hängen an unbekannte Endungen ".bin" an: dann wird auf den gewünschten Namen zurückbenannt. */
    private fun create(name: String): Uri {
        val created = DocumentsContract.createDocument(resolver, docUri(rootDocId), mimeFor(name), name) ?: throw IOException("Datei lässt sich nicht anlegen: $name")
        val actual = runCatching {
            resolver.query(created, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        if (actual != null && actual != name) {
            return runCatching { DocumentsContract.renameDocument(resolver, created, name) }.getOrNull() ?: created
        }
        return created
    }

    override fun read(name: String): InputStream? = find(name)?.let { resolver.openInputStream(docUri(it.docId)) }

    override fun delete(name: String): Boolean = find(name)?.let { runCatching { DocumentsContract.deleteDocument(resolver, docUri(it.docId)) }.getOrDefault(false) } ?: false

    override fun rename(from: String, to: String): Boolean {
        val source = find(from) ?: return false
        if (find(to) != null) return false
        return runCatching { DocumentsContract.renameDocument(resolver, docUri(source.docId), to) != null }.getOrDefault(false)
    }

    override fun list(): List<StoredFile> =
        children().filter { !it.isDir }.map { StoredFile(it.name, it.size, docUri(it.docId).toString(), it.modified) }.sortedBy { it.name.lowercase() }

    /** Freier Platz des Speichers, auf dem der Ordner liegt (nur für den internen Speicher und SD-Karten bekannt, sonst -1). */
    override fun freeBytes(): Long {
        if (tree.authority != "com.android.externalstorage.documents") return -1L
        val volume = rootDocId.substringBefore(':')
        val root = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
        return runCatching { root.usableSpace }.getOrDefault(0L).takeIf { it > 0 } ?: -1L
    }

    override fun sub(name: String, create: Boolean): FileStore? {
        val existing = children().firstOrNull { it.name == name && it.isDir }
        val docId = existing?.docId ?: if (!create) return null else runCatching {
            DocumentsContract.createDocument(resolver, docUri(rootDocId), DocumentsContract.Document.MIME_TYPE_DIR, name)?.let { DocumentsContract.getDocumentId(it) }
        }.getOrNull() ?: return null
        return TreeStore(context, tree, docId)
    }
}
