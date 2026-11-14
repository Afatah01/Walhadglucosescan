package com.glucosescan.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.widget.LinearLayout
import com.glucosescan.app.R

/**
 * The big coloured card with the value. Between its ground and its words it draws two big
 * soft circles, so that the card does not look flat. The circles are a little darker than
 * the ground, never lighter: white words stay easy to read on them.
 */
class HeroLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.hero_decor)
        style = Paint.Style.FILL
    }
    private val clip = Path()

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // the round corners of the ground (hero_*.xml)
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), CORNER_DP * dp, CORNER_DP * dp, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) {
            return
        }
        val saved = canvas.save()
        canvas.clipPath(clip)
        canvas.drawCircle(width - 36 * dp, -30 * dp, 150 * dp, paint)
        canvas.drawCircle(24 * dp, height + 40 * dp, 130 * dp, paint)
        canvas.restoreToCount(saved)
    }

    companion object {
        private const val CORNER_DP = 28f
    }
}
