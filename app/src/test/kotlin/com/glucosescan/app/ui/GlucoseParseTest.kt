package com.glucosescan.app.ui

import com.glucosescan.app.core.GlucoseUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading the number that the user typed. */
class GlucoseParseTest {
    @Test
    fun mgdl() {
        assertEquals(112, GlucoseFormat.parse("112", GlucoseUnit.MGDL))
        assertEquals(112, GlucoseFormat.parse("  112 ", GlucoseUnit.MGDL))
        assertEquals(40, GlucoseFormat.parse("040", GlucoseUnit.MGDL))
        // somebody types mmol/L by mistake: the number is far too small and gets refused later
        assertEquals(6, GlucoseFormat.parse("5.6", GlucoseUnit.MGDL))
        assertEquals(113, GlucoseFormat.parse("112,5", GlucoseUnit.MGDL))
    }

    @Test
    fun mmol() {
        assertEquals(112, GlucoseFormat.parse("6.2", GlucoseUnit.MMOLL))
        assertEquals(112, GlucoseFormat.parse("6,2", GlucoseUnit.MMOLL))
        assertEquals(108, GlucoseFormat.parse("6", GlucoseUnit.MMOLL))
        assertEquals(108, GlucoseFormat.parse("6.", GlucoseUnit.MMOLL))
        assertEquals(9, GlucoseFormat.parse(".5", GlucoseUnit.MMOLL))
        // somebody types mg/dL by mistake: the number is far too big and gets refused later
        assertEquals(2018, GlucoseFormat.parse("112", GlucoseUnit.MMOLL))
    }

    @Test
    fun whatTheUserTypedInMmolIsWhatTheAppShowsAgain() {
        for (tenths in 22..278) {
            val typed = (tenths / 10).toString() + "." + (tenths % 10).toString()
            val mgdl = GlucoseFormat.parse(typed, GlucoseUnit.MMOLL)!!
            assertEquals(typed, GlucoseFormat.value(mgdl, GlucoseUnit.MMOLL))
            assertEquals("$typed is a number the app accepts", true, mgdl in 39..501)
        }
    }

    @Test
    fun otherDigitsAreRead() {
        // Arabic digits, as some keyboards give them
        assertEquals(112, GlucoseFormat.parse("\u0661\u0661\u0662", GlucoseUnit.MGDL))
    }

    @Test
    fun whatIsNotANumber() {
        for (text in listOf("", " ", ".", ",", "abc", "1 2", "1.2.3", "-5", "+5", "1e3", "12345678", "5 mmol")) {
            assertNull(text, GlucoseFormat.parse(text, GlucoseUnit.MGDL))
            assertNull(text, GlucoseFormat.parse(text, GlucoseUnit.MMOLL))
        }
    }
}
