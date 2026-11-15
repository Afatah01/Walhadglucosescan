package com.glucosescan.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.glucosescan.app.core.ScanStatus
import com.glucosescan.app.data.Settings
import com.glucosescan.app.nfc.ScanRunner
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.GlucoseFormat
import com.glucosescan.app.ui.Texts

/**
 * The screen for scanning the sensor: a picture of it, what to do, and what the last scan read.
 *
 * Two things here are never made up:
 *
 * - **The sensor number** is whatever the sensor itself gave over NFC. If the tag does not hand
 *   over an identifier, the screen says "Not available" rather than showing a number that looks
 *   like one. A wrong number is worse than none: it is the number a doctor reads off the box.
 * - **What the phone can do about NFC.** Android does not read NFC while the screen is off, and
 *   no app can change that. The screen says so instead of promising a background scan that would
 *   not happen. A Libre 3 has no NFC at all, and the screen says that too.
 *
 * While this screen is open the phone looks for the sensor (NFC reader mode), so the person does
 * not have to press anything.
 */
class ScanSensorActivity : Activity(), NfcAdapter.ReaderCallback {

    private lateinit var settings: Settings
    private var nfcAdapter: NfcAdapter? = null
    private var resumed = false

    private val handler = Handler(Looper.getMainLooper())

    /** True while a scan is read that came from an intent (the phone opened this app). */
    @Volatile
    private var intentScanPending = false

