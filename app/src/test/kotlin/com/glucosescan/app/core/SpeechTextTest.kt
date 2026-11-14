package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechTextTest {
    private fun value(mgdl: Int) = Glucose(1_767_268_800_000L, mgdl, 0.0, true, "S")

    @Test
    fun aNormalValue() {
        assertEquals("Glucose 104. Steady.", SpeechText.value(value(104), GlucoseUnit.MGDL, Trend.CONSTANT, below = false, above = false, withTrend = true))
        assertEquals("Glucose 5.8. Steady.", SpeechText.value(value(104), GlucoseUnit.MMOLL, Trend.CONSTANT, below = false, above = false, withTrend = true))
        assertEquals("Glucose 104.", SpeechText.value(value(104), GlucoseUnit.MGDL, Trend.CONSTANT, below = false, above = false, withTrend = false))
        assertEquals("the direction is not known", "Glucose 104.", SpeechText.value(value(104), GlucoseUnit.MGDL, null, below = false, above = false, withTrend = true))
        assertEquals("Glucose 104.", SpeechText.value(value(104), GlucoseUnit.MGDL, Trend.UNKNOWN, below = false, above = false, withTrend = true))
    }

    @Test
    fun outsideTheTargetRange() {
        assertEquals("Glucose 62. Below target. Falling.", SpeechText.value(value(62), GlucoseUnit.MGDL, Trend.FALLING, below = true, above = false, withTrend = true))
        assertEquals("Glucose 262. Above target. Rising fast.", SpeechText.value(value(262), GlucoseUnit.MGDL, Trend.FAST_RISING, below = false, above = true, withTrend = true))
        assertEquals("Glucose 62. Below target.", SpeechText.value(value(62), GlucoseUnit.MGDL, Trend.FALLING, below = true, above = false, withTrend = false))
    }

    @Test
    fun belowAndAboveWhatTheSensorMeasures() {
        assertEquals("Glucose is low. Below 40.", SpeechText.value(value(30), GlucoseUnit.MGDL, Trend.FALLING, below = true, above = false, withTrend = true))
        assertEquals("Glucose is low. Below 2.2.", SpeechText.value(value(30), GlucoseUnit.MMOLL, Trend.FALLING, below = true, above = false, withTrend = true))
        assertEquals("Glucose is high. Above 500.", SpeechText.value(value(600), GlucoseUnit.MGDL, Trend.RISING, below = false, above = true, withTrend = true))
        assertEquals("Glucose is high. Above 27.8.", SpeechText.value(value(600), GlucoseUnit.MMOLL, null, below = false, above = true, withTrend = true))
    }

    @Test
    fun everyDirectionHasItsWords() {
        assertEquals("Rising very fast", SpeechText.trendWord(Trend.RAPIDLY_RISING))
        assertEquals("Rising fast", SpeechText.trendWord(Trend.FAST_RISING))
        assertEquals("Rising", SpeechText.trendWord(Trend.RISING))
        assertEquals("Steady", SpeechText.trendWord(Trend.CONSTANT))
        assertEquals("Falling", SpeechText.trendWord(Trend.FALLING))
        assertEquals("Falling fast", SpeechText.trendWord(Trend.FAST_FALLING))
        assertEquals("Falling very fast", SpeechText.trendWord(Trend.RAPIDLY_FALLING))
        assertNull(SpeechText.trendWord(Trend.UNKNOWN))
    }

    @Test
    fun onlyTheNumber() {
        assertEquals("104", SpeechText.number(value(104), GlucoseUnit.MGDL))
        assertEquals("5.8", SpeechText.number(value(104), GlucoseUnit.MMOLL))
        assertEquals("Low", SpeechText.number(value(20), GlucoseUnit.MGDL))
        assertEquals("High", SpeechText.number(value(700), GlucoseUnit.MMOLL))
    }

    @Test
    fun alarms() {
        assertEquals("Low glucose alarm. 62.", SpeechText.alarm(AlarmKind.LOW, value(62), GlucoseUnit.MGDL))
        assertEquals("Low glucose alarm. 3.4.", SpeechText.alarm(AlarmKind.LOW, value(62), GlucoseUnit.MMOLL))
        assertEquals("High glucose alarm. 280.", SpeechText.alarm(AlarmKind.HIGH, value(280), GlucoseUnit.MGDL))
        assertEquals("High glucose alarm.", SpeechText.alarm(AlarmKind.HIGH, null, GlucoseUnit.MGDL))
        assertEquals("No values from the sensor.", SpeechText.alarm(AlarmKind.NO_DATA, value(100), GlucoseUnit.MGDL))
    }
}
