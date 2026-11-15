package com.glucosescan.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.nfc.NfcAdapter
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import com.glucosescan.app.core.Correction
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.ReferencePair
import com.glucosescan.app.core.ScanStatus
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.ScanMessage
import com.glucosescan.app.data.ScanOutcome
import com.glucosescan.app.data.SensorSnapshot
import com.glucosescan.app.data.Settings
import com.glucosescan.app.live.AlarmSounder
import com.glucosescan.app.nfc.ScanRunner
import com.glucosescan.app.ui.AppClock
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
import org.robolectric.shadows.ShadowNfcAdapter
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/**
 * Opens every screen with prepared data, checks what it shows, and saves pictures of the
 * screens to docs/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1500dp-xhdpi")
class ScreensTest {
    private val minute = 60_000L
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private var now = 0L

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Mogadishu"))
        GlucoseStore.closeShared()
        app = RuntimeEnvironment.getApplication()
        settings = Settings(app)
        store = GlucoseStore.get(app)
        // Tuesday 10 November 2026, 14:32:20 local time (UTC+3) (11:32:20 UTC)
        now = 1_794_310_340_000L
        AppClock.source = { now }
        ScanRunner.reset()
        Speaker.reset()
        AlarmSounder.plays = 0
        // a phone with NFC that is switched on
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        ShadowNfcAdapter.setNfcHardwareExists(true)
        val adapter = NfcAdapter.getDefaultAdapter(app)
        assertNotNull("the test phone has NFC", adapter)
        shadowOf(adapter).setEnabled(true)
    }

    @After
    fun tearDown() {
        GlucoseStore.closeShared()
        AppClock.source = { System.currentTimeMillis() }
    }

    // ------------------------------------------------------------ helpers

    private fun accept(unit: GlucoseUnit) {
        settings.noticeAccepted = true
        settings.unit = unit
    }

    /**
     * Saves values like a scan would: one each minute for 16 minutes, before that one each
     * 15 minutes. The newest value is [newest] mg/dL and [ageMinutes] old. It was 10 mg/dL
     * higher 15 minutes before.
     */
    private fun seed(newest: Int = 104, ageMinutes: Int = 0, hours: Int = 8): Long {
        val latestTime = (now - now % minute) - ageMinutes * minute
        val values = ArrayList<Glucose>()
        fun shape(minutesBefore: Int): Int {
            if (minutesBefore <= 15) {
                return newest + Math.round(minutesBefore * 10 / 15f)
            }
            val wave = 78 * Math.sin((minutesBefore - 15) / 58.0)
            return Math.max(55, Math.round(newest + 10 + wave).toInt())
        }
        var m = 20
        while (m <= hours * 60) {
            values.add(Glucose(latestTime - m * minute, shape(m), null, false, "3MH001HM1LM"))
            m += 15
        }
        for (k in 15 downTo 0) {
            values.add(Glucose(latestTime - k * minute, shape(k), null, true, "3MH001HM1LM"))
        }
        store.insert(values)
        return latestTime
    }

    private fun sensor(status: ScanStatus = ScanStatus.OK, age: Int = 10320, scanTime: Long = now) {
        settings.lastSensor = SensorSnapshot("3MH001HM1LM", "Libre 2 EU", "1 - European", status, age, 20659, scanTime)
    }

    private fun main(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        return activity
    }

    private fun text(activity: Activity, id: Int): String = activity.findViewById<TextView>(id).text.toString()

    private fun isShown(activity: Activity, id: Int): Boolean = activity.findViewById<View>(id).visibility == View.VISIBLE

    /** The drawable resource that a view has as its ground. */
    private fun ground(activity: Activity, id: Int): Int = shadowOf(activity.findViewById<View>(id).background).createdFromResId

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

    /** The big number and its arrow stand on one line, side by side, inside the coloured card. */
    private fun assertBigValueFits(activity: Activity) {
        val value = activity.findViewById<TextView>(R.id.value)
        val arrow = activity.findViewById<TextView>(R.id.arrow)
        val card = activity.findViewById<View>(R.id.valueCard)
        assertEquals("the number is on one line", 1, value.lineCount)
        var needed = value.paint.measureText(value.text.toString())
        if (arrow.visibility == View.VISIBLE) {
            assertEquals("the arrow is on one line", 1, arrow.lineCount)
            needed += arrow.paint.measureText(arrow.text.toString()) + (arrow.layoutParams as ViewGroup.MarginLayoutParams).marginStart
        }
        val room = card.width - card.paddingLeft - card.paddingRight
        assertTrue("card is laid out", room > 0)
        assertTrue("needs $needed px, has $room px", needed <= room)
        assertTrue("nothing is cut: ${value.width} of ${value.paint.measureText(value.text.toString())}", value.width >= value.paint.measureText(value.text.toString()) - 1)
    }

    /** The brand logo is on the screen, as a real picture with a size. */
    private fun logoIsShown(activity: Activity): Boolean {
        val logo = activity.findViewById<ImageView>(R.id.brandLogo)
        val picture = logo.drawable as? BitmapDrawable ?: return false
        return logo.visibility == View.VISIBLE && logo.width > 0 && logo.height > 0 &&
            picture.bitmap.width == 480 && picture.bitmap.height == 480 &&
            logo.contentDescription == "Walhad Glucose Scan logo"
    }

    private fun sensorCard(activity: Activity): String {
        val box = activity.findViewById<LinearLayout>(R.id.sensorBox)
        val lines = ArrayList<String>()
        for (i in 0 until box.childCount) {
            val row = box.getChildAt(i)
            lines.add(row.findViewById<TextView>(R.id.label).text.toString() + "=" + row.findViewById<TextView>(R.id.text).text.toString())
        }
        return lines.joinToString("|")
    }

    private fun picture(activity: Activity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        picture(activity.window.decorView, name)
    }

    private fun picture(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val folder = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
        folder.mkdirs()
        FileOutputStream(File(folder, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ------------------------------------------------------------ first start

    @Test
    fun theSafetyNoticeComesBeforeEverythingElse() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(activity.isFinishing)
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(WelcomeActivity::class.java.name, next.component!!.className)

        // also if only the unit is missing, or only the acceptance
        settings.noticeAccepted = true
        assertTrue(Robolectric.buildActivity(MainActivity::class.java).setup().get().isFinishing)
        settings.noticeAccepted = false
        settings.unit = GlucoseUnit.MGDL
        assertTrue(Robolectric.buildActivity(MainActivity::class.java).setup().get().isFinishing)
    }

    @Test
    fun welcomeNeedsUnitAndAcceptance() {
        val activity = Robolectric.buildActivity(WelcomeActivity::class.java).setup().get()
        val start = activity.findViewById<Button>(R.id.start)
        val accept = activity.findViewById<CheckBox>(R.id.accept)
        assertTrue(logoIsShown(activity))
        picture(activity, "01-first-start")

        assertFalse(start.isEnabled)
        accept.performClick()
        assertFalse("no unit chosen yet", start.isEnabled)
        activity.findViewById<RadioButton>(R.id.unitMmoll).performClick()
        assertTrue(start.isEnabled)
        accept.performClick()
        assertFalse("not accepted", start.isEnabled)
        accept.performClick()
        assertTrue(start.isEnabled)

        assertFalse(settings.noticeAccepted)
        start.performClick()
        assertTrue(settings.noticeAccepted)
        assertEquals(GlucoseUnit.MMOLL, settings.unit)
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertTrue(activity.isFinishing)
    }

    // ------------------------------------------------------------ main screen

    @Test
    fun mainShowsACurrentValue() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104)
        sensor()
        settings.lastOutcome = ScanOutcome(ScanMessage.OK, now)
        val activity = main()

        assertEquals("104", text(activity, R.id.value))
        assertEquals("mg/dL", text(activity, R.id.unit))
        assertEquals("\u2192", text(activity, R.id.arrow))
        assertEquals("Steady \u00b7 \u22120.7 mg/dL per minute", text(activity, R.id.trendLine))
        assertEquals("Change over the last 15 minutes", text(activity, R.id.trendBasis))
        assertTrue(text(activity, R.id.valueTime).endsWith("just now"))
        assertFalse(isShown(activity, R.id.valueNote))
        assertFalse(isShown(activity, R.id.lastValue))
        // a green card with white words, and the words "In target range"
        assertEquals(MainActivity.ValueLook.IN_RANGE, activity.valueLook)
        assertEquals(R.drawable.hero_good, ground(activity, R.id.valueCard))
        assertEquals("In target range", text(activity, R.id.rangeChip))
        assertTrue(isShown(activity, R.id.rangeChip))
        assertEquals(Color.WHITE, activity.findViewById<TextView>(R.id.value).currentTextColor)
        assertEquals(Color.WHITE, activity.findViewById<TextView>(R.id.valueTime).currentTextColor)
        assertEquals(R.drawable.pill_on_hero, ground(activity, R.id.valueTime))
        assertBigValueFits(activity)

        // the NFC sign stands next to the words about scanning
        val sign = activity.findViewById<TextView>(R.id.nfcSign)
        assertEquals(View.VISIBLE, sign.visibility)
        assertEquals("NFC", sign.text.toString())
        val signPicture = sign.compoundDrawables[1]
        assertNotNull("the sign is drawn over the word", signPicture)
        assertTrue(signPicture.intrinsicWidth > 0 && signPicture.intrinsicWidth == signPicture.intrinsicHeight)
        assertTrue(sign.height > signPicture.intrinsicHeight)

        assertTrue(text(activity, R.id.scanStatus).endsWith("Scan OK"))
        assertEquals(app.getColor(R.color.green), activity.findViewById<TextView>(R.id.scanStatus).currentTextColor)
        assertEquals(app.getString(R.string.hint_scan), text(activity, R.id.scanHint))
        assertFalse(isShown(activity, R.id.nfcSettings))

        val card = sensorCard(activity)
        assertTrue(card, card.contains("State=Ready"))
        assertTrue(card, card.contains("Age=7d 4h 0min"))
        assertTrue(card, card.contains("Time left=7d 4h 19min"))
        assertTrue(card, card.contains("Type=Libre 2 EU"))
        assertTrue(card, card.contains("Serial number=3MH001HM1LM"))

        assertTrue(activity.findViewById<View>(R.id.range6).isSelected)
        assertTrue(shadowOf(NfcAdapter.getDefaultAdapter(app)).isInReaderMode)
        picture(activity, "02-main")
    }

    @Test
    fun mainInMmol() {
        accept(GlucoseUnit.MMOLL)
        seed(newest = 104)
        sensor()
        settings.lastOutcome = ScanOutcome(ScanMessage.OK, now)
        val activity = main()
        assertEquals("5.8", text(activity, R.id.value))
        assertEquals("mmol/L", text(activity, R.id.unit))
        assertEquals("Steady \u00b7 \u22120.04 mmol/L per minute", text(activity, R.id.trendLine))
        picture(activity, "03-main-mmol")
    }

    @Test
    fun aValueOutsideTheTargetRangeIsRed() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 200)
        val high = main()
        assertEquals("200", text(high, R.id.value))
        assertEquals(MainActivity.ValueLook.ALERT, high.valueLook)
        assertEquals(R.drawable.hero_alert, ground(high, R.id.valueCard))
        assertEquals("Above target range", text(high, R.id.rangeChip))

        store.deleteAll()
        seed(newest = 72)
        val low = main()
        assertEquals("72", text(low, R.id.value))
        assertEquals(MainActivity.ValueLook.ALERT, low.valueLook)
        assertEquals(R.drawable.hero_alert, ground(low, R.id.valueCard))
        assertEquals("Below target range", text(low, R.id.rangeChip))
        assertEquals("low and still falling: not \"Steady\"", "Falling slowly \u00b7 \u22120.7 mg/dL per minute", text(low, R.id.trendLine))
        picture(low, "04-main-low-value")

        // exactly on a limit is still inside the range
        store.deleteAll()
        seed(newest = 80)
        val onLowLimit = main()
        assertEquals("80", text(onLowLimit, R.id.value))
        assertEquals(MainActivity.ValueLook.IN_RANGE, onLowLimit.valueLook)
        assertEquals("In target range", text(onLowLimit, R.id.rangeChip))
        store.deleteAll()
        seed(newest = 180)
        val onHighLimit = main()
        assertEquals(MainActivity.ValueLook.IN_RANGE, onHighLimit.valueLook)
        assertEquals(R.drawable.hero_good, ground(onHighLimit, R.id.valueCard))
    }

    @Test
    fun aValueOfSomeMinutesShowsItsAge() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104, ageMinutes = 8)
        val activity = main()
        assertEquals("104", text(activity, R.id.value))
        assertTrue(text(activity, R.id.valueTime).endsWith("8 minutes ago"))
        // the time stands on a yellow patch, in bold dark words
        val time = activity.findViewById<TextView>(R.id.valueTime)
        assertEquals(R.drawable.pill_amber, ground(activity, R.id.valueTime))
        assertEquals(app.getColor(R.color.on_amber), time.currentTextColor)
        assertTrue("bold words", time.typeface.isBold)
        assertEquals(MainActivity.ValueLook.IN_RANGE, activity.valueLook)
        assertTrue(isShown(activity, R.id.arrow))
        picture(activity, "04b-main-value-some-minutes-old")
    }

    @Test
    fun anOldValueIsNotShownAsCurrent() {
        accept(GlucoseUnit.MGDL)
        val time = seed(newest = 104, ageMinutes = 42)
        sensor(scanTime = time)
        settings.lastOutcome = ScanOutcome(ScanMessage.OK, time)
        val activity = main()

        assertEquals("\u2013 \u2013", text(activity, R.id.value))
        assertEquals("No current value", text(activity, R.id.valueNote))
        assertTrue(text(activity, R.id.lastValue).startsWith("Last value: 104 mg/dL, 42 minutes ago ("))
        assertFalse(isShown(activity, R.id.arrow))
        assertFalse(isShown(activity, R.id.unit))
        assertFalse(isShown(activity, R.id.trendLine))
        assertFalse(isShown(activity, R.id.valueTime))
        assertFalse("no words about the range without a current value", isShown(activity, R.id.rangeChip))
        assertEquals(MainActivity.ValueLook.NO_VALUE, activity.valueLook)
        assertEquals(R.drawable.hero_none, ground(activity, R.id.valueCard))
        picture(activity, "05-main-old-value")
    }

    @Test
    fun lowAndHighAreSpelledOut() {
        accept(GlucoseUnit.MGDL)
        store.insert(listOf(Glucose(now, 30, null, true)))
        val low = main()
        assertEquals("LOW", text(low, R.id.value))
        assertEquals("Below 40 mg/dL", text(low, R.id.valueNote))
        assertTrue(isShown(low, R.id.valueNote))
        assertEquals(MainActivity.ValueLook.ALERT, low.valueLook)
        assertEquals(R.drawable.hero_alert, ground(low, R.id.valueCard))
        assertEquals("Below target range", text(low, R.id.rangeChip))
        assertFalse(isShown(low, R.id.arrow))
        assertBigValueFits(low)

        store.deleteAll()
        settings.unit = GlucoseUnit.MMOLL
        store.insert(listOf(Glucose(now, 620, null, true)))
        val high = main()
        assertEquals("HIGH", text(high, R.id.value))
        assertEquals("Above 27.8 mmol/L", text(high, R.id.valueNote))
        assertEquals(MainActivity.ValueLook.ALERT, high.valueLook)
        assertEquals("Above target range", text(high, R.id.rangeChip))
        assertBigValueFits(high)
        picture(high, "04c-main-high")
    }

    @Test
    fun nothingScannedYet() {
        accept(GlucoseUnit.MGDL)
        val activity = main()
        assertEquals("\u2013 \u2013", text(activity, R.id.value))
        assertEquals("No value yet", text(activity, R.id.valueNote))
        assertEquals(MainActivity.ValueLook.NO_VALUE, activity.valueLook)
        assertEquals(R.drawable.hero_none, ground(activity, R.id.valueCard))
        assertFalse(isShown(activity, R.id.rangeChip))
        assertFalse(isShown(activity, R.id.scanStatus))
        assertTrue(sensorCard(activity).startsWith("No sensor scanned yet."))
        picture(activity, "06-main-empty")
    }

    @Test
    fun aValueFromTheFutureIsNotShown() {
        accept(GlucoseUnit.MGDL)
        store.insert(listOf(Glucose(now + 60 * minute, 104, null, true)))
        val activity = main()
        assertEquals("\u2013 \u2013", text(activity, R.id.value))
        assertEquals(app.getString(R.string.clock_wrong), text(activity, R.id.valueNote))
        assertTrue(isShown(activity, R.id.valueNote))
        assertEquals("a problem is shown on the red card", MainActivity.ValueLook.ALERT, activity.valueLook)
        assertEquals(R.drawable.hero_alert, ground(activity, R.id.valueCard))
        assertFalse(isShown(activity, R.id.rangeChip))
    }

    @Test
    fun anEndedSensorIsShownAsAProblem() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104, ageMinutes = 3)
        sensor(status = ScanStatus.EXPIRED, age = 20700)
        settings.lastOutcome = ScanOutcome(ScanMessage.EXPIRED, now)
        val activity = main()
        assertTrue(text(activity, R.id.scanStatus).endsWith("This sensor has ended. It gives no more values."))
        assertEquals(app.getColor(R.color.red), activity.findViewById<TextView>(R.id.scanStatus).currentTextColor)
        val card = sensorCard(activity)
        assertTrue(card, card.contains("State=Ended"))
        assertFalse(card, card.contains("Time left"))
        picture(activity, "07-main-sensor-ended")
    }

    @Test
    fun aRunningSensorPastItsEndIsShownAsEnded() {
        accept(GlucoseUnit.MGDL)
        // scanned 2 days ago, 1 day before its end
        sensor(status = ScanStatus.OK, age = 20659 - 1440, scanTime = now - 2 * 1440 * minute)
        val card = sensorCard(main())
        assertTrue(card, card.contains("State=Ended"))
    }

    @Test
    fun warmingUpShowsTheTimeLeft() {
        accept(GlucoseUnit.MGDL)
        sensor(status = ScanStatus.STARTING, age = 25, scanTime = now - 10 * minute)
        settings.lastOutcome = ScanOutcome(ScanMessage.STARTING, now - 10 * minute, number = 35)
        val activity = main()
        assertTrue(text(activity, R.id.scanStatus).endsWith("The sensor is warming up. First value in about 35 minutes."))
        val card = sensorCard(activity)
        assertTrue(card, card.contains("State=Warming up"))
        assertTrue(card, card.contains("Warm-up left=25min"))
    }

    @Test
    fun everyScanMessageHasItsText() {
        accept(GlucoseUnit.MGDL)
        for (message in ScanMessage.values()) {
            settings.lastOutcome = ScanOutcome(message, now, number = 2, text = "X1")
            val shown = text(main(), R.id.scanStatus)
            assertTrue("$message: $shown", shown.length > 12)
        }
    }

    @Test
    fun nfcSwitchedOffIsSaid() {
        accept(GlucoseUnit.MGDL)
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(false)
        val activity = main()
        assertEquals(app.getString(R.string.nfc_off), text(activity, R.id.scanHint))
        assertTrue(isShown(activity, R.id.nfcSettings))
        assertFalse(shadowOf(NfcAdapter.getDefaultAdapter(app)).isInReaderMode)
        activity.findViewById<View>(R.id.nfcSettings).performClick()
        assertEquals(android.provider.Settings.ACTION_NFC_SETTINGS, shadowOf(activity).nextStartedActivity.action)
    }

    @Test
    fun aTagThatIsNotASensorIsSaid() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104)
        val activity = main()
        val before = store.count()

        // the phone finds a tag that is not of the type NFC-V
        shadowOf(NfcAdapter.getDefaultAdapter(app)).dispatchTagDiscovered(ShadowNfcAdapter.createMockTag())
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(text(activity, R.id.scanStatus).endsWith("This is not a Libre sensor."))
        assertEquals(app.getColor(R.color.red), activity.findViewById<TextView>(R.id.scanStatus).currentTextColor)
        assertEquals(ScanMessage.NOT_A_SENSOR, settings.lastOutcome!!.message)
        assertEquals("nothing was saved or removed", before, store.count())
        assertEquals("the value on the screen stays", "104", text(activity, R.id.value))
    }

    @Test
    fun aPhoneWithoutNfcIsSaid() {
        accept(GlucoseUnit.MGDL)
        ShadowNfcAdapter.setNfcHardwareExists(false)
        val activity = main()
        assertEquals(app.getString(R.string.nfc_missing), text(activity, R.id.scanHint))
        assertFalse(isShown(activity, R.id.nfcSettings))
    }

    @Test
    fun theChartRangeIsKept() {
        accept(GlucoseUnit.MGDL)
        seed(hours = 30)
        val activity = main()
        activity.findViewById<View>(R.id.range24).performClick()
        assertEquals(24, settings.chartHours)
        assertTrue(activity.findViewById<View>(R.id.range24).isSelected)
        assertFalse(activity.findViewById<View>(R.id.range6).isSelected)
        picture(activity, "08-main-24-hours")
        activity.findViewById<View>(R.id.range3).performClick()
        assertEquals(3, settings.chartHours)
        picture(activity, "09-main-3-hours")
    }

    @Test
    fun theBarAtTheBottomOpensTheOtherMainScreens() {
        accept(GlucoseUnit.MGDL)
        val activity = main()
        assertTrue("Home is marked", activity.findViewById<View>(R.id.tabHome).isSelected)
        assertFalse(activity.findViewById<View>(R.id.tabMore).isSelected)
        assertEquals("Home", text(activity, R.id.tabHome))

        assertEquals("Analysis", text(activity, R.id.tabAnalysis))
        assertEquals("Report", text(activity, R.id.tabReport))
        assertEquals("the bar at the top names the screen", "Walhad Glucose Scan", text(activity, R.id.topTitle))
        activity.findViewById<View>(R.id.tabAnalysis).performClick()
        assertEquals(AnalysisActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        activity.findViewById<View>(R.id.tabReport).performClick()
        assertEquals(ReportActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        activity.findViewById<View>(R.id.tabMore).performClick()
        assertEquals(MoreActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertFalse("Home stays under the other screens", activity.isFinishing)

        // the tab of the screen that is open does nothing
        activity.findViewById<View>(R.id.tabHome).performClick()
        assertNull(shadowOf(activity).nextStartedActivity)

        // from another main screen: the new one opens, the old one closes
        val more = Robolectric.buildActivity(MoreActivity::class.java).setup().get()
        assertTrue(more.findViewById<View>(R.id.tabMore).isSelected)
        assertEquals("More", text(more, R.id.topTitle))
        more.findViewById<View>(R.id.tabReport).performClick()
        assertEquals(ReportActivity::class.java.name, shadowOf(more).nextStartedActivity.component!!.className)
        assertTrue(more.isFinishing)
    }

    @Test
    fun theMoreScreenLeadsToEverythingElse() {
        accept(GlucoseUnit.MGDL)
        val more = Robolectric.buildActivity(MoreActivity::class.java).setup().get()
        val ways = linkedMapOf(
            R.id.buttonLive to LiveActivity::class.java,
            R.id.buttonAlarms to AlarmsActivity::class.java,
            R.id.buttonVoice to VoiceActivity::class.java,
            R.id.buttonStatistics to StatisticsActivity::class.java,
            R.id.buttonReadings to ReadingsActivity::class.java,
            R.id.buttonSettings to SettingsActivity::class.java,
            R.id.buttonAbout to AboutActivity::class.java
        )
        for ((id, screen) in ways) {
            more.findViewById<View>(id).performClick()
            assertEquals(screen.name, shadowOf(more).nextStartedActivity.component!!.className)
        }
        val live = more.findViewById<View>(R.id.buttonLive)
        assertEquals("Bluetooth values", live.findViewById<TextView>(R.id.navTitle).text.toString())
        assertEquals("Off", live.findViewById<TextView>(R.id.navState).text.toString())
        assertEquals("Powered by Abdifatah Elmi", text(more, R.id.credit))
        picture(more, "30-more")
    }

    @Test
    @Config(qualifiers = "w360dp-h1500dp-night-xhdpi")
    fun mainAtNight() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104)
        sensor()
        settings.lastOutcome = ScanOutcome(ScanMessage.OK, now)
        val activity = main()
        assertEquals("104", text(activity, R.id.value))
        picture(activity, "10-main-night")
    }

    @Test
    @Config(qualifiers = "w320dp-h1800dp-xhdpi", fontScale = 1.3f)
    fun mainOnASmallPhoneWithBigText() {
        accept(GlucoseUnit.MMOLL)
        seed(newest = 250)
        sensor()
        settings.lastOutcome = ScanOutcome(ScanMessage.UNSUPPORTED, now, text = "Libre 2 US")
        val activity = main()
        assertEquals("13.9", text(activity, R.id.value))
        assertTrue(isShown(activity, R.id.arrow))
        assertBigValueFits(activity)
        picture(activity, "11-main-small-phone-big-text")
    }

    @Test
    @Config(qualifiers = "w320dp-h1800dp-xhdpi", fontScale = 2.0f)
    fun theBigNumberIsNeverCutOrBroken() {
        // the widest things the card can show, on a small phone with the biggest text
        accept(GlucoseUnit.MMOLL)
        seed(newest = 400)
        val wide = main()
        assertEquals("22.2", text(wide, R.id.value))
        assertTrue(isShown(wide, R.id.arrow))
        assertBigValueFits(wide)
        picture(wide, "11b-main-small-phone-biggest-text")

        store.deleteAll()
        store.insert(listOf(Glucose(now, 620, null, true)))
        val high = main()
        assertEquals("HIGH", text(high, R.id.value))
        assertBigValueFits(high)

        // and it comes back to its full size when there is room
        store.deleteAll()
        seed(newest = 104)
        val small = main()
        assertEquals("5.8", text(small, R.id.value))
        assertBigValueFits(small)
    }

    @Test
    fun theBigNumberHasItsFullSizeOnANormalPhone() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104)
        val activity = main()
        val metrics = activity.resources.displayMetrics
        val valueSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 84f, metrics)
        val arrowSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 48f, metrics)
        assertEquals(valueSize, activity.findViewById<TextView>(R.id.value).textSize, 0.5f)
        assertEquals(arrowSize, activity.findViewById<TextView>(R.id.arrow).textSize, 0.5f)
    }

    // ------------------------------------------------------------ other screens

    @Test
    fun readingsList() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104, hours = 30)
        val activity = Robolectric.buildActivity(ReadingsActivity::class.java).setup().get()
        val list = activity.findViewById<ListView>(R.id.list)
        val count = store.count()
        assertTrue("values and at least two day titles", list.adapter.count >= count + 2)
        assertEquals(View.VISIBLE, list.visibility)

        // each value has a dot: red when it is outside the target range (80 to 180), else green
        var green = 0
        var red = 0
        for (position in 0 until list.adapter.count) {
            if (list.adapter.getItemViewType(position) != 1) {
                continue
            }
            val row = list.adapter.getView(position, null, list)
            val number = row.findViewById<TextView>(R.id.text).text.toString().removeSuffix(" mg/dL").toInt()
            val dot = shadowOf(row.findViewById<View>(R.id.dot).background).createdFromResId
            if (number < 80 || number > 180) {
                assertEquals("$number", R.drawable.dot_red, dot)
                assertEquals(app.getColor(R.color.red), row.findViewById<TextView>(R.id.text).currentTextColor)
                red++
            } else {
                assertEquals("$number", R.drawable.dot_green, dot)
                assertEquals(app.getColor(R.color.text), row.findViewById<TextView>(R.id.text).currentTextColor)
                green++
            }
        }
        assertTrue("green $green, red $red", green > 10 && red > 10)
        picture(activity, "12-readings")
    }

    @Test
    fun readingsListWithoutValues() {
        accept(GlucoseUnit.MGDL)
        val activity = Robolectric.buildActivity(ReadingsActivity::class.java).setup().get()
        assertTrue(isShown(activity, R.id.empty))
        assertFalse(isShown(activity, R.id.list))
    }

    @Test
    fun settingsScreen() {
        accept(GlucoseUnit.MGDL)
        seed()
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals("80 mg/dL", text(activity, R.id.lowValue))
        assertEquals("180 mg/dL", text(activity, R.id.highValue))
        picture(activity, "13-settings")

        // limits move in steps of 5
        activity.findViewById<View>(R.id.lowMinus).performClick()
        activity.findViewById<View>(R.id.highPlus).performClick()
        activity.findViewById<View>(R.id.highPlus).performClick()
        assertEquals(75, settings.lowLimit)
        assertEquals(190, settings.highLimit)
        assertEquals("75 mg/dL", text(activity, R.id.lowValue))
        assertEquals("190 mg/dL", text(activity, R.id.highValue))

        // low cannot go over high
        settings.setLimits(100, 100)
        activity.findViewById<View>(R.id.lowPlus).performClick()
        assertEquals(100, settings.lowLimit)
        activity.findViewById<View>(R.id.highMinus).performClick()
        assertEquals(100, settings.highLimit)

        // unit
        activity.findViewById<RadioButton>(R.id.unitMmoll).performClick()
        assertEquals(GlucoseUnit.MMOLL, settings.unit)
        assertEquals("5.6 mmol/L", text(activity, R.id.lowValue))

        // open by touching the sensor: off at first
        val alias = ComponentName(app.packageName, SettingsActivity.SENSOR_TOUCH_ALIAS)
        val touch = activity.findViewById<Switch>(R.id.touchSwitch)
        assertFalse(touch.isChecked)
        touch.performClick()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, app.packageManager.getComponentEnabledSetting(alias))
        touch.performClick()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, app.packageManager.getComponentEnabledSetting(alias))
    }

    @Test
    fun deletingAsksFirst() {
        accept(GlucoseUnit.MGDL)
        seed()
        sensor()
        val before = store.count()
        assertTrue(before > 0)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        activity.findViewById<View>(R.id.deleteButton).performClick()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("cancel keeps everything", before, store.count())

        activity.findViewById<View>(R.id.deleteButton).performClick()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, store.count())
        assertNull(settings.lastSensor)
        assertTrue(text(activity, R.id.readingsCount).startsWith("Saved readings: 0."))
    }

    @Test
    @Config(qualifiers = "w360dp-h1900dp-xhdpi")
    fun aboutScreen() {
        accept(GlucoseUnit.MGDL)
        settings.lastScanDetails = "time: 2026-01-01 12:00:00 UTC\noutcome: OK\nserial: 3MH001HM1LM\n"
        val activity = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        assertEquals("Walhad Glucose Scan", text(activity, R.id.appName))
        assertEquals("Version 4.3.0", text(activity, R.id.version))
        assertEquals("Powered by Abdifatah Elmi", text(activity, R.id.madeBy))
        assertTrue(logoIsShown(activity))
        // the only credit on the screen is the maker of this app
        val credits = text(activity, R.id.credits)
        assertTrue(credits.startsWith("Walhad Glucose Scan is powered by Abdifatah Elmi."))
        assertTrue("the letters of the app are named, as their license asks", credits.contains("Inter, by The Inter Project Authors (SIL Open Font License 1.1)"))
        assertTrue(credits.contains("This app is not from Abbott"))
        val words = allWords(activity.window.decorView)
        assertTrue(words.contains("Powered by Abdifatah Elmi"))
        assertFalse(words.contains("Metzen"))
        assertFalse(words.contains("Glucose Direct"))
        assertTrue(text(activity, R.id.details).contains("serial: 3MH001HM1LM"))
        picture(activity, "14-about")

        // the notice that the license of the open-source part asks for is kept, on its own page
        assertEquals("Open-source licenses", text(activity, R.id.licenses))
        activity.findViewById<View>(R.id.licenses).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        val notice = shadowOf(dialog).message.toString()
        assertTrue(notice.contains("MIT License\n\nCopyright (c) 2023 Reimar Metzen\n\nPermission is hereby granted, free of charge"))
        assertTrue(notice.contains("The above copyright notice and this permission notice shall be included in all copies"))
        assertTrue(notice.contains("THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND"))
        assertTrue(notice.contains("OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."))
        assertTrue(notice.contains("github.com/creepymonster/GlucoseDirect"))
        // word for word the permission notice of the LICENSE file of this project
        val licenseFile = listOf(File("../LICENSE"), File("LICENSE")).first { it.exists() }
        val permission = licenseFile.readText().substringAfter("Reimar Metzen.").trim()
        assertTrue(permission.startsWith("Permission is hereby granted") && permission.endsWith("DEALINGS IN THE\nSOFTWARE."))
        val spaces = Regex("\\s+")
        assertTrue(notice.replace(spaces, " ").contains(permission.replace(spaces, " ")))
        val dialogView = dialog.window!!.decorView
        if (dialogView.width > 0 && dialogView.height > 0) {
            picture(dialogView, "15-open-source-licenses")
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)

        activity.findViewById<View>(R.id.copy).performClick()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(settings.lastScanDetails, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun theAppCarriesTheBrand() {
        // name under the icon and in the title of the main screen
        assertEquals("Walhad Glucose Scan", app.packageManager.getApplicationLabel(app.applicationInfo).toString())
        accept(GlucoseUnit.MGDL)
        assertEquals("Walhad Glucose Scan", main().title.toString())

        // the icon: logo picture on a blue plate; the phone cuts the shape
        assertEquals(R.mipmap.ic_launcher, app.applicationInfo.icon)
        val icon = app.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val foreground = (icon.foreground as BitmapDrawable).bitmap
        val background = (icon.background as BitmapDrawable).bitmap
        assertEquals(foreground.width, foreground.height)
        assertEquals(foreground.width, background.width)
        // the corner of the picture is see-through, its middle is not; the plate is blue all over
        assertEquals(0, Color.alpha(foreground.getPixel(1, 1)))
        assertEquals(255, Color.alpha(foreground.getPixel(foreground.width / 2, foreground.height / 2)))
        // the plate is white: the picture carries its own light ground, and a coloured plate
        // behind it would fight with the coloured band along the bottom of the design
        val plate = background.getPixel(1, 1)
        assertTrue("the plate should be white, was $plate", Color.red(plate) > 240 && Color.green(plate) > 240 && Color.blue(plate) > 240)
        // the picture is put inside the safe zone, so no round mask can cut it: a point just
        // inside the corner of the safe zone is still empty, the middle is not
        val safe = foreground.width / 4
        assertEquals(0, Color.alpha(foreground.getPixel(safe / 3, safe / 3)))
    }

    @Test
    fun aboutScreenWithoutAScan() {
        accept(GlucoseUnit.MGDL)
        val activity = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        assertEquals("No scan yet.", text(activity, R.id.details))
        assertFalse(activity.findViewById<View>(R.id.copy).isEnabled)
    }

    // ------------------------------------------------------------ correcting the value

    /** Saves pairs "sensor value to right value" for a sensor, like the screen "Correct the value" does. */
    private fun correct(vararg pairs: Pair<Int, Int>, serial: String = "3MH001HM1LM") {
        var correction = Correction.NONE
        var time = now - now % minute - 3 * 24 * 60 * minute
        for ((sensorValue, rightValue) in pairs) {
            correction = correction.adding(serial, ReferencePair(time, sensorValue, rightValue))
            time += 60 * minute
        }
        assertEquals("every pair is accepted", pairs.size, correction.pairs.size)
        settings.correction = correction
    }

    private fun correctionScreen(): CorrectionActivity {
        val activity = Robolectric.buildActivity(CorrectionActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun type(activity: Activity, text: String) {
        activity.findViewById<EditText>(R.id.input).setText(text)
        activity.findViewById<View>(R.id.save).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun answer(button: Int) {
        ShadowAlertDialog.getLatestAlertDialog().getButton(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun withoutACorrectionTheSensorValueIsShown() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104)
        sensor()
        val activity = main()
        assertFalse(settings.correction.isActive)
        assertEquals("104", text(activity, R.id.value))
        assertFalse(isShown(activity, R.id.correctedNote))

        // the Home screen has no button for it any more (since 4.1); the way goes through the settings
        assertFalse(allWords(activity.window.decorView).contains("Correct the value"))
        assertEquals("Powered by Abdifatah Elmi", text(activity, R.id.credit))
        val settingsScreen = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals("Set it up", text(settingsScreen, R.id.openCorrection))
        settingsScreen.findViewById<View>(R.id.openCorrection).performClick()
        assertEquals(CorrectionActivity::class.java.name, shadowOf(settingsScreen).nextStartedActivity.component!!.className)
    }

    @Test
    fun mainShowsTheCorrectedValueAndNamesTheSensorValue() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104)
        sensor()
        settings.lastOutcome = ScanOutcome(ScanMessage.OK, now)
        correct(104 to 112)
        val activity = main()

        assertEquals("112", text(activity, R.id.value))
        assertTrue(isShown(activity, R.id.correctedNote))
        assertEquals("Corrected \u00b7 sensor value: 104 mg/dL", text(activity, R.id.correctedNote))
        assertEquals("In target range", text(activity, R.id.rangeChip))
        assertEquals(MainActivity.ValueLook.IN_RANGE, activity.valueLook)
        // the change is counted with the corrected values: 122 before, 112 now
        assertEquals("Steady \u00b7 \u22120.7 mg/dL per minute", text(activity, R.id.trendLine))
        assertBigValueFits(activity)
        // nothing was changed in the saved values
        assertEquals(104, store.latest()!!.value)
        picture(activity, "16-main-corrected")
    }

    @Test
    fun aCorrectionNeverHidesALowSensorValue() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 74)
        sensor()
        correct(74 to 86)
        var activity = main()

        // 86 is inside the target range (80 to 180), the sensor value 74 is not: the card stays red
        assertEquals("86", text(activity, R.id.value))
        assertEquals(MainActivity.ValueLook.ALERT, activity.valueLook)
        assertEquals(R.drawable.hero_alert, ground(activity, R.id.valueCard))
        assertEquals("Sensor value is below target range", text(activity, R.id.rangeChip))
        assertEquals("Corrected \u00b7 sensor value: 74 mg/dL", text(activity, R.id.correctedNote))
        picture(activity, "16b-main-corrected-sensor-value-low")

        // both below
        correct(74 to 78)
        activity = main()
        assertEquals("78", text(activity, R.id.value))
        assertEquals(MainActivity.ValueLook.ALERT, activity.valueLook)
        assertEquals("Below target range", text(activity, R.id.rangeChip))

        // both inside
        settings.setLimits(70, 180)
        activity = main()
        assertEquals("78", text(activity, R.id.value))
        assertEquals(MainActivity.ValueLook.IN_RANGE, activity.valueLook)
        assertEquals("In target range", text(activity, R.id.rangeChip))
    }

    @Test
    fun aLowSensorValueIsNamedNextToTheCorrectedValue() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 36)
        sensor()
        correct(40 to 60)
        val activity = main()
        // the sensor says LOW (36), the correction makes 54 of it: still red, and LOW is named
        assertEquals("54", text(activity, R.id.value))
        assertEquals(MainActivity.ValueLook.ALERT, activity.valueLook)
        assertEquals("Below target range", text(activity, R.id.rangeChip))
        assertEquals("Corrected \u00b7 sensor value: LOW", text(activity, R.id.correctedNote))
    }

    @Test
    fun anOldCorrectedValueAlsoNamesTheSensorValue() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104, ageMinutes = 40)
        sensor()
        correct(104 to 112)
        val activity = main()
        assertEquals("\u2013 \u2013", text(activity, R.id.value))
        val last = text(activity, R.id.lastValue)
        assertTrue(last, last.startsWith("Last value: 112 mg/dL, 40 minutes ago"))
        assertTrue(last, last.endsWith("\nCorrected \u00b7 sensor value: 104 mg/dL"))
        assertFalse(isShown(activity, R.id.correctedNote))
    }

    @Test
    fun aCorrectionIsOnlyForItsOwnSensor() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104)
        sensor()
        correct(104 to 112, serial = "3MH001GJ0VW")
        val activity = main()
        assertEquals("104", text(activity, R.id.value))
        assertFalse(isShown(activity, R.id.correctedNote))

        // the screen says that the saved values are of another sensor
        val screen = correctionScreen()
        assertTrue(isShown(screen, R.id.otherSensor))
        assertEquals("1 value saved for sensor 3MH001GJ0VW.", text(screen, R.id.summary))
        // a value saved now is for the sensor that was scanned: the old pairs are gone
        type(screen, "110")
        answer(AlertDialog.BUTTON_POSITIVE)
        assertEquals("3MH001HM1LM", settings.correction.sensorSerial)
        assertEquals(1, settings.correction.pairs.size)
        assertFalse(isShown(screen, R.id.otherSensor))
    }

    @Test
    @Config(qualifiers = "w360dp-h1900dp-xhdpi")
    fun theRightValueIsSavedAndUsed() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104)
        sensor()
        val activity = correctionScreen()
        assertEquals("Your glucose is steady. You can save the right value now.", text(activity, R.id.status))
        assertEquals(app.getColor(R.color.green), activity.findViewById<TextView>(R.id.status).currentTextColor)
        assertTrue(isShown(activity, R.id.save))
        assertTrue(isShown(activity, R.id.inputRow))
        assertEquals("mg/dL", text(activity, R.id.inputUnit))
        assertEquals("Nothing saved. The app shows the sensor value.", text(activity, R.id.summary))
        assertFalse(isShown(activity, R.id.deleteAll))
        assertFalse(isShown(activity, R.id.pairsHeader))
        assertFalse(isShown(activity, R.id.tip))
        var words = allWords(activity.window.decorView)
        assertTrue(words, words.contains("Sensor value\n104 mg/dL \u00b7 "))
        assertFalse(words, words.contains("Shown in the app"))

        // the app asks before it saves
        type(activity, "112")
        val question = shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message.toString()
        assertTrue(question, question.startsWith("Sensor value: 104 mg/dL\nRight value: 112 mg/dL\n"))
        answer(AlertDialog.BUTTON_NEGATIVE)
        assertFalse("cancel saves nothing", settings.correction.isActive)

        activity.findViewById<View>(R.id.save).performClick()
        answer(AlertDialog.BUTTON_POSITIVE)
        val correction = settings.correction
        assertEquals("3MH001HM1LM", correction.sensorSerial)
        assertEquals(1, correction.pairs.size)
        assertEquals(104, correction.pairs[0].sensorValue)
        assertEquals(112, correction.pairs[0].referenceValue)
        assertEquals(store.latest()!!.timestamp, correction.pairs[0].time)

        // the screen shows what was saved
        words = allWords(activity.window.decorView)
        assertTrue(words, words.contains("1 value saved for sensor 3MH001HM1LM."))
        assertTrue(words, words.contains("104 \u2192 112 mg/dL"))
        assertTrue(words, words.contains("Shown in the app\n112 mg/dL"))
        assertEquals("", activity.findViewById<EditText>(R.id.input).text.toString())
        assertTrue(isShown(activity, R.id.deleteAll))
        assertTrue(isShown(activity, R.id.pairsHeader))
        assertTrue(isShown(activity, R.id.tip))
        assertTrue(text(activity, R.id.tip).contains("at least 40 mg/dL higher or lower"))
        picture(activity, "17-correct-the-value")

        // saving again in the same minute replaces the pair
        type(activity, "110")
        answer(AlertDialog.BUTTON_POSITIVE)
        assertEquals(1, settings.correction.pairs.size)
        assertEquals(110, settings.correction.pairs[0].referenceValue)

        // the main screen shows the corrected value
        assertEquals("110", text(main(), R.id.value))
    }

    @Test
    fun aSecondValueFarAwayMakesLowAndHighValuesRight() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 160)
        sensor()
        correct(60 to 70)
        val activity = correctionScreen()
        assertTrue(isShown(activity, R.id.tip))
        type(activity, "160")
        answer(AlertDialog.BUTTON_POSITIVE)

        val correction = settings.correction
        assertEquals(2, correction.pairs.size)
        assertTrue(correction.hasLine)
        assertEquals(0.9, correction.slope, 1e-9)
        assertEquals(70, correction.valueFor(60))
        assertEquals(115, correction.valueFor(110))
        assertEquals(160, correction.valueFor(160))
        assertFalse("no more tip", isShown(activity, R.id.tip))
        assertEquals(2, activity.findViewById<LinearLayout>(R.id.pairsBox).childCount)
        assertEquals("2 values saved for sensor 3MH001HM1LM.", text(activity, R.id.summary))
    }

    @Test
    fun aCorrectionNeedsACurrentValue() {
        accept(GlucoseUnit.MGDL)
        // nothing scanned yet
        var activity = correctionScreen()
        assertEquals("Scan the sensor first. Then come back here.", text(activity, R.id.status))
        assertEquals(app.getColor(R.color.red), activity.findViewById<TextView>(R.id.status).currentTextColor)
        assertFalse(isShown(activity, R.id.save))
        assertFalse(isShown(activity, R.id.inputRow))

        // a value of 8 minutes ago
        seed(newest = 104, ageMinutes = 8)
        activity = correctionScreen()
        assertEquals("The value is too old. Scan the sensor again, then come back here.", text(activity, R.id.status))
        assertFalse(isShown(activity, R.id.save))
        assertFalse(isShown(activity, R.id.inputRow))
        picture(activity, "17b-correct-the-value-not-possible")
    }

    @Test
    fun aValueThatGetsTooOldWhileTheScreenIsOpenIsNotUsed() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104)
        val activity = correctionScreen()
        assertTrue(isShown(activity, R.id.save))
        now += 7 * minute
        type(activity, "112")
        assertNull("no question is asked", ShadowAlertDialog.getLatestAlertDialog())
        assertFalse(settings.correction.isActive)
        assertEquals("The value is too old. Scan the sensor again, then come back here.", text(activity, R.id.status))
        assertFalse(isShown(activity, R.id.save))
    }

    @Test
    fun aCorrectionIsRefusedWhileTheGlucoseChangesFast() {
        accept(GlucoseUnit.MGDL)
        val latestTime = now - now % minute
        val values = ArrayList<Glucose>()
        for (k in 15 downTo 0) {
            values.add(Glucose(latestTime - k * minute, 104 + 2 * k, null, true, "3MH001HM1LM"))
        }
        store.insert(values)
        var activity = correctionScreen()
        assertEquals(
            "Your glucose is changing fast now (\u22122.0 mg/dL per minute). A correction made now would be wrong. Wait until the arrow shows \u2192 and scan again.",
            text(activity, R.id.status)
        )
        assertFalse(isShown(activity, R.id.save))

        // LOW and HIGH are no numbers
        store.deleteAll()
        seed(newest = 30)
        activity = correctionScreen()
        assertEquals("The sensor shows LOW or HIGH. This value cannot be used for a correction.", text(activity, R.id.status))
        assertFalse(isShown(activity, R.id.save))

        // only one value: the app cannot see whether the glucose is steady
        store.deleteAll()
        store.insert(listOf(Glucose(latestTime, 104, null, true, "3MH001HM1LM")))
        activity = correctionScreen()
        assertEquals("The app cannot see yet if your glucose is steady. Scan again in 5 minutes, then come back here.", text(activity, R.id.status))
        assertFalse(isShown(activity, R.id.save))
    }

    @Test
    fun aWrongNumberIsNotSaved() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104)
        sensor()
        val activity = correctionScreen()

        type(activity, "")
        assertTrue(isShown(activity, R.id.inputError))
        assertEquals("Type the number from your meter.", text(activity, R.id.inputError))

        // mmol/L typed by mistake
        type(activity, "5.6")
        assertEquals("The number must be between 40 and 500 mg/dL.", text(activity, R.id.inputError))

        // 36 more than the sensor value
        type(activity, "140")
        assertEquals(
            "This number is too far from the sensor value (104 mg/dL). The app allows about 30 mg/dL more or less. Check the number. If it is right, measure again later.",
            text(activity, R.id.inputError)
        )
        assertNull("no question was asked", ShadowAlertDialog.getLatestAlertDialog())
        assertFalse(settings.correction.isActive)

        // 30 more is allowed
        type(activity, "134")
        assertFalse(isShown(activity, R.id.inputError))
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog())
        answer(AlertDialog.BUTTON_POSITIVE)
        assertEquals(134, settings.correction.pairs[0].referenceValue)
    }

    @Test
    fun correctingInMmol() {
        accept(GlucoseUnit.MMOLL)
        seed(newest = 104)
        sensor()
        val activity = correctionScreen()
        assertEquals("mmol/L", text(activity, R.id.inputUnit))

        type(activity, "112")
        assertEquals("The number must be between 2.2 and 27.8 mmol/L.", text(activity, R.id.inputError))
        type(activity, "9.0")
        assertEquals(
            "This number is too far from the sensor value (5.8 mmol/L). The app allows about 1.7 mmol/L more or less. Check the number. If it is right, measure again later.",
            text(activity, R.id.inputError)
        )

        // with a comma, as many keyboards give it
        type(activity, "6,2")
        val question = shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message.toString()
        assertTrue(question, question.startsWith("Sensor value: 5.8 mmol/L\nRight value: 6.2 mmol/L\n"))
        answer(AlertDialog.BUTTON_POSITIVE)
        assertEquals(112, settings.correction.pairs[0].referenceValue)
        val words = allWords(activity.window.decorView)
        assertTrue(words, words.contains("5.8 \u2192 6.2 mmol/L"))
        assertTrue(words, words.contains("at least 2.2 mmol/L higher or lower"))

        val main = main()
        assertEquals("6.2", text(main, R.id.value))
        assertEquals("Corrected \u00b7 sensor value: 5.8 mmol/L", text(main, R.id.correctedNote))
    }

    @Test
    fun theCorrectionCanBeDeleted() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 104)
        sensor()
        correct(104 to 112, 170 to 160)
        val activity = correctionScreen()
        val box = activity.findViewById<LinearLayout>(R.id.pairsBox)
        assertEquals(2, box.childCount)
        // the newest pair comes first
        assertEquals("170 \u2192 160 mg/dL", box.getChildAt(0).findViewById<TextView>(R.id.pairText).text.toString())

        // one pair
        box.getChildAt(0).findViewById<View>(R.id.pairDelete).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, settings.correction.pairs.size)
        assertEquals(104, settings.correction.pairs[0].sensorValue)
        assertEquals(1, box.childCount)

        // all: the app asks first
        activity.findViewById<View>(R.id.deleteAll).performClick()
        answer(AlertDialog.BUTTON_NEGATIVE)
        assertTrue("cancel keeps the correction", settings.correction.isActive)
        activity.findViewById<View>(R.id.deleteAll).performClick()
        answer(AlertDialog.BUTTON_POSITIVE)
        assertFalse(settings.correction.isActive)
        assertEquals("Nothing saved. The app shows the sensor value.", text(activity, R.id.summary))
        assertEquals(0, box.childCount)
        assertFalse(isShown(activity, R.id.deleteAll))

        // the main screen shows the sensor value again
        val main = main()
        assertEquals("104", text(main, R.id.value))
        assertFalse(isShown(main, R.id.correctedNote))
    }

    @Test
    fun readingsAndAboutKnowTheCorrection() {
        accept(GlucoseUnit.MGDL)
        seed(newest = 74, hours = 3)
        sensor()
        settings.lastScanDetails = "scan details"

        // without a correction: no note, the sensor values
        var readings = Robolectric.buildActivity(ReadingsActivity::class.java).setup().get()
        assertFalse(isShown(readings, R.id.correctedNote))
        var list = readings.findViewById<ListView>(R.id.list)
        assertEquals("74 mg/dL", list.adapter.getView(1, null, list).findViewById<TextView>(R.id.text).text.toString())
        var about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        assertEquals("scan details", text(about, R.id.details))

        correct(74 to 86)
        readings = Robolectric.buildActivity(ReadingsActivity::class.java).setup().get()
        assertTrue(isShown(readings, R.id.correctedNote))
        assertEquals("The values of sensor 3MH001HM1LM are shown corrected.", text(readings, R.id.correctedNote))
        list = readings.findViewById<ListView>(R.id.list)
        // 86 is inside the target range, the sensor value 74 is not: the dot stays red
        val row = list.adapter.getView(1, null, list)
        assertEquals("86 mg/dL", row.findViewById<TextView>(R.id.text).text.toString())
        assertEquals(R.drawable.dot_red, shadowOf(row.findViewById<View>(R.id.dot).background).createdFromResId)
        picture(readings, "12b-readings-corrected")

        about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val details = text(about, R.id.details)
        assertTrue(details, details.startsWith("scan details\nvalue correction: sensor 3MH001HM1LM, shift, slope 1.000, intercept 12.0\n"))
        assertTrue(details, details.endsWith(", 74, 86"))
        assertTrue(about.findViewById<View>(R.id.copy).isEnabled)
    }

    @Test
    fun theCorrectionSurvivesInTheSettings() {
        correct(60 to 70, 160 to 160)
        val again = Settings(app).correction
        assertEquals("3MH001HM1LM", again.sensorSerial)
        assertEquals(2, again.pairs.size)
        assertEquals(115, again.valueFor(110))
        settings.correction = Correction.NONE
        assertFalse(Settings(app).correction.isActive)
        assertNull(Settings(app).correction.sensorSerial)
    }
}
