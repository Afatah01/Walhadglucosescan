package com.glucosescan.app.live

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import com.glucosescan.app.ui.Speaker

/**
 * Makes an alarm noticed. The phone vibrates and the app **says** what the alarm is about,
 * again and again until the time is over or the user stops it.
 *
 * There is no ringtone and no beep any more: a voice says everything there is to say, so the
 * user does not have to look at the phone to know what is going on. If no words are given
 * (the user has switched the voice off) the phone only vibrates.
 */
class AlarmSounder(context: Context, private val handler: Handler) {
    private val context = context.applicationContext
    private var whenDone: (() -> Unit)? = null
    private var words: String? = null
    private var short: String? = null
    private val end = Runnable { finish(byTime = true) }
    private val again = Runnable { repeat() }

    /** True from [play] until the alarm has ended or was stopped. */
    var isPlaying: Boolean = false
        private set

    /**
     * Starts the alarm for [millis] milliseconds: vibration, and [words] said at once and
     * every few seconds after that.
     *
     * @param short what to say if the voice of the phone cannot speak English
     * @param done called when the alarm has run to its end (not when it was stopped)
     */
    fun play(millis: Long = SOUND_MILLIS, words: String? = null, short: String? = null, done: (() -> Unit)? = null) {
        stop()
        whenDone = done
        this.words = words
        this.short = short
        isPlaying = true
        plays++
        vibrate()
        say()
        if (!words.isNullOrBlank()) {
            handler.postDelayed(again, REPEAT_MILLIS)
        }
        handler.postDelayed(end, millis)
    }

    /** Stops the alarm, the vibration and the voice now. */
    fun stop() {
        finish(byTime = false)
    }

    private fun say() {
        val text = words
        if (text.isNullOrBlank()) {
            return
        }
        Speaker.get(context).say(text, short ?: text)
    }

    private fun repeat() {
        if (!isPlaying) {
            return
        }
        say()
        handler.postDelayed(again, REPEAT_MILLIS)
    }

    private fun finish(byTime: Boolean) {
        handler.removeCallbacks(end)
        handler.removeCallbacks(again)
        val wasPlaying = isPlaying
        isPlaying = false
        words = null
        short = null
        try {
            Speaker.get(context).stop()
        } catch (e: RuntimeException) {
            // no voice was speaking: nothing to stop
        }
        try {
            vibrator()?.cancel()
        } catch (e: RuntimeException) {
            // nothing to do
        }
        val callback = whenDone
        whenDone = null
        if (byTime && wasPlaying) {
            callback?.invoke()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vibrator = vibrator() ?: return
        try {
            if (!vibrator.hasVibrator()) {
                return
            }
            if (Build.VERSION.SDK_INT >= 26) {
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, 0), attributes)
            } else {
                vibrator.vibrate(PATTERN, 0)
            }
        } catch (e: RuntimeException) {
            // nothing to do
        }
    }

    companion object {
        /** How long an alarm goes on. */
        const val SOUND_MILLIS = 30_000L

        /** The words are said again after this time, as long as the alarm goes on. */
        const val REPEAT_MILLIS = 9_000L

        private val PATTERN = longArrayOf(0, 600, 400)

        /** How many times an alarm was started. For tests. */
        @Volatile
        var plays = 0
    }
}
