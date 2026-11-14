package com.glucosescan.app.ble

import com.glucosescan.app.core.LiveProcessor
import com.glucosescan.app.core.LiveStatus
import com.glucosescan.app.data.LiveSensorRecord
import com.glucosescan.app.data.LiveState
import com.glucosescan.app.libre.FactoryCalibration
import com.glucosescan.app.libre.Libre2Ble
import com.glucosescan.app.libre.SensorInfo
import com.glucosescan.app.testing.BleMessages
import com.glucosescan.app.testing.TestSensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the whole course of a live connection against a pretended sensor: no phone, no
 * Bluetooth. The pretended sensor behaves like the original app expects of a Libre 2: it takes
 * the unlock bytes only if they are made for it and carry a count higher than the last one.
 */
class LiveSessionTest {
    private val sample = TestSensors.example
    private val noon = 1_767_268_800_000L
    private val sensorAddress = "F6:E5:D4:C3:B2:A1"

    private class FakeLink : BleLink {
        var problem: LinkProblem? = null
        val calls = ArrayList<String>()
        var scanning = false
        var target: String? = null
        var waits = false
        var payload: ByteArray? = null
        var discoverWorks = true

        override fun problem(): LinkProblem? = problem

        override fun startScan(sensor: LiveSensorRecord) {
            calls.add("scan")
            scanning = true
        }

        override fun stopScan() {
            scanning = false
        }

        override fun connect(address: String, wait: Boolean) {
            calls.add("connect $address " + if (wait) "wait" else "now")
            target = address
            waits = wait
        }

        override fun discoverServices(): Boolean {
            calls.add("discover")
            return discoverWorks
        }

        override fun writeUnlock(payload: ByteArray): Boolean {
            calls.add("unlock")
            this.payload = payload
            return true
        }

        override fun enableNotifications(): Boolean {
            calls.add("notify")
            return true
        }

        override fun close() {
            target = null
        }
    }

    private class FakeTimer : SessionTimer {
        var delay: Long? = null

        override fun set(delayMillis: Long) {
            delay = delayMillis
        }

        override fun cancel() {
            delay = null
        }
    }

    private inner class FakeHost : SessionHost {
        var time = noon
        var record: LiveSensorRecord? = null
        var address: String? = null
        var count = 0
        val states = ArrayList<LiveState>()
        val messages = ArrayList<ByteArray>()
        val lines = ArrayList<String>()
        var rejected = 0

        override fun now(): Long = time

        override fun sensor(): LiveSensorRecord? = record

        override fun savedAddress(): String? = address

        override fun saveAddress(address: String) {
            this.address = address
        }

        override fun nextUnlockCount(): Int {
            count++
            return count
        }

        override fun onMessage(message: ByteArray): LiveStatus {
            messages.add(message)
            return LiveProcessor.process(record!!.toLiveSensor(), message, time).status
        }

        override fun onState(state: LiveState) {
            states.add(state)
        }

        override fun onRejected() {
            rejected++
        }

        override fun log(text: String) {
            lines.add(text)
        }
    }

    private lateinit var link: FakeLink
    private lateinit var timer: FakeTimer
    private lateinit var host: FakeHost
    private lateinit var session: LiveSession

    /** The highest unlock count the pretended sensor has taken. */
    private var sensorCount = 0

    /** False: the sensor was taken over by another app and refuses this phone. */
    private var sensorIsOurs = true

    @Before
    fun setUp() {
        link = FakeLink()
        timer = FakeTimer()
        host = FakeHost()
        session = LiveSession(link, timer, host)
        sensorCount = 0
        sensorIsOurs = true
    }

