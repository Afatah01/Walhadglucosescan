package com.glucosescan.app.nfc

import java.io.IOException

/**
 * A connection to one NFC tag of the type "NFC-V" (ISO 15693). The real one talks to the phone's
 * NFC chip. Tests use a fake one.
 */
interface NfcVLink {
    /** The id of the tag as Android gives it. */
    val id: ByteArray

    /**
     * Sends [command] and returns the answer. The first byte of the answer is the status byte.
     * @throws IOException if the tag does not answer or is gone
     */
    @Throws(IOException::class)
    fun transceive(command: ByteArray): ByteArray
}

/** Thrown when the sensor could not be read over NFC. */
class NfcReadException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        /** The tag did not answer (any more). Most often the phone was moved away too early. */
        NO_ANSWER,

        /** The tag answered, but not like a Libre sensor does. */
        NOT_A_SENSOR,

        /** The tag gave an answer that makes no sense. */
        BAD_ANSWER
    }
}
