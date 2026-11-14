package com.glucosescan.app

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.nfc.NfcAdapter
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.glucosescan.app.core.AnswerLanguage
import com.glucosescan.app.core.Correction
import com.glucosescan.app.core.Finding
import com.glucosescan.app.core.FindingKind
import com.glucosescan.app.core.FindingLevel
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.ReferencePair
import com.glucosescan.app.core.ScanStatus
import com.glucosescan.app.core.Verdict
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.SensorSnapshot
import com.glucosescan.app.data.Settings
import com.glucosescan.app.live.AlarmSounder
import com.glucosescan.app.live.LiveBus
import com.glucosescan.app.nfc.ScanRunner
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.ChartView
import com.glucosescan.app.ui.CurrentValues
import com.glucosescan.app.ui.FindingTexts
import com.glucosescan.app.ui.ScaleView
import com.glucosescan.app.ui.Speaker
import com.glucosescan.app.ui.Texts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowNfcAdapter
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/**
 * What came with version 3.0: on the Home screen the scale, the forecast, the offer of live
 * values, the numbers of the last day and the short analysis; and the Analysis screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h2300dp-xhdpi")
class AnalysisScreensTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val dayLength = 24 * hour
    private val serial = "3MH001HM1LM"
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore

    /** Tuesday 10 November 2026, 14:32:20 local time (UTC+3) (11:32:20 UTC) */
    private var now = 1_794_310_340_000L

    /** Tuesday 10 November 2026, 0:00 local time (UTC+3). */
    private val midnight = 1_794_258_000_000L

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

    private fun <T : Activity> open(screen: Class<T>): T {
        val activity = Robolectric.buildActivity(screen).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    /** Android writes a narrow space before AM and PM; the tests write a plain one. */
    private fun plain(words: CharSequence): String = words.toString().replace('\u202F', ' ').replace('\u00A0', ' ').replace("\u2060", "")

    private fun text(activity: Activity, id: Int): String = plain(activity.findViewById<TextView>(id).text)

    private fun isShown(activity: Activity, id: Int): Boolean = activity.findViewById<View>(id).visibility == View.VISIBLE

    private fun click(activity: Activity, id: Int) {
        activity.findViewById<View>(id).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ground(activity: Activity, id: Int): Int = shadowOf(activity.findViewById<View>(id).background).createdFromResId

    private fun allWords(view: View): String {
        val words = StringBuilder()
        if (view is TextView && view.visibility == View.VISIBLE) {
            words.append(plain(view.text)).append('\n')
        }
        if (view is ViewGroup && view.visibility == View.VISIBLE) {
            for (i in 0 until view.childCount) {
                words.append(allWords(view.getChildAt(i)))
            }
        }
        return words.toString()
    }

    private fun words(activity: Activity): String = allWords(activity.window.decorView)

    /** The lines of a box of rows with a label: "label=text". */
    private fun rows(activity: Activity, boxId: Int): List<String> {
        val box = activity.findViewById<LinearLayout>(boxId)
        return (0 until box.childCount).map {
            val row = box.getChildAt(it)
            plain(row.findViewById<TextView>(R.id.label).text) + "=" + plain(row.findViewById<TextView>(R.id.text).text)
        }
    }

    /** The sentences of a box; a finding has its level in front: "[Good] words". */
    private fun sentences(activity: Activity, boxId: Int): List<String> {
        val box = activity.findViewById<LinearLayout>(boxId)
        return (0 until box.childCount).map {
            val row = box.getChildAt(it)
            val level = row.findViewById<TextView>(R.id.level)
            val caption = row.findViewById<TextView>(R.id.caption)
            (if (caption != null && caption.visibility == View.VISIBLE) plain(caption.text) + ": " else "") +
                (if (level != null) "[" + level.text + "] " else "") + plain(row.findViewById<TextView>(R.id.text).text)
        }
    }

    /** Moves the screen up, as a finger would, until the view [id] is [above] pixels under the top. */
    private fun scrollTo(activity: Activity, id: Int, above: Int) {
        val view = activity.findViewById<View>(id)
        var top = view.top
        var parent = view.parent
        while (parent != null && parent !is ScrollView) {
            top += (parent as View).top
            parent = parent.parent
        }
        (parent as ScrollView).scrollTo(0, Math.max(0, top - above))
        shadowOf(Looper.getMainLooper()).idle()
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

    /** A moment by the local clock of the tests (UTC+3). [day] 0 is 10 November, -1 the day before. */
    private fun at(day: Int, hourOfDay: Int, minuteOfHour: Int = 0): Long = midnight + day * dayLength + hourOfDay * hour + minuteOfHour * minute

    /** The last 16 minutes as after a scan: one value each minute, falling by 10 mg/dL to [newest]. */
    private fun lastMinutes(newest: Int = 104): List<Glucose> {
        val latest = now - now % minute
        return (15 downTo 0).map { Glucose(latest - it * minute, newest + Math.round(it * 10 / 15f), null, true, serial) }
    }

    /**
     * A made-up person, [days] days back from this morning: 110 all the time, 200 from noon
     * to 2 PM, and one hour at 60 last night from 3 to 4 o'clock. One value in each quarter
     * of an hour until 14:15 today, then the last 16 minutes as after a scan.
     */
    private fun seedPerson(days: Int = 14, newest: Int = 104) {
        val values = ArrayList<Glucose>()
        var start = at(-days, 0)
        while (start < at(0, 14, 15)) {
            val hourOfDay = (((start - midnight) % dayLength + dayLength) % dayLength / hour).toInt()
            val low = start >= at(0, 3) && start < at(0, 4)
            values.add(Glucose(start + 7 * minute, if (low) 60 else if (hourOfDay in 12..13) 200 else 110, null, false, serial))
            start += 15 * minute
        }
        values.addAll(lastMinutes(newest))
        store.insert(values)
    }

    // ------------------------------------------------------------ Home: where the value heads

    @Test
    fun homeShowsWhereTheValueHeads() {
        store.insert(lastMinutes(104))
        val home = open(MainActivity::class.java)
        assertEquals("104", text(home, R.id.value))
        assertTrue(isShown(home, R.id.outlookCard))
        assertEquals("Next 30 minutes", text(home, R.id.outlookTitle))
        assertEquals("If it goes on like this: about 84 mg/dL at 3:02 PM.", text(home, R.id.outlookText))
        assertEquals("A guess from the values of the last 15 minutes. Food, medicine and movement change it.", text(home, R.id.outlookNote))
        assertFalse("80 mg/dL is more than 20 minutes away", isShown(home, R.id.outlookWarning))
        assertFalse(home.warnsOfLow)
        assertEquals("Steady \u00b7 \u22120.7 mg/dL per minute", text(home, R.id.trendLine))
        assertEquals(R.drawable.card_background, ground(home, R.id.outlookCard))

        // the chart continues the line, and says what the dotted line is
        assertTrue(home.findViewById<ChartView>(R.id.chart).showsForecast)
        assertTrue(isShown(home, R.id.chartForecastLegend))
        assertEquals("Dotted line: where the values head in the next 30 minutes. It is a guess.", text(home, R.id.chartForecastLegend))
    }

    @Test
    fun homeWarnsBeforeTheValueGoesBelowTheTargetRange() {
        store.insert(lastMinutes(88))
        val home = open(MainActivity::class.java)
        assertEquals("88", text(home, R.id.value))
        assertEquals("In target range", text(home, R.id.rangeChip))
        assertTrue(isShown(home, R.id.outlookWarning))
        assertEquals("It may go below your target range (80 mg/dL) in about 12 minutes.", text(home, R.id.outlookWarning))
        assertEquals("If it goes on like this: about 68 mg/dL at 3:02 PM.", text(home, R.id.outlookText))
        assertTrue(home.warnsOfLow)
        assertEquals("not \"Steady\" next to a warning", "Falling slowly \u00b7 \u22120.7 mg/dL per minute", text(home, R.id.trendLine))
        assertEquals("the card turns yellow", R.drawable.card_notice, ground(home, R.id.outlookCard))
        assertEquals(app.getColor(R.color.notice_title), home.findViewById<TextView>(R.id.outlookTitle).currentTextColor)
        picture(home, "41-home-low-soon")

        // in the other unit
        settings.unit = GlucoseUnit.MMOLL
        val mmol = open(MainActivity::class.java)
        assertEquals("It may go below your target range (4.4 mmol/L) in about 12 minutes.", text(mmol, R.id.outlookWarning))
        assertEquals("If it goes on like this: about 3.8 mmol/L at 3:02 PM.", text(mmol, R.id.outlookText))
    }

    @Test
    fun aValueBelowTheTargetRangeIsNotCalledSoon() {
        store.insert(lastMinutes(75))
        val home = open(MainActivity::class.java)
        assertEquals("Below target range", text(home, R.id.rangeChip))
        assertTrue("the forecast is shown all the same", isShown(home, R.id.outlookCard))
        assertEquals("If it goes on like this: about 55 mg/dL at 3:02 PM.", text(home, R.id.outlookText))
        assertFalse(isShown(home, R.id.outlookWarning))
        assertFalse(home.warnsOfLow)
    }

    @Test
    fun theForecastDoesNotGoBelowWhatASensorCanShow() {
        val latest = now - now % minute
        store.insert((15 downTo 0).map { Glucose(latest - it * minute, 62 + it * 2, null, true, serial) })
        val home = open(MainActivity::class.java)
        assertEquals("62", text(home, R.id.value))
        assertEquals("If it goes on like this: under 40 mg/dL at 3:02 PM.", text(home, R.id.outlookText))
    }

    @Test
    fun noForecastWithoutTheValuesOfTheLastMinutes() {
        // only one value each 15 minutes, as the older part of a scan
        val latest = now - now % minute
        store.insert((0..8).map { Glucose(latest - it * 15 * minute, 104 + it * 3, null, false, serial) })
        val home = open(MainActivity::class.java)
        assertEquals("104", text(home, R.id.value))
        assertFalse(isShown(home, R.id.outlookCard))
        assertFalse(isShown(home, R.id.chartForecastLegend))
        assertFalse(home.findViewById<ChartView>(R.id.chart).showsForecast)
    }

    @Test
    fun anOldValueHasNoForecast() {
        store.insert(lastMinutes(104))
        now += 7 * minute
        val home = open(MainActivity::class.java)
        assertFalse(isShown(home, R.id.outlookCard))
        assertFalse(isShown(home, R.id.chartForecastLegend))
        assertFalse(home.findViewById<ChartView>(R.id.chart).showsForecast)
        assertTrue("the value is still shown, with its time: so is the scale", isShown(home, R.id.scale))

        // after a quarter of an hour the value is no longer shown big, and the scale goes too
        now += 13 * minute
        val later = open(MainActivity::class.java)
        assertFalse(isShown(later, R.id.scale))
        assertFalse(isShown(later, R.id.outlookCard))
    }

    @Test
    fun aCorrectionNeverHidesALowThatComes() {
        // the user set the app 15 % higher (the meter said 115 when the sensor said 100);
        // the sensor values head below the range
        store.insert(lastMinutes(88))
        settings.correction = Correction.NONE.adding(serial, ReferencePair(now - hour, 100, 115))
        val current = CurrentValues.read(store, settings, now)
        assertEquals(101, current.shown!!.value)
        assertEquals("by the shown values 80 is more than 20 minutes away", null, current.forecast!!.minutesUntilBelow(80, now))
        assertEquals(12, current.sensorForecast!!.minutesUntilBelow(80, now))
        assertEquals(12, current.minutesUntilBelowTarget(now))
        val home = open(MainActivity::class.java)
        assertEquals("101", text(home, R.id.value))
        assertTrue(home.warnsOfLow)
        assertEquals("It may go below your target range (80 mg/dL) in about 12 minutes.", text(home, R.id.outlookWarning))
    }

    @Test
    fun theVoiceSaysWhenTheValueHeadsBelowTheTargetRange() {
        store.insert(lastMinutes(88))
        val current = CurrentValues.read(store, settings, now)
        assertEquals("Glucose 88. Falling slowly. Below target soon.", current.speech(GlucoseUnit.MGDL, true, now))
        assertEquals("Glucose 88. Below target soon.", current.speech(GlucoseUnit.MGDL, false, now))
        assertEquals("the user switched that sentence off", "Glucose 88. Steady.", current.speech(GlucoseUnit.MGDL, true, null))
        assertEquals("Glucose 4.9. Falling slowly. Below target soon.", current.speech(GlucoseUnit.MMOLL, true, now))
    }

    @Test
    fun farFromTheLimitTheVoiceSaysNothingAboutALow() {
        store.insert(lastMinutes(104))
        val current = CurrentValues.read(store, settings, now)
        assertEquals("Glucose 104. Steady.", current.speech(GlucoseUnit.MGDL, true, now))
    }

    // ------------------------------------------------------------ Home: the scale

    @Test
    fun theScaleShowsWhereTheValueLies() {
        store.insert(lastMinutes(104))
        val home = open(MainActivity::class.java)
        val scale = home.findViewById<ScaleView>(R.id.scale)
        assertTrue(isShown(home, R.id.scale))
        assertEquals(300, scale.scaleMax)
        assertEquals((104 - 40) / 260f, scale.position, 1e-6f)
        assertEquals("The value on a line from 40 to 300. Your target range is 80 to 180 mg/dL.", scale.contentDescription.toString())

        // a wide target range makes the line longer
        settings.setLimits(70, 280)
        val wide = open(MainActivity::class.java).findViewById<ScaleView>(R.id.scale)
        assertEquals(320, wide.scaleMax)
        assertEquals((104 - 40) / 280f, wide.position, 1e-6f)
    }

    @Test
    fun withoutAValueThereIsNoScale() {
        val home = open(MainActivity::class.java)
        assertFalse(isShown(home, R.id.scale))
        assertFalse(isShown(home, R.id.outlookCard))
    }

    // ------------------------------------------------------------ Home: the offer of live values

    @Test
    fun homeOffersValuesByThemselves() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        val home = open(MainActivity::class.java)
        assertTrue(isShown(home, R.id.liveOffer))
        val words = words(home)
        assertTrue(words.contains("Values by themselves"))
        assertTrue(
            "it says honestly that a scan cannot start by itself",
            words.contains("A scan cannot start by itself: the phone must touch the sensor. Values come by themselves in two other ways.")
        )
        assertTrue("and what the way over Bluetooth costs", words.contains("Over Bluetooth, from the sensor: then LibreLink loses its values and its alarms."))
        click(home, R.id.offerBluetooth)
        assertEquals(LiveActivity::class.java.name, shadowOf(home).nextStartedActivity.component!!.className)
        assertFalse("opening the screen switches nothing on", settings.liveEnabled)
        click(home, R.id.offerYes)
        assertEquals(LinkUpActivity::class.java.name, shadowOf(home).nextStartedActivity.component!!.className)
        assertFalse(settings.linkUpEnabled)

        click(home, R.id.offerNo)
        assertFalse(isShown(home, R.id.liveOffer))
        assertTrue(settings.liveOfferHidden)
        assertFalse("it stays away", isShown(open(MainActivity::class.java), R.id.liveOffer))
    }

    @Test
    fun aPhoneWithoutBluetoothIsOfferedLibreLinkUpOnly() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, false)
        val home = open(MainActivity::class.java)
        assertTrue("LibreLinkUp needs the internet, not Bluetooth", isShown(home, R.id.liveOffer))
        assertFalse(isShown(home, R.id.offerBluetooth))
    }

    @Test
    fun withLiveValuesOnThereIsNoOffer() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        settings.liveEnabled = true
        val home = open(MainActivity::class.java)
        assertFalse(isShown(home, R.id.liveOffer))
        assertTrue(isShown(home, R.id.liveCard))
    }

    // ------------------------------------------------------------ Home: the last 24 hours and the short analysis

    @Test
    fun homeShowsTheNumbersOfTheLastDay() {
        seedPerson()
        settings.lastSensor = SensorSnapshot(serial, "Libre 2 EU", "1 - European", ScanStatus.OK, 10_320, 20_659, now)
        val home = open(MainActivity::class.java)
        // 14:33 yesterday to 14:33 today: 97 quarter hours, 4 of them at 60 and 8 at 200
        assertEquals("88 %", text(home, R.id.tileInTarget))
        assertEquals("115 mg/dL", text(home, R.id.tileAverage))
        assertEquals("60 mg/dL", text(home, R.id.tileLowest))
        assertEquals("200 mg/dL", text(home, R.id.tileHighest))

        assertEquals(
            listOf(
                "Last 24 hours: [Look at this] There were low values (under 70 mg/dL).",
                "Last 14 days: [Good to know] 1 low event (under 70 mg/dL). It lasted 1h 0min."
            ),
            sentences(home, R.id.analysisBox)
        )
        assertFalse(isShown(home, R.id.analysisEmpty))
        picture(home, "40-home")

        click(home, R.id.analysisCard)
        assertEquals(AnalysisActivity::class.java.name, shadowOf(home).nextStartedActivity.component!!.className)
    }

    @Test
    fun theTilesInTheOtherUnit() {
        seedPerson()
        settings.unit = GlucoseUnit.MMOLL
        val home = open(MainActivity::class.java)
        assertEquals("6.4 mmol/L", text(home, R.id.tileAverage))
        assertEquals("3.3 mmol/L", text(home, R.id.tileLowest))
        assertEquals("11.1 mmol/L", text(home, R.id.tileHighest))
    }

    @Test
    fun withFewValuesTheHomeScreenSaysThatTheAnalysisNeedsMore() {
        store.insert(lastMinutes(104))
        val home = open(MainActivity::class.java)
        assertTrue(isShown(home, R.id.analysisEmpty))
        assertEquals("Not enough values yet. The analysis needs the values of 12 hours or more.", text(home, R.id.analysisEmpty))
        assertEquals(listOf("Last 24 hours: [Good] Almost always in range, and no lows."), sentences(home, R.id.analysisBox))
        assertEquals("100 %", text(home, R.id.tileInTarget))
        assertEquals("104 mg/dL", text(home, R.id.tileLowest))
        assertEquals("114 mg/dL", text(home, R.id.tileHighest))
    }

    @Test
    fun withoutValuesTheTilesShowADash() {
        val home = open(MainActivity::class.java)
        for (id in listOf(R.id.tileInTarget, R.id.tileAverage, R.id.tileLowest, R.id.tileHighest)) {
            assertEquals("–", text(home, id))
        }
        assertTrue(sentences(home, R.id.analysisBox).isEmpty())
        assertTrue(isShown(home, R.id.analysisEmpty))
    }

    @Test
    fun theSensorCardHasABarForTheTimeUsed() {
        settings.lastSensor = SensorSnapshot(serial, "Libre 2 EU", "1 - European", ScanStatus.OK, 10_320, 20_659, now)
        val home = open(MainActivity::class.java)
        val bar = home.findViewById<ProgressBar>(R.id.sensorProgress)
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals(1000, bar.max)
        assertEquals(499, bar.progress)

        // a sensor that is warming up has no bar
        settings.lastSensor = SensorSnapshot(serial, "Libre 2 EU", "1 - European", ScanStatus.STARTING, 20, 20_659, now)
        assertEquals(View.GONE, open(MainActivity::class.java).findViewById<View>(R.id.sensorProgress).visibility)
    }

    // ------------------------------------------------------------ Analysis

    @Test
    fun theAnalysisScreen() {
        seedPerson()
        val screen = open(AnalysisActivity::class.java)
        assertTrue(screen.findViewById<View>(R.id.tabAnalysis).isSelected)
        assertEquals("Analysis", text(screen, R.id.topTitle))

        // right now
        assertEquals("104 mg/dL · Steady", text(screen, R.id.nowValue))
        assertEquals(listOf("In 15 minutes=about 94 mg/dL", "In 30 minutes=about 84 mg/dL"), rows(screen, R.id.nowBox))
        assertFalse(isShown(screen, R.id.nowWarning))
        assertEquals("A guess from the values of the last 15 minutes. Food, medicine and movement change it.", text(screen, R.id.nowNote))

        // the last six hours, in words
        assertTrue(screen.findViewById<View>(R.id.hours6).isSelected)
        assertEquals(Verdict.OFTEN_HIGH, Verdict.of(screen.lastSpan!!))
        assertEquals(
            listOf(
                "[Look at this] No lows, but often high (over 180 mg/dL).",
                "Average 139 mg/dL. Lowest 104 mg/dL at 2:32 PM. Highest 200 mg/dL at 12:07 PM.",
                "In range (70 to 180 mg/dL) 68 % of the time. Under 70 mg/dL: 0 %. Over 180 mg/dL: 32 %.",
                "It started at 110 mg/dL (8:37 AM) and is at 104 mg/dL now (2:32 PM): about the same.",
                "Low events (under 70 mg/dL): none.",
                "High events (over 180 mg/dL): 1. 12:00 PM for 2h 0min, highest 200 mg/dL."
            ),
            sentences(screen, R.id.hoursWords)
        )

        // hour by hour, the newest first
        assertEquals(
            listOf(
                "2:00 PM=108 mg/dL · 104 to 114",
                "1:00 PM=200 mg/dL · 200 to 200",
                "12:00 PM=200 mg/dL · 200 to 200",
                "11:00 AM=110 mg/dL · 110 to 110",
                "10:00 AM=110 mg/dL · 110 to 110",
                "9:00 AM=110 mg/dL · 110 to 110",
                "8:33 AM=110 mg/dL · 110 to 110"
            ),
            rows(screen, R.id.hoursBox)
        )

        // day by day
        val days = rows(screen, R.id.daysBox)
        assertEquals(7, days.size)
        assertEquals("Today · Tuesday, November 10, 2026=Average 119 mg/dL · in range 80 % · 60 to 200 · 1 low event", days[0])
        assertEquals("Yesterday · Monday, November 9, 2026=Average 118 mg/dL · in range 92 % · 110 to 200", days[1])
        assertEquals("Sunday, November 8, 2026=Average 118 mg/dL · in range 92 % · 110 to 200", days[2])
        assertEquals("Wednesday, November 4, 2026=Average 118 mg/dL · in range 92 % · 110 to 200", days[6])

        // what stands out in the last 14 days
        assertTrue(screen.findViewById<View>(R.id.days14).isSelected)
        assertTrue(isShown(screen, R.id.analysisContent))
        assertFalse(isShown(screen, R.id.analysisEmptyCard))
        assertEquals(
            listOf(FindingKind.LOW_EVENTS, FindingKind.HIGHEST_PART, FindingKind.STEADY, FindingKind.IN_RANGE_GOOD),
            screen.lastAnalysis!!.findings.map { it.kind }
        )
        assertEquals(
            listOf(
                "[Good to know] 1 low event (under 70 mg/dL). It lasted 1h 0min.",
                "[Good to know] Your glucose is highest in the afternoon (noon to 6 PM): 140 mg/dL on average. Over the whole day it is 117 mg/dL.",
                "[Good] Your glucose is quite even: variation (CV) 21 %. The common goal is 36 % or less.",
                "[Good] 91 % of the time was in range (70 to 180 mg/dL). The common goal is 70 % or more."
            ),
            sentences(screen, R.id.findingsBox)
        )

        // the common goals
        assertEquals(
            listOf(
                "In range (70 to 180 mg/dL): 70 % or more=91 % · Reached",
                "Under 70 mg/dL: less than 4 %=0.3 % · Reached",
                "Under 54 mg/dL: less than 1 %=0 % · Reached",
                "Over 180 mg/dL: less than 25 %=8.3 % · Reached",
                "Over 250 mg/dL: less than 5 %=0 % · Reached",
                "Variation (CV): 36 % or less=21 % · Reached"
            ),
            rows(screen, R.id.goalsBox)
        )

        // by time of day
        assertEquals(
            listOf(
                "Night · midnight to 6 AM=Average 109 mg/dL · low 1 % · high 0 % · 1 low event",
                "Morning · 6 AM to noon=Average 110 mg/dL · low 0 % · high 0 %",
                "Afternoon · noon to 6 PM=Average 140 mg/dL · low 0 % · high 33 %",
                "Evening · 6 PM to midnight=Average 110 mg/dL · low 0 % · high 0 %"
            ),
            rows(screen, R.id.partsBox)
        )

        // the low events
        assertEquals(listOf("3:00 AM · 1h 0min · lowest 60 mg/dL"), sentences(screen, R.id.lowsBox))
        picture(screen, "43-analysis")
        scrollTo(screen, R.id.findingsBox, 260)
        picture(screen, "43b-analysis-goals-and-times-of-day")
    }

    @Test
    fun theAnalysisScreenNeverGivesAdviceAboutTreatment() {
        seedPerson()
        val words = words(open(AnalysisActivity::class.java))
        assertTrue(words.contains("It counts on this phone, by fixed rules. It is not a doctor, and it gives no advice about insulin or medicine."))
        assertTrue(words.contains("Either the app asks an AI over the internet and shows the answer here, or you give the text to an AI app on your phone yourself."))
        assertTrue(words.contains("An AI can be wrong. Do not change insulin or medicine because of it. Ask your doctor."))
        for (forbidden in listOf("take insulin", "more insulin", "less insulin", "units of insulin", "you should eat", "increase your", "reduce your", "your dose")) {
            assertFalse(forbidden, words.lowercase().contains(forbidden))
        }
    }

    @Test
    fun theAnalysisWarnsBeforeALow() {
        seedPerson(newest = 88)
        val screen = open(AnalysisActivity::class.java)
        assertEquals("the arrow \"steady\" covers a slow fall; next to the warning the words say what it is", "88 mg/dL · Falling slowly", text(screen, R.id.nowValue))
        assertTrue(isShown(screen, R.id.nowWarning))
        assertEquals("It may go below your target range (80 mg/dL) in about 12 minutes.", text(screen, R.id.nowWarning))
        assertEquals(listOf("In 15 minutes=about 78 mg/dL", "In 30 minutes=about 68 mg/dL"), rows(screen, R.id.nowBox))
    }

    @Test
    fun theHoursAndTheDaysCanBeChosen() {
        seedPerson()
        val screen = open(AnalysisActivity::class.java)
        click(screen, R.id.hours3)
        assertEquals(3, settings.analysisHours)
        assertTrue(screen.findViewById<View>(R.id.hours3).isSelected)
        assertFalse(screen.findViewById<View>(R.id.hours6).isSelected)
        assertEquals(listOf("2:00 PM", "1:00 PM", "12:00 PM", "11:33 AM"), rows(screen, R.id.hoursBox).map { it.substringBefore('=') })
        assertEquals("[Look at this] No lows, but often high (over 180 mg/dL).", sentences(screen, R.id.hoursWords)[0])

        click(screen, R.id.hours24)
        assertEquals(24, settings.analysisHours)
        assertEquals(25, rows(screen, R.id.hoursBox).size)
        assertEquals("the low of last night is in the last 24 hours", "[Look at this] There were low values (under 70 mg/dL).", sentences(screen, R.id.hoursWords)[0])
        assertTrue(sentences(screen, R.id.hoursWords).contains("Low events (under 70 mg/dL): 1. 3:00 AM for 1h 0min, lowest 60 mg/dL."))

        click(screen, R.id.days7)
        assertEquals(7, settings.statisticsDays)
        assertTrue(screen.findViewById<View>(R.id.days7).isSelected)
        assertEquals("Days: 7. From Tue, Nov 3, 2026, 2:33 PM to Tue, Nov 10, 2026, 2:32 PM.", text(screen, R.id.analysisPeriodLine))
        assertEquals(7 * dayLength, screen.lastAnalysis!!.stats.to - screen.lastAnalysis!!.stats.from)

        click(screen, R.id.days1)
        assertEquals(1, settings.statisticsDays)
        assertEquals("[Look at this] 4.1 % of the time was under 70 mg/dL. The common goal is less than 4 %.", sentences(screen, R.id.findingsBox)[0])
        assertEquals("one low event in one day", "[Look at this] 1 low event (under 70 mg/dL). It lasted 1h 0min.", sentences(screen, R.id.findingsBox)[1])

        // the choice is kept
        val again = open(AnalysisActivity::class.java)
        assertTrue(again.findViewById<View>(R.id.days1).isSelected)
        assertTrue(again.findViewById<View>(R.id.hours24).isSelected)
    }

    @Test
    fun theAnalysisWithoutValues() {
        val screen = open(AnalysisActivity::class.java)
        assertEquals("No current value. Scan the sensor, or switch on live values.", text(screen, R.id.nowValue))
        assertTrue(rows(screen, R.id.nowBox).isEmpty())
        assertEquals(listOf("No values in this time."), sentences(screen, R.id.hoursWords))
        assertEquals(7, rows(screen, R.id.hoursBox).size)
        assertTrue(rows(screen, R.id.hoursBox).all { it.endsWith("=no values") })
        assertTrue(rows(screen, R.id.daysBox).all { it.endsWith("=No values") })
        assertTrue(isShown(screen, R.id.analysisEmptyCard))
        assertFalse(isShown(screen, R.id.analysisContent))
        assertEquals("", screen.aiText)
        click(screen, R.id.aiSend)
        assertNull("nothing to send", shadowOf(screen).nextStartedActivity)
        picture(screen, "45-analysis-empty")
    }

    @Test
    fun aValueWithoutAForecastSaysWhy() {
        val latest = now - now % minute
        store.insert((0..8).map { Glucose(latest - it * 15 * minute, 104, null, false, serial) })
        val screen = open(AnalysisActivity::class.java)
        assertEquals("104 mg/dL", text(screen, R.id.nowValue).substringBefore(" ·"))
        assertTrue(rows(screen, R.id.nowBox).isEmpty())
        assertEquals("No forecast now. It needs the values of at least 8 minutes without big jumps.", text(screen, R.id.nowNote))
    }

    @Test
    fun aNewLiveValueIsShownAtOnce() {
        seedPerson()
        val screen = open(AnalysisActivity::class.java)
        assertEquals("104 mg/dL · Steady", text(screen, R.id.nowValue))
        now += minute
        store.insert(listOf(Glucose(now - now % minute, 103, null, true, serial)))
        LiveBus.changed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("103 mg/dL · Steady", text(screen, R.id.nowValue))
    }

    @Test
    fun theButtonsLeadToTheReportAndTheStatistics() {
        seedPerson()
        val screen = open(AnalysisActivity::class.java)
        click(screen, R.id.openStatistics)
        assertEquals(StatisticsActivity::class.java.name, shadowOf(screen).nextStartedActivity.component!!.className)
        assertFalse(screen.isFinishing)
        click(screen, R.id.openReport)
        assertEquals(ReportActivity::class.java.name, shadowOf(screen).nextStartedActivity.component!!.className)
        assertTrue("like the bar at the bottom: the report takes the place of this screen", screen.isFinishing)
    }

    // ------------------------------------------------------------ words

    @Test
    fun moreOrLessTimeInRangeThanBefore() {
        val worse = Finding(FindingKind.WORSE, FindingLevel.NOTE, a = 86.0, b = 80.0)
        val better = Finding(FindingKind.BETTER, FindingLevel.GOOD, a = 61.0, b = 74.0)
        assertEquals("Less time in range than in the 14 days before: 86 % then, 80 % now.", FindingTexts.text(app, worse, GlucoseUnit.MGDL, 14))
        assertEquals("not \"the 1 day before\"", "Less time in range than in the 24 hours before: 86 % then, 80 % now.", FindingTexts.text(app, worse, GlucoseUnit.MGDL, 1))
        assertEquals("Less time in range than on the day before: 86 % then, 80 % now.", FindingTexts.text(app, worse, GlucoseUnit.MGDL, 1, calendarDay = true))
        assertEquals("More time in range than in the 7 days before: 61 % then, 74 % now.", FindingTexts.text(app, better, GlucoseUnit.MGDL, 7))
        assertEquals("More time in range than in the 24 hours before: 61 % then, 74 % now.", FindingTexts.text(app, better, GlucoseUnit.MGDL, 1))
        assertEquals("More time in range than on the day before: 61 % then, 74 % now.", FindingTexts.text(app, better, GlucoseUnit.MGDL, 1, calendarDay = true))
    }

    @Test
    fun aNumberAndItsUnitStayOnOneLine() {
        assertEquals("about 84\u00A0mg/\u2060dL at 3:02 PM", Texts.unbroken("about 84 mg/dL at 3:02 PM"))
        assertEquals("4.7\u00A0mmol/\u2060L", Texts.unbroken("4.7 mmol/L"))
        assertEquals("All values are in\u00A0mg/\u2060dL.", Texts.unbroken("All values are in mg/dL."))
        assertEquals("(mg/\u2060dL)", Texts.unbroken("(mg/dL)"))
        assertEquals("68\u00A0% of the time", Texts.unbroken("68 % of the time"))
        assertEquals("No unit in here.", Texts.unbroken("No unit in here."))

        // on the screen: the warning keeps "80 mg/dL" together
        store.insert(lastMinutes(88))
        val home = open(MainActivity::class.java)
        val shown = home.findViewById<TextView>(R.id.outlookWarning).text.toString()
        assertTrue(shown, shown.contains("(80\u00A0mg/\u2060dL)"))
    }

    // ------------------------------------------------------------ Analysis: ask an AI

    @Test
    fun theNumbersCanBeGivenToAnAiApp() {
        seedPerson()
        val screen = open(AnalysisActivity::class.java)
        val text = screen.aiText
        assertTrue(text, text.startsWith("I use a glucose sensor (FreeStyle Libre 2). Below are my glucose data of the last 14 days.\nPlease analyse how my glucose was, in simple words:\n"))
        assertTrue(text.contains("Do not tell me to change insulin doses or medicines. That is for my doctor to decide.\n"))
        assertTrue(text.contains("All glucose values are in mg/dL.\n"))
        assertTrue(text, text.contains("PERIOD\nTue 27 Oct 2026, 14:33 to Tue 10 Nov 2026, 14:32. Values for 100 % of this time.\n"))
        assertTrue(text, text.contains("In range (70 to 180): 91 %\n"))
        assertTrue(text.contains("My own target range is 80 to 180: "))
        assertTrue(text, text.contains("LOW EVENTS (under 70 for 15 minutes or longer): 1\n- Tue 10 Nov 2026, 03:00, 60 minutes, lowest 60\n"))
        assertTrue(text, text.contains("DAY BY DAY, newest first (average; in range; lowest to highest; low events; high events)\nTue 10 Nov: 119; 80 %; 60 to 200; 1; 1\nMon 9 Nov: 118; 92 %; 110 to 200; 0; 1\n"))
        assertTrue(text, text.contains("THE LAST 24 HOURS, HOUR BY HOUR (average; lowest to highest)\nMon 14:00: 110; 110 to 110\n"))
        assertTrue(text, text.contains("Tue 14:00: 108; 104 to 114\n"))
        assertFalse("the name of the user is not in it, nor the number of the sensor", text.contains(serial))
        assertFalse(text.contains("Please answer in"))

        // English is the first choice; the text is hidden until the user wants to see it
        assertTrue(screen.findViewById<View>(R.id.aiEnglish).isSelected)
        assertFalse(isShown(screen, R.id.aiReport))
        assertEquals("Show the text", text(screen, R.id.aiShow))
        click(screen, R.id.aiShow)
        assertTrue(isShown(screen, R.id.aiReport))
        assertEquals(text, screen.findViewById<TextView>(R.id.aiReport).text.toString())
        assertEquals("Hide the text", text(screen, R.id.aiShow))
        scrollTo(screen, R.id.aiEnglish, 620)
        picture(screen, "44-analysis-ask-an-ai")
        click(screen, R.id.aiShow)
        assertFalse(isShown(screen, R.id.aiReport))

        // "Send": Android asks which app gets the text
        click(screen, R.id.aiSend)
        val chooser = shadowOf(screen).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(text, send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("My glucose data", send.getStringExtra(Intent.EXTRA_SUBJECT))

        // "Copy"
        click(screen, R.id.aiCopy)
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun theAiCanBeAskedToAnswerInSomaliOrArabic() {
        seedPerson()
        val screen = open(AnalysisActivity::class.java)
        click(screen, R.id.aiSomali)
        assertEquals(AnswerLanguage.SOMALI, settings.answerLanguage)
        assertTrue(screen.findViewById<View>(R.id.aiSomali).isSelected)
        assertFalse(screen.findViewById<View>(R.id.aiEnglish).isSelected)
        assertTrue(screen.aiText.contains("Please answer in Somali.\n"))

        click(screen, R.id.aiArabic)
        assertEquals(AnswerLanguage.ARABIC, settings.answerLanguage)
        assertTrue(screen.aiText.contains("Please answer in Arabic.\n"))
        assertFalse(screen.aiText.contains("Somali"))

        // the choice is kept
        assertTrue(open(AnalysisActivity::class.java).findViewById<View>(R.id.aiArabic).isSelected)
        click(screen, R.id.aiEnglish)
        assertFalse(screen.aiText.contains("Please answer in"))
    }

    @Test
    fun theTextForTheAiUsesTheUnitOfTheUser() {
        seedPerson()
        settings.unit = GlucoseUnit.MMOLL
        val screen = open(AnalysisActivity::class.java)
        assertTrue(screen.aiText.contains("All glucose values are in mmol/L.\n"))
        assertTrue(screen.aiText, screen.aiText.contains("In range (3.9 to 10.0): 91 %\n"))
        assertEquals("5.8 mmol/L · Steady", text(screen, R.id.nowValue))
        assertEquals(listOf("In 15 minutes=about 5.2 mmol/L", "In 30 minutes=about 4.7 mmol/L"), rows(screen, R.id.nowBox))
        assertEquals("Under 3.9 mmol/L: less than 4 %=0.3 % · Reached", rows(screen, R.id.goalsBox)[1])
    }
}
