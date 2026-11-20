package app.aichatmcp.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Stufen der Zusammenfassung eines Buches. */
enum class SummaryLevel {
    /** 6 Sätze über das ganze Buch */
    SHORT,

    /** ausführlich: etwa 600 bis 800 Wörter (ein bis zwei Seiten) */
    DETAILED,

    /** je Kapitel eine Zusammenfassung mit den wesentlichen Elementen */
    CHAPTERS,
}

/** Ein Stück des Buches, das in eine Anfrage passt, mit dem Kapitel, zu dem es gehört. */
data class BookPart(val chapter: String, val text: String)

/**
 * Bisherige Ergebnisse zu einem Buch (zwischengespeichert, damit ein Abbruch nichts kostet und eine zweite Stufe die Teile wiederverwendet):
 * [parts] = Zusammenfassungen der Buchteile nach Nummer, [chapters] = Zusammenfassungen der Kapitel nach Titel.
 */
@Serializable
data class SummaryData(
    val key: String = "",
    val parts: Map<Int, String> = emptyMap(),
    val chapters: Map<String, String> = emptyMap(),
    val short: String = "",
    val detailed: String = "",
    val perChapter: String = "",
)

/** Das Modell hat nicht geantwortet oder die Antwort war leer. */
class SummaryException(message: String) : Exception(message)

/**
 * Zusammenfassen von Büchern in Stufen für Modelle mit kleinem Kontext (Map-Reduce): Das Buch wird in Teile zerlegt, jeder Teil zusammengefasst, die Teile
 * eines Kapitels zur Kapitelzusammenfassung vereint; daraus entstehen die 6 Sätze, die ausführliche Zusammenfassung und die Liste der Kapitel. Passen
 * die Kapitelzusammenfassungen nicht in eine Anfrage, werden sie zuerst gruppenweise weiter verdichtet.
 */
object Summaries {
    const val PART_CHARS = 3500
    const val PART_MAX_CHARS = 4800

    /** Zerlegt das Buch in Teile; die Kapitel kommen von den Überschriften ([BookScenes]), ohne Überschriften ist jeder Teil ein "Abschnitt n". */
    fun parts(text: String): List<BookPart> =
        BookScenes.split(text, PART_CHARS, PART_MAX_CHARS).map { BookPart(chapterOf(it.title), it.text) }

    /** "Kapitel 2 (1/3)" gehört zu "Kapitel 2". */
    fun chapterOf(title: String): String = title.replace(Regex("""\s*\(\d+/\d+\)\s*$"""), "").trim()

    /** Ein Schlüssel für die Zwischenspeicherung: Name, Länge und Anfang des Textes. */
    fun key(name: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$name|${text.length}|${text.take(300)}".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Die Kapitel in der Reihenfolge des Buches. */
    fun chapters(parts: List<BookPart>): List<String> = parts.map { it.chapter }.distinct()

    /** Wie viele Anfragen die Stufe höchstens braucht (ohne schon Zwischengespeichertes abzuziehen: [data] zählt das ab). */
    fun requests(parts: List<BookPart>, level: SummaryLevel, data: SummaryData = SummaryData()): Int {
        val missingParts = parts.indices.count { data.parts[it].isNullOrBlank() }
        val merges = chapters(parts).count { ch -> ch !in data.chapters && parts.count { it.chapter == ch } > 1 }
        val final = when (level) {
            SummaryLevel.CHAPTERS -> 0
            SummaryLevel.SHORT -> if (data.short.isBlank()) 1 else 0
            SummaryLevel.DETAILED -> if (data.detailed.isBlank()) 1 else 0
        }
        return missingParts + merges + final
    }

    fun partPrompt(part: BookPart, language: String): List<WireMessage> = listOf(
        WireMessage(
            "user",
            "Summarize the following part of a book in 3 to 5 sentences: who acts, what happens, important places and turning points. Use only this text and invent nothing. " +
                "Answer in $language, as plain sentences, without lists and without an introduction.\n\nCHAPTER: ${part.chapter}\n\n${part.text.trim()}",
        ),
    )

    fun mergePrompt(title: String, pieces: List<String>, language: String): List<WireMessage> = listOf(
        WireMessage(
            "user",
            "Below are summaries of consecutive parts of ${if (title.isBlank()) "a book" else "\"$title\""}. Merge them into one summary of 4 to 6 sentences that keeps the essential events, people and turning points in order. " +
                "Answer in $language, as plain sentences, without lists and without an introduction.\n\n" + pieces.mapIndexed { i, p -> "${i + 1}. ${p.trim()}" }.joinToString("\n\n"),
        ),
    )

