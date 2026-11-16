package app.aichatmcp.core

import java.io.File

/*
 * Standardordner der App: AIChatMCP/models (Modelldateien für das Handy), AIChatMCP/companions, AIChatMCP/chats. Sie liegen im gemeinsamen
 * Speicher (mit "Zugriff auf alle Dateien" sichtbar für Dateimanager und PC), sonst im app-eigenen externen Ordner.
 */
object Folders {
    const val ROOT_NAME = "AIChatMCP"
    const val MODELS = "models"
    const val COMPANIONS = "companions"
    const val CHATS = "chats"

    /** Endungen, die in `models/` als Chat-Modell gelten. */
    fun isModelFile(f: File) = f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && !f.name.startsWith("mmproj", ignoreCase = true)

    /** Kopiert alles aus [from] nach [to], was dort noch fehlt (nichts wird überschrieben). Liefert die Zahl der kopierten Dateien. */
    fun copyMissing(from: File, to: File): Int {
        if (!from.isDirectory || from.canonicalPath == to.canonicalPath) return 0
        var n = 0
        from.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { src ->
            val target = File(to, src.relativeTo(from).path)
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                src.copyTo(target)
                n++
            }
        }
        return n
    }

    /** Modelldateien eines Ordners (GGUF ohne Bild-Hilfsdateien), nach Namen sortiert. */
    fun models(dir: File): List<File> = dir.listFiles { f -> isModelFile(f) }.orEmpty().sortedBy { it.name.lowercase() }
}
