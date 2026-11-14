package com.glucosescan.app.live

import android.Manifest
import android.app.Application
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import com.glucosescan.app.core.AlarmKind
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LiveSensorRecord
import com.glucosescan.app.data.LiveState
import com.glucosescan.app.data.Settings
import com.glucosescan.app.libre.FactoryCalibration
import com.glucosescan.app.libre.Libre2Ble
import com.glucosescan.app.libre.SensorInfo
import com.glucosescan.app.testing.BleMessages
import com.glucosescan.app.testing.FakeBleLink
import com.glucosescan.app.testing.TestSensors
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.CurrentValues
import com.glucosescan.app.ui.Speaker
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.time.Duration
import java.util.TimeZone

/**
 * The service that keeps the live connection, with a pretended Bluetooth: from the first
 * message to the notification, the alarms and the voice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class LiveServiceTest {
    private val minute = 60_000L
    private val sample = TestSensors.example
    private val address = "A4:11:22:33:44:55"

    /** Tuesday 10 November 2026, 14:32:20 local time (UTC+3) */
    private var now = 1_794_310_340_000L
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private var link: FakeBleLink? = null
    private var controller: ServiceController<LiveService>? = null
    private var age = 10_320

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
        LiveService.linkFactory = { _, _, events -> FakeBleLink(events).also { link = it } }
    }

    @After
    fun tearDown() {
        controller?.destroy()
        LiveService.linkFactory = null
        GlucoseStore.closeShared()
        AppClock.source = { System.currentTimeMillis() }
    }

    private fun allow() {
        if (Build.VERSION.SDK_INT >= 31) {
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun record(): LiveSensorRecord {
        val info = SensorInfo.libreStyleSensor(sample.uid, sample.patchInfo, sample.plain)
        return LiveSensorRecord(
            info.serial, sample.uid.copyOf(), sample.patchInfo.copyOf(), FactoryCalibration.libreStyleCalibration(sample.plain), 20659,
            now - age * minute, byteArrayOf(0x55, 0x44, 0x33, 0x22, 0x11, 0xA4.toByte()), now - minute
        )
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun command(action: String = LiveService.ACTION_START, kind: AlarmKind? = null): LiveService {
        val intent = Intent(app, LiveService::class.java).setAction(action)
        if (kind != null) {
            intent.putExtra(LiveService.EXTRA_KIND, kind.name)
        }
        val existing = controller
        if (existing == null) {
            controller = Robolectric.buildService(LiveService::class.java, intent).create().startCommand(0, 1)
        } else {
            existing.get().onStartCommand(intent, 0, 2) // the service runs already: Android hands it the next command
        }
        idle()
        return controller!!.get()
    }

    /** Switched on, allowed, with a sensor that a scan has set up; the service runs. */
    private fun running(): LiveService {
        allow()
        settings.liveEnabled = true
        settings.liveSensor = record()
        return command()
    }

    /** The pretended sensor is found and takes the connection. */
    private fun connect() {
        val events = link!!.events()!!
        events.onFound(address, null, sample.uid.copyOfRange(0, 6))
        events.onConnected()
        events.onServicesDiscovered(true)
        events.onUnlockWritten(true)
        events.onNotificationsEnabled(true)
        idle()
    }

    /** One message of the sensor. [values] are for 0, 2, 4, 6, 7, 12 and 15 minutes before now. */
    private fun send(values: List<Int?>) {
        val message = BleMessages.message(sample, age, values, header = age and 0xFFFF)
        for (part in BleMessages.packets(message)) {
            link!!.events()!!.onPacket(part)
        }
        idle()
    }

    private fun minutesPass(minutes: Int) {
        now += minutes * minute
        age += minutes
    }

    private fun notification(id: Int): Notification? {
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(manager).getNotification(id)
    }

    private fun title(id: Int): String? = notification(id)?.let { shadowOf(it).contentTitle?.toString() }

    private fun text(id: Int): String? = notification(id)?.let { shadowOf(it).contentText?.toString() }

    // ------------------------------------------------------------------ starting

    @Test
    fun startsOnlyWhenSwitchedOnAndAllowed() {
        assertFalse("live values are off", LiveService.start(app))
        assertNull(shadowOf(app).nextStartedService)

        settings.liveEnabled = true
        if (Build.VERSION.SDK_INT >= 31) {
            assertFalse("Bluetooth is not allowed yet", LiveService.start(app))
            assertEquals(LiveState.NO_PERMISSION, settings.liveState)
            assertNull(shadowOf(app).nextStartedService)
        }
        allow()
        assertTrue(LiveService.start(app))
        val started = shadowOf(app).nextStartedService
        assertEquals(LiveService::class.java.name, started.component!!.className)
        assertEquals(LiveService.ACTION_START, started.action)
    }

    @Test
    fun withoutASensorItWaitsForAScan() {
        allow()
        settings.liveEnabled = true
        val service = command()
        assertEquals(LiveState.NEEDS_SCAN, settings.liveState)
        assertEquals(LiveNotifications.ID_LIVE, shadowOf(service).lastForegroundNotificationId)
        assertEquals("No current value", title(LiveNotifications.ID_LIVE))
        assertEquals("Hold the phone to the sensor once. The scan sets up the connection.", text(LiveNotifications.ID_LIVE))
        assertTrue(link!!.calls.isEmpty())

        // a scan sets the sensor up and starts the service again: now it goes for the sensor
        settings.liveSensor = record()
        command()
        assertEquals(LiveState.SEARCHING, settings.liveState)
        assertTrue(link!!.scanning)
        assertEquals("Looking for the sensor…", text(LiveNotifications.ID_LIVE))
    }

    @Test
    fun switchedOffItEndsItself() {
        val service = running()
        assertEquals(LiveState.SEARCHING, settings.liveState)
        settings.liveEnabled = false
        command()
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller!!.destroy()
        controller = null
        assertEquals(LiveState.OFF, settings.liveState)
        assertNull(LiveService.instance)
        assertFalse(link!!.scanning)
    }

    // ------------------------------------------------------------------ values

    @Test
    fun aMessageIsSavedAndShownInTheNotification() {
        running()
        connect()
        assertEquals(LiveState.CONNECTED, settings.liveState)
        assertEquals(address, settings.liveAddress)
        assertEquals(1, settings.liveUnlockCount)
        assertArrayEquals(Libre2Ble.streamingUnlockPayload(sample.uid, sample.patchInfo, Libre2Ble.UNLOCK_CODE, 1), link!!.payload)
        assertEquals("Connected. Waiting for the first value.", text(LiveNotifications.ID_LIVE))

        send(listOf(104, 103, 101, 100, 99, 95, 92))
        assertEquals(LiveState.RECEIVING, settings.liveState)
        assertEquals(7, store.count())
        assertEquals(104, store.latest()!!.value)
        assertEquals(now, settings.liveLastMessageTime)
        assertEquals("104 mg/dL \u2192", title(LiveNotifications.ID_LIVE))
        assertTrue(text(LiveNotifications.ID_LIVE), text(LiveNotifications.ID_LIVE)!!.startsWith("In target range \u00b7 "))
        assertNull("no alarm", notification(LiveNotifications.ID_ALARM))
        assertEquals(0, AlarmSounder.plays)
        assertNull("nothing is said unless the user asks for it", Speaker.lastRequest)

        // a minute later: one more value, rising. The message also brings the minutes between
        // the values of the first one (1, 3, 5, 11 and 14 minutes before it).
        minutesPass(1)
        send(listOf(120, 104, 103, 101, 100, 96, 93))
        assertEquals(13, store.count())
        assertEquals("120 mg/dL \u2197", title(LiveNotifications.ID_LIVE))
        assertEquals("still the same connection", 1, settings.liveUnlockCount)
    }

    @Test
    fun inMmolTheNotificationUsesMmol() {
        settings.unit = GlucoseUnit.MMOLL
        running()
        connect()
        send(List(7) { 104 })
        assertEquals("5.8 mmol/L \u2192", title(LiveNotifications.ID_LIVE))
    }

    @Test
    fun anOldValueIsNotShownAsCurrent() {
        running()
        connect()
        send(List(7) { 104 })
        assertEquals("104 mg/dL \u2192", title(LiveNotifications.ID_LIVE))
        now += 7 * minute
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61)) // the service looks once a minute
        assertEquals("No current value", title(LiveNotifications.ID_LIVE))
    }

    // ------------------------------------------------------------------ alarms

    @Test
    fun aLowValueRingsAndCanBeSilenced() {
        running()
        connect()
        send(listOf(60, 64, 68, 72, 74, 84, 90))
        assertEquals("60 mg/dL \u2198", title(LiveNotifications.ID_LIVE))
        assertEquals(1, AlarmSounder.plays)
        assertEquals("Low glucose: 60 mg/dL", title(LiveNotifications.ID_ALARM))
        val alarm = notification(LiveNotifications.ID_ALARM)!!
        assertEquals("Silence for 30 minutes", alarm.actions[0].title.toString())
        assertEquals(AlarmKind.LOW, settings.alarmMemory.lastKind)
        assertTrue(settings.liveLog.contains("alarm: LOW"))

        // when the sound has ended, the app says what it is
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertEquals("Low glucose alarm. 60.", Speaker.lastRequest)

        // the next minute it does not ring again
        minutesPass(1)
        send(listOf(58, 60, 64, 68, 70, 80, 86))
        assertEquals(1, AlarmSounder.plays)
        assertEquals("58 mg/dL \u2198", title(LiveNotifications.ID_LIVE))

        // the user taps "Silence": the notification goes, and 15 minutes later nothing rings
        command(LiveService.ACTION_SILENCE, AlarmKind.LOW)
        assertNull(notification(LiveNotifications.ID_ALARM))
        assertEquals(AlarmKind.LOW, settings.alarmMemory.silencedKind)
        minutesPass(16)
        send(listOf(57, 57, 57, 57, 57, 57, 57))
        assertEquals(1, AlarmSounder.plays)

        // after the 30 minutes it rings again
        minutesPass(16)
        send(listOf(57, 57, 57, 57, 57, 57, 57))
        assertEquals(2, AlarmSounder.plays)
        assertEquals("Low glucose: 57 mg/dL", title(LiveNotifications.ID_ALARM))
    }

    @Test
    fun theAlarmGoesAwayWhenTheValueIsGoodAgain() {
        running()
        connect()
        send(List(7) { 62 })
        assertNotNull(notification(LiveNotifications.ID_ALARM))
        minutesPass(5)
        send(listOf(95, 90, 84, 78, 74, 62, 62))
        assertNull(notification(LiveNotifications.ID_ALARM))
        assertEquals(1, AlarmSounder.plays)
    }

    // ------------------------------------------------------------------ before a low

    /**
     * 86 at the last full minute, and 2 mg/dL less each minute. The clock of the test stands
     * 20 seconds after that minute: 8 minutes from now the line is at 69.3, below the low
     * alarm limit of 70.
     */
    private val falling = listOf(86, 90, 94, 98, 100, 110, 116)

    @Test
    fun aFallingValueRingsBeforeTheLowComes() {
        settings.alarmLowSoonOn = true
        running()
        connect()
        send(falling)
        assertEquals(8, CurrentValues.read(store, settings, now).minutesUntilBelow(settings.alarmLow, now))
        assertEquals(1, AlarmSounder.plays)
        assertEquals("Low glucose soon: 86 mg/dL", title(LiveNotifications.ID_ALARM))
        assertTrue(text(LiveNotifications.ID_ALARM)!!.startsWith("The glucose is falling. If it goes on like this, it is below your low alarm limit within 20 minutes."))
        assertEquals("Silence for 30 minutes", notification(LiveNotifications.ID_ALARM)!!.actions[0].title.toString())
        assertEquals(AlarmKind.LOW_SOON, settings.alarmMemory.lastKind)
        assertEquals(now, settings.alarmMemory.lastSoonTime)
        assertTrue(settings.liveLog.contains("alarm: LOW_SOON"))

        // when the sound has ended, the app says what it is
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertEquals("Low glucose soon. 86.", Speaker.lastRequest)

        // it goes on falling: the warning does not ring each minute
        minutesPass(1)
        send(listOf(84, 88, 92, 96, 98, 108, 114))
        assertEquals(1, AlarmSounder.plays)

        // 8 minutes later the value is below the limit: now the low alarm itself rings
        minutesPass(8)
        send(listOf(68, 72, 76, 80, 82, 92, 98))
        assertEquals(2, AlarmSounder.plays)
        assertEquals("Low glucose: 68 mg/dL", title(LiveNotifications.ID_ALARM))
        assertEquals(AlarmKind.LOW, settings.alarmMemory.lastKind)
    }

    @Test
    fun theWarningBeforeALowIsOffUntilTheUserSwitchesItOn() {
        running()
        connect()
        send(falling)
        assertEquals("the values do head below the limit", 8, CurrentValues.read(store, settings, now).minutesUntilBelow(settings.alarmLow, now))
        assertEquals(0, AlarmSounder.plays)
        assertNull(notification(LiveNotifications.ID_ALARM))
    }

    @Test
    fun theWarningBeforeALowDoesNotComeTwiceInHalfAnHour() {
        settings.alarmLowSoonOn = true
        running()
        connect()
        send(falling)
        assertEquals(1, AlarmSounder.plays)
        val warned = now

        // the value turns and stays at 90: the warning goes away, but the app remembers when it warned
        minutesPass(16)
        send(List(7) { 90 })
        assertNull(notification(LiveNotifications.ID_ALARM))
        assertNull(settings.alarmMemory.lastKind)
        assertEquals(warned, settings.alarmMemory.lastSoonTime)

        // 26 minutes after the warning the value heads down again: no second warning yet
        minutesPass(10)
        send(listOf(74, 78, 82, 86, 88, 90, 90))
        assertNotNull("it does head below the limit", CurrentValues.read(store, settings, now).minutesUntilBelow(settings.alarmLow, now))
        assertEquals(1, AlarmSounder.plays)
        assertNull(notification(LiveNotifications.ID_ALARM))
        assertEquals(warned, settings.alarmMemory.lastSoonTime)
    }

    @Test
    fun aValueThatHeadsBelowTheTargetRangeIsSaidSo() {
        settings.speakLiveMinutes = 5
        running()
        connect()
        // 86 and falling by 1.5 a minute; the target range starts at 80: that is 4 minutes away
        send(listOf(86, 89, 92, 95, 97, 104, 109))
        assertEquals("Glucose 86. Falling. Below target soon.", Speaker.lastRequest)
    }

    @Test
    fun theSentenceAboutALowThatComesCanBeSwitchedOff() {
        settings.speakLiveMinutes = 5
        settings.speakLowSoon = false
        running()
        connect()
        send(listOf(86, 89, 92, 95, 97, 104, 109))
        assertEquals("Glucose 86. Falling.", Speaker.lastRequest)
    }

    @Test
    fun aLowValueDoesNotRingWhenTheLowAlarmIsOff() {
        settings.alarmLowOn = false
        running()
        connect()
        send(List(7) { 55 })
        assertEquals(0, AlarmSounder.plays)
        assertNull(notification(LiveNotifications.ID_ALARM))
    }

    @Test
    fun aHighValueRingsOnlyIfThatAlarmIsOn() {
        running()
        connect()
        send(List(7) { 280 })
        assertEquals("off by default", 0, AlarmSounder.plays)

        settings.alarmHighOn = true
        minutesPass(1)
        send(List(7) { 282 })
        assertEquals(1, AlarmSounder.plays)
        assertEquals("High glucose: 282 mg/dL", title(LiveNotifications.ID_ALARM))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertEquals("High glucose alarm. 282.", Speaker.lastRequest)
    }

    @Test
    fun noValuesForTwentyMinutesRing() {
        val service = running()
        connect()
        send(List(7) { 104 })
        assertEquals(0, AlarmSounder.plays)

        // the connection is lost and does not come back
        link!!.events()!!.onDisconnected(8)
        idle()
        assertEquals(LiveState.SEARCHING, settings.liveState)
        now += 19 * minute
        service.onTick()
        assertEquals(0, AlarmSounder.plays)
        now += 2 * minute
        service.onTick()
        assertEquals(1, AlarmSounder.plays)
        assertEquals("No values from the sensor", title(LiveNotifications.ID_ALARM))
        assertTrue(text(LiveNotifications.ID_ALARM), text(LiveNotifications.ID_ALARM)!!.startsWith("No value for 20 minutes."))

        // 15 minutes later it rings again
        now += 16 * minute
        service.onTick()
        assertEquals(2, AlarmSounder.plays)

        // and not at all if the user has switched this alarm off
        settings.alarmNoDataOn = false
        now += 16 * minute
        service.onTick()
        assertEquals(2, AlarmSounder.plays)
    }

    @Test
    fun rightAfterSwitchingOnNoValuesIsNoAlarmYet() {
        allow()
        settings.liveEnabled = true
        val service = command() // no sensor set up yet
        now += 10 * minute
        service.onTick()
        assertEquals(0, AlarmSounder.plays)
        now += 11 * minute
        service.onTick()
        assertEquals("after 20 minutes without a scan the app reminds", 1, AlarmSounder.plays)
        assertTrue(text(LiveNotifications.ID_ALARM)!!.contains("Hold the phone to the sensor once."))
    }

    @Test
    fun anEndedSensorDoesNotRingForMissingValues() {
        val service = running()
        connect()
        age = 20_659
        send(List(7) { 104 })
        assertEquals(LiveState.SENSOR_ENDED, settings.liveState)
        assertEquals("The sensor has ended. Scan the new sensor when it runs.", text(LiveNotifications.ID_LIVE))
        now += 60 * minute
        service.onTick()
        assertEquals(0, AlarmSounder.plays)
    }

    // ------------------------------------------------------------------ voice

    @Test
    fun liveValuesAreSaidAsOftenAsTheUserChose() {
        settings.speakLiveMinutes = 5
        running()
        connect()
        send(List(7) { 104 })
        assertEquals("Glucose 104. Steady.", Speaker.lastRequest)
        assertEquals(1, Speaker.requests.size)

        for (k in 1..4) {
            minutesPass(1)
            send(List(7) { 104 + k })
        }
        assertEquals("not every minute", 1, Speaker.requests.size)
        minutesPass(1)
        send(List(7) { 110 })
        assertEquals(2, Speaker.requests.size)
        assertEquals("Glucose 110. Steady.", Speaker.lastRequest)
    }

    // ------------------------------------------------------------------ the sensor refuses

    @Test
    fun aRefusedSensorAsksForAScan() {
        running()
        val events = link!!.events()!!
        events.onFound(address, null, sample.uid.copyOfRange(0, 6))
        for (attempt in 1..5) {
            events.onConnected()
            events.onServicesDiscovered(true)
            events.onUnlockWritten(true)
            events.onDisconnected(19) // the sensor hangs up: it does not know this phone any more
            idle()
            if (attempt < 5) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121)) // the pause before the next try
            }
        }
        assertEquals(LiveState.REJECTED, settings.liveState)
        assertTrue("the next scan sets the sensor up again", settings.livePairWanted)
        assertEquals("The sensor does not answer this phone any more. Scan the sensor to connect again.", text(LiveNotifications.ID_LIVE))
        assertEquals(5, settings.liveUnlockCount)
    }

    // ------------------------------------------------------------------ after a restart of the phone

    @Test
    fun theReceiversStartTheServiceAgain() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull("live values are off", shadowOf(app).nextStartedService)

        allow()
        settings.liveEnabled = true
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(LiveService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(LiveService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        BootReceiver().onReceive(app, Intent("something.else"))
        assertNull(shadowOf(app).nextStartedService)

        TickReceiver().onReceive(app, Intent())
        assertEquals(LiveService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    private fun wakeUps(): Int {
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return shadowOf(alarms).scheduledAlarms.size
    }

    @Test
    fun theWakeUpStaysWhenAndroidEndsTheService() {
        running()
        assertEquals(1, wakeUps())

        // Android ends the service; live values are still switched on
        controller!!.destroy()
        controller = null
        assertNull(LiveService.instance)
        assertEquals("the next wake-up starts the service again", 1, wakeUps())
        assertTrue("still switched on", settings.liveEnabled)
        shadowOf(app).clearStartedServices()
        TickReceiver().onReceive(app, Intent())
        assertEquals(LiveService::class.java.name, shadowOf(app).nextStartedService.component!!.className)

        // the user switches live values off: no wake-up any more
        running()
        settings.liveEnabled = false
        controller!!.destroy()
        controller = null
        assertEquals(0, wakeUps())
        assertEquals(LiveState.OFF, settings.liveState)
    }

    @Test
    @Config(sdk = [34])
    fun theUserIsToldWhenLiveValuesCannotRun() {
        // switched on, but the phone does not allow Bluetooth (any more)
        settings.liveEnabled = true
        TickReceiver().onReceive(app, Intent())
        assertNull(shadowOf(app).nextStartedService)
        assertEquals("Live values have stopped", title(LiveNotifications.ID_STOPPED))
        assertEquals("The phone has stopped the app. No values and no alarms come now. Tap to open the app.", text(LiveNotifications.ID_STOPPED))
        assertEquals("it tries again", 1, wakeUps())

        // the user puts the notification away: it does not come back every five minutes
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(LiveNotifications.ID_STOPPED)
        now += 5 * minute
        TickReceiver().onReceive(app, Intent())
        assertNull(notification(LiveNotifications.ID_STOPPED))
        // but after an hour it does
        now += 56 * minute
        TickReceiver().onReceive(app, Intent())
        assertNotNull(notification(LiveNotifications.ID_STOPPED))

        // allowed again: the service starts and takes the notification away
        allow()
        settings.liveSensor = record()
        TickReceiver().onReceive(app, Intent())
        assertEquals(LiveService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        command()
        assertNull(notification(LiveNotifications.ID_STOPPED))
        assertEquals(0L, settings.liveStoppedShownTime)
    }
}
