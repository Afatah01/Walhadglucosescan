package com.glucosescan.app.linkup

import com.glucosescan.app.net.HttpRequest
import com.glucosescan.app.net.HttpResponse
import com.glucosescan.app.net.HttpTransport
import com.glucosescan.app.net.Net
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// LibreLinkUp is the follower app of FreeStyle Libre. The sensor sends its values to
// FreeStyle LibreLink over Bluetooth, LibreLink sends them to Abbott's servers, and
// LibreLinkUp shows them to the people who follow. This file asks those servers the same
// questions the LibreLinkUp app asks.
//
// Abbott has not published this way of asking and has not allowed it. The addresses, the
// headers and the fields were taken from what open-source apps do that have used it for
// years (GlucoDataHandler, nightscout-librelink-up, xDrip+). No code of them is in here.
// Abbott can change the servers at any time; then this stops working until the app is
// changed too.

/** What the app keeps about its login. */
class LinkUpLogin(
    /** The pass the server gave. It goes with every request. */
    val token: String,
    /** When the pass ends (milliseconds since 1970), or 0 if the server did not say. */
    val expires: Long,
    /** The number of the LibreLinkUp account. The server wants its SHA-256 with every request. */
    val userId: String,
    /** The part of the world the account lives in ("eu", "de", "us" ...), or "" for the main server. */
    val region: String
)

/** A person whose values the account may see. */
class LinkUpPatient(val id: String, val name: String)

/** One value as LibreLinkUp gives it. */
class LinkUpValue(
    /** Milliseconds since 1970. */
    val time: Long,
    val mgdl: Int,
    /** Abbott's arrow: 1 falling fast, 2 falling, 3 steady, 4 rising, 5 rising fast; 0 if not given. */
    val arrow: Int
)

/** What one look at LibreLinkUp brings. */
class LinkUpData(
    /** The newest value, or null if LibreLinkUp has none. */
    val current: LinkUpValue?,
    /** Older values, about one every 15 minutes for the last 12 hours. */
    val history: List<LinkUpValue>,
    /** Serial number of the sensor as LibreLinkUp writes it, or null. */
    val sensorSerial: String?,
    /** When the sensor was started (milliseconds since 1970), or 0. */
    val sensorStart: Long
)

enum class LinkUpProblem {
    /** No answer: no internet, or the server cannot be reached. */
    NO_INTERNET,

    /** The server does not accept the e-mail and the password. */
    WRONG_LOGIN,

    /** Too many tries. The server wants the app to wait. */
    LOCKED,

    /** Abbott wants the user to accept its terms of use or its privacy notice again. */
    NEEDS_STEP,

    /** The pass of the login is not accepted any more: log in again. */
    LOGIN_EXPIRED,

    /** The server answered with an error. */
    SERVER,

    /** The answer could not be understood. Abbott may have changed something. */
    BAD_ANSWER
}

class LinkUpFailure(
    val problem: LinkUpProblem,
    /** Short technical words (English), without secrets. */
    val detail: String,
    /** For [LinkUpProblem.LOCKED]: how many seconds to wait. */
    val waitSeconds: Int = 0,
    /** For [LinkUpProblem.NEEDS_STEP]: "tou" (terms of use) or "pp" (privacy notice). */
    val step: String = "",
    /** For [LinkUpProblem.NEEDS_STEP]: the pass with which the step is accepted. */
    val stepToken: String = "",
    /** For [LinkUpProblem.NEEDS_STEP]: where the account lives. */
    val region: String = ""
)

/** Either what was asked for, or why it did not work. */
class LinkUpReply<T> private constructor(val value: T?, val failure: LinkUpFailure?) {
    val isOk: Boolean
        get() = failure == null

    companion object {
        fun <T> ok(value: T): LinkUpReply<T> = LinkUpReply(value, null)

        fun <T> failed(failure: LinkUpFailure): LinkUpReply<T> = LinkUpReply(null, failure)

        fun <T> failed(problem: LinkUpProblem, detail: String): LinkUpReply<T> = LinkUpReply(null, LinkUpFailure(problem, detail))
    }
}

