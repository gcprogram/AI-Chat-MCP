package app.aichatmcp

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aichatmcp.core.Documents
import app.aichatmcp.local.VisionNative
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Vergleich von Bildmodellen und Prompts auf dem Gerät (nur Protokoll im Log, Tag AiChatVision). Läuft ohne Bedienoberfläche:
 * Bilder in `cache/vlm/images`, weitere Modelle in `cache/vlm/<name>/{model,mmproj}.gguf`; das eingerichtete Modell
 * der App (`files/vision`) heißt "aktuell". Ohne Bilder wird der Test übersprungen.
 */
@RunWith(AndroidJUnit4::class)
class VisionOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val oldPrompt = "Describe this image in exactly 7 sentences. Sentence 1: the main subject. Sentence 2: appearance and clothing. " +
        "Sentence 3: face and expression. Sentence 4: pose and action. Sentence 5: the background and setting. " +
        "Sentence 6: colors and lighting. Sentence 7: the overall mood. Also mention any text you can read."

    @Test
    fun compareModelsAndPrompts() {
        val root = File(context.cacheDir, "vlm")
        val images = File(root, "images").listFiles()?.sortedBy { it.name }.orEmpty()
        assumeTrue("Keine Testbilder unter cache/vlm/images", images.isNotEmpty())
        val runs = buildList {
            val current = File(context.filesDir, "vision")
            if (File(current, "model.gguf").exists()) {
                add(Triple("aktuell+alter Prompt", current, oldPrompt))
                add(Triple("aktuell+neuer Prompt", current, IMAGE_PROMPT))
            }
            root.listFiles { f -> f.isDirectory && f.name != "images" }?.sortedBy { it.name }?.forEach {
                add(Triple("${it.name}+neuer Prompt", it, IMAGE_PROMPT))
            }
        }
        val only = InstrumentationRegistry.getArguments().getString("only")
        for ((label, dir, prompt) in runs) {
            if (only != null && !label.contains(only)) continue
            val model = File(dir, "model.gguf")
            val proj = File(dir, "mmproj.gguf")
            if (!model.exists() || !proj.exists()) continue
            for (image in images) {
                val jpeg = ImagePrep.jpegFromBytes(image.readBytes())
                val start = System.nanoTime()
                val handle = VisionNative.load(model.path, proj.path, 4)
                check(handle != 0L) { VisionNative.lastError() }
                val loaded = (System.nanoTime() - start) / 1e9
                val text = try {
                    val out = StringBuilder()
                    VisionNative.describe(handle, jpeg, prompt.toByteArray(Charsets.UTF_8), 320) { out.append(String(it, Charsets.UTF_8)); true }
                    Documents.completeSentences(out.toString().trim())
                } finally {
                    VisionNative.free(handle)
                }
                val total = (System.nanoTime() - start) / 1e9
                val sentences = Regex("[.!?](\\s|$)").findAll(text).count()
                Log.i("AiChatVision", "ERGEBNIS [$label] ${image.name}: ${"%.0f".format(total)} s (Laden ${"%.1f".format(loaded)} s), $sentences Sätze\n$text\nENDE")
            }
        }
    }
}