    private fun record(lifetime: Int = 20659): LiveSensorRecord {
        val info = SensorInfo.libreStyleSensor(sample.uid, sample.patchInfo, sample.plain)
        return LiveSensorRecord(
            serial = info.serial,
            uid = sample.uid.copyOf(),
            patchInfo = sample.patchInfo.copyOf(),
            calibration = FactoryCalibration.libreStyleCalibration(sample.plain),
            lifetime = lifetime,
            startTime = noon - 10_320 * 60_000L,
            streamingAnswer = byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte()),
            pairedTime = noon - 60_000L
        )
    }

    /** The advertisement of the sensor: the first 6 bytes of its id. */
    private fun advertise(address: String = sensorAddress) {
        session.onFound(address, null, sample.uid.copyOfRange(0, 6))
    }

    /** What the pretended sensor does with the unlock bytes the app wrote. */
    private fun sensorTakesUnlock(): Boolean {
        val payload = link.payload ?: return false
        val expected = Libre2Ble.streamingUnlockPayload(sample.uid, sample.patchInfo, Libre2Ble.UNLOCK_CODE, host.count)
        if (!sensorIsOurs || !payload.contentEquals(expected) || host.count <= sensorCount) {
            return false
        }
        sensorCount = host.count
        return true
    }

    /** Runs the steps from "connected" to "listening". Returns false if the sensor closed the connection. */
    private fun setUpConnection(): Boolean {
        session.onConnected()
        session.onServicesDiscovered(true)
        session.onUnlockWritten(true) // the write itself always goes through
        if (!sensorTakesUnlock()) {
            session.onDisconnected(19) // a sensor that does not like the bytes hangs up
            return false
        }
        session.onNotificationsEnabled(true)
        return true
    }

    private fun send(message: ByteArray) {
        for (part in BleMessages.packets(message)) {
            session.onPacket(part)
        }
    }

    private fun connectByScan() {
        host.record = record()
        session.start()
        advertise()
        assertTrue(setUpConnection())
    }

    // ------------------------------------------------------------------ the good way

    @Test
    fun withoutASensorAScanIsNeeded() {
        session.start()
        assertEquals(LiveState.NEEDS_SCAN, session.state)
        assertFalse(session.isActive)
        assertNull(timer.delay)
        assertTrue(link.calls.isEmpty())
    }

    @Test
    fun findsConnectsUnlocksAndListens() {
        host.record = record()
        session.start()
        assertEquals(LiveState.SEARCHING, session.state)
        assertTrue(link.scanning)
        assertEquals(LiveSession.SCAN_MILLIS, timer.delay)
        assertEquals(host.record!!.pairedTime, session.sensorPairedTime)

        // another device nearby is not taken
        session.onFound("11:22:33:44:55:66", "Headphones", byteArrayOf(1, 2, 3, 4, 5, 6))
        assertTrue(link.scanning)

        advertise()
        assertFalse(link.scanning)
        assertEquals(sensorAddress, host.address)
        assertEquals(sensorAddress, link.target)
        assertFalse(link.waits)

        assertTrue(setUpConnection())
        assertEquals(listOf("scan", "connect $sensorAddress now", "discover", "unlock", "notify"), link.calls)
        assertEquals(1, host.count)
        assertEquals(LiveState.CONNECTED, session.state)
        assertEquals(LiveSession.DATA_MILLIS, timer.delay)

        send(BleMessages.steady(sample, 10_320, 104))
        assertEquals(1, host.messages.size)
        assertEquals(LiveState.RECEIVING, session.state)
        assertEquals(listOf(LiveState.SEARCHING, LiveState.CONNECTING, LiveState.CONNECTED, LiveState.RECEIVING), host.states)

        host.time += 60_000L
        send(BleMessages.steady(sample, 10_321, 105, header = 0x1234))
        assertEquals(2, host.messages.size)
        assertEquals(0, host.rejected)
    }

    @Test
    fun aKnownAddressIsUsedWithoutSearching() {
        host.record = record()
        host.address = sensorAddress
        session.start()
        assertEquals(listOf("connect $sensorAddress now"), link.calls)
        assertEquals(LiveSession.CONNECT_MILLIS, timer.delay)
        assertTrue(setUpConnection())
        assertEquals(LiveState.CONNECTED, session.state)
    }

    @Test
    fun theSensorIsKnownByItsNameOrByTheAddressOfTheScanToo() {
        val sensor = record()
        assertTrue(LiveSession.isOurSensor(sensor, "00:00:00:00:00:01", "ABBOTT" + sensor.serial, null))
        assertTrue(LiveSession.isOurSensor(sensor, "00:00:00:00:00:01", " abbott" + sensor.serial!!.lowercase() + " ", null))
        assertTrue(LiveSession.isOurSensor(sensor, sensorAddress.lowercase(), null, null))
        assertTrue("with the two bytes of the maker in front", LiveSession.isOurSensor(sensor, "00:00:00:00:00:01", null, byteArrayOf(0x02, 0x1A) + sample.uid.copyOfRange(0, 6)))
        assertFalse(LiveSession.isOurSensor(sensor, "00:00:00:00:00:01", "ABBOTT3MH000000XX", byteArrayOf(9, 9, 9, 9, 9, 9)))
        assertFalse(LiveSession.isOurSensor(sensor, "00:00:00:00:00:01", null, null))
        assertEquals(sensorAddress, sensor.addressFromAnswer)
    }

    // ------------------------------------------------------------------ losing and finding again

    @Test
    fun aLostConnectionIsMadeAgainAtOnce() {
        connectByScan()
        send(BleMessages.steady(sample, 10_320, 104))
        link.calls.clear()

        session.onDisconnected(8) // out of reach
        assertEquals(LiveState.SEARCHING, session.state)
        assertEquals(LiveSession.RECONNECT_MILLIS, timer.delay)
        session.onTimer()
        assertEquals(listOf("connect $sensorAddress now"), link.calls)

        // not in reach yet: search, then wait for it with little power
        session.onTimer()
        assertTrue(link.scanning)
        session.onTimer()
        assertFalse(link.scanning)
        assertEquals("connect $sensorAddress wait", link.calls.last())
        assertEquals(LiveSession.WAIT_MILLIS, timer.delay)

        // the user comes back
        assertTrue(setUpConnection())
        assertEquals(2, host.count)
        host.time += 30 * 60_000L
        send(BleMessages.steady(sample, 10_350, 110))
        assertEquals(LiveState.RECEIVING, session.state)
        assertEquals(0, host.rejected)
    }

    @Test
    fun withoutAnAnswerTheSearchGoesOnInRounds() {
        host.record = record()
        session.start()
        session.onTimer() // search ended without the sensor
        assertEquals("the address the sensor gave during the scan", "connect $sensorAddress wait", link.calls.last())
        session.onTimer() // waited 4 minutes
        assertTrue(link.scanning)
        assertEquals(LiveSession.SCAN_MILLIS, timer.delay)
        session.onScanFailed(2)
        assertFalse(link.scanning)
        assertEquals("connect $sensorAddress wait", link.calls.last())
        assertEquals(LiveState.SEARCHING, session.state)
        assertEquals(0, host.count)
    }

    @Test
    fun noValueForTooLongMakesANewConnection() {
        connectByScan()
        send(BleMessages.steady(sample, 10_320, 104))
        session.onTimer() // 150 seconds without a message
        assertEquals(LiveState.SEARCHING, session.state)
        assertEquals("the first pause is short", 5_000L, timer.delay)
        session.onTimer()
        assertEquals("connect $sensorAddress now", link.calls.last())
    }

    @Test
    fun pausesGetLongerUpToTwoMinutes() {
        assertEquals(listOf(5_000L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L), (0..7).map { LiveSession.pauseMillis(it) })

        host.record = record()
        host.address = sensorAddress
        link.discoverWorks = false // something is wrong with this phone's Bluetooth
        session.start()
        val pauses = ArrayList<Long>()
        repeat(4) {
            session.onConnected()
            pauses.add(timer.delay!!)
            session.onTimer()
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L), pauses)
        assertEquals("no unlock count is used up by this", 0, host.count)
        assertEquals(0, host.rejected)
    }

    // ------------------------------------------------------------------ refused

    @Test
    fun aSensorThatAnotherAppTookOverIsGivenUpAfterFiveTries() {
        host.record = record()
        host.address = sensorAddress
        sensorIsOurs = false
        session.start()
        for (attempt in 1..LiveSession.MAX_REJECTIONS) {
            assertEquals("try $attempt", "connect $sensorAddress now", link.calls.last())
            assertFalse(setUpConnection())
            if (attempt < LiveSession.MAX_REJECTIONS) {
                assertEquals(LiveState.SEARCHING, session.state)
                assertEquals(0, host.rejected)
                session.onTimer() // the pause is over
            }
        }
        assertEquals(LiveState.REJECTED, session.state)
        assertEquals(1, host.rejected)
        assertFalse(session.isActive)
        assertNull("no more tries until a scan", timer.delay)
        assertEquals(5, host.count)
        assertTrue(host.lines.last().contains("a scan is needed"))
    }

    @Test
    fun oneBadTryIsForgottenAfterAGoodMessage() {
        host.record = record()
        host.address = sensorAddress
        session.start()
        repeat(3) {
            sensorIsOurs = false
            assertFalse(setUpConnection())
            session.onTimer()
        }
        sensorIsOurs = true
        assertTrue(setUpConnection())
        send(BleMessages.steady(sample, 10_320, 104))
        assertEquals(LiveState.RECEIVING, session.state)

        // four more bad tries later it is still not given up: the count started anew
        session.onDisconnected(8)
        session.onTimer()
        repeat(4) {
            sensorIsOurs = false
            assertFalse(setUpConnection())
            session.onTimer()
        }
        assertEquals(0, host.rejected)
        assertEquals(LiveState.SEARCHING, session.state)
    }

    @Test
    fun aConnectionWithoutAnyMessageCountsAsRefused() {
        connectByScan()
        session.onTimer() // connected, but nothing came for 150 seconds
        assertEquals(LiveState.SEARCHING, session.state)
        assertTrue(host.lines.last().contains("1 of 5"))
    }

    @Test
    fun messagesOfAnotherSensorAreNotTaken() {
        connectByScan()
        val other = TestSensors.example2
        repeat(LiveSession.MAX_BAD_MESSAGES - 1) {
            send(BleMessages.steady(other, 3_170, 104))
            assertEquals(LiveState.CONNECTED, session.state)
        }
        send(BleMessages.steady(other, 3_170, 104))
        assertEquals("after 5 unreadable messages the connection is made anew", LiveState.SEARCHING, session.state)
        assertEquals(5, host.messages.size)
    }

    // ------------------------------------------------------------------ sensor states and phone states

    @Test
    fun aWarmingUpSensorKeepsTheConnection() {
        connectByScan()
        send(BleMessages.steady(sample, 30, 104))
        assertEquals(LiveState.WARMING_UP, session.state)
        assertTrue(session.isActive)
        assertEquals(LiveSession.DATA_MILLIS, timer.delay)
    }

    @Test
    fun anEndedSensorStopsTheSession() {
        connectByScan()
        send(BleMessages.steady(sample, 20_659, 104))
        assertEquals(LiveState.SENSOR_ENDED, session.state)
        assertFalse(session.isActive)
        assertNull(timer.delay)
        assertNull(link.target)
        assertEquals(0, host.rejected)
    }

    @Test
    fun bluetoothOffOrNotAllowed() {
        host.record = record()
        link.problem = LinkProblem.BLUETOOTH_OFF
        session.start()
        assertEquals(LiveState.BLUETOOTH_OFF, session.state)
        assertFalse(session.isActive)

        link.problem = LinkProblem.NO_PERMISSION
        session.start()
        assertEquals(LiveState.NO_PERMISSION, session.state)
        assertTrue(link.calls.isEmpty())

        link.problem = null
        session.start()
        assertEquals(LiveState.SEARCHING, session.state)
        assertTrue(link.scanning)
    }

    @Test
    fun stopEndsEverything() {
        connectByScan()
        send(BleMessages.steady(sample, 10_320, 104))
        session.stop()
        assertEquals(LiveState.OFF, session.state)
        assertNull(timer.delay)
        assertNull(link.target)
        assertEquals(0L, session.sensorPairedTime)

        // late calls of the phone's Bluetooth change nothing
        val before = link.calls.size
        session.onDisconnected(0)
        session.onConnected()
        send(BleMessages.steady(sample, 10_321, 104))
        session.onTimer()
        assertEquals(before, link.calls.size)
        assertEquals(1, host.messages.size)
        assertEquals(LiveState.OFF, session.state)
    }

    @Test
    fun theUnlockCountThatIsUsedUpNeedsANewScan() {
        host.record = record()
        host.address = sensorAddress
        host.count = Libre2Ble.MAX_UNLOCK_COUNT
        session.start()
        session.onConnected()
        session.onServicesDiscovered(true)
        assertEquals(LiveState.REJECTED, session.state)
        assertEquals(1, host.rejected)
        assertFalse(link.calls.contains("unlock"))
    }
}
