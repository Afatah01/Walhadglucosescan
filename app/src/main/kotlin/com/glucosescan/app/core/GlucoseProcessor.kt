// Ported from Glucose Direct (MIT License, Copyright (c) 2023 Reimar Metzen):
// App/Modules/SensorConnector/SensorConnector.swift, case .addSensorReadings,
// and the stdev helper in Library/Content/SensorGlucose.swift.

package com.glucosescan.app.core

/** Decides which measurements of a scan become new glucose values of the app. */
object GlucoseProcessor {
    /** If the newest 5 values differ more than this from each other, the scan is not used. */
    const val MAX_STDEV: Double = 100.0

    /** A value must be this much newer than the newest known value: 1 minute minus 15 seconds. */
    private const val INTERVAL_MILLIS: Long = (1 * 60 - 15) * 1000L

    class Result(
        /** New values to keep, oldest first. Empty if there is nothing new. */
        val newValues: List<Glucose>,
        /** True if the scan was thrown away because the values jump too much. */
        val tooNoisy: Boolean,
        /** How many good measurements the scan had. */
        val goodReadings: Int,
        /** How many faulty measurements the scan had. */
        val faultyReadings: Int
    )

    /**
     * @param readings all measurements of the scan, oldest first
     * @param latest the newest value the app already has, or null
     */
    fun process(readings: List<SensorReading>, latest: Glucose?, sensorSerial: String?): Result {
        // only valid values
        val readGlucoseValues = ArrayList<Glucose>()
        var faulty = 0
        for (reading in readings) {
            val whole = if (reading.isOk) Glucose.toInteger(reading.glucoseValue) else null
            if (whole == null) {
                faulty++
                continue
            }
            val value = whole.coerceIn(-9_999L, 9_999L).toInt() // far outside 39..501 anyway
            readGlucoseValues.add(Glucose(reading.timestamp, value, null, reading.fromTrend, sensorSerial))
        }

        // calc stdev of last 5 values
        val stdev = if (readGlucoseValues.size >= 5) stdev(readGlucoseValues.takeLast(5)) else 0.0

        // filter unwanted values, then count the change per minute
        var previousGlucose = latest
        val glucoseValues = ArrayList<Glucose>()
        for (glucose in readGlucoseValues) {
            if (!(latest == null || latest.timestamp + INTERVAL_MILLIS < glucose.timestamp)) {
                continue
            }

            val withChange = glucose.withChangeFrom(previousGlucose)
            previousGlucose = withChange
            glucoseValues.add(withChange)
        }

        // stdev is over 100: nothing is kept
        val tooNoisy = !(stdev < MAX_STDEV)
        return Result(
            newValues = if (tooNoisy) emptyList() else glucoseValues,
            tooNoisy = tooNoisy,
            goodReadings = readGlucoseValues.size,
            faultyReadings = faulty
        )
    }

    /** How much the values differ from each other (sample standard deviation of the limited values). */
    internal fun stdev(values: List<Glucose>): Double {
        val glucoseValues = values.map { it.readableValue.toDouble() }

        val length = glucoseValues.size.toDouble()
        val avg = glucoseValues.fold(0.0) { a, b -> a + b } / length
        val sumOfSquaredAvgDiff = glucoseValues.map { StrictMath.pow(it - avg, 2.0) }.fold(0.0) { a, b -> a + b }

        return Math.sqrt(sumOfSquaredAvgDiff / (length - 1))
    }
}
