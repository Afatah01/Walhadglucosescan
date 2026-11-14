package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The warning before a low: the alarm rules and the words. */
class LowSoonTest {
    private val minute = 60_000L
    private val now = 1_794_310_320_000L
    private val on = AlarmSettings(lowOn = true, low = 70, highOn = false, high = 250, noDataOn = true, lowSoonOn = true)
    private val off = AlarmSettings(lowOn = true, low = 70, highOn = false, high = 250, noDataOn = true)

    private fun value(mgdl: Int, at: Long = now): Glucose = Glucose(at, mgdl, null, true, "A")

    @Test
    fun theReasonIsLowSoonOnlyWhileTheValueIsNotLowYet() {
        assertEquals(AlarmKind.LOW_SOON, AlarmRules.reason(value(85), value(85), now, on, 0L, 12))
        assertNull("the forecast does not go below the limit", AlarmRules.reason(value(85), value(85), now, on, 0L, null))
        assertNull("switched off (as from the start)", AlarmRules.reason(value(85), value(85), now, off, 0L, 12))
        assertEquals("a low value is a low alarm, not a warning", AlarmKind.LOW, AlarmRules.reason(value(65), value(65), now, on, 0L, 1))
        assertEquals("the sensor value under a correction is low", AlarmKind.LOW, AlarmRules.reason(value(85), value(66), now, on, 0L, 5))
        assertNull("an old value warns of nothing", AlarmRules.reason(value(85, now - 7 * minute), null, now, on, 0L, 12))
        assertEquals("no values for long: that alarm comes first", AlarmKind.NO_DATA, AlarmRules.reason(value(85, now - 30 * minute), null, now, on, 0L, 12))
    }

    @Test
    fun theWarningSoundsOnceInHalfAnHour() {
        var memory = AlarmMemory()
        // it sounds
        var sound = AlarmRules.decide(AlarmKind.LOW_SOON, now, on, memory)
        assertEquals(AlarmKind.LOW_SOON, sound)
        memory = AlarmRules.remember(memory, AlarmKind.LOW_SOON, sound, now)
        assertEquals(now, memory.lastSoonTime)

        // the next minute: quiet
        sound = AlarmRules.decide(AlarmKind.LOW_SOON, now + minute, on, memory)
        assertNull(sound)
        memory = AlarmRules.remember(memory, AlarmKind.LOW_SOON, sound, now + minute)

        // the reason goes away and comes back after 10 minutes: still quiet
        memory = AlarmRules.remember(memory, null, null, now + 2 * minute)
        assertNull(memory.lastKind)
        assertEquals("the time of the last warning is kept", now, memory.lastSoonTime)
        assertNull(AlarmRules.decide(AlarmKind.LOW_SOON, now + 10 * minute, on, memory))
        assertNull(AlarmRules.decide(AlarmKind.LOW_SOON, now + 29 * minute, on, memory))

        // after half an hour it may sound again
        assertEquals(AlarmKind.LOW_SOON, AlarmRules.decide(AlarmKind.LOW_SOON, now + 30 * minute, on, memory))
    }

    @Test
    fun aRealLowSoundsRightAfterTheWarning() {
        var memory = AlarmRules.remember(AlarmMemory(), AlarmKind.LOW_SOON, AlarmKind.LOW_SOON, now)
        val sound = AlarmRules.decide(AlarmKind.LOW, now + 3 * minute, on, memory)
        assertEquals(AlarmKind.LOW, sound)
        memory = AlarmRules.remember(memory, AlarmKind.LOW, sound, now + 3 * minute)
        assertEquals(AlarmKind.LOW, memory.lastKind)
        assertEquals(now, memory.lastSoonTime)
    }

    @Test
    fun silencingKeepsTheTimeOfTheLastWarning() {
        val memory = AlarmRules.remember(AlarmMemory(), AlarmKind.LOW_SOON, AlarmKind.LOW_SOON, now)
        val silenced = AlarmRules.silence(memory, AlarmKind.LOW_SOON, now + minute)
        assertEquals(now, silenced.lastSoonTime)
        assertNull(AlarmRules.decide(AlarmKind.LOW_SOON, now + 31 * minute - 1, on, silenced))
    }

