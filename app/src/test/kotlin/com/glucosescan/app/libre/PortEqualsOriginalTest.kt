package com.glucosescan.app.libre

import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.Trend
import com.glucosescan.app.reference.ReferenceVectors
import com.glucosescan.app.reference.ReferenceVectors.double
import com.glucosescan.app.reference.ReferenceVectors.hex
import com.glucosescan.app.reference.ReferenceVectors.same
import com.glucosescan.app.reference.ReferenceVectors.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every test here gives the Kotlin code the same input that the ORIGINAL Swift code got,
 * and requires the same output.
 */
class PortEqualsOriginalTest {

    @Test
    fun processCrypto() {
        val records = ReferenceVectors.of("CRYPTO")
        assertTrue(records.size >= 400)
        for (r in records) {
            val input = r[0].split(',').map { it.toInt(16) }.toIntArray()
            val expected = r[1].split(',').map { it.toInt(16) }.toIntArray()
            assertArrayEquals("input ${r[0]}", expected, Libre2Crypto.processCrypto(input))
        }
    }

    @Test
    fun crc16() {
        val records = ReferenceVectors.of("CRC")
        assertTrue(records.size >= 200)
        for (r in records) {
            assertEquals("data ${r[0]}", r[1].toInt(16), Crc16.compute(hex(r[0])))
        }
    }

    @Test
    fun readBits() {
        val records = ReferenceVectors.of("BITS")
        assertTrue(records.size >= 600)
        for (r in records) {
            val value = readBits(hex(r[0]), r[1].toInt(), r[2].toInt(), r[3].toInt())
            assertEquals("${r[0]} ${r[1]} ${r[2]} ${r[3]}", r[4].toInt(), value)
        }
    }

    @Test
    fun sensorTypeFamilyRegion() {
        val records = ReferenceVectors.of("TYPE")
        assertTrue(records.size >= 1500)
        for (r in records) {
            val info = hex(r[0])
            assertEquals("type of ${r[0]}", ReferenceVectors.sensorTypes.getValue(r[1]), SensorType.of(info))
            assertEquals("family of ${r[0]}", r[2].toInt(), SensorFamily.of(info).rawValue)
            assertEquals("region of ${r[0]}", ReferenceVectors.sensorRegions.getValue(r[3]), SensorRegion.of(info[3].toInt() and 0xFF))
        }
    }

    @Test
    fun decryptFram() {
        val records = ReferenceVectors.of("DECRYPT")
        assertTrue(records.size >= 320)
        var decoded = 0
        var refused = 0
        for (r in records) {
            val result = Libre2Crypto.decryptFram(hex(r[0]), hex(r[1]), hex(r[2]))
            if (r[3] == "NIL") {
                assertNull("uid ${r[0]} info ${r[1]}", result)
                refused++
            } else {
                assertEquals("uid ${r[0]} info ${r[1]}", r[3], toHex(result!!))
                decoded++
            }
        }
        assertTrue(decoded > 200)
        assertTrue(refused > 10)
    }

    @Test
    fun calibrationNumbersFromMemory() {
        val records = ReferenceVectors.of("CALPARAM")
        assertTrue(records.size >= 300)
        for (r in records) {
            assertCalibration(r, 1, FactoryCalibration.libreStyleCalibration(hex(r[0])))
        }
    }

    @Test
    fun calibrationTables() {
        val tables = ReferenceVectors.of("TABLE").associate { it[0] to it[2].split(',').map { bits -> double(bits) } }
        val t1 = tables.getValue("t1")
        val t2 = tables.getValue("t2")
        assertEquals(1023, t1.size)
        assertEquals(1023, t2.size)
        assertEquals(1023, CALIBRATION_T1.size)
        assertEquals(1023, CALIBRATION_T2.size)
        for (i in 0 until 1023) {
            assertTrue("t1[$i]", same(t1[i], CALIBRATION_T1[i]))
            assertTrue("t2[$i]", same(t2[i], CALIBRATION_T2[i]))
        }
    }

    @Test
    fun calibrate() {
        val records = ReferenceVectors.of("CALIBRATE")
        assertTrue(records.size >= 7000)
        val rowsSeen = HashSet<Int>()
        for (r in records) {
            val calibration = FactoryCalibration(r[0].toInt(), r[1].toInt(), r[2].toDouble(), r[3].toDouble(), r[4].toDouble(), r[5].toDouble())
            rowsSeen.add(calibration.i2)
            val value = calibration.calibrate(double(r[6]), double(r[7]), double(r[8]))
            val expected = double(r[9])
            assertTrue(
                "calibration $calibration raw ${double(r[6])} temp ${double(r[7])} adj ${double(r[8])}: expected $expected (${r[10]}) but was $value",
                same(expected, value)
            )
        }
        assertEquals("every table row was used", 1023, rowsSeen.size)
    }

