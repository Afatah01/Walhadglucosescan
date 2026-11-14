package com.glucosescan.app.core

import java.util.TimeZone

/**
 * Everything the report of one day shows: the numbers of the day, the values for the chart,
 * hour by hour, the low and high events, the findings, and the six days before for comparison.
 *
 * No Android code here, so it is easy to test.
 */
class DailyReport(
    /** The midnight that starts the day, and the midnight that ends it. */
    val dayStart: Long,
    val dayEnd: Long,
    /** Where the values of the day end: [dayEnd], or the running minute if the day is today. */
    val until: Long,
    /** True if the day is not over yet. */
    val running: Boolean,
    /** The day from [dayStart] to [until]. */
    val span: Span,
    /** Goals and findings of the day. */
    val analysis: Analysis,
    /** One entry for each hour of the day from [dayStart] to [until], oldest first. */
    val hours: List<HourSummary>,
    /** The values of the day as the app shows them, oldest first. */
    val values: List<Glucose>,
    /** The day and the six days before it, newest first. */
    val week: List<DaySummary>,
    /** The target range of the user (mg/dL). */
    val lowLimit: Int,
    val highLimit: Int
) {
    /**
     * What stands out on the day, for the report. While the day runs, "more (or less) time in
     * range than before" is left out: the time before is then the evening of yesterday, and a
     * morning cannot be compared with an evening.
     */
    val findings: List<Finding>
        get() = if (running) analysis.findings.filter { it.kind != FindingKind.BETTER && it.kind != FindingKind.WORSE } else analysis.findings

    val hasValues: Boolean
        get() = span.hasValues
}

object DailyReports {
    /** How many days the table at the end of the report has. */
    const val WEEK_DAYS = 7

    /**
     * @param values the values as the app shows them, in any order; for the table of the days
     * before, they should reach back seven days before the day
     * @param day any moment of the day the report is about
     * @param now the time now; values after it are not counted
     */
    fun build(values: List<Glucose>, day: Long, now: Long, lowLimit: Int, highLimit: Int, zone: TimeZone = TimeZone.getDefault()): DailyReport {
        val start = AnalysisCalculator.dayStart(day, zone)
        val end = AnalysisCalculator.addDays(start, 1, zone)
        val runningMinute = now - Math.floorMod(now, 60_000L) + 60_000L
        val until = Math.max(start, Math.min(end, runningMinute))
        val ofDay = values.filter { it.timestamp >= start && it.timestamp < until }.sortedBy { it.timestamp }
        return DailyReport(
            dayStart = start,
            dayEnd = end,
            until = until,
            running = until < end,
            span = AnalysisCalculator.span(ofDay, start, until, lowLimit, highLimit, zone),
            analysis = AnalysisCalculator.compute(values, start, until, lowLimit, highLimit, zone),
            hours = AnalysisCalculator.hours(ofDay, start, until, zone),
            values = ofDay,
            week = AnalysisCalculator.days(values, start, WEEK_DAYS, until, lowLimit, highLimit, zone),
            lowLimit = lowLimit,
            highLimit = highLimit
        )
    }
}
