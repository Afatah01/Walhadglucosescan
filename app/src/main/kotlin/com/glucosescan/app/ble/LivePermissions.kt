package com.glucosescan.app.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Which permissions live values need on this version of Android, and which are missing. */
object LivePermissions {
    /** The permissions the user must give before the app can connect to the sensor. */
    fun bluetooth(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyArray() // Android 11 and older give Bluetooth to every app that asks for it when it is installed
        }
    }

    /**
     * Android 6 to 11 only let an app search for Bluetooth devices if it may read the
     * location. The app does not read the location. Without this permission the app still
     * connects, with the address the sensor gave during the scan.
     */
    fun search(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) emptyArray() else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Android 13 and newer ask before an app may show notifications. */
    fun notifications(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    }

    /** Everything to ask for when the user switches live values on. */
    fun all(): Array<String> = bluetooth() + search() + notifications()

    fun isGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun hasBluetooth(context: Context): Boolean = bluetooth().all { isGranted(context, it) }

    fun canSearch(context: Context): Boolean = hasBluetooth(context) && search().all { isGranted(context, it) }

    fun hasNotifications(context: Context): Boolean = notifications().all { isGranted(context, it) }
}
