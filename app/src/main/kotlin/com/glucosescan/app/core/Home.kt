package com.glucosescan.app.core

/**
 * The five bands the Home screen colours a value with.
 *
 * The two outer bands are fixed numbers from the diabetes literature, not the person's own
 * target: a "very low" is a very low on every phone. The three inner bands follow the target
 * the person set for themselves.
 */
enum class GlucoseBand {
    /** Under 54 mg/dL. */
    VERY_LOW,

    /** Under the person's low limit. */
    LOW,

    /** Within the person's target range. */
    IN_RANGE,

    /** Over the person's high limit. */
    HIGH,

    /** Over 250 mg/dL. */
    VERY_HIGH;

    companion object {
        /**
         * @param mgdl the value in mg/dL
         * @param low the low limit of the person's target range
         * @param high the high limit of the person's target range
         */
        fun of(mgdl: Int, low: Int, high: Int): GlucoseBand {
            return when {
                mgdl < StatisticsCalculator.VERY_LOW_BELOW -> VERY_LOW
                mgdl < low -> LOW
                mgdl > StatisticsCalculator.VERY_HIGH_ABOVE -> VERY_HIGH
                mgdl > high -> HIGH
                else -> IN_RANGE
            }
        }
    }
}

/**
 * How much the line the estimates come from can be trusted, as a number and as a word.
 *
 * The number is built only from things measured on the phone: how tightly the values lie
 * around the line, how many there are, and how far back they reach. A line through four values
 * that jump about is not a forecast, whatever it says.
 */
class PredictionConfidence(
    /** 0 to 100. */
    val score: Int,
    /** The number of values the line was built on. */
    val basedOn: Int,
    /** How far back those values reach, in minutes. */
    val spanMinutes: Int,
    /** How far the values lie from the line, mg/dL. */
    val scatter: Double
) {
    /** The word the screen shows. */
    val word: PredictionConfidenceWord
        get() = when {
            score >= 70 -> PredictionConfidenceWord.HIGH
            score >= 40 -> PredictionConfidenceWord.MODERATE
            else -> PredictionConfidenceWord.LOW
        }

    companion object {
        fun of(forecast: Forecast): PredictionConfidence {
            // 1. The fit: values right on the line are worth full marks, and values that swing
            //    7 mg/dL or more around it are worth none.
            val fit = (100.0 - forecast.scatter * 15.0).coerceIn(0.0, 100.0)
            // 2. The amount: ten values are as good as it gets.
            val amount = (forecast.count * 10.0).coerceIn(0.0, 100.0)
            // 3. The reach: twelve minutes back is full marks, four is a fifth.
            val reach = (forecast.spanMinutes * 100.0 / 12.0).coerceIn(0.0, 100.0)
            // 4. The discipline of the readings: gaps and irregular intervals drag the score down.
            val raw = fit * 0.60 + amount * 0.25 + reach * 0.15
            val score = Math.round(raw * (0.7 + 0.3 * forecast.regularity)).toInt().coerceIn(0, 100)
            return PredictionConfidence(score, forecast.count, forecast.spanMinutes, forecast.scatter)
        }
    }
}

/** The three words for [PredictionConfidence]. */
enum class PredictionConfidenceWord {
    HIGH,
    MODERATE,
    LOW
}

/** A limit the line is heading for, and when it gets there. */
class ThresholdCrossing(
    /** The limit in mg/dL: the low or the high limit of the person's range. */
    val limit: Int,
    /** Whole minutes from now. */
    val minutes: Int,
    /** True when the line goes up to the limit, false when it goes down to it. */
    val rising: Boolean
)

/** The times to the two limits of the target range. */
object Thresholds {

    /** How far ahead a crossing is looked for, in minutes: three hours. */
    const val WINDOW_MINUTES = 180

    /**
     * Whole minutes until the line reaches [limit], or null when it does not clearly move
     * towards it inside [WINDOW_MINUTES]. A line that has already passed the limit names no
     * time: the value itself says that, in its own words.
     */
    fun minutesTo(forecast: Forecast, now: Long, limit: Int): Int? {
        val current = forecast.exact(now)
        if (Math.abs(forecast.slope) < Forecaster.MIN_FALL) {
            return null
        }
        val rising = forecast.slope > 0
        // a falling line can only reach a limit that is below it, and the other way round
        if (rising && current >= limit) return null
        if (!rising && current <= limit) return null
        val minutes = Math.abs((limit - current) / forecast.slope)
        if (minutes > WINDOW_MINUTES) return null
        return Math.max(1, Math.round(minutes).toInt())
    }

