package com.glucosescan.app.live

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.glucosescan.app.cloud.BackupProblem
import com.glucosescan.app.cloud.BackupRunner
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LiveLog
import com.glucosescan.app.data.Settings
import com.glucosescan.app.net.Net
import com.glucosescan.app.ui.AppClock
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The online copy, made automatic.
 *
 * What is on the phone is the truth; the online copy is its replica. This object is the only
 * place that decides when the replica is refreshed:
 *
 *  - every 30 minutes while the phone is online (WorkManager; survives reboots, retried with
 *    exponential backoff when the network or the database is down),
 *  - right after the phone got something new to copy (the live loop asks for a one-time run),
 *  - on demand from the Monitoring screen ("copy now & check").
 *
 * Every table is written with an upsert on a stable identity, so sending the same row twice —
 * after a crash, a reboot, a lost response — never creates a duplicate. Nothing is deleted from
 * the phone before the database has it: the rows simply stop being marked as waiting.
 */
object OnlineSync {
    /** One periodic piece of work for the whole app; asking again is harmless. */
    const val UNIQUE_PERIODIC = "online_copy_sync"
    const val UNIQUE_NOW = "online_copy_sync_now"
    const val FROM = "online_copy"

    /** Signed in means: the phone holds a way back into the account — a password or a refresh pass. */
    fun isSignedIn(settings: Settings): Boolean =
        settings.backupPassword.isNotEmpty() || settings.backupRefreshToken.isNotEmpty()

    /** True when there is something to copy to and a reason to. */
    fun shouldSync(settings: Settings): Boolean = isSignedIn(settings) && settings.backupEnabled

    /**
     * Asks Android to keep the copy fresh: with network, every 30 minutes, exponential backoff.
     * Safe to call any number of times (app start, boot, package update).
     */
    fun schedule(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<OnlineSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .addTag(UNIQUE_PERIODIC)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (e: RuntimeException) {
            LiveLog.add(Settings(context), AppClock.now(), "online copy sync may not be scheduled: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Asks Android to copy as soon as the network allows. Does nothing when the person is not
     * signed in or switched the copy off. Coalesced: a hundred calls while one is pending
     * produce a single run.
     */
    fun enqueueNow(context: Context) {
        val settings = Settings(context)
        if (!shouldSync(settings)) {
            return
        }
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<OnlineSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .addTag(UNIQUE_NOW)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
        } catch (e: RuntimeException) {
            LiveLog.add(settings, AppClock.now(), "online copy sync now may not be scheduled: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Opens a monitoring session row for this phone. A session left open by a crashed or killed
     * process is closed first, with the reason "process-restarted" — the truth about when the
     * monitoring actually ran is kept.
     */
    fun openSession(context: Context, source: String) {
        val settings = Settings(context)
        if (settings.monitoringSessionClientId.isNotEmpty() && settings.monitoringSessionEndedAt == 0L) {
            return // a session is already open
        }
        if (settings.monitoringSessionClientId.isNotEmpty()) {
            // the last session never got closed (the process died): close it now, honestly
            settings.monitoringSessionEndedAt = AppClock.now()
            settings.monitoringSessionEndReason = "process-restarted"
        }
        settings.monitoringSessionClientId = UUID.randomUUID().toString()
        settings.monitoringSessionStartedAt = AppClock.now()
        settings.monitoringSessionEndedAt = 0L
        settings.monitoringSessionEndReason = ""
        settings.monitoringSessionSource = source
        settings.monitoringSessionCopied = false
        enqueueNow(context)
    }

    /** Closes the open session, if any. The row is pushed by the next sync. */
    fun closeSession(context: Context, reason: String) {
        val settings = Settings(context)
        if (settings.monitoringSessionClientId.isEmpty() || settings.monitoringSessionEndedAt != 0L) {
            return // nothing open
        }
        settings.monitoringSessionEndedAt = AppClock.now()
        settings.monitoringSessionEndReason = reason
        settings.monitoringSessionCopied = false
        enqueueNow(context)
    }
}

/**
 * The worker that refreshes the online copy. Runs the same code the "copy now" button runs,
 * so there is one path, and it is the tested one. Failures are retried by Android with
 * exponential backoff; a failure to reach the internet also marks the safety-net records.
 */
class OnlineSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val app = applicationContext
        val settings = Settings(app)
        val now = AppClock.now()
        if (!OnlineSync.shouldSync(settings)) {
            return Result.success() // nobody signed in, or the person switched the copy off: nothing to do
        }
        val store = GlucoseStore.get(app)
        val runner = BackupRunner(store, settings, Net.transport)
        val result = try {
            runner.copy(now)
        } catch (e: Exception) {
            LiveLog.add(settings, now, "online copy sync crashed: ${e.javaClass.simpleName}")
            return Result.retry()
        }
        // an authenticated round-trip: what the Monitoring screen shows is measured, not remembered
        runner.ping(now)
        if (result.isOk) {
            // record the run itself, so the online copy shows the phone woke up to copy
            store.recordSync(UUID.randomUUID().toString(), OnlineSync.FROM, true, result.count.toString() + " values copied", now)
            return Result.success()
        }
        val problem = result.failure!!.problem
        val detail = result.failure!!.detail
        store.recordSync(UUID.randomUUID().toString(), OnlineSync.FROM, false, detail, now)
        return if (problem == BackupProblem.NO_INTERNET || problem == BackupProblem.SERVER) {
            Result.retry()
        } else {
            // login/key problems do not fix themselves by retrying in a minute
            Result.success()
        }
    }
}
