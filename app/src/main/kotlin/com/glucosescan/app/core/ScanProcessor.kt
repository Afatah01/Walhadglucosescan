package com.glucosescan.app.core

import com.glucosescan.app.libre.Crc16
import com.glucosescan.app.libre.FramParser
import com.glucosescan.app.libre.Libre2Crypto
import com.glucosescan.app.libre.SensorInfo
import com.glucosescan.app.libre.SensorState
import com.glucosescan.app.libre.SensorType

/** What was read from the sensor over NFC, before any decoding. */
class RawScan(
    /** 8 bytes, in the order they are sent over NFC (the last byte is 0xE0). */
    val uid: ByteArray,
    /** The answer to the "patch info" command, without the status byte. */
    val patchInfo: ByteArray,
    /** The 344 bytes of the sensor memory as read (still scrambled). */
    val fram: ByteArray,
    /** Milliseconds since 1970. */
    val scanTime: Long
)

enum class ScanStatus {
    /** The sensor is running. The result holds the measurements. */
    OK,

    /** A new sensor that was not started yet. */
    NOT_STARTED,

    /** Started less than 60 minutes ago. No values yet. */
    STARTING,

    /** The sensor has reached the end of its time. */
    EXPIRED,

    /** The sensor has switched itself off. */
    SHUT_DOWN,

    /** The sensor says it is broken. */
    FAILURE,

    /** The sensor is in a state this app does not know. */
    UNKNOWN_STATE,

    /** Not a Libre 2 of the European type. */
    UNSUPPORTED_SENSOR,

    /** The data makes no sense. Nothing from it is used. */
    BAD_DATA
}

class ScanResult(
    val status: ScanStatus,
    val scanTime: Long,
    val sensorType: SensorType?,
    /** Null if the scan could not be decoded. */
    val sensor: SensorInfo?,
    /** Only for [ScanStatus.OK]. */
    val readings: Timeline.Result?,
    /** True if a checksum was wrong. Reading the sensor again can help. */
    val checksumFailed: Boolean,
    /** Short technical words about what happened (English). */
    val detail: String,
    /** The decoded sensor memory, if decoding worked. */
    val plainFram: ByteArray?
)

/**
 * Turns the bytes of one scan into a result.
 *
 * The decoding is the ported code of the original app. Around it, this app is more careful
 * than the original (see README.md, "Differences"):
 *
 *  - Only Libre 2 of the European type is accepted.
 *  - All three checksums of the sensor memory must be right. The original does not check them.
 *  - Values are only used while the sensor itself says "ready". The original also uses them
 *    when the sensor says it has expired, has shut down or has failed.
 */
object ScanProcessor {
    const val FRAM_SIZE = 344

    fun process(scan: RawScan): ScanResult {
        fun bad(detail: String, type: SensorType? = null, checksum: Boolean = false, plain: ByteArray? = null): ScanResult {
            return ScanResult(ScanStatus.BAD_DATA, scan.scanTime, type, null, null, checksum, detail, plain)
        }

        if (scan.patchInfo.size < 6) {
            return bad("patch info has ${scan.patchInfo.size} bytes, 6 are needed")
        }

        val type = SensorType.of(scan.patchInfo)
        if (type != SensorType.LIBRE2_EU) {
            return ScanResult(ScanStatus.UNSUPPORTED_SENSOR, scan.scanTime, type, null, null, false, "sensor type: ${type.label}", null)
        }

        if (scan.patchInfo.size != 6) {
            return bad("patch info has ${scan.patchInfo.size} bytes, 6 are expected for Libre 2", type)
        }
        if (scan.uid.size != 8) {
            return bad("sensor id has ${scan.uid.size} bytes, 8 are needed", type)
        }
        if (scan.fram.size != FRAM_SIZE) {
            return bad("sensor memory has ${scan.fram.size} bytes, $FRAM_SIZE are needed", type)
        }

        val fram = Libre2Crypto.decryptFram(scan.uid, scan.patchInfo, scan.fram)
            ?: return bad("sensor memory could not be decoded", type)

        val headerOk = Crc16.sectionIsValid(fram, 0, 24)
        val bodyOk = Crc16.sectionIsValid(fram, 24, 320)
        val footerOk = Crc16.sectionIsValid(fram, 320, 344)
        if (!headerOk || !bodyOk || !footerOk) {
            return bad("checksum wrong (header ${ok(headerOk)}, body ${ok(bodyOk)}, footer ${ok(footerOk)})", type, checksum = true, plain = fram)
        }

        val sensor = SensorInfo.libreStyleSensor(scan.uid, scan.patchInfo, fram)
        if (!sensor.factoryCalibration.isUsable) {
            return bad("factory calibration is not usable: ${sensor.factoryCalibration}", type, plain = fram)
        }

        fun result(status: ScanStatus, detail: String, readings: Timeline.Result? = null): ScanResult {
            return ScanResult(status, scan.scanTime, type, sensor, readings, false, detail, fram)
        }

        return when (sensor.state) {
            SensorState.NOT_YET_STARTED -> result(ScanStatus.NOT_STARTED, "sensor state 1")
            SensorState.STARTING -> result(ScanStatus.STARTING, "sensor state 2")
            SensorState.EXPIRED -> result(ScanStatus.EXPIRED, "sensor state 4")
            SensorState.SHUTDOWN -> result(ScanStatus.SHUT_DOWN, "sensor state 5")
            SensorState.FAILURE -> result(ScanStatus.FAILURE, "sensor state 6")
            SensorState.UNKNOWN -> result(ScanStatus.UNKNOWN_STATE, "sensor state ${fram[4].toInt() and 0xFF}")
            SensorState.READY -> when {
                sensor.age >= sensor.lifetime -> result(ScanStatus.EXPIRED, "age ${sensor.age} >= lifetime ${sensor.lifetime}")
                sensor.age <= sensor.warmupTime -> result(ScanStatus.STARTING, "age ${sensor.age} <= warm-up ${sensor.warmupTime}")
                else -> {
                    val records = FramParser.records(fram, sensor.factoryCalibration)
                    val readings = Timeline.readings(records, scan.scanTime)
                    result(ScanStatus.OK, if (readings.historyTimeKnown) "ok" else "ok, 15-minute values skipped (index ${records.historyIndex} does not fit age ${records.age})", readings)
                }
            }
        }
    }

    private fun ok(value: Boolean): String = if (value) "ok" else "WRONG"
}
