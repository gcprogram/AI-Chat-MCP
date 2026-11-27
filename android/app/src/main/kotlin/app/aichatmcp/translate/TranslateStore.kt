package app.aichatmcp.translate

import app.aichatmcp.core.translate.Chunk
import app.aichatmcp.core.translate.ImageAsset
import app.aichatmcp.core.translate.extensionForMime
import app.aichatmcp.core.translate.mimeForExtension
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Die Bilder des geöffneten Dokuments als Dateien (Nummer = Dateiname). Sie liegen auf dem Gerät, nicht im Speicher,
 * damit ein Buch mit vielen Bildern den Arbeitsspeicher nicht füllt und eine wiederhergestellte Sitzung die Bilder noch hat.
 */
class ImageStore(private val dir: File) {
    fun replaceAll(images: Map<Int, ImageAsset>) {
        dir.deleteRecursively()
        dir.mkdirs()
        for ((id, asset) in images) File(dir, "$id.${extensionForMime(asset.mime)}").writeBytes(asset.bytes)
    }

    fun load(id: Int): ImageAsset? {
        val file = dir.listFiles()?.firstOrNull { it.nameWithoutExtension == id.toString() } ?: return null
        return ImageAsset(file.readBytes(), mimeForExtension(file.extension))
    }

    fun clear() {
        dir.deleteRecursively()
    }
}

data class SavedSection(val index: Int, val source: String, val translation: String, val warnings: List<String> = emptyList())

/** Eine unterbrochene Übersetzung: die fertigen Abschnitte und die noch offenen, damit sie sich auch nach einem Neustart fortsetzen lässt. */
data class SavedSession(
    val documentName: String = "",
    val langSource: String = "en",
    val langTarget: String = "de",
    val sections: List<SavedSection> = emptyList(),
    val pending: List<Chunk> = emptyList(),
    val total: Int = 0,
    val error: String = "",
)

/** Sichert die Übersetzung nach jedem Abschnitt. Android beendet Apps im Hintergrund jederzeit; so geht beim Zurückkehren nichts verloren. */
class TranslateSessionStore(private val file: File) {
    fun load(): SavedSession? = try {
        if (!file.exists()) null else {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            SavedSession(
                documentName = o.optString("documentName"), langSource = o.optString("langSource", "en"), langTarget = o.optString("langTarget", "de"),
                sections = o.optJSONArray("sections").objects().map { s ->
                    SavedSection(s.getInt("index"), s.getString("source"), s.getString("translation"), s.optJSONArray("warnings").strings())
                },
                pending = o.optJSONArray("pending").objects().map { Chunk(it.getInt("index"), it.getString("text")) },
                total = o.optInt("total"), error = o.optString("error"),
            )
        }
    } catch (e: Exception) {
        null
    }

    fun save(data: SavedSession) {
        val o = JSONObject()
            .put("documentName", data.documentName).put("langSource", data.langSource).put("langTarget", data.langTarget)
            .put("total", data.total).put("error", data.error)
            .put("sections", JSONArray().also { a ->
                data.sections.forEach { s ->
                    a.put(JSONObject().put("index", s.index).put("source", s.source).put("translation", s.translation).put("warnings", JSONArray(s.warnings)))
                }
            })
            .put("pending", JSONArray().also { a -> data.pending.forEach { a.put(JSONObject().put("index", it.index).put("text", it.text)) } })
        val temp = File(file.path + ".tmp")
        temp.writeText(o.toString(), Charsets.UTF_8)
        temp.renameTo(file) // atomar genug: nie eine halb geschriebene Sitzung
    }

    fun clear() {
        file.delete()
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
