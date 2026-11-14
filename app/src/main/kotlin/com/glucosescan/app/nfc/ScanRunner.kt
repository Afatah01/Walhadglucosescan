package com.glucosescan.app.nfc

import android.content.Context
import android.nfc.Tag
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.SpeechText
import com.glucosescan.app.cloud.Backup
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.ScanMessage
import com.glucosescan.app.data.ScanOutcome
import com.glucosescan.app.data.Settings
import com.glucosescan.app.live.LiveBus
import com.glucosescan.app.live.LiveService
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.CurrentValues
import com.glucosescan.app.ui.Speaker
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one scan from start to end, on whatever screen the phone touched the sensor: reads and
 * saves, vibrates, says the value, and lets the live connection know about the sensor.
 */
object ScanRunner {
    private const val REPEAT_PAUSE_MILLIS = 3_000L
    private val scanning = AtomicBoolean(false)

    /** The sensor of the last good scan and when that scan ended (time since the phone started). */
    @Volatile
    private var lastGoodTagId: ByteArray? = null

    @Volatile
    private var lastGoodScanEnd = 0L

    /** True while a sensor is being read. */
    val isScanning: Boolean
        get() = scanning.get()

    /**
     * Call it on a background thread: reading takes about a second.
     * @return what came of the scan, or null if the scan was left out (another one is running,
     * or the phone found the same sensor again right after a good scan)
     */
    fun scan(context: Context, tag: Tag): ScanOutcome? {
        val app = context.applicationContext
        if (!scanning.compareAndSet(false, true)) {
            return null
        }
        try {
            // The phone can find the same sensor again while it is still held to it.
            // Right after a good scan that is not a new scan.
            val lastId = lastGoodTagId
            if (lastId != null && lastId.contentEquals(tag.id) && SystemClock.elapsedRealtime() - lastGoodScanEnd < REPEAT_PAUSE_MILLIS) {
                return null
            }
            LiveBus.changed()

            val settings = Settings(app)
            val store = GlucoseStore.get(app)
            val outcome = try {
                TagScanner.scan(tag, store, settings, appInfo(app))
            } catch (e: Exception) {
                ScanOutcome(ScanMessage.ERROR, AppClock.now(), text = e.javaClass.simpleName).also { settings.lastOutcome = it }
            }
            val good = !outcome.message.isProblem
            if (good) {
                lastGoodTagId = tag.id
                lastGoodScanEnd = SystemClock.elapsedRealtime()
            }
            vibrate(app, good)
            say(app, settings, store, good)
            if (settings.liveEnabled && !settings.linkUpEnabled) {
                // The scan may just have set up the sensor for live values.
                LiveService.start(app)
            }
            if (good) {
                Backup.request(app, soon = true) // the online copy, if the user has one
            }
            return outcome
        } finally {
            scanning.set(false)
            LiveBus.changed()
        }
    }

    /** For tests: forgets the last scan. */
    fun reset() {
        lastGoodTagId = null
        lastGoodScanEnd = 0L
    }

    private fun say(context: Context, settings: Settings, store: GlucoseStore, good: Boolean) {
        if (!settings.speakAfterScan) {
            return
        }
        val speaker = Speaker.get(context)
        if (!good) {
            speaker.say(SpeechText.SCAN_PROBLEM, "")
            return
        }
        val unit = settings.unit ?: GlucoseUnit.MGDL
        val now = AppClock.now()
        val current = CurrentValues.read(store, settings, now)
        val sentence = current.speech(unit, settings.speakTrend, if (settings.speakLowSoon) now else null)
        val shown = current.shown
        if (sentence != null && shown != null) {
            settings.lastSpokenTime = now
            speaker.say(sentence, SpeechText.number(shown, unit))
        } else if (!settings.linkUpEnabled) {
            // (with LibreLinkUp on, the number on the screen does not come from the scan: if
            // LibreLinkUp has no fresh value, nothing is said rather than something wrong)
            speaker.say(SpeechText.SCAN_NO_VALUE, "")
        }
    }

    fun appInfo(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        return "app: $version, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), phone: ${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private fun vibrate(context: Context, success: Boolean) {
        try {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            val failurePattern = longArrayOf(0, 80, 120, 80, 120, 80)
            if (Build.VERSION.SDK_INT >= 26) {
                val effect = if (success) {
                    VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
                } else {
                    VibrationEffect.createWaveform(failurePattern, -1)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                if (success) vibrator.vibrate(150) else vibrator.vibrate(failurePattern, -1)
            }
        } catch (e: RuntimeException) {
            // no vibration on this phone
        }
    }
}
