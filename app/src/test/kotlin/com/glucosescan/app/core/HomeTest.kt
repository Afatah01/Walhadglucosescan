package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The numbers behind the new Home screen, checked against figures a person can work out by hand.
 * Nothing here touches Android, so a wrong figure is caught here, not on somebody's phone.
 */
class HomeTest {

    private val zone = TimeZone.getTimeZone("UTC")
    private val now = 1_700_000_000_000L / 60_000L * 60_000L
    private val day = 24 * 60 * 60_000L
    private val to = now - 60_000L
    private val from = to - day

    /** 96 quarter hours: 60 at 120 (in range), 20 at 200 (high), 10 at 300 (very high), 6 at 60 (low). */
    private fun values(): List<Glucose> {
        val list = ArrayList<Glucose>()
        var index = 0
        fun add(count: Int, value: Int) {
            repeat(count) {
                list.add(Glucose(from + index * 15 * 60_000L, value, 0.0, true, null))
                index++
            }
        }
        add(60, 120)
        add(20, 200)
        add(10, 300)
        add(6, 60)
        return list
    }

    private fun stats() = StatisticsCalculator.compute(values(), from, to, 70, 180, zone)

    // ------------------------------------------------------------ the five bands

    @Test
    fun theFiveBandsFollowThePublishedLimits() {
        assertEquals(GlucoseBand.VERY_LOW, GlucoseBand.of(53, 70, 180))
        assertEquals(GlucoseBand.LOW, GlucoseBand.of(69, 70, 180))
        assertEquals(GlucoseBand.IN_RANGE, GlucoseBand.of(70, 70, 180))
        assertEquals(GlucoseBand.IN_RANGE, GlucoseBand.of(180, 70, 180))
        assertEquals(GlucoseBand.HIGH, GlucoseBand.of(181, 70, 180))
        assertEquals(GlucoseBand.HIGH, GlucoseBand.of(250, 70, 180))
        assertEquals(GlucoseBand.VERY_HIGH, GlucoseBand.of(251, 70, 180))
    }

    @Test
    fun theBandsFollowTheTargetThePersonSet() {
        assertEquals(GlucoseBand.IN_RANGE, GlucoseBand.of(140, 80, 150))
        assertEquals(GlucoseBand.HIGH, GlucoseBand.of(151, 80, 150))
        assertEquals(GlucoseBand.LOW, GlucoseBand.of(79, 80, 150))
    }

    // ------------------------------------------------------------ the day figures

    @Test
    fun everyQuarterHourIsCountedOnce() {
        val s = stats()
        assertEquals(96, s.readingCount)
        assertEquals(96, s.slots)
        assertEquals(0, s.missingSlots)
    }

    @Test
    fun theTightRangeIsSeventyTo140() {
        // 60 quarter hours at 120 lie inside 70..140 and nothing else does
        assertEquals(63, stats().inTightPercent)
    }

    @Test
    fun veryLowAndVeryHighStandApart() {
        val s = stats()
        assertEquals(0, s.veryLowPercent)          // nothing under 54
        assertEquals(10, s.veryHighPercent)        // 10 of 96 over 250
    }

    @Test
    fun belowAndAboveUseTheTargetOfThePerson() {
        val s = stats()
        // 6 quarter hours under the low limit of 70
        assertEquals(6, s.belowPercent)
        // 30 quarter hours over the high limit of 180 (20 at 200 and 10 at 300)
        assertEquals(31, s.abovePercent)
    }

    @Test
    fun coverageSaysHowMuchOfThePeriodHasReadings() {
        val s = stats()
        assertEquals(96, s.possibleSlots)
        assertEquals(100, s.coveragePercent)
        // nothing at all is reported as nothing, never as zero percent of a full day
        val empty = StatisticsCalculator.compute(emptyList(), from, to, 70, 180, zone)
        assertFalse(empty.hasValues)
        assertEquals(0, empty.readingCount)
        assertEquals(0, empty.inTightPercent)
        assertEquals(0, empty.veryHighPercent)
    }

    // ------------------------------------------------------------ the four-word judgement

