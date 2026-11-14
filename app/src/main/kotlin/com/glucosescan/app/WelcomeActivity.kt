package com.glucosescan.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.Settings

/** First start: the safety notice and the choice of the unit. */
class WelcomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        val settings = Settings(this)

        val units = findViewById<RadioGroup>(R.id.unitGroup)
        val accept = findViewById<CheckBox>(R.id.accept)
        val start = findViewById<Button>(R.id.start)

        when (settings.unit) {
            GlucoseUnit.MGDL -> units.check(R.id.unitMgdl)
            GlucoseUnit.MMOLL -> units.check(R.id.unitMmoll)
            null -> { }
        }

        fun update() {
            start.isEnabled = accept.isChecked && units.checkedRadioButtonId != -1
        }
        units.setOnCheckedChangeListener { _, _ -> update() }
        accept.setOnCheckedChangeListener { _, _ -> update() }
        update()

        start.setOnClickListener {
            val unit = when (units.checkedRadioButtonId) {
                R.id.unitMgdl -> GlucoseUnit.MGDL
                R.id.unitMmoll -> GlucoseUnit.MMOLL
                else -> null
            }
            if (unit != null && accept.isChecked) {
                settings.unit = unit
                settings.noticeAccepted = true
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }
}
