package com.glucosescan.app.ui

import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import java.util.Locale

/** Turns numbers into the text that is shown. No Android code here, so it is easy to test. */
object GlucoseFormat {
    /** mg/dL to mmol/L, as the original app does it. */
    fun toMmolL(mgdl: Double): Double = mgdl * GlucoseUnit.EXCHANGE_RATE

    /** "104" for mg/dL, "5.8" for mmol/L (one decimal, a half goes up). */
    fun value(mgdl: Int, unit: GlucoseUnit): String {
        return when (unit) {
            GlucoseUnit.MGDL -> mgdl.toString()
            GlucoseUnit.MMOLL -> {
                // mg/dL x 0.0555, counted in whole numbers so that no rounding error can creep in
                val tenths = Math.floorDiv(mgdl.toLong() * 555 + 500, 1000L)
                val sign = if (tenths < 0) "\u2212" else ""
                sign + (Math.abs(tenths) / 10).toString() + "." + (Math.abs(tenths) % 10).toString()
            }
        }
    }

    /** The value of [glucose], limited to what the sensor can measure. */
    fun value(glucose: Glucose, unit: GlucoseUnit): String = value(glucose.readableValue, unit)

    /**
     * What the user typed, as mg/dL: "112" for mg/dL, "6.2" or "6,2" for mmol/L. Null if it
     * is not a number. A half goes up.
     */
    fun parse(text: String, unit: GlucoseUnit): Int? {
        val clean = StringBuilder()
        for (c in text.trim()) {
            if (c == '.' || c == ',') {
                clean.append('.')
            } else {
                val digit = Character.digit(c, 10)
                if (digit < 0) {
                    return null
                }
                clean.append(digit)
            }
        }
        if (clean.length > 7 || clean.count { it == '.' } > 1 || clean.none { it != '.' }) {
            return null
        }
        val number = clean.toString().toDoubleOrNull() ?: return null
        val mgdl = when (unit) {
            GlucoseUnit.MGDL -> number
            GlucoseUnit.MMOLL -> number / GlucoseUnit.EXCHANGE_RATE
        }
        return Math.floor(mgdl + 0.5).toInt()
    }

    /** A change with its sign: "+1.2" or "−0.7" for mg/dL, "+0.07" for mmol/L. */
    fun change(mgdl: Double, unit: GlucoseUnit): String {
        val text = when (unit) {
            GlucoseUnit.MGDL -> String.format(Locale.US, "%.1f", Math.abs(mgdl))
            GlucoseUnit.MMOLL -> String.format(Locale.US, "%.2f", Math.abs(toMmolL(mgdl)))
        }
        val isZero = text.all { it == '0' || it == '.' }
        return when {
            isZero -> text
            mgdl > 0 -> "+$text"
            else -> "\u2212$text"
        }
    }

    /** Minutes as "3d 4h 12min", "4h 12min" or "12min". */
    fun duration(minutes: Int): String {
        val m = Math.max(0, minutes)
        val days = m / (24 * 60)
        val hours = (m / 60) % 24
        val rest = m % 60
        return when {
            days > 0 -> "${days}d ${hours}h ${rest}min"
            hours > 0 -> "${hours}h ${rest}min"
            else -> "${rest}min"
        }
    }
}
