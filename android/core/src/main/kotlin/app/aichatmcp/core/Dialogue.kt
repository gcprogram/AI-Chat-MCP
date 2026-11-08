package app.aichatmcp.core

/** Wer beim Vorlesen spricht: der Erzähler (Standardstimme des Companions) oder eine Figur in wörtlicher Rede. */
enum class Role { NARRATOR, MALE, FEMALE }

/** Ein Stück eines Abschnitts mit der Rolle, die es sprechen soll. */
data class Utterance(val text: String, val role: Role)

enum class Gender {
    MALE, FEMALE;

    fun flip(): Gender = if (this == MALE) FEMALE else MALE
    fun role(): Role = if (this == MALE) Role.MALE else Role.FEMALE
}

/** Gängige Vornamen (deutsch und einige internationale) mit eindeutigem Geschlecht; mehrdeutige (Kim, Alex, Chris, …) fehlen mit Absicht. */
object FirstNames {
    private val MALE = (
        "Aaron Adam Adrian Albert Alexander Alfred Andreas Anton Armin Arne Arthur August Axel Ben Benedikt Benjamin Bernd Bernhard Bert Björn Boris Bruno " +
            "Carl Christian Christoph Claus Clemens Daniel David Dennis Detlef Dieter Dirk Dominik Eduard Elias Emil Erik Ernst Erwin Fabian Felix Ferdinand " +
            "Florian Frank Franz Frederik Friedrich Fritz Gabriel Georg Gerd Gerhard Gregor Günter Gustav Hannes Hans Harald Hartmut Heinrich Heinz Helmut " +
            "Henning Henri Henry Herbert Hermann Holger Horst Hubert Hugo Ingo Jakob Jan Jannik Jens Jochen Joachim Johann Johannes Jonas Jonathan Jörg " +
            "Josef Joseph Julian Jürgen Karl Karsten Kaspar Klaus Konrad Konstantin Kurt Lars Laurenz Leo Leon Leopold Lorenz Lothar Lucas Ludwig Luis Lukas " +
            "Manfred Manuel Marc Marcel Marco Marcus Mario Markus Martin Mathias Matthias Max Maximilian Michael Mike Moritz Nikolaus Nils Norbert Oliver " +
            "Oskar Otto Patrick Paul Peter Philipp Rainer Ralf Reinhard Richard Robert Roland Rolf Rudolf Rüdiger Sebastian Siegfried Simon Stefan Stephan " +
            "Sven Theo Theodor Thomas Thorsten Tim Timo Tobias Tom Udo Ulrich Uwe Viktor Vincent Volker Walter Werner Wilhelm Willi Wolfgang Xaver " +
            "John James William George Harry Jack Oliver Charlie Thomas Edward Arthur Michel Pierre Jean Luca Marco Giovanni Juan Carlos Pedro Miguel"
        ).split(' ').filter { it.isNotEmpty() }.toHashSet()

