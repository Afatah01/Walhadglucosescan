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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.glucosescan.app.ble.LivePermissions
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LinkUpState
import com.glucosescan.app.data.LiveState
import com.glucosescan.app.data.Settings
import com.glucosescan.app.linkup.LinkUpConnect
import com.glucosescan.app.linkup.LinkUpFailure
import com.glucosescan.app.linkup.LinkUpPatient
import com.glucosescan.app.linkup.LinkUpProblem
import com.glucosescan.app.linkup.LinkUpRunner
import com.glucosescan.app.live.LinkUpWords
import com.glucosescan.app.live.LiveBus
import com.glucosescan.app.live.LiveService
import com.glucosescan.app.net.Net
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.Texts

/**
 * Values from LibreLinkUp: the user logs in here, and the app then fetches a value each
 * minute over the internet. The sensor keeps its Bluetooth connection to FreeStyle LibreLink.
 */
class LinkUpActivity : BaseActivity() {
    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var busy = false
    private val refresh = Runnable { render() }

    /** What the screen said last under the "Connect" button. For tests. */
    internal var lastMessage: String = ""
        private set

    /** The window that is open (terms, choice of the person), or null. For tests. */
    internal var dialog: AlertDialog? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!noticeAccepted()) {
            return
        }
        settings = Settings(this)
        setContentWithBack(R.layout.activity_linkup)

        findViewById<EditText>(R.id.linkupEmail).setText(settings.linkUpEmail)
        findViewById<View>(R.id.linkupConnect).setOnClickListener { connectPressed() }
        findViewById<View>(R.id.linkupSwitchOff).setOnClickListener { switchOff() }
        findViewById<View>(R.id.linkupAllowNotifications).setOnClickListener {
            requestPermissions(LivePermissions.notifications(), REQUEST_PERMISSIONS)
        }
        findViewById<View>(R.id.linkupBatteryButton).setOnClickListener { askForBackgroundUse() }
        findViewById<View>(R.id.linkupLogCopy).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.linkup_details_title), settings.liveLog))
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
        if (settings.linkUpEnabled) {
            LiveService.start(this) // does nothing new if it runs already
        }
        render()
    }

    override fun onPause() {
        super.onPause()
        LiveBus.remove(refresh)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ logging in

    private fun connectPressed() {
        if (busy) {
            return
        }
        val email = findViewById<EditText>(R.id.linkupEmail).text.toString().trim()
        val password = findViewById<EditText>(R.id.linkupPassword).text.toString()
        val typedNothingNew = password.isEmpty() && (email.isEmpty() || email == settings.linkUpEmail)
        if (typedNothingNew && settings.linkUpStep.isNotEmpty() && settings.linkUpPassword.isNotEmpty()) {
            // The server waits for a yes to its terms. Nothing needs to be typed again: the app
            // logs in with what was typed last time, and the question comes back with a fresh pass.
            connect(settings.linkUpEmail, settings.linkUpPassword)
            return
        }
        if (!email.contains('@') || password.isEmpty()) {
            say(getString(R.string.linkup_error_fields))
            return
        }
        if (settings.liveEnabled) {
            // Bluetooth values and LibreLinkUp never run together
            show(
                AlertDialog.Builder(this)
                    .setTitle(R.string.linkup_bluetooth_title)
                    .setMessage(R.string.linkup_bluetooth_text)
                    .setPositiveButton(R.string.linkup_bluetooth_yes) { _, _ ->
                        settings.liveEnabled = false
                        LiveService.stop(this)
                        settings.liveState = LiveState.OFF
                        connect(email, password)
                    }
                    .setNegativeButton(R.string.cancel, null)
            )
            return
        }
        connect(email, password)
    }

    private fun connect(email: String, password: String) {
        work { runner -> runner.connect(email, password, AppClock.now()) }
    }

    /** Runs [task] away from the screen and shows what came of it. */
    private fun work(task: (LinkUpRunner) -> LinkUpConnect) {
        busy = true
        say("")
        render()
        val runner = LinkUpRunner(GlucoseStore.get(this), settings, Net.transport)
        Net.background.execute {
            val result = try {
                task(runner)
            } catch (e: Exception) {
                LinkUpConnect(emptyList(), LinkUpFailure(LinkUpProblem.BAD_ANSWER, Net.words(e)))
            }
            handler.post {
                busy = false
                if (!isDestroyed) {
                    connected(result)
                }
            }
        }
    }

    private fun connected(result: LinkUpConnect) {
        val failure = result.failure
        if (failure != null) {
            if (failure.problem == LinkUpProblem.NEEDS_STEP && (failure.step == "tou" || failure.step == "pp") && failure.stepToken.isNotEmpty()) {
                render()
                askStep(failure.step)
                return
            }
            say(problemWords(failure))
            render()
            return
        }
        val patients = result.patients
        when {
            patients.isEmpty() -> say(getString(R.string.linkup_error_no_patient))
            patients.size == 1 -> switchOn(patients[0])
            else -> {
                val names = patients.map { it.name.ifEmpty { getString(R.string.linkup_person_unnamed) } }.toTypedArray()
                show(
                    AlertDialog.Builder(this)
                        .setTitle(R.string.linkup_choose_title)
                        .setItems(names) { _, which -> switchOn(patients[which]) }
                        .setNegativeButton(R.string.cancel, null)
                )
            }
        }
        render()
    }

    /** Abbott wants its terms accepted again. Only the user can say yes to that. */
    private fun askStep(step: String) {
        show(
            AlertDialog.Builder(this)
                .setTitle(R.string.linkup_step_title)
                .setMessage(if (step == "pp") R.string.linkup_step_pp else R.string.linkup_step_tou)
                .setPositiveButton(R.string.linkup_step_yes) { _, _ -> work { runner -> runner.acceptStep(AppClock.now()) } }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    private fun switchOn(patient: LinkUpPatient) {
        LinkUpRunner(GlucoseStore.get(this), settings, Net.transport).choose(patient)
        settings.liveWaitingSince = AppClock.now()
        findViewById<EditText>(R.id.linkupPassword).setText("")
        say("")
        if (!LivePermissions.hasNotifications(this)) {
            requestPermissions(LivePermissions.notifications(), REQUEST_PERMISSIONS)
        }
        LiveService.start(this, LiveService.ACTION_LOOK)
        LiveBus.changed()
        render()
    }

    private fun switchOff() {
        LinkUpRunner(GlucoseStore.get(this), settings, Net.transport).switchOff(forgetLogin = true)
        LiveService.stop(this)
        say("")
        Toast.makeText(this, R.string.linkup_switched_off, Toast.LENGTH_LONG).show()
        LiveBus.changed()
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && ready) {
            if (settings.linkUpEnabled) {
                LiveService.start(this)
            }
            render()
        }
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

    // ------------------------------------------------------------------ showing

    private fun problemWords(failure: LinkUpFailure): String {
        return when (failure.problem) {
            LinkUpProblem.NO_INTERNET -> getString(R.string.linkup_error_no_internet)
            LinkUpProblem.WRONG_LOGIN -> getString(R.string.linkup_error_wrong_login)
            LinkUpProblem.LOCKED -> {
                val minutes = Math.max(1, (failure.waitSeconds + 59) / 60)
                resources.getQuantityString(R.plurals.linkup_error_locked, minutes, minutes)
            }
            else -> getString(R.string.linkup_error_server)
        }
    }

    private fun say(words: String) {
        lastMessage = words
        val message = findViewById<TextView>(R.id.linkupMessage)
        message.text = words
        message.visibility = if (words.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun show(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        dialog = builder.show()
    }

    private fun render() {
        if (!ready || isDestroyed) {
            return
        }
        val on = settings.linkUpEnabled
        val state = settings.linkUpState
        val now = AppClock.now()

        val connect = findViewById<Button>(R.id.linkupConnect)
        connect.isEnabled = !busy
        connect.setText(if (busy) R.string.linkup_busy else R.string.linkup_connect)

        // logging in is offered while the link is off, and when the server wants the user again
        findViewById<View>(R.id.linkupLoginCard).visibility = if (!on || state.stopsAsking) View.VISIBLE else View.GONE
        for (id in intArrayOf(R.id.linkupStatusCard, R.id.linkupBatteryCard, R.id.linkupLogCard)) {
            findViewById<View>(id).visibility = if (on) View.VISIBLE else View.GONE
        }
        if (!on) {
            return
        }

        val stateText = findViewById<TextView>(R.id.linkupStateText)
        stateText.text = LinkUpWords.state(this, state)
        stateText.setTextColor(getColor(if (state.isProblem) R.color.red else R.color.text))
        findViewById<TextView>(R.id.linkupPersonText).text =
            getString(R.string.linkup_person, settings.linkUpPatientName.ifEmpty { getString(R.string.linkup_person_unnamed) })
        findViewById<TextView>(R.id.linkupAccountText).text = getString(R.string.linkup_account, settings.linkUpEmail)
        val last = settings.linkUpLastAnswerTime
        findViewById<TextView>(R.id.linkupLastText).text =
            if (last > 0L) getString(R.string.linkup_last_answer, Texts.timeOrDateTime(this, last, now)) else getString(R.string.linkup_no_answer_yet)

        val notifications = LivePermissions.hasNotifications(this)
        findViewById<View>(R.id.linkupAllowNotifications).visibility = if (notifications) View.GONE else View.VISIBLE
        findViewById<View>(R.id.linkupNotificationsNote).visibility = if (notifications) View.GONE else View.VISIBLE

        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val free = power != null && power.isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.linkupBatteryText).setText(if (free) R.string.live_battery_ok else R.string.live_battery_text)
        findViewById<View>(R.id.linkupBatteryButton).visibility = if (free) View.GONE else View.VISIBLE

        val log = settings.liveLog
        findViewById<TextView>(R.id.linkupLogText).text = if (log.isEmpty()) getString(R.string.live_details_empty) else log
        findViewById<View>(R.id.linkupLogCopy).isEnabled = log.isNotEmpty()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 12
    }
}
