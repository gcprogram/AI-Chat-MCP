package app.aichatmcp.core

/**
 * Wie die Entscheidungen nach einer Szene aussehen: [CLASSIC] = Handlung / Risiko / Umweg, [STRANDS] = der Spieler wählt, welcher von bis zu drei
 * Handlungssträngen (die nächsten Szenen des Buches) als Nächstes gespielt wird, [MIXED] = abwechselnd.
 */
enum class DecisionMode {
    CLASSIC, STRANDS, MIXED;

    companion object {
        fun parse(value: String?): DecisionMode = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CLASSIC
    }
}

/** Die Arten von Entscheidung im Solo-Abenteuer. */
enum class DecisionKind {
    /** folgt der Handlung des Buches */
    STORY,

    /** weicht von der Handlung ab und kann Verletzung oder Tod bringen (zählt als falsche Entscheidung) */
    RISK,

    /** eine Variante, die erzählt wird und danach zur Handlung zurückführt */
    DETOUR,

    /** wählt einen Handlungsstrang: die Szene [Decision.scene] kommt als Nächstes (ohne Risiko) */
    STRAND,
}

/** Ein Vorschlag nach einer Szene; [relations] sind die Punkte für Hauptfiguren (positiv oder negativ), die die Entscheidung bringt. */
data class Decision(val kind: DecisionKind, val text: String, val relations: Map<String, Int> = emptyMap(), val scene: Int = -1)

enum class SoloEnd {
    /** die Spielerfigur ist gestorben (dritte Verwundung oder tödlicher Ausgang) */
    DEAD,

    /** drei falsche Entscheidungen an einem Tag: die Geschichte weicht zu stark ab */
    LOST,

    /** die letzte Szene ist gespielt */
    WON,
}

/** Der Spielstand eines Solo-Abenteuers. Beziehungen: Punkte je Hauptfigur (-5 bis +5). */
data class SoloState(
    val wounds: Int = 0,
    val wrongToday: Int = 0,
    val scenesPlayed: Int = 0,
    val day: Int = 1,
    val relations: Map<String, Int> = emptyMap(),
    val end: SoloEnd? = null,
)

/** Was eine Entscheidung für die Spielerfigur bedeutet. */
enum class Consequence { NONE, WOUNDED, KILLED, DIED_OF_WOUNDS, LOST }

/** Ergebnis einer Entscheidung: neuer Spielstand, Folge und ob (und mit welchem Ergebnis) danach eine Nacht vergeht. */
data class Resolution(val state: SoloState, val consequence: Consequence, val night: Boolean, val healed: Boolean)

/**
 * Regeln des Solo-Abenteuers (Entscheidung nach jeder Szene). Die App legt die Folgen fest, nicht das Modell (kleine Modelle erfinden sonst
 * Verletzungen, Heilungen und Tode nach Laune): eine Entscheidung folgt der Handlung, weicht gefährlich ab (Verwundung, Tod, falsche Entscheidung)
 * oder ist ein Umweg zurück zur Handlung. Die dritte Verwundung tötet, pro Nacht heilt eine, drei falsche Entscheidungen an einem Tag verlieren die Geschichte.
 */
object Solo {
    const val MAX_WOUNDS = 3
    const val MAX_WRONG_PER_DAY = 3
    const val DEFAULT_SCENES_PER_DAY = 3
    const val MAX_RELATION = 5

    /** Bei einer riskanten Entscheidung: Würfel 0 bis 99, darunter [FATAL_BELOW] tödlich, darunter [WOUND_BELOW] eine Verwundung, sonst knapp entkommen. */
    const val FATAL_BELOW = 10
    const val WOUND_BELOW = 60

    /** Wie viele Stränge zur Wahl stehen (die nächsten noch ungespielten Szenen des Buches). */
    const val MAX_STRANDS = 3

    /** Die nächsten ungespielten Szenen in der Reihenfolge des Buches (höchstens [max]); die erste ist die, die der Handlung folgt. */
    fun candidates(sceneCount: Int, played: Set<Int>, max: Int = MAX_STRANDS): List<Int> = (0 until sceneCount).filter { it !in played }.take(max)

