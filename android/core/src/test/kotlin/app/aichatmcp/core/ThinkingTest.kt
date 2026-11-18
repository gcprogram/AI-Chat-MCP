package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThinkingTest {
    private fun split(vararg chunks: String): Pair<String, String> {
        val s = ThinkSplitter()
        val parts = chunks.flatMap { s.feed(it) } + s.flush()
        return parts.filter { it.reasoning }.joinToString("") { it.text } to parts.filter { !it.reasoning }.joinToString("") { it.text }
    }

    @Test
    fun separatesTheThinkBlockFromTheAnswer() {
        val (r, a) = split("<think>\nOkay, ich überlege.\n</think>\n\nIch bin gut.")
        assertEquals("Okay, ich überlege.\n", r)
        assertEquals("Ich bin gut.", a)
    }

    @Test
    fun worksWhenTagsAreSplitAcrossChunks() {
        val (r, a) = split("<thi", "nk>\nDenke", " nach</thi", "nk>", "\n\nHallo", " Tom.")
        assertEquals("Denke nach", r)
        assertEquals("Hallo Tom.", a)
    }

    @Test
    fun plainAnswersPassUnchanged() {
        assertEquals("" to "Ich bin gut.", split("Ich bin gut."))
        assertEquals("" to "  Hallo <b>", split("  Hal", "lo <b>"))
        assertEquals("" to "<th", split("<th")) // abgebrochener Anfang ist Text, nicht ewig zurückgehalten
    }

    @Test
    fun aTagInTheMiddleOfAnAnswerIsNotABlock() {
        assertEquals("" to "Das Tag <think> ist Text.", split("Das Tag <think> ist Text."))
    }

    @Test
    fun anUnfinishedBlockStaysReasoning() {
        assertEquals("Noch am Denken" to "", split("<think>Noch am Denken"))
    }

    @Test
    fun thinkingIsSwitchedOffOnlyForQwen3ModelsThatCanDoIt() {
        val prompt = "<|im_start|>user\nHi<|im_end|>\n<|im_start|>assistant\n"
        assertEquals(prompt + Thinking.EMPTY_BLOCK, Thinking.prefill(prompt, "Qwen3-1.7B-Q4_K_M.gguf", thinking = false))
        assertEquals(prompt, Thinking.prefill(prompt, "Qwen3-1.7B-Q4_K_M.gguf", thinking = true))
        assertEquals(prompt, Thinking.prefill(prompt, "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", thinking = false))
        assertEquals(prompt, Thinking.prefill(prompt, "Qwen3-4B-Thinking-2507-Q4_K_M.gguf", thinking = false))
        assertEquals(prompt, Thinking.prefill(prompt, "gemma-3n-E2B-it-Q4_K_M.gguf", thinking = false))
        assertEquals("anderer Prompt", Thinking.prefill("anderer Prompt", "Qwen3-1.7B.gguf", thinking = false))
        assertTrue(Thinking.switchable("qwen3-1.7b") && !Thinking.switchable("llama"))
        assertFalse(Thinking.switchable(""))
    }
}
