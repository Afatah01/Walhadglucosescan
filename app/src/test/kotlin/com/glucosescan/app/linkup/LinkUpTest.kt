package com.glucosescan.app.linkup

import android.app.Application
import com.glucosescan.app.core.Correction
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.ReferencePair
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LinkUpHandler
import com.glucosescan.app.data.LinkUpState
import com.glucosescan.app.data.Settings
import com.glucosescan.app.data.ValueSource
import com.glucosescan.app.net.HttpResponse
import com.glucosescan.app.testing.FakeLinkUp
import com.glucosescan.app.testing.FakeServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.TimeZone

/** The questions the app asks LibreLinkUp, and what it makes of the answers. */
@RunWith(RobolectricTestRunner::class)
class LinkUpApiTest {
    private val minute = 60_000L

    /** Tuesday 10 November 2026, 14:32:20 local time (UTC+3) (11:32:20 UTC) */
    private var now = 1_794_310_340_000L
    private val zone = TimeZone.getTimeZone("Africa/Mogadishu")
    private lateinit var server: FakeLinkUp
    private lateinit var api: LinkUpApi

    @Before
    fun setUp() {
        TimeZone.setDefault(zone)
        server = FakeLinkUp { now }
        api = LinkUpApi(server)
    }

    private fun login(): LinkUpLogin = api.login(server.email, server.password).value!!

    @Test
    fun theLoginGoesToTheRegionOfTheAccount() {
        val reply = api.login(" " + server.email + " ", server.password)
        assertTrue(reply.isOk)
        val login = reply.value!!
        assertEquals("pass-1", login.token)
        assertEquals(server.userId, login.userId)
        assertEquals("eu", login.region)
        assertEquals(now + 180L * 24 * 60 * minute, login.expires + (now % 1000)) // the server names whole seconds

        // first the main server, which names the region; then the server of the region
        assertEquals(listOf("https://api.libreview.io/llu/auth/login", "https://api-eu.libreview.io/llu/auth/login"), server.requests.map { it.url })
        val first = server.requests[0]
        assertEquals("POST", first.method)
        assertEquals("llu.android", first.headers["product"])
        assertEquals("4.17.0", first.headers["version"])
        assertEquals("application/json", first.headers["Content-Type"])
        assertFalse("nobody is logged in yet: no pass", first.headers.containsKey("Authorization"))
        assertFalse("and no account number", first.headers.containsKey("Account-Id"))
        val body = JSONObject(first.body!!)
        assertEquals(server.email, body.getString("email"))
        assertEquals(server.password, body.getString("password"))
        assertEquals(2, body.length())
    }

    @Test
    fun aKnownRegionIsAskedDirectly() {
        assertTrue(api.login(server.email, server.password, "eu").isOk)
        assertEquals(listOf("https://api-eu.libreview.io/llu/auth/login"), server.requests.map { it.url })
    }

    @Test
    fun anAccountOnTheMainServerNeedsNoRegion() {
        server.region = ""
        val login = login()
        assertEquals("", login.region)
        assertEquals(1, server.requests.size)
        assertTrue(api.patients(login).isOk)
        assertEquals("https://api.libreview.io/llu/connections", server.last.url)
    }

    @Test
    fun theServerCanAskForANewerAppVersion() {
        server.minimumVersion = "5.2.0"
        val reply = api.login(server.email, server.password, "eu")
        assertTrue(reply.isOk)
        assertEquals("the app goes on with the version the server named", "5.2.0", api.version)
        assertEquals(listOf("4.17.0", "5.2.0"), server.requests.map { it.headers["version"] })

        // and keeps using it
        api.patients(reply.value!!)
        assertEquals("5.2.0", server.last.headers["version"])
    }

    @Test
    fun aWrongPasswordIsToldApart() {
        val reply = api.login(server.email, "guess", "eu")
        assertFalse(reply.isOk)
        assertEquals(LinkUpProblem.WRONG_LOGIN, reply.failure!!.problem)
        assertFalse("the words about the problem never hold the password", reply.failure!!.detail.contains("guess"))
    }

