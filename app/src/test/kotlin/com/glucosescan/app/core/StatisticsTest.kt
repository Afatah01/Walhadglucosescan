package com.glucosescan.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class StatisticsTest {
    private val minute = 60_000L
    private val quarter = 15 * minute
    private val day = 24 * 60 * minute
    private val zone = TimeZone.getTimeZone("Africa/Mogadishu") // 3 hours ahead of UTC, no summer time

    /** Midnight in Mogadishu, 1 January 2026. */
    private val midnight = 1_767_214_800_000L

    private fun value(time: Long, mgdl: Int, fromTrend: Boolean = true) = Glucose(time, mgdl, null, fromTrend, "S")

    private fun compute(values: List<Glucose>, from: Long = midnight, to: Long = midnight + day, low: Int = 80, high: Int = 180) =
        StatisticsCalculator.compute(values, from, to, low, high, zone)

    @Test
    fun midnightIsMidnight() {
        assertEquals(0L, (midnight + zone.getOffset(midnight)) % day)
    }

    @Test
    fun withoutValues() {
        val s = compute(emptyList())
        assertFalse(s.hasValues)
        assertEquals(0, s.slots)
        assertEquals(96, s.possibleSlots)
        assertEquals(0, s.coveragePercent)
        assertNull(s.mean)
        assertNull(s.gmi)
        assertNull(s.sd)
        assertNull(s.cv)
        assertNull(s.lowest)
        assertNull(s.highest)
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0), s.rangePercents)
        assertEquals(0, s.inTargetPercent)
        assertEquals(24, s.hours.size)
        assertTrue(s.hours.all { it == null })
        assertFalse(s.gmiIsReliable)
    }

    /** The 15 one-minute values around a scan must not count 15 times more than a 15-minute value. */
    @Test
    fun everyQuarterHourCountsOnce() {
        val values = ArrayList<Glucose>()
        for (k in 0 until 15) {
            values.add(value(midnight + 12 * 60 * minute + k * minute, 100))
        }
        values.add(value(midnight + 12 * 60 * minute + quarter, 200, fromTrend = false))

        val s = compute(values)
        assertEquals(2, s.slots)
        assertEquals("not (15 x 100 + 200) / 16", 150.0, s.mean!!, 1e-9)
        assertArrayEquals(intArrayOf(0, 0, 1, 1, 0), s.rangeCounts)
        assertArrayEquals(intArrayOf(0, 0, 50, 50, 0), s.rangePercents)
        assertEquals(100, s.lowest)
        assertEquals(200, s.highest)
        assertEquals(1, s.days)
    }

    @Test
    fun aQuarterHourTakesTheMeanOfItsValues() {
        val s = compute(listOf(value(midnight, 100), value(midnight + 5 * minute, 110), value(midnight + 14 * minute, 120)))
        assertEquals(1, s.slots)
        assertEquals(110.0, s.mean!!, 1e-9)
        assertNull("one quarter hour has no spread", s.sd)
    }

    @Test
    fun theLimitsOfTheFiveRanges() {
        val expected = mapOf(
            39 to 0, 53 to 0, 54 to 1, 69 to 1, 70 to 2, 100 to 2, 180 to 2, 181 to 3, 250 to 3, 251 to 4, 501 to 4
        )
        for ((mgdl, range) in expected) {
            assertEquals("$mgdl mg/dL", range, StatisticsCalculator.rangeOf(mgdl.toDouble()))
        }
        val values = listOf(50, 60, 100, 200, 300).mapIndexed { k, mgdl -> value(midnight + k * quarter, mgdl) }
        val s = compute(values)
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 1), s.rangeCounts)
        assertArrayEquals(intArrayOf(20, 20, 20, 20, 20), s.rangePercents)
    }

    @Test
    fun theTargetRangeOfTheUser() {
        val values = listOf(79, 80, 120, 180, 181, 60).mapIndexed { k, mgdl -> value(midnight + k * quarter, mgdl) }
        val s = compute(values, low = 80, high = 180)
        assertEquals(3, s.inTarget)
        assertEquals(50, s.inTargetPercent)
        assertEquals(4, compute(values, low = 70, high = 180).inTarget)
    }

    @Test
    fun percentsAlwaysAddUpTo100() {
        assertArrayEquals(intArrayOf(34, 33, 33), StatisticsCalculator.wholePercents(intArrayOf(1, 1, 1)))
        assertArrayEquals(intArrayOf(17, 17, 17, 17, 16, 16), StatisticsCalculator.wholePercents(intArrayOf(1, 1, 1, 1, 1, 1)))
        assertArrayEquals(intArrayOf(0, 100), StatisticsCalculator.wholePercents(intArrayOf(0, 7)))
        assertArrayEquals(intArrayOf(0, 0), StatisticsCalculator.wholePercents(intArrayOf(0, 0)))
        assertArrayEquals(intArrayOf(1, 99), StatisticsCalculator.wholePercents(intArrayOf(1, 199)))
        for (a in 0..12) {
            for (b in 0..12) {
                for (c in 0..12) {
                    if (a + b + c > 0) {
                        assertEquals(100, StatisticsCalculator.wholePercents(intArrayOf(a, b, c, 3, 1)).sum())
                    }
                }
            }
        }
    }

    @Test
    fun meanSpreadAndEstimatedA1c() {
        val values = listOf(100, 120, 140, 160, 180).mapIndexed { k, mgdl -> value(midnight + k * quarter, mgdl) }
        val s = compute(values)
        assertEquals(140.0, s.mean!!, 1e-9)
        assertEquals(Math.sqrt(1000.0), s.sd!!, 1e-9) // squares: 1600 + 400 + 0 + 400 + 1600 = 4000; / 4
        assertEquals(Math.sqrt(1000.0) / 140.0 * 100.0, s.cv!!, 1e-9)
        assertEquals(3.31 + 0.02392 * 140.0, s.gmi!!, 1e-9)
        assertEquals("a mean of 154 mg/dL stands for about 7 percent", 7.0, StatisticsCalculator.gmi(154.0), 0.01)
        assertEquals(100, s.lowest)
        assertEquals(180, s.highest)
    }

    @Test
    fun valuesOutsideWhatTheSensorMeasuresCountAsItsLimits() {
        val s = compute(listOf(value(midnight, 20), value(midnight + quarter, 700)))
        assertEquals(39, s.lowest)
        assertEquals(501, s.highest)
        assertEquals(270.0, s.mean!!, 1e-9)
        assertArrayEquals(intArrayOf(1, 0, 0, 0, 1), s.rangeCounts)
    }

    @Test
    fun onlyValuesOfThePeriodCount() {
        val values = listOf(
            value(midnight - minute, 300),
            value(midnight, 100),
            value(midnight + day - minute, 110),
            value(midnight + day, 300)
        )
        val s = compute(values)
        assertEquals(2, s.slots)
        assertEquals(105.0, s.mean!!, 1e-9)
    }

    @Test
    fun howMuchOfThePeriodHasValues() {
        val values = ArrayList<Glucose>()
        for (k in 0 until 48) {
            values.add(value(midnight + k * quarter, 100))
        }
        assertEquals(50, compute(values).coveragePercent)
        assertEquals(96, compute(values).possibleSlots)
        assertEquals(7 * 96, compute(values, from = midnight - 6 * day).possibleSlots)
        assertEquals(7, compute(values, from = midnight - 6 * day).coveragePercent)
    }

    @Test
    fun daysAreCountedBackFromTheEndOfThePeriod() {
        // two days that end at 14:32: they touch three days of the calendar
        val to = midnight + 2 * day + 14 * 60 * minute + 32 * minute
        val from = to - 2 * day
        val values = ArrayList<Glucose>()
        var time = from
        while (time < to) {
            values.add(value(time, 100, fromTrend = false))
            time += quarter
        }
        val all = compute(values, from = from, to = to)
        assertEquals("two days, not three", 2, all.days)
        assertEquals(100, all.coveragePercent)

        // values only in the last 24 hours
        assertEquals(1, compute(values.filter { it.timestamp >= to - day }, from = from, to = to).days)
        // one value in the first minute and one in the last
        assertEquals(2, compute(listOf(value(from, 100), value(to - minute, 100)), from = from, to = to).days)
        assertEquals(0, compute(emptyList(), from = from, to = to).days)
    }

    @Test
    fun theTypicalDayGoesByTheLocalHour() {
        val values = ArrayList<Glucose>()
        // 08:00 on five days: 100, 110, 120, 130, 140
        for (d in 0 until 5) {
            values.add(value(midnight + d * day + 8 * 60 * minute, 100 + d * 10))
        }
        // 22:30 on two days only
        values.add(value(midnight + 22 * 60 * minute + 30 * minute, 150))
        values.add(value(midnight + day + 22 * 60 * minute + 30 * minute, 160))

        val s = compute(values, to = midnight + 5 * day)
        assertEquals(5, s.days)
        val eight = s.hours[8]
        assertNotNull(eight)
        assertEquals(120.0, eight!!.median, 1e-9)
        assertEquals(104.0, eight.low, 1e-9)
        assertEquals(136.0, eight.high, 1e-9)
        assertEquals(5, eight.count)
        assertNull("two values are too few for a typical hour", s.hours[22])
        assertNull(s.hours[5])
        assertNull("it is 08:00 in Mogadishu, not 08:00 UTC", s.hours[11])
    }

    @Test
    fun percentiles() {
        val list = listOf(10.0, 20.0, 30.0, 40.0)
        assertEquals(10.0, StatisticsCalculator.percentile(list, 0.0), 1e-9)
        assertEquals(40.0, StatisticsCalculator.percentile(list, 1.0), 1e-9)
        assertEquals(25.0, StatisticsCalculator.percentile(list, 0.5), 1e-9)
        assertEquals(13.0, StatisticsCalculator.percentile(list, 0.1), 1e-9)
        assertEquals(7.0, StatisticsCalculator.percentile(listOf(7.0), 0.9), 1e-9)
        assertTrue(StatisticsCalculator.percentile(emptyList(), 0.5).isNaN())
    }

    @Test
    fun theEstimateNeedsTwoFullWeeks() {
        val twoWeeks = ArrayList<Glucose>()
        for (k in 0 until 14 * 96) {
            if (k % 10 < 8) { // 80 percent of the time
                twoWeeks.add(value(midnight + k * quarter, 120))
            }
        }
        assertTrue(compute(twoWeeks, to = midnight + 14 * day).gmiIsReliable)
        assertFalse("only a week", compute(twoWeeks, to = midnight + 7 * day).gmiIsReliable)
        val thin = twoWeeks.filterIndexed { index, _ -> index % 2 == 0 }
        assertFalse("values on 40 percent of the time", compute(thin, to = midnight + 14 * day).gmiIsReliable)
    }
}
