package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarksAndKeywordsTest {
    @Test
    fun marksFollowTheRules() {
        val marks = ReadMarks()
        marks.update("content://a", "A.epub", chunk = 12, total = 100, now = 1)
        marks.update("content://b", "B.pdf", chunk = 3, total = 40, now = 2)
        assertEquals(listOf("B.pdf", "A.epub"), marks.all().map { it.name }) // zuletzt gelesen zuerst
        marks.update("content://a", "A.epub", chunk = 30, total = 100, now = 3) // Wechsel zwischen Texten: die andere Marke bleibt
        assertEquals(30, marks.get("content://a")!!.chunk)
        assertEquals(3, marks.get("content://b")!!.chunk)
        marks.update("content://b", "B.pdf", chunk = 0, total = 40, now = 4) // ganz am Anfang: Marke weg
        assertNull(marks.get("content://b"))
        marks.update("content://a", "A.epub", chunk = 99, total = 100, now = 5, finished = true) // Ende: Marke weg
        assertNull(marks.get("content://a"))
        marks.update("content://c", "C.txt", chunk = 40, total = 40, now = 6) // hinter dem letzten Abschnitt
        assertNull(marks.get("content://c"))
    }

    @Test
    fun marksSurviveARestartAndBrokenFilesAreIgnored() {
        val marks = ReadMarks()
        marks.update("content://x/ä", "Bü ch.epub", 7, 50, 123)
        val again = ReadMarks.fromJson(marks.toJson())
        assertEquals(ReadMark("content://x/ä", "Bü ch.epub", 7, 50, 123), again.get("content://x/ä"))
        assertTrue(ReadMarks.fromJson("{kaputt").all().isEmpty())
        assertTrue(ReadMarks.fromJson(null).all().isEmpty())
        assertTrue(ReadMarks.fromJson("").all().isEmpty())
    }

    @Test
    fun restoreIndexAdaptsToAChangedSplit() {
        val mark = ReadMark("u", "n", chunk = 20, total = 100)
        assertEquals(20, ReadMarks.restoreIndex(mark, 100))
        assertEquals(40, ReadMarks.restoreIndex(mark, 200)) // doppelt so viele Abschnitte
        assertEquals(10, ReadMarks.restoreIndex(mark, 50)) // halb so viele Abschnitte
        assertEquals(2, ReadMarks.restoreIndex(ReadMark("u", "n", 50, 100), 5))
        assertEquals(4, ReadMarks.restoreIndex(ReadMark("u", "n", 99, 100), 5)) // bleibt im gültigen Bereich
        assertEquals(0, ReadMarks.restoreIndex(mark, 0))
    }

    private fun spec(alias: String, vararg keywords: String) =
        ToolSpec(alias = alias, server = "s", remoteName = "t", keywords = keywords.map { Keyword(it) })

    @Test
    fun keywordsTriggerTheirToolsAndOnlyAllowedOnes() {
        val tools = listOf(spec("tv-p1", "\\bprogramm\\s*1\\b", "channel one"), spec("tv-off", "fernseher aus"), spec("tv-p2", "programm 2"), spec("weather"))
        val rules = KeywordRules(tools, listOf("tv-p1", "TV-OFF", "weather"))
        assertEquals(listOf("tv-p1"), rules.match("Schalte bitte auf Programm 1 um.").map { it.alias })
        assertEquals(listOf("tv-p1"), rules.match("Please switch to CHANNEL ONE").map { it.alias })
        assertEquals(listOf("tv-off"), rules.match("Fernseher aus!").map { it.alias })
        assertEquals(emptyList(), rules.match("Programm 2 ist nicht freigeschaltet").map { it.alias })
        assertEquals(emptyList(), rules.match("Programm 10 ist zu groß für den Ausdruck"))
        assertEquals(emptyList(), rules.match("   "))
        assertEquals(listOf("tv-p1", "tv-off"), rules.match("Programm 1 und Fernseher aus").map { it.alias })
        assertTrue(KeywordRules(tools, emptyList()).isEmpty)
    }

    @Test
    fun capturedGroupsBecomeArguments() {
        val rules = KeywordRules(listOf(spec("weather", "wetter in (\\w+)(?: (morgen|heute))?")), listOf("weather"))
        assertEquals("Paris|morgen", rules.match("Wie ist das Wetter in Paris morgen?").single().args)
        assertEquals("Berlin", rules.match("wetter in Berlin").single().args)
    }

    @Test
    fun theThrottleBlocksQuickRepeats() {
        val throttle = KeywordThrottle(minIntervalMs = 1000)
        assertTrue(throttle.allow("tv-p1", 0))
        assertFalse(throttle.allow("tv-p1", 500))
        assertTrue(throttle.allow("tv-off", 500)) // anderes Werkzeug
        assertTrue(throttle.allow("tv-p1", 1500))
        throttle.reset()
        assertTrue(throttle.allow("tv-p1", 1600))
    }

    @Test
    fun aliasesReadKeywordsFromTheConfiguration() {
        val registry = ToolRegistry()
        registry.remote["s" to "t"] = ToolSpec(alias = "t", server = "s", remoteName = "t")
        val config = AppJson.parseToJsonElement(
            """{"ok": {"server": "s", "tool": "t", "keywords": ["programm\\s*1", "channel 1"]},
                "single": {"server": "s", "tool": "t", "keywords": "nur einer"},
                "none": {"server": "s", "tool": "t"},
                "bad": {"server": "s", "tool": "t", "keywords": ["(offen"]},
                "wrong": {"server": "s", "tool": "t", "keywords": {"a": 1}},
                "fixed": {"server": "s", "tool": "t", "keywords": [{"pattern": "wetter", "args": ["Paderborn", "morgen"]}, "plain", {"pattern": "x", "args": "eins"}]}}""",
        ) as kotlinx.serialization.json.JsonObject
        registry.addAliases(config)
        assertEquals(listOf(Keyword("programm\\s*1"), Keyword("channel 1")), registry.tools.getValue("ok").keywords)
        assertEquals(listOf(Keyword("nur einer")), registry.tools.getValue("single").keywords)
        assertEquals(emptyList(), registry.tools.getValue("none").keywords)
        assertEquals(
            listOf(Keyword("wetter", listOf("Paderborn", "morgen")), Keyword("plain"), Keyword("x", listOf("eins"))),
            registry.tools.getValue("fixed").keywords,
        )
        assertFalse("bad" in registry.tools)
        assertFalse("wrong" in registry.tools)
        assertTrue(registry.errors.any { "ungültiger Ausdruck" in it && "bad" in it })
        assertTrue(registry.errors.any { "wrong" in it })
    }
}
