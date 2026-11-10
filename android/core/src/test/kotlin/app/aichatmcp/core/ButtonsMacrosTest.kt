package app.aichatmcp.core

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fernbedienung im Speicher: Werkzeuge status/buttons/send/off wie die Broadcast-Remote. */
private class FakeRemote(private val buttons: String = BUTTONS) : McpClient {
    override val name = "remote"
    val sent = mutableListOf<Pair<String, String>>()
    var buttonCalls = 0
    var failOff = false

    override fun start(timeoutSec: Double) {}
    override fun close() {}

    override fun listTools(timeoutSec: Double) = listOf(
        McpTool("status", "Reports the state.", null, true),
        McpTool("buttons", "Lists the buttons.", null, true),
        McpTool("send", "Sends a code.", AppJson.parseToJsonElement("""{"type":"object","properties":{"code":{"type":"string"}},"required":["code"]}""") as JsonObject),
        McpTool("off", "Switches off. More text.", null),
    )

    override fun callTool(name: String, arguments: Map<String, Any?>, timeoutSec: Double): Pair<String, Boolean> {
        if (name == "buttons") { buttonCalls++; return buttons to false }
        sent += name to arguments["code"]?.toString().orEmpty()
        return if (name == "off" && failOff) "kaputt" to true else "ok $name" to false
    }

    companion object {
        const val BUTTONS = """[{"code":1,"hex":"01","label":"P1"},{"code":2,"hex":"02","label":"P2"},{"code":11,"hex":"0b","label":"Wave"},{"code":17,"hex":"11"}]"""
    }
}

class ButtonsMacrosTest {
    private fun registry(aliases: String, remote: FakeRemote = FakeRemote()): Triple<ToolRegistry, FakeRemote, MutableList<Long>> {
        val sleeps = mutableListOf<Long>()
        val reg = ToolRegistry().also { it.sleeper = { ms -> sleeps += ms } }
        reg.addServer("remote", remote, exposeRaw = false)
        reg.addAliases(aliasJson(aliases))
        return Triple(reg, remote, sleeps)
    }

    @Test
    fun buttonByLabelNeedsOnlyServerAndLabel() {
        val (reg, fake, _) = registry(
            """{"tv-p1":{"server":"remote","button":"P1"},"tv-wave":{"server":"remote","button":"wave"},
               "tv-17":{"server":"remote","button":"0x11"},"tv-off":{"server":"remote","tool":"off"}}""",
        )
        assertEquals(emptyList(), reg.errors)
        assertEquals(1, fake.buttonCalls) // die Tastenliste wird nur einmal gelesen
        val out = reg.run(findCall("[[mcp:tv-p1]]")!!, listOf("tv-p1"))
        assertEquals(listOf(true, RESULT_DISCARD, "P1"), listOf(out.ok, out.mode, out.status))
        reg.run(findCall("[[mcp:tv-wave]]")!!, listOf("tv-wave"))
        reg.run(findCall("[[mcp:tv-17]]")!!, listOf("tv-17"))
        assertEquals(listOf("send" to "0x01", "send" to "0x0b", "send" to "0x11"), fake.sent)
    }

    @Test
    fun unknownButtonNamesTheKnownOnes() {
        val (reg, _, _) = registry("""{"tv-p9":{"server":"remote","button":"P9"}}""")
        assertEquals(emptyList(), reg.available())
        val message = reg.errors.single()
        assertTrue("P9" in message && "p1" in message && "wave" in message, message)
    }

    private val tvAlias = """{"tv":{"server":"remote","buttons":true,"hint":"TV remote","example":"[[mcp:tv|P1]]"}}"""

    @Test
    fun oneAliasForAllButtonsWithTheButtonAsArgument() {
        val (reg, fake, _) = registry(tvAlias)
        assertEquals(emptyList(), reg.errors)
        assertEquals(listOf("P1", "P2", "Wave", "0x11", "off"), reg.tools.getValue("tv").buttons)
        for (text in listOf("[[mcp:tv|P2]]", "[[mcp:tv|wave]]", "[[mcp:tv|0x11]]", "[[mcp:tv|off]]", "[[mcp:tv|button=P1]]")) {
            val out = reg.run(findCall(text)!!, listOf("tv"))
            assertTrue(out.ok, "$text: ${out.text}")
        }
        assertEquals(listOf("send" to "0x02", "send" to "0x0b", "send" to "0x11", "off" to "", "send" to "0x01"), fake.sent)
        assertEquals("tv: P2", reg.run(findCall("[[mcp:tv|P2]]")!!, listOf("tv")).status)
    }

