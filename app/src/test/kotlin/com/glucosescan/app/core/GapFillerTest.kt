package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GapFillerTest {
    private val minute = 60_000L
    private val noon = 1_767_268_800_000L

    private fun reading(minutesBefore: Int, value: Double, trend: Boolean, errorBits: Int = 0) =
        SensorReading(noon - minutesBefore * minute, value, errorBits, trend)

    private fun known(minutesBefore: Int, value: Int = 100) = Glucose(noon - minutesBefore * minute, value, null, true, "S")

    @Test
    fun withoutAKnownValueNothingIsAHole() {
        val readings = listOf(reading(30, 100.0, false), reading(1, 101.0, true))
        assertTrue(GapFiller.fill(readings, emptyList(), null, "S").isEmpty())
    }

    @Test
    fun fillsWhatIsMissingBeforeTheNewestValue() {
        // the app has live values up to 3 hours ago and again from 2 minutes ago
        val have = listOf(known(200), known(180), known(2), known(0))
        val readings = listOf(
            reading(195, 90.0, false), // near the known value of 200 minutes ago: not a hole
            reading(165, 111.0, false), // 15 minutes from the next known one: a hole
            reading(60, 120.4, false),
            reading(15, 130.6, true),
            reading(2, 140.0, true), // known already
            reading(0, 150.0, true) // not older than the newest
        )
        val filled = GapFiller.fill(readings, have, known(0), "S")
        assertEquals(listOf(165, 60, 15), filled.map { ((noon - it.timestamp) / minute).toInt() })
        assertEquals(listOf(111, 120, 131), filled.map { it.value })
        assertEquals(listOf(false, false, true), filled.map { it.fromTrend })
        assertTrue(filled.all { it.minuteChange == null && it.sensorSerial == "S" })
    }

    @Test
    fun aOneMinuteValueFillsItsOwnMinuteOnly() {
        val have = listOf(known(10), known(8), known(0))
        val filled = GapFiller.fill(listOf(reading(9, 99.0, true), reading(8, 98.0, true)), have, known(0), "S")
        assertEquals(1, filled.size)
        assertEquals(noon - 9 * minute, filled[0].timestamp)
    }

    @Test
    fun faultyMeasurementsFillNothing() {
        val readings = listOf(reading(30, 100.0, false, errorBits = 1), reading(45, Double.NaN, false))
        assertTrue(GapFiller.fill(readings, listOf(known(0)), known(0), "S").isEmpty())
    }

    @Test
    fun aHistoryValueNextToAKnownValueIsLeftOut() {
        // 7 minutes away counts as "the same time", 8 minutes away does not
        val have = listOf(known(37), known(0))
        assertTrue(GapFiller.fill(listOf(reading(30, 100.0, false)), have, known(0), "S").isEmpty())
        assertEquals(1, GapFiller.fill(listOf(reading(29, 100.0, false)), have, known(0), "S").size)
    }

    @Test
    fun searchInSortedTimes() {
        val times = longArrayOf(10, 20, 30)
        assertTrue(GapFiller.hasOneWithin(times, 20, 0))
        assertFalse(GapFiller.hasOneWithin(times, 25, 4))
        assertTrue(GapFiller.hasOneWithin(times, 25, 5))
        assertFalse(GapFiller.hasOneWithin(times, 40, 9))
        assertTrue(GapFiller.hasOneWithin(times, 1, 9))
        assertFalse(GapFiller.hasOneWithin(LongArray(0), 1, 1_000))
        assertNull(null)
    }
}
