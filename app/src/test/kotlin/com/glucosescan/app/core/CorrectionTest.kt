package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The correction of the values with the pairs "sensor value, right value" that the user saves. */
class CorrectionTest {
    private val serial = "3MH001HM1LM"
    private val minute = 60_000L

    private fun correction(vararg pairs: Pair<Int, Int>): Correction {
        return Correction(serial, pairs.mapIndexed { i, pair -> ReferencePair(1_000_000L + i * 60 * minute, pair.first, pair.second) })
    }

    private fun glucose(value: Int, sensor: String? = serial, time: Long = 5 * minute): Glucose = Glucose(time, value, -0.5, true, sensor)

    // ------------------------------------------------------------ no pairs

    @Test
    fun withoutPairsNothingIsChanged() {
        val none = Correction.NONE
        assertFalse(none.isActive)
        assertNull(none.sensorSerial)
        for (value in listOf(-20, 0, 38, 39, 104, 501, 700)) {
            assertEquals(value, none.valueFor(value))
        }
        val value = glucose(104)
        assertSame(value, none.apply(value))
        val values = listOf(value)
        assertSame(values, none.applyAll(values))
        assertFalse(none.appliesTo(value))
        // pairs without a sensor are no correction either
        assertFalse(Correction(null, listOf(ReferencePair(0, 104, 112))).isActive)
        assertFalse(Correction("", listOf(ReferencePair(0, 104, 112))).isActive)
        assertEquals("value correction: none", none.describe())
    }

    // ------------------------------------------------------------ one pair

    @Test
    fun onePairMovesTheValues() {
        val one = correction(104 to 112)
        assertTrue(one.isActive)
        assertFalse(one.hasLine)
        assertEquals(1.0, one.slope, 0.0)
        assertEquals(8.0, one.intercept, 1e-9)

        // the pair itself comes out right
        assertEquals(112, one.valueFor(104))
        // above the pair the difference stays the same: it does not grow
        assertEquals(128, one.valueFor(120))
        assertEquals(208, one.valueFor(200))
        assertEquals(508, one.valueFor(500))
        // below the pair the difference gets smaller in step with the value
        assertEquals(56, one.valueFor(52)) // +4
        assertEquals(28, one.valueFor(26)) // +2
        assertEquals(32, one.valueFor(30)) // +2.3
        assertEquals(0, one.valueFor(0))
        assertEquals(-7, one.valueFor(-7))
    }

    @Test
    fun onePairCanAlsoMoveDown() {
        val one = correction(200 to 180)
        assertEquals(180, one.valueFor(200))
        assertEquals(280, one.valueFor(300))
        assertEquals(90, one.valueFor(100)) // -10
        assertEquals(45, one.valueFor(50)) // -5
    }

    @Test
    fun aPairAtAHighValueLiftsALowValueOnlyALittle() {
        // +40 at 250: a low value of 50 gets +8, not +40
        val one = correction(250 to 290)
        assertEquals(290, one.valueFor(250))
        assertEquals(58, one.valueFor(50))
        assertEquals(46, one.valueFor(40))
    }

    @Test
    fun pairsCloseTogetherGiveTheirMeanDifference() {
        // 100 -> 105 and 104 -> 115: sensor values only 4 apart, so no line
        val close = correction(100 to 105, 104 to 115)
        assertFalse(close.hasLine)
        assertEquals(1.0, close.slope, 0.0)
        assertEquals(8.0, close.intercept, 1e-9)
        assertEquals(110, close.valueFor(102))
        assertEquals(308, close.valueFor(300))
        assertEquals(55, close.valueFor(51)) // +4
    }

    // ------------------------------------------------------------ a line

    @Test
    fun twoPairsFarApartGiveALine() {
        val line = correction(60 to 70, 160 to 160)
        assertTrue(line.hasLine)
        assertEquals(0.9, line.slope, 1e-9)
        assertEquals(16.0, line.intercept, 1e-9)

        assertEquals(70, line.valueFor(60))
        assertEquals(160, line.valueFor(160))
        assertEquals(115, line.valueFor(110))
        assertEquals(88, line.valueFor(80))
        // above the highest pair: the difference of the highest pair (here none)
        assertEquals(300, line.valueFor(300))
        assertEquals(480, line.valueFor(480))
        // below the lowest pair: +10 at 60 gets smaller
        assertEquals(53, line.valueFor(45)) // +7.5
        assertEquals(35, line.valueFor(30)) // +5
    }

