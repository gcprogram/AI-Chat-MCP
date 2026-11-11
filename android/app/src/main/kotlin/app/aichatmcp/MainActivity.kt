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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import app.aichatmcp.ui.ChatScreen
import app.aichatmcp.ui.CompanionsScreen
import app.aichatmcp.ui.Screen
import app.aichatmcp.ui.SettingsScreen
import app.aichatmcp.ui.ToolsScreen

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
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

@Composable
private fun Root(vm: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.CHAT) }
    BackHandler(enabled = screen != Screen.CHAT) { screen = Screen.CHAT }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
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

    when (screen) {
        Screen.CHAT -> ChatScreen(
            vm, open = { screen = it },
            onExport = { exportChat.launch(ChatLog.fileName(vm.persona.id)) },
            onImport = { importChat.launch("*/*") },
            onMic = ::onMic,
            onUpload = { pickDocument.launch(arrayOf("*/*")) },
        )
        Screen.SETTINGS -> SettingsScreen(
            vm, onBack = { screen = Screen.CHAT }, onPickModel = { pickModel.launch(arrayOf("*/*")) },
            onPickVision = { pickVision.launch(arrayOf("*/*")) }, onPickVisionProj = { pickVisionProj.launch(arrayOf("*/*")) },
            onPickPiper = { pickPiper.launch(null) },
            onPickWhisper = { pickWhisper.launch(null) },
            onRequestNotifications = ::requestNotifications,
            onRequestCalendar = { calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
            onToast = ::toast,
        )
        Screen.COMPANIONS -> CompanionsScreen(
            vm, onBack = { screen = Screen.CHAT }, onImport = { importPersona.launch("*/*") },
            onImportFolder = { pickPersonaFolder.launch(null) },
            onExport = { id -> exportPersonaId = id; exportPersona.launch("$id.json") },
            onImportAvatars = { avatarFolder.launch(null) },
            onPickAvatar = { id -> avatarTarget = id; avatarPick.launch(arrayOf("image/*")) },
        )
        Screen.TOOLS -> ToolsScreen(vm, onBack = { screen = Screen.CHAT }, onImportMcp = { importMcp.launch("*/*") }, onExportMcp = { t -> exportMcpText = t; exportMcp.launch("mcp_servers_phone.json") }, onImportCert = { importCert.launch("*/*") }, onImportKeywords = { pickKeywords.launch(arrayOf("*/*")) })
    }
}