    @Test
    fun aLockedAccountSaysHowLongToWait() {
        server.lockedSeconds = 300
        val reply = api.login(server.email, server.password, "eu")
        assertEquals(LinkUpProblem.LOCKED, reply.failure!!.problem)
        assertEquals(300, reply.failure!!.waitSeconds)

        // a number that makes no sense is kept between one minute and one hour
        server.lockedSeconds = 5
        assertEquals(60, api.login(server.email, server.password, "eu").failure!!.waitSeconds)
        server.lockedSeconds = 99_999
        assertEquals(3600, api.login(server.email, server.password, "eu").failure!!.waitSeconds)

        // "too many requests" as a plain HTTP answer, with the header that names the pause
        server.answer = { HttpResponse(429, "", mapOf("retry-after" to "120")) }
        val plain = api.login(server.email, server.password, "eu")
        assertEquals(LinkUpProblem.LOCKED, plain.failure!!.problem)
        assertEquals(120, plain.failure!!.waitSeconds)
    }

    @Test
    fun theTermsOfUseMustBeAcceptedFirst() {
        server.steps.add("tou")
        server.steps.add("pp")
        val reply = api.login(server.email, server.password, "eu")
        val failure = reply.failure!!
        assertEquals(LinkUpProblem.NEEDS_STEP, failure.problem)
        assertEquals("tou", failure.step)
        assertEquals("step-pass", failure.stepToken)
        assertEquals("eu", failure.region)

        // after the terms of use the server wants the privacy notice
        val next = api.acceptStep("tou", failure.stepToken, "eu")
        assertEquals("https://api-eu.libreview.io/auth/continue/tou", server.last.url)
        assertEquals("Bearer step-pass", server.last.headers["Authorization"])
        assertEquals(LinkUpProblem.NEEDS_STEP, next.failure!!.problem)
        assertEquals("pp", next.failure!!.step)

        val done = api.acceptStep("pp", next.failure!!.stepToken, "eu")
        assertTrue(done.isOk)
        assertEquals("pass-1", done.value!!.token)
        assertEquals("eu", done.value!!.region)

        assertEquals(LinkUpProblem.BAD_ANSWER, api.acceptStep("anything", "x", "eu").failure!!.problem)
    }

    @Test
    fun thePeopleWhoShareTheirValues() {
        server.patients.add(FakeLinkUp.Patient("9b1d0a1e-1111-4000-8000-000000000002", "Second", ""))
        val login = login()
        val reply = api.patients(login)
        assertTrue(reply.isOk)
        assertEquals(listOf("Sample Person", "Second"), reply.value!!.map { it.name })
        assertEquals("9b1d0a1e-1111-4000-8000-000000000001", reply.value!![0].id)

        val request = server.last
        assertEquals("GET", request.method)
        assertEquals("https://api-eu.libreview.io/llu/connections", request.url)
        assertEquals("Bearer pass-1", request.headers["Authorization"])
        assertEquals("the account number goes along as SHA-256, never as it is", LinkUpApi.sha256(server.userId), request.headers["Account-Id"])
        assertNull(request.body)

        server.patients.clear()
        assertEquals("nobody shares with this account: an empty list, not an error", 0, api.patients(login).value!!.size)
    }

