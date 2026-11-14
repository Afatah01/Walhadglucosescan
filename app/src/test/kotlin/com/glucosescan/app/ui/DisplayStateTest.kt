package com.glucosescan.app.ui

import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayStateTest {
    private val minute = 60_000L
    private val noon = 1_767_268_800_000L

    private fun value(minutesAfterNoon: Int, mgdl: Int, sensor: String? = "A") =
        Glucose(noon + minutesAfterNoon * minute, mgdl, null, true, sensor)

    /** One value each minute for the 16 minutes up to noon. [first] is the oldest. */
    private fun trend(first: Int, stepPerMinute: Int) = (0..15).map { value(it - 15, first + it * stepPerMinute) }

    @Test
    fun nothingSaved() {
        val state = DisplayState.of(emptyList(), noon)
        assertEquals(DisplayState.Kind.NONE, state.kind)
        assertFalse(state.showsValue)
        assertNull(state.glucose)
        assertNull(state.trend)
    }

    @Test
    fun aValueGetsOld() {
        val values = trend(100, 0)
        fun kindAfter(seconds: Long) = DisplayState.of(values, noon + seconds * 1000).kind

        assertEquals(DisplayState.Kind.CURRENT, kindAfter(0))
        assertEquals(DisplayState.Kind.CURRENT, kindAfter(5 * 60 + 59)) // 5 whole minutes
        assertEquals(DisplayState.Kind.RECENT, kindAfter(6 * 60))
        assertEquals(DisplayState.Kind.RECENT, kindAfter(15 * 60 + 59))
        assertEquals(DisplayState.Kind.OLD, kindAfter(16 * 60))
        assertEquals(DisplayState.Kind.OLD, kindAfter(3 * 24 * 3600))

        assertEquals(0, DisplayState.of(values, noon + 59_000).ageMinutes)
        assertEquals(7, DisplayState.of(values, noon + 7 * minute + 30_000).ageMinutes)
    }

    @Test
    fun anOldValueIsNotShownBigAndHasNoArrow() {
        val state = DisplayState.of(trend(100, 2), noon + 16 * minute)
        assertEquals(DisplayState.Kind.OLD, state.kind)
        assertFalse(state.showsValue)
        assertNull(state.trend)
        assertEquals(130, state.glucose!!.value) // still known, for the small "last value" line
    }

    @Test
    fun aValueFromTheFutureIsNotShown() {
        val values = trend(100, 0)
        // up to 2 minutes ahead is taken as "now"
        assertEquals(DisplayState.Kind.CURRENT, DisplayState.of(values, noon - 2 * minute).kind)
        assertEquals(0, DisplayState.of(values, noon - 2 * minute).ageMinutes)
        val state = DisplayState.of(values, noon - 2 * minute - 1)
        assertEquals(DisplayState.Kind.CLOCK_WRONG, state.kind)
        assertFalse(state.showsValue)
        assertNull(state.trend)
    }

    @Test
    fun theArrowUsesTheLast15Minutes() {
        // 100 -> 130 in 15 minutes: +2.0 per minute
        var state = DisplayState.of(trend(100, 2), noon)
        assertEquals(2.0, state.changePerMinute!!, 1e-12)
        assertEquals(15, state.changeMinutes)
        assertEquals(Trend.RISING, state.trend) // more than 1, not more than 2

        state = DisplayState.of(trend(100, 3), noon)
        assertEquals(Trend.FAST_RISING, state.trend) // +3 per minute

        state = DisplayState.of(trend(100, 4), noon)
        assertEquals(Trend.RAPIDLY_RISING, state.trend) // +4 per minute

        state = DisplayState.of(trend(200, -3), noon)
        assertEquals(-3.0, state.changePerMinute!!, 1e-12)
        assertEquals(Trend.FAST_FALLING, state.trend)

        state = DisplayState.of(trend(100, 0), noon)
        assertEquals(Trend.CONSTANT, state.trend)
    }

    @Test
    fun oneJumpBetweenTwoMinutesDoesNotTurnTheArrow() {
        // steady at 100, the newest value is 103: the real scans show such steps
        val values = trend(100, 0).dropLast(1) + value(0, 103)
        val state = DisplayState.of(values, noon)
        assertEquals(0.2, state.changePerMinute!!, 1e-12)
        assertEquals(Trend.CONSTANT, state.trend)
    }

    @Test
    fun theRealScanExampleFallsSlowly() {
        // one-minute values of the real scan "Example", oldest first
        val real = listOf(109, 107, 106, 105, 105, 105, 104, 104, 104, 103, 102, 101, 100, 99, 99, 99)
        val values = real.mapIndexed { index, mgdl -> value(index - 15, mgdl) }
        val state = DisplayState.of(values, noon)
        assertEquals(-10.0 / 15, state.changePerMinute!!, 1e-12)
        assertEquals(Trend.CONSTANT, state.trend)
    }

    @Test
    fun aShorterTimeIsUsedIfThe15MinuteValueIsMissing() {
        // values only for the last 8 minutes
        val values = (0..8).map { value(it - 8, 100 + it * 2) }
        val state = DisplayState.of(values, noon)
        assertEquals(8, state.changeMinutes)
        assertEquals(2.0, state.changePerMinute!!, 1e-12)

        // only 4 minutes: too short for an arrow
        val few = (0..4).map { value(it - 4, 100 + it * 2) }
        val fewState = DisplayState.of(few, noon)
        assertNull(fewState.changePerMinute)
        assertNull(fewState.trend)
        assertTrue(fewState.showsValue)
    }

    @Test
    fun noArrowAcrossTwoSensorsOrAtTheEdgeOfTheRange() {
        val twoSensors = (0..15).map { value(it - 15, 100, if (it < 8) "A" else "B") }
        assertNull(DisplayState.of(twoSensors, noon).changePerMinute)

        val nowLow = trend(60, 0).dropLast(1) + value(0, 30)
        assertNull(DisplayState.of(nowLow, noon).trend)

        val wasHigh = listOf(value(-15, 600)) + (1..15).map { value(it - 15, 480) }
        assertNull(DisplayState.of(wasHigh, noon).trend)
    }

    @Test
    fun anOlderValueFarBackIsNotUsedForTheArrow() {
        val values = listOf(value(-120, 80), value(-17, 90), value(0, 150))
        val state = DisplayState.of(values, noon)
        assertNull(state.changePerMinute)
        assertEquals(150, state.glucose!!.value)
    }
}
