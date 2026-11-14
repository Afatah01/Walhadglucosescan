package com.glucosescan.app.cloud

import android.app.Application
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings
import com.glucosescan.app.data.StoredValue
import com.glucosescan.app.data.ValueSource
import com.glucosescan.app.net.HttpResponse
import com.glucosescan.app.testing.FakeServer
import com.glucosescan.app.testing.FakeSupabase
import com.glucosescan.app.ui.AppClock
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The online copy: accounts, sending values, getting them back. */
@RunWith(RobolectricTestRunner::class)
class BackupTest {
    private val minute = 60_000L
    private val url = "https://walhad-test.supabase.co"
    private var now = 1_794_310_340_000L
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private lateinit var server: FakeSupabase
    private lateinit var runner: BackupRunner

    @Before
    fun setUp() {
        GlucoseStore.closeShared()
        app = RuntimeEnvironment.getApplication()
        settings = Settings(app)
        store = GlucoseStore.get(app)
        server = FakeSupabase { now }
        runner = BackupRunner(store, settings, server)
        AppClock.source = { now }
    }

    @After
    fun tearDown() {
        GlucoseStore.closeShared()
        AppClock.source = { System.currentTimeMillis() }
        FakeServer.remove()
    }

    private fun sensor() = store.table(ValueSource.SENSOR)

    private fun linkUp() = store.table(ValueSource.LINKUP)

    /** [count] values, one each minute, the newest [ago] minutes ago. */
    private fun values(count: Int, ago: Int = 0, serial: String = "3MH001HM1LM"): List<Glucose> {
        val latest = now - now % minute - ago * minute
        return (count - 1 downTo 0).map { Glucose(latest - it * minute, 100 + it % 50, if (it % 2 == 0) -0.5 else null, it % 15 != 0, serial) }
    }

    private fun logIn(): FakeSupabase.Account {
        val account = server.account("me@example.com", "long-password")
        val result = runner.logIn(url, server.key, "me@example.com", "long-password", now)
        assertTrue(result.failure?.detail ?: "", result.isOk)
        return account
    }

    // ------------------------------------------------------------ accounts

    @Test
    fun aNewAccountMustConfirmItsEmailFirst() {
        val result = runner.signUp(url, server.key, " me@example.com ", "long-password", now)
        assertTrue(result.isOk)
        assertTrue(result.confirmFirst)
        assertFalse("nothing is switched on before the first login", settings.backupEnabled)
        assertEquals("", settings.backupPassword)

        val request = server.last
        assertEquals("POST", request.method)
        assertEquals("$url/auth/v1/signup", request.url)
        assertEquals("the key of the project goes with every request", server.key, request.headers["apikey"])
        assertFalse(request.headers.containsKey("Authorization"))
        assertEquals("me@example.com", org.json.JSONObject(request.body!!).getString("email"))

        // the link in the e-mail was not opened yet
        val early = runner.logIn(url, server.key, "me@example.com", "long-password", now)
        assertEquals(BackupProblem.NOT_CONFIRMED, early.failure!!.problem)
        assertFalse(settings.backupEnabled)

        server.accounts[0].confirmed = true
        assertTrue(runner.logIn(url, server.key, "me@example.com", "long-password", now).isOk)
        assertTrue(settings.backupEnabled)
    }

    @Test
    fun aProjectThatAsksForNoConfirmationLogsInAtOnce() {
        server.confirmEmail = false
        val result = runner.signUp(url, server.key, "me@example.com", "long-password", now)
        assertTrue(result.isOk)
        assertFalse(result.confirmFirst)
        assertTrue(settings.backupEnabled)
        assertEquals(server.accounts[0].id, settings.backupUserId)
    }

