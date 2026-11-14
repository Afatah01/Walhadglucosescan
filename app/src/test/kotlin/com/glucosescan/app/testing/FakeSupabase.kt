package com.glucosescan.app.testing

import com.glucosescan.app.net.HttpRequest
import com.glucosescan.app.net.HttpResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/**
 * A pretended Supabase project: accounts with a login, and the table "glucose_values" in
 * which each person reaches only the own rows. It answers the way the Supabase documents
 * describe the real one.
 */
class FakeSupabase(private val clock: () -> Long) : FakeServer() {
    class Account(val id: String, val email: String, var password: String, var confirmed: Boolean)

    var key = "sb_publishable_test_1234"

    /** True: a new account must open the link in an e-mail before its first login (the usual setting). */
    var confirmEmail = true
    val accounts = ArrayList<Account>()

    /** The rows of the table, by person, source and time. */
    val rows = TreeMap<String, JSONObject>()

    /** False: the set-up text was not run yet. */
    var tableExists = true

    /** How long a pass is good, in seconds. */
    var passSeconds = 3600L

    /** How many values one page holds at most (the project setting "max rows"). */
    var maxRows = 1000
    private val passes = HashMap<String, Pair<String, Long>>() // pass -> account, end
    private val refreshes = HashMap<String, String>() // pass for the next pass -> account
    private var counter = 0
    var logins = 0
    var refreshed = 0

    init {
        answer = { request -> handle(request) }
    }

    fun account(email: String, password: String, confirmed: Boolean = true): Account {
        val account = Account("user-" + (accounts.size + 1) + "-0000-4000-8000-00000000000" + (accounts.size + 1), email, password, confirmed)
        accounts.add(account)
        return account
    }

    /** Ends every pass that was given out; the passes for a next pass stay good. */
    fun endPasses() {
        passes.clear()
    }

    /** Ends everything: the app must log in with the password again. */
    fun endAllLogins() {
        passes.clear()
        refreshes.clear()
    }

    fun rowsOf(account: Account): List<JSONObject> = rows.filterKeys { it.startsWith(account.id + "/") }.values.toList()

    private fun handle(request: HttpRequest): HttpResponse {
        if (request.headers["apikey"] != key) {
            return HttpResponse(401, "{\"message\":\"Invalid API key\",\"hint\":\"Double check your Supabase `anon` or `service_role` API key.\"}")
        }
        val path = request.url.removePrefix("https://").substringAfter('/', "").let { "/$it" }
        return when {
            path == "/auth/v1/signup" -> signUp(JSONObject(request.body ?: "{}"))
            path == "/auth/v1/token?grant_type=password" -> logIn(JSONObject(request.body ?: "{}"))
            path == "/auth/v1/token?grant_type=refresh_token" -> refresh(JSONObject(request.body ?: "{}"))
            path == "/auth/v1/logout" -> {
                val who = person(request)
                if (who != null) {
                    passes.entries.removeAll { it.value.first == who }
                    refreshes.entries.removeAll { it.value == who }
                }
                HttpResponse(204, "")
            }
            path.startsWith("/rest/v1/glucose_values") -> table(request, path)
            else -> HttpResponse(404, "{\"message\":\"nothing here\"}")
        }
    }

    private fun signUp(body: JSONObject): HttpResponse {
        val email = body.optString("email")
        val password = body.optString("password")
        if (accounts.any { it.email == email }) {
            return HttpResponse(422, "{\"code\":422,\"error_code\":\"user_already_exists\",\"msg\":\"User already registered\"}")
        }
        if (password.length < 6) {
            return HttpResponse(422, "{\"code\":422,\"error_code\":\"weak_password\",\"msg\":\"Password should be at least 6 characters.\"}")
        }
        val account = account(email, password, confirmed = !confirmEmail)
        if (confirmEmail) {
            return HttpResponse(200, JSONObject().put("id", account.id).put("email", email).put("confirmation_sent_at", "2026-11-10T11:32:20Z").toString())
        }
        return session(account)
    }

    private fun logIn(body: JSONObject): HttpResponse {
        val account = accounts.firstOrNull { it.email == body.optString("email") && it.password == body.optString("password") }
            ?: return HttpResponse(400, "{\"code\":400,\"error_code\":\"invalid_credentials\",\"msg\":\"Invalid login credentials\"}")
        if (!account.confirmed) {
            return HttpResponse(400, "{\"code\":400,\"error_code\":\"email_not_confirmed\",\"msg\":\"Email not confirmed\"}")
        }
        logins++
        return session(account)
    }

