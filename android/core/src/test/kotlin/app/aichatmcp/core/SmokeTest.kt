package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun coreLoads() = assertEquals("0.1.0", CORE_VERSION)
}
