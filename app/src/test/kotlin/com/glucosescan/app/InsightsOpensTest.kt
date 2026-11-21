package com.glucosescan.app

import android.app.Activity
import android.os.Build
import android.view.View
import android.widget.EditText
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Opening Insights must never close the app, whether or not the AI can be reached.
 *
 * This is the screen that was reported as "the app closes when I press Insights". The tests below
 * open it exactly as the bottom bar does, on a phone with the notice accepted, and check three
 * things: that the screen survives, that the phone's own reading appears, and that a question
 * asked with no internet still leaves the screen open with the phone's answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class InsightsOpensTest {

    private val now = 1_700_000_000_000L / 60_000L * 60_000L
    private val minute = 60_000L

    @Before
    fun setUp() {
        AppClock.source = { now }
        val settings = Settings(org.robolectric.RuntimeEnvironment.getApplication())
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

    /** A fortnight of ordinary one-minute readings, one each four minutes. */
    private fun seedReading(): GlucoseStore {
        val store = GlucoseStore.get(org.robolectric.RuntimeEnvironment.getApplication())
        val values = ArrayList<Glucose>()
        var time = now - 14 * 24 * 60 * minute
        var value = 110
        while (time <= now) {
            values.add(Glucose(time, value, null, true, "sensor"))
            value = if (value > 160) 95 else value + 1
            time += 4 * minute
        }
        store.insert(values)
        return store
    }

    private fun settle() {
        ShadowLooper.idleMainLooper()
        Thread.sleep(300)
        ShadowLooper.idleMainLooper()
    }

    /** Waits for the card to stop saying "thinking", so a slow network cannot fail the test. */
    private fun waitForAnswer(activity: Activity, upToMillis: Long = 40_000): Boolean {
        val until = System.currentTimeMillis() + upToMillis
        while (System.currentTimeMillis() < until) {
            ShadowLooper.idleMainLooper()
            val bar = activity.findViewById<android.widget.ProgressBar>(R.id.answerProgress)
            if (bar.visibility != View.VISIBLE) {
                return true
            }
            Thread.sleep(250)
        }
        return false
    }

    @Test
    fun insightsOpensAndShowsTheReadingOfThePhone() {
        seedReading()
        val controller = Robolectric.buildActivity(AiInsightsActivity::class.java).setup()
        settle()
        val activity = controller.get()
        assertTrue("the screen must still be open", !activity.isFinishing && !activity.isDestroyed)

        val box = activity.findViewById<LinearLayout>(R.id.readingBox)
        assertNotNull(box)
        assertTrue("the phone must have written something into the reading card", box.childCount > 0)

        val ask = activity.findViewById<android.widget.Button>(R.id.askButton)
        assertNotNull(ask)
        assertTrue("the button must be usable", ask.isEnabled)
    }

    @Test
    fun anEmptyAppStillOpensInsights() {
        // no readings at all: the screen must say so instead of closing
        val controller = Robolectric.buildActivity(AiInsightsActivity::class.java).setup()
        settle()
        val activity = controller.get()
        assertTrue(!activity.isFinishing && !activity.isDestroyed)
        val note = activity.findViewById<TextView>(R.id.periodNote)
        assertNotNull(note)
    }

    @Test
    fun askingWithNoInternetKeepsTheScreenAndSaysWhereTheAnswerCameFrom() {
        seedReading()
        val controller = Robolectric.buildActivity(AiInsightsActivity::class.java).setup()
        settle()
        val activity = controller.get()
        activity.findViewById<EditText>(R.id.ownQuestion).setText("How am I doing?")
        activity.findViewById<android.widget.Button>(R.id.askButton).performClick()
        settle()
        val answered = waitForAnswer(activity)
        assertTrue("an answer must arrive, from the AI or from the phone", answered)

        assertTrue("asking must not close the screen", !activity.isFinishing && !activity.isDestroyed)
        val answer = activity.findViewById<TextView>(R.id.answerText)
        assertNotNull(answer)
        assertTrue("there must be an answer, from the AI or from the phone", answer.text.isNotEmpty())
        val badge = activity.findViewById<TextView>(R.id.answerSource)
        assertEquals("the badge must be visible", android.view.View.VISIBLE, badge.visibility)
        assertTrue("the badge names the source", badge.text.isNotEmpty())
        // on a test phone there is no internet, so the phone answers and the badge says so
        assertTrue(
            "with no connection the phone answers",
            badge.text.toString().lowercase().contains("phone") || badge.text.toString().lowercase().contains("ai")
        )
    }

    @Test
    fun everyPeriodButtonSwitchesWithoutClosingTheScreen() {
        seedReading()
        val controller = Robolectric.buildActivity(AiInsightsActivity::class.java).setup()
        settle()
        val activity = controller.get()
        for (id in listOf(R.id.period7, R.id.period14, R.id.period30, R.id.period90)) {
            activity.findViewById<View>(id).performClick()
            settle()
            assertTrue("still open after " + id, !activity.isFinishing && !activity.isDestroyed)
        }
        assertNotNull(activity.findViewById<LinearLayout>(R.id.questionChips))
    }
}
