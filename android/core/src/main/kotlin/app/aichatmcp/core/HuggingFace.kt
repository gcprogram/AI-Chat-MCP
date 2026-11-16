package app.aichatmcp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Ein Treffer der Modellsuche auf Hugging Face. */
data class ModelHit(val id: String, val downloads: Long, val likes: Long)

/** Eine GGUF-Datei eines Repositories mit Größe und Quantisierung (z. B. "Q4_K_M"). */
data class GgufFile(val file: RemoteFile, val quant: String, val isProjector: Boolean)

/**
 * Hugging Face: Modelle suchen, die Dateien eines Repositories auflisten und Adressen/Eingaben auswerten. Der reine Teil (Adressen, Auswertung
 * der JSON-Antworten) ist ohne Netz testbar; [HuggingFaceApi] holt die Antworten.
 */
object HuggingFace {
    private const val HOST = "https://huggingface.co"

    /** "owner/repo" aus einer Eingabe (Adresse einer Seite, Datei-Adresse oder die Kurzform); null, wenn sie nicht passt. Dazu der Dateipfad, falls die Eingabe eine Datei nennt. */
    fun parseInput(input: String): Pair<String, String?>? {
        val text = input.trim().removeSuffix("/")
        val path = text.removePrefix("https://huggingface.co/").removePrefix("http://huggingface.co/").removePrefix("huggingface.co/")
        val parts = path.substringBefore('?').split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1]
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(owner) || !Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(repo)) return null
        // .../resolve/<branch>/<Datei> oder .../blob/<branch>/<Datei>
        val file = if (parts.size > 4 && (parts[2] == "resolve" || parts[2] == "blob")) parts.drop(4).joinToString("/") else null
        return "$owner/$repo" to file
    }

    fun treeUrl(repo: String) = "$HOST/api/models/$repo/tree/main?recursive=true"

    fun searchUrl(query: String, limit: Int = 25) =
        "$HOST/api/models?search=${URLEncoder.encode(query.trim(), "UTF-8")}&filter=gguf&sort=downloads&direction=-1&limit=$limit"

    fun fileUrl(repo: String, path: String) =
        "$HOST/$repo/resolve/main/${path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}?download=true"

    /** Die Dateien aus der Antwort des Verzeichnis-Aufrufs (nur Dateien, Pfad relativ zum Repository). */
    fun parseTree(json: String, repo: String): List<RemoteFile> {
        val array = AppJson.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o.string("type") != "file") return@mapNotNull null
            val path = o.string("path") ?: return@mapNotNull null
            val size = (o["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            RemoteFile(fileUrl(repo, path), path, size)
        }
    }

    fun parseSearch(json: String): List<ModelHit> {
        val array = AppJson.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.string("id") ?: o.string("modelId") ?: return@mapNotNull null
            ModelHit(id, (o["downloads"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0, (o["likes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0)
        }
    }

    private val QUANT = Regex("(?i)(IQ\\d_[A-Z0-9]+|UD-[A-Z0-9_]+|Q\\d_K_[A-Z]+|Q\\d_K|Q\\d_\\d|Q\\d|BF16|F16|F32)")

    /** Die GGUF-Dateien eines Repositories (Dateien in Unterordnern nur, wenn es keine im obersten Ordner gibt), kleinste zuerst; Bildmodell-Projektoren sind markiert. */
    fun ggufFiles(files: List<RemoteFile>): List<GgufFile> {
        val all = files.filter { it.name.endsWith(".gguf", ignoreCase = true) }
        val top = all.filter { '/' !in it.name }
        return (top.ifEmpty { all }).map { f ->
            val base = f.name.substringAfterLast('/')
            GgufFile(f, QUANT.find(base)?.value?.uppercase().orEmpty(), base.startsWith("mmproj", ignoreCase = true))
        }.sortedBy { it.file.bytes }
    }

    /** Die beste Voreinstellung unter den Dateien: Q4_K_M, sonst eine andere 4-Bit-Stufe, sonst die kleinste (ohne Projektor). */
    fun suggested(files: List<GgufFile>): GgufFile? {
        val models = files.filter { !it.isProjector }
        return models.firstOrNull { it.quant == "Q4_K_M" } ?: models.firstOrNull { it.quant.startsWith("Q4") || it.quant.startsWith("IQ4") } ?: models.firstOrNull()
    }
}

/** Fragt Hugging Face ab. Fehler als [IOException] mit verständlichem Text. */
class HuggingFaceApi(http: OkHttpClient = OkHttpClient(), private val token: () -> String? = { null }) {
    private val client = http.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun get(url: String): String {
        val request = Request.Builder().url(url).apply { token()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }.build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw IOException("Hugging Face ist nicht erreichbar: ${e.message}")
        }
        response.use {
            val body = it.body?.string().orEmpty()
            when {
                it.code == 404 -> throw IOException("Nicht gefunden (HTTP 404). Name oder Adresse prüfen.")
                it.code == 401 || it.code == 403 -> throw IOException("Zugriff verweigert (HTTP ${it.code}): geschütztes Modell, Zugangsschlüssel nötig.")
                it.code != 200 -> throw IOException("Hugging Face meldet HTTP ${it.code}.")
            }
            return body
        }
    }

    fun search(query: String): List<ModelHit> = HuggingFace.parseSearch(get(HuggingFace.searchUrl(query)))

    fun files(repo: String): List<RemoteFile> = HuggingFace.parseTree(get(HuggingFace.treeUrl(repo)), repo)

    /** Alle Dateien eines Ordners im Repository (z. B. espeak-ng-data), Namen unverändert relativ zum Repository. */
    fun folder(repo: String, prefix: String): List<RemoteFile> = files(repo).filter { it.name.startsWith("$prefix/") }
}
