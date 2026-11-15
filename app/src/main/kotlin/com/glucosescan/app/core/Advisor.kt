package com.glucosescan.app.core

import com.glucosescan.app.ui.GlucoseFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * What the app itself says about a period: a long answer in the language of the user, made
 * by fixed rules from the values on the phone. No internet, no key, nothing to fill in —
 * it works from the first value onwards.
 *
 * It is written like a doctor's summary and it names the numbers that matter in diabetes
 * care (time in range, variability, estimated HbA1c, lows and highs, the early morning),
 * but it is a description of what the sensor measured. It gives no dose and no treatment.
 *
 * All glucose numbers are in mg/dL inside this file; they are written out in the unit of
 * the user. No Android code here, so that it is easy to test.
 */
class AdvisorBlock(
    /** A few words over the block, for example "Time in range". */
    val title: String,
    /** The sentences of the block, in order. */
    val lines: List<String>
)

/** One day of the "day by day" block. */
class AdvisorDay(
    val start: Long,
    /** Whole percent of the quarter hours from 70 to 180 mg/dL. */
    val inRange: Int,
    val mean: Int?,
    val lows: Int,
    val highs: Int
)

/** The numbers the advisor talks about. Counted once, then only written out. */
class AdvisorFacts(
    val from: Long,
    val to: Long,
    val zone: TimeZone,
    val unit: GlucoseUnit,
    val lowLimit: Int,
    val highLimit: Int,
    /** Quarter hours of the period that have values. */
    val quarters: Int,
    /** Whole percent of the period that has values. */
    val coverage: Int,
    /** How many days the period holds, at least 1. */
    val dayCount: Int,
    val mean: Int?,
    val lowest: Int?,
    val highest: Int?,
    /** The estimated HbA1c in percent, or null when the period is too short for it. */
    val gmi: Double?,
    /** Whole percent under 70, from 70 to 180, over 180, and from 70 to 140 mg/dL. */
    val below: Int,
    val inRange: Int,
    val above: Int,
    val tight: Int,
    /** Variation in percent, or null with too few values. */
    val cv: Int?,
    /** The calmest and the highest hour of the day, 0 to 23, with their means. */
    val bestHour: Int?,
    val bestHourMean: Int?,
    val worstHour: Int?,
    val worstHourMean: Int?,
    val parts: List<PartSummary>,
    val morning: MorningChange?,
    val lowCount: Int,
    val longestLowMinutes: Int,
    val longestLowStart: Long?,
    val nightLows: Int,
    val highCount: Int,
    val longestHighMinutes: Int,
    val longestHighStart: Long?,
    /** Rises of 50 mg/dL or more within one hour. */
    val spikes: Int,
    /** The hour of the biggest of those rises. */
    val spikeHour: Int?,
    val dayRows: List<AdvisorDay>,
    /** The period of the same length right before, or null without values. */
    val beforeMean: Int?,
    val beforeInRange: Int?,
    val goals: List<Goal>,
    val verdict: Verdict?
) {
    val hasValues: Boolean
        get() = quarters > 0
}

object Advisor {

    /** 50 mg/dL within one hour: fast enough to be worth naming. */
    const val SPIKE_RISE = 50

    /** How many quarter hours make the hour a fast rise is counted over. */
    const val SPIKE_QUARTERS = 4L

    /** A rise or fall of this size between 2:30 and 5:30 is worth telling the doctor. */
    const val MORNING_MATTERS = 20

    /** The consensus goal for variation, in percent. */
    const val STEADY_CV = AnalysisCalculator.GOAL_VARIATION

