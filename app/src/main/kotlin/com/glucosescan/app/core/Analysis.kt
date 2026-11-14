package com.glucosescan.app.core

import java.util.Calendar
import java.util.SortedMap
import java.util.TimeZone
import java.util.TreeMap

// The analysis of the values the sensor sends: the last hours, hour by hour, day by day, low
// and high events, the parts of the day, the early morning, goals and findings. Everything is counted on the phone, by the fixed rules written
// down here. It is not "artificial intelligence" and it gives no advice about treatment.
//
// This is new code of this app. The original app (Glucose Direct) has no analysis.
// All glucose numbers are in mg/dL.

/** The four parts of the day, by the clock of the phone. */
enum class DayPart(val fromHour: Int, val toHour: Int) {
    NIGHT(0, 6),
    MORNING(6, 12),
    AFTERNOON(12, 18),
    EVENING(18, 24);

    companion object {
        fun of(hour: Int): DayPart = values().firstOrNull { hour >= it.fromHour && hour < it.toHour } ?: NIGHT
    }
}

/**
 * A time with low (or high) glucose: quarter hours in a row whose mean is under 70 (or over
 * 180) mg/dL. One quarter hour is enough, as doctors count it (15 minutes or longer).
 */
class Episode(
    /** Start of the first quarter hour (milliseconds since 1970). */
    val start: Long,
    /** End of the last quarter hour. */
    val end: Long,
    /** The lowest (or highest) quarter-hour mean. */
    val extreme: Double,
    /** How many quarter hours with values it has. */
    val quarters: Int
) {
    val minutes: Int
        get() = ((end - start) / 60_000L).toInt()
}

/** One part of the day over all days of the period. */
class PartSummary(
    val part: DayPart,
    /** Quarter hours with values. */
    val quarters: Int,
    val mean: Double?,
    /** Quarter hours under 70, from 70 to 180, over 180 mg/dL. */
    val below: Int,
    val inRange: Int,
    val above: Int,
    /** Low and high events that started in this part of the day. */
    val lowEvents: Int,
    val highEvents: Int
) {
    val belowPercent: Int
        get() = percent(below)
    val inRangePercent: Int
        get() = percent(inRange)
    val abovePercent: Int
        get() = percent(above)

    private fun percent(count: Int): Int = if (quarters <= 0) 0 else Math.round(count * 100.0 / quarters).toInt()
}

/** The change from about 2:30 to 5:30 in the morning, over the nights of the period. */
class MorningChange(
    /** Nights that have values at both times. */
    val nights: Int,
    /** Mean change in mg/dL: positive = rises towards the morning. */
    val change: Double
)

enum class GoalKind {
    IN_RANGE,
    LOW,
    VERY_LOW,
    HIGH,
    VERY_HIGH,
    VARIATION
}

/**
 * One of the common goals for adults with diabetes (international consensus on time in
 * range, Battelino and others, Diabetes Care 2019). A doctor may set other goals.
 */
class Goal(
    val kind: GoalKind,
    /** What was measured, in percent. */
    val percent: Double,
    /** The limit of the goal, in percent. */
    val limit: Int,
    val met: Boolean
)

enum class FindingLevel {
    /** Needs a talk with the doctor soon. */
    URGENT,

    /** Outside a common goal. */
    ATTENTION,

    /** Good to know. */
    NOTE,

    /** Inside a common goal. */
    GOOD
}

/** What a finding is about. The comments say what the numbers a, b, c of the [Finding] hold. */
enum class FindingKind {
    /** a = percent of the time that has values */
    FEW_VALUES,

    /** a = percent of the time under 54 */
    VERY_LOW_TIME,

    /** a = percent of the time under 70 */
    LOW_TIME,

    /** a = number of low events, b = their mean length in minutes, c = the longest in minutes */
    LOW_EVENTS,

    /** part = where most low events start, a = how many start there, b = all low events */
    LOWS_AT_PART,

    /** nothing */
    NO_LOWS,

    /** a = percent of the time over 250 */
    VERY_HIGH_TIME,

    /** a = percent of the time over 180 */
    HIGH_TIME,

    /** part = the part of the day with the highest mean, a = its mean, b = the mean of the whole period */
    HIGHEST_PART,

    /** a = variation (CV) in percent */
    SWINGS,

    /** a = variation (CV) in percent */
    STEADY,

