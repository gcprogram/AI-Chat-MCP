package app.aichatmcp.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Eine Datei in einem [FileStore]: Name, Größe, Zeitpunkt der letzten Änderung (ms) und die Angabe, mit der man sie öffnet (Pfad oder `content://`-Adresse). */
data class StoredFile(val name: String, val bytes: Long, val ref: String, val modified: Long = 0L)

/**
 * Ein flacher Ordner mit Dateien, egal ob ein normaler Ordner ([DirStore]) oder ein vom Nutzer gewählter Ordner des Systems (Android: Storage Access
 * Framework). Der Downloader und die Sicherung arbeiten nur damit; so braucht die App keine Berechtigung "Zugriff auf alle Dateien".
 */
interface FileStore {
    /** Für die Anzeige. */
    val label: String

    /** Größe in Bytes; -1, wenn es die Datei nicht gibt. */
    fun size(name: String): Long

    /** Schreibt in die Datei (wird angelegt); [append] hängt an das Vorhandene an, sonst wird sie neu geschrieben. */
    fun write(name: String, append: Boolean = false): OutputStream

    fun read(name: String): InputStream?

    fun delete(name: String): Boolean

    /** Benennt um; ein vorhandenes Ziel wird nicht überschrieben (false). */
    fun rename(from: String, to: String): Boolean

    fun list(): List<StoredFile>

    /** Freier Platz in Bytes; -1, wenn er sich nicht ermitteln lässt. */
    fun freeBytes(): Long

    /** Unterordner; mit [create] wird er angelegt, wenn es ihn nicht gibt. Null, wenn es ihn nicht gibt (oder das Anlegen nicht geht). */
    fun sub(name: String, create: Boolean = true): FileStore?

    fun exists(name: String): Boolean = size(name) >= 0
}

/** Ein normaler Ordner als [FileStore]. */
class DirStore(val dir: File) : FileStore {
    override val label: String get() = dir.path

    private fun file(name: String) = File(dir, name)

    override fun size(name: String): Long = file(name).let { if (it.isFile) it.length() else -1L }

    override fun write(name: String, append: Boolean): OutputStream {
        file(name).parentFile?.mkdirs()
        return java.io.FileOutputStream(file(name), append)
    }

    override fun read(name: String): InputStream? = file(name).takeIf { it.isFile }?.inputStream()

    override fun delete(name: String): Boolean = file(name).delete()

    override fun rename(from: String, to: String): Boolean {
        val target = file(to)
        if (target.exists()) return false
        return file(from).renameTo(target)
    }

    override fun list(): List<StoredFile> =
        dir.listFiles { f -> f.isFile }.orEmpty().map { StoredFile(it.name, it.length(), it.path, it.lastModified()) }.sortedBy { it.name.lowercase() }

    override fun freeBytes(): Long {
        var probe: File? = dir
        while (probe != null && !probe.exists()) probe = probe.parentFile
        return probe?.usableSpace ?: -1L
    }

    override fun sub(name: String, create: Boolean): FileStore? {
        val d = File(dir, name)
        if (create) d.mkdirs()
        return if (d.isDirectory) DirStore(d) else null
    }
}

/** Kopiert Dateien zwischen zwei Speichern (Sicherung und Wiederherstellung). */
object StoreCopy {
    /**
     * Kopiert alle Dateien aus [from] nach [to], die [accept] erlaubt. Mit [onlyNewer] (Wiederherstellen) werden vorhandene Dateien nur ersetzt, wenn die Quelle
     * neuer ist; ohne (Sichern) wird immer geschrieben. Liefert die Zahl der kopierten Dateien.
     */
    fun copy(from: FileStore, to: FileStore, onlyNewer: Boolean, accept: (String) -> Boolean = { true }): Int {
        val existing = to.list().associateBy { it.name }
        var n = 0
        for (f in from.list()) {
            if (!accept(f.name)) continue
            val have = existing[f.name]
            if (onlyNewer && have != null && f.modified <= have.modified) continue
            val input = from.read(f.name) ?: continue
            input.use { src -> to.write(f.name, append = false).use { out -> src.copyTo(out) } }
            n++
        }
        return n
    }
}
