package com.glucosescan.app.core

import com.glucosescan.app.libre.roundHalfAwayFromZero
import java.util.TreeMap

/**
 * Where the glucose goes if it keeps changing as in the last minutes: a straight line through
 * the values of the last 15 minutes, continued for half an hour.
 *
 * It is a guess, not a measurement. Food, insulin and movement change the course, and the
 * line knows nothing of them. All glucose numbers are in mg/dL.
 *
 * This is new code of this app. The original app (Glucose Direct) has no forecast.
 */
class Forecast(
    /** The time of the newest value the line is built on (milliseconds since 1970). */
    val time: Long,
    /** How many values the line is built on. */
    val count: Int,
    /** Minutes from the oldest to the newest of these values. */
    val spanMinutes: Int,
    /** The change of the line in mg/dL per minute. */
    val slope: Double,
    /** The line at [time]. */
    val level: Double,
    /** How far the values lie from the line (standard deviation). */
    val scatter: Double
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
}

object Forecaster {
    private const val MINUTE = 60_000L

    /** The line is built on the values of this many minutes before the newest value. */
    const val WINDOW_MINUTES = 15

    /** Fewer values than this make no line. */
    const val MIN_VALUES = 5

    /** The values must reach back at least this many minutes. */
    const val MIN_SPAN_MINUTES = 8

    /** A value older than this is not continued: as on the main screen, where it no longer counts as current. */
    const val MAX_AGE_MINUTES = 5

    /** Values that jump more than this around the line make no forecast. */
    const val MAX_SCATTER = 10.0

    /** How far the line is continued. */
    const val HORIZON_MINUTES = 30

    /** "Soon" means within this many minutes. */
    const val SOON_MINUTES = 20

    /** A fall slower than this (mg/dL per minute) is not called "low soon": near a limit it would come and go. */
    const val MIN_FALL = 0.5

    /**
     * @param values saved values in any order; only those of the last [WINDOW_MINUTES] minutes
     * before the newest one, and of the same sensor, are used
     * @param now milliseconds since 1970
     * @return null if there is no current value or too little to build a line on
     */
    fun of(values: List<Glucose>, now: Long): Forecast? {
        val newest = values.maxByOrNull { it.timestamp } ?: return null
        val age = now - newest.timestamp
        if (age < -2 * MINUTE || age / MINUTE > MAX_AGE_MINUTES || newest.type != GlucoseType.NORMAL) {
            return null
        }

        val from = newest.timestamp - WINDOW_MINUTES * MINUTE
        val used = TreeMap<Long, Glucose>() // one value for each minute
        for (glucose in values) {
            if (glucose.timestamp < from || glucose.timestamp > newest.timestamp) {
                continue
            }
            if (glucose.sensorSerial != newest.sensorSerial || glucose.type != GlucoseType.NORMAL) {
                continue
            }
            used[glucose.timestamp] = glucose
        }
        val n = used.size
        val span = (newest.timestamp - used.firstKey()) / MINUTE
        if (n < MIN_VALUES || span < MIN_SPAN_MINUTES) {
            return null
        }

        // the straight line that lies closest to the values (least squares);
        // x = minutes before the newest value (0 or negative), y = mg/dL
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        for ((time, glucose) in used) {
            val x = (time - newest.timestamp) / 60_000.0
            val y = glucose.value.toDouble()
            sumX += x
            sumY += y
            sumXX += x * x
            sumXY += x * y
        }
        val denominator = n * sumXX - sumX * sumX
        if (denominator <= 0.0) {
            return null
        }
        val slope = (n * sumXY - sumX * sumY) / denominator
        val level = (sumY - slope * sumX) / n

        var squares = 0.0
        for ((time, glucose) in used) {
            val x = (time - newest.timestamp) / 60_000.0
            val rest = glucose.value - (level + slope * x)
            squares += rest * rest
        }
        val scatter = Math.sqrt(squares / (n - 2))
        if (scatter > MAX_SCATTER) {
            return null
        }
        return Forecast(newest.timestamp, n, span.toInt(), slope, level, scatter)
    }
}
