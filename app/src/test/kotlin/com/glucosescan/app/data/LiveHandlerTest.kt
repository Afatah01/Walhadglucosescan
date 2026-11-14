package com.glucosescan.app.data

import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.LiveStatus
import com.glucosescan.app.libre.FactoryCalibration
import com.glucosescan.app.libre.SensorInfo
import com.glucosescan.app.nfc.FakeLibre2Tag
import com.glucosescan.app.nfc.LibreNfcReader
import com.glucosescan.app.nfc.SensorScanner
import com.glucosescan.app.testing.BleMessages
import com.glucosescan.app.testing.TestSensors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** From a Bluetooth message to the saved values, and how scans and live values go together. */
@RunWith(RobolectricTestRunner::class)
class LiveHandlerTest {
    private val minute = 60_000L
    private val noon = 1_767_268_800_000L
    private val sample = TestSensors.example
    private lateinit var store: GlucoseStore
    private lateinit var settings: Settings
    private lateinit var handler: LiveHandler

    @Before
    fun setUp() {
        store = GlucoseStore(RuntimeEnvironment.getApplication(), null)
        settings = Settings(RuntimeEnvironment.getApplication())
        handler = LiveHandler(store, settings)
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun setUpSensor(sample: TestSensors.Sample = this.sample, lifetime: Int = 20659) {
        val info = SensorInfo.libreStyleSensor(sample.uid, sample.patchInfo, sample.plain)
        settings.liveSensor = LiveSensorRecord(
            info.serial, sample.uid, sample.patchInfo, FactoryCalibration.libreStyleCalibration(sample.plain), lifetime,
            noon - 10_320 * minute, byteArrayOf(1, 2, 3, 4, 5, 6), noon - minute
        )
    }

    private fun minutesBefore(time: Long, values: List<Glucose>): List<Int> = values.map { ((time - it.timestamp) / minute).toInt() }

    @Test
    fun withoutASensorNothingIsTaken() {
        val outcome = handler.handle(BleMessages.steady(sample, 10_320, 104), noon)
        assertEquals(LiveStatus.BAD_DATA, outcome.status)
        assertEquals(0, store.count())
    }

    @Test
    fun theFirstMessageSavesSevenValues() {
        setUpSensor()
        val outcome = handler.handle(BleMessages.message(sample, 10_320, listOf(104, 103, 101, 100, 99, 95, 92)), noon + 20_000)
        assertEquals(LiveStatus.OK, outcome.status)
        assertEquals(10_320, outcome.age)
        assertEquals(7, outcome.saved)
        assertFalse(outcome.tooNoisy)
        val all = store.since(0)
        assertEquals(listOf(15, 12, 7, 6, 4, 2, 0), minutesBefore(noon, all))
        assertEquals(listOf(92, 95, 99, 100, 101, 103, 104), all.map { it.value })
        assertTrue(all.all { it.fromTrend && it.sensorSerial == "3MH001HM1LM" })
        assertEquals(noon + 20_000, settings.liveLastMessageTime)
    }

    @Test
    fun eachMinuteAddsANewValueAndFillsTheMinutesBetween() {
        setUpSensor()
        handler.handle(BleMessages.steady(sample, 10_320, 104), noon)
        val second = handler.handle(BleMessages.message(sample, 10_321, listOf(106, 105, 105, 105, 105, 105, 105), header = 0x1111), noon + minute)
        // new: the minute of the message. Holes: 1, 3, 5, 11 and 14 minutes before noon, which
        // the first message did not hold. The minute 6 before noon was there already.
        assertEquals(6, second.saved)
        assertEquals(listOf(15, 14, 12, 11, 7, 6, 5, 4, 3, 2, 1, 0, -1), minutesBefore(noon, store.since(0)))
        assertEquals("what was there stays", 104, store.since(0).first { it.timestamp == noon - 6 * minute }.value)
        assertEquals(105, store.since(0).first { it.timestamp == noon - 5 * minute }.value)
        assertEquals(106, store.latest()!!.value)
        assertEquals(noon + minute, store.latest()!!.timestamp)
        // the change is counted from the value before, as after a scan
        assertEquals(2.0, store.latest()!!.minuteChange!!, 0.0)

        // the same message twice changes nothing
        val again = handler.handle(BleMessages.message(sample, 10_321, listOf(106, 105, 105, 105, 105, 105, 105), header = 0x1111), noon + minute + 5_000)
        assertEquals(LiveStatus.OK, again.status)
        assertEquals(0, again.saved)
        assertEquals(13, store.count())
    }

    @Test
    fun aShortBreakIsFilledFromTheNextMessage() {
        setUpSensor()
        handler.handle(BleMessages.steady(sample, 10_320, 104), noon)
        assertEquals(7, store.count())

        // five minutes without a connection; the next message also holds the minutes 2 and 4
        // before it (noon + 3 and noon + 1), which were never seen
        val outcome = handler.handle(BleMessages.message(sample, 10_325, listOf(110, 108, 106, 104, 104, 104, 104), header = 0x2222), noon + 5 * minute)
        assertEquals(LiveStatus.OK, outcome.status)
        val times = store.since(noon + 1).map { ((it.timestamp - noon) / minute).toInt() }
        assertEquals(listOf(1, 3, 5), times)
        assertEquals(listOf(106, 108, 110), store.since(noon + 1).map { it.value })
        // and two older minutes that the first message did not hold: 1 and 10 minutes before noon
        assertEquals(5, outcome.saved)
        assertEquals(listOf(15, 12, 10, 7, 6, 4, 2, 1, 0, -1, -3, -5), minutesBefore(noon, store.since(0)))
    }

    @Test
    fun aMessageOfAnotherSensorOrADamagedOneIsThrownAway() {
        setUpSensor()
        val other = handler.handle(BleMessages.steady(TestSensors.example2, 3_170, 104), noon)
        assertEquals(LiveStatus.BAD_DATA, other.status)
        val damaged = BleMessages.steady(sample, 10_320, 104)
        damaged[20] = (damaged[20].toInt() xor 0x40).toByte()
        assertEquals(LiveStatus.BAD_DATA, handler.handle(damaged, noon).status)
        assertEquals(LiveStatus.BAD_DATA, handler.handle(ByteArray(20), noon).status)
        assertEquals(0, store.count())
        assertEquals("a bad message is no sign of life", 0L, settings.liveLastMessageTime)
    }

    @Test
    fun aSensorThatWarmsUpOrHasEndedGivesNoValues() {
        setUpSensor()
        val warming = handler.handle(BleMessages.steady(sample, 30, 104), noon)
        assertEquals(LiveStatus.STARTING, warming.status)
        assertEquals("the alarm \"no values\" starts to wait anew", noon, settings.liveWaitingSince)
        val ended = handler.handle(BleMessages.steady(sample, 20_659, 104), noon + minute)
        assertEquals(LiveStatus.EXPIRED, ended.status)
        assertEquals(0, store.count())
    }

    @Test
    fun valuesThatTheSensorDoesNotHaveAreLeftOut() {
        setUpSensor()
        val outcome = handler.handle(BleMessages.message(sample, 10_320, listOf(null, 103, null, 100, 99, 95, 92)), noon)
        assertEquals(LiveStatus.OK, outcome.status)
        assertEquals(listOf(15, 12, 7, 6, 2), minutesBefore(noon, store.since(0)))
        assertEquals("the newest minute has no value", noon - 2 * minute, store.latest()!!.timestamp)
    }

    @Test
    fun valuesFromTheFutureAreRemoved() {
        setUpSensor()
        store.insert(listOf(Glucose(noon + 3 * 60 * minute, 150, null, true, "X")))
        handler.handle(BleMessages.steady(sample, 10_320, 104), noon)
        assertEquals(noon, store.latest()!!.timestamp)
        assertEquals(7, store.count())
    }

    /** The scan is the main part: with live values running, a scan fills everything the phone missed. */
    @Test
    fun aScanFillsTheHoursThatLiveValuesMissed() {
        setUpSensor()
        // live values came until three hours before the scan, and again in the last minutes
        val scanTime = noon + 20_000
        store.insert((180..200).map { Glucose(noon - it * minute, 120, null, true, "3MH001HM1LM") })
        store.insert(listOf(Glucose(noon - minute, 100, null, true, "3MH001HM1LM"), Glucose(noon, 99, null, true, "3MH001HM1LM")))
        assertEquals(23, store.count())

        val tag = FakeLibre2Tag(sample.uid, sample.patchInfo, sample.encrypted.copyOf())
        val scanner = SensorScanner(LibreNfcReader(now = { scanTime }, sleep = { }), now = { scanTime })
        val outcome = ScanHandler(store, settings, "test").handle(scanner.scan(tag), scanner.lastRawScan, scanner.log)

        assertEquals("nothing newer than the live value", ScanMessage.OK_NO_NEW, outcome.message)
        assertTrue(settings.lastScanDetails, settings.lastScanDetails.contains("holes filled: "))
        val all = store.since(0)
        // the 15 minutes before the scan are complete now
        for (m in 0..15) {
            assertTrue("minute $m", all.any { it.timestamp == noon - m * minute })
        }
        // and the hours between have a value each quarter of an hour
        val between = all.filter { it.timestamp > noon - 180 * minute && it.timestamp < noon - 15 * minute }
        assertTrue("${between.size} values in the hole", between.size >= 9)
        var previous = noon - 180 * minute
        for (value in between) {
            assertTrue("no hole longer than 30 minutes", value.timestamp - previous <= 30 * minute)
            previous = value.timestamp
        }
        // what was there stays as it was
        assertEquals(99, all.last().value)
        assertEquals(120, all.first { it.timestamp == noon - 190 * minute }.value)
        assertNull(all.firstOrNull { it.timestamp > noon })
    }

    @Test
    fun theLogKeepsTheNewestLines() {
        for (k in 1..50) {
            LiveLog.add(settings, noon + k * 1_000L, "line $k")
        }
        val lines = settings.liveLog.split('\n')
        assertEquals(40, lines.size)
        assertTrue(lines.first().endsWith("line 11"))
        assertTrue(lines.last().endsWith("line 50"))
    }
}