    /** a = percent of the time from 70 to 180 */
    IN_RANGE_GOOD,

    /** a = percent of the time from 70 to 180 */
    IN_RANGE_UNDER_GOAL,

    /** a = time in range of the period before, b = now (both in percent) */
    BETTER,

    /** a = time in range of the period before, b = now (both in percent) */
    WORSE,

    /** a = number of nights, b = mean change (positive) */
    MORNING_RISE,

    /** a = number of nights, b = mean change (negative) */
    NIGHT_FALL
}

class Finding(
    val kind: FindingKind,
    val level: FindingLevel,
    val a: Double = 0.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val part: DayPart? = null
)

/**
 * What the glucose did in one stretch of time, for example the last 6 hours or one day.
 * The single values are the values as the app shows them.
 */
class Span(
    val from: Long,
    val to: Long,
    val stats: Statistics,
    /** The lowest and the highest single value (the first one, if several are the same), or null without values. */
    val lowest: Glucose?,
    val highest: Glucose?,
    /** The oldest and the newest value in the stretch. */
    val first: Glucose?,
    val last: Glucose?,
    /** Oldest first. */
    val lows: List<Episode>,
    val highs: List<Episode>
) {
    val hasValues: Boolean
        get() = stats.hasValues

    /** Exact percent of the time under 70, from 70 to 180, over 180 mg/dL. */
    val under70: Double
        get() = share(stats.rangeCounts[0] + stats.rangeCounts[1])
    val inRange: Double
        get() = share(stats.rangeCounts[2])
    val over180: Double
        get() = share(stats.rangeCounts[3] + stats.rangeCounts[4])

    private fun share(count: Int): Double = if (stats.slots <= 0) 0.0 else count * 100.0 / stats.slots
}

/** One hour by the clock of the phone. */
class HourSummary(
    /** Start of the hour, milliseconds since 1970. */
    val start: Long,
    /** 0 to 23. */
    val hourOfDay: Int,
    /** How many values the hour has. */
    val count: Int,
    /** Mean of the quarter hours of the hour (mg/dL), or null without values. */
    val mean: Double?,
    /** The lowest and the highest single value, or null without values. */
    val lowest: Int?,
    val highest: Int?
)

/** One day by the calendar of the phone, from midnight to midnight. */
class DaySummary(
    val start: Long,
    val end: Long,
    val stats: Statistics,
    /** Low and high events in the day. An event over midnight counts in both days. */
    val lows: Int,
    val highs: Int
) {
    val hasValues: Boolean
        get() = stats.hasValues

    /** Whole percent of the time from 70 to 180 mg/dL: the number that the bar of the ranges shows. */
    val inRangePercent: Int
        get() = stats.rangePercents[2]
}

/** In one word, how a stretch of time was. */
enum class Verdict(val level: FindingLevel) {
    /** At least one quarter hour under 54 mg/dL. */
    VERY_LOW(FindingLevel.URGENT),

    /** At least one quarter hour under 70 mg/dL. */
    LOWS(FindingLevel.ATTENTION),

    /** No lows, but over 180 mg/dL for a quarter of the time or more. */
    OFTEN_HIGH(FindingLevel.ATTENTION),

    /** No lows, in range 70 to under 90 percent of the time. */
    IN_RANGE(FindingLevel.GOOD),

    /** No lows, in range 90 percent of the time or more. */
    STEADY(FindingLevel.GOOD);

    companion object {
        /** Null if the stretch has no values. */
        fun of(span: Span): Verdict? {
            if (!span.hasValues) {
                return null
            }
            val counts = span.stats.rangeCounts
            return when {
                counts[0] > 0 -> VERY_LOW
                counts[1] > 0 -> LOWS
                span.inRange >= 90.0 -> STEADY
                span.over180 >= AnalysisCalculator.GOAL_HIGH -> OFTEN_HIGH
                else -> IN_RANGE
            }
        }
    }
}

