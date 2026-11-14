package com.glucosescan.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Vibrator
import android.widget.LinearLayout
import android.widget.TextView
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.ScanMessage
import com.glucosescan.app.data.Settings
import com.glucosescan.app.nfc.FakeLibre2Tag
import com.glucosescan.app.reference.ReferenceVectors.hex
import com.glucosescan.app.testing.TestSensors
import com.glucosescan.app.live.LiveService
import com.glucosescan.app.nfc.ScanRunner
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.Speaker
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBasicTagTechnology
import org.robolectric.shadows.ShadowNfcAdapter
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.IOException
import java.time.Duration
import java.util.TimeZone

/** Stands in for the NFC chip of the phone: what the app sends goes to the fake sensor. */
@Implements(NfcV::class)
class FakeSensorNfcVShadow : ShadowBasicTagTechnology() {
    @Implementation
    @Throws(IOException::class)
    protected fun transceive(data: ByteArray): ByteArray {
        val sensor = current ?: throw IOException("Tag was lost.")
        return sensor.transceive(data)
    }

    companion object {
        @JvmStatic
        @Volatile
        var current: FakeLibre2Tag? = null
    }
}

/**
 * The whole way with the real screen: the phone finds a (fake) sensor, the app reads it over
 * the Android NFC classes, saves the values and shows them.
 */
@RunWith(RobolectricTestRunner::class)
// Android 6 is the oldest Android the app accepts, Android 14 is the one it is made for.
@Config(shadows = [FakeSensorNfcVShadow::class], qualifiers = "w360dp-h1300dp-xhdpi", sdk = [23, 34])
@Suppress("DEPRECATION")
class ScanOnScreenTest {
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private lateinit var adapter: NfcAdapter