    @Test
    fun sensorDescription() {
        val records = ReferenceVectors.of("SENSOR")
        assertTrue(records.size >= 300)
        for (r in records) {
            val sensor = SensorInfo.libreStyleSensor(hex(r[0]), hex(r[1]), hex(r[2]))
            val what = "uid ${r[0]} info ${r[1]}"
            assertEquals(what, r[3].toInt(), sensor.family.rawValue)
            assertEquals(what, ReferenceVectors.sensorTypes.getValue(r[4]), sensor.type)
            assertEquals(what, ReferenceVectors.sensorRegions.getValue(r[5]), sensor.region)
            assertEquals(what, r[6], sensor.serial ?: "NIL")
            assertEquals(what, ReferenceVectors.sensorStates.getValue(r[7]), sensor.state)
            assertEquals(what, r[8].toInt(), sensor.age)
            assertEquals(what, r[9].toInt(), sensor.lifetime)
            assertEquals(what, r[10].toInt(), sensor.warmupTime)
            assertEquals(what, r[11], sensor.remainingWarmupTime?.toString() ?: "NIL")
            assertEquals(what, r[12].toInt(), sensor.remainingLifetime)
            assertEquals(what, r[13].toInt(), sensor.elapsedLifetime)
            assertCalibration(r, 14, sensor.factoryCalibration)
        }
    }

    @Test
    fun everyRecordOfTheMemory() {
        val records = ReferenceVectors.of("RECORDS")
        assertTrue(records.size >= 120)
        for (r in records) {
            val fram = hex(r[1])
            val parsed = FramParser.records(fram, FactoryCalibration.libreStyleCalibration(fram))
            assertEquals(r[0], r[2].toInt(), parsed.age)
            assertEquals(r[0], r[3].toInt(), parsed.trendIndex)
            assertEquals(r[0], r[4].toInt(), parsed.historyIndex)
            assertRecords(r[0] + " trend", r[5].removePrefix("T="), parsed.trend)
            assertRecords(r[0] + " history", r[6].removePrefix("H="), parsed.history)
        }
    }

    /**
     * The whole parseFRAM of the original: values, errors and times. The times are put on the
     * records here in the test in the way the original does it. (The app itself uses other time
     * rules for the 15-minute values, see TimelineTest.)
     */
    @Test
    fun parseFramLikeTheOriginal() {
        val records = ReferenceVectors.of("PARSE")
        assertTrue(records.size >= 600)
        var compared = 0
        for (r in records) {
            val pairing = r[1].toLong() * 1000L
            val fram = hex(r[2])
            val calibration = FactoryCalibration.libreStyleCalibration(fram)
            assertCalibration(r, 3, calibration)
            val parsed = FramParser.records(fram, calibration)

            fun floorMinute(t: Long) = t - Math.floorMod(t, 60_000L)
            class Reading(val timestamp: Long, val value: Double, val error: Int)

            fun reading(record: FramRecord, time: Long) =
                Reading(floorMinute(time), if (record.errorBits == 0) record.glucose else 0.0, record.errorBits)

            val age = parsed.age
            val trend = (0..15).map { i -> reading(parsed.trend[i], pairing - i * 60_000L) }.sortedBy { it.timestamp }
            val delay = (age - 3) % 15 + 3
            val history = (0..31).map { i ->
                val id = age - delay - i * 15
                reading(parsed.history[i], if (id > -1) pairing - i * 15 * 60_000L else pairing)
            }.sortedBy { it.timestamp }.filter { it.timestamp < trend.first().timestamp }

            fun check(name: String, expectedText: String, actual: List<Reading>) {
                val expected = if (expectedText.isEmpty()) emptyList() else expectedText.split(';')
                assertEquals("${r[0]} $name count", expected.size, actual.size)
                for (k in expected.indices) {
                    val f = expected[k].split(',')
                    assertEquals("${r[0]} $name[$k] time", f[0].toLong() * 1000L, actual[k].timestamp)
                    assertEquals("${r[0]} $name[$k] error", f[2].toInt(), actual[k].error)
                    assertTrue("${r[0]} $name[$k] value: expected ${double(f[1])} but was ${actual[k].value}", same(double(f[1]), actual[k].value))
                    compared++
                }
            }
            check("trend", r[9].removePrefix("T="), trend)
            check("history", r[10].removePrefix("H="), history)
        }
        assertTrue("many readings were compared", compared > 20_000)
    }

