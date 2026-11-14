package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AiReportTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val quarter = 15 * minute
    private val dayLength = 24 * hour
    private val zone = TimeZone.getTimeZone("Africa/Mogadishu")

    /** Tuesday 10 November 2026, 0:00 local time (UTC+3). */
    private val midnight = 1_794_258_000_000L

    private fun at(day: Int, hourOfDay: Int, minuteOfHour: Int = 0): Long = midnight + day * dayLength + hourOfDay * hour + minuteOfHour * minute

    private fun quarters(from: Long, to: Long, mgdl: (Int) -> Int): List<Glucose> {
        val list = ArrayList<Glucose>()
        var start = from
        while (start < to) {
            val hourOfDay = (((start - midnight) % dayLength + dayLength) % dayLength / hour).toInt()
            list.add(Glucose(start + 7 * minute, mgdl(hourOfDay), null, false, "A"))
            start += quarter
        }
        return list
    }

    /** A week: every night an hour at 50, every afternoon six hours at 260, the rest at 120. */
    private val week: List<Glucose> = quarters(at(-7, 0), at(0, 0)) { h ->
        when {
            h == 2 -> 50
            h in 12..17 -> 260
            else -> 120
        }
    }

    private fun text(unit: GlucoseUnit = GlucoseUnit.MGDL, language: AnswerLanguage = AnswerLanguage.ENGLISH): String {
        val analysis = AnalysisCalculator.compute(week, at(-7, 0), at(0, 0), 70, 180, zone)
        val hours = AnalysisCalculator.hours(week, at(-1, 0), at(0, 0), zone)
        val days = AnalysisCalculator.days(week, at(-1, 12), 7, at(0, 0), 70, 180, zone)
        return AiReport.build(analysis, hours, days, unit, 70, 180, language, zone)
    }

    @Test
    fun theQuestionComesFirst() {
        val text = text()
        assertTrue(text, text.startsWith("I use a glucose sensor (FreeStyle Libre 2). Below are my glucose data of the last 7 days.\nPlease analyse how my glucose was, in simple words:\n"))
        assertTrue(text.contains("1. How was it in the last hours and on the last days?\n"))
        assertTrue(text.contains("4. What should I ask my doctor or diabetes nurse?\n"))
        assertTrue("the AI is told not to give doses", text.contains("Do not tell me to change insulin doses or medicines. That is for my doctor to decide.\n"))
        assertTrue(text.contains("They can differ a little from the FreeStyle LibreLink app, and no doctor has checked them. All glucose values are in mg/dL.\n\n"))
        assertFalse(text.contains("Please answer in"))
        assertTrue(text.endsWith(".\n"))
        assertFalse(text.endsWith("\n\n"))
    }

    @Test
    fun theLanguageOfTheAnswer() {
        assertTrue(text(language = AnswerLanguage.SOMALI).contains("That is for my doctor to decide.\nPlease answer in Somali.\n\nAbout the data:"))
        assertTrue(text(language = AnswerLanguage.ARABIC).contains("Please answer in Arabic.\n"))
        assertEquals(AnswerLanguage.SOMALI, AnswerLanguage.of("so"))
        assertEquals(AnswerLanguage.ARABIC, AnswerLanguage.of("ar"))
        assertEquals(AnswerLanguage.ENGLISH, AnswerLanguage.of("en"))
        assertEquals(AnswerLanguage.ENGLISH, AnswerLanguage.of(null))
        assertEquals(AnswerLanguage.ENGLISH, AnswerLanguage.of("xx"))
    }

    @Test
    fun theNumbersOfTheWeek() {
        val text = text()
        assertTrue(text, text.contains("PERIOD\nTue 3 Nov 2026, 00:00 to Mon 9 Nov 2026, 23:59. Values for 100 % of this time.\n\n"))
        assertTrue(
            text,
            text.contains(
                "SUMMARY\n" +
                    "Average glucose: 152\n" +
                    "Estimated A1c (GMI): 6.9 % (not reliable: it needs 14 days with values for most of the time)\n" +
                    "Variation (CV): 42 %\n" +
                    "Lowest and highest value: 50 and 260\n\n"
            )
        )
        assertTrue(
            text,
            text.contains(
                "TIME IN RANGES\n" +
                    "Very high (over 250): 25 %\n" +
                    "High (over 180, with very high): 25 %\n" +
                    "In range (70 to 180): 71 %\n" +
                    "Low (under 70, with very low): 4.2 %\n" +
                    "Very low (under 54): 4.2 %\n" +
                    "My own target range is 70 to 180: 71 % of the time inside.\n\n"
            )
        )
        assertTrue(
            text,
            text.contains(
                "BY TIME OF DAY (average; time under 70; time over 180; low events that started there)\n" +
                    "Night (0 to 6 o'clock): 108; 17 %; 0 %; 7\n" +
                    "Morning (6 to 12 o'clock): 120; 0 %; 0 %; 0\n" +
                    "Afternoon (12 to 18 o'clock): 260; 0 %; 100 %; 0\n" +
                    "Evening (18 to 24 o'clock): 120; 0 %; 0 %; 0\n\n"
            )
        )
        assertTrue(
            text,
            text.contains(
                "LOW EVENTS (under 70 for 15 minutes or longer): 7\n" +
                    "- Mon 9 Nov 2026, 02:00, 60 minutes, lowest 50\n" +
                    "- Sun 8 Nov 2026, 02:00, 60 minutes, lowest 50\n"
            )
        )
        assertTrue(text.contains("- Tue 3 Nov 2026, 02:00, 60 minutes, lowest 50\nHIGH EVENTS (over 180): 7, mean length 360 minutes\n\n"))
        assertTrue(
            text,
            text.contains(
                "DAY BY DAY, newest first (average; in range; lowest to highest; low events; high events)\n" +
                    "Mon 9 Nov: 152; 71 %; 50 to 260; 1; 1\n" +
                    "Sun 8 Nov: 152; 71 %; 50 to 260; 1; 1\n"
            )
        )
        assertTrue(
            text,
            text.contains(
                "THE LAST 24 HOURS, HOUR BY HOUR (average; lowest to highest)\n" +
                    "Mon 00:00: 120; 120 to 120\n" +
                    "Mon 01:00: 120; 120 to 120\n" +
                    "Mon 02:00: 50; 50 to 50\n"
            )
        )
        assertTrue(text.contains("Mon 12:00: 260; 260 to 260\n"))
        assertTrue(
            text,
            text.contains(
                "TYPICAL DAY (middle value of each hour, from 0 to 23 o'clock; \"-\" = too few values)\n" +
                    "120 120 50 120 120 120 120 120 120 120 120 120 260 260 260 260 260 260 120 120 120 120 120 120\n\n"
            )
        )
        assertTrue(text, text.endsWith("EARLY MORNING (7 nights)\nFrom about 2:30 to 5:30 in the morning the glucose rises by 70 on average.\n"))
    }

    @Test
    fun inTheOtherUnit() {
        val text = text(unit = GlucoseUnit.MMOLL)
        assertTrue(text.contains("All glucose values are in mmol/L.\n"))
        assertTrue(text, text.contains("Average glucose: 8.4\n"))
        assertTrue(text.contains("Lowest and highest value: 2.8 and 14.4\n"))
        assertTrue(text.contains("Very high (over 13.9): 25 %\n"))
        assertTrue(text.contains("In range (3.9 to 10.0): 71 %\n"))
        assertTrue(text.contains("Very low (under 3.0): 4.2 %\n"))
        assertTrue(text.contains("- Mon 9 Nov 2026, 02:00, 60 minutes, lowest 2.8\n"))
        assertTrue(text.contains("the glucose rises by 3.9 on average.\n"))
        assertFalse("no number without its unit named at the top", text.contains("mg/dL"))
    }

    @Test
    fun aPeriodWithoutValues() {
        val analysis = AnalysisCalculator.compute(emptyList(), at(-1, 0), at(0, 0), 70, 180, zone)
        val text = AiReport.build(analysis, emptyList(), emptyList(), GlucoseUnit.MGDL, 70, 180, AnswerLanguage.ENGLISH, zone)
        assertTrue(text.contains("Below are my glucose data of the last 24 hours.\n"))
        assertTrue(text, text.endsWith("PERIOD\nMon 9 Nov 2026, 00:00 to Mon 9 Nov 2026, 23:59. Values for 0 % of this time.\n\nThere are no values in this period.\n"))
    }

    @Test
    fun onlyTheNewestTwentyLowEventsAreListed() {
        // 30 days with a low hour every night
        val month = quarters(at(-30, 0), at(0, 0)) { h -> if (h == 3) 60 else 110 }
        val analysis = AnalysisCalculator.compute(month, at(-30, 0), at(0, 0), 70, 180, zone)
        val text = AiReport.build(analysis, emptyList(), emptyList(), GlucoseUnit.MGDL, 70, 180, AnswerLanguage.ENGLISH, zone)
        assertTrue(text.contains("Below are my glucose data of the last 30 days.\n"))
        assertTrue(text.contains("LOW EVENTS (under 70 for 15 minutes or longer): 30\n- Mon 9 Nov 2026, 03:00, 60 minutes, lowest 60\n"))
        assertEquals(20, text.split("\n").count { it.startsWith("- ") })
        assertTrue(text.contains("- Wed 21 Oct 2026, 03:00, 60 minutes, lowest 60\nHIGH EVENTS (over 180): 0\n\n"))
        assertFalse("without the lists of days and hours their headings are left out", text.contains("DAY BY DAY") || text.contains("HOUR BY HOUR"))
        assertTrue("30 days with values are enough for the estimate", text.contains("Estimated A1c (GMI): 5.9 %\n"))
        assertTrue(text, text.endsWith("EARLY MORNING (30 nights)\nFrom about 2:30 to 5:30 in the morning the glucose stays about the same on average.\n"))
    }

    @Test
    fun aDayWithFewValuesSaysSo() {
        val someHours = quarters(at(-1, 8), at(-1, 14)) { 130 }
        val analysis = AnalysisCalculator.compute(someHours, at(-1, 0), at(0, 0), 70, 180, zone)
        val days = AnalysisCalculator.days(someHours, at(-1, 12), 2, at(0, 0), 70, 180, zone)
        val hours = AnalysisCalculator.hours(someHours, at(-1, 0), at(0, 0), zone)
        val text = AiReport.build(analysis, hours, days, GlucoseUnit.MGDL, 70, 180, AnswerLanguage.ENGLISH, zone)
        assertTrue(text, text.contains("Values for 25 % of this time.\n"))
        assertTrue(text, text.contains("DAY BY DAY, newest first (average; in range; lowest to highest; low events; high events)\nMon 9 Nov: 130; 100 %; 130 to 130; 0; 0 (values for only 25 % of the day)\n\n"))
        assertTrue(text.contains("Night (0 to 6 o'clock): no values\n"))
        assertTrue(text.contains("THE LAST 24 HOURS, HOUR BY HOUR (average; lowest to highest)\nMon 08:00: 130; 130 to 130\n"))
        assertEquals("only hours with values are listed", 6, text.split("\n").count { it.startsWith("Mon ") && it.contains(":00: ") })
        assertTrue(text.contains("\n- - - - - - - - 130 130 130 130 130 130 - - - - - - - - - -\n"))
    }
}
