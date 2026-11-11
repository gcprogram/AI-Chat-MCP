package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryHelpTest {
    private val aliases = """{"todo-add":{"server":"demo","tool":"calculate","params":["expression"],"hint":"Adds a to-do","example":"[[mcp:todo-add|Buy milk]]"},
        "todo-list":{"server":"demo","tool":"get_time","params":[],"hint":"Lists the to-dos","example":"[[mcp:todo-list]]"}}"""

    private fun session(): ChatSession {
        val s = ChatSession(Config(), Persona(name = "T", tools = listOf("todo-add", "todo-list")), demoRegistry(aliases))
        s.addUser("Füge der Liste zu: Milch")
        s.addAssistant("[[mcp:todo-update|Milch]]")
        return s
    }

    @Test
    fun unknownToolErrorListsTheToolsAndAsksForAnImmediateCorrection() {
        val s = session()
        s.addToolResult("todo-update", "Unbekanntes Werkzeug 'todo-update'. Verfügbar: todo-add, todo-list.", false)
        val last = s.wireHistory().last().content
        assertTrue(last.startsWith("[Error from tool call todo-update] Unbekanntes Werkzeug"), last)
        assertTrue("- todo-add: Adds a to-do. Example: [[mcp:todo-add|Buy milk]]" in last, last)
        assertTrue("- todo-list: Lists the to-dos." in last, last)
        assertTrue("Do not apologize" in last && "Sage" !in last, last)
    }

    @Test
    fun otherErrorsAndOldTurnsKeepTheShortForm() {
        val s = session()
        s.addToolResult("todo-add", "Server kaputt", false)
        assertTrue(s.wireHistory().last().content.startsWith("[Fehler bei todo-add] Server kaputt"))
        val t = session()
        t.addToolResult("todo-update", "Unbekanntes Werkzeug 'todo-update'. Verfügbar: todo-add.", false)
        t.addAssistant("Das geht nicht.")
        t.addUser("Neue Frage")
        val old = t.wireHistory().first { it.isTool }.content
        assertFalse("Tools you can call" in old, old) // ältere Runde: nicht noch einmal die ganze Liste
        assertEquals("[Fehler bei todo-update] Unbekanntes Werkzeug 'todo-update'. Verfügbar: todo-add.\nSage Nutzer kurz, dass es nicht geklappt hat, oder versuche den Aufruf im richtigen Format erneut.", old)
    }

    @Test
    fun missingArgumentIsRetryableToo() {
        assertTrue(isRetryableError("Es fehlt: text. Richtiges Format: [[mcp:todo-add|<text>]]"))
        assertTrue(isRetryableError("Unbekannte Taste 'P99'. Erlaubt: P1."))
        assertFalse(isRetryableError("Calendar access is not allowed."))
    }
}