    /** Soll diese Entscheidungsrunde Stränge anbieten? [round] = Zahl der bisher getroffenen Entscheidungen; Stränge brauchen mindestens zwei zur Wahl. */
    fun useStrands(mode: DecisionMode, round: Int, candidateCount: Int): Boolean = candidateCount >= 2 && when (mode) {
        DecisionMode.CLASSIC -> false
        DecisionMode.STRANDS -> true
        DecisionMode.MIXED -> round % 2 == 1
    }

    private val STRAND_LINE = Regex("""(?m)^\s*[*#_\s\-]*(\d)\s*[.:)]\s*[*_]*\s*(.+)$""")

    /**
     * Liest die Strang-Entscheidungen ("1: …", "2: …"): die Zeile n gehört zur n-ten Szene in [candidates]. Fehlt eine (oder ist sie zu kurz), steht ihre
     * Entscheidung trotzdem zur Wahl ("Ich wende mich … zu"), damit das Spiel weitergeht. [titles] = Überschriften der Kandidaten.
     */
    fun parseStrands(text: String, candidates: List<Int>, titles: List<String>, german: Boolean): List<Decision> {
        val byNumber = HashMap<Int, String>()
        for (m in STRAND_LINE.findAll(text)) {
            val n = m.groupValues[1].toInt()
            val body = m.groupValues[2].replace(Regex("""\s+"""), " ").trim().trim('*', '_')
            if (n in 1..candidates.size && n !in byNumber && body.length >= 8) byNumber[n] = body
        }
        return candidates.mapIndexed { i, scene ->
            val title = titles.getOrNull(i).orEmpty()
            Decision(
                DecisionKind.STRAND,
                byNumber[i + 1] ?: if (german) "Ich wende mich dem Abschnitt „$title“ zu." else "I turn to the part \"$title\".",
                scene = scene,
            )
        }
    }

    /** Wendet [decision] an. [roll] ist der Würfel (0 bis 99, zum Testen vorgegeben), [scenesPerDay]: nach so vielen Szenen vergeht eine Nacht. */
    fun resolve(state: SoloState, decision: Decision, roll: Int, scenesPerDay: Int = DEFAULT_SCENES_PER_DAY): Resolution {
        var s = state.copy(relations = applyRelations(state.relations, decision.relations))
        var consequence = Consequence.NONE
        if (decision.kind == DecisionKind.RISK) {
            s = s.copy(wrongToday = s.wrongToday + 1)
            when {
                roll < FATAL_BELOW -> {
                    consequence = Consequence.KILLED
                    s = s.copy(end = SoloEnd.DEAD)
                }
                roll < WOUND_BELOW -> {
                    val wounds = s.wounds + 1
                    s = s.copy(wounds = wounds)
                    if (wounds >= MAX_WOUNDS) {
                        consequence = Consequence.DIED_OF_WOUNDS
                        s = s.copy(end = SoloEnd.DEAD)
                    } else consequence = Consequence.WOUNDED
                }
            }
            if (s.end == null && s.wrongToday >= MAX_WRONG_PER_DAY) {
                consequence = Consequence.LOST
                s = s.copy(end = SoloEnd.LOST)
            }
        }
        s = s.copy(scenesPlayed = s.scenesPlayed + 1)
        var night = false
        var healed = false
        val perDay = if (scenesPerDay > 0) scenesPerDay else DEFAULT_SCENES_PER_DAY
        if (s.end == null && s.scenesPlayed % perDay == 0) {
            night = true
            healed = s.wounds > 0
            s = s.copy(wounds = (s.wounds - 1).coerceAtLeast(0), wrongToday = 0, day = s.day + 1)
        }
        return Resolution(s, consequence, night, healed)
    }

    private fun applyRelations(old: Map<String, Int>, delta: Map<String, Int>): Map<String, Int> {
        if (delta.isEmpty()) return old
        val out = old.toMutableMap()
        for ((name, d) in delta) out[name] = ((out[name] ?: 0) + d).coerceIn(-MAX_RELATION, MAX_RELATION)
        return out
    }

