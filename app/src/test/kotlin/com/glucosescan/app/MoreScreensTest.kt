package com.glucosescan.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Application
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import com.glucosescan.app.core.Correction
import com.glucosescan.app.core.CsvExport
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.ReferencePair
import com.glucosescan.app.core.StatisticsCalculator
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LiveSensorRecord
import com.glucosescan.app.data.LiveState
import com.glucosescan.app.data.Settings
import com.glucosescan.app.libre.FactoryCalibration
import com.glucosescan.app.live.AlarmSounder
import com.glucosescan.app.live.LiveService
import com.glucosescan.app.nfc.ScanRunner
import com.glucosescan.app.testing.TestSensors
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.PulseDotView
import com.glucosescan.app.ui.Speaker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNfcAdapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/** The screens that came with version 2.0: what they show and do, with pictures in docs/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1500dp-xhdpi")
class MoreScreensTest {
    private val minute = 60_000L
    private val serial = "3MH001HM1LM"
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore

    /** Tuesday 10 November 2026, 14:32:20 local time (UTC+3) (11:32:20 UTC) */
    private var now = 1_794_310_340_000L

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Mogadishu"))
        GlucoseStore.closeShared()
        app = RuntimeEnvironment.getApplication()
        settings = Settings(app)
        store = GlucoseStore.get(app)
        AppClock.source = { now }
        ScanRunner.reset()
        Speaker.reset()
        AlarmSounder.plays = 0
        MoreActivity.lastExportMessage = null
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
        settings.noticeAccepted = true
        settings.unit = GlucoseUnit.MGDL
    }

    @After
    fun tearDown() {
        GlucoseStore.closeShared()
        AppClock.source = { System.currentTimeMillis() }
    }

    // ------------------------------------------------------------ helpers

    private fun <T : Activity> open(screen: Class<T>, intent: Intent? = null): T {
        val activity = if (intent == null) Robolectric.buildActivity(screen).setup().get() else Robolectric.buildActivity(screen, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun text(activity: Activity, id: Int): String = activity.findViewById<TextView>(id).text.toString()

    private fun isShown(activity: Activity, id: Int): Boolean = activity.findViewById<View>(id).visibility == View.VISIBLE

    private fun click(activity: Activity, id: Int) {
        activity.findViewById<View>(id).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** All words that a screen shows, of every text view in it. */
    private fun allWords(view: View): String {
        val words = StringBuilder()
        if (view is TextView && view.visibility == View.VISIBLE) {
            words.append(view.text).append('\n')
        }
        if (view is ViewGroup && view.visibility == View.VISIBLE) {
            for (i in 0 until view.childCount) {
                words.append(allWords(view.getChildAt(i)))
            }
        }
        return words.toString()
    }

    private fun words(activity: Activity): String = allWords(activity.window.decorView)

    /** The lines of a box of rows: "label=text". */
    private fun rows(activity: Activity, boxId: Int): List<String> {
        val box = activity.findViewById<LinearLayout>(boxId)
        return (0 until box.childCount).map {
            val row = box.getChildAt(it)
            row.findViewById<TextView>(R.id.label).text.toString() + "=" + row.findViewById<TextView>(R.id.text).text.toString()
        }
    }

    private fun picture(activity: Activity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val folder = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
        folder.mkdirs()
        FileOutputStream(File(folder, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The value a made-up person has at a time of day: low in the night, high after meals. */
    private fun wave(time: Long, k: Int): Int {
        val hour = ((time / minute + 180) % 1440) / 60.0 // local time of the tests (UTC+3)
        val day = 128 + 42 * Math.sin((hour - 9) / 24.0 * 2 * Math.PI) + 30 * Math.sin((hour - 13) / 24.0 * 4 * Math.PI)
        return Math.round(day + 22 * Math.sin(k * 0.83) + 9 * Math.sin(k * 0.21)).toInt().coerceIn(45, 330)
    }

    /** One value each 15 minutes for [days] days, up to now. */
    private fun seedDays(days: Int) {
        val latest = now - now % minute
        val values = ArrayList<Glucose>()
        for (k in 0 until days * 96) {
            val time = latest - k * 15 * minute
            values.add(Glucose(time, wave(time, k), null, false, serial))
        }
        store.insert(values)
    }

    /** The last 8 hours as after a scan: one value each minute for 16 minutes, one each 15 minutes before. */
    private fun seedScan(newest: Int = 104) {
        val latest = now - now % minute
        val values = ArrayList<Glucose>()
        var m = 30
        while (m <= 8 * 60) {
            values.add(Glucose(latest - m * minute, newest + 10 + Math.round(60 * Math.sin((m - 15) / 58.0)).toInt(), null, false, serial))
            m += 15
        }
        for (k in 15 downTo 0) {
            values.add(Glucose(latest - k * minute, newest + Math.round(k * 10 / 15f), null, true, serial))
        }
        store.insert(values)
    }

    private fun liveSensor(): LiveSensorRecord {
        val sample = TestSensors.example
        return LiveSensorRecord(
            serial, sample.uid, sample.patchInfo, FactoryCalibration.libreStyleCalibration(sample.plain), 20659,
            now - 10_320 * minute, byteArrayOf(0x55, 0x44, 0x33, 0x22, 0x11, 0xA4.toByte()), now - 30 * minute
        )
    }

    private fun allowBluetooth() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)
    }

    // ------------------------------------------------------------ Home with live values

    @Test
    fun homeShowsTheLiveConnection() {
        seedScan()
        allowBluetooth()
        settings.liveEnabled = true
        settings.liveSensor = liveSensor()
        settings.liveState = LiveState.RECEIVING

        val home = open(MainActivity::class.java)
        assertTrue(isShown(home, R.id.liveCard))
        assertEquals("Connected. A new value each minute.", text(home, R.id.liveState))
        assertEquals(app.getColor(R.color.text), home.findViewById<TextView>(R.id.liveState).currentTextColor)
        // the sign at the top says that values are coming in, and its dot pulses
        assertTrue(isShown(home, R.id.livePill))
        assertEquals("LIVE", text(home, R.id.livePillText))
        assertEquals(MainActivity.LiveSign.LIVE, home.liveSign)
        assertTrue(home.findViewById<PulseDotView>(R.id.liveDot).pulsing)
        assertFalse("with live values on, they are not offered again", isShown(home, R.id.liveOffer))
        assertEquals("104", text(home, R.id.value))
        assertEquals("the service is asked to run", LiveService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        picture(home, "20-main-live-values")

        click(home, R.id.liveCard)
        assertEquals(LiveActivity::class.java.name, shadowOf(home).nextStartedActivity.component!!.className)
    }

    @Test
    fun homeSaysWhatIsMissingForLiveValues() {
        seedScan()
        allowBluetooth()
        settings.liveEnabled = true
        settings.liveState = LiveState.NEEDS_SCAN
        val home = open(MainActivity::class.java)
        assertEquals("Hold the phone to the sensor once. The scan sets up the connection.", text(home, R.id.liveState))
        assertEquals("a problem is red", app.getColor(R.color.red), home.findViewById<TextView>(R.id.liveState).currentTextColor)
        // the sign at the top says that something is wrong; its dot does not pulse
        assertEquals("PROBLEM", text(home, R.id.livePillText))
        assertEquals(MainActivity.LiveSign.PROBLEM, home.liveSign)
        assertFalse(home.findViewById<PulseDotView>(R.id.liveDot).pulsing)
    }

    @Test
    fun homeWithoutLiveValuesLooksAsBefore() {
        seedScan()
        val home = open(MainActivity::class.java)
        assertFalse(isShown(home, R.id.liveCard))
        assertFalse("no sign at the top while live values are off", isShown(home, R.id.livePill))
        assertEquals(MainActivity.LiveSign.NONE, home.liveSign)
        assertNull("no service", shadowOf(app).nextStartedService)
        assertTrue(home.findViewById<View>(R.id.tabHome).isSelected)
    }

    @Test
    fun withoutPermissionTheHomeScreenSaysSo() {
        settings.liveEnabled = true
        settings.liveState = LiveState.SEARCHING
        val home = open(MainActivity::class.java)
        assertEquals("The app may not use Bluetooth yet. Open More, Live values and allow it.", text(home, R.id.liveState))
        assertNull("the service cannot run without the permission", shadowOf(app).nextStartedService)
    }

    // ------------------------------------------------------------ statistics

    @Test
    fun statisticsOfFourteenDays() {
        seedDays(14)
        val screen = open(StatisticsActivity::class.java)
        assertEquals("Statistics", screen.title.toString())
        assertTrue("14 days is the first choice", screen.findViewById<View>(R.id.days14).isSelected)
        assertTrue(isShown(screen, R.id.statsContent))
        assertFalse(isShown(screen, R.id.statsEmpty))

        // the screen shows what the calculator gives for the same values
        val to = now + minute
        val from = to - 14 * StatisticsCalculator.DAY
        val expected = StatisticsCalculator.compute(store.between(from, to), from, to, settings.lowLimit, settings.highLimit)
        val summary = rows(screen, R.id.summaryBox)
        assertEquals("Average=${Math.round(expected.mean!!)} mg/dL", summary[0])
        assertEquals("Estimated A1c (GMI)=" + String.format(java.util.Locale.US, "%.1f", expected.gmi!!) + " %", summary[1])
        assertEquals("Variation (CV)=${Math.round(expected.cv!!)} %", summary[2])
        assertEquals("Lowest and highest=${expected.lowest} to ${expected.highest} mg/dL", summary[3])
        assertEquals("Time with values=100 %", summary[4])
        assertEquals("Days with values=14", summary[5])
        assertTrue(expected.gmiIsReliable)

        val percents = expected.rangePercents
        assertEquals(100, percents.sum())
        assertEquals(
            listOf(
                "Very high: over 250 mg/dL=${percents[4]} %",
                "High: over 180 to 250 mg/dL=${percents[3]} %",
                "In range: 70 to 180 mg/dL=${percents[2]} %",
                "Low: 54 to under 70 mg/dL=${percents[1]} %",
                "Very low: under 54 mg/dL=${percents[0]} %"
            ),
            rows(screen, R.id.rangesBox)
        )
        assertTrue("the made-up values lie in several ranges", percents[2] in 50..95 && percents[3] > 0)
        assertEquals("In your target range (80 to 180 mg/dL): ${expected.inTargetPercent} %", text(screen, R.id.targetLine))
        assertEquals(24, expected.hours.count { it != null })
        picture(screen, "21-statistics")

        // fewer days: the estimate of the A1c is marked as rough
        click(screen, R.id.days7)
        assertEquals(7, settings.statisticsDays)
        assertTrue(screen.findViewById<View>(R.id.days7).isSelected)
        assertFalse(screen.findViewById<View>(R.id.days14).isSelected)
        assertTrue(rows(screen, R.id.summaryBox)[1], rows(screen, R.id.summaryBox)[1].endsWith("(rough: needs 14 days with values most of the time)"))

        // more days than there are values: the time with values is not full
        click(screen, R.id.days30)
        assertEquals("Time with values=47 %", rows(screen, R.id.summaryBox)[4])
    }

    @Test
    fun statisticsInMmol() {
        settings.unit = GlucoseUnit.MMOLL
        seedDays(14)
        val screen = open(StatisticsActivity::class.java)
        val summary = rows(screen, R.id.summaryBox)
        assertTrue(summary[0], Regex("Average=\\d+\\.\\d mmol/L").matches(summary[0]))
        assertEquals("In range: 3.9 to 10.0 mmol/L", rows(screen, R.id.rangesBox)[2].substringBefore("="))
        assertEquals("Very low: under 3.0 mmol/L", rows(screen, R.id.rangesBox)[4].substringBefore("="))
        assertTrue(text(screen, R.id.targetLine).startsWith("In your target range (4.4 to 10.0 mmol/L): "))
    }

    @Test
    fun statisticsUseTheCorrectedValues() {
        seedScan()
        val plain = open(StatisticsActivity::class.java)
        val before = plain.last!!.mean!!

        // the user has told the app that the sensor reads 8 mg/dL too low
        val correction = Correction.NONE.adding(serial, ReferencePair(now - 5 * minute, 104, 112))
        settings.correction = correction
        val corrected = open(StatisticsActivity::class.java)
        val to = now + minute
        val from = to - 14 * StatisticsCalculator.DAY
        val expected = StatisticsCalculator.compute(correction.applyAll(store.between(from, to)), from, to, settings.lowLimit, settings.highLimit)
        assertEquals(expected.mean!!, corrected.last!!.mean!!, 0.0)
        assertTrue("the numbers are those of the shown values", corrected.last!!.mean!! > before + 5)
    }

    @Test
    fun statisticsWithoutValues() {
        val screen = open(StatisticsActivity::class.java)
        assertTrue(isShown(screen, R.id.statsEmpty))
        assertFalse(isShown(screen, R.id.statsContent))
        assertTrue(words(screen).contains("No values in this time. Scan the sensor, or switch on live values."))
        picture(screen, "21b-statistics-empty")
    }

    @Test
    @Config(qualifiers = "w360dp-h1500dp-night-xhdpi")
    fun statisticsAtNight() {
        seedDays(30)
        settings.statisticsDays = 30
        val screen = open(StatisticsActivity::class.java)
        assertTrue(screen.findViewById<View>(R.id.days30).isSelected)
        assertEquals("Time with values=100 %", rows(screen, R.id.summaryBox)[4])
        picture(screen, "21c-statistics-night")
    }

    // ------------------------------------------------------------ More: the file with all data

    @Test
    fun allDataCanBeSavedAsAFile() {
        seedScan()
        val more = open(MoreActivity::class.java)

        // the button lets Android ask where the file goes
        click(more, R.id.buttonExport)
        val ask = shadowOf(more).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, ask.action)
        assertEquals("text/csv", ask.type)
        assertEquals("walhad-glucose-2026-11-10.csv", ask.getStringExtra(Intent.EXTRA_TITLE))

        val uri = Uri.parse("content://documents/walhad.csv")
        val file = ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(uri, file)
        more.export(uri)
        val end = System.currentTimeMillis() + 20_000
        while (MoreActivity.lastExportMessage == null && System.currentTimeMillis() < end) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("Saved. Readings: 47.", MoreActivity.lastExportMessage)

        val lines = file.toString("UTF-8").trim().split("\n").map { it.trim() }
        assertEquals(CsvExport.HEADER, lines[0])
        assertEquals(1 + 47, lines.size)
        assertEquals("kind,time,mg/dL,mmol/L,sensor value mg/dL,note,sensor", lines[0])
        assertTrue(lines.any { it.contains(",104,") })
    }

    @Test
    fun withoutDataThereIsNothingToSave() {
        val more = open(MoreActivity::class.java)
        click(more, R.id.buttonExport)
        assertNull(shadowOf(more).nextStartedActivityForResult)
    }

    @Test
    fun moreShowsIfLiveValuesAreOn() {
        settings.liveEnabled = true
        val more = open(MoreActivity::class.java)
        val row = more.findViewById<View>(R.id.buttonLive)
        assertEquals("Bluetooth values", row.findViewById<TextView>(R.id.navTitle).text.toString())
        assertEquals("On", row.findViewById<TextView>(R.id.navState).text.toString())
        assertEquals("for somebody who cannot see the screen", "Bluetooth values: On. Values from the sensor", row.contentDescription.toString())
    }

    // ------------------------------------------------------------ live values

    @Test
    fun liveValuesAreOffUntilTheUserSaysYesTwice() {
        val screen = open(LiveActivity::class.java)
        val switch = screen.findViewById<Switch>(R.id.liveSwitch)
        assertFalse(switch.isChecked)
        assertFalse(isShown(screen, R.id.liveStatusCard))
        assertFalse(isShown(screen, R.id.liveBatteryCard))
        val shown = words(screen)
        assertTrue(shown.contains("FreeStyle LibreLink gets no more values over Bluetooth and its alarms stop"))
        assertTrue(shown.contains("tested on a computer, not with a real sensor"))
        assertTrue(shown.contains("To give the connection back: switch live values off here, then scan the sensor with LibreLink."))
        picture(screen, "24-live-values-off")

        // the switch asks once more
        switch.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("Switch on live values?", shadowOf(dialog).title.toString())
        val question = shadowOf(dialog).message.toString()
        assertTrue(question.contains("the alarms of LibreLink stop"))
        assertTrue(question.contains("The alarms of this app were never tried on a real phone. Do not rely on them."))
        assertFalse("not yet", settings.liveEnabled)

        // "Cancel" leaves everything off
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(settings.liveEnabled)
        assertFalse(switch.isChecked)

        // "Switch on"
        settings.liveSensor = liveSensor() // an older set-up, perhaps taken back by LibreLink since
        switch.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(settings.liveEnabled)
        assertTrue(switch.isChecked)
        assertNull("the next scan sets the sensor up anew", settings.liveSensor)
        assertTrue(settings.livePairWanted)
        assertEquals(now, settings.liveWaitingSince)

        // Android asks for the permissions
        val asked = shadowOf(screen).lastRequestedPermission
        assertNotNull(asked)
        assertTrue(asked.requestedPermissions.toList().containsAll(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)))
        assertNull("no service before Bluetooth is allowed", shadowOf(app).nextStartedService)
        assertTrue(isShown(screen, R.id.liveStatusCard))
        assertEquals("The app may not use Bluetooth yet. Open More, Live values and allow it.", text(screen, R.id.liveStateText))
        assertTrue(isShown(screen, R.id.liveAllowBluetooth))
        assertTrue(isShown(screen, R.id.livePermissionNote))

        // the user allows it
        allowBluetooth()
        screen.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, IntArray(asked.requestedPermissions.size) { PackageManager.PERMISSION_GRANTED })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(LiveService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        assertFalse(isShown(screen, R.id.liveAllowBluetooth))
        assertFalse(isShown(screen, R.id.liveAllowNotifications))
        assertEquals("Hold the phone to the sensor once. The scan sets up the connection.", text(screen, R.id.liveStateText))
        assertEquals("No sensor set up yet", text(screen, R.id.liveSensorText))
        assertFalse("nothing to set up again", isShown(screen, R.id.livePairAgain))
        assertEquals("No message from the sensor yet", text(screen, R.id.liveMessageText))
        picture(screen, "25-live-values-waiting-for-a-scan")
    }

    @Test
    fun theLiveScreenShowsTheConnection() {
        allowBluetooth()
        settings.liveEnabled = true
        settings.liveSensor = liveSensor()
        settings.liveState = LiveState.RECEIVING
        settings.liveLastMessageTime = now - 20_000
        settings.liveLog = "11-10 14:02:11 sensor 3MH001HM1LM set up for live values by a scan\n11-10 14:02:14 state: SEARCHING\n11-10 14:02:19 found the sensor at A4:11:22:33:44:55\n11-10 14:02:21 state: RECEIVING"
        val screen = open(LiveActivity::class.java)
        assertTrue(screen.findViewById<Switch>(R.id.liveSwitch).isChecked)
        assertEquals("Connected. A new value each minute.", text(screen, R.id.liveStateText))
        assertEquals("Sensor 3MH001HM1LM", text(screen, R.id.liveSensorText))
        assertEquals("Last message: 2:32 PM", text(screen, R.id.liveMessageText))
        assertTrue(text(screen, R.id.liveLogText).contains("found the sensor"))
        assertTrue(isShown(screen, R.id.liveBatteryCard))
        assertTrue("the phone may stop the app: a button asks to allow background use", isShown(screen, R.id.liveBatteryButton))
        picture(screen, "25b-live-values-connected")

        // "set up again": the next scan sends the command to the sensor again
        assertTrue(isShown(screen, R.id.livePairAgain))
        assertFalse(settings.livePairWanted)
        click(screen, R.id.livePairAgain)
        assertTrue(settings.livePairWanted)

        // the button for the battery opens the Android screen for it
        click(screen, R.id.liveBatteryButton)
        assertEquals(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, shadowOf(screen).nextStartedActivity.action)

        // switching off needs no question
        screen.findViewById<Switch>(R.id.liveSwitch).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(settings.liveEnabled)
        assertEquals(LiveState.OFF, settings.liveState)
        assertFalse(isShown(screen, R.id.liveStatusCard))
        assertNotNull("the sensor stays known, for the next time", settings.liveSensor)
    }

    // ------------------------------------------------------------ alarms

    @Test
    fun alarmsScreen() {
        val screen = open(AlarmsActivity::class.java)
        assertTrue("alarms need live values: said at the top", isShown(screen, R.id.alarmsNeedLive))
        assertTrue(screen.findViewById<Switch>(R.id.alarmLowSwitch).isChecked)
        assertFalse(screen.findViewById<Switch>(R.id.alarmHighSwitch).isChecked)
        assertTrue(screen.findViewById<Switch>(R.id.alarmNoDataSwitch).isChecked)
        assertEquals("70 mg/dL", text(screen, R.id.alarmLowValue))
        assertEquals("250 mg/dL", text(screen, R.id.alarmHighValue))
        picture(screen, "26-alarms")

        click(screen, R.id.alarmLowPlus)
        assertEquals(75, settings.alarmLow)
        assertEquals("75 mg/dL", text(screen, R.id.alarmLowValue))
        repeat(10) { click(screen, R.id.alarmLowMinus) }
        assertEquals("not lower than 55", 55, settings.alarmLow)
        repeat(30) { click(screen, R.id.alarmLowPlus) }
        assertEquals(120, settings.alarmLow)

        click(screen, R.id.alarmHighMinus)
        assertEquals(240, settings.alarmHigh)
        repeat(30) { click(screen, R.id.alarmHighMinus) }
        assertEquals(130, settings.alarmHigh)
        assertEquals("130 mg/dL", text(screen, R.id.alarmHighValue))

        screen.findViewById<Switch>(R.id.alarmHighSwitch).performClick()
        screen.findViewById<Switch>(R.id.alarmLowSwitch).performClick()
        screen.findViewById<Switch>(R.id.alarmNoDataSwitch).performClick()
        assertTrue(settings.alarmHighOn)
        assertFalse(settings.alarmLowOn)
        assertFalse(settings.alarmNoDataOn)

        click(screen, R.id.alarmsOpenLive)
        assertEquals(LiveActivity::class.java.name, shadowOf(screen).nextStartedActivity.component!!.className)
    }

    @Test
    fun theWarningBeforeALowCanBeSwitchedOn() {
        val screen = open(AlarmsActivity::class.java)
        val switch = screen.findViewById<Switch>(R.id.alarmLowSoonSwitch)
        assertFalse("off from the start: it is a guess, and the user decides", switch.isChecked)
        assertEquals("Warn before a low comes", switch.text.toString())
        assertTrue(words(screen).contains("It is a guess: it can ring when no low comes, and it can miss a fast low."))
        switch.performClick()
        assertTrue(settings.alarmLowSoonOn)
        assertTrue(settings.alarmSettings.lowSoonOn)
        assertTrue("the choice is kept", open(AlarmsActivity::class.java).findViewById<Switch>(R.id.alarmLowSoonSwitch).isChecked)
        switch.performClick()
        assertFalse(settings.alarmLowSoonOn)
    }

    @Test
    fun theAlarmSoundCanBeTried() {
        settings.liveEnabled = true
        settings.unit = GlucoseUnit.MMOLL
        val screen = open(AlarmsActivity::class.java)
        assertFalse(isShown(screen, R.id.alarmsNeedLive))
        assertEquals("3.9 mmol/L", text(screen, R.id.alarmLowValue))
        assertEquals("13.9 mmol/L", text(screen, R.id.alarmHighValue))

        val test = screen.findViewById<Button>(R.id.alarmsTest)
        assertEquals("Test the alarm sound", test.text.toString())
        test.performClick()
        assertEquals(1, AlarmSounder.plays)
        assertEquals("Stop the sound", test.text.toString())
        test.performClick()
        assertEquals("Test the alarm sound", test.text.toString())
        assertEquals(1, AlarmSounder.plays)

        // it stops by itself, and when the screen is left
        test.performClick()
        assertEquals(2, AlarmSounder.plays)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(9))
        assertEquals("Test the alarm sound", test.text.toString())
    }

    // ------------------------------------------------------------ voice

    @Test
    fun voiceScreen() {
        val screen = open(VoiceActivity::class.java)
        assertTrue(screen.findViewById<Switch>(R.id.voiceAfterScan).isChecked)
        assertTrue(screen.findViewById<Switch>(R.id.voiceTrend).isChecked)
        assertTrue(screen.findViewById<Switch>(R.id.voiceAlarms).isChecked)
        assertTrue("live values are not said unless chosen", screen.findViewById<View>(R.id.voiceLive0).isSelected)
        assertEquals("Example: Glucose 104. Steady.", text(screen, R.id.voiceExample))
        assertFalse(isShown(screen, R.id.voiceStatus))
        picture(screen, "27-voice")

        click(screen, R.id.voiceLive15)
        assertEquals(15, settings.speakLiveMinutes)
        assertTrue(screen.findViewById<View>(R.id.voiceLive15).isSelected)
        assertFalse(screen.findViewById<View>(R.id.voiceLive0).isSelected)

        screen.findViewById<Switch>(R.id.voiceTrend).performClick()
        assertFalse(settings.speakTrend)
        assertEquals("Example: Glucose 104.", text(screen, R.id.voiceExample))
        screen.findViewById<Switch>(R.id.voiceAfterScan).performClick()
        screen.findViewById<Switch>(R.id.voiceAlarms).performClick()
        assertFalse(settings.speakAfterScan)
        assertFalse(settings.speakAlarms)

        // the test says a made-up value and says that it is a test
        click(screen, R.id.voiceTest)
        assertEquals("This is a test. Glucose 104.", Speaker.lastRequest)
        assertTrue(isShown(screen, R.id.voiceStatus))
    }

    @Test
    fun theVoiceCanLeaveOutBelowTargetSoon() {
        val screen = open(VoiceActivity::class.java)
        val switch = screen.findViewById<Switch>(R.id.voiceLowSoon)
        assertTrue("on from the start", switch.isChecked)
        assertEquals("Say \"Below target soon\" when the values head below your target range", switch.text.toString())
        switch.performClick()
        assertFalse(settings.speakLowSoon)
        assertFalse(open(VoiceActivity::class.java).findViewById<Switch>(R.id.voiceLowSoon).isChecked)
    }

    @Test
    fun voiceExampleInMmol() {
        settings.unit = GlucoseUnit.MMOLL
        val screen = open(VoiceActivity::class.java)
        assertEquals("Example: Glucose 5.8. Steady.", text(screen, R.id.voiceExample))
    }

    // ------------------------------------------------------------ settings and first start

    @Test
    fun deletingAllData() {
        seedScan()
        val screen = open(SettingsActivity::class.java)
        assertEquals("Saved readings: 47. Readings older than 90 days are removed.", text(screen, R.id.readingsCount))
        assertEquals("Delete all readings", text(screen, R.id.deleteButton))
        click(screen, R.id.deleteButton)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("Delete all saved readings from this phone? You cannot undo this.", shadowOf(dialog).message.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, store.count())
    }

    @Test
    fun theTextsNoLongerSayThatThereAreNoAlarms() {
        settings.noticeAccepted = false
        val welcome = open(WelcomeActivity::class.java)
        val notice = words(welcome)
        assertFalse(notice.contains("This app has no alarms"))
        assertTrue(notice.contains("unless you let the values come by themselves (from LibreLinkUp, or over Bluetooth). Its alarms work only then, and they can fail."))
        assertTrue(notice.contains("This app is not a medical device."))

        settings.noticeAccepted = true
        val about = words(open(AboutActivity::class.java))
        assertFalse(about.contains("It only reads. It never changes the sensor."))
        assertTrue(about.contains("A scan only reads. It never changes the sensor. Only when you switch on live values, the app tells the sensor to send its values to this app."))
    }

    @Test
    fun theNewScreensNeedTheSafetyNoticeToo() {
        settings.noticeAccepted = false
        for (screen in listOf(StatisticsActivity::class.java, AnalysisActivity::class.java, ReportActivity::class.java, MoreActivity::class.java, LiveActivity::class.java, AlarmsActivity::class.java, VoiceActivity::class.java)) {
            val activity = Robolectric.buildActivity(screen).setup().get()
            assertTrue(screen.simpleName, activity.isFinishing)
            assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        }
    }
}
