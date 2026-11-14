// The idea of this file, the straight line through the pairs and the limits of its slope are
// ported from "Custom calibration" in Glucose Direct (MIT License, Copyright (c) 2023
// Reimar Metzen): Library/Content/Calibrations/CustomCalibration.swift.
//
// Everything else here is stricter than the original and is this app's own: when a pair is
// accepted, how far a value may be moved, and what happens below and above the pairs.

package com.glucosescan.app.core

import com.glucosescan.app.libre.roundHalfAwayFromZero
import java.util.Locale

/** Why a pair cannot be saved, or [OK]. */
enum class PairCheck {
    OK,

    /** No value is saved yet. */
    NO_VALUE,

    /** The newest value is more than 5 minutes old, or the phone clock was changed. */
    OLD_VALUE,

    /** The value does not say which sensor made it. */
    NO_SENSOR,

    /** The sensor value is LOW or HIGH. */
    NOT_MEASURABLE,

    /** The app does not know how fast the glucose changes. */
    CHANGE_UNKNOWN,

    /** The glucose changes by more than 1 mg/dL per minute. */
    NOT_STEADY,

    /** The typed value is not a value that the app shows as a number. */
    OUT_OF_RANGE,

    /** The typed value is too far from the sensor value. */
    TOO_DIFFERENT
}

/** When a pair is accepted, and how far a value may be moved. All numbers are mg/dL. */
object CorrectionRules {
    /** The sensor value and the typed value must be numbers, not LOW and not HIGH. */
    const val MIN_VALUE = Glucose.MIN_READABLE_GLUCOSE
    const val MAX_VALUE = Glucose.MAX_READABLE_GLUCOSE

    /** "Steady": the arrow shows →. */
    const val MAX_CHANGE_PER_MINUTE = 1.0

    /** No value is moved by more than this ... */
    const val MAX_SHIFT = 30.0

    /** ... or by more than this share of the sensor value, whichever is more. */
    const val MAX_SHIFT_SHARE = 0.25

    /** How far a sensor value of [sensorValue] may be moved at most, up or down. */
    fun maxShift(sensorValue: Double): Double = Math.max(MAX_SHIFT, MAX_SHIFT_SHARE * sensorValue)

    /**
     * Can the sensor value of this moment be used for a pair? It must be current, a number,
     * and steady: while the glucose changes fast, a meter and the sensor do not show the same.
     *
     * @param latest the newest sensor value (not corrected), or null
     * @param isCurrent true if [latest] is at most 5 minutes old
     * @param changePerMinute how fast the sensor values change, or null if not known
     */
    fun checkSensorSide(latest: Glucose?, isCurrent: Boolean, changePerMinute: Double?): PairCheck {
        return when {
            latest == null -> PairCheck.NO_VALUE
            !isCurrent -> PairCheck.OLD_VALUE
            latest.sensorSerial.isNullOrEmpty() -> PairCheck.NO_SENSOR
            latest.value < MIN_VALUE || latest.value > MAX_VALUE -> PairCheck.NOT_MEASURABLE
            changePerMinute == null || changePerMinute.isNaN() -> PairCheck.CHANGE_UNKNOWN
            Math.abs(changePerMinute) > MAX_CHANGE_PER_MINUTE -> PairCheck.NOT_STEADY
            else -> PairCheck.OK
        }
    }

    /** Can [referenceValue], the value the user typed, be the right value for [sensorValue]? */
    fun checkReference(sensorValue: Int, referenceValue: Int): PairCheck {
        return when {
            referenceValue < MIN_VALUE || referenceValue > MAX_VALUE -> PairCheck.OUT_OF_RANGE
            Math.abs(referenceValue - sensorValue) > maxShift(sensorValue.toDouble()) -> PairCheck.TOO_DIFFERENT
            else -> PairCheck.OK
        }
    }
}

/**
 * One pair of numbers in mg/dL: what the sensor formula gave, and the right value that the
 * user typed for the same minute (from a finger-prick meter or from LibreLink).
 */
