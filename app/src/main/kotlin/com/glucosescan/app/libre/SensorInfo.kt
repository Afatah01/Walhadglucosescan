// Ported from Glucose Direct (MIT License, Copyright (c) 2023 Reimar Metzen):
// Library/Content/Sensor.swift, Sensor.libreStyleSensor and sensorSerialNumber.

package com.glucosescan.app.libre

/** What one scan tells about the sensor itself. All times are in minutes. */
class SensorInfo(
    val family: SensorFamily,
    val type: SensorType,
    val region: SensorRegion,
    /** The serial number that is printed on the sensor box, or null. */
    val serial: String?,
    val state: SensorState,
    /** Minutes since the sensor was started. */
    val age: Int,
    /** The sensor is treated as ended when [age] reaches this. One hour before the sensor's own limit. */
    val lifetime: Int,
    val factoryCalibration: FactoryCalibration
) {
    /** A new sensor gives no values in its first 60 minutes. */
    val warmupTime: Int = WARMUP_MINUTES

    val remainingWarmupTime: Int?
        get() = if (age <= warmupTime) warmupTime - age else null

    val remainingLifetime: Int
        get() = Math.max(0, lifetime - age)

    val elapsedLifetime: Int
        get() = Math.max(0, lifetime - remainingLifetime)

    companion object {
        const val WARMUP_MINUTES = 60

        /**
         * @param uid 8 bytes, in the order they are sent over NFC
         * @param patchInfo 6 bytes
         * @param fram the plain 344 bytes of the sensor memory
         */
        fun libreStyleSensor(uid: ByteArray, patchInfo: ByteArray, fram: ByteArray): SensorInfo {
            val family = SensorFamily.of(patchInfo)

            var age = 0
            if (fram.size >= 318) {
                age = (fram[316].toInt() and 0xFF) + ((fram[317].toInt() and 0xFF) shl 8)
            }

            var lifetime = 20_160
            if (fram.size >= 328) {
                lifetime = ((fram[326].toInt() and 0xFF) + ((fram[327].toInt() and 0xFF) shl 8)) - 60 // for safety reasons
            }

            return SensorInfo(
                family = family,
                type = SensorType.of(patchInfo),
                region = SensorRegion.of(patchInfo[3].toInt() and 0xFF),
                serial = sensorSerialNumber(uid, family),
                state = SensorState.of(fram[4].toInt() and 0xFF),
                age = age,
                lifetime = lifetime,
                factoryCalibration = FactoryCalibration.libreStyleCalibration(fram)
            )
        }

        internal fun sensorSerialNumber(uid: ByteArray, sensorFamily: SensorFamily): String? {
            val lookupTable = "0123456789ACDEFGHJKLMNPQRTUVWXYZ"

            if (uid.size != 8) {
                return null
            }

            // the id backwards, then the last 6 bytes of that
            val bytes = IntArray(6) { uid[5 - it].toInt() and 0xFF }
            val fiveBitsArray = intArrayOf(
                bytes[0] shr 3,
                (bytes[0] shl 2) + (bytes[1] shr 6),

                bytes[1] shr 1,
                (bytes[1] shl 4) + (bytes[2] shr 4),

                (bytes[2] shl 1) + (bytes[3] shr 7),

                bytes[3] shr 2,
                (bytes[3] shl 3) + (bytes[4] shr 5),

                bytes[4],

                bytes[5] shr 3,
                bytes[5] shl 2
            )

            val serial = StringBuilder(sensorFamily.rawValue.toString())
            for (value in fiveBitsArray) {
                serial.append(lookupTable[value and 0x1f])
            }
            return serial.toString()
        }
    }
}
