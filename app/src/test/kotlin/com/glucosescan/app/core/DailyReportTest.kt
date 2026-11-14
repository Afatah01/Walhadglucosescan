package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DailyReportTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val quarter = 15 * minute
    private val dayLength = 24 * hour
    private val zone = TimeZone.getTimeZone("Africa/Mogadishu")

    /** Tuesday 10 November 2026, 0:00 local time (UTC+3). */
    private val midnight = 1_794_258_000_000L

    private fun at(day: Int, hourOfDay: Int, minuteOfHour: Int = 0): Long = midnight + day * dayLength + hourOfDay * hour + minuteOfHour * minute

    private fun value(time: Long, mgdl: Int): Glucose = Glucose(time, mgdl, null, false, "A")

    private fun quarters(from: Long, to: Long, mgdl: (Int) -> Int): List<Glucose> {
        val list = ArrayList<Glucose>()
        var start = from
        while (start < to) {
            val hourOfDay = (((start - midnight) % dayLength + dayLength) % dayLength / hour).toInt()
            list.add(value(start + 7 * minute, mgdl(hourOfDay)))
            start += quarter
        }
        return list
    }

    /** Eight days: lunch time high, one low hour in the night before the 9th. */
    private val values: List<Glucose> = quarters(at(-8, 0), at(0, 14, 30)) { h -> if (h in 12..13) 210 else 115 }
        .map { if (it.timestamp >= at(-1, 3) && it.timestamp < at(-1, 4)) value(it.timestamp, 58) else it }

    @Test
    fun aDayThatIsOver() {
        val now = at(0, 14, 32) + 20_000
        val report = DailyReports.build(values.shuffled(java.util.Random(7)), at(-1, 17, 45), now, 80, 160, zone)
        assertEquals(at(-1, 0), report.dayStart)
        assertEquals(at(0, 0), report.dayEnd)
        assertEquals(at(0, 0), report.until)
        assertFalse(report.running)
        assertTrue(report.hasValues)
        assertEquals(80, report.lowLimit)
        assertEquals(160, report.highLimit)

        // the values of the day only, oldest first
        assertEquals(96, report.values.size)
        assertEquals(at(-1, 0, 7), report.values.first().timestamp)
        assertEquals(at(-1, 23, 52), report.values.last().timestamp)
        assertEquals(report.values.map { it.timestamp }.sorted(), report.values.map { it.timestamp })

        // the day as a whole
        assertEquals(96, report.span.stats.slots)
        assertEquals(58, report.span.lowest!!.value)
        assertEquals(at(-1, 3, 7), report.span.lowest!!.timestamp)
        assertEquals(210, report.span.highest!!.value)
        assertEquals(1, report.span.lows.size)
        assertEquals(at(-1, 3), report.span.lows[0].start)
        assertEquals(60, report.span.lows[0].minutes)
        assertEquals(1, report.span.highs.size)
        assertEquals(120, report.span.highs[0].minutes)
        assertEquals(Verdict.LOWS, Verdict.of(report.span))
        assertEquals("84 of 96 quarter hours", 87.5, report.span.inRange, 1e-9)
        assertEquals("the own target range is 80 to 160", 84, report.span.stats.inTarget)

        // hour by hour
        assertEquals((0..23).toList(), report.hours.map { it.hourOfDay })
        assertEquals(58.0, report.hours[3].mean!!, 1e-9)
        assertEquals(210.0, report.hours[12].mean!!, 1e-9)
        assertEquals(115.0, report.hours[23].mean!!, 1e-9)
        assertTrue(report.hours.all { it.count == 4 })

        // goals and findings of the day
        assertEquals(6, report.analysis.goals.size)
        assertTrue(report.analysis.findings.any { it.kind == FindingKind.LOW_EVENTS })
        assertEquals(96, report.analysis.before.slots)

        // the day and the six days before, newest first
        assertEquals(7, report.week.size)
        assertEquals(at(-1, 0), report.week[0].start)
        assertEquals(at(-7, 0), report.week[6].start)
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0), report.week.map { it.lows })
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1), report.week.map { it.highs })
        assertEquals(88, report.week[0].inRangePercent)
        assertEquals(92, report.week[1].inRangePercent)
    }

    @Test
    fun todayEndsWithTheRunningMinute() {
        val now = at(0, 14, 32) + 20_000
        val report = DailyReports.build(values + value(now + 5 * minute, 400), now, now, 70, 180, zone)
        assertEquals(at(0, 0), report.dayStart)
        assertEquals(at(1, 0), report.dayEnd)
        assertEquals(at(0, 14, 33), report.until)
        assertTrue(report.running)
        assertEquals(58, report.values.size)
        assertEquals("a value after now is not counted", 210, report.span.highest!!.value)
        assertEquals((0..14).toList(), report.hours.map { it.hourOfDay })
        assertEquals(2, report.hours[14].count)
        assertEquals(at(0, 14, 33), report.week[0].stats.to)
        assertEquals(at(0, 0), report.week[1].stats.to)
        assertEquals(1, report.week[1].lows)
        assertEquals(0, report.span.lows.size)
        assertEquals(Verdict.IN_RANGE, Verdict.of(report.span))
    }

    @Test
    fun exactlyOnTheMinute() {
        val report = DailyReports.build(values, at(0, 9), at(0, 9), 70, 180, zone)
        assertEquals(at(0, 9, 1), report.until)
        assertEquals(36, report.values.size)
    }

    @Test
    fun aDayWithoutValues() {
        val now = at(0, 14, 32)
        val old = DailyReports.build(values, at(-20, 12), now, 70, 180, zone)
        assertFalse(old.hasValues)
        assertFalse(old.running)
        assertTrue(old.values.isEmpty())
        assertEquals(24, old.hours.size)
        assertTrue(old.analysis.goals.isEmpty())
        assertEquals(7, old.week.size)
        assertTrue(old.week.none { it.hasValues })

        // a day that has not started yet
        val tomorrow = DailyReports.build(values, at(1, 12), now, 70, 180, zone)
        assertEquals(at(1, 0), tomorrow.dayStart)
        assertEquals(at(1, 0), tomorrow.until)
        assertTrue(tomorrow.running)
        assertFalse(tomorrow.hasValues)
        assertTrue(tomorrow.hours.isEmpty())
        assertTrue("the days before it are in the table", tomorrow.week[1].hasValues)
    }
}
