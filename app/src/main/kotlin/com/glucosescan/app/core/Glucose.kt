// The rules in this file are ported from Glucose Direct (MIT License, Copyright (c) 2023
// Reimar Metzen): Library/Content/SensorGlucose.swift, SensorTrend.swift, SensorReading.swift,
// GlucoseUnit.swift, DirectConfig.swift and Library/Extensions/Double.swift.

package com.glucosescan.app.core

import com.glucosescan.app.libre.roundHalfAwayFromZero

enum class GlucoseUnit(val label: String) {
    MGDL("mg/dL"),
    MMOLL("mmol/L");

    companion object {
        /** mmol/L = mg/dL x 0.0555 */
        const val EXCHANGE_RATE: Double = 0.0555
    }
}

enum class GlucoseType {
    NORMAL,

    /** Below what the sensor can measure (under 40 mg/dL). */
    LOW,

    /** Above what the sensor can measure (over 500 mg/dL). */
    HIGH
}

/** The direction arrow. */
enum class Trend(val arrow: String) {
    RAPIDLY_RISING("↑↑"),
    FAST_RISING("↑"),
    RISING("↗"),
    CONSTANT("→"),
    FALLING("↘"),
    FAST_FALLING("↓"),
    RAPIDLY_FALLING("↓↓"),
    UNKNOWN("?");

    companion object {
        /** @param slope change in mg/dL per minute */
        fun fromSlope(slope: Double): Trend {
            return if (slope > 3.5) {
                RAPIDLY_RISING
            } else if (slope > 2) {
                FAST_RISING
            } else if (slope > 1) {
                RISING
            } else if (slope < -3.5) {
                RAPIDLY_FALLING
            } else if (slope < -2) {
                FAST_FALLING
            } else if (slope < -1) {
                FALLING
            } else {
                CONSTANT
            }
        }
    }
}

/** One glucose value as the app keeps and shows it. */
class Glucose(
    timestamp: Long,
    /** mg/dL as a whole number. Not limited: can be under 39 or over 501. */
    val value: Int,
    /** Change against the value before, in mg/dL per minute. Null if there was no value before. */
    val minuteChange: Double?,
    /** True for the one-minute values, false for the 15-minute values. */
    val fromTrend: Boolean,
    /** Serial number of the sensor that made the value, or null. */
    val sensorSerial: String? = null
) {
    /** Milliseconds since 1970. Always the start of a minute: seconds are cut off. */
    val timestamp: Long = timestamp - Math.floorMod(timestamp, 60_000L)

    /** The value limited to what a sensor can measure: 39 means "low", 501 means "high". */
    val readableValue: Int
        get() = limitToReadable(value)

    val type: GlucoseType
        get() = when {
            value < MIN_READABLE_GLUCOSE -> GlucoseType.LOW
            value > MAX_READABLE_GLUCOSE -> GlucoseType.HIGH
            else -> GlucoseType.NORMAL
        }

    val trend: Trend
        get() = if (minuteChange != null) Trend.fromSlope(minuteChange) else Trend.UNKNOWN

    /**
     * The same value with its change per minute against [previous] filled in.
     * Without a value before, nothing changes.
     */
    fun withChangeFrom(previous: Glucose?): Glucose {
        if (previous == null) {
            return this
        }

        return Glucose(
            timestamp = timestamp,
            value = value,
            minuteChange = minuteChange(previous.timestamp, previous.value, timestamp, value),
            fromTrend = fromTrend,
            sensorSerial = sensorSerial
        )
    }

    companion object {
        const val MIN_READABLE_GLUCOSE = 39
        const val MAX_READABLE_GLUCOSE = 501

        fun limitToReadable(value: Int): Int {
            return when {
                value < MIN_READABLE_GLUCOSE -> MIN_READABLE_GLUCOSE
                value > MAX_READABLE_GLUCOSE -> MAX_READABLE_GLUCOSE
                else -> value
            }
        }

        /**
         * A number with decimals as a whole number, or null if it is not a real number or far
         * too big. A value exactly in the middle goes away from zero.
         */
        fun toInteger(value: Double): Long? {
            return if (value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble()) {
                roundHalfAwayFromZero(value).toLong()
            } else {
                null
            }
        }

        /**
         * Change per minute between two values, as the original app counts it. Both values are
         * first limited to 39..501. Both times must be the start of a minute.
         */
        private fun minuteChange(previousTimestamp: Long, previousValue: Int, nextTimestamp: Long, nextValue: Int): Double {
            if (previousTimestamp == nextTimestamp) {
                return 0.0
            }

            val glucoseDiff = limitToReadable(nextValue).toDouble() - limitToReadable(previousValue).toDouble()
            val minutesDiff = ((nextTimestamp - previousTimestamp) / 1000.0) / 60

            return glucoseDiff / minutesDiff
        }
    }
}
