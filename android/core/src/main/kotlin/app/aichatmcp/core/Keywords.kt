package app.aichatmcp.core

/** Ein Treffer: Dieses Werkzeug soll mit diesen Argumenten laufen (Argumente im Format eines Markers, durch | getrennt). */
data class KeywordHit(val alias: String, val args: String, val keyword: String)

/**
 * Schlüsselwörter für Werkzeuge: Im Alias-Eintrag von mcp_servers.json steht `"keywords": ["regulärer Ausdruck", ...]`. Kommt ein
 * Ausdruck in einem Text vor (Eingabe des Nutzers, Antwort des Modells, vorgelesener Text, Bildbeschreibung), löst das Werkzeug
 * deterministisch aus, ohne dass das Modell es schreiben muss. Gruppen im Ausdruck werden zu den Argumenten des Werkzeugs.
 */
class KeywordRules(tools: Collection<ToolSpec>, allowed: Collection<String>) {
    private class Rule(val alias: String, val source: String, val regex: Regex)

    private val rules: List<Rule>

    init {
        val ok = allowed.map { normalizeName(it) }.toSet()
        rules = tools.filter { it.alias in ok && it.keywords.isNotEmpty() }
            .flatMap { spec -> spec.keywords.map { Rule(spec.alias, it, Regex(it, RegexOption.IGNORE_CASE)) } }
    }

    val isEmpty: Boolean get() = rules.isEmpty()

    /** Alle Werkzeuge, deren Ausdrücke im Text vorkommen (je Werkzeug höchstens einmal, in der Reihenfolge der Konfiguration). */
    fun match(text: String): List<KeywordHit> {
        if (rules.isEmpty() || text.isBlank()) return emptyList()
        val hits = LinkedHashMap<String, KeywordHit>()
        for (rule in rules) {
            if (rule.alias in hits) continue
            val m = rule.regex.find(text) ?: continue
            val args = m.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString("|")
            hits[rule.alias] = KeywordHit(rule.alias, args, rule.source)
        }
        return hits.values.toList()
    }
}

/** Verhindert, dass dasselbe Werkzeug in kurzer Zeit mehrfach auslöst (z. B. dasselbe Stichwort in aufeinanderfolgenden Abschnitten). */
class KeywordThrottle(private val minIntervalMs: Long = 30_000) {
    private val last = HashMap<String, Long>()

    fun allow(alias: String, now: Long): Boolean {
        val before = last[alias]
        if (before != null && now - before < minIntervalMs) return false
        last[alias] = now
        return true
    }

    fun reset() = last.clear()
}
