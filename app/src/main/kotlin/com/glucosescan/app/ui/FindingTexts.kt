package com.glucosescan.app.ui

import android.content.Context
import com.glucosescan.app.R
import com.glucosescan.app.core.AnalysisCalculator
import com.glucosescan.app.core.DayPart
import com.glucosescan.app.core.Finding
import com.glucosescan.app.core.FindingKind
import com.glucosescan.app.core.FindingLevel
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.Percent
import com.glucosescan.app.core.StatisticsCalculator

/** The words for the findings of the analysis. */
object FindingTexts {
    fun levelWord(context: Context, level: FindingLevel): String {
        return context.getString(
            when (level) {
                FindingLevel.URGENT -> R.string.level_urgent
                FindingLevel.ATTENTION -> R.string.level_attention
                FindingLevel.NOTE -> R.string.level_note
                FindingLevel.GOOD -> R.string.level_good
            }
        )
    }

    /** The colour of the word that names the level. */
    fun levelColor(level: FindingLevel): Int {
        return when (level) {
            FindingLevel.URGENT -> R.color.red
            FindingLevel.ATTENTION -> R.color.notice_title
            FindingLevel.NOTE -> R.color.blue
            FindingLevel.GOOD -> R.color.green
        }
    }

    /** The patch behind the word that names the level. */
    fun levelBackground(level: FindingLevel): Int {
        return when (level) {
            FindingLevel.URGENT -> R.drawable.badge_urgent
            FindingLevel.ATTENTION -> R.drawable.badge_attention
            FindingLevel.NOTE -> R.drawable.badge_note
            FindingLevel.GOOD -> R.drawable.badge_good
        }
    }

    /** "38 %", "3.4 %": one decimal under 10. */
    fun percent(exact: Double): String = Percent.text(exact) + " %"

    /** "14 days", "1 day". */
    fun days(context: Context, days: Int): String = context.resources.getQuantityString(R.plurals.days_count, days, days)

    /** A glucose number with its unit: "70 mg/dL", "3.9 mmol/L". Also right for a difference. */
    fun glucose(mgdl: Double, unit: GlucoseUnit): String = GlucoseFormat.value(Math.round(mgdl).toInt(), unit) + " " + unit.label

    fun glucose(mgdl: Int, unit: GlucoseUnit): String = GlucoseFormat.value(mgdl, unit) + " " + unit.label

    /** "about 96 mg/dL", or "under 40 mg/dL" and "over 500 mg/dL" at the ends of what a sensor can show. */
    fun forecast(context: Context, readable: Int, unit: GlucoseUnit): String {
        return when {
            readable <= 39 -> context.getString(R.string.outlook_under, glucose(40, unit))
            readable >= 501 -> context.getString(R.string.outlook_over, glucose(500, unit))
            else -> context.getString(R.string.outlook_about, glucose(readable, unit))
        }
    }

    private fun partAt(context: Context, part: DayPart?): String {
        return context.getString(
            when (part) {
                DayPart.MORNING -> R.string.part_at_morning
                DayPart.AFTERNOON -> R.string.part_at_afternoon
                DayPart.EVENING -> R.string.part_at_evening
                else -> R.string.part_at_night
            }
        )
    }