class LinkUpApi(
    private val transport: HttpTransport,
    /**
     * The version of the LibreLinkUp app this app says it is. The server refuses versions that
     * are too old and names the oldest one it takes; the app then goes on with that one, and
     * the caller saves it for the next time.
     */
    var version: String = DEFAULT_VERSION,
    /** "io" for the world, "ru" for Russia. */
    private val domain: String = "io"
) {
    /**
     * Logs in.
     * @param region where the account lives, as far as known from the last login; "" if not known
     */
    fun login(email: String, password: String, region: String = ""): LinkUpReply<LinkUpLogin> {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        var where = region
        for (round in 0..MAX_REDIRECTS) {
            val answer = ask("POST", where, "/llu/auth/login", null, null, body)
            val json = answer.json ?: return LinkUpReply.failed(answer.failure!!)
            val data = json.optJSONObject("data")
            if (data != null && data.optBoolean("redirect", false)) {
                val next = data.optString("region", "").trim().lowercase(Locale.US)
                if (!REGION.matches(next) || next == where) {
                    return LinkUpReply.failed(LinkUpProblem.BAD_ANSWER, "redirect without a usable region")
                }
                where = next
                continue
            }
            return loginFrom(json, where)
        }
        return LinkUpReply.failed(LinkUpProblem.SERVER, "sent from one region to the next too often")
    }

    /**
     * Tells the server that the user accepts the terms of use ("tou") or the privacy notice
     * ("pp"). The answer is a login, or the next step.
     */
    fun acceptStep(step: String, stepToken: String, region: String): LinkUpReply<LinkUpLogin> {
        if (step != "tou" && step != "pp") {
            return LinkUpReply.failed(LinkUpProblem.BAD_ANSWER, "unknown step")
        }
        val answer = ask("POST", region, "/auth/continue/$step", stepToken, null, null)
        val json = answer.json ?: return LinkUpReply.failed(answer.failure!!)
        return loginFrom(json, region)
    }

    /** The people whose values this account may see. An empty list: nobody shares with it. */
    fun patients(login: LinkUpLogin): LinkUpReply<List<LinkUpPatient>> {
        val answer = ask("GET", login.region, "/llu/connections", login.token, login.userId, null)
        val json = answer.json ?: return LinkUpReply.failed(answer.failure!!)
        val list = json.optJSONArray("data") ?: return LinkUpReply.failed(LinkUpProblem.BAD_ANSWER, "connections: no list")
        val patients = ArrayList<LinkUpPatient>()
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: continue
            val id = entry.optString("patientId", "")
            if (id.isEmpty()) {
                continue
            }
            val name = (text(entry, "firstName") + " " + text(entry, "lastName")).trim()
            patients.add(LinkUpPatient(id, name))
        }
        return LinkUpReply.ok(patients)
    }

    /** The newest value of one person and the values of the last hours. */
    fun values(login: LinkUpLogin, patientId: String, zone: TimeZone = TimeZone.getDefault()): LinkUpReply<LinkUpData> {
        if (!PATIENT_ID.matches(patientId)) {
            return LinkUpReply.failed(LinkUpProblem.BAD_ANSWER, "not a patient number")
        }
        val answer = ask("GET", login.region, "/llu/connections/$patientId/graph", login.token, login.userId, null)
        val json = answer.json ?: return LinkUpReply.failed(answer.failure!!)
        val data = json.optJSONObject("data") ?: return LinkUpReply.failed(LinkUpProblem.BAD_ANSWER, "graph: no data")
        val connection = data.optJSONObject("connection") ?: return LinkUpReply.failed(LinkUpProblem.BAD_ANSWER, "graph: no connection")
        return LinkUpReply.ok(LinkUpParser.data(connection, data.optJSONArray("graphData"), data.optJSONArray("activeSensors"), zone))
    }

    // ------------------------------------------------------------------ one request

    private class Answer(val json: JSONObject?, val failure: LinkUpFailure?)

    private fun ask(method: String, region: String, path: String, token: String?, userId: String?, body: String?): Answer {
        var response: HttpResponse? = null
        for (round in 0..1) {
            response = try {
                transport.send(HttpRequest(method, address(region, path), headers(token, userId), body))
            } catch (e: IOException) {
                return Answer(null, LinkUpFailure(LinkUpProblem.NO_INTERNET, Net.words(e)))
            }
            // "your app is too old": the answer names the oldest version the server takes
            val wanted = minimumVersion(response)
            if (round == 0 && wanted != null && wanted != version) {
                version = wanted
                continue
            }
            break
        }
        return understand(response!!, path, region)
    }

    private fun understand(response: HttpResponse, path: String, region: String): Answer {
        val what = path.substringAfterLast("/llu/").substringBefore('/').ifEmpty { "request" }
        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            null
        }
        val status = json?.optInt("status", if (response.isSuccess) 0 else -1) ?: -1
        val message = if (json != null) message(json) else ""

        if (response.code == 429 || status == 429) {
            return Answer(null, LinkUpFailure(LinkUpProblem.LOCKED, "$what: too many requests", waitSeconds = lockout(response, json)))
        }
        if (response.code == 401 || message.contains("jwt", ignoreCase = true)) {
            return Answer(null, LinkUpFailure(LinkUpProblem.LOGIN_EXPIRED, "$what: HTTP ${response.code} $message".trim()))
        }
        if (json == null) {
            val kind = if (response.code >= 500) LinkUpProblem.SERVER else LinkUpProblem.BAD_ANSWER
            return Answer(null, LinkUpFailure(kind, "$what: HTTP ${response.code}, no JSON"))
        }
        when (status) {
            0 -> if (!response.isSuccess) {
                return Answer(null, LinkUpFailure(LinkUpProblem.SERVER, "$what: HTTP ${response.code} $message".trim()))
            }
            2 -> return Answer(null, LinkUpFailure(LinkUpProblem.WRONG_LOGIN, "$what: status 2 $message".trim()))
            4 -> {
                val data = json.optJSONObject("data")
                val step = data?.optJSONObject("step")?.optString("type", "") ?: ""
                val stepToken = data?.optJSONObject("authTicket")?.optString("token", "") ?: ""
                return Answer(null, LinkUpFailure(LinkUpProblem.NEEDS_STEP, "$what: step \"$step\"", step = step, stepToken = stepToken, region = region))
            }
            920 -> return Answer(null, LinkUpFailure(LinkUpProblem.SERVER, "$what: the server wants a newer app version than $version"))
            else -> return Answer(null, LinkUpFailure(LinkUpProblem.SERVER, "$what: status $status $message".trim()))
        }
        return Answer(json, null)
    }

    private fun address(region: String, path: String): String {
        val host = if (region.isEmpty()) "api.libreview.$domain" else "api-$region.libreview.$domain"
        return "https://$host$path"
    }

    private fun headers(token: String?, userId: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["product"] = PRODUCT
        headers["version"] = version
        headers["Accept"] = "application/json"
        headers["Content-Type"] = "application/json"
        headers["cache-control"] = "no-cache"
        if (!userId.isNullOrEmpty()) {
            headers["Account-Id"] = sha256(userId)
        }
        if (!token.isNullOrEmpty()) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    private fun loginFrom(json: JSONObject, region: String): LinkUpReply<LinkUpLogin> {
        val data = json.optJSONObject("data") ?: return LinkUpReply.failed(LinkUpProblem.BAD_ANSWER, "login: no data")
        val ticket = data.optJSONObject("authTicket")
        val token = ticket?.optString("token", "") ?: ""
        val userId = data.optJSONObject("user")?.optString("id", "") ?: ""
        if (token.isEmpty() || userId.isEmpty()) {
            return LinkUpReply.failed(LinkUpProblem.BAD_ANSWER, "login: no pass or no account number")
        }
        val expires = (ticket?.optLong("expires", 0L) ?: 0L) * 1000L
        return LinkUpReply.ok(LinkUpLogin(token, expires, userId, region))
    }

    private fun minimumVersion(response: HttpResponse): String? {
        if (response.code != 403 && !response.body.contains("minimumVersion")) {
            return null
        }
        return try {
            val wanted = JSONObject(response.body).optJSONObject("data")?.optString("minimumVersion", "") ?: ""
            if (VERSION.matches(wanted)) wanted else null
        } catch (e: JSONException) {
            null
        }
    }

    /** How long the server wants the app to wait, in seconds. Between 1 and 60 minutes. */
    private fun lockout(response: HttpResponse, json: JSONObject?): Int {
        var seconds = response.header("Retry-After")?.trim()?.toIntOrNull() ?: 0
        var data = json?.optJSONObject("data")
        var depth = 0
        while (data != null && depth < 3) {
            val found = data.optInt("lockout", 0)
            if (found > seconds) {
                seconds = found
            }
            data = data.optJSONObject("data")
            depth++
        }
        return (if (seconds <= 0) DEFAULT_LOCKOUT_SECONDS else seconds).coerceIn(60, 3600)
    }

    private fun message(json: JSONObject): String {
        val found = json.optJSONObject("error")?.optString("message", "")?.ifEmpty { null }
            ?: json.optJSONObject("data")?.optString("message", "")?.ifEmpty { null }
            ?: json.optString("message", "")
        return found.take(80)
    }

    private fun text(json: JSONObject, name: String): String = if (json.isNull(name)) "" else json.optString(name, "")

    companion object {
        /** The version GlucoDataHandler uses in autumn 2026. The server asks for 4.16.0 or newer. */
        const val DEFAULT_VERSION = "4.17.0"
        const val PRODUCT = "llu.android"
        const val MAX_REDIRECTS = 3
        const val DEFAULT_LOCKOUT_SECONDS = 300

        private val REGION = Regex("[a-z0-9]{2,8}")
        private val VERSION = Regex("[0-9]{1,3}(\\.[0-9]{1,4}){1,3}")
        private val PATIENT_ID = Regex("[A-Za-z0-9-]{8,64}")

        /** SHA-256 of [text] as 64 small hex letters. */
        fun sha256(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(bytes.size * 2)
            for (byte in bytes) {
                hex.append(String.format(Locale.US, "%02x", byte.toInt() and 0xFF))
            }
            return hex.toString()
        }
    }
}