class ReferencePair(
    /** Time of the sensor value, milliseconds since 1970. */
    val time: Long,
    val sensorValue: Int,
    val referenceValue: Int
) {
    /** True if the rules accept this pair. */
    val isUsable: Boolean
        get() = sensorValue >= CorrectionRules.MIN_VALUE && sensorValue <= CorrectionRules.MAX_VALUE &&
            CorrectionRules.checkReference(sensorValue, referenceValue) == PairCheck.OK
}

/**
 * Corrects the values of one sensor with the pairs that the user saved. The saved glucose
 * values are never changed: the correction is put on top when a value is shown, and it is
 * gone when the pairs are deleted.
 *
 * How a sensor value is moved:
 * - Pairs that lie close together (or one pair alone) give one number: the mean difference
 *   between right value and sensor value.
 * - Pairs that lie at least [MIN_SPREAD] apart give a straight line, as in the original app:
 *   least squares, with a slope of at least [MIN_SLOPE] and at most [MAX_SLOPE].
 * - Above the highest pair the difference stays as it is there. It does not grow.
 * - Below the lowest pair the difference gets smaller, in step with the value. So a pair
 *   saved at a high value cannot lift a low value by much.
 * - No value is moved by more than [CorrectionRules.maxShift].
 *
 * A higher sensor value never gives a lower corrected value.
 */
class Correction(sensorSerial: String?, pairs: List<ReferencePair>) {
    /** The pairs that are used, oldest first. Pairs that the rules do not accept are left out. */
    val pairs: List<ReferencePair> =
        if (sensorSerial.isNullOrEmpty()) emptyList() else pairs.filter { it.isUsable }.sortedBy { it.time }.takeLast(MAX_PAIRS)

    /** The sensor that the pairs belong to. Null if there is no correction. */
    val sensorSerial: String? = if (this.pairs.isEmpty()) null else sensorSerial

    /** True if values are changed at all. */
    val isActive: Boolean
        get() = pairs.isNotEmpty()

    /** True if the pairs lie far enough apart for a line. False: one difference for all values. */
    val hasLine: Boolean

    /** right value = [slope] x sensor value + [intercept], between the lowest and the highest pair. */
    val slope: Double
    val intercept: Double

    private val lowAnchor: Double
    private val highAnchor: Double
    private val lowShift: Double
    private val highShift: Double

    init {
        if (this.pairs.isEmpty()) {
            hasLine = false
            slope = 1.0
            intercept = 0.0
            lowAnchor = 1.0
            highAnchor = 1.0
            lowShift = 0.0
            highShift = 0.0
        } else {
            val xs = this.pairs.map { it.sensorValue.toDouble() }
            val ys = this.pairs.map { it.referenceValue.toDouble() }
            val xMean = xs.average()
            val yMean = ys.average()
            val xMin = xs.min()
            val xMax = xs.max()

            if (xMax - xMin >= MIN_SPREAD) {
                // the line of the original app
                val sum1 = xs.indices.map { xs[it] * ys[it] }.average() - xMean * yMean
                val sum2 = xs.map { it * it }.average() - xMean * xMean
                val fitted = if (sum2 > 0.0) sum1 / sum2 else 1.0
                hasLine = true
                slope = Math.min(Math.max(fitted, MIN_SLOPE), MAX_SLOPE)
                intercept = yMean - slope * xMean
                lowAnchor = xMin
                highAnchor = xMax
            } else {
                hasLine = false
                slope = 1.0
                intercept = yMean - xMean
                lowAnchor = xMean
                highAnchor = xMean
            }
            lowShift = limit(lowAnchor, (slope - 1.0) * lowAnchor + intercept)
            highShift = limit(highAnchor, (slope - 1.0) * highAnchor + intercept)
        }
    }

    /** True if [glucose] is from the sensor that this correction is for. */
    fun appliesTo(glucose: Glucose): Boolean = isActive && glucose.sensorSerial == sensorSerial

