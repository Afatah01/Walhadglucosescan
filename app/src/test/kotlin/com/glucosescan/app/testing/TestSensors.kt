package com.glucosescan.app.testing

import com.glucosescan.app.core.RawScan
import com.glucosescan.app.libre.Crc16
import com.glucosescan.app.libre.Libre2Crypto
import com.glucosescan.app.reference.ReferenceVectors

/** Sensor data for tests. It is built from two real Libre 2 scans. */
object TestSensors {
    class Sample(val name: String, val uid: ByteArray, val patchInfo: ByteArray, val encrypted: ByteArray, val plain: ByteArray) {
        fun rawScan(scanTime: Long): RawScan = RawScan(uid.copyOf(), patchInfo.copyOf(), encrypted.copyOf(), scanTime)

        /**
         * The same sensor with a changed memory. [change] gets the plain memory. After it the
         * checksums are set right again and the memory is scrambled like the sensor does it.
         */
        fun changed(fixChecksums: Boolean = true, change: (ByteArray) -> Unit): Sample {
            val newPlain = plain.copyOf()
            change(newPlain)
            if (fixChecksums) {
                fixChecksums(newPlain)
            }
            return Sample(name, uid, patchInfo, scramble(uid, patchInfo, newPlain), newPlain)
        }
    }

    private val samples: Map<String, Sample> by lazy {
        ReferenceVectors.of("SAMPLE").associate { r ->
            r[0] to Sample(r[0], ReferenceVectors.hex(r[1]), ReferenceVectors.hex(r[2]), ReferenceVectors.hex(r[3]), ReferenceVectors.hex(r[4]))
        }
    }

    /** Real scan: sensor 3MH001HM1LM, 10320 minutes old, newest value 99 mg/dL. */
    val example: Sample get() = samples.getValue("Example")

    /** Real scan: sensor 3MH001GJ0VW, 3170 minutes old, newest value 95 mg/dL. */
    val example2: Sample get() = samples.getValue("Example2")

    /** The scrambling is its own reverse: doing it on a plain memory gives the scrambled one. */
    fun scramble(uid: ByteArray, patchInfo: ByteArray, plain: ByteArray): ByteArray =
        Libre2Crypto.decryptFram(uid, patchInfo, plain)!!

    fun fixChecksums(plain: ByteArray) {
        for ((start, end) in listOf(0 to 24, 24 to 320, 320 to 344)) {
            val crc = Crc16.compute(plain, start + 2, end)
            plain[start] = (crc shr 8).toByte()
            plain[start + 1] = (crc and 0xFF).toByte()
        }
    }

    fun setAge(plain: ByteArray, minutes: Int) {
        plain[316] = (minutes and 0xFF).toByte()
        plain[317] = ((minutes shr 8) and 0xFF).toByte()
    }

    fun setState(plain: ByteArray, state: Int) {
        plain[4] = state.toByte()
    }

    fun setHistoryIndex(plain: ByteArray, index: Int) {
        plain[27] = index.toByte()
    }

    /** Sets all 6 bytes of the newest one-minute value to 0. */
    fun clearNewestTrendRecord(plain: ByteArray) {
        val trendIndex = plain[26].toInt() and 0xFF
        val offset = 28 + ((trendIndex - 1 + 16) % 16) * 6
        for (k in offset until offset + 6) {
            plain[k] = 0
        }
    }

    /** Sets the 10-bit number that selects the row of the calibration table (bits 3..12 of byte 2). */
    fun setCalibrationRow(plain: ByteArray, row: Int) {
        val word = (plain[2].toInt() and 0xFF) or ((plain[3].toInt() and 0xFF) shl 8)
        val changed = (word and (0x3FF shl 3).inv()) or ((row and 0x3FF) shl 3)
        plain[2] = (changed and 0xFF).toByte()
        plain[3] = ((changed shr 8) and 0xFF).toByte()
    }

    /** Sets the raw glucose number (14 bits) of the newest one-minute value. */
    fun setNewestTrendRaw(plain: ByteArray, raw: Int) {
        val trendIndex = plain[26].toInt() and 0xFF
        val j = (trendIndex - 1 + 16) % 16
        val offset = 28 + j * 6
        plain[offset] = (raw and 0xFF).toByte()
        plain[offset + 1] = ((plain[offset + 1].toInt() and 0xC0) or ((raw shr 8) and 0x3F)).toByte()
    }
}