    @Test
    fun theWords() {
        val shown = value(85)
        assertEquals("Glucose 85. Falling. Below target soon.", SpeechText.value(shown, GlucoseUnit.MGDL, Trend.FALLING, below = false, above = false, withTrend = true, lowSoon = true))
        assertEquals("Glucose 85. Below target soon.", SpeechText.value(shown, GlucoseUnit.MGDL, Trend.FALLING, below = false, above = false, withTrend = false, lowSoon = true))
        assertEquals("Glucose 85. Falling.", SpeechText.value(shown, GlucoseUnit.MGDL, Trend.FALLING, below = false, above = false, withTrend = true))
        // the arrow "steady" covers a slow fall: "Steady. Below target soon." would contradict itself
        assertEquals("Glucose 85. Falling slowly. Below target soon.", SpeechText.value(shown, GlucoseUnit.MGDL, Trend.CONSTANT, below = false, above = false, withTrend = true, lowSoon = true))
        assertEquals("Glucose 85. Steady.", SpeechText.value(shown, GlucoseUnit.MGDL, Trend.CONSTANT, below = false, above = false, withTrend = true))
        // low already and still falling slowly: not "Steady" either
        assertEquals(
            "Glucose 72. Below target. Falling slowly.",
            SpeechText.value(value(72), GlucoseUnit.MGDL, Trend.CONSTANT, below = true, above = false, withTrend = true, fallsWhileBelow = true)
        )
        assertEquals(
            "Glucose 72. Below target. Steady.",
            SpeechText.value(value(72), GlucoseUnit.MGDL, Trend.CONSTANT, below = true, above = false, withTrend = true)
        )
        assertEquals(
            "a faster fall keeps its own word",
            "Glucose 72. Below target. Falling.",
            SpeechText.value(value(72), GlucoseUnit.MGDL, Trend.FALLING, below = true, above = false, withTrend = true, fallsWhileBelow = true)
        )
        assertEquals(
            "above the range the flag changes nothing",
            "Glucose 85. Steady.",
            SpeechText.value(shown, GlucoseUnit.MGDL, Trend.CONSTANT, below = false, above = false, withTrend = true, fallsWhileBelow = true)
        )
        assertEquals(
            "a value that is below target already is not \"soon\"",
            "Glucose 75. Below target. Falling.",
            SpeechText.value(value(75), GlucoseUnit.MGDL, Trend.FALLING, below = true, above = false, withTrend = true, lowSoon = true)
        )
        assertEquals("Low glucose soon. 85.", SpeechText.alarm(AlarmKind.LOW_SOON, shown, GlucoseUnit.MGDL))
        assertEquals("Low glucose soon. 4.7.", SpeechText.alarm(AlarmKind.LOW_SOON, shown, GlucoseUnit.MMOLL))
        assertEquals("Low glucose soon.", SpeechText.alarm(AlarmKind.LOW_SOON, null, GlucoseUnit.MGDL))
    }

    @Test
    fun theOlderKindsKeepTheirNumbers() {
        // the number of a kind is part of the "Silence" button of its notification
        assertEquals(listOf("LOW", "HIGH", "NO_DATA", "LOW_SOON"), AlarmKind.values().map { it.name })
    }

    @Test
    fun percentNumbers() {
        assertEquals("0", Percent.text(0.0))
        assertEquals("under 0.1", Percent.text(0.04))
        assertEquals("0.1", Percent.text(0.07))
        assertEquals("3.4", Percent.text(3.44))
        assertEquals("4", Percent.text(4.0))
        assertEquals("10", Percent.text(9.96))
        assertEquals("13", Percent.text(12.5))
        assertEquals("82", Percent.text(82.3))
        assertEquals("100", Percent.text(100.0))
    }
}