    @Test
    fun aFullDayInRangeIsCalledGood() {
        val list = ArrayList<Glucose>()
        repeat(96) { list.add(Glucose(from + it * 15 * 60_000L, 110, 0.0, true, null)) }
        val summary = ControlSummaryOf.of(StatisticsCalculator.compute(list, from, to, 70, 180, zone))
        assertEquals(ControlLevel.GOOD, summary.level)
        assertTrue(summary.sentence.contains("100 % of the time in range"))
        assertTrue(summary.trustworthy)
    }

    @Test
    fun aDayWithLowsIsCriticalAndSaysWhereTheFigureComesFrom() {
        val summary = ControlSummaryOf.of(stats())
        assertEquals(ControlLevel.CRITICAL, summary.level)
        assertTrue("the sentence names the share below", summary.sentence.contains("below"))
    }

    @Test
    fun aSwingsDayNeedsAttention() {
        val list = ArrayList<Glucose>()
        repeat(96) { i ->
            // half the quarter hours under the range, half inside it: 50 % in range
            list.add(Glucose(from + i * 15 * 60_000L, if (i % 2 == 0) 110 else 200, 0.0, true, null))
        }
        val summary = ControlSummaryOf.of(StatisticsCalculator.compute(list, from, to, 70, 180, zone))
        assertTrue(summary.level == ControlLevel.NEEDS_ATTENTION || summary.level == ControlLevel.POOR)
    }

    @Test
    fun tooLittleDataIsNeverCalledGood() {
        val list = ArrayList<Glucose>()
        repeat(3) { list.add(Glucose(to - 30 * 60_000L + it * 5 * 60_000L, 110, 0.0, true, null)) }
        val summary = ControlSummaryOf.of(StatisticsCalculator.compute(list, from, to, 70, 180, zone))
        assertFalse(summary.judged)
        assertFalse(summary.trustworthy)
        assertEquals("Insufficient data", summary.word)
        assertFalse(summary.level == ControlLevel.GOOD)
    }

    // ------------------------------------------------------------ the estimates

    private fun line(start: Double, perMinute: Double, count: Int, scatter: Double = 0.4): Forecast =
        Forecast(
            time = now,
            count = count,
            spanMinutes = count - 1,
            slope = perMinute,
            level = start + perMinute * (count - 1),
            scatter = scatter
        )

    @Test
    fun confidenceIsHighForATightLongLine() {
        val confidence = PredictionConfidence.of(line(100.0, 0.0, 14))
        assertEquals(PredictionConfidenceWord.HIGH, confidence.word)
        assertTrue(confidence.score >= 70)
    }

    @Test
    fun confidenceIsLowWhenTheValuesJumpAbout() {
        val confidence = PredictionConfidence.of(line(100.0, 0.0, 12, scatter = 9.0))
        assertEquals(PredictionConfidenceWord.LOW, confidence.word)
    }

    @Test
    fun threeReadingsCannotEarnHighConfidence() {
        assertTrue(PredictionConfidence.of(line(100.0, 0.0, 3)).word != PredictionConfidenceWord.HIGH)
    }

    @Test
    fun theTimeToTheLowLimitComesFromTheSlope() {
        // A line at 100 now, falling one mg/dL a minute: 70 is thirty minutes away.
        val falling = Forecast(time = now, count = 12, spanMinutes = 11, slope = -1.0, level = 100.0, scatter = 0.3)
        assertEquals(30, Thresholds.minutesTo(falling, now, 70))
        // A line at 150 now, rising one a minute: 180 is thirty minutes away.
        val rising = Forecast(time = now, count = 12, spanMinutes = 11, slope = 1.0, level = 150.0, scatter = 0.3)
        assertEquals(30, Thresholds.minutesTo(rising, now, 180))
    }

    @Test
    fun aLineThatBarelyMovesNamesNoTime() {
        val flat = Forecast(time = now, count = 12, spanMinutes = 11, slope = 0.1, level = 120.0, scatter = 0.3)
        assertNull(Thresholds.minutesTo(flat, now, 70))
        assertNull(Thresholds.minutesTo(flat, now, 180))
    }