    @Test
    fun theSlopeIsLimitedLikeInTheOriginal() {
        // steeper than 1.25
        val steep = correction(100 to 100, 200 to 250)
        assertEquals(1.25, steep.slope, 0.0)
        assertEquals(-12.5, steep.intercept, 1e-9)
        assertEquals(113, steep.valueFor(100)) // 112.5
        assertEquals(175, steep.valueFor(150))
        assertEquals(238, steep.valueFor(200)) // 237.5
        assertEquals(338, steep.valueFor(300)) // +37.5 stays
        assertEquals(56, steep.valueFor(50)) // +12.5 at 100 gets smaller

        // flatter than 0.8
        val flat = correction(100 to 100, 200 to 150)
        assertEquals(0.8, flat.slope, 0.0)
        assertEquals(5.0, flat.intercept, 1e-9)
        assertEquals(85, flat.valueFor(100))
        assertEquals(165, flat.valueFor(200))
        assertEquals(365, flat.valueFor(400)) // -35 stays
    }

    /** Slope and intercept as CustomCalibration.swift of the original app counts them. */
    private fun originalLine(pairs: List<ReferencePair>): Pair<Double, Double> {
        val xs = pairs.map { it.sensorValue.toDouble() }
        val ys = pairs.map { it.referenceValue.toDouble() }
        fun average(input: List<Double>): Double = input.reduce { a, b -> a + b } / input.size
        val sum1 = average(xs.zip(ys).map { it.first * it.second }) - average(xs) * average(ys)
        val sum2 = average(xs.zip(xs).map { it.first * it.second }) - Math.pow(average(xs), 2.0)
        val slope = if (pairs.size < 2 || sum2 == 0.0) 1.0 else Math.min(Math.max(sum1 / sum2, 0.8), 1.25)
        return Pair(slope, average(ys) - slope * average(xs))
    }

    @Test
    fun theLineIsTheLineOfTheOriginal() {
        val random = Random(20261110)
        var lines = 0
        repeat(3000) {
            val pairs = randomPairs(random)
            val correction = Correction(serial, pairs)
            assertEquals(pairs.size, correction.pairs.size)
            val xs = pairs.map { it.sensorValue }
            if (correction.hasLine) {
                lines++
                assertTrue(xs.max() - xs.min() >= 40)
                val (slope, intercept) = originalLine(pairs)
                assertEquals(slope, correction.slope, 1e-9)
                assertEquals(intercept, correction.intercept, 1e-6)
            } else {
                assertTrue(xs.max() - xs.min() < 40)
                assertEquals(1.0, correction.slope, 0.0)
            }
        }
        assertTrue("lines: $lines", lines > 1500)
    }

    // ------------------------------------------------------------ limits that always hold

    /** 1 to 12 pairs that the rules accept. */
    private fun randomPairs(random: Random): List<ReferencePair> {
        val pairs = ArrayList<ReferencePair>()
        val count = 1 + random.nextInt(12)
        val narrow = random.nextInt(4) == 0
        val centre = 39 + random.nextInt(463)
        while (pairs.size < count) {
            val x = if (narrow) (centre + random.nextInt(31) - 15).coerceIn(39, 501) else 39 + random.nextInt(463)
            val most = CorrectionRules.maxShift(x.toDouble())
            val y = (x + Math.round((random.nextDouble() * 2 - 1) * most).toInt()).coerceIn(39, 501)
            val pair = ReferencePair(1_000_000L + pairs.size * minute, x, y)
            if (pair.isUsable) {
                pairs.add(pair)
            }
        }
        return pairs
    }

    @Test
    fun noValueIsMovedFar_andAHigherSensorValueNeverGivesALowerValue() {
        val random = Random(4530)
        var biggest = 0
        repeat(3000) {
            val correction = Correction(serial, randomPairs(random))
            var before = Int.MIN_VALUE
            for (x in -60..700) {
                val y = correction.valueFor(x)
                val most = if (x <= 0) 0.0 else CorrectionRules.maxShift(x.toDouble())
                assertTrue("$x -> $y with ${correction.describe()}", Math.abs(y - x) <= Math.ceil(most))
                assertTrue("$x -> $y after $before with ${correction.describe()}", y >= before)
                before = y
                biggest = Math.max(biggest, Math.abs(y - x))
            }
        }
        assertTrue("values are moved by more than 30 where a quarter is more: $biggest", biggest >= 60)
    }

    @Test
    fun theLimitIs30OrAQuarter() {
        assertEquals(30.0, CorrectionRules.maxShift(39.0), 0.0)
        assertEquals(30.0, CorrectionRules.maxShift(120.0), 0.0)
        assertEquals(50.0, CorrectionRules.maxShift(200.0), 0.0)
        assertEquals(100.0, CorrectionRules.maxShift(400.0), 0.0)

        // three pairs: the line would lift 39 by more than 30
        val line = correction(39 to 69, 100 to 130, 400 to 300)
        assertTrue(line.hasLine)
        for (x in 39..400) {
            assertTrue("$x", Math.abs(line.valueFor(x) - x) <= Math.ceil(CorrectionRules.maxShift(x.toDouble())))
        }
    }