    @Test
    fun sha256IsTheUsualOne() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LinkUpApi.sha256("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", LinkUpApi.sha256(""))
    }

    @Test
    fun theValuesOfAPerson() {
        server.current = FakeLinkUp.Value(now - 40_000L, 104, 2)
        server.history.add(FakeLinkUp.Value(now - 32 * minute, 131))
        server.history.add(FakeLinkUp.Value(now - 17 * minute, 118))
        server.sensorStart = now - 5 * 24 * 60 * minute
        val login = login()
        val reply = api.values(login, server.patients[0].id, zone)
        assertTrue(reply.isOk)
        assertEquals("https://api-eu.libreview.io/llu/connections/9b1d0a1e-1111-4000-8000-000000000001/graph", server.last.url)

        val data = reply.value!!
        // LibreLinkUp writes times to the second
        assertEquals((now - 40_000L) / 1000 * 1000, data.current!!.time)
        assertEquals(104, data.current!!.mgdl)
        assertEquals(2, data.current!!.arrow)
        assertEquals(listOf(131, 118), data.history.map { it.mgdl })
        assertEquals((now - 32 * minute) / 1000 * 1000, data.history[0].time)
        assertEquals("0M0063KNUJ0", data.sensorSerial)
        assertEquals((now - 5 * 24 * 60 * minute) / 1000 * 1000, data.sensorStart)
    }

    @Test
    fun aPersonWithoutAValue() {
        val data = api.values(login(), server.patients[0].id, zone).value!!
        assertNull(data.current)
        assertTrue(data.history.isEmpty())
    }

    @Test
    fun whatIsNotAPatientNumberIsNeverPutIntoAnAddress() {
        val login = login()
        val before = server.requests.size
        assertEquals(LinkUpProblem.BAD_ANSWER, api.values(login, "../../auth/login", zone).failure!!.problem)
        assertEquals(LinkUpProblem.BAD_ANSWER, api.values(login, "", zone).failure!!.problem)
        assertEquals(before, server.requests.size)
    }

    @Test
    fun aPassThatHasEndedIsToldApart() {
        val login = login()
        server.endToken()
        assertEquals(LinkUpProblem.LOGIN_EXPIRED, api.values(login, server.patients[0].id, zone).failure!!.problem)
        assertEquals(LinkUpProblem.LOGIN_EXPIRED, api.patients(login).failure!!.problem)
    }

    @Test
    fun noInternetAndBrokenAnswers() {
        val login = login()
        server.offline = true
        assertEquals(LinkUpProblem.NO_INTERNET, api.values(login, server.patients[0].id, zone).failure!!.problem)
        assertEquals(LinkUpProblem.NO_INTERNET, api.login(server.email, server.password).failure!!.problem)
        server.offline = false

        // a page of a web shield instead of an answer
        server.answer = { HttpResponse(403, "<html>Just a moment...</html>") }
        assertEquals(LinkUpProblem.BAD_ANSWER, api.patients(login).failure!!.problem)
        server.answer = { HttpResponse(502, "Bad Gateway") }
        assertEquals(LinkUpProblem.SERVER, api.patients(login).failure!!.problem)
        server.answer = { HttpResponse(200, "{\"status\":0,\"data\":{}}") }
        assertEquals("a login without a pass", LinkUpProblem.BAD_ANSWER, api.login("a@b.c", "x", "eu").failure!!.problem)
        assertEquals(LinkUpProblem.BAD_ANSWER, api.patients(login).failure!!.problem)
        assertEquals(LinkUpProblem.BAD_ANSWER, api.values(login, server.patients[0].id, zone).failure!!.problem)
        server.answer = { HttpResponse(400, "{\"message\":\"RequiredHeaderMissing\"}") }
        assertEquals(LinkUpProblem.SERVER, api.patients(login).failure!!.problem)
        assertTrue(api.patients(login).failure!!.detail.contains("RequiredHeaderMissing"))
        server.answer = { HttpResponse(200, "{\"status\":0,\"data\":{\"redirect\":true,\"region\":\"../evil\"}}") }
        val before = server.requests.size
        assertEquals("a region that is no region is not followed", LinkUpProblem.BAD_ANSWER, api.login("a@b.c", "x").failure!!.problem)
        assertEquals(1, server.requests.size - before)
    }

    // ------------------------------------------------------------ reading values

    private fun value(json: String): LinkUpValue? = LinkUpParser.value(JSONObject(json), zone)

    @Test
    fun theTimeOfAValue() {
        // 11:32:20 in London (UTC) is 14:32:20 three hours further east
        val utc = value("{\"FactoryTimestamp\":\"11/10/2026 11:32:20 AM\",\"Timestamp\":\"11/10/2026 2:32:20 PM\",\"ValueInMgPerDl\":104,\"TrendArrow\":3}")!!
        assertEquals(now, utc.time)
        assertEquals("only the clock of the other phone: read by the clock of this place", now, value("{\"Timestamp\":\"11/10/2026 2:32:20 PM\",\"ValueInMgPerDl\":104}")!!.time)
        assertEquals("midnight is 12 AM", now - 11 * 60 * minute - 32 * minute - 20_000L, value("{\"FactoryTimestamp\":\"11/10/2026 12:00:00 AM\",\"ValueInMgPerDl\":104}")!!.time)
        assertEquals("a narrow space before PM, as newer systems write it", now + 60 * minute, value("{\"FactoryTimestamp\":\"11/10/2026 12:32:20\\u202FPM\",\"ValueInMgPerDl\":104}")!!.time)

        assertNull("no time", value("{\"ValueInMgPerDl\":104}"))
        assertNull("not a time", value("{\"FactoryTimestamp\":\"yesterday\",\"ValueInMgPerDl\":104}"))
        assertNull("the 13th month", value("{\"FactoryTimestamp\":\"13/10/2026 11:32:20 AM\",\"ValueInMgPerDl\":104}"))
        assertNull("something after the time", value("{\"FactoryTimestamp\":\"11/10/2026 11:32:20 AM xyz\",\"ValueInMgPerDl\":104}"))
    }

    @Test
    fun theNumberOfAValue() {
        val time = "\"FactoryTimestamp\":\"11/10/2026 11:32:20 AM\""
        assertEquals(104, value("{$time,\"ValueInMgPerDl\":104}")!!.mgdl)
        assertEquals("no arrow given", 0, value("{$time,\"ValueInMgPerDl\":104}")!!.arrow)
        assertEquals("an arrow that does not exist", 0, value("{$time,\"ValueInMgPerDl\":104,\"TrendArrow\":9}")!!.arrow)
        assertEquals("only the number in mmol/L: 5.8 mmol/L", 104, value("{$time,\"Value\":5.8,\"GlucoseUnits\":0}")!!.mgdl)
        assertEquals("only the number in mg/dL", 104, value("{$time,\"Value\":104,\"GlucoseUnits\":1}")!!.mgdl)
        assertNull("no number", value("{$time}"))
        assertNull("zero", value("{$time,\"ValueInMgPerDl\":0}"))
        assertNull("far too high", value("{$time,\"ValueInMgPerDl\":5000}"))
        assertEquals("what the sensor calls LO is kept; the app shows it as low", 39, value("{$time,\"ValueInMgPerDl\":39}")!!.mgdl)
    }

    @Test
    fun brokenEntriesOfTheListAreLeftOut() {
        val connection = JSONObject(
            "{\"patientId\":\"x\",\"sensor\":{\"sn\":null,\"a\":0}," +
                "\"glucoseItem\":{\"FactoryTimestamp\":\"11/10/2026 11:32:20 AM\",\"ValueInMgPerDl\":104,\"TrendArrow\":4}}"
        )
        val graph = org.json.JSONArray(
            "[{\"FactoryTimestamp\":\"11/10/2026 11:15:00 AM\",\"ValueInMgPerDl\":118}," +
                "{\"FactoryTimestamp\":\"\",\"ValueInMgPerDl\":120}," +
                "\"not an entry\"," +
                "{\"FactoryTimestamp\":\"11/10/2026 11:00:00 AM\",\"ValueInMgPerDl\":125}]"
        )
        val data = LinkUpParser.data(connection, graph, null, zone)
        assertEquals("the newest value under its other name", 104, data.current!!.mgdl)
        assertEquals("oldest first", listOf(125, 118), data.history.map { it.mgdl })
        assertNull(data.sensorSerial)
        assertEquals(0L, data.sensorStart)
    }
}

