package com.glucosescan.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.glucosescan.app.core.AlarmKind
import com.glucosescan.app.core.AlarmMemory
import com.glucosescan.app.core.AlarmSettings
import com.glucosescan.app.core.AnswerLanguage
import com.glucosescan.app.core.Correction
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.LiveSensor
import com.glucosescan.app.core.ScanStatus
import com.glucosescan.app.libre.FactoryCalibration

/** What the last scan told about the sensor. */
class SensorSnapshot(
    val serial: String?,
    val typeLabel: String,
    val regionLabel: String,
    val status: ScanStatus,
    /** Minutes since the sensor was started, at the time of the scan. */
    val age: Int,
    /** The sensor counts as ended when its age reaches this (minutes). */
    val lifetime: Int,
    /** Milliseconds since 1970. */
    val scanTime: Long
)

/** What happened in the last scan, for the line under the value. */
class ScanOutcome(
    val message: ScanMessage,
    val time: Long,
    /** A number that belongs to the message (for example minutes), or 0. */
    val number: Int = 0,
    /** A text that belongs to the message (for example the sensor type), or "". */
    val text: String = ""
)

enum class ScanMessage(val isProblem: Boolean) {
    OK(false),
    OK_NO_NEW(false),
    SENSOR_NO_VALUE(true),
    TOO_NOISY(true),
    NOT_STARTED(true),
    STARTING(false),
    STARTING_NO_TIME(false),
    EXPIRED(true),
    STOPPED(true),
    UNKNOWN_STATE(true),
    UNSUPPORTED(true),
    BAD_DATA(true),
    LOST(true),
    NOT_A_SENSOR(true),
    ERROR(true)
}

/**
 * The sensor that sends live values, as it was set up by a scan. Everything the app needs to
 * find the sensor over Bluetooth and to read its messages.
 */
class LiveSensorRecord(
    val serial: String?,
    /** 8 bytes, in the order they are sent over NFC (the last byte is 0xE0). */
    val uid: ByteArray,
    val patchInfo: ByteArray,
    val calibration: FactoryCalibration,
    /** The sensor counts as ended when its age reaches this (minutes). */
    val lifetime: Int,
    /** When the sensor was started (milliseconds since 1970), as far as the scan could tell. */
    val startTime: Long,
    /** The 6 bytes the sensor answered when Bluetooth was switched on. Its Bluetooth address, turned round. */
    val streamingAnswer: ByteArray,
    /** When the scan set the sensor up (milliseconds since 1970). */
    val pairedTime: Long
) {
    fun toLiveSensor(): LiveSensor = LiveSensor(serial, uid, patchInfo, calibration, lifetime)

    /** The Bluetooth address "AA:BB:CC:DD:EE:FF" that the answer of the sensor stands for, or null. */
    val addressFromAnswer: String?
        get() {
            if (streamingAnswer.size != 6) {
                return null
            }
            return streamingAnswer.reversedArray().joinToString(":") { String.format("%02X", it.toInt() and 0xFF) }
        }

    /** The name the sensor shows over Bluetooth: "ABBOTT" and its serial number. */
    val bluetoothName: String?
        get() = if (serial != null) "ABBOTT$serial" else null
}

/** What the live connection is doing. The code is what is saved. */
enum class LiveState {
    /** Live values are switched off. */
    OFF,

    /** Live values are on, but no sensor was set up yet: a scan is needed. */
    NEEDS_SCAN,

    /** The app may not use Bluetooth (or may not show notifications). */
    NO_PERMISSION,

    /** Bluetooth is switched off on the phone. */
    BLUETOOTH_OFF,

    /** Looking for the sensor. */
    SEARCHING,

    /** The sensor was found. The connection is being set up. */
    CONNECTING,

    /** Connected. Waiting for the first value. */
    CONNECTED,

    /** Values are coming in. */
    RECEIVING,

    /** The sensor is still warming up (first 60 minutes). */
    WARMING_UP,

    /** The sensor has reached the end of its time. */
    SENSOR_ENDED,

    /** The sensor does not accept this phone any more: a scan is needed. */
    REJECTED
}

/** What the link to LibreLinkUp is doing. The name is what is saved. */
enum class LinkUpState {
    /** Not switched on. */
    OFF,

