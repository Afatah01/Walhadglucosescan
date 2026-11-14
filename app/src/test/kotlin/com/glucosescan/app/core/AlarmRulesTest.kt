package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmRulesTest {
    private val minute = 60_000L
    private val now = 1_767_268_800_000L
    private val start = now - 24 * 60 * minute // live values were switched on yesterday
    private val all = AlarmSettings(lowOn = true, low = 70, highOn = true, high = 250, noDataOn = true)

    private fun value(mgdl: Int, minutesAgo: Int = 0) = Glucose(now - minutesAgo * minute, mgdl, 0.0, true, "S")

    private fun reason(mgdl: Int, minutesAgo: Int = 0, settings: AlarmSettings = all, sensor: Int = mgdl) =
        AlarmRules.reason(value(mgdl, minutesAgo), value(sensor, minutesAgo), now, settings, start)

    @Test
    fun lowAndHighLimits() {
        assertEquals(AlarmKind.LOW, reason(69))
        assertNull("the limit itself is still fine", reason(70))
        assertNull(reason(110))
        assertNull("the limit itself is still fine", reason(250))
        assertEquals(AlarmKind.HIGH, reason(251))
        assertEquals("below what the sensor can measure", AlarmKind.LOW, reason(20))
        assertEquals("above what the sensor can measure", AlarmKind.HIGH, reason(600))
    }

    @Test
    fun aCorrectionCannotHideALowValue() {
        assertEquals("shown 78, but the sensor formula says 66", AlarmKind.LOW, reason(78, sensor = 66))
        assertEquals("shown 66, sensor formula 78", AlarmKind.LOW, reason(66, sensor = 78))
        assertNull(reason(78, sensor = 74))
        assertNull("high goes by the shown value only", reason(240, sensor = 260))
        assertEquals(AlarmKind.HIGH, reason(260, sensor = 240))
    }

    @Test
    fun switchedOffAlarmsStayQuiet() {
        val none = AlarmSettings(lowOn = false, low = 70, highOn = false, high = 250, noDataOn = false)
        assertNull(reason(50, settings = none))
        assertNull(reason(300, settings = none))
        assertNull(reason(100, minutesAgo = 300, settings = none))
        assertNull(AlarmRules.reason(null, null, now, none, start))

        val onlyHigh = AlarmSettings(lowOn = false, low = 70, highOn = true, high = 250, noDataOn = false)
        assertNull(reason(50, settings = onlyHigh))
        assertEquals(AlarmKind.HIGH, reason(300, settings = onlyHigh))
    }

    @Test
    fun onlyACurrentValueGivesAGlucoseAlarm() {
        assertEquals(AlarmKind.LOW, reason(60, minutesAgo = 5))
        assertNull("6 minutes old: not current any more", reason(60, minutesAgo = 6))
        assertNull(reason(60, minutesAgo = 20))
        assertEquals("more than 20 minutes without a value", AlarmKind.NO_DATA, reason(60, minutesAgo = 21))
        assertEquals(AlarmKind.NO_DATA, reason(110, minutesAgo = 600))
        assertNull("a value from the future means the clock is wrong", reason(60, minutesAgo = -10))
    }

    @Test
    fun noValuesCountsFromTheMomentValuesAreExpected() {
        assertEquals(AlarmKind.NO_DATA, AlarmRules.reason(null, null, now, all, start))
        assertNull("live values were switched on 5 minutes ago", AlarmRules.reason(null, null, now, all, now - 5 * minute))
        assertNull(AlarmRules.reason(null, null, now, all, now - 20 * minute))
        assertEquals(AlarmKind.NO_DATA, AlarmRules.reason(null, null, now, all, now - 21 * minute))
        assertNull(
            "an old value, but the sensor said 3 minutes ago that it is warming up",
            AlarmRules.reason(value(100, 300), value(100, 300), now, all, now - 3 * minute)
        )
        val ten = AlarmSettings(true, 70, true, 250, true, noDataMinutes = 10)
        assertEquals(AlarmKind.NO_DATA, AlarmRules.reason(value(100, 11), value(100, 11), now, ten, start))
    }

    @Test
    fun theSameAlarmRepeatsOnlyAfterSomeTime() {
        var memory = AlarmMemory()
        var t = now

        fun step(reason: AlarmKind?): AlarmKind? {
            val sounded = AlarmRules.decide(reason, t, all, memory)
            memory = AlarmRules.remember(memory, reason, sounded, t)
            return sounded
        }

        assertEquals(AlarmKind.LOW, step(AlarmKind.LOW))
        for (k in 1..14) {
            t = now + k * minute
            assertNull("minute $k", step(AlarmKind.LOW))
        }
        t = now + 15 * minute
        assertEquals("still low after 15 minutes: again", AlarmKind.LOW, step(AlarmKind.LOW))
        t = now + 16 * minute
        assertNull(step(AlarmKind.LOW))
    }

    @Test
    fun aNewLowAfterAGoodValueSoundsAtOnce() {
        var memory = AlarmMemory()
        var t = now

        fun step(reason: AlarmKind?): AlarmKind? {
            val sounded = AlarmRules.decide(reason, t, all, memory)
            memory = AlarmRules.remember(memory, reason, sounded, t)
            return sounded
        }

        assertEquals(AlarmKind.LOW, step(AlarmKind.LOW))
        t += minute
        assertNull(step(AlarmKind.LOW))
        t += minute
        assertNull("back in range", step(null))
        t += minute
        assertEquals("low again 3 minutes after the first alarm", AlarmKind.LOW, step(AlarmKind.LOW))
        t += minute
        assertEquals("another kind of alarm does not wait", AlarmKind.HIGH, step(AlarmKind.HIGH))
        t += minute
        assertEquals(AlarmKind.NO_DATA, step(AlarmKind.NO_DATA))
    }

    @Test
    fun silenceHoldsForHalfAnHourAndOnlyForItsKind() {
        var memory = AlarmRules.remember(AlarmMemory(), AlarmKind.LOW, AlarmKind.LOW, now)
        memory = AlarmRules.silence(memory, AlarmKind.LOW, now + minute)

        assertNull(AlarmRules.decide(AlarmKind.LOW, now + 16 * minute, all, memory))
        assertNull(AlarmRules.decide(AlarmKind.LOW, now + 30 * minute, all, memory))
        assertEquals(AlarmKind.LOW, AlarmRules.decide(AlarmKind.LOW, now + 31 * minute, all, memory))
        assertEquals("high is not silenced", AlarmKind.HIGH, AlarmRules.decide(AlarmKind.HIGH, now + 5 * minute, all, memory))

        // the silence stays when the value was good for a moment
        val after = AlarmRules.remember(memory, null, null, now + 5 * minute)
        assertEquals(AlarmKind.LOW, after.silencedKind)
        assertNull(AlarmRules.decide(AlarmKind.LOW, now + 6 * minute, all, after))

        // and it is forgotten when its time is over
        val later = AlarmRules.remember(memory, null, null, now + 40 * minute)
        assertNull(later.silencedKind)
        assertEquals(0L, later.silencedUntil)
    }

    @Test
    fun aClockThatJumpsBackDoesNotBlockAlarms() {
        val memory = AlarmMemory(AlarmKind.LOW, now + 10 * 60 * minute) // "last alarm" lies in the future
        assertEquals(AlarmKind.LOW, AlarmRules.decide(AlarmKind.LOW, now, all, memory))
    }
}
