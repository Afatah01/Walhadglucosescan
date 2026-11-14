package com.glucosescan.app.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import com.glucosescan.app.R
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseType
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.ScanStatus
import com.glucosescan.app.core.Trend
import com.glucosescan.app.data.ScanMessage
import com.glucosescan.app.data.ScanOutcome
import java.util.Calendar
import java.util.Date

/** Builds the texts of the screens. */
object Texts {
    /** "14:32" */
    fun timeOfDay(context: Context, time: Long): String = DateFormat.getTimeFormat(context).format(Date(time))

    /** "Tue, Nov 10, 14:32" */
    fun dateTime(context: Context, time: Long): String {
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
        return DateUtils.formatDateTime(context, time, flags)
    }

    /** "Tuesday, November 10, 2026" */
    fun day(context: Context, time: Long): String {
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR
        return DateUtils.formatDateTime(context, time, flags)
    }

    /** Only the time if [time] is on the same day as [now], else the date and the time. */
    fun timeOrDateTime(context: Context, time: Long, now: Long): String {
        return if (sameDay(time, now)) timeOfDay(context, time) else dateTime(context, time)
    }

    fun sameDay(a: Long, b: Long): Boolean {
        val first = Calendar.getInstance()
        first.timeInMillis = a
        val second = Calendar.getInstance()
        second.timeInMillis = b
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
    }

    /** "just now", "3 minutes ago", "2h 5min ago" */
    fun ago(context: Context, minutes: Int): String {
        return when {
            minutes <= 0 -> context.getString(R.string.just_now)
            minutes < 60 -> context.resources.getQuantityString(R.plurals.minutes_ago, minutes, minutes)
            else -> context.getString(R.string.time_ago, GlucoseFormat.duration(minutes))
        }
    }

    /** "104", "LOW" or "HIGH" */
    fun valueText(context: Context, glucose: Glucose, unit: GlucoseUnit): String {
        return when (glucose.type) {
            GlucoseType.LOW -> context.getString(R.string.value_low)
            GlucoseType.HIGH -> context.getString(R.string.value_high)
            GlucoseType.NORMAL -> GlucoseFormat.value(glucose, unit)
        }
    }

    /** "104 mg/dL", "LOW" or "HIGH" */
    fun valueWithUnit(context: Context, glucose: Glucose, unit: GlucoseUnit): String {
        return when (glucose.type) {
            GlucoseType.NORMAL -> GlucoseFormat.value(glucose, unit) + " " + unit.label
            else -> valueText(context, glucose, unit)
        }
    }

    /** What LOW or HIGH means, or null for a normal value. */
    fun lowHighNote(context: Context, glucose: Glucose, unit: GlucoseUnit): String? {
        return when (glucose.type) {
            GlucoseType.LOW -> context.getString(R.string.low_explained, GlucoseFormat.value(40, unit), unit.label)
            GlucoseType.HIGH -> context.getString(R.string.high_explained, GlucoseFormat.value(500, unit), unit.label)
            GlucoseType.NORMAL -> null
        }
    }

    /**
     * The same words for the screen, with each glucose number and its unit kept in one piece.
     * Android may end a line after a slash or after a number: that gives "mg/" at the end of
     * one line and "dL" at the start of the next, or a number without its unit. A space
     * that does not break and an unseen sign after the slash (the "word joiner") forbid it.
     */
    fun unbroken(words: String): String {
        return words
            .replace(" mg/dL", "\u00A0mg/\u2060dL")
            .replace(" mmol/L", "\u00A0mmol/\u2060L")
            .replace("mg/dL", "mg/\u2060dL")
            .replace("mmol/L", "mmol/\u2060L")
            .replace(" %", "\u00A0%")
    }

    /**
     * The direction in words.
     * @param headsLow true: the value falls slowly, and it is below the target range or the
     * forecast goes below it soon. The arrow "steady" means a change of less than 1 mg/dL a
     * minute, so it covers a slow fall too; then "Steady" next to a warning or on a low value
     * would look wrong, and the words are "Falling slowly".
     */
    fun trendWord(context: Context, trend: Trend, headsLow: Boolean = false): String {
        if (headsLow && trend == Trend.CONSTANT) {
            return context.getString(R.string.trend_falling_slowly)
        }
        return context.getString(
            when (trend) {
                Trend.RAPIDLY_RISING -> R.string.trend_rapidly_rising
                Trend.FAST_RISING -> R.string.trend_fast_rising
                Trend.RISING -> R.string.trend_rising
                Trend.CONSTANT, Trend.UNKNOWN -> R.string.trend_constant
                Trend.FALLING -> R.string.trend_falling
                Trend.FAST_FALLING -> R.string.trend_fast_falling
                Trend.RAPIDLY_FALLING -> R.string.trend_rapidly_falling
            }
        )
    }

    fun stateLabel(context: Context, status: ScanStatus): String {
        return context.getString(
            when (status) {
                ScanStatus.OK -> R.string.state_ready
                ScanStatus.NOT_STARTED -> R.string.state_not_started
                ScanStatus.STARTING -> R.string.state_starting
                ScanStatus.EXPIRED -> R.string.state_expired
                ScanStatus.SHUT_DOWN -> R.string.state_shut_down
                ScanStatus.FAILURE -> R.string.state_failure
                ScanStatus.UNKNOWN_STATE, ScanStatus.UNSUPPORTED_SENSOR, ScanStatus.BAD_DATA -> R.string.state_unknown
            }
        )
    }

    /** The sentence that tells what the last scan did. */
    fun scanMessage(context: Context, outcome: ScanOutcome): String {
        return when (outcome.message) {
            ScanMessage.OK -> context.getString(R.string.scan_ok)
            ScanMessage.OK_NO_NEW -> context.getString(R.string.scan_ok_no_new)
            ScanMessage.SENSOR_NO_VALUE -> context.getString(R.string.scan_no_value)
            ScanMessage.TOO_NOISY -> context.getString(R.string.scan_too_noisy)
            ScanMessage.NOT_STARTED -> context.getString(R.string.scan_not_started)
            ScanMessage.STARTING -> context.resources.getQuantityString(R.plurals.scan_starting, outcome.number, outcome.number)
            ScanMessage.STARTING_NO_TIME -> context.getString(R.string.scan_starting_no_time)
            ScanMessage.EXPIRED -> context.getString(R.string.scan_expired)
            ScanMessage.STOPPED -> context.getString(R.string.scan_stopped)
            ScanMessage.UNKNOWN_STATE -> context.getString(R.string.scan_unknown_state, outcome.text)
            ScanMessage.UNSUPPORTED -> context.getString(R.string.scan_unsupported, outcome.text)
            ScanMessage.BAD_DATA -> context.getString(R.string.scan_bad_data)
            ScanMessage.LOST -> context.getString(R.string.scan_lost)
            ScanMessage.NOT_A_SENSOR -> context.getString(R.string.scan_not_sensor)
            ScanMessage.ERROR -> context.getString(R.string.scan_error, outcome.text)
        }
    }
}