    @Test
    fun macroCanPressButtonsOfAButtonSet() {
        val (reg, fake, _) = registry("""{"tv":{"server":"remote","buttons":true},"demo":{"macro":["tv|P1",2,"tv|off"]}}""")
        assertEquals(emptyList(), reg.errors)
        assertTrue(reg.run(findCall("[[mcp:demo]]")!!, listOf("demo")).ok)
        assertEquals(listOf("send" to "0x01", "off" to ""), fake.sent)
    }

    @Test
    fun unknownOrMissingButtonIsReportedWithTheAllowedOnes() {
        val (reg, fake, _) = registry(tvAlias)
        val out = reg.run(findCall("[[mcp:tv|P99]]")!!, listOf("tv"))
        assertFalse(out.ok)
        assertTrue("P99" in out.text && "P1, P2, Wave" in out.text, out.text)
        assertTrue("Es fehlt: button" in reg.run(findCall("[[mcp:tv]]")!!, listOf("tv")).text)
        assertEquals(emptyList(), fake.sent)
    }

    @Test
    fun companionChoicesHideAndBlockButtons() {
        val (reg, fake, _) = registry(tvAlias)
        val choices = mapOf("tv" to listOf("p1", "OFF"))
        val section = reg.promptSection(listOf("tv"), choices)
        assertTrue("Buttons: P1, off." in section && "P2" !in section && "Example: [[mcp:tv|P1]]" in section, section)
        assertFalse(reg.run(findCall("[[mcp:tv|P2]]")!!, listOf("tv"), choices = choices).ok)
        assertTrue(reg.run(findCall("[[mcp:tv|OFF]]")!!, listOf("tv"), choices = choices).ok)
        assertEquals(listOf("off" to ""), fake.sent)
        assertTrue("Example: [[mcp:tv|off]]" in reg.promptSection(listOf("tv"), mapOf("tv" to listOf("off"))))
        assertTrue(reg.promptSection(listOf("tv")).lines().size < 12) // ein Alias statt je Taste einer
    }

    @Test
    fun aliasesOfAStoppedServerStaySelectable() {
        val reg = ToolRegistry()
        reg.addAliases(aliasJson("""{"tv":{"server":"aus","buttons":true},"weather":{"server":"wetter","tool":"forecast"}}"""), setOf("aus"))
        assertEquals(emptyList(), reg.available())
        assertEquals(listOf("tv", "weather"), reg.selectable())
    }

    @Test
    fun personaKeepsToolChoicesInItsFile() {
        val p = Persona(name = "A", tools = listOf("tv"), toolChoices = mapOf("tv" to listOf("P1", "off")), id = "a")
        assertEquals(p, Persona.parse(p.toJson(), "a").getOrThrow())
        assertTrue(Persona.parse("""{"name":"B"}""", "b").getOrThrow().toolChoices.isEmpty())
    }

    @Test
    fun macroPlaysCallsAndWaits() {
        val (reg, fake, sleeps) = registry(
            """{"tv-p1":{"server":"remote","button":"P1"},"tv-off":{"server":"remote","tool":"off"},
               "show":{"macro":["tv-p1",3,{"wait":0.5},"tv-off"],"hint":"Short demo"}}""",
        )
        assertEquals(emptyList(), reg.errors)
        val out = reg.run(findCall("[[mcp:show]]")!!, listOf("show"))
        assertTrue(out.ok)
        assertEquals(listOf(3000L, 500L), sleeps)
        assertEquals(listOf("send" to "0x01", "off" to ""), fake.sent)
        assertEquals(RESULT_DISCARD, out.mode)
        assertTrue("tv-p1: ok send" in out.text && "wartet 3 s" in out.text, out.text)
    }