    /**
     * Counts everything the words need.
     *
     * @param values the values as the app shows them, in any order
     * @param from start of the period, milliseconds since 1970
     * @param to end of the period (not included)
     */
    fun facts(
        values: List<Glucose>,
        from: Long,
        to: Long,
        lowLimit: Int,
        highLimit: Int,
        unit: GlucoseUnit,
        zone: TimeZone = TimeZone.getDefault()
    ): AdvisorFacts {
        val analysis = AnalysisCalculator.compute(values, from, to, lowLimit, highLimit, zone)
        val stats = analysis.stats
        val means = AnalysisCalculator.quarterMeans(values, from, to)
        val hours = AnalysisCalculator.hours(values, from, to, zone)
        val days = AnalysisCalculator.days(values, to - 1L, 14, to, lowLimit, highLimit, zone)

        val all = means.values
        val tight = if (all.isEmpty()) 0 else Math.round(all.count { it >= 70.0 && it <= 140.0 } * 100.0 / all.size).toInt()

        val withHour = hours.filter { it.mean != null && it.count > 0 }
        val best = withHour.minByOrNull { it.mean!! }
        val worst = withHour.maxByOrNull { it.mean!! }

        val longestLow = analysis.lows.maxByOrNull { it.minutes }
        val longestHigh = analysis.highs.maxByOrNull { it.minutes }
        val nightLows = analysis.lows.count { hourOf(it.start, zone) < 6 }

        var spikes = 0
        var spikeHour: Int? = null
        var biggest = 0.0
        // the map holds one mean for each quarter hour, keyed by the number of the quarter
        val times = means.keys.toList()
        for (i in times.indices) {
            val after = means[times[i] + SPIKE_QUARTERS] ?: continue
            val rise = after - means[times[i]]!!
            if (rise >= SPIKE_RISE) {
                spikes++
                if (rise > biggest) {
                    biggest = rise
                    spikeHour = hourOf(times[i] * StatisticsCalculator.SLOT, zone)
                }
            }
        }

        val dayCount = Math.max(1, Math.round((to - from).toDouble() / StatisticsCalculator.DAY).toInt())
        val dayRows = days.take(14).map { day ->
            AdvisorDay(day.start, day.stats.rangePercents[2], day.stats.mean?.let { Math.round(it).toInt() }, day.lows, day.highs)
        }
        val before = analysis.before

        return AdvisorFacts(
            from = from,
            to = to,
            zone = zone,
            unit = unit,
            lowLimit = lowLimit,
            highLimit = highLimit,
            quarters = stats.slots,
            coverage = stats.coveragePercent,
            dayCount = dayCount,
            mean = stats.mean?.let { Math.round(it).toInt() },
            lowest = stats.lowest,
            highest = stats.highest,
            gmi = if (stats.gmiIsReliable) stats.gmi else null,
            below = Math.round(analysis.under70).toInt(),
            inRange = Math.round(analysis.inRange).toInt(),
            above = Math.round(analysis.over180).toInt(),
            tight = tight,
            cv = stats.cv?.let { Math.round(it).toInt() },
            bestHour = best?.hourOfDay,
            bestHourMean = best?.mean?.let { Math.round(it).toInt() },
            worstHour = worst?.hourOfDay,
            worstHourMean = worst?.mean?.let { Math.round(it).toInt() },
            parts = analysis.parts,
            morning = analysis.morning,
            lowCount = analysis.lows.size,
            longestLowMinutes = longestLow?.minutes ?: 0,
            longestLowStart = longestLow?.start,
            nightLows = nightLows,
            highCount = analysis.highs.size,
            longestHighMinutes = longestHigh?.minutes ?: 0,
            longestHighStart = longestHigh?.start,
            spikes = spikes,
            spikeHour = spikeHour,
            dayRows = dayRows,
            beforeMean = before.mean?.let { Math.round(it).toInt() },
            beforeInRange = if (before.hasValues) before.rangePercents[2] else null,
            goals = analysis.goals,
            verdict = verdictOf(analysis)
        )
    }

    /** The same one word the analysis screen uses, counted from the ranges. */
    private fun verdictOf(analysis: Analysis): Verdict? {
        if (!analysis.stats.hasValues) {
            return null
        }
        val counts = analysis.stats.rangeCounts
        return when {
            counts[0] > 0 -> Verdict.VERY_LOW
            counts[1] > 0 -> Verdict.LOWS
            analysis.inRange >= 90.0 -> Verdict.STEADY
            analysis.over180 >= AnalysisCalculator.GOAL_HIGH -> Verdict.OFTEN_HIGH
            else -> Verdict.IN_RANGE
        }
    }

