package com.glucosescan.app.ble

import com.glucosescan.app.libre.Libre2Ble

/**
 * Puts the parts of a Bluetooth message together. A Libre 2 sends each message of 46 bytes in
 * three parts (20, 18 and 8 bytes), one right after the other, once a minute.
 *
 * The original app takes any three parts in a row for a message. If it starts to listen in
 * the middle of a message, every later message is cut wrongly. Here a message that was begun
 * is dropped when its next part does not come within some seconds.
 */
class PacketAssembler {
    private var buffer = ByteArray(0)
    private var startedAt = 0L

    /** How many bytes of a message are waiting for the rest. */
    val pending: Int
        get() = buffer.size

    /**
     * @param now when the part came in, in milliseconds (any clock that only runs forward)
     * @return the whole message when this part completed one, else null
     */
    fun add(packet: ByteArray, now: Long): ByteArray? {
        if (packet.isEmpty()) {
            return null
        }
        if (buffer.isNotEmpty() && (now - startedAt > MAX_MESSAGE_MILLIS || now < startedAt)) {
            buffer = ByteArray(0) // the rest of the old message never came
        }
        if (buffer.isEmpty()) {
            startedAt = now
        }
        buffer += packet

        if (buffer.size == Libre2Ble.MESSAGE_SIZE) {
            val message = buffer
            buffer = ByteArray(0)
            return message
        }
        if (buffer.size > Libre2Ble.MESSAGE_SIZE) {
            // too much: the parts do not belong together. Start again with this part.
            buffer = if (packet.size < Libre2Ble.MESSAGE_SIZE) packet.copyOf() else ByteArray(0)
            startedAt = now
        }
        return null
    }

    fun reset() {
        buffer = ByteArray(0)
    }

    companion object {
        /** The parts of one message come within a second. After this time the message is given up. */
        const val MAX_MESSAGE_MILLIS = 10_000L
    }
}
