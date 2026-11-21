package com.glucosescan.app.core

import com.glucosescan.app.libre.roundHalfAwayFromZero
import java.util.TreeMap

/**
 * Where the glucose goes if it keeps changing as it has been: a line through the real,
 * timestamped readings, damped with the horizon. It is an estimate, not a measurement. Food,
 * insulin, movement, illness and stress change the course, and the line knows nothing of them.
 * All glucose numbers are in mg/dL.
 *
 * The engine (see [Forecaster]) builds the line with exponentially weighted least squares over
 * up to 45 minutes of readings, so a reading from four minutes ago counts more than one from
 * forty minutes ago, but all of them count. Irregular intervals are handled by the time-weighted
 * fit and reported through [regularity]. A big hole in the data (the sensor off the body or the
 * network down) cuts the series: only the readings after the hole are used.
 *
 * This is new code of this app. The original app (Glucose Direct) has no forecast.
 */
class Forecast(
    /** The time of the newest value the model is built on (milliseconds since 1970). */
    val time: Long,
    /** How many values the model is built on. */
    val count: Int,
    /** Minutes from the oldest to the newest of these values. */
    val spanMinutes: Int,
    /** The change of the line in mg/dL per minute. */
    val slope: Double,
    /** The line at [time]. */
    val level: Double,
    /** How far the values lie from the line (weighted standard deviation, mg/dL). */
    val scatter: Double,
    /**
     * The curve of the change (mg/dL per minute squared), from a weighted quadratic fit.
     * Positive means the movement is bending upwards, negative that it is flattening. Used
     * for the confidence of the estimates; the line itself stays linear.
     */
    val acceleration: Double = 0.0,
    /**
     * How regularly the readings arrived: 1.0 means about every minute, lower means gaps and
     * irregular intervals. Slides the confidence down; never invents values.
     */
    val regularity: Double = 1.0
) {
    /** The line at the moment [at], with decimals and without limits. */
    fun exact(at: Long): Double = level + slope * ((at - time) / 60_000.0)

    /** The line at the moment [at] as a whole number, limited to what a sensor can show (39..501). */
    fun valueAt(at: Long): Int {
        val value = exact(at).coerceIn(-10_000.0, 10_000.0)
        return Glucose.limitToReadable(roundHalfAwayFromZero(value).toInt())
    }

    /**
     * Whole minutes from [now] until the line is below [limit], or null: the line is below it
     * already, it does not fall clearly (less than [Forecaster.MIN_FALL] a minute), or it needs
     * longer than [within] minutes.
     */
    fun minutesUntilBelow(limit: Int, now: Long, within: Int = Forecaster.SOON_MINUTES): Int? {
        val current = exact(now)
        if (current < limit || slope > -Forecaster.MIN_FALL) {
            return null
        }
        val minutes = Math.floor((current - limit) / -slope).toInt() + 1 // the first whole minute below the limit
        return if (minutes <= within) minutes else null
    }

    /**
     * How wide the plausible range of the estimate at [minutes] ahead is, as a half-width in
     * mg/dL. It grows with the horizon: the farther the guess reaches, the less sure it is.
     * Derived only from the scatter of the readings themselves, never assumed.
     */
    fun uncertaintyAt(minutes: Int): Double {
        val horizon = minutes.coerceAtLeast(0) / 15.0
        return scatter * Math.sqrt(1.0 + horizon * Forecaster.UNCERTAINTY_GROWTH) * (1.0 + (1.0 - regularity) * 0.6)
    }
}

object Forecaster {
    private const val MINUTE = 60_000L

    /** How far back the short window reaches, for the "recent trend" words and the short anchor. */
    const val WINDOW_MINUTES = 15

    /** The readings of this many minutes before the newest one build the model. */
    const val MODEL_WINDOW_MINUTES = 45

    /** How far back the history is looked at before a hole in the data cuts the series. */
    const val HISTORY_MINUTES = 90

    /** A hole in the data this wide (minutes) cuts everything before it. */
    private const val GAP_CUT_MINUTES = 15

    /** Fewer values than this make no model. */
    const val MIN_VALUES = 5