    private fun hourOf(moment: Long, zone: TimeZone): Int {
        val clock = Calendar.getInstance(zone)
        clock.timeInMillis = moment
        return clock.get(Calendar.HOUR_OF_DAY)
    }

    /**
     * Writes the numbers as sentences, in the language of the user. Blocks that have
     * nothing to say are left out, so the answer is never padded with empty words.
     */
    fun tell(facts: AdvisorFacts, language: AnswerLanguage): List<AdvisorBlock> {
        val words = when (language) {
            AnswerLanguage.ENGLISH -> English
            AnswerLanguage.SOMALI -> Somali
            AnswerLanguage.ARABIC -> Arabic
        }
        return words.tell(facts)
    }
}

// ------------------------------------------------------------------ the words

/**
 * The sentences of one language. Every field is a pattern for [String.format]: %1$s, %2$d
 * and so on. The numbers are put in by [Words.tell], so that a translator only sees words.
 */
internal abstract class Words {

    abstract val titlePeriod: String
    abstract val titleLine: String
    abstract val titleMean: String
    abstract val titleRange: String
    abstract val titleSteady: String
    abstract val titleHours: String
    abstract val titleParts: String
    abstract val titleMorning: String
    abstract val titleLows: String
    abstract val titleHighs: String
    abstract val titleDays: String
    abstract val titleBefore: String
    abstract val titleGoals: String
    abstract val titleDoctor: String
    abstract val titleQuestions: String
    abstract val titleCare: String

    abstract val noValues: String
    abstract val period: String // 1 from, 2 to, 3 length, 4 coverage %, 5 quarters
    abstract val quarters: String // 1 quarters

    abstract val steady: String // 1 mean, 2 in range %
    abstract val inRange: String
    abstract val oftenHigh: String // 1 mean, 2 high limit, 3 over %
    abstract val lows: String // 1 mean, 2 low limit, 3 under %
    abstract val veryLow: String // 1 very low limit

    abstract val mean: String // 1 mean
    abstract val extremes: String // 1 lowest, 2 highest, 3 swing
    abstract val gmi: String // 1 percent
    abstract val gmiTooShort: String

    abstract val rangeLine: String // 1 low, 2 high, 3 percent
    abstract val sidesLine: String // 1 low limit, 2 below %, 3 high limit, 4 above %
    abstract val tightLine: String // 1 70, 2 140, 3 percent

    abstract val cvLine: String // 1 percent
    abstract val cvSteady: String // 1 limit
    abstract val cvUnsteady: String // 1 limit

    abstract val calmestHour: String // 1 hour, 2 mean
    abstract val highestHour: String // 1 hour, 2 mean

    abstract val partLine: String // 1 part, 2 in range %, 3 below %, 4 above %
    abstract val partNight: String
    abstract val partMorning: String
    abstract val partAfternoon: String
    abstract val partEvening: String

    abstract val morningRose: String // 1 nights, 2 change
    abstract val morningFell: String
    abstract val morningDawn: String
    abstract val morningNone: String

    abstract val lowCount: String // 1 count, 2 word, 3 limit
    abstract val lowLongest: String // 1 duration, 2 time
    abstract val lowNight: String // 1 count, 2 word
    abstract val lowNightCare: String
    abstract val lowNone: String

    abstract val highCount: String // 1 count, 2 word, 3 limit
    abstract val highLongest: String // 1 duration, 2 time
    abstract val highSpikes: String // 1 count, 2 word, 3 rise, 4 hour
    abstract val highNone: String

    abstract val dayLine: String // 1 day, 2 in range %, 3 mean, 4 lows, 5 highs

    abstract val beforeLine: String // 1 before mean, 2 before %, 3 now mean, 4 now %
    abstract val beforeDown: String
    abstract val beforeUp: String
    abstract val beforeSame: String