/** Everything the analysis screen shows for one period. */
class Analysis(
    val stats: Statistics,
    /** The period of the same length right before this one. */
    val before: Statistics,
    /** Oldest first. */
    val lows: List<Episode>,
    val highs: List<Episode>,
    /** Night, morning, afternoon, evening. */
    val parts: List<PartSummary>,
    /** Null with fewer than [AnalysisCalculator.MIN_NIGHTS] nights that can be counted. */
    val morning: MorningChange?,
    /** Empty if there are no values. */
    val goals: List<Goal>,
    /** The most important first. Empty if there are no values. */
    val findings: List<Finding>
) {
    /** Exact percent of the time under 54, under 70, from 70 to 180, over 180, over 250 mg/dL. */
    val under54: Double
        get() = share(stats.rangeCounts[0])
    val under70: Double
        get() = share(stats.rangeCounts[0] + stats.rangeCounts[1])
    val inRange: Double
        get() = share(stats.rangeCounts[2])
    val over180: Double
        get() = share(stats.rangeCounts[3] + stats.rangeCounts[4])
    val over250: Double
        get() = share(stats.rangeCounts[4])

    private fun share(count: Int): Double = if (stats.slots <= 0) 0.0 else count * 100.0 / stats.slots
}

object AnalysisCalculator {
    private const val SLOT = StatisticsCalculator.SLOT
    private const val HOUR = 60 * 60_000L
    private const val DAY = StatisticsCalculator.DAY

    /** With fewer quarter hours than this (12 hours of values) nothing is said about patterns. */
    const val MIN_QUARTERS = 48
    const val MIN_NIGHTS = 3

    /** The goals of the consensus, in percent. */
    const val GOAL_IN_RANGE = 70
    const val GOAL_LOW = 4
    const val GOAL_VERY_LOW = 1
    const val GOAL_HIGH = 25
    const val GOAL_VERY_HIGH = 5
    const val GOAL_VARIATION = 36

    /**
     * @param values the values as the app shows them, in any order. For the comparison with the
     * period before, they should reach back to [from] minus the length of the period.
     * @param from start of the period, milliseconds since 1970
     * @param to end of the period (not included)
     * @param lowLimit the target range of the user (mg/dL); only used for [Statistics.inTarget]
     */
    fun compute(
        values: List<Glucose>,
        from: Long,
        to: Long,
        lowLimit: Int,
        highLimit: Int,
        zone: TimeZone = TimeZone.getDefault()
    ): Analysis {
        val stats = StatisticsCalculator.compute(values, from, to, lowLimit, highLimit, zone)
        val before = StatisticsCalculator.compute(values, from - (to - from), from, lowLimit, highLimit, zone)
        val means = quarterMeans(values, from, to)

        val lows = episodes(means, lows = true)
        val highs = episodes(means, lows = false)
        val parts = parts(means, lows, highs, zone)
        val morning = morning(means, zone)

        val goals = ArrayList<Goal>()
        val findings = ArrayList<Finding>()
        val analysis = Analysis(stats, before, lows, highs, parts, morning, goals, findings)
        if (stats.hasValues) {
            goals.add(Goal(GoalKind.IN_RANGE, analysis.inRange, GOAL_IN_RANGE, analysis.inRange >= GOAL_IN_RANGE))
            goals.add(Goal(GoalKind.LOW, analysis.under70, GOAL_LOW, analysis.under70 < GOAL_LOW))
            goals.add(Goal(GoalKind.VERY_LOW, analysis.under54, GOAL_VERY_LOW, analysis.under54 < GOAL_VERY_LOW))
            goals.add(Goal(GoalKind.HIGH, analysis.over180, GOAL_HIGH, analysis.over180 < GOAL_HIGH))
            goals.add(Goal(GoalKind.VERY_HIGH, analysis.over250, GOAL_VERY_HIGH, analysis.over250 < GOAL_VERY_HIGH))
            stats.cv?.let { goals.add(Goal(GoalKind.VARIATION, it, GOAL_VARIATION, it <= GOAL_VARIATION)) }
            findings.addAll(findings(analysis))
        }
        return analysis
    }

    /** What the glucose did from [from] to [to] (not included). */
    fun span(values: List<Glucose>, from: Long, to: Long, lowLimit: Int, highLimit: Int, zone: TimeZone = TimeZone.getDefault()): Span {
        val stats = StatisticsCalculator.compute(values, from, to, lowLimit, highLimit, zone)
        var lowest: Glucose? = null
        var highest: Glucose? = null
        var first: Glucose? = null
        var last: Glucose? = null
        for (glucose in values) {
            if (glucose.timestamp < from || glucose.timestamp >= to) {
                continue
            }
            val low = lowest
            if (low == null || glucose.readableValue < low.readableValue || (glucose.readableValue == low.readableValue && glucose.timestamp < low.timestamp)) {
                lowest = glucose
            }
            val high = highest
            if (high == null || glucose.readableValue > high.readableValue || (glucose.readableValue == high.readableValue && glucose.timestamp < high.timestamp)) {
                highest = glucose
            }
            val oldest = first
            if (oldest == null || glucose.timestamp < oldest.timestamp) {
                first = glucose
            }
            val newest = last
            if (newest == null || glucose.timestamp > newest.timestamp) {
                last = glucose
            }
        }
        val means = quarterMeans(values, from, to)
        return Span(from, to, stats, lowest, highest, first, last, episodes(means, lows = true), episodes(means, lows = false))
    }

