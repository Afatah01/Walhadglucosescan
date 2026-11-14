package com.glucosescan.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.glucosescan.app.R
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.HourBand

/**
 * The "typical day": for each hour of the day the middle value of all days (line) and the band
 * that holds 8 of 10 values. Hours with too few values stay empty.
 */
class DayChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var hours: List<HourBand?> = emptyList()
    private var unit: GlucoseUnit = GlucoseUnit.MGDL
    private var low = 80
    private var high = 180

    private val dp = resources.displayMetrics.density
    private val labelSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)

    private val gridPaint = Paint().apply {
        color = context.getColor(R.color.grid)
        strokeWidth = dp
        style = Paint.Style.STROKE
    }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.red)
        strokeWidth = 1.5f * dp
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.blue)
        strokeWidth = 2.5f * dp
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.blue)
        style = Paint.Style.FILL
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.day_band)
        style = Paint.Style.FILL
    }
    private val targetPaint = Paint().apply {
        color = context.getColor(R.color.chart_band)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = AppFont.regular(context)
        color = context.getColor(R.color.text_secondary)
        textSize = labelSize
    }

    /**
     * @param hours 24 entries, one for each hour of the day; null where there are too few values
     * @param low the target range of the user (mg/dL)
     */
    fun show(hours: List<HourBand?>, unit: GlucoseUnit, low: Int, high: Int) {
        this.hours = hours
        this.unit = unit
        this.low = low
        this.high = high
        contentDescription = context.getString(R.string.stats_day_title)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hours.size != 24 || width == 0 || height == 0) {
            return
        }
        val yMin = ChartView.Y_MIN.toFloat()
        var highest = 0.0
        for (band in hours) {
            if (band != null) {
                highest = Math.max(highest, band.high)
            }
        }
        val yMax = Math.max(ChartView.Y_MAX_AT_LEAST.toDouble(), highest + 20).toFloat()

        // labels of the value axis, as on the chart of the Home screen
        val yTicks = ArrayList<Pair<Float, String>>()
        if (unit == GlucoseUnit.MGDL) {
            val step = if (yMax > 400) 100 else 50
            var v = step
            while (v <= yMax) {
                if (v >= yMin) yTicks.add(Pair(v.toFloat(), v.toString()))
                v += step
            }
        } else {
            val step = if (yMax > 400) 6 else 3
            var mmol = step
            while (mmol / GlucoseUnit.EXCHANGE_RATE <= yMax) {
                val position = (mmol / GlucoseUnit.EXCHANGE_RATE).toFloat()
                if (position >= yMin) yTicks.add(Pair(position, mmol.toString()))
                mmol += step
            }
        }
        var labelWidth = 0f
        for (tick in yTicks) {
            labelWidth = Math.max(labelWidth, labelPaint.measureText(tick.second))
        }

        val left = labelWidth + 8 * dp
        val right = width - 10 * dp
        val top = 8 * dp
        val bottom = height - labelSize - 10 * dp
        if (right <= left || bottom <= top) {
            return
        }

        /** [hour] 0 to 24, also with a fraction */
        fun x(hour: Float): Float = left + hour / 24f * (right - left)
        fun y(value: Float): Float = bottom - (value.coerceIn(yMin, yMax) - yMin) / (yMax - yMin) * (bottom - top)

        if (high > low) {
            canvas.drawRect(left, y(high.toFloat()), right, y(low.toFloat()), targetPaint)
        }
        for (tick in yTicks) {
            val py = y(tick.first)
            canvas.drawLine(left, py, right, py, gridPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(tick.second, left - 6 * dp, py + labelSize * 0.35f, labelPaint)
        }
        canvas.drawLine(left, bottom, right, bottom, gridPaint)
        canvas.drawLine(left, top, left, bottom, gridPaint)

        // the time axis: every 6 hours
        val is24 = DateFormat.is24HourFormat(context)
        labelPaint.textAlign = Paint.Align.CENTER
        for (hour in intArrayOf(0, 6, 12, 18, 24)) {
            val px = x(hour.toFloat())
            canvas.drawLine(px, top, px, bottom, gridPaint)
            val label = if (is24) {
                (if (hour < 10) "0$hour" else "$hour") + ":00"
            } else {
                when (hour) {
                    0, 24 -> "12 AM"
                    12 -> "12 PM"
                    else -> if (hour < 12) "$hour AM" else "${hour - 12} PM"
                }
            }
            val half = labelPaint.measureText(label) / 2
            val cx = px.coerceIn(half, width - half)
            canvas.drawText(label, cx, bottom + labelSize + 4 * dp, labelPaint)
        }

        for (limit in intArrayOf(low, high)) {
            if (limit > yMin && limit < yMax) {
                val path = Path()
                path.moveTo(left, y(limit.toFloat()))
                path.lineTo(right, y(limit.toFloat()))
                canvas.drawPath(path, limitPaint)
            }
        }

        // The value of an hour stands in the middle of that hour. Hours next to each other are
        // joined; an hour with too few values breaks the line and the band.
        var k = 0
        while (k < 24) {
            if (hours[k] == null) {
                k++
                continue
            }
            var end = k
            while (end + 1 < 24 && hours[end + 1] != null) {
                end++
            }
            val band = Path()
            val line = Path()
            for (i in k..end) {
                val px = x(i + 0.5f)
                val item = hours[i]!!
                if (i == k) {
                    band.moveTo(px, y(item.high.toFloat()))
                    line.moveTo(px, y(item.median.toFloat()))
                } else {
                    band.lineTo(px, y(item.high.toFloat()))
                    line.lineTo(px, y(item.median.toFloat()))
                }
            }
            for (i in end downTo k) {
                band.lineTo(x(i + 0.5f), y(hours[i]!!.low.toFloat()))
            }
            band.close()
            if (end > k) {
                canvas.drawPath(band, bandPaint)
                canvas.drawPath(line, linePaint)
            }
            for (i in k..end) {
                canvas.drawCircle(x(i + 0.5f), y(hours[i]!!.median.toFloat()), 2.5f * dp, pointPaint)
            }
            k = end + 1
        }
    }
}
