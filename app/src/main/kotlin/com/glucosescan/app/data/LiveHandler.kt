package com.glucosescan.app.data

import com.glucosescan.app.core.GapFiller
import com.glucosescan.app.core.GlucoseProcessor
import com.glucosescan.app.core.LiveProcessor
import com.glucosescan.app.core.LiveStatus
import com.glucosescan.app.core.Timeline

/** What became of one Bluetooth message. */
class LiveOutcome(
    val status: LiveStatus,
    /** Minutes since the sensor was started, or -1. */
    val age: Int,
    /** How many values were saved: new ones and ones that fill holes. */
    val saved: Int,
    /** True if the message was thrown away because its values jump too much. */
    val tooNoisy: Boolean,
    /** Short technical words (English). */
    val detail: String
)

/** Takes a Bluetooth message of the sensor and saves what is new. It is the live twin of [ScanHandler]. */
class LiveHandler(
    store: GlucoseStore,
    private val settings: Settings
) {
    private val own = store.table(ValueSource.SENSOR)

    /**
     * @param message the 46 bytes as they came from the sensor
     * @param now when the message was complete, in milliseconds since 1970
     */
    fun handle(message: ByteArray, now: Long): LiveOutcome {
        val record = settings.liveSensor
            ?: return LiveOutcome(LiveStatus.BAD_DATA, -1, 0, false, "no sensor is set up")

        val result = LiveProcessor.process(record.toLiveSensor(), message, now)
        when (result.status) {
            LiveStatus.BAD_DATA -> return LiveOutcome(result.status, result.age, 0, false, result.detail)

            LiveStatus.STARTING -> {
                // nobody can expect a value yet: the "no values" alarm starts to wait from here
                settings.liveWaitingSince = now
                settings.liveLastMessageTime = now
                return LiveOutcome(result.status, result.age, 0, false, result.detail)
            }

            LiveStatus.EXPIRED -> {
                settings.liveLastMessageTime = now
                return LiveOutcome(result.status, result.age, 0, false, result.detail)
            }

            LiveStatus.OK -> {
                settings.liveLastMessageTime = now

                // A value from the future can only come from a wrong phone clock. Remove such
                // values, or they would hide every new value.
                own.deleteAfter(Timeline.floorToMinute(now) + ScanHandler.FUTURE_TOLERANCE_MILLIS)
                if (now - lastCleanup > CLEANUP_EVERY_MILLIS || now < lastCleanup) {
                    lastCleanup = now
                    own.deleteBefore(now - ScanHandler.KEEP_MILLIS)
                }

                val readings = result.readings
                val latest = own.latest()
                val done = GlucoseProcessor.process(readings, latest, record.serial)
                if (done.tooNoisy) {
                    return LiveOutcome(result.status, result.age, 0, true, "values jump too much")
                }
                own.insert(done.newValues)
                var saved = done.newValues.size
                if (latest != null && readings.isNotEmpty()) {
                    // after a short break in the connection the message also brings what was missed
                    val holes = GapFiller.fill(readings, own.since(readings.minOf { it.timestamp }), latest, record.serial)
                    own.insert(holes)
                    saved += holes.size
                }
                return LiveOutcome(result.status, result.age, saved, false, "good ${done.goodReadings}, faulty ${done.faultyReadings}, saved $saved")
            }
        }
    }

    companion object {
        private const val CLEANUP_EVERY_MILLIS = 60 * 60_000L

        @Volatile
        private var lastCleanup = 0L
    }
}

/** Keeps a short list of text lines about the live connection, for the screen "Live values". */
object LiveLog {
    private const val MAX_LINES = 40

    @Synchronized
    fun add(settings: Settings, now: Long, text: String) {
        val format = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        val lines = settings.liveLog.split('\n').filter { it.isNotEmpty() }.toMutableList()
        lines.add(format.format(java.util.Date(now)) + " " + text)
        settings.liveLog = lines.takeLast(MAX_LINES).joinToString("\n")
    }
}
