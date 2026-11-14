package com.glucosescan.app.testing

import com.glucosescan.app.ble.BleEvents
import com.glucosescan.app.ble.BleLink
import com.glucosescan.app.ble.LinkProblem
import com.glucosescan.app.data.LiveSensorRecord

/** Stands in for the phone's Bluetooth. The test plays the sensor by calling [events]. */
class FakeBleLink(val events: () -> BleEvents?) : BleLink {
    var problem: LinkProblem? = null
    val calls = ArrayList<String>()
    var scanning = false
    var target: String? = null
    var payload: ByteArray? = null

    override fun problem(): LinkProblem? = problem

    override fun startScan(sensor: LiveSensorRecord) {
        calls.add("scan")
        scanning = true
    }

    override fun stopScan() {
        scanning = false
    }

    override fun connect(address: String, wait: Boolean) {
        calls.add("connect $address " + if (wait) "wait" else "now")
        target = address
    }

    override fun discoverServices(): Boolean {
        calls.add("discover")
        return true
    }

    override fun writeUnlock(payload: ByteArray): Boolean {
        calls.add("unlock")
        this.payload = payload
        return true
    }

    override fun enableNotifications(): Boolean {
        calls.add("notify")
        return true
    }

    override fun close() {
        target = null
    }
}