    /** The values must reach back at least this many minutes. */
    const val MIN_SPAN_MINUTES = 8

    /** A value older than this is not continued: as on the main screen, where it no longer counts as current. */
    const val MAX_AGE_MINUTES = 5

    /** Values that jump more than this around the line make no forecast. */
    const val MAX_SCATTER = 10.0

    /** How far the line is continued. */
    const val HORIZON_MINUTES = 30

    /** The far horizon the Home screen offers when the line is strong enough for it, in minutes. */
    const val FAR_HORIZON_MINUTES = 60

    /** "Soon" means within this many minutes. */
    const val SOON_MINUTES = 20

    /** A fall slower than this (mg/dL per minute) is not called "low soon": near a limit it would come and go. */
    const val MIN_FALL = 0.5

    /** The half-life of the weights, in minutes: a reading this much older counts half as much. */
    private const val HALF_LIFE_MINUTES = 18.0

    /** How much the uncertainty half-width grows per 15 minutes of horizon, multiplied by the scatter. */
    const val UNCERTAINTY_GROWTH = 0.9

    /**
     * @param values saved values in any order; only those of the last [HISTORY_MINUTES] minutes
     * before the newest one, and of the same sensor, are used
     * @param now milliseconds since 1970
     * @return null if there is no current value, too little to build on, or the readings jump too much
     */
    fun of(values: List<Glucose>, now: Long): Forecast? {
        val newest = values.maxByOrNull { it.timestamp } ?: return null
        val age = now - newest.timestamp
        if (age < -2 * MINUTE || age / MINUTE > MAX_AGE_MINUTES || newest.type != GlucoseType.NORMAL) {
            return null
        }

        // one value for each timestamp, newest sensor only, real values only
        val from = newest.timestamp - HISTORY_MINUTES * MINUTE
        val sameAll = TreeMap<Long, Glucose>()
        for (glucose in values) {
            if (glucose.timestamp < from || glucose.timestamp > newest.timestamp) {
                continue
            }
            if (glucose.sensorSerial != newest.sensorSerial || glucose.type != GlucoseType.NORMAL) {
                continue
            }
            sameAll[glucose.timestamp] = glucose
        }
        if (sameAll.isEmpty()) {
            return null
        }

        // A hole in the data (sensor off the body, network down, phone off) cuts the series:
        // only the readings after the hole still describe where the glucose goes now.
        val stamps = sameAll.keys.toList()
        var seriesFrom = from
        for (index in 1 until stamps.size) {
            val gap = stamps[index] - stamps[index - 1]
            if (gap > GAP_CUT_MINUTES * MINUTE) {
                seriesFrom = stamps[index]
            }
        }

        // The model is built on the last MODEL_WINDOW_MINUTES after any cut.
        val modelFrom = Math.max(seriesFrom, newest.timestamp - MODEL_WINDOW_MINUTES * MINUTE)
        val used = TreeMap<Long, Glucose>()
        for ((time, glucose) in sameAll) {
            if (time >= modelFrom) {
                used[time] = glucose
            }
        }
        val n = used.size
        if (n < MIN_VALUES) {
            return null
        }
        val span = (newest.timestamp - used.firstKey()) / MINUTE
        if (span < MIN_SPAN_MINUTES) {
            return null
        }

        // ---- exponentially weighted least squares: level + slope through real timestamps ----
        // x = minutes before the newest value (0 or negative), y = mg/dL
        val halfLife = HALF_LIFE_MINUTES
        var sumW = 0.0
        var sumWX = 0.0
        var sumWY = 0.0
        var sumWXX = 0.0
        var sumWXY = 0.0
        for ((time, glucose) in used) {
            val x = (time - newest.timestamp) / 60_000.0
            val y = glucose.value.toDouble()
            val w = Math.pow(0.5, -x / halfLife) // newer readings weigh more; x is <= 0
            sumW += w
            sumWX += w * x
            sumWY += w * y
            sumWXX += w * x * x
            sumWXY += w * x * y
        }
        val denominator = sumW * sumWXX - sumWX * sumWX
        if (denominator <= 0.0) {
            return null
        }
        val slope = (sumW * sumWXY - sumWX * sumWY) / denominator
        val level = (sumWY - slope * sumWX) / sumW

        // weighted residual scatter, with the effective sample size (readings are not equally weighted)
        var sumWR2 = 0.0
        var sumW2 = 0.0
        for ((time, glucose) in used) {
            val x = (time - newest.timestamp) / 60_000.0
            val w = Math.pow(0.5, -x / halfLife)
            val rest = glucose.value - (level + slope * x)
            sumWR2 += w * rest * rest
            sumW2 += w * w
        }
        val effectiveN = if (sumW2 > 0.0) sumW * sumW / sumW2 else n.toDouble()
        val scatter = Math.sqrt(sumWR2 / Math.max(effectiveN - 2.0, 1.0))
        if (scatter > MAX_SCATTER) {
            return null
        }

        // ---- acceleration: weighted quadratic term, used for confidence, never for the line ----
        var acceleration = 0.0
        if (n >= 6) {
            var sW = 0.0
            var sX = 0.0
            var sX2 = 0.0
            var sX3 = 0.0
            var sX4 = 0.0
            var sY = 0.0
            var sXY = 0.0
            var sX2Y = 0.0
            for ((time, glucose) in used) {
                val x = (time - newest.timestamp) / 60_000.0
                val x2 = x * x
                val y = glucose.value.toDouble()
                val w = Math.pow(0.5, -x / halfLife)
                sW += w
                sX += w * x
                sX2 += w * x2
                sX3 += w * x2 * x
                sX4 += w * x2 * x2
                sY += w * y
                sXY += w * x * y
                sX2Y += w * x2 * y
            }
            // solve the 3x3 normal equations for a + b x + c x^2 (Cramer's rule)
            val m = arrayOf(
                doubleArrayOf(sW, sX, sX2, sY),
                doubleArrayOf(sX, sX2, sX3, sXY),
                doubleArrayOf(sX2, sX3, sX4, sX2Y)
            )
            val det = det3(m)
            if (Math.abs(det) > 1e-9) {
                val detC = det3(
                    arrayOf(
                        doubleArrayOf(sW, sX, sY),
                        doubleArrayOf(sX, sX2, sXY),
                        doubleArrayOf(sX2, sX3, sX2Y)
                    )
                )
                acceleration = (2.0 * (detC / det)).coerceIn(-0.15, 0.15)
            }
        }

        // ---- how regularly the readings arrived (expected: about one per minute) ----
        val regularity = regularity(stamps)

        return Forecast(newest.timestamp, n, span.toInt(), slope, level, scatter, acceleration, regularity)
    }

