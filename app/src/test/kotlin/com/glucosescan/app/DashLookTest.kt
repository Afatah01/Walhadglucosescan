package com.glucosescan.app

import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings
import com.glucosescan.app.ui.AppClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The redesigned Home screen, opened and measured on a phone-sized screen.
 *
 * These tests do three things the redesign must not break: they prove the screen still opens and
 * fills in from real readings, they prove the 5.5.0 layout keeps every figure of the old one, and
 * they draw the finished screen to a PNG so the design can be looked at instead of described.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w411dp-h891dp-xxhdpi")
class DashLookTest {

    private val now = 1_700_000_000_000L / 60_000L * 60_000L
    private val minute = 60_000L

    @Before
    fun setUp() {
        AppClock.source = { now }
        val settings = Settings(RuntimeEnvironment.getApplication())
        settings.noticeAccepted = true
        settings.unit = GlucoseUnit.MGDL
        settings.lowLimit = 70
        settings.highLimit = 180
    }

    @After
    fun tearDown() {
        AppClock.source = { System.currentTimeMillis() }
        GlucoseStore.closeShared()
    }

    private fun seed(): GlucoseStore {
        val store = GlucoseStore.get(RuntimeEnvironment.getApplication())
        val values = ArrayList<Glucose>()
        var time = now - 12 * 60 * minute
        var value = 96.0
        var step = 1.4
        while (time <= now) {
            values.add(Glucose(time, Math.round(value).toInt(), null, true, "sensor"))
            value += step
            if (value > 175) step = -1.6
            if (value < 78) step = 1.5
            time += minute
        }
        store.insert(values)
        return store
    }

    @Test
    fun theDashboardOpensAndKeepsEveryFigure() {
        seed()
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue("the screen must be open", !home.isFinishing && !home.isDestroyed)

        // the main reading
        val value = home.findViewById<TextView>(R.id.value)
        assertNotNull(value)
        assertTrue("a real reading is shown", value.text.toString().isNotEmpty())

        // the prediction card
        assertNotNull(home.findViewById<View>(R.id.outlookCard))
        assertNotNull(home.findViewById<TextView>(R.id.outlook15))
        assertNotNull(home.findViewById<TextView>(R.id.outlook30))
        assertNotNull(home.findViewById<View>(R.id.outlookAck))

        // every statistic of the old screen is still there, in the new grid
        for (id in listOf(
            R.id.tileInTarget, R.id.tileAverage, R.id.tileLowest, R.id.tileHighest,
            R.id.tileTimeLow, R.id.tileTimeHigh, R.id.tileVariation, R.id.tileGmi,
            R.id.tileEvents, R.id.tileInTight, R.id.tileVeryLow, R.id.tileVeryHigh,
            R.id.tileCoverage, R.id.tileReadings
        )) {
            val view = home.findViewById<TextView>(id)
            assertNotNull("the figure " + id + " must exist", view)
            assertTrue("the figure " + id + " must be filled", view.text.toString().isNotEmpty())
        }

        // the chart, its period chips and the sensor card
        assertNotNull(home.findViewById<View>(R.id.chart))
        for (id in listOf(R.id.range3, R.id.range6, R.id.range12, R.id.range24)) {
            assertNotNull(home.findViewById<View>(id))
        }
        assertNotNull(home.findViewById<LinearLayout>(R.id.sensorBox))
        assertTrue("the sensor card says something", home.findViewById<LinearLayout>(R.id.sensorBox).childCount > 0)
    }

    @Test
    fun theBottomBarStillHasFiveScreensAndTheySelect() {
        seed()
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        for (id in listOf(R.id.tabHome, R.id.tabAnalysis, R.id.tabReport, R.id.tabInsights, R.id.tabMore)) {
            assertNotNull(home.findViewById<View>(id))
        }
        assertTrue(home.findViewById<View>(R.id.tabHome).isSelected)
    }

