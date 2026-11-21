package com.glucosescan.app.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts live values again after the phone was switched on or the app was updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            MonitoringSyncWorker.schedule(context)   // the background safety-net survives reboots
            OnlineSync.schedule(context)             // so does the copy of the values to the online database
            LiveService.startFromBackground(context)
        }
    }
}

/**
 * Android calls this every few minutes while live values are on, also when the phone sleeps.
 * The service then looks whether values still come in (alarm "no values") and whether the
 * connection needs a new try. If Android has ended the service, it is started again. If that
 * is not allowed right now, a notification tells the user that live values have stopped.
 */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = LiveService.instance
        if (service != null) {
            service.onTick()
        } else {
            LiveService.startFromBackground(context)
        }
    }
}
