package com.glucosescan.app.cloud

import com.glucosescan.app.core.Glucose
import com.glucosescan.app.data.StoredValue
import com.glucosescan.app.data.ValueSource
import com.glucosescan.app.net.HttpRequest
import com.glucosescan.app.net.HttpResponse
import com.glucosescan.app.net.HttpTransport
import com.glucosescan.app.net.Net
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

// The online copy lives in a Supabase project that belongs to the user: a Postgres database
// with a login in front of it. This file talks to the two parts of such a project that the
// app needs, the way Supabase describes them:
//   …/auth/v1   accounts: sign up, log in, get a new pass
//   …/rest/v1   the table "glucose_values" (see res/raw/supabase_setup.sql)
//
// The "publishable key" of the project goes with every request. It is not a secret; what a
// request may do is decided by the login and by the rules of the table.

enum class BackupProblem {
    /** No answer: no internet, or the project cannot be reached (a free project that sleeps). */
    NO_INTERNET,

    /** The database does not accept the e-mail and the password. */
    LOGIN,

    /** The e-mail of the account was not confirmed yet. */
    NOT_CONFIRMED,

    /** The password is too short or too simple for the database. */
    WEAK_PASSWORD,

    /** The e-mail has an account already. */
    EXISTS,

    /** The database does not accept the key of the project. */
    KEY,

    /** The table for the values is not there, or the app may not use it: the set-up text was not run. */
    NO_TABLE,

    /** The pass of the login is not accepted any more. */
    PASS_EXPIRED,

    /** The database answered with an error. */
    SERVER
}

class BackupFailure(val problem: BackupProblem, val detail: String)

/** Either what was asked for, or why it did not work. */
class BackupReply<T> private constructor(val value: T?, val failure: BackupFailure?) {
    val isOk: Boolean
        get() = failure == null

    companion object {
        fun <T> ok(value: T): BackupReply<T> = BackupReply(value, null)

        fun <T> failed(failure: BackupFailure): BackupReply<T> = BackupReply(null, failure)
    }
}

/** A login to the database. */
class BackupSession(
    /** The pass for the next hour or so. */
    val accessToken: String,
    /** The pass that gets the next pass. It can be used once. */
    val refreshToken: String,
    /** When [accessToken] ends (milliseconds since 1970). */
    val expires: Long,
    val userId: String
)

