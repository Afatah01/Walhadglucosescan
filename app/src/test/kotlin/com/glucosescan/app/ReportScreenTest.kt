package com.glucosescan.app

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.glucosescan.app.core.DailyReport
import com.glucosescan.app.core.DailyReports
import com.glucosescan.app.core.FindingKind
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings
import com.glucosescan.app.report.PdfKind
import com.glucosescan.app.report.PicturePdf
import com.glucosescan.app.report.ReportRenderer
import com.glucosescan.app.ui.AppClock
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.TimeZone
import java.util.zip.Inflater

/**
 * A pretended Downloads folder: it does what Android does for an app that puts a file there.
 * It remembers what the app asked for.
 */
internal class FakeDownloads : ContentProvider() {
    class Entry(val uri: Uri, val values: ContentValues)

    val inserted = ArrayList<Entry>()
    val updates = ArrayList<Pair<Uri, ContentValues>>()
    val deleted = ArrayList<Uri>()

    /** The name this Android gives the next file, or null: the name the app asks for. */
    var nameGiven: String? = null
    private var nextId = 1

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val result = Uri.withAppendedPath(uri, (nextId++).toString())
        val kept = ContentValues(values)
        nameGiven?.let { kept.put(MediaStore.MediaColumns.DISPLAY_NAME, it) }
        inserted.add(Entry(result, kept))
        return result
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        updates.add(Pair(uri, ContentValues(values)))
        inserted.firstOrNull { it.uri == uri }?.values?.putAll(values)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        deleted.add(uri)
        return 1
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val entry = inserted.firstOrNull { it.uri == uri } ?: return null
        val columns = projection ?: arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { entry.values.get(it) })
        return cursor
    }

    override fun getType(uri: Uri): String = "application/pdf"

    companion object {
        /** Puts the pretended folder where Android has the real one. */
        fun install(): FakeDownloads {
            val downloads = FakeDownloads()
            ShadowContentResolver.registerProviderInternal("media", downloads)
            return downloads
        }

        /** The address of the file with this number in the folder. */
        fun uri(id: Int): Uri = Uri.parse("content://media/external_primary/downloads/$id")
    }
}

/** A made-up person and the helpers that the two report tests share. */
internal object ReportTesting {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
    const val SERIAL = "3MH001HM1LM"

    /** Tuesday 10 November 2026, 14:32:20 local time (UTC+3) (11:32:20 UTC) */
    const val NOW = 1_794_310_340_000L

    /** Tuesday 10 November 2026, 0:00 local time (UTC+3). */
    const val MIDNIGHT = 1_794_258_000_000L

    fun at(day: Int, hourOfDay: Int, minuteOfHour: Int = 0): Long = MIDNIGHT + day * DAY + hourOfDay * HOUR + minuteOfHour * MINUTE

    /**
     * 14 days back from this morning: 110 all the time, 200 from noon to 2 PM, and one hour
     * at 60 last night from 3 to 4 o'clock. One value in each quarter of an hour until 14:15
     * today, then one value each minute, falling from 114 to 104.
     */
    fun person(): List<Glucose> {
        val values = ArrayList<Glucose>()
        var start = at(-14, 0)
        while (start < at(0, 14, 15)) {
            val hourOfDay = (((start - MIDNIGHT) % DAY + DAY) % DAY / HOUR).toInt()
            val low = start >= at(0, 3) && start < at(0, 4)
            values.add(Glucose(start + 7 * MINUTE, if (low) 60 else if (hourOfDay in 12..13) 200 else 110, null, false, SERIAL))
            start += 15 * MINUTE
        }
        val latest = NOW - NOW % MINUTE
        for (k in 15 downTo 0) {
            values.add(Glucose(latest - k * MINUTE, 104 + Math.round(k * 10 / 15f), null, true, SERIAL))
        }
        return values
    }

