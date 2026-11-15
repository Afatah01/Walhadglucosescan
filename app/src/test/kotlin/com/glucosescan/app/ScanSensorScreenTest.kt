package com.glucosescan.app

import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.ScanStatus
import com.glucosescan.app.libre.SensorInfo
import com.glucosescan.app.data.SensorSnapshot
import com.glucosescan.app.data.Settings
import com.glucosescan.app.testing.TestSensors
import com.glucosescan.app.ui.AppClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.util.TimeZone

/**
 * The screen for scanning the sensor, and the promise it keeps: the number it shows is the one
 * the sensor itself gave, or it says it has none. It never shows a number that was made up.
 */
@RunWith(RobolectricTestRunner::class)
class ScanSensorScreenTest {

    private val now = 1_794_310_340_000L
    private lateinit var settings: Settings
    private lateinit var activity: ActivityController<ScanSensorActivity>

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Mogadishu"))
        AppClock.source = { now }
        settings = Settings(RuntimeEnvironment.getApplication())
        settings.noticeAccepted = true
        settings.unit = GlucoseUnit.MGDL
        settings.lastSensor = null
    }

    @After
    fun tearDown() {
        activity.close()
        AppClock.source = { System.currentTimeMillis() }
    }

    private fun open(): ScanSensorActivity {
        activity = Robolectric.buildActivity(ScanSensorActivity::class.java).setup()
        return activity.get()
    }

    private fun sensor(serial: String?) = SensorSnapshot(
        serial = serial,
        typeLabel = "FreeStyle Libre 2",
        regionLabel = "Europe",
        status = ScanStatus.OK,
        age = 1_000,
        lifetime = 20_160,
        scanTime = now
    )

    // ------------------------------------------------------- the number is never invented

    @Test fun `with no sensor scanned it says the number is not available`() {
        val screen = open()
        assertEquals(
            screen.getString(R.string.scan_number_none),
            screen.findViewById<TextView>(R.id.sensorNumber).text.toString()
        )
        assertEquals(
            screen.getString(R.string.scan_number_waiting),
            screen.findViewById<TextView>(R.id.sensorNumberNote).text.toString()
        )
    }

    @Test fun `a sensor that gave no number says so instead of showing one`() {
        settings.lastSensor = sensor(serial = null)
        val screen = open()
        assertEquals(
            screen.getString(R.string.scan_number_none),
            screen.findViewById<TextView>(R.id.sensorNumber).text.toString()
        )
        assertEquals(
            screen.getString(R.string.scan_number_note),
            screen.findViewById<TextView>(R.id.sensorNumberNote).text.toString()
        )
    }

    @Test fun `an empty number is treated as no number`() {
        settings.lastSensor = sensor(serial = "   ")
        val screen = open()
        assertEquals(
            screen.getString(R.string.scan_number_none),
            screen.findViewById<TextView>(R.id.sensorNumber).text.toString()
        )
    }

    /** The number shown is the one the sensor's own data gives, not one the app thought up. */
    @Test fun `the number is the one the sensor itself gave`() {
        val sample = TestSensors.example
        val info = SensorInfo.libreStyleSensor(sample.uid, sample.patchInfo, sample.plain)
        val serial: String = requireNotNull(info.serial) { "the test sensor must give a number" }

        settings.lastSensor = sensor(serial)
        val screen = open()
        assertEquals(serial, screen.findViewById<TextView>(R.id.sensorNumber).text.toString())
    }

    /** The second real sensor gives a different number, and the screen shows that one. */
    @Test fun `a different sensor shows its own number`() {
        val first = TestSensors.example
        val second = TestSensors.example2
        val firstSerial: String? = SensorInfo.libreStyleSensor(first.uid, first.patchInfo, first.plain).serial
        val secondSerial: String? = SensorInfo.libreStyleSensor(second.uid, second.patchInfo, second.plain).serial

        require(firstSerial != null && secondSerial != null) { "both test sensors must give a number" }
        require(firstSerial != secondSerial) { "the two test sensors must differ" }

        settings.lastSensor = sensor(secondSerial)
        val screen = open()
        assertEquals(secondSerial, screen.findViewById<TextView>(R.id.sensorNumber).text.toString())
    }

    // ------------------------------------------------------- what the screen says

    @Test fun `it tells the person what to do`() {
        val screen = open()
        assertEquals(
            screen.getString(R.string.scan_prompt),
            screen.findViewById<TextView>(R.id.scanTitle).text.toString()
        )
    }

    /** No promise of a background scan that Android would not keep. */
    @Test fun `the help says the screen must be on and that a Libre 3 has no NFC`() {
        val help = open().findViewById<TextView>(R.id.scanHelp).text.toString()
        val lower = help.lowercase()
        assertTrue(help, lower.contains("screen") && lower.contains("on"))
        assertTrue(help, lower.contains("libre 3"))
        assertTrue(help, lower.contains("bluetooth"))
    }

    @Test fun `no scan running means no turning circle`() {
        val screen = open()
        assertEquals(
            android.view.View.GONE.toInt(),
            screen.findViewById<ProgressBar>(R.id.scanProgress).visibility
        )
    }

    @Test fun `a sensor shows its state, its type and when it was scanned`() {
        settings.lastSensor = sensor("3MH001HM1LM")
        val screen = open()
        val box = screen.findViewById<android.widget.LinearLayout>(R.id.sensorBox)
        val words = (0 until box.childCount).joinToString("\n") { i ->
            val row = box.getChildAt(i)
            row.findViewById<TextView>(R.id.label).text.toString() + " " +
                row.findViewById<TextView>(R.id.text).text.toString()
        }
        assertTrue(words, words.contains(screen.getString(R.string.sensor_state)))
        assertTrue(words, words.contains("FreeStyle Libre 2"))
        assertTrue(words, words.contains("Europe"))
        assertTrue(words, words.contains(screen.getString(R.string.sensor_age)))
    }

    @Test fun `with no sensor there is nothing under the number`() {
        val screen = open()
        assertEquals(0, screen.findViewById<android.widget.LinearLayout>(R.id.sensorBox).childCount)
    }

    @Test fun `the last scan is hidden until there has been one`() {
        val screen = open()
        assertEquals(
            android.view.View.GONE.toInt(),
            screen.findViewById<android.view.View>(R.id.resultCard).visibility
        )
    }

}
