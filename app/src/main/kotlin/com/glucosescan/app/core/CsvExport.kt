package com.glucosescan.app.core

import com.glucosescan.app.ui.GlucoseFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Writes the saved values as a table that spreadsheet programs can open (CSV). */
object CsvExport {
    const val HEADER = "kind,time,mg/dL,mmol/L,sensor value mg/dL,note,sensor"

    /**
     * @param values the glucose values as they are stored (the values of the sensor formula)
     * @param shown gives the value as the app shows it (with the correction of the user)
     * @return the text of the file. Lines end with CR LF, as the CSV rules say.
     */
    fun build(values: List<Glucose>, shown: (Glucose) -> Glucose, zone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.timeZone = zone

        // "2026-01-01T12:00:00+03:00". The pattern letter for "+03:00" does not exist on Android 6.
        fun time(timestamp: Long): String {
            val offsetMinutes = zone.getOffset(timestamp) / 60_000
            val sign = if (offsetMinutes < 0) '-' else '+'
            val size = Math.abs(offsetMinutes)
            return format.format(Date(timestamp)) + String.format(Locale.US, "%c%02d:%02d", sign, size / 60, size % 60)
        }

        class Row(val time: Long, val order: Int, val text: String)

        val rows = ArrayList<Row>(values.size)
        for (glucose in values) {
            val display = shown(glucose)
            val line = listOf(
                if (glucose.fromTrend) "glucose (1 minute)" else "glucose (15 minutes)",
                time(glucose.timestamp),
                display.readableValue.toString(),
                GlucoseFormat.value(display.readableValue, GlucoseUnit.MMOLL),
                glucose.readableValue.toString(),
                when (display.type) {
                    GlucoseType.LOW -> "LOW: below 40 mg/dL"
                    GlucoseType.HIGH -> "HIGH: above 500 mg/dL"
                    GlucoseType.NORMAL -> ""
                },
                glucose.sensorSerial ?: ""
            )
            rows.add(Row(glucose.timestamp, 0, line.joinToString(",") { field(it) }))
        }
        rows.sortWith(compareBy<Row> { it.time }.thenBy { it.order })

        val text = StringBuilder(HEADER.length + rows.size * 64)
        text.append(HEADER).append("\r\n")
        for (row in rows) {
            text.append(row.text).append("\r\n")
        }
        return text.toString()
    }

    /**
     * One cell. Text with a comma, a quote or a line break is put in quotes. Text that a
     * spreadsheet program could take for a formula (it starts with = + - or @) gets a
     * leading apostrophe, so that it stays text.
     */
    fun field(text: String): String {
        var cell = text
        if (cell.isNotEmpty() && (cell[0] == '=' || cell[0] == '+' || cell[0] == '-' || cell[0] == '@' || cell[0] == '\t' || cell[0] == '\r')) {
            cell = "'" + cell
        }
        val needsQuotes = cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
    }
}
