package com.glucosescan.app.core

import com.glucosescan.app.libre.FactoryCalibration
import com.glucosescan.app.libre.SensorInfo
import com.glucosescan.app.testing.BleMessages
import com.glucosescan.app.testing.TestSensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveProcessorTest {
    private val noon = 1_767_268_800_000L
    private val sample = TestSensors.example

    private fun sensor(sample: TestSensors.Sample = this.sample, lifetime: Int = 20659): LiveSensor {
        val info = SensorInfo.libreStyleSensor(sample.uid, sample.patchInfo, sample.plain)
        return LiveSensor(info.serial, sample.uid, sample.patchInfo, FactoryCalibration.libreStyleCalibration(sample.plain), lifetime)
    }

    /** A Bluetooth message with the raw numbers of a scan must give exactly the values of the scan. */
    @Test
    fun liveValuesEqualTheValuesOfAScan() {
        for (sample in listOf(TestSensors.example, TestSensors.example2)) {
            val scan = ScanProcessor.process(sample.rawScan(noon + 12_345))
            val age = scan.sensor!!.age
            val records = BleMessages.recordsOf(sample)
            for (r in records) {
                assertTrue("the adjustment fits into a Bluetooth record", Math.abs(r.adjustment) <= 124)
            }
            val message = BleMessages.scramble(sample.uid, BleMessages.plain(age, records))

            val live = LiveProcessor.process(sensor(sample), message, noon + 12_345)

            assertEquals(LiveStatus.OK, live.status)
            assertEquals(age, live.age)
            assertEquals(7, live.readings.size)
            val scanTrend = scan.readings!!.trend.associateBy { it.timestamp }
            for (reading in live.readings) {
                val same = scanTrend.getValue(reading.timestamp)
                assertEquals(same.glucoseValue, reading.glucoseValue, 0.0)
                assertTrue(reading.isOk)
                assertTrue(reading.fromTrend)
            }
            assertEquals("oldest first", live.readings.map { it.timestamp }.sorted(), live.readings.map { it.timestamp })
            assertEquals(listOf(15, 12, 7, 6, 4, 2, 0), live.readings.map { ((noon - it.timestamp) / 60_000L).toInt() })
        }
    }

    @Test
    fun valuesAreAsChosen() {
        val message = BleMessages.message(sample, 5000, listOf(104, 103, 101, 100, 99, 95, 92))
        val live = LiveProcessor.process(sensor(), message, noon)
        assertEquals(LiveStatus.OK, live.status)
        val values = live.readings.map { Glucose.toInteger(it.glucoseValue)!!.toInt() }
        assertEquals(listOf(92, 95, 99, 100, 101, 103, 104), values)
    }

    @Test
    fun timesAreCutToTheMinute() {
        val live = LiveProcessor.process(sensor(), BleMessages.steady(sample, 5000, 110), noon + 37_000)
        assertEquals(noon, live.readings.last().timestamp)
        for (reading in live.readings) {
            assertEquals(0L, reading.timestamp % 60_000L)
        }
    }

    @Test
    fun theAgeOfTheSensorDecides() {
        assertEquals(LiveStatus.STARTING, LiveProcessor.process(sensor(), BleMessages.steady(sample, 0, 110), noon).status)
        assertEquals(LiveStatus.STARTING, LiveProcessor.process(sensor(), BleMessages.steady(sample, 60, 110), noon).status)
        assertTrue(LiveProcessor.process(sensor(), BleMessages.steady(sample, 60, 110), noon).readings.isEmpty())

        val first = LiveProcessor.process(sensor(), BleMessages.steady(sample, 61, 110), noon)
        assertEquals(LiveStatus.OK, first.status)
        assertEquals("only the value of minute 61 is after the warm-up", 1, first.readings.size)

        val tenth = LiveProcessor.process(sensor(), BleMessages.steady(sample, 70, 110), noon)
        assertEquals("minutes 70, 68, 66, 64, 63", 5, tenth.readings.size)

        assertEquals(LiveStatus.OK, LiveProcessor.process(sensor(), BleMessages.steady(sample, 20658, 110), noon).status)
        val ended = LiveProcessor.process(sensor(), BleMessages.steady(sample, 20659, 110), noon)
        assertEquals(LiveStatus.EXPIRED, ended.status)
        assertTrue(ended.readings.isEmpty())
        assertEquals(20659, ended.age)
    }

    @Test
    fun aDamagedMessageIsNotUsed() {
        val good = BleMessages.steady(sample, 5000, 110)
        assertEquals(LiveStatus.OK, LiveProcessor.process(sensor(), good, noon).status)

        for (at in listOf(0, 1, 2, 17, 30, 44, 45)) {
            val damaged = good.copyOf()
            damaged[at] = (damaged[at].toInt() xor 0x10).toByte()
            val result = LiveProcessor.process(sensor(), damaged, noon)
            assertEquals("one wrong bit in byte $at", LiveStatus.BAD_DATA, result.status)
            assertTrue(result.readings.isEmpty())
            assertEquals(-1, result.age)
        }
        assertEquals(LiveStatus.BAD_DATA, LiveProcessor.process(sensor(), good.copyOf(45), noon).status)
        assertEquals(LiveStatus.BAD_DATA, LiveProcessor.process(sensor(), good + 0, noon).status)
        assertEquals(LiveStatus.BAD_DATA, LiveProcessor.process(sensor(), ByteArray(0), noon).status)
        assertEquals(LiveStatus.BAD_DATA, LiveProcessor.process(sensor(), ByteArray(46), noon).status)
    }

    @Test
    fun aMessageOfAnotherSensorIsNotUsed() {
        val other = BleMessages.steady(TestSensors.example2, 5000, 110)
        assertEquals(LiveStatus.BAD_DATA, LiveProcessor.process(sensor(TestSensors.example), other, noon).status)
        assertEquals(LiveStatus.OK, LiveProcessor.process(sensor(TestSensors.example2), other, noon).status)
    }

    /** The original takes an all-zero record for a good one. This app does not. */
    @Test
    fun aRecordWithoutRawValueIsFaulty() {
        val message = BleMessages.message(sample, 5000, listOf(null, 103, 101, null, 99, 95, 92))
        val live = LiveProcessor.process(sensor(), message, noon)
        assertEquals(LiveStatus.OK, live.status)
        assertEquals(7, live.readings.size)
        val byMinute = live.readings.associateBy { ((noon - it.timestamp) / 60_000L).toInt() }
        assertFalse(byMinute.getValue(0).isOk)
        assertFalse(byMinute.getValue(6).isOk)
        assertTrue(byMinute.getValue(2).isOk)

        val processed = GlucoseProcessor.process(live.readings, null, "3MH001HM1LM")
        assertEquals(listOf(92, 95, 99, 101, 103), processed.newValues.map { it.value })
        assertEquals(2, processed.faultyReadings)
    }

    @Test
    fun aSensorWithoutUsableCalibrationGivesNothing() {
        val broken = LiveSensor("X", sample.uid, sample.patchInfo, FactoryCalibration(0, 0, 0.0, 0.0, 0.0, 0.0), 20000)
        assertEquals(LiveStatus.BAD_DATA, LiveProcessor.process(broken, BleMessages.steady(sample, 5000, 110), noon).status)
    }
}
