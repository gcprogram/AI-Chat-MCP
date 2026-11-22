package app.aichatmcp.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Eine Szene des Spielbuchs. Alles außer [title] und [chapter] erzeugt die Vorbereitung und kann von Hand (auch außerhalb der App) geändert werden:
 * [plot] = Zusammenfassung (daraus erzählt der Spielleiter), [place] = wo die Spielerfigur am Ende der Szene ist, [thread] = Handlungsstrang,
 * [enter] = die Entscheidung, die in diese Szene hineinführt (Richtung oder Handlung am Anfang der Szene, ohne Ergebnis), [risk] und [detour] = die
 * beiden Alternativen am Ende der Szene (gefährlich bzw. harmloser Umweg, beide vom Ort [place] aus möglich).
 */
@Serializable
data class GbScene(
    val title: String,
    val chapter: String = "",
    val thread: String = "",
    val plot: String = "",
    val place: String = "",
    val enter: String = "",
    val risk: String = "",
    val detour: String = "",
)

/** Das Spielbuch: ein Buch, einmal vorbereitet. Wird als JSON gespeichert, ist von Hand änderbar und lässt sich wieder einlesen; zum Spielen braucht es den Buchtext nicht mehr. */
@Serializable
data class Gamebook(
    val format: String = Gamebooks.FORMAT,
    val version: Int = 1,
    /** Verbindet das Spielbuch mit dem Buch, aus dem es stammt ([Summaries.key]). */
    val key: String = "",
    val title: String = "",
    val language: String = "Deutsch",
    val firstPerson: Boolean = false,
    val characters: List<String> = emptyList(),
    val scenes: List<GbScene> = emptyList(),
) {
    /** Die Szenen für das Spiel: Titel und Zusammenfassung als Text. */
    fun sceneList(): List<Scene> = scenes.map { Scene(it.title, it.plot) }

    fun plots(): Map<Int, String> = scenes.withIndex().filter { it.value.plot.isNotBlank() }.associate { it.index to it.value.plot }

    /** Die Handlungsstränge je Szene; leer, wenn sie nicht für alle Szenen bekannt sind. */
    fun threads(): Map<Int, String> = if (scenes.isNotEmpty() && scenes.all { it.thread.isNotBlank() }) scenes.withIndex().associate { it.index to it.value.thread } else emptyMap()

    fun chapters(): List<String> = scenes.map { it.chapter.ifBlank { Summaries.chapterOf(it.title) } }
}

/** Eine Entscheidung nach einer Szene, in der Form, wie sie das Spiel anbietet. */
object Gamebooks {
    const val FORMAT = "aichat-gamebook"

    /** Wie viele Szenen eine Anfrage nach den Strängen umfasst. */
    const val BATCH = 10
    private const val PLOT_CHARS = 300
    private const val PASSAGE_CHARS = 3600
    private const val NEXT_CHARS = 500

    private val JSON = Json(AppJson) { prettyPrint = true; encodeDefaults = true }

    fun toJson(book: Gamebook): String = JSON.encodeToString(Gamebook.serializer(), book)

    /** Liest ein Spielbuch aus JSON (auch von Hand geändert); null, wenn es keins ist (falsches Format oder keine Szenen). */
    fun parse(text: String): Gamebook? {
        val book = runCatching { AppJson.decodeFromString(Gamebook.serializer(), text.trim().removePrefix("﻿")) }.getOrNull() ?: return null
        if (book.format != FORMAT || book.scenes.isEmpty()) return null
        return book.copy(scenes = book.scenes.map { it.copy(chapter = it.chapter.ifBlank { Summaries.chapterOf(it.title) }) })
    }

    // --- Vorspann und Nachspann ---------------------------------------------------------------------------------

    private val FRONT = Regex(
        """(?i)(\bISBN\b|©|\(c\)\s*\d{4}|copyright|all rights reserved|alle rechte vorbehalten|originalausgabe|originaltitel|impressum|erstmals erschienen|printed in|inhaltsverzeichnis|table of contents|danksagung|acknowledg|leseprobe|über den autor|about the author|verlagsgruppe|gedruckt auf)""",
    )

