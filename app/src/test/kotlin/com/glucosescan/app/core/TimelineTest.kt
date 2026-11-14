package com.glucosescan.app.core

import com.glucosescan.app.libre.FactoryCalibration
import com.glucosescan.app.libre.FramParser
import com.glucosescan.app.testing.TestSensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private val minute = 60_000L

    /** 2026-01-01 12:00:00 UTC */
    private val noon = 1_767_268_800_000L

    private fun records(plain: ByteArray) = FramParser.records(plain, FactoryCalibration.libreStyleCalibration(plain))

    @Test
    fun timeIsCutToTheMinute() {
        assertEquals(noon, Timeline.floorToMinute(noon))
        assertEquals(noon, Timeline.floorToMinute(noon + 59_999))
        assertEquals(noon + minute, Timeline.floorToMinute(noon + minute))
        assertEquals(-minute, Timeline.floorToMinute(-1))
    }

    @Test
    fun newestHistoryMinuteOfTheRealScans() {
        // Example: 10320 minutes old, 687 values stored (687 = 21 x 32 + 15)
        assertEquals(10305, Timeline.newestHistoryMinute(10320, 15))
        // Example2: 3170 minutes old, 211 values stored (211 = 6 x 32 + 19)
        assertEquals(3165, Timeline.newestHistoryMinute(3170, 19))
    }

    @Test
    fun newestHistoryMinuteAroundTheStoringMinute() {
        val n = 100 // value number 100 belongs to minute 1500 and is stored at minute 1503
        val index = n % 32
        val before = (n - 1) % 32

        // normal: stored from minute 1503 on
        for (age in 1503..1517) {
            assertEquals("age $age", 1500, Timeline.newestHistoryMinute(age, index))
        }
        // before minute 1503 the value before is the newest
        for (age in 1488..1502) {
            assertEquals("age $age", 1485, Timeline.newestHistoryMinute(age, before))
        }
        // early: already stored in minute 1500, 1501 or 1502
        for (age in 1500..1502) {
            assertEquals("age $age", 1500, Timeline.newestHistoryMinute(age, index))
        }
        // but never a value from the future
        assertEquals(-1, Timeline.newestHistoryMinute(1499, index))
        // late: not stored yet in minute 1503 or 1504
        assertEquals(1485, Timeline.newestHistoryMinute(1503, before))
        assertEquals(1485, Timeline.newestHistoryMinute(1504, before))
        // later than that it does not fit any more
        assertEquals(-1, Timeline.newestHistoryMinute(1505, before))
        assertEquals(-1, Timeline.newestHistoryMinute(1510, before))
    }

    @Test
    fun newestHistoryMinuteIsUnknownIfNothingFits() {
        for (index in 0 until 32) {
            val result = Timeline.newestHistoryMinute(3170, index)
            if (index == 19) assertEquals(3165, result) else assertEquals("index $index", -1, result)
        }
        assertEquals(-1, Timeline.newestHistoryMinute(3170, 32))
        assertEquals(-1, Timeline.newestHistoryMinute(3170, 255))
        assertEquals(-1, Timeline.newestHistoryMinute(3170, -1))
        assertEquals(-1, Timeline.newestHistoryMinute(2, 0))
        assertEquals("no value is stored before minute 18", -1, Timeline.newestHistoryMinute(10, 0))
    }

    @Test
    fun realScanExample() {
        val r = records(TestSensors.example.plain)
        assertEquals(10320, r.age)
        assertEquals(15, r.trendIndex)
        assertEquals(15, r.historyIndex)

        val result = Timeline.readings(r, noon + 31_000)
        assertTrue(result.historyTimeKnown)

        // 16 one-minute values: 11:45 ... 12:00
        assertEquals(16, result.trend.size)
        for (k in 0 until 16) {
            assertEquals(noon - (15 - k) * minute, result.trend[k].timestamp)
            assertTrue(result.trend[k].fromTrend)
            assertTrue(result.trend[k].isOk)
            // oldest first: entry k is record number 15 - k
            assertEquals(r.trend[15 - k].glucose, result.trend[k].glucoseValue, 0.0)
        }
        assertEquals(99, Glucose.toInteger(result.trend.last().glucoseValue)!!.toInt())

        // 15-minute values. The newest one is from sensor minute 10305 = 11:45, which the
        // one-minute values cover already. So the newest one that is kept is from 11:30,
        // and it is record number 1.
        assertEquals(31, result.history.size)
        for (k in 0 until 31) {
            val recordNumber = 31 - k
            assertEquals(noon - 15 * minute - recordNumber * 15 * minute, result.history[k].timestamp)
            assertFalse(result.history[k].fromTrend)
            assertEquals(r.history[recordNumber].glucose, result.history[k].glucoseValue, 0.0)
        }
        assertEquals(noon - 30 * minute, result.history.last().timestamp)
        assertEquals(noon - 480 * minute, result.history.first().timestamp)

        // everything in time order, no time twice
        val all = result.all
        assertEquals(47, all.size)
        for (k in 1 until all.size) {
            assertTrue(all[k - 1].timestamp < all[k].timestamp)
        }
    }

    @Test
    fun realScanExample2() {
        val r = records(TestSensors.example2.plain)
        assertEquals(3170, r.age)
        assertEquals(2, r.trendIndex)
        assertEquals(19, r.historyIndex)

        val result = Timeline.readings(r, noon)
        assertEquals(16, result.trend.size)
        assertEquals(95, Glucose.toInteger(result.trend.last().glucoseValue)!!.toInt())

        // newest 15-minute value: sensor minute 3165 = 5 minutes before the scan (covered by
        // the one-minute values). The next: minute 3150 = 20 minutes before the scan.
        assertEquals(31, result.history.size)
        assertEquals(noon - 20 * minute, result.history.last().timestamp)
        assertEquals(r.history[1].glucose, result.history.last().glucoseValue, 0.0)
        assertEquals(noon - (20 + 30 * 15) * minute, result.history.first().timestamp)
        assertEquals(r.history[31].glucose, result.history.first().glucoseValue, 0.0)
    }

    @Test
    fun aFifteenMinuteValueKeepsItsTimeFromScanToScan() {
        // The same measurement must get the same time, whenever the sensor is scanned.
        // Sensor minute 3150 of Example2 was 20 minutes before a scan at minute 3170.
        // Scan again 12 minutes later (sensor minute 3182, still 211 values stored).
        val later = TestSensors.example2.changed { TestSensors.setAge(it, 3182) }
        val first = Timeline.readings(records(TestSensors.example2.plain), noon)
        val second = Timeline.readings(records(later.plain), noon + 12 * minute)

        val firstTimes = first.history.associate { it.timestamp to it.glucoseValue }
        // In the second scan the value of minute 3165 is older than the one-minute values now.
        assertEquals(32, second.history.size)
        assertEquals(noon - 5 * minute, second.history.last().timestamp)
        for (reading in second.history.dropLast(1)) {
            assertEquals("same time, same value", firstTimes.getValue(reading.timestamp), reading.glucoseValue, 0.0)
        }
    }

    @Test
    fun historyIsSkippedIfItsTimeIsNotSure() {
        val odd = TestSensors.example.changed { TestSensors.setHistoryIndex(it, 9) }
        val result = Timeline.readings(records(odd.plain), noon)
        assertFalse(result.historyTimeKnown)
        assertTrue(result.history.isEmpty())
        assertEquals(16, result.trend.size)
    }

    @Test
    fun valuesFromTheFirstHourAreNotUsed() {
        // 70 minutes old: 4 values stored ((70 - 3) / 15), the newest is from minute 60
        val young = TestSensors.example.changed {
            TestSensors.setAge(it, 70)
            TestSensors.setHistoryIndex(it, 4)
        }
        val result = Timeline.readings(records(young.plain), noon)
        assertTrue(result.historyTimeKnown)
        assertTrue("minutes 15, 30, 45 and 60 are all in the first hour", result.history.isEmpty())
        // one-minute values of the sensor minutes 61..70 only
        assertEquals(10, result.trend.size)
        assertEquals(noon - 9 * minute, result.trend.first().timestamp)
        assertEquals(noon, result.trend.last().timestamp)

        // 80 minutes old: 5 values stored, the newest is from minute 75
        val older = TestSensors.example.changed {
            TestSensors.setAge(it, 80)
            TestSensors.setHistoryIndex(it, 5)
        }
        val result2 = Timeline.readings(records(older.plain), noon)
        assertEquals(16, result2.trend.size)
        assertEquals("minute 75 is covered by the one-minute values, minute 60 is in the first hour", 0, result2.history.size)

        // 95 minutes old: 6 values stored, the newest is from minute 90, then 75 (used), 60 (not used)
        val oldEnough = TestSensors.example.changed {
            TestSensors.setAge(it, 95)
            TestSensors.setHistoryIndex(it, 6)
        }
        val result3 = Timeline.readings(records(oldEnough.plain), noon)
        assertEquals(1, result3.history.size)
        assertEquals(noon - 20 * minute, result3.history[0].timestamp)
    }

    @Test
    fun aRecordWithRawValueZeroIsAlwaysFaulty() {
        val broken = TestSensors.example.changed { TestSensors.setNewestTrendRaw(it, 0) }
        val r = records(broken.plain)
        assertEquals(0, r.trend[0].rawGlucose)
        val result = Timeline.readings(r, noon)
        assertFalse(result.trend.last().isOk)
        assertTrue(result.trend[result.trend.size - 2].isOk)

        // also if the sensor stored no error bits with it (the original app takes such a record as good)
        val empty = TestSensors.example.changed { TestSensors.clearNewestTrendRecord(it) }
        val r2 = records(empty.plain)
        assertEquals(0, r2.trend[0].rawGlucose)
        assertEquals(0, r2.trend[0].errorBits)
        val result2 = Timeline.readings(r2, noon)
        assertFalse(result2.trend.last().isOk)
        assertEquals(Timeline.ERROR_INVALID_DATA, result2.trend.last().errorBits)
    }
}
