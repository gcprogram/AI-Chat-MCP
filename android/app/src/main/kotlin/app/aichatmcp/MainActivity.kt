package app.aichatmcp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aichatmcp.core.ChatLog
import app.aichatmcp.ui.BookScreen
import app.aichatmcp.ui.PlayScreen
import app.aichatmcp.ui.ReadScreen
import app.aichatmcp.ui.ChatScreen
import app.aichatmcp.ui.DownloadQuestion
import app.aichatmcp.ui.FolderActions
import app.aichatmcp.ui.ModelsScreen
import app.aichatmcp.ui.OnboardingScreen
import app.aichatmcp.ui.CompanionsScreen
import app.aichatmcp.ui.GalleryScreen
import app.aichatmcp.ui.StartBar
import app.aichatmcp.ui.Screen
import app.aichatmcp.ui.SettingsScreen
import app.aichatmcp.ui.I18n
import app.aichatmcp.ui.LocalUiLanguage
import app.aichatmcp.ui.ToolsScreen
import app.aichatmcp.ui.TranslateScreen
import app.aichatmcp.translate.ExportFormat
import app.aichatmcp.ui.trNow

class MainActivity : ComponentActivity() {
    override fun onStop() {
        android.util.Log.i("AiChat", "Activity onStop, finishing=$isFinishing")
        super.onStop()
    }

    override fun onDestroy() {
        android.util.Log.i("AiChat", "Activity onDestroy, finishing=$isFinishing, changing=$isChangingConfigurations")
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching { I18n.load(assets.open("i18n_en.json").bufferedReader(Charsets.UTF_8).use { it.readText() }) } // Wörterbuch für die englische Oberfläche
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

@Composable
private fun Root(vm: ChatViewModel = viewModel()) {
    SideEffect { I18n.current = vm.config.uiLanguage }
    CompositionLocalProvider(LocalUiLanguage provides vm.config.uiLanguage) { RootContent(vm) }
}

@Composable
private fun RootContent(vm: ChatViewModel) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(if (vm.config.onboardingDone) Screen.CHAT else Screen.INTRO) }
    BackHandler(enabled = screen != Screen.CHAT && screen != Screen.INTRO) { screen = if (screen == Screen.MODELS || screen == Screen.TOOLS) Screen.SETTINGS else if (screen == Screen.COMPANIONS) Screen.GALLERY else Screen.CHAT }