    private fun refresh(body: JSONObject): HttpResponse {
        val id = refreshes.remove(body.optString("refresh_token")) // a pass for the next pass works once
            ?: return HttpResponse(400, "{\"code\":400,\"error_code\":\"refresh_token_not_found\",\"msg\":\"Invalid Refresh Token: Refresh Token Not Found\"}")
        refreshed++
        return session(accounts.first { it.id == id })
    }

    private fun session(account: Account): HttpResponse {
        counter++
        val pass = "access-$counter"
        val next = "refresh-$counter"
        passes[pass] = Pair(account.id, clock() + passSeconds * 1000L)
        refreshes[next] = account.id
        val json = JSONObject()
            .put("access_token", pass)
            .put("token_type", "bearer")
            .put("expires_in", passSeconds)
            .put("refresh_token", next)
            .put("user", JSONObject().put("id", account.id).put("email", account.email))
        return HttpResponse(200, json.toString())
    }

    /** The account a request comes from, or null if its pass is not good (any more). */
    private fun person(request: HttpRequest): String? {
        val pass = (request.headers["Authorization"] ?: "").removePrefix("Bearer ")
        val found = passes[pass] ?: return null
        return if (clock() < found.second) found.first else null
    }

    private fun table(request: HttpRequest, path: String): HttpResponse {
        val who = person(request) ?: return HttpResponse(401, "{\"code\":\"PGRST303\",\"details\":null,\"hint\":null,\"message\":\"JWT expired\"}")
        if (!tableExists) {
            return HttpResponse(404, "{\"code\":\"PGRST205\",\"details\":null,\"hint\":null,\"message\":\"Could not find the table 'public.glucose_values' in the schema cache\"}")
        }
        val query = HashMap<String, String>()
        for (part in path.substringAfter('?', "").split('&')) {
            if (part.contains('=')) {
                query[part.substringBefore('=')] = part.substringAfter('=')
            }
        }
        val mine = who + "/"
        when (request.method) {
            "POST" -> {
                val list = JSONArray(request.body ?: "[]")
                for (i in 0 until list.length()) {
                    val row = list.getJSONObject(i)
                    if (row.optString("user_id") != who) {
                        return HttpResponse(403, "{\"code\":\"42501\",\"message\":\"new row violates row-level security policy for table \\\"glucose_values\\\"\"}")
                    }
                    val id = mine + row.getInt("source") + "/" + String.format("%015d", row.getLong("time_ms"))
                    if (!rows.containsKey(id)) { // "ignore duplicates"
                        rows[id] = row
                    }
                }
                return HttpResponse(201, "")
            }
            "DELETE" -> {
                if (!query.containsKey("time_ms")) {
                    return HttpResponse(400, "{\"code\":\"21000\",\"message\":\"DELETE requires a WHERE clause\"}")
                }
                rows.keys.removeAll { it.startsWith(mine) }
                return HttpResponse(204, "")
            }
            "GET" -> {
                val from = (query["time_ms"] ?: "gte.0").removePrefix("gte.").toLong()
                val all = rows.filterKeys { it.startsWith(mine) }.values
                    .filter { it.getLong("time_ms") >= from }
                    .sortedWith(compareBy({ it.getLong("time_ms") }, { it.getInt("source") }))
                val limit = Math.min(query["limit"]?.toIntOrNull() ?: maxRows, maxRows)
                val offset = query["offset"]?.toIntOrNull() ?: 0
                val page = all.drop(offset).take(limit)
                val out = JSONArray()
                val columns = (query["select"] ?: "").split(',')
                for (row in page) {
                    val copy = JSONObject()
                    for (column in columns) {
                        copy.put(column, row.opt(column) ?: JSONObject.NULL)
                    }
                    out.put(copy)
                }
                val headers = HashMap<String, String>()
                if ((request.headers["Prefer"] ?: "").contains("count=exact")) {
                    headers["content-range"] = if (page.isEmpty()) "*/" + all.size else offset.toString() + "-" + (offset + page.size - 1) + "/" + all.size
                }
                return HttpResponse(200, out.toString(), headers)
            }
        }
        return HttpResponse(405, "{\"message\":\"method not allowed\"}")
    }
}
