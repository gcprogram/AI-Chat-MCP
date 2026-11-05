package app.aichatmcp.core

import kotlin.math.sqrt

/**
 * Erkennt die Sprache eines Textes über die Buchstabenhäufigkeit (Kosinus-Ähnlichkeit zu Referenzprofilen) und die Schrift
 * (Kana = Japanisch). Gedacht fürs Vorlesen: Die Lesesprache richtet sich nach dem Text, nicht nach dem Companion.
 */
object LanguageDetect {
    /** [tag] ist ein Sprach-Tag (de-DE), [confidence] der Abstand zur zweitbesten Sprache (0 bis 1, grob). */
    data class Guess(val tag: String, val confidence: Double)

    private const val MIN_LETTERS = 60
    private const val MIN_MARGIN = 0.004

    // Relative Buchstabenhäufigkeiten in Prozent (übliche Referenzwerte je Sprache; Akzentbuchstaben getrennt gezählt).
    private val PROFILES: Map<String, Map<Char, Double>> = mapOf(
        "en-US" to freq(
            "e12.7 t9.1 a8.2 o7.5 i7.0 n6.7 s6.3 h6.1 r6.0 d4.3 l4.0 c2.8 u2.8 m2.4 w2.4 f2.2 g2.0 y2.0 p1.9 b1.5 v1.0 k0.8 j0.15 x0.15 q0.1 z0.07",
        ),
        "de-DE" to freq(
            "e16.4 n9.8 i7.6 s7.3 r7.0 a6.5 t6.2 d5.1 h4.8 u4.2 l3.4 c3.1 g3.0 m2.5 o2.5 b1.9 w1.9 f1.7 k1.5 z1.1 p0.8 v0.7 j0.27 y0.04 x0.03 q0.02 ä0.5 ö0.3 ü0.6 ß0.3",
        ),
        "fr-FR" to freq(
            "e14.7 s7.9 a7.6 i7.5 t7.2 n7.1 r6.6 u6.3 l5.5 o5.4 d3.7 c3.3 m3.0 p3.0 é1.9 v1.6 q1.4 f1.1 g1.0 b0.9 h0.7 j0.6 à0.5 x0.4 è0.3 y0.3 ê0.2 z0.1 ç0.1 k0.05 w0.04",
        ),
        "es-ES" to freq(
            "e13.7 a12.5 o8.7 s8.0 r6.9 n6.7 i6.2 d5.9 l5.0 c4.7 t4.6 u3.9 m3.2 p2.5 g1.0 q0.9 y0.9 v0.9 b1.4 h0.7 f0.7 z0.5 j0.4 ñ0.3 x0.2 á0.5 é0.4 í0.7 ó0.8 ú0.2 k0.01 w0.01",
        ),
        "it-IT" to freq(
            "e11.8 a11.7 i11.3 o9.8 n6.9 l6.5 r6.4 t5.6 s5.0 c4.5 d3.7 p3.0 u3.0 m2.5 v2.1 g1.6 h1.6 f1.2 z1.0 b0.9 q0.5 à0.6 è0.4 ì0.1 ò0.1 ù0.1",
        ),
    )

    private fun freq(spec: String): Map<Char, Double> =
        spec.trim().split(' ').associate { it[0] to it.substring(1).toDouble() }

    private val NORMS: Map<String, Double> = PROFILES.mapValues { (_, p) -> sqrt(p.values.sumOf { it * it }) }

    /** Null, wenn zu wenig Text da ist oder keine Sprache klar vorne liegt (dann gilt die Sprache des Companions). */
    fun detect(text: String): Guess? {
        val sample = if (text.length > 20_000) text.substring(0, 20_000) else text
        var kana = 0
        var han = 0
        val counts = HashMap<Char, Int>()
        var letters = 0
        for (ch in sample) {
            when {
                ch in '぀'..'ヿ' -> kana++
                ch in '一'..'鿿' -> han++
                ch.isLetter() -> {
                    letters++
                    val c = ch.lowercaseChar()
                    counts[c] = (counts[c] ?: 0) + 1
                }
            }
        }
        if (kana >= 8 && kana * 10 >= letters + kana + han) return Guess("ja-JP", 1.0)
        if (letters < MIN_LETTERS) return null
        var best = "" to 0.0
        var second = 0.0
        for ((tag, profile) in PROFILES) {
            var dot = 0.0
            var norm = 0.0
            for ((c, n) in counts) {
                val share = n.toDouble() / letters
                dot += share * 100.0 * (profile[c] ?: 0.0)
                norm += (share * 100.0) * (share * 100.0)
            }
            val score = dot / (sqrt(norm) * NORMS.getValue(tag) + 1e-9)
            if (score > best.second) {
                second = best.second
                best = tag to score
            } else if (score > second) {
                second = score
            }
        }
        val margin = best.second - second
        return if (margin >= MIN_MARGIN) Guess(best.first, margin) else null
    }
}
