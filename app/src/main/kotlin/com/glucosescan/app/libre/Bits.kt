// Ported from Glucose Direct (MIT License, Copyright (c) 2023 Reimar Metzen):
// App/Modules/SensorConnector/SensorUtility.swift, functions readBits and crc16.

package com.glucosescan.app.libre

/**
 * Reads [bitCount] bits from [buffer]. The first bit is [bitOffset] bits after the start of
 * byte [byteOffset]. The lowest bit comes first. Bits outside the buffer count as 0.
 */
internal fun readBits(buffer: ByteArray, byteOffset: Int, bitOffset: Int, bitCount: Int): Int {
    if (bitCount == 0) {
        return 0
    }

    var res = 0
    for (i in 0 until bitCount) {
        val totalBitOffset = byteOffset * 8 + bitOffset + i
        val byte = Math.floorDiv(totalBitOffset, 8)
        val bit = totalBitOffset % 8
        if (byte >= 0 && byte < buffer.size && (((buffer[byte].toInt() and 0xFF) shr bit) and 0x1) == 1) {
            res = res or (1 shl i)
        }
    }

    return res
}

/** The checksum that the sensor uses for its memory. */
internal object Crc16 {
    private val table = IntArray(256) { index ->
        var c = index
        repeat(8) {
            c = if ((c and 1) != 0) (c ushr 1) xor 0x8408 else c ushr 1
        }
        c
    }

    /** Checksum of the bytes [from] (included) to [to] (not included), as a number 0..65535. */
    fun compute(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (k in from until to) {
            crc = (crc ushr 8) xor table[(crc xor (data[k].toInt() and 0xFF)) and 0xFF]
        }

        var reverseCrc = 0
        repeat(16) {
            reverseCrc = ((reverseCrc shl 1) or (crc and 1)) and 0xFFFF
            crc = crc ushr 1
        }

        // byteSwapped
        return ((reverseCrc and 0xFF) shl 8) or (reverseCrc ushr 8)
    }

    /**
     * True if the part [start] until [end] of a sensor memory image is intact. The first two
     * bytes of the part hold the checksum of the rest of the part.
     */
    fun sectionIsValid(fram: ByteArray, start: Int, end: Int): Boolean {
        if (start < 0 || end > fram.size || end - start < 3) {
            return false
        }
        val stored = ((fram[start].toInt() and 0xFF) shl 8) or (fram[start + 1].toInt() and 0xFF)
        return stored == compute(fram, start + 2, end)
    }
}
