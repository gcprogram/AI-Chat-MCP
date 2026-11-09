package app.aichatmcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aichatmcp.core.AppJson
import app.aichatmcp.core.DefaultServerFactory
import app.aichatmcp.core.RESULT_BOTH
import app.aichatmcp.core.ToolRegistry
import app.aichatmcp.core.findCall
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Organizer auf dem echten Gerät: neue Standard-Aliase werden einmal in eine bestehende mcp_servers.json übernommen (ein vom Nutzer
 * gelöschter kommt nicht wieder), und `todo-add`/`todo-list`/`todo-done` laufen über die Registry mit den mitgelieferten Assets.
 */
@RunWith(AndroidJUnit4::class)
class OrganizerOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext // die App: ihre Assets enthalten die Standardkonfiguration

    private fun asset(name: String) = runCatching { context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()

    @Test
    fun newDefaultsAreMergedOnceAndTodoAliasesWork() {
        val dir = File(context.cacheDir, "organizer-test").apply { deleteRecursively(); mkdirs() }
        try {
            File(dir, "mcp_servers.json").writeText(
                """{"servers": {"weather": {"builtin": "weather", "raw": false}}, "aliases": {"weather": {"server": "weather", "tool": "forecast", "params": ["place"]}}}""",
            )
            val storage = AppStorage(dir, ::asset)
            storage.ensureDefaults()
            val merged = AppJson.parseToJsonElement(storage.mcpJson()) as JsonObject
            val aliases = merged["aliases"] as JsonObject
            assertTrue("weather bleibt", "weather" in aliases && "weather" in (merged["servers"] as JsonObject))
            assertTrue("organizer fehlt", "organizer" in (merged["servers"] as JsonObject))
            assertTrue(listOf("todo-add", "todo-list", "todo-done").all { it in aliases })

            // der Nutzer löscht todo-list: beim nächsten Start kommt es nicht zurück
            val without = JsonObject(merged.toMutableMap().apply { put("aliases", JsonObject(aliases.toMutableMap().apply { remove("todo-list") })) })
            storage.saveMcpJson(AppJson.encodeToString(JsonObject.serializer(), without))
            storage.ensureDefaults()
            assertFalse("todo-list" in (AppJson.parseToJsonElement(storage.mcpJson()) as JsonObject)["aliases"] as JsonObject)

            val registry = ToolRegistry()
            registry.startFromConfig(storage.mcpJson(), DefaultServerFactory(todos = storage.todos))
            val allowed = listOf("todo-add", "todo-done")
            val added = registry.run(findCall("[[mcp:todo-add|Buy milk|tomorrow]]")!!, allowed)
            assertTrue(added.text, added.ok && added.text.startsWith("Added to-do 1: Buy milk (due tomorrow)"))
            assertEquals(RESULT_BOTH, added.mode)
            assertEquals(listOf("Buy milk"), storage.todos.open().map { it.text })
            val done = registry.run(findCall("[[mcp:todo-done|milk]]")!!, allowed)
            assertTrue(done.text, done.ok && done.text.startsWith("Marked as done: 1."))
            assertTrue(storage.todos.open().isEmpty())
            registry.shutdown()
        } finally {
            dir.deleteRecursively()
        }
    }
}
