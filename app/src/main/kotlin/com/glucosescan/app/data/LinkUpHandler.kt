package com.glucosescan.app.data

import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.Timeline
import com.glucosescan.app.linkup.LinkUpData

/** What became of one answer of LibreLinkUp. */
class LinkUpOutcome(
    /** How many values were new and were saved. */
    val saved: Int,
    /** True if the answer brought a value that is newer than everything saved before. */
    val newValue: Boolean,
    /** The time of the newest value in the answer (milliseconds since 1970), or 0 if it had none. */
    val newestTime: Long,
    /** True if the newest value lies in the future: the clock of this phone is wrong. */
    val clockWrong: Boolean
)

/**
 * Saves the values that LibreLinkUp gave. They go into their own set (see [ValueSource]) and
 * are never mixed with the values the app has read from the sensor itself.
 */
class LinkUpHandler(
    store: GlucoseStore,
    private val settings: Settings
) {
    private val table = store.table(ValueSource.LINKUP)

    /**
     * @param data what LibreLinkUp answered
     * @param now milliseconds since 1970
     */
    fun handle(data: LinkUpData, now: Long): LinkUpOutcome {
        if (now - lastCleanup > CLEANUP_EVERY_MILLIS || now < lastCleanup) {
            lastCleanup = now
            table.deleteBefore(now - ScanHandler.KEEP_MILLIS)
        }

        val serial = serialFor(data.sensorSerial)
        val limit = Timeline.floorToMinute(now) + ScanHandler.FUTURE_TOLERANCE_MILLIS
        val before = table.latest()
        var saved = 0
        var newValue = false
        var clockWrong = false

        // The newest value first: if an older value of the list falls into the same minute,
        // the one-minute value wins.
        val current = data.current
        if (current != null) {
            if (current.time > limit) {
                clockWrong = true
            } else {
                val value = Glucose(current.time, current.mgdl, null, true, serial)
                val previous = before?.takeIf {
                    it.sensorSerial == serial && it.timestamp < value.timestamp && value.timestamp - it.timestamp <= CHANGE_OVER_MILLIS
                }
                val added = table.insert(listOf(value.withChangeFrom(previous)))
                saved += added
                newValue = added > 0 && (before == null || value.timestamp > before.timestamp)
            }
        }

        val history = data.history
            .filter { it.time <= limit && it.time >= now - ScanHandler.KEEP_MILLIS }
            .map { Glucose(it.time, it.mgdl, null, false, serial) }
        saved += table.insert(history)

        if ((data.sensorSerial ?: "") != settings.linkUpSensorSerial) {
            settings.linkUpSensorSerial = data.sensorSerial ?: ""
        }
        if (data.sensorStart != settings.linkUpSensorStart) {
            settings.linkUpSensorStart = data.sensorStart
        }
        return LinkUpOutcome(saved, newValue, current?.time ?: 0L, clockWrong)
    }

    companion object {
        /**
         * The values from LibreLinkUp carry this in front of the serial number of the sensor.
         * So "Correct the value", which belongs to one sensor and to the app's own formula,
         * can never change a number that comes from Abbott.
         */
        const val SERIAL_PREFIX = "LibreLinkUp"

        fun serialFor(sensorSerial: String?): String = if (sensorSerial.isNullOrEmpty()) SERIAL_PREFIX else "$SERIAL_PREFIX $sensorSerial"

        /** The change per minute is counted against a value that is at most this much older. */
        private const val CHANGE_OVER_MILLIS = 16 * 60_000L
        private const val CLEANUP_EVERY_MILLIS = 60 * 60_000L

        @Volatile
        private var lastCleanup = 0L
    }
}
