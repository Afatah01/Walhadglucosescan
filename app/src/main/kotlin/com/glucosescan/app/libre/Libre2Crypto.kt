// Ported from Glucose Direct (MIT License, Copyright (c) 2023 Reimar Metzen):
// App/Modules/SensorConnector/SensorUtility.swift, Libre2EUtility.decryptFRAM and processCrypto.
// That file thanks guidos, ivalkou and dabear and many others.

package com.glucosescan.app.libre

/** A Libre 2 sends its memory in a scrambled form. This turns it into the plain form. */
internal object Libre2Crypto {
    private val keys = intArrayOf(0xa0c5, 0x6860, 0x0000, 0x14c6)

    /** All numbers are 16 bit (0..65535). */
    fun processCrypto(input: IntArray): IntArray {
        fun op(value: Int): Int {
            // We check for last 2 bits and do the xor with specific value if bit is 1
            var res = value ushr 2 // Result does not include these last 2 bits

            if ((value and 1) != 0) { // If last bit is 1
                res = res xor keys[1]
            }

            if ((value and 2) != 0) { // If second last bit is 1
                res = res xor keys[0]
            }

            return res
        }

        val r0 = op(input[0]) xor input[3]
        val r1 = op(r0) xor input[2]
        val r2 = op(r1) xor input[1]
        val r3 = op(r2) xor input[0]
        val r4 = op(r3)
        val r5 = op(r4 xor r0)
        val r6 = op(r5 xor r1)
        val r7 = op(r6 xor r2)

        val f1 = r0 xor r4
        val f2 = r1 xor r5
        val f3 = r2 xor r6
        val f4 = r3 xor r7

        return intArrayOf(f4, f3, f2, f1)
    }

    /**
     * @param uid the 8 bytes of the sensor id, in the order they are sent over NFC
     * @param patchInfo the 6 bytes of the "patch info" answer
     * @param fram the 344 bytes as read from the sensor
     * @return the plain 344 bytes, or null if the input has the wrong size or type
     */
    fun decryptFram(uid: ByteArray, patchInfo: ByteArray, fram: ByteArray): ByteArray? {
        if (uid.size != 8 || patchInfo.size != 6 || SensorType.of(patchInfo) == SensorType.LIBRE1 || fram.size != 344) {
            return null
        }

        fun word(high: Byte, low: Byte): Int {
            return ((high.toInt() and 0xFF) shl 8) + (low.toInt() and 0xFF)
        }

        val result = ByteArray(344)

        for (i in 0 until 43) {
            var y = word(patchInfo[5], patchInfo[4])
            if (i < 3 || i >= 40) {
                y = 0xcadc
            }

            val s1 = if ((patchInfo[0].toInt() and 0xFF) == 0xe5) {
                (word(uid[5], uid[4]) + y + i) and 0xffff
            } else {
                ((word(uid[5], uid[4]) + (word(patchInfo[5], patchInfo[4]) xor 0x44)) + i) and 0xffff
            }

            val s2 = (word(uid[3], uid[2]) + keys[2]) and 0xffff
            val s3 = (word(uid[1], uid[0]) + (i shl 1)) and 0xffff
            val s4 = 0x241a xor keys[3]

            val key = processCrypto(intArrayOf(s1, s2, s3, s4))
            result[i * 8 + 0] = ((fram[i * 8 + 0].toInt() and 0xFF) xor (key[0] and 0xff)).toByte()
            result[i * 8 + 1] = ((fram[i * 8 + 1].toInt() and 0xFF) xor ((key[0] shr 8) and 0xff)).toByte()
            result[i * 8 + 2] = ((fram[i * 8 + 2].toInt() and 0xFF) xor (key[1] and 0xff)).toByte()
            result[i * 8 + 3] = ((fram[i * 8 + 3].toInt() and 0xFF) xor ((key[1] shr 8) and 0xff)).toByte()
            result[i * 8 + 4] = ((fram[i * 8 + 4].toInt() and 0xFF) xor (key[2] and 0xff)).toByte()
            result[i * 8 + 5] = ((fram[i * 8 + 5].toInt() and 0xFF) xor ((key[2] shr 8) and 0xff)).toByte()
            result[i * 8 + 6] = ((fram[i * 8 + 6].toInt() and 0xFF) xor (key[3] and 0xff)).toByte()
            result[i * 8 + 7] = ((fram[i * 8 + 7].toInt() and 0xFF) xor ((key[3] shr 8) and 0xff)).toByte()
        }

        return result
    }
}
