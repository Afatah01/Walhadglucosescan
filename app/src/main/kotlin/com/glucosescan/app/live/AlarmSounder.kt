package com.glucosescan.app.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Makes the sound of an alarm: the alarm tone of the phone, at the alarm volume of the phone,
 * together with vibration, for some seconds.
 */
class AlarmSounder(context: Context, private val handler: Handler) {
    private val context = context.applicationContext
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var whenDone: (() -> Unit)? = null
    private val end = Runnable { finish(byTime = true) }

    /** True from [play] until the sound has ended or was stopped (also on a phone that could make no sound). */
    var isPlaying: Boolean = false
        private set

    /**
     * @param done called when the sound has run to its end (not when it was stopped)
     */
    fun play(millis: Long = SOUND_MILLIS, done: (() -> Unit)? = null) {
        stop()
        whenDone = done
        isPlaying = true
        plays++
        vibrate()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val media = MediaPlayer()
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            media.setDataSource(context, uri)
            media.isLooping = true
            media.prepare()
            media.start()
            player = media
        } catch (e: Exception) {
            // no alarm tone on this phone, or it cannot be played: a plain tone instead
            player = null
            try {
                val generator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)
                tone = generator
            } catch (e2: RuntimeException) {
                tone = null
            }
        }
        handler.postDelayed(end, millis)
    }

    /** Stops the sound and the vibration now. */
    fun stop() {
        finish(byTime = false)
    }

    private fun finish(byTime: Boolean) {
        handler.removeCallbacks(end)
        val wasPlaying = isPlaying
        isPlaying = false
        try {
            player?.stop()
        } catch (e: RuntimeException) {
            // nothing to do
        }
        try {
            player?.release()
        } catch (e: RuntimeException) {
            // nothing to do
        }
        player = null
        try {
            tone?.stopTone()
            tone?.release()
        } catch (e: RuntimeException) {
            // nothing to do
        }
        tone = null
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
        const val SOUND_MILLIS = 20_000L
        private val PATTERN = longArrayOf(0, 600, 400)

        /** How many times a sound was started. For tests. */
        @Volatile
        var plays = 0
    }
}