    /** Keeps the progress bar moving while a scan is running. */
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 400L)
        }
    }

    private val nfcStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateReaderMode()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(R.layout.activity_scan_sensor)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        findViewById<Button>(R.id.scanFix).setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
            }
        }
        findViewById<TextView>(R.id.scanHelp).text = getString(R.string.scan_help)

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(nfcStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(nfcStateReceiver, filter)
        }
        updateReaderMode()
        handler.removeCallbacks(tick)
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(tick)
        unregisterReceiver(nfcStateReceiver)
        try {
            nfcAdapter?.disableReaderMode(this)
        } catch (e: RuntimeException) {
            // NFC service is gone; nothing to switch off
        }
    }

    /** While this screen is open, the phone looks for NFC-V tags and gives them to us. */
    private fun updateReaderMode() {
        val adapter = nfcAdapter ?: return
        // Switching this on makes the phone look for tags anew, which would break a read that
        // is already going on, so it waits until that read is done.
        if (!resumed || !adapter.isEnabled || intentScanPending) {
            return
        }
        val options = Bundle()
        // do not ask "is the tag still there" while the memory is read
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000)
        try {
            adapter.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                options
            )
        } catch (e: RuntimeException) {
            // NFC service is gone; the screen shows that NFC is off
        }
    }

    /** The phone found a tag while this screen is open. Called on a background thread. */
    override fun onTagDiscovered(tag: Tag) {
        scan(tag)
    }

    /** The phone opened this screen because it touched a sensor (if switched on in the settings). */
    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != NfcAdapter.ACTION_TECH_DISCOVERED) {
            return
        }
        @Suppress("DEPRECATION")
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            intentScanPending = true
            Thread({
                try {
                    scan(tag)
                } finally {
                    intentScanPending = false
                    handler.post { updateReaderMode() }
                }
            }, "sensor-scan").start()
        }
    }

    /** Reads the sensor and stays on this screen to show what came of it. */
    private fun scan(tag: Tag) {
        ScanRunner.scan(this, tag)
        handler.post { render() }
    }

    // ---------------------------------------------------------------- showing

    private fun render() {
        if (isFinishing || isDestroyed) {
            return
        }
        val now = AppClock.now()
        renderBlocked()
        renderNumber()
        renderSensor(now)
        renderResult()
    }

    /** No NFC on this phone, or it is switched off: say so, and offer the way to switch it on. */
    private fun renderBlocked() {
        val adapter = nfcAdapter
        val box = findViewById<TextView>(R.id.scanBlocked)
        val fix = findViewById<Button>(R.id.scanFix)
        val scanning = ScanRunner.isScanning

        findViewById<ProgressBar>(R.id.scanProgress).visibility =
            if (scanning) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.scanTitle).text =
            if (scanning) getString(R.string.scan_reading) else getString(R.string.scan_prompt)

        when {
            adapter == null -> {
                box.text = getString(R.string.nfc_missing)
                box.visibility = View.VISIBLE
                fix.visibility = View.GONE
            }
            !adapter.isEnabled -> {
                box.text = getString(R.string.nfc_off)
                box.visibility = View.VISIBLE
                fix.visibility = View.VISIBLE
            }
            else -> {
                box.visibility = View.GONE
                fix.visibility = View.GONE
            }
        }
    }

    /**
     * The number of the sensor, exactly as the sensor gave it. If it did not give one, the
     * screen says so: a made-up number here would be read as a real one.
     */
    private fun renderNumber() {
        val sensor = settings.lastSensor
        val number = findViewById<TextView>(R.id.sensorNumber)
        val note = findViewById<TextView>(R.id.sensorNumberNote)
        val serial = sensor?.serial?.takeIf { it.isNotBlank() }

        if (serial == null) {
            number.text = getString(R.string.scan_number_none)
            number.setTextColor(getColor(R.color.text_tertiary))
            note.text = if (sensor == null) {
                getString(R.string.scan_number_waiting)
            } else {
                getString(R.string.scan_number_note)
            }
        } else {
            number.text = serial
            number.setTextColor(getColor(R.color.text))
            note.text = getString(R.string.scan_number_note)
        }
    }

    /** What the last scan told about the sensor itself. */
    private fun renderSensor(now: Long) {
        val box = findViewById<LinearLayout>(R.id.sensorBox)
        box.removeAllViews()
        val sensor = settings.lastSensor ?: return

        val minutesSinceScan = Math.max(0L, (now - sensor.scanTime) / 60_000L).coerceAtMost(1_000_000L).toInt()
        val running = sensor.status == ScanStatus.OK || sensor.status == ScanStatus.STARTING
        val ageNow = if (running) sensor.age + minutesSinceScan else sensor.age
        val status = if (sensor.status == ScanStatus.OK && ageNow >= sensor.lifetime) ScanStatus.EXPIRED else sensor.status
        val startTime = sensor.scanTime - sensor.age * 60_000L

        addRow(box, getString(R.string.sensor_state), Texts.stateLabel(this, status),
            status != ScanStatus.OK && status != ScanStatus.STARTING)

        if (sensor.status != ScanStatus.NOT_STARTED && sensor.age > 0) {
            addRow(box, getString(R.string.sensor_start), Texts.dateTime(this, startTime), false)
            if (sensor.lifetime > 0) {
                addRow(box, getString(R.string.sensor_end), Texts.dateTime(this, startTime + sensor.lifetime * 60_000L), false)
            }
        }
        if (status == ScanStatus.OK) {
            addRow(box, getString(R.string.sensor_age), GlucoseFormat.duration(ageNow), false)
            addRow(box, getString(R.string.sensor_left), GlucoseFormat.duration(Math.max(0, sensor.lifetime - ageNow)), false)
        }
        addRow(box, getString(R.string.sensor_type), sensor.typeLabel, false)
        addRow(box, getString(R.string.sensor_region), sensor.regionLabel, false)
        addRow(box, getString(R.string.sensor_as_of, Texts.timeOrDateTime(this, sensor.scanTime, now)), "", false)
    }

    /** What the last scan came to. */
    private fun renderResult() {
        val card = findViewById<View>(R.id.resultCard)
        val outcome = settings.lastOutcome ?: run {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.scanResult).text =
            Texts.scanMessage(this, outcome) + "\n" + Texts.timeOrDateTime(this, outcome.time, AppClock.now())
    }

    private fun addRow(box: LinearLayout, label: String, text: String, problem: Boolean) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_label_value, box, false)
        row.findViewById<TextView>(R.id.label).text = label
        val textView = row.findViewById<TextView>(R.id.text)
        textView.text = text
        if (problem) {
            textView.setTextColor(getColor(R.color.red))
        }
        box.addView(row)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
