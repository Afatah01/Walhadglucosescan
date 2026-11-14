package com.glucosescan.app.reference

import com.glucosescan.app.core.GlucoseType
import com.glucosescan.app.core.Trend
import com.glucosescan.app.libre.SensorRegion
import com.glucosescan.app.libre.SensorState
import com.glucosescan.app.libre.SensorType

/**
 * Loads the results that the ORIGINAL Glucose Direct Swift code produced
 * (file reference_vectors.txt, made by reference/run.sh).
 */
object ReferenceVectors {
    private val records: Map<String, List<List<String>>> by lazy {
        val stream = ReferenceVectors::class.java.getResourceAsStream("/reference/reference_vectors.txt")
            ?: error("reference_vectors.txt is missing")
        val map = LinkedHashMap<String, MutableList<List<String>>>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val fields = line.split('|')
                map.getOrPut(fields[0]) { ArrayList() }.add(fields.subList(1, fields.size))
            }
        }
        map
    }

    /** All records of one kind. Each record is the list of its fields (without the kind). */
    fun of(kind: String): List<List<String>> = records[kind] ?: error("no records of kind $kind")

    fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex length" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** A Double written as the 16 hex digits of its 64 bits. */
    fun double(bits: String): Double = Double.fromBits(java.lang.Long.parseUnsignedLong(bits, 16))

    /** True if both are the same number. "Not a number" equals "not a number". */
    fun same(a: Double, b: Double): Boolean = (a.isNaN() && b.isNaN()) || a.toRawBits() == b.toRawBits()

    // The names the Swift code prints for its enum cases.
    val sensorTypes = mapOf(
        "unknown" to SensorType.UNKNOWN, "libre1" to SensorType.LIBRE1, "libre2EU" to SensorType.LIBRE2_EU,
        "libre2CA" to SensorType.LIBRE2_CA, "libre2US" to SensorType.LIBRE2_US, "libre3" to SensorType.LIBRE3,
        "libreProH" to SensorType.LIBRE_PRO_H, "libreSense" to SensorType.LIBRE_SENSE, "libreUS14day" to SensorType.LIBRE_US_14DAY
    )
    val sensorRegions = mapOf(
        "unknown" to SensorRegion.UNKNOWN, "european" to SensorRegion.EUROPEAN, "usa" to SensorRegion.USA,
        "australian" to SensorRegion.AUSTRALIAN, "eastern" to SensorRegion.EASTERN
    )
    val sensorStates = mapOf(
        "unknown" to SensorState.UNKNOWN, "expired" to SensorState.EXPIRED, "failure" to SensorState.FAILURE,
        "notYetStarted" to SensorState.NOT_YET_STARTED, "ready" to SensorState.READY, "shutdown" to SensorState.SHUTDOWN,
        "starting" to SensorState.STARTING
    )
    val trends = mapOf(
        "rapidlyRising" to Trend.RAPIDLY_RISING, "fastRising" to Trend.FAST_RISING, "rising" to Trend.RISING,
        "constant" to Trend.CONSTANT, "falling" to Trend.FALLING, "fastFalling" to Trend.FAST_FALLING,
        "rapidlyFalling" to Trend.RAPIDLY_FALLING, "unknown" to Trend.UNKNOWN
    )
    val glucoseTypes = mapOf("normal" to GlucoseType.NORMAL, "low" to GlucoseType.LOW, "high" to GlucoseType.HIGH)
}
