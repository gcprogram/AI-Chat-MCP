package app.aichatmcp.core

import kotlinx.coroutines.flow.collect

/** Die Antwort des Modells ist für diese Stufe unbrauchbar (zu kurz, Format nicht erkannt, wiederholt Früheres): gleich noch einmal versuchen. */
class BookFormatException(message: String) : LlmException(message)

/** Das Modell, mit dem eine Stufe läuft: Anbindung, Einstellungen (Temperatur …) und Modellname. */
class StepModel(val backend: LlmBackend, val config: Config, val model: String)

/**
 * Führt die Stufen des Autorenmodus aus: eine Stufe = eine Anfrage an das Modell (Kapitel in Teilen von etwa 550 Wörtern). Das Ergebnis jeder Stufe
 * wird geprüft und ins Projekt übernommen; der Aufrufer speichert das Projekt nach jeder Stufe, damit ein Abbruch nichts kostet.
 *
 * [resolve] wählt das Modell je Stufe (z. B. ein starkes Modell im Netz für die Planung und das Handy-Modell für den Text); ohne sie laufen alle Stufen
 * mit [backend], [config] und [modelName].
 */
class BookWriter(
    private val backend: () -> LlmBackend,
    private val config: () -> Config,
    private val modelName: () -> String,
    private val resolve: ((BookStep) -> StepModel)? = null,
) {
    @Volatile
    private var token = CancelToken()

    /** Bricht die laufende Stufe ab (ihr halbfertiger Text wird verworfen). */
    fun cancel() = token.cancel()

    /**
     * Führt [step] aus. Liefert das neue Projekt, oder null, wenn abgebrochen wurde. Fehler des Modells als [LlmException]. [attempt] zählt die
     * Wiederholungen derselben Stufe (ab dem dritten Versuch wird eine Wiederholung früherer Kapitelpläne nicht mehr beanstandet; die Temperatur steigt
     * leicht, damit das Modell nicht wieder dasselbe schreibt). [onText] bekommt den wachsenden Text der Stufe (zur Anzeige).
     */
    suspend fun run(p: BookProject, step: BookStep, attempt: Int = 0, onText: (String) -> Unit = {}): BookProject? {
        if (step == BookStep.Done) return p
        if (step == BookStep.Plan) return apply(p, step, "") // legt nur die leeren Kapitel an, kein Modell nötig
        val t = CancelToken().also { token = it }
        val messages = when (step) {
            BookStep.Premise -> BookPrompts.premise(p)
            BookStep.Characters -> BookPrompts.characters(p)
            is BookStep.ChapterPlan -> BookPrompts.chapterPlan(p, step.chapter)
            is BookStep.Beats -> BookPrompts.beats(p, step.chapter)
            is BookStep.Part -> BookPrompts.part(p, step)
            is BookStep.Summary -> BookPrompts.summary(p, step.chapter)
            BookStep.Plan, BookStep.Done -> return p
        }
        val model = resolve?.invoke(step) ?: StepModel(backend(), config(), modelName())
        val raw = complete(model, messages, BookPrompts.maxTokens(step, p.brief.chapters), attempt, t, onText) ?: return null
        return apply(p, step, raw, strict = attempt < STRICT_ATTEMPTS)
    }

    private suspend fun complete(model: StepModel, messages: List<WireMessage>, maxTokens: Int, attempt: Int, t: CancelToken, onText: (String) -> Unit): String? {
        val c = model.config
        val splitter = ThinkSplitter()
        val out = StringBuilder()
        fun take(parts: List<ThinkSplitter.Part>) {
            for (part in parts) if (!part.reasoning) out.append(part.text)
            onText(out.toString())
        }
        val temperature = (c.temperature + 0.15 * attempt).coerceAtMost(1.2)
        model.backend.streamChat(model.model, messages, temperature, maxTokens, emptyList(), t, c.samplingParams()).collect { event ->
            if (t.stopped) return@collect
            if (event is StreamEvent.Content) take(splitter.feed(event.text))
        }
        if (t.cancelled) return null
        take(splitter.flush())
        return out.toString().trim()
    }

    companion object {
        private const val STRICT_ATTEMPTS = 2

        /**
         * Übernimmt die Antwort des Modells zu [step] ins Projekt; zu kurze oder unlesbare Antworten sind ein Fehler (der Nutzer kann die Stufe wiederholen).
         * [strict]: auch ein Kapitelplan, der frühere Pläne wiederholt, ist ein Fehler.
         */
        fun apply(p: BookProject, step: BookStep, raw: String, strict: Boolean = false): BookProject {
            val text = raw.trim()
            return when (step) {
                BookStep.Premise -> {
                    val (title, premiseRaw) = BookParse.premise(BookParse.cleanBlock(text))
                    val rest = Documents.completeSentences(premiseRaw) // an der Token-Grenze abgebrochener letzter Satz fällt weg
                    if (rest.length < 40) throw BookFormatException("Die Antwort für die Handlung war zu kurz. Bitte noch einmal versuchen.")
                    if (BookParse.actCount(rest) < 3) throw BookFormatException("Die Handlung war unvollständig (ein Akt fehlt, vermutlich zu früh abgebrochen). Bitte noch einmal versuchen.")
                    p.copy(title = p.title.ifBlank { title }, premise = rest)
                }
                BookStep.Characters -> {
                    val cleaned = BookParse.dropDangling(BookParse.cleanBlock(text))
                    if (cleaned.length < 80) throw BookFormatException("Die Antwort für die Figuren war zu kurz. Bitte noch einmal versuchen.")
                    p.copy(characters = cleaned)
                }
                BookStep.Plan -> {
                    val word = if (p.german) "Kapitel" else "Chapter"
                    p.copy(chapters = List(p.brief.chapters.coerceAtLeast(1)) { i -> BookChapter("$word ${i + 1}", "") })
                }
                is BookStep.ChapterPlan -> {
                    val parsed = BookParse.chapters(text, 1).firstOrNull() ?: throw BookFormatException("Der Kapitelplan war nicht lesbar. Bitte noch einmal versuchen.")
                    if (parsed.plan.length < 40 || parsed.plan == parsed.title) throw BookFormatException("Der Kapitelplan war zu kurz. Bitte noch einmal versuchen.")
                    val others = p.chapters.filterIndexed { i, c -> i != step.chapter && c.plan.isNotBlank() }.map { it.plan }
                    if (strict && BookParse.repeats(parsed.plan, others)) throw BookFormatException("Der Kapitelplan wiederholt frühere Kapitel. Bitte noch einmal versuchen.")
                    p.copy(chapters = p.chapters.mapIndexed { i, c -> if (i == step.chapter) c.copy(title = parsed.title, plan = parsed.plan) else c })
                }
                is BookStep.Beats -> {
                    val beats = BookParse.beats(text)
                    if (BookParse.beatList(beats).size < 2) throw BookFormatException("Die Szenen-Beats waren nicht lesbar (erwartet: Zeilen \"BEAT 1: …\"). Bitte noch einmal versuchen.")
                    p.copy(chapters = p.chapters.mapIndexed { i, c -> if (i == step.chapter) c.copy(beats = beats) else c })
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
            .split("\n").joinToString("\n") { line -> if (line.count { it == '*' } % 2 == 1) line.replace("*", "") else line } // ungepaarte Betonungs-Sterne (abgebrochener Satz) weg
    }
}
