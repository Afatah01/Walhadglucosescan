package com.glucosescan.app.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateFormat
import com.glucosescan.app.R
import com.glucosescan.app.core.DailyReport
import com.glucosescan.app.core.Episode
import com.glucosescan.app.core.FindingLevel
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.HourSummary
import com.glucosescan.app.core.StatisticsCalculator
import com.glucosescan.app.core.Verdict
import com.glucosescan.app.ui.FindingTexts
import com.glucosescan.app.ui.AppFont
import com.glucosescan.app.ui.GlucoseFormat
import com.glucosescan.app.ui.ReportWords
import com.glucosescan.app.ui.Texts
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws the report of one day: two pages of A4 paper. The same drawing makes the picture on
 * the screen and the PDF file, so that the file looks like what the user saw.
 *
 * All sizes are in points (1/72 inch), as a PDF counts them. The paper is always white, also
 * when the phone is in dark mode.
 */
class ReportRenderer(
    private val context: Context,
    private val report: DailyReport,
    private val unit: GlucoseUnit,
    /** When the report was made (milliseconds since 1970). */
    private val createdAt: Long,
    /** The version of the app, for the last line of each page. */
    private val appVersion: String,
    /** True: the values were fetched from LibreLinkUp; false: the app has read them from the sensor itself. */
    private val fromLinkUp: Boolean = false
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // The letters of the app, not of the phone: the report looks the same wherever it is made.
    private val regularFace = AppFont.regular(context)
    private val boldFace = AppFont.bold(context)
    // "Linear" and "subpixel" text: the letters keep their exact widths. Without them Android
    // rounds each letter to whole points, and on a page that is drawn larger or smaller than
    // it is laid out (a picture, a PDF) the gaps between letters and words come out uneven.
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val is24 = DateFormat.is24HourFormat(context)
    private val hourFormat = SimpleDateFormat(if (is24) "HH:mm" else "h a", Locale.getDefault())
    private val longDay = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMdyyyy"), Locale.getDefault())
    private val shortDay = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), Locale.getDefault())
    private val fullDay = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEE d MMM"), Locale.getDefault())

    /** What the pages say, line by line. For tests: a picture cannot be read by a test, these words can. */
    val words = ArrayList<String>()

    /** @param page 0 or 1 */
    fun draw(canvas: Canvas, page: Int) {
        canvas.drawColor(Color.WHITE)
        when (page) {
            0 -> firstPage(canvas)
            1 -> secondPage(canvas)
            2 -> thirdPage(canvas)
            else -> fourthPage(canvas)
        }
        footer(canvas, page)
    }

    // ------------------------------------------------------------------ helpers

    private fun value(mgdl: Int): String = GlucoseFormat.value(mgdl, unit)

    private fun value(mgdl: Double): String = GlucoseFormat.value(Math.round(mgdl).toInt(), unit)

    private fun time(moment: Long): String = Texts.timeOfDay(context, moment)

    private fun fill(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int, radius: Float = 0f) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.pathEffect = null
        if (radius > 0f) {
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        } else {
            canvas.drawRect(left, top, right, bottom, paint)
        }
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float = 0.6f, dashed: Boolean = false) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = width
        paint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(4f, 3f), 0f) else null
        if (dashed) {
            val path = Path()
            path.moveTo(x1, y1)
            path.lineTo(x2, y2)
            canvas.drawPath(path, paint)
        } else {
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        paint.pathEffect = null
    }

    private fun text(canvas: Canvas, words: String, x: Float, baseline: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.typeface = if (bold) boldFace else regularFace
        textPaint.textAlign = align
        canvas.drawText(words, x, baseline, textPaint)
        if (words.isNotBlank()) {
            this.words.add(words)
        }
    }

    /** Words that break into lines at [width]. Returns how high they are. */
    private fun paragraph(canvas: Canvas, words: String, x: Float, top: Float, width: Float, size: Float, color: Int, bold: Boolean = false, draw: Boolean = true): Float {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.typeface = if (bold) boldFace else regularFace
        textPaint.textAlign = Paint.Align.LEFT
        val shown = Texts.unbroken(words)
        val layout = StaticLayout.Builder.obtain(shown, 0, shown.length, textPaint, Math.max(1, width.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.12f)
            .setIncludePad(false)
            .build()
        if (draw) {
            canvas.save()
            canvas.translate(x, top)
            layout.draw(canvas)
            canvas.restore()
            this.words.add(words)
        }
        return layout.height.toFloat()
    }

    private fun levelColor(level: FindingLevel): Int {
        return when (level) {
            FindingLevel.URGENT -> RED
            FindingLevel.ATTENTION -> BROWN
            FindingLevel.NOTE -> BLUE
            FindingLevel.GOOD -> GREEN_TEXT
        }
    }

    private fun levelTint(level: FindingLevel): Int {
        return when (level) {
            FindingLevel.URGENT -> 0xFFFDE3E3.toInt()
            FindingLevel.ATTENTION -> 0xFFFFF0C9.toInt()
            FindingLevel.NOTE -> 0xFFE3EEFD.toInt()
            FindingLevel.GOOD -> 0xFFE3F4E6.toInt()
        }
    }

    /** The colour of a value by its range: low, in range (70 to 180), high. */
    private fun rangeColor(mgdl: Double): Int = RANGE_COLORS[StatisticsCalculator.rangeOf(mgdl)]

    /** The upper end of the charts in mg/dL. */
    private fun chartMax(): Float {
        val highest = report.span.highest?.readableValue ?: 0
        return Math.max(300, highest + 20).toFloat()
    }

    /** The lines and numbers of the value axis: (mg/dL, label). */
    private fun valueTicks(max: Float): List<Pair<Float, String>> {
        val ticks = ArrayList<Pair<Float, String>>()
        if (unit == GlucoseUnit.MGDL) {
            val step = if (max > 400) 100 else 50
            var tick = step
            while (tick <= max) {
                if (tick >= CHART_MIN) {
                    ticks.add(Pair(tick.toFloat(), tick.toString()))
                }
                tick += step
            }
        } else {
            val step = if (max > 400) 6 else 3
            var mmol = step
            while (mmol / GlucoseUnit.EXCHANGE_RATE <= max) {
                val position = (mmol / GlucoseUnit.EXCHANGE_RATE).toFloat()
                if (position >= CHART_MIN) {
                    ticks.add(Pair(position, mmol.toString()))
                }
                mmol += step
            }
        }
        return ticks
    }

    private fun sectionTitle(canvas: Canvas, words: String, y: Float) {
        text(canvas, words, MARGIN, y, 12f, BLUE, bold = true)
    }

    /**
     * The end of every page: a rule, the careful words, the name of the maker and the number
     * of the page. The name stands at the bottom left, where the eye looks last, on both
     * pages, so that a printed report always says who made it.
     */
    private fun footer(canvas: Canvas, page: Int) {
        line(canvas, MARGIN, 794f, WIDTH - MARGIN, 794f, GRID)
        paragraph(
            canvas,
            "Walhad Glucose Scan $appVersion. This app is not a medical device" +
                (if (fromLinkUp) ". The values come from LibreLinkUp. " else ", and the values can differ from FreeStyle LibreLink. ") +
                "Show the report to your doctor or diabetes nurse. Do not change insulin or medicine by yourself because of it.",
            MARGIN,
            799f,
            WIDTH - 2 * MARGIN,
            7.5f,
            GREY
        )
        text(canvas, CREDIT, MARGIN, 828f, 8.5f, TEXT, bold = true)
        text(canvas, "Page " + (page + 1) + " of " + PAGES, WIDTH - MARGIN, 828f, 8f, GREY, align = Paint.Align.RIGHT)
    }

    // ------------------------------------------------------------------ page 1

    private fun firstPage(canvas: Canvas) {
        val span = report.span
        val stats = span.stats
        val contentWidth = WIDTH - 2 * MARGIN

        // the blue head with the logo
        fill(canvas, 0f, 0f, WIDTH, 92f, BLUE)
        fill(canvas, 0f, 88f, WIDTH, 92f, BLUE_DARK)
        val logo: Bitmap? = BitmapFactory.decodeResource(context.resources, R.drawable.brand_logo)
        if (logo != null) {
            canvas.drawBitmap(logo, null, RectF(MARGIN, 16f, MARGIN + 58f, 74f), bitmapPaint)
        }
        text(canvas, "Daily glucose report", 108f, 42f, 21f, Color.WHITE, bold = true)
        text(canvas, longDay.format(Date(report.dayStart)), 108f, 62f, 13f, Color.WHITE)
        text(canvas, "Walhad Glucose Scan", 108f, 78f, 9.5f, Color.WHITE)
        text(canvas, "Made " + Texts.dateTime(context, createdAt), WIDTH - MARGIN, 78f, 9f, Color.WHITE, align = Paint.Align.RIGHT)
        if (report.running) {
            text(canvas, "The day is not over: values until " + time(report.until - 60_000L), WIDTH - MARGIN, 62f, 9f, Color.WHITE, align = Paint.Align.RIGHT)
        }

        // one sentence that sums the day up
        val verdict = Verdict.of(span)
        if (verdict == null) {
            fill(canvas, MARGIN, 104f, WIDTH - MARGIN, 132f, 0xFFEEF2F8.toInt(), 8f)
            text(canvas, "No values on this day.", MARGIN + 12f, 122.5f, 12f, TEXT, bold = true)
            return
        }
        fill(canvas, MARGIN, 104f, WIDTH - MARGIN, 132f, levelTint(verdict.level), 8f)
        text(canvas, ReportWords.verdict(verdict, unit), MARGIN + 12f, 122.5f, 12f, levelColor(verdict.level), bold = true)

        // four numbers
        val lowest = span.lowest
        val highest = span.highest
        val boxes = listOf(
            Triple("Average", stats.mean?.let { value(it) } ?: "–", unit.label),
            Triple("Lowest", lowest?.let { ReportWords.value(it, unit) } ?: "–", lowest?.let { "at " + time(it.timestamp) } ?: ""),
            Triple("Highest", highest?.let { ReportWords.value(it, unit) } ?: "–", highest?.let { "at " + time(it.timestamp) } ?: ""),
            Triple(
                "In range",
                ReportWords.inRange(span),
                value(StatisticsCalculator.LOW_BELOW) + " to " + value(StatisticsCalculator.HIGH_ABOVE) + " " + unit.label
            )
        )
        val gap = 10f
        val boxWidth = (contentWidth - gap * (boxes.size - 1)) / boxes.size
        for ((index, box) in boxes.withIndex()) {
            val left = MARGIN + index * (boxWidth + gap)
            fill(canvas, left, 142f, left + boxWidth, 198f, 0xFFF4F7FC.toInt(), 8f)
            fill(canvas, left, 150f, left + 3.5f, 190f, BOX_ACCENTS[index], 1.5f)
            text(canvas, box.first, left + 12f, 157f, 9f, GREY)
            text(canvas, box.second, left + 12f, 177f, if (box.second.length > 9) 13f else 17f, TEXT, bold = true)
            text(canvas, box.third, left + 12f, 190f, 8.5f, GREY)
        }

        // the chart of the day
        sectionTitle(canvas, "The day", 220f)
        text(canvas, "Values for " + stats.coveragePercent + " % of the time. Each point is one value.", WIDTH - MARGIN, 220f, 9f, GREY, align = Paint.Align.RIGHT)
        dayChart(canvas, MARGIN + 26f, 238f, WIDTH - MARGIN, 430f)

        // time in ranges
        sectionTitle(canvas, "Time in ranges", 468f)
        rangeBar(canvas, MARGIN, 476f, WIDTH - MARGIN, 490f, stats.rangePercents)
        val percents = stats.rangePercents
        val labels = listOf(
            "Very low: under " + value(StatisticsCalculator.VERY_LOW_BELOW),
            "Low: " + value(StatisticsCalculator.VERY_LOW_BELOW) + " to under " + value(StatisticsCalculator.LOW_BELOW),
            "In range: " + value(StatisticsCalculator.LOW_BELOW) + " to " + value(StatisticsCalculator.HIGH_ABOVE),
            "High: over " + value(StatisticsCalculator.HIGH_ABOVE) + " to " + value(StatisticsCalculator.VERY_HIGH_ABOVE),
            "Very high: over " + value(StatisticsCalculator.VERY_HIGH_ABOVE)
        )
        val column = contentWidth / 5
        for (k in 0 until 5) {
            val left = MARGIN + k * column
            fill(canvas, left, 499f, left + 8f, 507f, RANGE_COLORS[k], 2f)
            text(canvas, percents[k].toString() + " %", left + 12f, 507f, 11f, TEXT, bold = true)
            text(canvas, labels[k], left, 519f, 7.6f, GREY)
        }

        // how the day was, in words
        sectionTitle(canvas, "How the day was", 546f)
        var y = 554f
        val limit = 796f
        for (sentence in ReportWords.span(context, span, unit, ended = !report.running)) {
            val height = paragraph(canvas, sentence, MARGIN + 10f, y, contentWidth - 10f, 10f, TEXT, draw = false)
            if (y + height > limit) {
                break
            }
            fill(canvas, MARGIN, y + 4.2f, MARGIN + 3.2f, y + 7.4f, BLUE, 1.6f)
            paragraph(canvas, sentence, MARGIN + 10f, y, contentWidth - 10f, 10f, TEXT)
            y += height + 4.5f
        }
        y += 4f
        for (finding in report.findings) {
            val words = FindingTexts.text(context, finding, unit, 1, calendarDay = true)
            val height = paragraph(canvas, words, MARGIN + 10f, y + 11f, contentWidth - 10f, 10f, TEXT, draw = false)
            if (y + 11f + height > limit) {
                break
            }
            fill(canvas, MARGIN, y + 1f, MARGIN + 3.2f, y + 11f + height, levelColor(finding.level), 1.6f)
            text(canvas, FindingTexts.levelWord(context, finding.level).uppercase(Locale.US), MARGIN + 10f, y + 8f, 7.6f, levelColor(finding.level), bold = true)
            paragraph(canvas, words, MARGIN + 10f, y + 11f, contentWidth - 10f, 10f, TEXT)
            y += 11f + height + 6f
        }
    }

    /** The values of the day from midnight to midnight. */
    private fun dayChart(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val max = chartMax()
        val start = report.dayStart
        val end = report.dayEnd
        fun x(moment: Long): Float = left + (moment - start).toFloat() / (end - start).toFloat() * (right - left)
        fun y(mgdl: Float): Float = bottom - (mgdl.coerceIn(CHART_MIN.toFloat(), max) - CHART_MIN) / (max - CHART_MIN) * (bottom - top)

        // the part of the day that has not come yet
        if (report.running) {
            fill(canvas, x(report.until), top, right, bottom, 0xFFF2F4F8.toInt())
        }
        // the range from 70 to 180
        fill(canvas, left, y(StatisticsCalculator.HIGH_ABOVE.toFloat()), right, y(StatisticsCalculator.LOW_BELOW.toFloat()), BAND)

        for (tick in valueTicks(max)) {
            line(canvas, left, y(tick.first), right, y(tick.first), GRID)
            text(canvas, tick.second, left - 5f, y(tick.first) + 3f, 8f, GREY, align = Paint.Align.RIGHT)
        }
        // an upright line every three hours
        for (hour in 0..24 step 3) {
            val moment = start + (end - start) * hour / 24
            line(canvas, x(moment), top, x(moment), bottom, GRID)
            val align = if (hour == 24) Paint.Align.RIGHT else if (hour == 0) Paint.Align.LEFT else Paint.Align.CENTER
            text(canvas, hourFormat.format(Date(moment)), x(moment), bottom + 11f, 8f, GREY, align = align)
        }
        line(canvas, left, top, left, bottom, AXIS)
        line(canvas, left, bottom, right, bottom, AXIS)
        line(canvas, left, y(StatisticsCalculator.LOW_BELOW.toFloat()), right, y(StatisticsCalculator.LOW_BELOW.toFloat()), RED, 0.8f, dashed = true)
        line(canvas, left, y(StatisticsCalculator.HIGH_ABOVE.toFloat()), right, y(StatisticsCalculator.HIGH_ABOVE.toFloat()), AMBER, 0.8f, dashed = true)

        // the line: values are joined when they are at most 20 minutes apart
        val path = Path()
        var previous = Long.MIN_VALUE
        for (glucose in report.values) {
            val px = x(glucose.timestamp)
            val py = y(glucose.readableValue.toFloat())
            if (previous != Long.MIN_VALUE && glucose.timestamp - previous <= 20 * 60_000L) {
                path.lineTo(px, py)
            } else {
                path.moveTo(px, py)
            }
            previous = glucose.timestamp
        }
        paint.style = Paint.Style.STROKE
        paint.color = BLUE
        paint.strokeWidth = 1.3f
        paint.strokeJoin = Paint.Join.ROUND
        paint.pathEffect = null
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
        val radius = if (report.values.size > 400) 0.9f else 1.5f
        for (glucose in report.values) {
            paint.color = when {
                glucose.readableValue < StatisticsCalculator.LOW_BELOW -> RED
                glucose.readableValue > StatisticsCalculator.HIGH_ABOVE -> AMBER
                else -> BLUE
            }
            canvas.drawCircle(x(glucose.timestamp), y(glucose.readableValue.toFloat()), radius, paint)
        }
        text(canvas, unit.label, left, top - 3f, 7.5f, GREY)
    }

    /** A bar cut into the five ranges, from very low (left) to very high (right). */
    private fun rangeBar(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, percents: IntArray) {
        val total = percents.sum()
        val clip = Path()
        val radius = (bottom - top) / 2
        clip.addRoundRect(RectF(left, top, right, bottom), radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        fill(canvas, left, top, right, bottom, 0xFFE9F0FA.toInt())
        if (total > 0) {
            var x = left
            for (k in 0 until 5) {
                val width = (right - left) * percents[k] / total
                fill(canvas, x, top, x + width, bottom, RANGE_COLORS[k])
                x += width
            }
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------ page 2

    private fun secondPage(canvas: Canvas) {
        val contentWidth = WIDTH - 2 * MARGIN
        fill(canvas, 0f, 0f, WIDTH, 40f, BLUE)
        text(canvas, "Daily glucose report", MARGIN, 25f, 12f, Color.WHITE, bold = true)
        text(canvas, shortDay.format(Date(report.dayStart)), WIDTH - MARGIN, 25f, 11f, Color.WHITE, align = Paint.Align.RIGHT)

        // hour by hour: a bar for each hour of the day
        sectionTitle(canvas, "Hour by hour", 64f)
        text(canvas, "Bar: average of the hour. Thin line: lowest to highest value.", WIDTH - MARGIN, 64f, 9f, GREY, align = Paint.Align.RIGHT)
        hourBars(canvas, MARGIN + 26f, 82f, WIDTH - MARGIN, 204f)

        // the same as a table, in two halves
        val half = (contentWidth - 16f) / 2
        val rowHeight = 13.2f
        val tableTop = 232f
        for (side in 0..1) {
            val left = MARGIN + side * (half + 16f)
            val columns = floatArrayOf(left + 6f, left + half * 0.50f, left + half * 0.74f, left + half - 6f)
            fill(canvas, left, tableTop, left + half, tableTop + rowHeight, 0xFFE3EEFD.toInt(), 3f)
            text(canvas, "Hour", columns[0], tableTop + 9.4f, 8.5f, BLUE, bold = true)
            text(canvas, "Average", columns[1], tableTop + 9.4f, 8.5f, BLUE, bold = true, align = Paint.Align.RIGHT)
            text(canvas, "Lowest", columns[2], tableTop + 9.4f, 8.5f, BLUE, bold = true, align = Paint.Align.RIGHT)
            text(canvas, "Highest", columns[3], tableTop + 9.4f, 8.5f, BLUE, bold = true, align = Paint.Align.RIGHT)
            for (row in 0 until 12) {
                val hourOfDay = side * 12 + row
                val top = tableTop + (row + 1) * rowHeight
                if (row % 2 == 1) {
                    fill(canvas, left, top, left + half, top + rowHeight, 0xFFF6F8FC.toInt())
                }
                val hour = report.hours.firstOrNull { it.hourOfDay == hourOfDay }
                val start = report.dayStart + (report.dayEnd - report.dayStart) * hourOfDay / 24
                text(canvas, hourFormat.format(Date(start)), columns[0], top + 9.6f, 8.8f, TEXT)
                val mean = hour?.mean
                val low = hour?.lowest
                val high = hour?.highest
                if (hour != null && mean != null && low != null && high != null) {
                    fill(canvas, columns[1] - 40f, top + 3.4f, columns[1] - 34f, top + 9.4f, rangeColor(mean), 1.5f)
                    text(canvas, value(mean), columns[1], top + 9.6f, 8.8f, TEXT, bold = true, align = Paint.Align.RIGHT)
                    text(canvas, value(low), columns[2], top + 9.6f, 8.8f, TEXT, align = Paint.Align.RIGHT)
                    text(canvas, value(high), columns[3], top + 9.6f, 8.8f, TEXT, align = Paint.Align.RIGHT)
                } else {
                    text(canvas, "–", columns[1], top + 9.6f, 8.8f, GREY, align = Paint.Align.RIGHT)
                }
            }
        }
        text(canvas, "All values in " + unit.label + ". An hour without values shows a dash.", MARGIN, tableTop + 13 * rowHeight + 12f, 8f, GREY)

        // the day and the six days before it
        val weekTop = 440f
        sectionTitle(canvas, "This day and the six days before", weekTop)
        val headTop = weekTop + 8f
        val weekRow = 17f
        val columns = floatArrayOf(MARGIN + 6f, MARGIN + 150f, MARGIN + 205f, MARGIN + 258f, MARGIN + 311f, MARGIN + 352f, MARGIN + 396f)
        val heads = listOf("Day", "Average", "In range", "Lowest", "Highest", "Lows", "Highs")
        fill(canvas, MARGIN, headTop, WIDTH - MARGIN, headTop + weekRow, 0xFFE3EEFD.toInt(), 3f)
        for ((index, head) in heads.withIndex()) {
            text(canvas, head, columns[index], headTop + 11.6f, 8.5f, BLUE, bold = true, align = if (index == 0) Paint.Align.LEFT else Paint.Align.RIGHT)
        }
        text(canvas, "Time in ranges", MARGIN + 410f, headTop + 11.6f, 8.5f, BLUE, bold = true)
        for ((index, day) in report.week.withIndex()) {
            val top = headTop + (index + 1) * weekRow
            if (index % 2 == 1) {
                fill(canvas, MARGIN, top, WIDTH - MARGIN, top + weekRow, 0xFFF6F8FC.toInt())
            }
            val bold = index == 0
            text(canvas, shortDay.format(Date(day.start)), columns[0], top + 11.8f, 9f, TEXT, bold = bold)
            val stats = day.stats
            val mean = stats.mean
            val low = stats.lowest
            val high = stats.highest
            if (mean == null || low == null || high == null) {
                text(canvas, "no values", columns[1], top + 11.8f, 9f, GREY, align = Paint.Align.RIGHT)
                continue
            }
            text(canvas, value(mean), columns[1], top + 11.8f, 9f, TEXT, bold = bold, align = Paint.Align.RIGHT)
            text(canvas, day.inRangePercent.toString() + " %", columns[2], top + 11.8f, 9f, TEXT, bold = bold, align = Paint.Align.RIGHT)
            text(canvas, value(low), columns[3], top + 11.8f, 9f, TEXT, bold = bold, align = Paint.Align.RIGHT)
            text(canvas, value(high), columns[4], top + 11.8f, 9f, TEXT, bold = bold, align = Paint.Align.RIGHT)
            text(canvas, day.lows.toString(), columns[5], top + 11.8f, 9f, if (day.lows > 0) RED else TEXT, bold = bold || day.lows > 0, align = Paint.Align.RIGHT)
            text(canvas, day.highs.toString(), columns[6], top + 11.8f, 9f, TEXT, bold = bold, align = Paint.Align.RIGHT)
            rangeBar(canvas, MARGIN + 410f, top + 5f, WIDTH - MARGIN - 6f, top + 12f, stats.rangePercents)
        }
        val weekBottom = headTop + (report.week.size + 1) * weekRow
        text(canvas, "Lows and highs: how many times the glucose was under " + value(StatisticsCalculator.LOW_BELOW) + " or over " +
            value(StatisticsCalculator.HIGH_ABOVE) + " " + unit.label + " for 15 minutes or longer.", MARGIN, weekBottom + 13f, 8f, GREY)

        // how the numbers are counted
        var y = weekBottom + 40f
        sectionTitle(canvas, "How the numbers are counted", y)
        y += 8f
        val notes = listOf(
            if (fromLinkUp) {
                "The values come from LibreLinkUp, the follower service of the FreeStyle Libre sensor. The app Walhad Glucose Scan fetched them over the internet" +
                    (if (report.analysis.stats.hasValues) "" else " (no values on this day)") +
                    ". They are the numbers of Abbott's own program; the app has not changed them."
            } else {
                "The values come from a FreeStyle Libre 2 sensor, read by the app Walhad Glucose Scan" +
                    (if (report.analysis.stats.hasValues) "" else " (no values on this day)") +
                    ". They are the values as the app shows them, with your correction if you set one up."
            },
            "Each quarter of an hour counts once, so that many values around a scan do not count more than the rest of the day. " +
                "Times without values are missing in all numbers.",
            "In range means " + value(StatisticsCalculator.LOW_BELOW) + " to " + value(StatisticsCalculator.HIGH_ABOVE) + " " + unit.label +
                ". Your own target range in the app is " + value(report.lowLimit) + " to " + value(report.highLimit) + " " + unit.label + ".",
            "The sentences on page 1 are made by fixed rules from these numbers. They describe what happened. They are not advice."
        )
        for (note in notes) {
            fill(canvas, MARGIN, y + 4.2f, MARGIN + 3.2f, y + 7.4f, BLUE, 1.6f)
            y += paragraph(canvas, note, MARGIN + 10f, y, contentWidth - 10f, 9.5f, TEXT) + 5f
        }
    }


    // ------------------------------------------------------------------ page 3

    /**
     * The page a doctor or diabetes nurse reaches for: how steady the values were, every low
     * and every high with the time it began and how long it lasted, and what is worth raising
     * at the appointment.
     *
     * Nothing here is a diagnosis and nothing here names a dose. The last block says so in as
     * many words, because a report that looks clinical can otherwise be read as instruction.
     */
    private fun thirdPage(canvas: Canvas) {
        fill(canvas, 0f, 0f, WIDTH, 40f, BLUE)
        text(canvas, "Daily glucose report", MARGIN, 25f, 12f, Color.WHITE, bold = true)
        text(canvas, shortDay.format(Date(report.dayStart)), WIDTH - MARGIN, 25f, 11f, Color.WHITE, align = Paint.Align.RIGHT)

        val stats = report.span.stats
        var y = 64f

        // ---- D. how steady the values were
        sectionTitle(canvas, "How steady the values were", y)
        y += 16f
        val cv = stats.cv
        val rows = listOf(
            "Spread around the average (CV)" to (cv?.let { String.format(Locale.US, "%.0f %%", it) } ?: "\u2013"),
            "Standard deviation (SD)" to (stats.sd?.let { value(it) + " " + unit.label } ?: "\u2013"),
            "Estimated HbA1c (GMI)" to (stats.gmi?.let { String.format(Locale.US, "%.1f %%", it) } ?: "\u2013"),
            "Values present" to (stats.coveragePercent.toString() + " % of the time"),
            "Lowest single value" to ((stats.lowest?.let { value(it) } ?: "\u2013") + " " + unit.label),
            "Highest single value" to ((stats.highest?.let { value(it) } ?: "\u2013") + " " + unit.label)
        )
        for ((label, number) in rows) {
            text(canvas, label, MARGIN + 8f, y + 10f, 9.5f, TEXT)
            text(canvas, number, WIDTH - MARGIN, y + 10f, 9.5f, TEXT, bold = true, align = Paint.Align.RIGHT)
            line(canvas, MARGIN, y + 15f, WIDTH - MARGIN, y + 15f, GRID, 0.5f)
            y += 17.5f
        }
        val cvWords = if (cv == null) {
            "Not enough values to say how steady they are."
        } else when {
            cv < 20.0 -> "Steady. Under 20 % is what is aimed for in someone who does not inject insulin."
            cv < 36.0 -> "Steady enough. The aim is under 36 %."
            else -> "Not steady. Over 36 % means the values swing a lot over the day, and that is worth raising."
        }
        text(canvas, cvWords, MARGIN + 8f, y + 11f, 9f, GREY)
        y += 30f

        // ---- E. every low and every high
        sectionTitle(canvas, "Every low and high", y)
        text(
            canvas,
            "An event is 15 minutes or longer under " + value(StatisticsCalculator.LOW_BELOW) +
                " or over " + value(StatisticsCalculator.HIGH_ABOVE) + " " + unit.label + ".",
            WIDTH - MARGIN, y, 8.5f, GREY, align = Paint.Align.RIGHT
        )
        y += 14f
        y = eventTable(canvas, "Low", report.span.lows, y, RED)
        y += 10f
        y = eventTable(canvas, "High", report.span.highs, y, AMBER)
        y += 16f

        // ---- H. what to raise at the appointment
        sectionTitle(canvas, "For the appointment", y)
        y += 10f
        val points = medicalPoints()
        for (point in points) {
            fill(canvas, MARGIN, y + 4.2f, MARGIN + 3.2f, y + 7.4f, BLUE, 1.6f)
            y += paragraph(canvas, point, MARGIN + 10f, y, WIDTH - 2 * MARGIN - 10f, 9.5f, TEXT) + 5f
        }
    }

    /**
     * The fourth page: the same numbers taken apart by the hour and by the day, so that a
     * pattern in time can be seen. This is where "is it always bad after lunch?" is answered.
     */
    private fun fourthPage(canvas: Canvas) {
        fill(canvas, 0f, 0f, WIDTH, 40f, BLUE)
        text(canvas, "Daily glucose report", MARGIN, 25f, 12f, Color.WHITE, bold = true)
        text(canvas, periodName(), WIDTH - MARGIN, 25f, 11f, Color.WHITE, align = Paint.Align.RIGHT)

        var y = 62f

        // ---- F. hour by hour
        sectionTitle(canvas, "Hour by hour", y)
        text(canvas, "What each hour of the day looks like over " + periodName().lowercase(Locale.US) + ".", WIDTH - MARGIN, y, 8.5f, GREY, align = Paint.Align.RIGHT)
        y += 14f
        y = hourTable(canvas, y)

        // ---- G. day by day
        y += 14f
        sectionTitle(canvas, "Day by day", y)
        text(canvas, "One line for every day of the stretch.", WIDTH - MARGIN, y, 8.5f, GREY, align = Paint.Align.RIGHT)
        y += 14f
        y = dayTable(canvas, y)

        // ---- I. the numbers in full
        if (y < 700f) {
            y += 14f
            sectionTitle(canvas, "The numbers in full", y)
            y += 14f
            numbersInFull(canvas, y)
        }
    }

    /** What the stretch is called on the page: "one day", "the last 14 days". */
    private fun periodName(): String {
        val days = report.week.size
        return when {
            days <= 1 -> "one day"
            else -> "the last $days days"
        }
    }

    /** One line for every hour of the day, over the whole stretch. */
    private fun hourTable(canvas: Canvas, top: Float): Float {
        val byHour = Array<MutableList<HourSummary>>(24) { ArrayList() }
        for (hour in report.hours) {
            byHour[hour.hourOfDay].add(hour)
        }
        val columns = floatArrayOf(MARGIN + 8f, MARGIN + 74f, MARGIN + 142f, MARGIN + 210f, MARGIN + 278f, WIDTH - MARGIN - 8f)
        val heads = arrayOf("Hour", "Average", "In range", "Lowest", "Highest", "Days")
        val rowHeight = 12.2f
        var y = top

        for ((index, head) in heads.withIndex()) {
            val align = if (index == 0) Paint.Align.LEFT else Paint.Align.RIGHT
            text(canvas, head, columns[index], y + 8f, 8f, GREY, bold = true, align = align)
        }
        line(canvas, MARGIN, y + 12f, WIDTH - MARGIN, y + 12f, AXIS, 0.7f)
        y += 15f

        for (hour in 0..23) {
            val hours = byHour[hour]
            val withValues = hours.filter { it.mean != null }
            val label = String.format(Locale.US, "%02d:00", hour)
            text(canvas, label, columns[0], y + 8f, 8.5f, TEXT)
            if (withValues.isEmpty()) {
                text(canvas, "\u2013", columns[1], y + 8f, 8.5f, GREY, align = Paint.Align.RIGHT)
                text(canvas, "\u2013", columns[2], y + 8f, 8.5f, GREY, align = Paint.Align.RIGHT)
                text(canvas, "\u2013", columns[3], y + 8f, 8.5f, GREY, align = Paint.Align.RIGHT)
                text(canvas, "\u2013", columns[4], y + 8f, 8.5f, GREY, align = Paint.Align.RIGHT)
                text(canvas, "0", columns[5], y + 8f, 8.5f, GREY, align = Paint.Align.RIGHT)
            } else {
                val mean = withValues.mapNotNull { it.mean }.average()
                val lowest = withValues.mapNotNull { it.lowest }.minOrNull()
                val highest = withValues.mapNotNull { it.highest }.maxOrNull()
                val inRange = withValues.count { it.mean!! >= report.lowLimit && it.mean!! <= report.highLimit }
                val percent = Math.round(100.0 * inRange / withValues.size).toInt()
                text(canvas, value(mean) + " " + unit.label, columns[1], y + 8f, 8.5f, TEXT, bold = true, align = Paint.Align.RIGHT)
                text(canvas, percent.toString() + " %", columns[2], y + 8f, 8.5f, rangeColor(mean), align = Paint.Align.RIGHT)
                text(canvas, value(lowest!!), columns[3], y + 8f, 8.5f, TEXT, align = Paint.Align.RIGHT)
                text(canvas, value(highest!!), columns[4], y + 8f, 8.5f, TEXT, align = Paint.Align.RIGHT)
                text(canvas, withValues.size.toString(), columns[5], y + 8f, 8.5f, GREY, align = Paint.Align.RIGHT)
            }
            line(canvas, MARGIN, y + 12f, WIDTH - MARGIN, y + 12f, GRID, 0.4f)
            y += rowHeight
        }
        return y
    }

    /** One line for every day of the stretch. */
    private fun dayTable(canvas: Canvas, top: Float): Float {
        val days = report.week
        if (days.isEmpty()) {
            text(canvas, "No day of this stretch has values.", MARGIN + 8f, top + 10f, 9f, GREY)
            return top + 20f
        }
        val columns = floatArrayOf(MARGIN + 8f, MARGIN + 92f, MARGIN + 160f, MARGIN + 220f, MARGIN + 288f, MARGIN + 356f, WIDTH - MARGIN - 8f)
        val heads = arrayOf("Day", "Average", "In range", "Lowest", "Highest", "Lows", "Highs")
        val rowHeight = 13.6f
        var y = top

        for ((index, head) in heads.withIndex()) {
            val align = if (index == 0) Paint.Align.LEFT else Paint.Align.RIGHT
            text(canvas, head, columns[index], y + 8f, 8f, GREY, bold = true, align = align)
        }
        line(canvas, MARGIN, y + 12f, WIDTH - MARGIN, y + 12f, AXIS, 0.7f)
        y += 15f

        for (day in days) {
            if (y > 690f) {
                text(canvas, "and " + (days.size - days.indexOf(day)) + " more days", columns[0], y + 8f, 8.5f, GREY)
                y += rowHeight
                break
            }
            text(canvas, fullDay.format(Date(day.start)), columns[0], y + 8f, 8.5f, TEXT)
            if (!day.hasValues) {
                text(canvas, "no values", columns[1], y + 8f, 8.5f, GREY, align = Paint.Align.RIGHT)
                y += rowHeight
                continue
            }
            val mean = day.stats.mean
            text(canvas, (mean?.let { value(it) } ?: "\u2013") + " " + unit.label, columns[1], y + 8f, 8.5f, TEXT, bold = true, align = Paint.Align.RIGHT)
            text(canvas, day.inRangePercent.toString() + " %", columns[2], y + 8f, 8.5f, rangeColor(mean ?: 0.0), align = Paint.Align.RIGHT)
            text(canvas, day.stats.lowest?.let { value(it) } ?: "\u2013", columns[3], y + 8f, 8.5f, TEXT, align = Paint.Align.RIGHT)
            text(canvas, day.stats.highest?.let { value(it) } ?: "\u2013", columns[4], y + 8f, 8.5f, TEXT, align = Paint.Align.RIGHT)
            text(canvas, day.lows.toString(), columns[5], y + 8f, 8.5f, if (day.lows > 0) RED else GREY, align = Paint.Align.RIGHT)
            text(canvas, day.highs.toString(), columns[6], y + 8f, 8.5f, if (day.highs > 0) AMBER else GREY, align = Paint.Align.RIGHT)
            line(canvas, MARGIN, y + 12.5f, WIDTH - MARGIN, y + 12.5f, GRID, 0.4f)
            y += rowHeight
        }
        return y
    }

    /** The rest of the numbers a doctor may ask for, in two columns. */
    private fun numbersInFull(canvas: Canvas, top: Float) {
        val stats = report.span.stats
        val sorted = report.values.map { it.readableValue.toDouble() }.sorted()
        fun at(p: Double): String =
            if (sorted.isEmpty()) "\u2013" else value(StatisticsCalculator.percentile(sorted, p))

        // time in the tight range, 70 to 140: a stricter aim than 70 to 180
        val tight = if (sorted.isEmpty()) "\u2013" else {
            val inside = sorted.count { it >= 70.0 && it <= 140.0 }
            (Math.round(100.0 * inside / sorted.size).toInt()).toString() + " %"
        }
        val readings = sorted.size
        val perDay = if (stats.days > 0 && readings > 0) {
            String.format(Locale.US, "%.0f", readings.toDouble() / stats.days)
        } else "\u2013"
        val ea1c = stats.gmi?.let { gmi ->
            val mmol = Math.round((gmi - 2.15) * 10.929).toInt()
            String.format(Locale.US, "%.1f %%  (%d mmol/mol)", gmi, mmol)
        } ?: "\u2013"

        val pairs = listOf(
            "Values in all" to readings.toString(),
            "Values a day" to perDay,
            "Days with values" to stats.days.toString(),
            "10th percentile" to at(0.10),
            "Median (50th)" to at(0.50),
            "90th percentile" to at(0.90),
            "Estimated HbA1c" to ea1c,
            "In the tight range 70\u2013" + value(140) to tight,
            "Very low (under " + value(StatisticsCalculator.VERY_LOW_BELOW) + ")" to stats.rangePercents[0].toString() + " %",
            "Low (" + value(StatisticsCalculator.LOW_BELOW) + "\u2013" + value(report.lowLimit) + ")" to stats.rangePercents[1].toString() + " %",
            "High (" + value(report.highLimit) + "\u2013" + value(StatisticsCalculator.HIGH_ABOVE) + ")" to stats.rangePercents[3].toString() + " %",
            "Very high (over " + value(StatisticsCalculator.VERY_HIGH_ABOVE) + ")" to stats.rangePercents[4].toString() + " %"
        )
        val columnWidth = (WIDTH - 2 * MARGIN) / 2f
        for ((index, pair) in pairs.withIndex()) {
            val column = index % 2
            val row = index / 2
            val x = MARGIN + 8f + column * columnWidth
            val y = top + row * 15f
            text(canvas, pair.first, x, y + 8f, 8.5f, GREY)
            text(canvas, pair.second, x + columnWidth - 16f, y + 8f, 8.5f, TEXT, bold = true, align = Paint.Align.RIGHT)
        }
    }

    /** One table of events: when it began, how long it lasted, and its lowest (or highest) value. */
    private fun eventTable(canvas: Canvas, kind: String, episodes: List<Episode>, top: Float, accent: Int): Float {
        val columns = floatArrayOf(MARGIN + 8f, MARGIN + 132f, MARGIN + 232f, WIDTH - MARGIN - 8f)
        val rowHeight = 13.5f
        var y = top
        fill(canvas, MARGIN, y, WIDTH - MARGIN, y + rowHeight, 0xFFE3EEFD.toInt(), 3f)
        text(canvas, kind, columns[0], y + 9.6f, 8.5f, BLUE, bold = true)
        text(canvas, "Began", columns[1], y + 9.6f, 8.5f, BLUE, bold = true)
        text(canvas, "How long", columns[2], y + 9.6f, 8.5f, BLUE, bold = true)
        text(canvas, kind, columns[3], y + 9.6f, 8.5f, BLUE, bold = true, align = Paint.Align.RIGHT)
        y += rowHeight
        if (episodes.isEmpty()) {
            text(canvas, "None on this day.", columns[0], y + 10f, 9f, GREY)
            return y + rowHeight
        }
        for ((index, episode) in episodes.withIndex()) {
            if (index % 2 == 1) {
                fill(canvas, MARGIN, y, WIDTH - MARGIN, y + rowHeight, 0xFFF6F8FC.toInt())
            }
            text(canvas, kind + " " + (index + 1), columns[0], y + 9.8f, 9f, accent, bold = true)
            text(canvas, time(episode.start), columns[1], y + 9.8f, 9f, TEXT)
            text(canvas, GlucoseFormat.duration(episode.minutes), columns[2], y + 9.8f, 9f, TEXT)
            text(canvas, value(episode.extreme) + " " + unit.label, columns[3], y + 9.8f, 9f, TEXT, bold = true, align = Paint.Align.RIGHT)
            y += rowHeight
        }
        return y
    }

    /**
     * What is worth saying out loud at the appointment. Each line is drawn only from numbers
     * that are actually on this page. No line names a dose, a medicine or a diagnosis.
     */
    private fun medicalPoints(): List<String> {
        val stats = report.span.stats
        val points = ArrayList<String>()
        val inRange = stats.rangePercents[2]
        points.add(
            "Time in range was " + inRange + " %. The aim agreed internationally is more than 70 % of the time between " +
                value(StatisticsCalculator.LOW_BELOW) + " and " + value(StatisticsCalculator.HIGH_ABOVE) + " " + unit.label + "."
        )
        val veryLow = stats.rangePercents[0] + stats.rangePercents[1]
        if (veryLow > 0) {
            points.add(
                "Time below " + value(StatisticsCalculator.LOW_BELOW) + " " + unit.label + " was " + veryLow + " %" +
                    (if (stats.rangePercents[0] > 0) ", of which " + stats.rangePercents[0] + " % was under " + value(StatisticsCalculator.VERY_LOW_BELOW) + " " + unit.label else "") +
                    ". The aim is less than 4 % below range, and less than 1 % below " + value(StatisticsCalculator.VERY_LOW_BELOW) + " " + unit.label + "."
            )
        } else {
            points.add("There was no time below " + value(StatisticsCalculator.LOW_BELOW) + " " + unit.label + " on this day.")
        }
        val lowsAtNight = report.span.lows.count { hourOfDay(it.start) in 0..5 || hourOfDay(it.start) >= 23 }
        if (lowsAtNight > 0) {
            points.add(lowsAtNight.toString() + " of the lows began between 11 at night and 6 in the morning. Night lows are the ones most worth discussing.")
        }
        val cv = stats.cv
        if (cv != null && cv >= 36.0) {
            points.add("The values swing widely (CV " + String.format(Locale.US, "%.0f %%", cv) + "). The aim is under 36 %.")
        }
        stats.gmi?.let {
            points.add("The estimated HbA1c from these values (GMI) is " + String.format(Locale.US, "%.1f %%", it) + ". A laboratory HbA1c can differ from it.")
        }
        if (stats.coveragePercent < 80) {
            points.add("Only " + stats.coveragePercent + " % of the day has values. Every number here is counted from what is there, so gaps make the picture less certain.")
        }
        points.add("This report describes what the sensor measured. It is not a diagnosis, and nothing in it is a dose, a medicine or a change to one. Any change to treatment is for the doctor or diabetes nurse to decide.")
        return points
    }

    /** The hour of the day (0 to 23) at which a moment falls, on the clock of the phone. */
    private fun hourOfDay(moment: Long): Int {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = moment
        return calendar.get(java.util.Calendar.HOUR_OF_DAY)
    }

    /** A bar for each of the 24 hours of the day. */
    private fun hourBars(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val max = chartMax()
        fun y(mgdl: Float): Float = bottom - (mgdl.coerceIn(CHART_MIN.toFloat(), max) - CHART_MIN) / (max - CHART_MIN) * (bottom - top)
        fill(canvas, left, y(StatisticsCalculator.HIGH_ABOVE.toFloat()), right, y(StatisticsCalculator.LOW_BELOW.toFloat()), BAND)
        for (tick in valueTicks(max)) {
            line(canvas, left, y(tick.first), right, y(tick.first), GRID)
            text(canvas, tick.second, left - 5f, y(tick.first) + 3f, 8f, GREY, align = Paint.Align.RIGHT)
        }
        line(canvas, left, top, left, bottom, AXIS)
        line(canvas, left, bottom, right, bottom, AXIS)
        val slot = (right - left) / 24
        for (hourOfDay in 0 until 24) {
            val center = left + slot * (hourOfDay + 0.5f)
            if (hourOfDay % 3 == 0) {
                val start = report.dayStart + (report.dayEnd - report.dayStart) * hourOfDay / 24
                text(canvas, hourFormat.format(Date(start)), center, bottom + 11f, 8f, GREY, align = Paint.Align.CENTER)
            }
            val hour = report.hours.firstOrNull { it.hourOfDay == hourOfDay } ?: continue
            val mean = hour.mean ?: continue
            val low = hour.lowest ?: continue
            val high = hour.highest ?: continue
            fill(canvas, center - slot * 0.32f, y(mean.toFloat()), center + slot * 0.32f, bottom, rangeColor(mean), 2f)
            line(canvas, center, y(low.toFloat()), center, y(high.toFloat()), TEXT, 0.9f)
            line(canvas, center - 2.2f, y(low.toFloat()), center + 2.2f, y(low.toFloat()), TEXT, 0.9f)
            line(canvas, center - 2.2f, y(high.toFloat()), center + 2.2f, y(high.toFloat()), TEXT, 0.9f)
        }
        text(canvas, unit.label, left, top - 3f, 7.5f, GREY)
    }

    companion object {
        /** A4 paper in points. */
        /** The line at the bottom of every page of the report. */
        const val CREDIT = "Powered by: Abdifatah Elmi"

        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val PAGES = 4

        private const val WIDTH = 595f
        private const val MARGIN = 36f
        private const val CHART_MIN = 40

        private val BLUE = 0xFF0B5ED7.toInt()
        private val BLUE_DARK = 0xFF0A47A6.toInt()
        private val TEXT = 0xFF172033.toInt()
        private val GREY = 0xFF55637A.toInt()
        private val GRID = 0xFFDDE4EE.toInt()
        private val AXIS = 0xFFB4C0D2.toInt()
        private val BAND = 0x2E22A35A
        private val RED = 0xFFC62828.toInt()
        private val AMBER = 0xFFD9730D.toInt()
        private val BROWN = 0xFF8A5A00.toInt()
        private val GREEN_TEXT = 0xFF23762B.toInt()

        /** Very low, low, in range, high, very high: the colours of the statistics screen. */
        private val RANGE_COLORS = intArrayOf(0xFF8E1B2E.toInt(), 0xFFE0523D.toInt(), 0xFF1E8E4E.toInt(), 0xFFF2B632.toInt(), 0xFFE07B1A.toInt())
        private val BOX_ACCENTS = intArrayOf(0xFF0B5ED7.toInt(), 0xFFC62828.toInt(), 0xFFD9730D.toInt(), 0xFF1E8E4E.toInt())

        /** How wide the pictures of the pages are when the app writes the PDF itself: 2.5 pixels for a point (180 for an inch). */
        internal const val PICTURE_PDF_WIDTH = 1488

        /** The same on a phone that has too little memory for that. */
        private const val SMALL_PICTURE_PDF_WIDTH = 992

        /**
         * Writes the report as a PDF file with two pages.
         *
         * First the PDF maker of Android draws the pages: the letters stay letters, sharp at
         * every size, and the file is small. That maker is a part of the phone. If it fails,
         * the app writes the file itself, with each page as a picture ([PicturePdf]).
         * Nothing goes to [out] before one of the two has made a whole file.
         *
         * @return how the file was made
         * @throws IOException if no file could be made
         */
        @Throws(IOException::class)
        fun writePdf(renderer: ReportRenderer, out: OutputStream): PdfKind {
            val letters = try {
                withAndroid(renderer)
            } catch (e: Exception) {
                null
            } catch (e: LinkageError) {
                null // an Android without the PDF maker
            } catch (e: OutOfMemoryError) {
                null
            }
            if (letters != null) {
                out.write(letters)
                out.flush()
                return PdfKind.LETTERS
            }
            val pictures = try {
                withPictures(renderer, PICTURE_PDF_WIDTH)
            } catch (e: OutOfMemoryError) {
                try {
                    withPictures(renderer, SMALL_PICTURE_PDF_WIDTH)
                } catch (again: OutOfMemoryError) {
                    throw IOException("too little memory for the report")
                }
            }
            out.write(pictures)
            out.flush()
            return PdfKind.PICTURES
        }

        /** The PDF maker of Android. Returns null if what it made is not a PDF file. */
        private fun withAndroid(renderer: ReportRenderer): ByteArray? {
            val buffer = ByteArrayOutputStream(64 * 1024)
            val document = PdfDocument()
            try {
                for (page in 0 until PAGES) {
                    val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, page + 1).create()
                    val sheet = document.startPage(info)
                    renderer.draw(sheet.canvas, page)
                    document.finishPage(sheet)
                }
                document.writeTo(buffer)
            } finally {
                document.close()
            }
            val bytes = buffer.toByteArray()
            return if (looksLikePdf(bytes)) bytes else null
        }

        /** The writer of this app: each page as a picture. */
        private fun withPictures(renderer: ReportRenderer, widthPx: Int): ByteArray {
            val buffer = ByteArrayOutputStream(512 * 1024)
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(renderer.report.dayStart))
            PicturePdf.write(
                buffer, PAGE_WIDTH, PAGE_HEIGHT, PAGES,
                "Daily glucose report $day",
                "Walhad Glucose Scan " + renderer.appVersion,
                pdfDate(renderer.createdAt)
            ) { page -> picture(renderer, page, widthPx) }
            return buffer.toByteArray()
        }

        /** True if [bytes] start and end as a PDF file does. */
        internal fun looksLikePdf(bytes: ByteArray): Boolean {
            if (bytes.size < 200) {
                return false
            }
            val head = String(bytes, 0, 8, Charsets.ISO_8859_1)
            val tail = String(bytes, bytes.size - 64, 64, Charsets.ISO_8859_1)
            return head.startsWith("%PDF-") && tail.contains("%%EOF")
        }

        /** A moment as a PDF file writes it: D:20261110143220+03'00' (by the clock of the phone). */
        internal fun pdfDate(moment: Long): String {
            val zone = java.util.TimeZone.getDefault()
            val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            format.timeZone = zone
            val minutes = zone.getOffset(moment) / 60_000
            val sign = if (minutes < 0) '-' else '+'
            val absolute = Math.abs(minutes)
            return "D:" + format.format(Date(moment)) + sign + String.format(Locale.US, "%02d'%02d'", absolute / 60, absolute % 60)
        }

        /** One page as a picture that is [widthPx] wide. */
        fun picture(renderer: ReportRenderer, page: Int, widthPx: Int): Bitmap {
            val scale = widthPx / WIDTH
            val bitmap = Bitmap.createBitmap(widthPx, Math.round(PAGE_HEIGHT * scale), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            renderer.draw(canvas, page)
            return bitmap
        }
    }
}

/** How a PDF file was made. */
enum class PdfKind {
    /** By the PDF maker of Android: the letters are letters. */
    LETTERS,

    /** By this app: each page is a picture. */
    PICTURES
}