class SupabaseApi(
    private val transport: HttpTransport,
    /** "https://name.supabase.co", without a slash at the end. */
    private val url: String,
    private val key: String
) {
    // ------------------------------------------------------------------ accounts

    /**
     * Makes an account. The answer is a login, or null if the database first wants the
     * e-mail confirmed (it has sent a letter with a link).
     */
    fun signUp(email: String, password: String, now: Long): BackupReply<BackupSession?> {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val response = send("POST", "/auth/v1/signup", null, body) ?: return noAnswer()
        if (!response.isSuccess) {
            return BackupReply.failed(failure(response))
        }
        val json = json(response) ?: return BackupReply.failed(BackupFailure(BackupProblem.SERVER, "sign up: no JSON"))
        return BackupReply.ok(if (json.has("access_token")) session(json, now) else null)
    }

    fun logIn(email: String, password: String, now: Long): BackupReply<BackupSession> {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        return sessionFrom(send("POST", "/auth/v1/token?grant_type=password", null, body), now)
    }

    /** Gets a new pass with the pass that is kept for this. */
    fun refresh(refreshToken: String, now: Long): BackupReply<BackupSession> {
        val body = JSONObject().put("refresh_token", refreshToken).toString()
        return sessionFrom(send("POST", "/auth/v1/token?grant_type=refresh_token", null, body), now)
    }

    /** Tells the database that the passes of this login are not to be used any more. */
    fun logOut(accessToken: String) {
        send("POST", "/auth/v1/logout", accessToken, "{}")
    }

    private fun sessionFrom(response: HttpResponse?, now: Long): BackupReply<BackupSession> {
        if (response == null) {
            return noAnswer()
        }
        if (!response.isSuccess) {
            return BackupReply.failed(failure(response))
        }
        val session = json(response)?.let { session(it, now) }
            ?: return BackupReply.failed(BackupFailure(BackupProblem.SERVER, "login: the answer holds no pass"))
        return BackupReply.ok(session)
    }

    private fun session(json: JSONObject, now: Long): BackupSession? {
        val access = text(json, "access_token")
        val refresh = text(json, "refresh_token")
        val userId = json.optJSONObject("user")?.let { text(it, "id") } ?: ""
        if (access.isEmpty() || refresh.isEmpty() || userId.isEmpty()) {
            return null
        }
        val seconds = json.optLong("expires_in", 3600L).coerceIn(60L, 7L * 24 * 3600)
        return BackupSession(access, refresh, now + seconds * 1000L, userId)
    }

    // ------------------------------------------------------------------ the table

    /**
     * Adds values to the copy. A value that is there already (same person, same source, same
     * minute) is left as it is.
     */
    fun add(session: BackupSession, values: List<StoredValue>): BackupReply<Int> {
        if (values.isEmpty()) {
            return BackupReply.ok(0)
        }
        val rows = JSONArray()
        for (stored in values) {
            val glucose = stored.glucose
            val row = JSONObject()
                .put("user_id", session.userId)
                .put("source", stored.source.code)
                .put("time_ms", glucose.timestamp)
                .put("mgdl", glucose.value)
                .put("kind", if (glucose.fromTrend) 0 else 1)
            val change = glucose.minuteChange
            row.put("change", if (change == null || change.isNaN() || change.isInfinite()) JSONObject.NULL else change)
            row.put("sensor", glucose.sensorSerial ?: JSONObject.NULL)
            rows.put(row)
        }
        val response = send(
            "POST", "/rest/v1/$TABLE?on_conflict=user_id,source,time_ms", session.accessToken, rows.toString(),
            mapOf("Prefer" to "resolution=ignore-duplicates,return=minimal")
        ) ?: return noAnswer()
        return if (response.isSuccess) BackupReply.ok(values.size) else BackupReply.failed(failure(response))
    }

    /** How many values the copy of this person holds. */
    fun count(session: BackupSession): BackupReply<Int> {
        val response = send("GET", "/rest/v1/$TABLE?select=time_ms&limit=1", session.accessToken, null, mapOf("Prefer" to "count=exact"))
            ?: return noAnswer()
        if (!response.isSuccess) {
            return BackupReply.failed(failure(response))
        }
        // "0-0/1234", or "*/0" for an empty table
        val total = response.header("Content-Range")?.substringAfter('/', "")?.trim()?.toIntOrNull()
            ?: return BackupReply.failed(BackupFailure(BackupProblem.SERVER, "count: no Content-Range"))
        return BackupReply.ok(total)
    }

    /**
     * One page of the copy, oldest values first.
     * @param from only values from this time on (milliseconds since 1970)
     * @param offset how many values to leave out at the start
     */
    fun page(session: BackupSession, from: Long, offset: Int): BackupReply<List<StoredValue>> {
        val path = "/rest/v1/$TABLE?select=source,time_ms,mgdl,kind,change,sensor&time_ms=gte.$from" +
            "&order=time_ms.asc,source.asc&limit=$PAGE&offset=$offset"
        val response = send("GET", path, session.accessToken, null) ?: return noAnswer()
        if (!response.isSuccess) {
            return BackupReply.failed(failure(response))
        }
        val list = try {
            JSONArray(response.body)
        } catch (e: JSONException) {
            return BackupReply.failed(BackupFailure(BackupProblem.SERVER, "page: no list"))
        }
        val values = ArrayList<StoredValue>()
        for (i in 0 until list.length()) {
            val row = list.optJSONObject(i) ?: continue
            val source = ValueSource.of(row.optInt("source", -1)) ?: continue
            val time = row.optLong("time_ms", 0L)
            val mgdl = row.optInt("mgdl", 0)
            if (time <= 0L || mgdl <= 0 || mgdl > 2000) {
                continue
            }
            val change = if (row.isNull("change")) null else row.optDouble("change").takeIf { !it.isNaN() && !it.isInfinite() }
            val sensor = if (row.isNull("sensor")) null else row.optString("sensor")
            values.add(StoredValue(source, Glucose(time, mgdl, change, row.optInt("kind", 0) == 0, sensor)))
        }
        return BackupReply.ok(values)
    }

    /** Removes every value of this person from the copy. */
    fun removeAll(session: BackupSession): BackupReply<Boolean> {
        val response = send("DELETE", "/rest/v1/$TABLE?time_ms=gte.0", session.accessToken, null, mapOf("Prefer" to "return=minimal"))
            ?: return noAnswer()
        return if (response.isSuccess) BackupReply.ok(true) else BackupReply.failed(failure(response))
    }

    // ------------------------------------------------------------------ one request

    private var lastError = ""

    private fun send(method: String, path: String, accessToken: String?, body: String?, extra: Map<String, String> = emptyMap()): HttpResponse? {
        val headers = LinkedHashMap<String, String>()
        headers["apikey"] = key
        headers["Accept"] = "application/json"
        if (body != null) {
            headers["Content-Type"] = "application/json"
        }
        if (accessToken != null) {
            headers["Authorization"] = "Bearer $accessToken"
        }
        headers.putAll(extra)
        return try {
            transport.send(HttpRequest(method, url + path, headers, body, READ_TIMEOUT_MILLIS))
        } catch (e: IOException) {
            lastError = Net.words(e)
            null
        }
    }

    private fun <T> noAnswer(): BackupReply<T> = BackupReply.failed(BackupFailure(BackupProblem.NO_INTERNET, lastError))

    private fun json(response: HttpResponse): JSONObject? {
        return try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            null
        }
    }

    /** Why a request failed. The two parts of Supabase word their errors differently. */
    private fun failure(response: HttpResponse): BackupFailure {
        val json = json(response)
        val code = if (json != null) text(json, "error_code").ifEmpty { text(json, "code") }.ifEmpty { text(json, "error") } else ""
        val message = if (json != null) {
            text(json, "msg").ifEmpty { text(json, "message") }.ifEmpty { text(json, "error_description") }
        } else {
            ""
        }
        val all = (code + " " + message).lowercase(Locale.US)
        val problem = when {
            all.contains("email_not_confirmed") || all.contains("email not confirmed") -> BackupProblem.NOT_CONFIRMED
            all.contains("invalid_credentials") || all.contains("invalid login") || all.contains("invalid_grant") && !all.contains("refresh") -> BackupProblem.LOGIN
            all.contains("weak_password") || all.contains("password should") -> BackupProblem.WEAK_PASSWORD
            all.contains("user_already_exists") || all.contains("already registered") -> BackupProblem.EXISTS
            all.contains("api key") || all.contains("apikey") || all.contains("no api key") -> BackupProblem.KEY
            all.contains("pgrst205") || all.contains("42p01") || all.contains("42501") || all.contains("could not find the table") ||
                all.contains("does not exist") || all.contains("permission denied") -> BackupProblem.NO_TABLE
            response.code == 401 || all.contains("jwt") || all.contains("refresh") || all.contains("session") -> BackupProblem.PASS_EXPIRED
            else -> BackupProblem.SERVER
        }
        return BackupFailure(problem, ("HTTP " + response.code + " " + code + " " + message).replace(Regex("\\s+"), " ").trim().take(200))
    }

    private fun text(json: JSONObject, name: String): String {
        if (json.isNull(name)) {
            return ""
        }
        val found = json.opt(name)
        return if (found is String) found else found?.toString() ?: ""
    }

    companion object {
        const val TABLE = "glucose_values"

        /** How many values one request brings or takes. */
        const val PAGE = 500
        const val READ_TIMEOUT_MILLIS = 60_000

        private val PROJECT_URL = Regex("https://[a-z0-9][a-z0-9.-]{2,200}(:[0-9]{2,5})?")

        /** The address as the app needs it, or null if what was typed cannot be one. */
        fun cleanUrl(typed: String): String? {
            var url = typed.trim().lowercase(Locale.US).trimEnd('/')
            // people copy the address of a page of the project: keep only the first part
            for (tail in listOf("/rest/v1", "/auth/v1")) {
                if (url.endsWith(tail)) {
                    url = url.removeSuffix(tail)
                }
            }
            if (!url.startsWith("http")) {
                url = "https://$url"
            }
            return if (PROJECT_URL.matches(url)) url else null
        }
    }
}