    @Test
    fun onePairAloneAlwaysComesOutRight() {
        val random = Random(77)
        repeat(2000) {
            val pair = randomPairs(random).first()
            assertEquals(pair.referenceValue, Correction(serial, listOf(pair)).valueFor(pair.sensorValue))
        }
    }

    // ------------------------------------------------------------ which values

    @Test
    fun onlyTheValuesOfItsOwnSensorAreCorrected() {
        val one = correction(104 to 112)
        val own = glucose(104)
        val other = glucose(104, "3MH001GJ0VW")
        val unknown = glucose(104, null)

        assertTrue(one.appliesTo(own))
        assertFalse(one.appliesTo(other))
        assertFalse(one.appliesTo(unknown))
        assertSame(other, one.apply(other))
        assertSame(unknown, one.apply(unknown))

        val corrected = one.apply(own)
        assertEquals(112, corrected.value)
        assertEquals(own.timestamp, corrected.timestamp)
        assertEquals(serial, corrected.sensorSerial)
        assertTrue(corrected.fromTrend)
        assertNull("the change is counted anew on the screens", corrected.minuteChange)
        assertEquals(104, own.value) // the saved value is not touched

        val all = one.applyAll(listOf(own, other, unknown))
        assertEquals(listOf(112, 104, 104), all.map { it.value })
    }

    @Test
    fun lowAndHighStayLowAndHighUnlessTheCorrectionSaysOtherwise() {
        val up = correction(40 to 60)
        // the sensor says LOW (36), the corrected value is a number again
        assertEquals(GlucoseType.LOW, glucose(36).type)
        assertEquals(54, up.apply(glucose(36)).value)
        assertEquals(GlucoseType.NORMAL, up.apply(glucose(36)).type)
        // far below, it stays LOW
        assertEquals(GlucoseType.LOW, up.apply(glucose(20)).type)

        val down = correction(60 to 50)
        assertEquals(GlucoseType.LOW, down.apply(glucose(45)).type) // 37.5 -> 38
        assertEquals(GlucoseType.HIGH, correction(480 to 500).apply(glucose(490)).type) // 510
    }

    // ------------------------------------------------------------ keeping the pairs

    @Test
    fun pairsThatTheRulesDoNotAcceptAreLeftOut() {
        val pairs = listOf(
            ReferencePair(1, 104, 112),
            ReferencePair(2, 104, 150), // 46 away
            ReferencePair(3, 20, 40), // sensor value was LOW
            ReferencePair(4, 104, 20), // not a value of a meter
            ReferencePair(5, 600, 501) // sensor value was HIGH
        )
        val correction = Correction(serial, pairs)
        assertEquals(1, correction.pairs.size)
        assertEquals(112, correction.valueFor(104))
    }

    @Test
    fun addingAndRemovingPairs() {
        var correction = Correction.NONE.adding(serial, ReferencePair(10 * minute, 104, 112))
        assertEquals(serial, correction.sensorSerial)
        assertEquals(1, correction.pairs.size)

        // the same minute again: the pair is replaced
        correction = correction.adding(serial, ReferencePair(10 * minute, 104, 110))
        assertEquals(1, correction.pairs.size)
        assertEquals(110, correction.valueFor(104))

        // another minute: one more pair
        correction = correction.adding(serial, ReferencePair(90 * minute, 180, 170))
        assertEquals(2, correction.pairs.size)
        assertTrue(correction.hasLine)

        // removing
        correction = correction.removing(10 * minute)
        assertEquals(1, correction.pairs.size)
        assertEquals(170, correction.valueFor(180))
        assertFalse(correction.removing(90 * minute).isActive)

        // a pair of another sensor starts anew
        val next = correction.adding("3MH001GJ0VW", ReferencePair(200 * minute, 100, 95))
        assertEquals("3MH001GJ0VW", next.sensorSerial)
        assertEquals(1, next.pairs.size)
        assertEquals(95, next.valueFor(100))

        // only the newest 12 are kept
        var many = Correction.NONE
        for (i in 1..20) {
            many = many.adding(serial, ReferencePair(i * minute, 100 + i, 100 + i))
        }
        assertEquals(12, many.pairs.size)
        assertEquals(109, many.pairs.first().sensorValue)
        assertEquals(120, many.pairs.last().sensorValue)
    }