    /**
     * One finding as a sentence.
     * @param days how many days the analysed period has
     * @param calendarDay true: the period is one day of the calendar, from midnight to midnight
     */
    fun text(context: Context, finding: Finding, unit: GlucoseUnit, days: Int, calendarDay: Boolean = false): String {
        val resources = context.resources
        fun whole(percent: Int): String = "$percent %"
        fun minutes(value: Double): String = GlucoseFormat.duration(Math.round(value).toInt())
        val lowBelow = StatisticsCalculator.LOW_BELOW
        val highAbove = StatisticsCalculator.HIGH_ABOVE
        return when (finding.kind) {
            FindingKind.FEW_VALUES -> context.getString(R.string.finding_few_values, whole(Math.round(finding.a).toInt()))
            FindingKind.VERY_LOW_TIME -> context.getString(
                R.string.finding_very_low_time,
                percent(finding.a),
                glucose(StatisticsCalculator.VERY_LOW_BELOW, unit),
                whole(AnalysisCalculator.GOAL_VERY_LOW)
            )
            FindingKind.LOW_TIME -> context.getString(R.string.finding_low_time, percent(finding.a), glucose(lowBelow, unit), whole(AnalysisCalculator.GOAL_LOW))
            FindingKind.LOW_EVENTS -> {
                val count = finding.a.toInt()
                resources.getQuantityString(R.plurals.finding_low_events, count, count, glucose(lowBelow, unit), minutes(finding.b), minutes(finding.c))
            }
            FindingKind.LOWS_AT_PART -> context.getString(R.string.finding_lows_at_part, finding.a.toInt(), finding.b.toInt(), partAt(context, finding.part))
            FindingKind.NO_LOWS -> context.getString(R.string.finding_no_lows, glucose(lowBelow, unit))
            FindingKind.VERY_HIGH_TIME -> context.getString(
                R.string.finding_very_high_time,
                percent(finding.a),
                glucose(StatisticsCalculator.VERY_HIGH_ABOVE, unit),
                whole(AnalysisCalculator.GOAL_VERY_HIGH)
            )
            FindingKind.HIGH_TIME -> context.getString(R.string.finding_high_time, percent(finding.a), glucose(highAbove, unit), whole(AnalysisCalculator.GOAL_HIGH))
            FindingKind.HIGHEST_PART -> context.getString(R.string.finding_highest_part, partAt(context, finding.part), glucose(finding.a, unit), glucose(finding.b, unit))
            FindingKind.SWINGS -> context.getString(R.string.finding_swings, whole(Math.round(finding.a).toInt()), whole(AnalysisCalculator.GOAL_VARIATION))
            FindingKind.STEADY -> context.getString(R.string.finding_steady, whole(Math.round(finding.a).toInt()), whole(AnalysisCalculator.GOAL_VARIATION))
            FindingKind.IN_RANGE_GOOD -> context.getString(
                R.string.finding_in_range_good,
                percent(finding.a),
                GlucoseFormat.value(lowBelow, unit),
                glucose(highAbove, unit),
                whole(AnalysisCalculator.GOAL_IN_RANGE)
            )
            FindingKind.IN_RANGE_UNDER_GOAL -> context.getString(
                R.string.finding_in_range_under,
                percent(finding.a),
                GlucoseFormat.value(lowBelow, unit),
                glucose(highAbove, unit),
                whole(AnalysisCalculator.GOAL_IN_RANGE)
            )
            FindingKind.BETTER -> when {
                calendarDay -> context.getString(R.string.finding_better_day, percent(finding.a), percent(finding.b))
                days == 1 -> context.getString(R.string.finding_better_hours, percent(finding.a), percent(finding.b))
                else -> context.getString(R.string.finding_better, percent(finding.a), percent(finding.b), days(context, days))
            }
            FindingKind.WORSE -> when {
                calendarDay -> context.getString(R.string.finding_worse_day, percent(finding.a), percent(finding.b))
                days == 1 -> context.getString(R.string.finding_worse_hours, percent(finding.a), percent(finding.b))
                else -> context.getString(R.string.finding_worse, percent(finding.a), percent(finding.b), days(context, days))
            }
            FindingKind.MORNING_RISE -> {
                val nights = finding.a.toInt()
                resources.getQuantityString(R.plurals.finding_morning_rise, nights, nights, glucose(finding.b, unit))
            }
            FindingKind.NIGHT_FALL -> {
                val nights = finding.a.toInt()
                resources.getQuantityString(R.plurals.finding_night_fall, nights, nights, glucose(-finding.b, unit))
            }
        }
    }
}
