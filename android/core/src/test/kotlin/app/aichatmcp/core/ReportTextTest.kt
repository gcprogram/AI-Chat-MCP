package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportTextTest {
    @Test
    fun bodyContainsOnlyTheReportedReplyModelAndVersion() {
        val body = ReportText.body("Das ist eine Antwort.", "Qwen3-4B", "0.1.0", german = true)
        assertTrue("Gemeldete Antwort:\nDas ist eine Antwort." in body)
        assertTrue("Modell: Qwen3-4B" in body && "App-Version: 0.1.0" in body)
        assertTrue(body.startsWith("Was stimmt nicht?"))
    }

    @Test
    fun longRepliesAreCutAndEnglishIsAvailable() {
        val body = ReportText.body("x".repeat(5000), "", "1", german = false)
        assertTrue(body.length < 2300)
        assertTrue("[…]" in body && "Model: unknown" in body && body.startsWith("What is wrong?"))
        assertEquals("Report: AI reply", ReportText.subject(false))
    }

    @Test
    fun generalReportHasNoReplySection() {
        val body = ReportText.general("m", "1", german = true)
        assertFalse("Gemeldete Antwort" in body)
        assertTrue("Modell: m" in body)
    }
}
