package com.glucosescan.app.ui

import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseType
import com.glucosescan.app.core.Trend

/**
 * Decides what the big value on the main screen may show. A value from a scan gets old fast,
 * and an old value must never look like a current one.
 */
class DisplayState(
    val kind: Kind,
    /** The newest value, or null if there is none. */
    val glucose: Glucose?,
    /** How old the newest value is, in whole minutes. */
    val ageMinutes: Int,
    /** Change in mg/dL per minute over about the last 15 minutes, or null if not known. */
    val changePerMinute: Double?,
    /** Over how many minutes [changePerMinute] was measured. */
    val changeMinutes: Int
) {
    enum class Kind {
        /** No value saved yet. */
        NONE,

        /** Up to 5 minutes old: shown normally. */
        CURRENT,

        /** 6 to 15 minutes old: shown, with its age marked. */
        RECENT,

        /** Older than 15 minutes: the big value is not shown. */
        OLD,

        /** The value is from the future: the phone clock was changed. */
        CLOCK_WRONG
    }

    /** True if the big value and the arrow may be shown. */
    val showsValue: Boolean
        get() = kind == Kind.CURRENT || kind == Kind.RECENT

    val trend: Trend?
        get() = if (showsValue && changePerMinute != null) Trend.fromSlope(changePerMinute) else null

    companion object {
        const val CURRENT_MINUTES = 5
        const val RECENT_MINUTES = 15
        private const val MINUTE = 60_000L

        /**
         * @param values the saved values of at least the last 20 minutes before the newest one, in any order
         * @param now milliseconds since 1970
         */
        fun of(values: List<Glucose>, now: Long): DisplayState {
            val latest = values.maxByOrNull { it.timestamp }
                ?: return DisplayState(Kind.NONE, null, 0, null, 0)

            val ageMillis = now - latest.timestamp
            if (ageMillis < -2 * MINUTE) {
                return DisplayState(Kind.CLOCK_WRONG, latest, 0, null, 0)
            }
            val ageMinutes = Math.max(0L, ageMillis / MINUTE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val kind = when {
                ageMinutes <= CURRENT_MINUTES -> Kind.CURRENT
                ageMinutes <= RECENT_MINUTES -> Kind.RECENT
                else -> Kind.OLD
            }

            val past = valueForChange(values, latest)
            if (past == null) {
                return DisplayState(kind, latest, ageMinutes, null, 0)
            }
            val minutes = ((latest.timestamp - past.timestamp) / MINUTE).toInt()
            val change = (latest.value - past.value).toDouble() / minutes
            return DisplayState(kind, latest, ageMinutes, change, minutes)
        }

        /**
         * The value to compare the newest one with: the one closest to 15 minutes before it,
         * and 5 to 16 minutes before it. Null if there is none, if it is from another sensor,
         * or if one of the two values is outside of what the sensor can measure.
         */
        private fun valueForChange(values: List<Glucose>, latest: Glucose): Glucose? {
            if (latest.type != GlucoseType.NORMAL) {
                return null
            }
            var best: Glucose? = null
            var bestDistance = Long.MAX_VALUE
            for (candidate in values) {
                val before = latest.timestamp - candidate.timestamp
                if (before < 5 * MINUTE || before > 16 * MINUTE) {
                    continue
                }
                val distance = Math.abs(before - 15 * MINUTE)
                if (distance < bestDistance) {
                    best = candidate
                    bestDistance = distance
                }
            }
            if (best == null || best.type != GlucoseType.NORMAL) {
                return null
            }
            if (best.sensorSerial != latest.sensorSerial) {
                return null
            }
            return best
        }
    }
}
