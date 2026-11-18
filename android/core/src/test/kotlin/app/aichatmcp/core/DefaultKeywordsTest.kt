package app.aichatmcp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Die Standard-Schlüsselwörter der Todo-Werkzeuge: Kleine Modelle (1.7B) beantworten "zeige todos an" sonst frei und erfinden den Inhalt. */
class DefaultKeywordsTest {
    private fun patterns(alias: String): List<Regex> {
        val file = listOf("../app/src/main/assets/default_mcp_servers.json", "app/src/main/assets/default_mcp_servers.json").map(::File).first { it.isFile }
        val aliases = (AppJson.parseToJsonElement(file.readText()) as JsonObject)["aliases"] as JsonObject
        val keywords = (aliases[alias] as JsonObject)["keywords"] as JsonArray
        return keywords.map { Regex((it as JsonPrimitive).content, RegexOption.IGNORE_CASE) }
    }

    private fun listHit(text: String) = patterns("todo-list").any { it.find(text) != null }

    @Test
    fun listKeywordsCatchQuestionsAboutTheTodos() {
        for (text in listOf("Welche Todos haben wir?", "zeige todos an", "Zeig mir die Todos", "Todos", "Aufgabenliste", "Welche Aufgaben stehen an?", "Was steht an? Zeig die Erinnerungen")) {
            assertTrue(listHit(text), text)
        }
    }

    @Test
    fun listKeywordsLeaveOtherSentencesToTheModel() {
        for (text in listOf("Neues Todo: Milch kaufen", "Ich habe heute viele Aufgaben", "Welche Farbe hat der Himmel?", "Stelle den Wecker auf 8:10 morgen", "Ich muss meine Todos noch sortieren")) {
            assertTrue(!listHit(text), text)
        }
    }

    @Test
    fun addKeywordTakesTheTextAfterTheColon() {
        val add = patterns("todo-add").first()
        assertEquals("KI Diktiergerät kaufen am Black friday.", add.find("Neues Todo:  KI Diktiergerät kaufen am Black friday.")!!.groupValues[1])
        assertEquals("Milch", add.find("todo - Milch")!!.groupValues[1])
        assertEquals("Zahnarzt anrufen", add.find("Neue Aufgabe: Zahnarzt anrufen")!!.groupValues[1])
        assertNull(add.find("zeige todos an"))
        assertNull(add.find("Ich habe ein Todo: Milch")) // mitten im Satz: dem Modell überlassen
    }
}
