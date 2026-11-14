// The order of the steps follows Glucose Direct (MIT License, Copyright (c) 2023 Reimar Metzen):
// App/Modules/SensorConnector/LibreConnection/LibreNFC.swift.
//
// A scan only READS. The app never sends "activate".
//
// One command changes the sensor: "enable streaming" (see [LibreNfcReader.enableStreaming]).
// It is sent only if the user has switched on "Live values", once after a good scan. From then
// on the sensor sends its values over Bluetooth to this app and not to the app that had the
// connection before.

package com.glucosescan.app.nfc

import com.glucosescan.app.core.RawScan
import com.glucosescan.app.libre.Libre2Ble
import java.io.IOException

/** Reads the "patch info" and the memory of a Libre sensor. */
class LibreNfcReader(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) }
) {
    /** How the memory blocks are asked for. If one way does not work, the next one is tried. */
    enum class Mode(val blocksPerRequest: Int) {
        /** 3 blocks with one "read multiple blocks" command. */
        MULTI(3),

        /** 1 block with one "read multiple blocks" command. */
        MULTI_ONE(1),

        /** 1 block with the "read single block" command. */
        SINGLE(1)
    }

    /** The way that worked in the last read. The next read starts with it. */
    var mode: Mode = Mode.MULTI

    /** Text lines about what happened, for the details screen. */
    val log = ArrayList<String>()

    /**
     * Step 1: asks the tag what kind of sensor it is.
     * @return the id of the sensor (8 bytes, 0xE0 last) and its patch info
     */
    @Throws(NfcReadException::class)
    fun readPatchInfo(link: NfcVLink): Pair<ByteArray, ByteArray> {
        val uid = normalizeUid(link.id)
        if (uid.size != 8) {
            throw NfcReadException(NfcReadException.Reason.NOT_A_SENSOR, "tag id has ${uid.size} bytes")
        }

        // 0x02 = high data rate, 0xA1 = custom command, then the code of the chip maker (from the id).
        val answer = request(link, byteArrayOf(FLAG_HIGH_DATA_RATE, CMD_PATCH_INFO, uid[6]), "patch info")
        if (isError(answer)) {
            throw NfcReadException(NfcReadException.Reason.NOT_A_SENSOR, "patch info command refused: ${hex(answer)}")
        }
        val patchInfo = answer.copyOfRange(1, answer.size)
        log.add("patch info: ${hex(patchInfo)}")
        return Pair(uid, patchInfo)
    }

    /** Step 2: reads the 43 memory blocks (344 bytes). */
    @Throws(NfcReadException::class)
    fun readFram(link: NfcVLink): ByteArray {
        val fram = ByteArray(BLOCKS * BLOCK_SIZE)
        var block = 0
        var modeIndex = Mode.values().indexOf(mode)
        val firstModeTried = modeIndex
        var lastProblem = ""

        while (block < BLOCKS) {
            val current = Mode.values()[modeIndex]
            val count = Math.min(current.blocksPerRequest, BLOCKS - block)
            val command = when (current) {
                Mode.MULTI, Mode.MULTI_ONE -> byteArrayOf(FLAG_HIGH_DATA_RATE, CMD_READ_MULTIPLE_BLOCKS, block.toByte(), (count - 1).toByte())
                Mode.SINGLE -> byteArrayOf(FLAG_HIGH_DATA_RATE, CMD_READ_SINGLE_BLOCK, block.toByte())
            }

            var answer: ByteArray? = null
            try {
                answer = request(link, command, "block $block")
            } catch (e: NfcReadException) {
                lastProblem = e.message ?: "no answer"
            }

            if (answer != null && !isError(answer) && answer.size == 1 + count * BLOCK_SIZE) {
                System.arraycopy(answer, 1, fram, block * BLOCK_SIZE, count * BLOCK_SIZE)
                block += count
                continue
            }
            if (answer != null) {
                lastProblem = "unexpected answer for block $block: ${hex(answer)}"
            }
            log.add("$current failed at block $block: $lastProblem")

            // try the next way, starting again at the block that failed
            modeIndex = (modeIndex + 1) % Mode.values().size
            if (modeIndex == firstModeTried) {
                val reason = if (answer == null) NfcReadException.Reason.NO_ANSWER else NfcReadException.Reason.BAD_ANSWER
                throw NfcReadException(reason, lastProblem)
            }
        }

        mode = Mode.values()[modeIndex]
        return fram
    }

    /** Both steps. */
    @Throws(NfcReadException::class)
    fun read(link: NfcVLink): RawScan {
        val (uid, patchInfo) = readPatchInfo(link)
        val fram = readFram(link)
        return RawScan(uid, patchInfo, fram, now())
    }

    /**
     * Tells the sensor to send its values over Bluetooth to this app ("enable streaming").
     *
     * THIS CHANGES THE SENSOR. After it, the app that had the Bluetooth connection before (for
     * example FreeStyle LibreLink) gets no more values and no more alarms over Bluetooth, until
     * that app scans the sensor again. Call it only if the user asked for live values.
     *
     * @param unlockCode any 32-bit number; the same number is needed to unlock the connection later
     * @return the 6 bytes the sensor answers with (its Bluetooth address, turned round)
     */
    @Throws(NfcReadException::class)
    fun enableStreaming(link: NfcVLink, uid: ByteArray, patchInfo: ByteArray, unlockCode: Long): ByteArray {
        if (uid.size != 8 || patchInfo.size < 6) {
            throw NfcReadException(NfcReadException.Reason.BAD_ANSWER, "no sensor id or patch info for enable streaming")
        }
        val parameters = Libre2Ble.enableStreamingParameters(uid, patchInfo, unlockCode)
        val command = byteArrayOf(FLAG_HIGH_DATA_RATE, CMD_PATCH_INFO, uid[6]) + parameters
        val answer = request(link, command, "enable streaming")
        if (isError(answer)) {
            throw NfcReadException(NfcReadException.Reason.BAD_ANSWER, "enable streaming refused: ${hex(answer)}")
        }
        val data = answer.copyOfRange(1, answer.size)
        if (data.size != 6) {
            throw NfcReadException(NfcReadException.Reason.BAD_ANSWER, "enable streaming answered with ${data.size} bytes: ${hex(answer)}")
        }
        log.add("streaming enabled, answer: ${hex(data)}")
        return data
    }

    /** Sends one command. Tries again a few times if the tag does not answer. */
    private fun request(link: NfcVLink, command: ByteArray, what: String): ByteArray {
        var last: IOException? = null
        for (attempt in 1..RETRY_COUNT) {
            try {
                val answer = link.transceive(command)
                if (answer.isEmpty()) {
                    last = IOException("empty answer")
                } else {
                    return answer
                }
            } catch (e: IOException) {
                last = e
            }
            if (attempt < RETRY_COUNT) {
                sleep(RETRY_PAUSE_MILLIS)
            }
        }
        throw NfcReadException(NfcReadException.Reason.NO_ANSWER, "no answer for $what (${last?.javaClass?.simpleName}: ${last?.message})", last)
    }

    companion object {
        const val BLOCKS = 43
        const val BLOCK_SIZE = 8
        const val RETRY_COUNT = 5
        const val RETRY_PAUSE_MILLIS = 100L

        const val FLAG_HIGH_DATA_RATE: Byte = 0x02
        const val CMD_READ_SINGLE_BLOCK: Byte = 0x20
        const val CMD_READ_MULTIPLE_BLOCKS: Byte = 0x23
        const val CMD_PATCH_INFO: Byte = 0xA1.toByte()

        /** The status byte says "error" if its lowest bit is set. */
        fun isError(answer: ByteArray): Boolean = answer.isEmpty() || (answer[0].toInt() and 0x01) != 0

        /**
         * Android gives the id of an NFC-V tag with the lowest byte first, so the last byte is
         * 0xE0. The decoding needs it like that. If a phone should give it the other way round,
         * it is turned.
         */
        fun normalizeUid(id: ByteArray): ByteArray {
            if (id.size == 8 && (id[7].toInt() and 0xFF) != 0xE0 && (id[0].toInt() and 0xFF) == 0xE0) {
                return id.reversedArray()
            }
            return id.copyOf()
        }

        fun hex(bytes: ByteArray): String {
            val text = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                text.append(HEX[(b.toInt() shr 4) and 0x0F]).append(HEX[b.toInt() and 0x0F])
            }
            return text.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