/** Reads the values out of an answer of LibreLinkUp. */
object LinkUpParser {
    /**
     * @param connection the person: newest value and sensor
     * @param graph the older values, or null
     * @param activeSensors the sensors that are running, or null
     * @param zone the time zone of this phone, for answers that carry only the local time
     */
    fun data(connection: JSONObject, graph: JSONArray?, activeSensors: JSONArray?, zone: TimeZone): LinkUpData {
        val current = value(connection.optJSONObject("glucoseMeasurement") ?: connection.optJSONObject("glucoseItem"), zone)
        val history = ArrayList<LinkUpValue>()
        if (graph != null) {
            for (i in 0 until graph.length()) {
                value(graph.optJSONObject(i), zone)?.let { history.add(it) }
            }
        }
        val sensor = connection.optJSONObject("sensor") ?: activeSensors?.optJSONObject(0)?.optJSONObject("sensor")
        val serial = sensor?.let { if (it.isNull("sn")) "" else it.optString("sn", "") }?.trim()?.take(20)
        val start = (sensor?.optLong("a", 0L) ?: 0L) * 1000L
        return LinkUpData(current, history.sortedBy { it.time }, if (serial.isNullOrEmpty()) null else serial, if (start > 0L) start else 0L)
    }

    /** One value, or null if it has no usable time or no usable number. */
    fun value(json: JSONObject?, zone: TimeZone): LinkUpValue? {
        if (json == null) {
            return null
        }
        val time = time(text(json, "FactoryTimestamp"), text(json, "Timestamp"), zone)
        if (time <= 0L) {
            return null
        }
        var mgdl = json.optInt("ValueInMgPerDl", 0)
        if (mgdl <= 0) {
            // only the number in the unit of the user: 1 = mg/dL, 0 = mmol/L
            val number = json.optDouble("Value", Double.NaN)
            if (!number.isNaN() && number > 0.0) {
                mgdl = if (json.optInt("GlucoseUnits", 1) == 1) Math.round(number).toInt() else Math.round(number * MGDL_PER_MMOLL).toInt()
            }
        }
        if (mgdl < MIN_MGDL || mgdl > MAX_MGDL) {
            return null
        }
        val arrow = json.optInt("TrendArrow", 0)
        return LinkUpValue(time, mgdl, if (arrow in 1..5) arrow else 0)
    }

