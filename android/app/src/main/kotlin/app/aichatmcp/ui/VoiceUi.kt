package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.core.PIPER_PREFIX
import app.aichatmcp.core.Speech
import app.aichatmcp.speech.SpeechOutput

/** Die Sprachen, für die es Standardstimmen gibt (jede Sprache einmal; "English (UK)" teilt sich die Stimmen mit "English"). */
private val VOICE_LANGUAGES = listOf("Deutsch", "English", "Français", "Español", "Italiano", "Japanese")

/** Sprechgeschwindigkeit als Regler (0,8 bis 2,0): 1,0 = normal, die deutschen Stimmen klingen mit 1,2 natürlicher. */
@Composable
fun SpeedSlider(rate: Double, onChange: (Double) -> Unit) {
    Column {
        Text(t("Speech speed: ${"%.2f".format(rate)}×", "Sprechgeschwindigkeit: ${"%.2f".format(rate)}×"), style = MaterialTheme.typography.bodyMedium)
        Slider(value = rate.toFloat(), onValueChange = { onChange((Math.round(it * 20) / 20.0)) }, valueRange = 0.8f..2.0f)
    }
}

/** Eine Zeile mit Auswahl der Stimme und Knopf zum Anhören. */
@Composable
fun VoiceRow(label: String, options: List<String>, selected: String, onPick: (String) -> Unit, onTest: () -> Unit, canTest: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DropdownField(label, options, selected, onPick, Modifier.weight(1f), raw = true)
        OutlinedButton(onClick = onTest, enabled = canTest) { Text(t("Listen", "Anhören")) }
    }
}

/**
 * Die Standardstimmen je Sprache: Erzähler, Frau und Mann. Sie gelten überall: beim Vorlesen und Spielen von Büchern (ein Ich-Erzähler oder eine Spielerfigur spricht
 * mit der Stimme ihres Geschlechts), in der wörtlichen Rede und für Companions ohne eigene Stimme (nach ihrem Geschlecht). [voices] ist der Entwurf der Einstellungen.
 */
@Composable
fun DefaultVoicesSection(vm: ChatViewModel, voices: Map<String, String>, onChange: (Map<String, String>) -> Unit, dialogue: Boolean, onDialogue: (Boolean) -> Unit) {
    var language by remember { mutableStateOf(VOICE_LANGUAGES.firstOrNull { it == vm.persona.language } ?: if (vm.config.uiLanguage == "de") "Deutsch" else "English") }
    var options by remember { mutableStateOf<List<SpeechOutput.VoiceOption>?>(null) } // null = wird noch geladen
    LaunchedEffect(language) {
        options = null
        vm.loadVoiceOptions(language) { options = it }
    }
    val code = Speech.languageTag(language)?.substringBefore('-')?.lowercase().orEmpty()
    val all = options.orEmpty()
    SectionTitle(t("Default voices", "Standardstimmen"))
    Text(
        t("These voices are used everywhere: for reading and playing books, for direct speech and for companions without a voice of their own (by their gender). A first-person narrator or your character speaks with the voice of their gender.",
            "Diese Stimmen gelten überall: beim Vorlesen und Spielen von Büchern, für wörtliche Rede und für Companions ohne eigene Stimme (nach ihrem Geschlecht). Ein Ich-Erzähler oder deine Spielerfigur spricht mit der Stimme ihres Geschlechts."),
        style = MaterialTheme.typography.bodySmall,
    )
    DropdownField(t("Language", "Sprache"), VOICE_LANGUAGES, language, { language = it }, raw = true) // Sprachnamen nie übersetzen
    val default = t("(default voice)", "(Standardstimme)")
    val like = t("(like the narrator)", "(wie Erzähler)")
    listOf(
        "narrator" to t("Narrator", "Erzähler"),
        "female" to t("Female voice (women, female main character)", "Weibliche Stimme (Frauen, weibliche Hauptfigur)"),
        "male" to t("Male voice (men, male main character)", "Männliche Stimme (Männer, männliche Hauptfigur)"),
    ).forEach { (role, label) ->
        val noneLabel = if (role == "narrator") default else like
        val key = "$code/$role"
        val current = voices[key].orEmpty()
        val shown = all.firstOrNull { it.name == current }?.label ?: if (current.isEmpty() || options == null) noneLabel else t("(not available)", "(nicht verfügbar)")
        VoiceRow(
            label, listOf(noneLabel) + all.map { it.label }, shown,
            { picked -> val name = all.firstOrNull { it.label == picked }?.name.orEmpty(); onChange(if (name.isEmpty()) voices - key else voices + (key to name)) },
            onTest = { vm.testCompanionVoice(language, current) }, canTest = true,
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(t("Change the voice for direct speech when reading books", "Beim Vorlesen die Stimme bei wörtlicher Rede wechseln"), modifier = Modifier.weight(1f))
        Switch(checked = dialogue, onCheckedChange = onDialogue)
    }
    val narrator = voices["$code/narrator"].orEmpty()
    if (dialogue && narrator.isNotEmpty() && !narrator.startsWith(PIPER_PREFIX)) {
        Text(
            t("The voice change needs a Piper voice as narrator (the system voice cannot be switched in the middle of a text).",
                "Der Stimmenwechsel braucht eine Piper-Stimme als Erzähler (die Systemstimme lässt sich mitten im Text nicht wechseln)."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
    }
    if (all.none { it.name.startsWith(PIPER_PREFIX) } && options != null) {
        Text(
            t("There is no Piper voice for “$language” yet.", "Für „$language“ ist noch keine Piper-Stimme installiert."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
    }
}
