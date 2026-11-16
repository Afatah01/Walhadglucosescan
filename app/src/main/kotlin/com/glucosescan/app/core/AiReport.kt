package com.glucosescan.app.core

import com.glucosescan.app.ui.GlucoseFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The language in which the AI is asked to answer. The code is what is saved. */
enum class AnswerLanguage(val code: String, val english: String) {
    ENGLISH("en", "English"),
    SOMALI("so", "Somali"),
    ARABIC("ar", "Arabic");

    companion object {
        fun of(code: String?): AnswerLanguage = values().firstOrNull { it.code == code } ?: ENGLISH
    }
}

/**
 * Writes the numbers of a period as plain text, with a question in front, so that the user
 * can give it to the built-in AI (or to an AI app of their choice).
 *
 * The text is English, like the rest of the app; the AI door answers in English. It asks for
 * an explanation and for questions to take to the doctor, and it tells the AI not to give doses.
 *
 * No Android code here, so it is easy to test.
 */
object AiReport {
    /** At most this many low events go into the text, the newest ones. */
    const val MAX_EVENTS = 20

    /**
     * @param analysis the period
     * @param hours the last 24 hours, hour by hour, oldest first (see [AnalysisCalculator.hours])
     * @param days the last days, newest first (see [AnalysisCalculator.days])
     * @param recent the newest readings with their timestamps and arrows. A forecast question
     *   can only be answered from these; summaries alone are not enough, so the section is
     *   written only when they were sent.
     */
    fun build(
        analysis: Analysis,
        hours: List<HourSummary>,
        days: List<DaySummary>,
        unit: GlucoseUnit,
        lowLimit: Int,
        highLimit: Int,
        language: AnswerLanguage,
        zone: TimeZone = TimeZone.getDefault(),
        fromLinkUp: Boolean = false,
        recent: List<Glucose> = emptyList()
    ): String {
        val stats = analysis.stats
        val dayCount = Math.max(1L, Math.round((stats.to - stats.from).toDouble() / StatisticsCalculator.DAY))
        val time = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US)
        time.timeZone = zone
        val dayFormat = SimpleDateFormat("EEE d MMM", Locale.US)
        dayFormat.timeZone = zone
        val hourFormat = SimpleDateFormat("EEE HH:00", Locale.US)
        hourFormat.timeZone = zone
        fun value(mgdl: Double): String = GlucoseFormat.value(Math.round(mgdl).toInt(), unit)
        fun value(mgdl: Int): String = GlucoseFormat.value(mgdl, unit)
        fun percent(exact: Double): String = Percent.text(exact) + " %"

        val text = StringBuilder()
        text.append("I use a glucose sensor (FreeStyle Libre 2). Below are my glucose data of the last ")
            .append(if (dayCount == 1L) "24 hours" else "$dayCount days").append(".\n")
        text.append("Please analyse how my glucose was, in simple words:\n")
        text.append("1. How was it in the last hours and on the last days?\n")
        text.append("2. What looks good?\n")
        text.append("3. What patterns do you see (time of day, nights, lows, highs)?\n")
        text.append("4. What should I ask my doctor or diabetes nurse?\n")
        text.append("Do not tell me to change insulin doses or medicines. That is for my doctor to decide.\n")
        if (language != AnswerLanguage.ENGLISH) {
            text.append("Please answer in ").append(language.english).append(".\n")
        }
        text.append('\n')
        if (fromLinkUp) {
            text.append("About the data: the app \"Walhad Glucose Scan\" got them from LibreLinkUp, the follower service of the maker of the sensor. ")
                .append("No doctor has checked them. ")
        } else {
            text.append("About the data: the app \"Walhad Glucose Scan\" made them from the raw data of the sensor. ")
                .append("They can differ a little from the FreeStyle LibreLink app, and no doctor has checked them. ")
        }
        text.append("All glucose values are in ").append(unit.label).append(".\n\n")

        text.append("PERIOD\n")
        text.append(time.format(Date(stats.from))).append(" to ").append(time.format(Date(stats.to - 60_000L)))
            .append(". Values for ").append(stats.coveragePercent).append(" % of this time.\n\n")

        if (!stats.hasValues) {
            text.append("There are no values in this period.\n")
            return text.toString()
        }

        text.append("SUMMARY\n")
        stats.mean?.let { text.append("Average glucose: ").append(value(it)).append('\n') }
        stats.gmi?.let {
            text.append("Estimated A1c (GMI): ").append(String.format(Locale.US, "%.1f", it)).append(" %")
            if (!stats.gmiIsReliable) {
                text.append(" (not reliable: it needs 14 days with values for most of the time)")
            }
            text.append('\n')
        }
        stats.cv?.let { text.append("Variation (CV): ").append(Math.round(it)).append(" %\n") }
        val lowest = stats.lowest
        val highest = stats.highest
        if (lowest != null && highest != null) {
            text.append("Lowest and highest value: ").append(value(lowest)).append(" and ").append(value(highest)).append('\n')
        }
        text.append('\n')