    fun toast(text: String) = Toast.makeText(context, trNow(text), Toast.LENGTH_LONG).show()
    fun read(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull()

    fun nameOf(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "import"

    val importPersona = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> read(u)?.let { text -> toast(vm.importPersona(text, nameOf(u)) ?: "Companion importiert") } ?: toast("Datei nicht lesbar") }
    }
    val importMcp = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> read(u)?.let { text -> vm.saveMcpJson(text)?.let(::toast) ?: toast("Werkzeuge werden neu geladen") } ?: toast("Datei nicht lesbar") }
    }
    var exportMcpText by remember { mutableStateOf("") }
    val exportMcp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { u ->
            runCatching { context.contentResolver.openOutputStream(u)?.use { it.write(exportMcpText.toByteArray(Charsets.UTF_8)) } }
                .onSuccess { toast("Werkzeug-Konfiguration exportiert") }.onFailure { toast("Export fehlgeschlagen: ${it.message}") }
        }
    }
    val importCert = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u ->
            val pem = read(u)
            if (pem != null && "BEGIN CERTIFICATE" in pem) { vm.saveCert(nameOf(u), pem); toast("Zertifikat gespeichert als ${nameOf(u)}") }
            else toast("Keine PEM-Zertifikatsdatei")
        }
    }
    val scope = rememberCoroutineScope()
    var avatarTarget by remember { mutableStateOf("") }
    val avatarFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            scope.launch {
                runCatching { vm.importAvatarFolder(it) }
                    .onSuccess { n -> toast(if (n > 0) "$n Bilder übernommen" else "Keine Bilder im gewählten Ordner (bitte den Ordner avatars wählen)") }
                    .onFailure { e -> toast("Import fehlgeschlagen: ${e.message}") }
            }
        }
    }
    val avatarPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            scope.launch { toast(if (runCatching { vm.setAvatar(avatarTarget, u, nameOf(u)) }.getOrDefault(false)) "Avatar gesetzt" else "Bild nicht lesbar") }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleListening() else toast("Ohne Mikrofon-Berechtigung keine Spracheingabe.")
    }
    fun onMic() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleListening()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast("Ohne Berechtigung für Benachrichtigungen kommt keine Erinnerung.")
    }
    fun requestNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        vm.refreshCalendarPermission()
        if (granted.values.any { !it }) toast("Ohne Kalender-Berechtigung bleiben Termine außen vor.")
    }
    // Ordner: Der Nutzer wählt ihn im Systemdialog (keine Berechtigung "Zugriff auf alle Dateien" nötig)
    val pickModelFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { toast(vm.setModelFolder(it)) } }
    val backupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.backupTo(it, ::toast) } }
    val restoreFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.restoreFrom(it, ::toast) } }
    val folders = remember { FolderActions({ pickModelFolder.launch(null) }, { backupFolder.launch(null) }, { restoreFolder.launch(null) }) }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            // Zugriff dauerhaft behalten, sonst ist die Datei nach einem Neustart nicht mehr lesbar
            runCatching { context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.setLocalModel(u.toString(), nameOf(u))
        }
    }
    // Kein MIME-Filter: CSV und Excel melden die Dateimanager uneinheitlich; das Format erkennt die App am Namen/Inhalt
    val pickKeywords = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::previewKeywordImport) }
    val pickPiper = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::importPiperFolder) }
    val pickWhisper = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::importWhisperFolder) }
    val pickVision = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importVisionModel(it, nameOf(it)) } }
    val pickVisionProj = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importVisionProj(it, nameOf(it)) } }
    // Kein MIME-Filter: manche Dateimanager melden .md/.txt mit unbekanntem Typ. Das Format prüft die App am Namen.
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::loadDocument) }
    val importChat = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> read(u)?.let { vm.importChat(it)?.let(::toast) } ?: toast("Datei nicht lesbar") }
    }
    val pickPersonaFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::importPersonaFolder) }
    var exportPersonaId by remember { mutableStateOf("") }
    val exportPersona = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { u ->
            runCatching { context.contentResolver.openOutputStream(u)?.use { it.write(vm.exportPersona(exportPersonaId).toByteArray(Charsets.UTF_8)) } }
                .onSuccess { toast("Companion exportiert") }.onFailure { toast("Export fehlgeschlagen: ${it.message}") }
        }
    }
    val exportChat = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { u ->
            runCatching { context.contentResolver.openOutputStream(u)?.use { it.write(vm.exportChat().toByteArray(Charsets.UTF_8)) } }
                .onSuccess { toast("Chat gespeichert") }.onFailure { toast("Speichern fehlgeschlagen: ${it.message}") }
        }
    }

    val bookVm: BookViewModel = viewModel()
    val translateVm: TranslateViewModel = viewModel()
    val pickTranslateFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(translateVm::selectFile) }
    val translateExports = ExportFormat.entries.associateWith { format ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri -> uri?.let { translateVm.export(format, it) } }
    }
    LaunchedEffect(translateVm.message) {
        if (translateVm.message.isNotEmpty()) { Toast.makeText(context, translateVm.message, Toast.LENGTH_LONG).show(); translateVm.consumeMessage() }
    }
    fun readTranslation() {
        vm.readTranslation(translateVm.documentName.ifBlank { "Translation" }, translateVm.readableText())
        screen = Screen.CHAT
    }
    // Mitlesen: jeder fertig übersetzte Abschnitt geht an den Vorleser des Chats (auch wenn man inzwischen woanders ist)
    SideEffect {
        translateVm.liveHook = object : LiveReadHook {
            override fun start() = vm.liveReadReset()
            override fun text(name: String, language: String, text: String) = vm.liveReadAppend(name, language, text)
            override fun end() = vm.liveReadEnd()
        }
    }
    var summaryText by remember { mutableStateOf("") }
    val saveSummary = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let { runCatching { context.contentResolver.openOutputStream(it)?.use { out -> out.write(summaryText.toByteArray(Charsets.UTF_8)) } }.onSuccess { toast("Gespeichert") }.onFailure { e -> toast("Speichern fehlgeschlagen: ${e.message}") } }
    }
    var bookSave by remember { mutableStateOf(false) }
    fun writeBytes(uri: Uri, bytes: ByteArray) =
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.onSuccess { toast("Gespeichert") }.onFailure { toast("Speichern fehlgeschlagen: ${it.message}") }
    // Spielbücher (.game) und gespeicherte Spiele (.savegame) liegen im Ordner books; "Andere Datei…" öffnet den Dateidialog (kein MIME-Filter: die Dateimanager kennen die Endungen nicht)
    val pickGameFile = rememberLauncherForActivityResult(OpenDocumentAt()) { uri ->
        uri?.let { u -> toast(read(u)?.let { text -> vm.openGameFile(text) { screen = Screen.CHAT } } ?: "Datei nicht lesbar") }
    }
    val saveMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let { writeBytes(it, bookVm.markdown(bookSave)) } }
    val saveDocx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) { uri ->
        uri?.let { writeBytes(it, bookVm.docx(bookSave)) }
    }

    DownloadQuestion(vm)

    // Das Start-Menü (Chat, Companions, Schreiben, Vorlesen, Spielen) steht unter den Hauptbildschirmen
    val mainScreens = setOf(Screen.CHAT, Screen.GALLERY, Screen.BOOK, Screen.READ, Screen.PLAY, Screen.TRANSLATE)
    Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) {
    when (screen) {
        Screen.GALLERY -> GalleryScreen(vm, open = { screen = it })
        Screen.CHAT -> ChatScreen(
            vm, open = { screen = it },
            onExport = { exportChat.launch(ChatLog.fileName(vm.persona.id)) },
            onImport = { importChat.launch("*/*") },
            onMic = ::onMic,
            onUpload = { pickDocument.launch(arrayOf("*/*")) },
            onTranslate = { doc -> translateVm.selectDocument(doc.name, doc.uri, doc.text); screen = Screen.TRANSLATE },
        )
        Screen.SETTINGS -> SettingsScreen(
            vm, onBack = { screen = Screen.CHAT }, onPickModel = { pickModel.launch(arrayOf("*/*")) },
            onPickVision = { pickVision.launch(arrayOf("*/*")) }, onPickVisionProj = { pickVisionProj.launch(arrayOf("*/*")) },
            onPickPiper = { pickPiper.launch(null) },
            onPickWhisper = { pickWhisper.launch(null) },
            onRequestNotifications = ::requestNotifications,
            onRequestCalendar = { calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
            onToast = ::toast,
            onOpenModels = { screen = Screen.MODELS }, folders = folders, onReplayIntro = { screen = Screen.INTRO }, onOpenTools = { screen = Screen.TOOLS },
        )
        Screen.MODELS -> ModelsScreen(vm, onBack = { screen = Screen.SETTINGS }, folders = folders)
        Screen.BOOK -> BookScreen(
            bookVm, onBack = { screen = Screen.CHAT },
            onSave = { kind, plan -> bookSave = plan; if (kind == "md") saveMarkdown.launch(bookVm.fileName("md")) else saveDocx.launch(bookVm.fileName("docx")) },
        )
        Screen.READ -> ReadScreen(
            vm, onBack = { screen = Screen.CHAT }, onLoad = { pickDocument.launch(arrayOf("*/*")) },
            onSaveText = { fileName, text -> summaryText = text; saveSummary.launch(fileName) },
        )
        Screen.PLAY -> PlayScreen(
            vm, onBack = { screen = Screen.CHAT }, onLoad = { pickDocument.launch(arrayOf("*/*")) }, onStarted = { screen = Screen.CHAT },
            onLoadGamebook = { pickGameFile.launch(vm.bookLocation) },
        )
        Screen.TRANSLATE -> TranslateScreen(
            translateVm, vm, onBack = { screen = Screen.CHAT }, onPick = { pickTranslateFile.launch(arrayOf("*/*")) },
            onExport = { format -> translateExports.getValue(format).launch(translateVm.suggestedFileName(format)) },
            onRead = ::readTranslation, onOpenModels = { screen = Screen.MODELS },
        )
        Screen.INTRO -> OnboardingScreen(vm, onDone = { vm.finishOnboarding(); screen = Screen.CHAT }, folders = folders)
        Screen.COMPANIONS -> CompanionsScreen(
            vm, onBack = { screen = Screen.GALLERY }, onImport = { importPersona.launch("*/*") },
            onImportFolder = { pickPersonaFolder.launch(null) },
            onExport = { id -> exportPersonaId = id; exportPersona.launch("$id.json") },
            onImportAvatars = { avatarFolder.launch(null) },
            onPickAvatar = { id -> avatarTarget = id; avatarPick.launch(arrayOf("image/*")) },
        )
        Screen.TOOLS -> ToolsScreen(vm, onBack = { screen = Screen.SETTINGS }, onImportMcp = { importMcp.launch("*/*") }, onExportMcp = { t -> exportMcpText = t; exportMcp.launch("mcp_servers_phone.json") }, onImportCert = { importCert.launch("*/*") }, onImportKeywords = { pickKeywords.launch(arrayOf("*/*")) })
    }
    }
    if (screen in mainScreens) StartBar(screen) { screen = it }
    }

}