    /** Waits until the file is written (that happens off the main thread) and the screen has heard of it. */
    fun waitForSaving() {
        val end = System.currentTimeMillis() + 60_000L
        while (ReportActivity.lastMessage == null && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    fun folder(): File {
        val folder = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
        folder.mkdirs()
        return folder
    }

    /** What a program that opens a PDF file does first: it finds the table at the end and looks up every object. */
    class Pdf(val bytes: ByteArray) {
        private val text = String(bytes, Charsets.ISO_8859_1)
        val objects = HashMap<Int, Int>()

        init {
            assertTrue(text.startsWith("%PDF-1.4\n"))
            assertTrue(text.endsWith("%%EOF\n"))
            val startxref = text.lastIndexOf("startxref\n")
            val table = text.substring(startxref + 10, text.indexOf('\n', startxref + 10)).toInt()
            assertEquals("the table is where the end of the file says", "xref\n", text.substring(table, table + 5))
            val lines = text.substring(table, text.indexOf("trailer", table)).split("\n")
            val count = lines[1].removePrefix("0 ").toInt()
            assertEquals("0000000000 65535 f ", lines[2])
            for (number in 1 until count) {
                val line = lines[2 + number]
                assertEquals("each line of the table is 20 bytes with its end", 19, line.length)
                assertTrue(line, line.endsWith(" 00000 n "))
                val offset = line.substring(0, 10).toInt()
                assertTrue("object $number starts where the table says", text.startsWith("$number 0 obj\n", offset))
                objects[number] = offset
            }
            assertTrue(text.substring(table).contains("/Size $count /Root 1 0 R /Info 3 0 R"))
        }

        /** The words of object [number] up to its stream or its end. */
        fun head(number: Int): String {
            val start = objects[number]!!
            val stream = text.indexOf("stream\n", start)
            val end = text.indexOf("endobj", start)
            return text.substring(start, if (stream in 0 until end) stream else end)
        }

        /** The bytes of the stream of object [number], by the length the object states. */
        fun stream(number: Int): ByteArray {
            val head = head(number)
            val length = Regex("/Length (\\d+)").find(head)!!.groupValues[1].toInt()
            val from = objects[number]!! + head.length + "stream\n".length
            val after = String(bytes, from + length, 12, Charsets.ISO_8859_1)
            assertTrue("after the stream comes its end: $after", after.trimStart('\n').startsWith("endstream"))
            return bytes.copyOfRange(from, from + length)
        }

        fun number(name: String, objectNumber: Int): Int = Regex("/$name (\\d+)").find(head(objectNumber))!!.groupValues[1].toInt()

        /** The picture of object [number], unpacked: red, green, blue of each pixel. */
        fun pixels(number: Int): ByteArray {
            val result = ByteArray(number("Width", number) * number("Height", number) * 3)
            val inflater = Inflater()
            inflater.setInput(stream(number))
            var filled = 0
            while (filled < result.size && !inflater.finished()) {
                val got = inflater.inflate(result, filled, result.size - filled)
                if (got == 0 && inflater.needsInput()) {
                    break
                }
                filled += got
            }
            assertEquals("the picture has all its pixels", result.size, filled)
            assertTrue("and nothing more", inflater.finished() || inflater.inflate(ByteArray(1)) == 0)
            inflater.end()
            return result
        }
    }
}

/**
 * The daily report: the screen, the two pages, the PDF file, and the way into the Downloads
 * folder of the phone (Android 10 and newer).
 *
 * The PDF maker of Android does not exist on a computer. So these tests cannot run it: here
 * the app always falls back to its own writer, which makes each page a picture. That writer
 * is tested here down to the last pixel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1500dp-xhdpi")
class ReportScreenTest {
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private lateinit var downloads: FakeDownloads
    private var now = ReportTesting.NOW

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Mogadishu"))
        GlucoseStore.closeShared()
        app = RuntimeEnvironment.getApplication()
        settings = Settings(app)
        store = GlucoseStore.get(app)
        AppClock.source = { now }
        settings.noticeAccepted = true
        settings.unit = GlucoseUnit.MGDL
        ReportActivity.lastMessage = null
        ReportActivity.lastKind = null
        store.insert(ReportTesting.person())
        downloads = FakeDownloads.install()
    }

    @After
    fun tearDown() {
        GlucoseStore.closeShared()
        AppClock.source = { System.currentTimeMillis() }
    }

    // ------------------------------------------------------------ helpers

    private fun open(): ReportActivity {
        val activity = Robolectric.buildActivity(ReportActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun plain(words: CharSequence): String = words.toString().replace('\u202F', ' ').replace('\u00A0', ' ').replace("\u2060", "")

    private fun text(activity: Activity, id: Int): String = plain(activity.findViewById<TextView>(id).text)

    private fun isShown(activity: Activity, id: Int): Boolean = activity.findViewById<View>(id).visibility == View.VISIBLE

    private fun click(activity: Activity, id: Int) {
        activity.findViewById<View>(id).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun save(bitmap: Bitmap, name: String) {
        FileOutputStream(File(ReportTesting.folder(), "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun picture(activity: Activity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        save(bitmap, name)
    }

    private fun report(day: Int = 0, unit: GlucoseUnit = GlucoseUnit.MGDL): Pair<DailyReport, ReportRenderer> {
        val start = ReportTesting.at(day, 0)
        val values = store.between(start - 7 * ReportTesting.DAY, start + ReportTesting.DAY)
        val built = DailyReports.build(values, start, now, settings.lowLimit, settings.highLimit)
        return Pair(built, ReportRenderer(app, built, unit, now, "4.0.0"))
    }

    /** All the words of every page. */
    private fun words(renderer: ReportRenderer): List<String> {
        for (page in 0 until ReportRenderer.PAGES) {
            ReportRenderer.picture(renderer, page, 600)
        }
        return renderer.words.map { plain(it) }
    }

    /** What gets written into the file with this number in the pretended Downloads folder. */
    private fun fileInDownloads(id: Int = 1, into: OutputStream = ByteArrayOutputStream()): Pair<Uri, OutputStream> {
        val uri = FakeDownloads.uri(id)
        shadowOf(app.contentResolver).registerOutputStream(uri, into)
        return Pair(uri, into)
    }

    // ------------------------------------------------------------ the screen

    @Test
    fun theReportOfToday() {
        val screen = open()
        assertTrue(screen.findViewById<View>(R.id.tabReport).isSelected)
        assertEquals("Daily report", text(screen, R.id.topTitle))
        assertEquals("Today · Tuesday, November 10, 2026", text(screen, R.id.dayLabel))
        assertEquals("Values for 100 % of the day\nThe day is not over. Values until 2:32 PM.", text(screen, R.id.daySummary))
        assertTrue(screen.findViewById<View>(R.id.dayBefore).isEnabled)
        assertFalse("there is no report of tomorrow", screen.findViewById<View>(R.id.dayAfter).isEnabled)
        assertTrue(screen.report!!.running)
        assertEquals(ReportTesting.MIDNIGHT, screen.report!!.dayStart)

        // both pages are shown as pictures, in the shape of A4 paper
        for (id in listOf(R.id.page1, R.id.page2)) {
            assertTrue(isShown(screen, id))
            val bitmap = (screen.findViewById<ImageView>(id).drawable as BitmapDrawable).bitmap
            assertEquals(842f / 595f, bitmap.height.toFloat() / bitmap.width.toFloat(), 0.01f)
            assertTrue("wide enough to read", bitmap.width >= 640)
        }
        assertEquals("Page 1 of the report: the day, time in ranges and how the day was", screen.findViewById<View>(R.id.page1).contentDescription.toString())

        assertEquals("Download the report (PDF)", text(screen, R.id.savePdf))
        assertTrue(screen.findViewById<View>(R.id.savePdf).isEnabled)
        assertFalse("nothing is saved yet", isShown(screen, R.id.savedButtons))
        assertFalse(isShown(screen, R.id.reportMessage))
        assertFalse(isShown(screen, R.id.reportEmpty))
        picture(screen, "46-report")
    }

    @Test
    fun theArrowsGoThroughTheDays() {
        val screen = open()
        click(screen, R.id.dayBefore)
        assertEquals("Yesterday · Monday, November 9, 2026", text(screen, R.id.dayLabel))
        assertEquals("Values for 100 % of the day", text(screen, R.id.daySummary))
        assertFalse(screen.report!!.running)
        assertTrue(screen.findViewById<View>(R.id.dayAfter).isEnabled)

        click(screen, R.id.dayBefore)
        assertEquals("Sunday, November 8, 2026", text(screen, R.id.dayLabel))
        click(screen, R.id.dayAfter)
        click(screen, R.id.dayAfter)
        assertEquals("Today · Tuesday, November 10, 2026", text(screen, R.id.dayLabel))
        click(screen, R.id.dayAfter)
        assertEquals("it does not go into tomorrow", "Today · Tuesday, November 10, 2026", text(screen, R.id.dayLabel))
    }

    @Test
    fun aDayWithoutValuesHasNoReport() {
        val screen = open()
        repeat(15) { click(screen, R.id.dayBefore) }
        assertEquals("Monday, October 26, 2026", text(screen, R.id.dayLabel))
        assertTrue(isShown(screen, R.id.reportEmpty))
        assertFalse(isShown(screen, R.id.page1))
        assertFalse(isShown(screen, R.id.page2))
        assertFalse(isShown(screen, R.id.daySummary))
        assertFalse("nothing to download", screen.findViewById<View>(R.id.savePdf).isEnabled)
        click(screen, R.id.savePdf)
        assertNull(ReportActivity.lastMessage)
        assertTrue(downloads.inserted.isEmpty())
        picture(screen, "48-report-empty-day")

        // 90 days back is the end: older values are not kept
        repeat(80) { click(screen, R.id.dayBefore) }
        assertEquals("Wednesday, August 12, 2026", text(screen, R.id.dayLabel))
        assertFalse(screen.findViewById<View>(R.id.dayBefore).isEnabled)
    }

    @Test
    fun theDayStaysWhenTheScreenIsTurned() {
        val controller = Robolectric.buildActivity(ReportActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        click(controller.get(), R.id.dayBefore)
        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Yesterday · Monday, November 9, 2026", text(controller.get(), R.id.dayLabel))
    }

    // ------------------------------------------------------------ what the pages say

    @Test
    fun whatThePagesSay() {
        val (built, renderer) = report()
        val words = words(renderer)
        val all = words.joinToString("\n")

        // the head
        assertEquals(listOf("Daily glucose report", "Tuesday, November 10, 2026", "Walhad Glucose Scan"), words.subList(0, 3))
        assertTrue(all, words.contains("The day is not over: values until 2:32 PM"))
        assertTrue(all, words.any { it.startsWith("Made Tue, Nov 10") })

        // one sentence about the day, and four numbers
        assertTrue(all, words.contains("There were low values (under 70 mg/dL)."))
        for (expected in listOf("Average", "119", "Lowest", "60 mg/dL", "at 3:07 AM", "Highest", "200 mg/dL", "at 12:07 PM", "In range", "80 %", "70 to 180 mg/dL")) {
            assertTrue(expected + " in\n" + all, words.contains(expected))
        }

        // time in ranges: 59 quarter hours, 4 of them low (6.8 %), 47 in range (79.7 %), 8 high (13.6 %).
        // Rounded each by itself they would give 7 + 80 + 14 = 101: the bar takes 13 for the last.
        assertEquals(listOf(0, 7, 80, 13, 0), built.span.stats.rangePercents.toList())
        for (expected in listOf("Very low: under 54", "Low: 54 to under 70", "In range: 70 to 180", "High: over 180 to 250", "Very high: over 250", "7 %", "13 %")) {
            assertTrue(expected + " in\n" + all, words.contains(expected))
        }

        // how the day was
        for (
        expected in listOf(
            "Average 119 mg/dL. Lowest 60 mg/dL at 3:07 AM. Highest 200 mg/dL at 12:07 PM.",
            "In range (70 to 180 mg/dL) 80 % of the time. Under 70 mg/dL: 6.8 %. Over 180 mg/dL: 13 %.",
            "It started at 110 mg/dL (12:07 AM) and is at 104 mg/dL now (2:32 PM): about the same.",
            "Low events (under 70 mg/dL): 1. 3:00 AM for 1h 0min, lowest 60 mg/dL.",
            "High events (over 180 mg/dL): 1. 12:00 PM for 2h 0min, highest 200 mg/dL."
        )
        ) {
            assertTrue(expected + " in\n" + all, words.contains(expected))
        }

        // page 2: hour by hour, and the week
        for (expected in listOf("Hour by hour", "Hour", "12 AM", "3 AM", "11 PM", "This day and the six days before", "Tue, Nov 10", "Wed, Nov 4", "How the numbers are counted")) {
            assertTrue(expected + " in\n" + all, words.contains(expected))
        }
        assertFalse("the week has seven days", words.contains("Tue, Nov 3"))
        assertTrue(words.contains("In range means 70 to 180 mg/dL. Your own target range in the app is 80 to 180 mg/dL."))
        assertTrue(words.contains("The sentences on page 1 are made by fixed rules from these numbers. They describe what happened. They are not advice."))

        // each page ends with who made it and what it is not
        assertEquals(ReportRenderer.PAGES, words.count { it.startsWith("Walhad Glucose Scan 4.0.0. This app is not a medical device") })
        for (page in 1..ReportRenderer.PAGES) {
            assertTrue(words.contains("Page $page of ${ReportRenderer.PAGES}"))
        }
        assertEquals("the name of the maker stands at the bottom of every page", ReportRenderer.PAGES, words.count { it == ReportRenderer.CREDIT })
        assertEquals("Powered by: Abdifatah Elmi", ReportRenderer.CREDIT)

        // nothing about the person is on the paper
        assertFalse(all.contains(ReportTesting.SERIAL))
    }

    @Test
    fun theHourTableOfTheReport() {
        val (built, _) = report()
        assertEquals("the hours that have begun", 15, built.hours.size)
        val three = built.hours.first { it.hourOfDay == 3 }
        assertEquals(60.0, three.mean!!, 1e-9)
        val noon = built.hours.first { it.hourOfDay == 12 }
        assertEquals(200.0, noon.mean!!, 1e-9)
        assertEquals("the hour that runs: 110 at 14:07, then one value each minute", 104, built.hours.first { it.hourOfDay == 14 }.lowest)
        assertNull("3 PM has not come yet", built.hours.firstOrNull { it.hourOfDay == 15 })
        assertEquals(7, built.week.size)
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0), built.week.map { it.lows })
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1), built.week.map { it.highs })
    }

    @Test
    fun theReportInTheOtherUnit() {
        val words = words(report(unit = GlucoseUnit.MMOLL).second)
        val all = words.joinToString("\n")
        for (expected in listOf("There were low values (under 3.9 mmol/L).", "6.6", "3.3 mmol/L", "11.1 mmol/L", "3.9 to 10.0 mmol/L", "Low: 3.0 to under 3.9")) {
            assertTrue(expected + " in\n" + all, words.contains(expected))
        }
        assertFalse("mg/dL should not appear in a mmol/L report:\n" + all, all.contains("mg/dL"))
    }

    @Test
    fun aDayThatIsOverSaysEnded() {
        val (built, renderer) = report(day = -1)
        assertFalse(built.running)
        val words = words(renderer)
        assertEquals("Monday, November 9, 2026", words[1])
        assertFalse(words.any { it.startsWith("The day is not over") })
        // 92 % in range, 8 % high, nothing low
        assertTrue(words.joinToString("\n"), words.contains("Almost always in range, and no lows."))
        assertTrue(words.contains("In range (70 to 180 mg/dL) 92 % of the time. Under 70 mg/dL: 0 %. Over 180 mg/dL: 8.3 %."))
        assertTrue(words.joinToString("\n"), words.contains("It started at 110 mg/dL (12:07 AM) and ended at 110 mg/dL (11:52 PM): about the same."))
        assertTrue(words.contains("Low events (under 70 mg/dL): none."))
    }

    @Test
    fun aRunningDayIsNotComparedWithTheEveningBefore() {
        val (built, renderer) = report()
        assertTrue(built.running)
        assertTrue("the plain analysis does compare with the same length of time before", built.analysis.findings.any { it.kind == FindingKind.WORSE })
        assertFalse(built.findings.any { it.kind == FindingKind.WORSE || it.kind == FindingKind.BETTER })
        assertFalse(words(renderer).any { it.contains("time in range than") })
    }

    @Test
    fun aDayThatIsOverIsComparedWithTheDayBefore() {
        // yesterday evening was high: each quarter hour from 6 PM to midnight gets a second value,
        // so that its mean is 220
        val extra = ArrayList<Glucose>()
        var start = ReportTesting.at(-1, 18)
        while (start < ReportTesting.at(0, 0)) {
            extra.add(Glucose(start + 8 * ReportTesting.MINUTE, 330, null, false, ReportTesting.SERIAL))
            start += 15 * ReportTesting.MINUTE
        }
        store.insert(extra)
        val (built, renderer) = report(day = -1)
        assertFalse(built.running)
        val words = words(renderer)
        // 64 of 96 quarter hours in range; the day before 88 of 96
        assertTrue(words.joinToString("\n"), words.contains("Less time in range than on the day before: 92 % then, 67 % now."))
    }

    @Test
    fun aReportOfLibreLinkUpValuesSaysWhereTheyComeFrom() {
        val built = report().first
        val words = words(ReportRenderer(app, built, GlucoseUnit.MGDL, now, "4.0.0", fromLinkUp = true))
        val all = words.joinToString("\n")
        assertTrue(
            all,
            words.contains(
                "The values come from LibreLinkUp, the follower service of the FreeStyle Libre sensor. The app Walhad Glucose Scan fetched " +
                    "them over the internet. They are the numbers of Abbott's own program; the app has not changed them."
            )
        )
        assertEquals(ReportRenderer.PAGES, words.count { it.startsWith("Walhad Glucose Scan 4.0.0. This app is not a medical device. The values come from LibreLinkUp. Show the report") })
        assertFalse(all.contains("can differ from FreeStyle LibreLink"))
        assertFalse(all.contains("with your correction"))

        // without LibreLinkUp the report says what it said before
        val own = words(report().second).joinToString("\n")
        assertTrue(own.contains("The values come from a FreeStyle Libre 2 sensor, read by the app Walhad Glucose Scan."))
        assertTrue(own.contains("and the values can differ from FreeStyle LibreLink."))
    }

    @Test
    fun theTwoPagesAsPictures() {
        val (_, renderer) = report()
        val first = ReportRenderer.picture(renderer, 0, 1240)
        val second = ReportRenderer.picture(renderer, 1, 1240)
        assertEquals(1240, first.width)
        assertEquals(1755, first.height)
        assertEquals("the head is blue", 0xFF0B5ED7.toInt(), first.getPixel(1230, 20))
        assertEquals("the paper is white, also in dark mode", Color.WHITE, first.getPixel(4, 900))
        assertEquals(Color.WHITE, second.getPixel(4, 900))
        save(first, "report-page-1")
        save(second, "report-page-2")
    }

    @Test
    @Config(qualifiers = "w360dp-h1500dp-night-xhdpi")
    fun inDarkModeThePaperStaysWhite() {
        val screen = open()
        val bitmap = (screen.findViewById<ImageView>(R.id.page1).drawable as BitmapDrawable).bitmap
        assertEquals(Color.WHITE, bitmap.getPixel(4, bitmap.height / 2))
        picture(screen, "47-report-dark")
    }

    // ------------------------------------------------------------ the PDF file

    @Test
    fun theFileIsARealPdfWithEveryPage() {
        val (_, renderer) = report()
        val out = ByteArrayOutputStream()
        val kind = ReportRenderer.writePdf(renderer, out)
        assertEquals("a computer has no PDF maker of Android: the app wrote the file itself", PdfKind.PICTURES, kind)
        val pdf = ReportTesting.Pdf(out.toByteArray())
        File(ReportTesting.folder(), "sample-report.pdf").writeBytes(pdf.bytes)

        assertEquals(3 + 3 * ReportRenderer.PAGES, pdf.objects.size)
        assertTrue(pdf.head(1).contains("/Type /Catalog /Pages 2 0 R"))
        assertTrue(pdf.head(2).contains("/Count " + ReportRenderer.PAGES))
        assertTrue(pdf.head(3), pdf.head(3).contains("/Title (Daily glucose report 2026-11-10) /Producer (Walhad Glucose Scan 4.0.0) /CreationDate (D:20261110143220+03'00')"))
        for (page in 0 until ReportRenderer.PAGES) {
            val number = 4 + 3 * page
            assertTrue(pdf.head(number), pdf.head(number).contains("/Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Im$page ${number + 2} 0 R >> >> /Contents ${number + 1} 0 R"))
            assertEquals("q\n595 0 0 842 0 0 cm\n/Im$page Do\nQ\n", String(pdf.stream(number + 1), Charsets.US_ASCII))
            assertTrue(pdf.head(number + 2).contains("/Subtype /Image /Width 1488 /Height 2106 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode"))

            // the picture in the file is the page, pixel for pixel
            val expected = ReportRenderer.picture(ReportRenderer(app, report().first, GlucoseUnit.MGDL, now, "4.0.0"), page, ReportRenderer.PICTURE_PDF_WIDTH)
            val pixels = pdf.pixels(number + 2)
            val row = IntArray(expected.width)
            var different = 0
            for (y in 0 until expected.height) {
                expected.getPixels(row, 0, expected.width, 0, y, expected.width, 1)
                for (x in 0 until expected.width) {
                    val at = (y * expected.width + x) * 3
                    val inFile = (0xFF shl 24) or ((pixels[at].toInt() and 0xFF) shl 16) or ((pixels[at + 1].toInt() and 0xFF) shl 8) or (pixels[at + 2].toInt() and 0xFF)
                    if (inFile != row[x]) {
                        different++
                    }
                }
            }
            assertEquals("page ${page + 1}", 0, different)
        }
        assertTrue("small enough to send: ${pdf.bytes.size} bytes", pdf.bytes.size < 1_500_000)
    }

    @Test
    fun thePictureWriterPutsSeeThroughPixelsOnWhitePaper() {
        val out = ByteArrayOutputStream()
        PicturePdf.write(out, 200, 100, 1, "A (test) \\ title é", "maker", null) {
            val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
            bitmap.setPixel(0, 0, 0xFF102030.toInt())
            bitmap.setPixel(1, 0, 0x00000000)
            bitmap
        }
        val pdf = ReportTesting.Pdf(out.toByteArray())
        assertEquals(6, pdf.objects.size)
        assertTrue(pdf.head(3), pdf.head(3).contains("/Title (A \\(test\\) \\\\ title ?) /Producer (maker) >>"))
        assertTrue(pdf.head(4).contains("/MediaBox [0 0 200 100]"))
        assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30, -1, -1, -1), pdf.pixels(6))
    }

    @Test
    fun thePdfDate() {
        assertEquals("D:20261110143220+03'00'", ReportRenderer.pdfDate(now))
        TimeZone.setDefault(TimeZone.getTimeZone("America/St_Johns"))
        assertEquals("D:20261110080220-03'30'", ReportRenderer.pdfDate(now))
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("D:20261110113220+00'00'", ReportRenderer.pdfDate(now))
    }

    @Test
    fun onlyAWholeFileCountsAsAPdf() {
        assertFalse(ReportRenderer.looksLikePdf(ByteArray(0)))
        assertFalse(ReportRenderer.looksLikePdf(ByteArray(5000)))
        assertFalse(ReportRenderer.looksLikePdf(("%PDF-1.4\n" + "x".repeat(400)).toByteArray()))
        assertTrue(ReportRenderer.looksLikePdf(("%PDF-1.4\n" + "x".repeat(400) + "\n%%EOF\n").toByteArray()))
    }

    // ------------------------------------------------------------ downloading

    @Test
    fun theReportIsDownloadedIntoTheDownloadsFolder() {
        val screen = open()
        val (uri, file) = fileInDownloads()
        click(screen, R.id.savePdf)
        ReportTesting.waitForSaving()

        assertEquals("Saved in the Downloads folder of the phone:\nwalhad-glucose-report-2026-11-10.pdf", ReportActivity.lastMessage)
        assertTrue(isShown(screen, R.id.reportMessage))
        assertEquals(ReportActivity.lastMessage, text(screen, R.id.reportMessage))
        assertEquals(app.getColor(R.color.green), screen.findViewById<TextView>(R.id.reportMessage).currentTextColor)

        // the app asked Android for a new file in Downloads, hidden while it is written
        assertEquals(1, downloads.inserted.size)
        assertEquals(uri, downloads.inserted[0].uri)
        val asked = downloads.inserted[0].values
        assertEquals("walhad-glucose-report-2026-11-10.pdf", asked.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("application/pdf", asked.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Download", asked.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))

        // the file was written, and then shown to the user and to other apps
        val written = (file as ByteArrayOutputStream).toByteArray()
        val pdf = ReportTesting.Pdf(written)
        assertEquals(3 + 3 * ReportRenderer.PAGES, pdf.objects.size)
        assertEquals(PdfKind.PICTURES, ReportActivity.lastKind)
        assertEquals(1, downloads.updates.size)
        assertEquals(uri, downloads.updates[0].first)
        assertEquals(0, downloads.updates[0].second.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("in the end the file is not hidden", 0, asked.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertTrue(downloads.deleted.isEmpty())

        // now the file can be opened and sent on
        assertEquals(uri, screen.savedUri)
        assertTrue(isShown(screen, R.id.savedButtons))
        assertTrue(screen.findViewById<View>(R.id.savePdf).isEnabled)
        picture(screen, "49-report-downloaded")

        click(screen, R.id.openPdf)
        val view = shadowOf(screen).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(uri, view.data)
        assertEquals("application/pdf", view.type)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        click(screen, R.id.sharePdf)
        val chooser = shadowOf(screen).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        @Suppress("DEPRECATION")
        assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("Daily glucose report", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun theReportOfAnotherDayGetsItsOwnFile() {
        val screen = open()
        fileInDownloads(1)
        click(screen, R.id.savePdf)
        ReportTesting.waitForSaving()
        assertTrue(isShown(screen, R.id.savedButtons))

        // another day: the buttons of the old file go away
        click(screen, R.id.dayBefore)
        assertNull(screen.savedUri)
        assertFalse(isShown(screen, R.id.savedButtons))
        assertFalse(isShown(screen, R.id.reportMessage))

        ReportActivity.lastMessage = null
        val (uri, _) = fileInDownloads(2)
        click(screen, R.id.savePdf)
        ReportTesting.waitForSaving()
        assertEquals("Saved in the Downloads folder of the phone:\nwalhad-glucose-report-2026-11-09.pdf", ReportActivity.lastMessage)
        assertEquals(uri, screen.savedUri)
        assertEquals(2, downloads.inserted.size)
    }

    @Test
    fun theFileIsHiddenWhileItIsWritten() {
        val screen = open()
        var pendingWhileWriting: Int? = null
        val watching = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (pendingWhileWriting == null) {
                    pendingWhileWriting = downloads.inserted[0].values.getAsInteger(MediaStore.MediaColumns.IS_PENDING)
                }
                super.write(b, off, len)
            }
        }
        fileInDownloads(1, watching)
        click(screen, R.id.savePdf)
        ReportTesting.waitForSaving()
        assertEquals("half a file is never shown in Downloads", 1, pendingWhileWriting)
        assertEquals(0, downloads.inserted[0].values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
    }

    @Test
    fun aSecondFileOfTheSameDayGetsTheNameAndroidGivesIt() {
        val screen = open()
        fileInDownloads(1)
        downloads.nameGiven = "walhad-glucose-report-2026-11-10 (1).pdf"
        click(screen, R.id.savePdf)
        ReportTesting.waitForSaving()
        assertEquals("Saved in the Downloads folder of the phone:\nwalhad-glucose-report-2026-11-10 (1).pdf", ReportActivity.lastMessage)
    }

    @Test
    fun whenDownloadsFailsTheUserIsAskedWhereToPutTheFile() {
        val screen = open()
        val broken = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("no space left")
            }
        }
        val (uri, _) = fileInDownloads(1, broken)
        click(screen, R.id.savePdf)
        ReportTesting.waitForSaving()

        assertEquals("The report could not be saved.", ReportActivity.lastMessage)
        assertEquals(app.getColor(R.color.red), screen.findViewById<TextView>(R.id.reportMessage).currentTextColor)
        assertNull(screen.savedUri)
        assertFalse(isShown(screen, R.id.savedButtons))
        assertEquals("nothing half-written stays in Downloads", listOf(uri), downloads.deleted)
        assertTrue(downloads.updates.isEmpty())

        // the other way: Android asks the user for a place
        val asked = shadowOf(screen).nextStartedActivityForResult
        assertNotNull(asked)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, asked.intent.action)
        assertEquals("application/pdf", asked.intent.type)
        assertEquals("walhad-glucose-report-2026-11-10.pdf", asked.intent.getStringExtra(Intent.EXTRA_TITLE))

        // the user chooses a place: now it works
        ReportActivity.lastMessage = null
        val chosen = Uri.parse("content://com.android.providers.downloads.documents/document/77")
        val file = ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(chosen, file)
        shadowOf(screen).receiveResult(asked.intent, Activity.RESULT_OK, Intent().setData(chosen))
        ReportTesting.waitForSaving()
        assertEquals("The report was saved.", ReportActivity.lastMessage)
        assertEquals(chosen, screen.savedUri)
        assertTrue(isShown(screen, R.id.savedButtons))
        ReportTesting.Pdf(file.toByteArray())
        assertEquals("the place the user chose is not a new file in Downloads", 1, downloads.inserted.size)
    }

    @Test
    fun aSecondTapWhileTheFileIsWrittenDoesNotWriteASecondFile() {
        val screen = open()
        fileInDownloads(1)
        screen.findViewById<View>(R.id.savePdf).performClick()
        screen.findViewById<View>(R.id.savePdf).performClick()
        ReportTesting.waitForSaving()
        assertEquals(1, downloads.inserted.size)
    }
}

/**
 * Android 9 and older do not let an app write into Downloads without a permission. There
 * the app asks the user where the file shall go. Android 6 is the oldest the app accepts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], qualifiers = "w360dp-h1500dp-xhdpi")
class ReportOnOldAndroidTest {
    private lateinit var app: Application
    private lateinit var downloads: FakeDownloads

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Mogadishu"))
        GlucoseStore.closeShared()
        app = RuntimeEnvironment.getApplication()
        AppClock.source = { ReportTesting.NOW }
        val settings = Settings(app)
        settings.noticeAccepted = true
        settings.unit = GlucoseUnit.MGDL
        ReportActivity.lastMessage = null
        GlucoseStore.get(app).insert(ReportTesting.person())
        downloads = FakeDownloads.install()
    }

    @After
    fun tearDown() {
        GlucoseStore.closeShared()
        AppClock.source = { System.currentTimeMillis() }
    }

    @Test
    fun theUserChoosesWhereTheFileGoes() {
        val screen = Robolectric.buildActivity(ReportActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Today · Tuesday, November 10, 2026", screen.findViewById<TextView>(R.id.dayLabel).text.toString())
        screen.findViewById<View>(R.id.savePdf).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("no file in Downloads without asking", downloads.inserted.isEmpty())
        val asked = shadowOf(screen).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, asked.intent.action)
        assertTrue(asked.intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("application/pdf", asked.intent.type)
        assertEquals("walhad-glucose-report-2026-11-10.pdf", asked.intent.getStringExtra(Intent.EXTRA_TITLE))

        // the user closes the question: nothing happens
        shadowOf(screen).receiveResult(asked.intent, Activity.RESULT_CANCELED, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ReportActivity.lastMessage)
        assertNull(screen.savedUri)

        // the user chooses a place
        screen.findViewById<View>(R.id.savePdf).performClick()
        val again = shadowOf(screen).nextStartedActivityForResult
        val chosen = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Freport.pdf")
        val file = ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(chosen, file)
        shadowOf(screen).receiveResult(again.intent, Activity.RESULT_OK, Intent().setData(chosen))
        ReportTesting.waitForSaving()
        assertEquals("The report was saved.", ReportActivity.lastMessage)
        assertEquals(chosen, screen.savedUri)
        assertEquals(View.VISIBLE, screen.findViewById<View>(R.id.savedButtons).visibility)
        val pdf = ReportTesting.Pdf(file.toByteArray())
        assertEquals("one object for the catalog, one for the pages, three for every page", 3 + 3 * ReportRenderer.PAGES, pdf.objects.size)
        assertTrue(downloads.inserted.isEmpty())
    }
}
