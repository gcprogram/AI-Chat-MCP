package app.aichatmcp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.core.BACKEND_LOCAL
import app.aichatmcp.core.BACKEND_SERVER
import app.aichatmcp.core.LANGUAGE_DE
import app.aichatmcp.core.LANGUAGE_EN
import app.aichatmcp.core.ModelCatalog

private const val STEPS = 7

/**
 * Einführung beim ersten Start (und auf Wunsch aus den Einstellungen): was die App kann und was man einrichten sollte, Schritt für Schritt,
 * jeder Schritt überspringbar. Modelle werden hier gleich heruntergeladen (WLAN-Hinweis und Platzprüfung wie sonst auch).
 */
@Composable
fun OnboardingScreen(vm: ChatViewModel, onDone: () -> Unit, onRequestAllFiles: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    var online by rememberSaveable { mutableStateOf(vm.config.backend == BACKEND_SERVER && vm.config.localModel.isEmpty() && vm.config.onboardingDone) }
    val server = remember { ServerDraft(vm.config) }
    BackHandler(enabled = step > 0) { step-- }
    LaunchedEffect(step) { vm.refreshModelFiles(); vm.refreshVoices() }

    fun next() {
        if (step == 1 && online) vm.saveConfig(server.apply(vm.config).copy(backend = BACKEND_SERVER)).also { vm.rememberProviderKey(server.provider, server.key) }
        if (step == 1 && !online && vm.config.localModel.isNotEmpty()) vm.saveConfig(vm.config.copy(backend = BACKEND_LOCAL))
        if (step < STEPS - 1) step++ else onDone()
    }

    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(progress = { (step + 1f) / STEPS }, modifier = Modifier.fillMaxWidth())
        Text(
            t("Step ${step + 1} of $STEPS", "Schritt ${step + 1} von $STEPS"), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        Column(Modifier.padding(horizontal = 16.dp)) { DownloadHost(vm) } // Fortschritt und Rückfragen bleiben beim Scrollen sichtbar
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (step) {
                0 -> Welcome(vm)
                1 -> ChatModelStep(vm, online, { online = it }, server, onRequestAllFiles)
                2 -> {
                    StepTitle(t("Speak to your companion", "Sprich mit deinem Companion"))
                    Text(
                        t("The microphone button types what you say. Whisper recognises speech offline in many languages, without Google and without internet. Without it the app uses the speech recognition of your phone (Google).",
                            "Der Mikrofon-Knopf schreibt, was du sagst. Whisper erkennt Sprache offline in vielen Sprachen, ohne Google und ohne Internet. Ohne Whisper nutzt die App die Spracherkennung deines Handys (Google)."),
                    )
                                CatalogList(vm, ModelCatalog.whisper)
                }
                3 -> VoicesStep(vm)
                4 -> {
                    StepTitle(t("Pictures (optional)", "Bilder (optional)"))
                    Text(
                        t("Chat models only read text. A small image model describes a picture you share, and the description goes to your companion. It is loaded only while describing.",
                            "Chat-Modelle lesen nur Text. Ein kleines Bildmodell beschreibt ein Bild, das du teilst, und die Beschreibung geht an deinen Companion. Es wird nur während der Beschreibung geladen."),
                    )
                                CatalogList(vm, ModelCatalog.vision)
                }
                5 -> {
                    StepTitle(t("Where your files live", "Wo deine Dateien liegen"))
                    Text(
                        t("Models, companions and chats get their own folders: AIChatMCP/models, AIChatMCP/companions and AIChatMCP/chats. You can copy companions and chats between phone and PC there.",
                            "Modelle, Companions und Chats haben eigene Ordner: AIChatMCP/models, AIChatMCP/companions und AIChatMCP/chats. Dort kannst du Companions und Chats zwischen Handy und PC kopieren."),
                    )
                    FolderInfo(vm, onRequestAllFiles)
                    Text(
                        t("Calendar, notifications and the microphone are asked for only when you use those features (Settings > Organizer, and the microphone button).",
                            "Kalender, Benachrichtigungen und Mikrofon werden erst gefragt, wenn du die Funktion nutzt (Einstellungen > Organizer und der Mikrofon-Knopf)."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> Ready(vm)
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) TextButton(onClick = { step-- }) { Text(t("Back", "Zurück")) } else Spacer(Modifier.height(0.dp))
            Spacer(Modifier.weight(1f))
            if (step in 1..4) TextButton(onClick = { if (step < STEPS - 1) step++ }) { Text(t("Skip", "Überspringen")) }
            Button(onClick = ::next) { Text(if (step == STEPS - 1) t("Start chatting", "Los geht's") else t("Next", "Weiter")) }
        }
    }
}

@Composable
private fun StepTitle(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Welcome(vm: ChatViewModel) {
    StepTitle(t("Welcome", "Willkommen"))
    Text(
        t("This app is a chat with AI companions that run on your phone, with a personality and a picture you choose. Here is what it can do:",
            "Diese App ist ein Chat mit KI-Companions, die auf deinem Handy laufen, mit einer Persönlichkeit und einem Bild, die du wählst. Das kann sie:"),
    )
    listOf(
        t("Chat offline: a small model runs on the phone, or connect to your own server or to OpenAI, Anthropic and Gemini.", "Offline chatten: ein kleines Modell läuft auf dem Handy, oder verbinde dich mit deinem Server oder mit OpenAI, Anthropic und Gemini."),
        t("Talk and listen: speech input with Whisper, voices for replies and for reading documents aloud.", "Sprechen und zuhören: Spracheingabe mit Whisper, Stimmen für Antworten und zum Vorlesen von Dokumenten."),
        t("Organize: to-dos, calendar, alarms and timers, plus a daily update.", "Organisieren: Todos, Kalender, Wecker und Timer, dazu ein Tagesupdate."),
        t("Read: upload text, Word, PDF or EPUB files and have them read to you; describe pictures.", "Lesen: Text-, Word-, PDF- oder EPUB-Dateien hochladen und vorlesen lassen; Bilder beschreiben."),
        t("Tools: connect your own tools (MCP), for example a remote control or web search.", "Werkzeuge: eigene Werkzeuge (MCP) anbinden, zum Beispiel eine Fernbedienung oder die Websuche."),
    ).forEach { Text("•  $it") }
    Text(
        t("The next steps set up what you need. You can skip any step and change everything later in Settings.",
            "Die nächsten Schritte richten ein, was du brauchst. Du kannst jeden Schritt überspringen und später alles in den Einstellungen ändern."),
    )
    Text(t("Language of the app", "Sprache der App"), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = vm.config.uiLanguage == LANGUAGE_EN, onClick = { vm.saveConfig(vm.config.copy(uiLanguage = LANGUAGE_EN)) }, label = { androidx.compose.material3.Text("English") })
        FilterChip(selected = vm.config.uiLanguage == LANGUAGE_DE, onClick = { vm.saveConfig(vm.config.copy(uiLanguage = LANGUAGE_DE)) }, label = { androidx.compose.material3.Text("Deutsch") })
    }
}

@Composable
private fun ChatModelStep(vm: ChatViewModel, online: Boolean, onOnline: (Boolean) -> Unit, server: ServerDraft, onRequestAllFiles: () -> Unit) {
    StepTitle(t("Choose a chat model", "Wähle ein Chat-Modell"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !online, onClick = { onOnline(false) }, label = { Text(t("On this phone", "Auf diesem Handy")) })
        FilterChip(selected = online, onClick = { onOnline(true) }, label = { Text(t("Online / own server", "Online / eigener Server")) })
    }
    if (!online) {
        Text(
            t("The model runs completely on your phone, offline and private. It is a big download, so use Wi-Fi. Qwen3 4B is the best choice for most recent phones; take the 1.7B model if your phone is older or has little memory. Gemmasutra is for role play and stories.",
                "Das Modell läuft komplett auf deinem Handy, offline und privat. Es ist ein großer Download, also nutze das WLAN. Qwen3 4B ist für die meisten neueren Handys die beste Wahl; nimm das 1.7B-Modell, wenn dein Handy älter ist oder wenig Speicher hat. Gemmasutra ist für Rollenspiel und Geschichten."),
        )
        CatalogList(vm, ModelCatalog.chat)
        Text(
            t("You can find more models later (Settings > App > Models and downloads), including any GGUF model from Hugging Face.",
                "Weitere Modelle findest du später unter Einstellungen > App > Modelle und Downloads, auch jedes GGUF-Modell von Hugging Face."),
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            t("Use a model on the internet or on your own computer. Pick a provider; the address is filled in. Enter your API key, load the list of models and choose one. Works at home and away.",
                "Nutze ein Modell im Internet oder auf deinem eigenen Rechner. Wähle einen Anbieter, die Adresse wird eingetragen. Gib deinen API-Schlüssel ein, lade die Modellliste und wähle eines. Funktioniert zu Hause und unterwegs."),
        )
        ServerFields(vm, server)
    }
}

@Composable
private fun VoicesStep(vm: ChatViewModel) {
    StepTitle(t("Voices", "Stimmen"))
    Text(
        t("Your companion can read its answers aloud. Piper voices work offline and sound natural; the system voices come from Google text-to-speech. Download one voice for your language, you can choose a voice per companion later.",
            "Dein Companion kann seine Antworten vorlesen. Piper-Stimmen funktionieren offline und klingen natürlich; die Systemstimmen kommen von der Google-Sprachausgabe. Lade eine Stimme für deine Sprache, je Companion wählst du später die Stimme."),
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(t("Read answers aloud", "Antworten vorlesen"), modifier = Modifier.weight(1f))
        Switch(checked = vm.config.speakReplies, onCheckedChange = { vm.saveConfig(vm.config.copy(speakReplies = it)) })
    }
    val preferred = if (vm.config.uiLanguage == LANGUAGE_DE) "de" else "en"
    CatalogList(vm, ModelCatalog.piper.sortedByDescending { it.language == preferred })
    OutlinedButton(onClick = vm::installVoices) { Text(t("Install system voices…", "Systemstimmen installieren…")) }
    Text(
        t("The button opens the voice installer of your phone's text-to-speech engine.",
            "Der Knopf öffnet die Stimmen-Installation der Sprachausgabe deines Handys."),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Ready(vm: ChatViewModel) {
    StepTitle(t("You are ready", "Du bist startklar"))
    val c = vm.config
    val chat = when {
        c.backend == BACKEND_LOCAL && c.localModel.isNotEmpty() -> t("Chat model: ${c.localModelName} (on this phone)", "Chat-Modell: ${c.localModelName} (auf diesem Handy)")
        c.backend != BACKEND_LOCAL && c.model.isNotEmpty() -> t("Chat model: ${c.model} (online)", "Chat-Modell: ${c.model} (online)")
        else -> t("No chat model yet. Choose one in Settings > Chat model before you start.", "Noch kein Chat-Modell. Wähle vor dem Start eines unter Einstellungen > Chat-Modell.")
    }
    Text("•  $chat", color = if (chat.startsWith("No") || chat.startsWith("Noch")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    Text("•  " + if (vm.whisperModels.isNotEmpty()) t("Voice input: Whisper", "Spracheingabe: Whisper") else t("Voice input: phone speech recognition", "Spracheingabe: Spracherkennung des Handys"))
    Text("•  " + if (vm.piperVoices.isNotEmpty()) t("Voices: ${vm.piperVoices.size} Piper voice(s)", "Stimmen: ${vm.piperVoices.size} Piper-Stimme(n)") else t("Voices: system voice", "Stimmen: Systemstimme"))
    Text("•  " + if (vm.visionAvailable) t("Image model: installed", "Bildmodell: installiert") else t("Image model: none", "Bildmodell: keines"))
    Text(
        t("Tip: the menu (⋮) in the chat opens Companions, Tools and Settings. Create your own companions with a personality and a picture.",
            "Tipp: Das Menü (⋮) im Chat öffnet Companions, Werkzeuge und Einstellungen. Lege eigene Companions mit Persönlichkeit und Bild an."),
    )
}
