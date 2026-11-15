package com.glucosescan.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.glucosescan.app.ai.AiProblem
import com.glucosescan.app.ai.AiQuestion
import com.glucosescan.app.ai.AiSource
import com.glucosescan.app.ai.AiTurn
import com.glucosescan.app.ai.WalhadAi
import com.glucosescan.app.core.Advisor
import com.glucosescan.app.core.AdvisorBlock
import com.glucosescan.app.core.AnalysisCalculator
import com.glucosescan.app.core.AnswerLanguage
import com.glucosescan.app.core.AiReport
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings
import com.glucosescan.app.net.Net
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.AppFont
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Insights: what the numbers say over a stretch of days, and a place to ask about them.
 *
 * Two answers are shown, and the screen always says which is which:
 *
 * - **What your phone reads** is counted on the phone itself, from the person's own values, by
 *   fixed rules ([Advisor]). It needs no internet and no key, so it is always there.
 * - **The answer** comes from the AI service, when it can be reached, and is built only from the
 *   numbers on this screen.
 *
 * If the AI cannot be reached, is over its daily limit, or answers with something this app will
 * not show, the screen says so and keeps the phone's reading. It never shows the phone's words
 * as if the AI had written them, and it never invents a number that was not measured.
 */
class AiInsightsActivity : BaseActivity() {

    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private val handler = Handler(Looper.getMainLooper())

    /** How many days back the screen looks. 7, 14, 30 or 90. */
    private var days: Int = DEFAULT_DAYS

    /** The last exchanges, so that "and at night?" has something to hang on to. */
    private val history = ArrayList<AiTurn>()