    @Test
    fun macroErrorInAStepIsReportedWithItsNumber() {
        val remote = FakeRemote().also { it.failOff = true }
        val (reg, fake, _) = registry("""{"off":{"server":"remote","tool":"off"},"m":{"macro":["off",1,"off"]}}""", remote)
        val out = reg.run(findCall("[[mcp:m]]")!!, listOf("m"))
        assertEquals(false to "Schritt 1 (off): kaputt", out.ok to out.text)
        assertEquals(1, fake.sent.size) // das Makro bricht beim ersten Fehler ab
    }

    @Test
    fun macroCallingASkippedServerDisappearsWithoutAnError() {
        val reg = ToolRegistry()
        reg.addServer("remote", FakeRemote(), exposeRaw = false)
        reg.addAliases(
            aliasJson("""{"on":{"server":"remote","tool":"off"},"tv":{"server":"aus","button":"P1"},"m":{"macro":["on","tv"]}}"""),
            setOf("aus"),
        )
        assertEquals(emptyList(), reg.errors)
        assertEquals(listOf("on"), reg.available())
    }

    @Test
    fun macroValidation() {
        val (reg, _, _) = registry(
            """{"off":{"server":"remote","tool":"off"},"m1":{"macro":[]},"m2":{"macro":[{"wait":99},"off"]},"m3":{"macro":[3]},
               "m4":{"macro":["off","m5"]},"m5":{"macro":["off"]},"m6":{"macro":["nix"]}}""",
        )
        assertEquals(listOf("m5", "off"), reg.available())
        val errors = reg.errors.joinToString(" | ")
        for (name in listOf("m1", "m2", "m3", "m4", "m6")) assertTrue("Alias '$name'" in errors, errors)
        assertTrue("selbst ein Makro" in errors)
    }

    @Test
    fun remoteGetsOneAliasForAllButtons() {
        val remote = FakeRemote()
        val (tools, buttons) = ServerImport.read(remote)
        val entries = ServerImport.aliasEntries("remote", tools, buttons, "tv-")
        assertEquals(listOf("tv", "tv-status"), entries.keys.toList()) // send/buttons/off stecken im Alias "tv"
        assertEquals(
            """{"server":"remote","buttons":true,"hint":"Remote control remote: press a button","example":"[[mcp:tv|P1]]"}""",
            entries.getValue("tv").toString(),
        )
    }

    @Test
    fun plainServerGetsOneEntryPerTool() {
        val entries = ServerImport.aliasEntries("demo", listOf(McpTool("get_time", "Returns the time.", null)), null, "demo-")
        assertEquals(listOf("demo-get-time"), entries.keys.toList())
        assertEquals("get_time", entries.getValue("demo-get-time").string("tool"))
    }

    @Test
    fun mergeKeepsExistingAliasesAndHidesRawTools() {
        val remote = FakeRemote()
        val (tools, buttons) = ServerImport.read(remote)
        val config = aliasJson("""{"servers":{"remote":{"url":"https://x/mcp"}},"aliases":{"tv":{"server":"remote","tool":"send","args":{"code":"0x99"}}}}""")
        val merged = ServerImport.merge(config, "remote", tools, buttons, "tv-")
        assertEquals(listOf("tv"), merged.skipped)
        assertFalse("tv" in merged.added)
        assertEquals("0x99", merged.config.obj("aliases")!!.obj("tv")!!.obj("args")!!.string("code")) // nicht überschrieben
        assertEquals("false", merged.config.obj("servers")!!.obj("remote")!!["raw"].toString())
        assertTrue("tv-status" in merged.config.obj("aliases")!!)
    }

    @Test
    fun importedConfigLoadsIntoARegistry() {
        val remote = FakeRemote()
        val (tools, buttons) = ServerImport.read(remote)
        val merged = ServerImport.merge(aliasJson("{}"), "remote", tools, buttons, "tv-")
        val reg = ToolRegistry()
        reg.addServer("remote", remote, exposeRaw = false)
        reg.addAliases(merged.config.obj("aliases"))
        assertEquals(emptyList(), reg.errors)
        assertEquals(listOf("tv", "tv-status"), reg.available())
        assertEquals(listOf("P1", "P2", "Wave", "0x11", "off"), reg.tools.getValue("tv").buttons)
    }
}
