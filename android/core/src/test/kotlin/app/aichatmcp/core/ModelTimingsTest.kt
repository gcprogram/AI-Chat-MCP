package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelTimingsTest {
    @Test
    fun clockFormatsMinutesAndSeconds() {
        assertEquals("2:24", ModelTimings.clock(144_040))
        assertEquals("0:01", ModelTimings.clock(1_000))
        assertEquals("10:05", ModelTimings.clock(605_000))
    }

    @Test
    fun answerTimeIsTheMedianOfTheLastFiveRoundedUp() {
        var t = ModelTimings()
        assertNull(t.answerSeconds())
        for (ms in listOf(2_300L, 9_000L, 3_100L)) t = t.withAnswer(ms)
        assertEquals(4, t.answerSeconds()) // Median 3,1 s -> 4
        for (ms in listOf(100_000L, 1_000L, 2_000L)) t = t.withAnswer(ms)
        assertEquals(5, t.answersMs.size) // nur die letzten fünf
        assertEquals(4, t.answerSeconds()) // Median 3,1 s -> 4
        assertEquals(t, t.withAnswer(0)) // 0 = keine Messung
    }

    @Test
    fun readyTimeKeepsTheLastMeasurement() {
        assertEquals(144_040L, ModelTimings().withReady(5_000).withReady(144_040).readyMs)
        assertEquals(5_000L, ModelTimings().withReady(5_000).withReady(0).readyMs)
    }

    @Test
    fun savesAndLoadsAndToleratesGarbage() {
        val all = mapOf("Qwen3-4B" to ModelTimings().withReady(144_040).withAnswer(5_000))
        assertEquals(all, ModelTimings.parseAll(ModelTimings.toJson(all)))
        assertEquals(emptyMap(), ModelTimings.parseAll("kaputt"))
        assertEquals(emptyMap(), ModelTimings.parseAll(null))
    }
}