    abstract val goalLine: String // 1 name, 2 measured, 3 goal, 4 reached
    abstract val goalInRange: String
    abstract val goalLow: String
    abstract val goalVeryLow: String
    abstract val goalHigh: String
    abstract val goalVeryHigh: String
    abstract val goalVariation: String
    abstract val goalMet: String
    abstract val goalMissed: String

    abstract val doctorNight: String // 1 count
    abstract val doctorLows: String // 1 count, 2 length
    abstract val doctorHigh: String // 1 limit, 2 percent
    abstract val doctorCv: String // 1 percent
    abstract val doctorDawn: String
    abstract val doctorGaps: String // 1 coverage %
    abstract val doctorNothing: String

    abstract val question1: String // 1 low, 2 high
    abstract val question2: String // 1 low limit
    abstract val question3: String // 1 high limit
    abstract val question4: String
    abstract val question5: String

    abstract val care: String

    /** "1 episode" or "3 episodes". */
    abstract fun episodes(count: Int): String

    /** "7 days" or "24 hours". */
    abstract fun length(days: Int): String

    /** Writes everything, leaving out what has nothing to say. */
    fun tell(facts: AdvisorFacts): List<AdvisorBlock> {
        if (!facts.hasValues) {
            return listOf(AdvisorBlock(titlePeriod, listOf(noValues)))
        }
        val out = ArrayList<AdvisorBlock>()
        val unit = facts.unit
        fun value(mgdl: Int): String = GlucoseFormat.value(mgdl, unit) + " " + unit.label
        fun value(mgdl: Double): String = value(Math.round(mgdl).toInt())
        fun percent(exact: Double): String = Percent.text(exact) + " %"
        fun date(moment: Long): String = Texts.dateTime(facts.zone, moment)
        fun clock(moment: Long): String = Texts.timeOfDay(facts.zone, moment)
        fun minutes(count: Int): String = Texts.duration(count)

        out.add(
            AdvisorBlock(
                titlePeriod,
                listOf(
                    format(period, date(facts.from), date(facts.to), length(facts.dayCount), facts.coverage, facts.quarters),
                    format(quarters, facts.quarters)
                )
            )
        )

        // in one line
        val mean = facts.mean
        if (mean != null) {
            val headline = when (facts.verdict) {
                Verdict.STEADY -> format(steady, value(mean), facts.inRange)
                Verdict.IN_RANGE -> format(inRange, value(mean), facts.inRange)
                Verdict.OFTEN_HIGH -> format(oftenHigh, value(mean), value(facts.highLimit), facts.above)
                Verdict.LOWS -> format(lows, value(mean), value(facts.lowLimit), facts.below)
                Verdict.VERY_LOW -> format(veryLow, value(StatisticsCalculator.VERY_LOW_BELOW))
                null -> null
            }
            if (headline != null) {
                out.add(AdvisorBlock(titleLine, listOf(headline)))
            }

            // the average
            val meanLines = ArrayList<String>()
            meanLines.add(format(this.mean, value(mean)))
            if (facts.lowest != null && facts.highest != null) {
                meanLines.add(format(extremes, value(facts.lowest), value(facts.highest), value(facts.highest - facts.lowest)))
            }
            meanLines.add(if (facts.gmi != null) format(gmi, percent(facts.gmi)) else gmiTooShort)
            out.add(AdvisorBlock(titleMean, meanLines))
        }

        // time in range
        out.add(
            AdvisorBlock(
                titleRange,
                listOf(
                    format(rangeLine, value(facts.lowLimit), value(facts.highLimit), facts.inRange),
                    format(sidesLine, value(facts.lowLimit), facts.below, value(facts.highLimit), facts.above),
                    format(tightLine, value(70), value(140), facts.tight)
                )
            )
        )

        // how steady
        if (facts.cv != null) {
            val verdictLine = if (facts.cv <= Advisor.STEADY_CV) format(cvSteady, Advisor.STEADY_CV) else format(cvUnsteady, Advisor.STEADY_CV)
            out.add(AdvisorBlock(titleSteady, listOf(format(cvLine, facts.cv), verdictLine)))
        }

        // hour by hour
        val hourLines = ArrayList<String>()
        if (facts.bestHour != null) {
            hourLines.add(format(calmestHour, facts.bestHour, value(facts.bestHourMean!!)))
        }
        if (facts.worstHour != null && facts.worstHour != facts.bestHour) {
            hourLines.add(format(highestHour, facts.worstHour, value(facts.worstHourMean!!)))
        }
        if (hourLines.isNotEmpty()) {
            out.add(AdvisorBlock(titleHours, hourLines))
        }

        // the parts of the day
        out.add(
            AdvisorBlock(
                titleParts,
                facts.parts.map { part ->
                    format(
                        partLine,
                        partName(part.part),
                        part.inRangePercent,
                        part.belowPercent,
                        part.abovePercent
                    )
                }
            )
        )

        // the early morning
        val morningLines = ArrayList<String>()
        val morning = facts.morning
        if (morning == null) {
            morningLines.add(morningNone)
        } else {
            val change = value(Math.abs(Math.round(morning.change).toInt()))
            morningLines.add(
                if (morning.change >= 0) format(morningRose, morning.nights, change) else format(morningFell, morning.nights, change)
            )
            if (morning.change >= Advisor.MORNING_MATTERS) {
                morningLines.add(morningDawn)
            }
        }
        out.add(AdvisorBlock(titleMorning, morningLines))

        // lows
        out.add(
            AdvisorBlock(
                titleLows,
                lowLines(facts, value(facts.lowLimit), ::clock, ::minutes)
            )
        )

        // highs
        out.add(
            AdvisorBlock(
                titleHighs,
                highLines(facts, value(facts.highLimit), value(Advisor.SPIKE_RISE), ::clock, ::minutes)
            )
        )

        // day by day
        if (facts.dayRows.isNotEmpty()) {
            out.add(
                AdvisorBlock(
                    titleDays,
                    facts.dayRows.reversed().map { day ->
                        format(
                            dayLine,
                            Texts.date(facts.zone, day.start),
                            day.inRange,
                            day.mean?.let { value(it) } ?: "-",
                            day.lows,
                            day.highs
                        )
                    }
                )
            )
        }

        // compared with the days before
        if (facts.beforeMean != null && facts.beforeInRange != null) {
            val lines = ArrayList<String>()
            lines.add(format(beforeLine, value(facts.beforeMean), facts.beforeInRange, value(mean ?: facts.beforeMean), facts.inRange))
            val change = (mean ?: facts.beforeMean) - facts.beforeMean
            lines.add(
                when {
                    change <= -10 -> beforeDown
                    change >= 10 -> beforeUp
                    else -> beforeSame
                }
            )
            out.add(AdvisorBlock(titleBefore, lines))
        }

        // the goals
        if (facts.goals.isNotEmpty()) {
            out.add(
                AdvisorBlock(
                    titleGoals,
                    facts.goals.map { goal ->
                        format(
                            goalLine,
                            goalName(goal.kind) { amount -> value(amount) },
                            percent(goal.percent),
                            goal.limit.toString() + " %",
                            if (goal.met) goalMet else goalMissed
                        )
                    }
                )
            )
        }

        // what to tell the doctor
        val doctorLines = ArrayList<String>()
        if (facts.nightLows > 0) {
            doctorLines.add(format(doctorNight, facts.nightLows))
        }
        if (facts.lowCount > 0) {
            doctorLines.add(format(doctorLows, facts.lowCount, length(facts.dayCount)))
        }
        if (facts.above > AnalysisCalculator.GOAL_HIGH) {
            doctorLines.add(format(doctorHigh, value(facts.highLimit), facts.above))
        }
        if (facts.cv != null && facts.cv > Advisor.STEADY_CV) {
            doctorLines.add(format(doctorCv, facts.cv))
        }
        if (facts.morning != null && facts.morning.change >= Advisor.MORNING_MATTERS) {
            doctorLines.add(doctorDawn)
        }
        if (facts.coverage < 70) {
            doctorLines.add(format(doctorGaps, facts.coverage))
        }
        if (doctorLines.isEmpty()) {
            doctorLines.add(doctorNothing)
        }
        out.add(AdvisorBlock(titleDoctor, doctorLines))

        // questions
        out.add(
            AdvisorBlock(
                titleQuestions,
                listOf(
                    format(question1, value(facts.lowLimit), value(facts.highLimit)),
                    format(question2, value(facts.lowLimit)),
                    format(question3, value(facts.highLimit)),
                    question4,
                    question5
                )
            )
        )

        out.add(AdvisorBlock(titleCare, listOf(care)))
        return out
    }

