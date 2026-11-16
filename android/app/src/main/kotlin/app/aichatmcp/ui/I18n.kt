package app.aichatmcp.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import app.aichatmcp.core.AppJson
import app.aichatmcp.core.LANGUAGE_DE
import app.aichatmcp.core.LANGUAGE_EN
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/*
 * Sprache der Oberfläche: Englisch (Standard) oder Deutsch. Die älteren Texte der App stehen im Quelltext deutsch; ein Wörterbuch
 * (assets/i18n_en.json: genaue Texte und Muster für Texte mit eingesetzten Werten) macht daraus bei englischer Oberfläche Englisch.
 * Alles, was in `Text(...)` steht, wird dabei übersetzt (unser `Text` ersetzt den von Material), Texte ohne Eintrag bleiben deutsch.
 * Neue Texte schreibt man mit `t("English", "Deutsch")`. Die Antwortsprache der Companions hängt nicht daran.
 */
object I18n {
    /** Aktuelle Sprache für Code außerhalb von Compose (Toasts); die Oberfläche selbst liest [LocalUiLanguage]. */
    @Volatile
    var current: String = LANGUAGE_EN

    private var exact: Map<String, String> = emptyMap()
    private var patterns: List<Pair<Regex, String>> = emptyList()
    private val cache = ConcurrentHashMap<String, String>()

    fun load(json: String) {
        val root = AppJson.parseToJsonElement(json) as JsonObject
        exact = (root["exact"] as? JsonObject).orEmpty().mapValues { (it.value as JsonPrimitive).content }
        patterns = (root["patterns"] as? JsonArray).orEmpty().mapNotNull { el ->
            val pair = el as? JsonArray ?: return@mapNotNull null
            runCatching { Regex((pair[0] as JsonPrimitive).content, RegexOption.DOT_MATCHES_ALL) to (pair[1] as JsonPrimitive).content }.getOrNull()
        }
        cache.clear()
    }

    /** Übersetzt einen deutschen Quelltext ins Englische (Zeilenumbrüche und Leerzeichen am Rand bleiben); ohne Eintrag bleibt er unverändert. */
    fun toEnglish(text: String): String {
        if (text.length < 2 || exact.isEmpty()) return text
        return cache.getOrPut(text) { translate(text) }
    }

    private fun translate(text: String): String {
        val core = text.trim()
        if (core.isEmpty()) return text
        val lead = text.substring(0, text.indexOf(core))
        val tail = text.substring(lead.length + core.length)
        val whole = lookup(core)
        if (whole != null) return lead + whole + tail
        // zusammengesetzte Meldungen aus mehreren Sätzen: Satz für Satz
        val sentences = core.split(Regex("(?<=[.!?])\\s+"))
        if (sentences.size > 1) {
            val parts = sentences.map { lookup(it) }
            if (parts.any { it != null }) return lead + sentences.indices.joinToString(" ") { parts[it] ?: sentences[it] } + tail
        }
        // erste Zeile und Rest getrennt ("Titel\nDetails")
        val lines = core.split('\n')
        if (lines.size > 1) {
            val parts = lines.map { lookup(it.trim()) }
            if (parts.any { it != null }) return lead + lines.indices.joinToString("\n") { parts[it] ?: lines[it] } + tail
        }
        return text
    }

    private fun lookup(s: String): String? {
        exact[s]?.let { return it }
        for ((regex, replacement) in patterns) {
            if (regex.matches(s)) return regex.replace(s, replacement)
        }
        return null
    }
}

/** Sprache der Oberfläche für die Texte in Compose (damit sie sich beim Umschalten neu zeichnen). */
val LocalUiLanguage = compositionLocalOf { LANGUAGE_EN }

/** Deutscher Quelltext -> Text in der gewählten Sprache. */
@Composable
fun tr(text: String): String = if (LocalUiLanguage.current == LANGUAGE_DE) text else I18n.toEnglish(text)

/** Neue Texte: englisch und deutsch nebeneinander. */
@Composable
fun t(en: String, de: String): String = if (LocalUiLanguage.current == LANGUAGE_DE) de else en

/** Wie [t], außerhalb von Compose (Toasts). */
fun tNow(en: String, de: String): String = if (I18n.current == LANGUAGE_DE) de else en

/** Deutscher Quelltext außerhalb von Compose in die gewählte Sprache. */
fun trNow(text: String): String = if (I18n.current == LANGUAGE_DE) text else I18n.toEnglish(text)

/** Ersetzt `androidx.compose.material3.Text` in diesem Paket: gleiche Aufrufe, der Text wird in die Oberflächensprache übersetzt. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        tr(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily, textAlign = textAlign,
        overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, style = style,
    )
}

/** Mit eigener Gliederung (Werkzeugaufrufe im Chat): die Teile übersetzt der Aufrufer, dieser Text geht unverändert durch. */
@Composable
fun Text(
    text: androidx.compose.ui.text.AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(text, modifier = modifier, color = color, style = style)
}
