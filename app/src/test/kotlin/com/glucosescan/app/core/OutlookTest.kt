package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card "Next 30 minutes": which way the line goes, what it calls for, and when the words at
 * the top must be about staying safe rather than about the number.
 */
class OutlookTest {

    private val minute = 60_000L

    /** Tuesday 10 November 2026, 11:32 UTC. */
    private val now = 1_794_310_320_000L

    private val low = 70
    private val high = 180

    /** One value each minute: [count] values ending with [newest] at [end], changing by [slope] a minute. */
    private fun line(newest: Int, slope: Double, count: Int = 16, end: Long = now): List<Glucose> {
        return (0 until count).map { k ->
            Glucose(end - k * minute, Math.round(newest - slope * k).toInt(), null, true, "A")
        }
    }

    private fun outlook(newest: Int, slope: Double, nowValue: Int = newest): Outlook? =
        Outlooks.of(Forecaster.of(line(newest, slope), now), now, nowValue, low, high)

    // ------------------------------------------------------------------ nothing to say

    @Test
    fun withoutALineThereIsNoOutlook() {
        assertNull(Outlooks.of(null, now, 100, low, high))
    }

    // ------------------------------------------------------------------ the number and the time

    @Test
    fun itSaysWhereTheLineIsInHalfAnHour() {
        val outlook = outlook(100, -1.0)!!
        assertEquals(now + 30 * minute, outlook.at)
        // 100 now, one less each minute
        assertEquals(70, outlook.value)
        assertEquals(-1.0, outlook.perMinute, 1e-9)
        assertEquals(16, outlook.basedOn)
        assertEquals(15, outlook.spanMinutes)
    }

    @Test
    fun aRiseIsCarriedForwardToo() {
        val outlook = outlook(120, 2.0)!!
        assertEquals(180, outlook.value)
        assertEquals(OutlookDirection.RISING, outlook.direction)
    }

    // ------------------------------------------------------------------ which way

    @Test
    fun theDirectionComesFromTheSlope() {
        assertEquals(OutlookDirection.FALLING, outlook(100, -1.0)!!.direction)
        assertEquals(OutlookDirection.RISING, outlook(100, 1.0)!!.direction)
        assertEquals("half a unit a minute is called steady", OutlookDirection.STEADY, outlook(100, 0.2)!!.direction)
        assertEquals(OutlookDirection.STEADY, outlook(100, 0.0)!!.direction)
    }

    // ------------------------------------------------------------------ what it calls for

    @Test
    fun anOrdinaryValueCallsForNoWarning() {
        val outlook = outlook(110, 0.1)!!
        assertEquals(OutlookRisk.NONE, outlook.risk)
        assertFalse(outlook.isUrgent)
        assertNull(outlook.minutesUntilLow)
    }

    @Test
    fun aValueHeadingForTheLowLimitIsCalledLowSoon() {
        // 82 now, one less a minute: the line crosses 70 in 13 minutes
        val outlook = outlook(82, -1.0)!!
        assertEquals(OutlookRisk.LOW_SOON, outlook.risk)
        assertEquals(13, outlook.minutesUntilLow)
        assertEquals(OutlookDirection.FALLING, outlook.direction)
    }

    @Test
    fun aValueUnderTheLowLimitIsCalledLow() {
        val outlook = outlook(65, 0.0, nowValue = 65)!!
        assertEquals(OutlookRisk.LOW, outlook.risk)
    }

    @Test
    fun aValueUnder54IsASevereLow() {
        val outlook = outlook(50, 0.0, nowValue = 50)!!
        assertEquals(OutlookRisk.SEVERE_LOW, outlook.risk)
        assertTrue("a severe low is urgent whatever the line does", outlook.isUrgent)
    }

    @Test
    fun aLowThatIsStillFallingIsUrgent() {
        val outlook = outlook(65, -1.5, nowValue = 65)!!
        assertEquals(OutlookRisk.LOW, outlook.risk)
        assertEquals(OutlookDirection.FALLING, outlook.direction)
        assertTrue(outlook.isUrgent)
    }

    @Test
    fun aLowThatIsComingBackUpIsNotUrgent() {
        val outlook = outlook(65, 1.5, nowValue = 65)!!
        assertEquals(OutlookRisk.LOW, outlook.risk)
        assertEquals(OutlookDirection.RISING, outlook.direction)
        assertFalse("it is already on its way back", outlook.isUrgent)
    }

    @Test
    fun aValueOverTheHighLimitIsCalledHigh() {
        val outlook = outlook(200, 0.0, nowValue = 200)!!
        assertEquals(OutlookRisk.HIGH, outlook.risk)
        assertFalse("a high is not an emergency in the way a low is", outlook.isUrgent)
    }

    @Test
    fun aValueOver250IsVeryHigh() {
        val outlook = outlook(260, 0.0, nowValue = 260)!!
        assertEquals(OutlookRisk.VERY_HIGH, outlook.risk)
    }

    @Test
    fun theSevereLowBeatsEveryOtherRisk() {
        // under 54 and heading up: still a severe low (a gentle rise, so the line stays
        // inside what a sensor can show)
        val outlook = outlook(50, 0.6, nowValue = 50)!!
        assertEquals(OutlookRisk.SEVERE_LOW, outlook.risk)
        assertTrue(outlook.isUrgent)
    }
}
