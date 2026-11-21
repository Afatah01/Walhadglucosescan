package com.glucosescan.app.core

enum class AlarmKind {
    /** The value is below the alarm limit. */
    LOW,

    /** The value is above the alarm limit. */
    HIGH,

    /** The sensor has sent no value for some time. */
    NO_DATA,

    /**
     * The value is not below the low limit yet, but the forecast says it will be within 20
     * minutes (see [Forecast]). New at the end, so that the numbers of the older kinds stay.
     */
    LOW_SOON,

    /** The value is below the very-low (severe) limit. New at the end, so that the numbers of the older kinds stay. */
    VERY_LOW,

    /** The value is above the very-high limit. */
    VERY_HIGH,

    /** The glucose is rising faster than the rapid-change rate the user set. */
    RAPID_RISE,

    /** The glucose is falling faster than the rapid-change rate the user set. */
    RAPID_FALL
}

/** What the user has chosen for the alarms. Glucose numbers are in mg/dL. */
class AlarmSettings(
    val lowOn: Boolean,
    val low: Int,
    val highOn: Boolean,
    val high: Int,
    val noDataOn: Boolean,
    /** "No values" is alarmed when the newest value is older than this. */
    val noDataMinutes: Int = DEFAULT_NO_DATA_MINUTES,
    /** While the reason stays, the same alarm sounds again after this many minutes. */
    val repeatMinutes: Int = DEFAULT_REPEAT_MINUTES,
    /** True: an alarm sounds when the forecast goes below [low] within 20 minutes. */
    val lowSoonOn: Boolean = false,
    /** True: an alarm sounds when the value is below the very-low limit. */
    val veryLowOn: Boolean = true,
    /** The very-low limit (mg/dL). */
    val veryLow: Int = DEFAULT_VERY_LOW,
    /** True: an alarm sounds when the value is above the very-high limit. */
    val veryHighOn: Boolean = false,
    /** The very-high limit (mg/dL). */
    val veryHigh: Int = DEFAULT_VERY_HIGH,
    /** True: an alarm sounds when the glucose changes faster than [rapidRate]. */
    val rapidOn: Boolean = false,
    /** The rapid-change rate, mg/dL per minute. */
    val rapidRate: Double = DEFAULT_RAPID_RATE
) {
    companion object {
        const val DEFAULT_LOW = 70
        const val DEFAULT_HIGH = 250
        const val DEFAULT_NO_DATA_MINUTES = 20
        const val DEFAULT_REPEAT_MINUTES = 15
        const val DEFAULT_VERY_LOW = 54
        const val DEFAULT_VERY_HIGH = 250
        const val DEFAULT_RAPID_RATE = 2.0
        const val DEFAULT_RAPID_TENTHS = 20
        const val SNOOZE_MINUTES = 30

        /** "Low soon" is a guess that can come and go: it sounds at most once in this many minutes. */
        const val SOON_REPEAT_MINUTES = 30
    }
}

/** What was alarmed last, and what the user has silenced. Times are milliseconds since 1970. */
class AlarmMemory(
    val lastKind: AlarmKind? = null,
    val lastTime: Long = 0L,
    val silencedKind: AlarmKind? = null,
    val silencedUntil: Long = 0L,
    /** When "low soon" sounded last. Kept when the reason goes away. */
    val lastSoonTime: Long = 0L
)

/** Decides when an alarm sounds. No Android code here, so it is easy to test. */
object AlarmRules {
    private const val MINUTE = 60_000L

    /** A value older than this is not used for a low or high alarm. */
    const val CURRENT_MINUTES = 5