    /** The corrected value for a sensor value of the sensor of this correction, in mg/dL. */
    fun valueFor(sensorValue: Int): Int {
        if (!isActive) {
            return sensorValue
        }
        val x = sensorValue.toDouble()
        val shift = when {
            x <= 0.0 -> 0.0
            x <= lowAnchor -> lowShift * x / lowAnchor
            x >= highAnchor -> highShift
            else -> (slope - 1.0) * x + intercept
        }
        return roundHalfAwayFromZero(x + limit(x, shift)).toInt()
    }

    /**
     * [glucose] with its corrected value, or [glucose] itself if it is from another sensor.
     * The change per minute of a corrected value is not known: the screens count it anew.
     */
    fun apply(glucose: Glucose): Glucose {
        if (!appliesTo(glucose)) {
            return glucose
        }
        return Glucose(glucose.timestamp, valueFor(glucose.value), null, glucose.fromTrend, glucose.sensorSerial)
    }

    fun applyAll(values: List<Glucose>): List<Glucose> = if (isActive) values.map { apply(it) } else values

    /**
     * This correction with one more pair for the sensor [serial]. A pair of the same minute is
     * replaced. Pairs of another sensor are dropped: a new sensor starts anew.
     */
    fun adding(serial: String, pair: ReferencePair): Correction {
        val kept = if (serial == sensorSerial) pairs.filter { it.time != pair.time } else emptyList()
        return Correction(serial, kept + pair)
    }

    /** This correction without the pair of the time [time]. */
    fun removing(time: Long): Correction = Correction(sensorSerial, pairs.filter { it.time != time })

    /** The pairs as text, to keep them in the settings. */
    fun encode(): String = pairs.joinToString(";") { "${it.time},${it.sensorValue},${it.referenceValue}" }

    /** For the technical details. */
    fun describe(): String {
        if (!isActive) {
            return "value correction: none"
        }
        val text = StringBuilder("value correction: sensor ").append(sensorSerial)
        text.append(if (hasLine) ", line" else ", shift")
        text.append(String.format(Locale.US, ", slope %.3f, intercept %.1f", slope, intercept))
        text.append("\npairs (time, sensor value, right value): ")
        text.append(pairs.joinToString("; ") { "${it.time}, ${it.sensorValue}, ${it.referenceValue}" })
        return text.toString()
    }

    companion object {
        /** The limits of the slope, as in the original app. */
        const val MIN_SLOPE = 0.8
        const val MAX_SLOPE = 1.25

        /** A line is used when the lowest and the highest sensor value of the pairs are this far apart (mg/dL). */
        const val MIN_SPREAD = 40

        /** Only the newest pairs are kept. */
        const val MAX_PAIRS = 12

        /** No correction. */
        val NONE = Correction(null, emptyList())

        /** The opposite of [encode]. Text that cannot be read gives no pair. */
        fun decode(sensorSerial: String?, text: String?): Correction {
            if (sensorSerial.isNullOrEmpty() || text.isNullOrEmpty()) {
                return NONE
            }
            val pairs = ArrayList<ReferencePair>()
            for (part in text.split(';')) {
                val numbers = part.split(',')
                if (numbers.size != 3) {
                    continue
                }
                val time = numbers[0].trim().toLongOrNull() ?: continue
                val sensorValue = numbers[1].trim().toIntOrNull() ?: continue
                val referenceValue = numbers[2].trim().toIntOrNull() ?: continue
                pairs.add(ReferencePair(time, sensorValue, referenceValue))
            }
            return Correction(sensorSerial, pairs)
        }

        /** [shift] cut to what is allowed for a sensor value of [sensorValue]. */
        private fun limit(sensorValue: Double, shift: Double): Double {
            val most = CorrectionRules.maxShift(sensorValue)
            return Math.min(Math.max(shift, -most), most)
        }
    }
}
