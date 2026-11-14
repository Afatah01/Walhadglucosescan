package com.glucosescan.app.core

import com.glucosescan.app.libre.FactoryCalibration
import com.glucosescan.app.libre.Libre2Ble
import com.glucosescan.app.libre.SensorInfo

/** What the app must know about a sensor to read its Bluetooth messages. It is saved when the sensor is set up by a scan. */
class LiveSensor(
    val serial: String?,
    /** 8 bytes, in the order they are sent over NFC (the last byte is 0xE0). */
    val uid: ByteArray,
    /** The 6 bytes of the "patch info". */
    val patchInfo: ByteArray,
    val calibration: FactoryCalibration,
    /** The sensor counts as ended when its age reaches this (minutes). */
    val lifetime: Int
)

enum class LiveStatus {
    /** The sensor is running. The result holds the measurements. */
    OK,

    /** Started less than 60 minutes ago. No values yet. */
    STARTING,

    /** The sensor has reached the end of its time. */
    EXPIRED,

    /** The message is damaged, or it is not from this sensor. Nothing from it is used. */
    BAD_DATA
}

class LiveResult(
    val status: LiveStatus,
    /** Minutes since the sensor was started, or -1 if the message could not be read. */
    val age: Int,
    /** One-minute values, oldest first. Only for [LiveStatus.OK]. */
    val readings: List<SensorReading>,
    /** Short technical words about what happened (English). */
    val detail: String
)

/**
 * Turns one Bluetooth message of the sensor into measurements with their times.
 *
 * The decoding is the ported code of the original app (package "libre"). Around it, this app
 * is more careful than the original, in the same way as for a scan (see [Timeline]):
 *
 *  1. A measurement with raw glucose 0 is always treated as faulty.
 *  2. Measurements from the first 60 minutes of the sensor are not used.
 *  3. The three 15-minute values of a message are not used at all. Their time is known less
 *     exactly than the time of the same values in a scan. A scan fills such gaps.
 */
object LiveProcessor {
    /**
     * @param message the 46 bytes as they came from the sensor
     * @param now when the last part of the message arrived, in milliseconds since 1970
     */
    fun process(sensor: LiveSensor, message: ByteArray, now: Long): LiveResult {
        if (!sensor.calibration.isUsable) {
            return LiveResult(LiveStatus.BAD_DATA, -1, emptyList(), "factory calibration is not usable: ${sensor.calibration}")
        }
        val plain = Libre2Ble.decrypt(sensor.uid, message)
            ?: return LiveResult(LiveStatus.BAD_DATA, -1, emptyList(), "message of ${message.size} bytes could not be decoded (size or checksum)")

        val records = Libre2Ble.parse(sensor.calibration, plain)
        val age = records.age
        if (age >= sensor.lifetime) {
            return LiveResult(LiveStatus.EXPIRED, age, emptyList(), "age $age >= lifetime ${sensor.lifetime}")
        }
        if (age <= SensorInfo.WARMUP_MINUTES) {
            return LiveResult(LiveStatus.STARTING, age, emptyList(), "age $age <= warm-up ${SensorInfo.WARMUP_MINUTES}")
        }

        val minute = Timeline.floorToMinute(now)
        val firstUsableMinute = SensorInfo.WARMUP_MINUTES + 1
        val readings = ArrayList<SensorReading>(records.trend.size)
        for (record in records.trend) { // oldest first
            if (age - record.minutesBefore < firstUsableMinute) {
                continue
            }
            val errorBits = when {
                record.rawGlucose != 0 -> 0
                record.errorBits != 0 -> record.errorBits
                else -> Timeline.ERROR_INVALID_DATA
            }
            readings.add(SensorReading(minute - record.minutesBefore * Timeline.MINUTE, record.glucose, errorBits, fromTrend = true))
        }
        return LiveResult(LiveStatus.OK, age, readings, "ok")
    }
}