    /** Switched on; the first answer is not there yet. */
    STARTING,

    /** LibreLinkUp answers and has a fresh value. */
    RECEIVING,

    /** LibreLinkUp answers, but its newest value is old: the phone with LibreLink has sent nothing new. */
    NO_NEW_VALUE,

    /** No answer: no internet, or the server cannot be reached. */
    NO_INTERNET,

    /** The e-mail and the password are not accepted. The user must type them again. */
    WRONG_LOGIN,

    /** Abbott wants its terms of use or its privacy notice accepted again. The user must do that. */
    NEEDS_STEP,

    /** Too many tries: the server wants the app to wait. */
    LOCKED,

    /** Nobody shares values with this account (any more). */
    NO_PATIENT,

    /** The server answers with an error, or with something the app does not understand. */
    SERVER_PROBLEM,

    /** The newest value of LibreLinkUp lies in the future: the clock of this phone is wrong. */
    CLOCK_WRONG;

    /** True if only the user can make it work again. */
    val needsUser: Boolean
        get() = this == WRONG_LOGIN || this == NEEDS_STEP || this == NO_PATIENT || this == CLOCK_WRONG

    /** True if the app stops asking the server until the user has done something in the app. */
    val stopsAsking: Boolean
        get() = this == WRONG_LOGIN || this == NEEDS_STEP || this == NO_PATIENT

    /** True if something is wrong (and not only "not yet" or "nothing new"). */
    val isProblem: Boolean
        get() = this != OFF && this != STARTING && this != RECEIVING && this != NO_NEW_VALUE
}

/**
 * Which AI service the app talks to. The code is what is saved.
 *
 * There are two, and only two: Google Gemini and OpenAI. Both are built into the app — the
 * address and the model are filled in by themselves, so the user only has to paste a key.
 * Gemini has a free level; OpenAI is paid, but a question here costs a fraction of a cent.
 */

