package com.glucosescan.app.nfc

import com.glucosescan.app.core.RawScan
import com.glucosescan.app.core.ScanProcessor
import com.glucosescan.app.core.ScanResult
import com.glucosescan.app.core.ScanStatus
import com.glucosescan.app.libre.Libre2Ble
import com.glucosescan.app.libre.SensorType

/** One whole scan: asks the sensor, reads its memory and decodes it. */
class SensorScanner(
    private val reader: LibreNfcReader = LibreNfcReader(),
    private val now: () -> Long = { System.currentTimeMillis() },
    /**
     * Asked after a good scan: must the sensor be told to send its values over Bluetooth to this
     * app? Gets the id of the sensor and the result of the scan. The default never does it.
     */
    private val wantsStreaming: (uid: ByteArray, result: ScanResult) -> Boolean = { _, _ -> false }
) {
    /** True if the last scan sent the command "enable streaming". */
    var streamingTried: Boolean = false
        private set

    /** The 6 bytes the sensor answered to "enable streaming" in the last scan, or null. */
    var streamingAnswer: ByteArray? = null
        private set

    /** The bytes of the last scan, for the details screen. Null if nothing was read. */
    var lastRawScan: RawScan? = null
        private set

    val log: List<String>
        get() = reader.log

    /**
     * @throws NfcReadException if the sensor could not be read
     */
    @Throws(NfcReadException::class)
    fun scan(link: NfcVLink): ScanResult {
        reader.log.clear()
        lastRawScan = null
        streamingTried = false
        streamingAnswer = null

        val (uid, patchInfo) = reader.readPatchInfo(link)

        // Do not read the memory of a sensor this app cannot use.
        if (patchInfo.size < 6 || SensorType.of(patchInfo) != SensorType.LIBRE2_EU) {
            val scan = RawScan(uid, patchInfo, ByteArray(0), now())
            lastRawScan = scan
            return ScanProcessor.process(scan)
        }

        var scan = RawScan(uid, patchInfo, reader.readFram(link), now())
        lastRawScan = scan
        var result = ScanProcessor.process(scan)

        // The sensor may have written to its memory while it was read. Read once more.
        if (result.status == ScanStatus.BAD_DATA && result.checksumFailed) {
            reader.log.add("first read: ${result.detail}; reading again")
            scan = RawScan(uid, patchInfo, reader.readFram(link), now())
            lastRawScan = scan
            result = ScanProcessor.process(scan)
        }

        // Only a sensor that runs (or warms up) is set up for live values, and only if asked.
        val runs = result.status == ScanStatus.OK || result.status == ScanStatus.STARTING
        if (runs && wantsStreaming(uid, result)) {
            streamingTried = true
            try {
                streamingAnswer = reader.enableStreaming(link, uid, patchInfo, Libre2Ble.UNLOCK_CODE)
            } catch (e: NfcReadException) {
                // the scan itself stays good; the next scan tries again
                reader.log.add("enable streaming failed: ${e.message}")
            }
        }

        return result
    }
}
