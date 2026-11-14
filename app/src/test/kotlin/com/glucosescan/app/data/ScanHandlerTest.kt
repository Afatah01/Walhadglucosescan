package com.glucosescan.app.data

import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.ScanStatus
import com.glucosescan.app.nfc.FakeLibre2Tag
import com.glucosescan.app.nfc.LibreNfcReader
import com.glucosescan.app.nfc.NfcReadException
import com.glucosescan.app.nfc.SensorScanner
import com.glucosescan.app.reference.ReferenceVectors.hex
import com.glucosescan.app.testing.TestSensors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** From the (fake) sensor to the saved values: the whole way of a scan, without the screen. */
@RunWith(RobolectricTestRunner::class)
class ScanHandlerTest {
    private val minute = 60_000L
    private val noon = 1_767_268_800_000L
    private lateinit var store: GlucoseStore
    private lateinit var settings: Settings
    private lateinit var handler: ScanHandler

    @Before
    fun setUp() {
        store = GlucoseStore(RuntimeEnvironment.getApplication(), null)
        settings = Settings(RuntimeEnvironment.getApplication())
        handler = ScanHandler(store, settings, "test")
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun scan(sample: TestSensors.Sample, time: Long, prepare: (FakeLibre2Tag) -> Unit = { }): ScanOutcome {
        val tag = FakeLibre2Tag(sample.uid, sample.patchInfo, sample.encrypted.copyOf())
        prepare(tag)
        val scanner = SensorScanner(LibreNfcReader(now = { time }, sleep = { }), now = { time })
        return try {
            handler.handle(scanner.scan(tag), scanner.lastRawScan, scanner.log)
        } catch (e: NfcReadException) {
            handler.handleFailure(e, time, scanner.lastRawScan, scanner.log)
        }
    }

    @Test
    fun firstScanSavesTheValues() {
        val outcome = scan(TestSensors.example, noon + 20_000)
        assertEquals(ScanMessage.OK, outcome.message)
        assertEquals(47, store.count())

        val latest = store.latest()!!
        assertEquals(99, latest.value)
        assertEquals(noon, latest.timestamp)
        assertTrue(latest.fromTrend)
        assertEquals("3MH001HM1LM", latest.sensorSerial)

        val all = store.since(0)
        assertEquals(noon - 480 * minute, all.first().timestamp)
        assertEquals(31, all.count { !it.fromTrend })
        // the real one-minute values of this scan, oldest first
        assertEquals(listOf(109, 107, 106, 105, 105, 105, 104, 104, 104, 103, 102, 101, 100, 99, 99, 99), all.takeLast(16).map { it.value })

        val sensor = settings.lastSensor!!
        assertEquals("3MH001HM1LM", sensor.serial)
        assertEquals(ScanStatus.OK, sensor.status)
        assertEquals(10320, sensor.age)
        assertEquals(20659, sensor.lifetime)
        assertEquals("Libre 2 EU", sensor.typeLabel)
        assertEquals(noon + 20_000, sensor.scanTime)

        assertEquals(ScanMessage.OK, settings.lastOutcome!!.message)
        val details = settings.lastScanDetails
        assertTrue(details.contains("outcome: OK"))
        assertTrue(details.contains("serial: 3MH001HM1LM"))
        assertTrue(details.contains("trend index: 15 (age mod 16 = 0)"))
        assertTrue(details.contains("history index: 15 (expected 15)"))
        assertTrue(details.contains("saved: 47"))
        assertTrue(details.contains("memory as read: " + hex2(TestSensors.example.encrypted)))
    }

    private fun hex2(bytes: ByteArray) = LibreNfcReader.hex(bytes)

    @Test
    fun scanningAgainInTheSameMinuteAddsNothing() {
        scan(TestSensors.example, noon + 5_000)
        val outcome = scan(TestSensors.example, noon + 40_000)
        assertEquals(ScanMessage.OK_NO_NEW, outcome.message)
        assertEquals(47, store.count())
    }

    @Test
    fun aLaterScanAddsOnlyTheNewValues() {
        scan(TestSensors.example, noon)
        // 3 minutes later the sensor is 3 minutes older; the memory content is the same here,
        // so the three newest one-minute values count as new
        val later = TestSensors.example.changed { TestSensors.setAge(it, 10323) }
        val outcome = scan(later, noon + 3 * minute)
        assertEquals(ScanMessage.OK, outcome.message)
        assertEquals(50, store.count())
        assertEquals(noon + 3 * minute, store.latest()!!.timestamp)
        // the change of the first new value is counted from the value saved before
        val added = store.since(noon + minute)
        assertEquals(3, added.size)
        assertNotNull(added[0].minuteChange)
    }

    @Test
    fun valuesFromTheFutureAreRemovedAtTheNextScan() {
        // the phone clock was one day ahead when these were saved
        store.insert(listOf(Glucose(noon + 24 * 60 * minute, 250, null, true), Glucose(noon + 3 * minute, 240, null, true)))
        val outcome = scan(TestSensors.example, noon)
        assertEquals(ScanMessage.OK, outcome.message)
        assertEquals(99, store.latest()!!.value)
        assertEquals(noon, store.latest()!!.timestamp)
        assertEquals(47, store.count())
        assertTrue(settings.lastScanDetails.contains("removed values with a time in the future: 2"))
    }

    @Test
    fun valuesOlderThan90DaysAreRemoved() {
        store.insert(listOf(Glucose(noon - 91L * 24 * 60 * minute, 120, null, true), Glucose(noon - 89L * 24 * 60 * minute, 121, null, true)))
        scan(TestSensors.example, noon)
        assertEquals(48, store.count())
        assertEquals(121, store.since(0).first().value)
    }

    @Test
    fun sensorsThatGiveNoValues() {
        val cases = listOf(
            1 to ScanMessage.NOT_STARTED,
            4 to ScanMessage.EXPIRED,
            5 to ScanMessage.STOPPED,
            6 to ScanMessage.STOPPED,
            9 to ScanMessage.UNKNOWN_STATE
        )
        for ((state, message) in cases) {
            val sample = TestSensors.example.changed { TestSensors.setState(it, state) }
            val outcome = scan(sample, noon)
            assertEquals("state $state", message, outcome.message)
            assertTrue(outcome.message.isProblem)
            assertEquals("state $state: nothing saved", 0, store.count())
            assertEquals("the sensor card still knows the sensor", "3MH001HM1LM", settings.lastSensor!!.serial)
        }
    }

    @Test
    fun warmingUp() {
        val warming = TestSensors.example.changed {
            TestSensors.setState(it, 2)
            TestSensors.setAge(it, 25)
        }
        val outcome = scan(warming, noon)
        assertEquals(ScanMessage.STARTING, outcome.message)
        assertEquals(35, outcome.number)
        assertEquals(0, store.count())
        assertEquals(ScanStatus.STARTING, settings.lastSensor!!.status)

        // the sensor says "ready" but is not 60 minutes old yet
        val early = TestSensors.example.changed { TestSensors.setAge(it, 60) }
        val outcome2 = scan(early, noon)
        assertEquals(ScanMessage.STARTING, outcome2.message)
        assertEquals(1, outcome2.number)
        assertEquals(0, store.count())

        // still "warming up" by its own word, but past the hour: no time can be told
        val late = TestSensors.example.changed {
            TestSensors.setState(it, 2)
            TestSensors.setAge(it, 70)
        }
        assertEquals(ScanMessage.STARTING_NO_TIME, scan(late, noon).message)
        assertEquals(0, store.count())
    }

    @Test
    fun otherSensorTypesSaveNothingAndKeepTheSensorCard() {
        scan(TestSensors.example, noon)
        val libre1 = TestSensors.Sample("libre1", TestSensors.example.uid, hex("df0000010102"), TestSensors.example.encrypted, TestSensors.example.plain)
        val outcome = scan(libre1, noon + 5 * minute)
        assertEquals(ScanMessage.UNSUPPORTED, outcome.message)
        assertEquals("Libre 1", outcome.text)
        assertEquals(47, store.count())
        assertEquals("3MH001HM1LM", settings.lastSensor!!.serial)
    }

    @Test
    fun aBrokenReadSavesNothing() {
        val outcome = scan(TestSensors.example, noon) { it.fram[100] = (it.fram[100] + 1).toByte() }
        assertEquals(ScanMessage.BAD_DATA, outcome.message)
        assertEquals(0, store.count())
        assertNull(settings.lastSensor)
        assertTrue(settings.lastScanDetails.contains("checksum wrong"))
    }

    @Test
    fun aLostSensorSavesNothing() {
        val outcome = scan(TestSensors.example, noon) { it.goneAfterCommands = 6 }
        assertEquals(ScanMessage.LOST, outcome.message)
        assertEquals(0, store.count())
        assertEquals(ScanMessage.LOST, settings.lastOutcome!!.message)

        val notASensor = scan(TestSensors.example, noon) { it.answersPatchInfo = false }
        assertEquals(ScanMessage.NOT_A_SENSOR, notASensor.message)
    }

    @Test
    fun theNewestValueIsFaulty() {
        val sample = TestSensors.example.changed { TestSensors.setNewestTrendRaw(it, 0) }
        val outcome = scan(sample, noon)
        assertEquals(ScanMessage.SENSOR_NO_VALUE, outcome.message)
        assertEquals("the good values before it are saved", 46, store.count())
        assertEquals(noon - minute, store.latest()!!.timestamp)
        assertEquals(99, store.latest()!!.value)
    }

    @Test
    fun wildValuesAreNotSaved() {
        // raw value 16383 is the biggest possible one: far above 500 mg/dL after decoding
        val sample = TestSensors.example.changed { TestSensors.setNewestTrendRaw(it, 16383) }
        val outcome = scan(sample, noon)
        assertEquals(ScanMessage.TOO_NOISY, outcome.message)
        assertEquals(0, store.count())
    }

    @Test
    fun settingsKeepWhatTheyAreGiven() {
        assertNull(settings.unit)
        settings.unit = GlucoseUnit.MMOLL
        assertEquals(GlucoseUnit.MMOLL, settings.unit)
        assertEquals(80, settings.lowLimit)
        assertEquals(180, settings.highLimit)
        settings.setLimits(70, 160)
        assertEquals(70, settings.lowLimit)
        assertEquals(160, settings.highLimit)
        settings.setLimits(165, 160) // low over high: refused
        assertEquals(70, settings.lowLimit)
        settings.setLimits(160, 160)
        assertEquals(160, settings.lowLimit)
        settings.setLimits(10, 900) // kept inside 40..400
        assertEquals(40, settings.lowLimit)
        assertEquals(400, settings.highLimit)
        assertEquals(6, settings.chartHours)
        settings.chartHours = 24
        assertEquals(24, settings.chartHours)
    }
}