    /**
     * The reason for an alarm that holds right now, or null. It does not look at what was
     * alarmed before.
     *
     * @param shown the newest value as the app shows it (with the correction of the user), or null
     * @param sensor the same value as the sensor formula gave it (without correction), or null
     * @param waitingSince since when values are expected: when live values were switched on, or
     * when the sensor last said that it is still warming up. Before that nobody could expect a value.
     * @param minutesToLow in how many minutes the forecast goes below the low limit, or null if
     * it does not within 20 minutes (see [Forecast.minutesUntilBelow])
     * @param ratePerMinute the change of the forecast line in mg/dL per minute (from the shown
     * values and the sensor values alike; the rapider of the two counts), or null if not known
     */
    fun reason(shown: Glucose?, sensor: Glucose?, now: Long, settings: AlarmSettings, waitingSince: Long, minutesToLow: Int? = null, ratePerMinute: Double? = null): AlarmKind? {
        val lastSign = Math.max(shown?.timestamp ?: Long.MIN_VALUE, waitingSince)
        if (now - lastSign > settings.noDataMinutes * MINUTE) {
            return if (settings.noDataOn) AlarmKind.NO_DATA else null
        }
        if (shown == null) {
            return null
        }
        if (now - shown.timestamp > CURRENT_MINUTES * MINUTE + MINUTE - 1 || shown.timestamp - now > 2 * MINUTE) {
            return null // too old for a glucose alarm, not old enough for "no values"; or the clock is wrong
        }
        // A correction must never hide a low value: the sensor value counts too.
        val veryLow = shown.value < settings.veryLow || (sensor != null && sensor.value < settings.veryLow)
        if (settings.veryLowOn && veryLow) {
            return AlarmKind.VERY_LOW
        }
        val veryHigh = shown.value > settings.veryHigh || (sensor != null && sensor.value > settings.veryHigh)
        if (settings.veryHighOn && veryHigh) {
            return AlarmKind.VERY_HIGH
        }
        val low = shown.value < settings.low || (sensor != null && sensor.value < settings.low)
        if (settings.lowOn && low) {
            return AlarmKind.LOW
        }
        if (settings.lowSoonOn && !low && minutesToLow != null) {
            return AlarmKind.LOW_SOON
        }
        if (settings.highOn && shown.value > settings.high) {
            return AlarmKind.HIGH
        }
        if (settings.rapidOn && ratePerMinute != null) {
            if (ratePerMinute >= settings.rapidRate) {
                return AlarmKind.RAPID_RISE
            }
            if (ratePerMinute <= -settings.rapidRate) {
                return AlarmKind.RAPID_FALL
            }
        }
        return null
    }

    /**
     * The alarm that must sound now, or null: there is a [reason], the user has not silenced
     * it, and the same alarm did not sound in the last [AlarmSettings.repeatMinutes] minutes.
     */
    fun decide(reason: AlarmKind?, now: Long, settings: AlarmSettings, memory: AlarmMemory): AlarmKind? {
        val kind = reason ?: return null
        if (memory.silencedKind == kind && now < memory.silencedUntil) {
            return null
        }
        if (memory.lastKind == kind && now >= memory.lastTime && now - memory.lastTime < settings.repeatMinutes * MINUTE) {
            return null
        }
        if (kind == AlarmKind.LOW_SOON && now >= memory.lastSoonTime && now - memory.lastSoonTime < AlarmSettings.SOON_REPEAT_MINUTES * MINUTE) {
            return null
        }
        return kind
    }

    /**
     * What to remember after [decide]. When the reason is gone, the memory of the last alarm
     * is cleared, so that the next low or high value sounds at once.
     */
    fun remember(memory: AlarmMemory, reason: AlarmKind?, sounded: AlarmKind?, now: Long): AlarmMemory {
        val silenceHolds = memory.silencedKind != null && now < memory.silencedUntil
        val silencedKind = if (silenceHolds) memory.silencedKind else null
        val silencedUntil = if (silenceHolds) memory.silencedUntil else 0L
        val soonTime = if (sounded == AlarmKind.LOW_SOON) now else memory.lastSoonTime
        return when {
            sounded != null -> AlarmMemory(sounded, now, silencedKind, silencedUntil, soonTime)
            reason == null -> AlarmMemory(null, 0L, silencedKind, silencedUntil, soonTime)
            reason != memory.lastKind -> AlarmMemory(null, 0L, silencedKind, silencedUntil, soonTime)
            else -> AlarmMemory(memory.lastKind, memory.lastTime, silencedKind, silencedUntil, soonTime)
        }
    }

    /** The user pressed "Silence": this kind of alarm stays quiet for [minutes]. */
    fun silence(memory: AlarmMemory, kind: AlarmKind, now: Long, minutes: Int = AlarmSettings.SNOOZE_MINUTES): AlarmMemory {
        return AlarmMemory(memory.lastKind, memory.lastTime, kind, now + minutes * MINUTE, memory.lastSoonTime)
    }
}
