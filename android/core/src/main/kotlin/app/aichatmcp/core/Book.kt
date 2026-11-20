package app.aichatmcp.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Autorenmodus: Aus Thema und Rahmenbedingungen entsteht ein Buch in Stufen (Prämisse und Handlung, Dramatis Personae, Kapitelplan, Kapitel).
 * Ein Projekt ist eine JSON-Datei; jede Stufe ist einzeln wiederholbar und vom Nutzer bearbeitbar, der Ablauf lässt sich anhalten und
 * fortsetzen (auch mitten in einem Kapitel).
 */
@Serializable
data class BookBrief(
    val topic: String = "",
    val genre: String = "",
    val chapters: Int = 8,
    @SerialName("words_per_chapter") val wordsPerChapter: Int = 1000,
    /** z. B. "Ich-Erzähler, Vergangenheit" oder "dritte Person, Vergangenheit". Leer = dritte Person, Vergangenheit. */
    val perspective: String = "",
    val style: String = "",
    val language: String = "Deutsch",
    /** Rahmenbedingungen: Orte, Zeit, Figuren, Tabus. */
    val constraints: String = "",
)

@Serializable
data class BookChapter(
    val title: String,
    val plan: String,
    val text: String = "",
    /** Wie viele Teile des Kapitels schon geschrieben sind (ein Teil = eine Anfrage an das Modell). */
    val parts: Int = 0,
    val summary: String = "",
    /** Szenen-Beats des Kapitels: 3 bis 9 Zeilen "1. Ort; wer; was geschieht; Ergebnis". Jeder Teil des Kapitels schreibt nur seine Beats aus. */
    val beats: String = "",
)

@Serializable
data class BookProject(
    val id: String,
    val title: String = "",
    val brief: BookBrief = BookBrief(),
    val premise: String = "",
    val characters: String = "",
    val chapters: List<BookChapter> = emptyList(),
    /** Feste Fakten aus den fertigen Kapiteln (Namen, Orte, Ereignisse), damit nichts widerspricht. */
    val facts: String = "",
    val created: Long = 0L,
) {
    val german: Boolean get() = brief.language.trim().lowercase() in setOf("deutsch", "german", "de", "de-de", "deutsch (de)")

    /** Teile je Kapitel: ein Teil sind etwa [PART_WORDS] Wörter. */
    val partsPerChapter: Int get() = ((brief.wordsPerChapter + PART_WORDS - 1) / PART_WORDS).coerceIn(1, 8)

    val displayTitle: String get() = title.ifBlank { brief.topic.take(60) }.ifBlank { "Buch" }

    /** Was als Nächstes zu tun ist. */
    fun nextStep(): BookStep = when {
        premise.isBlank() -> BookStep.Premise
        characters.isBlank() -> BookStep.Characters
        chapters.isEmpty() -> BookStep.Plan
        chapters.any { it.plan.isBlank() } -> BookStep.ChapterPlan(chapters.indexOfFirst { it.plan.isBlank() })
        chapters.any { it.beats.isBlank() } -> BookStep.Beats(chapters.indexOfFirst { it.beats.isBlank() })
        else -> {
            val i = chapters.indexOfFirst { it.summary.isBlank() }
            when {
                i < 0 -> BookStep.Done
                chapters[i].parts < partsPerChapter -> BookStep.Part(i, chapters[i].parts + 1, partsPerChapter)
                else -> BookStep.Summary(i)
            }
        }
    }

    /** Handlung, Figuren, Kapitelpläne und Beats liegen vor: ab hier schreibt das Modell nur noch Text. */
    val planReady: Boolean get() = nextStep().let { it is BookStep.Part || it is BookStep.Summary || it == BookStep.Done }

    /** Wie viele Schritte es insgesamt gibt und wie viele erledigt sind (für die Fortschrittsanzeige). */
    fun progress(): Pair<Int, Int> {
        val n = chapters.size.takeIf { it > 0 } ?: brief.chapters
        val total = 2 + n * (partsPerChapter + 3)
        val done = (if (premise.isNotBlank()) 1 else 0) + (if (characters.isNotBlank()) 1 else 0) +
            chapters.sumOf { (if (it.plan.isNotBlank()) 1 else 0) + (if (it.beats.isNotBlank()) 1 else 0) + it.parts.coerceAtMost(partsPerChapter) + (if (it.summary.isNotBlank()) 1 else 0) }
        return done to total
    }

    /** Zu welchem Akt (1 bis 3) das Kapitel [chapter] von [count] gehört: das erste Viertel Akt 1, die Mitte Akt 2, das letzte Viertel Akt 3. */
    fun actOf(chapter: Int, count: Int): Int {
        val pos = (chapter + 0.5) / count.coerceAtLeast(1)
        return if (pos < 0.25) 1 else if (pos < 0.75) 2 else 3
    }

    val finished: Boolean get() = nextStep() == BookStep.Done

    companion object {
        const val PART_WORDS = 550
    }
}