    /**
     * One entry for every hour of the clock from the hour of [from] to the hour of the last
     * minute before [to], oldest first. Hours without values are in the list too.
     */
    fun hours(values: List<Glucose>, from: Long, to: Long, zone: TimeZone = TimeZone.getDefault()): List<HourSummary> {
        if (to <= from) {
            return emptyList()
        }
        class Cell {
            var sum = 0.0
            var quarters = 0
            var count = 0
            var lowest = Int.MAX_VALUE
            var highest = Int.MIN_VALUE
        }

        fun key(time: Long): Long = Math.floorDiv(time + zone.getOffset(time), HOUR) // the number of the hour in local time
        val cells = TreeMap<Long, Cell>()
        for ((slot, mean) in quarterMeans(values, from, to)) {
            val cell = cells.getOrPut(key(slot * SLOT)) { Cell() }
            cell.sum += mean
            cell.quarters++
        }
        for (glucose in values) {
            if (glucose.timestamp < from || glucose.timestamp >= to) {
                continue
            }
            val cell = cells.getOrPut(key(glucose.timestamp)) { Cell() }
            cell.count++
            cell.lowest = Math.min(cell.lowest, glucose.readableValue)
            cell.highest = Math.max(cell.highest, glucose.readableValue)
        }
        val result = ArrayList<HourSummary>()
        var hour = key(from)
        val lastHour = key(to - 1)
        while (hour <= lastHour && result.size < 24 * 100) {
            val local = hour * HOUR
            val start = local - zone.getOffset(local - zone.rawOffset)
            val cell = cells[hour]
            val hourOfDay = Math.floorMod(hour, 24L).toInt()
            if (cell == null || cell.count == 0 || cell.quarters == 0) {
                result.add(HourSummary(start, hourOfDay, 0, null, null, null))
            } else {
                result.add(HourSummary(start, hourOfDay, cell.count, cell.sum / cell.quarters, cell.lowest, cell.highest))
            }
            hour++
        }
        return result
    }