    private val LINE = Regex("""(?im)^\s*[*#_\s\-]*(?:\d[.)]\s*)?(story|handlung|risiko|risk|umweg|variante|detour)\s*[*_]*\s*[:：\-–]\s*(.+)$""")
    private val TAGS = Regex("""\[([^\]]{1,80})]""")
    private val RELATION = Regex("""([\p{L}][\p{L}'’ \-]{0,30}?)\s*([+-])\s?([1-2])""")

    /**
     * Liest die Vorschläge der Antwort ("STORY: …", "RISIKO: …", "UMWEG: …", je mit optionalen Beziehungspunkten "[Name +1]"). Höchstens einer je Art,
     * in der Reihenfolge Story, Risiko, Umweg. Beziehungspunkte gelten nur für bekannte Hauptfiguren ([known], leer = alle); je Entscheidung höchstens ±2.
     */
    fun parseDecisions(text: String, known: List<String>): List<Decision> {
        val byKind = LinkedHashMap<DecisionKind, Decision>()
        for (m in LINE.findAll(text)) {
            val kind = when (m.groupValues[1].lowercase()) {
                "story", "handlung" -> DecisionKind.STORY
                "risiko", "risk" -> DecisionKind.RISK
                else -> DecisionKind.DETOUR
            }
            if (kind in byKind) continue
            var body = m.groupValues[2].trim()
            val relations = LinkedHashMap<String, Int>()
            for (tag in TAGS.findAll(body)) {
                for (r in RELATION.findAll(tag.groupValues[1])) {
                    val name = r.groupValues[1].trim().removeSuffix(",").trim()
                    val canonical = if (known.isEmpty()) name else known.firstOrNull { it.equals(name, ignoreCase = true) } ?: continue
                    val value = r.groupValues[3].toInt() * (if (r.groupValues[2] == "-") -1 else 1)
                    relations[canonical] = value
                }
            }
            body = body.replace(TAGS, "").replace(Regex("""\s+"""), " ").trim().trim('*', '_')
            if (body.length >= 8) byKind[kind] = Decision(kind, body, relations)
        }
        return DecisionKind.entries.mapNotNull { byKind[it] }
    }

    /** Wenn das Modell keine Vorschläge liefert, bleibt wenigstens der Weg der Handlung (1 bis 3 Entscheidungen). */
    fun fallbackStory(german: Boolean) = Decision(DecisionKind.STORY, if (german) "Ich folge dem Lauf der Geschichte." else "I follow the course of the story.")

    /** Die Vorschläge für die Anzeige: immer die Weg-der-Handlung-Entscheidung dabei, sonst nur, was das Modell geliefert hat. */
    fun withStory(list: List<Decision>, german: Boolean): List<Decision> = if (list.any { it.kind == DecisionKind.STORY }) list else listOf(fallbackStory(german)) + list

    /** Eine Zeile für den Chat: was die Entscheidung bewirkt hat (die App hat es festgelegt, nicht das Modell). */
    fun resultLine(decision: Decision, r: Resolution, german: Boolean): String {
        val parts = mutableListOf<String>()
        when (r.consequence) {
            Consequence.WOUNDED -> parts += if (german) "Verwundet (${r.state.wounds} von $MAX_WOUNDS)" else "Wounded (${r.state.wounds} of $MAX_WOUNDS)"
            Consequence.KILLED -> parts += if (german) "Tödlicher Ausgang" else "Fatal outcome"
            Consequence.DIED_OF_WOUNDS -> parts += if (german) "Dritte Verwundung: die Figur stirbt" else "Third wound: the character dies"
            Consequence.LOST -> parts += if (german) "Dritte falsche Entscheidung an einem Tag: die Geschichte ist verloren" else "Third wrong decision in one day: the story is lost"
            Consequence.NONE -> if (decision.kind == DecisionKind.RISK) parts += if (german) "Knapp entkommen" else "A narrow escape"
        }
        for ((name, d) in decision.relations) parts += "$name ${if (d > 0) "+" else ""}$d"
        if (r.night) parts += if (german) (if (r.healed) "Eine Nacht vergeht: eine Verwundung heilt" else "Eine Nacht vergeht") else (if (r.healed) "A night passes: a wound heals" else "A night passes")
        return parts.joinToString(" · ")
    }

