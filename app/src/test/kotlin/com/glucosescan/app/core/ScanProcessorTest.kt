package com.glucosescan.app.core

import com.glucosescan.app.libre.SensorRegion
import com.glucosescan.app.libre.SensorState
import com.glucosescan.app.libre.SensorType
import com.glucosescan.app.reference.ReferenceVectors.hex
import com.glucosescan.app.testing.TestSensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanProcessorTest {
    private val noon = 1_767_268_800_000L

    private fun newest(result: ScanResult): Int = Glucose.toInteger(result.readings!!.trend.last().glucoseValue)!!.toInt()

    @Test
    fun realScanExample() {
        val result = ScanProcessor.process(TestSensors.example.rawScan(noon))
        assertEquals(ScanStatus.OK, result.status)
        assertFalse(result.checksumFailed)
        assertEquals(SensorType.LIBRE2_EU, result.sensorType)
        val sensor = result.sensor!!
        assertEquals("3MH001HM1LM", sensor.serial)
        assertEquals(SensorState.READY, sensor.state)
        assertEquals(SensorRegion.EUROPEAN, sensor.region)
        assertEquals(10320, sensor.age)
        assertEquals(20659, sensor.lifetime)
        assertEquals(99, newest(result))
        assertEquals(16, result.readings!!.trend.size)
        assertEquals(31, result.readings!!.history.size)
        assertTrue(result.readings!!.all.all { it.isOk })
    }

    @Test
    fun realScanExample2() {
        val result = ScanProcessor.process(TestSensors.example2.rawScan(noon))
        assertEquals(ScanStatus.OK, result.status)
        assertEquals("3MH001GJ0VW", result.sensor!!.serial)
        assertEquals(3170, result.sensor!!.age)
        assertEquals(20699, result.sensor!!.lifetime)
        assertEquals(95, newest(result))
    }

    @Test
    fun realScansGiveSensibleValues() {
        for (sample in listOf(TestSensors.example, TestSensors.example2)) {
            val result = ScanProcessor.process(sample.rawScan(noon))
            val good = GlucoseProcessor.process(result.readings!!.all, null, result.sensor!!.serial)
            assertFalse(good.tooNoisy)
            assertEquals(47, good.newValues.size)
            for (g in good.newValues) {
                assertTrue("${sample.name}: ${g.value} mg/dL", g.value in 55..250)
            }
            // from one minute to the next a real value moves only a little
            val trend = good.newValues.filter { it.fromTrend }
            for (k in 1 until trend.size) {
                assertTrue(Math.abs(trend[k].value - trend[k - 1].value) <= 5)
            }
        }
    }

    @Test
    fun valuesAreOnlyUsedWhileTheSensorSaysReady() {
        val expected = mapOf(
            1 to ScanStatus.NOT_STARTED,
            2 to ScanStatus.STARTING,
            4 to ScanStatus.EXPIRED,
            5 to ScanStatus.SHUT_DOWN,
            6 to ScanStatus.FAILURE,
            0 to ScanStatus.UNKNOWN_STATE,
            7 to ScanStatus.UNKNOWN_STATE,
            255 to ScanStatus.UNKNOWN_STATE
        )
        for ((state, status) in expected) {
            val sample = TestSensors.example.changed { TestSensors.setState(it, state) }
            val result = ScanProcessor.process(sample.rawScan(noon))
            assertEquals("state $state", status, result.status)
            assertNull("state $state: no values", result.readings)
            assertNotNull("state $state: sensor info is there", result.sensor)
        }
    }

    @Test
    fun aReadySensorPastItsTimeHasEnded() {
        // lifetime of Example is 20659 minutes
        for ((age, status) in listOf(20658 to ScanStatus.OK, 20659 to ScanStatus.EXPIRED, 20719 to ScanStatus.EXPIRED, 40000 to ScanStatus.EXPIRED)) {
            val sample = TestSensors.example.changed { TestSensors.setAge(it, age) }
            val result = ScanProcessor.process(sample.rawScan(noon))
            assertEquals("age $age", status, result.status)
            assertEquals(status == ScanStatus.OK, result.readings != null)
        }
    }

    @Test
    fun aReadySensorInItsFirstHourIsStillStarting() {
        for ((age, status) in listOf(0 to ScanStatus.STARTING, 59 to ScanStatus.STARTING, 60 to ScanStatus.STARTING, 61 to ScanStatus.OK)) {
            val sample = TestSensors.example.changed { TestSensors.setAge(it, age) }
            val result = ScanProcessor.process(sample.rawScan(noon))
            assertEquals("age $age", status, result.status)
        }
        // at minute 61 only the one value of minute 61 can be used
        val sample = TestSensors.example.changed { TestSensors.setAge(it, 61) }
        val readings = ScanProcessor.process(sample.rawScan(noon)).readings!!
        assertEquals(1, readings.trend.size)
        assertTrue(readings.history.isEmpty())
    }

    @Test
    fun everySingleWrongBitIsNoticed() {
        val sample = TestSensors.example2
        var checked = 0
        for (byte in 0 until 344) {
            for (bit in 0 until 8) {
                val fram = sample.encrypted.copyOf()
                fram[byte] = (fram[byte].toInt() xor (1 shl bit)).toByte()
                val result = ScanProcessor.process(RawScan(sample.uid, sample.patchInfo, fram, noon))
                assertEquals("byte $byte bit $bit", ScanStatus.BAD_DATA, result.status)
                assertTrue("byte $byte bit $bit", result.checksumFailed)
                assertNull(result.readings)
                assertNull(result.sensor)
                checked++
            }
        }
        assertEquals(2752, checked)
    }

    @Test
    fun memoryOfAnotherSensorIsNotAccepted() {
        // the memory of one sensor with the id of the other one: decoding gives nonsense
        val mixed = RawScan(TestSensors.example.uid, TestSensors.example.patchInfo, TestSensors.example2.encrypted, noon)
        val result = ScanProcessor.process(mixed)
        assertEquals(ScanStatus.BAD_DATA, result.status)
        assertTrue(result.checksumFailed)

        val allZero = RawScan(TestSensors.example.uid, TestSensors.example.patchInfo, ByteArray(344), noon)
        assertEquals(ScanStatus.BAD_DATA, ScanProcessor.process(allZero).status)

        val allOnes = RawScan(TestSensors.example.uid, TestSensors.example.patchInfo, ByteArray(344) { 0xFF.toByte() }, noon)
        assertEquals(ScanStatus.BAD_DATA, ScanProcessor.process(allOnes).status)
    }

    @Test
    fun otherSensorTypesAreRefused() {
        val others = mapOf(
            "df0000010102" to SensorType.LIBRE1,
            "a20800010102" to SensorType.LIBRE1,
            "e50003020000" to SensorType.LIBRE_US_14DAY,
            "700010000000" to SensorType.LIBRE_PRO_H,
            "760130020000" to SensorType.LIBRE2_US,
            "760130040000" to SensorType.LIBRE2_CA,
            "760170000000" to SensorType.LIBRE_SENSE,
            "000000000000" to SensorType.UNKNOWN,
            "00".repeat(28) to SensorType.LIBRE3
        )
        for ((patchInfo, type) in others) {
            val result = ScanProcessor.process(RawScan(TestSensors.example.uid, hex(patchInfo), TestSensors.example.encrypted, noon))
            assertEquals(patchInfo, ScanStatus.UNSUPPORTED_SENSOR, result.status)
            assertEquals(patchInfo, type, result.sensorType)
            assertNull(result.readings)
        }
    }

    @Test
    fun wrongLengthsAreRefused() {
        val s = TestSensors.example
        assertEquals(ScanStatus.BAD_DATA, ScanProcessor.process(RawScan(s.uid, ByteArray(0), s.encrypted, noon)).status)
        assertEquals(ScanStatus.BAD_DATA, ScanProcessor.process(RawScan(s.uid, s.patchInfo.copyOf(5), s.encrypted, noon)).status)
        assertEquals(ScanStatus.BAD_DATA, ScanProcessor.process(RawScan(s.uid, s.patchInfo + 0, s.encrypted, noon)).status)
        assertEquals(ScanStatus.BAD_DATA, ScanProcessor.process(RawScan(s.uid.copyOf(7), s.patchInfo, s.encrypted, noon)).status)
        assertEquals(ScanStatus.BAD_DATA, ScanProcessor.process(RawScan(s.uid, s.patchInfo, s.encrypted.copyOf(343), noon)).status)
        assertEquals(ScanStatus.BAD_DATA, ScanProcessor.process(RawScan(s.uid, s.patchInfo, s.encrypted.copyOf(360), noon)).status)
        assertEquals(ScanStatus.BAD_DATA, ScanProcessor.process(RawScan(s.uid, s.patchInfo, ByteArray(0), noon)).status)
    }

    @Test
    fun aCalibrationRowThatDoesNotExistIsRefused() {
        // The original app crashes on row 0. This app must refuse the scan.
        val sample = TestSensors.example.changed { TestSensors.setCalibrationRow(it, 0) }
        val result = ScanProcessor.process(sample.rawScan(noon))
        assertEquals(ScanStatus.BAD_DATA, result.status)
        assertFalse(result.checksumFailed)
        assertNull(result.readings)

        // rows 1 and 1023 are the first and the last row of the table
        for (row in listOf(1, 1023)) {
            val ok = TestSensors.example.changed { TestSensors.setCalibrationRow(it, row) }
            assertEquals("row $row", ScanStatus.OK, ScanProcessor.process(ok.rawScan(noon)).status)
        }
    }

    @Test
    fun theTestHelperBuildsValidSensors() {
        // changing nothing gives the real scan back
        val same = TestSensors.example.changed { }
        assertTrue(same.encrypted.contentEquals(TestSensors.example.encrypted))
        // without fixing the checksums a change is noticed
        val broken = TestSensors.example.changed(fixChecksums = false) { TestSensors.setAge(it, 5000) }
        assertEquals(ScanStatus.BAD_DATA, ScanProcessor.process(broken.rawScan(noon)).status)
    }

    @Test
    fun aHistoryIndexThatDoesNotFitOnlyRemovesTheOldValues() {
        val odd = TestSensors.example.changed { TestSensors.setHistoryIndex(it, 9) }
        val result = ScanProcessor.process(odd.rawScan(noon))
        assertEquals(ScanStatus.OK, result.status)
        assertFalse(result.readings!!.historyTimeKnown)
        assertTrue(result.readings!!.history.isEmpty())
        assertEquals(16, result.readings!!.trend.size)
        assertTrue(result.detail.contains("15-minute values skipped"))
    }
}
