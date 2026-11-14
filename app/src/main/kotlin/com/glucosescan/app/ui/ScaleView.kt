package com.glucosescan.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.glucosescan.app.R
import com.glucosescan.app.core.GlucoseUnit

/**
 * A line from low to high with the target range marked and a round mark at the value. It
 * stands on the big coloured card, under the number, and is drawn in white. It only shows
 * where the value lies: the number and the words above it say the same.
 */
class ScaleView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var value = 0
    private var low = 80
    private var high = 180
    private var unit = GlucoseUnit.MGDL
    private var shown = false

    private val dp = resources.displayMetrics.density
    private val labelSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.scale_track)
        style = Paint.Style.FILL
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.scale_zone)
        style = Paint.Style.FILL
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.on_color)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.scale_marker_ring)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = AppFont.regular(context)
        color = context.getColor(R.color.on_color)
        textSize = labelSize
        textAlign = Paint.Align.CENTER
    }

    /** The upper end of the line in mg/dL: 300, or more if the target range of the user goes higher. */
    val scaleMax: Int
        get() = Math.max(SCALE_MAX_AT_LEAST, high + 40)

    /** Where the mark stands: 0 = left end, 1 = right end. For tests. */
    val position: Float
        get() = fraction(value)

    /**
     * @param value the shown value in mg/dL
     * @param low the lower end of the target range (mg/dL)
     * @param high the upper end of the target range (mg/dL)
     */
    fun show(value: Int, low: Int, high: Int, unit: GlucoseUnit) {
        this.value = value
        this.low = low
        this.high = Math.max(low, high)
        this.unit = unit
        shown = true
        contentDescription = context.getString(
            R.string.scale_description,
            GlucoseFormat.value(SCALE_MIN, unit),
            GlucoseFormat.value(scaleMax, unit),
            GlucoseFormat.value(low, unit),
            GlucoseFormat.value(this.high, unit) + " " + unit.label
        )
        invalidate()
    }

    private fun fraction(mgdl: Int): Float {
        val max = scaleMax
        return (mgdl.coerceIn(SCALE_MIN, max) - SCALE_MIN).toFloat() / (max - SCALE_MIN).toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = paddingTop + paddingBottom + (2 * MARKER_DP * dp + 4 * dp + labelSize * 1.25f).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!shown || width == 0 || height == 0) {
            return
        }
        val radius = MARKER_DP * dp
        val left = paddingLeft + radius
        val right = width - paddingRight - radius
        if (right <= left) {
            return
        }
        val centerY = paddingTop + radius
        val half = TRACK_DP * dp / 2
        fun x(mgdl: Int): Float = left + fraction(mgdl) * (right - left)

        canvas.drawRoundRect(left, centerY - half, right, centerY + half, half, half, trackPaint)
        canvas.drawRoundRect(x(low), centerY - half, x(high), centerY + half, half, half, zonePaint)

        // the two ends of the target range, as numbers under the line; left out if they would touch
        val lowText = GlucoseFormat.value(low, unit)
        val highText = GlucoseFormat.value(high, unit)
        val lowWidth = labelPaint.measureText(lowText)
        val highWidth = labelPaint.measureText(highText)
        val lowX = x(low).coerceIn(lowWidth / 2, width - lowWidth / 2)
        val highX = x(high).coerceIn(highWidth / 2, width - highWidth / 2)
        if (highX - lowX >= (lowWidth + highWidth) / 2 + 6 * dp) {
            val baseline = centerY + radius + 4 * dp + labelSize
            canvas.drawText(lowText, lowX, baseline, labelPaint)
            canvas.drawText(highText, highX, baseline, labelPaint)
        }

        // the mark: white, with a dark edge so that it also shows on the white part of the line
        val markX = x(value)
        canvas.drawCircle(markX, centerY, radius, ringPaint)
        canvas.drawCircle(markX, centerY, radius - 2.5f * dp, markerPaint)
    }

    companion object {
        const val SCALE_MIN = 40
        const val SCALE_MAX_AT_LEAST = 300
        private const val MARKER_DP = 9f
        private const val TRACK_DP = 8f
    }
}