    /** Die Anweisung an den Spielleiter für die Antwort auf die gewählte Entscheidung: die Folge steht fest (verbindlich), er erzählt sie nur. */
    fun outcomeNote(decision: Decision, r: Resolution, german: Boolean): String {
        val s = r.state
        val core = when (r.consequence) {
            Consequence.KILLED -> if (german) "Die Entscheidung weicht von der Handlung ab und endet tödlich: Die Spielerfigur stirbt. Beschreibe den Tod passend zur Szene in 3 bis 5 Sätzen. Das ist das Ende der Geschichte: setze sie nicht fort und führe keine neue Szene ein."
            else "The decision departs from the plot and ends fatally: the player's character dies. Describe the death to fit the scene in 3 to 5 sentences. This is the end of the story: do not continue it and do not introduce a new scene."
            Consequence.DIED_OF_WOUNDS -> if (german) "Die Entscheidung weicht von der Handlung ab, und die dritte Verwundung ist tödlich: Die Spielerfigur stirbt an ihren Verletzungen. Beschreibe, wie es dazu kommt, passend zur Szene in 3 bis 5 Sätzen. Das ist das Ende der Geschichte: setze sie nicht fort und führe keine neue Szene ein."
            else "The decision departs from the plot and the third wound is fatal: the player's character dies of the injuries. Describe how it happens to fit the scene in 3 to 5 sentences. This is the end of the story: do not continue it and do not introduce a new scene."
            Consequence.LOST -> if (german) "Die Entscheidung weicht ab (die dritte falsche an diesem Tag): Die Geschichte ist verloren, sie weicht zu stark vom Buch ab. Beschreibe in 3 bis 5 Sätzen, wie die Ereignisse entgleiten und die Geschichte ohne den Plan der Handlung endet. Setze sie nicht fort und führe keine neue Szene ein."
            else "The decision departs from the plot (the third wrong one today): the story is lost, it strays too far from the book. Describe in 3 to 5 sentences how events slip away and the story ends without the plot. Do not continue it and do not introduce a new scene."
            Consequence.WOUNDED -> if (german) "Die Entscheidung weicht von der Handlung ab und die Spielerfigur wird verwundet (Verwundungen jetzt ${s.wounds} von $MAX_WOUNDS). Beschreibe die Verletzung konkret und passend zur Szene in 2 bis 4 Sätzen, dann führe zurück in die Handlung (neue Szene unten)."
            else "The decision departs from the plot and the player's character is wounded (wounds now ${s.wounds} of $MAX_WOUNDS). Describe the injury concretely and to fit the scene in 2 to 4 sentences, then lead back to the plot (new scene below)."
            Consequence.NONE -> when (decision.kind) {
                DecisionKind.RISK -> if (german) "Die Entscheidung weicht von der Handlung ab, aber die Spielerfigur entkommt unverletzt. Beschreibe das knappe Entkommen in 2 bis 4 Sätzen, dann führe zurück in die Handlung (neue Szene unten)."
                else "The decision departs from the plot, but the player's character escapes unharmed. Describe the narrow escape in 2 to 4 sentences, then lead back to the plot (new scene below)."
                DecisionKind.DETOUR -> if (german) "Der Spieler wählt eine Variante. Beschreibe sie anschaulich in 2 bis 4 Sätzen und führe sie so weiter, dass sie in die Handlung zurückmündet (neue Szene unten). Niemand wird dadurch verletzt."
                else "The player chooses a variant. Describe it vividly in 2 to 4 sentences and lead it so that it flows back into the plot (new scene below). Nobody is hurt by it."
                DecisionKind.STRAND -> if (german) "Der Spieler wählt diesen Handlungsstrang. Beschreibe in 1 bis 2 Sätzen, wie die Spielerfigur sich ihm zuwendet, und erzähle dann die neue Szene (unten), als erlebte die Spielerfigur sie selbst, auch wenn im Buch eine andere Figur sie erlebt; erwähne diesen Wechsel nicht. Niemand wird dadurch verletzt."
                else "The player chooses this strand. Describe in 1 to 2 sentences how the player's character turns to it, then tell the new scene (below) as if the player's character experienced it, even if another character does in the book; do not mention this switch. Nobody is hurt by it."
                DecisionKind.STORY -> if (german) "Der Spieler folgt der Handlung. Beschreibe das Ergebnis kurz in 1 bis 3 Sätzen, dann führe in die neue Szene ein (unten)."
                else "The player follows the plot. Describe the result briefly in 1 to 3 sentences, then introduce the new scene (below)."
            }
        }
        val extra = buildString {
            if (r.night && s.end == null) append(if (german) " Danach vergeht eine Nacht${if (r.healed) ": eine Verwundung heilt (jetzt ${s.wounds} von $MAX_WOUNDS)" else ""}; erwähne den neuen Tag in einem Satz." else " Then a night passes${if (r.healed) ": a wound heals (now ${s.wounds} of $MAX_WOUNDS)" else ""}; mention the new day in one sentence.")
            if (decision.relations.isNotEmpty()) {
                val list = decision.relations.entries.joinToString(", ") { (n, d) -> "$n ${if (d > 0) "+" else ""}$d" }
                append(if (german) " Die Entscheidung verändert Beziehungen ($list): lass es in den Reaktionen dieser Figuren spürbar werden." else " The decision changes relationships ($list): let it show in the reactions of those characters.")
            }
        }
        return core + extra
    }

