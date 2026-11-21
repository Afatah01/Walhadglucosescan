package com.glucosescan.app.core

/**
 * How a *measured* reading sits against the limits the user chose for the alarms.
 *
 * Pure code: no Android, no clock of its own. Forecasts are never used here — a prediction is
 * not a measurement, and the measured state of the dashboard must never show one as the other.
 */
object AlertEngine {

    /** The measured reading is below/above the user's limits, or inside them. */
    enum class Measured { NORMAL, LOW, HIGH, VERY_LOW, VERY_HIGH }

    /** A reading older than this is not used as "now" (the same clock [AlarmRules] uses). */
    const val MAX_READING_AGE_MILLIS = AlarmRules.CURRENT_MINUTES * 60_000L

    /**
     * Classifies a measured value (mg/dL) against the user's own limits. Very-low/very-high win
     * over low/high, matching [AlarmRules.reason], so dashboard and alarm can never disagree.
     */
    fun classify(value: Int, settings: AlarmSettings): Measured = when {
        settings.veryLowOn && value < settings.veryLow -> Measured.VERY_LOW
        settings.veryHighOn && value > settings.veryHigh -> Measured.VERY_HIGH
        settings.lowOn && value < settings.low -> Measured.LOW
        settings.highOn && value > settings.high -> Measured.HIGH
        else -> Measured.NORMAL
    }

    /**
     * True when the reading may be used as "now": recent enough, and not from the future
     * (a phone with the wrong clock must not produce an alert that says the reading is current).
     */
    fun isFresh(measuredAt: Long, now: Long): Boolean {
        if (measuredAt - now > 2 * 60_000L) {
            return false
        }
        return now - measuredAt <= MAX_READING_AGE_MILLIS
    }

    /**
     * Unique id of an alert about one reading and one kind of alarm. The same reading produces
     * the same id, so recording is naturally idempotent — a duplicate sync or a duplicate
     * evaluation can never produce two alert-event rows or two alarms for one minute of glucose.
     */
    fun eventId(kind: AlarmKind, measuredAt: Long): String = "${kind.name}:$measuredAt"
}
