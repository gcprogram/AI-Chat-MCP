package app.aichatmcp.core

/*
 * Bereinigung der Modellantwort (Port von reply.py) - Gegenmittel für kleine Modelle, die sich wiederholen:
 * dieselben Sätze am Ende jeder Antwort, Floskeln, durchsickernde Markup-/Rollenfragmente, abgebrochene Sätze.
 * Besonders schädlich: Das Modell sieht seine eigenen Wiederholungen im Verlauf und macht sie nach. Deshalb wird
 * die Antwort bereinigt, BEVOR sie im Verlauf landet, und eine erkennbare Endlosschleife schon beim Streamen
 * abgebrochen (spart Tokens).
 */

const val SIMILARITY = 0.8 // Wortfolgen-Ähnlichkeit, ab der ein Satz als Wiederholung gilt
const val MIN_WORDS = 4 // kürzere Sätze ("Ja, genau.") zählen nie als Wiederholung
const val HISTORY_REPLIES = 6 // so viele frühere Antworten werden zum Vergleich herangezogen

private val SPLIT = Regex("((?<=[.!?…])[ \\t]+|\\n+)")
private val MARKUP = Regex(
    "</?(?:h[1-6]|div|span|p|br|ul|ol|li|html|body)\\b[^<>]{0,40}>" +
        "|<\\|[^<>|]{1,40}\\|>|</?(?:start|end)_of_turn>",
    RegexOption.IGNORE_CASE,
)
private val LOOP = Regex("(.{6,60}?)(?:\\s*\\1){2,}", RegexOption.DOT_MATCHES_ALL) // Baustein mindestens dreimal hintereinander
private val SENTENCE_END = Regex("[.!?…][\"»“)]*(?=\\s)")
private val TRIGGER = Regex("[.!?…\\n<:\\]]")
private val NON_WORD = Regex("(?U)[^\\w\\s]")
private val SPACES = Regex("\\s+")

data class Reply(
    /** Bereinigter Text für Verlauf und Anzeige; enthält den Werkzeugaufruf, falls vorhanden. */
    val text: String,
    val call: ToolCall? = null,
    /** Was bereinigt wurde (für die Statuszeile). */
    val notes: List<String> = emptyList(),
)

private fun roleHeader(userName: String, personaName: String): Regex {
    val names = setOf("User", "Nutzer", "Human", "Assistant", userName, personaName).filter { it.isNotEmpty() }
        .joinToString("|") { Regex.escape(it) }
    return Regex("(?:^|\\n|(?<=[.!?…]\\s))(?:$names)[ \\t]*:")
}

/** (Satz, folgendes Trennzeichen)-Paare; Zeilenumbrüche bleiben erhalten. */
internal fun sentences(text: String): List<Pair<String, String>> {
    val parts = mutableListOf<String>() // Text, Trenner, Text, Trenner, ... (wie re.split mit Gruppe)
    var pos = 0
    for (m in SPLIT.findAll(text)) {
        parts += text.substring(pos, m.range.first)
        parts += m.value
        pos = m.range.last + 1
    }
    parts += text.substring(pos)
    val pairs = mutableListOf<Pair<String, String>>()
    for (i in parts.indices step 2) {
        val sep = parts.getOrElse(i + 1) { "" }
        if (parts[i].isNotBlank() || sep.isNotEmpty()) pairs += parts[i] to sep
    }
    return pairs
}

internal fun words(sentence: String): List<String> =
    NON_WORD.replace(sentence.lowercase(), " ").trim().split(SPACES).filter { it.isNotEmpty() }

/** Nachbau von difflib.SequenceMatcher(None, a, b, autojunk=False).ratio() für Wortlisten. */
internal object Difflib {
    fun ratio(a: List<String>, b: List<String>): Double {
        val total = a.size + b.size
        if (total == 0) return 1.0
        return 2.0 * matches(a, b) / total
    }