    /** Der Spielstand als Text für den Spielleiter-Prompt (Verwundungen, Tag, Beziehungen mit Bedeutung). */
    fun stateText(s: SoloState, german: Boolean): String {
        val rel = s.relations.filter { it.value != 0 }.entries.joinToString(", ") { (n, v) -> "$n ${if (v > 0) "+" else ""}$v (${relationMeaning(v, german)})" }
        return if (german) buildString {
            append("Tag ${s.day}. Verwundungen: ${s.wounds} von $MAX_WOUNDS")
            if (s.wounds > 0) append(" (die Spielerfigur ist angeschlagen: erwähne gelegentlich Schmerz oder einen Verband)")
            append(".")
            if (rel.isNotEmpty()) append(" Beziehungen: $rel.")
        } else buildString {
            append("Day ${s.day}. Wounds: ${s.wounds} of $MAX_WOUNDS")
            if (s.wounds > 0) append(" (the player's character is hurt: occasionally mention pain or a bandage)")
            append(".")
            if (rel.isNotEmpty()) append(" Relationships: $rel.")
        }
    }

    private fun relationMeaning(v: Int, german: Boolean): String = when {
        v >= 3 -> if (german) "vertraut der Spielerfigur" else "trusts the player's character"
        v >= 1 -> if (german) "wohlwollend" else "friendly"
        v <= -3 -> if (german) "feindselig" else "hostile"
        else -> if (german) "misstrauisch" else "wary"
    }

    /** Statuszeile über dem Chat: Verwundungen als Punkte, Tag, falsche Entscheidungen des Tages, Beziehungen. */
    fun statusLine(s: SoloState, german: Boolean): String {
        val hearts = "●".repeat(s.wounds) + "○".repeat((MAX_WOUNDS - s.wounds).coerceAtLeast(0))
        val rel = s.relations.filter { it.value != 0 }.entries.joinToString(" · ") { (n, v) -> "$n ${if (v > 0) "+" else ""}$v" }
        return buildString {
            append(if (german) "Verwundungen $hearts · Tag ${s.day} · falsche Entscheidungen heute ${s.wrongToday}/$MAX_WRONG_PER_DAY" else "Wounds $hearts · day ${s.day} · wrong decisions today ${s.wrongToday}/$MAX_WRONG_PER_DAY")
            if (rel.isNotEmpty()) append(" · ").append(rel)
        }
    }
}
