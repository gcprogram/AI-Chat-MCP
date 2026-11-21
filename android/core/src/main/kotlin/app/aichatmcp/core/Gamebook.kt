package app.aichatmcp.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Das Spielbuch: Ein Buch wird vor dem Spielen einmal vorbereitet und im Zwischenspeicher ([SummaryData], `summaries/<Schlüssel>.json`) abgelegt:
 * die Zusammenfassung jeder Szene ([SummaryData.parts], dieselben wie beim Vorleser) und der **Handlungsstrang** jeder Szene ([SummaryData.threads]).
 * Das Spiel liest nur noch diese kurzen Angaben: Der Spielleiter erzählt aus der Zusammenfassung, und die Stränge bieten je Strang die nächste noch
 * ungespielte Szene an (innerhalb eines Strangs bleibt die Reihenfolge des Buches, zwischen Strängen darf der Spieler wechseln) - das verhindert
 * "Seltsamkeiten", wenn eine Szene vor der gespielt wird, auf der sie aufbaut.
 */
object Gamebooks {
    /** Wie viele Szenen eine Anfrage nach den Strängen umfasst. */
    const val BATCH = 10
    private const val PLOT_CHARS = 300

    fun batches(count: Int): Int = (count + BATCH - 1) / BATCH

    private fun batchDone(b: Int, count: Int, data: SummaryData): Boolean = (b * BATCH until minOf(count, (b + 1) * BATCH)).all { it in data.threads }

    /** Wie viele Anfragen noch fehlen (Zusammenfassungen der Szenen und Gruppen von Szenen ohne Strang). */
    fun requests(count: Int, data: SummaryData): Int =
        (0 until count).count { data.parts[it].isNullOrBlank() } + (0 until batches(count)).count { !batchDone(it, count, data) }

    fun ready(count: Int, data: SummaryData): Boolean = count > 0 && requests(count, data) == 0

    /** Wie viele verschiedene Handlungsstränge gefunden wurden. */
    fun threadCount(data: SummaryData): Int = data.threads.values.map { it.lowercase() }.distinct().size

    /** Die Anfrage: Welchen Handlungsstrang gehört jede dieser Szenen ([items] = Nummer ab 0, Titel, Zusammenfassung) an? [known] = Stränge bisher. */
    fun threadPrompt(items: List<Triple<Int, String, String>>, known: List<String>, language: String): List<WireMessage> {
        val list = items.joinToString("\n") { (i, title, plot) -> "${i + 1}. $title: ${plot.trim().take(PLOT_CHARS)}" }
        val knownLine = if (known.isEmpty()) "There are no storylines yet." else "Storylines so far: ${known.joinToString("; ")}."
        return listOf(
            WireMessage(
                "user",
                "Below are consecutive scenes of a book (number, title, summary). Assign each scene to the storyline it belongs to. A storyline is a sequence of scenes that follows the same main character " +
                    "or group, or the same plot thread; scenes that tell different things in parallel or independent episodes belong to different storylines. $knownLine " +
                    "Reuse a known storyline whenever the scene continues it; add a new one (1 to 3 words, in $language) only for a really separate storyline. A book with only one storyline uses one name for all scenes.\n" +
                    "Answer with exactly one line per scene in this form and nothing else:\n<number>: <storyline>\n\n$list",
            ),
        )
    }

    private val LINE = Regex("""(?m)^\s*[*#_\s\-]*(\d{1,5})\s*[:.)\-–]\s*[*_]*\s*(.+?)\s*$""")

    /**
     * Liest die Antwort: Zeile "n: Strang" für die Szenen [numbers] (Nummern ab 0, die Zeilen zählen ab 1). Gleiche Namen (ohne Rücksicht auf Groß-/Kleinschreibung)
     * werden zusammengelegt, bekannte Namen ([known]) haben Vorrang. Szenen ohne Zeile übernehmen den Strang der vorigen, die erste im Zweifel den zuletzt bekannten
     * oder [fallback].
     */
    fun parseThreads(answer: String, numbers: List<Int>, known: List<String>, fallback: String): Map<Int, String> {
        val names = known.toMutableList()
        val found = HashMap<Int, String>()
        for (m in LINE.findAll(answer)) {
            val index = m.groupValues[1].toInt() - 1
            if (index !in numbers || index in found) continue
            val raw = m.groupValues[2].replace("**", "").trim().trim('*', '_', '"', '„', '“', '»', '«', ' ', '.').take(40)
            if (raw.isBlank()) continue
            val name = names.firstOrNull { it.equals(raw, ignoreCase = true) } ?: raw.also { names += it }
            found[index] = name
        }
        val out = LinkedHashMap<Int, String>()
        var last = fallback
        for (i in numbers) {
            last = found[i] ?: last
            out[i] = last
        }
        return out
    }

    /** Die Handlungsstränge in der Reihenfolge ihres ersten Auftretens (für die Anzeige). */
    fun threadNames(data: SummaryData): List<String> = data.threads.toSortedMap().values.distinctBy { it.lowercase() }

    /**
     * Bereitet das Spielbuch vor: Zusammenfassung jeder fehlenden Szene, danach die Stränge je [BATCH] Szenen. Nach jeder Anfrage bekommt [onData] den Stand zum Speichern
     * (Abbruch oder Fehler kosten nichts, ein neuer Lauf macht dort weiter); [onProgress] die Zahl der erledigten und aller nötigen Anfragen.
     */
    suspend fun prepare(
        scenes: List<Scene>, language: String, start: SummaryData,
        ask: suspend (List<WireMessage>, Int) -> String?,
        onProgress: (done: Int, total: Int, what: String) -> Unit = { _, _, _ -> }, onData: (SummaryData) -> Unit = {},
    ): SummaryData {
        var data = start
        val total = requests(scenes.size, start).coerceAtLeast(1)
        var done = 0
        suspend fun call(prompt: List<WireMessage>, maxTokens: Int, what: String): String {
            onProgress(done.coerceAtMost(total), total, what)
            val answer = ask(prompt, maxTokens)?.let(Summaries::clean).orEmpty()
            if (answer.isBlank()) {
                currentCoroutineContext().ensureActive() // ein Abbruch ist keine Fehlermeldung des Modells
                throw SummaryException("Das Modell hat nicht geantwortet.")
            }
            done++
            onProgress(done.coerceAtMost(total), total, what)
            return answer
        }
        for ((i, scene) in scenes.withIndex()) {
            if (!data.parts[i].isNullOrBlank()) continue
            val answer = call(Summaries.partPrompt(BookPart(scene.title, scene.text), language), 250, scene.title)
            data = data.copy(parts = data.parts + (i to answer))
            onData(data)
        }
        for (b in 0 until batches(scenes.size)) {
            if (batchDone(b, scenes.size, data)) continue
            val numbers = (b * BATCH until minOf(scenes.size, (b + 1) * BATCH)).toList()
            val known = threadNames(data)
            val items = numbers.map { Triple(it, scenes[it].title, data.parts.getValue(it)) }
            val answer = call(threadPrompt(items, known, language), 200, scenes[numbers.first()].title)
            val fallback = data.threads[numbers.first() - 1] ?: if (language.startsWith("Deu")) "Haupthandlung" else "Main story"
            data = data.copy(threads = data.threads + parseThreads(answer, numbers, known, fallback))
            onData(data)
        }
        onData(data)
        return data
    }
}