    @Test
    fun whatCanGoWrongWithAnAccount() {
        server.account("me@example.com", "long-password")
        assertEquals(BackupProblem.EXISTS, runner.signUp(url, server.key, "me@example.com", "other-password", now).failure!!.problem)
        assertEquals(BackupProblem.WEAK_PASSWORD, runner.signUp(url, server.key, "new@example.com", "abc", now).failure!!.problem)
        assertEquals(BackupProblem.LOGIN, runner.logIn(url, server.key, "me@example.com", "guess", now).failure!!.problem)
        assertEquals(BackupProblem.KEY, runner.logIn(url, "sb_publishable_wrong", "me@example.com", "long-password", now).failure!!.problem)
        server.offline = true
        assertEquals(BackupProblem.NO_INTERNET, runner.logIn(url, server.key, "me@example.com", "long-password", now).failure!!.problem)
        assertFalse(settings.backupEnabled)
        assertEquals("a failed login is no problem of a running copy", "", settings.backupProblem)
    }

    @Test
    fun theSecretsAreNotWrittenDownAsTheyAre() {
        logIn()
        val saved = app.getSharedPreferences("settings", 0).all
        for ((key, value) in saved) {
            assertFalse("$key holds the password", value.toString().contains("long-password"))
            assertFalse("$key holds a pass", value.toString().contains("access-1") || value.toString().contains("refresh-1"))
        }
        assertEquals("long-password", settings.backupPassword)
        assertEquals("access-1", settings.backupAccessToken)
    }

    // ------------------------------------------------------------ copying

    @Test
    fun theValuesOfBothSetsAreCopied() {
        val account = logIn()
        sensor().insert(values(3))
        linkUp().insert(listOf(Glucose(now - 5 * minute, 111, null, true, "LibreLinkUp 0M0063KNUJ0")))
        assertEquals(4, runner.waiting())

        val result = runner.copy(now)
        assertTrue(result.isOk)
        assertEquals(4, result.count)
        assertEquals(0, runner.waiting())
        assertEquals(4, settings.backupOnlineCount)
        assertEquals(now, settings.backupLastTime)
        assertEquals("", settings.backupProblem)

        val rows = server.rowsOf(account)
        assertEquals(4, rows.size)
        val row = rows.first { it.getInt("source") == 1 }
        assertEquals(account.id, row.getString("user_id"))
        assertEquals(now - now % minute - 5 * minute, row.getLong("time_ms"))
        assertEquals(111, row.getInt("mgdl"))
        assertEquals(0, row.getInt("kind"))
        assertTrue(row.isNull("change"))
        assertEquals("LibreLinkUp 0M0063KNUJ0", row.getString("sensor"))
        val own = rows.first { it.getInt("source") == 0 && it.getLong("time_ms") == now - now % minute }
        assertEquals(100, own.getInt("mgdl"))
        assertEquals(1, own.getInt("kind"))
        assertEquals(-0.5, own.getDouble("change"), 0.0001)

        // how the rows were sent
        val request = server.to("/rest/v1/glucose_values?on_conflict").first()
        assertEquals("POST", request.method)
        assertEquals("$url/rest/v1/glucose_values?on_conflict=user_id,source,time_ms", request.url)
        assertEquals(server.key, request.headers["apikey"])
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("a value that is there already is left alone", "resolution=ignore-duplicates,return=minimal", request.headers["Prefer"])
        assertEquals(3, JSONArray(request.body!!).length())

        // nothing new: nothing is sent
        val before = server.to("on_conflict").size
        now += minute
        val again = runner.copy(now)
        assertTrue(again.isOk)
        assertEquals(0, again.count)
        assertEquals(before, server.to("on_conflict").size)

        // one more value: only that one is sent
        sensor().insert(listOf(Glucose(now, 97, null, true, "3MH001HM1LM")))
        assertEquals(1, runner.copy(now).count)
        assertEquals(1, JSONArray(server.to("on_conflict").last().body!!).length())
        assertEquals(5, settings.backupOnlineCount)
    }

    @Test
    fun manyValuesGoInPiecesOfFiveHundred() {
        logIn()
        sensor().insert(values(1203))
        assertEquals(1203, runner.copy(now).count)
        assertEquals(listOf(500, 500, 203), server.to("on_conflict").map { JSONArray(it.body!!).length() })
        assertEquals(1203, server.rows.size)
    }

