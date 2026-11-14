package com.glucosescan.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.glucosescan.app.ai.AiClient
import com.glucosescan.app.ai.AiConfig
import com.glucosescan.app.ai.AiModels
import com.glucosescan.app.ai.AiProblem
import com.glucosescan.app.data.AiProvider
import com.glucosescan.app.data.Settings
import com.glucosescan.app.net.Net

/** The user chooses the AI service here and types its key. */
class AiSetupActivity : BaseActivity() {
    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var busy = false
    private var provider = AiProvider.GEMINI

    /** What the screen said last. For tests. */
    internal var lastMessage: String = ""
        private set

    /** The list of models that is open, or null. For tests. */
    internal var dialog: AlertDialog? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!noticeAccepted()) {
            return
        }
        settings = Settings(this)
        setContentWithBack(R.layout.activity_ai)

        provider = AiProvider.of(savedInstanceState?.getString(STATE_PROVIDER) ?: settings.aiProvider.code)
        for ((id, value) in PROVIDERS) {
            findViewById<View>(id).setOnClickListener { choose(value) }
        }
        if (savedInstanceState == null) {
            findViewById<EditText>(R.id.aiModel).setText(settings.aiModelInUse)
            findViewById<EditText>(R.id.aiUrl).setText(settings.aiUrlInUse)
        }
        findViewById<View>(R.id.aiGetKey).setOnClickListener { openKeyPage() }
        findViewById<View>(R.id.aiFindModels).setOnClickListener { findModels() }
        findViewById<View>(R.id.aiSave).setOnClickListener { save() }
        findViewById<View>(R.id.aiRemove).setOnClickListener { remove() }
        ready = true
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PROVIDER, provider.code)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    /** Another service: its usual address and model go into the fields. */
    private fun choose(next: AiProvider) {
        if (next == provider) {
            return
        }
        provider = next
        findViewById<EditText>(R.id.aiUrl).setText(next.defaultUrl)
        findViewById<EditText>(R.id.aiModel).setText(next.defaultModel)
        findViewById<EditText>(R.id.aiKey).setText("")
        say("", false)
        render()
    }

    /** The key in the field, or the saved one if the field is empty and the service is the saved one. */
    private fun keyInUse(): String {
        val typed = findViewById<EditText>(R.id.aiKey).text.toString().trim()
        return if (typed.isNotEmpty() || provider != settings.aiProvider) typed else settings.aiKey
    }

    /**
     * Opens the page where the user makes a key, in the browser. The app never has a key of
     * its own: a key belongs to the person who made it, and a shared key would stop working
     * the moment somebody used it up.
     */
    private fun openKeyPage() {
        val page = when (settings.aiProvider) {
            AiProvider.GEMINI -> "https://aistudio.google.com/apikey"
            AiProvider.OPENAI -> "https://platform.openai.com/api-keys"
            AiProvider.OTHER -> null
        }
        if (page == null) {
            say(getString(R.string.ai_help_other), false)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
        } catch (e: android.content.ActivityNotFoundException) {
            say(getString(R.string.ai_key_open_failed), true)
        }
    }

    private fun url(): String = findViewById<EditText>(R.id.aiUrl).text.toString().trim().trimEnd('/')

    private fun model(): String = findViewById<EditText>(R.id.aiModel).text.toString().trim()

    /** True if key and address are there; says what is missing if not. */
    private fun checked(needModel: Boolean): Boolean {
        return when {
            keyInUse().isEmpty() -> {
                say(getString(R.string.ai_error_need_key), true)
                false
            }
            !url().startsWith("https://") || url().length < 12 -> {
                say(getString(R.string.ai_error_need_url), true)
                false
            }
            needModel && model().isEmpty() -> {
                say(getString(R.string.ai_error_need_model), true)
                false
            }
            else -> true
        }
    }

    private fun findModels() {
        if (busy || !checked(needModel = false)) {
            return
        }
        busy = true
        say("", false)
        render()
        val config = AiConfig(provider, url(), keyInUse(), model())
        val transport = Net.transport
        Net.patient.execute {
            val models = try {
                AiClient(transport).models(config)
            } catch (e: Exception) {
                AiModels(emptyList(), AiProblem.SERVER, Net.words(e))
            }
            handler.post {
                busy = false
                if (!isDestroyed) {
                    render()
                    showModels(models)
                }
            }
        }
    }

    private fun showModels(models: AiModels) {
        if (models.problem != null) {
            say(AiWords.problem(this, models.problem, models.detail), true)
            return
        }
        if (models.names.isEmpty()) {
            say(getString(R.string.ai_models_none), true)
            return
        }
        val names = models.names.toTypedArray()
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ai_models_title)
            .setItems(names) { _, which -> findViewById<EditText>(R.id.aiModel).setText(names[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun save() {
        if (!checked(needModel = true)) {
            return
        }
        val key = keyInUse()
        settings.aiProvider = provider
        settings.aiKey = key
        // the usual address and model are not written down: a later version of the app may know better ones
        settings.aiUrl = if (url() == provider.defaultUrl) "" else url()
        settings.aiModel = if (model() == provider.defaultModel) "" else model()
        findViewById<EditText>(R.id.aiKey).setText("")
        say(getString(R.string.ai_saved), false)
        render()
    }

    private fun remove() {
        settings.aiKey = ""
        settings.aiAnswer = ""
        settings.aiAnswerTime = 0L
        settings.aiAnswerModel = ""
        say(getString(R.string.ai_removed), false)
        render()
    }

    private fun say(words: String, problem: Boolean) {
        lastMessage = words
        val message = findViewById<TextView>(R.id.aiMessage)
        message.text = words
        message.setTextColor(getColor(if (problem) R.color.red else R.color.green))
        message.visibility = if (words.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun render() {
        if (!ready || isDestroyed) {
            return
        }
        for ((id, value) in PROVIDERS) {
            findViewById<View>(id).isSelected = value == provider
        }
        findViewById<TextView>(R.id.aiHelp).setText(
            when (provider) {
                AiProvider.GEMINI -> R.string.ai_help_gemini
                AiProvider.OPENAI -> R.string.ai_help_openai
                AiProvider.OTHER -> R.string.ai_help_other
            }
        )
        // the address is asked for only where the app cannot know it
        val needsUrl = provider == AiProvider.OTHER
        findViewById<View>(R.id.aiUrlLabel).visibility = if (needsUrl) View.VISIBLE else View.GONE
        val urlField = findViewById<EditText>(R.id.aiUrl)
        urlField.visibility = if (needsUrl) View.VISIBLE else View.GONE
        urlField.hint = getString(R.string.ai_url_hint)
        findViewById<EditText>(R.id.aiKey).hint = getString(R.string.ai_key_hint)
        findViewById<EditText>(R.id.aiModel).hint = getString(R.string.ai_model_hint)

        val saved = settings.aiKey.isNotEmpty() && provider == settings.aiProvider
        findViewById<View>(R.id.aiKeySaved).visibility = if (saved) View.VISIBLE else View.GONE
        findViewById<View>(R.id.aiRemove).visibility = if (settings.aiKey.isNotEmpty()) View.VISIBLE else View.GONE

        val find = findViewById<Button>(R.id.aiFindModels)
        find.isEnabled = !busy
        find.setText(if (busy) R.string.ai_finding_models else R.string.ai_find_models)
    }

    companion object {
        private const val STATE_PROVIDER = "provider"
        private val PROVIDERS = mapOf(R.id.aiGemini to AiProvider.GEMINI, R.id.aiOpenAi to AiProvider.OPENAI, R.id.aiOther to AiProvider.OTHER)
    }
}

/** The words for what went wrong with the AI service. */
object AiWords {
    fun problem(context: android.content.Context, problem: AiProblem, detail: String): String {
        val words = context.getString(
            when (problem) {
                AiProblem.NO_INTERNET -> R.string.ai_error_no_internet
                AiProblem.KEY -> R.string.ai_error_key
                AiProblem.MODEL -> R.string.ai_error_model
                AiProblem.LIMIT -> R.string.ai_error_limit
                AiProblem.BLOCKED -> R.string.ai_error_blocked
                AiProblem.EMPTY -> R.string.ai_error_empty
                AiProblem.SERVER -> R.string.ai_error_server
            }
        )
        // what the service itself said helps the person who looks after the app
        val showDetail = detail.isNotEmpty() && problem != AiProblem.NO_INTERNET && problem != AiProblem.EMPTY
        return if (showDetail) context.getString(R.string.ai_error_detail, words, detail) else words
    }
}
