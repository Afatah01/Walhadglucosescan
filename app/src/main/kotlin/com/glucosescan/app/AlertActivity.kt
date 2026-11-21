package com.glucosescan.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.Settings
import com.glucosescan.app.ui.GlucoseFormat
import com.glucosescan.app.ui.Texts

/**
 * The severe alert, on the screen, when — and only when — Android lets the app put it there:
 * a full-screen intent from the alert notification, for VERY LOW / VERY HIGH, on a phone that
 * granted this app the full-screen intent access (Android 14 and newer), or on older Android
 * where the platform decides by the importance of the channel.
 *
 * It shows a measured value and when it was measured. Never a forecast. It does not open by
 * itself for ordinary readings, and the official Libre alarms stay the primary safety net.
 *
 * Deliberately not a [com.glucosescan.app.BaseActivity]: this screen must not enable NFC reader
 * mode in the middle of an alarm.
 */
class AlertActivity : Activity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_VALUE = "value"
        const val EXTRA_MEASURED_AT = "measured_at"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setContentView(R.layout.activity_alert)

        val unit = Settings(this).unit ?: GlucoseUnit.MGDL
        val kind = intent.getStringExtra(EXTRA_KIND) ?: ""
        val value = intent.getIntExtra(EXTRA_VALUE, -1)
        val measuredAt = intent.getLongExtra(EXTRA_MEASURED_AT, 0L)

        findViewById<TextView>(R.id.alertLabel).text = when (kind) {
            "VERY_LOW" -> getString(R.string.alert_screen_very_low)
            "VERY_HIGH" -> getString(R.string.alert_screen_very_high)
            "LOW" -> getString(R.string.alert_screen_low)
            "HIGH" -> getString(R.string.alert_screen_high)
            else -> kind
        }
        findViewById<TextView>(R.id.alertValue).text =
            if (value > 0) getString(R.string.mon_value_unit, GlucoseFormat.value(value, unit), unit.label) else "—"
        findViewById<TextView>(R.id.alertMeasured).text =
            if (measuredAt > 0L) getString(R.string.alert_screen_measured, Texts.dateTime(this, measuredAt)) else ""

        findViewById<Button>(R.id.alertOpenMonitoring).setOnClickListener {
            startActivity(Intent(this, MonitoringActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        findViewById<Button>(R.id.alertDismiss).setOnClickListener { finish() }
    }
}