    @Test
    fun aPassThatHasEndedIsRenewed() {
        logIn()
        sensor().insert(values(2))
        assertTrue(runner.copy(now).isOk)
        assertEquals(1, server.logins)

        // the pass is good for an hour; the app gets the next one by itself
        now += 61 * minute
        sensor().insert(listOf(Glucose(now, 99, null, true, "3MH001HM1LM")))
        assertEquals(1, runner.copy(now).count)
        assertEquals(1, server.refreshed)
        assertEquals("no password was needed", 1, server.logins)
        assertEquals("access-2", settings.backupAccessToken)
        assertEquals("the pass for the next pass is new too: the old one works only once", "refresh-2", settings.backupRefreshToken)

        // the server ends a pass before its time
        server.endPasses()
        now += minute
        sensor().insert(listOf(Glucose(now, 98, null, true, "3MH001HM1LM")))
        assertEquals(1, runner.copy(now).count)
        assertEquals(2, server.refreshed)

        // every pass is gone (a long time without internet): the saved password helps
        server.endAllLogins()
        now += minute
        sensor().insert(listOf(Glucose(now, 97, null, true, "3MH001HM1LM")))
        assertEquals(1, runner.copy(now).count)
        assertEquals(2, server.logins)
        assertEquals(5, server.rows.size)
    }

    @Test
    fun aPasswordThatWasChangedElsewhereStopsTheCopy() {
        val account = logIn()
        account.password = "changed-on-the-web"
        server.endAllLogins()
        sensor().insert(values(2))
        now += minute
        val result = runner.copy(now)
        assertEquals(BackupProblem.LOGIN, result.failure!!.problem)
        assertEquals("LOGIN", settings.backupProblem)
        assertEquals("the wrong password is forgotten, not tried again and again", "", settings.backupPassword)
        val asked = server.requests.size
        now += minute
        assertEquals(BackupProblem.LOGIN, runner.copy(now).failure!!.problem)
        assertEquals("and the dead passes are not sent again either", asked, server.requests.size)
        assertEquals("the values wait", 2, runner.waiting())
    }

    @Test
    fun withoutTheTableTheUserIsToldToRunTheSetUpText() {
        logIn()
        server.tableExists = false
        sensor().insert(values(2))
        val result = runner.copy(now)
        assertEquals(BackupProblem.NO_TABLE, result.failure!!.problem)
        assertEquals("NO_TABLE", settings.backupProblem)
        assertEquals(2, runner.waiting())

        server.tableExists = true
        now += minute
        assertEquals(2, runner.copy(now).count)
        assertEquals("", settings.backupProblem)
    }

    @Test
    fun withoutInternetTheValuesWait() {
        logIn()
        sensor().insert(values(2))
        server.offline = true
        assertEquals(BackupProblem.NO_INTERNET, runner.copy(now).failure!!.problem)
        assertEquals(2, runner.waiting())
        assertEquals(0L, settings.backupLastTime)
        server.offline = false
        assertEquals(2, runner.copy(now + minute).count)
    }

    @Test
    fun switchedOffNothingIsSent() {
        sensor().insert(values(2))
        assertFalse(runner.copy(now).isOk)
        assertFalse(runner.restore(now).isOk)
        assertFalse(runner.removeOnline(now).isOk)
        assertEquals(0, server.requests.size)
    }

    // ------------------------------------------------------------ getting the values back

