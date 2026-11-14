package com.glucosescan.app.live

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LinkUpState
import com.glucosescan.app.data.LiveState
import com.glucosescan.app.data.Settings
import com.glucosescan.app.data.ValueSource
import com.glucosescan.app.linkup.LinkUpRunner
import com.glucosescan.app.testing.FakeBleLink
import com.glucosescan.app.testing.FakeLinkUp
import com.glucosescan.app.testing.FakeServer
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.Speaker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.TimeZone

/** The service while the values come from LibreLinkUp: no Bluetooth, a question each minute. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class LinkUpServiceTest {
    private val minute = 60_000L

    /** Tuesday 10 November 2026, 14:32:20 local time (UTC+3) */
    private var now = 1_794_310_340_000L
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private lateinit var server: FakeLinkUp
    private var link: FakeBleLink? = null
    private var controller: ServiceController<LiveService>? = null

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Mogadishu"))
        GlucoseStore.closeShared()
        AppClock.source = { now }
        Speaker.reset()
        AlarmSounder.plays = 0
        app = RuntimeEnvironment.getApplication()
        settings = Settings(app)
        store = GlucoseStore.get(app)
        settings.noticeAccepted = true
        settings.unit = GlucoseUnit.MGDL
        server = FakeLinkUp { now }
        FakeServer.install(server)
        LiveService.linkFactory = { _, _, events -> FakeBleLink(events).also { link = it } }
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        if (Build.VERSION.SDK_INT >= 33) {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        server.current = FakeLinkUp.Value(now - 20_000L, 104)
        server.history.add(FakeLinkUp.Value(now - 16 * minute, 119))
    }

    @After
    fun tearDown() {
        controller?.destroy()
        LiveService.linkFactory = null
        GlucoseStore.closeShared()
        AppClock.source = { System.currentTimeMillis() }
        FakeServer.remove()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Logged in and switched on, as the screen does it. */
    private fun switchOn() {
        val runner = LinkUpRunner(store, settings, server)
        val connect = runner.connect(server.email, server.password, now)
        assertTrue(connect.isOk)
        runner.choose(connect.patients[0])
        settings.liveWaitingSince = now
    }

    private fun command(action: String = LiveService.ACTION_START): LiveService {
        val intent = Intent(app, LiveService::class.java).setAction(action)
        val existing = controller
        if (existing == null) {
            controller = Robolectric.buildService(LiveService::class.java, intent).create().startCommand(0, 1)
        } else {
            existing.get().onStartCommand(intent, 0, 2)
        }
        idle()
        return controller!!.get()
    }

    /** Time goes by: on the clock of the app and on the clock of the phone alike. */
    private fun pass(seconds: Int) {
        now += seconds * 1000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds.toLong()))
    }

    /** A minute goes by, and LibreLink has sent the next value. */
    private fun nextValue(mgdl: Int) {
        now += minute
        server.current = FakeLinkUp.Value(now - 20_000L, mgdl)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
    }

    private fun notification(id: Int): Notification? {
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(manager).getNotification(id)
    }

    private fun title(id: Int): String? = notification(id)?.let { shadowOf(it).contentTitle?.toString() }

    private fun text(id: Int): String? = notification(id)?.let { shadowOf(it).contentText?.toString()?.replace('\u202F', ' ') }

    private fun graphRequests(): Int = server.to("/graph").size

    // ------------------------------------------------------------------ starting

    @Test
    fun itStartsWithoutAnyBluetoothPermission() {
        assertFalse("nothing is switched on", LiveService.start(app))
        switchOn()
        // (no Bluetooth permission was given in this test)
        assertTrue(LiveService.start(app, LiveService.ACTION_LOOK))
        val started = shadowOf(app).nextStartedService
        assertEquals(LiveService::class.java.name, started.component!!.className)
        assertEquals(LiveService.ACTION_LOOK, started.action)

        val service = command()
        assertEquals(LiveNotifications.ID_LIVE, shadowOf(service).lastForegroundNotificationId)
        if (Build.VERSION.SDK_INT >= 34) {
            assertEquals("no Bluetooth: Android is told the other reason", ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        }
        assertTrue("the sensor is left alone: nothing is done over Bluetooth", link == null || link!!.calls.isEmpty())
        assertEquals(LiveState.OFF, settings.liveState)
    }

    @Test
    fun theFirstQuestionGoesOutAtOnce() {
        switchOn()
        command()
        assertEquals(1, graphRequests())
        assertEquals(LinkUpState.RECEIVING, settings.linkUpState)
        assertEquals(listOf(119, 104), store.table(ValueSource.LINKUP).since(0L).map { it.value })

        assertEquals("104 mg/dL →", title(LiveNotifications.ID_LIVE))
        assertEquals("In target range · 2:32 PM", text(LiveNotifications.ID_LIVE))
        assertNull(notification(LiveNotifications.ID_ALARM))
    }

    @Test
    fun aValueEachMinute() {
        switchOn()
        command()
        // the value is 20 seconds old: the next one is due in 40 seconds, the app asks 12 seconds after that
        pass(50)
        assertEquals("not yet", 1, graphRequests())
        now += 10_000L
        server.current = FakeLinkUp.Value(now - 20_000L, 101)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(2, graphRequests())
        // (no arrow for a moment: the older value of the list is now 17 minutes back, too far
        // to compare with, and the values of the last minutes are too close)
        assertEquals("101 mg/dL", title(LiveNotifications.ID_LIVE))

        for (value in listOf(99, 97, 96, 95)) {
            nextValue(value)
        }
        assertEquals("one question for each value, not more", 6, graphRequests())
        assertEquals(listOf(119, 104, 101, 99, 97, 96, 95), store.table(ValueSource.LINKUP).since(0L).map { it.value })
        assertEquals("9 mg/dL less than five minutes ago", "95 mg/dL ↘", title(LiveNotifications.ID_LIVE))
        assertEquals("one login for all of it", 1, server.logins)
    }

    @Test
    fun theWakeUpComesWhenTheNextQuestionIsDue() {
        switchOn()
        command()
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarm = shadowOf(alarms).scheduledAlarms.last()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertTrue("it must also come while the phone sleeps deeply", alarm.isAllowWhileIdle)
        val wait = alarm.triggerAtMs - SystemClock.elapsedRealtime()
        assertTrue("52 seconds: 40 until the next value is due, and 12 more; it was $wait", wait in 51_900L..52_000L)
        assertEquals("exact: a wake-up that Android may shift by minutes would be of no use", 0L, alarm.windowLengthMs)
    }

    @Test
    fun withoutTheRightForExactWakeUpsTheAppStillAsks() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        switchOn()
        command()
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        assertTrue(shadowOf(alarms).scheduledAlarms.last().isAllowWhileIdle)
        nextValue(101)
        assertEquals(2, graphRequests())
    }

    @Test
    fun aWakeUpAfterSleepAsksRightAway() {
        switchOn()
        val service = command()
        assertEquals(1, graphRequests())
        // The phone sleeps: its timers stand still for four minutes. Then Android wakes the app.
        now += 4 * minute
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
        server.current = FakeLinkUp.Value(now - 20_000L, 92)
        service.onTick()
        idle()
        assertEquals(2, graphRequests())
        assertEquals("92 mg/dL", title(LiveNotifications.ID_LIVE))

        // a second wake-up a moment later does not ask twice
        service.onTick()
        idle()
        assertEquals(2, graphRequests())
    }

    @Test
    fun aCommandToLookAsksAtOnce() {
        switchOn()
        command()
        pass(5)
        command(LiveService.ACTION_START)
        assertEquals("an ordinary start does not ask before it is time", 1, graphRequests())
        command(LiveService.ACTION_LOOK)
        assertEquals(2, graphRequests())
    }

    // ------------------------------------------------------------------ alarms and voice

    @Test
    fun theLowAlarmWorksWithTheValuesOfLibreLinkUp() {
        switchOn()
        command()
        assertEquals(0, AlarmSounder.plays)
        nextValue(86)
        nextValue(69)
        assertEquals(1, AlarmSounder.plays)
        assertEquals("Low glucose: 69 mg/dL", title(LiveNotifications.ID_ALARM))
        assertEquals("69 mg/dL", title(LiveNotifications.ID_LIVE))

        // back above the limit: the alarm goes away
        nextValue(84)
        assertNull(notification(LiveNotifications.ID_ALARM))
    }

    @Test
    fun withoutInternetTheNoValuesAlarmRings() {
        switchOn()
        command()
        server.offline = true
        for (i in 1..19) {
            pass(60)
        }
        assertEquals(0, AlarmSounder.plays)
        assertEquals(LinkUpState.NO_INTERNET, settings.linkUpState)
        assertEquals("No current value", title(LiveNotifications.ID_LIVE))
        assertEquals("No internet. No values come.", text(LiveNotifications.ID_LIVE))
        pass(60)
        pass(60)
        assertEquals("20 minutes without a value", 1, AlarmSounder.plays)
        assertEquals("No values", title(LiveNotifications.ID_ALARM)?.take(9))
        assertTrue(shadowOf(notification(LiveNotifications.ID_ALARM)!!).contentText.toString().contains("No internet. No values come."))

        // the internet is back
        server.offline = false
        nextValue(108)
        assertEquals(LinkUpState.RECEIVING, settings.linkUpState)
        assertNull(notification(LiveNotifications.ID_ALARM))
        assertEquals("108 mg/dL", title(LiveNotifications.ID_LIVE))
    }

    @Test
    fun theValueIsSaidAsOftenAsTheUserWants() {
        settings.speakLiveMinutes = 5
        switchOn()
        command()
        assertEquals("Glucose 104. Steady.", Speaker.lastRequest)
        Speaker.reset()
        nextValue(103)
        nextValue(102)
        assertNull("not each minute", Speaker.lastRequest)
        nextValue(101)
        nextValue(100)
        nextValue(99)
        assertEquals("Glucose 99. Steady.", Speaker.lastRequest)
    }

    // ------------------------------------------------------------------ problems

    @Test
    fun aPasswordThatIsWrongNowIsToldInTheNotification() {
        switchOn()
        command()
        server.password = "changed"
        server.endToken()
        nextValue(101)
        assertEquals(LinkUpState.WRONG_LOGIN, settings.linkUpState)
        pass(6 * 60)
        assertEquals("No current value", title(LiveNotifications.ID_LIVE))
        assertEquals("LibreLinkUp does not accept the password any more. Open More, LibreLinkUp and log in again.", text(LiveNotifications.ID_LIVE))
        val asked = server.requests.size
        pass(10 * 60)
        assertEquals("the server is left alone", asked, server.requests.size)
    }

    @Test
    fun switchedOffTheServiceEnds() {
        switchOn()
        val service = command()
        LinkUpRunner(store, settings, server).switchOff(forgetLogin = true)
        command()
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller!!.destroy()
        controller = null
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertEquals("no wake-up is left behind", 0, shadowOf(alarms).scheduledAlarms.size)
        val asked = server.requests.size
        pass(5 * 60)
        assertEquals(asked, server.requests.size)
    }

    @Test
    fun whenAndroidEndsTheServiceTheWakeUpStartsItAgain() {
        switchOn()
        command()
        controller!!.destroy()
        controller = null
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        assertNull(LiveService.instance)
        // the wake-up comes: no service runs, so it is started
        shadowOf(app).clearStartedServices()
        TickReceiver().onReceive(app, Intent(app, TickReceiver::class.java))
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(LiveService::class.java.name, started.component!!.className)
    }

    @Test
    fun scannedValuesDoNotCountWhileLibreLinkUpIsOn() {
        // a scan a moment ago gave a low value by the app's own formula
        store.table(ValueSource.SENSOR).insert(listOf(Glucose(now - minute, 61, null, true, "3MH001HM1LM")))
        switchOn()
        command()
        assertEquals("the alarms look at the numbers on the screen: those of LibreLinkUp", 0, AlarmSounder.plays)
        assertEquals("104 mg/dL →", title(LiveNotifications.ID_LIVE))
    }
}
