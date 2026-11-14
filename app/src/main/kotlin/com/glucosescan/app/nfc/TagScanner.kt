package com.glucosescan.app.nfc

import android.nfc.Tag
import android.nfc.tech.NfcV
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.ScanHandler
import com.glucosescan.app.data.ScanOutcome
import com.glucosescan.app.data.Settings
import com.glucosescan.app.ui.AppClock
import java.io.IOException

/** The phone's NFC chip as an [NfcVLink]. */
class AndroidNfcVLink(private val nfcV: NfcV, override val id: ByteArray) : NfcVLink {
    @Throws(IOException::class)
    override fun transceive(command: ByteArray): ByteArray {
        try {
            return nfcV.transceive(command)
        } catch (e: SecurityException) {
            throw IOException("tag is out of date", e) // the phone has seen another tag in between
        } catch (e: IllegalStateException) {
            throw IOException("tag is closed", e)
        }
    }
}

/** Reads the sensor that the phone has found and saves the result. Call it from a background thread. */
object TagScanner {
    private const val CONNECT_RETRY_PAUSE_MILLIS = 250L

    fun scan(tag: Tag, store: GlucoseStore, settings: Settings, appInfo: String): ScanOutcome {
        val handler = ScanHandler(store, settings, appInfo)
        val reader = LibreNfcReader(now = { AppClock.now() })
        val savedMode = settings.readerMode
        LibreNfcReader.Mode.values().firstOrNull { it.name == savedMode }?.let { reader.mode = it }
        val scanner = SensorScanner(reader, now = { AppClock.now() }, wantsStreaming = { uid, _ -> LivePairing.isWanted(settings, uid) })

        val nfcV = NfcV.get(tag)
        if (nfcV == null) {
            val error = NfcReadException(NfcReadException.Reason.NOT_A_SENSOR, "the tag is not of the type NFC-V")
            return handler.handleFailure(error, AppClock.now(), null, emptyList())
        }

        try {
            connect(nfcV)
            val result = scanner.scan(AndroidNfcVLink(nfcV, tag.id))
            settings.readerMode = reader.mode.name
            val outcome = handler.handle(result, scanner.lastRawScan, scanner.log)
            LivePairing.save(settings, result, scanner)
            return outcome
        } catch (e: NfcReadException) {
            return handler.handleFailure(e, AppClock.now(), scanner.lastRawScan, scanner.log)
        } catch (e: IOException) {
            val error = NfcReadException(NfcReadException.Reason.NO_ANSWER, "no connection (${e.javaClass.simpleName}: ${e.message})", e)
            return handler.handleFailure(error, AppClock.now(), scanner.lastRawScan, scanner.log)
        } catch (e: RuntimeException) {
            return handler.handleFailure(e, AppClock.now(), scanner.lastRawScan, scanner.log)
        } finally {
            try {
                nfcV.close()
            } catch (e: Exception) {
                // nothing to do
            }
        }
    }

    /** Connects, with one more try after a short pause. */
    @Throws(IOException::class)
    private fun connect(nfcV: NfcV) {
        try {
            nfcV.connect()
        } catch (first: Exception) {
            try {
                Thread.sleep(CONNECT_RETRY_PAUSE_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            try {
                if (!nfcV.isConnected) {
                    nfcV.connect()
                }
            } catch (second: IOException) {
                throw second
            } catch (second: RuntimeException) {
                throw IOException("connect failed", second)
            }
        }
    }
}

/** Setting a sensor up for live values (Bluetooth) at the end of a scan. */
object LivePairing {
    /**
     * True if this scan must tell the sensor to send its values to this app: live values are
     * switched on, and the sensor is new for the app or the user asked to set it up again.
     */
    fun isWanted(settings: Settings, uid: ByteArray): Boolean {
        // With LibreLinkUp on, the sensor must stay with LibreLink: a scan then only reads, always.
        if (!settings.liveEnabled || settings.linkUpEnabled) {
            return false
        }
        val record = settings.liveSensor
        return settings.livePairWanted || record == null || !record.uid.contentEquals(uid)
    }

    /** Keeps what the Bluetooth side needs, if the scan has set the sensor up. */
    fun save(settings: Settings, result: com.glucosescan.app.core.ScanResult, scanner: SensorScanner) {
        if (!scanner.streamingTried) {
            return
        }
        val answer = scanner.streamingAnswer
        val sensor = result.sensor
        val raw = scanner.lastRawScan
        if (answer == null || sensor == null || raw == null) {
            com.glucosescan.app.data.LiveLog.add(settings, result.scanTime, "the scan could not set the sensor up for live values; scan again")
            return
        }
        settings.liveSensor = com.glucosescan.app.data.LiveSensorRecord(
            serial = sensor.serial,
            uid = raw.uid.copyOf(),
            patchInfo = raw.patchInfo.copyOf(),
            calibration = sensor.factoryCalibration,
            lifetime = sensor.lifetime,
            startTime = result.scanTime - sensor.age * 60_000L,
            streamingAnswer = answer.copyOf(),
            pairedTime = result.scanTime
        )
        settings.liveUnlockCount = 0
        settings.liveAddress = null
        settings.livePairWanted = false
        settings.liveWaitingSince = result.scanTime
        com.glucosescan.app.data.LiveLog.add(settings, result.scanTime, "sensor ${sensor.serial ?: "?"} set up for live values by a scan")
    }
}
