package com.glucosescan.app.testing

import com.glucosescan.app.linkup.LinkUpApi
import com.glucosescan.app.net.HttpRequest
import com.glucosescan.app.net.HttpResponse
import com.glucosescan.app.net.HttpTransport
import com.glucosescan.app.net.Net
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor

/** A pretended internet: the tests say what it answers, and it writes down what was asked. */
open class FakeServer : HttpTransport {
    val requests = ArrayList<HttpRequest>()

    /** True: there is no connection; every request fails the way a phone without internet makes it fail. */
    var offline = false

    var answer: (HttpRequest) -> HttpResponse = { HttpResponse(404, "{\"message\":\"nothing here\"}") }

    override fun send(request: HttpRequest): HttpResponse {
        requests.add(request)
        if (offline) {
            throw IOException("Unable to resolve host")
        }
        return answer(request)
    }

    val last: HttpRequest
        get() = requests.last()

    /** The requests whose address holds [part]. */
    fun to(part: String): List<HttpRequest> = requests.filter { it.url.contains(part) }

    companion object {
        /** Work that waits for the internet runs at once and in line: the tests need not wait for threads. */
        fun install(server: HttpTransport) {
            Net.transport = server
            Net.background = Executor { it.run() }
            Net.patient = Executor { it.run() }
        }

        /** After a test: an internet that is not there, so that no later test can reach a real server. */
        fun remove() {
            Net.transport = FakeServer().also { it.offline = true }
        }
    }
}

/**
 * A pretended LibreLinkUp. It answers the way open-source projects describe the real one
 * (GlucoDataHandler, nightscout-librelink-up): regions, the version it wants, the header with
 * the hashed account number, the steps for the terms of use, lock-outs.
 */
class FakeLinkUp(private val clock: () -> Long) : FakeServer() {
    class Patient(val id: String, val first: String, val last: String)

    class Value(val time: Long, val mgdl: Int, val arrow: Int = 3)

    var email = "follower@example.com"
    var password = "right-password"

    /** Where the account lives. The main server sends the app there. "" = the main server itself. */
    var region = "eu"
    var minimumVersion = "4.16.0"
    var userId = "5f2c9a1e-0000-4000-8000-00000000abcd"
    var token = "pass-1"

    /** How long a pass is good (milliseconds). */
    var tokenLife = 180L * 24 * 60 * 60_000L
    private var tokenEnds = 0L
    val patients = arrayListOf(Patient("9b1d0a1e-1111-4000-8000-000000000001", "Sample", "Person"))

    /** The steps the user must accept before a login is given, in order: "tou", "pp". */
    val steps = ArrayList<String>()
    var stepToken = "step-pass"

    /** Greater than 0: the server answers "locked" and names this many seconds. */
    var lockedSeconds = 0

    /** Not 0: the values request answers with this HTTP code. */
    var valuesFail = 0

    var current: Value? = null
    val history = ArrayList<Value>()
    var sensorSerial: String? = "0M0063KNUJ0"
    var sensorStart = 0L
    var logins = 0

    init {
        answer = { request -> handle(request) }
    }

    /** Ends the pass that was given: the next values request is refused until the app logs in again. */
    fun endToken() {
        tokenEnds = 1L
    }