    private val FEMALE = (
        "Adelheid Agnes Alexandra Alice Alina Amelie Anette Angela Angelika Anita Anja Anke Ann Anna Annabelle Anne Annegret Annelie Annette Antje Antonia " +
            "Astrid Barbara Beate Beatrice Bettina Birgit Bianca Brigitte Britta Carina Carla Carmen Carolin Caroline Cäcilie Charlotte Christa Christiane " +
            "Christina Christine Clara Claudia Cornelia Dagmar Daniela Désirée Diana Doris Dorothea Edith Elena Eleonore Elfriede Elisa Elisabeth Elke Ella " +
            "Ellen Elsa Else Emilia Emilie Emma Erika Esther Eva Evelyn Fatima Felicitas Franziska Frauke Frieda Friederike Gabi Gabriele Gerda Gertrud " +
            "Gisela Greta Gretchen Gudrun Hanna Hannah Hannelore Heide Heidi Helena Helene Helga Henriette Hermine Hilde Hildegard Ida Ilse Ina Ines Inge " +
            "Ingeborg Ingrid Irene Irina Iris Irmgard Isabel Isabell Isabella Isolde Jana Jasmin Jennifer Jessica Johanna Judith Julia Juliane Jutta Karin " +
            "Karla Karolina Katharina Kathrin Katja Katrin Klara Klaudia Kristina Kristin Lara Laura Lea Lena Leonie Lieselotte Lilli Lilly Lina Linda Lisa " +
            "Lisbeth Lotte Louisa Louise Lucia Luisa Luise Lydia Magda Magdalena Maike Margarete Margit Maria Marianne Marie Marion Marlene Marta Martha " +
            "Martina Mathilde Maya Meike Melanie Melissa Mia Michaela Miriam Mona Monika Nadine Natalie Nathalie Nele Nicole Nina Nora Olga Ottilie Paula " +
            "Paulina Petra Pia Ramona Rebecca Rebekka Regina Renate Rita Romy Rosa Rosemarie Ruth Sabine Sabrina Sandra Sara Sarah Silke Sophia Sophie " +
            "Stefanie Steffi Stephanie Susanne Svenja Sybille Tamara Tanja Theresa Therese Tina Ulla Ulrike Ursula Ute Uta Valentina Vanessa Vera Verena " +
            "Veronika Viktoria Waltraud Wilhelmine Yvonne Zoe Zoë Mary Elizabeth Jane Emily Hermione Marie Claire Sophia Giulia Francesca Maria Carmen Rosa"
        ).split(' ').filter { it.isNotEmpty() }.toHashSet()

    fun genderOf(name: String): Gender? = when {
        name in MALE && name !in FEMALE -> Gender.MALE
        name in FEMALE && name !in MALE -> Gender.FEMALE
        else -> null
    }
}

/** Anführungszeichen eines Textes: welche Zeichen eine Rede öffnen und schließen. */
class QuoteStyle(val opens: String, val closes: String, val toggle: Boolean = false) {
    companion object {
        /** Erkennt den Stil am Text (deutsch „…“, französisch »…« oder «…», englisch “…”, gerade "…"); null = keine wörtliche Rede erkennbar. */
        fun detect(text: String): QuoteStyle? {
            fun count(c: Char) = text.count { it == c }
            return when {
                count('„') > 0 -> QuoteStyle("„", "“”\"")
                count('»') > 0 || count('«') > 0 -> {
                    val a = text.indexOf('»')
                    val b = text.indexOf('«')
                    if (a >= 0 && (b < 0 || a < b)) QuoteStyle("»", "«") else QuoteStyle("«", "»")
                }
                count('“') > 0 && count('”') > 0 -> QuoteStyle("“", "”")
                count('"') >= 2 -> QuoteStyle("\"", "\"", toggle = true)
                else -> null
            }
        }
    }

    val all: String get() = opens + closes
}

/** Woher die Zuordnung einer Rede stammt (für Tests und die Anzeige). */
enum class Source { TAG, SAME, FLIP, JUDGE, NONE }

/** Eine wörtliche Rede im Gesamttext: [open] und [close] sind die Positionen der Anführungszeichen. */
class SpeechSpan(val open: Int, val close: Int) {
    var gender: Gender? = null
    var source: Source = Source.NONE
}

/**
 * Wörtliche Rede im Vorleser: erkennt Reden, ordnet sie nach festen Regeln einem Geschlecht zu (Begleitsatz wie "sagte sie", Vornamen,
 * Sprecherwechsel bei neuem Absatz) und zerlegt die Abschnitte in Erzähler- und Rede-Stücke. Der Text selbst bleibt unverändert; die
 * Stücke hängen als [ReadItem.Speech.parts] am Abschnitt, damit Abschnittsnummern, Marken und Bilder an derselben Stelle bleiben.
 *
 * Grundlage ist der Text der Abschnitte: Absatzwechsel sind dort schon zu ". " geworden ([Documents.speechText]); zwei Reden, zwischen
 * denen nur ". " steht, gelten deshalb als Sprecherwechsel.
 */