    /**
     * Welche Szenen sind Vorspann oder Nachspann (Titelseite, Copyright, Inhaltsverzeichnis, Danksagung)? Nur die ersten und letzten vier Szenen werden geprüft.
     * Sie gehören nicht zur Geschichte; ein Spielleiter, der sie erzählen soll, erfindet sonst etwas.
     */
    fun frontMatter(scenes: List<Scene>): Set<Int> {
        val out = HashSet<Int>()
        for ((i, s) in scenes.withIndex()) {
            if (i >= 4 && i < scenes.size - 4) continue
            val markers = FRONT.findAll(s.text).map { it.value.lowercase() }.toSet().size
            if (markers >= 2 || (markers >= 1 && s.text.length < 1500)) out += i
        }
        return out
    }

    /** Die Szenen der Geschichte ohne Vorspann und Nachspann (Titelseite, Copyright, Danksagung). */
    fun storyScenes(scenes: List<Scene>): List<Scene> {
        val skip = frontMatter(scenes)
        return scenes.filterIndexed { i, _ -> i !in skip }
    }

    // --- Vorbereitung -------------------------------------------------------------------------------------------

    /** Ein leeres Spielbuch zu den Szenen (nur Titel und Kapitel). */
    fun skeleton(scenes: List<Scene>, title: String, language: String, key: String, firstPerson: Boolean, characters: List<String>): Gamebook =
        Gamebook(key = key, title = title, language = language, firstPerson = firstPerson, characters = characters, scenes = scenes.map { GbScene(it.title, Summaries.chapterOf(it.title)) })

    /** Passt [book] zu diesen Szenen (gleiche Zahl und Titel), sodass eine unterbrochene Vorbereitung dort weitermachen kann? */
    fun compatible(book: Gamebook, scenes: List<Scene>): Boolean = book.scenes.size == scenes.size && book.scenes.indices.all { book.scenes[it].title == scenes[it].title }

    /**
     * Ist die Karte der Szene fertig? Zusammenfassung, Eingang und Ort müssen da sein: Eine Notlösung (Anfang des Buchtextes statt Zusammenfassung, weil das Modell das Format
     * nicht eingehalten hat) hat keinen Ort und wird bei der nächsten Vorbereitung erneut erzeugt.
     */
    fun sceneDone(s: GbScene): Boolean = s.plot.isNotBlank() && s.enter.isNotBlank() && s.place.isNotBlank()

    private val ENGLISH = Regex("""(?i)\b(the|and|of|to|with|is|are|she|her|his|they|that|from)\b""")
    private val GERMAN = Regex("""(?i)\b(der|die|das|und|nicht|ist|mit|sich|ein|eine|zu|den|sie|er|von)\b""")

    /**
     * Ist die Karte in der falschen Sprache geschrieben? Das Modell antwortet bei einer englischen Anweisung manchmal englisch, obwohl das Buch deutsch ist (die Entscheidungen
     * stünden dann englisch im deutschen Spiel). Erkannt wird nur Deutsch gegen Englisch; andere Sprachen werden nicht geprüft.
     */
    fun wrongLanguage(s: GbScene, language: String): Boolean {
        val text = s.plot + " " + s.enter + " " + s.risk + " " + s.detour
        val en = ENGLISH.findAll(text).count()
        val de = GERMAN.findAll(text).count()
        return when {
            language.startsWith("Deu", ignoreCase = true) -> en >= 3 && en > de * 2
            language.startsWith("Eng", ignoreCase = true) -> de >= 3 && de > en * 2
            else -> false
        }
    }

    /** Ist die Karte der Szene [i] fertig und in der Sprache des Buches? Sonst wird sie bei der nächsten Vorbereitung erneut erzeugt. */
    fun cardDone(book: Gamebook, i: Int): Boolean = sceneDone(book.scenes[i]) && !wrongLanguage(book.scenes[i], book.language)

    fun batches(count: Int): Int = (count + BATCH - 1) / BATCH

    private fun batchDone(b: Int, book: Gamebook): Boolean = (b * BATCH until minOf(book.scenes.size, (b + 1) * BATCH)).all { book.scenes[it].thread.isNotBlank() }