/** The settings of the app. They are kept on the phone only. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** True when the user has read and accepted the safety notice. */
    var noticeAccepted: Boolean
        get() = prefs.getBoolean("notice_accepted", false)
        set(value) = prefs.edit().putBoolean("notice_accepted", value).apply()

    /** Null until the user has chosen one. */
    var unit: GlucoseUnit?
        get() = when (prefs.getString("unit", null)) {
            "mgdl" -> GlucoseUnit.MGDL
            "mmoll" -> GlucoseUnit.MMOLL
            else -> null
        }
        set(value) = prefs.edit().putString("unit", when (value) {
            GlucoseUnit.MGDL -> "mgdl"
            GlucoseUnit.MMOLL -> "mmoll"
            null -> null
        }).apply()

    /** Values under this are shown in red (mg/dL). */
    var lowLimit: Int
        get() = prefs.getInt("low_limit", DEFAULT_LOW)
        set(value) = prefs.edit().putInt("low_limit", value).apply()

    /** Values over this are shown in red (mg/dL). */
    var highLimit: Int
        get() = prefs.getInt("high_limit", DEFAULT_HIGH)
        set(value) = prefs.edit().putInt("high_limit", value).apply()

    /** How many hours the chart shows: 3, 6, 12 or 24. */
    var chartHours: Int
        get() = prefs.getInt("chart_hours", 6).let { if (it in CHART_HOURS) it else 6 }
        set(value) = prefs.edit().putInt("chart_hours", value).apply()

    /** The way of reading memory blocks that worked last time. */
    var readerMode: String?
        get() = prefs.getString("reader_mode", null)
        set(value) = prefs.edit().putString("reader_mode", value).apply()

    /** Technical text about the last scan. */
    var lastScanDetails: String
        get() = prefs.getString("last_scan_details", "") ?: ""
        set(value) = prefs.edit().putString("last_scan_details", value).apply()

    /**
     * The pairs "sensor value, right value" that the user saved for one sensor, and what they
     * do to the values that are shown. Without pairs nothing is changed.
     */
    var correction: Correction
        get() = Correction.decode(prefs.getString("correction_serial", null), prefs.getString("correction_pairs", null))
        set(value) {
            val editor = prefs.edit()
            if (value.isActive) {
                editor.putString("correction_serial", value.sensorSerial).putString("correction_pairs", value.encode())
            } else {
                editor.remove("correction_serial").remove("correction_pairs")
            }
            editor.apply()
        }

    var lastSensor: SensorSnapshot?
        get() {
            if (!prefs.contains("sensor_scan_time")) {
                return null
            }
            val status = try {
                ScanStatus.valueOf(prefs.getString("sensor_status", "") ?: "")
            } catch (e: IllegalArgumentException) {
                return null
            }
            return SensorSnapshot(
                serial = prefs.getString("sensor_serial", null),
                typeLabel = prefs.getString("sensor_type", "") ?: "",
                regionLabel = prefs.getString("sensor_region", "") ?: "",
                status = status,
                age = prefs.getInt("sensor_age", 0),
                lifetime = prefs.getInt("sensor_lifetime", 0),
                scanTime = prefs.getLong("sensor_scan_time", 0)
            )
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                for (key in listOf("sensor_serial", "sensor_type", "sensor_region", "sensor_status", "sensor_age", "sensor_lifetime", "sensor_scan_time")) {
                    editor.remove(key)
                }
            } else {
                editor.putString("sensor_serial", value.serial)
                editor.putString("sensor_type", value.typeLabel)
                editor.putString("sensor_region", value.regionLabel)
                editor.putString("sensor_status", value.status.name)
                editor.putInt("sensor_age", value.age)
                editor.putInt("sensor_lifetime", value.lifetime)
                editor.putLong("sensor_scan_time", value.scanTime)
            }
            editor.apply()
        }

    /**
     * A random id for this install, made once and then kept. It is sent with a question to the
     * AI service so that it can count how many questions one phone asks in a day, and the
     * service keeps only a hash of it. It names nobody: it is not an account, not an email, and
     * not the id Android uses for advertising. Wiping the app's data makes a new one.
     */
    val installId: String
        get() {
            val saved = prefs.getString("install_id", null)
            if (!saved.isNullOrBlank()) {
                return saved
            }
            val made = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("install_id", made).apply()
            return made
        }

    var lastOutcome: ScanOutcome?
        get() {
            val message = try {
                ScanMessage.valueOf(prefs.getString("outcome_message", "") ?: "")
            } catch (e: IllegalArgumentException) {
                return null
            }
            return ScanOutcome(message, prefs.getLong("outcome_time", 0), prefs.getInt("outcome_number", 0), prefs.getString("outcome_text", "") ?: "")
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove("outcome_message").remove("outcome_time").remove("outcome_number").remove("outcome_text")
            } else {
                editor.putString("outcome_message", value.message.name)
                editor.putLong("outcome_time", value.time)
                editor.putInt("outcome_number", value.number)
                editor.putString("outcome_text", value.text)
            }
            editor.apply()
        }

    // ------------------------------------------------------------------ voice

    /** True: the app says the value after every scan. */
    var speakAfterScan: Boolean
        get() = prefs.getBoolean("speak_after_scan", true)
        set(value) = prefs.edit().putBoolean("speak_after_scan", value).apply()

    /** True: the direction ("Rising", "Steady") is said too. */
    var speakTrend: Boolean
        get() = prefs.getBoolean("speak_trend", true)
        set(value) = prefs.edit().putBoolean("speak_trend", value).apply()

    /** True: after the sound of an alarm the app says what the alarm is about. */
    var speakAlarms: Boolean
        get() = prefs.getBoolean("speak_alarms", true)
        set(value) = prefs.edit().putBoolean("speak_alarms", value).apply()

    /** True: "Below target soon" is said when the forecast goes below the target range within 20 minutes. */
    var speakLowSoon: Boolean
        get() = prefs.getBoolean("speak_low_soon", true)
        set(value) = prefs.edit().putBoolean("speak_low_soon", value).apply()

    /** How often a live value is said, in minutes: 0 (never), 5, 15, 30 or 60. */
    var speakLiveMinutes: Int
        get() = prefs.getInt("speak_live_minutes", 0).let { if (it in SPEAK_LIVE_MINUTES) it else 0 }
        set(value) = prefs.edit().putInt("speak_live_minutes", if (value in SPEAK_LIVE_MINUTES) value else 0).apply()

    /** When a live value was said last (milliseconds since 1970). */
    var lastSpokenTime: Long
        get() = prefs.getLong("last_spoken_time", 0L)
        set(value) = prefs.edit().putLong("last_spoken_time", value).apply()

    // ------------------------------------------------------------------ live values

    /** True: the app keeps a Bluetooth connection to the sensor and gets a value each minute. */
    var liveEnabled: Boolean
        get() = prefs.getBoolean("live_enabled", false)
        set(value) = prefs.edit().putBoolean("live_enabled", value).apply()

    /** True: the next good scan sets the sensor up for live values (again). */
    var livePairWanted: Boolean
        get() = prefs.getBoolean("live_pair_wanted", false)
        set(value) = prefs.edit().putBoolean("live_pair_wanted", value).apply()

    /** The sensor that was set up for live values, or null. */
    var liveSensor: LiveSensorRecord?
        get() {
            val uid = unhex(prefs.getString("live_uid", null))
            val patchInfo = unhex(prefs.getString("live_patch_info", null))
            val answer = unhex(prefs.getString("live_answer", null))
            val numbers = (prefs.getString("live_calibration", null) ?: "").split(',')
            if (uid == null || uid.size != 8 || patchInfo == null || patchInfo.size < 6 || answer == null || numbers.size != 6) {
                return null
            }
            val calibration = try {
                FactoryCalibration(numbers[0].toInt(), numbers[1].toInt(), numbers[2].toDouble(), numbers[3].toDouble(), numbers[4].toDouble(), numbers[5].toDouble())
            } catch (e: NumberFormatException) {
                return null
            }
            return LiveSensorRecord(
                serial = prefs.getString("live_serial", null),
                uid = uid,
                patchInfo = patchInfo,
                calibration = calibration,
                lifetime = prefs.getInt("live_lifetime", 0),
                startTime = prefs.getLong("live_start_time", 0L),
                streamingAnswer = answer,
                pairedTime = prefs.getLong("live_paired_time", 0L)
            )
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                for (key in listOf("live_uid", "live_patch_info", "live_answer", "live_calibration", "live_serial", "live_lifetime", "live_start_time", "live_paired_time")) {
                    editor.remove(key)
                }
            } else {
                val c = value.calibration
                editor.putString("live_uid", hex(value.uid))
                editor.putString("live_patch_info", hex(value.patchInfo))
                editor.putString("live_answer", hex(value.streamingAnswer))
                editor.putString("live_calibration", "${c.i1},${c.i2},${c.i3},${c.i4},${c.i5},${c.i6}")
                editor.putString("live_serial", value.serial)
                editor.putInt("live_lifetime", value.lifetime)
                editor.putLong("live_start_time", value.startTime)
                editor.putLong("live_paired_time", value.pairedTime)
            }
            editor.apply()
        }

    /**
     * How many times the app has unlocked the Bluetooth connection since the sensor was set up.
     * The sensor only takes a number that is higher than the last one.
     */
    var liveUnlockCount: Int
        @SuppressLint("ApplySharedPref")
        set(value) {
            // written at once, not in the background: a number must never be used twice,
            // also not after the app was stopped right after this line
            prefs.edit().putInt("live_unlock_count", value).commit()
        }
        get() = prefs.getInt("live_unlock_count", 0)

    /** The Bluetooth address under which the sensor was found last, or null. */
    var liveAddress: String?
        get() = prefs.getString("live_address", null)
        set(value) = prefs.edit().putString("live_address", value).apply()

    /** Since when live values are expected (see AlarmRules.reason). */
    var liveWaitingSince: Long
        get() = prefs.getLong("live_waiting_since", 0L)
        set(value) = prefs.edit().putLong("live_waiting_since", value).apply()

    /** When the notification "live values have stopped" was last shown, or 0. */
    var liveStoppedShownTime: Long
        get() = prefs.getLong("live_stopped_shown_time", 0L)
        set(value) = prefs.edit().putLong("live_stopped_shown_time", value).apply()

    /** When the last good Bluetooth message came (milliseconds since 1970), or 0. */
    var liveLastMessageTime: Long
        get() = prefs.getLong("live_last_message_time", 0L)
        set(value) = prefs.edit().putLong("live_last_message_time", value).apply()

    /** What the live connection is doing, as the Bluetooth service last wrote it. */
    var liveState: LiveState
        get() = try {
            LiveState.valueOf(prefs.getString("live_state", "") ?: "")
        } catch (e: IllegalArgumentException) {
            LiveState.OFF
        }
        set(value) = prefs.edit().putString("live_state", value.name).apply()

    /** Technical text lines about the live connection, newest last. */
    var liveLog: String
        get() = prefs.getString("live_log", "") ?: ""
        set(value) = prefs.edit().putString("live_log", value).apply()

    // ------------------------------------------------------------------ alarms

    var alarmLowOn: Boolean
        get() = prefs.getBoolean("alarm_low_on", true)
        set(value) = prefs.edit().putBoolean("alarm_low_on", value).apply()

    /** The low alarm sounds below this (mg/dL). */
    var alarmLow: Int
        get() = prefs.getInt("alarm_low", AlarmSettings.DEFAULT_LOW)
        set(value) = prefs.edit().putInt("alarm_low", roundToStep(value).coerceIn(ALARM_LOW_MIN, ALARM_LOW_MAX)).apply()

    var alarmHighOn: Boolean
        get() = prefs.getBoolean("alarm_high_on", false)
        set(value) = prefs.edit().putBoolean("alarm_high_on", value).apply()

    /** The high alarm sounds above this (mg/dL). */
    var alarmHigh: Int
        get() = prefs.getInt("alarm_high", AlarmSettings.DEFAULT_HIGH)
        set(value) = prefs.edit().putInt("alarm_high", roundToStep(value).coerceIn(ALARM_HIGH_MIN, ALARM_HIGH_MAX)).apply()

    /** True: an alarm sounds when the sensor has sent no value for 20 minutes. */
    var alarmNoDataOn: Boolean
        get() = prefs.getBoolean("alarm_no_data_on", true)
        set(value) = prefs.edit().putBoolean("alarm_no_data_on", value).apply()

    /** True: an alarm sounds when the forecast goes below the low limit within 20 minutes. Off from the start. */
    var alarmLowSoonOn: Boolean
        get() = prefs.getBoolean("alarm_low_soon_on", false)
        set(value) = prefs.edit().putBoolean("alarm_low_soon_on", value).apply()

    val alarmSettings: AlarmSettings
        get() = AlarmSettings(alarmLowOn, alarmLow, alarmHighOn, alarmHigh, alarmNoDataOn, lowSoonOn = alarmLowSoonOn)

    /** What was alarmed last and what the user has silenced. */
    var alarmMemory: AlarmMemory
        get() = AlarmMemory(
            lastKind = alarmKind(prefs.getString("alarm_last_kind", null)),
            lastTime = prefs.getLong("alarm_last_time", 0L),
            silencedKind = alarmKind(prefs.getString("alarm_silenced_kind", null)),
            silencedUntil = prefs.getLong("alarm_silenced_until", 0L),
            lastSoonTime = prefs.getLong("alarm_last_soon_time", 0L)
        )
        set(value) = prefs.edit()
            .putString("alarm_last_kind", value.lastKind?.name)
            .putLong("alarm_last_time", value.lastTime)
            .putString("alarm_silenced_kind", value.silencedKind?.name)
            .putLong("alarm_silenced_until", value.silencedUntil)
            .putLong("alarm_last_soon_time", value.lastSoonTime)
            .apply()

    private fun alarmKind(name: String?): AlarmKind? = AlarmKind.values().firstOrNull { it.name == name }

    // ------------------------------------------------------------------ analysis

    /** The language in which an AI is asked to answer (see AiReport). */
    var answerLanguage: AnswerLanguage
        get() = AnswerLanguage.of(prefs.getString("answer_language", null))
        set(value) = prefs.edit().putString("answer_language", value.code).apply()

    /** How many hours the analysis screen looks back in "the last hours": 3, 6, 12 or 24. */
    var analysisHours: Int
        get() = prefs.getInt("analysis_hours", 6).let { if (it == 3 || it == 6 || it == 12 || it == 24) it else 6 }
        set(value) = prefs.edit().putInt("analysis_hours", value).apply()

    /** True: the user does not want the offer of live values on the main screen. */
    var liveOfferHidden: Boolean
        get() = prefs.getBoolean("live_offer_hidden", false)
        set(value) = prefs.edit().putBoolean("live_offer_hidden", value).apply()

    // ------------------------------------------------------------------ LibreLinkUp

    /**
     * True: the app fetches the values from LibreLinkUp over the internet, and all screens
     * show those values (see [ValueSource]). Bluetooth live values are off then.
     */
    var linkUpEnabled: Boolean
        get() = prefs.getBoolean(KEY_LINKUP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LINKUP_ENABLED, value).apply()

    /** True if values come by themselves, one way or the other: over Bluetooth or from LibreLinkUp. */
    val valuesComeByThemselves: Boolean
        get() = liveEnabled || linkUpEnabled

    var linkUpEmail: String
        get() = prefs.getString("linkup_email", "") ?: ""
        set(value) = prefs.edit().putString("linkup_email", value.trim()).apply()

    /** The password of the LibreLinkUp account. It is needed again each time the login runs out. Saved locked (see [Secrets]). */
    var linkUpPassword: String
        get() = Secrets.open(prefs.getString("linkup_password", null))
        set(value) = prefs.edit().putString("linkup_password", Secrets.lock(value)).apply()

    /** The pass of the last login. Saved locked. */
    var linkUpToken: String
        get() = Secrets.open(prefs.getString("linkup_token", null))
        set(value) = prefs.edit().putString("linkup_token", Secrets.lock(value)).apply()

    /** When the pass ends (milliseconds since 1970), or 0 if not known. */
    var linkUpTokenExpires: Long
        get() = prefs.getLong("linkup_token_expires", 0L)
        set(value) = prefs.edit().putLong("linkup_token_expires", value).apply()

    var linkUpUserId: String
        get() = prefs.getString("linkup_user_id", "") ?: ""
        set(value) = prefs.edit().putString("linkup_user_id", value).apply()

    /** Where the account lives ("eu", "de" ...), or "" for the main server. */
    var linkUpRegion: String
        get() = prefs.getString("linkup_region", "") ?: ""
        set(value) = prefs.edit().putString("linkup_region", value).apply()

    /** The app version the server accepted last, or "" for the one built into the app. */
    var linkUpVersion: String
        get() = prefs.getString("linkup_version", "") ?: ""
        set(value) = prefs.edit().putString("linkup_version", value).apply()

    /** The person whose values are fetched. */
    var linkUpPatientId: String
        get() = prefs.getString("linkup_patient_id", "") ?: ""
        set(value) = prefs.edit().putString("linkup_patient_id", value).apply()

    var linkUpPatientName: String
        get() = prefs.getString("linkup_patient_name", "") ?: ""
        set(value) = prefs.edit().putString("linkup_patient_name", value).apply()

    var linkUpState: LinkUpState
        get() = try {
            LinkUpState.valueOf(prefs.getString("linkup_state", "") ?: "")
        } catch (e: IllegalArgumentException) {
            LinkUpState.OFF
        }
        set(value) = prefs.edit().putString("linkup_state", value.name).apply()

    /** When LibreLinkUp was asked last (milliseconds since 1970). */
    var linkUpLastAskTime: Long
        get() = prefs.getLong("linkup_last_ask", 0L)
        set(value) = prefs.edit().putLong("linkup_last_ask", value).apply()

    /** When LibreLinkUp answered last with values. */
    var linkUpLastAnswerTime: Long
        get() = prefs.getLong("linkup_last_answer", 0L)
        set(value) = prefs.edit().putLong("linkup_last_answer", value).apply()

    /** The server asked the app to wait until this time. */
    var linkUpWaitUntil: Long
        get() = prefs.getLong("linkup_wait_until", 0L)
        set(value) = prefs.edit().putLong("linkup_wait_until", value).apply()

    /** How many times in a row asking failed. */
    var linkUpFailures: Int
        get() = prefs.getInt("linkup_failures", 0)
        set(value) = prefs.edit().putInt("linkup_failures", value).apply()

    /** The step Abbott wants ("tou" or "pp"), with the pass to accept it; "" if there is none. */
    var linkUpStep: String
        get() = prefs.getString("linkup_step", "") ?: ""
        set(value) = prefs.edit().putString("linkup_step", value).apply()

    var linkUpStepToken: String
        get() = Secrets.open(prefs.getString("linkup_step_token", null))
        set(value) = prefs.edit().putString("linkup_step_token", Secrets.lock(value)).apply()

    /** Serial number of the sensor as LibreLinkUp names it, or "". */
    var linkUpSensorSerial: String
        get() = prefs.getString("linkup_sensor_serial", "") ?: ""
        set(value) = prefs.edit().putString("linkup_sensor_serial", value).apply()

    /** When the sensor was started, as LibreLinkUp says (milliseconds since 1970), or 0. */
    var linkUpSensorStart: Long
        get() = prefs.getLong("linkup_sensor_start", 0L)
        set(value) = prefs.edit().putLong("linkup_sensor_start", value).apply()

    /** Forgets the login to LibreLinkUp. The e-mail stays, so that it need not be typed again. */
    fun forgetLinkUpLogin() {
        prefs.edit()
            .remove("linkup_password").remove("linkup_token").remove("linkup_token_expires").remove("linkup_user_id")
            .remove("linkup_patient_id").remove("linkup_patient_name").remove("linkup_step").remove("linkup_step_token")
            .remove("linkup_wait_until").remove("linkup_failures")
            .apply()
    }

    // ------------------------------------------------------------------ AI

    // There is no key, no provider and no model to set: the AI is built in, and everything
    // that selects one lives on the server (see supabase/README.md). Nothing of it is stored
    // on the phone, and nothing of it is asked of the person using the app.

    /** The last answer of the AI, so that it is still there when the screen is opened again. */
    var aiAnswer: String
        get() = prefs.getString("ai_answer", "") ?: ""
        set(value) = prefs.edit().putString("ai_answer", value).apply()

    /** When the last answer came (milliseconds since 1970), or 0. */
    var aiAnswerTime: Long
        get() = prefs.getLong("ai_answer_time", 0L)
        set(value) = prefs.edit().putLong("ai_answer_time", value).apply()

    /** The model that gave the last answer. */
    var aiAnswerModel: String
        get() = prefs.getString("ai_answer_model", "") ?: ""
        set(value) = prefs.edit().putString("ai_answer_model", value).apply()

    // ------------------------------------------------------------------ the online copy

    /** True: new values are copied to the online database of the user. */
    var backupEnabled: Boolean
        get() = prefs.getBoolean("backup_enabled", false)
        set(value) = prefs.edit().putBoolean("backup_enabled", value).apply()

    /** The address of the Supabase project: "https://xxxx.supabase.co". */
    var backupUrl: String
        get() = prefs.getString("backup_url", "") ?: ""
        set(value) = prefs.edit().putString("backup_url", value.trim().trimEnd('/')).apply()

    /** The public key of the project ("publishable" key). It is not a secret. */
    var backupKey: String
        get() = prefs.getString("backup_key", "") ?: ""
        set(value) = prefs.edit().putString("backup_key", value.trim()).apply()

    var backupEmail: String
        get() = prefs.getString("backup_email", "") ?: ""
        set(value) = prefs.edit().putString("backup_email", value.trim()).apply()

    /** The password of the account in the online database. Saved locked. */
    var backupPassword: String
        get() = Secrets.open(prefs.getString("backup_password", null))
        set(value) = prefs.edit().putString("backup_password", Secrets.lock(value)).apply()

    /** The pass for the next minutes. Saved locked. */
    var backupAccessToken: String
        get() = Secrets.open(prefs.getString("backup_access_token", null))
        set(value) = prefs.edit().putString("backup_access_token", Secrets.lock(value)).apply()

    /** The pass that gets a new pass. Saved locked. */
    var backupRefreshToken: String
        get() = Secrets.open(prefs.getString("backup_refresh_token", null))
        set(value) = prefs.edit().putString("backup_refresh_token", Secrets.lock(value)).apply()

    /** The number of the account in the database. It stays after logging out, so that the app notices another account. */
    var backupUserId: String
        get() = prefs.getString("backup_user_id", "") ?: ""
        set(value) = prefs.edit().putString("backup_user_id", value).apply()

    /** When [backupAccessToken] ends (milliseconds since 1970). */
    var backupTokenExpires: Long
        get() = prefs.getLong("backup_token_expires", 0L)
        set(value) = prefs.edit().putLong("backup_token_expires", value).apply()

    /** When values were copied last without a problem (milliseconds since 1970), or 0. */
    var backupLastTime: Long
        get() = prefs.getLong("backup_last_time", 0L)
        set(value) = prefs.edit().putLong("backup_last_time", value).apply()

    /** When the app tried last (milliseconds since 1970), or 0. */
    var backupLastTryTime: Long
        get() = prefs.getLong("backup_last_try", 0L)
        set(value) = prefs.edit().putLong("backup_last_try", value).apply()

    /** What went wrong at the last try, as a code of BackupProblem, or "". */
    var backupProblem: String
        get() = prefs.getString("backup_problem", "") ?: ""
        set(value) = prefs.edit().putString("backup_problem", value).apply()

    /** Short technical words about the last try. */
    var backupDetail: String
        get() = prefs.getString("backup_detail", "") ?: ""
        set(value) = prefs.edit().putString("backup_detail", value).apply()

    /** How many values the online copy holds, as far as known; -1 if not known. */
    var backupOnlineCount: Int
        get() = prefs.getInt("backup_online_count", -1)
        set(value) = prefs.edit().putInt("backup_online_count", value).apply()

    /** Forgets the login to the online database. The address, the key and the e-mail stay. */
    fun forgetBackupLogin() {
        prefs.edit()
            .remove("backup_password").remove("backup_access_token").remove("backup_refresh_token").remove("backup_token_expires")
            .remove("backup_enabled").remove("backup_problem").remove("backup_detail").remove("backup_online_count")
            .apply()
    }

    // ------------------------------------------------------------------ statistics

    /** How many days the statistics screen looks back: 1, 7, 14, 30 or 90. */
    var statisticsDays: Int
        get() = prefs.getInt("statistics_days", 14).let { if (it in STATISTICS_DAYS) it else 14 }
        set(value) = prefs.edit().putInt("statistics_days", value).apply()

    /** Sets both limits. They are kept in 40..400, in steps of 5, with low not over high. */
    fun setLimits(low: Int, high: Int) {
        val newLow = roundToStep(low).coerceIn(MIN_LIMIT, MAX_LIMIT)
        val newHigh = roundToStep(high).coerceIn(MIN_LIMIT, MAX_LIMIT)
        if (newLow > newHigh) {
            return
        }
        prefs.edit().putInt("low_limit", newLow).putInt("high_limit", newHigh).apply()
    }

    private fun roundToStep(value: Int): Int = Math.round(value / LIMIT_STEP.toFloat()) * LIMIT_STEP

    companion object {
        /** The name under which [linkUpEnabled] is saved. The store of the values reads it too. */
        const val KEY_LINKUP_ENABLED = "linkup_enabled"

        const val DEFAULT_LOW = 80
        const val DEFAULT_HIGH = 180
        const val LIMIT_STEP = 5
        const val MIN_LIMIT = 40
        const val MAX_LIMIT = 400
        val CHART_HOURS = listOf(3, 6, 12, 24)
        val SPEAK_LIVE_MINUTES = listOf(0, 5, 15, 30, 60)
        val STATISTICS_DAYS = listOf(1, 7, 14, 30, 90)
        const val ALARM_LOW_MIN = 55
        const val ALARM_LOW_MAX = 120
        const val ALARM_HIGH_MIN = 130
        const val ALARM_HIGH_MAX = 400

        private const val HEX = "0123456789abcdef"

        fun hex(bytes: ByteArray): String {
            val text = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                text.append(HEX[(b.toInt() shr 4) and 0x0F]).append(HEX[b.toInt() and 0x0F])
            }
            return text.toString()
        }

        /** The bytes of a hex text, or null if it is none. */
        fun unhex(text: String?): ByteArray? {
            if (text == null || text.length % 2 != 0) {
                return null
            }
            val bytes = ByteArray(text.length / 2)
            for (k in bytes.indices) {
                val high = Character.digit(text[2 * k], 16)
                val low = Character.digit(text[2 * k + 1], 16)
                if (high < 0 || low < 0) {
                    return null
                }
                bytes[k] = ((high shl 4) or low).toByte()
            }
            return bytes
        }
    }
}