class Dialogue private constructor(
    private val items: List<ReadItem.Speech>,
    private val starts: IntArray,
    val text: String,
    val style: QuoteStyle?,
    val spans: List<SpeechSpan>,
) {
    /** Reden ohne Zuordnung (Kandidaten für den KI-Rückfall), in Lesereihenfolge. */
    fun unresolved(): List<Int> = spans.indices.filter { spans[it].gender == null }

    /** Wie [unresolved], aber zuerst die ab dem Abschnitt [from] (dort wird gerade gelesen), danach die davor. */
    fun unresolvedFrom(from: ReadItem.Speech?): List<Int> {
        val k = items.indexOfFirst { it === from }
        val offset = if (k >= 0) starts[k] else 0
        val (later, earlier) = unresolved().partition { spans[it].close >= offset }
        return later + earlier
    }

    /** Kontext einer Rede für den KI-Rückfall: Text davor, die Rede, Text danach. */
    fun context(i: Int, chars: Int = 160): Triple<String, String, String> {
        val s = spans[i]
        return Triple(
            text.substring(maxOf(0, s.open - chars), s.open), text.substring(s.open + 1, s.close),
            text.substring(s.close + 1, minOf(text.length, s.close + 1 + chars)),
        )
    }

    /** Setzt das Geschlecht einer Rede (z. B. vom KI-Rückfall) und aktualisiert die betroffenen Abschnitte. Liefert false, wenn nichts zu tun war. */
    fun assign(i: Int, gender: Gender, source: Source = Source.JUDGE): Boolean {
        val s = spans[i]
        if (s.gender != null) return false
        s.gender = gender
        s.source = source
        for (k in items.indices) {
            val end = starts[k] + items[k].text.length
            if (s.open < end && s.close >= starts[k]) items[k].parts = partsFor(k)
        }
        return true
    }

    /** Alle Reden mit einem bestimmten Namen im Begleitsatz (für "Anna ist weiblich": einmal fragen, überall anwenden). */
    fun namesAround(i: Int): List<String> {
        val s = spans[i]
        val before = text.substring(maxOf(0, s.open - 90), s.open)
        val after = text.substring(s.close + 1, minOf(text.length, s.close + 1 + 90))
        return NAME_WORD.findAll(before + " " + after).map { it.value }.filter { it !in STOP_WORDS && FirstNames.genderOf(it) == null }.distinct().toList()
    }

    private fun partsFor(k: Int): List<Utterance> {
        val itemStart = starts[k]
        val itemEnd = itemStart + items[k].text.length
        val out = mutableListOf<Utterance>()
        fun add(raw: String, role: Role) {
            val cleaned = clean(raw, style)
            if (cleaned.none { it.isLetterOrDigit() }) return
            if (out.isNotEmpty() && out.last().role == role) out[out.size - 1] = Utterance(out.last().text + " " + cleaned, role)
            else out += Utterance(cleaned, role)
        }
        var pos = itemStart
        for (s in spans) {
            if (s.close < itemStart || s.open >= itemEnd) continue
            val a = maxOf(itemStart, s.open)
            if (a > pos) add(text.substring(pos, a), Role.NARRATOR)
            val innerStart = maxOf(itemStart, s.open + 1)
            val innerEnd = minOf(itemEnd, s.close)
            if (innerEnd > innerStart) add(text.substring(innerStart, innerEnd), s.gender?.role() ?: Role.NARRATOR)
            pos = minOf(itemEnd, s.close + 1)
        }
        if (pos < itemEnd) add(text.substring(pos, itemEnd), Role.NARRATOR)
        return out
    }

    companion object {
        private val NAME_WORD = Regex("\\p{Lu}\\p{L}{2,}")
        private val STOP_WORDS = setOf(
            "Der", "Die", "Das", "Dann", "Sie", "Er", "Es", "Ein", "Eine", "Und", "Aber", "Doch", "Als", "Wie", "Was", "Wer", "Wo", "Da", "Nun", "Auch",
            "Noch", "Nur", "Ich", "Wir", "Ihr", "Herr", "Frau", "Fräulein", "Dort", "Hier", "Jetzt", "Darauf", "Danach", "Plötzlich", "Schließlich",
        )

        // Verben des Sagens (Stämme); mit "sie" zählt nur die Einzahl (nicht auf "-en"/"-ten" endend: "sie sagten")
        private const val VERB = "(?<!\\p{L})(?:sag|frag|rief|ruf|antwort|flüster|murmel|erwider|entgegn|mein|schri|schrei|brüll|seufz|lach|lächel|grins|schluchz|" +
            "stammel|stotter|sprach|sprich|erklär|bemerk|forder|bat|bitt|befahl|befiehl|raun|zisch|jammer|klag|hauch|flehte|fauchte|knurrte|keuchte|" +
            "stöhnte|kicherte|spottete|warnte|mahnte|betonte|versicherte|gestand|lobte|schimpfte|fluchte|wiederholte|unterbrach|fügte)\\p{L}*"
        private const val WHO = "(?:[Ee]r|[Ss]ie|(?:[Hh]err|[Ff]rau|[Ff]räulein)\\s+\\p{Lu}\\p{L}+|(?:[Dd]er|[Dd]ie|[Dd]as)\\s+\\p{L}+|\\p{Lu}\\p{L}+)"
        private val VERB_THEN_WHO = Regex("($VERB)(?:\\s+\\p{L}+)?\\s+($WHO)")
        private val WHO_THEN_VERB = Regex("($WHO)\\s+(?:\\p{L}+\\s+){0,2}($VERB)")
        private val MALE_NOUNS = setOf(
            "Mann", "Junge", "Vater", "Bruder", "Sohn", "Onkel", "Opa", "Großvater", "König", "Prinz", "Herr", "Lehrer", "Doktor", "Arzt", "Wirt",
            "Fremde", "Alte", "Kapitän", "Soldat", "Bauer", "Knecht", "Diener", "Priester", "Ritter",
        )
        private val FEMALE_NOUNS = setOf(
            "Frau", "Mädchen", "Mutter", "Schwester", "Tochter", "Tante", "Oma", "Großmutter", "Königin", "Prinzessin", "Lehrerin", "Ärztin", "Wirtin",
            "Fremde", "Alte", "Magd", "Dienerin", "Nonne", "Hexe", "Kellnerin",
        )

        /** Zerlegt die Reden eines Ablaufs und ordnet sie zu; die Abschnitte bekommen ihre [ReadItem.Speech.parts]. */
        fun analyze(plan: ReadPlan): Dialogue {
            val items = plan.items.filterIsInstance<ReadItem.Speech>()
            val starts = IntArray(items.size)
            val sb = StringBuilder()
            for ((k, item) in items.withIndex()) {
                if (k > 0) sb.append(if (item.paragraphStart) '\n' else ' ') // Absatzwechsel bleibt sichtbar
                starts[k] = sb.length
                sb.append(item.text)
            }
            val text = sb.toString()
            val style = QuoteStyle.detect(text)
            val spans = if (style == null) emptyList() else findSpans(text, style)
            if (spans.isNotEmpty()) {
                resolve(text, style!!, spans)
            }
            val d = Dialogue(items, starts, text, style, spans)
            if (spans.isNotEmpty()) for (k in items.indices) items[k].parts = d.partsFor(k)
            return d
        }

        internal fun findSpans(text: String, style: QuoteStyle, maxInner: Int = 1500): List<SpeechSpan> {
            val spans = mutableListOf<SpeechSpan>()
            var start = -1
            for (i in text.indices) {
                val c = text[i]
                if (start < 0) {
                    if (c in style.opens) start = i
                } else if (c in style.closes && (!style.toggle || c == style.opens[0])) {
                    if (i - start - 1 in 1..maxInner) spans += SpeechSpan(start, i)
                    start = -1
                } else if (c in style.opens && !style.toggle) {
                    start = i // ein vorheriges Anführungszeichen blieb offen: neu ansetzen
                } else if (i - start > maxInner) {
                    start = -1 // so lange Rede gibt es nicht: ein verirrtes Zeichen, nicht den Rest des Textes verschlucken
                }
            }
            return spans
        }

        private fun genderOfWho(who: String, verb: String): Gender? {
            val lower = who.lowercase()
            return when {
                lower == "er" -> Gender.MALE
                lower == "sie" -> if (verb.endsWith("en")) null else Gender.FEMALE // "sie sagten": Mehrzahl
                lower.startsWith("herr ") -> Gender.MALE
                lower.startsWith("frau ") || lower.startsWith("fräulein ") -> Gender.FEMALE
                lower.startsWith("der ") || lower.startsWith("die ") || lower.startsWith("das ") -> {
                    val noun = who.substringAfter(' ').trim().replaceFirstChar { it.uppercase() }
                    when {
                        noun in MALE_NOUNS && noun !in FEMALE_NOUNS -> Gender.MALE
                        noun in FEMALE_NOUNS && noun !in MALE_NOUNS -> Gender.FEMALE
                        noun in MALE_NOUNS || noun in FEMALE_NOUNS -> if (lower.startsWith("der ")) Gender.MALE else Gender.FEMALE
                        else -> null
                    }
                }
                else -> FirstNames.genderOf(who)
            }
        }

        /** Geschlecht aus einem Begleitsatz-Stück; [nearestLast]: bei Text *vor* der Rede zählt die letzte Fundstelle (die nächste an der Rede). */
        internal fun tagGender(fragment: String, nearestLast: Boolean): Gender? {
            val found = mutableListOf<Pair<Int, Gender?>>()
            for (m in VERB_THEN_WHO.findAll(fragment)) found += m.range.first to genderOfWho(m.groupValues[2], m.groupValues[1])
            for (m in WHO_THEN_VERB.findAll(fragment)) found += m.range.first to genderOfWho(m.groupValues[1], m.groupValues[2])
            val known = found.filter { it.second != null }.sortedBy { it.first }
            return (if (nearestLast) known.lastOrNull() else known.firstOrNull())?.second
        }

        private fun after(text: String, span: SpeechSpan, style: QuoteStyle): String {
            var fragment = text.substring(span.close + 1, minOf(text.length, span.close + 1 + 100))
            fragment = fragment.substringBefore('\n') // der Begleitsatz steht im selben Absatz
            // Ein Begleitsatz beginnt gleich hinter der Rede (", sagte er" oder "sagte er"); ein neuer Satz mit Großbuchstaben gehört zur nächsten Rede
            if (!Regex("^\\s*[,;]|^\\s*\\p{Ll}").containsMatchIn(fragment)) return ""
            Regex("[.!?](?=\\s|$)").find(fragment)?.let { fragment = fragment.substring(0, it.range.first) }
            val next = fragment.indexOfFirst { it in style.opens }
            return if (next >= 0) fragment.substring(0, next) else fragment
        }

        private fun before(text: String, span: SpeechSpan, style: QuoteStyle): String {
            var fragment = text.substring(maxOf(0, span.open - 120), span.open)
            val quoteClass = style.all.map { "\\u%04x".format(it.code) }.joinToString("") // \uXXXX statt \Q..\E: gilt in Java und ICU
            val boundary = Regex("[.!?][$quoteClass]?\\s").findAll(fragment).lastOrNull()
            if (boundary != null) fragment = fragment.substring(boundary.range.last + 1)
            fragment = fragment.substringAfterLast('\n') // nur der eigene Absatz zählt
            val prevClose = fragment.indexOfLast { it in style.closes }
            return if (prevClose >= 0) fragment.substring(prevClose + 1) else fragment
        }

        private fun resolve(text: String, style: QuoteStyle, spans: List<SpeechSpan>) {
            // 1. Begleitsatz direkt vor oder hinter der Rede
            for (s in spans) {
                val g = tagGender(before(text, s, style), nearestLast = true) ?: tagGender(after(text, s, style), nearestLast = false)
                if (g != null) {
                    s.gender = g
                    s.source = Source.TAG
                }
            }
            // 2. Gespräch ohne Begleitsatz: neuer Absatz zwischen zwei Reden = Sprecherwechsel; im selben Absatz dicht hintereinander = derselbe
            fun link(a: SpeechSpan, b: SpeechSpan): Boolean? { // true = Wechsel, false = derselbe, null = unklar
                val sep = text.substring(a.close + 1, b.open)
                if (sep.length > 160 || sep.count { it == '\n' } > 1) return null
                if ('\n' in sep) return true
                // Dicht dahinter im selben Absatz: derselbe Sprecher, wenn dazwischen nur der Begleitsatz der ersten Rede steht (beginnt mit Komma)
                // oder gar kein Großbuchstabe (neuer Satz mit anderem Subjekt: unklar)
                return if (sep.length <= 40 && (sep.startsWith(",") || sep.none { it.isUpperCase() })) false else null
            }
            for (i in 1 until spans.size) {
                val prev = spans[i - 1]
                val cur = spans[i]
                if (cur.gender != null || prev.gender == null) continue
                val change = link(prev, cur) ?: continue
                cur.gender = if (change) prev.gender!!.flip() else prev.gender
                cur.source = if (change) Source.FLIP else Source.SAME
            }
            for (i in spans.size - 2 downTo 0) {
                val cur = spans[i]
                val next = spans[i + 1]
                if (cur.gender != null || next.gender == null) continue
                val change = link(cur, next) ?: continue
                cur.gender = if (change) next.gender!!.flip() else next.gender
                cur.source = if (change) Source.FLIP else Source.SAME
            }
        }

        /** Entfernt Anführungszeichen und führende Satzzeichen, damit die Stimme nicht mit einem Komma beginnt. */
        internal fun clean(raw: String, style: QuoteStyle?): String {
            var t = raw
            if (style != null) t = t.filter { it !in style.all }
            t = t.replace('„', ' ').replace('“', ' ').replace('”', ' ').replace('»', ' ').replace('«', ' ')
            t = t.replace(Regex("\\s+"), " ").trim()
            return t.trimStart(',', ';', ':', '.', ' ', '–', '-')
        }
    }
}

