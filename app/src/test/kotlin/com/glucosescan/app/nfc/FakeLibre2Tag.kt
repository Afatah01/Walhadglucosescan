package com.glucosescan.app.nfc

import java.io.IOException

/**
 * Stands in for a real Libre 2 sensor in tests. It answers the NFC-V (ISO 15693) commands that
 * the app may send, and it notes every command so that tests can check that nothing else is sent.
 *
 * Frame layout (checked against how the open-source app xDrip+ talks to Libre 2 on Android):
 *   request : flags, command, [8 bytes id if the "addressed" flag 0x20 is set], parameters
 *   answer  : status byte 0x00, data            (good)
 *             status byte 0x01, error code      (error)
 */
class FakeLibre2Tag(
    /** Sensor id, lowest byte first (the last byte is 0xE0), as Android reports it. */
    private val uid: ByteArray,
    private val patchInfo: ByteArray,
    /** The 344 bytes the sensor sends (scrambled). */
    var fram: ByteArray,
    /** What the phone reports as tag id. Default: the same as [uid]. */
    private val reportedId: ByteArray = uid
) : NfcVLink {

    /** Every command that was sent, as hex text. */
    val commands = ArrayList<String>()

    /** Commands that would change the sensor or that the app has no reason to send. */
    val forbiddenCommands = ArrayList<String>()

    /** False (the default): "enable streaming" counts as a forbidden command and is refused. */
    var allowStreaming: Boolean = false

    /** Every "enable streaming" command that was sent, as hex text. */
    val streamingCommands = ArrayList<String>()

    /** The unlock code of the last accepted "enable streaming" command, or null. */
    var streamingCode: Long? = null

    /** The Bluetooth address of the sensor, first byte first. */
    var bluetoothAddress: ByteArray = byteArrayOf(0xA4.toByte(), 0x11, 0x22, 0x33, 0x44, 0x55)

    /** The tag stops answering after this many commands (the phone was moved away). */
    var goneAfterCommands: Int = Int.MAX_VALUE

    /** The next n commands get no answer. */
    var failNext: Int = 0

    /** Every n-th command gets no answer (0 = never). */
    var failEvery: Int = 0

    /** False: the tag refuses to give more than one block per command. */
    var supportsMultipleBlocks: Boolean = true

    /** False: the tag refuses the "read multiple blocks" command at all. */
    var supportsReadMultipleCommand: Boolean = true

    /** False: the tag is not a Libre sensor and refuses the patch info command. */
    var answersPatchInfo: Boolean = true

    /** Called before a memory block is sent. Tests use it to change the memory during a read. */
    var beforeBlockRead: ((block: Int) -> Unit)? = null

    override val id: ByteArray
        get() = reportedId.copyOf()

    override fun transceive(command: ByteArray): ByteArray {
        commands.add(LibreNfcReader.hex(command))
        if (commands.size > goneAfterCommands) {
            throw IOException("Tag was lost.")
        }
        if (failNext > 0) {
            failNext--
            throw IOException("Transceive failed")
        }
        if (failEvery > 0 && commands.size % failEvery == 0) {
            throw IOException("Transceive failed")
        }
        if (command.size < 2) {
            return error(0x0F)
        }

        val flags = command[0].toInt() and 0xFF
        val code = command[1].toInt() and 0xFF
        var at = 2

        // custom commands carry the code of the chip maker right after the command code
        if (code >= 0xA0) {
            if (command.size < 3 || command[2] != MAKER_CODE) {
                return error(0x01)
            }
            at = 3
        }

        if ((flags and 0x20) != 0) { // addressed: the id must be ours
            if (command.size < at + 8) {
                return error(0x0F)
            }
            if (!command.copyOfRange(at, at + 8).contentEquals(uid)) {
                throw IOException("Tag was lost.") // another tag is asked: we stay silent
            }
            at += 8
        }
        val parameters = command.copyOfRange(at, command.size)

        return when (code) {
            0xA1 -> {
                if (!answersPatchInfo) {
                    error(0x01)
                } else if (parameters.isNotEmpty() && (parameters[0].toInt() and 0xFF) == 0x1E) {
                    // "enable streaming": changes the sensor. Only tests about live values allow it.
                    streamingCommands.add(LibreNfcReader.hex(command))
                    if (!allowStreaming) {
                        forbiddenCommands.add(LibreNfcReader.hex(command))
                        error(0x0F)
                    } else if (parameters.size != 9) {
                        error(0x0F)
                    } else {
                        var code = 0L
                        for (k in 4 downTo 1) {
                            code = (code shl 8) or (parameters[k].toLong() and 0xFF)
                        }
                        // a real sensor checks the 4 key bytes; a wrong key is refused
                        val expected = com.glucosescan.app.libre.Libre2Ble.enableStreamingParameters(uid, patchInfo, code)
                        if (!expected.contentEquals(parameters)) {
                            error(0x0F)
                        } else {
                            streamingCode = code
                            byteArrayOf(0x00) + bluetoothAddress.reversedArray()
                        }
                    }
                } else if (parameters.isNotEmpty()) {
                    // other sub-commands, such as 0x1B (activate)
                    forbiddenCommands.add(LibreNfcReader.hex(command))
                    error(0x0F)
                } else {
                    byteArrayOf(0x00) + patchInfo
                }
            }

            0x20 -> { // read single block
                if (parameters.size != 1) error(0x0F) else blocks(parameters[0].toInt() and 0xFF, 1)
            }

            0x23 -> { // read multiple blocks: first block, number of blocks minus 1
                if (!supportsReadMultipleCommand) {
                    error(0x01)
                } else if (parameters.size != 2) {
                    error(0x0F)
                } else {
                    val count = (parameters[1].toInt() and 0xFF) + 1
                    if (count > 1 && !supportsMultipleBlocks) error(0x0F) else blocks(parameters[0].toInt() and 0xFF, count)
                }
            }

            else -> {
                forbiddenCommands.add(LibreNfcReader.hex(command))
                error(0x01) // command not supported
            }
        }
    }

    private fun blocks(first: Int, count: Int): ByteArray {
        if (first + count > BLOCKS) {
            return error(0x10) // block not available: a real Libre 2 refuses blocks after 42
        }
        for (block in first until first + count) {
            beforeBlockRead?.invoke(block)
        }
        return byteArrayOf(0x00) + fram.copyOfRange(first * 8, (first + count) * 8)
    }

    private fun error(code: Int): ByteArray = byteArrayOf(0x01, code.toByte())

    companion object {
        const val BLOCKS = 43
        const val MAKER_CODE: Byte = 0x07
    }
}