    /**
     * The moment of a value. "FactoryTimestamp" is the time in London without summer time
     * (UTC) and is taken first; "Timestamp" is the clock of the phone that read the sensor.
     * Both look like "11/14/2026 3:46:12 PM".
     */
    fun time(factory: String, local: String, zone: TimeZone): Long {
        val utc = parse(factory, TimeZone.getTimeZone("UTC"))
        return if (utc > 0L) utc else parse(local, zone)
    }

    private fun parse(text: String, zone: TimeZone): Long {
        // some systems write a narrow or a fixed space before AM and PM
        val clean = text.replace('\u202F', ' ').replace('\u00A0', ' ').trim()
        if (clean.isEmpty()) {
            return 0L
        }
        val format = SimpleDateFormat("M/d/yyyy h:mm:ss a", Locale.US)
        format.timeZone = zone
        format.isLenient = false
        val position = ParsePosition(0)
        val date = format.parse(clean, position)
        return if (date == null || position.index != clean.length) 0L else date.time
    }

    private fun text(json: JSONObject, name: String): String = if (json.isNull(name)) "" else json.optString(name, "")

    /** Fewer or more than this cannot be a glucose value; such an entry is damaged. */
    const val MIN_MGDL = 10
    const val MAX_MGDL = 900
    private const val MGDL_PER_MMOLL = 18.016
}
