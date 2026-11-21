package com.glucosescan.app.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LinkUpState
import com.glucosescan.app.data.LiveLog
import com.glucosescan.app.data.Settings
import com.glucosescan.app.linkup.LinkUpRunner
import com.glucosescan.app.monitoring.AlertPipeline
import com.glucosescan.app.monitoring.MonitoringEvents
import com.glucosescan.app.net.Net
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.CurrentValues
import java.util.concurrent.TimeUnit

/**
 * The background safety-net for monitoring.
 *
 * The live service already asks LibreLinkUp every minute while it runs. This worker is the
 * watchdog for when it does not: Android ends the service, the process is killed, the minute
 * alarm is swallowed — then this runs about every 15 minutes (the shortest period Android
 * allows for periodic work), asks LibreLinkUp, records what it found, and evaluates fresh
 * readings through the same alert pipeline. It never invents a reading: if none is due it
 * records "up to date" and stops. Failed runs are retried with exponential backoff.
 *
 * It is enqueued as unique work, so scheduling it again (app start, phone boot, package update)
 * never creates a second copy.
 */
class MonitoringSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val app = applicationContext
        val settings = Settings(app)
        settings.monitoringLastWorkerTime = AppClock.now()
        if (!settings.valuesComeByThemselves) {
            return Result.success() // live values are switched off: nothing to keep safe
        }
        val store = GlucoseStore.get(app)

        var ok: Boolean
        var detail: String?
        var readingAt = 0L
        if (settings.linkUpEnabled) {
            val look = try {
                LinkUpRunner(store, settings, Net.transport).look(AppClock.now())
            } catch (e: Exception) {
                LiveLog.add(settings, AppClock.now(), "safety-sync: ${Net.words(e)}")
                null
            }
            ok = look != null && look.state != LinkUpState.NO_INTERNET && look.state != LinkUpState.SERVER_PROBLEM
            readingAt = look?.newestTime ?: 0L
            detail = if (ok) {
                if (look?.newValue == true) "new reading" else "up to date"
            } else {
                look?.state?.name ?: "no answer"
            }
        } else {
            // values come over Bluetooth: there is nothing to ask over the internet,
            // the live service does the talking; this only records that the net is watched
            ok = true
            detail = "values come over Bluetooth"
        }
        MonitoringEvents.syncDone(app, ok, detail, readingAt, FROM)
        if (!ok) {
            OnlineSync.enqueueNow(app)
            return Result.retry()
        }

        // A reading that arrived while the service was dead must still raise its alarm —
        // but only a fresh one: a backlog is recorded as read, never presented as "now".
        try {
            val now = AppClock.now()
            val current = CurrentValues.read(store, settings, now)
            val stateWords = if (settings.linkUpEnabled) {
                LinkUpWords.state(app, settings.linkUpState)
            } else {
                LiveNotifications.stateText(app, settings.liveState)
            }
            val handler = Handler(Looper.getMainLooper())
            AlertPipeline.evaluateAndAlert(
                app, settings, handler, AlarmSounder(app, handler), current, now, stateWords,
                suppressNoData = false, wake = null
            ) { text -> LiveLog.add(settings, AppClock.now(), text) }
        } catch (e: Exception) {
            LiveLog.add(settings, AppClock.now(), "safety-sync alert check failed: ${e.javaClass.simpleName}")
        }
        OnlineSync.enqueueNow(app)
        return Result.success()
    }

    companion object {
        /** One unique piece of work for the whole app; scheduling it again changes nothing. */
        const val UNIQUE = "monitoring_safety_sync"
        const val FROM = "workmanager"

        /**
         * Asks Android to keep the safety-sync alive: network-connected, every 15 minutes,
         * with exponential backoff after a failure. Safe to call any number of times.
         */
        fun schedule(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = PeriodicWorkRequestBuilder<MonitoringSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(UNIQUE)
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
            } catch (e: RuntimeException) {
                // Android refused; the live service and its minute alarm carry monitoring alone
                LiveLog.add(Settings(context), AppClock.now(), "safety-sync may not be scheduled: ${e.javaClass.simpleName}")
            }
        }
    }
}