/** Logging in, fetching once a minute, and what the app does when something goes wrong. */
@RunWith(RobolectricTestRunner::class)
class LinkUpRunnerTest {
    private val minute = 60_000L
    private var now = 1_794_310_340_000L
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private lateinit var server: FakeLinkUp
    private lateinit var runner: LinkUpRunner

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Mogadishu"))
        GlucoseStore.closeShared()
        app = RuntimeEnvironment.getApplication()
        settings = Settings(app)
        store = GlucoseStore.get(app)
        server = FakeLinkUp { now }
        runner = LinkUpRunner(store, settings, server)
        server.current = FakeLinkUp.Value(now - 20_000L, 104)
    }

    @After
    fun tearDown() {
        GlucoseStore.closeShared()
    }

    /** Logs in and switches the link on, as the screen does. */
    private fun switchOn() {
        val connect = runner.connect(server.email, server.password, now)
        assertTrue(connect.failure?.detail ?: "", connect.isOk)
        runner.choose(connect.patients[0])
    }

    private fun linkUp() = store.table(ValueSource.LINKUP)

    @Test
    fun loggingInChangesNothingUntilAPersonIsChosen() {
        val connect = runner.connect(server.email, server.password, now)
        assertTrue(connect.isOk)
        assertEquals(listOf("Sample Person"), connect.patients.map { it.name })
        assertFalse(settings.linkUpEnabled)
        assertEquals(ValueSource.SENSOR, store.view)
        assertEquals("eu", settings.linkUpRegion)
        assertEquals(server.email, settings.linkUpEmail)
        assertEquals(server.password, settings.linkUpPassword)

        runner.choose(connect.patients[0])
        assertTrue(settings.linkUpEnabled)
        assertEquals(LinkUpState.STARTING, settings.linkUpState)
        assertEquals("Sample Person", settings.linkUpPatientName)
        assertEquals("from now on the screens show the values of LibreLinkUp", ValueSource.LINKUP, store.view)
    }

    @Test
    fun thePasswordIsNotWrittenDownAsItIs() {
        switchOn()
        val saved = app.getSharedPreferences("settings", 0).all
        for ((key, value) in saved) {
            assertFalse("$key holds the password", value.toString().contains(server.password))
            assertFalse("$key holds the pass", value.toString().contains("pass-1"))
        }
    }

    @Test
    fun aLookSavesTheNewestValueAndTheOlderOnes() {
        server.history.add(FakeLinkUp.Value(now - 31 * minute, 131))
        server.history.add(FakeLinkUp.Value(now - 16 * minute, 119))
        switchOn()
        val look = runner.look(now)
        assertEquals(LinkUpState.RECEIVING, look.state)
        assertTrue(look.newValue)
        assertEquals(now - 20_000L, look.newestTime + (now - 20_000L) % 1000)
        assertEquals(LinkUpState.RECEIVING, settings.linkUpState)
        assertEquals(now, settings.linkUpLastAnswerTime)

        val values = linkUp().since(0L)
        assertEquals(listOf(131, 119, 104), values.map { it.value })
        assertEquals("a time is the start of its minute", now - now % minute, values[2].timestamp)
        assertEquals(listOf(false, false, true), values.map { it.fromTrend })
        assertEquals("the values carry the name of where they come from", "LibreLinkUp 0M0063KNUJ0", values[2].sensorSerial)
        assertEquals("0M0063KNUJ0", settings.linkUpSensorSerial)

        // the screens see them; the values the app has read itself are another set
        assertEquals(104, store.latest()!!.value)
        assertEquals(0, store.table(ValueSource.SENSOR).count())
    }

    @Test
    fun theSameValueIsSavedOnce() {
        switchOn()
        assertTrue(runner.look(now).newValue)
        now += 30_000L
        val again = runner.look(now)
        assertFalse(again.newValue)
        assertEquals(LinkUpState.RECEIVING, again.state)
        assertEquals(1, linkUp().count())

        // a minute later LibreLink has sent the next one
        now += 30_000L
        server.current = FakeLinkUp.Value(now - 20_000L, 101)
        assertTrue(runner.look(now).newValue)
        val values = linkUp().since(0L)
        assertEquals(listOf(104, 101), values.map { it.value })
        assertEquals("the change per minute against the value before", -3.0, values[1].minuteChange!!, 0.001)
    }

    @Test
    fun scannedValuesAndLinkUpValuesNeverMix() {
        val serial = "3MH001HM1LM"
        store.table(ValueSource.SENSOR).insert(listOf(Glucose(now - 2 * minute, 93, null, true, serial)))
        assertEquals(93, store.latest()!!.value)
        switchOn()
        runner.look(now)
        assertEquals("the big number is the one of LibreLinkUp", 104, store.latest()!!.value)
        assertEquals(1, store.count())
        assertEquals(2, store.countAll())

        // a correction of the user belongs to the app's own formula and to one sensor
        val correction = Correction(serial, listOf(ReferencePair(now - 60 * minute, 100, 115)))
        assertTrue(correction.appliesTo(store.table(ValueSource.SENSOR).latest()!!))
        assertFalse("never to a number from Abbott", correction.appliesTo(store.latest()!!))
        assertEquals(104, correction.apply(store.latest()!!).value)

        // switched off: the scanned values are shown again, the fetched ones stay on the phone
        runner.switchOff(forgetLogin = true)
        assertEquals(ValueSource.SENSOR, store.view)
        assertEquals(93, store.latest()!!.value)
        assertEquals(1, linkUp().count())
    }

    @Test
    fun aSensorWithTheSameSerialStillCountsAsAnotherSource() {
        // LibreLinkUp may name the sensor exactly as the scan does
        server.sensorSerial = "3MH001HM1LM"
        switchOn()
        runner.look(now)
        val correction = Correction("3MH001HM1LM", listOf(ReferencePair(now - 60 * minute, 100, 115)))
        assertFalse(correction.appliesTo(store.latest()!!))
        assertEquals("LibreLinkUp 3MH001HM1LM", store.latest()!!.sensorSerial)
        assertEquals("LibreLinkUp", LinkUpHandler.serialFor(null))
    }

    @Test
    fun anOldValueMeansThatLibreLinkHasSentNothingNew() {
        server.current = FakeLinkUp.Value(now - 9 * minute, 104)
        switchOn()
        val look = runner.look(now)
        assertEquals(LinkUpState.NO_NEW_VALUE, look.state)
        assertTrue("it is saved all the same: it is the newest there is", look.newValue)
        now += minute
        assertEquals(LinkUpState.NO_NEW_VALUE, runner.look(now).state)

        server.current = null
        now += minute
        assertEquals("no value at all", LinkUpState.NO_NEW_VALUE, runner.look(now).state)
    }

    @Test
    fun aValueFromTheFutureMeansTheClockOfThePhoneIsWrong() {
        server.current = FakeLinkUp.Value(now + 50 * minute, 104)
        switchOn()
        val look = runner.look(now)
        assertEquals(LinkUpState.CLOCK_WRONG, look.state)
        assertFalse(look.newValue)
        assertEquals(0, linkUp().count())

        // the user sets the clock: the app goes on by itself
        now += 50 * minute + 20_000L
        assertEquals(LinkUpState.RECEIVING, runner.look(now).state)
        assertEquals(1, linkUp().count())
    }

    @Test
    fun withoutInternetTheAppJustAsksAgain() {
        switchOn()
        server.offline = true
        assertEquals(LinkUpState.NO_INTERNET, runner.look(now).state)
        assertEquals(1, settings.linkUpFailures)
        now += minute
        val before = server.requests.size
        assertEquals(LinkUpState.NO_INTERNET, runner.look(now).state)
        assertEquals("no pause: the internet may be back any moment", before + 1, server.requests.size)

        server.offline = false
        now += minute
        server.current = FakeLinkUp.Value(now - 20_000L, 99)
        assertEquals(LinkUpState.RECEIVING, runner.look(now).state)
        assertEquals(0, settings.linkUpFailures)
    }

    @Test
    fun aPassThatHasEndedIsRenewedWithTheSavedPassword() {
        switchOn()
        assertEquals(1, server.logins)
        runner.look(now)

        // the server ends the pass early
        server.endToken()
        server.token = "pass-2"
        now += minute
        server.current = FakeLinkUp.Value(now - 20_000L, 101)
        val look = runner.look(now)
        assertEquals(LinkUpState.RECEIVING, look.state)
        assertTrue(look.newValue)
        assertEquals(2, server.logins)
        assertEquals("pass-2", settings.linkUpToken)

        // the app knows when a pass ends and renews it two minutes before
        server.tokenLife = 60 * minute
        server.endToken()
        server.token = "pass-3"
        now += minute
        runner.look(now)
        assertEquals(3, server.logins)
        val requests = server.requests.size
        now += 57 * minute
        runner.look(now)
        assertEquals("still good for three minutes", 3, server.logins)
        assertEquals(requests + 1, server.requests.size)
        now += 2 * minute
        server.token = "pass-4"
        runner.look(now)
        assertEquals(4, server.logins)
    }

    @Test
    fun aPasswordThatIsWrongNowIsNotTriedAgainAndAgain() {
        switchOn()
        runner.look(now)
        // the user has changed the password in the LibreLinkUp app
        server.password = "new-password"
        server.endToken()
        now += minute
        val look = runner.look(now)
        assertEquals(LinkUpState.WRONG_LOGIN, look.state)
        assertTrue(settings.linkUpState.needsUser)
        assertEquals("", settings.linkUpToken)

        // minute after minute: the server hears nothing more, or the account would be locked
        val asked = server.requests.size
        for (i in 1..30) {
            now += minute
            assertEquals(LinkUpState.WRONG_LOGIN, runner.look(now).state)
        }
        assertEquals(asked, server.requests.size)

        // the user logs in again on the screen
        val connect = runner.connect(server.email, "new-password", now)
        assertTrue(connect.isOk)
        runner.choose(connect.patients[0])
        server.current = FakeLinkUp.Value(now - 20_000L, 97)
        assertEquals(LinkUpState.RECEIVING, runner.look(now).state)
    }

    @Test
    fun aLockedAccountIsLeftAloneForTheTimeTheServerNames() {
        switchOn()
        runner.look(now)
        server.endToken()
        server.lockedSeconds = 300
        now += minute
        assertEquals(LinkUpState.LOCKED, runner.look(now).state)
        assertEquals(now + 300_000L, settings.linkUpWaitUntil)

        val asked = server.requests.size
        now += 4 * minute
        assertEquals(LinkUpState.LOCKED, runner.look(now).state)
        assertEquals(asked, server.requests.size)

        server.lockedSeconds = 0
        now += minute
        server.current = FakeLinkUp.Value(now - 20_000L, 96)
        assertEquals("the pause is over: the app tries by itself", LinkUpState.RECEIVING, runner.look(now).state)
    }

    @Test
    fun aServerWithErrorsIsAskedLessAndLessOften() {
        switchOn()
        runner.look(now)
        server.valuesFail = 500
        var asks = 0
        var last = server.to("/graph").size
        val gaps = ArrayList<Int>()
        var sinceLast = 0
        for (i in 1..60) {
            now += minute
            sinceLast++
            assertEquals(LinkUpState.SERVER_PROBLEM, runner.look(now).state)
            val count = server.to("/graph").size
            if (count != last) {
                asks++
                gaps.add(sinceLast)
                sinceLast = 0
                last = count
            }
        }
        assertEquals("first each minute, then with longer pauses, in the end every 10 minutes", listOf(1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), gaps)
        assertFalse("the screens show a problem, but nothing the user must do", settings.linkUpState.needsUser)

        server.valuesFail = 0
        now += 10 * minute
        server.current = FakeLinkUp.Value(now - 20_000L, 111)
        assertEquals(LinkUpState.RECEIVING, runner.look(now).state)
        assertEquals(0L, settings.linkUpWaitUntil)
    }

    @Test
    fun aPersonWhoStopsSharing() {
        switchOn()
        runner.look(now)
        server.patients.clear()
        now += minute
        assertEquals(LinkUpState.NO_PATIENT, runner.look(now).state)
        val asked = server.requests.size
        now += minute
        assertEquals(LinkUpState.NO_PATIENT, runner.look(now).state)
        assertEquals("only the user can do something about that", asked, server.requests.size)
    }

    @Test
    fun theTermsOfUseInTheMiddleOfTheNight() {
        switchOn()
        runner.look(now)
        // the pass ends, and Abbott wants a yes to new terms before it gives the next one
        server.endToken()
        server.steps.add("tou")
        now += minute
        assertEquals(LinkUpState.NEEDS_STEP, runner.look(now).state)
        assertEquals("tou", settings.linkUpStep)
        assertEquals("step-pass", settings.linkUpStepToken)
        val asked = server.requests.size
        now += minute
        runner.look(now)
        assertEquals("the app never says yes by itself", asked, server.requests.size)

        // in the morning the user says yes on the screen
        val connect = runner.acceptStep(now)
        assertTrue(connect.isOk)
        assertEquals("", settings.linkUpStep)
        runner.choose(connect.patients[0])
        server.current = FakeLinkUp.Value(now - 20_000L, 121)
        assertEquals(LinkUpState.RECEIVING, runner.look(now).state)
    }

    @Test
    fun theFirstLoginWithTermsOfUse() {
        server.steps.add("tou")
        val connect = runner.connect(server.email, server.password, now)
        assertEquals(LinkUpProblem.NEEDS_STEP, connect.failure!!.problem)
        assertEquals("tou", connect.failure!!.step)
        assertFalse(settings.linkUpEnabled)
        assertEquals("what was typed is kept: after the yes nothing must be typed again", server.password, settings.linkUpPassword)

        val after = runner.acceptStep(now)
        assertTrue(after.isOk)
        assertEquals(1, after.patients.size)
        assertEquals("pass-1", settings.linkUpToken)

        assertEquals("no step is waiting any more", LinkUpProblem.BAD_ANSWER, runner.acceptStep(now).failure!!.problem)
    }

    @Test
    fun aPassForTheTermsThatIsTooOldIsFetchedAnew() {
        server.steps.add("tou")
        assertEquals(LinkUpProblem.NEEDS_STEP, runner.connect(server.email, server.password, now).failure!!.problem)
        assertEquals("step-pass", settings.linkUpStepToken)

        // the user comes back hours later: the pass for the step has ended
        server.stepToken = "step-pass-2"
        now += 5 * 60 * minute
        val late = runner.acceptStep(now)
        assertEquals("the app has logged in again, and the server asks again", LinkUpProblem.NEEDS_STEP, late.failure!!.problem)
        assertEquals("step-pass-2", late.failure!!.stepToken)
        assertEquals("step-pass-2", settings.linkUpStepToken)
        assertEquals("nothing was accepted yet", 1, server.steps.size)

        // now the yes gets through
        val done = runner.acceptStep(now)
        assertTrue(done.isOk)
        assertEquals(1, done.patients.size)
        assertEquals("", settings.linkUpStep)
    }

    @Test
    fun aWrongPasswordAtTheFirstLoginSavesNothing() {
        val connect = runner.connect(server.email, "guess", now)
        assertEquals(LinkUpProblem.WRONG_LOGIN, connect.failure!!.problem)
        assertEquals("", settings.linkUpPassword)
        assertEquals("", settings.linkUpEmail)
        assertFalse(settings.linkUpEnabled)
    }

    @Test
    fun theVersionTheServerWantsIsRemembered() {
        server.minimumVersion = "5.0.1"
        switchOn()
        assertEquals("5.0.1", settings.linkUpVersion)
        val before = server.requests.size
        runner.look(now)
        assertEquals("the next question uses it right away", listOf("5.0.1"), server.requests.drop(before).map { it.headers["version"] })
    }

    @Test
    fun switchedOffNothingIsAsked() {
        assertEquals(LinkUpState.OFF, runner.look(now).state)
        assertEquals(0, server.requests.size)

        switchOn()
        runner.look(now)
        runner.switchOff(forgetLogin = true)
        assertFalse(settings.linkUpEnabled)
        assertEquals(LinkUpState.OFF, settings.linkUpState)
        assertEquals("", settings.linkUpPassword)
        assertEquals("", settings.linkUpToken)
        assertEquals("", settings.linkUpPatientId)
        assertEquals("the e-mail stays, so that it need not be typed again", server.email, settings.linkUpEmail)
        val asked = server.requests.size
        assertEquals(LinkUpState.OFF, runner.look(now + minute).state)
        assertEquals(asked, server.requests.size)
    }

    @Test
    fun oldValuesAreRemovedAfterNinetyDays() {
        linkUp().insert(listOf(Glucose(now - 91L * 24 * 60 * minute, 100, null, true, "LibreLinkUp")))
        linkUp().insert(listOf(Glucose(now - 89L * 24 * 60 * minute, 101, null, true, "LibreLinkUp")))
        switchOn()
        // (the tidying happens at most once an hour; this is the first answer after the start)
        now += 61 * minute
        server.current = FakeLinkUp.Value(now - 20_000L, 104)
        runner.look(now)
        assertEquals(listOf(101, 104), linkUp().since(0L).map { it.value })
        assertNotNull(store.latest())
    }
}
