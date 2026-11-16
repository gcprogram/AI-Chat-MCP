package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.core.Config
import app.aichatmcp.core.Providers
import kotlinx.coroutines.launch

/** Der Bearbeitungsstand der Verbindung zu einem Chat-Server im Netz (Einstellungen und Einführung teilen sich die Eingabefelder). */
class ServerDraft(c: Config) {
    var provider by mutableStateOf(c.provider.ifEmpty { Providers.forUrl(c.baseUrl).id })
    var url by mutableStateOf(c.baseUrl)
    var key by mutableStateOf(c.apiKey)
    var model by mutableStateOf(c.model)
    var models by mutableStateOf(c.models)
    var status by mutableStateOf("")

    fun apply(c: Config): Config = c.copy(provider = provider, baseUrl = url.trim(), apiKey = key.trim(), model = model, models = models)

    /** Anbieter wechseln: Adresse voreinstellen, den gemerkten Schlüssel des neuen Anbieters laden (der des alten bleibt gemerkt). */
    fun select(vm: ChatViewModel, id: String) {
        if (id == provider) return
        vm.rememberProviderKey(provider, key)
        provider = id
        val p = Providers.byId(id)
        if (p.baseUrl.isNotEmpty()) url = p.baseUrl
        key = vm.providerKey(id)
        models = emptyList()
        model = ""
        status = ""
    }
}

/** Anbieter, Adresse, Schlüssel und Modell (Liste vom Anbieter laden) für ein Chat-Modell im Netz. */
@Composable
fun ServerFields(vm: ChatViewModel, d: ServerDraft) {
    val scope = rememberCoroutineScope()
    val provider = Providers.byId(d.provider)
    DropdownField(
        t("Provider", "Anbieter"), Providers.all.map { it.title }, provider.title,
        { title -> Providers.all.firstOrNull { it.title == title }?.let { d.select(vm, it.id) } },
    )
    TextSetting(t("Server URL (including /v1)", "Server-URL (inkl. /v1)"), d.url, { d.url = it }, keyboard = KeyboardType.Uri)
    if (Providers.insecureWarning(d.url)) {
        Text(
            t("This address is not encrypted (http) and not in your home network: your key and messages would travel in plain text. Use https.",
                "Diese Adresse ist nicht verschlüsselt (http) und liegt nicht im Heimnetz: Schlüssel und Nachrichten gingen im Klartext durchs Netz. Nimm https."),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        )
    }
    TextSetting(
        if (provider.needsKey) t("API key", "API-Schlüssel") else t("API key (only if the server needs one)", "API-Schlüssel (nur wenn der Server einen verlangt)"),
        d.key, { d.key = it }, secret = true,
    )
    if (provider.keyUrl.isNotEmpty()) Text(t("Get a key: ${provider.keyUrl}", "Schlüssel besorgen: ${provider.keyUrl}"), style = MaterialTheme.typography.bodySmall)
    if (provider.id == Providers.LMSTUDIO) {
        Text(
            t("Works at home (the address of the PC running LM Studio or Ollama, same Wi-Fi) and away from home (a VPN, or a server reachable from the internet; use https there).",
                "Funktioniert zu Hause (Adresse des PCs mit LM Studio oder Ollama, gleiches WLAN) und unterwegs (per VPN oder einen aus dem Internet erreichbaren Server; dort https verwenden)."),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            d.status = tNow("Loading models…", "Lade Modelle…")
            scope.launch {
                vm.fetchModels(d.url, d.key).fold(
                    onSuccess = { list ->
                        d.models = list
                        if (d.model !in list && list.isNotEmpty()) d.model = list.first()
                        d.status = tNow("${list.size} models found", "${list.size} Modelle gefunden")
                    },
                    onFailure = { d.status = it.message ?: tNow("Error", "Fehler") },
                )
            }
        }) { Text(t("Load models", "Modelle laden")) }
        Text(d.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
    DropdownField(t("Model", "Modell"), d.models.ifEmpty { listOfNotNull(d.model.ifEmpty { null }) }, d.model, { d.model = it }, Modifier.fillMaxWidth())
}