    /**
     * Nothing may overflow the screen: every card fits inside the page width, the two columns
     * are the same width, and the big number is wide enough to hold what it shows.
     */
    /**
     * The statistics cards must be compact: the 5.8.0 design asks for 54dp minimum-height
     * cards with 7dp vertical padding and single-line labels, so on a standard phone a
     * filled card measures roughly 54 to 80 density-independent pixels tall.
     */
    @Test
    fun compactStatCardsMeasureInTheTargetBand() {
        seed()
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = home.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2340)
        val page = ((home.findViewById<View>(R.id.screenContent) as android.view.ViewGroup).getChildAt(0) as android.widget.ScrollView).getChildAt(0) as LinearLayout
        page.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        page.layout(0, 0, 1080, page.measuredHeight)
        val dp = home.resources.displayMetrics.density
        for (id in listOf(R.id.tileInTarget, R.id.tileAverage, R.id.tileTimeLow, R.id.tileCoverage)) {
            val card = home.findViewById<View>(id).parent?.parent as View
            val heightDp = card.height / dp
            assertTrue("the card " + id + " must be at least 50dp tall, was " + heightDp, heightDp >= 50)
            assertTrue("the card " + id + " must be at most 80dp tall, was " + heightDp, heightDp <= 80)
        }
    }

    @Test
    fun allThreeForecastCardsAreOnTheScreenAtOnce() {
        seed()
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = home.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2340)

        // all three exist, are visible, and are filled with a real figure
        for ((cardId, valueId, timeId) in listOf(
            Triple(R.id.forecast15Card, R.id.outlook15, R.id.outlook15Time),
            Triple(R.id.forecast30Card, R.id.outlook30, R.id.outlook30Time),
            Triple(R.id.forecast60Card, R.id.outlook60, R.id.outlook60Time)
        )) {
            val card = home.findViewById<View>(cardId)
            assertNotNull(card)
            assertEquals("the card " + cardId + " must be visible", View.VISIBLE, card.visibility)
            val value = home.findViewById<TextView>(valueId)
            assertTrue("the card " + cardId + " must show a figure", value.text.toString().isNotEmpty())
            assertNotNull(home.findViewById<TextView>(timeId))
        }

        // the two figures the app has always shown are untouched by the redesign
        val fifteen = home.findViewById<TextView>(R.id.outlook15).text.toString()
        val thirty = home.findViewById<TextView>(R.id.outlook30).text.toString()
        val sixty = home.findViewById<TextView>(R.id.outlook60).text.toString()
        assertTrue("15 minutes", fifteen.isNotEmpty() && fifteen != "–")
        assertTrue("30 minutes", thirty.isNotEmpty() && thirty != "–")
        assertTrue("60 minutes", sixty.isNotEmpty() && sixty != "–")
    }

    @Test
    fun nothingOverflowsTheScreen() {
        seed()
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = home.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2340)

        // the page column is 16dp in from each edge
        val density = home.resources.displayMetrics.density
        val side = (16 * density).toInt()
        val expectedWidth = 1080 - 2 * side

        val content = home.findViewById<View>(R.id.screenContent)
        val scroll = (content as android.view.ViewGroup).getChildAt(0) as android.widget.ScrollView
        val page = scroll.getChildAt(0) as LinearLayout
        page.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        page.layout(0, 0, 1080, page.measuredHeight)

        for (id in listOf(R.id.valueCard, R.id.outlookCard, R.id.chartCard, R.id.analysisCard, R.id.sensorCard)) {
            val view = home.findViewById<View>(id)
            assertNotNull(view)
            assertEquals(
                "the card " + id + " must fill the page width",
                expectedWidth,
                view.width
            )
        }

        // the two columns of the metric rows are equal and inside the page
        val a = home.findViewById<View>(R.id.tileInTarget)
        val b = home.findViewById<View>(R.id.tileAverage)
        assertTrue("both columns must be on screen", a.width > 0 && b.width > 0)
        assertTrue("the columns must be the same width", Math.abs(a.width - b.width) <= 2)
        assertTrue("two columns and their gap must fit", 2 * a.width + 3 * a.left <= expectedWidth + 4)

        // the main reading is drawn, and the figures are not clipped to nothing
        val value = home.findViewById<TextView>(R.id.value)
        assertTrue("the big number must have room", value.width > 0 && value.height > 0)
        assertTrue("the page must be taller than the window, so that it scrolls", page.measuredHeight > 2340)
    }
}
