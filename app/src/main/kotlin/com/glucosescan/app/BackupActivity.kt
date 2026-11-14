package com.glucosescan.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.glucosescan.app.cloud.BackupFailure
import com.glucosescan.app.cloud.BackupProblem
import com.glucosescan.app.cloud.BackupResult
import com.glucosescan.app.cloud.BackupRunner
import com.glucosescan.app.cloud.SupabaseApi
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings
import com.glucosescan.app.live.LiveBus
import com.glucosescan.app.net.Net
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.Texts

/**
 * The online copy of the values, in a database (a Supabase project) that belongs to the
 * user: logging in, copying, getting the values back.
 */
class BackupActivity : BaseActivity() {
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var busy = false
    private val refresh = Runnable { render() }

    /** What the screen said last. For tests. */
    internal var lastMessage: String = ""
        private set

    /** The question that is open, or null. For tests. */
    internal var dialog: AlertDialog? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!noticeAccepted()) {
            return
        }
        settings = Settings(this)
        store = GlucoseStore.get(this)
        setContentWithBack(R.layout.activity_backup)

        if (savedInstanceState == null) {
            findViewById<EditText>(R.id.backupUrl).setText(settings.backupUrl)
            findViewById<EditText>(R.id.backupKey).setText(settings.backupKey)
            findViewById<EditText>(R.id.backupEmail).setText(settings.backupEmail)
        }
        findViewById<View>(R.id.backupCopySql).setOnClickListener { copySetUpText() }
        findViewById<View>(R.id.backupLogin).setOnClickListener { account(create = false) }
        findViewById<View>(R.id.backupCreate).setOnClickListener { account(create = true) }
        findViewById<View>(R.id.backupNow).setOnClickListener { work { runner, now -> runner.copy(now) } }
        findViewById<View>(R.id.backupRestore).setOnClickListener { restore() }
        findViewById<View>(R.id.backupLogout).setOnClickListener { logOut() }
        findViewById<View>(R.id.backupDeleteOnline).setOnClickListener { askToEmpty() }
        ready = true
    }

    override fun onResume() {
        super.onResume()
        if (ready) {
            LiveBus.add(refresh)
            render()
        }
    }

    override fun onPause() {
        super.onPause()
        LiveBus.remove(refresh)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    /** The text that makes the table: the same text the README shows. */
    internal fun setUpText(): String = resources.openRawResource(R.raw.supabase_setup).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun copySetUpText() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("supabase-setup.sql", setUpText()))
        say(getString(R.string.backup_sql_copied), false)
    }

    // ------------------------------------------------------------------ the work

    private var what = Work.COPY

    private enum class Work { CREATE, LOGIN, COPY, RESTORE, EMPTY }

    private fun account(create: Boolean) {
        if (busy) {
            return
        }
        val typedUrl = findViewById<EditText>(R.id.backupUrl).text.toString()
        val key = findViewById<EditText>(R.id.backupKey).text.toString().trim()
        val email = findViewById<EditText>(R.id.backupEmail).text.toString().trim()
        val password = findViewById<EditText>(R.id.backupPassword).text.toString()
        if (typedUrl.isBlank() || key.isEmpty() || !email.contains('@') || password.isEmpty()) {
            say(getString(R.string.backup_error_fields), true)
            return
        }
        val url = SupabaseApi.cleanUrl(typedUrl)
        if (url == null) {
            say(getString(R.string.backup_error_url), true)
            return
        }
        what = if (create) Work.CREATE else Work.LOGIN
        run { runner, now ->
            val result = if (create) runner.signUp(url, key, email, password, now) else runner.logIn(url, key, email, password, now)
            // the first copy right after the login, so that the user sees that it works
            if (result.isOk && !result.confirmFirst) runner.copy(now).let { copied -> if (copied.isOk) result else copied } else result
        }
    }

    private fun work(task: (BackupRunner, Long) -> BackupResult) {
        if (busy) {
            return
        }
        what = Work.COPY
        run(task)
    }

    private fun restore() {
        if (busy) {
            return
        }
        what = Work.RESTORE
        run { runner, now -> runner.restore(now) }
    }

    private fun askToEmpty() {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.backup_delete_title)
            .setMessage(R.string.backup_delete_text)
            .setPositiveButton(R.string.backup_delete_yes) { _, _ ->
                if (!busy) {
                    what = Work.EMPTY
                    run { runner, now -> runner.removeOnline(now) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun logOut() {
        if (busy) {
            return
        }
        val runner = BackupRunner(store, settings, Net.transport)
        settings.backupEnabled = false
        Net.patient.execute {
            try {
                runner.logOut()
            } catch (e: Exception) {
                settings.forgetBackupLogin()
            }
            handler.post {
                if (!isDestroyed) {
                    render()
                }
            }
        }
        say(getString(R.string.backup_logged_out), false)
        render()
    }

    private fun run(task: (BackupRunner, Long) -> BackupResult) {
        busy = true
        say("", false)
        render()
        val runner = BackupRunner(store, settings, Net.transport)
        val now = AppClock.now()
        Net.patient.execute {
            val result = try {
                task(runner, now)
            } catch (e: Exception) {
                BackupResult(BackupFailure(BackupProblem.SERVER, Net.words(e)))
            }
            handler.post {
                busy = false
                if (!isDestroyed) {
                    done(result)
                }
            }
        }
    }

    private fun done(result: BackupResult) {
        val failure = result.failure
        if (failure != null) {
            say(BackupWords.problem(this, failure, loggedIn = settings.backupEnabled), true)
            render()
            return
        }
        when (what) {
            Work.CREATE, Work.LOGIN -> {
                if (result.confirmFirst) {
                    say(getString(R.string.backup_confirm_first, settings.backupEmail), false)
                } else {
                    findViewById<EditText>(R.id.backupPassword).setText("")
                    say(getString(R.string.backup_logged_in), false)
                }
            }
            Work.COPY -> say(if (result.count > 0) resources.getQuantityString(R.plurals.backup_done, result.count, result.count) else getString(R.string.backup_done_none), false)
            Work.RESTORE -> {
                say(if (result.count > 0) resources.getQuantityString(R.plurals.backup_restore_done, result.count, result.count) else getString(R.string.backup_restore_none), false)
                LiveBus.changed()
            }
            Work.EMPTY -> say(getString(R.string.backup_deleted), false)
        }
        render()
    }

    // ------------------------------------------------------------------ showing

    private fun say(words: String, problem: Boolean) {
        lastMessage = words
        for (id in intArrayOf(R.id.backupMessage, R.id.backupStatusMessage)) {
            val message = findViewById<TextView>(id)
            message.text = words
            message.setTextColor(getColor(if (problem) R.color.red else R.color.green))
            message.visibility = if (words.isEmpty()) View.GONE else View.VISIBLE
        }
        if (words.isNotEmpty()) {
            findViewById<View>(android.R.id.content).announceForAccessibility(words)
        }
    }

    private fun render() {
        if (!ready || isDestroyed) {
            return
        }
        val on = settings.backupEnabled
        findViewById<View>(R.id.backupStatusCard).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.backupStepsCard).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.backupLoginCard).visibility = if (on) View.GONE else View.VISIBLE

        val login = findViewById<Button>(R.id.backupLogin)
        login.isEnabled = !busy
        login.setText(if (busy && !on) R.string.backup_busy else R.string.backup_login)
        findViewById<View>(R.id.backupCreate).isEnabled = !busy
        val copy = findViewById<Button>(R.id.backupNow)
        copy.setText(if (busy && on) R.string.backup_busy else R.string.backup_now)
        for (id in intArrayOf(R.id.backupNow, R.id.backupRestore, R.id.backupLogout, R.id.backupDeleteOnline)) {
            findViewById<View>(id).isEnabled = !busy
        }
        if (!on) {
            return
        }

        val now = AppClock.now()
        val problem = BackupProblem.values().firstOrNull { it.name == settings.backupProblem }
        val state = findViewById<TextView>(R.id.backupStateText)
        if (problem != null) {
            state.text = BackupWords.problem(this, BackupFailure(problem, settings.backupDetail), loggedIn = true)
            state.setTextColor(getColor(R.color.red))
        } else {
            state.setText(R.string.backup_status_on)
            state.setTextColor(getColor(R.color.text))
        }
        findViewById<TextView>(R.id.backupAccountText).text = getString(R.string.backup_account, settings.backupEmail)
        val last = settings.backupLastTime
        findViewById<TextView>(R.id.backupLastText).text =
            if (last > 0L) getString(R.string.backup_last, Texts.timeOrDateTime(this, last, now)) else getString(R.string.backup_never)
        val waiting = BackupRunner(store, settings, Net.transport).waiting()
        findViewById<TextView>(R.id.backupWaitingText).text =
            if (waiting > 0) resources.getQuantityString(R.plurals.backup_waiting, waiting, waiting) else getString(R.string.backup_waiting_none)
        val online = settings.backupOnlineCount
        val onlineText = findViewById<TextView>(R.id.backupOnlineText)
        onlineText.text = if (online >= 0) resources.getQuantityString(R.plurals.backup_online_count, online, online) else ""
        onlineText.visibility = if (online >= 0) View.VISIBLE else View.GONE
    }
}

/** The words for what went wrong with the online copy. */
object BackupWords {
    /** @param loggedIn true: the copy was on already (a saved login failed); false: the user is just logging in */
    fun problem(context: Context, failure: BackupFailure, loggedIn: Boolean): String {
        val words = context.getString(
            when (failure.problem) {
                BackupProblem.NO_INTERNET -> R.string.backup_error_no_internet
                BackupProblem.LOGIN -> if (loggedIn) R.string.backup_error_login_again else R.string.backup_error_login
                BackupProblem.PASS_EXPIRED -> if (loggedIn) R.string.backup_error_login_again else R.string.backup_error_server
                BackupProblem.NOT_CONFIRMED -> R.string.backup_error_not_confirmed
                BackupProblem.WEAK_PASSWORD -> R.string.backup_error_weak
                BackupProblem.EXISTS -> R.string.backup_error_exists
                BackupProblem.KEY -> R.string.backup_error_key
                BackupProblem.NO_TABLE -> R.string.backup_error_table
                BackupProblem.SERVER -> R.string.backup_error_server
            }
        )
        val detail = failure.detail
        return if (failure.problem == BackupProblem.SERVER && detail.isNotEmpty()) context.getString(R.string.ai_error_detail, words, detail) else words
    }
}
