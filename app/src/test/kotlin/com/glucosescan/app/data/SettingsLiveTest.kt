package com.glucosescan.app.data

import com.glucosescan.app.core.AlarmKind
import com.glucosescan.app.core.AlarmMemory
import com.glucosescan.app.libre.FactoryCalibration
import com.glucosescan.app.libre.SensorInfo
import com.glucosescan.app.testing.TestSensors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** What version 2.0 keeps in the settings: the sensor for live values, voice, alarms. */
@RunWith(RobolectricTestRunner::class)
class SettingsLiveTest {
    private lateinit var settings: Settings
    private val noon = 1_767_268_800_000L

    @Before
    fun setUp() {
        settings = Settings(RuntimeEnvironment.getApplication())
    }

    @Test
    fun whatANewInstallStartsWith() {
        assertFalse("live values are off until the user switches them on", settings.liveEnabled)
        assertFalse(settings.livePairWanted)
        assertNull(settings.liveSensor)
        assertNull(settings.liveAddress)
        assertEquals(0, settings.liveUnlockCount)
        assertEquals(LiveState.OFF, settings.liveState)
        assertEquals("", settings.liveLog)

        assertTrue(settings.speakAfterScan)
        assertTrue(settings.speakTrend)
        assertTrue(settings.speakAlarms)
        assertEquals(0, settings.speakLiveMinutes)

        assertTrue(settings.alarmLowOn)
        assertEquals(70, settings.alarmLow)
        assertFalse(settings.alarmHighOn)
        assertEquals(250, settings.alarmHigh)
        assertTrue(settings.alarmNoDataOn)
        assertEquals(14, settings.statisticsDays)
    }

    @Test
    fun theSensorForLiveValuesIsKeptWithEveryNumber() {
        val sample = TestSensors.example
        val calibration = FactoryCalibration.libreStyleCalibration(sample.plain)
        val serial = SensorInfo.libreStyleSensor(sample.uid, sample.patchInfo, sample.plain).serial
        val answer = byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte())
        settings.liveSensor = LiveSensorRecord(serial, sample.uid, sample.patchInfo, calibration, 20659, noon - 5, answer, noon)

        val back = Settings(RuntimeEnvironment.getApplication()).liveSensor!!
        assertEquals("3MH001HM1LM", back.serial)
        assertArrayEquals(sample.uid, back.uid)
        assertArrayEquals(sample.patchInfo, back.patchInfo)
        assertEquals(20659, back.lifetime)
        assertEquals(noon - 5, back.startTime)
        assertEquals(noon, back.pairedTime)
        assertArrayEquals(answer, back.streamingAnswer)
        assertEquals("F6:E5:D4:C3:B2:A1", back.addressFromAnswer)
        assertEquals("ABBOTT3MH001HM1LM", back.bluetoothName)

        // the calibration gives the same glucose value as before
        for (raw in intArrayOf(800, 1500, 3000)) {
            assertEquals(calibration.calibrate(raw.toDouble(), 7000.0, 12.0), back.calibration.calibrate(raw.toDouble(), 7000.0, 12.0), 0.0)
        }
        assertTrue(back.calibration.isUsable)

        settings.liveSensor = null
        assertNull(settings.liveSensor)
    }

    @Test
    fun numbersStayInsideTheirLimits() {
        settings.alarmLow = 10
        assertEquals(Settings.ALARM_LOW_MIN, settings.alarmLow)
        settings.alarmLow = 500
        assertEquals(Settings.ALARM_LOW_MAX, settings.alarmLow)
        settings.alarmHigh = 10
        assertEquals(Settings.ALARM_HIGH_MIN, settings.alarmHigh)
        settings.alarmHigh = 9_999
        assertEquals(Settings.ALARM_HIGH_MAX, settings.alarmHigh)
        settings.speakLiveMinutes = 7
        assertEquals(0, settings.speakLiveMinutes)
        settings.speakLiveMinutes = 15
        assertEquals(15, settings.speakLiveMinutes)
        settings.statisticsDays = 3
        assertEquals(14, settings.statisticsDays)
        settings.statisticsDays = 90
        assertEquals(90, settings.statisticsDays)
    }

    @Test
    fun theUnlockCountAndTheAlarmMemoryAreKept() {
        settings.liveUnlockCount = 41
        settings.liveAddress = "F6:E5:D4:C3:B2:A1"
        settings.liveState = LiveState.RECEIVING
        settings.alarmMemory = AlarmMemory(AlarmKind.LOW, noon, AlarmKind.NO_DATA, noon + 5)

        val back = Settings(RuntimeEnvironment.getApplication())
        assertEquals(41, back.liveUnlockCount)
        assertEquals("F6:E5:D4:C3:B2:A1", back.liveAddress)
        assertEquals(LiveState.RECEIVING, back.liveState)
        val memory = back.alarmMemory
        assertEquals(AlarmKind.LOW, memory.lastKind)
        assertEquals(noon, memory.lastTime)
        assertEquals(AlarmKind.NO_DATA, memory.silencedKind)
        assertEquals(noon + 5, memory.silencedUntil)

        val alarms = back.alarmSettings
        assertTrue(alarms.lowOn)
        assertEquals(70, alarms.low)
        assertEquals(20, alarms.noDataMinutes)
        assertEquals(15, alarms.repeatMinutes)
    }

    @Test
    fun hexText() {
        assertEquals("00ff10a5", Settings.hex(byteArrayOf(0, -1, 16, 0xA5.toByte())))
        assertArrayEquals(byteArrayOf(0, -1, 16, 0xA5.toByte()), Settings.unhex("00FF10a5"))
        assertNull(Settings.unhex("abc"))
        assertNull(Settings.unhex("zz"))
        assertNull(Settings.unhex(null))
    }
}
