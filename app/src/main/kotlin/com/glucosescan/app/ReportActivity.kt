package com.glucosescan.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.glucosescan.app.core.AnalysisCalculator
import com.glucosescan.app.core.DailyReport
import com.glucosescan.app.core.PeriodReports
import com.glucosescan.app.core.DailyReports
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.StatisticsCalculator
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings
import com.glucosescan.app.report.PdfKind
import com.glucosescan.app.report.ReportRenderer
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.Texts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The report of one day: two pages with the chart of the day, the time in ranges, how the
 * day was in words, hour by hour, and the six days before. The screen shows the pages as
 * pictures and saves them as a PDF file: on Android 10 and newer straight into the Downloads
 * folder of the phone, on older Android where the user says. The saved file can be opened
 * and sent on with the apps of the phone. The app itself sends nothing anywhere.
 */
class ReportActivity : BaseActivity() {
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private var ready = false

    /** The midnight that starts the day the report is about. */
    private var day = 0L

    /** Where the report of [day] was saved last, or null. */
    internal var savedUri: Uri? = null
        private set

    /** True while a file is written: a second tap on the button must not write a second file. */
    private var saving = false

    /** How many days the report reaches back. 1 is the single-day report. */
    internal var period: Int = 1
        private set

    /** What the screen shows now. For tests. */
    internal var report: DailyReport? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!noticeAccepted()) {
            return
        }
        settings = Settings(this)
        store = GlucoseStore.get(this)
        setContentWithBar(R.layout.activity_report, Tab.REPORT)
        day = savedInstanceState?.getLong(KEY_DAY, 0L) ?: 0L
        if (day == 0L) {
            day = AnalysisCalculator.dayStart(AppClock.now())
        }
        for (length in PeriodReports.LENGTHS) {
            val chip = findViewById<TextView>(chipId(length))
            chip.text = periodName(length)
            chip.setOnClickListener {
                if (period != length) {
                    period = length
                    savedUri = null
                    findViewById<View>(R.id.reportMessage).visibility = View.GONE
                    render()
                }
            }
        }
        findViewById<View>(R.id.dayBefore).setOnClickListener { move(-1) }
        findViewById<View>(R.id.dayAfter).setOnClickListener { move(1) }
        findViewById<View>(R.id.savePdf).setOnClickListener { download() }
        findViewById<View>(R.id.openPdf).setOnClickListener { open() }
        findViewById<View>(R.id.sharePdf).setOnClickListener { share() }
        ready = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_DAY, day)
    }

    override fun onResume() {
        super.onResume()
        if (ready) {
            render()
        }
    }

    private fun chipId(length: Int): Int = when (length) {
        1 -> R.id.period1
        7 -> R.id.period7
        14 -> R.id.period14
        30 -> R.id.period30
        else -> R.id.period90
    }

    private fun periodName(length: Int): String = when (length) {
        1 -> getString(R.string.report_period_one_1)
        7 -> getString(R.string.report_period_days_7)
        14 -> getString(R.string.report_period_days_14)
        30 -> getString(R.string.report_period_days_30)
        else -> getString(R.string.report_period_days_90)
    }

    private fun move(days: Int) {
        day = AnalysisCalculator.addDays(day, days)
        savedUri = null
        findViewById<View>(R.id.reportMessage).visibility = View.GONE
        render()
    }

    private fun unit(): GlucoseUnit = settings.unit ?: GlucoseUnit.MGDL

    private fun appVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun render() {
        val now = AppClock.now()
        val today = AnalysisCalculator.dayStart(now)
        val oldest = AnalysisCalculator.addDays(today, -KEEP_DAYS)
        day = day.coerceIn(oldest, today)
        findViewById<View>(R.id.dayBefore).isEnabled = day > oldest
        findViewById<View>(R.id.dayAfter).isEnabled = day < today
        findViewById<TextView>(R.id.dayLabel).text = AnalysisActivity.dayName(this, day, today)

        // how far back the report reaches decides which card is used to choose
        val single = period <= 1
        findViewById<View>(R.id.dayCard).visibility = if (single) View.VISIBLE else View.GONE
        for (length in PeriodReports.LENGTHS) {
            val chip = findViewById<TextView>(chipId(length))
            val chosen = length == period
            chip.isSelected = chosen
            chip.isActivated = chosen
        }

        val values = settings.correction.applyAll(
            store.between(
                if (single) AnalysisCalculator.addDays(day, -DailyReports.WEEK_DAYS) else AnalysisCalculator.addDays(now, -period),
                if (single) AnalysisCalculator.addDays(day, 1) else now
            )
        )
        val built = if (single) {
            DailyReports.build(values, day, now, settings.lowLimit, settings.highLimit)
        } else {
            PeriodReports.build(values, period, now, settings.lowLimit, settings.highLimit)
        }
        report = built

        val summary = findViewById<TextView>(R.id.daySummary)
        val pages = PAGES_OF_REPORT.map { findViewById<ImageView>(it) }
        val has = built.hasValues
        findViewById<View>(R.id.reportEmpty).visibility = if (has) View.GONE else View.VISIBLE
        findViewById<View>(R.id.savePdf).isEnabled = has && !saving
        findViewById<View>(R.id.savedButtons).visibility = if (has && savedUri != null) View.VISIBLE else View.GONE
        for (page in pages) {
            page.visibility = if (has) View.VISIBLE else View.GONE
        }
        if (!has) {
            summary.visibility = View.GONE
            for (page in pages) {
                page.setImageDrawable(null)
            }
            return
        }
        var line = getString(R.string.report_day_values, built.span.stats.coveragePercent.toString() + " %")
        if (built.running) {
            line = getString(R.string.two_lines, line, getString(R.string.report_day_running, Texts.timeOfDay(this, built.until - 60_000L)))
        }
        summary.text = line
        summary.visibility = View.VISIBLE

        // the pages as pictures, as wide as the screen (but not bigger than needed to read them)
        val metrics = resources.displayMetrics
        val width = Math.min(MAX_PICTURE_WIDTH, Math.max(320, metrics.widthPixels - (34 * metrics.density).toInt()))
        val renderer = ReportRenderer(this, built, unit(), now, appVersion(), settings.linkUpEnabled)
        for ((index, page) in pages.withIndex()) {
            page.setImageBitmap(ReportRenderer.picture(renderer, index, width))
        }
    }

    private fun fileName(report: DailyReport): String {
        return "walhad-glucose-report-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(report.dayStart)) +
            (if (period > 1) "-" + period + "d" else "") + ".pdf"
    }

    /**
     * The button "Download". Android 10 and newer let an app put a file into the Downloads
     * folder without asking for any permission. Older Android does not: there the user is
     * asked where the file goes.
     */
    private fun download() {
        val current = report
        if (current == null || !current.hasValues || saving) {
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            save(null)
        } else {
            askWhereToSave()
        }
    }

    /** Lets the user choose the place and the name of the file. Android then gives the app that one file to write. */
    private fun askWhereToSave() {
        val current = report
        if (current == null || !current.hasValues) {
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(PDF)
            .putExtra(Intent.EXTRA_TITLE, fileName(current))
        try {
            startActivityForResult(intent, REQUEST_SAVE)
        } catch (e: ActivityNotFoundException) {
            showMessage(getString(R.string.report_failed), false)
        }
    }

    @Deprecated("the plain Android way; this app uses no support library")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_SAVE && resultCode == Activity.RESULT_OK && uri != null) {
            save(uri)
        }
    }

    /**
     * Writes the report of the shown day as a PDF.
     * @param target the file the user chose, or null: a new file in the Downloads folder
     * (Android 10 and newer only)
     */
    internal fun save(target: Uri?) {
        val current = report ?: return
        if (saving || !current.hasValues) {
            return
        }
        saving = true
        findViewById<View>(R.id.savePdf).isEnabled = false
        val app = applicationContext
        val unit = unit()
        val version = appVersion()
        val fromLinkUp = settings.linkUpEnabled
        val madeAt = AppClock.now()
        val savedDay = day
        val name = fileName(current)
        val handler = Handler(Looper.getMainLooper())
        Thread({
            var uri = target
            var savedName = name
            val ok = try {
                if (uri == null) {
                    uri = newDownload(app, name) ?: throw java.io.IOException("no place in Downloads")
                }
                val stream = app.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("no stream")
                lastKind = stream.use { ReportRenderer.writePdf(ReportRenderer(app, current, unit, madeAt, version, fromLinkUp), it) }
                if (target == null) {
                    savedName = finishDownload(app, uri, name)
                }
                true
            } catch (e: Exception) {
                false
            }
            if (!ok && target == null && uri != null) {
                // nothing half-written stays in Downloads
                try {
                    app.contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    // the entry is gone already, or Android does not let go of it
                }
            }
            val written = uri
            handler.post {
                saving = false
                val words = when {
                    !ok -> app.getString(R.string.report_failed)
                    target == null -> app.getString(R.string.report_saved_downloads, savedName)
                    else -> app.getString(R.string.report_saved)
                }
                lastMessage = words
                if (ok && savedDay == day) {
                    savedUri = written
                }
                if (!isDestroyed) {
                    showMessage(words, ok)
                    val has = report?.hasValues == true
                    findViewById<View>(R.id.savePdf).isEnabled = has
                    findViewById<View>(R.id.savedButtons).visibility = if (has && savedUri != null) View.VISIBLE else View.GONE
                    if (!ok && target == null) {
                        // Downloads did not work on this phone: the user chooses the place
                        askWhereToSave()
                    }
                }
            }
        }, "report").start()
    }

    private fun showMessage(words: String, good: Boolean) {
        val message = findViewById<TextView>(R.id.reportMessage)
        message.text = words
        message.setTextColor(getColor(if (good) R.color.green else R.color.red))
        message.visibility = View.VISIBLE
        Toast.makeText(applicationContext, words, Toast.LENGTH_LONG).show()
    }

    /** Shows the saved file in the PDF app of the phone. */
    private fun open() {
        val uri = savedUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, PDF)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showMessage(getString(R.string.report_no_viewer), false)
        } catch (e: SecurityException) {
            showMessage(getString(R.string.report_no_viewer), false)
        }
    }

    /** Hands the saved file to an app the user chooses, for example to send it to the doctor. */
    private fun share() {
        val uri = savedUri ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType(PDF)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_share_subject))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(null, uri) // lets the app that gets the file read it
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.report_share)))
        } catch (e: ActivityNotFoundException) {
            showMessage(getString(R.string.report_no_share), false)
        } catch (e: SecurityException) {
            showMessage(getString(R.string.report_no_share), false)
        }
    }

    companion object {

        /** The image views of the pages, in order. Keep in step with ReportRenderer.PAGES. */
        private val PAGES_OF_REPORT = intArrayOf(R.id.page1, R.id.page2, R.id.page3, R.id.page4, R.id.page5)

        private const val KEY_DAY = "day"
        private const val REQUEST_SAVE = 8
        private const val PDF = "application/pdf"

        /** Readings are kept for 90 days: there is nothing to report before that. */
        private const val KEEP_DAYS = 90
        private const val MAX_PICTURE_WIDTH = 1240

        /** What the last saving said. For tests. */
        @Volatile
        internal var lastMessage: String? = null

        /** How the last file was made. For tests. */
        @Volatile
        internal var lastKind: PdfKind? = null

        /**
         * A new, still hidden file in the Downloads folder (Android 10 and newer). If the name
         * is taken, Android adds a number to it.
         */
        private fun newDownload(context: Context, name: String): Uri? {
            if (Build.VERSION.SDK_INT < 29) {
                return null
            }
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, PDF)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            return context.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        }

        /** Makes the written file visible to the user and to other apps. Returns the name Android gave it. */
        private fun finishDownload(context: Context, uri: Uri, name: String): String {
            if (Build.VERSION.SDK_INT < 29) {
                return name
            }
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            try {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val given = cursor.getString(0)
                        if (!given.isNullOrBlank()) {
                            return given
                        }
                    }
                }
            } catch (e: Exception) {
                // the name asked for is shown
            }
            return name
        }
    }
}
