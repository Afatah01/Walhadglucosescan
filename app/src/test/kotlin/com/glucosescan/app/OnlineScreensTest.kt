package com.glucosescan.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.nfc.NfcAdapter
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.glucosescan.app.core.AnswerLanguage
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LinkUpState
import com.glucosescan.app.data.ScanMessage
import com.glucosescan.app.data.ScanOutcome
import com.glucosescan.app.data.Settings
import com.glucosescan.app.data.ValueSource
import com.glucosescan.app.linkup.LinkUpRunner
import com.glucosescan.app.live.AlarmSounder
import com.glucosescan.app.live.LiveBus
import com.glucosescan.app.live.LiveService
import com.glucosescan.app.net.HttpResponse
import com.glucosescan.app.nfc.ScanRunner
import com.glucosescan.app.testing.FakeLinkUp
import com.glucosescan.app.testing.FakeServer
import com.glucosescan.app.testing.FakeSupabase
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.Speaker
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowNfcAdapter
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/**
 * What came with version 4.0, on the screens: values from LibreLinkUp, the AI that is asked
 * over the internet, and the online copy. The internet is a pretended one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1500dp-xhdpi")
class OnlineScreensTest {
    private val minute = 60_000L
    private val serial = "3MH001HM1LM"
    private val linkUpSerial = "LibreLinkUp 0M0063KNUJ0"
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore

    /** Tuesday 10 November 2026, 14:32:20 local time (UTC+3) (11:32:20 UTC) */
    private var now = 1_794_310_340_000L

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Mogadishu"))
        GlucoseStore.closeShared()
        app = RuntimeEnvironment.getApplication()
        settings = Settings(app)
        store = GlucoseStore.get(app)
        AppClock.source = { now }
        ScanRunner.reset()
        Speaker.reset()
        AlarmSounder.plays = 0
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
        settings.noticeAccepted = true
        settings.unit = GlucoseUnit.MGDL
        FakeServer.install(FakeServer().also { it.offline = true })
    }

    @After
    fun tearDown() {
        GlucoseStore.closeShared()
        AppClock.source = { System.currentTimeMillis() }
        FakeServer.remove()
    }

    // ------------------------------------------------------------ helpers

    private fun <T : Activity> open(screen: Class<T>): T {
        val activity = Robolectric.buildActivity(screen).setup().get()
        idle()
        return activity
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun plain(words: CharSequence): String = words.toString().replace('\u202F', ' ').replace('\u00A0', ' ').replace("\u2060", "")

    private fun text(activity: Activity, id: Int): String = plain(activity.findViewById<TextView>(id).text)

    private fun isShown(activity: Activity, id: Int): Boolean {
        var view: View? = activity.findViewById(id)
        while (view != null) {
            if (view.visibility != View.VISIBLE) {
                return false
            }
            view = view.parent as? View
        }
        return true
    }

    private fun click(activity: Activity, id: Int) {
        activity.findViewById<View>(id).performClick()
        idle()
    }

    private fun type(activity: Activity, id: Int, words: String) {
        activity.findViewById<EditText>(id).setText(words)
    }

    private fun allWords(view: View): String {
        val words = StringBuilder()
        if (view is TextView && view.visibility == View.VISIBLE) {
            words.append(plain(view.text)).append('\n')
        }
        if (view is ViewGroup && view.visibility == View.VISIBLE) {
            for (i in 0 until view.childCount) {
                words.append(allWords(view.getChildAt(i)))
            }
        }
        return words.toString()
    }

    private fun words(activity: Activity): String = allWords(activity.window.decorView)

    /** A row of the lists on the More screen: its name, and after a colon its state if it shows one. */
    private fun row(activity: Activity, id: Int): String {
        val row = activity.findViewById<View>(id)
        val title = row.findViewById<TextView>(R.id.navTitle).text.toString()
        val state = row.findViewById<TextView>(R.id.navState)
        return if (state.visibility == View.VISIBLE) title + ": " + state.text else title
    }

    private fun dialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog()

    private fun dialogWords(): String = plain(shadowOf(dialog()).message ?: "")

    private fun yes() {
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }

    private fun no() {
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
    }

    private fun toast(): String = plain(ShadowToast.getTextOfLatestToast() ?: "")

    private fun scrollTo(activity: Activity, id: Int, above: Int) {
        val view = activity.findViewById<View>(id)
        var top = view.top
        var parent = view.parent
        while (parent != null && parent !is ScrollView) {
            top += (parent as View).top
            parent = parent.parent
        }
        (parent as ScrollView).scrollTo(0, Math.max(0, top - above))
        idle()
    }

    private fun picture(activity: Activity, name: String) {
        idle()
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val folder = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
        folder.mkdirs()
        FileOutputStream(File(folder, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The last 16 minutes, one value each minute, falling by 10 mg/dL to [newest]. */
    private fun lastMinutes(newest: Int, sensor: String): List<Glucose> {
        val latest = now - now % minute
        return (15 downTo 0).map { Glucose(latest - it * minute, newest + Math.round(it * 10 / 15f), null, true, sensor) }
    }

    /** Fourteen days of values, one each quarter of an hour: 110, and 200 from noon to 2 PM. */
    private fun twoWeeks(sensor: String): List<Glucose> {
        val list = ArrayList<Glucose>()
        var time = now - now % (15 * minute) - 14L * 24 * 60 * minute
        while (time < now - 16 * minute) {
            val hour = ((time + 3 * 60 * minute) / (60 * minute)) % 24
            list.add(Glucose(time, if (hour == 12L || hour == 13L) 200 else 110, null, false, sensor))
            time += 15 * minute
        }
        return list
    }

    private fun linkUpServer(): FakeLinkUp {
        val server = FakeLinkUp { now }
        server.current = FakeLinkUp.Value(now - 20_000L, 104)
        server.history.add(FakeLinkUp.Value(now - 16 * minute, 119))
        FakeServer.install(server)
        return server
    }

    /** LibreLinkUp is on and has just answered. */
    private fun linkUpRunning(): FakeLinkUp {
        val server = linkUpServer()
        val runner = LinkUpRunner(store, settings, server)
        val connect = runner.connect(server.email, server.password, now)
        runner.choose(connect.patients[0])
        runner.look(now)
        return server
    }

    // ------------------------------------------------------------ More

    @Test
    fun moreLeadsToTheThreeThingsThatUseTheInternet() {
        val more = open(MoreActivity::class.java)
        assertEquals("LibreLinkUp: Off", row(more, R.id.buttonLinkUp))
        assertEquals("Bluetooth values: Off", row(more, R.id.buttonLive))
        assertEquals("there is nothing to set up: the AI is built in", "AI: Ready", row(more, R.id.buttonAi))
        assertEquals("Online copy: Off", row(more, R.id.buttonBackup))
        assertEquals("a row that is neither on nor off shows no state", "Alarms", row(more, R.id.buttonAlarms))
        assertEquals("Values over the internet", more.findViewById<View>(R.id.buttonLinkUp).findViewById<TextView>(R.id.navNote).text.toString())
        assertTrue(words(more).contains("LibreLinkUp, AI and the online copy use the internet. They are off until you set them up."))
        picture(more, "60-more")

        val screens = mapOf(R.id.buttonLinkUp to LinkUpActivity::class.java, R.id.buttonAi to AiInsightsActivity::class.java, R.id.buttonBackup to BackupActivity::class.java)
        for ((id, screen) in screens) {
            click(more, id)
            assertEquals(screen.name, shadowOf(more).nextStartedActivity.component!!.className)
        }

        settings.linkUpEnabled = true
        settings.backupEnabled = true
        val later = open(MoreActivity::class.java)
        assertEquals("LibreLinkUp: On", row(later, R.id.buttonLinkUp))
        assertEquals("AI: Ready", row(later, R.id.buttonAi))
        assertEquals("Online copy: On", row(later, R.id.buttonBackup))
    }

    @Test
    fun theAppSaysWhatItUsesTheInternetFor() {
        val about = open(AboutActivity::class.java)
        assertTrue(words(about).contains("The app uses the internet only for three things, and only after you have set them up"))
        assertFalse(words(about).contains("It does not use the internet"))
    }

    // ------------------------------------------------------------ LibreLinkUp: logging in

    @Test
    fun theLinkUpScreenWhileItIsOff() {
        val server = linkUpServer()
        val screen = open(LinkUpActivity::class.java)
        assertFalse(isShown(screen, R.id.linkupStatusCard))
        assertTrue(isShown(screen, R.id.linkupLoginCard))
        assertFalse(isShown(screen, R.id.linkupLogCard))
        assertTrue(words(screen).contains("Bluetooth stays with LibreLink the whole time."))
        assertTrue(words(screen).contains("This way is not from Abbott."))
        picture(screen, "50-linkup-off")

        click(screen, R.id.linkupConnect)
        assertEquals("Type the e-mail and the password first.", text(screen, R.id.linkupMessage))
        type(screen, R.id.linkupEmail, "not an address")
        type(screen, R.id.linkupPassword, "x")
        click(screen, R.id.linkupConnect)
        assertEquals("Type the e-mail and the password first.", screen.lastMessage)
        assertEquals("nothing was sent", 0, server.requests.size)
    }

    @Test
    fun loggingInSwitchesLinkUpOn() {
        val server = linkUpServer()
        val screen = open(LinkUpActivity::class.java)
        type(screen, R.id.linkupEmail, " " + server.email + " ")
        type(screen, R.id.linkupPassword, server.password)
        shadowOf(app).clearStartedServices()
        click(screen, R.id.linkupConnect)

        assertTrue(settings.linkUpEnabled)
        assertEquals(ValueSource.LINKUP, store.view)
        assertEquals("Sample Person", settings.linkUpPatientName)
        assertEquals("", screen.lastMessage)
        assertEquals("the password does not stay on the screen", "", text(screen, R.id.linkupPassword))
        val started = shadowOf(app).nextStartedService
        assertEquals(LiveService::class.java.name, started.component!!.className)
        assertEquals("the service is told to ask at once", LiveService.ACTION_LOOK, started.action)

        assertTrue(isShown(screen, R.id.linkupStatusCard))
        assertFalse(isShown(screen, R.id.linkupLoginCard))
        assertEquals("Asking LibreLinkUp…", text(screen, R.id.linkupStateText))
        assertEquals("Values of: Sample Person", text(screen, R.id.linkupPersonText))
        assertEquals("Account: follower@example.com", text(screen, R.id.linkupAccountText))
        assertEquals("No answer of LibreLinkUp yet.", text(screen, R.id.linkupLastText))

        // the service asks (here: by hand) and tells the screens
        LinkUpRunner(store, settings, server).look(now)
        LiveBus.changed()
        idle()
        assertEquals("Values come from LibreLinkUp, one each minute.", text(screen, R.id.linkupStateText))
        assertEquals("Last answer of LibreLinkUp: 2:32 PM", text(screen, R.id.linkupLastText))
        assertTrue(text(screen, R.id.linkupLogText).contains("LibreLinkUp logged in, region \"eu\", 1 connection(s)"))
        assertFalse("the technical details hold no secrets", text(screen, R.id.linkupLogText).contains(server.password) || text(screen, R.id.linkupLogText).contains("pass-1"))
        picture(screen, "51-linkup-on")
    }

    @Test
    fun whatTheLoginCanSay() {
        val server = linkUpServer()
        val screen = open(LinkUpActivity::class.java)
        type(screen, R.id.linkupEmail, server.email)
        type(screen, R.id.linkupPassword, "guess")
        click(screen, R.id.linkupConnect)
        assertTrue(text(screen, R.id.linkupMessage).startsWith("LibreLinkUp does not accept this e-mail and password."))
        assertFalse(settings.linkUpEnabled)

        type(screen, R.id.linkupPassword, server.password)
        server.lockedSeconds = 290
        click(screen, R.id.linkupConnect)
        assertEquals("Too many tries. LibreLinkUp asks to wait 5 minutes.", screen.lastMessage)

        server.lockedSeconds = 0
        server.offline = true
        click(screen, R.id.linkupConnect)
        assertEquals("No answer from LibreLinkUp. Is the internet on?", screen.lastMessage)

        server.offline = false
        server.answer = { HttpResponse(500, "oops") }
        click(screen, R.id.linkupConnect)
        assertTrue(screen.lastMessage.startsWith("LibreLinkUp answered with an error."))
        assertFalse(settings.linkUpEnabled)
    }

    @Test
    fun anAccountThatFollowsNobody() {
        val server = linkUpServer()
        server.patients.clear()
        val screen = open(LinkUpActivity::class.java)
        type(screen, R.id.linkupEmail, server.email)
        type(screen, R.id.linkupPassword, server.password)
        click(screen, R.id.linkupConnect)
        assertTrue(screen.lastMessage.startsWith("Nobody shares values with this account yet."))
        assertTrue(screen.lastMessage.contains("invite this e-mail address"))
        assertFalse(settings.linkUpEnabled)
    }

    @Test
    fun anAccountThatFollowsSeveralPeople() {
        val server = linkUpServer()
        server.patients.add(FakeLinkUp.Patient("9b1d0a1e-1111-4000-8000-000000000002", "Second", "Person"))
        val screen = open(LinkUpActivity::class.java)
        type(screen, R.id.linkupEmail, server.email)
        type(screen, R.id.linkupPassword, server.password)
        click(screen, R.id.linkupConnect)
        assertFalse("first the user says whose values", settings.linkUpEnabled)
        assertEquals("Whose values?", plain(shadowOf(dialog()).title))
        assertEquals(listOf("Sample Person", "Second Person"), shadowOf(dialog()).items.map { it.toString() })
        shadowOf(dialog()).clickOnItem(1)
        idle()
        assertTrue(settings.linkUpEnabled)
        assertEquals("Second Person", settings.linkUpPatientName)
        assertEquals("9b1d0a1e-1111-4000-8000-000000000002", settings.linkUpPatientId)
    }

    @Test
    fun theTermsOfUseAreAcceptedByTheUserOnly() {
        val server = linkUpServer()
        server.steps.add("tou")
        server.steps.add("pp")
        val screen = open(LinkUpActivity::class.java)
        type(screen, R.id.linkupEmail, server.email)
        type(screen, R.id.linkupPassword, server.password)
        click(screen, R.id.linkupConnect)
        assertEquals("Abbott asks for your yes", plain(shadowOf(dialog()).title))
        assertTrue(dialogWords().startsWith("LibreLinkUp wants you to accept its terms of use again."))
        assertEquals(0, server.to("/auth/continue").size)

        // "Cancel": nothing is accepted
        no()
        assertFalse(settings.linkUpEnabled)
        assertEquals(0, server.to("/auth/continue").size)

        // "Connect" again asks again, without typing anything anew
        type(screen, R.id.linkupPassword, "")
        val loginsBefore = server.logins
        click(screen, R.id.linkupConnect)
        assertEquals("the app logs in with what was typed before; so the pass for the yes is a fresh one", loginsBefore + 1, server.logins)
        assertTrue(dialogWords().startsWith("LibreLinkUp wants you to accept its terms of use again."))
        yes()
        assertEquals(listOf("https://api-eu.libreview.io/auth/continue/tou"), server.to("/auth/continue").map { it.url })
        assertTrue("then the privacy notice", dialogWords().startsWith("LibreLinkUp wants you to confirm its privacy notice again."))
        assertFalse(settings.linkUpEnabled)
        yes()
        assertTrue(settings.linkUpEnabled)
        assertEquals("Sample Person", settings.linkUpPatientName)
    }

    @Test
    fun bluetoothValuesAreSwitchedOffBeforeLinkUpGoesOn() {
        val server = linkUpServer()
        settings.liveEnabled = true
        val screen = open(LinkUpActivity::class.java)
        type(screen, R.id.linkupEmail, server.email)
        type(screen, R.id.linkupPassword, server.password)
        click(screen, R.id.linkupConnect)
        assertEquals("Bluetooth values are on", plain(shadowOf(dialog()).title))
        assertTrue(dialogWords().contains("scan the sensor once with FreeStyle LibreLink"))
        assertEquals("nothing happens before the user has said yes", 0, server.requests.size)
        assertTrue(settings.liveEnabled)

        yes()
        assertFalse("never both at once", settings.liveEnabled)
        assertTrue(settings.linkUpEnabled)
    }

    @Test
    fun switchingLinkUpOff() {
        val server = linkUpRunning()
        val screen = open(LinkUpActivity::class.java)
        assertTrue(isShown(screen, R.id.linkupStatusCard))
        assertEquals("Values come from LibreLinkUp, one each minute.", text(screen, R.id.linkupStateText))
        click(screen, R.id.linkupSwitchOff)
        assertFalse(settings.linkUpEnabled)
        assertEquals("", settings.linkUpPassword)
        assertEquals("", settings.linkUpToken)
        assertEquals("LibreLinkUp is switched off. The password was removed from this phone.", toast())
        assertFalse(isShown(screen, R.id.linkupStatusCard))
        assertTrue(isShown(screen, R.id.linkupLoginCard))
        assertEquals("the e-mail is still in its field", server.email, settings.linkUpEmail)
        assertEquals("the values that were fetched stay on the phone", 2, store.table(ValueSource.LINKUP).count())
        assertEquals(ValueSource.SENSOR, store.view)
    }

    @Test
    fun whenTheServerWantsTheUserTheLoginComesBack() {
        linkUpRunning()
        settings.linkUpState = LinkUpState.WRONG_LOGIN
        val screen = open(LinkUpActivity::class.java)
        assertTrue(isShown(screen, R.id.linkupStatusCard))
        assertEquals("LibreLinkUp does not accept the password any more. Open More, LibreLinkUp and log in again.", text(screen, R.id.linkupStateText))
        assertEquals(app.getColor(R.color.red), screen.findViewById<TextView>(R.id.linkupStateText).currentTextColor)
        assertTrue("the fields to log in are there again", isShown(screen, R.id.linkupLoginCard))
        assertEquals("follower@example.com", text(screen, R.id.linkupEmail))

        // a pause the server asked for needs nothing from the user
        settings.linkUpState = LinkUpState.LOCKED
        val waiting = open(LinkUpActivity::class.java)
        assertFalse(isShown(waiting, R.id.linkupLoginCard))
        assertEquals("LibreLinkUp asks the app to wait. The app tries again by itself.", text(waiting, R.id.linkupStateText))
    }

    @Test
    fun bluetoothValuesSwitchLinkUpOff() {
        linkUpRunning()
        val screen = open(LiveActivity::class.java)
        click(screen, R.id.liveSwitch)
        assertTrue(dialogWords().startsWith("LibreLinkUp will be switched off: both cannot be on together."))
        assertTrue(dialogWords().contains("FreeStyle LibreLink gets no more values over Bluetooth"))
        no()
        assertTrue(settings.linkUpEnabled)
        assertFalse(settings.liveEnabled)

        click(screen, R.id.liveSwitch)
        yes()
        assertFalse(settings.linkUpEnabled)
        assertTrue(settings.liveEnabled)
        assertEquals("", settings.linkUpPassword)
    }

    // ------------------------------------------------------------ Home with LibreLinkUp

    @Test
    fun homeShowsTheNumbersOfLibreLinkUp() {
        linkUpRunning()
        store.table(ValueSource.LINKUP).insert(lastMinutes(104, linkUpSerial))
        // a scan gave another number a moment ago, by the app's own formula
        store.table(ValueSource.SENSOR).insert(lastMinutes(93, serial))
        settings.lastOutcome = ScanOutcome(ScanMessage.OK, now)
        val home = open(MainActivity::class.java)

        assertEquals("104", text(home, R.id.value))
        assertEquals("In target range", text(home, R.id.rangeChip))
        assertTrue(isShown(home, R.id.livePill))
        assertEquals("LIVE", text(home, R.id.livePillText))
        assertTrue(isShown(home, R.id.liveCard))
        assertEquals("LibreLinkUp", text(home, R.id.liveCardTitle))
        assertEquals("Values come from LibreLinkUp, one each minute.", text(home, R.id.liveState))
        assertFalse("the numbers of Abbott are not corrected", isShown(home, R.id.correctedNote))
        assertFalse(isShown(home, R.id.liveOffer))
        assertEquals(
            "2:32 PM · Scan OK\nThe scan gave 93 mg/dL by the app's own formula. The big number is from LibreLinkUp.",
            text(home, R.id.scanStatus)
        )
        picture(home, "52-home-linkup")

        click(home, R.id.liveCard)
        assertEquals(LinkUpActivity::class.java.name, shadowOf(home).nextStartedActivity.component!!.className)
    }

    @Test
    fun theSignOnHomeFollowsLibreLinkUp() {
        linkUpRunning()
        store.table(ValueSource.LINKUP).insert(lastMinutes(104, linkUpSerial))
        settings.linkUpState = LinkUpState.NO_INTERNET
        var home = open(MainActivity::class.java)
        assertEquals("WAITING", text(home, R.id.livePillText))
        assertEquals("No internet. No values come.", text(home, R.id.liveState))
        assertEquals(app.getColor(R.color.red), home.findViewById<TextView>(R.id.liveState).currentTextColor)

        settings.linkUpState = LinkUpState.NEEDS_STEP
        home = open(MainActivity::class.java)
        assertEquals("PROBLEM", text(home, R.id.livePillText))

        // the last value is some minutes old: not "live" any more, though the link is fine
        settings.linkUpState = LinkUpState.RECEIVING
        now += 4 * minute
        home = open(MainActivity::class.java)
        assertEquals("WAITING", text(home, R.id.livePillText))
    }

    @Test
    fun theSignOnHomeUsesShortWordsSoTheNameOfTheAppKeepsItsLine() {
        linkUpRunning()
        store.table(ValueSource.LINKUP).insert(lastMinutes(104, linkUpSerial))
        val signs = ArrayList<String>()
        for (state in listOf(LinkUpState.RECEIVING, LinkUpState.NO_INTERNET, LinkUpState.NEEDS_STEP)) {
            settings.linkUpState = state
            val home = open(MainActivity::class.java)
            assertEquals("Walhad Glucose Scan", plain(home.findViewById<TextView>(R.id.topTitle).text))
            assertTrue("$state: the sign is shown", isShown(home, R.id.livePill))
            signs.add(text(home, R.id.livePillText))
        }
        // no "Live: waiting": the words are short, so the name next to them stays on one line
        assertEquals(listOf("LIVE", "WAITING", "PROBLEM"), signs)
    }

    @Test
    fun withoutLinkUpHomeIsAsBefore() {
        store.insert(lastMinutes(93, serial))
        val home = open(MainActivity::class.java)
        assertEquals("93", text(home, R.id.value))
        assertFalse(isShown(home, R.id.livePill))
        assertFalse(isShown(home, R.id.liveCard))
    }

    @Test
    fun homeOffersBothWaysToGetValuesByThemselves() {
        val home = open(MainActivity::class.java)
        assertTrue(isShown(home, R.id.liveOffer))
        assertEquals("From LibreLinkUp", text(home, R.id.offerYes))
        assertEquals("Over Bluetooth", text(home, R.id.offerBluetooth))
        assertTrue(words(home).contains("FreeStyle LibreLink keeps the sensor, and the number is the same as in LibreLink."))
        click(home, R.id.offerYes)
        assertEquals(LinkUpActivity::class.java.name, shadowOf(home).nextStartedActivity.component!!.className)
        click(home, R.id.offerBluetooth)
        assertEquals(LiveActivity::class.java.name, shadowOf(home).nextStartedActivity.component!!.className)

        // a phone without Bluetooth can still use LibreLinkUp
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, false)
        val other = open(MainActivity::class.java)
        assertTrue(isShown(other, R.id.liveOffer))
        assertTrue(isShown(other, R.id.offerYes))
        assertFalse(isShown(other, R.id.offerBluetooth))

        click(other, R.id.offerNo)
        assertFalse(isShown(other, R.id.liveOffer))
    }

    @Test
    fun theAlarmsScreenNamesBothWays() {
        val alarms = open(AlarmsActivity::class.java)
        assertTrue(isShown(alarms, R.id.alarmsNeedLive))
        assertTrue(words(alarms).contains("Alarms only work while values come by themselves: from LibreLinkUp, or over Bluetooth."))
        click(alarms, R.id.alarmsOpenLinkUp)
        assertEquals(LinkUpActivity::class.java.name, shadowOf(alarms).nextStartedActivity.component!!.className)

        linkUpRunning()
        assertFalse(isShown(open(AlarmsActivity::class.java), R.id.alarmsNeedLive))
    }

    // ------------------------------------------------------------ AI: nothing to set up

    @Test
    fun thereIsNoScreenWhereAKeyIsTyped() {
        // The AI is built in: no key, no provider, no address. So there is nowhere to type one,
        // and the AI row of More leads to the insights rather than to a form.
        val more = open(MoreActivity::class.java)
        click(more, R.id.buttonAi)
        assertEquals(AiInsightsActivity::class.java.name, shadowOf(more).nextStartedActivity.component!!.className)
        assertEquals("AI: Ready", row(more, R.id.buttonAi))
    }

    @Test
    fun withoutAKeyTheNumbersAreStillRead() {
        store.insert(twoWeeks(serial) + lastMinutes(104, serial))
        val screen = open(AnalysisActivity::class.java)
        assertEquals("Ask the AI now", text(screen, R.id.aiAsk))
        assertTrue("the box for an own question is there even without a key", isShown(screen, R.id.aiQuestion))
        // it read the numbers on its own, and said something
        assertTrue(isShown(screen, R.id.aiAnswerBox))
        assertTrue(text(screen, R.id.aiAnswer).length > 40)
    }

    // ------------------------------------------------------------ AI: asking

    /** What the built-in server answers with. */
    private fun walhadAnswers(server: FakeServer, words: String) {
        server.answer = { HttpResponse(200, JSONObject().put("ok", true).put("answer", words).toString()) }
    }

    /** What the built-in server says when it cannot answer. */
    private fun walhadProblem(server: FakeServer, problem: String, detail: String = "") {
        server.answer = {
            HttpResponse(200, JSONObject().put("ok", false).put("problem", problem).put("detail", detail).toString())
        }
    }

    @Test
    fun theAiIsAskedAndItsAnswerIsShown() {
        val server = FakeServer()
        FakeServer.install(server)
        walhadAnswers(server, "Your glucose was in range most of the time.\n\n- After lunch it goes up to 200 mg/dL.\n- Ask your doctor about the afternoon.\n\nThis is not medical advice.")
        settings.answerLanguage = AnswerLanguage.SOMALI
        store.insert(twoWeeks(serial) + lastMinutes(104, serial))
        val screen = open(AnalysisActivity::class.java)
        assertEquals("Ask the AI now", text(screen, R.id.aiAsk))
        assertTrue(isShown(screen, R.id.aiQuestion))
        type(screen, R.id.aiQuestion, "Why is it high after lunch?")
        click(screen, R.id.aiAsk)

        // what went to the built-in server
        val request = server.last
        assertEquals("https://bhogtezbvoijtixkzrwr.supabase.co/functions/v1/walhad-ai-core", request.url)
        val body = JSONObject(request.body!!)
        assertEquals("so", body.getString("language"))
        assertEquals("Why is it high after lunch?", body.getString("question"))
        val report = body.getString("report")
        assertEquals("the same text that \"Show the text\" shows", text(screen, R.id.aiReport).trimEnd(), report.trimEnd())
        assertTrue(report.contains("TIME IN RANGES"))
        assertTrue(report.contains("made them from the raw data of the sensor"))
        assertFalse("no serial number of the sensor", report.contains(serial))
        assertFalse("no key of any kind is in the app", request.headers.values.any { it == "KEY-123" })

        // the answer
        assertTrue(isShown(screen, R.id.aiAnswerBox))
        assertTrue(text(screen, R.id.aiAnswer).startsWith("Your glucose was in range most of the time."))
        assertEquals("2:32 PM · AI", text(screen, R.id.aiAnswerMeta))
        assertTrue(words(screen).contains("An AI can be wrong. Do not change insulin or medicine because of this. Ask your doctor."))
        assertFalse(isShown(screen, R.id.aiAskState))
        scrollTo(screen, R.id.aiCard, 150)
        picture(screen, "54-analysis-ai-answer")

        // it is still there when the screen is opened the next time
        now += 30 * minute
        val later = open(AnalysisActivity::class.java)
        assertTrue(isShown(later, R.id.aiAnswerBox))
        assertEquals("2:32 PM · AI", text(later, R.id.aiAnswerMeta))
    }

    @Test
    fun whenTheAiCannotAnswerThePhoneDoesAndSaysSo() {
        val server = FakeServer()
        FakeServer.install(server)
        store.insert(twoWeeks(serial) + lastMinutes(104, serial))

        // over the limit: the phone reads the numbers itself and says why
        walhadProblem(server, "limit", "daily limit reached")
        val screen = open(AnalysisActivity::class.java)
        click(screen, R.id.aiAsk)
        assertTrue(isShown(screen, R.id.aiAskState))
        assertEquals(
            "The AI service says: too many questions, or the free amount is used up. Try again later.\n\n(daily limit reached)",
            text(screen, R.id.aiAskState)
        )
        assertTrue("the phone answered rather than leaving the card empty", text(screen, R.id.aiAnswer).length > 40)
        assertEquals("2:32 PM · Phone", text(screen, R.id.aiAnswerMeta))

        // and with no internet at all, the same
        server.offline = true
        click(screen, R.id.aiAsk)
        assertEquals("No answer from the AI service. Is the internet on?", text(screen, R.id.aiAskState))
        assertTrue("the button works again", screen.findViewById<View>(R.id.aiAsk).isEnabled)
    }

    @Test
    fun theAiIsToldWhenTheNumbersComeFromLibreLinkUp() {
        linkUpRunning()
        store.table(ValueSource.LINKUP).insert(twoWeeks(linkUpSerial) + lastMinutes(104, linkUpSerial))
        val screen = open(AnalysisActivity::class.java)
        val report = text(screen, R.id.aiReport)
        assertTrue(report.contains("got them from LibreLinkUp, the follower service of the maker of the sensor"))
        assertFalse(report.contains("made them from the raw data"))
    }

    // ------------------------------------------------------------ the online copy

    private val projectUrl = "https://walhad-test.supabase.co"

    private fun fill(screen: Activity, server: FakeSupabase, email: String = "me@example.com", password: String = "long-password") {
        type(screen, R.id.backupUrl, projectUrl)
        type(screen, R.id.backupKey, server.key)
        type(screen, R.id.backupEmail, email)
        type(screen, R.id.backupPassword, password)
    }

    @Test
    fun theCopyScreenWhileItIsOff() {
        val server = FakeSupabase { now }
        FakeServer.install(server)
        val screen = open(BackupActivity::class.java)
        assertFalse(isShown(screen, R.id.backupStatusCard))
        assertTrue(isShown(screen, R.id.backupStepsCard))
        assertTrue(isShown(screen, R.id.backupLoginCard))
        assertTrue(words(screen).contains("Only your e-mail and your password open your copy."))
        picture(screen, "55-backup-off")

        // the text that makes the table, for the SQL Editor of the project
        click(screen, R.id.backupCopySql)
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(copied.contains("create table if not exists public.glucose_values"))
        assertTrue(copied.contains("enable row level security"))
        assertTrue(copied.contains("revoke all on table public.glucose_values from anon"))
        assertEquals("one rule each for reading, adding, changing and removing", 4, Regex("create policy").findAll(copied).count())
        assertEquals(4, Regex("\\(select auth\\.uid\\(\\)\\) = user_id").findAll(copied).count() - 1)
        assertEquals("The set-up text is copied. Paste it into the SQL Editor of your project.", screen.lastMessage)

        click(screen, R.id.backupLogin)
        assertEquals("Fill in all four fields first.", screen.lastMessage)
        fill(screen, server)
        type(screen, R.id.backupUrl, "my project")
        click(screen, R.id.backupLogin)
        assertEquals("The Project URL must look like https://name.supabase.co", screen.lastMessage)
        assertEquals(0, server.requests.size)
    }

    @Test
    fun makingAnAccountAndLoggingIn() {
        val server = FakeSupabase { now }
        FakeServer.install(server)
        store.insert(lastMinutes(104, serial).takeLast(3))
        val screen = open(BackupActivity::class.java)
        fill(screen, server)
        click(screen, R.id.backupCreate)
        assertTrue(screen.lastMessage.startsWith("The account was made. The database has sent an e-mail to me@example.com."))
        assertFalse(settings.backupEnabled)

        click(screen, R.id.backupLogin)
        assertEquals("The e-mail is not confirmed yet. Open the link in the e-mail from the database, then press \"Log in\".", screen.lastMessage)
        assertEquals(app.getColor(R.color.red), screen.findViewById<TextView>(R.id.backupMessage).currentTextColor)

        server.accounts[0].confirmed = true
        click(screen, R.id.backupLogin)
        assertEquals("You are logged in. The online copy is on.", screen.lastMessage)
        assertTrue(settings.backupEnabled)
        assertEquals("the first copy is made right away", 3, server.rows.size)
        assertEquals("", text(screen, R.id.backupPassword))

        assertTrue(isShown(screen, R.id.backupStatusCard))
        assertFalse(isShown(screen, R.id.backupLoginCard))
        assertFalse(isShown(screen, R.id.backupStepsCard))
        assertEquals("The online copy is on.", text(screen, R.id.backupStateText))
        assertEquals("Account: me@example.com", text(screen, R.id.backupAccountText))
        assertEquals("Last copy: 2:32 PM", text(screen, R.id.backupLastText))
        assertEquals("Every value of this phone is in the copy.", text(screen, R.id.backupWaitingText))
        assertEquals("The copy holds 3 values.", text(screen, R.id.backupOnlineText))
        picture(screen, "56-backup-on")
    }

    @Test
    fun whatTheLoginToTheCopyCanSay() {
        val server = FakeSupabase { now }
        FakeServer.install(server)
        server.account("me@example.com", "long-password")
        val screen = open(BackupActivity::class.java)
        fill(screen, server, password = "guess")
        click(screen, R.id.backupLogin)
        assertEquals("The database does not accept this e-mail and password.", screen.lastMessage)
        click(screen, R.id.backupCreate)
        assertEquals("This e-mail has an account already. Press \"Log in\".", screen.lastMessage)
        fill(screen, server, email = "new@example.com", password = "abc")
        click(screen, R.id.backupCreate)
        assertEquals("The database wants a longer or less simple password.", screen.lastMessage)
        fill(screen, server)
        type(screen, R.id.backupKey, "sb_publishable_wrong")
        click(screen, R.id.backupLogin)
        assertEquals("The database does not accept the key. Copy the \"Publishable key\" of your project again.", screen.lastMessage)
        server.offline = true
        click(screen, R.id.backupLogin)
        assertTrue(screen.lastMessage.startsWith("No answer from the database. Is the internet on?"))

        // the table was not made yet: the login works, the first copy says what is missing
        server.offline = false
        server.tableExists = false
        store.insert(lastMinutes(104, serial).takeLast(2))
        fill(screen, server)
        click(screen, R.id.backupLogin)
        assertTrue(settings.backupEnabled)
        assertTrue(screen.lastMessage.startsWith("The database has no table for the values yet. Do step 2:"))
        assertEquals(app.getColor(R.color.red), screen.findViewById<TextView>(R.id.backupStateText).currentTextColor)
        assertEquals("2 values wait to be copied.", text(screen, R.id.backupWaitingText))
    }

    @Test
    fun copyingGettingBackEmptyingAndLoggingOut() {
        val server = FakeSupabase { now }
        FakeServer.install(server)
        server.account("me@example.com", "long-password")
        store.insert(lastMinutes(104, serial))
        val screen = open(BackupActivity::class.java)
        fill(screen, server)
        click(screen, R.id.backupLogin)
        assertEquals(16, server.rows.size)

        click(screen, R.id.backupNow)
        assertEquals("Nothing new to copy.", screen.lastMessage)
        now += minute
        store.insert(listOf(Glucose(now, 99, null, true, serial)))
        LiveBus.changed()
        idle()
        assertEquals("the screen follows what happens", "1 value waits to be copied.", text(screen, R.id.backupWaitingText))
        click(screen, R.id.backupNow)
        assertEquals("1 value was copied.", screen.lastMessage)
        assertEquals("The copy holds 17 values.", text(screen, R.id.backupOnlineText))

        // the values are lost on the phone and come back
        store.deleteAll()
        click(screen, R.id.backupRestore)
        assertEquals("17 values came back from the copy.", screen.lastMessage)
        assertEquals(17, store.count())
        click(screen, R.id.backupRestore)
        assertEquals("The copy holds no value of the last 90 days that this phone does not have.", screen.lastMessage)

        // emptying asks first
        click(screen, R.id.backupDeleteOnline)
        assertEquals("Empty the online copy?", plain(shadowOf(dialog()).title))
        no()
        assertEquals(17, server.rows.size)
        click(screen, R.id.backupDeleteOnline)
        yes()
        assertEquals("The online copy is empty now.", screen.lastMessage)
        assertEquals(0, server.rows.size)
        assertEquals("the phone keeps its values", 17, store.count())
        assertEquals("The copy holds 0 values.", text(screen, R.id.backupOnlineText))
        assertEquals("17 values wait to be copied.", text(screen, R.id.backupWaitingText))

        click(screen, R.id.backupLogout)
        assertEquals("The online copy is off. What is in the database stays there.", screen.lastMessage)
        assertFalse(settings.backupEnabled)
        assertEquals("", settings.backupPassword)
        assertTrue(isShown(screen, R.id.backupLoginCard))
        assertFalse(isShown(screen, R.id.backupStatusCard))
        assertEquals("the address and the e-mail are still in their fields", projectUrl, text(screen, R.id.backupUrl))
        assertNotNull(server.to("/auth/v1/logout").firstOrNull())
        assertNull(server.to("/auth/v1/logout").firstOrNull()?.headers?.get("Authorization")?.takeIf { it == "Bearer " })
    }
}
