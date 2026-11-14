package com.glucosescan.app.core

import com.glucosescan.app.libre.FramParser
import com.glucosescan.app.libre.FramRecord
import com.glucosescan.app.libre.FramRecords
import com.glucosescan.app.libre.SensorInfo

/** One measurement with the time it was made. */
class SensorReading(
    /** Milliseconds since 1970, always the start of a minute. */
    val timestamp: Long,
    /** mg/dL. Only meaningful if [errorBits] is 0. */
    val glucoseValue: Double,
    /** 0 means the measurement is good. */
    val errorBits: Int,
    /** True for the one-minute values, false for the 15-minute values. */
    val fromTrend: Boolean
) {
    val isOk: Boolean
        get() = errorBits == 0
}

/**
 * Gives every measurement in the sensor memory its time.
 *
 * The glucose numbers themselves come unchanged from the ported code in the package "libre".
 * The TIME rules here are stricter than in the original app (see README.md, "Differences"):
 *
 *  1. A measurement with raw glucose 0 is always treated as faulty.
 *  2. Measurements from the first 60 minutes of the sensor are not used.
 *  3. A 15-minute value gets the time at which the sensor made it. The sensor stores it some
 *     minutes later. The original app puts value number i at "now minus i x 15 minutes".
 *  4. If the memory does not allow to tell that time for sure, the 15-minute values of this
 *     scan are not used.
 */
object Timeline {
    const val MINUTE: Long = 60_000L

    /** Error bit this app sets when the raw glucose is 0 but the sensor stored no error bits. */
    const val ERROR_INVALID_DATA: Int = 1 shl 15

    class Result(
        /** 15-minute values, oldest first. All are older than the oldest one-minute value. */
        val history: List<SensorReading>,
        /** One-minute values, oldest first. */
        val trend: List<SensorReading>,
        /** False if rule 4 removed the 15-minute values. */
        val historyTimeKnown: Boolean
    ) {
        /** Everything, oldest first. */
        val all: List<SensorReading>
            get() = history + trend
    }

    fun floorToMinute(time: Long): Long = time - Math.floorMod(time, MINUTE)

    /**
     * The sensor minute at which the newest 15-minute value was made, or -1 if that cannot be
     * told for sure.
     *
     * The sensor makes one such value every 15 minutes and stores it about 3 minutes later.
     * So at sensor minute [age] it should have stored (age - 3) / 15 values, and value number n
     * belongs to minute n x 15. [historyIndex] is the number of stored values, counted in a ring
     * of 32. It must fit to that count. One value more or less is accepted only close to the
     * minute at which the sensor stores a value (it can be a little early or late then).
     */
    fun newestHistoryMinute(age: Int, historyIndex: Int): Int {
        if (age < 3 || historyIndex < 0 || historyIndex >= FramParser.HISTORY_COUNT) {
            return -1
        }

        val expected = (age - 3) / 15
        val minutesSinceStoring = (age - 3) % 15

        if (expected >= 1 && expected % FramParser.HISTORY_COUNT == historyIndex) {
            return expected * 15
        }

        // a little early: the next value is stored already, and it is not from the future
        val early = expected + 1
        if (early % FramParser.HISTORY_COUNT == historyIndex && early * 15 <= age) {
            return early * 15
        }

        // a little late: the value that is due in this minute is not stored yet
        val late = expected - 1
        if (late >= 1 && late % FramParser.HISTORY_COUNT == historyIndex && minutesSinceStoring <= 1) {
            return late * 15
        }

        return -1
    }

    /**
     * @param scanTime when the sensor was read, in milliseconds since 1970
     */
    fun readings(records: FramRecords, scanTime: Long): Result {
        val now = floorToMinute(scanTime)
        val age = records.age
        val firstUsableMinute = SensorInfo.WARMUP_MINUTES + 1

        // One-minute values: number i was made i minutes ago.
        val trend = ArrayList<SensorReading>(FramParser.TREND_COUNT)
        for (i in FramParser.TREND_COUNT - 1 downTo 0) {
            val sensorMinute = age - i
            if (sensorMinute < firstUsableMinute) {
                continue
            }
            trend.add(reading(records.trend[i], now - i * MINUTE, fromTrend = true))
        }

        // 15-minute values.
        val oldestTrendTime = now - (FramParser.TREND_COUNT - 1) * MINUTE
        val newestMinute = newestHistoryMinute(age, records.historyIndex)
        val history = ArrayList<SensorReading>(FramParser.HISTORY_COUNT)
        if (newestMinute >= 0) {
            for (i in FramParser.HISTORY_COUNT - 1 downTo 0) {
                val sensorMinute = newestMinute - i * 15
                if (sensorMinute < firstUsableMinute) {
                    continue
                }
                val timestamp = now - (age - sensorMinute) * MINUTE
                if (timestamp >= oldestTrendTime) {
                    continue
                }
                history.add(reading(records.history[i], timestamp, fromTrend = false))
            }
        }

        return Result(history, trend, historyTimeKnown = newestMinute >= 0)
    }

    private fun reading(record: FramRecord, timestamp: Long, fromTrend: Boolean): SensorReading {
        val errorBits = when {
            record.rawGlucose != 0 -> 0
            record.errorBits != 0 -> record.errorBits
            else -> ERROR_INVALID_DATA
        }
        return SensorReading(timestamp, record.glucose, errorBits, fromTrend)
    }
}
