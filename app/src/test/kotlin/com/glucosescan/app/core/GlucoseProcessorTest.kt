package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlucoseProcessorTest {
    private val minute = 60_000L
    private val noon = 1_767_268_800_000L

    private fun good(minutesAfterNoon: Int, value: Double, fromTrend: Boolean = true) =
        SensorReading(noon + minutesAfterNoon * minute, value, 0, fromTrend)

    private fun faulty(minutesAfterNoon: Int) = SensorReading(noon + minutesAfterNoon * minute, 0.0, 0x40, true)

    @Test
    fun firstScanKeepsEveryGoodValue() {
        val readings = listOf(good(-30, 110.4, fromTrend = false), good(-2, 100.5), good(-1, 103.49), good(0, 106.0))
        val result = GlucoseProcessor.process(readings, null, "3MH001HM1LM")

        assertFalse(result.tooNoisy)
        assertEquals(4, result.goodReadings)
        assertEquals(0, result.faultyReadings)
        assertEquals(listOf(110, 101, 103, 106), result.newValues.map { it.value })
        assertEquals(listOf(false, true, true, true), result.newValues.map { it.fromTrend })
        assertEquals("3MH001HM1LM", result.newValues[0].sensorSerial)

        assertNull("the first value ever has no change", result.newValues[0].minuteChange)
        assertEquals((101 - 110) / 28.0, result.newValues[1].minuteChange!!, 1e-12)
        assertEquals(2.0, result.newValues[2].minuteChange!!, 1e-12)
        assertEquals(3.0, result.newValues[3].minuteChange!!, 1e-12)
        assertEquals(Trend.FAST_RISING, result.newValues[3].trend)
    }

    @Test
    fun onlyValuesNewerThanTheNewestKnownOneAreKept() {
        val latest = Glucose(noon - 1 * minute, 100, null, true)
        val readings = listOf(good(-3, 90.0), good(-2, 95.0), good(-1, 100.0), good(0, 104.0), good(1, 105.0))
        val result = GlucoseProcessor.process(readings, latest, null)

        assertEquals(listOf(noon, noon + minute), result.newValues.map { it.timestamp })
        assertEquals(4.0, result.newValues[0].minuteChange!!, 1e-12)
        assertEquals(1.0, result.newValues[1].minuteChange!!, 1e-12)
    }

    @Test
    fun scanningAgainInTheSameMinuteGivesNothingNew() {
        val latest = Glucose(noon, 104, null, true)
        val readings = listOf(good(-2, 95.0), good(-1, 100.0), good(0, 104.0))
        val result = GlucoseProcessor.process(readings, latest, null)
        assertTrue(result.newValues.isEmpty())
        assertFalse(result.tooNoisy)
        assertEquals(3, result.goodReadings)
    }

    @Test
    fun faultyReadingsAreLeftOut() {
        val readings = listOf(good(-3, 90.0), faulty(-2), good(-1, 96.0), faulty(0))
        val result = GlucoseProcessor.process(readings, null, null)
        assertEquals(listOf(90, 96), result.newValues.map { it.value })
        assertEquals(2, result.faultyReadings)
        assertEquals("change is counted over the gap", 3.0, result.newValues[1].minuteChange!!, 1e-12)
    }

    @Test
    fun valuesThatAreNotNumbersAreLeftOut() {
        val readings = listOf(good(-2, Double.NaN), good(-1, Double.POSITIVE_INFINITY), good(0, 99.6))
        val result = GlucoseProcessor.process(readings, null, null)
        assertEquals(listOf(100), result.newValues.map { it.value })
        assertEquals(2, result.faultyReadings)
    }

    @Test
    fun lowAndHighValuesKeepTheirRealNumber() {
        val result = GlucoseProcessor.process(listOf(good(-1, 20.2), good(0, 620.7)), null, null)
        assertEquals(listOf(20, 621), result.newValues.map { it.value })
        assertEquals(GlucoseType.LOW, result.newValues[0].type)
        assertEquals(39, result.newValues[0].readableValue)
        assertEquals(GlucoseType.HIGH, result.newValues[1].type)
        assertEquals(501, result.newValues[1].readableValue)
        // the change is counted with the limited values, like the original does: 501 - 39
        assertEquals(462.0, result.newValues[1].minuteChange!!, 1e-12)
    }

    @Test
    fun aScanWithWildJumpsIsThrownAway() {
        // newest 5 values: 100, 100, 100, 100, 600 (counted as 501). Spread = 179.3, limit is 100.
        val wild = listOf(good(-5, 90.0), good(-4, 100.0), good(-3, 100.0), good(-2, 100.0), good(-1, 100.0), good(0, 600.0))
        val result = GlucoseProcessor.process(wild, null, null)
        assertTrue(result.tooNoisy)
        assertTrue(result.newValues.isEmpty())
        assertEquals(6, result.goodReadings)

        // spread of 100, 110, 120, 130, 140 is 15.8
        val calm = listOf(good(-4, 100.0), good(-3, 110.0), good(-2, 120.0), good(-1, 130.0), good(0, 140.0))
        val calmResult = GlucoseProcessor.process(calm, null, null)
        assertFalse(calmResult.tooNoisy)
        assertEquals(5, calmResult.newValues.size)
    }

    @Test
    fun spreadIsTheSampleStandardDeviation() {
        fun values(vararg v: Int) = v.map { Glucose(noon, it, null, true) }
        assertEquals(Math.sqrt(250.0), GlucoseProcessor.stdev(values(100, 110, 120, 130, 140)), 1e-12)
        assertEquals(Math.sqrt(32160.2), GlucoseProcessor.stdev(values(100, 100, 100, 100, 600)), 1e-9)
        assertEquals(0.0, GlucoseProcessor.stdev(values(80, 80, 80, 80, 80)), 0.0)
    }

    @Test
    fun withFewerThanFiveValuesTheSpreadIsNotChecked() {
        val result = GlucoseProcessor.process(listOf(good(-3, 40.0), good(-2, 500.0), good(-1, 40.0), good(0, 500.0)), null, null)
        assertFalse(result.tooNoisy)
        assertEquals(4, result.newValues.size)
    }

    @Test
    fun secondsAreCutOffFromTheTime() {
        val g = Glucose(noon + 59_999, 100, null, true)
        assertEquals(noon, g.timestamp)
    }
}
