package com.glucosescan.app.ui

import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class GlucoseFormatTest {
    @Test
    fun valuesInMgdl() {
        assertEquals("104", GlucoseFormat.value(104, GlucoseUnit.MGDL))
        assertEquals("39", GlucoseFormat.value(39, GlucoseUnit.MGDL))
    }

    @Test
    fun valuesInMmol() {
        // mmol/L = mg/dL x 0.0555, one decimal
        assertEquals("5.8", GlucoseFormat.value(104, GlucoseUnit.MMOLL)) // 5.772
        assertEquals("5.6", GlucoseFormat.value(100, GlucoseUnit.MMOLL)) // 5.55 goes up
        assertEquals("10.0", GlucoseFormat.value(180, GlucoseUnit.MMOLL)) // 9.99
        assertEquals("4.4", GlucoseFormat.value(80, GlucoseUnit.MMOLL)) // 4.44
        assertEquals("3.9", GlucoseFormat.value(70, GlucoseUnit.MMOLL)) // 3.885
        assertEquals("2.2", GlucoseFormat.value(40, GlucoseUnit.MMOLL)) // 2.22
        assertEquals("27.8", GlucoseFormat.value(500, GlucoseUnit.MMOLL)) // 27.75
        assertEquals("3.0", GlucoseFormat.value(54, GlucoseUnit.MMOLL)) // 2.997
    }

    @Test
    fun aValueOutsideTheSensorRangeIsLimited() {
        assertEquals("39", GlucoseFormat.value(Glucose(0, 20, null, true), GlucoseUnit.MGDL))
        assertEquals("501", GlucoseFormat.value(Glucose(0, 700, null, true), GlucoseUnit.MGDL))
    }

    @Test
    fun changes() {
        assertEquals("+1.2", GlucoseFormat.change(1.2, GlucoseUnit.MGDL))
        assertEquals("\u22120.7", GlucoseFormat.change(-0.6667, GlucoseUnit.MGDL))
        assertEquals("0.0", GlucoseFormat.change(0.0, GlucoseUnit.MGDL))
        assertEquals("0.0", GlucoseFormat.change(-0.04, GlucoseUnit.MGDL))
        assertEquals("+0.07", GlucoseFormat.change(1.2, GlucoseUnit.MMOLL)) // 0.0666
        assertEquals("\u22120.11", GlucoseFormat.change(-2.0, GlucoseUnit.MMOLL))
        assertEquals("0.00", GlucoseFormat.change(0.05, GlucoseUnit.MMOLL))
    }

    @Test
    fun durations() {
        assertEquals("0min", GlucoseFormat.duration(0))
        assertEquals("59min", GlucoseFormat.duration(59))
        assertEquals("1h 0min", GlucoseFormat.duration(60))
        assertEquals("23h 59min", GlucoseFormat.duration(1439))
        assertEquals("1d 0h 0min", GlucoseFormat.duration(1440))
        assertEquals("7d 4h 0min", GlucoseFormat.duration(10320))
        assertEquals("0min", GlucoseFormat.duration(-5))
    }
}