    /** Wie viele Anfragen noch fehlen: eine je Szene ohne Karte und eine je Gruppe von Szenen ohne Strang. */
    fun requests(book: Gamebook): Int = book.scenes.indices.count { !cardDone(book, it) } + (0 until batches(book.scenes.size)).count { !batchDone(it, book) }

    fun ready(book: Gamebook): Boolean = book.scenes.isNotEmpty() && requests(book) == 0

    /**
     * Lässt sich mit dem Spielbuch spielen? Jede Szene braucht eine Zusammenfassung; fehlen Eingang, Ort oder Alternativen, gibt es dort "Weiter." und nur die Handlung.
     * Die Stränge sind freiwillig. Gilt auch für ein von Hand geschriebenes oder halb fertiges Buch.
     */
    fun playable(book: Gamebook): Boolean = book.scenes.isNotEmpty() && book.scenes.all { it.plot.isNotBlank() }

    /** Wie viele Szenen noch keine Zusammenfassung haben (für die Meldung bei einem unvollständigen Spielbuch). */
    fun missingScenes(book: Gamebook): Int = book.scenes.count { it.plot.isBlank() }

    /** Wie viele Szenen nur eine Notlösung haben (Anfang des Buchtextes statt Karte): eine neue Vorbereitung erzeugt sie erneut. */
    fun roughScenes(book: Gamebook): Int = book.scenes.indices.count { book.scenes[it].plot.isNotBlank() && !cardDone(book, it) }