sealed interface BookStep {
    data object Premise : BookStep
    data object Characters : BookStep
    /** Legt die leeren Kapitel an (ohne Modell); die Pläne kommen danach Kapitel für Kapitel ([ChapterPlan]). */
    data object Plan : BookStep

    /** Der Plan eines einzelnen Kapitels, geschrieben in Kenntnis aller schon geplanten (damit sich nichts wiederholt). */
    data class ChapterPlan(val chapter: Int) : BookStep

    /** Die Szenen-Beats eines Kapitels (aus seinem Plan und den Nachbarkapiteln). */
    data class Beats(val chapter: Int) : BookStep
    data class Part(val chapter: Int, val part: Int, val parts: Int) : BookStep
    data class Summary(val chapter: Int) : BookStep
    data object Done : BookStep
}

/** Projekte als JSON-Dateien in einem Ordner (`<id>.json`). */
class BookStore(private val dir: File) {
    fun list(): List<BookProject> =
        dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().mapNotNull { load(it.nameWithoutExtension) }.sortedByDescending { it.created }

    fun load(id: String): BookProject? =
        runCatching { AppJson.decodeFromString(BookProject.serializer(), File(dir, File(id).name + ".json").readText(Charsets.UTF_8)) }.getOrNull()

    fun save(p: BookProject) {
        dir.mkdirs()
        val target = File(dir, File(p.id).name + ".json")
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(BookProject.serializer(), p), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun delete(id: String) {
        File(dir, File(id).name + ".json").delete()
    }

    fun newId(topic: String): String {
        val base = Persona.slugify(topic.take(30)).ifEmpty { "buch" }
        var id = base
        var n = 2
        while (File(dir, "$id.json").exists()) id = "$base-${n++}"
        return id
    }
}

/** Liest die Antworten des Modells in Bausteine. */
object BookParse {
    private val HEADER = Regex("""(?im)^\s*[#*_\s]*(?:kapitel|chapter)\s*(\d+)\s*[*_]*\s*[:.\-–—)]\s*(.*)$""")

    /** Der Kapitelplan: "KAPITEL 1: Titel | Inhalt" (der Inhalt darf in den folgenden Zeilen weitergehen). Höchstens [expected] Kapitel; leer, wenn nichts erkannt wurde. */
    fun chapters(text: String, expected: Int): List<BookChapter> {
        val result = mutableListOf<BookChapter>()
        var title: String? = null
        val body = StringBuilder()
        fun flush() {
            val first = title ?: return
            // "Titel | Inhalt" in einer Zeile; sonst ist die Kopfzeile der Titel und der folgende Text der Inhalt
            val (t, rawPlan) = if ('|' in first) first.split('|', limit = 2).let { it[0] to (it[1] + "\n" + body) } else first to body.toString()
            val clean = t.replace(Regex("[*_#]"), "").trim().ifEmpty { "Kapitel ${result.size + 1}" }
            val plan = rawPlan.replace(Regex("[*_#]"), "").replace(Regex("""\s*\n\s*"""), " ").trim().let { Documents.completeSentences(cutAtSentence(it, MAX_PLAN_CHARS)) }
            result += BookChapter(clean, plan.ifEmpty { clean })
            title = null
            body.setLength(0)
        }
        for (line in text.lines()) {
            val m = HEADER.find(line)
            if (m != null) {
                flush()
                title = m.groupValues[2].trim()
            } else if (title != null) {
                body.append(line).append('\n')
            }
        }
        flush()
        return result.take(expected.coerceAtLeast(1))
    }

    private const val MAX_PLAN_CHARS = 900

    /** Der Plan besteht nur aus dem Titel oder ist ein Bruchstück (an der Token-Grenze abgebrochen). */
    fun planTooShort(c: BookChapter): Boolean = c.plan.length < 60 || c.plan == c.title

    /** Entfernt am Ende eines Textblocks eine Feldzeile ohne Inhalt ("Beziehungen:") und einen abgebrochenen Satz. */
    fun dropDangling(text: String): String {
        val lines = text.lines().toMutableList()
        while (lines.isNotEmpty() && (lines.last().isBlank() || lines.last().trimEnd().endsWith(":"))) lines.removeAt(lines.lastIndex)
        return Documents.completeSentences(lines.joinToString("\n"))
    }

    /** Kürzt auf höchstens [max] Zeichen am Ende eines Satzes (Modelle schreiben statt eines Plans manchmal das ganze Kapitel). */
    fun cutAtSentence(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end > max / 2) cut.take(end + 1) else cut.trimEnd() + " …"
    }

