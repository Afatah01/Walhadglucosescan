// Ported from Glucose Direct (MIT License, Copyright (c) 2023 Reimar Metzen):
// Library/Content/Calibrations/FactoryCalibration.swift.

package com.glucosescan.app.libre

/**
 * The numbers that the factory stored in the sensor. They turn a raw measurement into a
 * glucose value in mg/dL.
 */
class FactoryCalibration(
    val i1: Int,
    val i2: Int,
    val i3: Double,
    val i4: Double,
    val i5: Double,
    val i6: Double
) {
    /** False if the sensor memory holds a table row that does not exist. Then no value can be made. */
    val isUsable: Boolean
        get() = i2 >= 1 && i2 <= CALIBRATION_T1.size

    /**
     * @return the glucose value in mg/dL. It can be "not a number" or endless if the raw numbers
     * make no sense. The caller must check that.
     * @throws IllegalStateException if [isUsable] is false
     */
    fun calibrate(rawValue: Double, rawTemperature: Double, rawTemperatureAdjustment: Double): Double {
        check(isUsable) { "calibration row $i2 does not exist" }

        val x: Double = 1000.0 + 71500.0
        val y: Double = 1000.0
        val ca = 0.0009180023
        val cb = 0.0001964561
        val cc = 0.0000007061775
        val cd = 0.00000005283566
        val rLeft = rawTemperature * x
        val rRight = rawTemperatureAdjustment + i6

        val r = (rLeft / rRight) - y
        val logR = StrictMath.log(r)
        val d = StrictMath.pow(logR, 3.0) * cd + StrictMath.pow(logR, 2.0) * cc + logR * cb + ca

        val temperature = 1 / d - 273.15

        val g1 = 65.0 * (rawValue - i3) / (i4 - i3)
        val g2 = StrictMath.pow(1.045, 32.5 - temperature)

        val g3 = g1 * g2

        val v1 = CALIBRATION_T1[i2 - 1]
        val v2 = CALIBRATION_T2[i2 - 1]

        return roundHalfAwayFromZero((g3 - v1) / v2)
    }

    override fun toString(): String = "{ i1: $i1, i2: $i2, i3: $i3, i4: $i4, i5: $i5, i6: $i6 }"

    companion object {
        /** Reads the factory numbers from the plain 344 bytes of a Libre 1 or Libre 2 memory. */
        fun libreStyleCalibration(fram: ByteArray): FactoryCalibration {
            val i1 = readBits(fram, 2, 0, 3)
            val i2 = readBits(fram, 2, 3, 0xa)

            var i3 = readBits(fram, 0x150, 0, 8).toDouble()
            if (readBits(fram, 0x150, 0x21, 1) != 0) {
                i3 = -i3
            }

            val i4 = readBits(fram, 0x150, 8, 0xe).toDouble()
            val i5 = (readBits(fram, 0x150, 0x28, 0xc) shl 2).toDouble()
            val i6 = (readBits(fram, 0x150, 0x34, 0xc) shl 2).toDouble()

            return FactoryCalibration(i1, i2, i3, i4, i5, i6)
        }
    }
}

/**
 * Rounds to the nearest whole number. A value exactly in the middle goes away from zero
 * (2.5 gives 3, -2.5 gives -3). This is how the original app rounds.
 */
internal fun roundHalfAwayFromZero(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) {
        return value
    }
    val whole = if (value < 0) Math.ceil(value) else Math.floor(value) // toward zero
    val rest = Math.abs(value - whole)
    return if (rest >= 0.5) whole + Math.signum(value) else whole
}