/** Absätze und Abschnitte für den Dialogmodus: echte Absatzgrenzen bleiben erhalten (als Zeilenumbruch im Abschnitt). */
object DialogueText {
    /**
     * Absätze eines Rohtextes. Gibt es Leerzeilen (mindestens drei Blöcke), trennen sie die Absätze und einzelne Zeilenumbrüche sind nur
     * Umbrüche der Zeilenbreite; sonst ist jede Zeile ein Absatz (so setzen manche Ausgaben Dialoge).
     */
    fun paragraphs(raw: String): List<String> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = text.split(Regex("\\n[ \\t]*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val parts = if (blocks.size >= 3) {
            // Zeilenumbruch mitten im Absatz = Umbruch der Zeilenbreite (Silbentrennung am Zeilenende wird zusammengesetzt)
            blocks.map { it.replace(Regex("(\\p{L})-\\n(\\p{L})"), "$1$2").replace(Regex("[ \\t]*\\n[ \\t]*"), " ") }
        } else {
            text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return parts.map { Documents.speechText(it) }.filter { p -> p.any { it.isLetterOrDigit() } }.map { p ->
            if (p.last() in ".!?…:;\"“”»«") p else "$p."
        }
    }

    /** Fasst Absätze zu Abschnitten von höchstens [max] Zeichen zusammen (Absätze durch Zeilenumbruch getrennt); Paar = Text und "beginnt mit neuem Absatz". */
    fun chunks(rawParagraphs: List<String>, max: Int): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        val cur = StringBuilder()
        fun emit() {
            if (cur.isNotEmpty()) out += cur.toString() to true
            cur.setLength(0)
        }
        for (paragraph in rawParagraphs.flatMap { paragraphs(it) }) {
            if (paragraph.length > max) {
                emit()
                Speech.chunks(paragraph, max).forEachIndexed { i, piece -> out += piece to (i == 0) }
                continue
            }
            if (cur.isNotEmpty() && cur.length + 1 + paragraph.length > max) emit()
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(paragraph)
        }
        emit()
        return out
    }
}