    /** Die Handlungsstränge in der Reihenfolge ihres ersten Auftretens. */
    fun threadNames(book: Gamebook): List<String> = book.scenes.map { it.thread }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }

    /** Das Ergebnis der Karten-Anfrage zu einer Szene. */
    data class Card(val summary: String, val place: String, val enter: String, val risk: String, val detour: String)

    /**
     * Die Anfrage zu einer Szene: Zusammenfassung, Ort am Ende und die drei Entscheidungen. [scene] ist der Text der Szene, [next] der Anfang der folgenden.
     * Das Modell soll den Verlauf zur Richtung machen (kein Ergebnis verraten) und die Alternativen vom Ort aus möglich halten.
     */
    fun cardPrompt(book: String, scene: Scene, next: Scene?, language: String): List<WireMessage> {
        val nextBlock = next?.let { "\n\nNEXT (the beginning of the following passage):\n${it.text.trim().take(NEXT_CHARS)}" }.orEmpty()
        val german = language.startsWith("Deu", ignoreCase = true)
        // Die Anweisung ist englisch (kleine Modelle folgen ihr besser), die Vorlage der Werte steht in der Sprache des Buches: sonst antwortet ein Modell trotz "in Deutsch" englisch.
        // Jede Zeile beginnt mit ihrer Beschriftung; ohne diese Vorgabe ließ das 8B die Beschriftungen weg (die App liest das zur Not an der Reihenfolge, aber besser ist es richtig).
        val template = if (german) {
            "SUMMARY: <Zusammenfassung in 3 bis 5 Sätzen: wer handelt, was geschieht, welche Orte und Wendepunkte; nur der Inhalt der Passage, nichts erfinden>\n" +
                "PLACE: <Ort der Hauptfigur am ENDE der Passage (Gebäude, Stockwerk, Raum oder Ort im Freien), in wenigen Worten>\n" +
                "ENTER: <ein kurzer Satz in der Ich-Form: die Richtung oder Handlung der Hauptfigur am ANFANG der Passage, zum Beispiel \"Ich gehe weiter die Treppe hinauf.\", wenn die Figur den Fuß auf die nächste Stufe setzt; nur die Bewegung oder Handlung selbst, nie ihr Ergebnis, nichts, was erst später in der Passage enthüllt wird, und keine Namen von Personen, die die Figur noch nicht kennt>\n" +
                (if (next != null) {
                    "RISK: <ein Satz in der Ich-Form: eine andere, mutigere oder gefährliche Handlung am ENDE der Passage statt des Weitergehens mit NEXT; sie muss von genau dem Ort in PLACE aus möglich sein (kein Sprung in andere Stockwerke, entfernte Räume oder Gebäude; höchstens einen Schritt oder eine Tür weit)>\n" +
                        "DETOUR: <ein Satz in der Ich-Form: eine andere, harmlose Handlung, ebenfalls vom Ort in PLACE aus möglich, die trotzdem zu NEXT hinführt>\n"
                } else "RISK: -\nDETOUR: -\n")
        } else {
            "SUMMARY: <3 to 5 sentences in $language: who acts, what happens, which places and turning points; use only the passage and invent nothing>\n" +
                "PLACE: <where the main character is at the END of the passage (building, floor or level, room, or outdoor place), in a few words>\n" +
                "ENTER: <one short sentence in the first person (\"I ...\") naming the direction or action the main character takes at the BEGINNING of the passage, for example \"I go on up the stairs.\" when the text says the character sets a foot on the next step; name only the movement or action itself: never its result, nothing that is revealed later in the passage, and no names of people the character has not met yet>\n" +
                (if (next != null) {
                    "RISK: <one sentence in the first person: a different, bolder or dangerous action the main character could take at the END of the passage instead of continuing with NEXT; it must be possible from exactly the place named in PLACE (no jumping to other floors, rooms far away or buildings; at most one step or one door away)>\n" +
                        "DETOUR: <one sentence in the first person: a different, harmless action, also possible from the place named in PLACE, that still leads on towards NEXT>\n"
                } else "RISK: -\nDETOUR: -\n")
        }
        return listOf(
            WireMessage(
                "user",
                "You prepare a solo gamebook (choose your path) from the novel \"$book\". Read the PASSAGE and answer in exactly this format: five lines, each starting with its label:\n" + template +
                    "Every value must be written in $language, never in another language. Begin every line with SUMMARY:, PLACE:, ENTER:, RISK: or DETOUR:, and write nothing before or after.\n\n" +
                    "PASSAGE (${scene.title}):\n${scene.text.trim().take(PASSAGE_CHARS)}$nextBlock",
            ),
        )
    }

    private val LABEL = Regex("""(?im)^[ \t]*[*#_\-]*[ \t]*(SUMMARY|PLACE|ENTER|RISK|DETOUR)[ \t]*[*_]*[ \t]*[:：][ \t]*""")

    private fun tidy(s: String): String = s.replace("**", "").replace(Regex("""\s+"""), " ").trim().trim('"', '„', '“', '”', '»', '«', '*', '_', ' ')

    /**
     * Liest die Antwort zur Karte; fehlende Zeilen bleiben leer, "-" gilt als leer. Schreibt das Modell die fünf Zeilen ohne Beschriftung (das tat das 8B), gilt die Reihenfolge:
     * die letzten vier Zeilen sind Ort, Eingang, Risiko, Umweg, alles davor die Zusammenfassung.
     */
    fun parseCard(answer: String): Card {
        val matches = LABEL.findAll(answer).toList()
        if (matches.isEmpty()) {
            val lines = answer.lines().map { tidy(it) }.filter { it.isNotEmpty() }
            if (lines.size < 5) return Card("", "", "", "", "")
            val tail = lines.takeLast(4).map { if (it == "-") "" else it }
            val first = lines.dropLast(4).joinToString(" ")
            return Card(first, tail[0].take(120), tail[1], tail[2], tail[3])
        }
        val values = HashMap<String, String>()
        for ((i, m) in matches.withIndex()) {
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else answer.length
            val key = m.groupValues[1].uppercase()
            if (key !in values) values[key] = tidy(answer.substring(m.range.last + 1, end)).let { if (it == "-") "" else it }
        }
        fun one(key: String): String = values[key].orEmpty().let { v -> if (v.length > 220) (Regex("""^.{20,220}?[.!?…]""").find(v)?.value ?: v.take(220)) else v }
        return Card(values["SUMMARY"].orEmpty(), one("PLACE").take(120), one("ENTER"), one("RISK"), one("DETOUR"))
    }

    /** Die Anfrage: Welchem Handlungsstrang gehört jede dieser Szenen ([items] = Nummer ab 0, Titel, Zusammenfassung) an? [known] = Stränge bisher. */
    fun threadPrompt(items: List<Triple<Int, String, String>>, known: List<String>, language: String): List<WireMessage> {
        val list = items.joinToString("\n") { (i, title, plot) -> "${i + 1}. $title: ${plot.trim().take(PLOT_CHARS)}" }
        val knownLine = if (known.isEmpty()) "There are no storylines yet." else "Storylines so far: ${known.joinToString("; ")}."
        return listOf(
            WireMessage(
                "user",
                "Below are consecutive scenes of a book (number, title, summary). Assign each scene to the storyline it belongs to. A storyline follows one main character or group, " +
                    "or one place and plot thread; name it after the character or group (for example \"Lucy and Lockwood\") and not after a topic. Scenes that happen in parallel or independently of each other belong to different storylines. $knownLine " +
                    "Reuse a known storyline whenever the scene continues it; add a new one (1 to 3 words, in $language) only for a really separate storyline. A book that follows one main character uses one name for all scenes.\n" +
                    "Answer with exactly one line per scene in this form and nothing else:\n<number>: <storyline>\n\n$list",
            ),
        )
    }

    private val LINE = Regex("""(?m)^\s*[*#_\s\-]*(\d{1,5})\s*[:.)\-–]\s*[*_]*\s*(.+?)\s*$""")

    /**
     * Liest die Antwort: Zeile "n: Strang" für die Szenen [numbers] (Nummern ab 0, die Zeilen zählen ab 1). Gleiche Namen (ohne Rücksicht auf Groß-/Kleinschreibung)
     * werden zusammengelegt, bekannte Namen ([known]) haben Vorrang, eine wiederholte Nummer im Namen ("96: 96: Alarm") wird entfernt. Szenen ohne Zeile übernehmen
     * den Strang der vorigen, die erste im Zweifel [fallback].
     */
    fun parseThreads(answer: String, numbers: List<Int>, known: List<String>, fallback: String): Map<Int, String> {
        val names = known.toMutableList()
        val found = HashMap<Int, String>()
        for (m in LINE.findAll(answer)) {
            val index = m.groupValues[1].toInt() - 1
            if (index !in numbers || index in found) continue
            val raw = m.groupValues[2].replace("**", "").replace(Regex("""^\d{1,5}\s*[:.)\-–]\s*"""), "").trim().trim('*', '_', '"', '„', '“', '»', '«', ' ', '.').take(40)
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

    /**
     * Bereitet das Spielbuch vor: je Szene eine Karte (Zusammenfassung, Ort, Entscheidungen), danach die Stränge je [BATCH] Szenen. [scenes] sind die Szenen mit
     * Buchtext (ohne Vor- und Nachspann), [start] der bisherige Stand zu genau diesen Szenen ([skeleton] oder ein unterbrochener Lauf). Nach jeder Anfrage bekommt
     * [onData] den Stand zum Speichern (Abbruch oder Fehler kosten nichts); [onProgress] die Zahl der erledigten und aller nötigen Anfragen. Die Karten der Szenen
     * hängen nicht voneinander ab: [parallel] davon laufen gleichzeitig (ein externes Modell schafft so mehr; ein Modell auf dem Handy rechnet ohnehin nur eine Anfrage).
     */
    suspend fun prepare(
        scenes: List<Scene>, start: Gamebook,
        ask: suspend (List<WireMessage>, Int) -> String?,
        onProgress: (done: Int, total: Int, what: String) -> Unit = { _, _, _ -> }, onData: (Gamebook) -> Unit = {},
        parallel: Int = 1,
    ): Gamebook {
        var book = start
        val total = requests(start).coerceAtLeast(1)
        var done = 0
        val lock = Mutex()
        val german = book.language.startsWith("Deu", ignoreCase = true)
        suspend fun call(prompt: List<WireMessage>, maxTokens: Int, what: String, plain: Boolean = false): String {
            onProgress(done.coerceAtMost(total), total, what)
            // Karten nicht mit der Bereinigung für Zusammenfassungen lesen: sie würde eine Zeile "SUMMARY:" ohne Wert entfernen
            val answer = ask(prompt, maxTokens)?.let { if (plain) it.trim() else Summaries.clean(it) }.orEmpty()
            if (answer.isBlank()) {
                currentCoroutineContext().ensureActive() // ein Abbruch ist keine Fehlermeldung des Modells
                throw SummaryException("Das Modell hat nicht geantwortet.")
            }
            return answer
        }
        suspend fun doCard(i: Int) {
            val scene = scenes[i]
            val prompt = cardPrompt(book.title, scene, scenes.getOrNull(i + 1), book.language)
            var card = parseCard(call(prompt, 380, scene.title, plain = true))
            if (card.summary.isBlank()) card = parseCard(call(prompt, 380, scene.title, plain = true)) // das Format fehlt: ein zweiter Versuch
            // Notlösung (auch beim zweiten Mal kein Format): Anfang des Buchtextes, ohne Ort; die nächste Vorbereitung erzeugt die Karte erneut
            val summary = card.summary.ifBlank { scene.text.trim().take(300) }
            val enter = card.enter
            lock.withLock {
                book = book.copy(scenes = book.scenes.toMutableList().also { it[i] = it[i].copy(plot = summary, place = card.place, enter = enter, risk = card.risk, detour = card.detour) })
                done++
                onProgress(done.coerceAtMost(total), total, scene.title)
                onData(book)
            }
        }
        val pending = scenes.indices.filter { !cardDone(book, it) }
        if (parallel <= 1) pending.forEach { doCard(it) }
        else coroutineScope {
            val permits = Semaphore(parallel)
            pending.map { i -> async { permits.withPermit { doCard(i) } } }.awaitAll()
        }
        for (b in 0 until batches(book.scenes.size)) {
            if (batchDone(b, book)) continue
            val numbers = (b * BATCH until minOf(book.scenes.size, (b + 1) * BATCH)).toList()
            val known = threadNames(book)
            val items = numbers.map { Triple(it, book.scenes[it].title, book.scenes[it].plot) }
            val answer = call(threadPrompt(items, known, book.language), 200, book.scenes[numbers.first()].title)
            val fallback = book.scenes.getOrNull(numbers.first() - 1)?.thread?.takeIf { it.isNotBlank() } ?: if (german) "Haupthandlung" else "Main story"
            val threads = parseThreads(answer, numbers, known, fallback)
            book = book.copy(scenes = book.scenes.toMutableList().also { list -> for ((i, t) in threads) list[i] = list[i].copy(thread = t) })
            done++
            onProgress(done.coerceAtMost(total), total, book.scenes[numbers.first()].title)
            onData(book)
        }
        onData(book)
        return book
    }

    // --- Entscheidungen aus dem Spielbuch -----------------------------------------------------------------------

    /** Die Entscheidung, die in die Szene [index] hineinführt ("Weiter.", wenn nichts da ist). */
    fun enterOf(book: Gamebook, index: Int, german: Boolean): String = book.scenes.getOrNull(index)?.enter?.takeIf { it.isNotBlank() } ?: if (german) "Weiter." else "Continue."

    /**
     * Die Entscheidungen am Ende der Szene [current]: je nach [mode] und [round] die Stränge (die nächste Szene je Handlungsstrang **im selben Kapitel**,
     * Text = Entscheidung, die in sie hineinführt) oder Handlung/Risiko/Umweg (Handlung = Eingang der nächsten Szene, Risiko und Umweg aus dieser Szene).
     * Fertige Texte, kein Modellaufruf. Leer, wenn alle Szenen gespielt sind.
     */
    fun options(book: Gamebook, current: Int, played: Set<Int>, mode: DecisionMode, round: Int, german: Boolean): List<Decision> {
        val n = book.scenes.size
        val next = Solo.candidates(n, played, 1).firstOrNull() ?: return emptyList()
        val threads = book.threads()
        val strands = if (threads.isEmpty()) listOf(next) else Solo.candidates(n, played, Solo.MAX_STRANDS, threads, book.chapters())
        if (Solo.useStrands(mode, round, strands.size)) return strands.map { Decision(DecisionKind.STRAND, enterOf(book, it, german), scene = it) }
        val scene = book.scenes.getOrNull(current)
        return listOfNotNull(
            Decision(DecisionKind.STORY, enterOf(book, next, german), scene = next),
            scene?.risk?.takeIf { it.isNotBlank() }?.let { Decision(DecisionKind.RISK, it) },
            scene?.detour?.takeIf { it.isNotBlank() }?.let { Decision(DecisionKind.DETOUR, it) },
        )
    }
}
