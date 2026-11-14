package com.glucosescan.app.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import com.glucosescan.app.MainActivity
import com.glucosescan.app.R
import com.glucosescan.app.core.AlarmKind
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.LiveState
import com.glucosescan.app.data.Settings
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.CurrentValue
import com.glucosescan.app.ui.Texts

/** The notifications of live values: the value that stays in the status bar, and the alarms. */
object LiveNotifications {
    const val CHANNEL_LIVE = "live"
    const val CHANNEL_ALARM = "alarm"
    const val ID_LIVE = 1
    const val ID_ALARM = 2
    const val ID_STOPPED = 3

    /** How long until "live values have stopped" is shown again. */
    const val STOPPED_AGAIN_MILLIS = 60 * 60_000L

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // quiet: it changes every minute and must never make a sound
        val live = NotificationChannel(CHANNEL_LIVE, context.getString(R.string.channel_live), NotificationManager.IMPORTANCE_LOW)
        live.description = context.getString(R.string.channel_live_text)
        live.setShowBadge(false)
        manager.createNotificationChannel(live)

        // The sound and the vibration of an alarm are made by the app itself (AlarmSounder), at
        // the alarm volume. The channel only makes the notification come up on the screen.
        val alarm = NotificationChannel(CHANNEL_ALARM, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH)
        alarm.description = context.getString(R.string.channel_alarm_text)
        alarm.setSound(null, null)
        alarm.enableVibration(false)
        manager.createNotificationChannel(alarm)
    }

    /** The words for what the live connection is doing. */
    fun stateText(context: Context, state: LiveState): String {
        return context.getString(
            when (state) {
                LiveState.OFF -> R.string.live_state_off
                LiveState.NEEDS_SCAN -> R.string.live_state_needs_scan
                LiveState.NO_PERMISSION -> R.string.live_state_no_permission
                LiveState.BLUETOOTH_OFF -> R.string.live_state_bluetooth_off
                LiveState.SEARCHING -> R.string.live_state_searching
                LiveState.CONNECTING -> R.string.live_state_connecting
                LiveState.CONNECTED -> R.string.live_state_connected
                LiveState.RECEIVING -> R.string.live_state_receiving
                LiveState.WARMING_UP -> R.string.live_state_warming_up
                LiveState.SENSOR_ENDED -> R.string.live_state_sensor_ended
                LiveState.REJECTED -> R.string.live_state_rejected
            }
        )
    }

    /** True if the state is one the user must do something about. */
    fun isProblem(state: LiveState): Boolean {
        return state == LiveState.NEEDS_SCAN || state == LiveState.NO_PERMISSION || state == LiveState.BLUETOOTH_OFF ||
            state == LiveState.SENSOR_ENDED || state == LiveState.REJECTED
    }

    /** The title of the value notification: "104 mg/dL →", or words if there is no current value. */
    fun liveTitle(context: Context, current: CurrentValue, unit: GlucoseUnit): String {
        val shown = current.shown
        if (shown == null || !current.isCurrent) {
            return context.getString(R.string.notification_no_value)
        }
        val arrow = current.state.trend?.arrow
        val value = Texts.valueWithUnit(context, shown, unit)
        return if (arrow != null) "$value $arrow" else value
    }

    /** The line under the title: the range and the time of the value, and what the connection does. */
    fun liveText(context: Context, current: CurrentValue, state: LiveState): String {
        return liveText(context, current, stateText(context, state), state == LiveState.RECEIVING)
    }

    /**
     * @param stateText what the connection is doing, in words
     * @param receiving true if values are coming in: then the words are left out
     */
    fun liveText(context: Context, current: CurrentValue, stateText: String, receiving: Boolean): String {
        val shown = current.shown
        if (shown == null || !current.isCurrent) {
            return stateText
        }
        val range = context.getString(
            when {
                current.below -> R.string.range_below
                current.above -> R.string.range_above
                else -> R.string.range_in
            }
        )
        val line = context.getString(R.string.value_time, range, Texts.timeOfDay(context, shown.timestamp))
        return if (receiving) line else context.getString(R.string.value_time, line, stateText)
    }

    fun live(context: Context, current: CurrentValue, state: LiveState, unit: GlucoseUnit): Notification {
        return live(context, current, stateText(context, state), state == LiveState.RECEIVING, unit)
    }

    fun live(context: Context, current: CurrentValue, stateText: String, receiving: Boolean, unit: GlucoseUnit): Notification {
        val builder = builder(context, CHANNEL_LIVE)
        val shown = current.shown
        if (shown != null && current.isCurrent) {
            builder.setSmallIcon(numberIcon(context, Texts.valueText(context, shown, unit)))
        } else {
            builder.setSmallIcon(R.drawable.ic_stat_drop)
        }
        builder.setContentTitle(liveTitle(context, current, unit))
        builder.setContentText(liveText(context, current, stateText, receiving))
        builder.setOngoing(true)
        builder.setOnlyAlertOnce(true)
        builder.setShowWhen(false)
        builder.setCategory(Notification.CATEGORY_SERVICE)
        builder.setColor(context.getColor(R.color.brand))
        builder.setContentIntent(openApp(context))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    fun alarmTitle(context: Context, kind: AlarmKind, current: CurrentValue, unit: GlucoseUnit): String {
        val shown = current.shown
        return when (kind) {
            AlarmKind.LOW -> if (shown != null) context.getString(R.string.alarm_low_title, Texts.valueWithUnit(context, shown, unit)) else context.getString(R.string.alarm_low_plain)
            AlarmKind.HIGH -> if (shown != null) context.getString(R.string.alarm_high_title, Texts.valueWithUnit(context, shown, unit)) else context.getString(R.string.alarm_high_plain)
            AlarmKind.NO_DATA -> context.getString(R.string.alarm_no_data_title)
            AlarmKind.LOW_SOON -> if (shown != null) context.getString(R.string.alarm_low_soon_title, Texts.valueWithUnit(context, shown, unit)) else context.getString(R.string.alarm_low_soon_plain)
        }
    }

    fun alarm(context: Context, kind: AlarmKind, current: CurrentValue, state: LiveState, unit: GlucoseUnit): Notification {
        return alarm(context, kind, current, stateText(context, state), unit)
    }

    fun alarm(context: Context, kind: AlarmKind, current: CurrentValue, stateText: String, unit: GlucoseUnit): Notification {
        val builder = builder(context, CHANNEL_ALARM)
        val text = when (kind) {
            AlarmKind.LOW -> context.getString(R.string.alarm_low_text)
            AlarmKind.HIGH -> context.getString(R.string.alarm_high_text)
            AlarmKind.NO_DATA -> context.getString(R.string.alarm_no_data_text, stateText)
            AlarmKind.LOW_SOON -> context.getString(R.string.alarm_low_soon_text)
        }
        builder.setSmallIcon(R.drawable.ic_stat_drop)
        builder.setContentTitle(alarmTitle(context, kind, current, unit))
        builder.setContentText(text)
        builder.setStyle(Notification.BigTextStyle().bigText(text))
        builder.setCategory(Notification.CATEGORY_ALARM)
        builder.setColor(context.getColor(R.color.red))
        builder.setAutoCancel(true)
        builder.setContentIntent(openApp(context))
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            builder.setPriority(Notification.PRIORITY_HIGH)
        }

        val silence = Intent(context, LiveService::class.java)
            .setAction(LiveService.ACTION_SILENCE)
            .putExtra(LiveService.EXTRA_KIND, kind.name)
        val pending = PendingIntent.getService(context, 10 + kind.ordinal, silence, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(
            Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_drop), context.getString(R.string.alarm_silence), pending).build()
        )
        return builder.build()
    }

    /**
     * Tells the user that live values (and with them the alarms) have stopped, when Android
     * has ended the service and does not let it start again by itself.
     */
    fun showStopped(context: Context) {
        // The try to start again comes every few minutes. The user is told once, and then
        // once an hour for as long as live values are on but cannot run.
        val settings = Settings(context)
        val now = AppClock.now()
        val shownAt = settings.liveStoppedShownTime
        if (shownAt != 0L && now >= shownAt && now - shownAt < STOPPED_AGAIN_MILLIS) {
            return
        }
        settings.liveStoppedShownTime = now

        createChannels(context)
        val text = context.getString(R.string.live_stopped_text)
        val builder = builder(context, CHANNEL_ALARM)
        builder.setSmallIcon(R.drawable.ic_stat_drop)
        builder.setContentTitle(context.getString(R.string.live_stopped_title))
        builder.setContentText(text)
        builder.setStyle(Notification.BigTextStyle().bigText(text))
        builder.setCategory(Notification.CATEGORY_ERROR)
        builder.setColor(context.getColor(R.color.red))
        builder.setAutoCancel(true)
        builder.setContentIntent(openApp(context))
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(ID_STOPPED, builder.build())
        } catch (e: RuntimeException) {
            // notifications are not allowed: nothing more can be done from here
        }
    }

    private fun builder(context: Context, channel: String): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, channel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The value as a small white picture for the status bar. */
    fun numberIcon(context: Context, text: String): Icon {
        val size = Math.max(24, Math.round(24 * context.resources.displayMetrics.density))
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        var textSize = size * 0.92f
        paint.textSize = textSize
        while (textSize > size * 0.3f && paint.measureText(text) > size) {
            textSize -= 1f
            paint.textSize = textSize
        }
        val metrics = paint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, size / 2f, baseline, paint)
        return Icon.createWithBitmap(bitmap)
    }
}
