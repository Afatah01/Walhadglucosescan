package com.glucosescan.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.glucosescan.app.R
import com.glucosescan.app.core.Forecast
import com.glucosescan.app.core.Forecaster
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The chart of the last hours. Blue line and points, with a soft blue ground under the line;
 * red points outside the target range; red dashed lines at the two limits. With a forecast,
 * the chart goes on for half an hour after "now", and a dotted line shows where the values head.
 */
class ChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var values: List<Glucose> = emptyList()
    private var now: Long = 0
    private var hours: Int = 6
    private var unit: GlucoseUnit = GlucoseUnit.MGDL
    private var low: Int = 80
    private var high: Int = 180
    private var forecast: Forecast? = null

    /** True if the chart shows a forecast now. For tests. */
    val showsForecast: Boolean
        get() = forecast != null

    private val dp = resources.displayMetrics.density
    private val labelSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)

    private val blue = context.getColor(R.color.blue)
    private val red = context.getColor(R.color.red)

    private val gridPaint = Paint().apply {
        color = context.getColor(R.color.grid)
        strokeWidth = dp
        style = Paint.Style.STROKE
    }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = red
        strokeWidth = 1.5f * dp
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blue
        strokeWidth = 2 * dp
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = AppFont.regular(context)
        color = context.getColor(R.color.text_secondary)
        textSize = labelSize
    }
    private val patchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.surface) // the chart lies on a card
        style = Paint.Style.FILL
    }
    private val bandPaint = Paint().apply {
        color = context.getColor(R.color.chart_band)
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillFrom = context.getColor(R.color.chart_fill)
    private val fillTo = context.getColor(R.color.chart_fill_end)
    private var fillTop = Float.NaN
    private var fillBottom = Float.NaN
    private val forecastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.chart_forecast)
        strokeWidth = 2.5f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(1f * dp, 6 * dp), 0f)
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.chart_now)
        strokeWidth = dp
        style = Paint.Style.STROKE
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = AppFont.regular(context)
        color = context.getColor(R.color.text_secondary)
        textSize = labelSize * 1.2f
        textAlign = Paint.Align.CENTER
    }

    /**
     * @param values the saved values, oldest first
     * @param now the right edge of the chart, in milliseconds since 1970
     * @param hours how many hours the chart shows
     * @param low values under this are red (mg/dL)
     * @param high values over this are red (mg/dL)
     * @param forecast where the values head, or null. With it the chart shows half an hour more.
     */
    fun show(
        values: List<Glucose>,
        now: Long,
        hours: Int,
        unit: GlucoseUnit,
        low: Int,
        high: Int,
        forecast: Forecast? = null
    ) {
        this.forecast = forecast
        this.values = values
        this.now = now
        this.hours = hours
        this.unit = unit
        this.low = low
        this.high = high
        contentDescription = context.getString(R.string.chart_description, hours)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (now == 0L || width == 0 || height == 0) {
            return
        }

        val start = now - hours * HOUR
        // the right edge: "now", or half an hour later if there is a forecast to show
        val shownForecast = forecast
        val end = if (shownForecast != null) now + Forecaster.HORIZON_MINUTES * 60_000L else now
        val visible = values.filter { it.timestamp in start..now }

        val yMin = Y_MIN.toFloat()
        var highest = 0
        for (glucose in visible) {
            highest = Math.max(highest, glucose.readableValue)
        }
        val yMax = Math.max(Y_MAX_AT_LEAST, highest + 20).toFloat()

        // labels of the value axis
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
        val right = width - 8 * dp
        val top = 8 * dp
        val bottom = height - labelSize - 10 * dp
        if (right <= left || bottom <= top) {
            return
        }

        fun x(time: Long): Float = left + (time - start).toFloat() / (end - start).toFloat() * (right - left)
        fun y(value: Float): Float = bottom - (value.coerceIn(yMin, yMax) - yMin) / (yMax - yMin) * (bottom - top)

        // a soft green band over the target range
        if (high > low) {
            canvas.drawRect(left, y(high.toFloat()), right, y(low.toFloat()), bandPaint)
        }

        // lines and labels of the value axis
        for (tick in yTicks) {
            val py = y(tick.first)
            canvas.drawLine(left, py, right, py, gridPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(tick.second, left - 6 * dp, py + labelSize * 0.35f, labelPaint)
        }
        canvas.drawLine(left, bottom, right, bottom, gridPaint)
        canvas.drawLine(left, top, left, bottom, gridPaint)

        // lines and labels of the time axis, at full hours
        val stepHours = when {
            hours <= 6 -> 1
            hours <= 12 -> 2
            else -> 4
        }
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h a"
        val timeFormat = SimpleDateFormat(pattern, Locale.getDefault())
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = end
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        while (calendar.get(Calendar.HOUR_OF_DAY) % stepHours != 0) {
            calendar.add(Calendar.HOUR_OF_DAY, -1)
        }
        // first the lines, and how wide the widest label is
        val tickTimes = ArrayList<Long>()
        val tickHours = ArrayList<Int>()
        var widestLabel = 0f
        while (calendar.timeInMillis >= start) {
            val time = calendar.timeInMillis
            tickTimes.add(time)
            tickHours.add(calendar.get(Calendar.HOUR_OF_DAY))
            widestLabel = Math.max(widestLabel, labelPaint.measureText(timeFormat.format(Date(time))))
            val px = x(time)
            canvas.drawLine(px, top, px, bottom, gridPaint)
            calendar.add(Calendar.HOUR_OF_DAY, -stepHours)
        }
        // then the labels: every line, or every 2nd, 3rd ... line if they would touch
        val lineDistance = stepHours * HOUR.toFloat() / (end - start).toFloat() * (right - left)
        val every = Math.max(1, Math.ceil(((widestLabel + 10 * dp) / lineDistance).toDouble()).toInt())
        labelPaint.textAlign = Paint.Align.CENTER
        for (k in tickTimes.indices) {
            if ((tickHours[k] / stepHours) % every != 0) {
                continue
            }
            val label = timeFormat.format(Date(tickTimes[k]))
            val px = x(tickTimes[k])
            val half = labelPaint.measureText(label) / 2
            if (px + half <= width && px - half >= 0) {
                canvas.drawText(label, px, bottom + labelSize + 4 * dp, labelPaint)
            }
        }

        // the two limits
        for (limit in intArrayOf(low, high)) {
            if (limit > yMin && limit < yMax) {
                val path = Path()
                path.moveTo(left, y(limit.toFloat()))
                path.lineTo(right, y(limit.toFloat()))
                canvas.drawPath(path, limitPaint)
            }
        }

        if (shownForecast != null) {
            // a thin upright line at "now": left of it is measured, right of it is a guess
            canvas.drawLine(x(now), top, x(now), bottom, nowPaint)
        }

        if (visible.isEmpty()) {
            // a plain patch behind the words, so that no line runs through them
            val message = context.getString(R.string.chart_empty)
            val centerX = (left + right) / 2
            val centerY = (top + bottom) / 2
            val halfWidth = emptyPaint.measureText(message) / 2 + 8 * dp
            canvas.drawRoundRect(
                centerX - halfWidth,
                centerY - emptyPaint.textSize * 1.25f,
                centerX + halfWidth,
                centerY + emptyPaint.textSize * 0.65f,
                8 * dp,
                8 * dp,
                patchPaint
            )
            canvas.drawText(message, centerX, centerY, emptyPaint)
            return
        }

        // the line: values are joined when they are at most 20 minutes apart.
        // Under each joined piece lies a soft blue ground that fades out downwards.
        if (fillTop != top || fillBottom != bottom) {
            fillPaint.shader = LinearGradient(0f, top, 0f, bottom, fillFrom, fillTo, Shader.TileMode.CLAMP)
            fillTop = top
            fillBottom = bottom
        }
        val line = Path()
        var ground: Path? = null
        var pieceLength = 0
        var lastX = 0f
        fun closeGround() {
            val path = ground
            if (path != null && pieceLength >= 2) {
                path.lineTo(lastX, bottom)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            ground = null
            pieceLength = 0
        }
        var previous: Glucose? = null
        for (glucose in visible) {
            val px = x(glucose.timestamp)
            val py = y(glucose.readableValue.toFloat())
            if (previous != null && glucose.timestamp - previous.timestamp <= MAX_GAP) {
                line.lineTo(px, py)
                ground?.lineTo(px, py)
            } else {
                closeGround()
                line.moveTo(px, py)
                ground = Path().also {
                    it.moveTo(px, bottom)
                    it.lineTo(px, py)
                }
            }
            pieceLength++
            lastX = px
            previous = glucose
        }
        closeGround()
        canvas.drawPath(line, linePaint)

        // the points; smaller when there are very many of them (live values: one each minute)
        val radius = (if (visible.size > MANY_POINTS) 1.5f else if (hours <= 6) 2.5f else 2f) * dp
        for (glucose in visible) {
            pointPaint.color = if (glucose.value < low || glucose.value > high) red else blue
            canvas.drawCircle(x(glucose.timestamp), y(glucose.readableValue.toFloat()), radius, pointPaint)
        }

        // the newest value: a bigger point with a light edge
        val newest = visible.last()
        val newestX = x(newest.timestamp)
        val newestY = y(newest.readableValue.toFloat())
        canvas.drawCircle(newestX, newestY, 6.5f * dp, patchPaint)
        pointPaint.color = if (newest.value < low || newest.value > high) red else blue
        canvas.drawCircle(newestX, newestY, 4.5f * dp, pointPaint)

        // the forecast: a dotted line from the newest value to half an hour later
        if (shownForecast != null) {
            val to = shownForecast.time + Forecaster.HORIZON_MINUTES * 60_000L
            val dotted = Path()
            dotted.moveTo(x(shownForecast.time), y(shownForecast.level.toFloat()))
            dotted.lineTo(x(to), y(shownForecast.exact(to).toFloat()))
            canvas.drawPath(dotted, forecastPaint)
        }
    }

    companion object {
        private const val HOUR = 60 * 60_000L
        private const val MAX_GAP = 20 * 60_000L
        private const val MANY_POINTS = 150
        const val Y_MIN = 40
        const val Y_MAX_AT_LEAST = 300
    }
}
