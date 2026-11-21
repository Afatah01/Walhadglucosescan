package com.glucosescan.app.cloud

import android.content.Context
import com.glucosescan.app.BuildConfig
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LiveLog
import com.glucosescan.app.data.ScanHandler
import com.glucosescan.app.data.Settings
import com.glucosescan.app.data.StoredValue
import com.glucosescan.app.data.ValueSource
import com.glucosescan.app.live.LiveBus
import com.glucosescan.app.net.HttpTransport
import com.glucosescan.app.net.Net
import com.glucosescan.app.ui.AppClock

/** What one piece of work on the online copy brought. */
class BackupResult(
    /** Null if it worked. */
    val failure: BackupFailure?,
    /** How many values were copied, came back, or are in the copy: what the work was about. */
    val count: Int = 0,
    /** For "create account": true if the database wants the e-mail confirmed before the first login. */
    val confirmFirst: Boolean = false
) {
    val isOk: Boolean
        get() = failure == null
}

/**
 * Keeps the online copy of the values: logs in, sends the values that are not in the copy
 * yet, and fetches them back. Everything in here waits for the internet, so it must run away
 * from the screen (see [Net.patient]).
 */
class BackupRunner(
    private val store: GlucoseStore,
    private val settings: Settings,
    private val transport: HttpTransport = Net.transport
) {
    private fun api(): SupabaseApi = SupabaseApi(transport, settings.backupUrl, settings.backupKey)

    // ------------------------------------------------------------------ accounts

    /** Makes an account in the database of the user. If the database logs in right away, the copy is switched on. */
    fun signUp(url: String, key: String, email: String, password: String, now: Long): BackupResult {
        settings.backupUrl = url
        settings.backupKey = key
        settings.backupEmail = email
        val reply = api().signUp(email, password, now)
        if (!reply.isOk) {
            return failed(reply.failure!!, now, remember = false)
        }
        val session = reply.value ?: return BackupResult(null, confirmFirst = true)
        switchOn(session, password)
        return BackupResult(null)
    }

    /** Logs in and switches the copy on. */
    fun logIn(url: String, key: String, email: String, password: String, now: Long): BackupResult {
        settings.backupUrl = url
        settings.backupKey = key
        settings.backupEmail = email
        val reply = api().logIn(email, password, now)
        val session = reply.value ?: return failed(reply.failure!!, now, remember = false)
        switchOn(session, password)
        return BackupResult(null)
    }

    private fun switchOn(session: BackupSession, password: String) {
        val other = settings.backupUserId.isNotEmpty() && settings.backupUserId != session.userId
        keep(session)
        settings.backupPassword = password
        if (other) {
            // another account: its copy has none of the values of this phone yet
            for (source in ValueSource.values()) {
                store.table(source).markAllUnsynced()
            }
            settings.backupOnlineCount = -1
        }
        settings.backupProblem = ""
        settings.backupDetail = ""
        settings.backupEnabled = true
    }

    /** Switches the copy off and forgets the login. The copy itself stays in the database. */
    fun logOut() {
        val token = settings.backupAccessToken
        if (token.isNotEmpty() && settings.backupUrl.isNotEmpty()) {
            api().logOut(token) // if this does not get through, the passes end by themselves
        }
        settings.forgetBackupLogin()
    }

    // ------------------------------------------------------------------ the copy

    /** Sends every value that is not in the copy yet. */
    fun copy(now: Long): BackupResult {
        if (!settings.backupEnabled) {
            return BackupResult(BackupFailure(BackupProblem.LOGIN, "the online copy is off"))
        }
        settings.backupLastTryTime = now
        var session = session(now) ?: return failed(lastFailure!!, now)
        var copied = 0
        var renewed = false
        for (source in ValueSource.values()) {
            val table = store.table(source)
            var rounds = 0
            while (rounds < MAX_ROUNDS) {
                rounds++
                val values = table.unsynced(SupabaseApi.PAGE)
                if (values.isEmpty()) {
                    break
                }
                var reply = api().add(session, values.map { StoredValue(source, it) })
                if (!reply.isOk && reply.failure!!.problem == BackupProblem.PASS_EXPIRED && !renewed) {
                    renewed = true
                    session = renew(now) ?: return failed(lastFailure!!, now)
                    reply = api().add(session, values.map { StoredValue(source, it) })
                }
                if (!reply.isOk) {
                    return failed(reply.failure!!, now)
                }
                table.markSynced(values.map { it.timestamp })
                copied += values.size
            }
        }
        // the monitoring audit trail rides along: alert events, sync events, this phone's
        // registration and one app-health snapshot. Best-effort: it must never break the copy
        // of the values, and rows that do not make it stay on the phone for the next round.
        try {
            copyMonitoring(session)
        } catch (e: Exception) {
            LiveLog.add(settings, now, "monitoring copy failed: ${Net.words(e)}")
        }
        // the predictions, and the open/closed monitoring session: same rules as the rest —
        // rows that do not make it stay on the phone and are tried again next time.
        try {
            pushForecasts(session)
        } catch (e: Exception) {
            LiveLog.add(settings, now, "forecast copy failed: ${Net.words(e)}")
        }
        try {
            pushSession(session)
        } catch (e: Exception) {
            LiveLog.add(settings, now, "session copy failed: ${Net.words(e)}")
        }
        val count = api().count(session)
        if (count.isOk) {
            settings.backupOnlineCount = count.value!!
        }
        settings.backupLastTime = now
        settings.backupProblem = ""
        settings.backupDetail = if (copied > 0) "copied $copied" else "nothing new"
        return BackupResult(null, copied)
    }

    /**
     * Copies the monitoring records that are not in the online copy yet: the alert events, the
     * sync events, the registration of this phone (refreshed every copy) and one app-health
     * snapshot of how the monitoring stack looks from here. If the network is gone we could not
     * get here, so the network flag says "online".
     */
    private fun copyMonitoring(session: BackupSession) {
        val alerts = store.unsyncedAlerts(100)
        if (alerts.isNotEmpty()) {
            val reply = api().addAlertEvents(session, alerts)
            if (reply.isOk) {
                store.markAlertsSynced(alerts.map { it.id })
            }
        }
        val syncs = store.unsyncedSyncs(100)
        if (syncs.isNotEmpty()) {
            val reply = api().addSyncEvents(session, syncs)
            if (reply.isOk) {
                store.markSyncsSynced(syncs.map { it.id })
            }
        }
        api().addDeviceRegistration(session, settings.deviceId, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        val foreground = settings.valuesComeByThemselves && com.glucosescan.app.live.LiveService.instance != null
        api().addAppHealth(session, foreground, settings.linkUpState.name, networkUp = true, detail = null)
    }

    /** Copies the predictions that are not in the online copy yet (or that have been scored since they were sent). */
    private fun pushForecasts(session: BackupSession) {
        val pending = com.glucosescan.app.data.ForecastLog.unsynced(store, 200)
        if (pending.isEmpty()) {
            return
        }
        val reply = api().addForecastPredictions(session, pending)
        if (reply.isOk) {
            store.writableDatabase.let { db -> com.glucosescan.app.data.ForecastLog.markSynced(db, pending) }
        }
    }

    /** Writes the open monitoring session (or its close) to the online copy. */
    private fun pushSession(session: BackupSession) {
        val clientId = settings.monitoringSessionClientId
        val startedAt = settings.monitoringSessionStartedAt
        if (clientId.isEmpty() || startedAt == 0L) {
            return
        }
        val endedAt = settings.monitoringSessionEndedAt
        if (endedAt > 0L && settings.monitoringSessionCopied) {
            return // the close is already in the copy; keep the row on screen, stop the traffic
        }
        val reply = api().addMonitoringSession(
            session, clientId, startedAt,
            if (endedAt > 0L) endedAt else null,
            settings.monitoringSessionEndReason.ifEmpty { null },
            settings.monitoringSessionSource.ifEmpty { "live" }
        )
        if (reply.isOk && endedAt > 0L) {
            // keep the finished session on screen, but stop copying it on every sync
            settings.monitoringSessionCopied = true
        }
    }

    /**
     * An authenticated round-trip to the project, timed. What the Monitoring screen shows about
     * the online copy comes from this answer — never from a flag remembered from last week.
     */
    fun ping(now: Long): BackupResult {
        if (!settings.backupEnabled) {
            return BackupResult(BackupFailure(BackupProblem.LOGIN, "the online copy is off"))
        }
        val session = session(now) ?: return pingFailed(lastFailure!!, now)
        val reply = api().ping(session)
        settings.supabasePingTime = now
        if (reply.isOk) {
            settings.supabasePingOk = true
            settings.supabasePingMillis = reply.value ?: -1L
            settings.supabasePingWords = "answered in ${reply.value} ms"
            return BackupResult(null)
        }
        return pingFailed(reply.failure!!, now)
    }

    private fun pingFailed(failure: BackupFailure, now: Long): BackupResult {
        settings.supabasePingTime = now
        settings.supabasePingOk = false
        settings.supabasePingMillis = -1L
        settings.supabasePingWords = failure.detail
        return BackupResult(failure)
    }

    /** Fetches the values of the last 90 days from the copy. Values the phone has already stay as they are. */
    fun restore(now: Long): BackupResult {
        if (!settings.backupEnabled) {
            return BackupResult(BackupFailure(BackupProblem.LOGIN, "the online copy is off"))
        }
        val session = session(now) ?: return failed(lastFailure!!, now)
        val from = now - ScanHandler.KEEP_MILLIS
        var offset = 0
        var added = 0
        var pages = 0
        while (pages < MAX_ROUNDS) {
            pages++
            val reply = api().page(session, from, offset)
            val values = reply.value ?: return failed(reply.failure!!, now)
            if (values.isEmpty()) {
                break
            }
            for (source in ValueSource.values()) {
                // they are in the copy: no need to send them there again
                added += store.table(source).insert(values.filter { it.source == source && it.glucose.timestamp <= now + ScanHandler.FUTURE_TOLERANCE_MILLIS }.map { it.glucose }, synced = true)
            }
            offset += values.size
            if (values.size < SupabaseApi.PAGE) {
                break
            }
        }
        settings.backupProblem = ""
        settings.backupDetail = "restored $added"
        return BackupResult(null, added)
    }

    /** Empties the copy. The values on the phone stay; they count as "not copied" again. */
    fun removeOnline(now: Long): BackupResult {
        if (!settings.backupEnabled) {
            return BackupResult(BackupFailure(BackupProblem.LOGIN, "the online copy is off"))
        }
        val session = session(now) ?: return failed(lastFailure!!, now)
        val reply = api().removeAll(session)
        if (!reply.isOk) {
            return failed(reply.failure!!, now)
        }
        for (source in ValueSource.values()) {
            store.table(source).markAllUnsynced()
        }
        settings.backupOnlineCount = 0
        settings.backupLastTime = 0L
        settings.backupDetail = "the copy was emptied"
        return BackupResult(null)
    }

    /** How many values of both sets are not in the copy yet. */
    fun waiting(): Int = ValueSource.values().sumOf { store.table(it).unsyncedCount() }

    // ------------------------------------------------------------------ the login

    private var lastFailure: BackupFailure? = null

    /** A login that is good for the next minutes, or null; [lastFailure] then says why. */
    private fun session(now: Long): BackupSession? {
        val access = settings.backupAccessToken
        val refresh = settings.backupRefreshToken
        val userId = settings.backupUserId
        if (access.isNotEmpty() && userId.isNotEmpty() && now < settings.backupTokenExpires - EXPIRY_MARGIN_MILLIS) {
            return BackupSession(access, refresh, settings.backupTokenExpires, userId)
        }
        return renew(now)
    }

    /** Gets a new pass: with the pass that is kept for this, or else with the password. */
    private fun renew(now: Long): BackupSession? {
        val refresh = settings.backupRefreshToken
        if (refresh.isNotEmpty()) {
            val reply = api().refresh(refresh, now)
            val session = reply.value
            if (session != null) {
                keep(session)
                return session
            }
            val problem = reply.failure!!.problem
            if (problem != BackupProblem.PASS_EXPIRED && problem != BackupProblem.LOGIN) {
                // no internet, or the database is in trouble: the passes may still be good later
                lastFailure = reply.failure
                return null
            }
            // the passes are dead: forget them, so that they are not sent again and again
            settings.backupAccessToken = ""
            settings.backupRefreshToken = ""
            settings.backupTokenExpires = 0L
        }
        val password = settings.backupPassword
        if (password.isEmpty() || settings.backupEmail.isEmpty()) {
            lastFailure = BackupFailure(BackupProblem.LOGIN, "no password is saved")
            return null
        }
        val reply = api().logIn(settings.backupEmail, password, now)
        val session = reply.value
        if (session == null) {
            lastFailure = reply.failure
            if (reply.failure!!.problem == BackupProblem.LOGIN) {
                settings.backupPassword = "" // a wrong password is not tried again and again
            }
            return null
        }
        keep(session)
        return session
    }

    private fun keep(session: BackupSession) {
        settings.backupAccessToken = session.accessToken
        settings.backupRefreshToken = session.refreshToken
        settings.backupTokenExpires = session.expires
        settings.backupUserId = session.userId
    }

    private fun failed(failure: BackupFailure, now: Long, remember: Boolean = true): BackupResult {
        if (remember) {
            settings.backupProblem = failure.problem.name
            settings.backupDetail = failure.detail
            settings.backupLastTryTime = now
        }
        return BackupResult(failure)
    }

    companion object {
        private const val EXPIRY_MARGIN_MILLIS = 60_000L

        /** A stop for loops that should end by themselves: 400 x 500 values is far more than 90 days hold. */
        private const val MAX_ROUNDS = 400
    }
}

/** Starts the copying when there is something to copy and it is time for it. */
object Backup {
    /** Without a reason to hurry, the app copies at most this often. */
    const val EVERY_MILLIS = 15 * 60_000L

    @Volatile
    private var running = false

    /**
     * Copies the new values in the background, if the online copy is on.
     * @param soon true: right now (after a scan); false: only if the last try is some minutes ago
     */
    fun request(context: Context, soon: Boolean = false) {
        val app = context.applicationContext
        val settings = Settings(app)
        if (!settings.backupEnabled || running) {
            return
        }
        val now = AppClock.now()
        val last = settings.backupLastTryTime
        if (!soon && now >= last && now - last < EVERY_MILLIS) {
            return
        }
        running = true
        settings.backupLastTryTime = now
        val transport = Net.transport
        Net.patient.execute {
            try {
                BackupRunner(GlucoseStore.get(app), settings, transport).copy(now)
            } catch (e: Exception) {
                settings.backupProblem = BackupProblem.SERVER.name
                settings.backupDetail = Net.words(e)
            } finally {
                running = false
                LiveBus.changed()
            }
        }
    }
}
