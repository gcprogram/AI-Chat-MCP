package app.aichatmcp.core

/*
 * Katalog der Modelle, die die App selbst herunterlädt (Einführung beim Erststart und Einstellungen > Modelle).
 * Quelle sind die Repositories auf Hugging Face; die Größen stammen aus deren Dateilisten und dienen der Platzprüfung vorab
 * (der Download selbst prüft gegen die Größe, die der Server meldet).
 */

enum class ModelKind { CHAT, WHISPER, VISION, PIPER }

/** Eine Datei im Netz. [name] ist der Pfad relativ zum Zielordner (dabei dürfen Dateien umbenannt werden, z. B. zu model.gguf). */
data class RemoteFile(val url: String, val name: String, val bytes: Long = 0)

data class CatalogEntry(
    val id: String,
    val kind: ModelKind,
    val title: String,
    /** Kurzbeschreibung (englisch; die Oberfläche übersetzt sie). */
    val note: String,
    val files: List<RemoteFile>,
    /** Unterordner im Zielordner der Art (Whisper, Piper); leer bei Chat- und Bildmodellen (Dateien liegen direkt im Zielordner). */
    val folder: String = "",
    val recommended: Boolean = false,
    /** Sprach-Kürzel, für die eine Piper-Stimme gedacht ist. */
    val language: String = "",
) {
    val bytes: Long get() = files.sumOf { it.bytes }
}

object ModelCatalog {
    private const val MB = 1_000_000L

    private fun hf(repo: String, path: String) = "https://huggingface.co/$repo/resolve/main/$path?download=true"

    val chat = listOf(
        CatalogEntry(
            "qwen3-4b-instruct", ModelKind.CHAT, "Qwen3 4B Instruct (2507)",
            "Best all-rounder for tools (to-dos, calendar, alarms). Needs a recent phone, about 3 GB of RAM.",
            listOf(RemoteFile(hf("lmstudio-community/Qwen3-4B-Instruct-2507-GGUF", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf"), "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", 2497 * MB)),
            recommended = true,
        ),
        CatalogEntry(
            "qwen3-1.7b", ModelKind.CHAT, "Qwen3 1.7B",
            "Small and fast, for older phones. Simpler answers, less reliable with tools; may show some 'thinking' text.",
            listOf(RemoteFile(hf("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf"), "Qwen3-1.7B-Q4_K_M.gguf", 1107 * MB)),
        ),
        CatalogEntry(
            "gemmasutra-4b", ModelKind.CHAT, "Gemmasutra Small 4B (role play)",
            "Fine-tuned for role play and storytelling; writes tool calls poorly. Role-play model.",
            listOf(
                RemoteFile(
                    hf("bartowski/TheDrummer_Gemmasutra-Small-4B-v1-GGUF", "TheDrummer_Gemmasutra-Small-4B-v1-Q4_K_M.gguf"),
                    "TheDrummer_Gemmasutra-Small-4B-v1-Q4_K_M.gguf", 2459 * MB,
                ),
            ),
        ),
        CatalogEntry(
            "gemma-3n-e2b", ModelKind.CHAT, "Gemma 3n E2B",
            "Google's small model built for phones; good language skills, weaker with tools.",
            listOf(RemoteFile(hf("lmstudio-community/gemma-3n-E2B-it-GGUF", "gemma-3n-E2B-it-Q4_K_M.gguf"), "gemma-3n-E2B-it-Q4_K_M.gguf", 2788 * MB)),
        ),
    )

    val whisper = listOf(
        CatalogEntry(
            "whisper-small", ModelKind.WHISPER, "Whisper small",
            "Offline speech input in many languages. Good accuracy, fast enough on a phone.",
            listOf(
                RemoteFile(hf("csukuangfj/sherpa-onnx-whisper-small", "small-encoder.int8.onnx"), "small-encoder.int8.onnx", 112 * MB),
                RemoteFile(hf("csukuangfj/sherpa-onnx-whisper-small", "small-decoder.int8.onnx"), "small-decoder.int8.onnx", 262 * MB),
                RemoteFile(hf("csukuangfj/sherpa-onnx-whisper-small", "small-tokens.txt"), "small-tokens.txt", 1 * MB),
            ),
            folder = "sherpa-onnx-whisper-small", recommended = true,
        ),
    )

    val vision = listOf(
        CatalogEntry(
            "smolvlm-500m", ModelKind.VISION, "SmolVLM 500M",
            "Describes pictures as text so a chat model can talk about them. Two files, loaded only while describing.",
            listOf(
                RemoteFile(hf("ggml-org/SmolVLM-500M-Instruct-GGUF", "SmolVLM-500M-Instruct-Q8_0.gguf"), "model.gguf", 437 * MB),
                RemoteFile(hf("ggml-org/SmolVLM-500M-Instruct-GGUF", "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf"), "mmproj.gguf", 109 * MB),
            ),
            recommended = true,
        ),
    )

    /** Der gemeinsame Ordner espeak-ng-data steckt in jedem Stimmen-Repository; die Dateiliste dazu holt die App bei Bedarf von Hugging Face. */
    const val ESPEAK_REPO = "csukuangfj/vits-piper-de_DE-thorsten-medium"

    private fun voice(id: String, title: String, language: String, onnxBytes: Long, note: String, recommended: Boolean = false): CatalogEntry {
        val repo = "csukuangfj/vits-piper-$id"
        return CatalogEntry(
            "piper-$id", ModelKind.PIPER, title, note,
            listOf(
                RemoteFile(hf(repo, "$id.onnx"), "$id.onnx", onnxBytes),
                RemoteFile(hf(repo, "$id.onnx.json"), "$id.onnx.json", 5_000),
                RemoteFile(hf(repo, "tokens.txt"), "tokens.txt", 1_000),
            ),
            folder = "vits-piper-$id", recommended = recommended, language = language,
        )
    }

    val piper = listOf(
        voice("de_DE-thorsten-medium", "German – Thorsten", "de", 63 * MB, "Natural German male voice.", recommended = true),
        voice("de_DE-thorsten_emotional-medium", "German – Thorsten (emotions)", "de", 77 * MB, "German male voice with several moods."),
        voice("en_US-lessac-medium", "English (US) – Lessac", "en", 63 * MB, "Clear American English voice.", recommended = true),
        voice("en_GB-cori-medium", "English (UK) – Cori", "en", 64 * MB, "British English female voice."),
    )

    /** Der gemeinsame Sprachdaten-Ordner, der zu jeder Piper-Stimme gehört (einmalig, etwa 18 MB). */
    const val ESPEAK_BYTES = 18 * MB

    val all: List<CatalogEntry> get() = chat + whisper + vision + piper

    fun byId(id: String): CatalogEntry? = all.firstOrNull { it.id == id }
}