    /**
     * The limit the line reaches first, and when. Both limits are checked, so a rising line
     * names the high limit and a falling one names the low limit.
     */
    fun crossings(forecast: Forecast, now: Long, lowLimit: Int, highLimit: Int): List<ThresholdCrossing> {
        val list = ArrayList<ThresholdCrossing>(2)
        val toLow = minutesTo(forecast, now, lowLimit)
        if (toLow != null) list.add(ThresholdCrossing(lowLimit, toLow, false))
        val toHigh = minutesTo(forecast, now, highLimit)
        if (toHigh != null) list.add(ThresholdCrossing(highLimit, toHigh, true))
        return list
    }
}

/** How serious the card of estimates is. The colour never stands alone: the words say the same. */
enum class OutlookSeverity {
    NORMAL,
    WARNING,
    URGENT
}

/**
 * How a stretch of days is going, in the four words the Home screen uses.
 *
 * This is a judgement about numbers, never a diagnosis, and it is deliberately slow to say
 * anything good: "Good" on three readings is worse than no answer at all.
 */
enum class ControlLevel {
    GOOD,
    NEEDS_ATTENTION,
    POOR,
    CRITICAL
}

/**
 * The judgement of a period, with the figures it came from.
 *
 * The sentence always carries the numbers behind the word, so the person can check it against
 * the cards above it.
 */
class ControlSummary(
    val level: ControlLevel,
    /** The word for the badge: Good, Needs attention, Poor control or Critical. */
    val word: String,
    /** The sentence under the badge, built from the real figures. */
    val sentence: String,
    val inRangePercent: Int,
    val belowPercent: Int,
    val abovePercent: Int,
    val coveragePercent: Int,
    val cv: Double?,
    /** False when there is too little data for the word to mean anything. */
    val trustworthy: Boolean,
    /** False when the word is only "not enough data". */
    val judged: Boolean
)

object ControlSummaryOf {

    /** Below this many quarter hours (about twelve hours of data) the period is not judged. */
    const val MIN_QUARTERS = 48

    /** The coverage the literature asks for before a period can be judged. */
    const val ENOUGH_COVERAGE = 70

    fun of(stats: Statistics): ControlSummary {
        val inRange = stats.inTargetPercent
        val below = stats.belowPercent
        val above = stats.abovePercent
        val percents = stats.rangePercents
        val veryLow = if (percents.size >= 5) percents[0] else 0
        val veryHigh = if (percents.size >= 5) percents[4] else 0

        if (stats.slots < MIN_QUARTERS) {
            return ControlSummary(
                level = ControlLevel.NEEDS_ATTENTION,
                word = "Insufficient data",
                sentence = "Only " + stats.slots + " quarter hours of readings are here, with " +
                    stats.coveragePercent + " % coverage. The period cannot be judged yet.",
                inRangePercent = inRange,
                belowPercent = below,
                abovePercent = above,
                coveragePercent = stats.coveragePercent,
                cv = stats.cv,
                trustworthy = false,
                judged = false
            )
        }

        val level = when {
            veryLow >= 1 || below >= 5 -> ControlLevel.CRITICAL
            inRange < 50 || above > 50 -> ControlLevel.POOR
            inRange < 70 || below >= 1 || (stats.cv != null && stats.cv!! > 36.0) -> ControlLevel.NEEDS_ATTENTION
            else -> ControlLevel.GOOD
        }
        val word = when (level) {
            ControlLevel.GOOD -> "Good"
            ControlLevel.NEEDS_ATTENTION -> "Needs attention"
            ControlLevel.POOR -> "Poor control"
            ControlLevel.CRITICAL -> "Critical"
        }

        val parts = ArrayList<String>(4)
        parts.add(Integer.toString(inRange) + " % of the time in range")
        if (below > 0) parts.add(Integer.toString(below) + " % below")
        if (above > 0) parts.add(Integer.toString(above) + " % above")
        if (veryHigh > 0) parts.add(Integer.toString(veryHigh) + " % very high")
        var sentence = parts.joinToString(", ") + "."
        if (stats.coveragePercent < ENOUGH_COVERAGE) {
            sentence += " Only " + stats.coveragePercent + " % of this period has readings, so treat this with care."
        }
        if (level == ControlLevel.CRITICAL) {
            sentence += " Follow the plan your clinician gave you, and tell them about these readings."
        }

        return ControlSummary(
            level = level,
            word = word,
            sentence = sentence,
            inRangePercent = inRange,
            belowPercent = below,
            abovePercent = above,
            coveragePercent = stats.coveragePercent,
            cv = stats.cv,
            trustworthy = stats.coveragePercent >= ENOUGH_COVERAGE,
            judged = true
        )
    }
}