    @Test
    fun thePairsSurviveAsText() {
        val correction = correction(60 to 70, 160 to 160, 104 to 112)
        val text = correction.encode()
        assertEquals("1000000,60,70;4600000,160,160;8200000,104,112", text)
        val back = Correction.decode(serial, text)
        assertEquals(text, back.encode())
        assertEquals(serial, back.sensorSerial)
        assertEquals(correction.slope, back.slope, 0.0)
        for (x in 0..600) {
            assertEquals(correction.valueFor(x), back.valueFor(x))
        }

        // text that cannot be read gives no pair, and never a crash
        assertFalse(Correction.decode(serial, null).isActive)
        assertFalse(Correction.decode(serial, "").isActive)
        assertFalse(Correction.decode(null, text).isActive)
        assertFalse(Correction.decode(serial, "abc;1,2;;,,,;x,104,112;5,104").isActive)
        assertEquals(1, Correction.decode(serial, "abc;7, 104 ,112;9,9,9,9").pairs.size)
    }

    @Test
    fun theDescriptionNamesEverything() {
        assertEquals(
            "value correction: sensor 3MH001HM1LM, shift, slope 1.000, intercept 8.0\npairs (time, sensor value, right value): 1000000, 104, 112",
            correction(104 to 112).describe()
        )
        assertTrue(correction(60 to 70, 160 to 160).describe().startsWith("value correction: sensor 3MH001HM1LM, line, slope 0.900, intercept 16.0\n"))
    }

    // ------------------------------------------------------------ the rules

    @Test
    fun aPairNeedsACurrentSteadyNumber() {
        val value = glucose(104)
        assertEquals(PairCheck.OK, CorrectionRules.checkSensorSide(value, true, 0.4))
        assertEquals(PairCheck.OK, CorrectionRules.checkSensorSide(value, true, -1.0))
        assertEquals(PairCheck.OK, CorrectionRules.checkSensorSide(value, true, 1.0))
        assertEquals(PairCheck.OK, CorrectionRules.checkSensorSide(glucose(39), true, 0.0))
        assertEquals(PairCheck.OK, CorrectionRules.checkSensorSide(glucose(501), true, 0.0))

        assertEquals(PairCheck.NO_VALUE, CorrectionRules.checkSensorSide(null, true, 0.0))
        assertEquals(PairCheck.OLD_VALUE, CorrectionRules.checkSensorSide(value, false, 0.0))
        assertEquals(PairCheck.NO_SENSOR, CorrectionRules.checkSensorSide(glucose(104, null), true, 0.0))
        assertEquals(PairCheck.NO_SENSOR, CorrectionRules.checkSensorSide(glucose(104, ""), true, 0.0))
        assertEquals(PairCheck.NOT_MEASURABLE, CorrectionRules.checkSensorSide(glucose(38), true, 0.0))
        assertEquals(PairCheck.NOT_MEASURABLE, CorrectionRules.checkSensorSide(glucose(502), true, 0.0))
        assertEquals(PairCheck.CHANGE_UNKNOWN, CorrectionRules.checkSensorSide(value, true, null))
        assertEquals(PairCheck.CHANGE_UNKNOWN, CorrectionRules.checkSensorSide(value, true, Double.NaN))
        assertEquals(PairCheck.NOT_STEADY, CorrectionRules.checkSensorSide(value, true, 1.01))
        assertEquals(PairCheck.NOT_STEADY, CorrectionRules.checkSensorSide(value, true, -1.5))
    }

    @Test
    fun theTypedValueMustBeANumberOfAMeterAndNotFarAway() {
        assertEquals(PairCheck.OK, CorrectionRules.checkReference(104, 112))
        assertEquals(PairCheck.OK, CorrectionRules.checkReference(104, 104))
        assertEquals(PairCheck.OK, CorrectionRules.checkReference(104, 134)) // +30
        assertEquals(PairCheck.OK, CorrectionRules.checkReference(104, 74)) // -30
        assertEquals(PairCheck.TOO_DIFFERENT, CorrectionRules.checkReference(104, 135))
        assertEquals(PairCheck.TOO_DIFFERENT, CorrectionRules.checkReference(104, 73))
        // a quarter above 120
        assertEquals(PairCheck.OK, CorrectionRules.checkReference(250, 312)) // 62.5 allowed
        assertEquals(PairCheck.TOO_DIFFERENT, CorrectionRules.checkReference(250, 313))
        assertEquals(PairCheck.OK, CorrectionRules.checkReference(44, 55))
        assertEquals(PairCheck.TOO_DIFFERENT, CorrectionRules.checkReference(44, 80))

        assertEquals(PairCheck.OUT_OF_RANGE, CorrectionRules.checkReference(50, 38))
        assertEquals(PairCheck.OUT_OF_RANGE, CorrectionRules.checkReference(480, 502))
        assertEquals(PairCheck.OUT_OF_RANGE, CorrectionRules.checkReference(104, 6))
        assertEquals(PairCheck.OK, CorrectionRules.checkReference(50, 39))
        assertEquals(PairCheck.OK, CorrectionRules.checkReference(480, 501))
    }
}
