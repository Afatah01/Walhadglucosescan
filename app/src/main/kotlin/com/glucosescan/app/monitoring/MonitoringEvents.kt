package com.glucosescan.app.monitoring

import android.app.NotificationManager
import android.content.Context
import com.glucosescan.app.BuildConfig
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LiveLog
import com.glucosescan.app.data.Settings
import com.glucosescan.app.live.LiveNotifications
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.CurrentValues
import java.util.UUID

/**
 * One door for everything the monitoring stack needs to write down: sync outcomes and the
 * user-triggered test alert.
 *
 * The alert events themselves are recorded by [AlertPipeline] at the moment the alarm decision
 * is made, so that every alarm — whoever raised it — leaves the same record. Copying to the
 * online copy (if the user switched it on) happens in the backup cycle, never on the alert path.
 */
object MonitoringEvents {

    /**
     * A sync has finished. Writes the rolling markers always, and a persistent sync event when
     * something changed (never more than one row per state change) or when asked to record one
     * always (the background safety-sync).
     *
     * @param from "sync" (the live loop) or "workmanager" (the background safety-sync)
     */
    fun syncDone(context: Context, ok: Boolean, detail: String?, readingAt: Long, from: String) {
        val app = context.applicationContext
        val settings = Settings(app)
        val store = GlucoseStore.get(app)
        val now = AppClock.now()
        val first = settings.monitoringLastSyncTime == 0L
        val wasOk = settings.monitoringLastSyncOk
        settings.monitoringLastSyncTime = now
        settings.monitoringLastSyncOk = ok
        settings.monitoringLastError = if (ok) "" else (detail ?: "unknown")
        if (readingAt > 0L) {
            settings.monitoringLastReadingAt = readingAt
        }
        val changed = first || wasOk != ok
        if (changed || from == "workmanager") {
            store.recordSync(UUID.randomUUID().toString(), from, ok, detail, now)
        }
        LiveLog.add(settings, now, "sync ($from): " + (if (ok) "ok" else "problem") + (detail?.let { " — $it" } ?: ""))
    }

    /**
     * Posts a simulated alert and records it as TEST, so a person (or the test protocol) can
     * check end to end that the notification path works — high-importance channel, sound or
     * vibration, the works — without any reading being behind it. The alert is clearly labelled
     * simulated; it is never presented as a measurement.
     */
    fun sendTestAlert(context: Context): Boolean {
        val app = context.applicationContext
        val settings = Settings(app)
        val store = GlucoseStore.get(app)
        val now = AppClock.now()
        val current = CurrentValues.read(store, settings, now)
        val unit = settings.unit ?: GlucoseUnit.MGDL
        val posted = try {
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(LiveNotifications.ID_TEST, LiveNotifications.testAlert(app, current, unit))
            manager != null
        } catch (e: RuntimeException) {
            false
        }
        val shown = current.shown
        store.recordAlertIfNew(
            "TEST:" + now,
            "TEST",
            shown?.value,
            shown?.timestamp ?: now,
            now,
            if (posted) "simulated" else "failed",
            if (posted) "simulated test alert triggered by the user; not a medical reading" else "simulated test alert; the notification could not be posted"
        )
        LiveLog.add(settings, now, "test alert " + (if (posted) "posted" else "NOT posted"))
        return posted
    }
}
