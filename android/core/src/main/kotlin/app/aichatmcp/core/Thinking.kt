package app.aichatmcp.core

/**
 * Modelle mit "Nachdenken" (Qwen3, DeepSeek-R1 und Ableger) schreiben vor der Antwort einen Block `<think>…</think>`. Der Block gehört nicht zur Antwort
 * und nicht in den Verlauf (er würde sonst bei jeder Anfrage mitgeschickt und das Modell ahmt ihn nach): [ThinkSplitter] trennt ihn vom Antworttext.
 * [Thinking.prefill] schaltet das Nachdenken bei Qwen3 ab, indem der leere Block schon im Prompt steht (das entspricht `enable_thinking=false` der
 * Chat-Vorlage; die C-Schnittstelle von llama.cpp kennt diesen Schalter nicht).
 */
object Thinking {
    private const val ASSISTANT_START = "<|im_start|>assistant\n"
    const val EMPTY_BLOCK = "<think>\n\n</think>\n\n"

    /**
     * Gilt der Modellname für ein Qwen3-Modell, dessen Nachdenken sich abschalten lässt? Die Varianten "Instruct" (2507) denken ohnehin nicht, bei
     * "Thinking" ist es fest eingebaut.
     */
    fun switchable(modelName: String): Boolean {
        val n = modelName.lowercase()
        return "qwen3" in n && listOf("instruct", "thinking", "coder").none { it in n }
    }

    /** Der Prompt mit dem leeren Block am Ende, wenn das Nachdenken aus sein soll und sich abschalten lässt; sonst unverändert. */
    fun prefill(prompt: String, modelName: String, thinking: Boolean): String =
        if (!thinking && switchable(modelName) && prompt.endsWith(ASSISTANT_START)) prompt + EMPTY_BLOCK else prompt
}

/** Teilt einen Antwortstrom in Nachdenken ([Part.reasoning] = true) und Antworttext. Ein Block zählt nur am Anfang der Antwort. */
class ThinkSplitter {
    data class Part(val reasoning: Boolean, val text: String)

    private enum class State { START, THINK, ANSWER }

    private var state = State.START
    private val pending = StringBuilder()
    private var trimAnswer = false

    fun feed(text: String): List<Part> {
        val out = mutableListOf<Part>()
        pending.append(text)
        loop@ while (pending.isNotEmpty()) {
            when (state) {
                State.START -> {
                    val trimmed = pending.toString().trimStart()
                    when {
                        trimmed.isEmpty() -> break@loop // nur Leerraum bisher
                        trimmed.startsWith(OPEN) -> {
                            state = State.THINK
                            val rest = trimmed.removePrefix(OPEN).removePrefix("\n")
                            pending.setLength(0)
                            pending.append(rest)
                        }
                        OPEN.startsWith(trimmed) -> break@loop // der Anfang des Tags, es kommt mehr
                        else -> {
                            state = State.ANSWER
                        }
                    }
                }
                State.THINK -> {
                    val s = pending.toString()
                    val end = s.indexOf(CLOSE)
                    if (end >= 0) {
                        if (end > 0) out += Part(true, s.substring(0, end))
                        state = State.ANSWER
                        trimAnswer = true
                        pending.setLength(0)
                        pending.append(s.substring(end + CLOSE.length))
                    } else {
                        val keep = partialSuffix(s)
                        if (s.length > keep) out += Part(true, s.substring(0, s.length - keep))
                        pending.setLength(0)
                        pending.append(s.takeLast(keep))
                        break@loop
                    }
                }
                State.ANSWER -> {
                    var s = pending.toString()
                    pending.setLength(0)
                    if (trimAnswer) {
                        s = s.trimStart()
                        if (s.isNotEmpty()) trimAnswer = false
                    }
                    if (s.isNotEmpty()) out += Part(false, s)
                }
            }
        }
        return out
    }

    /** Am Ende des Stroms: was noch zurückgehalten wird, ausgeben. */
    fun flush(): List<Part> {
        val s = pending.toString()
        pending.setLength(0)
        if (s.isEmpty()) return emptyList()
        return listOf(Part(state == State.THINK, if (state == State.ANSWER && trimAnswer) s.trimStart() else s)).filter { it.text.isNotEmpty() }
    }

    /** Wie viele Zeichen am Ende könnten der Anfang von "</think>" sein? */
    private fun partialSuffix(s: String): Int {
        for (n in minOf(CLOSE.length - 1, s.length) downTo 1) if (CLOSE.startsWith(s.takeLast(n))) return n
        return 0
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
