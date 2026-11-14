package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ForecastTest {
    private val minute = 60_000L

    /** Tuesday 10 November 2026, 11:32 UTC. */
    private val now = 1_794_310_320_000L

    /** One value each minute: [count] values that end with [newest] at [end] and change by [slope] each minute. */
    private fun line(newest: Int, slope: Double, count: Int = 16, end: Long = now, serial: String? = "A"): List<Glucose> {
        return (0 until count).map { k ->
            Glucose(end - k * minute, Math.round(newest - slope * k).toInt(), null, true, serial)
        }
    }

    @Test
    fun aStraightFallIsContinued() {
        val forecast = Forecaster.of(line(100, -1.0), now)!!
        assertEquals(-1.0, forecast.slope, 1e-9)
        assertEquals(100.0, forecast.level, 1e-9)
        assertEquals(0.0, forecast.scatter, 1e-9)
        assertEquals(16, forecast.count)
        assertEquals(15, forecast.spanMinutes)
        assertEquals(now, forecast.time)
        assertEquals(85, forecast.valueAt(now + 15 * minute))
        assertEquals(70, forecast.valueAt(now + 30 * minute))
        assertEquals(100, forecast.valueAt(now))
    }

    @Test
    fun theMinutesUntilALimitAreCounted() {
        val forecast = Forecaster.of(line(100, -1.0), now)!!
        // 100 now, one less each minute: 85 is reached after 15 minutes, the first minute below it is the 16th
        assertEquals(16, forecast.minutesUntilBelow(85, now))
        assertEquals(1, forecast.minutesUntilBelow(100, now))
        assertEquals("exactly 20 minutes is still soon", 20, forecast.minutesUntilBelow(81, now))
        assertNull("80 comes after 21 minutes: not within 20", forecast.minutesUntilBelow(80, now))
        assertEquals(21, forecast.minutesUntilBelow(80, now, within = 30))
        assertNull("already below", forecast.minutesUntilBelow(101, now))
        // two minutes later the line is at 98
        assertEquals(14, forecast.minutesUntilBelow(85, now + 2 * minute))
    }

    @Test
    fun aRiseOrASlowFallIsNotCalledLowSoon() {
        assertNull(Forecaster.of(line(82, 1.0), now)!!.minutesUntilBelow(80, now + 5 * minute))
        val slow = Forecaster.of(line(82, -0.3), now)!!
        assertEquals(-0.3, slow.slope, 0.05)
        assertNull("it would be there in 7 minutes, but so slow a fall comes and goes", slow.minutesUntilBelow(80, now))
        assertEquals(4, Forecaster.of(line(82, -0.6), now)!!.minutesUntilBelow(80, now))
    }

    @Test
    fun tooLittleGivesNoForecast() {
        assertNull(Forecaster.of(emptyList(), now))
        assertNull("four values", Forecaster.of(line(100, -1.0, count = 4), now))
        assertNull("five values, but only four minutes", Forecaster.of(line(100, -1.0, count = 5), now))
        assertNull("eight values reach back seven minutes", Forecaster.of(line(100, -1.0, count = 8), now))
        assertNotNull("nine values reach back eight minutes", Forecaster.of(line(100, -1.0, count = 9), now))
        // five values spread over 12 minutes are enough
        val spread = listOf(0, 3, 6, 9, 12).map { Glucose(now - it * minute, 100 + it, null, true, "A") }
        assertEquals(-1.0, Forecaster.of(spread, now)!!.slope, 1e-9)
    }

    @Test
    fun anOldValueIsNotContinued() {
        assertNotNull(Forecaster.of(line(100, -1.0), now + 5 * minute + 59_000))
        assertNull(Forecaster.of(line(100, -1.0), now + 6 * minute))
        assertNull("a value from the future: the clock was changed", Forecaster.of(line(100, -1.0), now - 3 * minute))
    }

    @Test
    fun jumpingValuesGiveNoForecast() {
        val jumping = (0 until 16).map { Glucose(now - it * minute, if (it % 2 == 0) 125 else 75, null, true, "A") }
        assertNull(Forecaster.of(jumping, now))
        val calm = (0 until 16).map { Glucose(now - it * minute, if (it % 2 == 0) 103 else 97, null, true, "A") }
        assertNotNull(Forecaster.of(calm, now))
    }

    @Test
    fun onlyTheLastQuarterHourOfTheSameSensorCounts() {
        // an hour ago the glucose rose; in the last 15 minutes it falls
        val older = (16..60).map { Glucose(now - it * minute, 40 + it, null, true, "A") }
        val forecast = Forecaster.of(older + line(100, -1.0), now)!!
        assertEquals(-1.0, forecast.slope, 1e-9)
        assertEquals(16, forecast.count)

        // a new sensor: the values of the old one are not used
        val newSensor = line(100, -1.0, count = 3, serial = "B") + line(103, -1.0, count = 13, end = now - 3 * minute, serial = "A")
        assertNull(Forecaster.of(newSensor, now))
    }

    @Test
    fun lowAndHighCannotBeContinued() {
        assertNull("the newest value is below what the sensor can measure", Forecaster.of(line(30, -1.0), now))
        assertNull(Forecaster.of(line(510, 1.0), now))
    }

    @Test
    fun theForecastStaysInsideWhatASensorCanShow() {
        assertEquals(39, Forecaster.of(line(60, -3.0), now)!!.valueAt(now + 30 * minute))
        assertEquals(501, Forecaster.of(line(450, 4.0), now)!!.valueAt(now + 30 * minute))
    }

    @Test
    fun aScanIsEnoughForAForecast() {
        // a scan: one value each minute for 16 minutes, before that one each 15 minutes
        val history = (1..31).map { Glucose(now - (15 + it * 15) * minute, 150, null, false, "A") }
        val forecast = Forecaster.of(history + line(104, -0.67), now)!!
        assertEquals(-0.67, forecast.slope, 0.03)
        assertEquals(84.0, forecast.valueAt(now + 30 * minute).toDouble(), 1.5)
    }
}