    private fun handle(request: HttpRequest): HttpResponse {
        val version = request.headers["version"] ?: ""
        if (request.headers["product"] != LinkUpApi.PRODUCT || compare(version, minimumVersion) < 0) {
            return HttpResponse(403, JSONObject().put("data", JSONObject().put("minimumVersion", minimumVersion)).put("status", 920).toString())
        }
        val host = request.url.removePrefix("https://").substringBefore('/')
        val path = "/" + request.url.removePrefix("https://").substringAfter('/')
        val home = if (region.isEmpty()) "api.libreview.io" else "api-$region.libreview.io"

        if (path == "/llu/auth/login") {
            if (host != home) {
                return ok(JSONObject().put("redirect", true).put("region", region))
            }
            if (lockedSeconds > 0) {
                val inner = JSONObject().put("failures", 3).put("interval", 60).put("lockout", lockedSeconds)
                return HttpResponse(200, JSONObject().put("status", 429).put("data", JSONObject().put("code", 60).put("data", inner).put("message", "locked")).toString())
            }
            val body = JSONObject(request.body ?: "{}")
            if (body.optString("email") != email || body.optString("password") != password) {
                return HttpResponse(200, JSONObject().put("status", 2).put("error", JSONObject().put("message", "notAuthenticated")).toString())
            }
            logins++
            return loginOrStep()
        }
        if (path.startsWith("/auth/continue/")) {
            val type = path.substringAfterLast('/')
            if (request.headers["Authorization"] != "Bearer $stepToken" || steps.firstOrNull() != type) {
                return HttpResponse(401, "{\"message\":\"invalid or expired jwt\"}")
            }
            steps.removeAt(0)
            return loginOrStep()
        }

        // everything else needs the pass and the hashed account number
        if (host != home) {
            return HttpResponse(401, "{\"message\":\"invalid or expired jwt\"}")
        }
        if (request.headers["Account-Id"] != LinkUpApi.sha256(userId)) {
            return HttpResponse(400, "{\"message\":\"RequiredHeaderMissing\"}")
        }
        if (request.headers["Authorization"] != "Bearer $token" || clock() >= tokenEnds) {
            return HttpResponse(401, "{\"message\":\"invalid or expired jwt\"}")
        }
        if (path == "/llu/connections") {
            val list = JSONArray()
            for (patient in patients) {
                list.put(connection(patient))
            }
            return HttpResponse(200, JSONObject().put("status", 0).put("data", list).toString())
        }
        if (path.startsWith("/llu/connections/") && path.endsWith("/graph")) {
            if (valuesFail != 0) {
                return HttpResponse(valuesFail, "{\"message\":\"server error\"}")
            }
            val id = path.removePrefix("/llu/connections/").removeSuffix("/graph")
            val patient = patients.firstOrNull { it.id == id } ?: return HttpResponse(404, "{\"status\":404,\"error\":{\"message\":\"notFound\"}}")
            val graph = JSONArray()
            for (value in history) {
                graph.put(measurement(value))
            }
            return ok(JSONObject().put("connection", connection(patient)).put("graphData", graph).put("activeSensors", JSONArray()))
        }
        return HttpResponse(404, "{\"message\":\"nothing here\"}")
    }

    private fun loginOrStep(): HttpResponse {
        val step = steps.firstOrNull()
        if (step != null) {
            val data = JSONObject()
                .put("step", JSONObject().put("type", step).put("componentName", "AcceptDocument"))
                .put("user", JSONObject().put("accountType", "pat").put("country", "SO"))
                .put("authTicket", JSONObject().put("token", stepToken).put("expires", (clock() + 3_600_000L) / 1000).put("duration", 3_600_000L))
            return HttpResponse(200, JSONObject().put("status", 4).put("data", data).toString())
        }
        tokenEnds = clock() + tokenLife
        val data = JSONObject()
            .put("user", JSONObject().put("id", userId).put("firstName", "Follower").put("lastName", "One").put("email", email))
            .put("authTicket", JSONObject().put("token", token).put("expires", tokenEnds / 1000).put("duration", tokenLife))
        return ok(data)
    }

    private fun ok(data: JSONObject): HttpResponse = HttpResponse(200, JSONObject().put("status", 0).put("data", data).toString())

    private fun connection(patient: Patient): JSONObject {
        val json = JSONObject().put("patientId", patient.id).put("firstName", patient.first).put("lastName", patient.last)
        val sensor = JSONObject().put("deviceId", "")
        sensor.put("sn", sensorSerial ?: JSONObject.NULL)
        sensor.put("a", sensorStart / 1000)
        json.put("sensor", sensor)
        current?.let { json.put("glucoseMeasurement", measurement(it)) }
        return json
    }

    private fun measurement(value: Value): JSONObject {
        return JSONObject()
            .put("FactoryTimestamp", stamp(value.time, TimeZone.getTimeZone("UTC")))
            .put("Timestamp", stamp(value.time, TimeZone.getDefault()))
            .put("type", 1)
            .put("ValueInMgPerDl", value.mgdl)
            .put("TrendArrow", value.arrow)
            .put("TrendMessage", JSONObject.NULL)
            .put("MeasurementColor", 1)
            .put("GlucoseUnits", 1)
            .put("Value", value.mgdl)
            .put("isHigh", false)
            .put("isLow", false)
    }

    companion object {
        /** A time the way LibreLinkUp writes it: "11/10/2026 11:32:20 AM". */
        fun stamp(time: Long, zone: TimeZone): String {
            val format = SimpleDateFormat("M/d/yyyy h:mm:ss a", Locale.US)
            format.timeZone = zone
            return format.format(Date(time))
        }

        private fun compare(a: String, b: String): Int {
            val left = a.split('.').map { it.toIntOrNull() ?: -1 }
            val right = b.split('.').map { it.toIntOrNull() ?: -1 }
            for (i in 0 until Math.max(left.size, right.size)) {
                val difference = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 })
                if (difference != 0) {
                    return difference
                }
            }
            return 0
        }
    }
}
