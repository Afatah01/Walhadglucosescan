package com.glucosescan.app.ble

import com.glucosescan.app.core.LiveStatus
import com.glucosescan.app.data.LiveSensorRecord
import com.glucosescan.app.data.LiveState

/** Why Bluetooth cannot be used right now. */
enum class LinkProblem {
    /** The user has not allowed the app to use Bluetooth. */
    NO_PERMISSION,

    /** Bluetooth is switched off, or the phone has none. */
    BLUETOOTH_OFF
}

/**
 * What a [LiveSession] asks of the phone's Bluetooth. The real one is AndroidBleLink. Tests use
 * a fake one, so that every step of a connection can be tried without a phone.
 *
 * Every call returns at once. What comes of it is told later through [BleEvents].
 */
interface BleLink {
    /** Null if Bluetooth can be used. */
    fun problem(): LinkProblem?

    /** Starts looking for sensors nearby. Each one found is told with [BleEvents.onFound]. */
    fun startScan(sensor: LiveSensorRecord)

    fun stopScan()

    /**
     * Connects to the device with this Bluetooth address.
     * @param wait false: try now and give up after some seconds. true: wait, for as long as it
     * takes, until the device comes near (needs little power).
     */
    fun connect(address: String, wait: Boolean)

    /** Asks the connected device what it offers. Answer: [BleEvents.onServicesDiscovered]. */
    fun discoverServices(): Boolean

    /** Writes the unlock bytes to the sensor. Answer: [BleEvents.onUnlockWritten]. */
    fun writeUnlock(payload: ByteArray): Boolean

    /** Asks the sensor to send its values. Answer: [BleEvents.onNotificationsEnabled]. */
    fun enableNotifications(): Boolean

    /** Ends the connection (or the try to connect) and frees everything. */
    fun close()
}

/** What Bluetooth tells a [LiveSession]. All calls come on the same thread. */
interface BleEvents {
    /**
     * @param manufacturerData the maker's bytes of the advertisement, without the two bytes of the maker's number
     */
    fun onFound(address: String, name: String?, manufacturerData: ByteArray?)

    fun onScanFailed(code: Int)

    fun onConnected()

    /** The connection ended, or a try to connect failed. */
    fun onDisconnected(status: Int)

    /** @param ok true if the sensor offers what the app needs */
    fun onServicesDiscovered(ok: Boolean)

    fun onUnlockWritten(ok: Boolean)

    fun onNotificationsEnabled(ok: Boolean)

    /** One part of a message came in. */
    fun onPacket(bytes: ByteArray)
}

/** One timer for a [LiveSession]. When it runs out, [LiveSession.onTimer] must be called. */
interface SessionTimer {
    /** Starts the timer. A timer that is running is replaced. */
    fun set(delayMillis: Long)

    fun cancel()
}

/** What a [LiveSession] needs from the app around it. */
interface SessionHost {
    /** Milliseconds since 1970. */
    fun now(): Long

    /** The sensor that was set up for live values by a scan, or null. */
    fun sensor(): LiveSensorRecord?

    /** The Bluetooth address under which the sensor was found before, or null. */
    fun savedAddress(): String?

    fun saveAddress(address: String)

    /** Counts the unlock number up by one, saves it and returns it. */
    fun nextUnlockCount(): Int

    /** A whole message (46 bytes) came in. Decodes and saves it. */
    fun onMessage(message: ByteArray): LiveStatus

    /** The session does something else now. */
    fun onState(state: LiveState)

    /** The sensor does not take this phone any more: the next scan must set it up again. */
    fun onRejected()

    /** A text line for the technical log. */
    fun log(text: String)
}
