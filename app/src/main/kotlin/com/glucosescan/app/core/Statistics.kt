package com.glucosescan.app.core

import java.util.TimeZone

/** The values of one hour of the day, over all days of the period. */
class HourBand(
    /** Half of the values are below this, half above (mg/dL). */
    val median: Double,
    /** One value in ten is below this. */
    val low: Double,
    /** One value in ten is above this. */
    val high: Double,
    val count: Int
)

/** Numbers about the glucose values of a period. All glucose numbers are in mg/dL. */
class Statistics(
    val from: Long,
    val to: Long,
    /** Quarter hours of the period that have at least one value. */
    val slots: Int,
    /** Quarter hours in the period. */
    val possibleSlots: Int,
    /** Days with at least one value. A day is 24 hours, counted back from the end of the period. */
    val days: Int,
    /** Null if there is no value. */
    val mean: Double?,
    /** "Glucose management indicator": an estimate of the HbA1c in percent. Null if there is no value. */
    val gmi: Double?,
    /** How much the values differ from the mean (standard deviation). Null with fewer than 2 quarter hours. */
    val sd: Double?,
    /** [sd] in percent of the mean. */
    val cv: Double?,
    val lowest: Int?,
    val highest: Int?,
    /** Quarter hours under 54, 54 to 69, 70 to 180, 181 to 250, over 250 mg/dL. */
    val rangeCounts: IntArray,
    /** Quarter hours inside the target range of the user. */
    val inTarget: Int,
    /** Quarter hours with a mean under the low limit of the person's target range. */
    val below: Int = 0,
    /** Quarter hours with a mean over the high limit of the person's target range. */
    val above: Int = 0,
    /** Quarter hours inside the published tight range, 70 to 140 mg/dL. */
    val inTight: Int = 0,
    /** How many quarter hours grew out of at least one reading: the same as [slots]. */
    val readingCount: Int = 0,
    /** Quarter hours of the period with no reading at all. */
    val missingSlots: Int = 0,
    /** 24 entries, one for each hour of the day. Null where there are fewer than 3 quarter hours. */
    val hours: List<HourBand?>
) {
    val hasValues: Boolean
        get() = slots > 0

    /** Which part of the period has values, in percent. */
    val coveragePercent: Int
        get() = if (possibleSlots <= 0) 0 else Math.min(100, Math.round(slots * 100.0 / possibleSlots).toInt())

    /** The five ranges in whole percent. They add up to 100 (or all are 0 without values). */
    val rangePercents: IntArray
        get() = StatisticsCalculator.wholePercents(rangeCounts)

    val inTargetPercent: Int
        get() = if (slots <= 0) 0 else Math.round(inTarget * 100.0 / slots).toInt()

    /** Quarter hours inside the published tight range (70 to 140 mg/dL), in whole percent. */
    val inTightPercent: Int
        get() = if (slots <= 0) 0 else Math.round(inTight * 100.0 / slots).toInt()

    /** Quarter hours under 54 mg/dL, in whole percent. */
    val veryLowPercent: Int
        get() = if (slots <= 0) 0 else Math.round(rangeCounts[0] * 100.0 / slots).toInt()

    /** Quarter hours over 250 mg/dL, in whole percent. */
    val veryHighPercent: Int
        get() = if (slots <= 0) 0 else Math.round(rangeCounts[4] * 100.0 / slots).toInt()

    /** Quarter hours with a mean under the low limit of the person's target range, in whole percent. */
    val belowPercent: Int
        get() = if (slots <= 0) 0 else Math.round(below * 100.0 / slots).toInt()

    /** Quarter hours with a mean over the high limit of the person's target range, in whole percent. */
    val abovePercent: Int
        get() = if (slots <= 0) 0 else Math.round(above * 100.0 / slots).toInt()

    /**
     * True if the period is long and full enough for the estimate [gmi] to mean something:
     * at least 14 days with values on at least 70 % of the time.
     */
    val gmiIsReliable: Boolean
        get() = to - from >= 14 * StatisticsCalculator.DAY && coveragePercent >= 70
}

/**
 * Counts the statistics.
 *
 * A scan gives one value each minute for 15 minutes and one value each 15 minutes before
 * that. Live values come each minute. If every value counted the same, the minutes around a
 * scan would weigh 15 times more than the rest of the day. So the time is cut into quarter
 * hours, each quarter hour gets the mean of its values, and every quarter hour counts once.
 */
object StatisticsCalculator {
    const val SLOT: Long = 15 * 60_000L
    const val DAY: Long = 24 * 60 * 60_000L

    /** The limits of the five ranges that doctors use (mg/dL). */
    const val VERY_LOW_BELOW = 54
    const val LOW_BELOW = 70
    const val HIGH_ABOVE = 180
    const val VERY_HIGH_ABOVE = 250

    /** The limits of the tight range that is reported next to time in range (mg/dL). */
    const val TIGHT_LOW = 70
    const val TIGHT_HIGH = 140