    fun shortPrompt(chapterSummaries: String, language: String): List<WireMessage> = listOf(
        WireMessage(
            "user",
            "Below are summaries of the chapters of a book, in order. Write a summary of the whole book in exactly 6 sentences: setting and main characters, the central conflict, the main turning points and the ending. " +
                "Answer in $language, as plain sentences, without lists and without an introduction.\n\n$chapterSummaries",
        ),
    )

    fun detailedPrompt(chapterSummaries: String, language: String): List<WireMessage> = listOf(
        WireMessage(
            "user",
            "Below are summaries of the chapters of a book, in order. Write a detailed summary of the whole book of about 600 to 800 words (one to two pages) in several paragraphs: the beginning, the development with the main turning points, the climax and the ending. " +
                "Name the main characters and important places. Use only what the summaries say. Answer in $language, without lists, without headings and without an introduction.\n\n$chapterSummaries",
        ),
    )

    /** Höchstens [max] Sätze (das Modell hält "genau 6 Sätze" nicht immer ein). */
    fun limitSentences(text: String, max: Int): String {
        val sentences = text.trim().split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
        return if (sentences.size <= max) text.trim() else sentences.take(max).joinToString(" ")
    }

    /** Was das Modell trotz Anweisung davorsetzt ("Hier ist die Zusammenfassung:") und Markdown-Zeichen weg. */
    fun clean(answer: String): String {
        val lines = answer.replace("**", "").lines().toMutableList()
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        if (lines.isNotEmpty() && Regex("""(?i)^(absolut|gerne|klar|natürlich|sicher|hier (sind|ist|kommt)|sure|certainly|here (are|is)|okay|summary)\b.{0,120}:\s*$""").matches(lines.first().trim())) lines.removeAt(0)
        return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /**
     * Führt die Stufe [level] aus. [ask] stellt eine Anfrage (Antwort oder null bei Fehler), [budgetChars] ist die Eingabelänge, die in eine Anfrage passt,
     * [onData] bekommt nach jeder Anfrage den Stand zum Speichern, [onProgress] die Zahl der erledigten und aller nötigen Anfragen. Abbruch über die
     * Coroutine; schon Erledigtes bleibt in den Daten.
     */
    suspend fun run(
        parts: List<BookPart>, level: SummaryLevel, language: String, start: SummaryData, budgetChars: Int,
        ask: suspend (List<WireMessage>, Int) -> String?, onProgress: (done: Int, total: Int, what: String) -> Unit = { _, _, _ -> }, onData: (SummaryData) -> Unit = {},
    ): SummaryData {
        var data = start
        val total = requests(parts, level, start).coerceAtLeast(1)
        var done = 0
        suspend fun call(prompt: List<WireMessage>, maxTokens: Int, what: String): String {
            onProgress(done.coerceAtMost(total), total, what)
            val answer = ask(prompt, maxTokens)?.let(::clean).orEmpty()
            if (answer.isBlank()) {
                currentCoroutineContext().ensureActive() // ein Abbruch ist keine Fehlermeldung des Modells
                throw SummaryException("Das Modell hat nicht geantwortet.")
            }
            done++
            onProgress(done.coerceAtMost(total), total, what)
            return answer
        }
        // 1. Zusammenfassung jedes Buchteils
        for ((i, part) in parts.withIndex()) {
            if (!data.parts[i].isNullOrBlank()) continue
            val answer = call(partPrompt(part, language), 250, part.chapter)
            data = data.copy(parts = data.parts + (i to answer))
            onData(data)
        }
        // 2. Zusammenfassung jedes Kapitels (mehrere Teile werden vereint)
        val order = chapters(parts)
        for (chapter in order) {
            if (!data.chapters[chapter].isNullOrBlank()) continue
            val pieces = parts.indices.filter { parts[it].chapter == chapter }.map { data.parts.getValue(it) }
            val summary = if (pieces.size == 1) pieces[0] else call(mergePrompt(chapter, pieces, language), 350, chapter)
            data = data.copy(chapters = data.chapters + (chapter to summary))
            onData(data)
        }
        val chapterSummaries = order.map { it to data.chapters.getValue(it) }
        // 3. die gewünschte Stufe
        data = when (level) {
            SummaryLevel.CHAPTERS -> data.copy(perChapter = chapterSummaries.joinToString("\n\n") { (title, summary) -> "$title\n$summary" })
            SummaryLevel.SHORT -> if (data.short.isNotBlank()) data else {
                val input = condense(chapterSummaries.map { it.second }, budgetChars, language) { prompt, max, what -> call(prompt, max, what) }
                data.copy(short = limitSentences(call(shortPrompt(input, language), 300, ""), 6))
            }
            SummaryLevel.DETAILED -> if (data.detailed.isNotBlank()) data else {
                val input = condense(chapterSummaries.map { it.second }, budgetChars, language) { prompt, max, what -> call(prompt, max, what) }
                data.copy(detailed = call(detailedPrompt(input, language), 1200, ""))
            }
        }
        onData(data)
        return data
    }

    /** Passen die Kapitelzusammenfassungen nicht in eine Anfrage, werden aufeinanderfolgende gruppenweise weiter verdichtet, bis sie passen. */
    private suspend fun condense(
        inputs: List<String>, budgetChars: Int, language: String, ask: suspend (List<WireMessage>, Int, String) -> String,
    ): String {
        var items = inputs
        var rounds = 0
        while (items.sumOf { it.length + 4 } > budgetChars && items.size > 1 && rounds < 6) {
            val groups = mutableListOf<MutableList<String>>()
            for (item in items) {
                val last = groups.lastOrNull()
                if (last != null && last.sumOf { it.length + 4 } + item.length <= budgetChars / 2) last += item else groups += mutableListOf(item)
            }
            if (groups.size >= items.size) break // jede Zusammenfassung allein ist schon über der Hälfte: mehr Verdichtung geht so nicht
            items = groups.map { g -> if (g.size == 1) g[0] else ask(mergePrompt("", g, language), 400, "") }
            rounds++
        }
        return items.joinToString("\n\n") { it.trim() }
    }
}

/** Bildbeschreibungen beim Vorlesen: Einleitungssatz und Übersetzung in die Sprache des Buches (das Bildmodell beschreibt englisch). */
object BookPicture {
    private val INTROS = mapOf(
        "de" to "An dieser Stelle gibt es ein Bild, welches Folgendes zeigt:",
        "en" to "At this point there is a picture showing the following:",
        "fr" to "À cet endroit, il y a une image qui montre ceci :",
        "es" to "En este punto hay una imagen que muestra lo siguiente:",
        "it" to "A questo punto c'è un'immagine che mostra quanto segue:",
        "pt" to "Neste ponto há uma imagem que mostra o seguinte:",
        "nl" to "Op deze plek is een afbeelding die het volgende laat zien:",
        "ja" to "ここには、次のことを示す画像があります。",
    )