    /** Nimmt Markdown und ein einleitendes Geplauder ("Absolut! Hier sind …:") aus einem Textblock. */
    fun cleanBlock(text: String): String {
        val lines = text.replace("**", "").lines().map { it.trimEnd().removePrefix("#").removePrefix("#").removePrefix("#").trimStart() }.toMutableList()
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        if (lines.isNotEmpty() && Regex("""(?i)^(absolut|gerne|klar|natürlich|sicher|hier (sind|ist|kommt)|sure|certainly|here (are|is)|okay)\b.{0,160}[:.!]$""").matches(lines.first())) lines.removeAt(0)
        return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private val ACT_HEADER = Regex("""(?im)^\s*[#*_\s]*(?:akt|act)\s*(1|2|3|iii|ii|i)\s*[*_]*\s*[:.\-–—]""")

    private fun actNumber(label: String): Int = when (label.lowercase()) { "1", "i" -> 1; "2", "ii" -> 2; else -> 3 }

    /** Wie viele der drei Akte ("AKT 1:" … "AKT 3:") die Handlung enthält (fehlt einer, wurde die Antwort an der Token-Grenze abgeschnitten). */
    fun actCount(premise: String): Int = ACT_HEADER.findAll(premise).map { actNumber(it.groupValues[1]) }.toSet().size

    /** Der Text des Aktes [n] (1 bis 3) aus der Handlung, leer wenn er fehlt. */
    fun act(premise: String, n: Int): String {
        val marks = ACT_HEADER.findAll(premise).toList()
        val i = marks.indexOfFirst { actNumber(it.groupValues[1]) == n }
        if (i < 0) return ""
        val end = if (i + 1 < marks.size) marks[i + 1].range.first else premise.length
        return premise.substring(marks[i].range.last + 1, end).trim()
    }

    private val BEAT_LINE = Regex("""(?im)^\s*[#*_\s\-]*(?:beat|szene|scene)?\s*(\d{1,2})\s*[*_]*\s*[:.)\-–—]\s*(.+)$""")

    /** Die Beats aus der Antwort ("BEAT 1: …" oder "1. …"), höchstens [max], als nummerierte Zeilen; leer, wenn nichts erkannt wurde. */
    fun beats(text: String, max: Int = 9): String {
        val found = BEAT_LINE.findAll(text).map { it.groupValues[2].replace(Regex("[*_#]"), "").trim() }.filter { it.length >= 10 }.toList()
        return found.take(max).mapIndexed { i, b -> "${i + 1}. ${cutAtSentence(b, 320)}" }.joinToString("\n")
    }

    /** Die Beats eines Kapitels als Liste ohne Nummer. */
    fun beatList(beats: String): List<String> = beats.lines().map { it.trim().replace(Regex("""^\d{1,2}\s*[.:)]\s*"""), "") }.filter { it.isNotEmpty() }

    /** Die Beats für Teil [part] von [parts]: gleichmäßig verteilt (der letzte Teil bekommt den Rest). Leer, wenn es keine Beats gibt. */
    fun beatsForPart(beats: String, part: Int, parts: Int): List<String> {
        val all = beatList(beats)
        if (all.isEmpty()) return emptyList()
        val size = (all.size + parts - 1) / parts.coerceAtLeast(1)
        return all.drop((part - 1) * size).take(size)
    }

    private fun words(s: String): List<String> = Regex("""[\p{L}\p{N}']+""").findAll(s.lowercase()).map { it.value }.toList()

    private fun grams(w: List<String>, n: Int = 5): Set<List<String>> = if (w.size < n) emptySet() else (0..w.size - n).map { w.subList(it, it + n) }.toSet()

    /** Wiederholt [plan] mindestens zu 30 % (Folgen von fünf Wörtern) einen der [others]? Kleine Modelle kopieren sonst frühere Kapitelpläne. */
    fun repeats(plan: String, others: List<String>): Boolean {
        val mine = grams(words(plan))
        if (mine.isEmpty()) return false
        return others.any { o -> grams(words(o)).let { og -> og.isNotEmpty() && mine.count { it in og }.toDouble() / mine.size >= 0.3 } }
    }

    /** Titel und Rest ("TITEL: …" und die übrigen Abschnitte) der Grundlage. */
    fun premise(text: String): Pair<String, String> {
        val m = Regex("""(?im)^\s*[#*_\s]*(?:titel|title)\s*[*_]*\s*:\s*(.+)$""").find(text)
        val title = m?.groupValues?.get(1)?.replace(Regex("""[*_#"„“]"""), "")?.trim().orEmpty()
        val rest = (if (m != null) text.removeRange(m.range) else text).trim()
        return title to rest
    }

    /** Zusammenfassung und Fakten eines fertigen Kapitels: "ZUSAMMENFASSUNG: … FAKTEN: …". */
    fun summary(text: String): Pair<String, String> {
        val s = Regex("""(?is)zusammenfassung\s*:|summary\s*:""").find(text)
        val f = Regex("""(?is)fakten\s*:|facts\s*:""").find(text)
        val summary = when {
            s == null -> text.trim()
            f != null && f.range.first > s.range.last -> text.substring(s.range.last + 1, f.range.first)
            else -> text.substring(s.range.last + 1)
        }.trim()
        val facts = f?.let { text.substring(it.range.last + 1).trim() }.orEmpty()
        return summary to facts
    }
}
