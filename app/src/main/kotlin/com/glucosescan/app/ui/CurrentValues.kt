package com.glucosescan.app.ui

import com.glucosescan.app.core.Forecast
import com.glucosescan.app.core.Forecaster
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.SpeechText
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings

/** The newest value as the app shows it, together with what the sensor formula gave. */
class CurrentValue(
    /** What may be shown: built from the values with the correction of the user. */
    val state: DisplayState,
    /** The newest value as it is stored (without correction), or null. */
    val sensor: Glucose?,
    /** True if a correction of the user changes the shown value. */
    val corrected: Boolean,
    private val lowLimit: Int,
    private val highLimit: Int,
    /** Where the shown values are heading, or null if that cannot be said (see [Forecaster]). */
    val forecast: Forecast? = null,
    /** The same for the values without the correction of the user; null if there is no correction. */
    val sensorForecast: Forecast? = null
) {
    /** The newest value as it is shown, or null. */
    val shown: Glucose?
        get() = state.glucose

    /** True if the shown value is at most 5 minutes old. */
    val isCurrent: Boolean
        get() = state.kind == DisplayState.Kind.CURRENT && state.glucose != null

    /** Below the target range. A correction never hides a low value: the sensor value counts too. */
    val below: Boolean
        get() {
            val value = shown ?: return false
            return value.value < lowLimit || (corrected && sensor != null && sensor.value < lowLimit)
        }

    val above: Boolean
        get() {
            val value = shown ?: return false
            return !below && value.value > highLimit
        }

    /**
     * In how many minutes the forecast goes below [limit] (mg/dL), or null if it does not
     * within 20 minutes or there is no current value. A correction never hides a low value:
     * the forecast of the sensor values counts too, and the earlier of the two is given.
     */
    fun minutesUntilBelow(limit: Int, now: Long): Int? {
        if (!isCurrent) {
            return null
        }
        val shownMinutes = forecast?.minutesUntilBelow(limit, now)
        val sensorMinutes = sensorForecast?.minutesUntilBelow(limit, now)
        return when {
            shownMinutes == null -> sensorMinutes
            sensorMinutes == null -> shownMinutes
            else -> Math.min(shownMinutes, sensorMinutes)
        }
    }

    /** Minutes until the value goes below the target range, by the forecast; null if it is below already or does not. */
    fun minutesUntilBelowTarget(now: Long): Int? = if (below) null else minutesUntilBelow(lowLimit, now)

    /**
     * True if the value is below the target range and still falls clearly: the line through
     * the last 15 minutes loses at least 0.5 mg/dL a minute (the sensor values count too).
     */
    val fallsWhileBelow: Boolean
        get() = isCurrent && below && listOfNotNull(forecast, sensorForecast).any { it.slope <= -Forecaster.MIN_FALL }

    /**
     * True if "Steady" would be the wrong word for the arrow "steady" (a change of less than
     * 1 mg/dL a minute): the value falls slowly, and it is below the target range already or
     * will be within 20 minutes. Then the app says "Falling slowly".
     */
    fun fallsTowardsLow(now: Long): Boolean = fallsWhileBelow || minutesUntilBelowTarget(now) != null

    /**
     * The sentence to say for this value, or null if there is no current value.
     * @param soonAt the time for which "Below target soon" is checked, or null to leave it out
     */
    fun speech(unit: GlucoseUnit, withTrend: Boolean, soonAt: Long? = null): String? {
        val value = shown ?: return null
        if (!isCurrent) {
            return null
        }
        val lowSoon = soonAt != null && minutesUntilBelowTarget(soonAt) != null
        return SpeechText.value(value, unit, state.trend, below, above, withTrend, lowSoon, fallsWhileBelow)
    }
}

object CurrentValues {
    fun read(store: GlucoseStore, settings: Settings, now: Long): CurrentValue {
        val correction = settings.correction
        val latest = store.latest()
        val recent = if (latest != null) correction.applyAll(store.since(latest.timestamp - 20 * 60_000L)) else emptyList()
        val corrected = latest != null && correction.appliesTo(latest)
        return CurrentValue(
            state = DisplayState.of(recent, now),
            sensor = latest,
            corrected = corrected,
            lowLimit = settings.lowLimit,
            highLimit = settings.highLimit,
            forecast = Forecaster.of(recent, now),
            sensorForecast = if (latest != null && correction.appliesTo(latest)) Forecaster.of(store.since(latest.timestamp - 20 * 60_000L), now) else null
        )
    }
}
