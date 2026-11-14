// Ported from Glucose Direct (MIT License, Copyright (c) 2023 Reimar Metzen):
// App/Modules/SensorConnector/SensorUtility.swift (usefulFunction, prepareVariables, decryptBLE,
// streamingUnlockPayload, parseBLE) and
// App/Modules/SensorConnector/LibreConnection/LibreNFC.swift (nfcCommand).
// Those files thank guidos, ivalkou and dabear and many others.

package com.glucosescan.app.libre

/** One measurement out of a Bluetooth message. */
class BleReading(
    /** How many minutes before the message the sensor made it. */
    val minutesBefore: Int,
    /** The raw number of the sensor. 0 means the sensor gave no value. */
    val rawGlucose: Int,
    /** mg/dL. Only meaningful if [errorBits] is 0. */
    val glucose: Double,
    /** 0 means the measurement is good (as the original app sees it). */
    val errorBits: Int
)

/** What one Bluetooth message of a Libre 2 holds. */
class BleRecords(
    /** Minutes since the sensor was started. */
    val age: Int,
    /** Values of the last 15 minutes (0, 2, 4, 6, 7, 12 and 15 minutes before), oldest first. */
    val trend: List<BleReading>,
    /** 15-minute values that are older than the oldest value of [trend], oldest first. */
    val history: List<BleReading>
)

/**
 * The Bluetooth side of a Libre 2 of the European type: the NFC command that switches Bluetooth
 * on, the bytes that unlock a connection, and the decoding of the messages.
 *
 * Every function here does the same as the original Swift code. The unit test
 * PortEqualsOriginalTest compares them with results of the original.
 */
internal object Libre2Ble {
    private val keys = intArrayOf(0xa0c5, 0x6860, 0x0000, 0x14c6)

    /** The sub-command "enable streaming" of the NFC command 0xA1. It changes the sensor. */
    const val SUBCOMMAND_ENABLE_STREAMING = 0x1e

    /**
     * The number that is given to the sensor with "enable streaming" and that is needed again to
     * unlock a connection. The original app uses 42.
     */
    const val UNLOCK_CODE: Long = 42L

    /** The biggest unlock count the original can send (it keeps the count in 16 bits). */
    const val MAX_UNLOCK_COUNT = 0xFFFF

    /** The size of a Bluetooth message as the sensor sends it, in three parts of 20, 18 and 8 bytes. */
    const val MESSAGE_SIZE = 46

    /** The size of a message after unscrambling. */
    const val PLAIN_SIZE = 44

    private fun word(high: Int, low: Int): Int = ((high and 0xFF) shl 8) + (low and 0xFF)

    private fun word(high: Byte, low: Byte): Int = word(high.toInt(), low.toInt())

    /** All numbers are 16 bit (0..65535). */
    fun prepareVariables(uid: ByteArray, x: Int, y: Int): IntArray {
        val s1 = (word(uid[5], uid[4]) + x + y) and 0xffff
        val s2 = (word(uid[3], uid[2]) + keys[2]) and 0xffff
        val s3 = (word(uid[1], uid[0]) + x * 2) and 0xffff
        val s4 = 0x241a xor keys[3]

        return intArrayOf(s1, s2, s3, s4)
    }

    fun prepareVariables(uid: ByteArray, i1: Int, i2: Int, i3: Int, i4: Int): IntArray {
        val s1 = (word(uid[5], uid[4]) + i1) and 0xffff
        val s2 = (word(uid[3], uid[2]) + i2) and 0xffff
        val s3 = (word(uid[1], uid[0]) + i3 + keys[2]) and 0xffff
        val s4 = (i4 + keys[3]) and 0xffff

        return intArrayOf(s1, s2, s3, s4)
    }

    /**
     * @param uid the 8 bytes of the sensor id, in the order they are sent over NFC
     * @return 4 bytes
     */
    fun usefulFunction(uid: ByteArray, x: Int, y: Int): ByteArray {
        val blockKey = Libre2Crypto.processCrypto(prepareVariables(uid, x, y))
        val low = blockKey[0]
        val high = blockKey[1]

        val r1 = low xor 0x4163
        val r2 = high xor 0x4344

        return byteArrayOf(r1.toByte(), (r1 shr 8).toByte(), r2.toByte(), (r2 shr 8).toByte())
    }

