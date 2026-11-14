package com.glucosescan.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.glucosescan.app.core.Correction
import com.glucosescan.app.core.CorrectionRules
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.PairCheck
import com.glucosescan.app.core.ReferencePair
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.ValueSource
import com.glucosescan.app.data.Settings
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.DisplayState
import com.glucosescan.app.ui.GlucoseFormat
import com.glucosescan.app.ui.Texts

/**
 * Here the user tells the app the right value for the sensor value of this minute: the number
 * of a finger-prick meter, or of LibreLink. The app keeps these pairs and corrects the values
 * of this sensor with them, see [Correction].
 */
class CorrectionActivity : BaseActivity() {
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore

    /** The sensor value of this moment (not corrected), and whether it can be used for a pair. */
    private class Moment(val latest: Glucose?, val changePerMinute: Double?, val check: PairCheck)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_correction)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        settings = Settings(this)
        store = GlucoseStore.get(this)

        findViewById<View>(R.id.save).setOnClickListener { save() }
        findViewById<View>(R.id.deleteAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.correction_delete_question)
                .setPositiveButton(R.string.delete_yes) { _, _ ->
                    settings.correction = Correction.NONE
                    Toast.makeText(this, R.string.correction_deleted, Toast.LENGTH_SHORT).show()
                    render()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun moment(): Moment {
        // a correction is about the values the app has calculated itself, never about the
        // numbers from LibreLinkUp
        val own = store.table(ValueSource.SENSOR)
        val latest = own.latest()
        val recent = if (latest != null) own.since(latest.timestamp - 20 * 60_000L) else emptyList()
        val state = DisplayState.of(recent, AppClock.now())
        val check = CorrectionRules.checkSensorSide(latest, state.kind == DisplayState.Kind.CURRENT, state.changePerMinute)
        return Moment(latest, state.changePerMinute, check)
    }

    private fun render() {
        val unit = settings.unit ?: GlucoseUnit.MGDL
        val correction = settings.correction
        val moment = moment()
        val latest = moment.latest

        // the value of this moment
        val nowBox = findViewById<LinearLayout>(R.id.nowBox)
        nowBox.removeAllViews()
        if (latest != null) {
            val time = Texts.timeOrDateTime(this, latest.timestamp, AppClock.now())
            addRow(nowBox, getString(R.string.correction_sensor_value), getString(R.string.correction_value_at, Texts.valueWithUnit(this, latest, unit), time))
            if (correction.appliesTo(latest)) {
                addRow(nowBox, getString(R.string.correction_shown_value), Texts.valueWithUnit(this, correction.apply(latest), unit))
            }
        }

        val canSave = moment.check == PairCheck.OK
        val status = findViewById<TextView>(R.id.status)
        status.text = problem(moment, unit) ?: getString(R.string.correction_ready)
        status.setTextColor(getColor(if (canSave) R.color.green else R.color.red))
        findViewById<View>(R.id.inputRow).visibility = if (canSave) View.VISIBLE else View.GONE
        findViewById<View>(R.id.save).visibility = if (canSave) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.inputUnit).text = unit.label
        if (!canSave) {
            findViewById<View>(R.id.inputError).visibility = View.GONE
        }

        // what is saved
        val summary = findViewById<TextView>(R.id.summary)
        val otherSensor = findViewById<View>(R.id.otherSensor)
        val tip = findViewById<TextView>(R.id.tip)
        val pairsBox = findViewById<LinearLayout>(R.id.pairsBox)
        pairsBox.removeAllViews()
        otherSensor.visibility = View.GONE
        tip.visibility = View.GONE
        findViewById<View>(R.id.deleteAll).visibility = if (correction.isActive) View.VISIBLE else View.GONE
        findViewById<View>(R.id.pairsHeader).visibility = if (correction.isActive) View.VISIBLE else View.GONE
        if (!correction.isActive) {
            summary.text = getString(R.string.correction_none)
            return
        }

        val count = correction.pairs.size
        summary.text = resources.getQuantityString(R.plurals.correction_count, count, count, correction.sensorSerial)
        if (latest != null && !correction.appliesTo(latest)) {
            otherSensor.visibility = View.VISIBLE
        }
        val inflater = LayoutInflater.from(this)
        for (pair in correction.pairs.reversed()) {
            val row = inflater.inflate(R.layout.row_pair, pairsBox, false)
            row.findViewById<TextView>(R.id.pairText).text = getString(
                R.string.correction_pair,
                GlucoseFormat.value(pair.sensorValue, unit),
                GlucoseFormat.value(pair.referenceValue, unit) + " " + unit.label
            )
            row.findViewById<TextView>(R.id.pairTime).text = Texts.dateTime(this, pair.time)
            row.findViewById<View>(R.id.pairDelete).setOnClickListener {
                settings.correction = settings.correction.removing(pair.time)
                Toast.makeText(this, R.string.correction_pair_deleted, Toast.LENGTH_SHORT).show()
                render()
            }
            pairsBox.addView(row)
        }
        if (!correction.hasLine) {
            tip.text = getString(R.string.correction_tip, GlucoseFormat.value(Correction.MIN_SPREAD, unit) + " " + unit.label)
            tip.visibility = View.VISIBLE
        }
    }

    /** Why the sensor value of this moment cannot be used, or null if it can. */
    private fun problem(moment: Moment, unit: GlucoseUnit): String? {
        return when (moment.check) {
            PairCheck.NO_VALUE, PairCheck.NO_SENSOR -> getString(R.string.correction_no_value)
            PairCheck.OLD_VALUE -> getString(R.string.correction_old_value)
            PairCheck.NOT_MEASURABLE -> getString(R.string.correction_not_measurable)
            PairCheck.CHANGE_UNKNOWN -> getString(R.string.correction_change_unknown)
            PairCheck.NOT_STEADY -> getString(R.string.correction_not_steady, GlucoseFormat.change(moment.changePerMinute ?: 0.0, unit), unit.label)
            PairCheck.OK, PairCheck.OUT_OF_RANGE, PairCheck.TOO_DIFFERENT -> null
        }
    }

    private fun save() {
        val unit = settings.unit ?: GlucoseUnit.MGDL
        val input = findViewById<EditText>(R.id.input)
        val moment = moment()
        val latest = moment.latest
        val serial = latest?.sensorSerial
        // the value can have got too old while this screen was open
        if (moment.check != PairCheck.OK || latest == null || serial == null) {
            render()
            return
        }

        val typed = GlucoseFormat.parse(input.text.toString(), unit)
        if (typed == null) {
            showError(getString(R.string.correction_not_a_number))
            return
        }
        when (CorrectionRules.checkReference(latest.value, typed)) {
            PairCheck.OUT_OF_RANGE -> {
                showError(getString(R.string.correction_out_of_range, GlucoseFormat.value(40, unit), GlucoseFormat.value(500, unit), unit.label))
                return
            }
            PairCheck.TOO_DIFFERENT -> {
                val most = Math.floor(CorrectionRules.maxShift(latest.value.toDouble())).toInt()
                showError(getString(R.string.correction_too_different, Texts.valueWithUnit(this, latest, unit), GlucoseFormat.value(most, unit) + " " + unit.label))
                return
            }
            else -> findViewById<View>(R.id.inputError).visibility = View.GONE
        }

        AlertDialog.Builder(this)
            .setMessage(getString(R.string.correction_confirm, Texts.valueWithUnit(this, latest, unit), GlucoseFormat.value(typed, unit) + " " + unit.label))
            .setPositiveButton(R.string.correction_save) { _, _ ->
                settings.correction = settings.correction.adding(serial, ReferencePair(latest.timestamp, latest.value, typed))
                input.setText("")
                Toast.makeText(this, R.string.correction_saved, Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showError(text: String) {
        val error = findViewById<TextView>(R.id.inputError)
        error.text = text
        error.visibility = View.VISIBLE
    }

    private fun addRow(box: LinearLayout, label: String, text: String) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_label_value, box, false)
        row.findViewById<TextView>(R.id.label).text = label
        row.findViewById<TextView>(R.id.text).text = text
        box.addView(row)
    }
}
