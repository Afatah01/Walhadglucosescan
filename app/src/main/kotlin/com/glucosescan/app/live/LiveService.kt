package com.glucosescan.app.live

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.glucosescan.app.ble.AndroidBleLink
import com.glucosescan.app.cloud.Backup
import com.glucosescan.app.ble.BleEvents
import com.glucosescan.app.ble.BleLink
import com.glucosescan.app.ble.LivePermissions
import com.glucosescan.app.ble.LiveSession
import com.glucosescan.app.ble.SessionHost
import com.glucosescan.app.ble.SessionTimer
import com.glucosescan.app.core.AlarmKind
import com.glucosescan.app.core.AlarmRules
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.LiveStatus
import com.glucosescan.app.core.SpeechText
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.monitoring.AlertPipeline
import com.glucosescan.app.monitoring.MonitoringEvents
import com.glucosescan.app.data.LiveHandler
import com.glucosescan.app.data.LiveLog
import com.glucosescan.app.data.LiveSensorRecord
import com.glucosescan.app.data.LinkUpState
import com.glucosescan.app.data.LiveState
import com.glucosescan.app.data.Settings
import com.glucosescan.app.linkup.LinkUpRunner
import com.glucosescan.app.net.Net
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.CurrentValue
import com.glucosescan.app.ui.CurrentValues
import com.glucosescan.app.ui.Speaker

/**
 * Gets a value each minute while the app is not on the screen, saves it, shows it in a
 * notification, says it aloud if the user wants that, and sounds the alarms. Android lets it
 * run because it shows that notification the whole time.
 *
 * The values come one of two ways, never both at once:
 * - over Bluetooth from the sensor itself ("live values"), or
 * - over the internet from LibreLinkUp, while the sensor keeps its Bluetooth connection to
 *   FreeStyle LibreLink.
 */
class LiveService : Service(), SessionHost {
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private lateinit var link: BleLink
    private lateinit var session: LiveSession
    private lateinit var sounder: AlarmSounder
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false
    private var loopRuns = false
    private var alarmShown: AlarmKind? = null

    /** True while a question to LibreLinkUp is on its way. */
    private var looking = false

    /** When LibreLinkUp is asked next, on the clock that also runs while the phone sleeps. 0 = at once. */
    private var nextLookAt = 0L
    private val lookRun = Runnable { lookIfDue() }

    /** When the timer of the session runs out, on the clock that also runs while the phone sleeps. 0 = no timer. */
    private var deadline = 0L
    private val timerRun = Runnable { fireTimer() }

    private val timer = object : SessionTimer {
        override fun set(delayMillis: Long) {
            deadline = SystemClock.elapsedRealtime() + delayMillis
            handler.removeCallbacks(timerRun)
            handler.postDelayed(timerRun, delayMillis)
        }

        override fun cancel() {
            deadline = 0L
            handler.removeCallbacks(timerRun)
        }
    }

    /** Looks at the value once a minute, so that the notification and the alarms stay right. */
    private val everyMinute = object : Runnable {
        override fun run() {
            check(newValue = false)
            lookIfDue()
            handler.postDelayed(this, MINUTE_MILLIS)
        }
    }