    @Test
    fun aNewPhoneGetsTheValuesBack() {
        val account = logIn()
        sensor().insert(values(700))
        linkUp().insert(values(40, serial = "LibreLinkUp 0M0063KNUJ0"))
        assertEquals(740, runner.copy(now).count)

        // a new phone: nothing on it, the same account
        store.deleteAll()
        settings.forgetBackupLogin()
        assertEquals(0, store.countAll())
        assertTrue(runner.logIn(url, server.key, "me@example.com", "long-password", now).isOk)
        val result = runner.restore(now)
        assertTrue(result.isOk)
        assertEquals(740, result.count)
        assertEquals(700, sensor().count())
        assertEquals(40, linkUp().count())
        assertEquals("what came from the copy is not sent there again", 0, runner.waiting())

        // every field is back
        val newest = sensor().latest()!!
        assertEquals(now - now % minute, newest.timestamp)
        assertEquals(100, newest.value)
        assertEquals(-0.5, newest.minuteChange!!, 0.0001)
        assertFalse(newest.fromTrend)
        assertEquals("3MH001HM1LM", newest.sensorSerial)
        assertEquals("LibreLinkUp 0M0063KNUJ0", linkUp().latest()!!.sensorSerial)

        // the pages that were asked for
        val pages = server.to("select=source,time_ms,mgdl,kind,change,sensor")
        assertEquals(listOf("0", "500"), pages.map { it.url.substringAfter("offset=") })
        assertTrue(pages[0].url.contains("order=time_ms.asc,source.asc"))
        assertTrue(pages[0].url.contains("limit=500"))

        // a second time brings nothing new
        assertEquals(0, runner.restore(now).count)
        assertEquals(740, server.rowsOf(account).size)
    }

    @Test
    fun onlyTheLastNinetyDaysComeBack() {
        logIn()
        val old = Glucose(now - 100L * 24 * 60 * minute, 150, null, true, "3MH001HM1LM")
        val recent = Glucose(now - 80L * 24 * 60 * minute, 140, null, true, "3MH001HM1LM")
        sensor().insert(listOf(old, recent))
        runner.copy(now)
        store.deleteAll()
        assertEquals("the phone keeps 90 days; older values stay in the copy", 1, runner.restore(now).count)
        assertEquals(140, sensor().latest()!!.value)
        assertTrue(server.to("select=source").last().url.contains("time_ms=gte." + (now - 90L * 24 * 60 * minute)))
    }

    @Test
    fun theCopyOfAnotherPersonIsNeverSeen() {
        val first = logIn()
        sensor().insert(values(5))
        runner.copy(now)

        // somebody else logs in on this phone, in the same project
        runner.logOut()
        val second = server.account("other@example.com", "another-password")
        assertTrue(runner.logIn(url, server.key, "other@example.com", "another-password", now).isOk)
        assertEquals("a new account: its copy is empty, so everything on the phone waits again", 5, runner.waiting())
        store.deleteAll()
        assertEquals(0, runner.restore(now).count)
        assertEquals(5, server.rowsOf(first).size)
        assertEquals(0, server.rowsOf(second).size)
    }

    @Test
    fun emptyingTheCopyKeepsThePhone() {
        val account = logIn()
        sensor().insert(values(5))
        runner.copy(now)
        assertTrue(runner.removeOnline(now).isOk)
        assertEquals(0, server.rowsOf(account).size)
        assertEquals(5, sensor().count())
        assertEquals("they can be copied again", 5, runner.waiting())
        assertEquals(0, settings.backupOnlineCount)
        val request = server.to("glucose_values?time_ms=gte.0").last()
        assertEquals("DELETE", request.method)
    }

    @Test
    fun loggingOutForgetsTheLogin() {
        logIn()
        runner.logOut()
        assertEquals("$url/auth/v1/logout", server.last.url)
        assertEquals("Bearer access-1", server.last.headers["Authorization"])
        assertFalse(settings.backupEnabled)
        assertEquals("", settings.backupPassword)
        assertEquals("", settings.backupAccessToken)
        assertEquals("", settings.backupRefreshToken)
        assertEquals("the address, the key and the e-mail stay for the next time", url, settings.backupUrl)
        assertEquals("me@example.com", settings.backupEmail)
    }

    // ------------------------------------------------------------ by itself

