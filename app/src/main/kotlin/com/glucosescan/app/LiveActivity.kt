package com.glucosescan.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.glucosescan.app.ble.LivePermissions
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LiveState
import com.glucosescan.app.linkup.LinkUpRunner
import com.glucosescan.app.data.Settings
import com.glucosescan.app.live.LiveBus
import com.glucosescan.app.live.LiveNotifications
import com.glucosescan.app.live.LiveService
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.Texts

/** Switches live values (Bluetooth) on and off and shows what the connection does. */
class LiveActivity : BaseActivity() {
    private lateinit var settings: Settings
    private var ready = false
    private val refresh = Runnable { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!noticeAccepted()) {
            return
        }
        settings = Settings(this)
        setContentWithBack(R.layout.activity_live)

        val switch = findViewById<Switch>(R.id.liveSwitch)
        switch.isChecked = settings.liveEnabled
        switch.setOnCheckedChangeListener { _, on ->
            if (on == settings.liveEnabled) {
                return@setOnCheckedChangeListener
            }
            if (on) {
                askBeforeSwitchingOn(switch)
            } else {
                switchOff()
            }
        }
        findViewById<View>(R.id.liveAllowBluetooth).setOnClickListener {
            requestPermissions(LivePermissions.bluetooth() + LivePermissions.search(), REQUEST_PERMISSIONS)
        }
        findViewById<View>(R.id.liveAllowNotifications).setOnClickListener {
            requestPermissions(LivePermissions.notifications(), REQUEST_PERMISSIONS)
        }
        findViewById<View>(R.id.livePairAgain).setOnClickListener {
            settings.livePairWanted = true
            Toast.makeText(this, R.string.live_pair_again_done, Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.liveBatteryButton).setOnClickListener { askForBackgroundUse() }
        findViewById<View>(R.id.liveLogCopy).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.live_details_title), settings.liveLog))
            if (Build.VERSION.SDK_INT < 33) { // newer Android versions show their own note
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        ready = true
    }

    override fun onResume() {
        super.onResume()
        if (!ready) {
            return
        }
        LiveBus.add(refresh)
        if (settings.liveEnabled && !settings.linkUpEnabled) {
            LiveService.start(this) // the user may just have given a permission in the Android settings
        }
        render()
    }

    override fun onPause() {
        super.onPause()
        LiveBus.remove(refresh)
    }

    /** The user must say yes once more: LibreLink loses its values and its alarms. */
    private fun askBeforeSwitchingOn(switch: Switch) {
        var agreed = false
        val words = (if (settings.linkUpEnabled) getString(R.string.live_confirm_linkup) else "") + getString(R.string.live_confirm_text)
        AlertDialog.Builder(this)
            .setTitle(R.string.live_confirm_title)
            .setMessage(words)
            .setPositiveButton(R.string.live_confirm_yes) { _, _ ->
                agreed = true
                switchOn()
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                if (!agreed) {
                    switch.isChecked = false
                }
            }
            .show()
    }

    internal fun switchOn() {
        if (settings.linkUpEnabled) {
            // the two ways never run together
            LinkUpRunner(GlucoseStore.get(this), settings).switchOff(forgetLogin = true)
            LiveService.stop(this)
        }
        // The sensor must be set up for this app by a scan. An older set-up may not be good any
        // more (LibreLink may have taken the sensor back), so the next scan makes a new one.
        settings.liveSensor = null
        settings.liveAddress = null
        settings.livePairWanted = true
        settings.liveWaitingSince = AppClock.now()
        settings.liveState = LiveState.NEEDS_SCAN
        settings.liveEnabled = true

        val missing = LivePermissions.all().filter { !LivePermissions.isGranted(this, it) }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            LiveService.start(this)
        }
        render()
    }

    private fun switchOff() {
        settings.liveEnabled = false
        LiveService.stop(this)
        settings.liveState = LiveState.OFF
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS || !ready) {
            return
        }
        if (!LivePermissions.hasBluetooth(this)) {
            Toast.makeText(this, R.string.live_permission_denied, Toast.LENGTH_LONG).show()
        }
        if (settings.liveEnabled) {
            LiveService.start(this)
        }
        render()
    }

    @SuppressLint("BatteryLife") // the app is not in a store; alarms need to run in the background
    private fun askForBackgroundUse() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                // this phone has no such screen
            }
        }
    }

    private fun render() {
        if (!ready || isDestroyed) {
            return
        }
        val on = settings.liveEnabled
        val switch = findViewById<Switch>(R.id.liveSwitch)
        if (switch.isChecked != on) {
            switch.isChecked = on
        }
        for (id in intArrayOf(R.id.liveStatusCard, R.id.liveBatteryCard, R.id.liveLogCard)) {
            findViewById<View>(id).visibility = if (on) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.liveWarningTitle).setText(if (on) R.string.live_warning_title_on else R.string.live_warning_title)
        if (!on) {
            return
        }

        val now = AppClock.now()
        val hasBluetooth = LivePermissions.hasBluetooth(this)
        val canSearch = LivePermissions.canSearch(this)
        val state = if (!hasBluetooth) LiveState.NO_PERMISSION else settings.liveState
        val stateText = findViewById<TextView>(R.id.liveStateText)
        stateText.text = LiveNotifications.stateText(this, state)
        stateText.setTextColor(getColor(if (LiveNotifications.isProblem(state)) R.color.red else R.color.text))

        val sensor = settings.liveSensor
        findViewById<TextView>(R.id.liveSensorText).text =
            if (sensor != null) getString(R.string.live_sensor_line, sensor.serial ?: "–") else getString(R.string.live_sensor_none)
        // without a sensor the next scan sets one up anyway
        findViewById<View>(R.id.livePairAgain).visibility = if (sensor != null) View.VISIBLE else View.GONE
        val last = settings.liveLastMessageTime
        findViewById<TextView>(R.id.liveMessageText).text =
            if (last > 0L) getString(R.string.live_last_message, Texts.timeOrDateTime(this, last, now)) else getString(R.string.live_no_message)

        findViewById<View>(R.id.liveAllowBluetooth).visibility = if (canSearch) View.GONE else View.VISIBLE
        findViewById<View>(R.id.livePermissionNote).visibility = if (canSearch) View.GONE else View.VISIBLE
        findViewById<View>(R.id.liveAllowNotifications).visibility = if (LivePermissions.hasNotifications(this)) View.GONE else View.VISIBLE

        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val free = power != null && power.isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.liveBatteryText).setText(if (free) R.string.live_battery_ok else R.string.live_battery_text)
        findViewById<View>(R.id.liveBatteryButton).visibility = if (free) View.GONE else View.VISIBLE

        val log = settings.liveLog
        findViewById<TextView>(R.id.liveLogText).text = if (log.isEmpty()) getString(R.string.live_details_empty) else log
        findViewById<View>(R.id.liveLogCopy).isEnabled = log.isNotEmpty()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 11
    }
}