/**
 * Which source supplied the newest reading. Used by the sensor card, which must say what is
 * actually arriving rather than what is switched on.
 */
enum class ReadingSource(val word: String) {
    SENSOR("Sensor scan"),
    LINKUP("LibreLinkUp"),
    NONE("Nothing yet")
}

/**
 * A forecast at one horizon, for the three cards of the Home screen.
 *
 * The 15- and 30-minute values come from the same weighted model ([Forecast.valueAt]). The
 * 60-minute value bends: the trend it has now is slowed towards the person's own average,
 * because a straight line carried an hour forward leaves reality behind. Nothing is invented —
 * the two numbers it bends between are the line and the mean of the readings that are already
 * there. Each horizon carries its own confidence: the longer it reaches, the less sure it is.
 */
class HorizonEstimate(
    /** The horizon in minutes: 15, 30 or 60. */
    val minutes: Int,
    /** The estimate in mg/dL, limited to what a sensor can read. */
    val value: Int,
    /** The time the estimate is for, milliseconds since 1970. */
    val at: Long,
    /** The band the estimate falls in, for the colour of the card. */
    val band: GlucoseBand,
    /** The direction the line is going at this horizon. */
    val arrow: String,
    /** How much this estimate is to be trusted (the 60-minute one least). */
    val confidence: PredictionConfidenceWord = PredictionConfidenceWord.MODERATE
)

object Horizons {

    /** How much of the trend survives an hour: [BEND_PER_HOUR] of the slope is left after it. */
    const val BEND_PER_HOUR = 0.45

    /**
     * The estimate at [minutes] ahead.
     *
     * @param forecast the line, from the readings themselves
     * @param now the moment "now" is
     * @param mean the mean of the readings of the window, or null when it cannot be had
     * @param lowLimit the low limit of the person's target range
     * @param highLimit the high limit of the person's target range
     */
    fun at(forecast: Forecast, now: Long, minutes: Int, mean: Double?, lowLimit: Int, highLimit: Int): HorizonEstimate {
        val at = now + minutes * 60_000L
        val value = if (minutes <= Forecaster.HORIZON_MINUTES) {
            // 15 and 30 minutes: the line the app has always drawn, untouched
            forecast.valueAt(at)
        } else {
            // an hour: the same line, slowing towards the average it came from
            val straight = forecast.exact(at)
            val damped = if (mean == null) {
                straight
            } else {
                val hours = minutes / 60.0
                val bend = 1.0 - (1.0 - BEND_PER_HOUR) * hours
                val blended = mean + (straight - mean) * bend.coerceIn(0.0, 1.0)
                blended
            }
            Glucose.limitToReadable(Math.round(damped).toInt())
        }
        val band = GlucoseBand.of(value, lowLimit, highLimit)
        // the arrow of the horizon: the way the line moves from now to then
        val change = value - forecast.level
        val arrow = when {
            change > 18 -> "\u2191\u2191"
            change > 7 -> "\u2191"
            change > 2 -> "\u2197"
            change < -18 -> "\u2193\u2193"
            change < -7 -> "\u2193"
            change < -2 -> "\u2198"
            else -> "\u2192"
        }
        // Each horizon is trusted on its own: the further the guess reaches, the more the
        // model's own scatter and irregularity drag the score down (0.3 point per minute).
        val base = PredictionConfidence.of(forecast).score
        val horizonScore = (base - (0.3 * minutes).toInt()).coerceIn(0, 100)
        val confidence = when {
            horizonScore >= 70 -> PredictionConfidenceWord.HIGH
            horizonScore >= 40 -> PredictionConfidenceWord.MODERATE
            else -> PredictionConfidenceWord.LOW
        }
        return HorizonEstimate(minutes, value, at, band, arrow, confidence)
    }
}