    @Test
    fun theAppCopiesByItselfButNotAllTheTime() {
        FakeServer.install(server)
        logIn()
        sensor().insert(values(2))
        Backup.request(app)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(2, server.rows.size)

        // five minutes later a live value comes in: too early for the next copy
        now += 5 * minute
        sensor().insert(listOf(Glucose(now, 99, null, true, "3MH001HM1LM")))
        Backup.request(app)
        assertEquals(2, server.rows.size)

        // after a scan the copy is made at once
        Backup.request(app, soon = true)
        assertEquals(3, server.rows.size)

        // and after a quarter of an hour again by itself
        now += 16 * minute
        sensor().insert(listOf(Glucose(now, 98, null, true, "3MH001HM1LM")))
        Backup.request(app)
        assertEquals(4, server.rows.size)

        // switched off: nothing
        runner.logOut()
        now += 20 * minute
        sensor().insert(listOf(Glucose(now, 97, null, true, "3MH001HM1LM")))
        Backup.request(app, soon = true)
        assertEquals(4, server.rows.size)
    }

    // ------------------------------------------------------------ small things

    @Test
    fun theAddressOfTheProject() {
        assertEquals("https://abcdefgh.supabase.co", SupabaseApi.cleanUrl(" https://ABCDEFGH.supabase.co/ "))
        assertEquals("https://abcdefgh.supabase.co", SupabaseApi.cleanUrl("abcdefgh.supabase.co"))
        assertEquals("people copy the address of a part of the project", "https://abcdefgh.supabase.co", SupabaseApi.cleanUrl("https://abcdefgh.supabase.co/rest/v1/"))
        assertEquals("https://db.example.org:8443", SupabaseApi.cleanUrl("https://db.example.org:8443"))
        assertNull(SupabaseApi.cleanUrl(""))
        assertNull(SupabaseApi.cleanUrl("not an address"))
        assertNull(SupabaseApi.cleanUrl("https://abc.supabase.co/some/page"))
        assertNull("no plain http: the values would travel unprotected", SupabaseApi.cleanUrl("http://abcdefgh.supabase.co"))
    }

    @Test
    fun theNumberOfValuesInTheCopy() {
        val session = BackupSession("a", "r", now + 3_600_000L, "u")
        val api = SupabaseApi(server, url, server.key)
        server.answer = { HttpResponse(200, "[]", mapOf("Content-Range" to "*/0")) }
        assertEquals(0, api.count(session).value)
        server.answer = { HttpResponse(206, "[{\"time_ms\":1}]", mapOf("content-range" to "0-0/1234")) }
        assertEquals(1234, api.count(session).value)
        assertEquals("count=exact", server.last.headers["Prefer"])
        server.answer = { HttpResponse(200, "[]") }
        assertEquals(BackupProblem.SERVER, api.count(session).failure!!.problem)
    }

    @Test
    fun damagedRowsOfTheCopyAreLeftOut() {
        val session = BackupSession("a", "r", now + 3_600_000L, "u")
        val api = SupabaseApi(server, url, server.key)
        server.answer = {
            HttpResponse(
                200,
                "[{\"source\":0,\"time_ms\":1794310320000,\"mgdl\":104,\"kind\":0,\"change\":null,\"sensor\":null}," +
                    "{\"source\":7,\"time_ms\":1794310320000,\"mgdl\":104,\"kind\":0}," +
                    "{\"source\":1,\"time_ms\":0,\"mgdl\":104,\"kind\":0}," +
                    "{\"source\":1,\"time_ms\":1794310320000,\"mgdl\":-4,\"kind\":0}," +
                    "\"text\"," +
                    "{\"source\":1,\"time_ms\":1794310380000,\"mgdl\":99,\"kind\":1,\"change\":-1.25,\"sensor\":\"LibreLinkUp\"}]"
            )
        }
        val values: List<StoredValue> = api.page(session, 0L, 0).value!!
        assertEquals(2, values.size)
        assertEquals(ValueSource.SENSOR, values[0].source)
        assertNull(values[0].glucose.sensorSerial)
        assertNull(values[0].glucose.minuteChange)
        assertEquals(ValueSource.LINKUP, values[1].source)
        assertFalse(values[1].glucose.fromTrend)
        assertEquals(-1.25, values[1].glucose.minuteChange!!, 0.0001)
    }
}