    /** Runs the question that waits for the internet. Tests replace it with one that runs at once. */
    private var background: java.util.concurrent.Executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "ai-question").apply { start() }
    }

    /** The questions that are ready to press, in the order they are shown. */
    private val readyQuestions = listOf(
        R.string.q_overall,
        R.string.q_lows,
        R.string.q_highs,
        R.string.q_night,
        R.string.q_pattern,
        R.string.q_compare,
        R.string.q_doctor
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!noticeAccepted()) {
            return
        }
        settings = Settings(this)
        store = GlucoseStore.get(this)
        setContentWithBar(R.layout.activity_insights, Tab.INSIGHTS)

        for ((index, view) in PERIODS.withIndex()) {
            val chip = findViewById<TextView>(view)
            chip.text = getString(R.string.insights_days, PERIOD_DAYS[index])
            chip.setOnClickListener {
                days = PERIOD_DAYS[index]
                history.clear()
                render()
            }
        }
        findViewById<Button>(R.id.askButton).setOnClickListener { ask() }
        findViewById<EditText>(R.id.ownQuestion).setOnEditorActionListener { _, _, _ ->
            ask()
            true
        }
        render()
    }

    // ------------------------------------------------------------------ showing

    private fun render() {
        val now = AppClock.now()
        markChips()
        renderReading(now)
        renderQuestions()
    }

    private fun markChips() {
        for ((index, view) in PERIODS.withIndex()) {
            val chip = findViewById<TextView>(view)
            val chosen = PERIOD_DAYS[index] == days
            chip.isSelected = chosen
            chip.isActivated = chosen
        }
    }

    /** The reading from the phone: the same engine as the Analysis screen, over [days] days. */
    private fun renderReading(now: Long) {
        val box = findViewById<LinearLayout>(R.id.readingBox)
        box.removeAllViews()
        val values = store.since(now - days * DAY_MILLIS)
        val note = findViewById<TextView>(R.id.periodNote)

        if (values.isEmpty()) {
            note.text = getString(R.string.insights_no_values)
            add(box, getString(R.string.insights_no_values))
            return
        }
        val unit = settings.unit ?: GlucoseUnit.MGDL
        val from = now - days * DAY_MILLIS
        val facts = Advisor.facts(values, from, now, settings.lowLimit, settings.highLimit, unit, TimeZone.getDefault())
        note.text = getString(R.string.insights_days, days) + " · " + values.size + " values"

        for (block in Advisor.tell(facts, settings.answerLanguage)) {
            addTitle(box, block.title)
            for (line in block.lines) {
                add(box, line)
            }
        }
    }

    /** The ready-made questions, as wide buttons that ask the moment they are pressed. */
    private fun renderQuestions() {
        val box = findViewById<LinearLayout>(R.id.questionChips)
        box.removeAllViews()
        for (textId in readyQuestions) {
            val button = Button(this)
            button.text = getString(textId)
            button.setTextColor(getColor(R.color.text))
            button.textSize = 14f
            button.isAllCaps = false
            button.typeface = AppFont.regular(this)
            button.background = getDrawable(R.drawable.range_button_background)
            button.minimumHeight = 0
            button.minHeight = 0
            button.setPadding(dp(12), dp(12), dp(12), dp(12))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(6)
            button.layoutParams = params
            button.setOnClickListener { ask(getString(textId)) }
            box.addView(button)
        }
    }

    private fun add(box: LinearLayout, words: String) {
        val view = TextView(this)
        view.text = words
        view.setTextColor(getColor(R.color.text))
        view.textSize = 14f
        view.setLineSpacing(0f, 1.25f)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(3)
        view.layoutParams = params
        box.addView(view)
    }

    private fun addTitle(box: LinearLayout, words: String) {
        val view = TextView(this)
        view.text = words
        view.setTextColor(getColor(R.color.blue))
        view.textSize = 14f
        view.typeface = AppFont.bold(this)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(11)
        view.layoutParams = params
        box.addView(view)
    }

    private fun dp(value: Int): Int =
        Math.round(value * resources.displayMetrics.density)

    // ------------------------------------------------------------------ asking

    private fun ask(question: String = findViewById<EditText>(R.id.ownQuestion).text.toString().trim()) {
        val now = AppClock.now()
        val unit = settings.unit ?: GlucoseUnit.MGDL
        val values = store.since(now - days * DAY_MILLIS)
        if (values.isEmpty()) {
            return
        }
        val from = now - days * DAY_MILLIS
        val zone = TimeZone.getDefault()
        val analysis = AnalysisCalculator.compute(values, from, now, settings.lowLimit, settings.highLimit, zone)
        val hours = AnalysisCalculator.hours(values, from, now, zone)
        val report = AiReport.build(
            analysis, hours, emptyList(), unit, settings.lowLimit, settings.highLimit,
            settings.answerLanguage, zone, settings.linkUpEnabled
        )

        showThinking()
        val request = AiQuestion(
            report = report,
            question = question,
            language = settings.answerLanguage,
            history = history.toList(),
            fallback = Advisor.tell(
                Advisor.facts(values, from, now, settings.lowLimit, settings.highLimit, unit, zone),
                settings.answerLanguage
            )
        )
        val ai = WalhadAi(Net.transport, install = settings.installId)
        background.execute {
            val answer = ai.ask(request)
            handler.post {
                if (isFinishing || isDestroyed) {
                    return@post
                }
                if (answer.source == AiSource.AI) {
                    history.add(AiTurn(question, answer.text))
                }
                showAnswer(answer.text, answer.source, answer.problem)
            }
        }
    }

    private fun showThinking() {
        val card = findViewById<View>(R.id.answerCard)
        card.visibility = View.VISIBLE
        findViewById<ProgressBar>(R.id.answerProgress).visibility = View.VISIBLE
        findViewById<TextView>(R.id.answerText).text = getString(R.string.insights_thinking)
        findViewById<TextView>(R.id.answerSource).apply {
            text = getString(R.string.insights_from_phone)
            visibility = View.GONE
        }
        findViewById<TextView>(R.id.answerNote).visibility = View.GONE
    }

    /**
     * Shows the answer with a badge saying where it came from. The badge is never left blank and
     * is never guessed: the badge comes from the answer itself, not from whether the text looks
     * like something an AI would write.
     */
    private fun showAnswer(text: String, source: AiSource, problem: AiProblem?) {
        findViewById<ProgressBar>(R.id.answerProgress).visibility = View.GONE
        findViewById<TextView>(R.id.answerText).text = text
        val badge = findViewById<TextView>(R.id.answerSource)
        badge.visibility = View.VISIBLE
        if (source == AiSource.AI) {
            badge.text = getString(R.string.insights_from_ai)
            badge.setBackgroundResource(R.drawable.pill_state_on)
            badge.setTextColor(getColor(R.color.state_on_text))
        } else {
            badge.text = getString(R.string.insights_from_phone)
            badge.setBackgroundResource(R.drawable.pill_state_off)
            badge.setTextColor(getColor(R.color.text_secondary))
        }

        val note = findViewById<TextView>(R.id.answerNote)
        val words = when (problem) {
            null -> if (source == AiSource.PHONE) R.string.insights_ai_off else null
            AiProblem.LIMIT -> R.string.insights_limit
            else -> R.string.insights_no_ai
        }
        if (words == null) {
            note.visibility = View.GONE
        } else {
            note.visibility = View.VISIBLE
            note.text = getString(words) +
                if (problem != null && problem != AiProblem.LIMIT && problem != AiProblem.NO_INTERNET) " ($problem)" else ""
        }
    }

    companion object {
        private const val DAY_MILLIS = 24 * 60 * 60_000L
        private const val DEFAULT_DAYS = 14

        private val PERIODS = intArrayOf(R.id.period7, R.id.period14, R.id.period30, R.id.period90)
        private val PERIOD_DAYS = intArrayOf(7, 14, 30, 90)

        /** The language the screen answers in, for tests. */
        internal fun advisoryBlocks(values: List<com.glucosescan.app.core.Glucose>, from: Long, to: Long, low: Int, high: Int, unit: GlucoseUnit, language: AnswerLanguage): List<AdvisorBlock> =
            Advisor.tell(Advisor.facts(values, from, to, low, high, unit, TimeZone.getDefault()), language)
    }
}