    private fun lowLines(facts: AdvisorFacts, limit: String, clock: (Long) -> String, minutes: (Int) -> String): List<String> {
        if (facts.lowCount == 0) {
            return listOf(lowNone)
        }
        val out = ArrayList<String>()
        out.add(format(lowCount, facts.lowCount, episodes(facts.lowCount), limit))
        if (facts.longestLowStart != null) {
            out.add(format(lowLongest, minutes(facts.longestLowMinutes), clock(facts.longestLowStart)))
        }
        out.add(format(lowNight, facts.nightLows, episodes(facts.nightLows)))
        if (facts.nightLows > 0) {
            out.add(lowNightCare)
        }
        return out
    }

    private fun highLines(
        facts: AdvisorFacts,
        limit: String,
        rise: String,
        clock: (Long) -> String,
        minutes: (Int) -> String
    ): List<String> {
        if (facts.highCount == 0) {
            return listOf(highNone)
        }
        val out = ArrayList<String>()
        out.add(format(highCount, facts.highCount, episodes(facts.highCount), limit))
        if (facts.longestHighStart != null) {
            out.add(format(highLongest, minutes(facts.longestHighMinutes), clock(facts.longestHighStart)))
        }
        if (facts.spikes > 0 && facts.spikeHour != null) {
            out.add(format(highSpikes, facts.spikes, episodes(facts.spikes), rise, facts.spikeHour))
        }
        return out
    }