    @Test
    fun wholeNumbersAndLimits() {
        val records = ReferenceVectors.of("READING")
        assertTrue(records.size >= 300)
        for (r in records) {
            val value = double(r[0])
            val whole = Glucose.toInteger(value)
            if (r[1] == "NIL") {
                assertNull("value $value", whole)
                continue
            }
            assertEquals("value $value", r[2].toLong(), whole)
            val glucose = Glucose(0L, whole!!.coerceIn(-9_999L, 9_999L).toInt(), null, true)
            assertEquals("value $value", r[3].toInt(), glucose.readableValue)
            assertEquals("value $value", ReferenceVectors.glucoseTypes.getValue(r[4]), glucose.type)
        }
    }

    @Test
    fun changePerMinuteAndArrow() {
        val records = ReferenceVectors.of("CHANGE")
        assertTrue(records.size >= 400)
        for (r in records) {
            val previous = Glucose(r[0].toLong() * 1000L, r[1].toInt(), null, true)
            val next = Glucose(r[2].toLong() * 1000L, r[3].toInt(), null, true).withChangeFrom(previous)
            val what = "prev ${r[1]} at ${r[0]}, next ${r[3]} at ${r[2]}"
            val change = next.minuteChange!!
            assertTrue("$what: expected ${double(r[4])} but was $change", same(double(r[4]), change))
            assertEquals(what, ReferenceVectors.trends.getValue(r[5]), next.trend)
            assertEquals(what, r[6].toInt(), next.readableValue)
        }

        val slopes = ReferenceVectors.of("SLOPE")
        assertTrue(slopes.size >= 26)
        for (r in slopes) {
            assertEquals("slope ${double(r[0])}", ReferenceVectors.trends.getValue(r[1]), Trend.fromSlope(double(r[0])))
        }
    }

    /** Two real Libre 2 scans: the decoded memory must be right and all three checksums must fit. */
    @Test
    fun realScans() {
        val samples = ReferenceVectors.of("SAMPLE")
        assertEquals(2, samples.size)
        for (r in samples) {
            val plain = Libre2Crypto.decryptFram(hex(r[1]), hex(r[2]), hex(r[3]))!!
            assertEquals(r[0], r[4], toHex(plain))
            assertTrue(r[0] + " header", Crc16.sectionIsValid(plain, 0, 24))
            assertTrue(r[0] + " body", Crc16.sectionIsValid(plain, 24, 320))
            assertTrue(r[0] + " footer", Crc16.sectionIsValid(plain, 320, 344))
        }
        for (r in ReferenceVectors.of("SAMPLECRC")) {
            for (k in 1..3) {
                val (stored, computed) = r[k].split(',')
                assertEquals("${r[0]} section $k: the original code agrees that the checksum fits", stored, computed)
            }
        }
    }

    // ------------------------------------------------------------ Bluetooth of the Libre 2

    @Test
    fun bluetoothUsefulFunction() {
        val records = ReferenceVectors.of("USEFUL")
        assertEquals(300, records.size)
        for (r in records) {
            val actual = Libre2Ble.usefulFunction(hex(r[0]), r[1].toInt(16), r[2].toInt(16))
            assertEquals("uid ${r[0]} x ${r[1]} y ${r[2]}", r[3], toHex(actual))
        }
    }

    /** The NFC command that switches Bluetooth on must be, byte for byte, the one of the original. */
    @Test
    fun bluetoothEnableStreamingCommand() {
        val records = ReferenceVectors.of("NFCCMD")
        assertEquals(200, records.size)
        var with42 = 0
        for (r in records) {
            val unlockCode = r[2].toLong()
            if (unlockCode == 42L) {
                with42++
            }
            assertEquals("command code", "a1", r[3])
            val actual = Libre2Ble.enableStreamingParameters(hex(r[0]), hex(r[1]), unlockCode)
            assertEquals("uid ${r[0]} info ${r[1]} code ${r[2]}", r[4], toHex(actual))
            assertEquals("1 byte sub-command, 4 bytes code, 4 bytes key", 9, actual.size)
            assertEquals(0x1e, actual[0].toInt())
        }
        assertEquals("half of the cases use the code of the app", 100, with42)
    }

