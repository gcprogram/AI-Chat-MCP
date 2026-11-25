package app.aichatmcp.core

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fernbedienung mit mehreren Profilen (tv, video); "switch" wechselt die Tasten. */
private class MultiProfileRemote : McpClient {
    override val name = "remote"
    var active = "tv"
    var reachable = true
    val sent = mutableListOf<Pair<String, String>>()
    private val schemaSend = AppJson.parseToJsonElement("""{"type":"object","properties":{"code":{"type":"string"}},"required":["code"]}""") as JsonObject
    private val schemaSwitch = AppJson.parseToJsonElement("""{"type":"object","properties":{"profile":{"type":"string"}},"required":["profile"]}""") as JsonObject

    private val profiles = mapOf(
        "tv" to """[{"code":1,"hex":"01","label":"P1","example":"ZDF"},{"code":2,"hex":"02","label":"P2"}]""",
        "video" to """[{"code":5,"hex":"05","label":"Play","example":"start the video"},{"code":250,"hex":"fa","label":"HDMI1","macro":true,"example":"TV to HDMI1"}]""",
    )

    override fun start(timeoutSec: Double) {}
    override fun close() {}

    override fun listTools(timeoutSec: Double) = listOf(
        McpTool("status", "Reports the state.", null, true),
        McpTool("buttons", "Lists the buttons.", null, true),
        McpTool("send", "Sends a code.", schemaSend),
        McpTool("off", "Switches off.", null),
        McpTool("profiles", "Lists profiles.", null, true),
        McpTool("switch", "Selects a profile.", schemaSwitch),
    )

    override fun callTool(name: String, arguments: Map<String, Any?>, timeoutSec: Double): Pair<String, Boolean> {
        if (!reachable) throw McpException("Handy nicht erreichbar")
        return when (name) {
            "buttons" -> profiles.getValue(active) to false
            "profiles" -> profiles.keys.joinToString(",", "[", "]") { """{"name":"$it","active":${it == active},"description":"Remote $it"}""" } to false
            "switch" -> {
                val wanted = arguments["profile"]?.toString().orEmpty().lowercase()
                if (wanted !in profiles) "Error: no profile named '$wanted' (see profiles)" to true
                else { active = wanted; "Profile $wanted active, 2 buttons (see buttons)." to false }
            }
            else -> { sent += name to arguments["code"]?.toString().orEmpty(); "ok $name" to false }
        }
    }
}

class ProfileSwitchTest {
    private val alias = """{"use":{"server":"remote","buttons":true,"hint":"Remote control"}}"""

    private fun setup(): Triple<ToolRegistry, MultiProfileRemote, MutableList<Double>> {
        val fake = MultiProfileRemote()
        val scheduled = mutableListOf<Double>()
        val reg = ToolRegistry().also { it.offScheduler = { ms, _ -> scheduled += ms / 1000.0; ({}) } }
        reg.addServer("remote", fake, exposeRaw = false)
        reg.addAliases(aliasJson(alias))
        assertEquals(emptyList(), reg.errors)
        return Triple(reg, fake, scheduled)
    }

    @Test
    fun profilesAndSwitchAreButtonWordsAndExamplesComeFromTheServer() {
        val (reg, _, _) = setup()
        assertEquals(listOf("P1", "P2", "off", "profiles", "switch"), reg.tools.getValue("use").buttons)
        val section = reg.promptSection(listOf("use"))
        assertTrue("Buttons: P1 (ZDF), P2, off, profiles, switch." in section, section)
        assertTrue("[[mcp:use|switch|<profile>]]" in section && "e.g. [[mcp:use|P1|3.5]]" in section, section)
        assertTrue(section.lines().size < 12)
    }

    @Test
    fun profilesListsTheRemoteControlsForTheModel() {
        val (reg, _, scheduled) = setup()
        val out = reg.run(findCall("[[mcp:use|profiles]]")!!, listOf("use"))
        assertTrue(out.ok, out.text)
        assertEquals(RESULT_MODEL, out.mode)
        assertTrue("- tv (active): Remote tv" in out.text && "- video: Remote video" in out.text, out.text)
        assertEquals(emptyList(), scheduled) // kein Auto-Aus für einen Lesebefehl
    }

    @Test
    fun switchChangesTheButtonsAndReportsThem() {
        val (reg, fake, _) = setup()
        val out = reg.run(findCall("[[mcp:use|switch|VIDEO]]")!!, listOf("use"))
        assertTrue(out.ok, out.text)
        assertEquals(RESULT_MODEL, out.mode)
        assertTrue("Profile video active" in out.text, out.text)
        assertTrue("Buttons: Play (start the video), HDMI1 (TV to HDMI1), off, profiles, switch" in out.text, out.text)
        assertEquals(listOf("Play", "HDMI1", "off", "profiles", "switch"), reg.tools.getValue("use").buttons)
        assertTrue(reg.run(findCall("[[mcp:use|HDMI1]]")!!, listOf("use")).ok)
        assertEquals("send" to "0xfa", fake.sent.last())
        assertFalse(reg.run(findCall("[[mcp:use|P1]]")!!, listOf("use")).ok) // P1 gibt es im neuen Profil nicht
    }

    @Test
    fun unknownOrMissingProfileIsAnErrorAndKeepsTheButtons() {
        val (reg, _, _) = setup()
        val out = reg.run(findCall("[[mcp:use|switch|nope]]")!!, listOf("use"))
        assertFalse(out.ok)
        assertTrue("no profile named" in out.text, out.text)
        assertTrue("Es fehlt: Profilname" in reg.run(findCall("[[mcp:use|switch]]")!!, listOf("use")).text)
        assertEquals(listOf("P1", "P2", "off", "profiles", "switch"), reg.tools.getValue("use").buttons)
    }

    @Test
    fun companionChoicesCanForbidSwitching() {
        val (reg, fake, _) = setup()
        val choices = mapOf("use" to listOf("P1", "off"))
        assertFalse(reg.run(findCall("[[mcp:use|switch|video]]")!!, listOf("use"), choices = choices).ok)
        assertEquals("tv", fake.active)
        val section = reg.promptSection(listOf("use"), choices)
        assertTrue("|switch" !in section && "profiles" !in section, section)
    }

    @Test
    fun refreshKeepsEverythingWhenTheServerIsUnreachable() {
        val (reg, fake, _) = setup()
        val before = reg.tools.getValue("use").buttons
        fake.reachable = false
        try {
            reg.refreshButtons("remote")
            error("Ausnahme erwartet")
        } catch (_: McpException) {
        }
        assertEquals(before, reg.tools.getValue("use").buttons)
        fake.reachable = true
        fake.active = "video"
        assertEquals(listOf("Play", "HDMI1", "off", "profiles", "switch"), reg.refreshButtons("remote"))
    }

    @Test
    fun importMakesNoAliasesForProfilesAndSwitch() {
        val tools = MultiProfileRemote().listTools()
        val entries = ServerImport.aliasEntries("remote", tools, listOf(1 to "P1"), "use-")
        assertEquals(listOf("use", "use-status"), entries.keys.toList())
    }
}