    /**
     * The parameters of the NFC command that switches Bluetooth on. They follow the command
     * code 0xA1 (and, on Android, the code of the chip maker). The sensor answers with 6 bytes.
     *
     * @param unlockCode any 32-bit number (0..4294967295). The same number must be given to
     * [streamingUnlockPayload] as "enableTime" later.
     */
    fun enableStreamingParameters(uid: ByteArray, patchInfo: ByteArray, unlockCode: Long): ByteArray {
        // Enables Bluetooth on Libre 2. Returns peripheral MAC address to connect to.
        // unlockCode could be any 32 bit value. The unlockCode and sensor Uid / patchInfo
        // will have also to be provided to the login function when connecting to peripheral.
        val b = byteArrayOf(
            (unlockCode and 0xFF).toByte(),
            ((unlockCode shr 8) and 0xFF).toByte(),
            ((unlockCode shr 16) and 0xFF).toByte(),
            ((unlockCode shr 24) and 0xFF).toByte()
        )
        val y = word(patchInfo[5], patchInfo[4]) xor word(b[1], b[0])

        val d = usefulFunction(uid, SUBCOMMAND_ENABLE_STREAMING, y)

        return byteArrayOf(SUBCOMMAND_ENABLE_STREAMING.toByte()) + b + d
    }

    /**
     * The 12 bytes that must be written to the sensor right after connecting.
     *
     * @param enableTime the "unlockCode" that was sent with the NFC command
     * @param unlockCount 1 for the first connection after the NFC command, then one more each time
     */
    fun streamingUnlockPayload(uid: ByteArray, patchInfo: ByteArray, enableTime: Long, unlockCount: Int): ByteArray {
        // First 4 bytes are just int32 of timestamp + unlockCount
        val time = (enableTime + unlockCount) and 0xFFFFFFFFL
        val b = intArrayOf(
            (time and 0xff).toInt(),
            ((time shr 8) and 0xff).toInt(),
            ((time shr 16) and 0xff).toInt(),
            ((time shr 24) and 0xff).toInt()
        )

        // Then we need data of activation command and enable command that were sent to sensor
        val ad = usefulFunction(uid, 0x1b, 0x1b6a)
        val ed = usefulFunction(uid, 0x1e, (enableTime and 0xffff).toInt() xor word(patchInfo[5], patchInfo[4]))

        val t11 = word(ed[1], ed[0]) xor word(b[3], b[2])
        val t12 = word(ad[1], ad[0])
        val t13 = word(ed[3], ed[2]) xor word(b[1], b[0])
        val t14 = word(ad[3], ad[2])

        val t2 = Libre2Crypto.processCrypto(prepareVariables(uid, t11, t12, t13, t14))

        val t31 = byteSwapped(crc16(0xc1, 0xc4, 0xc3, 0xc0, 0xd4, 0xe1, 0xe7, 0xba, t2[0] and 0xff, (t2[0] shr 8) and 0xff))
        val t32 = byteSwapped(crc16(t2[1] and 0xff, (t2[1] shr 8) and 0xff, t2[2] and 0xff, (t2[2] shr 8) and 0xff, t2[3] and 0xff, (t2[3] shr 8) and 0xff))
        val t33 = byteSwapped(crc16(ad[0].toInt(), ad[1].toInt(), ad[2].toInt(), ad[3].toInt(), ed[0].toInt(), ed[1].toInt()))
        val t34 = byteSwapped(crc16(ed[2].toInt(), ed[3].toInt(), b[0], b[1], b[2], b[3]))

        val t4 = Libre2Crypto.processCrypto(prepareVariables(uid, t31, t32, t33, t34))

        val res = intArrayOf(
            t4[0] and 0xff, (t4[0] shr 8) and 0xff,
            t4[1] and 0xff, (t4[1] shr 8) and 0xff,
            t4[2] and 0xff, (t4[2] shr 8) and 0xff,
            t4[3] and 0xff, (t4[3] shr 8) and 0xff
        )

        return ByteArray(12) { index -> (if (index < 4) b[index] else res[index - 4]).toByte() }
    }

