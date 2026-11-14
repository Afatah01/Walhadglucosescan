package com.glucosescan.app.testing

import com.glucosescan.app.libre.Crc16
import com.glucosescan.app.libre.FactoryCalibration
import com.glucosescan.app.libre.FramParser
import com.glucosescan.app.libre.Libre2Ble

/**
 * Builds Bluetooth messages like a Libre 2 sends them, for tests.
 *
 * The scrambling uses the key of the app's own decoding. That decoding is compared with the
 * original app in PortEqualsOriginalTest, with messages the original made.
 */
object BleMessages {
    /** One measurement as the sensor stores it. [rawTemperature] and [adjustment] are multiples of 4. */
    class Record(val rawGlucose: Int, val rawTemperature: Int, val adjustment: Int)

    /** How many minutes before the message each of the first 7 records was made. */
    val MINUTES = intArrayOf(0, 2, 4, 6, 7, 12, 15)

    /**
     * The 44 plain bytes of a message.
     *
     * @param records 10 records: 7 one-minute values (see [MINUTES]), then 3 values of the 15-minute history
     */
    fun plain(age: Int, records: List<Record>): ByteArray {
        require(records.size == 10) { "a message has 10 records" }
        val data = ByteArray(44)
        for ((i, record) in records.withIndex()) {
            val size = Math.abs(record.adjustment) shr 2
            require(record.rawGlucose in 0..0x3FFF && (record.rawTemperature shr 2) in 0..0xFFF && size in 0..0x1F) { "record $i does not fit" }
            writeBits(data, i * 4, 0, 14, record.rawGlucose)
            writeBits(data, i * 4, 14, 12, record.rawTemperature shr 2)
            writeBits(data, i * 4, 26, 5, size)
            writeBits(data, i * 4, 31, 1, if (record.adjustment < 0) 1 else 0)
        }
        data[40] = (age and 0xFF).toByte()
        data[41] = ((age shr 8) and 0xFF).toByte()
        val crc = Crc16.compute(data, 0, 42)
        data[42] = ((crc shr 8) and 0xFF).toByte()
        data[43] = (crc and 0xFF).toByte()
        return data
    }

    /** The 46 bytes as the sensor sends them. [header] is the number the sensor puts in front (any 16 bits). */
    fun scramble(uid: ByteArray, plain: ByteArray, header: Int = 0x5A3C): ByteArray {
        require(plain.size == 44)
        val first = (header and 0xFF).toByte()
        val second = ((header shr 8) and 0xFF).toByte()
        val key = Libre2Ble.keyStream(uid, first, second)
        return byteArrayOf(first, second) + ByteArray(44) { ((plain[it].toInt() xor key[it].toInt()) and 0xFF).toByte() }
    }

    /** A message comes in three parts: 20, 18 and 8 bytes. */
    fun packets(message: ByteArray): List<ByteArray> {
        require(message.size == 46)
        return listOf(message.copyOfRange(0, 20), message.copyOfRange(20, 38), message.copyOfRange(38, 46))
    }

    /**
     * The records of a message that a sample sensor would send at the moment of its scan:
     * the same raw numbers as in its memory.
     */
    fun recordsOf(sample: TestSensors.Sample): List<Record> {
        val calibration = FactoryCalibration.libreStyleCalibration(sample.plain)
        val fram = FramParser.records(sample.plain, calibration)
        val list = ArrayList<Record>()
        for (minutes in MINUTES) {
            val r = fram.trend[minutes]
            list.add(Record(r.rawGlucose, r.rawTemperature, r.rawTemperatureAdjustment))
        }
        for (k in 0 until 3) {
            val r = fram.history[k]
            list.add(Record(r.rawGlucose, r.rawTemperature, r.rawTemperatureAdjustment))
        }
        return list
    }

    /** The raw number that gives [mgdl] (as near as possible) with this temperature. */
    fun rawFor(calibration: FactoryCalibration, mgdl: Int, rawTemperature: Int, adjustment: Int): Int {
        var best = 1
        var bestDistance = Double.MAX_VALUE
        var low = 1
        var high = 0x3FFF
        // the value grows with the raw number
        while (low <= high) {
            val middle = (low + high) / 2
            val value = calibration.calibrate(middle.toDouble(), rawTemperature.toDouble(), adjustment.toDouble())
            val distance = Math.abs(value - mgdl)
            if (distance < bestDistance) {
                bestDistance = distance
                best = middle
            }
            if (value < mgdl) low = middle + 1 else high = middle - 1
        }
        return best
    }

    /**
     * A message of a sample sensor with chosen values.
     *
     * @param mgdl 7 values for 0, 2, 4, 6, 7, 12 and 15 minutes before the message; null = the sensor has no value there
     */
    fun message(sample: TestSensors.Sample, age: Int, mgdl: List<Int?>, header: Int = 0x5A3C): ByteArray {
        require(mgdl.size == 7)
        val calibration = FactoryCalibration.libreStyleCalibration(sample.plain)
        val base = recordsOf(sample)
        val records = ArrayList<Record>()
        for (i in 0 until 10) {
            val model = base[i]
            val wanted = if (i < 7) mgdl[i] else mgdl[6]
            records.add(
                if (wanted == null) {
                    Record(0, 0, 0)
                } else {
                    Record(rawFor(calibration, wanted, model.rawTemperature, model.rawTemperatureAdjustmentSafe()), model.rawTemperature, model.rawTemperatureAdjustmentSafe())
                }
            )
        }
        return scramble(sample.uid, plain(age, records), header)
    }

    /** A message where all values are the same. */
    fun steady(sample: TestSensors.Sample, age: Int, mgdl: Int, header: Int = 0x5A3C): ByteArray = message(sample, age, List(7) { mgdl }, header)

    private fun Record.rawTemperatureAdjustmentSafe(): Int = adjustment.coerceIn(-124, 124)

    private fun writeBits(buffer: ByteArray, byteOffset: Int, bitOffset: Int, bitCount: Int, value: Int) {
        for (i in 0 until bitCount) {
            val total = byteOffset * 8 + bitOffset + i
            val byte = total / 8
            val bit = total % 8
            val current = buffer[byte].toInt() and 0xFF
            buffer[byte] = (if (((value shr i) and 1) == 1) current or (1 shl bit) else current and (1 shl bit).inv()).toByte()
        }
    }
}