    @Test
    fun bluetoothUnlockPayload() {
        val records = ReferenceVectors.of("UNLOCK")
        assertEquals(300, records.size)
        for (r in records) {
            val actual = Libre2Ble.streamingUnlockPayload(hex(r[0]), hex(r[1]), r[2].toLong(), r[3].toInt())
            assertEquals("uid ${r[0]} info ${r[1]} time ${r[2]} count ${r[3]}", r[4], toHex(actual))
        }
    }

    @Test
    fun bluetoothDecrypt() {
        val records = ReferenceVectors.of("BLEDECRYPT")
        assertEquals(500, records.size)
        var good = 0
        var refused = 0
        for (r in records) {
            val actual = Libre2Ble.decrypt(hex(r[0]), hex(r[1]))
            if (r[2] == "ERROR") {
                assertNull("the original refuses ${r[1]}", actual)
                refused++
            } else {
                assertEquals("uid ${r[0]} message ${r[1]}", r[2], actual?.let { toHex(it) })
                good++
            }
        }
        assertEquals(300, good)
        assertEquals(200, refused)
    }

    @Test
    fun bluetoothRecords() {
        val records = ReferenceVectors.of("BLEPARSE")
        assertEquals(300, records.size)
        var faulty = 0
        var historyDropped = 0
        for (r in records) {
            val calibration = FactoryCalibration(r[1].toInt(), r[2].toInt(), r[3].toDouble(), r[4].toDouble(), r[5].toDouble(), r[6].toDouble())
            val actual = Libre2Ble.parse(calibration, hex(r[0]))
            assertEquals("age of ${r[0]}", r[7].toInt(), actual.age)

            fun check(name: String, expectedText: String, list: List<BleReading>) {
                val expected = if (expectedText.isEmpty()) emptyList() else expectedText.split(';')
                assertEquals("$name count of ${r[0]}", expected.size, list.size)
                for (k in expected.indices) {
                    val f = expected[k].split(',')
                    assertEquals("$name[$k] minutes before, ${r[0]}", f[0].toInt(), list[k].minutesBefore)
                    assertEquals("$name[$k] error, ${r[0]}", f[2].toInt(), list[k].errorBits)
                    assertTrue(
                        "$name[$k] value of ${r[0]}: expected ${double(f[1])} but was ${list[k].glucose}",
                        same(double(f[1]), list[k].glucose)
                    )
                    if (list[k].errorBits != 0) {
                        faulty++
                    }
                }
            }
            check("trend", r[8].removePrefix("T="), actual.trend)
            check("history", r[9].removePrefix("H="), actual.history)
            assertEquals(7, actual.trend.size)
            if (actual.history.size < 3) {
                historyDropped++
            }
        }
        assertTrue("faulty measurements are part of the cases: $faulty", faulty > 50)
        assertTrue("cases where a 15-minute value is as old as the oldest one-minute value: $historyDropped", historyDropped > 5)
    }

    private fun assertCalibration(r: List<String>, at: Int, calibration: FactoryCalibration) {
        val what = "calibration of ${r[0].take(24)}"
        assertEquals(what, r[at].toInt(), calibration.i1)
        assertEquals(what, r[at + 1].toInt(), calibration.i2)
        assertEquals(what, r[at + 2].toDouble(), calibration.i3, 0.0)
        assertEquals(what, r[at + 3].toDouble(), calibration.i4, 0.0)
        assertEquals(what, r[at + 4].toDouble(), calibration.i5, 0.0)
        assertEquals(what, r[at + 5].toDouble(), calibration.i6, 0.0)
    }

    private fun assertRecords(what: String, expectedText: String, actual: List<FramRecord>) {
        val expected = expectedText.split(';')
        assertEquals("$what count", expected.size, actual.size)
        for (k in expected.indices) {
            val f = expected[k].split(',')
            assertEquals("$what[$k] raw glucose", f[0].toInt(), actual[k].rawGlucose)
            assertEquals("$what[$k] raw temperature", f[1].toInt(), actual[k].rawTemperature)
            assertEquals("$what[$k] temperature adjustment", f[2].toInt(), actual[k].rawTemperatureAdjustment)
            assertTrue("$what[$k] value: expected ${double(f[3])} but was ${actual[k].glucose}", same(double(f[3]), actual[k].glucose))
        }
    }
}
