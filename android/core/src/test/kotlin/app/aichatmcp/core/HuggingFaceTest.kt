package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HuggingFaceTest {
    @Test
    fun parsesPagesFilesAndShortForms() {
        assertEquals("Qwen/Qwen3-4B-GGUF" to null, HuggingFace.parseInput("Qwen/Qwen3-4B-GGUF"))
        assertEquals("Qwen/Qwen3-4B-GGUF" to null, HuggingFace.parseInput("https://huggingface.co/Qwen/Qwen3-4B-GGUF/"))
        assertEquals("Qwen/Qwen3-4B-GGUF" to null, HuggingFace.parseInput("huggingface.co/Qwen/Qwen3-4B-GGUF/tree/main"))
        assertEquals(
            "unsloth/Qwen3-1.7B-GGUF" to "Qwen3-1.7B-Q4_K_M.gguf",
            HuggingFace.parseInput("https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf?download=true"),
        )
        assertEquals("a/b" to "sub/dir/x.gguf", HuggingFace.parseInput("https://huggingface.co/a/b/blob/main/sub/dir/x.gguf"))
        assertNull(HuggingFace.parseInput("nur-ein-wort"))
        assertNull(HuggingFace.parseInput("a b/c"))
    }

    @Test
    fun buildsAddresses() {
        assertEquals("https://huggingface.co/api/models/o/r/tree/main?recursive=true", HuggingFace.treeUrl("o/r"))
        assertEquals("https://huggingface.co/o/r/resolve/main/dir/a%20b.gguf?download=true", HuggingFace.fileUrl("o/r", "dir/a b.gguf"))
        assertTrue(HuggingFace.searchUrl("qwen 3 4b").contains("search=qwen+3+4b") && HuggingFace.searchUrl("x").contains("filter=gguf"))
    }

    @Test
    fun readsTreeAndSearchAnswers() {
        val tree = """[{"type":"file","path":"README.md","size":10},{"type":"directory","path":"sub"},
            {"type":"file","path":"M-Q8_0.gguf","size":4000},{"type":"file","path":"M-Q4_K_M.gguf","size":2500},
            {"type":"file","path":"mmproj-M-f16.gguf","size":300},{"type":"file","path":"sub/other-Q4_0.gguf","size":99}]"""
        val files = HuggingFace.parseTree(tree, "o/r")
        assertEquals(listOf("README.md", "M-Q8_0.gguf", "M-Q4_K_M.gguf", "mmproj-M-f16.gguf", "sub/other-Q4_0.gguf"), files.map { it.name })
        val gguf = HuggingFace.ggufFiles(files)
        assertEquals(listOf("mmproj-M-f16.gguf", "M-Q4_K_M.gguf", "M-Q8_0.gguf"), gguf.map { it.file.name }) // nur oberster Ordner, kleinste zuerst
        assertEquals(listOf("F16", "Q4_K_M", "Q8_0"), gguf.map { it.quant })
        assertTrue(gguf[0].isProjector && !gguf[1].isProjector)
        assertEquals("M-Q4_K_M.gguf", HuggingFace.suggested(gguf)!!.file.name)
        val hits = HuggingFace.parseSearch("""[{"id":"a/b","downloads":5,"likes":1},{"modelId":"c/d"},{"nix":1}]""")
        assertEquals(listOf("a/b", "c/d"), hits.map { it.id })
        assertEquals(5L, hits[0].downloads)
        assertEquals(emptyList(), HuggingFace.parseSearch("{}"))
    }

    @Test
    fun subfolderFilesAreUsedWhenTheTopFolderHasNone() {
        val files = HuggingFace.parseTree("""[{"type":"file","path":"Q4/x-Q4_K_M.gguf","size":5}]""", "o/r")
        assertEquals(listOf("Q4/x-Q4_K_M.gguf"), HuggingFace.ggufFiles(files).map { it.file.name })
    }

    @Test
    fun providersCleanTheirModelLists() {
        assertEquals(listOf("gpt-4o", "o3-mini"), Providers.openai.cleanModels(listOf("whisper-1", "text-embedding-3-small", "gpt-4o", "dall-e-3", "o3-mini", "tts-1")))
        assertEquals(listOf("claude-sonnet-4", "claude-3-haiku"), Providers.anthropic.cleanModels(listOf("claude-3-haiku", "claude-sonnet-4")))
        assertEquals(listOf("gemini-2.0-flash", "gemini-1.5-pro"), Providers.gemini.cleanModels(listOf("models/gemini-1.5-pro", "models/embedding-001", "models/gemini-2.0-flash", "models/imagen-3")))
        assertEquals(mapOf("x-api-key" to "K", "anthropic-version" to "2023-06-01"), Providers.anthropic.extraHeaders("K"))
        assertEquals(listOf("llama"), Providers.lmstudio.cleanModels(listOf("text-embedding-nomic", "llama")))
    }

    @Test
    fun providerFromStoredUrlAndInsecureWarning() {
        assertEquals("openai", Providers.forUrl("https://api.openai.com/v1/").id)
        assertEquals("gemini", Providers.forUrl("https://generativelanguage.googleapis.com/v1beta/openai").id)
        assertEquals("lmstudio", Providers.forUrl("").id)
        assertEquals("lmstudio", Providers.forUrl("http://192.168.1.5:1234/v1").id)
        assertEquals("lmstudio", Providers.forUrl("http://127.0.0.1:11434/v1").id)
        assertEquals("custom", Providers.forUrl("https://my.example.org/v1").id)
        assertFalse(Providers.insecureWarning("http://192.168.1.5:1234/v1"))
        assertFalse(Providers.insecureWarning("http://localhost:1234/v1"))
        assertFalse(Providers.insecureWarning("https://api.openai.com/v1"))
        assertTrue(Providers.insecureWarning("http://myserver.example.com/v1"))
        assertTrue(Providers.all.filter { it.needsKey }.all { it.baseUrl.startsWith("https://") && it.keyUrl.startsWith("https://") })
    }
}