    /**
     * The 64 bytes a message is scrambled with. They depend on the sensor and on the first two
     * bytes of the message. (In the original this is the first part of decryptBLE.)
     */
    fun keyStream(uid: ByteArray, first: Byte, second: Byte): ByteArray {
        val d = usefulFunction(uid, 0x1b, 0x1b6a)
        val x = (word(d[1], d[0]) xor word(d[3], d[2])) or 0x63
        val y = word(second, first) xor 0x63

        val key = ByteArray(64)
        var initialKey = Libre2Crypto.processCrypto(prepareVariables(uid, x, y))

        for (round in 0 until 8) {
            key[round * 8 + 0] = initialKey[0].toByte()
            key[round * 8 + 1] = (initialKey[0] shr 8).toByte()
            key[round * 8 + 2] = initialKey[1].toByte()
            key[round * 8 + 3] = (initialKey[1] shr 8).toByte()
            key[round * 8 + 4] = initialKey[2].toByte()
            key[round * 8 + 5] = (initialKey[2] shr 8).toByte()
            key[round * 8 + 6] = initialKey[3].toByte()
            key[round * 8 + 7] = (initialKey[3] shr 8).toByte()
            initialKey = Libre2Crypto.processCrypto(initialKey)
        }

        return key
    }

    /**
     * Unscrambles one Bluetooth message.
     *
     * @param data the 46 bytes as they were received
     * @return the 44 plain bytes, or null if the size is wrong or the checksum does not fit
     */
    fun decrypt(uid: ByteArray, data: ByteArray): ByteArray? {
        // The original does not look at the sizes and would stop the app on a short message.
        if (uid.size != 8 || data.size != MESSAGE_SIZE) {
            return null
        }

        val key = keyStream(uid, data[0], data[1])

        val result = ByteArray(PLAIN_SIZE) { i -> ((data[i + 2].toInt() and 0xFF) xor (key[i].toInt() and 0xFF)).toByte() }

        if (Crc16.compute(result, 0, 42) != word(result[42], result[43])) {
            return null
        }

        return result
    }

    /**
     * Reads the measurements out of an unscrambled message, as the original does.
     *
     * @param calibration must be usable (see [FactoryCalibration.isUsable])
     * @param data the 44 plain bytes
     */
    fun parse(calibration: FactoryCalibration, data: ByteArray): BleRecords {
        require(data.size == PLAIN_SIZE) { "a plain message has $PLAIN_SIZE bytes, not ${data.size}" }

        val historyDelay = 2
        val age = word(data[41], data[40])

        val trendReadings = ArrayList<BleReading>()
        val historyReadings = ArrayList<BleReading>()

        for (i in 0 until 10) {
            val rawGlucoseValue = readBits(data, i * 4, 0, 0xe)
            val rawTemperature = readBits(data, i * 4, 0xe, 0xc) shl 2
            // quality: 0 is "OK"
            val quality = if (rawGlucoseValue == 0) rawTemperature shr 2 else 0

            var rawTemperatureAdjustment = readBits(data, i * 4, 0x1a, 0x5) shl 2
            val negativeAdjustment = readBits(data, i * 4, 0x1f, 0x1)
            if (negativeAdjustment != 0) {
                rawTemperatureAdjustment = -rawTemperatureAdjustment
            }

            var id = age
            if (i < 7) {
                id -= TREND_MINUTES[i]
            } else {
                id = ((id - historyDelay) / 15) * 15 - 15 * (i - 7)
            }

            val minutesBefore = age - id

            val glucoseValue = calibration.calibrate(rawGlucoseValue.toDouble(), rawTemperature.toDouble(), rawTemperatureAdjustment.toDouble())
            val reading = if (quality == 0) {
                BleReading(minutesBefore, rawGlucoseValue, glucoseValue, 0)
            } else {
                BleReading(minutesBefore, rawGlucoseValue, 0.0, quality)
            }

            if (i < 7) {
                trendReadings.add(reading)
            } else {
                historyReadings.add(reading)
            }
        }

        // oldest first
        val trend = trendReadings.sortedByDescending { it.minutesBefore }
        val oldestTrend = trend.first().minutesBefore
        val history = historyReadings.sortedByDescending { it.minutesBefore }.filter { it.minutesBefore > oldestTrend }

        return BleRecords(age, trend, history)
    }

    /** How many minutes before the message each of the first 7 records was made. */
    val TREND_MINUTES = intArrayOf(0, 2, 4, 6, 7, 12, 15)

    private fun crc16(vararg bytes: Int): Int = Crc16.compute(ByteArray(bytes.size) { bytes[it].toByte() })

    private fun byteSwapped(value: Int): Int = ((value and 0xFF) shl 8) or ((value shr 8) and 0xFF)
}