    /** Der Einleitungssatz in der Sprache [tag] ("de-DE"); unbekannt = Englisch. */
    fun intro(tag: String?): String = INTROS[tag.orEmpty().substringBefore('-').substringBefore('_').lowercase()] ?: INTROS.getValue("en")

    /** Die Anfrage, die eine englische Bildbeschreibung in die Sprache [language] (Name, z. B. "Deutsch") übersetzt. */
    fun translatePrompt(text: String, language: String): List<WireMessage> = listOf(
        WireMessage(
            "user",
            "Translate the following description of a picture into $language. Keep the meaning and the sentence structure, add nothing and leave nothing out. " +
                "Answer with the translation only, without quotation marks and without an introduction.\n\n${text.trim()}",
        ),
    )
}

/** Der Satz, den der Vorleser am Ende eines Buches in der Sprache des Buches spricht. */
object BookEnd {
    private val PHRASES = mapOf(
        "de" to "Ende des Buches.", "en" to "End of the book.", "fr" to "Fin du livre.", "es" to "Fin del libro.", "it" to "Fine del libro.",
        "pt" to "Fim do livro.", "nl" to "Einde van het boek.", "ja" to "本はここで終わりです。", "pl" to "Koniec książki.", "tr" to "Kitabın sonu.",
    )

    /** [tag]: Sprachkennung des Buches ("de-DE"); unbekannt oder leer = Englisch. */
    fun phrase(tag: String?): String = PHRASES[tag.orEmpty().substringBefore('-').substringBefore('_').lowercase()] ?: PHRASES.getValue("en")
}
