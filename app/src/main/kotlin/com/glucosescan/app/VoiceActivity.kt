package com.glucosescan.app

import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.SpeechText
import com.glucosescan.app.core.Trend
import com.glucosescan.app.data.Settings
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.Speaker

/** What the app says aloud: after a scan, for live values, with an alarm. */
class VoiceActivity : BaseActivity() {
    private lateinit var settings: Settings
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!noticeAccepted()) {
            return
        }
        settings = Settings(this)
        setContentWithBack(R.layout.activity_voice)

        val afterScan = findViewById<Switch>(R.id.voiceAfterScan)
        afterScan.isChecked = settings.speakAfterScan
        afterScan.setOnCheckedChangeListener { _, on -> settings.speakAfterScan = on }
        val trend = findViewById<Switch>(R.id.voiceTrend)
        trend.isChecked = settings.speakTrend
        trend.setOnCheckedChangeListener { _, on ->
            settings.speakTrend = on
            showExample()
        }
        val lowSoon = findViewById<Switch>(R.id.voiceLowSoon)
        lowSoon.isChecked = settings.speakLowSoon
        lowSoon.setOnCheckedChangeListener { _, on -> settings.speakLowSoon = on }
        val alarms = findViewById<Switch>(R.id.voiceAlarms)
        alarms.isChecked = settings.speakAlarms
        alarms.setOnCheckedChangeListener { _, on -> settings.speakAlarms = on }

        for ((id, minutes) in LIVE) {
            findViewById<View>(id).setOnClickListener {
                settings.speakLiveMinutes = minutes
                showLive()
            }
        }

        findViewById<View>(R.id.voiceTest).setOnClickListener {
            val speaker = Speaker.get(this)
            speaker.onStatus = { runOnUiThread { showStatus(it) } }
            speaker.say(TEST_INTRO + " " + example(), SpeechText.number(exampleValue(), unit()))
            showStatus(speaker.status)
        }
        ready = true
        showLive()
        showExample()
    }

    override fun onDestroy() {
        if (ready) {
            Speaker.get(this).onStatus = null
        }
        super.onDestroy()
    }

    private fun unit(): GlucoseUnit = settings.unit ?: GlucoseUnit.MGDL

    /** A made-up value, only to show how a sentence sounds. */
    private fun exampleValue(): Glucose = Glucose(AppClock.now(), 104, null, true)

    private fun example(): String = SpeechText.value(exampleValue(), unit(), Trend.CONSTANT, false, false, settings.speakTrend)

    private fun showExample() {
        findViewById<TextView>(R.id.voiceExample).text = getString(R.string.voice_example, example())
    }

    private fun showLive() {
        val chosen = settings.speakLiveMinutes
        for ((id, minutes) in LIVE) {
            findViewById<View>(id).isSelected = minutes == chosen
        }
    }

    private fun showStatus(status: Speaker.Status) {
        if (isDestroyed) {
            return
        }
        val view = findViewById<TextView>(R.id.voiceStatus)
        view.setText(
            when (status) {
                Speaker.Status.STARTING -> R.string.voice_status_starting
                Speaker.Status.ENGLISH -> R.string.voice_status_ready
                Speaker.Status.NUMBERS_ONLY -> R.string.voice_status_numbers
                Speaker.Status.NO_VOICE -> R.string.voice_status_none
            }
        )
        view.setTextColor(getColor(if (status == Speaker.Status.NO_VOICE) R.color.red else R.color.text))
        view.visibility = View.VISIBLE
    }

    companion object {
        /** Said before the made-up value of the test, so that nobody takes it for a real one. */
        const val TEST_INTRO = "This is a test."

        private val LIVE = mapOf(
            R.id.voiceLive0 to 0,
            R.id.voiceLive5 to 5,
            R.id.voiceLive15 to 15,
            R.id.voiceLive30 to 30,
            R.id.voiceLive60 to 60
        )
    }
}
