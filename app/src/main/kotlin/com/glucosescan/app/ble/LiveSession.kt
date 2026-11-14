package com.glucosescan.app.ble

import com.glucosescan.app.core.LiveStatus
import com.glucosescan.app.data.LiveSensorRecord
import com.glucosescan.app.data.LiveState
import com.glucosescan.app.libre.Libre2Ble

/**
 * Keeps the Bluetooth connection to the sensor alive: finds the sensor, connects, unlocks,
 * listens, and starts again when the connection is lost.
 *
 * The steps are those of the original app (Glucose Direct, Libre2Connection.swift): after
 * connecting, the unlock bytes are written; when the sensor has taken them, the app asks for
 * notifications; then the sensor sends one message a minute.
 *
 * There is no Android code in this class. Bluetooth, time and storage come in from outside
 * ([BleLink], [SessionTimer], [SessionHost]), so the whole course can be run in a test.
 * All functions must be called on the same thread.
 */
class LiveSession(
    private val link: BleLink,
    private val timer: SessionTimer,
    private val host: SessionHost
) : BleEvents {

    private enum class Step {
        /** Nothing is going on (off, or something is missing). */
        IDLE,

        /** Looking for the sensor. */
        SCANNING,

        /** Trying to connect now. */
        CONNECTING,

        /** Waiting, with little power, for the sensor to come near. */
        WAITING_FOR_SENSOR,
        DISCOVERING,
        UNLOCKING,
        SUBSCRIBING,

        /** Connected and waiting for messages. */
        LISTENING,

        /** A pause before the next try. */
        PAUSE
    }

    private var step = Step.IDLE
    private var record: LiveSensorRecord? = null
    private val assembler = PacketAssembler()

    /** Tries in a row that did not end in a good message. Makes the pauses longer. */
    private var failures = 0

    /** Times in a row that the sensor took the connection but then gave nothing. */
    private var rejections = 0

    /** Messages in a row that could not be read. */
    private var badMessages = 0

    private var gotMessageInThisConnection = false
    private var scannedInThisRound = false

    var state: LiveState = LiveState.OFF
        private set

    /** The sensor the session works with, or null. */
    val sensorPairedTime: Long
        get() = record?.pairedTime ?: 0L

    /** True while the session tries to reach the sensor or is connected. */
    val isActive: Boolean
        get() = step != Step.IDLE

    /** Starts from the beginning: reads which sensor is set up and goes for it. */
    fun start() {
        shutDown()
        failures = 0
        rejections = 0
        badMessages = 0

        val sensor = host.sensor()
        record = sensor
        if (sensor == null) {
            setState(LiveState.NEEDS_SCAN)
            return
        }
        when (link.problem()) {
            LinkProblem.NO_PERMISSION -> {
                setState(LiveState.NO_PERMISSION)
                return
            }
            LinkProblem.BLUETOOTH_OFF -> {
                setState(LiveState.BLUETOOTH_OFF)
                return
            }
            null -> Unit
        }
        host.log("start for sensor ${sensor.serial ?: "?"}")
        beginRound()
    }

    /** Ends everything. */
    fun stop() {
        shutDown()
        record = null
        setState(LiveState.OFF)
    }

    private fun shutDown() {
        timer.cancel()
        if (step == Step.SCANNING) {
            link.stopScan()
        }
        link.close()
        assembler.reset()
        step = Step.IDLE
    }

    private fun setState(newState: LiveState) {
        if (state != newState) {
            state = newState
            host.onState(newState)
        }
    }

    // ------------------------------------------------------------------ finding and connecting

    /** One round: connect to the known address, else look for the sensor, else wait for it. */
    private fun beginRound() {
        scannedInThisRound = false
        val address = host.savedAddress()
        if (address != null) {
            connectNow(address)
        } else {
            scan()
        }
    }

    private fun scan() {
        val sensor = record ?: return
        scannedInThisRound = true
        step = Step.SCANNING
        setState(LiveState.SEARCHING)
        link.startScan(sensor)
        timer.set(SCAN_MILLIS)
    }

    private fun connectNow(address: String) {
        step = Step.CONNECTING
        setState(LiveState.SEARCHING)
        gotMessageInThisConnection = false
        link.connect(address, wait = false)
        timer.set(CONNECT_MILLIS)
    }

    /** Waits for the sensor at the address that is known or that the sensor gave over NFC. */
    private fun waitForSensor() {
        val address = host.savedAddress() ?: record?.addressFromAnswer
        if (address == null) {
            pause("no Bluetooth address known")
            return
        }
        step = Step.WAITING_FOR_SENSOR
        setState(LiveState.SEARCHING)
        gotMessageInThisConnection = false
        link.connect(address, wait = true)
        timer.set(WAIT_MILLIS)
    }

    override fun onFound(address: String, name: String?, manufacturerData: ByteArray?) {
        val sensor = record ?: return
        if (step != Step.SCANNING || !isOurSensor(sensor, address, name, manufacturerData)) {
            return
        }
        host.log("found the sensor at $address")
        link.stopScan()
        host.saveAddress(address)
        connectNow(address)
    }

    override fun onScanFailed(code: Int) {
        if (step != Step.SCANNING) {
            return
        }
        host.log("search failed (code $code)")
        link.stopScan()
        waitForSensor()
    }

    override fun onConnected() {
        if (step != Step.CONNECTING && step != Step.WAITING_FOR_SENSOR) {
            return
        }
        step = Step.DISCOVERING
        setState(LiveState.CONNECTING)
        timer.set(STEP_MILLIS)
        if (!link.discoverServices()) {
            fail("could not ask for the services")
        }
    }

    override fun onServicesDiscovered(ok: Boolean) {
        if (step != Step.DISCOVERING) {
            return
        }
        val sensor = record
        if (!ok || sensor == null) {
            fail("the device does not offer the Libre 2 service")
            return
        }
        val count = host.nextUnlockCount()
        if (count > Libre2Ble.MAX_UNLOCK_COUNT) {
            reject("the unlock count is used up")
            return
        }
        val payload = Libre2Ble.streamingUnlockPayload(sensor.uid, sensor.patchInfo, Libre2Ble.UNLOCK_CODE, count)
        step = Step.UNLOCKING
        timer.set(STEP_MILLIS)
        if (!link.writeUnlock(payload)) {
            fail("could not write the unlock bytes")
        }
    }

    override fun onUnlockWritten(ok: Boolean) {
        if (step != Step.UNLOCKING) {
            return
        }
        if (!ok) {
            countRejection("the sensor did not take the unlock bytes")
            return
        }
        step = Step.SUBSCRIBING
        timer.set(STEP_MILLIS)
        if (!link.enableNotifications()) {
            fail("could not ask for notifications")
        }
    }

    override fun onNotificationsEnabled(ok: Boolean) {
        if (step != Step.SUBSCRIBING) {
            return
        }
        if (!ok) {
            countRejection("the sensor refused notifications")
            return
        }
        step = Step.LISTENING
        assembler.reset()
        badMessages = 0
        setState(LiveState.CONNECTED)
        host.log("connected, waiting for values")
        timer.set(DATA_MILLIS)
    }

    // ------------------------------------------------------------------ listening

    override fun onPacket(bytes: ByteArray) {
        if (step != Step.LISTENING) {
            return
        }
        val message = assembler.add(bytes, host.now()) ?: return
        when (host.onMessage(message)) {
            LiveStatus.OK -> good(LiveState.RECEIVING)
            LiveStatus.STARTING -> good(LiveState.WARMING_UP)
            LiveStatus.EXPIRED -> {
                host.log("the sensor has ended")
                shutDown()
                setState(LiveState.SENSOR_ENDED)
            }
            LiveStatus.BAD_DATA -> {
                badMessages++
                host.log("a message could not be read ($badMessages in a row)")
                if (badMessages >= MAX_BAD_MESSAGES) {
                    fail("messages cannot be read")
                }
            }
        }
    }

    private fun good(newState: LiveState) {
        gotMessageInThisConnection = true
        failures = 0
        rejections = 0
        badMessages = 0
        setState(newState)
        timer.set(DATA_MILLIS)
    }

    override fun onDisconnected(status: Int) {
        when (step) {
            Step.IDLE, Step.SCANNING, Step.PAUSE -> return
            Step.CONNECTING, Step.WAITING_FOR_SENSOR -> {
                // the try to connect failed
                link.close()
                afterFailedConnect("connect failed (status $status)")
            }
            Step.DISCOVERING -> fail("connection lost while setting up (status $status)")
            Step.UNLOCKING, Step.SUBSCRIBING -> countRejection("the sensor closed the connection after the unlock bytes (status $status)")
            Step.LISTENING -> {
                if (gotMessageInThisConnection) {
                    // the usual end: the phone went out of reach. Try again at once.
                    host.log("connection lost (status $status)")
                    link.close()
                    assembler.reset()
                    failures = 0
                    setState(LiveState.SEARCHING)
                    step = Step.PAUSE
                    timer.set(RECONNECT_MILLIS)
                } else {
                    countRejection("the sensor closed the connection without a value (status $status)")
                }
            }
        }
    }

    /** The one timer ran out. */
    fun onTimer() {
        when (step) {
            Step.IDLE -> Unit
            Step.SCANNING -> {
                link.stopScan()
                host.log("sensor not found by searching")
                waitForSensor()
            }
            Step.CONNECTING -> {
                link.close()
                afterFailedConnect("no connection within ${CONNECT_MILLIS / 1000} s")
            }
            Step.WAITING_FOR_SENSOR -> {
                // look for it again; maybe it shows up under another address
                link.close()
                scan()
            }
            Step.DISCOVERING, Step.UNLOCKING, Step.SUBSCRIBING -> fail("no answer from the sensor")
            Step.LISTENING -> {
                if (gotMessageInThisConnection) {
                    fail("no value for ${DATA_MILLIS / 1000} s")
                } else {
                    countRejection("connected, but the sensor sent nothing")
                }
            }
            Step.PAUSE -> beginRound()
        }
    }

    private fun afterFailedConnect(reason: String) {
        host.log(reason)
        if (!scannedInThisRound) {
            scan()
        } else {
            waitForSensor()
        }
    }

    /** Something went wrong: close, wait a little (longer each time) and start a new round. */
    private fun fail(reason: String) {
        host.log(reason)
        pause(null)
    }

    private fun pause(reason: String?) {
        if (reason != null) {
            host.log(reason)
        }
        if (step == Step.SCANNING) {
            link.stopScan()
        }
        link.close()
        assembler.reset()
        failures++
        step = Step.PAUSE
        setState(LiveState.SEARCHING)
        timer.set(pauseMillis(failures))
    }

    /**
     * The sensor let the phone connect but gave no value. Once can be bad luck. Several times
     * in a row means that the sensor does not take the unlock bytes of this phone any more,
     * most often because another app has scanned it since. Then only a new scan helps.
     */
    private fun countRejection(reason: String) {
        rejections++
        if (rejections >= MAX_REJECTIONS) {
            reject(reason)
        } else {
            fail("$reason ($rejections of $MAX_REJECTIONS)")
        }
    }

    private fun reject(reason: String) {
        host.log("$reason: a scan is needed")
        shutDown()
        host.onRejected()
        setState(LiveState.REJECTED)
    }

    companion object {
        /** How long one search for the sensor lasts. */
        const val SCAN_MILLIS = 30_000L

        /** How long a try to connect may take. */
        const val CONNECT_MILLIS = 30_000L

        /** How long the phone waits for the sensor to come near before it searches again. */
        const val WAIT_MILLIS = 4 * 60_000L

        /** How long one step of setting up a connection may take. */
        const val STEP_MILLIS = 20_000L

        /** The sensor sends once a minute. Without a message for this long the connection is made anew. */
        const val DATA_MILLIS = 150_000L

        /** The pause before connecting again after the connection was lost. */
        const val RECONNECT_MILLIS = 1_000L

        const val MAX_REJECTIONS = 5
        const val MAX_BAD_MESSAGES = 5

        /** 5 s, 10 s, 20 s ... at most 2 minutes. Keeps a broken connection from using up the unlock numbers. */
        fun pauseMillis(failures: Int): Long {
            val doubling = Math.min(Math.max(failures, 1) - 1, 5)
            return Math.min(5_000L shl doubling, 120_000L)
        }

        /**
         * True if what was found is the sensor of [sensor]: its advertisement carries the first
         * 6 bytes of its id (as in the original app), or its name or its address fits.
         */
        fun isOurSensor(sensor: LiveSensorRecord, address: String, name: String?, manufacturerData: ByteArray?): Boolean {
            if (manufacturerData != null) {
                for (offset in intArrayOf(0, 2)) { // without or with the two bytes of the maker's number
                    if (manufacturerData.size >= offset + 6) {
                        var same = true
                        for (k in 0 until 6) {
                            if (manufacturerData[offset + k] != sensor.uid[k]) {
                                same = false
                                break
                            }
                        }
                        if (same) {
                            return true
                        }
                    }
                }
            }
            val expectedName = sensor.bluetoothName
            if (name != null && expectedName != null && name.trim().equals(expectedName, ignoreCase = true)) {
                return true
            }
            val expectedAddress = sensor.addressFromAnswer
            return expectedAddress != null && expectedAddress.equals(address, ignoreCase = true)
        }
    }
}
