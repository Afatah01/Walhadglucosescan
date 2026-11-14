package com.glucosescan.app.ui

import android.content.Context
import com.glucosescan.app.core.Episode
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.Span
import com.glucosescan.app.core.StatisticsCalculator
import com.glucosescan.app.core.Verdict

/**
 * Says in short English sentences how the glucose was in a stretch of time. The sentences
 * are built from the numbers by fixed rules: the same numbers always give the same words.
 * They describe; they give no advice about treatment.
 */
object ReportWords {
    /** At most this many low (or high) events are named one by one. */
    const val MAX_EVENTS = 4

    /** One sentence that sums the stretch up. */
    fun verdict(verdict: Verdict, unit: GlucoseUnit): String {
        return when (verdict) {
            Verdict.VERY_LOW -> "There were very low values (under " + FindingTexts.glucose(StatisticsCalculator.VERY_LOW_BELOW, unit) + ")."
            Verdict.LOWS -> "There were low values (under " + FindingTexts.glucose(StatisticsCalculator.LOW_BELOW, unit) + ")."
            Verdict.OFTEN_HIGH -> "No lows, but often high (over " + FindingTexts.glucose(StatisticsCalculator.HIGH_ABOVE, unit) + ")."
            Verdict.IN_RANGE -> "Mostly in range, and no lows."
            Verdict.STEADY -> "Almost always in range, and no lows."
        }
    }

    /** A single value: "104 mg/dL", or "under 40 mg/dL" and "over 500 mg/dL" at the ends of what the sensor can measure. */
    fun value(glucose: Glucose, unit: GlucoseUnit): String = value(glucose.readableValue, unit)

    fun value(readable: Int, unit: GlucoseUnit): String {
        return when {
            readable <= Glucose.MIN_READABLE_GLUCOSE -> "under " + FindingTexts.glucose(40, unit)
            readable >= Glucose.MAX_READABLE_GLUCOSE -> "over " + FindingTexts.glucose(500, unit)
            else -> FindingTexts.glucose(readable, unit)
        }
    }

    /**
     * How the stretch was, sentence by sentence. Empty if it has no values.
     * @param ended false: the stretch runs until now ("is at ... now"); true: it is over ("ended at ...")
     */
    fun span(context: Context, span: Span, unit: GlucoseUnit, ended: Boolean): List<String> {
        if (!span.hasValues) {
            return emptyList()
        }
        val stats = span.stats
        val sentences = ArrayList<String>()
        fun time(moment: Long): String = Texts.timeOfDay(context, moment)
        val lowBelow = FindingTexts.glucose(StatisticsCalculator.LOW_BELOW, unit)
        val highAbove = FindingTexts.glucose(StatisticsCalculator.HIGH_ABOVE, unit)

        val mean = stats.mean
        val lowest = span.lowest
        val highest = span.highest
        if (mean != null && lowest != null && highest != null) {
            sentences.add(
                "Average " + FindingTexts.glucose(mean, unit) + ". Lowest " + value(lowest, unit) + " at " + time(lowest.timestamp) +
                    ". Highest " + value(highest, unit) + " at " + time(highest.timestamp) + "."
            )
        }
        sentences.add(
            "In range (" + GlucoseFormat.value(StatisticsCalculator.LOW_BELOW, unit) + " to " + highAbove + ") " + inRange(span) +
                " of the time. Under " + lowBelow + ": " + under(span) + ". Over " + highAbove + ": " + over(span) + "."
        )

        val first = span.first
        val last = span.last
        if (first != null && last != null && last.timestamp - first.timestamp >= 30 * 60_000L) {
            val change = last.readableValue - first.readableValue
            val direction = when {
                change >= 15 -> FindingTexts.glucose(change, unit) + " higher"
                change <= -15 -> FindingTexts.glucose(-change, unit) + " lower"
                else -> "about the same"
            }
            sentences.add(
                "It started at " + value(first, unit) + " (" + time(first.timestamp) + ") and " + (if (ended) "ended at " else "is at ") +
                    value(last, unit) + (if (ended) "" else " now") + " (" + time(last.timestamp) + "): " + direction + "."
            )
        }

        sentences.add(events(context, span.lows, "Low", "lowest", lowBelow, unit))
        sentences.add(events(context, span.highs, "High", "highest", highAbove, unit))

        if (stats.coveragePercent < 70) {
            sentences.add("There are values for only " + stats.coveragePercent + " % of this time. The numbers can be far off.")
        }
        return sentences
    }

    /**
     * The three shares of a stretch as the report writes them. From 10 % on they are the whole
     * numbers of the bar of the ranges, which add up to 100: so a page never says "13 %" in one
     * place and "14 %" in another. Under 10 % one decimal is shown, because the common goals for
     * low values are that small ("less than 4 %").
     */
    fun inRange(span: Span): String = share(span.inRange, span.stats.rangePercents[2])

    fun under(span: Span): String = share(span.under70, span.stats.rangePercents[0] + span.stats.rangePercents[1])

    fun over(span: Span): String = share(span.over180, span.stats.rangePercents[3] + span.stats.rangePercents[4])

    private fun share(exact: Double, whole: Int): String = if (exact < 10.0) FindingTexts.percent(exact) else "$whole %"

    private fun events(context: Context, events: List<Episode>, name: String, extremeWord: String, limit: String, unit: GlucoseUnit): String {
        val side = if (name == "Low") "under" else "over"
        if (events.isEmpty()) {
            return "$name events ($side $limit): none."
        }
        val text = StringBuilder()
        text.append(name).append(" events (").append(side).append(' ').append(limit).append("): ").append(events.size).append(". ")
        // the newest ones are named
        val named = events.takeLast(MAX_EVENTS)
        text.append(
            named.joinToString("; ") {
                Texts.timeOfDay(context, it.start) + " for " + GlucoseFormat.duration(it.minutes) + ", " + extremeWord + " " +
                    value(Math.round(it.extreme).toInt(), unit)
            }
        )
        if (events.size > named.size) {
            text.append("; and ").append(events.size - named.size).append(" before that")
        }
        text.append('.')
        return text.toString()
    }
}
