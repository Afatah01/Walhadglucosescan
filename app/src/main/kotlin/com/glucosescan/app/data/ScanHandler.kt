package com.glucosescan.app.data

import com.glucosescan.app.core.GapFiller
import com.glucosescan.app.core.GlucoseProcessor
import com.glucosescan.app.core.RawScan
import com.glucosescan.app.core.ScanResult
import com.glucosescan.app.core.ScanStatus
import com.glucosescan.app.core.Timeline
import com.glucosescan.app.libre.FactoryCalibration
import com.glucosescan.app.libre.FramParser
import com.glucosescan.app.nfc.LibreNfcReader
import com.glucosescan.app.nfc.NfcReadException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Takes the result of a scan, saves what is new and says what to tell the user. */
class ScanHandler(
    store: GlucoseStore,
    private val settings: Settings,
    private val appInfo: String = ""
) {
    /** A scan is always saved with the values the app has read itself, whatever the screens show. */
    private val own = store.table(ValueSource.SENSOR)

    /** The sensor was read and decoded (which does not mean that it gave values). */
    fun handle(result: ScanResult, raw: RawScan?, log: List<String>): ScanOutcome {
        val now = result.scanTime

        // A value from the future can only come from a wrong phone clock. Remove such values,
        // or they would hide every new value.
        val removedFuture = own.deleteAfter(Timeline.floorToMinute(now) + FUTURE_TOLERANCE_MILLIS)
        own.deleteBefore(now - KEEP_MILLIS)

        val sensor = result.sensor
        if (sensor != null) {
            settings.lastSensor = SensorSnapshot(
                serial = sensor.serial,
                typeLabel = sensor.type.label,
                regionLabel = sensor.region.label,
                status = result.status,
                age = sensor.age,
                lifetime = sensor.lifetime,
                scanTime = now
            )
        }

        var saved = 0
        var filled = 0
        var processed: GlucoseProcessor.Result? = null
        val outcome: ScanOutcome = when (result.status) {
            ScanStatus.OK -> {
                val readings = result.readings!!
                val all = readings.all
                val latest = own.latest()
                val done = GlucoseProcessor.process(all, latest, sensor?.serial)
                processed = done
                own.insert(done.newValues)
                saved = done.newValues.size
                // With live values the newest value is often newer than most of the scan. The
                // scan then fills the holes before it (the phone was out of reach for a while).
                if (!done.tooNoisy && latest != null && all.isNotEmpty()) {
                    val holes = GapFiller.fill(all, own.since(all.minOf { it.timestamp }), latest, sensor?.serial)
                    own.insert(holes)
                    filled = holes.size
                }
                val newestIsGood = readings.trend.isNotEmpty() && readings.trend.last().isOk
                when {
                    done.tooNoisy -> ScanOutcome(ScanMessage.TOO_NOISY, now)
                    !newestIsGood -> ScanOutcome(ScanMessage.SENSOR_NO_VALUE, now)
                    done.newValues.isEmpty() -> ScanOutcome(ScanMessage.OK_NO_NEW, now)
                    else -> ScanOutcome(ScanMessage.OK, now)
                }
            }
            ScanStatus.NOT_STARTED -> ScanOutcome(ScanMessage.NOT_STARTED, now)
            ScanStatus.STARTING -> {
                val left = sensor?.remainingWarmupTime
                if (left != null) ScanOutcome(ScanMessage.STARTING, now, number = Math.max(1, left)) else ScanOutcome(ScanMessage.STARTING_NO_TIME, now)
            }
            ScanStatus.EXPIRED -> ScanOutcome(ScanMessage.EXPIRED, now)
            ScanStatus.SHUT_DOWN, ScanStatus.FAILURE -> ScanOutcome(ScanMessage.STOPPED, now)
            ScanStatus.UNKNOWN_STATE -> ScanOutcome(ScanMessage.UNKNOWN_STATE, now, text = result.detail)
            ScanStatus.UNSUPPORTED_SENSOR -> ScanOutcome(ScanMessage.UNSUPPORTED, now, text = result.sensorType?.label ?: "?")
            ScanStatus.BAD_DATA -> ScanOutcome(ScanMessage.BAD_DATA, now)
        }

        settings.lastOutcome = outcome
        settings.lastScanDetails = details(outcome, result, raw, log, processed, saved, filled, removedFuture, null)
        return outcome
    }

    /** The sensor could not be read. */
    fun handleFailure(error: Throwable, now: Long, raw: RawScan?, log: List<String>): ScanOutcome {
        val outcome = if (error is NfcReadException) {
            when (error.reason) {
                NfcReadException.Reason.NO_ANSWER -> ScanOutcome(ScanMessage.LOST, now)
                NfcReadException.Reason.NOT_A_SENSOR -> ScanOutcome(ScanMessage.NOT_A_SENSOR, now)
                NfcReadException.Reason.BAD_ANSWER -> ScanOutcome(ScanMessage.BAD_DATA, now)
            }
        } else {
            ScanOutcome(ScanMessage.ERROR, now, text = error.javaClass.simpleName)
        }
        settings.lastOutcome = outcome
        settings.lastScanDetails = details(outcome, null, raw, log, null, 0, 0, 0, error)
        return outcome
    }

    private fun details(
        outcome: ScanOutcome,
        result: ScanResult?,
        raw: RawScan?,
        log: List<String>,
        processed: GlucoseProcessor.Result?,
        saved: Int,
        filled: Int,
        removedFuture: Int,
        error: Throwable?
    ): String {
        val text = StringBuilder()
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")

        text.append("time: ").append(format.format(Date(outcome.time))).append('\n')
        if (appInfo.isNotEmpty()) {
            text.append(appInfo).append('\n')
        }
        text.append("outcome: ").append(outcome.message.name).append('\n')
        if (error != null) {
            text.append("error: ").append(error.javaClass.simpleName).append(": ").append(error.message ?: "").append('\n')
        }
        if (result != null) {
            text.append("status: ").append(result.status.name).append(" (").append(result.detail).append(")\n")
            text.append("sensor type: ").append(result.sensorType?.label ?: "?").append('\n')
            val sensor = result.sensor
            if (sensor != null) {
                text.append("serial: ").append(sensor.serial ?: "?").append('\n')
                text.append("region: ").append(sensor.region.label).append('\n')
                text.append("state: ").append(sensor.state.name).append(", age: ").append(sensor.age)
                    .append(", lifetime: ").append(sensor.lifetime).append('\n')
                text.append("calibration: ").append(sensor.factoryCalibration.toString()).append('\n')
            }
            val plain = result.plainFram
            if (plain != null && sensor != null && sensor.factoryCalibration.isUsable) {
                val records = FramParser.records(plain, FactoryCalibration.libreStyleCalibration(plain))
                text.append("trend index: ").append(records.trendIndex).append(" (age mod 16 = ").append(records.age % 16).append(")\n")
                text.append("history index: ").append(records.historyIndex).append(" (expected ")
                    .append(if (records.age >= 3) ((records.age - 3) / 15) % 32 else 0).append(")\n")
                text.append("trend raw, newest first: ").append(records.trend.joinToString(",") { it.rawGlucose.toString() }).append('\n')
                text.append("history raw, newest first: ").append(records.history.joinToString(",") { it.rawGlucose.toString() }).append('\n')
            }
            val readings = result.readings
            if (readings != null) {
                text.append("readings: ").append(readings.trend.size).append(" one-minute, ").append(readings.history.size)
                    .append(" 15-minute, history time known: ").append(readings.historyTimeKnown).append('\n')
            }
        }
        if (processed != null) {
            text.append("good: ").append(processed.goodReadings).append(", faulty: ").append(processed.faultyReadings)
                .append(", saved: ").append(saved).append(", holes filled: ").append(filled)
                .append(", too noisy: ").append(processed.tooNoisy).append('\n')
        }
        if (removedFuture > 0) {
            text.append("removed values with a time in the future: ").append(removedFuture).append('\n')
        }
        for (line in log) {
            text.append("nfc: ").append(line).append('\n')
        }
        if (raw != null) {
            text.append("id: ").append(LibreNfcReader.hex(raw.uid)).append('\n')
            text.append("patch info: ").append(LibreNfcReader.hex(raw.patchInfo)).append('\n')
            text.append("memory as read: ").append(LibreNfcReader.hex(raw.fram)).append('\n')
        }
        return text.toString()
    }

    companion object {
        /** Values up to 2 minutes ahead of the phone clock are accepted. */
        const val FUTURE_TOLERANCE_MILLIS = 2 * 60_000L

        /** Values are kept for 90 days. */
        const val KEEP_MILLIS = 90L * 24 * 60 * 60_000L
    }
}
