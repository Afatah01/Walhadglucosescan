// The reading of the single records is ported from Glucose Direct (MIT License,
// Copyright (c) 2023 Reimar Metzen): App/Modules/SensorConnector/SensorUtility.swift,
// LibreUtility.parseFRAM.

package com.glucosescan.app.libre

/** One stored measurement of the sensor. */
class FramRecord(
    val rawGlucose: Int,
    val rawTemperature: Int,
    val rawTemperatureAdjustment: Int,
    /**
     * 0 means no error. The original app only sees an error when the raw glucose number is 0.
     * Then this holds the error bits that the sensor stored in place of the temperature.
     */
    val errorBits: Int,
    /** Glucose in mg/dL from the factory calibration. Can be "not a number" or endless. */
    val glucose: Double
)

/** All measurements in the sensor memory. Index 0 is always the newest one. */
class FramRecords(
    /** Minutes since the sensor was started. */
    val age: Int,
    /** 16 values, one per minute. */
    val trend: List<FramRecord>,
    /** 32 values, one per 15 minutes. */
    val history: List<FramRecord>,
    /** Where the sensor will write its next 15-minute value (0..31), as stored in the memory. */
    val historyIndex: Int,
    /** Where the sensor will write its next one-minute value (0..15), as stored in the memory. */
    val trendIndex: Int
)

object FramParser {
    const val TREND_COUNT = 16
    const val HISTORY_COUNT = 32

    /**
     * @param fram the plain 344 bytes of the sensor memory
     * @throws IllegalStateException if the calibration is not usable
     */
    fun records(fram: ByteArray, calibration: FactoryCalibration): FramRecords {
        require(fram.size >= 344) { "sensor memory is too short" }

        val age = (fram[316].toInt() and 0xFF) + ((fram[317].toInt() and 0xFF) shl 8)

        val trend = ArrayList<FramRecord>(TREND_COUNT)
        val trendIndex = fram[26].toInt() and 0xFF // body[2]
        for (i in 0..15) {
            var j = trendIndex - 1 - i
            if (j < 0) {
                j += 16
            }

            trend.add(record(fram, 28 + j * 6, calibration)) // body[4 ..< 100]
        }

        val history = ArrayList<FramRecord>(HISTORY_COUNT)
        val historyIndex = fram[27].toInt() and 0xFF // body[3]
        for (i in 0..31) {
            var j = historyIndex - 1 - i
            if (j < 0) {
                j += 32
            }

            history.add(record(fram, 124 + j * 6, calibration)) // body[100 ..< 292]
        }

        return FramRecords(age, trend, history, historyIndex, trendIndex)
    }

    private fun record(fram: ByteArray, offset: Int, calibration: FactoryCalibration): FramRecord {
        val rawGlucoseValue = readBits(fram, offset, 0, 0xe)
        val rawTemperature = readBits(fram, offset, 0x1a, 0xc) shl 2
        val errorBits = if (rawGlucoseValue == 0) rawTemperature shr 2 else 0

        var rawTemperatureAdjustment = readBits(fram, offset, 0x26, 0x9) shl 2
        if (readBits(fram, offset, 0x2f, 0x1) != 0) {
            rawTemperatureAdjustment = -rawTemperatureAdjustment
        }

        val glucoseValue = calibration.calibrate(
            rawValue = rawGlucoseValue.toDouble(),
            rawTemperature = rawTemperature.toDouble(),
            rawTemperatureAdjustment = rawTemperatureAdjustment.toDouble()
        )

        return FramRecord(rawGlucoseValue, rawTemperature, rawTemperatureAdjustment, errorBits, glucoseValue)
    }
}
