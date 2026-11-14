package com.glucosescan.app

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings
import com.glucosescan.app.ui.GlucoseFormat

class SettingsActivity : BaseActivity() {
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        settings = Settings(this)
        store = GlucoseStore.get(this)

        // unit
        val units = findViewById<RadioGroup>(R.id.unitGroup)
        units.check(if (settings.unit == GlucoseUnit.MMOLL) R.id.unitMmoll else R.id.unitMgdl)
        units.setOnCheckedChangeListener { _, id ->
            settings.unit = if (id == R.id.unitMmoll) GlucoseUnit.MMOLL else GlucoseUnit.MGDL
            showLimits()
        }

        // limits
        val step = Settings.LIMIT_STEP
        findViewById<View>(R.id.lowMinus).setOnClickListener { changeLimits(-step, 0) }
        findViewById<View>(R.id.lowPlus).setOnClickListener { changeLimits(step, 0) }
        findViewById<View>(R.id.highMinus).setOnClickListener { changeLimits(0, -step) }
        findViewById<View>(R.id.highPlus).setOnClickListener { changeLimits(0, step) }
        showLimits()

        // open by touching the sensor
        val alias = ComponentName(packageName, SENSOR_TOUCH_ALIAS)
        val touch = findViewById<Switch>(R.id.touchSwitch)
        touch.isChecked = packageManager.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        touch.setOnCheckedChangeListener { _, on ->
            val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
        }

        // "Correct the value": for the values the app reads itself
        findViewById<View>(R.id.openCorrection).setOnClickListener { startActivity(Intent(this, CorrectionActivity::class.java)) }

        // delete
        findViewById<View>(R.id.deleteButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.delete_question)
                .setPositiveButton(R.string.delete_yes) { _, _ ->
                    store.deleteAll()
                    settings.lastSensor = null
                    settings.lastOutcome = null
                    settings.lastScanDetails = ""
                    showCount()
                    Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        showCount()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun changeLimits(lowChange: Int, highChange: Int) {
        settings.setLimits(settings.lowLimit + lowChange, settings.highLimit + highChange)
        showLimits()
    }

    private fun showLimits() {
        val unit = settings.unit ?: GlucoseUnit.MGDL
        findViewById<TextView>(R.id.lowValue).text = GlucoseFormat.value(settings.lowLimit, unit) + " " + unit.label
        findViewById<TextView>(R.id.highValue).text = GlucoseFormat.value(settings.highLimit, unit) + " " + unit.label
    }

    private fun showCount() {
        // both sets: the values the app has read itself and the ones fetched from LibreLinkUp
        findViewById<TextView>(R.id.readingsCount).text = getString(R.string.readings_count, store.countAll())
    }

    companion object {
        const val SENSOR_TOUCH_ALIAS = "com.glucosescan.app.SensorTouchAlias"
    }
}
