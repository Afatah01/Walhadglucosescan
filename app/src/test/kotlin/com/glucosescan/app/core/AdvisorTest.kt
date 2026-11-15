package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The words the app makes for itself about a period: that they are there from the first
 * value onwards (no key, no filling in), that they name the numbers that matter, and that
 * no sentence tells somebody to take a dose.
 */
class AdvisorTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val quarter = 15 * minute
    private val dayLength = 24 * hour
    private val zone = TimeZone.getTimeZone("Africa/Mogadishu")

    /** Tuesday 10 November 2026, 0:00 local time (UTC+3). */
    private val midnight = 1_794_258_000_000L

    private fun at(day: Int, hourOfDay: Int, minuteOfHour: Int = 0): Long =
        midnight + day * dayLength + hourOfDay * hour + minuteOfHour * minute

    private fun value(time: Long, mgdl: Int): Glucose = Glucose(time, mgdl, null, false, "A")

    /** One value in the middle of each quarter hour from [from] to [to]. [mgdl] gets the hour of the day; null leaves a hole. */
    private fun quarters(from: Long, to: Long, mgdl: (Int) -> Int?): List<Glucose> {
        val list = ArrayList<Glucose>()
        var start = from
        while (start < to) {
            val hourOfDay = (((start - midnight) % dayLength + dayLength) % dayLength / hour).toInt()
            mgdl(hourOfDay)?.let { list.add(value(start + 7 * minute, it)) }
            start += quarter
        }
        return list
    }

    private fun facts(values: List<Glucose>, from: Long, to: Long): AdvisorFacts =
        Advisor.facts(values, from, to, 70, 180, GlucoseUnit.MGDL, zone)

    private fun blocks(values: List<Glucose>, from: Long, to: Long, language: AnswerLanguage = AnswerLanguage.ENGLISH): List<AdvisorBlock> =
        Advisor.tell(facts(values, from, to), language)

    private fun block(blocks: List<AdvisorBlock>, title: String): AdvisorBlock = blocks.first { it.title == title }

    private fun say(blocks: List<AdvisorBlock>): String = blocks.joinToString("\n") { b -> b.title + "\n" + b.lines.joinToString("\n") }

    // ------------------------------------------------------------------ no setup at all

    @Test
    fun itSpeaksWithoutAnySetup() {
        // no key, no service, nothing filled in: the app has something to say anyway
        val values = quarters(at(0, 0), at(3, 0)) { 120 }
        val blocks = blocks(values, at(0, 0), at(3, 0))
        assertTrue("it says more than one thing", blocks.size > 10)
        assertTrue(everyLineIsWritten(blocks))
    }

    @Test
    fun withoutValuesItSaysWhatToDo() {
        val blocks = blocks(emptyList(), at(0, 0), at(1, 0))
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].lines[0].startsWith("There are no values"))
    }

    // ------------------------------------------------------------------ the numbers it names

    @Test
    fun itSaysHowThePeriodWasInOneLine() {
        val values = quarters(at(0, 0), at(2, 0)) { 110 }
        val line = block(blocks(values, at(0, 0), at(2, 0)), "In one line").lines[0]
        assertTrue(line, line.contains("110 mg/dL"))
        assertTrue(line, line.startsWith("Steady"))
    }

    @Test
    fun itNamesTheAverageTheLowestAndTheHighest() {
        val values = quarters(at(0, 0), at(2, 0)) { hour -> if (hour == 8) 220 else 100 }
        val lines = block(blocks(values, at(0, 0), at(2, 0)), "The average").lines
        assertTrue(lines.joinToString(" "), lines[0].contains("105 mg/dL"))
        assertTrue(lines[1].contains("100 mg/dL"))
        assertTrue(lines[1].contains("220 mg/dL"))
        assertTrue(lines[1].contains("120 mg/dL")) // the widest swing
    }

    @Test
    fun theEstimatedHbA1cComesOnlyWithEnoughDays() {
        val long = quarters(at(0, 0), at(14, 0)) { 150 }
        val longLine = block(blocks(long, at(0, 0), at(14, 0)), "The average").lines.last()
        assertTrue(longLine, longLine.contains("HbA1c of"))

        val short = quarters(at(0, 0), at(3, 0)) { 150 }
        val shortLine = block(blocks(short, at(0, 0), at(3, 0)), "The average").lines.last()
        assertTrue(shortLine, shortLine.startsWith("Too short"))
    }

    @Test
    fun itCountsTheTimeInRangeAndTheTightRange() {
        val values = quarters(at(0, 0), at(2, 0)) { hour -> if (hour in 12..15) 220 else 110 }
        val lines = block(blocks(values, at(0, 0), at(2, 0)), "Time in range").lines
        assertTrue(lines[0], lines[0].contains("83 %"))
        assertTrue(lines[1], lines[1].contains("17 %")) // over 180
        assertTrue(lines[2], lines[2].contains("83 %")) // and the tight range is the same here
    }

    @Test
    fun itSaysWhenTheValuesAreUnsteady() {
        val values = quarters(at(0, 0), at(2, 0)) { hour -> if (hour % 2 == 0) 240 else 60 }
        val lines = block(blocks(values, at(0, 0), at(2, 0)), "How steady it was").lines
        assertTrue(lines.joinToString(" "), lines[1].contains("unsteady"))

        val calm = quarters(at(0, 0), at(2, 0)) { 115 }
        val calmLine = block(blocks(calm, at(0, 0), at(2, 0)), "How steady it was").lines[1]
        assertTrue(calmLine, calmLine.contains("steady"))
    }

    @Test
    fun itNamesTheCalmestAndTheHighestHour() {
        val values = quarters(at(0, 0), at(2, 0)) { hour ->
            when (hour) {
                3 -> 90
                20 -> 230
                else -> 130
            }
        }
        val lines = block(blocks(values, at(0, 0), at(2, 0)), "Hour by hour").lines
        assertTrue(lines.joinToString(" "), lines.any { it.contains("03:00") && it.contains("90 mg/dL") })
        assertTrue(lines.joinToString(" "), lines.any { it.contains("20:00") && it.contains("230 mg/dL") })
    }

    @Test
    fun itCountsLowsAndSaysWhenTheyComeAtNight() {
        val values = quarters(at(0, 0), at(2, 0)) { hour -> if (hour in 0..2) 60 else 120 }
        val lines = block(blocks(values, at(0, 0), at(2, 0)), "Lows").lines
        assertTrue(lines.joinToString(" "), lines.any { it.contains("2 low episodes") })
        assertTrue(lines.joinToString(" "), lines.any { it.startsWith("At night") && it.contains("2 low") })
        assertTrue(lines.joinToString(" "), lines.any { it.contains("most important thing to tell your doctor") })
        assertTrue(lines.joinToString(" "), lines.any { it.contains("3 h") }) // how long the longest low lasted
        assertTrue(block(blocks(values, at(0, 0), at(2, 0)), "What to tell your doctor").lines.any { it.contains("low at night") })
    }

    @Test
    fun itSaysWhenThereWasNoLow() {
        val values = quarters(at(0, 0), at(2, 0)) { 120 }
        val lines = block(blocks(values, at(0, 0), at(2, 0)), "Lows").lines
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("No low"))
    }

    @Test
    fun itCountsHighsAndFastRises() {
        val values = quarters(at(0, 0), at(2, 0)) { hour ->
            when {
                hour == 7 -> 90
                hour in 8..11 -> 220
                else -> 110
            }
        }
        val lines = block(blocks(values, at(0, 0), at(2, 0)), "Highs").lines
        assertTrue(lines.joinToString(" "), lines.any { it.contains("2 high episodes") })
        assertTrue(lines.joinToString(" "), lines.any { it.contains("fast") && it.contains("07:00") })
    }

    @Test
    fun itComparesThePeriodWithTheOneBefore() {
        val before = quarters(at(-2, 0), at(0, 0)) { 200 }
        val now = quarters(at(0, 0), at(2, 0)) { 110 }
        val lines = block(blocks(before + now, at(0, 0), at(2, 0)), "Compared with the days before").lines
        assertTrue(lines.joinToString(" "), lines[0].contains("200 mg/dL"))
        assertTrue(lines.joinToString(" "), lines[1].contains("came down"))
    }

    @Test
    fun itNamesEveryGoalAndWhetherItWasReached() {
        val values = quarters(at(0, 0), at(2, 0)) { 120 }
        val lines = block(blocks(values, at(0, 0), at(2, 0)), "The goals").lines
        assertTrue(lines.joinToString(" "), lines.any { it.startsWith("Time in range") && it.contains("Reached.") })
        assertTrue(lines.joinToString(" "), lines.any { it.startsWith("Time under 70") && it.contains("Reached.") })
        assertTrue(lines.joinToString(" "), lines.size >= 5)
    }

    @Test
    fun itNamesTheFourPartsOfTheDay() {
        val values = quarters(at(0, 0), at(2, 0)) { 120 }
        val lines = block(blocks(values, at(0, 0), at(2, 0)), "The parts of the day").lines
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("Night"))
        assertTrue(lines[3].startsWith("Evening"))
    }

    // ------------------------------------------------------------------ the three languages

    @Test
    fun allThreeLanguagesSayTheSameNumberOfThings() {
        val values = quarters(at(0, 0), at(14, 0)) { hour ->
            when {
                hour in 0..2 -> 62
                hour == 8 -> 210
                hour in 12..15 -> 195
                else -> 120
            }
        }
        val english = blocks(values, at(0, 0), at(14, 0), AnswerLanguage.ENGLISH)
        val somali = blocks(values, at(0, 0), at(14, 0), AnswerLanguage.SOMALI)
        val arabic = blocks(values, at(0, 0), at(14, 0), AnswerLanguage.ARABIC)
        assertTrue("the Somali text is as long as the English one", somali.size == english.size)
        assertTrue("the Arabic text is as long as the English one", arabic.size == english.size)
        for (blocks in listOf(english, somali, arabic)) {
            assertTrue(everyLineIsWritten(blocks))
            for (one in blocks) {
                assertEquals("each block says something", true, one.lines.isNotEmpty())
                for (line in one.lines) {
                    assertFalse("no pattern is left in the text: $line", line.contains("%1") || line.contains("%2") || line.contains("%3") || line.contains("%4") || line.contains("%5"))
                    assertFalse("no empty number: $line", line.contains("null"))
                    assertFalse("no doubled word where a number should be: $line", line.contains("  "))
                }
            }
        }
    }

    @Test
    fun nothingInTheTextGivesADose() {
        val values = quarters(at(0, 0), at(14, 0)) { hour -> if (hour in 0..2) 60 else 190 }
        for (language in AnswerLanguage.values()) {
            val text = say(blocks(values, at(0, 0), at(14, 0), language))
            val dose = Regex("""\b(take|give|inject|use)\b[^.\n]{0,20}\b(units?|iu|insulin|mg)\b""", RegexOption.IGNORE_CASE)
            assertTrue("$language: $text", dose.find(text) == null)
            assertTrue("$language ends with the careful words", text.contains("not a medical device") || text.contains("ma aha qalab caafimaad") || text.contains("ليس جهازًا طبيًا"))
        }
    }

    private fun everyLineIsWritten(blocks: List<AdvisorBlock>): Boolean =
        blocks.all { one -> one.title.isNotBlank() && one.lines.all { it.isNotBlank() } }
}
