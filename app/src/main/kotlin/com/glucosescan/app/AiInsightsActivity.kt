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
import com.glucosescan.app.ai.AiAnswer
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

    /** True while an answer is on its way, so that pressing twice does not ask twice. */
    @Volatile
    private var busy = false

    /** Runs the question that waits for the internet. Tests replace it with one that runs at once. */
    private var background: java.util.concurrent.Executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "ai-question").apply { start() }
    }

    /**
     * The questions that are ready to press, in the order they are shown.
     *
     * The first seven are the ones that were there from the start; the rest were added so that
     * the things people actually ask are one press away instead of having to be typed. Every one
     * of them is answered from the readings of the stretch chosen above, and none of them is
     * answered without them.
     */
    private val readyQuestions = listOf(
        // the seven that were there first
        R.string.q_overall,
        R.string.q_lows,
        R.string.q_highs,
        R.string.q_night,
        R.string.q_pattern,
        R.string.q_compare,
        R.string.q_doctor,
        // the numbers themselves
        R.string.q_average,
        R.string.q_highest,
        R.string.q_lowest,
        R.string.q_time_in_range,
        R.string.q_time_outside,
        R.string.q_key_findings,
        // when, and why
        R.string.q_when_low,
        R.string.q_when_high,
        R.string.q_why_fall,
        R.string.q_why_rise,
        R.string.q_early_morning,
        R.string.q_steadiest_hours,
        R.string.q_wildest_hours,
        R.string.q_common_patterns,
        // how often, and is it changing
        R.string.q_hypo_how_often,
        R.string.q_hyper_how_often,
        R.string.q_swings,
        R.string.q_compare_7,
        R.string.q_compare_30,
        R.string.q_compare_90,
        // right now, and what to do
        R.string.q_trend_now,
        R.string.q_forecast_15,
        R.string.q_forecast_30,
        R.string.q_risk_soon,
        R.string.q_what_to_do_falling,
        R.string.q_what_to_do_rising,
        R.string.q_events_to_watch
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

    /**
     * The reading from the phone: the same engine as the Analysis screen, over [days] days.
     *
     * It is counted off the main thread. Ninety days is a hundred thousand measurements and
     * more; on the main thread that is a screen that stops answering, which is what a person
     * sees as a crash.
     */
    private fun renderReading(now: Long) {
        val box = findViewById<LinearLayout>(R.id.readingBox)
        val note = findViewById<TextView>(R.id.periodNote)
        box.removeAllViews()
        note.text = getString(R.string.insights_days, days)
        add(box, getString(R.string.insights_counting))

        val days = this.days
        val unit = settings.unit ?: GlucoseUnit.MGDL
        val language = settings.answerLanguage
        val low = settings.lowLimit
        val high = settings.highLimit
        val readings = store

        background.execute {
            val result = try {
                val from = AppClock.now() - days * DAY_MILLIS
                val values = readings.since(from)
                if (values.isEmpty()) {
                    Reading(null, getString(R.string.insights_no_values), emptyList())
                } else {
                    val facts = Advisor.facts(values, from, AppClock.now(), low, high, unit, TimeZone.getDefault())
                    Reading(values.size, null, Advisor.tell(facts, language))
                }
            } catch (e: Exception) {
                Reading(null, getString(R.string.insights_no_values), emptyList())
            }
            handler.post {
                if (isFinishing || isDestroyed) {
                    return@post
                }
                box.removeAllViews()
                if (result.empty != null) {
                    note.text = result.empty
                    add(box, result.empty)
                    return@post
                }
                note.text = getString(R.string.insights_days, days) + " · " + result.size + " values"
                for (block in result.blocks) {
                    addTitle(box, block.title)
                    for (line in block.lines) {
                        add(box, line)
                    }
                }
            }
        }
    }

    /** What the background found for the reading: how many values, and what the phone makes of them. */
    private class Reading(
        val size: Int?,
        /** Set when there is nothing to read. */
        val empty: String?,
        val blocks: List<AdvisorBlock>
    )

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
        // One question at a time. Without this guard every extra tap started another walk
        // through three months of values, and two or three of those at once were enough to
        // run the phone out of memory.
        if (busy) {
            return
        }
        busy = true
        setAskEnabled(false)

        val days = this.days
        val unit = settings.unit ?: GlucoseUnit.MGDL
        val language = settings.answerLanguage
        val low = settings.lowLimit
        val high = settings.highLimit
        val fromLinkUp = settings.linkUpEnabled
        val readings = store
        val asked = history.toList()

        showThinking()
        background.execute {
            // Nothing in here may reach the person as a crash, and none of it runs on the main
            // thread: walking three months of measurements is far too much work for a screen.
            val answer = try {
                val now = AppClock.now()
                val from = now - days * DAY_MILLIS
                val values = readings.since(from)
                if (values.isEmpty()) {
                    AiAnswer("", null, "", AiSource.PHONE)
                } else {
                    val zone = TimeZone.getDefault()
                    val analysis = AnalysisCalculator.compute(values, from, now, low, high, zone)
                    val hours = AnalysisCalculator.hours(values, from, now, zone)
                    val report = AiReport.build(
                        analysis, hours, emptyList(), unit, low, high, language, zone, fromLinkUp
                    )
                    val request = AiQuestion(
                        report = report,
                        question = question,
                        language = language,
                        history = asked,
                        fallback = Advisor.tell(Advisor.facts(values, from, now, low, high, unit, zone), language)
                    )
                    WalhadAi(Net.transport, install = settings.installId).ask(request)
                }
            } catch (e: Exception) {
                AiAnswer("", AiProblem.SERVER, Net.words(e), AiSource.PHONE)
            }
            handler.post {
                busy = false
                if (isFinishing || isDestroyed) {
                    return@post
                }
                setAskEnabled(true)
                if (answer.text.isEmpty()) {
                    showAnswer(getString(R.string.insights_no_values), AiSource.PHONE, answer.problem)
                    return@post
                }
                if (answer.source == AiSource.AI) {
                    history.add(AiTurn(question, answer.text))
                }
                showAnswer(answer.text, answer.source, answer.problem)
            }
        }
    }

    /** The button and the box are switched off while an answer is on its way. */
    private fun setAskEnabled(enabled: Boolean) {
        findViewById<View>(R.id.askButton).isEnabled = enabled
        findViewById<View>(R.id.ownQuestion).isEnabled = enabled
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
