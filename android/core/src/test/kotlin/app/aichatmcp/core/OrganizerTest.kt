package app.aichatmcp.core

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganizerTest {
    // Montag, 9. November 2026
    private val today = LocalDate.of(2026, 11, 9)

    private fun store(): TodoStore = TodoStore(File(kotlin.io.path.createTempDirectory("todos").toFile(), "todos.json"))

    private fun server(s: TodoStore = store()) = OrganizerServer("organizer", s) { today }

    private fun call(server: OrganizerServer, tool: String, vararg args: Pair<String, String>) = server.callTool(tool, args.toMap(), 5.0)

    @Test
    fun dateWordsUnderstandShortFormsInBothLanguages() {
        assertEquals(today, DateWords.parse("today", today))
        assertEquals(today, DateWords.parse("Heute", today))
        assertEquals(today.plusDays(1), DateWords.parse("tomorrow", today))
        assertEquals(today.plusDays(1), DateWords.parse("morgen", today))
        assertEquals(today.plusDays(2), DateWords.parse("day after tomorrow", today))
        assertEquals(today.plusDays(2), DateWords.parse("übermorgen", today))
        assertEquals(LocalDate.of(2026, 11, 13), DateWords.parse("friday", today))
        assertEquals(LocalDate.of(2026, 11, 13), DateWords.parse("am Freitag", today))
        assertEquals(LocalDate.of(2026, 11, 16), DateWords.parse("monday", today)) // heute ist Montag: der nächste, nie heute
        assertEquals(today.plusDays(3), DateWords.parse("in 3 days", today))
        assertEquals(LocalDate.of(2026, 12, 24), DateWords.parse("2026-12-24", today))
        assertEquals(LocalDate.of(2026, 12, 24), DateWords.parse("24.12.", today))
        assertEquals(LocalDate.of(2027, 11, 2), DateWords.parse("2.11.", today)) // schon vorbei: nächstes Jahr
        assertEquals(LocalDate.of(2026, 12, 24), DateWords.parse("24.12.26", today))
        assertEquals(LocalDate.of(2026, 11, 9), DateWords.parse("9.11.", today)) // heute zählt noch
    }

    @Test
    fun badDatesAreReportedInEnglishForTheModel() {
        val e = assertFailsWith<ToolError> { DateWords.parse("someday", today) }
        assertTrue("not understood" in e.message!!)
        assertTrue("does not exist" in assertFailsWith<ToolError> { DateWords.parse("31.2.2026", today) }.message!!)
        assertNull(DateWords.parseOptional("", today))
        assertNull(DateWords.parseOptional("none", today))
        assertNull(DateWords.parseOptional(null, today))
        assertEquals(today.plusDays(1), DateWords.parseOptional("tomorrow", today))
    }

    @Test
    fun todosArePersistedAndNumberedStably() {
        val file = File(kotlin.io.path.createTempDirectory("todos").toFile(), "todos.json")
        val a = TodoStore(file)
        val one = a.add("Milch kaufen", null, today)
        val two = a.add("Steuer", today.plusDays(1), today)
        assertEquals(listOf(1, 2), listOf(one.id, two.id))
        a.complete("1")
        assertEquals(3, a.add("Neu", null, today).id) // Ids werden nicht neu vergeben, auch wenn eines erledigt ist
        val b = TodoStore(file) // neu geladen
        assertEquals(listOf("Steuer", "Neu"), b.open().map { it.text }) // mit Datum zuerst
        assertEquals(3, b.all().size)
        assertTrue(b.all().first { it.id == 1 }.done)
        assertTrue(b.remove(3))
        assertFalse(b.remove(3))
    }

    @Test
    fun brokenFileDoesNotCrash() {
        val file = File(kotlin.io.path.createTempDirectory("todos").toFile(), "todos.json")
        file.writeText("{kaputt")
        val s = TodoStore(file)
        assertTrue(s.all().isEmpty())
        assertEquals(1, s.add("Wieder da", null, today).id)
    }

    @Test
    fun serverAddsListsAndCompletes() {
        val s = server()
        assertEquals("No open to-dos." to false, call(s, "todo_list"))
        val added = call(s, "todo_add", "text" to "Buy milk", "due" to "tomorrow")
        assertEquals("Added to-do 1: Buy milk (due tomorrow)." to false, added)
        assertEquals("Added to-do 2: Call the dentist." to false, call(s, "todo_add", "text" to "Call the dentist"))
        assertEquals("1. Buy milk (due tomorrow)\n2. Call the dentist" to false, call(s, "todo_list"))
        assertEquals("Marked as done: 2. Call the dentist." to false, call(s, "todo_done", "item" to "dentist")) // Textteil
        assertEquals("1. Buy milk (due tomorrow)" to false, call(s, "todo_list"))
        assertEquals("Marked as done: 1. Buy milk." to false, call(s, "todo_done", "item" to "#1"))
        assertEquals("No open to-dos." to false, call(s, "todo_list"))
    }

    @Test
    fun serverErrorsGoBackToTheModel() {
        val s = server()
        call(s, "todo_add", "text" to "Buy milk")
        call(s, "todo_add", "text" to "Buy bread")
        assertEquals("The text of the to-do is missing." to true, call(s, "todo_add", "text" to " "))
        assertTrue(call(s, "todo_add", "text" to "x", "due" to "someday").let { it.second && "not understood" in it.first })
        val ambiguous = call(s, "todo_done", "item" to "buy")
        assertTrue(ambiguous.second && "More than one" in ambiguous.first && "1. Buy milk" in ambiguous.first)
        assertEquals("There is no open to-do number 9." to true, call(s, "todo_done", "item" to "9"))
        assertEquals("No open to-do matches 'zzz'." to true, call(s, "todo_done", "item" to "zzz"))
        assertTrue(call(s, "todo_done", "item" to "").second)
        assertEquals("Unbekanntes Werkzeug: x" to true, call(s, "x"))
    }

    @Test
    fun overdueIsShownAsSuch() {
        val s = server()
        val store = store()
        val srv = OrganizerServer("organizer", store) { today }
        store.add("Alt", today.minusDays(2), today)
        assertEquals("1. Alt (due 2026-11-07, overdue)", call(srv, "todo_list").first)
        s.listTools(1.0) // Server ohne Aufrufe bleibt lauffähig
    }

    @Test
    fun writingToolsAreNotMarkedReadOnly() {
        val tools = server().listTools(1.0).associateBy { it.name }
        assertTrue(tools.getValue("todo_list").readOnly)
        assertFalse(tools.getValue("todo_add").readOnly)
        assertFalse(tools.getValue("todo_done").readOnly)
    }

    @Test
    fun factoryBuildsTheOrganizerOnlyWithAStore() {
        val config = kotlinx.serialization.json.buildJsonObject { put("builtin", kotlinx.serialization.json.JsonPrimitive("organizer")) }
        assertTrue(DefaultServerFactory(todos = store()).create("organizer", config) is OrganizerServer)
        assertFailsWith<McpException> { DefaultServerFactory().create("organizer", config) }
    }

    @Test
    fun dailyUpdateListsOverdueFirstAndShortensLongLists() {
        val todos = listOf(
            Todo(1, "Milch kaufen"), Todo(2, "Steuer", today.minusDays(4).toString()), Todo(3, "Zahnarzt anrufen", today.toString()),
            Todo(4, "Geschenk", today.plusDays(1).toString()), Todo(5, "Urlaub planen", "2026-12-24"),
        )
        val text = DailyUpdate.text(todos, today, german = true)
        assertEquals(
            "Tagesupdate · Montag, 09.11.2026\nOffene Todos (5):\n2. Steuer · überfällig seit 05.11.\n3. Zahnarzt anrufen · heute\n" +
                "4. Geschenk · morgen\n5. Urlaub planen · 24.12.\n1. Milch kaufen",
            text,
        )
        val english = DailyUpdate.text(todos.take(3), today, german = false)
        assertTrue(english.startsWith("Daily update · Monday, 2026-11-09\nOpen to-dos (3):\n2. Steuer · overdue since 2026-11-05"))
        assertEquals("Tagesupdate · Montag, 09.11.2026\nKeine offenen Todos.", DailyUpdate.text(emptyList(), today, true))
        val many = (1..11).map { Todo(it, "Aufgabe $it") }
        val long = DailyUpdate.text(many, today, true)
        assertTrue(long.endsWith("… und 3 weitere."))
        assertEquals(8, long.lines().count { Regex("^\\d+\\. ").containsMatchIn(it) })
    }

    @Test
    fun personaAndConfigCarryTheDailyUpdateSettings() {
        assertFalse(Persona().dailyUpdate)
        assertTrue(Persona.parse("""{"name": "Selena", "daily_update": true}""").getOrThrow().dailyUpdate)
        assertTrue(Persona(name = "S", dailyUpdate = true).toJson().contains("\"daily_update\": true"))
        val c = Config(briefingDays = mapOf("secretary" to "2026-11-09"))
        val back = AppJson.decodeFromString(Config.serializer(), AppJson.encodeToString(Config.serializer(), c))
        assertEquals("2026-11-09", back.briefingDays["secretary"])
        assertTrue(Config().briefingDays.isEmpty())
        assertTrue(Persona(language = "Deutsch").isGerman())
        assertFalse(Persona(language = "English").isGerman())
    }
}
