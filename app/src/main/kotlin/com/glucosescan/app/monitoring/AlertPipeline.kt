package com.glucosescan.app.monitoring

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import com.glucosescan.app.core.AlertEngine
import com.glucosescan.app.core.AlarmKind
import com.glucosescan.app.core.AlarmRules
import com.glucosescan.app.core.SpeechText
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings
import com.glucosescan.app.live.AlarmSounder
import com.glucosescan.app.live.LiveNotifications
import com.glucosescan.app.ui.CurrentValue

/**
 * What the alert engine decided.
 *
 * @param sounded the alarm that must sound now, or null when there is nothing to sound
 * @param reason  the reason that holds right now (before cooldowns and silence), or null
 */
class AlertResult(val sounded: AlarmKind?, val reason: AlarmKind?)

/**
 * The one place where a reading is compared to the user's alert limits and the alert goes out.
 *
 * Used both by the live service (every minute and on every new reading) and by the background
 * safety-sync worker (when it runs without the service). The rules, the cooldown memory, the
 * notification and the persistent records are shared, so a reading can never produce two alarms
 * or two records. Forecasts may add a "low soon" reason; a forecast is never presented as a
 * measurement, and the recorded value is the measured one.
 */
object AlertPipeline {

    /**
     * Compares [current] to the user's limits and, when an alarm must sound, posts the
     * high-importance notification, rings the sounder and writes the alert event down.
     *
     * @param suppressNoData true when an ended Bluetooth sensor must not raise a "no values"
     *   alarm every quarter of an hour (the notification already says the sensor ended)
     * @param wake optional callback to keep the phone awake for the sound (the service has one)
     * @param log optional sink for the connection log
     */
    fun evaluateAndAlert(
        context: Context,
        settings: Settings,
        handler: Handler,
        sounder: AlarmSounder,
        current: CurrentValue,
        now: Long,
        stateWords: String,
        suppressNoData: Boolean,
        wake: ((Long) -> Unit)?,
        log: ((String) -> Unit)?
    ): AlertResult {
        val alarmSettings = settings.alarmSettings
        val minutesToLow = if (alarmSettings.lowSoonOn) current.minutesUntilBelow(alarmSettings.low, now) else null
        // the rapider of the two lines (shown and sensor) decides a fast rise or fall
        val ratePerMinute = listOfNotNull(current.forecast, current.sensorForecast)
            .map { it.slope }
            .maxByOrNull { Math.abs(it) }
        var reason = AlarmRules.reason(current.shown, current.sensor, now, alarmSettings, settings.liveWaitingSince, minutesToLow, ratePerMinute)
        if (suppressNoData && reason == AlarmKind.NO_DATA) {
            reason = null // an ended sensor sends nothing; the notification says so
        }
        val memory = settings.alarmMemory
        val sound = AlarmRules.decide(reason, now, alarmSettings, memory)
        val remembered = AlarmRules.remember(memory, reason, sound, now)
        if (remembered.lastKind != memory.lastKind || remembered.lastTime != memory.lastTime ||
            remembered.silencedKind != memory.silencedKind || remembered.silencedUntil != memory.silencedUntil ||
            remembered.lastSoonTime != memory.lastSoonTime
        ) {
            settings.alarmMemory = remembered
        }

        if (sound == null) {
            return AlertResult(null, reason)
        }

        wake?.invoke(AlarmSounder.SOUND_MILLIS + 10_000L)
        log?.invoke("alarm: ${sound.name}")

        val unit = settings.unit ?: com.glucosescan.app.core.GlucoseUnit.MGDL
        val shown = current.shown
        val measuredAt = shown?.timestamp ?: now
        val value = shown?.value
        val store = GlucoseStore.get(context)

        // the reading must be fresh to be presented as "now": a stale value can raise the
        // "no values" alarm, but it can never masquerade as a current reading here either
        val posted = try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(LiveNotifications.ID_ALARM, LiveNotifications.alarm(context, sound, current, stateWords, unit))
            manager != null
        } catch (e: RuntimeException) {
            false
        }

        val eventId = AlertEngine.eventId(sound, measuredAt)
        val detail = buildString {
            append(if (AlertEngine.isFresh(measuredAt, now)) "measured reading" else "stale reading (kept, not current)")
            value?.let { append(", value=").append(it).append(" mg/dL") }
            shown?.minuteChange?.let { if (!it.isNaN()) append(", change=").append(it).append(" mg/dL per minute") }
            if (!posted) append("; notification NOT posted (permission off or Android refused)")
        }
        // unique event id: a duplicate evaluation of the same reading records nothing
        store.recordAlertIfNew(eventId, sound.name, value, measuredAt, now, if (posted) "posted" else "failed", detail)

        val sentence = SpeechText.alarm(sound, current.shown, unit)
        val short = if (sound == AlarmKind.NO_DATA || shown == null) "" else SpeechText.number(shown, unit)
        // no ringtone: the alarm vibrates and the voice says what it is about
        try {
            sounder.play(words = if (settings.speakAlarms) sentence else null, short = short)
        } catch (e: RuntimeException) {
            log?.invoke("alarm sound failed: ${e.javaClass.simpleName}")
        }
        return AlertResult(sound, reason)
    }
}