    /** The midnight that starts the day of [time], by the calendar of [zone]. */
    fun dayStart(time: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = time
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** The midnight [days] days after (or, with a minus, before) the midnight [dayStart]. */
    fun addDays(dayStart: Long, days: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = dayStart
        calendar.add(Calendar.DAY_OF_MONTH, days)
        return calendar.timeInMillis
    }

    /**
     * The day of [newest] and the days before it, newest first.
     * @param count how many days
     * @param until values at or after this time are not counted (the running minute)
     */
    fun days(
        values: List<Glucose>,
        newest: Long,
        count: Int,
        until: Long,
        lowLimit: Int,
        highLimit: Int,
        zone: TimeZone = TimeZone.getDefault()
    ): List<DaySummary> {
        val result = ArrayList<DaySummary>()
        var start = dayStart(newest, zone)
        for (i in 0 until count) {
            val end = addDays(start, 1, zone)
            val to = Math.max(start, Math.min(end, until))
            val stats = StatisticsCalculator.compute(values, start, to, lowLimit, highLimit, zone)
            val means = quarterMeans(values, start, to)
            result.add(DaySummary(start, end, stats, episodes(means, lows = true).size, episodes(means, lows = false).size))
            start = addDays(start, -1, zone)
        }
        return result
    }

    /** The mean of each quarter hour that has values: number of the quarter hour (time / 15 minutes) to mg/dL. */
    fun quarterMeans(values: List<Glucose>, from: Long, to: Long): TreeMap<Long, Double> {
        val sums = TreeMap<Long, DoubleArray>()
        for (glucose in values) {
            if (glucose.timestamp < from || glucose.timestamp >= to) {
                continue
            }
            val cell = sums.getOrPut(Math.floorDiv(glucose.timestamp, SLOT)) { DoubleArray(2) }
            cell[0] += glucose.readableValue.toDouble()
            cell[1] += 1.0
        }
        val means = TreeMap<Long, Double>()
        for ((slot, cell) in sums) {
            means[slot] = cell[0] / cell[1]
        }
        return means
    }

    /**
     * Low events (quarter hours under 70) or high events (over 180). A quarter hour inside the
     * limits ends an event. One quarter hour without values does not; two or more do.
     */
    fun episodes(means: SortedMap<Long, Double>, lows: Boolean): List<Episode> {
        val result = ArrayList<Episode>()
        var first = 0L
        var last = 0L
        var extreme = 0.0
        var count = 0
        fun close() {
            if (count > 0) {
                result.add(Episode(first * SLOT, (last + 1) * SLOT, extreme, count))
                count = 0
            }
        }
        for ((slot, mean) in means) {
            val inside = if (lows) mean < StatisticsCalculator.LOW_BELOW else mean > StatisticsCalculator.HIGH_ABOVE
            if (!inside) {
                close()
                continue
            }
            if (count > 0 && slot - last > 2) {
                close()
            }
            if (count == 0) {
                first = slot
                extreme = mean
            } else {
                extreme = if (lows) Math.min(extreme, mean) else Math.max(extreme, mean)
            }
            last = slot
            count++
        }
        close()
        return result
    }

    private fun localHour(time: Long, zone: TimeZone): Int {
        val local = time + zone.getOffset(time)
        return Math.floorMod(Math.floorDiv(local, HOUR), 24L).toInt()
    }

    private fun parts(means: SortedMap<Long, Double>, lows: List<Episode>, highs: List<Episode>, zone: TimeZone): List<PartSummary> {
        val size = DayPart.values().size
        val sums = DoubleArray(size)
        val counts = IntArray(size)
        val below = IntArray(size)
        val inRange = IntArray(size)
        val above = IntArray(size)
        for ((slot, mean) in means) {
            val k = DayPart.of(localHour(slot * SLOT, zone)).ordinal
            sums[k] += mean
            counts[k]++
            when {
                mean < StatisticsCalculator.LOW_BELOW -> below[k]++
                mean > StatisticsCalculator.HIGH_ABOVE -> above[k]++
                else -> inRange[k]++
            }
        }
        val lowEvents = IntArray(size)
        for (episode in lows) {
            lowEvents[DayPart.of(localHour(episode.start, zone)).ordinal]++
        }
        val highEvents = IntArray(size)
        for (episode in highs) {
            highEvents[DayPart.of(localHour(episode.start, zone)).ordinal]++
        }
        return DayPart.values().map { part ->
            val k = part.ordinal
            PartSummary(part, counts[k], if (counts[k] > 0) sums[k] / counts[k] else null, below[k], inRange[k], above[k], lowEvents[k], highEvents[k])
        }
    }

    /**
     * For each night: the mean from 2 to 3 o'clock and the mean from 5 to 6 o'clock (each needs
     * two quarter hours with values).
     */
    private fun morning(means: SortedMap<Long, Double>, zone: TimeZone): MorningChange? {
        val early = HashMap<Long, DoubleArray>() // day -> [sum, count]
        val late = HashMap<Long, DoubleArray>()
        for ((slot, mean) in means) {
            val time = slot * SLOT
            val local = time + zone.getOffset(time)
            val day = Math.floorDiv(local, DAY)
            val hour = Math.floorMod(Math.floorDiv(local, HOUR), 24L).toInt()
            val cell = when (hour) {
                2 -> early.getOrPut(day) { DoubleArray(2) }
                5 -> late.getOrPut(day) { DoubleArray(2) }
                else -> null
            } ?: continue
            cell[0] += mean
            cell[1] += 1.0
        }
        var nights = 0
        var sum = 0.0
        for ((day, first) in early) {
            val second = late[day] ?: continue
            if (first[1] < 2 || second[1] < 2) {
                continue
            }
            nights++
            sum += second[0] / second[1] - first[0] / first[1]
        }
        if (nights < MIN_NIGHTS) {
            return null
        }
        return MorningChange(nights, sum / nights)
    }

    /** What stands out, the most important first. */
    private fun findings(analysis: Analysis): List<Finding> {
        val stats = analysis.stats
        val list = ArrayList<Finding>()
        val coverage = stats.coveragePercent
        if (coverage < 70) {
            list.add(Finding(FindingKind.FEW_VALUES, FindingLevel.NOTE, a = coverage.toDouble()))
        }
        if (stats.slots < MIN_QUARTERS) {
            return list // too little to say more
        }

        // lows
        if (analysis.under54 >= GOAL_VERY_LOW) {
            list.add(Finding(FindingKind.VERY_LOW_TIME, FindingLevel.URGENT, a = analysis.under54))
        }
        if (analysis.under70 >= GOAL_LOW) {
            list.add(Finding(FindingKind.LOW_TIME, FindingLevel.ATTENTION, a = analysis.under70))
        }
        val lows = analysis.lows
        if (lows.isNotEmpty()) {
            val days = Math.max(1.0, (stats.to - stats.from).toDouble() / DAY)
            val often = lows.size / days >= 2.0 / 7.0 // two a week or more
            list.add(
                Finding(
                    FindingKind.LOW_EVENTS,
                    if (often) FindingLevel.ATTENTION else FindingLevel.NOTE,
                    a = lows.size.toDouble(),
                    b = lows.sumOf { it.minutes }.toDouble() / lows.size,
                    c = lows.maxOf { it.minutes }.toDouble()
                )
            )
            val most = analysis.parts.maxByOrNull { it.lowEvents }
            if (lows.size >= 3 && most != null && most.lowEvents * 2 >= lows.size) {
                list.add(Finding(FindingKind.LOWS_AT_PART, FindingLevel.ATTENTION, a = most.lowEvents.toDouble(), b = lows.size.toDouble(), part = most.part))
            }
        } else if (coverage >= 70) {
            list.add(Finding(FindingKind.NO_LOWS, FindingLevel.GOOD))
        }

        // highs
        if (analysis.over250 >= GOAL_VERY_HIGH) {
            list.add(Finding(FindingKind.VERY_HIGH_TIME, FindingLevel.ATTENTION, a = analysis.over250))
        }
        if (analysis.over180 >= GOAL_HIGH) {
            list.add(Finding(FindingKind.HIGH_TIME, FindingLevel.ATTENTION, a = analysis.over180))
        }
        val mean = stats.mean
        val full = analysis.parts.filter { it.quarters >= 8 && it.mean != null }
        val highest = full.maxByOrNull { it.mean ?: 0.0 }
        val highestMean = highest?.mean
        if (mean != null && full.size >= 3 && highest != null && highestMean != null && highestMean >= mean + 15) {
            list.add(Finding(FindingKind.HIGHEST_PART, FindingLevel.NOTE, a = highestMean, b = mean, part = highest.part))
        }

        // swings and time in range
        val cv = stats.cv
        if (cv != null) {
            if (cv > GOAL_VARIATION) {
                list.add(Finding(FindingKind.SWINGS, FindingLevel.ATTENTION, a = cv))
            } else {
                list.add(Finding(FindingKind.STEADY, FindingLevel.GOOD, a = cv))
            }
        }
        if (analysis.inRange >= GOAL_IN_RANGE) {
            list.add(Finding(FindingKind.IN_RANGE_GOOD, FindingLevel.GOOD, a = analysis.inRange))
        } else {
            list.add(Finding(FindingKind.IN_RANGE_UNDER_GOAL, FindingLevel.NOTE, a = analysis.inRange))
        }

        // against the period before
        val before = analysis.before
        if (before.slots >= MIN_QUARTERS && before.coveragePercent >= 50 && coverage >= 50) {
            val was = before.rangeCounts[2] * 100.0 / before.slots
            val now = analysis.inRange
            if (now - was >= 5) {
                list.add(Finding(FindingKind.BETTER, FindingLevel.GOOD, a = was, b = now))
            } else if (was - now >= 5) {
                list.add(Finding(FindingKind.WORSE, FindingLevel.NOTE, a = was, b = now))
            }
        }

        analysis.morning?.let {
            if (it.change >= 20) {
                list.add(Finding(FindingKind.MORNING_RISE, FindingLevel.NOTE, a = it.nights.toDouble(), b = it.change))
            } else if (it.change <= -20) {
                list.add(Finding(FindingKind.NIGHT_FALL, FindingLevel.NOTE, a = it.nights.toDouble(), b = it.change))
            }
        }

        // the most important first; inside a level the order above stays
        return list.sortedBy { it.level.ordinal }
    }
}