        text.append("TIME IN RANGES\n")
        text.append("Very high (over ").append(value(StatisticsCalculator.VERY_HIGH_ABOVE)).append(""): "").append(percent(analysis.over250)).append('\n')
        text.append("High (over ").append(value(StatisticsCalculator.HIGH_ABOVE)).append(", with very high): ").append(percent(analysis.over180)).append('\n')
        text.append("In range (").append(value(StatisticsCalculator.LOW_BELOW)).append(" to ").append(value(StatisticsCalculator.HIGH_ABOVE))
            .append(""): "").append(percent(analysis.inRange)).append('\n')
        text.append("Low (under ").append(value(StatisticsCalculator.LOW_BELOW)).append(", with very low): ").append(percent(analysis.under70)).append('\n')
        text.append("Very low (under ").append(value(StatisticsCalculator.VERY_LOW_BELOW)).append(""): "").append(percent(analysis.under54)).append('\n')
        text.append("My own target range is ").append(value(lowLimit)).append(" to ").append(value(highLimit)).append(": ")
            .append(stats.inTargetPercent).append(" % of the time inside.\n\n")

        text.append("BY TIME OF DAY (average; time under ").append(value(StatisticsCalculator.LOW_BELOW)).append("; time over ")
            .append(value(StatisticsCalculator.HIGH_ABOVE)).append("; low events that started there)\n")
        for (part in analysis.parts) {
            text.append(partName(part.part)).append(": ")
            val mean = part.mean
            if (mean == null) {
                text.append("no values\n")
            } else {
                text.append(value(mean)).append("; ").append(part.belowPercent).append(" %; ").append(part.abovePercent).append(" %; ")
                    .append(part.lowEvents).append('\n')
            }
        }
        text.append('\n')

        val lows = analysis.lows
        text.append("LOW EVENTS (under ").append(value(StatisticsCalculator.LOW_BELOW)).append(" for 15 minutes or longer): ").append(lows.size).append('\n')
        for (episode in lows.takeLast(MAX_EVENTS).reversed()) {
            text.append("- ").append(time.format(Date(episode.start))).append(", ").append(episode.minutes).append(" minutes, lowest ")
                .append(value(episode.extreme)).append('\n')
        }
        val highs = analysis.highs
        text.append("HIGH EVENTS (over ").append(value(StatisticsCalculator.HIGH_ABOVE)).append(""): "").append(highs.size)
        if (highs.isNotEmpty()) {
            text.append(", mean length ").append(Math.round(highs.sumOf { it.minutes }.toDouble() / highs.size)).append(" minutes")
        }
        text.append("\n\n")

        val daysWithValues = days.filter { it.hasValues }
        if (daysWithValues.isNotEmpty()) {
            text.append("DAY BY DAY, newest first (average; in range; lowest to highest; low events; high events)\n")
            for (day in daysWithValues) {
                val stat = day.stats
                text.append(dayFormat.format(Date(day.start))).append(": ")
                    .append(value(stat.mean ?: 0.0)).append("; ").append(day.inRangePercent).append(" %; ")
                    .append(value(stat.lowest ?: 0)).append(" to ").append(value(stat.highest ?: 0)).append("; ")
                    .append(day.lows).append("; ").append(day.highs)
                if (stat.coveragePercent < 70) {
                    text.append(" (values for only ").append(stat.coveragePercent).append(" % of the day)")
                }
                text.append('\n')
            }
            text.append('\n')
        }

        val hoursWithValues = hours.filter { it.mean != null }
        if (hoursWithValues.isNotEmpty()) {
            text.append("THE LAST 24 HOURS, HOUR BY HOUR (average; lowest to highest)\n")
            for (hour in hoursWithValues) {
                text.append(hourFormat.format(Date(hour.start))).append(": ").append(value(hour.mean ?: 0.0)).append("; ")
                    .append(value(hour.lowest ?: 0)).append(" to ").append(value(hour.highest ?: 0)).append('\n')
            }
            text.append('\n')
        }

        if (recent.isNotEmpty()) {
            text.append("RECENT READINGS (the last hour, oldest first, values in ").append(unit.label)
                .append(", the arrow is the sensor's own direction)\n")
            for (g in recent.takeLast(12)) {
                text.append(time.format(Date(g.timestamp))).append(" - ").append(value(g.readableValue))
                    .append(" ").append(g.trend.arrow).append('\n')
            }
            text.append('\n')
        }

        text.append("TYPICAL DAY (middle value of each hour, from 0 to 23 o'clock; \"-\" = too few values)\n")
        text.append(stats.hours.joinToString(" ") { if (it == null) "-" else value(it.median) }).append("\n\n")

        analysis.morning?.let {
            text.append("EARLY MORNING (").append(it.nights).append(" nights)\n")
            text.append("From about 2:30 to 5:30 in the morning the glucose ")
            if (Math.abs(it.change) < 2.0) {
                text.append("stays about the same on average.\n")
            } else {
                text.append(if (it.change >= 0) "rises" else "falls").append(" by ").append(value(Math.abs(it.change))).append(" on average.\n")
            }
        }
        return text.toString().trimEnd() + "\n"
    }

    private fun partName(part: DayPart): String {
        return when (part) {
            DayPart.NIGHT -> "Night (0 to 6 o'clock)"
            DayPart.MORNING -> "Morning (6 to 12 o'clock)"
            DayPart.AFTERNOON -> "Afternoon (12 to 18 o'clock)"
            DayPart.EVENING -> "Evening (18 to 24 o'clock)"
        }
    }
}

/** How a percent number is written: one decimal under 10, none from 10 up. */
object Percent {
    fun text(exact: Double): String {
        if (exact <= 0.0) {
            return "0"
        }
        if (exact < 10.0) {
            val tenths = Math.round(exact * 10)
            if (tenths >= 100) {
                return "10"
            }
            if (tenths == 0L) {
                return "under 0.1"
            }
            return if (tenths % 10 == 0L) (tenths / 10).toString() else (tenths / 10).toString() + "." + (tenths % 10).toString()
        }
        return Math.round(exact).toString()
    }
}
