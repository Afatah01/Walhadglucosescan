package com.glucosescan.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.glucosescan.app.core.AlertEngine
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.Trend
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings
import com.glucosescan.app.live.LinkUpWords
import com.glucosescan.app.live.LiveBus
import com.glucosescan.app.live.LiveService
import com.glucosescan.app.monitoring.MonitoringEvents
import com.glucosescan.app.BuildConfig
import com.glucosescan.app.cloud.BackupRunner
import com.glucosescan.app.data.ForecastLog
import com.glucosescan.app.data.ValueSource
import com.glucosescan.app.net.Net
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.CurrentValues
import com.glucosescan.app.ui.GlucoseFormat
import com.glucosescan.app.ui.Texts
import android.os.Handler
import android.os.Looper

/**
 * The monitoring screen: what the sync, the alert engine, the network and the online copy are
 * doing *right now*, straight from the phone and from the app's own records. No status is
 * ever painted: if the app cannot know, it says so.
 */
class MonitoringActivity : BaseActivity() {

    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private val busListener = Runnable { refresh() }
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        store = GlucoseStore.get(this)
        setContentWithBack(R.layout.activity_monitoring)

        findViewById<Button>(R.id.syncNowButton).setOnClickListener {
            it.isEnabled = false
            Toast.makeText(this, getString(R.string.monitoring_sync_now_running), Toast.LENGTH_SHORT).show()
            val app = applicationContext
            Net.patient.execute {
                val now = AppClock.now()
                val store = GlucoseStore.get(app)
                val freshSettings = Settings(app)
                val runner = BackupRunner(store, freshSettings, Net.transport)
                val result = try {
                    runner.copy(now)
                } catch (e: Exception) {
                    null
                }
                val pingResult = runner.ping(now)
                val message = when {
                    result == null -> getString(R.string.monitoring_sync_now_failed, "unexpected error")
                    result.isOk && pingResult.isOk -> getString(R.string.monitoring_sync_now_done, result.count)
                    result.isOk -> getString(R.string.monitoring_sync_now_failed, "readings copied, but the connection check failed")
                    else -> getString(R.string.monitoring_sync_now_failed, result.failure!!.detail)
                }
                Handler(Looper.getMainLooper()).post {
                    val button = findViewById<Button>(R.id.syncNowButton)
                    button.isEnabled = true
                    Toast.makeText(this@MonitoringActivity, message, Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
        }
        findViewById<Button>(R.id.testAlertButton).setOnClickListener {
            val posted = MonitoringEvents.sendTestAlert(this)
            Toast.makeText(
                this,
                getString(if (posted) R.string.monitoring_test_posted else R.string.monitoring_test_failed),
                Toast.LENGTH_LONG
            ).show()
            refresh()
        }
        findViewById<View>(R.id.rowBattery).setOnClickListener { requestNotOptimized() }
        ready = true
    }

    override fun onResume() {
        super.onResume()
        if (ready) {
            LiveBus.add(busListener)
            refresh()
        }
    }

    override fun onPause() {
        if (ready) {
            LiveBus.remove(busListener)
        }
        super.onPause()
    }

    private fun row(id: Int): View = findViewById(id)

    private fun label(view: View): TextView = view.findViewById(R.id.rowLabel)

    private fun value(view: View): TextView = view.findViewById(R.id.rowValue)

    private fun setRow(id: Int, labelText: String, valueText: String, valueColor: Int?) {
        val view = row(id)
        label(view).text = labelText
        value(view).text = valueText
        value(view).setTextColor(getColor(valueColor ?: R.color.text))
    }

    private fun refresh() {
        val now = AppClock.now()
        val unit = settings.unit ?: GlucoseUnit.MGDL
        val alarmSettings = settings.alarmSettings
        val current = CurrentValues.read(store, settings, now)
        val shown = current.shown
        val fresh = shown != null && AlertEngine.isFresh(shown.timestamp, now) && current.isCurrent

        // ---------------- right now
        setRow(
            R.id.rowCurrent,
            getString(R.string.monitoring_current_value),
            if (shown != null && fresh) {
                getString(R.string.mon_value_unit, GlucoseFormat.value(shown, unit), unit.label)
            } else {
                "—"
            },
            null
        )
        when {
            shown == null || !fresh -> setRow(R.id.rowStatus, getString(R.string.monitoring_status_label), getString(R.string.monitoring_status_none), R.color.amber)
            else -> when (AlertEngine.classify(shown.value, alarmSettings)) {
                AlertEngine.Measured.VERY_LOW -> setRow(R.id.rowStatus, getString(R.string.monitoring_status_label), getString(R.string.monitoring_status_very_low), R.color.red)
                AlertEngine.Measured.VERY_HIGH -> setRow(R.id.rowStatus, getString(R.string.monitoring_status_label), getString(R.string.monitoring_status_very_high), R.color.red)
                AlertEngine.Measured.LOW -> setRow(R.id.rowStatus, getString(R.string.monitoring_status_label), getString(R.string.monitoring_status_low), R.color.amber)
                AlertEngine.Measured.HIGH -> setRow(R.id.rowStatus, getString(R.string.monitoring_status_label), getString(R.string.monitoring_status_high), R.color.amber)
                AlertEngine.Measured.NORMAL -> setRow(R.id.rowStatus, getString(R.string.monitoring_status_label), getString(R.string.monitoring_status_normal), R.color.green)
            }
        }
        val trendWords = when (current.state.trend) {
            Trend.RAPIDLY_RISING, Trend.FAST_RISING -> R.string.monitoring_trend_rising_fast
            Trend.RISING -> R.string.monitoring_trend_rising
            Trend.CONSTANT -> R.string.monitoring_trend_steady
            Trend.FALLING -> R.string.monitoring_trend_falling
            Trend.FAST_FALLING, Trend.RAPIDLY_FALLING -> R.string.monitoring_trend_falling_fast
            Trend.UNKNOWN, null -> R.string.monitoring_trend_unknown
        }
        val arrow = current.state.trend?.arrow
        setRow(
            R.id.rowTrend,
            getString(R.string.monitoring_trend_label),
            if (fresh) (if (arrow != null) "$arrow " else "") + getString(trendWords) else getString(R.string.monitoring_trend_unknown),
            null
        )
        setRow(
            R.id.rowMeasured,
            getString(R.string.monitoring_measured_at),
            shown?.let { Texts.timeOrDateTime(this, it.timestamp, now) } ?: "—",
            if (shown != null && !fresh) R.color.amber else null
        )

        // ---------------- alert engine
        val serviceRes = when {
            !settings.valuesComeByThemselves -> R.string.monitoring_service_off
            LiveService.instance != null -> R.string.monitoring_service_running
            else -> R.string.monitoring_service_waiting
        }
        setRow(
            R.id.rowService, getString(R.string.monitoring_service_label), getString(serviceRes),
            when (serviceRes) {
                R.string.monitoring_service_running -> R.color.green
                R.string.monitoring_service_waiting -> R.color.amber
                else -> null
            }
        )
        val armed = mutableListOf<String>()
        if (settings.alarmLowOn) { armed += getString(R.string.monitoring_limit_on, getString(R.string.monitoring_label_low), GlucoseFormat.value(settings.alarmLow, unit)) }
        if (settings.alarmVeryLowOn) { armed += getString(R.string.monitoring_limit_on, getString(R.string.monitoring_label_very_low), GlucoseFormat.value(settings.alarmVeryLow, unit)) }
        if (settings.alarmHighOn) { armed += getString(R.string.monitoring_limit_on, getString(R.string.monitoring_label_high), GlucoseFormat.value(settings.alarmHigh, unit)) }
        if (settings.alarmVeryHighOn) { armed += getString(R.string.monitoring_limit_on, getString(R.string.monitoring_label_very_high), GlucoseFormat.value(settings.alarmVeryHigh, unit)) }
        if (settings.alarmRapidOn) { armed += getString(R.string.monitoring_limit_rate_on, (settings.alarmRapidRateTenths / 10.0).toString()) }
        setRow(
            R.id.rowAlerts, getString(R.string.monitoring_alerts_label),
            if (armed.isEmpty()) getString(R.string.monitoring_alerts_all_off) else armed.joinToString(" · "),
            if (armed.isEmpty()) R.color.amber else null
        )
        val lastAlert = store.latestAlert()
        if (lastAlert == null) {
            setRow(R.id.rowLastAlert, getString(R.string.monitoring_last_alert_label), getString(R.string.monitoring_last_alert_none), null)
        } else {
            val kind = lastAlert.kind
            val valueText = lastAlert.valueMgdl?.let { getString(R.string.mon_value_unit, GlucoseFormat.value(it, unit), unit.label) } ?: "—"
            val statusText = when (lastAlert.status) {
                "posted" -> getString(R.string.monitoring_event_posted)
                "failed" -> getString(R.string.monitoring_event_failed)
                "simulated" -> getString(R.string.monitoring_event_simulated)
                else -> lastAlert.status
            }
            setRow(
                R.id.rowLastAlert, getString(R.string.monitoring_last_alert_label),
                "$kind · $valueText · $statusText · " + Texts.timeOrDateTime(this, lastAlert.at, now),
                if (lastAlert.status == "failed") R.color.red else null
            )
        }
        val notifState = when {
            Build.VERSION.SDK_INT < 33 -> getString(R.string.monitoring_perm_na) to null
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED ->
                getString(R.string.monitoring_perm_granted) to R.color.green
            else -> getString(R.string.monitoring_perm_denied) to R.color.red
        }
        setRow(R.id.rowNotifPerm, getString(R.string.monitoring_notif_perm_label), notifState.first, notifState.second)
        val fsiState: Pair<String, Int?> = when {
            Build.VERSION.SDK_INT < 34 -> getString(R.string.monitoring_fsi_default) to null
            else -> try {
                val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
                if (manager?.canUseFullScreenIntent() == true) {
                    getString(R.string.monitoring_fsi_allowed) to R.color.green
                } else {
                    getString(R.string.monitoring_fsi_denied) to R.color.amber
                }
            } catch (e: RuntimeException) {
                getString(R.string.monitoring_fsi_denied) to R.color.amber
            }
        }
        setRow(R.id.rowFullScreen, getString(R.string.monitoring_fsi_label), fsiState.first, fsiState.second)

        // ---------------- sync
        if (settings.linkUpEnabled) {
            val problem = !settings.monitoringLastSyncOk && settings.monitoringLastSyncTime > 0L
            setRow(R.id.rowLinkUp, getString(R.string.monitoring_linkup_label), LinkUpWords.state(this, settings.linkUpState), if (problem) R.color.red else null)
        } else {
            setRow(R.id.rowLinkUp, getString(R.string.monitoring_linkup_label), getString(R.string.monitoring_linkup_unused), null)
        }
        val lastSync = settings.monitoringLastSyncTime
        if (lastSync == 0L) {
            setRow(R.id.rowLastSync, getString(R.string.monitoring_last_sync_label), getString(R.string.monitoring_sync_never), null)
        } else {
            val words = if (settings.monitoringLastSyncOk) getString(R.string.monitoring_sync_ok) else getString(R.string.monitoring_sync_problem) + if (settings.monitoringLastError.isNotEmpty()) " — " + settings.monitoringLastError else ""
            setRow(R.id.rowLastSync, getString(R.string.monitoring_last_sync_label), words + " · " + Texts.timeOrDateTime(this, lastSync, now), if (settings.monitoringLastSyncOk) null else R.color.red)
        }
        val newestReading = maxOf(settings.monitoringLastReadingAt, shown?.timestamp ?: 0L)
        setRow(
            R.id.rowLastReading, getString(R.string.monitoring_last_reading_label),
            if (newestReading > 0L) Texts.timeOrDateTime(this, newestReading, now) else getString(R.string.monitoring_never),
            if (newestReading > 0L && now - newestReading > 20 * 60_000L) R.color.amber else null
        )
        setRow(R.id.rowNetwork, getString(R.string.monitoring_network_label), networkWords(), if (networkUp()) null else R.color.red)
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        val optimized = pm?.isIgnoringBatteryOptimizations(packageName) == false
        setRow(
            R.id.rowBattery, getString(R.string.monitoring_battery_label),
            getString(if (optimized) R.string.monitoring_battery_optimized else R.string.monitoring_battery_free),
            if (optimized) R.color.amber else null
        )

        // ---------------- online copy: what the database itself answered
        val pingTime = settings.supabasePingTime
        when {
            pingTime == 0L -> setRow(R.id.rowSupabase, getString(R.string.monitoring_supabase_label), getString(R.string.monitoring_supabase_unchecked), R.color.amber)
            settings.supabasePingOk -> setRow(R.id.rowSupabase, getString(R.string.monitoring_supabase_label), getString(R.string.monitoring_supabase_ok, settings.supabasePingMillis.toInt(), Texts.timeOrDateTime(this, pingTime, now)), null)
            else -> setRow(R.id.rowSupabase, getString(R.string.monitoring_supabase_label), getString(R.string.monitoring_supabase_problem, settings.supabasePingWords, Texts.timeOrDateTime(this, pingTime, now)), R.color.red)
        }
        val waitingReadings = ValueSource.values().sumOf { store.table(it).unsyncedCount() }
        val waitingAlerts = store.unsyncedAlerts(1000).size
        val waitingSyncs = store.unsyncedSyncs(1000).size
        val waitingForecasts = ForecastLog.pendingSyncCount(store)
        val waitingTotal = waitingReadings + waitingAlerts + waitingSyncs + waitingForecasts
        setRow(
            R.id.rowPendingSync, getString(R.string.monitoring_pending_label),
            if (waitingTotal == 0) getString(R.string.monitoring_pending_none)
            else getString(R.string.monitoring_pending_counts, waitingReadings, waitingAlerts, waitingSyncs, waitingForecasts),
            if (waitingTotal == 0) null else R.color.amber
        )
        val problem = settings.backupProblem
        setRow(
            R.id.rowOnlineProblem, getString(R.string.monitoring_online_problem_label),
            if (problem.isEmpty()) getString(R.string.monitoring_online_problem_none) else "$problem — ${settings.backupDetail}",
            if (problem.isEmpty()) null else R.color.red
        )
        val sessionId = settings.monitoringSessionClientId
        val sessionSource = when (settings.monitoringSessionSource) {
            "linkup" -> getString(R.string.monitoring_session_source_linkup)
            "ble" -> getString(R.string.monitoring_session_source_ble)
            else -> settings.monitoringSessionSource.ifEmpty { "—" }
        }
        val sessionText = when {
            sessionId.isEmpty() -> getString(R.string.monitoring_session_none)
            settings.monitoringSessionEndedAt == 0L -> getString(R.string.monitoring_session_open, Texts.timeOrDateTime(this, settings.monitoringSessionStartedAt, now), sessionSource)
            else -> {
                val copied = if (settings.monitoringSessionCopied) getString(R.string.monitoring_session_copied) else ""
                getString(R.string.monitoring_session_closed, Texts.timeOrDateTime(this, settings.monitoringSessionEndedAt, now), settings.monitoringSessionEndReason.ifEmpty { "ended" }, copied)
            }
        }
        setRow(R.id.rowSession, getString(R.string.monitoring_session_label), sessionText, if (sessionId.isNotEmpty() && settings.monitoringSessionEndedAt == 0L) R.color.green else null)
        val never = getString(R.string.monitoring_background_never)
        val workerRan = settings.monitoringLastWorkerTime.let { if (it > 0L) Texts.timeOrDateTime(this, it, now) else never }
        val tried = settings.backupLastTryTime.let { if (it > 0L) Texts.timeOrDateTime(this, it, now) else never }
        setRow(R.id.rowBackground, getString(R.string.monitoring_background_label), getString(R.string.monitoring_background, workerRan, tried), null)
        setRow(R.id.rowBuild, getString(R.string.monitoring_build_label), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_SHA}", null)

        // ---------------- records
        if (!settings.backupEnabled) {
            setRow(R.id.rowOnlineCopy, getString(R.string.monitoring_online_copy_label), getString(R.string.monitoring_online_copy_off), null)
        } else {
            val copied = settings.backupLastTime
            setRow(
                R.id.rowOnlineCopy, getString(R.string.monitoring_online_copy_label),
                getString(R.string.monitoring_online_copy_on, if (copied > 0L) Texts.timeOrDateTime(this, copied, now) else getString(R.string.monitoring_never)),
                null
            )
        }

        // ---------------- records so far
        val alerts = store.recentAlerts(5)
        findViewById<TextView>(R.id.lastAlertsList).text = if (alerts.isEmpty()) {
            getString(R.string.monitoring_events_none)
        } else {
            alerts.joinToString("\n") { r ->
                val time = Texts.timeOrDateTime(this, r.at, now)
                val valueText = r.valueMgdl?.let { GlucoseFormat.value(it, unit) } ?: "—"
                "$time · ${r.kind} · $valueText · ${r.status}"
            }
        }
        val syncs = store.recentSyncs(5)
        findViewById<TextView>(R.id.lastSyncsList).text = if (syncs.isEmpty()) {
            getString(R.string.monitoring_events_none)
        } else {
            syncs.joinToString("\n") { r ->
                val time = Texts.timeOrDateTime(this, r.at, now)
                val verdict = if (r.ok) getString(R.string.monitoring_sync_ok) else getString(R.string.monitoring_sync_problem)
                val detail = r.detail ?: r.kind
                "$time · $verdict · $detail"
            }
        }
    }

    private fun networkUp(): Boolean {
        return try {
            val connectivity = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = connectivity.activeNetwork ?: return false
            connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun networkWords(): String = getString(if (networkUp()) R.string.monitoring_network_online else R.string.monitoring_network_offline)

    /** Asks the user to stop optimising the battery for this app. Only ever opens the system dialog. */
    private fun requestNotOptimized() {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            return
        }
        try {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ignored: Exception) {
                // no screen offers this on this phone; the row tells the story already
            }
        }
    }
}
