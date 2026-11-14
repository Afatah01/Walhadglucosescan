package com.glucosescan.app.core

/**
 * Finds the measurements that fill holes in what the app already knows.
 *
 * [GlucoseProcessor] only keeps measurements that are newer than the newest known value. That
 * is enough while the app only scans. With live values the newest value is often only a
 * minute old, while hours before it can be empty (the phone was out of reach of the sensor).
 * A scan brings the 8 hours before it, and a Bluetooth message the 15 minutes before it.
 * This object picks out of them what is missing.
 */
object GapFiller {
    /** A 15-minute value fills a hole only if no known value is nearer than this. */
    const val HISTORY_RADIUS: Long = 7 * 60_000L

    /**
     * @param readings the measurements of a scan or of a Bluetooth message
     * @param known the values the app has, at least from the time of the oldest reading on, in any order
     * @param latest the newest value the app has, or null
     * @return values older than [latest] that are missing, oldest first. They carry no change per minute.
     */
    fun fill(readings: List<SensorReading>, known: List<Glucose>, latest: Glucose?, sensorSerial: String?): List<Glucose> {
        if (latest == null) {
            return emptyList() // everything is new: GlucoseProcessor keeps it all
        }
        val times = LongArray(known.size) { known[it].timestamp }
        times.sort()

        val result = ArrayList<Glucose>()
        for (reading in readings) {
            if (reading.timestamp >= latest.timestamp || !reading.isOk) {
                continue
            }
            val whole = Glucose.toInteger(reading.glucoseValue) ?: continue
            val radius = if (reading.fromTrend) 0L else HISTORY_RADIUS
            if (hasOneWithin(times, reading.timestamp, radius)) {
                continue
            }
            result.add(Glucose(reading.timestamp, whole.coerceIn(-9_999L, 9_999L).toInt(), null, reading.fromTrend, sensorSerial))
        }
        result.sortBy { it.timestamp }
        return result
    }

    /** True if one of the sorted [times] is at most [radius] away from [time]. */
    internal fun hasOneWithin(times: LongArray, time: Long, radius: Long): Boolean {
        var low = 0
        var high = times.size - 1
        while (low <= high) { // finds the first time that is not before (time - radius)
            val middle = (low + high) ushr 1
            if (times[middle] < time - radius) low = middle + 1 else high = middle - 1
        }
        return low < times.size && times[low] <= time + radius
    }
}
