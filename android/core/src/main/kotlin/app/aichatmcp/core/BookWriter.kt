package app.aichatmcp.core

import kotlinx.coroutines.flow.collect

/** Die Antwort des Modells ist für diese Stufe unbrauchbar (zu kurz, Format nicht erkannt): gleich noch einmal versuchen. */
class BookFormatException(message: String) : LlmException(message)

/**
 * Führt die Stufen des Autorenmodus aus: eine Stufe = eine Anfrage an das Modell (Kapitel in Teilen von etwa 550 Wörtern). Das Ergebnis jeder Stufe
 * wird geprüft und ins Projekt übernommen; der Aufrufer speichert das Projekt nach jeder Stufe, damit ein Abbruch nichts kostet.
 */
class BookWriter(
    private val backend: () -> LlmBackend,
    private val config: () -> Config,
    private val modelName: () -> String,
) {
    @Volatile
    private var token = CancelToken()

    /** Bricht die laufende Stufe ab (ihr halbfertiger Text wird verworfen). */
    fun cancel() = token.cancel()

    /**
     * Führt [step] aus. Liefert das neue Projekt, oder null, wenn abgebrochen wurde. Fehler des Modells als [LlmException]. [onText] bekommt den
     * wachsenden Text der Stufe (zur Anzeige).
     */
    suspend fun run(p: BookProject, step: BookStep, onText: (String) -> Unit = {}): BookProject? {
        if (step == BookStep.Done) return p
        val t = CancelToken().also { token = it }
        val messages = when (step) {
            BookStep.Premise -> BookPrompts.premise(p)
            BookStep.Characters -> BookPrompts.characters(p)
            BookStep.Plan -> BookPrompts.plan(p)
            is BookStep.ChapterPlan -> BookPrompts.chapterPlan(p, step.chapter)
            is BookStep.Part -> BookPrompts.part(p, step)
            is BookStep.Summary -> BookPrompts.summary(p, step.chapter)
            BookStep.Done -> return p
        }
        val raw = complete(messages, BookPrompts.maxTokens(step), t, onText) ?: return null
        return apply(p, step, raw)
    }

    private suspend fun complete(messages: List<WireMessage>, maxTokens: Int, t: CancelToken, onText: (String) -> Unit): String? {
        val c = config()
        val splitter = ThinkSplitter()
        val out = StringBuilder()
        fun take(parts: List<ThinkSplitter.Part>) {
            for (part in parts) if (!part.reasoning) out.append(part.text)
            onText(out.toString())
        }
        backend().streamChat(modelName(), messages, c.temperature, maxTokens, emptyList(), t, c.samplingParams()).collect { event ->
            if (t.stopped) return@collect
            if (event is StreamEvent.Content) take(splitter.feed(event.text))
        }
        if (t.cancelled) return null
        take(splitter.flush())
        return out.toString().trim()
    }

    companion object {
        /** Übernimmt die Antwort des Modells zu [step] ins Projekt; zu kurze oder unlesbare Antworten sind ein Fehler (der Nutzer kann die Stufe wiederholen). */
        fun apply(p: BookProject, step: BookStep, raw: String): BookProject {
            val text = raw.trim()
            return when (step) {
                BookStep.Premise -> {
                    val (title, rest) = BookParse.premise(BookParse.cleanBlock(text))
                    if (rest.length < 40) throw BookFormatException("Die Antwort für die Handlung war zu kurz. Bitte noch einmal versuchen.")
                    p.copy(title = p.title.ifBlank { title }, premise = rest)
                }
                BookStep.Characters -> {
                    val cleaned = BookParse.cleanBlock(text)
                    if (cleaned.length < 80) throw BookFormatException("Die Antwort für die Figuren war zu kurz. Bitte noch einmal versuchen.")
                    p.copy(characters = cleaned)
                }
                BookStep.Plan -> {
                    val chapters = BookParse.chapters(text, p.brief.chapters)
                    if (chapters.isEmpty()) throw BookFormatException("Der Kapitelplan war nicht lesbar (erwartet: Zeilen \"KAPITEL 1: Titel | Inhalt\"). Bitte noch einmal versuchen.")
                    // Hat das Modell nicht alle Kapitel geliefert, bleiben leere Pläne; sie werden einzeln nachgeholt (BookStep.ChapterPlan)
                    val padded = chapters + List((p.brief.chapters - chapters.size).coerceAtLeast(0)) { i -> BookChapter("${if (p.german) "Kapitel" else "Chapter"} ${chapters.size + i + 1}", "") }
                    p.copy(chapters = padded)
                }
                is BookStep.ChapterPlan -> {
                    val parsed = BookParse.chapters(text, 1).firstOrNull() ?: throw BookFormatException("Der Kapitelplan war nicht lesbar. Bitte noch einmal versuchen.")
                    p.copy(chapters = p.chapters.mapIndexed { i, c -> if (i == step.chapter) c.copy(title = parsed.title, plan = parsed.plan) else c })
                }
                is BookStep.Part -> {
                    val part = Documents.completeSentences(cleanProse(text))
                    if (part.length < 80) throw BookFormatException("Der Text war zu kurz. Bitte noch einmal versuchen.")
                    val c = p.chapters[step.chapter]
                    val joined = if (c.text.isBlank()) part else c.text.trimEnd() + "\n\n" + part
                    p.copy(chapters = p.chapters.mapIndexed { i, ch -> if (i == step.chapter) ch.copy(text = joined, parts = step.part) else ch })
                }
                is BookStep.Summary -> {
                    val (summary, facts) = BookParse.summary(text)
                    val c = p.chapters[step.chapter]
                    val s = summary.ifBlank { c.text.take(300) }.replace(Regex("\\s*\\n\\s*"), " ").trim()
                    val newFacts = (p.facts + "\n" + facts).trim().let { if (it.length > MAX_FACTS) "…" + it.takeLast(MAX_FACTS) else it }
                    p.copy(chapters = p.chapters.mapIndexed { i, ch -> if (i == step.chapter) ch.copy(summary = s) else ch }, facts = newFacts)
                }
                BookStep.Done -> p
            }
        }

        private const val MAX_FACTS = 1800

        /** Entfernt, was das Modell trotz Anweisung dazuschreibt: Überschriften, Markdown-Betonung, "Teil 2:"-Zeilen. */
        fun cleanProse(text: String): String = text.lines()
            .filterNot { it.trimStart().startsWith("#") || Regex("""(?i)^\s*\**\s*(teil|part|kapitel|chapter)\s+\d+.{0,40}$""").matches(it) }
            .joinToString("\n").replace("**", "").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
