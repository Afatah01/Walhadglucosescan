package com.glucosescan.app.core

/**
 * What the next half hour looks like, and what to do about it.
 *
 * This is a guess, not a measurement. The line is the straight line that lies closest to the
 * values of the last 15 minutes, carried forward 30 minutes. Food, insulin, movement, illness
 * and stress all change the course, and the line knows nothing of any of them. Every screen that
 * shows a number from here must say so.
 *
 * The advice is written by this app, on the phone, with no network. It never names a dose and
 * never tells anybody to change one: for that, the words point at the person's own plan and at
 * their doctor.
 */
class Outlook(
    /** The line it comes from. */
    val forecast: Forecast,
    /** When the prediction is for: now plus 30 minutes. */
    val at: Long,
    /** The newest measured value, mg/dL. */
    val nowValue: Int,
    /** Where the line is at [at], mg/dL. */
    val value: Int,
    /** Which way it is heading. */
    val direction: OutlookDirection,
    /** What is worth paying attention to. */
    val risk: OutlookRisk,
    /** Whole minutes until the line is under the low limit, or null. */
    val minutesUntilLow: Int?
) {

    /** When the fifteen-minute estimate is for. */
    val at15: Long
        get() = at - 15 * 60_000L

    /** Where the line is at [at15], mg/dL. */
    val value15: Int
        get() = forecast.valueAt(at15)

    /**
     * How much the guess is to be trusted.
     *
     * It comes from how tightly the last values lie around the line, not from
     * how many there are alone: a line through values that jump about is a poor
     * guide however many of them there were. The card says so in words.
     */
    val confidence: OutlookConfidence
        get() = when {
            forecast.scatter <= Outlooks.FAIR_SCATTER && forecast.count >= Outlooks.GOOD_COUNT -> OutlookConfidence.GOOD
            forecast.scatter <= Outlooks.LOW_SCATTER -> OutlookConfidence.FAIR
            else -> OutlookConfidence.LOW
        }
    /** How much the line climbs or falls each minute, mg/dL. */
    val perMinute: Double
        get() = forecast.slope

    /** How many values the line was built on. */
    val basedOn: Int
        get() = forecast.count

    /** How far back those values reach, in minutes. */
    val spanMinutes: Int
        get() = forecast.spanMinutes

    /**
     * True when the words at the top of the card must be about staying safe rather than about
     * the number: a severe low, or a low that is still falling.
     */
    val isUrgent: Boolean
        get() = risk == OutlookRisk.SEVERE_LOW ||
            (risk == OutlookRisk.LOW && direction == OutlookDirection.FALLING)
}

/** Which way the line goes. */
enum class OutlookDirection {
    RISING,
    FALLING,
    STEADY
}

/** How much the guess is to be trusted. */
enum class OutlookConfidence {
    /** The values lie close to the line. */
    GOOD,

    /** Some spread, but the line is a fair guide. */
    FAIR,

    /** The values jump about: treat the number with care. */
    LOW
}

/** What the number calls for. Ordered from the most urgent. */
enum class OutlookRisk {
    /** Under 54 mg/dL. */
    SEVERE_LOW,

    /** Under the low limit the person set (70 mg/dL unless they changed it). */
    LOW,

    /** Still in range, but the line crosses the low limit within 20 minutes. */
    LOW_SOON,

    /** Over 250 mg/dL. */
    VERY_HIGH,

    /** Over the high limit the person set (180 mg/dL unless they changed it). */
    HIGH,

    /** Nothing that calls for a warning. */
    NONE
}

object Outlooks {

    /** Below this the words are about staying safe, whatever the line does. */
    private const val SEVERE_LOW = StatisticsCalculator.VERY_LOW_BELOW

    /** Above this it is called very high, whatever the high limit is set to. */
    private const val VERY_HIGH = StatisticsCalculator.VERY_HIGH_ABOVE

    /**
     * @param forecast the line, or null when there is not enough to build one
     * @param nowValue the newest measured value, mg/dL
     * @param lowLimit the low limit the person set
     * @param highLimit the high limit the person set
     * @return null when there is nothing to say but "not enough values"
     */
    fun of(forecast: Forecast?, now: Long, nowValue: Int, lowLimit: Int, highLimit: Int): Outlook? {
        val line = forecast ?: return null
        val at = now + Forecaster.HORIZON_MINUTES * 60_000L
        val value = line.valueAt(at)

        val slope = line.slope
        val direction = when {
            slope >= MIN_SLOPE -> OutlookDirection.RISING
            slope <= -MIN_SLOPE -> OutlookDirection.FALLING
            else -> OutlookDirection.STEADY
        }

        val minutes = line.minutesUntilBelow(lowLimit, now, Forecaster.SOON_MINUTES)
        val risk = when {
            nowValue < SEVERE_LOW -> OutlookRisk.SEVERE_LOW
            nowValue < lowLimit -> OutlookRisk.LOW
            minutes != null -> OutlookRisk.LOW_SOON
            nowValue > VERY_HIGH -> OutlookRisk.VERY_HIGH
            nowValue > highLimit -> OutlookRisk.HIGH
            else -> OutlookRisk.NONE
        }

        return Outlook(line, at, nowValue, value, direction, risk, minutes)
    }

    /** Slower than this (mg/dL a minute) is called steady: near a limit it would come and go. */
    private const val MIN_SLOPE = 0.5

    /** How far the values may lie from the line and still count as a fair guide. */
    const val FAIR_SCATTER = 3.0

    /** Beyond this the values jump about too much for the number to be worth much. */
    const val LOW_SCATTER = 6.0

    /** How many values the line needs before the guess is called good. */
    const val GOOD_COUNT = 10
}
