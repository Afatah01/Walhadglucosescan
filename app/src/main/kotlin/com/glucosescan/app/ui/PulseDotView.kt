package com.glucosescan.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.glucosescan.app.R

/**
 * A round dot. While [pulsing] is true, a ring grows out of it and fades, again and again:
 * the sign that values are coming in. The words next to the dot say the same.
 *
 * The ring does not move if the user has switched animations off in the Android settings.
 */
class PulseDotView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dot_live)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dot_live)
        style = Paint.Style.FILL
    }
    private var animator: ValueAnimator? = null
    private var phase = 0f

    var pulsing: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                update()
            }
        }

    /** True while the ring is moving. For tests. */
    val isRunning: Boolean
        get() = animator?.isRunning == true

    fun setColor(color: Int) {
        if (dotPaint.color != color) {
            dotPaint.color = color
            ringPaint.color = color
            invalidate()
        }
    }

    private fun animationsOn(): Boolean = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

    private fun update() {
        val run = pulsing && isAttachedToWindow && windowVisibility == VISIBLE && animationsOn()
        if (run) {
            if (animator == null) {
                val created = ValueAnimator.ofFloat(0f, 1f)
                created.duration = 1600L
                created.repeatCount = ValueAnimator.INFINITE
                created.repeatMode = ValueAnimator.RESTART
                created.interpolator = DecelerateInterpolator()
                created.addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                animator = created
                created.start()
            }
        } else {
            animator?.cancel()
            animator = null
            phase = 0f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        update()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        update()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = Math.min(width, height).toFloat()
        if (size <= 0f) {
            return
        }
        val centerX = width / 2f
        val centerY = height / 2f
        val dot = size * 0.26f
        if (animator != null && phase > 0f) {
            ringPaint.alpha = (150 * (1f - phase)).toInt().coerceIn(0, 255)
            canvas.drawCircle(centerX, centerY, dot + (size / 2f - dot) * phase, ringPaint)
        }
        canvas.drawCircle(centerX, centerY, dot, dotPaint)
    }
}
