package com.glucosescan.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.glucosescan.app.core.CsvExport
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings
import com.glucosescan.app.ui.AppClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The way to everything that is not on the three other main screens. */
class MoreActivity : BaseActivity() {
    private lateinit var settings: Settings
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!noticeAccepted()) {
            return
        }
        settings = Settings(this)
        setContentWithBar(R.layout.activity_more, Tab.MORE)

        for (group in intArrayOf(R.id.groupValues, R.id.groupData, R.id.groupApp)) {
            findViewById<View>(group).clipToOutline = true // the wave of a touch stays inside the round corners
        }
        for (entry in ENTRIES) {
            val row = findViewById<View>(entry.id)
            row.findViewById<ImageView>(R.id.navIcon).setImageResource(entry.icon)
            row.findViewById<TextView>(R.id.navTitle).setText(entry.title)
            row.findViewById<TextView>(R.id.navNote).setText(entry.note)
            row.contentDescription = getString(R.string.more_row_description, getString(entry.title), getString(entry.note))
            val screen = entry.screen
            row.setOnClickListener {
                if (screen != null) startActivity(Intent(this, screen)) else askWhereToSave()
            }
        }
        ready = true
    }

    override fun onResume() {
        super.onResume()
        if (ready) {
            // the four things that can be on or off say so at the end of their row
            state(R.id.buttonLinkUp, settings.linkUpEnabled, R.string.state_on, R.string.state_off)
            state(R.id.buttonLive, settings.liveEnabled, R.string.state_on, R.string.state_off)
            state(R.id.buttonBackup, settings.backupEnabled, R.string.state_on, R.string.state_off)
            // there is nothing to set up: the AI is built in
        state(R.id.buttonAi, true, R.string.state_ready, R.string.state_ready)
        }
    }

    private fun state(rowId: Int, on: Boolean, onWords: Int, offWords: Int) {
        val row = findViewById<View>(rowId)
        val words = getString(if (on) onWords else offWords)
        val state = row.findViewById<TextView>(R.id.navState)
        state.text = words
        state.setBackgroundResource(if (on) R.drawable.pill_state_on else R.drawable.pill_state_off)
        state.setTextColor(getColor(if (on) R.color.state_on_text else R.color.text_secondary))
        state.visibility = View.VISIBLE
        val entry = ENTRIES.first { it.id == rowId }
        row.contentDescription = getString(R.string.more_row_description_state, getString(entry.title), getString(entry.note), words)
    }

    /** Lets the user choose the place and the name of the file. Android then gives the app that one file to write. */
    private fun askWhereToSave() {
        val store = GlucoseStore.get(this)
        if (store.count() == 0) {
            Toast.makeText(this, R.string.export_nothing, Toast.LENGTH_LONG).show()
            return
        }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(AppClock.now()))
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "walhad-glucose-$day.csv")
        try {
            startActivityForResult(intent, REQUEST_EXPORT)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("the plain Android way; this app uses no support library")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_EXPORT && resultCode == Activity.RESULT_OK && uri != null) {
            export(uri)
        }
    }

    /** Writes all readings into the file the user chose. */
    internal fun export(uri: Uri) {
        val app = applicationContext
        val store = GlucoseStore.get(this)
        val correction = settings.correction
        val handler = Handler(Looper.getMainLooper())
        Thread({
            var readings = 0
            val ok = try {
                val values = store.since(0L)
                readings = values.size
                val text = CsvExport.build(values, { correction.apply(it) })
                val stream = app.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("no stream")
                stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                true
            } catch (e: Exception) {
                false
            }
            handler.post {
                val message = if (ok) getString(R.string.export_done, readings) else getString(R.string.export_failed)
                lastExportMessage = message
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        }, "export").start()
    }

    /** One row of the lists: where it stands, how it looks, where it leads (null: "Save data as a file"). */
    private class Entry(val id: Int, val icon: Int, val title: Int, val note: Int, val screen: Class<out Activity>?)

    companion object {
        private val ENTRIES = listOf(
            Entry(R.id.buttonScanSensor, R.drawable.ic_nfc, R.string.scan_screen_title, R.string.more_note_scan, ScanSensorActivity::class.java),
            Entry(R.id.buttonLinkUp, R.drawable.ic_cloud, R.string.linkup_title, R.string.more_note_linkup, LinkUpActivity::class.java),
            Entry(R.id.buttonLive, R.drawable.ic_bluetooth, R.string.more_bluetooth, R.string.more_note_live, LiveActivity::class.java),
            Entry(R.id.buttonAlarms, R.drawable.ic_bell, R.string.alarms_title, R.string.more_note_alarms, AlarmsActivity::class.java),
            Entry(R.id.buttonVoice, R.drawable.ic_voice, R.string.voice_title, R.string.more_note_voice, VoiceActivity::class.java),
            Entry(R.id.buttonStatistics, R.drawable.ic_tab_stats, R.string.stats_title, R.string.more_note_statistics, StatisticsActivity::class.java),
            Entry(R.id.buttonReadings, R.drawable.ic_list, R.string.readings, R.string.more_note_readings, ReadingsActivity::class.java),
            Entry(R.id.buttonExport, R.drawable.ic_save, R.string.export_title, R.string.more_note_export, null),
            Entry(R.id.buttonBackup, R.drawable.ic_cloud_up, R.string.backup_title, R.string.more_note_backup, BackupActivity::class.java),
            Entry(R.id.buttonAi, R.drawable.ic_spark, R.string.ai_setup_title, R.string.more_note_ai, AiInsightsActivity::class.java),
            Entry(R.id.buttonSettings, R.drawable.ic_settings, R.string.settings, R.string.more_note_settings, SettingsActivity::class.java),
            Entry(R.id.buttonAbout, R.drawable.ic_info, R.string.about, R.string.more_note_about, AboutActivity::class.java)
        )

        private const val REQUEST_EXPORT = 7

        /** What the last export said. For tests. */
        @Volatile
        internal var lastExportMessage: String? = null
    }
}