    private fun matches(a: List<String>, b: List<String>): Int {
        val b2j = HashMap<String, MutableList<Int>>()
        b.forEachIndexed { j, w -> b2j.getOrPut(w) { mutableListOf() }.add(j) }
        var total = 0
        val queue = ArrayDeque<IntArray>()
        queue.add(intArrayOf(0, a.size, 0, b.size))
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast()
            val (i, j, k) = longest(a, b2j, alo, ahi, blo, bhi)
            if (k > 0) {
                total += k
                if (alo < i && blo < j) queue.add(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.add(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        return total
    }

    private fun longest(a: List<String>, b2j: Map<String, List<Int>>, alo: Int, ahi: Int, blo: Int, bhi: Int): Triple<Int, Int, Int> {
        var bestI = alo
        var bestJ = blo
        var bestSize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newJ2len = HashMap<Int, Int>()
            for (j in b2j[a[i]].orEmpty()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                newJ2len[j] = k
                if (k > bestSize) {
                    bestI = i - k + 1
                    bestJ = j - k + 1
                    bestSize = k
                }
            }
            j2len = newJ2len
        }
        return Triple(bestI, bestJ, bestSize)
    }
}

private fun similar(a: List<String>, b: List<String>): Boolean {
    if (a == b) return true
    if (kotlin.math.abs(a.size - b.size) > maxOf(a.size, b.size) * 0.5) return false
    return Difflib.ratio(a, b) >= SIMILARITY
}

private fun pool(previous: List<String>): List<List<String>> {
    val pool = mutableListOf<List<String>>()
    for (text in previous.takeLast(HISTORY_REPLIES)) {
        for ((sentence, _) in sentences(CALL_RE.replace(text, ""))) {
            val w = words(sentence)
            if (w.size >= MIN_WORDS) pool += w
        }
    }
    return pool
}

/** Schneidet am ersten Markup-/Rollenfragment ab; danach ist das Modell aus der Spur. */
fun cutJunk(text: String, userName: String, personaName: String): Pair<String, Boolean> {
    val positions = listOfNotNull(MARKUP.find(text), roleHeader(userName, personaName).find(text)).map { it.range.first }
    if (positions.isEmpty()) return text to false
    return text.substring(0, positions.min()) to true
}

/** Entfernt einen abgebrochenen letzten Satz (Token-Limit, Abbruch), wenn davor ein ganzer Satz steht. */
fun trimDangling(text: String, minWords: Int = 3): String {
    val t = text.trimEnd()
    if (t.isEmpty() || !(t.last().isLetterOrDigit() || t.last() in ",;:-–(")) return t
    val last = SENTENCE_END.findAll(t).lastOrNull()
    if (last != null && t.substring(last.range.last + 1).trim().split(SPACES).filter { it.isNotEmpty() }.size >= minWords) {
        return t.substring(0, last.range.last + 1).trimEnd()
    }
    return t
}

/** Streicht Sätze, die schon vorher in dieser Antwort (looped) oder in früheren Antworten standen. */
internal fun removeRepeats(text: String, pool: List<List<String>>): Triple<String, Int, Boolean> {
    val kept = mutableListOf<Pair<String, String>>()
    val seen = mutableListOf<List<String>>()
    var removed = 0
    var looped = false
    for ((sentence, sep) in sentences(text)) {
        val w = words(sentence)
        if (w.size >= MIN_WORDS) {
            if (seen.any { similar(w, it) }) {
                removed++
                looped = true
                continue
            }
            if (pool.any { similar(w, it) }) {
                removed++
                continue
            }
            seen += w
        }
        kept += sentence to sep
    }
    return Triple(kept.joinToString("") { it.first + it.second }.trim(), removed, looped)
}

/** Vollständige Bereinigung einer fertigen (oder abgebrochenen) Antwort. */
fun finalizeReply(
    raw: String, userName: String, personaName: String, previous: List<String> = emptyList(),
    toolNames: List<String> = emptyList(),
): Reply {
    val notes = mutableListOf<String>()
    var text = raw
    var call: ToolCall? = null
    if (toolNames.isNotEmpty()) {
        call = findCall(text)
        if (call != null) {
            if (text.substring(call.end).isNotBlank()) notes += "Text nach dem Werkzeugaufruf verworfen"
            text = text.substring(0, call.start)
        }
    }
    text = text.replace(Regex("^\\s*${Regex.escape(personaName)}[ \\t]*:[ \\t]*"), "") // eigener Namens-Präfix
    val (cut, junk) = cutJunk(text, userName, personaName)
    text = cut
    if (junk) {
        notes += "Fremdtext/Markup abgeschnitten"
        call = null // Aufruf nach dem Aussetzer ist nicht vertrauenswürdig
        text = trimDangling(text, minWords = 1)
    }
    val collapsed = LOOP.replace(text, "$1")
    if (collapsed != text) notes += "Endlosschleife gekürzt"
    var (cleaned, removed, _) = removeRepeats(collapsed, pool(previous))
    if (removed > 0) notes += "$removed wiederholte Sätze entfernt"
    if (cleaned.isBlank()) cleaned = collapsed.trim() // lieber eine wiederholte Antwort als keine
    if (call == null) cleaned = trimDangling(cleaned)
    val final = (cleaned + (if (call != null) " " + call.raw else "")).trim()
    return Reply(final, call, notes)
}

/**
 * Beobachtet den Stream und meldet, wann die Generierung abgebrochen werden sollte:
 * "call" (Werkzeugaufruf komplett), "junk" (Markup/Rollenzeile), "repeat" (Satz wiederholt), "loop" (Baustein).
 */
class StreamGuard(private val userName: String, private val personaName: String, toolNames: List<String> = emptyList()) {
    private val tools = toolNames.isNotEmpty()
    private val buffer = StringBuilder()
    private var checked = 0

    fun feed(chunk: String): String? {
        buffer.append(chunk)
        if (!TRIGGER.containsMatchIn(chunk) && buffer.length - checked < 80) return null
        checked = buffer.length
        val all = buffer.toString()
        if (tools && findCall(all) != null) return "call"
        val text = CALL_RE.replace(all, "")
        if (cutJunk(text, userName, personaName).second) return "junk"
        if (LOOP.containsMatchIn(text.takeLast(400))) return "loop"
        val seen = mutableListOf<List<String>>()
        for ((sentence, sep) in sentences(text)) {
            if (sep.isEmpty()) break // letzter Satz ist noch nicht fertig
            val w = words(sentence)
            if (w.size >= MIN_WORDS) {
                if (seen.any { similar(w, it) }) return "repeat"
                seen += w
            }
        }
        return null
    }
}