    /** Determinant of a 3x3 augmented matrix's leading 3x3 (first three columns). */
    private fun det3(m: Array<DoubleArray>): Double {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
    }

    /**
     * 1.0 when readings came about every minute; slides down with gaps and irregular intervals.
     * Only the readings after the most recent hole in the series are counted.
     */
    private fun regularity(stamps: List<Long>): Double {
        if (stamps.size < 3) {
            return 0.4
        }
        val latest = stamps.last()
        var seriesFrom = stamps.first()
        for (index in 1 until stamps.size) {
            if (stamps[index] - stamps[index - 1] > GAP_CUT_MINUTES * MINUTE) {
                seriesFrom = stamps[index]
            }
        }
        val used = stamps.filter { it >= seriesFrom }
        if (used.size < 3) {
            return 0.4
        }
        var irregular = 0
        var total = 0
        for (index in 1 until used.size) {
            val gapMinutes = (used[index] - used[index - 1]) / 60_000.0
            total++
            if (gapMinutes > 3.5) { // missing about two or more expected readings in a row
                irregular++
            }
        }
        var score = 1.0 - irregular.toDouble() / total * 0.65
        // and if even the newest reading of the series is old, say so
        val newestAgeMinutes = (latest - used.first()) / 60_000.0
        if (used.size < MIN_VALUES || newestAgeMinutes <= 0.0) {
            score *= 0.7
        }
        return score.coerceIn(0.1, 1.0)
    }
}
