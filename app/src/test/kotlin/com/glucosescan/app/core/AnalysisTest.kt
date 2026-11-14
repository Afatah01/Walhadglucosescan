package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import java.util.TreeMap

class AnalysisTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val quarter = 15 * minute
    private val dayLength = 24 * hour
    private val zone = TimeZone.getTimeZone("Africa/Mogadishu") // three hours before London time, all year

    /** Tuesday 10 November 2026, 0:00 local time (UTC+3). */
    private val midnight = 1_794_258_000_000L

    /** A moment by the local clock of the tests (UTC+3). [day] 0 is 10 November, -1 the day before. */
    private fun at(day: Int, hourOfDay: Int, minuteOfHour: Int = 0): Long = midnight + day * dayLength + hourOfDay * hour + minuteOfHour * minute

    private fun value(time: Long, mgdl: Int): Glucose = Glucose(time, mgdl, null, false, "A")

    /** One value in the middle of each quarter hour from [from] to [to]. [mgdl] gets the hour of the day; null leaves a hole. */
    private fun quarters(from: Long, to: Long, mgdl: (Int) -> Int?): List<Glucose> {
        val list = ArrayList<Glucose>()
        var start = from
        while (start < to) {
            val hourOfDay = (((start - midnight) % dayLength + dayLength) % dayLength / hour).toInt()
            mgdl(hourOfDay)?.let { list.add(value(start + 7 * minute, it)) }
            start += quarter
        }
        return list
    }

    private fun kinds(analysis: Analysis): List<FindingKind> = analysis.findings.map { it.kind }

    private fun finding(analysis: Analysis, kind: FindingKind): Finding = analysis.findings.first { it.kind == kind }

    // ------------------------------------------------------------------ the last hours

    @Test
    fun theLastSixHours() {
        val to = at(0, 14, 30)
        val from = to - 6 * hour
        // four hours at 110, one hour at 60, one hour at 200: a value each minute
        val values = ArrayList<Glucose>()
        for (k in 0 until 360) {
            values.add(value(from + k * minute, if (k < 240) 110 else if (k < 300) 60 else 200))
        }
        values.add(value(from - minute, 300)) // before the stretch
        values.add(value(to, 20)) // the end is not part of it
        val span = AnalysisCalculator.span(values.shuffled(java.util.Random(1)), from, to, 80, 180, zone)

        assertTrue(span.hasValues)
        assertEquals(24, span.stats.slots)
        assertEquals(100, span.stats.coveragePercent)
        assertEquals((16 * 110 + 4 * 60 + 4 * 200) / 24.0, span.stats.mean!!, 1e-9)
        assertEquals(400.0 / 6, span.inRange, 1e-9)
        assertEquals(100.0 / 6, span.under70, 1e-9)
        assertEquals(100.0 / 6, span.over180, 1e-9)

        assertEquals(60, span.lowest!!.value)
        assertEquals("the first of several equal values", from + 240 * minute, span.lowest!!.timestamp)
        assertEquals(200, span.highest!!.value)
        assertEquals(from + 300 * minute, span.highest!!.timestamp)
        assertEquals(from, span.first!!.timestamp)
        assertEquals(to - minute, span.last!!.timestamp)

        assertEquals(1, span.lows.size)
        assertEquals(from + 240 * minute, span.lows[0].start)
        assertEquals(from + 300 * minute, span.lows[0].end)
        assertEquals(60, span.lows[0].minutes)
        assertEquals(4, span.lows[0].quarters)
        assertEquals(60.0, span.lows[0].extreme, 1e-9)
        assertEquals(1, span.highs.size)
        assertEquals(200.0, span.highs[0].extreme, 1e-9)
        assertEquals(to, span.highs[0].end)

        assertEquals(Verdict.LOWS, Verdict.of(span))
    }

    @Test
    fun aStretchWithoutValues() {
        val span = AnalysisCalculator.span(emptyList(), at(0, 8), at(0, 14), 70, 180, zone)
        assertFalse(span.hasValues)
        assertNull(span.lowest)
        assertNull(span.highest)
        assertNull(span.first)
        assertNull(span.last)
        assertTrue(span.lows.isEmpty())
        assertTrue(span.highs.isEmpty())
        assertEquals(0.0, span.inRange, 0.0)
        assertNull(Verdict.of(span))
    }

    @Test
    fun valuesTheSensorCannotMeasureCountAsItsLimits() {
        val from = at(0, 8)
        val span = AnalysisCalculator.span(listOf(value(from, 20), value(from + quarter, 600)), from, from + hour, 70, 180, zone)
        assertEquals(39, span.stats.lowest)
        assertEquals(501, span.stats.highest)
        assertEquals(20, span.lowest!!.value)
        assertEquals(Verdict.VERY_LOW, Verdict.of(span))
    }

    @Test
    fun theVerdictInOneWord() {
        val from = at(0, 8)
        fun verdict(vararg mgdl: Int): Verdict? {
            val values = mgdl.mapIndexed { k, v -> value(from + k * quarter, v) }
            return Verdict.of(AnalysisCalculator.span(values, from, from + mgdl.size * quarter, 70, 180, zone))
        }
        assertEquals(Verdict.STEADY, verdict(100, 100, 100, 100, 100, 100, 100, 100, 100, 100))
        assertEquals("90 percent in range is steady", Verdict.STEADY, verdict(100, 100, 100, 100, 100, 100, 100, 100, 100, 200))
        assertEquals(Verdict.IN_RANGE, verdict(100, 100, 100, 100, 100, 100, 100, 100, 200, 200))
        assertEquals("a quarter of the time over 180", Verdict.OFTEN_HIGH, verdict(100, 100, 100, 200, 100, 100, 100, 100, 100, 100, 200, 200))
        assertEquals(Verdict.OFTEN_HIGH, verdict(100, 100, 100, 100, 100, 100, 100, 200, 200, 200))
        assertEquals("one low quarter hour is named, however good the rest", Verdict.LOWS, verdict(100, 100, 100, 100, 100, 100, 100, 100, 100, 69))
        assertEquals(Verdict.LOWS, verdict(54, 200, 200, 200))
        assertEquals(Verdict.VERY_LOW, verdict(100, 100, 100, 100, 100, 100, 100, 100, 100, 53))
        assertEquals("70 and 180 are in range", Verdict.STEADY, verdict(70, 180))

        assertEquals(FindingLevel.URGENT, Verdict.VERY_LOW.level)
        assertEquals(FindingLevel.ATTENTION, Verdict.LOWS.level)
        assertEquals(FindingLevel.ATTENTION, Verdict.OFTEN_HIGH.level)
        assertEquals(FindingLevel.GOOD, Verdict.IN_RANGE.level)
        assertEquals(FindingLevel.GOOD, Verdict.STEADY.level)
    }

    @Test
    fun aQuarterHourCountsByItsMean() {
        // 14 minutes at 100 and one single minute at 40 are not a low: the mean of the quarter hour is 96
        val from = at(0, 8)
        val values = (0 until 15).map { value(from + it * minute, if (it == 7) 40 else 100) }
        val span = AnalysisCalculator.span(values, from, from + quarter, 70, 180, zone)
        assertEquals(96.0, span.stats.mean!!, 1e-9)
        assertTrue(span.lows.isEmpty())
        assertEquals(40, span.lowest!!.value)
        assertEquals(Verdict.STEADY, Verdict.of(span))
    }

    // ------------------------------------------------------------------ hour by hour

    @Test
    fun hourByHour() {
        val from = at(0, 9, 20)
        val to = at(0, 12, 10)
        val values = ArrayList<Glucose>()
        // 9:20 to 10:00: 40 values of 90
        for (k in 0 until 40) values.add(value(from + k * minute, 90))
        // 10 o'clock: a quarter hour of 100, one single value of 200, two quarter hours of 100
        for (k in 0 until 15) values.add(value(at(0, 10, k), 100))
        values.add(value(at(0, 10, 20), 200))
        for (k in 30 until 60) values.add(value(at(0, 10, k), 100))
        // 11 o'clock: nothing. 12:00 to 12:10: 150, and one value at 12:10 that is outside
        for (k in 0 until 10) values.add(value(at(0, 12, k), 150))
        values.add(value(to, 400))

        val hours = AnalysisCalculator.hours(values, from, to, zone)
        assertEquals(listOf(9, 10, 11, 12), hours.map { it.hourOfDay })
        assertEquals(listOf(at(0, 9), at(0, 10), at(0, 11), at(0, 12)), hours.map { it.start })
        assertEquals(listOf(40, 46, 0, 10), hours.map { it.count })

        assertEquals(90.0, hours[0].mean!!, 1e-9)
        assertEquals("the four quarter hours count the same, not the 46 values", 125.0, hours[1].mean!!, 1e-9)
        assertEquals(100, hours[1].lowest)
        assertEquals(200, hours[1].highest)
        assertNull(hours[2].mean)
        assertNull(hours[2].lowest)
        assertNull(hours[2].highest)
        assertEquals(150.0, hours[3].mean!!, 1e-9)
        assertEquals(150, hours[3].highest)
    }

    @Test
    fun theHoursOfAWholeDay() {
        val hours = AnalysisCalculator.hours(emptyList(), at(-1, 0), at(0, 0), zone)
        assertEquals((0..23).toList(), hours.map { it.hourOfDay })
        assertTrue(hours.all { it.count == 0 && it.mean == null })
        assertTrue(AnalysisCalculator.hours(emptyList(), at(0, 0), at(0, 0), zone).isEmpty())
        assertTrue(AnalysisCalculator.hours(emptyList(), at(0, 5), at(0, 4), zone).isEmpty())
        assertEquals("the end is not part of the stretch", listOf(4), AnalysisCalculator.hours(emptyList(), at(0, 4), at(0, 5), zone).map { it.hourOfDay })
    }

    @Test
    fun hoursWhereTheClockIsHalfAnHourOff() {
        // India: five and a half hours before London time
        val india = TimeZone.getTimeZone("Asia/Kolkata")
        val nineInDelhi = 1_794_268_800_000L + 3 * hour + 30 * minute // 10 November 2026, 3:30 in London, 9:00 in Delhi
        val values = listOf(value(nineInDelhi + 5 * minute, 100), value(nineInDelhi + 59 * minute, 140), value(nineInDelhi + 60 * minute, 180))
        val hours = AnalysisCalculator.hours(values, nineInDelhi, nineInDelhi + 2 * hour, india)
        assertEquals(listOf(9, 10), hours.map { it.hourOfDay })
        assertEquals(listOf(nineInDelhi, nineInDelhi + hour), hours.map { it.start })
        assertEquals(listOf(2, 1), hours.map { it.count })
        assertEquals(120.0, hours[0].mean!!, 1e-9)
        assertEquals(180.0, hours[1].mean!!, 1e-9)
    }

    // ------------------------------------------------------------------ day by day

    @Test
    fun dayByDay() {
        val now = at(0, 14, 32)
        val values = ArrayList<Glucose>()
        values.addAll(quarters(at(0, 0), at(0, 14, 30), { 120 })) // today until now
        values.addAll(quarters(at(-1, 0), at(0, 0), { h -> if (h in 12..14) 220 else 150 })) // yesterday, whole
        // the day before yesterday: nothing. Three days ago: only the morning, with a low
        values.addAll(quarters(at(-3, 6), at(-3, 12), { h -> if (h == 8) 60 else 100 }))
        values.add(value(now + 10 * minute, 500)) // after "now": not counted

        val days = AnalysisCalculator.days(values, now, 4, now, 70, 180, zone)
        assertEquals(4, days.size)
        assertEquals(listOf(at(0, 0), at(-1, 0), at(-2, 0), at(-3, 0)), days.map { it.start })
        assertEquals(listOf(at(1, 0), at(0, 0), at(-1, 0), at(-2, 0)), days.map { it.end })

        val today = days[0]
        assertEquals("today ends now", now, today.stats.to)
        assertEquals(58, today.stats.slots)
        assertEquals(100, today.inRangePercent)
        assertEquals(120, today.stats.highest)
        assertEquals(0, today.lows + today.highs)

        val yesterday = days[1]
        assertEquals(96, yesterday.stats.slots)
        assertEquals(100, yesterday.stats.coveragePercent)
        assertEquals(88, yesterday.inRangePercent) // 84 of 96 quarter hours: 87.5
        assertEquals(1, yesterday.highs)
        assertEquals(0, yesterday.lows)
        assertEquals((84 * 150 + 12 * 220) / 96.0, yesterday.stats.mean!!, 1e-9)

        assertFalse(days[2].hasValues)
        assertEquals(0, days[2].inRangePercent)

        assertEquals(24, days[3].stats.slots)
        assertEquals(25, days[3].stats.coveragePercent)
        assertEquals(1, days[3].lows)
        assertEquals(83, days[3].inRangePercent)
    }

    @Test
    fun anEventOverMidnightCountsInBothDays() {
        val values = quarters(at(-1, 22), at(0, 2), { h -> if (h == 23 || h == 0) 60 else 100 })
        val days = AnalysisCalculator.days(values, at(0, 12), 2, at(0, 12), 70, 180, zone)
        assertEquals(listOf(1, 1), days.map { it.lows })
        // as one stretch it is one event of two hours
        val span = AnalysisCalculator.span(values, at(-1, 22), at(0, 2), 70, 180, zone)
        assertEquals(1, span.lows.size)
        assertEquals(120, span.lows[0].minutes)
    }

    @Test
    fun aDayOnWhichTheClockIsSetBackHasTwentyFiveHours() {
        val berlin = TimeZone.getTimeZone("Europe/Berlin")
        val midnightBerlin = 1_792_879_200_000L // Sunday 25 October 2026, 0:00 in Berlin (22:00 in London the evening before)
        assertEquals(midnightBerlin, AnalysisCalculator.dayStart(midnightBerlin, berlin))
        assertEquals(midnightBerlin, AnalysisCalculator.dayStart(midnightBerlin + 24 * hour + 59 * minute, berlin))
        val next = AnalysisCalculator.addDays(midnightBerlin, 1, berlin)
        assertEquals(midnightBerlin + 25 * hour, next)
        assertEquals(midnightBerlin - 24 * hour, AnalysisCalculator.addDays(midnightBerlin, -1, berlin))
        assertEquals(next + 24 * hour, AnalysisCalculator.addDays(midnightBerlin, 2, berlin))

        val days = AnalysisCalculator.days(emptyList(), midnightBerlin + 5 * hour, 2, next + dayLength, 70, 180, berlin)
        assertEquals(100, days[0].stats.possibleSlots)
        assertEquals(96, days[1].stats.possibleSlots)

        // The clock shows the hour from 2 to 3 twice on this day. The list keeps one entry for
        // each hour of the clock, so "2 o'clock" holds the values of both.
        val values = (0 until 25).map { value(midnightBerlin + it * hour + 10 * minute, 100 + it) }
        val hours = AnalysisCalculator.hours(values, midnightBerlin, next, berlin)
        assertEquals((0..23).toList(), hours.map { it.hourOfDay })
        assertEquals(listOf(1, 1, 2, 1, 1), hours.take(5).map { it.count })
        assertEquals(102, hours[2].lowest)
        assertEquals(103, hours[2].highest)
        assertEquals(102.5, hours[2].mean!!, 1e-9)
        assertEquals(104, hours[3].lowest)
        assertEquals(124, hours[23].highest)
        assertEquals(25, hours.sumOf { it.count })
        assertEquals(midnightBerlin, hours[0].start)
        assertEquals(next - hour, hours[23].start)
    }

    @Test
    fun aDayOnWhichTheClockIsSetForwardHasTwentyThreeHours() {
        val berlin = TimeZone.getTimeZone("Europe/Berlin")
        val midnightBerlin = 1_774_738_800_000L // Sunday 29 March 2026, 0:00 in Berlin (23:00 in London the evening before)
        val next = AnalysisCalculator.addDays(midnightBerlin, 1, berlin)
        assertEquals(midnightBerlin + 23 * hour, next)
        assertEquals(92, AnalysisCalculator.days(emptyList(), midnightBerlin, 1, next, 70, 180, berlin)[0].stats.possibleSlots)

        // the hour from 2 to 3 does not exist on this day: its entry stays empty
        val values = (0 until 23).map { value(midnightBerlin + it * hour + 10 * minute, 100 + it) }
        val hours = AnalysisCalculator.hours(values, midnightBerlin, next, berlin)
        assertEquals((0..23).toList(), hours.map { it.hourOfDay })
        assertEquals(0, hours[2].count)
        assertNull(hours[2].mean)
        assertEquals(101, hours[1].lowest)
        assertEquals(102, hours[3].lowest)
        assertEquals(122, hours[23].lowest)
        assertEquals(23, hours.sumOf { it.count })
    }

    @Test
    fun dayStartInTheLocalTimeZone() {
        assertEquals(midnight, AnalysisCalculator.dayStart(midnight, zone))
        assertEquals(midnight, AnalysisCalculator.dayStart(at(0, 23, 59) + 59_999, zone))
        assertEquals(midnight - dayLength, AnalysisCalculator.dayStart(midnight - 1, zone))
        assertEquals(at(-30, 0), AnalysisCalculator.addDays(midnight, -30, zone))
    }

    // ------------------------------------------------------------------ low and high events

    @Test
    fun events() {
        fun means(vararg pairs: Pair<Long, Double>): TreeMap<Long, Double> = TreeMap(pairs.toMap())
        val slot = 15 * minute

        // 70 is not low, 180 is not high
        assertTrue(AnalysisCalculator.episodes(means(1L to 70.0, 2L to 180.0), lows = true).isEmpty())
        assertTrue(AnalysisCalculator.episodes(means(1L to 70.0, 2L to 180.0), lows = false).isEmpty())

        // one quarter hour is an event of 15 minutes
        val single = AnalysisCalculator.episodes(means(10L to 69.9), lows = true)
        assertEquals(1, single.size)
        assertEquals(10 * slot, single[0].start)
        assertEquals(11 * slot, single[0].end)
        assertEquals(15, single[0].minutes)
        assertEquals(1, single[0].quarters)

        // one quarter hour without values does not end an event; a quarter hour in range does
        val lows = AnalysisCalculator.episodes(means(10L to 65.0, 11L to 50.0, 13L to 60.0, 14L to 90.0, 15L to 68.0), lows = true)
        assertEquals(2, lows.size)
        assertEquals(10 * slot, lows[0].start)
        assertEquals(14 * slot, lows[0].end)
        assertEquals(60, lows[0].minutes)
        assertEquals("three quarter hours have values", 3, lows[0].quarters)
        assertEquals(50.0, lows[0].extreme, 0.0)
        assertEquals(15 * slot, lows[1].start)
        assertEquals(68.0, lows[1].extreme, 0.0)

        // two quarter hours without values end it
        val apart = AnalysisCalculator.episodes(means(10L to 200.0, 13L to 260.0, 14L to 190.0), lows = false)
        assertEquals(2, apart.size)
        assertEquals(15, apart[0].minutes)
        assertEquals(30, apart[1].minutes)
        assertEquals(260.0, apart[1].extreme, 0.0)

        assertTrue(AnalysisCalculator.episodes(TreeMap(), lows = true).isEmpty())
    }

    // ------------------------------------------------------------------ parts of the day

    @Test
    fun thePartsOfTheDay() {
        assertEquals(DayPart.NIGHT, DayPart.of(0))
        assertEquals(DayPart.NIGHT, DayPart.of(5))
        assertEquals(DayPart.MORNING, DayPart.of(6))
        assertEquals(DayPart.AFTERNOON, DayPart.of(12))
        assertEquals(DayPart.EVENING, DayPart.of(18))
        assertEquals(DayPart.EVENING, DayPart.of(23))

        val values = quarters(at(-1, 0), at(0, 0), { h ->
            when {
                h < 6 -> 100
                h < 12 -> 150
                h in 12..14 -> 200
                h < 18 -> 170
                h == 20 -> 60
                else -> 120
            }
        })
        val analysis = AnalysisCalculator.compute(values, at(-1, 0), at(0, 0), 70, 180, zone)
        val parts = analysis.parts
        assertEquals(listOf(DayPart.NIGHT, DayPart.MORNING, DayPart.AFTERNOON, DayPart.EVENING), parts.map { it.part })
        assertEquals(listOf(24, 24, 24, 24), parts.map { it.quarters })
        assertEquals(100.0, parts[0].mean!!, 1e-9)
        assertEquals(150.0, parts[1].mean!!, 1e-9)
        assertEquals(185.0, parts[2].mean!!, 1e-9)
        assertEquals(110.0, parts[3].mean!!, 1e-9)
        assertEquals(listOf(0, 0, 50, 0), parts.map { it.abovePercent })
        assertEquals(listOf(100, 100, 50, 83), parts.map { it.inRangePercent })
        assertEquals(listOf(0, 0, 0, 17), parts.map { it.belowPercent })
        assertEquals(listOf(0, 0, 0, 1), parts.map { it.lowEvents })
        assertEquals(listOf(0, 0, 1, 0), parts.map { it.highEvents })

        // a part without values
        val morningOnly = AnalysisCalculator.compute(values.filter { it.timestamp < at(-1, 6) }, at(-1, 0), at(0, 0), 70, 180, zone)
        assertNull(morningOnly.parts[2].mean)
        assertEquals(0, morningOnly.parts[2].quarters)
        assertEquals(0, morningOnly.parts[2].inRangePercent)
    }

    // ------------------------------------------------------------------ goals

    @Test
    fun theGoalsOfACalmDay() {
        val values = quarters(at(-1, 0), at(0, 0), { h -> 100 + (h % 4) * 10 })
        val analysis = AnalysisCalculator.compute(values, at(-1, 0), at(0, 0), 70, 180, zone)
        assertEquals(
            listOf(GoalKind.IN_RANGE, GoalKind.LOW, GoalKind.VERY_LOW, GoalKind.HIGH, GoalKind.VERY_HIGH, GoalKind.VARIATION),
            analysis.goals.map { it.kind }
        )
        assertEquals(listOf(70, 4, 1, 25, 5, 36), analysis.goals.map { it.limit })
        assertTrue(analysis.goals.all { it.met })
        assertEquals(100.0, analysis.goals[0].percent, 1e-9)
        assertEquals(0.0, analysis.goals[1].percent, 1e-9)
        assertEquals(analysis.stats.cv!!, analysis.goals[5].percent, 1e-9)
    }

    @Test
    fun theGoalsAtTheirLimits() {
        // a day has 96 quarter hours; [counts] says how many of them have which value
        fun goals(vararg counts: Pair<Int, Int>): Map<GoalKind, Goal> {
            val values = ArrayList<Glucose>()
            var k = 0
            for ((count, mgdl) in counts) {
                repeat(count) { values.add(value(at(-1, 0) + k++ * quarter + minute, mgdl)) }
            }
            assertEquals(96, values.size)
            return AnalysisCalculator.compute(values, at(-1, 0), at(0, 0), 70, 180, zone).goals.associateBy { it.kind }
        }

        // under 70: 3 of 96 is 3.1 percent (good), 4 of 96 is 4.2 percent (not)
        assertTrue(goals(3 to 60, 93 to 110).getValue(GoalKind.LOW).met)
        val fourLows = goals(4 to 60, 92 to 110)
        assertFalse(fourLows.getValue(GoalKind.LOW).met)
        assertEquals(100.0 / 24, fourLows.getValue(GoalKind.LOW).percent, 1e-9)
        assertTrue("60 is not under 54", fourLows.getValue(GoalKind.VERY_LOW).met)

        // under 54: one quarter hour of a day is 1.04 percent already
        val veryLow = goals(1 to 50, 95 to 110)
        assertFalse(veryLow.getValue(GoalKind.VERY_LOW).met)
        assertTrue("under 54 is under 70 too: 1 percent", veryLow.getValue(GoalKind.LOW).met)

        // over 180: 23 of 96 is good, 24 of 96 is exactly 25 percent and not good
        assertTrue(goals(23 to 200, 73 to 110).getValue(GoalKind.HIGH).met)
        val quarterHigh = goals(24 to 200, 72 to 110)
        assertFalse(quarterHigh.getValue(GoalKind.HIGH).met)
        assertEquals(25.0, quarterHigh.getValue(GoalKind.HIGH).percent, 1e-9)
        assertTrue(quarterHigh.getValue(GoalKind.IN_RANGE).met)
        assertTrue("200 is not over 250", quarterHigh.getValue(GoalKind.VERY_HIGH).met)

        // over 250: 4 of 96 is good, 5 of 96 is not
        assertTrue(goals(4 to 300, 92 to 110).getValue(GoalKind.VERY_HIGH).met)
        assertFalse(goals(5 to 300, 91 to 110).getValue(GoalKind.VERY_HIGH).met)

        // in range: 68 of 96 is 70.8 percent (good), 67 of 96 is 69.8 percent (not)
        assertTrue(goals(68 to 110, 28 to 200).getValue(GoalKind.IN_RANGE).met)
        assertFalse(goals(67 to 110, 29 to 200).getValue(GoalKind.IN_RANGE).met)

        // swings: half the day at 60, half at 180 gives a variation of 50 percent
        val swings = goals(48 to 60, 48 to 180).getValue(GoalKind.VARIATION)
        assertFalse(swings.met)
        assertEquals(50.26, swings.percent, 0.01)
    }

    @Test
    fun noGoalsWithoutValues() {
        val empty = AnalysisCalculator.compute(emptyList(), at(-1, 0), at(0, 0), 70, 180, zone)
        assertTrue(empty.goals.isEmpty())
        assertTrue(empty.findings.isEmpty())
        assertTrue(empty.lows.isEmpty())
        assertNull(empty.morning)
        assertEquals(0.0, empty.inRange, 0.0)

        // one single quarter hour has no variation
        val one = AnalysisCalculator.compute(listOf(value(at(-1, 3), 100)), at(-1, 0), at(0, 0), 70, 180, zone)
        assertEquals(5, one.goals.size)
        assertFalse(one.goals.any { it.kind == GoalKind.VARIATION })
    }

    // ------------------------------------------------------------------ findings

    @Test
    fun aGoodWeek() {
        val values = quarters(at(-7, 0), at(0, 0), { h -> 105 + (h % 3) * 5 })
        val analysis = AnalysisCalculator.compute(values, at(-7, 0), at(0, 0), 70, 180, zone)
        assertEquals(672, analysis.stats.slots)
        assertEquals(listOf(FindingKind.NO_LOWS, FindingKind.STEADY, FindingKind.IN_RANGE_GOOD), kinds(analysis))
        assertTrue(analysis.findings.all { it.level == FindingLevel.GOOD })
        assertEquals(100.0, finding(analysis, FindingKind.IN_RANGE_GOOD).a, 1e-9)
        assertEquals(analysis.stats.cv!!, finding(analysis, FindingKind.STEADY).a, 1e-9)
        // seven nights, no change towards the morning
        assertEquals(7, analysis.morning!!.nights)
        assertEquals(0.0, analysis.morning!!.change, 1e-9)
    }

    @Test
    fun aHardWeek() {
        // every night an hour at 50, every afternoon six hours at 260, the rest at 120
        val values = quarters(at(-7, 0), at(0, 0), { h ->
            when {
                h == 2 -> 50
                h in 12..17 -> 260
                else -> 120
            }
        })
        val analysis = AnalysisCalculator.compute(values, at(-7, 0), at(0, 0), 70, 180, zone)
        assertEquals(
            listOf(
                FindingKind.VERY_LOW_TIME,
                FindingKind.LOW_TIME, FindingKind.LOW_EVENTS, FindingKind.LOWS_AT_PART, FindingKind.VERY_HIGH_TIME, FindingKind.HIGH_TIME, FindingKind.SWINGS,
                FindingKind.HIGHEST_PART, FindingKind.MORNING_RISE,
                FindingKind.IN_RANGE_GOOD
            ),
            kinds(analysis)
        )
        assertEquals(
            listOf(
                FindingLevel.URGENT,
                FindingLevel.ATTENTION, FindingLevel.ATTENTION, FindingLevel.ATTENTION, FindingLevel.ATTENTION, FindingLevel.ATTENTION, FindingLevel.ATTENTION,
                FindingLevel.NOTE, FindingLevel.NOTE,
                FindingLevel.GOOD
            ),
            analysis.findings.map { it.level }
        )

        assertEquals(2800.0 / 672, analysis.under54, 1e-9)
        assertEquals(2800.0 / 672, finding(analysis, FindingKind.VERY_LOW_TIME).a, 1e-9)
        assertEquals(2800.0 / 672, finding(analysis, FindingKind.LOW_TIME).a, 1e-9)

        val events = finding(analysis, FindingKind.LOW_EVENTS)
        assertEquals(7.0, events.a, 0.0)
        assertEquals("mean length in minutes", 60.0, events.b, 0.0)
        assertEquals("the longest", 60.0, events.c, 0.0)
        assertEquals(7, analysis.lows.size)
        assertEquals(at(-7, 2), analysis.lows[0].start)
        assertEquals(at(-1, 3), analysis.lows[6].end)

        val where = finding(analysis, FindingKind.LOWS_AT_PART)
        assertEquals(DayPart.NIGHT, where.part)
        assertEquals(7.0, where.a, 0.0)
        assertEquals(7.0, where.b, 0.0)

        assertEquals(25.0, finding(analysis, FindingKind.VERY_HIGH_TIME).a, 1e-9)
        assertEquals(25.0, finding(analysis, FindingKind.HIGH_TIME).a, 1e-9)
        assertEquals(7, analysis.highs.size)
        assertEquals(360, analysis.highs[0].minutes)

        val highest = finding(analysis, FindingKind.HIGHEST_PART)
        assertEquals(DayPart.AFTERNOON, highest.part)
        assertEquals(260.0, highest.a, 1e-9)
        assertEquals(102_200.0 / 672, highest.b, 1e-9)

        assertEquals(42.0, finding(analysis, FindingKind.SWINGS).a, 0.05)
        assertEquals(47_600.0 / 672, finding(analysis, FindingKind.IN_RANGE_GOOD).a, 1e-9)

        val morning = finding(analysis, FindingKind.MORNING_RISE)
        assertEquals(7.0, morning.a, 0.0)
        assertEquals(70.0, morning.b, 1e-9)

        assertEquals(listOf(true, false, false, false, false, false), analysis.goals.map { it.met })
    }

    @Test
    fun tooFewValuesSayNothingAboutPatterns() {
        // ten hours of a day: 40 quarter hours, with lows and highs in them
        val tenHours = quarters(at(-1, 0), at(-1, 10), { h -> if (h < 2) 50 else 300 })
        val analysis = AnalysisCalculator.compute(tenHours, at(-1, 0), at(0, 0), 70, 180, zone)
        assertEquals(listOf(FindingKind.FEW_VALUES), kinds(analysis))
        assertEquals(42.0, analysis.findings[0].a, 0.0)
        assertEquals(FindingLevel.NOTE, analysis.findings[0].level)
        assertEquals("the goals are counted all the same", 6, analysis.goals.size)

        // a full stretch of ten hours has too few quarter hours as well, and nothing to say at all
        val short = AnalysisCalculator.compute(tenHours, at(-1, 0), at(-1, 10), 70, 180, zone)
        assertTrue(short.findings.isEmpty())

        // twelve hours are enough; with half of the day missing nobody says "no lows"
        val twelveHours = quarters(at(-1, 0), at(-1, 12), { 110 })
        val half = AnalysisCalculator.compute(twelveHours, at(-1, 0), at(0, 0), 70, 180, zone)
        assertEquals(listOf(FindingKind.FEW_VALUES, FindingKind.STEADY, FindingKind.IN_RANGE_GOOD), kinds(half))
        assertEquals("a level line has no variation", 0.0, half.stats.cv!!, 1e-9)
    }

    @Test
    fun lowEventsNowAndThen() {
        // a week with one low event: a note. Two in a week: attention.
        val one = quarters(at(-7, 0), at(0, 0), { 110 }).map { if (it.timestamp >= at(-3, 15) && it.timestamp < at(-3, 15, 30)) value(it.timestamp, 62) else it }
        val once = AnalysisCalculator.compute(one, at(-7, 0), at(0, 0), 70, 180, zone)
        val event = finding(once, FindingKind.LOW_EVENTS)
        assertEquals(FindingLevel.NOTE, event.level)
        assertEquals(1.0, event.a, 0.0)
        assertEquals(30.0, event.b, 0.0)
        assertFalse(kinds(once).contains(FindingKind.NO_LOWS))
        assertFalse("one event is no pattern", kinds(once).contains(FindingKind.LOWS_AT_PART))
        assertFalse("30 minutes of a week are far under 4 percent", kinds(once).contains(FindingKind.LOW_TIME))

        val two = one.map { if (it.timestamp >= at(-5, 9) && it.timestamp < at(-5, 10)) value(it.timestamp, 66) else it }
        val twice = AnalysisCalculator.compute(two, at(-7, 0), at(0, 0), 70, 180, zone)
        val events = finding(twice, FindingKind.LOW_EVENTS)
        assertEquals(FindingLevel.ATTENTION, events.level)
        assertEquals(2.0, events.a, 0.0)
        assertEquals(45.0, events.b, 0.0)
        assertEquals(60.0, events.c, 0.0)
    }

    @Test
    fun whereTheLowsStart() {
        // three low events, two of them in the evening
        val base = quarters(at(-7, 0), at(0, 0), { 110 })
        fun low(list: List<Glucose>, from: Long): List<Glucose> = list.map { if (it.timestamp >= from && it.timestamp < from + 30 * minute) value(it.timestamp, 64) else it }
        val values = low(low(low(base, at(-6, 19)), at(-4, 21)), at(-2, 8))
        val analysis = AnalysisCalculator.compute(values, at(-7, 0), at(0, 0), 70, 180, zone)
        val where = finding(analysis, FindingKind.LOWS_AT_PART)
        assertEquals(DayPart.EVENING, where.part)
        assertEquals(2.0, where.a, 0.0)
        assertEquals(3.0, where.b, 0.0)
        assertEquals(FindingLevel.ATTENTION, where.level)

        // three events in three different parts of the day: no pattern
        val spread = low(low(low(base, at(-6, 19)), at(-4, 3)), at(-2, 8))
        assertFalse(kinds(AnalysisCalculator.compute(spread, at(-7, 0), at(0, 0), 70, 180, zone)).contains(FindingKind.LOWS_AT_PART))
    }

    @Test
    fun betterOrWorseThanThePeriodBefore() {
        // the week before: ten hours a day over 180. This week: five hours a day.
        val before = quarters(at(-14, 0), at(-7, 0), { h -> if (h < 10) 200 else 120 })
        val now = quarters(at(-7, 0), at(0, 0), { h -> if (h < 5) 200 else 120 })
        val better = AnalysisCalculator.compute(before + now, at(-7, 0), at(0, 0), 70, 180, zone)
        val finding = finding(better, FindingKind.BETTER)
        assertEquals(FindingLevel.GOOD, finding.level)
        assertEquals(1400.0 / 24, finding.a, 1e-9)
        assertEquals(1900.0 / 24, finding.b, 1e-9)
        assertEquals(1400.0 / 24, better.before.rangeCounts[2] * 100.0 / better.before.slots, 1e-9)

        // the other way round
        val worse = AnalysisCalculator.compute(
            quarters(at(-14, 0), at(-7, 0), { h -> if (h < 5) 200 else 120 }) + quarters(at(-7, 0), at(0, 0), { h -> if (h < 10) 200 else 120 }),
            at(-7, 0), at(0, 0), 70, 180, zone
        )
        val worseFinding = finding(worse, FindingKind.WORSE)
        assertEquals(FindingLevel.NOTE, worseFinding.level)
        assertEquals(1900.0 / 24, worseFinding.a, 1e-9)
        assertEquals(1400.0 / 24, worseFinding.b, 1e-9)

        // a change of less than five points is not named, and no period before means no comparison
        val same = AnalysisCalculator.compute(
            quarters(at(-14, 0), at(-7, 0), { h -> if (h < 5) 200 else 120 }) + quarters(at(-7, 0), at(0, 0), { h -> if (h < 6) 200 else 120 }),
            at(-7, 0), at(0, 0), 70, 180, zone
        )
        assertFalse(kinds(same).contains(FindingKind.BETTER) || kinds(same).contains(FindingKind.WORSE))
        val alone = AnalysisCalculator.compute(now, at(-7, 0), at(0, 0), 70, 180, zone)
        assertFalse(kinds(alone).contains(FindingKind.BETTER) || kinds(alone).contains(FindingKind.WORSE))
    }

    @Test
    fun theEarlyMorning() {
        // from 2 to 3 o'clock 100, from 5 to 6 o'clock 130
        val rise = quarters(at(-3, 0), at(0, 0), { h -> if (h >= 5) 130 else 100 })
        val rising = AnalysisCalculator.compute(rise, at(-3, 0), at(0, 0), 70, 180, zone)
        assertEquals(3, rising.morning!!.nights)
        assertEquals(30.0, rising.morning!!.change, 1e-9)
        val up = finding(rising, FindingKind.MORNING_RISE)
        assertEquals(FindingLevel.NOTE, up.level)
        assertEquals(3.0, up.a, 0.0)
        assertEquals(30.0, up.b, 1e-9)

        val fall = quarters(at(-3, 0), at(0, 0), { h -> if (h in 4..7) 95 else 120 })
        val falling = AnalysisCalculator.compute(fall, at(-3, 0), at(0, 0), 70, 180, zone)
        assertEquals(-25.0, falling.morning!!.change, 1e-9)
        assertEquals(-25.0, finding(falling, FindingKind.NIGHT_FALL).b, 1e-9)

        // two nights are too few
        val twoNights = AnalysisCalculator.compute(rise.filter { it.timestamp >= at(-2, 0) }, at(-2, 0), at(0, 0), 70, 180, zone)
        assertNull(twoNights.morning)
        assertFalse(kinds(twoNights).contains(FindingKind.MORNING_RISE))

        // a night with a hole at 5 o'clock is not counted
        val hole = AnalysisCalculator.compute(rise.filter { it.timestamp < at(-2, 5) || it.timestamp >= at(-2, 6) }, at(-3, 0), at(0, 0), 70, 180, zone)
        assertNull(hole.morning)

        // a change of less than 20 is not named
        val small = quarters(at(-3, 0), at(0, 0), { h -> if (h >= 5) 115 else 100 })
        val smallChange = AnalysisCalculator.compute(small, at(-3, 0), at(0, 0), 70, 180, zone)
        assertEquals(15.0, smallChange.morning!!.change, 1e-9)
        assertFalse(kinds(smallChange).contains(FindingKind.MORNING_RISE))
    }

    @Test
    fun theHighestPartOfTheDayNeedsThreePartsWithValues() {
        // evenings at 190, the rest at 120: the mean of the day is 137.5
        val values = quarters(at(-2, 0), at(0, 0), { h -> if (h >= 18) 190 else 120 })
        val analysis = AnalysisCalculator.compute(values, at(-2, 0), at(0, 0), 70, 180, zone)
        val highest = finding(analysis, FindingKind.HIGHEST_PART)
        assertEquals(DayPart.EVENING, highest.part)
        assertEquals(190.0, highest.a, 1e-9)
        assertEquals(137.5, highest.b, 1e-9)

        // only the afternoon and the evening have values
        val two = AnalysisCalculator.compute(values.filter { it.timestamp >= at(-2, 12) && it.timestamp < at(-1, 0) || it.timestamp >= at(-1, 12) }, at(-2, 0), at(0, 0), 70, 180, zone)
        assertEquals(96, two.stats.slots)
        assertFalse(kinds(two).contains(FindingKind.HIGHEST_PART))
    }

    @Test
    fun theSharesOfThePeriod() {
        val values = quarters(at(-1, 0), at(0, 0), { h ->
            when (h) {
                0 -> 50
                1, 2 -> 65
                3, 4, 5 -> 200
                6, 7, 8, 9 -> 300
                else -> 110
            }
        })
        val analysis = AnalysisCalculator.compute(values, at(-1, 0), at(0, 0), 70, 180, zone)
        assertEquals(100.0 / 24, analysis.under54, 1e-9)
        assertEquals(300.0 / 24, analysis.under70, 1e-9)
        assertEquals(700.0 / 24, analysis.over180, 1e-9)
        assertEquals(400.0 / 24, analysis.over250, 1e-9)
        assertEquals(1400.0 / 24, analysis.inRange, 1e-9)
        assertEquals(100.0, analysis.under70 + analysis.inRange + analysis.over180, 1e-9)
    }
}