    /** LibreLinkUp needs the network: watch it while live values are on, and recover by itself. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (instance !== this@LiveService) {
                    return@post
                }
                // the connection is back: ask at once, and tell the user if we had said it was gone
                if (settings.linkUpEnabled) {
                    notifications()?.cancel(LiveNotifications.ID_NETWORK)
                    nextLookAt = 0L
                    lookIfDue()
                }
            }
        }

        override fun onLost(network: Network) {
            handler.post {
                if (instance !== this@LiveService) {
                    return@post
                }
                log("network lost")
                if (settings.linkUpEnabled) {
                    LiveNotifications.showNetworkLost(this@LiveService)
                }
            }
        }
    }
    private var networkCallbackRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) {
                return
            }
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF) {
                log(if (state == BluetoothAdapter.STATE_ON) "Bluetooth was switched on" else "Bluetooth was switched off")
                session.start()
                check(newValue = false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        store = GlucoseStore.get(this)
        sounder = AlarmSounder(this, handler)
        link = linkFactory?.invoke(this, handler) { session } ?: AndroidBleLink(this, handler) { session }
        session = LiveSession(link, timer, this)
        LiveNotifications.createChannels(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        // Android wants the notification right away, whatever else happens.
        if (!enterForeground()) {
            settings.liveState = LiveState.NO_PERMISSION
            LiveBus.changed()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!settings.valuesComeByThemselves || action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val linkUp = settings.linkUpEnabled

        notifications()?.cancel(LiveNotifications.ID_STOPPED)
        if (settings.liveStoppedShownTime != 0L) {
            settings.liveStoppedShownTime = 0L // it runs again: the next stop is told at once
            LiveNotifications.showMonitoringRestored(this) // monitoring is back; tell the user
        }
        if (linkUp && !networkCallbackRegistered) {
            networkCallbackRegistered = registerNetworkCallback()
        } else if (!linkUp && networkCallbackRegistered) {
            unregisterNetworkCallback()
        }
        if (!linkUp && !receiverRegistered) {
            // Only Android itself can send this broadcast, so the receiver can be open to it.
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(bluetoothReceiver, filter)
            }
            receiverRegistered = true
        }
        // a monitoring session is a session: opened when monitoring starts, closed when the service ends
        OnlineSync.openSession(this, if (linkUp) "linkup" else "ble")
        if (!loopRuns) {
            loopRuns = true
            handler.removeCallbacks(everyMinute)
            handler.postDelayed(everyMinute, MINUTE_MILLIS)
            if (settings.liveWaitingSince == 0L) {
                settings.liveWaitingSince = AppClock.now()
            }
        }

        if (action == ACTION_SILENCE) {
            val kind = AlarmKind.values().firstOrNull { it.name == intent?.getStringExtra(EXTRA_KIND) }
            if (kind != null) {
                silence(kind)
            }
        }

        if (linkUp) {
            // the values come over the internet: the sensor keeps its Bluetooth connection to LibreLink
            if (session.state != LiveState.OFF) {
                session.stop()
            }
            if (action == ACTION_LOOK) {
                nextLookAt = 0L
            }
            lookIfDue()
        } else {
            ensureSession()
        }
        check(newValue = false)
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        OnlineSync.closeSession(this, "service-ended")
        handler.removeCallbacksAndMessages(null)
        deadline = 0L
        session.stop()
        sounder.stop()
        if (receiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver)
            } catch (e: IllegalArgumentException) {
                // was not registered
            }
            receiverRegistered = false
        }
        unregisterNetworkCallback()
        notifications()?.cancel(LiveNotifications.ID_ALARM)
        releaseWakeLock()
        if (settings.valuesComeByThemselves) {
            // Not the user but Android has ended the service. The wake-up stays, so that
            // TickReceiver starts the service again in a few minutes.
            scheduleTick(this)
        } else {
            cancelTick(this)
        }
        if (!settings.liveEnabled) {
            settings.liveState = LiveState.OFF
        }
        LiveBus.changed()
        super.onDestroy()
    }

    /** Shows the notification that lets the service run. False if Android does not allow it. */
    private fun enterForeground(): Boolean {
        val now = AppClock.now()
        val linkUp = settings.linkUpEnabled
        val current = CurrentValues.read(store, settings, now)
        val notification = if (linkUp) {
            LiveNotifications.live(this, current, LinkUpWords.state(this, settings.linkUpState), settings.linkUpState == LinkUpState.RECEIVING, unit())
        } else {
            LiveNotifications.live(this, current, settings.liveState, unit())
        }
        return try {
            if (linkUp) {
                // No Bluetooth here. Android 14 and newer want to hear what the service is for:
                // "special use", explained in the manifest.
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(LiveNotifications.ID_LIVE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(LiveNotifications.ID_LIVE, notification)
                }
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(LiveNotifications.ID_LIVE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(LiveNotifications.ID_LIVE, notification)
            }
            true
        } catch (e: RuntimeException) {
            log("the service may not run: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Starts the session, or starts it again if the sensor was set up anew or something was missing. */
    private fun ensureSession() {
        val pairedTime = settings.liveSensor?.pairedTime ?: 0L
        val state = session.state
        val restart = when {
            pairedTime != session.sensorPairedTime -> true // a scan has set up a sensor (again)
            state == LiveState.OFF || state == LiveState.NEEDS_SCAN -> true
            state == LiveState.NO_PERMISSION || state == LiveState.BLUETOOTH_OFF -> true // look again: maybe it is there now
            else -> false
        }
        if (restart) {
            session.start()
        }
    }

    // ------------------------------------------------------------------ what the session needs

    override fun now(): Long = AppClock.now()

    override fun sensor(): LiveSensorRecord? = settings.liveSensor

    override fun savedAddress(): String? = settings.liveAddress

    override fun saveAddress(address: String) {
        settings.liveAddress = address
    }

    override fun nextUnlockCount(): Int {
        val next = settings.liveUnlockCount + 1
        settings.liveUnlockCount = next
        return next
    }

    override fun onMessage(message: ByteArray): LiveStatus {
        holdWakeLock(10_000L)
        val outcome = LiveHandler(store, settings).handle(message, AppClock.now())
        if (outcome.status != LiveStatus.OK || outcome.tooNoisy) {
            log("message: ${outcome.status.name}, ${outcome.detail}")
        }
        val newValue = outcome.status == LiveStatus.OK && outcome.saved > 0
        handler.post { check(newValue) }
        return outcome.status
    }

    override fun onState(state: LiveState) {
        settings.liveState = state
        log("state: ${state.name}")
        // While the phone looks for the sensor and sets the connection up, it must not fall
        // asleep in the middle. Once values come in, each message wakes the phone by itself.
        if (state == LiveState.SEARCHING || state == LiveState.CONNECTING) {
            holdWakeLock(90_000L)
        }
        if (state == LiveState.WARMING_UP || state == LiveState.SENSOR_ENDED) {
            settings.liveWaitingSince = AppClock.now()
        }
        handler.post { check(newValue = false) }
    }

    override fun onRejected() {
        settings.livePairWanted = true
    }

    override fun log(text: String) {
        LiveLog.add(settings, AppClock.now(), text)
    }

    // ------------------------------------------------------------------ once a minute

    /** Called by [TickReceiver] when the phone was woken to look after the connection. */
    private fun requestOnlineSyncIfWaiting() {
        try {
            val store = GlucoseStore.get(this)
            val waiting = com.glucosescan.app.data.ValueSource.values().sumOf { store.table(it).unsyncedCount() } +
                store.unsyncedAlerts(1).size + store.unsyncedSyncs(1).size +
                com.glucosescan.app.data.ForecastLog.pendingSyncCount(store)
            if (waiting > 0) {
                OnlineSync.enqueueNow(this)
            }
        } catch (e: Exception) {
            // the copy must never break the monitoring loop
        }
    }

    fun onTick() {
        check(newValue = false)
        lookIfDue()
        requestOnlineSyncIfWaiting()
    }

    // ------------------------------------------------------------------ LibreLinkUp

    /** Asks LibreLinkUp for the newest value, if it is time for that and no question is on its way. */
    private fun lookIfDue() {
        if (instance !== this || !settings.linkUpEnabled || looking) {
            return
        }
        val elapsed = SystemClock.elapsedRealtime()
        if (nextLookAt != 0L && elapsed < nextLookAt - LOOK_EARLY_MILLIS) {
            return
        }
        looking = true
        nextLookAt = elapsed + LOOK_EVERY_MILLIS
        holdWakeLock(LOOK_WAKE_MILLIS)
        val runner = LinkUpRunner(store, settings, Net.transport)
        Net.background.execute {
            val look = try {
                runner.look(AppClock.now())
            } catch (e: Exception) {
                log("LibreLinkUp: ${Net.words(e)}")
                null
            }
            handler.post {
                looking = false
                if (instance === this) {
                    // the server is "unreachable" for the app when the question errored out,
                    // or the answer says there is no internet or the server is broken
                    val unreachable = look == null ||
                        look.state == LinkUpState.NO_INTERNET ||
                        look.state == LinkUpState.SERVER_PROBLEM
                    if (unreachable) {
                        val fails = settings.linkUpFailCount + 1
                        settings.linkUpFailCount = fails
                        if (fails >= SYNC_FAIL_LIMIT && !settings.syncFailNotified) {
                            settings.syncFailNotified = true
                            LiveNotifications.showSyncFailure(this)
                        }
                    } else {
                        if (settings.linkUpFailCount != 0) {
                            settings.linkUpFailCount = 0
                        }
                        if (settings.syncFailNotified) {
                            settings.syncFailNotified = false
                            notifications()?.cancel(LiveNotifications.ID_SYNC_FAIL)
                            LiveNotifications.showMonitoringRestored(this)
                        }
                    }
                    // record the sync: markers always, a persistent event on a state change
                    MonitoringEvents.syncDone(
                        this,
                        ok = !unreachable,
                        detail = if (unreachable) (look?.state?.name ?: "no answer") else if (look?.newValue == true) "new reading" else "up to date",
                        readingAt = if (unreachable) 0L else (look?.newestTime ?: 0L),
                        from = "sync"
                    )
                    // LibreLink sends a value each minute. Ask again a few seconds after the next one is due.
                    val now = AppClock.now()
                    val newest = look?.newestTime ?: 0L
                    val wait = if (newest != 0L) newest + LOOK_EVERY_MILLIS + LOOK_AFTER_VALUE_MILLIS - now else LOOK_EVERY_MILLIS
                    val delay = if (wait in LOOK_MIN_MILLIS..LOOK_MAX_MILLIS) wait else LOOK_EVERY_MILLIS
                    nextLookAt = SystemClock.elapsedRealtime() + delay
                    handler.removeCallbacks(lookRun)
                    handler.postDelayed(lookRun, delay)
                    check(look?.newValue == true)
                }
            }
        }
    }

    /** The words for what the service is doing, for the notification. */
    private fun stateWords(): String {
        return if (settings.linkUpEnabled) LinkUpWords.state(this, settings.linkUpState) else LiveNotifications.stateText(this, session.state)
    }

    private fun check(newValue: Boolean) {
        if (instance !== this) {
            return // the service has ended
        }
        if (deadline != 0L && SystemClock.elapsedRealtime() >= deadline) {
            fireTimer() // the phone slept over the timer
        }
        val now = AppClock.now()
        val current = CurrentValues.read(store, settings, now)
        val receiving = if (settings.linkUpEnabled) settings.linkUpState == LinkUpState.RECEIVING else session.state == LiveState.RECEIVING
        notifications()?.notify(LiveNotifications.ID_LIVE, LiveNotifications.live(this, current, stateWords(), receiving, unit()))
        alarms(current, now)
        if (newValue) {
            settings.monitoringLastReadingAt = current.shown?.timestamp ?: now
            sayIfDue(current, now)
            if (settings.notifyValueOn) {
                LiveNotifications.showNewReading(this, current, unit())
            }
            Backup.request(this) // the online copy, if the user has one; at most every 15 minutes
        }
        scheduleTick(this)
        LiveBus.changed()
    }

    private fun fireTimer() {
        if (deadline == 0L) {
            return
        }
        deadline = 0L
        handler.removeCallbacks(timerRun)
        session.onTimer()
    }

    private fun alarms(current: CurrentValue, now: Long) {
        val suppressNoData = !settings.linkUpEnabled && session.state == LiveState.SENSOR_ENDED
        val result = AlertPipeline.evaluateAndAlert(
            this, settings, handler, sounder, current, now, stateWords(), suppressNoData,
            { millis -> holdWakeLock(millis) }, { text -> log(text) }
        )
        if (result.sounded != null) {
            alarmShown = result.sounded
        } else if (result.reason == null && alarmShown != null) {
            // the reason is gone: take the alarm away
            alarmShown = null
            sounder.stop()
            notifications()?.cancel(LiveNotifications.ID_ALARM)
        }
    }

    private fun sayIfDue(current: CurrentValue, now: Long) {
        val minutes = settings.speakLiveMinutes
        if (minutes <= 0 || !current.isCurrent || sounder.isPlaying) {
            return
        }
        val last = settings.lastSpokenTime
        if (now >= last && now - last < minutes * MINUTE_MILLIS - 30_000L) {
            return
        }
        val shown = current.shown ?: return
        val sentence = current.speech(unit(), settings.speakTrend, if (settings.speakLowSoon) now else null) ?: return
        settings.lastSpokenTime = now
        Speaker.get(this).say(sentence, SpeechText.number(shown, unit()))
    }

    private fun silence(kind: AlarmKind) {
        settings.alarmMemory = AlarmRules.silence(settings.alarmMemory, kind, AppClock.now())
        log("alarm silenced: ${kind.name}")
        stopAlarmSound()
        alarmShown = null
        notifications()?.cancel(LiveNotifications.ID_ALARM)
    }

    /** Stops the sound of an alarm that is ringing. The alarm stays in its notification. */
    fun stopAlarmSound() {
        sounder.stop()
        Speaker.get(this).stop()
    }

    // ------------------------------------------------------------------ helpers

    private fun unit(): GlucoseUnit = settings.unit ?: GlucoseUnit.MGDL

    private fun notifications(): NotificationManager? = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager

    /** Register the network callback. False if the phone does not allow it. */
    private fun registerNetworkCallback(): Boolean {
        return try {
            val connectivity = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.registerNetworkCallback(request, networkCallback)
            true
        } catch (e: RuntimeException) {
            log("network callback may not be registered: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) {
            return
        }
        networkCallbackRegistered = false
        try {
            (getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(networkCallback)
        } catch (e: RuntimeException) {
            // was not registered or the phone has no connectivity service
        }
    }

    private fun holdWakeLock(millis: Long) {
        try {
            val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walhad:live")
                .also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
            lock.acquire(millis)
        } catch (e: RuntimeException) {
            // works without, only less surely while the phone sleeps
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: RuntimeException) {
            // nothing to do
        }
    }

    companion object {
        const val ACTION_START = "com.glucosescan.app.action.LIVE_START"
        const val ACTION_STOP = "com.glucosescan.app.action.LIVE_STOP"
        const val ACTION_SILENCE = "com.glucosescan.app.action.LIVE_SILENCE"

        /** Asks LibreLinkUp at once, for example after the user has logged in. */
        const val ACTION_LOOK = "com.glucosescan.app.action.LIVE_LOOK"
        const val EXTRA_KIND = "kind"

        private const val MINUTE_MILLIS = 60_000L

        /** How long the phone may sleep before it is woken to look after the Bluetooth connection. */
        const val TICK_MILLIS = 5 * 60_000L

        /** How many failed questions to LibreLinkUp in a row before the user is told. */
        private const val SYNC_FAIL_LIMIT = 3

        /** LibreLinkUp is asked this often. LibreLink sends a new value each minute. */
        const val LOOK_EVERY_MILLIS = 60_000L

        /** A wake-up that comes this much too early still counts. */
        private const val LOOK_EARLY_MILLIS = 3_000L

        /** The next question goes out this long after the next value is due. */
        const val LOOK_AFTER_VALUE_MILLIS = 12_000L
        private const val LOOK_MIN_MILLIS = 15_000L
        private const val LOOK_MAX_MILLIS = 75_000L

        /** The phone is kept awake this long at most for one question to LibreLinkUp. */
        private const val LOOK_WAKE_MILLIS = 45_000L

        /** The service that runs, or null. */
        @Volatile
        var instance: LiveService? = null
            private set

        /** Tests put a fake Bluetooth here. */
        @Volatile
        internal var linkFactory: ((Context, Handler, () -> BleEvents?) -> BleLink)? = null

        /**
         * Starts the service if live values are switched on and the app may use Bluetooth.
         * If it runs already, it looks whether a scan has set up a sensor in the meantime.
         * @return false if it could not be started
         */
        fun start(context: Context, action: String = ACTION_START): Boolean {
            val settings = Settings(context)
            if (!settings.valuesComeByThemselves) {
                return false
            }
            if (!settings.linkUpEnabled && !LivePermissions.hasBluetooth(context)) {
                settings.liveState = LiveState.NO_PERMISSION
                LiveBus.changed()
                return false
            }
            val intent = Intent(context, LiveService::class.java).setAction(action)
            return try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: RuntimeException) {
                // Android did not let the service start from the background
                LiveLog.add(settings, AppClock.now(), "the service could not start: ${e.javaClass.simpleName}")
                false
            }
        }

        /**
         * For the receivers, which run while the app is not on the screen: starts the service,
         * and if Android does not allow that now, tells the user and tries again later.
         */
        fun startFromBackground(context: Context) {
            val settings = Settings(context)
            if (!settings.valuesComeByThemselves) {
                return
            }
            if (!start(context)) {
                LiveNotifications.showStopped(context)
                scheduleTick(context)
            }
        }

        private fun tickIntent(context: Context): PendingIntent {
            val intent = Intent(context, TickReceiver::class.java)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Asks Android to wake the phone in a while, also when it sleeps deeply.
         *
         * Over Bluetooth each value wakes the phone by itself; the wake-up every few minutes is
         * only there so that somebody notices during the night that the sensor has stopped
         * sending. With LibreLinkUp nothing wakes the phone: the app has to ask, so the wake-up
         * comes when the next question is due, and it is an exact one where Android allows that.
         */
        fun scheduleTick(context: Context) {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val settings = Settings(context)
            // (while only the user can make LibreLinkUp work again, nothing is asked: the slow wake-up is enough)
            val linkUp = settings.linkUpEnabled && !settings.linkUpState.stopsAsking
            try {
                if (linkUp) {
                    val service = instance
                    val due = if (service != null && service.nextLookAt != 0L) service.nextLookAt else SystemClock.elapsedRealtime() + LOOK_EVERY_MILLIS
                    val at = Math.max(due, SystemClock.elapsedRealtime() + 5_000L)
                    if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tickIntent(context))
                    } else {
                        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tickIntent(context))
                    }
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + TICK_MILLIS, tickIntent(context))
                }
            } catch (e: RuntimeException) {
                // the minute timer still works while the phone is awake
            }
        }

        fun cancelTick(context: Context) {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            try {
                alarms.cancel(tickIntent(context))
            } catch (e: RuntimeException) {
                // nothing to do
            }
        }

        /** Ends the service and the Bluetooth connection. */
        fun stop(context: Context) {
            context.stopService(Intent(context, LiveService::class.java))
            cancelTick(context)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(LiveNotifications.ID_STOPPED)
            val settings = Settings(context)
            settings.liveStoppedShownTime = 0L
            if (!settings.liveEnabled) {
                settings.liveState = LiveState.OFF
            }
            LiveBus.changed()
        }
    }
}

/** Tells the screens that are open that something about live values has changed. */
object LiveBus {
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    fun add(listener: Runnable) {
        listeners.add(listener)
    }

    fun remove(listener: Runnable) {
        listeners.remove(listener)
    }

    /** Calls every listener on the main thread. */
    fun changed() {
        if (listeners.isEmpty()) {
            return
        }
        handler.post {
            for (listener in listeners) {
                listener.run()
            }
        }
    }
}
