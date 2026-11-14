package com.glucosescan.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.glucosescan.app.R

/**
 * A bar cut into the five ranges of the statistics, from very low (left) to very high (right).
 * The numbers stand under it in words: the bar is only the picture to them.
 */
class RangeBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var percents = IntArray(5)
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val emptyColor = context.getColor(R.color.chip)

    /** @param percents very low, low, in range, high, very high */
    fun show(percents: IntArray) {
        this.percents = percents.copyOf(5)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) {
            return
        }
        val all = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val clip = Path()
        clip.addRoundRect(all, 8 * dp, 8 * dp, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        paint.color = emptyColor
        canvas.drawRect(all, paint)
        val total = percents.sum()
        if (total > 0) {
            var x = 0f
            for (k in 0 until 5) {
                val w = width * percents[k].toFloat() / total
                paint.color = context.getColor(COLORS[k])
                canvas.drawRect(x, 0f, x + w, height.toFloat(), paint)
                x += w
            }
        }
        canvas.restore()
    }

    companion object {
        /** The colours of the five ranges, from very low to very high. */
        val COLORS = intArrayOf(R.color.range_very_low, R.color.range_low, R.color.range_target, R.color.range_high, R.color.range_very_high)
    }
}