    /** Tuesday 10 November 2026, 14:32:20 local time (UTC+3) */
    private val now = 1_794_310_340_000L

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Mogadishu"))
        GlucoseStore.closeShared()
        AppClock.source = { now }
        ScanRunner.reset()
        Speaker.reset()
        app = RuntimeEnvironment.getApplication()
        settings = Settings(app)
        store = GlucoseStore.get(app)
        settings.noticeAccepted = true
        settings.unit = GlucoseUnit.MGDL
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        ShadowNfcAdapter.setNfcHardwareExists(true)
        adapter = NfcAdapter.getDefaultAdapter(app)
        shadowOf(adapter).setEnabled(true)
    }

    @After
    fun tearDown() {
        FakeSensorNfcVShadow.current = null
        GlucoseStore.closeShared()
        AppClock.source = { System.currentTimeMillis() }
    }

    private fun sensor(sample: TestSensors.Sample = TestSensors.example): FakeLibre2Tag {
        val fake = FakeLibre2Tag(sample.uid, sample.patchInfo, sample.encrypted.copyOf())
        FakeSensorNfcVShadow.current = fake
        return fake
    }

    /** A tag of the type NFC-V, as Android hands it to the app. */
    private fun androidTag(id: ByteArray): Tag {
        val extras = Bundle()
        extras.putByte("respflags", 0)
        extras.putByte("dsfid", 0)
        val nfcV = 5 // android.nfc.tech.TagTechnology.NFC_V
        if (Build.VERSION.SDK_INT <= 33) {
            return ReflectionHelpers.callStaticMethod(
                Tag::class.java,
                "createMockTag",
                ClassParameter.from(ByteArray::class.java, id),
                ClassParameter.from(IntArray::class.java, intArrayOf(nfcV)),
                ClassParameter.from(Array<Bundle>::class.java, arrayOf(extras))
            )
        }
        return ReflectionHelpers.callStaticMethod(
            Tag::class.java,
            "createMockTag",
            ClassParameter.from(ByteArray::class.java, id),
            ClassParameter.from(IntArray::class.java, intArrayOf(nfcV)),
            ClassParameter.from(Array<Bundle>::class.java, arrayOf(extras)),
            ClassParameter.from(java.lang.Long.TYPE, 0L)
        )
    }

    private fun main(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun touch(tag: Tag) {
        shadowOf(adapter).dispatchTagDiscovered(tag)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun text(activity: MainActivity, id: Int): String = activity.findViewById<TextView>(id).text.toString()

    private fun sensorCard(activity: MainActivity): String {
        val box = activity.findViewById<LinearLayout>(R.id.sensorBox)
        return (0 until box.childCount).joinToString("|") {
            val row = box.getChildAt(it)
            row.findViewById<TextView>(R.id.label).text.toString() + "=" + row.findViewById<TextView>(R.id.text).text.toString()
        }
    }

    @Test
    fun scanningASensorShowsItsValue() {
        val activity = main()
        assertEquals("\u2013 \u2013", text(activity, R.id.value))
        assertTrue(shadowOf(adapter).isInReaderMode)

        val fake = sensor()
        touch(androidTag(TestSensors.example.uid))

        // the real scan "Example": 99 mg/dL, 10 lower than 15 minutes before
        assertEquals("99", text(activity, R.id.value))
        assertEquals("mg/dL", text(activity, R.id.unit))
        assertEquals("\u2192", text(activity, R.id.arrow))
        assertEquals("Steady \u00b7 \u22120.7 mg/dL per minute", text(activity, R.id.trendLine))
        assertTrue(text(activity, R.id.valueTime).endsWith("just now"))
        assertTrue(text(activity, R.id.scanStatus).endsWith("Scan OK"))
        assertEquals(app.getColor(R.color.green), activity.findViewById<TextView>(R.id.scanStatus).currentTextColor)

        val card = sensorCard(activity)
        assertTrue(card, card.contains("State=Ready"))
        assertTrue(card, card.contains("Serial number=3MH001HM1LM"))
        assertTrue(card, card.contains("Age=7d 4h 0min"))

        assertEquals(47, store.count())
        assertEquals(99, store.latest()!!.value)

        // the app has only read: one "patch info" and 15 "read blocks"
        assertEquals(16, fake.commands.size)
        assertTrue(fake.forbiddenCommands.isEmpty())
        assertTrue("live values are off: the sensor is not set up for Bluetooth", fake.streamingCommands.isEmpty())
        assertNull(settings.liveSensor)
        assertNull("no service is started", shadowOf(app).nextStartedService)

        // one vibration tells that the scan is done
        val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        assertTrue(shadowOf(vibrator).isVibrating)
        assertEquals(150L, shadowOf(vibrator).milliseconds)
    }

    @Test
    fun theSameSensorFoundAgainRightAwayIsNotReadTwice() {
        val activity = main()
        val fake = sensor()
        val tag = androidTag(TestSensors.example.uid)
        touch(tag)
        assertEquals(16, fake.commands.size)

        // the phone reports the sensor again while it is still held to it
        touch(tag)
        assertEquals("not read again", 16, fake.commands.size)
        assertTrue(text(activity, R.id.scanStatus).endsWith("Scan OK"))

        // some seconds later it is a new scan
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        touch(tag)
        assertEquals(32, fake.commands.size)
        assertEquals(ScanMessage.OK_NO_NEW, settings.lastOutcome!!.message)
        assertEquals("99", text(activity, R.id.value))
        assertEquals(47, store.count())
    }

    @Test
    fun aFailedScanCanBeRepeatedAtOnce() {
        val activity = main()
        val tag = androidTag(TestSensors.example.uid)

        sensor().goneAfterCommands = 5 // the phone is moved away too early
        touch(tag)
        assertTrue(text(activity, R.id.scanStatus).endsWith("The sensor was lost. Hold the phone still on the sensor until the phone vibrates."))
        assertEquals(app.getColor(R.color.red), activity.findViewById<TextView>(R.id.scanStatus).currentTextColor)
        assertEquals("\u2013 \u2013", text(activity, R.id.value))
        assertEquals(0, store.count())
        val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        assertNotNull("the failure vibration is a pattern", shadowOf(vibrator).pattern)

        sensor()
        touch(tag)
        assertEquals("99", text(activity, R.id.value))
        assertTrue(text(activity, R.id.scanStatus).endsWith("Scan OK"))
    }

    @Test
    fun aSensorThatChangedDuringTheReadIsReadAgain() {
        val activity = main()
        val fake = sensor()
        val newer = TestSensors.example.changed { TestSensors.setAge(it, 10321) }
        var switched = false
        fake.beforeBlockRead = { block ->
            if (block == 30 && !switched) {
                switched = true
                fake.fram = newer.encrypted.copyOf()
            }
        }
        touch(androidTag(TestSensors.example.uid))
        assertEquals("99", text(activity, R.id.value))
        assertEquals(31, fake.commands.size)
        assertTrue(settings.lastScanDetails.contains("reading again"))
    }

    @Test
    fun anotherSensorTypeIsRefusedOnScreen() {
        val activity = main()
        val libre1 = FakeLibre2Tag(TestSensors.example.uid, hex("df0000010102"), TestSensors.example.encrypted)
        FakeSensorNfcVShadow.current = libre1
        touch(androidTag(TestSensors.example.uid))
        assertTrue(text(activity, R.id.scanStatus).endsWith("This sensor is not supported (Libre 1). This app reads only FreeStyle Libre 2 of the European type."))
        assertEquals("\u2013 \u2013", text(activity, R.id.value))
        assertEquals(1, libre1.commands.size)
        assertEquals(0, store.count())
    }

    @Test
    fun anEndedSensorShowsNoValue() {
        val activity = main()
        sensor(TestSensors.example.changed { TestSensors.setState(it, 5) })
        touch(androidTag(TestSensors.example.uid))
        assertEquals("\u2013 \u2013", text(activity, R.id.value))
        assertTrue(text(activity, R.id.scanStatus).endsWith("This sensor has stopped working. It gives no more values."))
        assertTrue(sensorCard(activity).contains("State=Stopped"))
        assertEquals(0, store.count())
    }

    @Test
    fun theAppCanBeOpenedByTouchingTheSensor() {
        val fake = sensor()
        val intent = Intent(NfcAdapter.ACTION_TECH_DISCOVERED)
        intent.setClassName(app, MainActivity::class.java.name)
        intent.putExtra(NfcAdapter.EXTRA_TAG, androidTag(TestSensors.example.uid))
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()

        // the read runs on its own thread: wait until it is done
        val end = System.currentTimeMillis() + 20_000
        while (settings.lastOutcome == null && System.currentTimeMillis() < end) {
            Thread.sleep(20)
        }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(ScanMessage.OK, settings.lastOutcome!!.message)
        assertEquals("99", text(activity, R.id.value))
        assertEquals(16, fake.commands.size)
        assertTrue("after the read the phone looks for sensors again", shadowOf(adapter).isInReaderMode)

        // the sensor is still at the phone and is found again: no second read
        touch(androidTag(TestSensors.example.uid))
        assertEquals(16, fake.commands.size)
    }

    @Test
    fun noScanBeforeTheNoticeIsAccepted() {
        settings.noticeAccepted = false
        val fake = sensor()
        val intent = Intent(NfcAdapter.ACTION_TECH_DISCOVERED)
        intent.putExtra(NfcAdapter.EXTRA_TAG, androidTag(TestSensors.example.uid))
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertTrue(fake.commands.isEmpty())
        assertEquals(0, store.count())
        assertFalse(shadowOf(adapter).isInReaderMode)
    }

    // ------------------------------------------------------------------ version 2.0: voice, every screen, live values

    @Test
    fun afterAScanTheValueIsSaid() {
        main()
        sensor()
        touch(androidTag(TestSensors.example.uid))
        assertEquals("Glucose 99. Steady.", Speaker.lastRequest)
        assertEquals(now, settings.lastSpokenTime)

        // without the direction
        settings.speakTrend = false
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        touch(androidTag(TestSensors.example.uid))
        assertEquals("Glucose 99.", Speaker.lastRequest)
        assertEquals(2, Speaker.requests.size)

        // switched off
        settings.speakAfterScan = false
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        touch(androidTag(TestSensors.example.uid))
        assertEquals(2, Speaker.requests.size)
    }

    @Test
    fun aValueBelowTheTargetRangeIsSaidSo() {
        settings.setLimits(110, 180)
        main()
        sensor()
        touch(androidTag(TestSensors.example.uid))
        assertEquals("low and still falling 0.7 mg/dL a minute: not \"Steady\"", "Glucose 99. Below target. Falling slowly.", Speaker.lastRequest)
    }

    @Test
    fun aScanWithoutAValueIsSaidToo() {
        main()
        val tag = androidTag(TestSensors.example.uid)
        sensor().goneAfterCommands = 5
        touch(tag)
        assertEquals("The scan did not work. Please look at the screen.", Speaker.lastRequest)

        sensor(TestSensors.example.changed { TestSensors.setState(it, 5) })
        touch(tag)
        assertEquals("an ended sensor is a problem as well", "The scan did not work. Please look at the screen.", Speaker.lastRequest)
        assertEquals(2, Speaker.requests.size)
    }

    @Test
    fun theSensorCanBeScannedOnEveryScreen() {
        val screens = listOf(
            StatisticsActivity::class.java, AnalysisActivity::class.java, ReportActivity::class.java, MoreActivity::class.java,
            LiveActivity::class.java, AlarmsActivity::class.java, VoiceActivity::class.java, ReadingsActivity::class.java,
            SettingsActivity::class.java, AboutActivity::class.java, CorrectionActivity::class.java
        )
        var reads = 0
        for (screen in screens) {
            val activity = Robolectric.buildActivity(screen).setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(screen.simpleName + " looks for sensors", shadowOf(adapter).isInReaderMode)
            val fake = sensor()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            touch(androidTag(TestSensors.example.uid))
            reads++
            assertEquals(screen.simpleName + " reads the sensor", 16, fake.commands.size)
            assertEquals(47, store.count())
            assertEquals(screen.simpleName + " shows the Home screen after the scan", MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
            assertEquals(reads, Speaker.requests.size)
        }
    }

    @Test
    fun withLiveValuesOnTheFirstScanSetsTheSensorUp() {
        allowBluetooth()
        settings.liveEnabled = true
        val activity = main()
        val fake = sensor()
        fake.allowStreaming = true
        touch(androidTag(TestSensors.example.uid))

        // the scan is as good as ever
        assertEquals("99", text(activity, R.id.value))
        assertEquals(47, store.count())
        assertEquals(ScanMessage.OK, settings.lastOutcome!!.message)

        // and one more command went out: "enable streaming", with this app's unlock code
        assertEquals(17, fake.commands.size)
        assertEquals(1, fake.streamingCommands.size)
        assertEquals(fake.commands.last(), fake.streamingCommands[0])
        assertEquals(42L, fake.streamingCode)
        assertTrue(fake.forbiddenCommands.isEmpty())

        val record = settings.liveSensor!!
        assertEquals("3MH001HM1LM", record.serial)
        assertArrayEquals(TestSensors.example.uid, record.uid)
        assertArrayEquals(TestSensors.example.patchInfo, record.patchInfo)
        assertEquals("the Bluetooth address the sensor gave", "A4:11:22:33:44:55", record.addressFromAnswer)
        assertEquals(now, record.pairedTime)
        assertEquals(now - 10_320 * 60_000L, record.startTime)
        assertEquals(0, settings.liveUnlockCount)
        assertFalse(settings.livePairWanted)
        assertTrue(settings.liveLog.contains("set up for live values"))

        // the service that keeps the Bluetooth connection is asked to start
        var started = false
        while (true) {
            val service = shadowOf(app).nextStartedService ?: break
            started = started || service.component!!.className == LiveService::class.java.name
        }
        assertTrue(started)

        // the next scans only read again
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        touch(androidTag(TestSensors.example.uid))
        assertEquals(33, fake.commands.size)
        assertEquals(1, fake.streamingCommands.size)

        // until the user asks to set the sensor up again
        settings.liveUnlockCount = 17
        settings.livePairWanted = true
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        touch(androidTag(TestSensors.example.uid))
        assertEquals(2, fake.streamingCommands.size)
        assertEquals("the count starts anew with a new set-up", 0, settings.liveUnlockCount)
        assertFalse(settings.livePairWanted)
    }

    @Test
    fun anotherSensorIsSetUpWhenItIsScanned() {
        allowBluetooth()
        settings.liveEnabled = true
        main()
        sensor().allowStreaming = true
        touch(androidTag(TestSensors.example.uid))
        assertEquals("3MH001HM1LM", settings.liveSensor!!.serial)
        settings.liveAddress = "A4:11:22:33:44:55"

        val second = sensor(TestSensors.example2)
        second.allowStreaming = true
        touch(androidTag(TestSensors.example2.uid))
        assertEquals(1, second.streamingCommands.size)
        assertEquals("3MH001GJ0VW", settings.liveSensor!!.serial)
        assertNull("the address of the old sensor is forgotten", settings.liveAddress)
    }

    @Test
    fun ifTheSensorRefusesTheSetUpTheScanIsStillGood() {
        allowBluetooth()
        settings.liveEnabled = true
        val activity = main()
        val fake = sensor() // refuses "enable streaming"
        touch(androidTag(TestSensors.example.uid))
        assertEquals(1, fake.streamingCommands.size)
        assertEquals("99", text(activity, R.id.value))
        assertEquals(ScanMessage.OK, settings.lastOutcome!!.message)
        assertEquals(47, store.count())
        assertNull(settings.liveSensor)
        assertTrue(settings.liveLog, settings.liveLog.contains("could not set the sensor up"))
        assertTrue(settings.lastScanDetails, settings.lastScanDetails.contains("enable streaming failed"))
    }

    @Test
    fun aSensorThatDoesNotRunIsNotSetUp() {
        allowBluetooth()
        settings.liveEnabled = true
        main()
        val ended = sensor(TestSensors.example.changed { TestSensors.setState(it, 5) })
        ended.allowStreaming = true
        touch(androidTag(TestSensors.example.uid))
        assertTrue(ended.streamingCommands.isEmpty())
        assertNull(settings.liveSensor)
    }

    /** On Android 12 and newer the user must allow "devices nearby" first. */
    private fun allowBluetooth() {
        if (Build.VERSION.SDK_INT >= 31) {
            shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN)
        }
    }
}
