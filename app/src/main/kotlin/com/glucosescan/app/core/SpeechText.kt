package com.glucosescan.app.core

import com.glucosescan.app.ui.GlucoseFormat

/**
 * The words the app speaks. They are English, like the rest of the app.
 * No Android code here, so it is easy to test.
 */
object SpeechText {
    /**
     * What is said for a value, for example "Glucose 104. Steady." or
     * "Glucose 62. Below target. Falling."
     *
     * @param shown the value as the app shows it
     * @param trend the arrow, or null if it is not known
     * @param below true if the value (or the sensor value under a correction) is below the target range
     * @param above true if the value is above the target range
     * @param withTrend false: the direction is left out
     * @param lowSoon true: the forecast goes below the target range within 20 minutes;
     * "Below target soon." is added
     * @param fallsWhileBelow true: the value is below the target range and still falls clearly
     */
    fun value(
        shown: Glucose,
        unit: GlucoseUnit,
        trend: Trend?,
        below: Boolean,
        above: Boolean,
        withTrend: Boolean,
        lowSoon: Boolean = false,
        fallsWhileBelow: Boolean = false
    ): String {
        val text = StringBuilder()
        when (shown.type) {
            GlucoseType.LOW -> return "Glucose is low. Below ${GlucoseFormat.value(40, unit)}."
            GlucoseType.HIGH -> return "Glucose is high. Above ${GlucoseFormat.value(500, unit)}."
            GlucoseType.NORMAL -> text.append("Glucose ").append(GlucoseFormat.value(shown, unit)).append('.')
        }
        if (below) {
            text.append(" Below target.")
        } else if (above) {
            text.append(" Above target.")
        }
        if (withTrend && trend != null) {
            // The arrow "steady" covers a slow fall too (less than 1 mg/dL a minute). When that
            // fall heads below the target range, "Steady" would contradict the next sentence;
            // and nobody should hear "Steady" for a value that is low and still falling.
            val slowFall = trend == Trend.CONSTANT && ((lowSoon && !below) || (below && fallsWhileBelow))
            val word = if (slowFall) "Falling slowly" else trendWord(trend)
            if (word != null) {
                text.append(' ').append(word).append('.')
            }
        }
        if (lowSoon && !below) {
            text.append(" Below target soon.")
        }
        return text.toString()
    }

    /**
     * Only the number, for a phone whose voice does not speak English: "104" or "5.8".
     * "Low" and "High" stay words, because no number can say "below 40".
     */
    fun number(shown: Glucose, unit: GlucoseUnit): String {
        return when (shown.type) {
            GlucoseType.LOW -> "Low"
            GlucoseType.HIGH -> "High"
            GlucoseType.NORMAL -> GlucoseFormat.value(shown, unit)
        }
    }

    fun trendWord(trend: Trend): String? {
        return when (trend) {
            Trend.RAPIDLY_RISING -> "Rising very fast"
            Trend.FAST_RISING -> "Rising fast"
            Trend.RISING -> "Rising"
            Trend.CONSTANT -> "Steady"
            Trend.FALLING -> "Falling"
            Trend.FAST_FALLING -> "Falling fast"
            Trend.RAPIDLY_FALLING -> "Falling very fast"
            Trend.UNKNOWN -> null
        }
    }

    /** What is said with an alarm. */
    fun alarm(kind: AlarmKind, shown: Glucose?, unit: GlucoseUnit): String {
        return when (kind) {
            AlarmKind.LOW -> "Low glucose alarm." + if (shown != null) " " + number(shown, unit) + "." else ""
            AlarmKind.HIGH -> "High glucose alarm." + if (shown != null) " " + number(shown, unit) + "." else ""
            AlarmKind.NO_DATA -> "No values from the sensor."
            AlarmKind.LOW_SOON -> "Low glucose soon." + if (shown != null) " " + number(shown, unit) + "." else ""
            AlarmKind.VERY_LOW -> "Very low glucose. This is urgent." + if (shown != null) " " + number(shown, unit) + "." else ""
            AlarmKind.VERY_HIGH -> "Very high glucose." + if (shown != null) " " + number(shown, unit) + "." else ""
            AlarmKind.RAPID_RISE -> "Glucose rising very fast." + if (shown != null) " " + number(shown, unit) + "." else ""
            AlarmKind.RAPID_FALL -> "Glucose falling very fast." + if (shown != null) " " + number(shown, unit) + "." else ""
        }
    }

    /** What is said when a scan gave no value. */
    const val SCAN_PROBLEM = "The scan did not work. Please look at the screen."

    /** What is said when the scan worked but the sensor has no value right now. */
    const val SCAN_NO_VALUE = "No value now. Please look at the screen."
}
