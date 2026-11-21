package com.glucosescan.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.Settings
import com.glucosescan.app.live.AlarmSounder
import com.glucosescan.app.ui.GlucoseFormat

/** The alarms: low, high, very low, very high, rapid changes, no values. They work only with live values. */
class AlarmsActivity : BaseActivity() {
    private lateinit var settings: Settings
    private var sounder: AlarmSounder? = null
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!noticeAccepted()) {
            return
        }
        settings = Settings(this)
        setContentWithBack(R.layout.activity_alarms)
        sounder = AlarmSounder(this, Handler(Looper.getMainLooper()))

        findViewById<View>(R.id.alarmsOpenLive).setOnClickListener { startActivity(Intent(this, LiveActivity::class.java)) }
        findViewById<View>(R.id.alarmsOpenLinkUp).setOnClickListener { startActivity(Intent(this, LinkUpActivity::class.java)) }

        val low = findViewById<Switch>(R.id.alarmLowSwitch)
        low.isChecked = settings.alarmLowOn
        low.setOnCheckedChangeListener { _, on -> settings.alarmLowOn = on }
        val high = findViewById<Switch>(R.id.alarmHighSwitch)
        high.isChecked = settings.alarmHighOn
        high.setOnCheckedChangeListener { _, on -> settings.alarmHighOn = on }
        val noData = findViewById<Switch>(R.id.alarmNoDataSwitch)
        noData.isChecked = settings.alarmNoDataOn
        noData.setOnCheckedChangeListener { _, on -> settings.alarmNoDataOn = on }
        val lowSoon = findViewById<Switch>(R.id.alarmLowSoonSwitch)
        lowSoon.isChecked = settings.alarmLowSoonOn
        lowSoon.setOnCheckedChangeListener { _, on -> settings.alarmLowSoonOn = on }
        val veryLow = findViewById<Switch>(R.id.alarmVeryLowSwitch)
        veryLow.isChecked = settings.alarmVeryLowOn
        veryLow.setOnCheckedChangeListener { _, on -> settings.alarmVeryLowOn = on }
        val veryHigh = findViewById<Switch>(R.id.alarmVeryHighSwitch)
        veryHigh.isChecked = settings.alarmVeryHighOn
        veryHigh.setOnCheckedChangeListener { _, on -> settings.alarmVeryHighOn = on }
        val rapid = findViewById<Switch>(R.id.alarmRapidSwitch)
        rapid.isChecked = settings.alarmRapidOn
        rapid.setOnCheckedChangeListener { _, on -> settings.alarmRapidOn = on }
        val notifyValue = findViewById<Switch>(R.id.notifyValueSwitch)
        notifyValue.isChecked = settings.notifyValueOn
        notifyValue.setOnCheckedChangeListener { _, on -> settings.notifyValueOn = on }

        findViewById<View>(R.id.alarmLowMinus).setOnClickListener { change(-LOW_STEP, 0) }
        findViewById<View>(R.id.alarmLowPlus).setOnClickListener { change(LOW_STEP, 0) }
        findViewById<View>(R.id.alarmHighMinus).setOnClickListener { change(0, -HIGH_STEP) }
        findViewById<View>(R.id.alarmHighPlus).setOnClickListener { change(0, HIGH_STEP) }
        findViewById<View>(R.id.alarmVeryLowMinus).setOnClickListener { changeVeryLow(-VERY_LOW_STEP) }
        findViewById<View>(R.id.alarmVeryLowPlus).setOnClickListener { changeVeryLow(VERY_LOW_STEP) }
        findViewById<View>(R.id.alarmVeryHighMinus).setOnClickListener { changeVeryHigh(-VERY_HIGH_STEP) }
        findViewById<View>(R.id.alarmVeryHighPlus).setOnClickListener { changeVeryHigh(VERY_HIGH_STEP) }
        findViewById<View>(R.id.alarmRapidMinus).setOnClickListener { changeRapid(-RAPID_STEP) }
        findViewById<View>(R.id.alarmRapidPlus).setOnClickListener { changeRapid(RAPID_STEP) }

        val test = findViewById<Button>(R.id.alarmsTest)
        test.setOnClickListener {
            val sound = sounder ?: return@setOnClickListener
            if (sound.isPlaying) {
                sound.stop()
                test.setText(R.string.alarms_test)
            } else {
                test.setText(R.string.alarms_test_stop)
                sound.play(TEST_MILLIS, words = getString(R.string.alarms_test_words)) {
                    test.setText(R.string.alarms_test)
                }
            }
        }
        ready = true
        showLimits()
    }

    override fun onResume() {
        super.onResume()
        if (ready) {
            findViewById<View>(R.id.alarmsNeedLive).visibility = if (settings.valuesComeByThemselves) View.GONE else View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        if (ready) {
            sounder?.stop()
            findViewById<Button>(R.id.alarmsTest).setText(R.string.alarms_test)
        }
    }

    private fun change(low: Int, high: Int) {
        if (low != 0) {
            settings.alarmLow = settings.alarmLow + low
        }
        if (high != 0) {
            settings.alarmHigh = settings.alarmHigh + high
        }
        showLimits()
    }

    private fun changeVeryLow(delta: Int) {
        settings.alarmVeryLow = settings.alarmVeryLow + delta
        showLimits()
    }

    private fun changeVeryHigh(delta: Int) {
        settings.alarmVeryHigh = settings.alarmVeryHigh + delta
        showLimits()
    }

    private fun changeRapid(delta: Int) {
        settings.alarmRapidRateTenths = settings.alarmRapidRateTenths + delta
        showLimits()
    }

    private fun showLimits() {
        val unit = settings.unit ?: GlucoseUnit.MGDL
        val rate = settings.alarmRapidRateTenths / 10.0
        findViewById<TextView>(R.id.alarmLowValue).text = GlucoseFormat.value(settings.alarmLow, unit) + " " + unit.label
        findViewById<TextView>(R.id.alarmHighValue).text = GlucoseFormat.value(settings.alarmHigh, unit) + " " + unit.label
        findViewById<TextView>(R.id.alarmVeryLowValue).text = GlucoseFormat.value(settings.alarmVeryLow, unit) + " " + unit.label
        findViewById<TextView>(R.id.alarmVeryHighValue).text = GlucoseFormat.value(settings.alarmVeryHigh, unit) + " " + unit.label
        findViewById<TextView>(R.id.alarmRapidValue).text = String.format(java.util.Locale.US, "%.1f mg/dL/min", rate)
    }

    companion object {
        private const val LOW_STEP = 5
        private const val HIGH_STEP = 10
        private const val VERY_LOW_STEP = 5
        private const val VERY_HIGH_STEP = 10
        private const val RAPID_STEP = 5 // 0.5 mg/dL per minute, in tenths
        private const val TEST_MILLIS = 8_000L
    }
}