    private fun partName(part: DayPart): String = when (part) {
        DayPart.NIGHT -> partNight
        DayPart.MORNING -> partMorning
        DayPart.AFTERNOON -> partAfternoon
        DayPart.EVENING -> partEvening
    }

    /**
     * The name of a goal. The four that name a number take it in the unit the person reads, so
     * that somebody reading mmol/L is not told about mg/dL.
     */
    private fun goalName(kind: GoalKind, value: (Int) -> String): String = when (kind) {
        GoalKind.IN_RANGE -> goalInRange
        GoalKind.LOW -> format(goalLow, value(StatisticsCalculator.LOW_BELOW))
        GoalKind.VERY_LOW -> format(goalVeryLow, value(StatisticsCalculator.VERY_LOW_BELOW))
        GoalKind.HIGH -> format(goalHigh, value(StatisticsCalculator.HIGH_ABOVE))
        GoalKind.VERY_HIGH -> format(goalVeryHigh, value(StatisticsCalculator.VERY_HIGH_ABOVE))
        GoalKind.VARIATION -> goalVariation
    }

    protected fun format(pattern: String, vararg parts: Any?): String =
        String.format(Locale.US, pattern, *parts)
}

/** How [Texts] writes a date, a time and a length of time, without Android. */
private object Texts {
    fun date(zone: TimeZone, moment: Long): String {
        val format = java.text.SimpleDateFormat("EEE d MMM", Locale.US)
        format.timeZone = zone
        return format.format(java.util.Date(moment))
    }

    fun dateTime(zone: TimeZone, moment: Long): String {
        val format = java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.US)
        format.timeZone = zone
        return format.format(java.util.Date(moment))
    }

    fun timeOfDay(zone: TimeZone, moment: Long): String {
        val format = java.text.SimpleDateFormat("HH:mm", Locale.US)
        format.timeZone = zone
        return format.format(java.util.Date(moment))
    }

    fun duration(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$rest min"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest min"
        }
    }
}