    /**
     * @param values the values to count, in any order; values outside [from]..[to] are skipped
     * @param from start of the period, milliseconds since 1970
     * @param to end of the period (not included)
     * @param lowLimit the target range of the user, lower end (mg/dL)
     * @param highLimit the target range of the user, upper end (mg/dL)
     */
    fun compute(values: List<Glucose>, from: Long, to: Long, lowLimit: Int, highLimit: Int, zone: TimeZone = TimeZone.getDefault()): Statistics {
        // the mean of each quarter hour
        val sums = HashMap<Long, DoubleArray>() // slot -> [sum, count, time of the newest value]
        var lowest: Int? = null
        var highest: Int? = null
        for (glucose in values) {
            if (glucose.timestamp < from || glucose.timestamp >= to) {
                continue
            }
            val value = glucose.readableValue
            val cell = sums.getOrPut(Math.floorDiv(glucose.timestamp, SLOT)) { DoubleArray(3) }
            cell[0] += value.toDouble()
            cell[1] += 1.0
            cell[2] = Math.max(cell[2], glucose.timestamp.toDouble())
            if (lowest == null || value < lowest) {
                lowest = value
            }
            if (highest == null || value > highest) {
                highest = value
            }
        }

        val possible = Math.max(0L, (to - from + SLOT - 1) / SLOT).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val means = DoubleArray(sums.size)
        val rangeCounts = IntArray(5)
        var inTarget = 0
        // the tight range is fixed at 70 to 140 mg/dL: a published measure, the same on every phone
        var inTight = 0
        var belowTarget = 0
        var aboveTarget = 0
        val days = HashSet<Long>()
        val byHour = Array(24) { ArrayList<Double>() }
        var index = 0
        for ((slot, cell) in sums.toSortedMap()) {
            val mean = cell[0] / cell[1]
            means[index++] = mean
            rangeCounts[rangeOf(mean)]++
            if (mean >= lowLimit && mean <= highLimit) {
                inTarget++
            }
            // The tight range is fixed at 70 to 140 mg/dL, not the person's own target: it is a
            // published measure, so it has to mean the same thing on every phone.
            if (mean >= TIGHT_LOW && mean <= TIGHT_HIGH) {
                inTight++
            }
            if (mean < lowLimit) {
                belowTarget++
            } else if (mean > highLimit) {
                aboveTarget++
            }
            val time = slot * SLOT
            val local = time + zone.getOffset(time)
            // days are counted back from the end of the period: 14 days give at most 14
            days.add(Math.floorDiv(to - 1 - cell[2].toLong(), DAY))
            byHour[Math.floorMod(Math.floorDiv(local, 60 * 60_000L), 24L).toInt()].add(mean)
        }

        val count = means.size
        val mean = if (count > 0) means.sum() / count else null
        var sd: Double? = null
        if (mean != null && count >= 2) {
            var squares = 0.0
            for (value in means) {
                squares += (value - mean) * (value - mean)
            }
            sd = Math.sqrt(squares / (count - 1))
        }

        val hours = ArrayList<HourBand?>(24)
        for (list in byHour) {
            if (list.size < 3) {
                hours.add(null)
            } else {
                list.sort()
                hours.add(HourBand(percentile(list, 0.5), percentile(list, 0.1), percentile(list, 0.9), list.size))
            }
        }

        return Statistics(
            from = from,
            to = to,
            slots = count,
            possibleSlots = possible,
            days = days.size,
            mean = mean,
            gmi = if (mean != null) gmi(mean) else null,
            sd = sd,
            cv = if (sd != null && mean != null && mean > 0) sd / mean * 100.0 else null,
            lowest = lowest,
            highest = highest,
            rangeCounts = rangeCounts,
            inTarget = inTarget,
            below = belowTarget,
            above = aboveTarget,
            inTight = inTight,
            readingCount = count,
            missingSlots = Math.max(0, possible - count),
            hours = hours
        )
    }

    /** 0 = very low, 1 = low, 2 = in range, 3 = high, 4 = very high. */
    fun rangeOf(mgdl: Double): Int {
        return when {
            mgdl < VERY_LOW_BELOW -> 0
            mgdl < LOW_BELOW -> 1
            mgdl <= HIGH_ABOVE -> 2
            mgdl <= VERY_HIGH_ABOVE -> 3
            else -> 4
        }
    }

    /**
     * The "glucose management indicator" in percent, from the mean glucose in mg/dL
     * (Bergenstal and others, Diabetes Care 2018): 3.31 + 0.02392 x mean.
     */
    fun gmi(meanMgdl: Double): Double = 3.31 + 0.02392 * meanMgdl

    /**
     * The value below which the part [p] (0..1) of the sorted list lies. Between two values
     * of the list the result lies between them.
     */
    fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) {
            return Double.NaN
        }
        val position = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val below = Math.floor(position).toInt()
        val above = Math.min(sorted.size - 1, below + 1)
        val part = position - below
        return sorted[below] + (sorted[above] - sorted[below]) * part
    }

    /**
     * Counts as whole percent that add up to exactly 100. The parts that lose most by cutting
     * off the decimals get the percent points that are left over.
     */
    fun wholePercents(counts: IntArray): IntArray {
        val total = counts.sum()
        val result = IntArray(counts.size)
        if (total <= 0) {
            return result
        }
        val rests = DoubleArray(counts.size)
        var used = 0
        for (i in counts.indices) {
            val exact = counts[i] * 100.0 / total
            result[i] = Math.floor(exact).toInt()
            rests[i] = exact - result[i]
            used += result[i]
        }
        var left = 100 - used
        while (left > 0) {
            var best = 0
            for (i in rests.indices) {
                if (rests[i] > rests[best]) {
                    best = i
                }
            }
            result[best]++
            rests[best] = -1.0
            left--
        }
        return result
    }
}