    @Test
    fun aLimitBeyondTheThreeHourWindowIsNotNamed() {
        // a line at 120 falling 0.2 a minute needs 250 minutes to reach 70
        val slow = Forecast(time = now, count = 12, spanMinutes = 11, slope = -0.2, level = 120.0, scatter = 0.3)
        assertNull(Thresholds.minutesTo(slow, now, 70))
    }

    @Test
    fun theFirstCrossingCarriesItsLimitAndDirection() {
        val falling = Forecast(time = now, count = 12, spanMinutes = 11, slope = -1.0, level = 100.0, scatter = 0.3)
        val crossings = Thresholds.crossings(falling, now, 70, 180)
        assertEquals(1, crossings.size)
        assertEquals(70, crossings[0].limit)
        assertFalse(crossings[0].rising)
        assertNotNull(crossings[0].minutes)
    }

    // ------------------------------------------------------------ the three horizons

    @Test
    fun theFifteenAndThirtyMinuteHorizonsAreTheLineUntouched() {
        val now = 1_700_000_000_000L / 60_000L * 60_000L
        val forecast = Forecast(time = now, count = 12, spanMinutes = 11, slope = -1.0, level = 120.0, scatter = 0.4)
        val fifteen = Horizons.at(forecast, now, 15, 140.0, 70, 180)
        val thirty = Horizons.at(forecast, now, 30, 140.0, 70, 180)
        // a straight line: 15 minutes from 120 at one a minute is 105, and 30 minutes is 90
        assertEquals(105, fifteen.value)
        assertEquals(90, thirty.value)
        assertEquals(15 * 60_000L, fifteen.at - now)
        assertEquals(30 * 60_000L, thirty.at - now)
        assertEquals(GlucoseBand.IN_RANGE, fifteen.band)
        assertEquals(GlucoseBand.IN_RANGE, thirty.band)
    }

    @Test
    fun theSixtyMinuteHorizonBendsTowardsTheAverageInsteadOfRunningAway() {
        val now = 1_700_000_000_000L / 60_000L * 60_000L
        // falling fast: a straight line would say 30 at an hour, which no sensor would keep up with
        val forecast = Forecast(time = now, count = 12, spanMinutes = 11, slope = -1.5, level = 120.0, scatter = 0.4)
        val straight = forecast.valueAt(now + 60 * 60_000L)
        val sixty = Horizons.at(forecast, now, 60, 130.0, 70, 180)
        // the straight line gives 120 - 90 = 30 at an hour; a sensor cannot read below 39
        assertEquals(Glucose.MIN_READABLE_GLUCOSE, straight)
        assertTrue("the hour must be closer to the average than a straight line would be", sixty.value > straight)
        assertTrue("but it must still be below the average", sixty.value < 130)
        // with no mean to bend towards, the plain line is used and nothing is invented
        assertEquals(straight, Horizons.at(forecast, now, 60, null, 70, 180).value)
    }

    @Test
    fun theSixtyMinuteHorizonNeverLeavesWhatASensorCanRead() {
        val now = 1_700_000_000_000L / 60_000L * 60_000L
        val falling = Forecast(time = now, count = 12, spanMinutes = 11, slope = -3.0, level = 120.0, scatter = 0.4)
        val low = Horizons.at(falling, now, 60, 120.0, 70, 180)
        assertTrue("never below what a sensor reads", low.value >= Glucose.MIN_READABLE_GLUCOSE)
        assertEquals(GlucoseBand.VERY_LOW, low.band)
    }

    @Test
    fun eachHorizonCarriesItsOwnArrow() {
        val now = 1_700_000_000_000L / 60_000L * 60_000L
        val rising = Forecast(time = now, count = 12, spanMinutes = 11, slope = 1.0, level = 100.0, scatter = 0.3)
        assertTrue("a rising line points up", Horizons.at(rising, now, 15, 100.0, 70, 180).arrow.contains("\u2191"))
        val flat = Forecast(time = now, count = 12, spanMinutes = 11, slope = 0.0, level = 100.0, scatter = 0.3)
        assertEquals("\u2192", Horizons.at(flat, now, 15, 100.0, 70, 180).arrow)
    }
}
