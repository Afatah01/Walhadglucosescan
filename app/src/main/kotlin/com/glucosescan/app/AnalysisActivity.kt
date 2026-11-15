package com.glucosescan.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.glucosescan.app.core.AiReport
import com.glucosescan.app.core.Analysis
import com.glucosescan.app.core.AnalysisCalculator
import com.glucosescan.app.core.AnswerLanguage
import com.glucosescan.app.core.DayPart
import com.glucosescan.app.core.FindingLevel
import com.glucosescan.app.core.Advisor
import com.glucosescan.app.core.Forecaster
import com.glucosescan.app.core.Goal
import com.glucosescan.app.core.GoalKind
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.Span
import com.glucosescan.app.core.StatisticsCalculator
import com.glucosescan.app.core.Verdict
import com.glucosescan.app.ai.AiAnswer
import com.glucosescan.app.ai.AiClient
import com.glucosescan.app.ai.AiConfig
import com.glucosescan.app.ai.AiProblem
import com.glucosescan.app.ai.AiPrompt
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.net.Net
import com.glucosescan.app.data.Settings
import com.glucosescan.app.live.LiveBus
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.CurrentValues
import com.glucosescan.app.ui.FindingTexts
import com.glucosescan.app.ui.GlucoseFormat
import com.glucosescan.app.ui.RangeBarView
import com.glucosescan.app.ui.ReportWords
import com.glucosescan.app.ui.Texts

/**
 * Says how the glucose was: right now and where it heads, the last hours in words and hour by
 * hour, the last days day by day, and what stands out over the last days. Everything is
 * counted on the phone by fixed rules. "Ask an AI" hands the numbers to an AI app of the user.
 */
class AnalysisActivity : BaseActivity() {
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private var ready = false
    private var textShown = false
    private val handler = Handler(Looper.getMainLooper())

    /** True while a question to the AI service is on its way. */
    internal var asking = false
        private set

    /** What went wrong with the last question to the AI service, in words; "" if nothing. */
    internal var aiProblem = ""
        private set

    /** A new live value came in: the part "right now" is shown anew. */
    private val refresh = Runnable {
        if (ready && !isDestroyed) {
            renderNow(AppClock.now(), settings.unit ?: GlucoseUnit.MGDL)
        }
    }

    /** What the screen shows now. For tests. */
    internal var lastAnalysis: Analysis? = null
        private set
    internal var lastSpan: Span? = null
        private set

    /** The text for an AI app, as the screen would send it now. */
    internal var aiText: String = ""
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!noticeAccepted()) {
            return
        }
        settings = Settings(this)
        store = GlucoseStore.get(this)
        setContentWithBar(R.layout.activity_analysis, Tab.ANALYSIS)
        for ((id, days) in DAYS) {
            findViewById<View>(id).setOnClickListener {
                settings.statisticsDays = days
                render()
            }
        }
        for ((id, hours) in HOURS) {
            findViewById<View>(id).setOnClickListener {
                settings.analysisHours = hours
                render()
            }
        }
        for ((id, language) in LANGUAGES) {
            findViewById<View>(id).setOnClickListener {
                settings.answerLanguage = language
                render()
            }
        }
        findViewById<View>(R.id.aiAsk).setOnClickListener { askAi() }
        findViewById<TextView>(R.id.analysisFooter).setText(if (settings.linkUpEnabled) R.string.analysis_footer_linkup else R.string.analysis_footer)
        findViewById<View>(R.id.aiSend).setOnClickListener { send() }
        findViewById<View>(R.id.aiCopy).setOnClickListener { copy() }
        findViewById<View>(R.id.aiShow).setOnClickListener {
            textShown = !textShown
            render()
        }
        findViewById<View>(R.id.openReport).setOnClickListener {
            // like the bar at the bottom: the Report screen takes the place of this one
            startActivity(Intent(this, ReportActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.openStatistics).setOnClickListener { startActivity(Intent(this, StatisticsActivity::class.java)) }
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
        if (ready) {
            LiveBus.remove(refresh)
        }
    }

    private fun render() {
        val now = AppClock.now()
        val unit = settings.unit ?: GlucoseUnit.MGDL
        renderNow(now, unit)
        renderHours(now, unit)
        renderDays(now, unit)
        renderPeriod(now, unit)
    }

    // ---------------------------------------------------------------- right now

    private fun renderNow(now: Long, unit: GlucoseUnit) {
        val current = CurrentValues.read(store, settings, now)
        val value = findViewById<TextView>(R.id.nowValue)
        val warning = findViewById<TextView>(R.id.nowWarning)
        val note = findViewById<TextView>(R.id.nowNote)
        val box = findViewById<LinearLayout>(R.id.nowBox)
        box.removeAllViews()
        warning.visibility = View.GONE
        note.visibility = View.GONE

        val shown = current.shown
        if (shown == null || !current.isCurrent) {
            value.text = getString(R.string.analysis_now_none)
            return
        }
        val trend = current.state.trend
        val words = Texts.valueWithUnit(this, shown, unit)
        val headsLow = current.fallsTowardsLow(now)
        value.text = if (trend != null) getString(R.string.analysis_now_value, words, Texts.trendWord(this, trend, headsLow)) else words

        note.visibility = View.VISIBLE
        val forecast = current.forecast
        if (forecast == null) {
            note.text = getString(R.string.analysis_now_no_forecast)
            return
        }
        addRow(box, getString(R.string.analysis_forecast_15), FindingTexts.forecast(this, forecast.valueAt(now + 15 * MINUTE), unit))
        addRow(box, getString(R.string.analysis_forecast_30), FindingTexts.forecast(this, forecast.valueAt(now + Forecaster.HORIZON_MINUTES * MINUTE), unit))
        val minutes = current.minutesUntilBelowTarget(now)
        if (minutes != null) {
            warning.text = Texts.unbroken(resources.getQuantityString(R.plurals.outlook_low_soon, minutes, FindingTexts.glucose(settings.lowLimit, unit), minutes))
            warning.visibility = View.VISIBLE
        }
        note.text = getString(R.string.outlook_note, forecast.spanMinutes)
    }

    // ---------------------------------------------------------------- the last hours

    private fun renderHours(now: Long, unit: GlucoseUnit) {
        val hours = settings.analysisHours
        for ((id, value) in HOURS) {
            findViewById<View>(id).isSelected = value == hours
        }
        val to = now + MINUTE
        val from = to - hours * HOUR
        val values = settings.correction.applyAll(store.between(from, to))
        val span = AnalysisCalculator.span(values, from, to, settings.lowLimit, settings.highLimit)
        lastSpan = span

        // in words
        val words = findViewById<LinearLayout>(R.id.hoursWords)
        words.removeAllViews()
        val verdict = Verdict.of(span)
        if (verdict == null) {
            addSentence(words, getString(R.string.analysis_hours_none))
        } else {
            addFinding(words, verdict.level, ReportWords.verdict(verdict, unit))
            for (sentence in ReportWords.span(this, span, unit, ended = false)) {
                addSentence(words, sentence)
            }
        }

        // hour by hour, the newest hour first
        val box = findViewById<LinearLayout>(R.id.hoursBox)
        box.removeAllViews()
        for (hour in AnalysisCalculator.hours(values, from, to).reversed()) {
            val row = LayoutInflater.from(this).inflate(R.layout.row_range, box, false)
            row.findViewById<TextView>(R.id.label).text = Texts.timeOfDay(this, Math.max(hour.start, from))
            val text = row.findViewById<TextView>(R.id.text)
            val patch = row.findViewById<View>(R.id.patch)
            val mean = hour.mean
            val low = hour.lowest
            val high = hour.highest
            if (mean == null || low == null || high == null) {
                text.text = getString(R.string.analysis_hour_none)
                text.setTextColor(getColor(R.color.text_secondary))
                patch.setBackgroundColor(getColor(R.color.chip))
            } else {
                text.text = getString(R.string.analysis_hour_line, FindingTexts.glucose(mean, unit), GlucoseFormat.value(low, unit), GlucoseFormat.value(high, unit))
                patch.setBackgroundColor(getColor(RangeBarView.COLORS[StatisticsCalculator.rangeOf(mean)]))
            }
            box.addView(row)
        }
    }

    // ---------------------------------------------------------------- day by day

    private fun renderDays(now: Long, unit: GlucoseUnit) {
        val to = now + MINUTE
        val today = AnalysisCalculator.dayStart(now)
        val from = AnalysisCalculator.addDays(today, -(DAYS_SHOWN - 1))
        val values = settings.correction.applyAll(store.between(from, to))
        val box = findViewById<LinearLayout>(R.id.daysBox)
        box.removeAllViews()
        for (day in AnalysisCalculator.days(values, now, DAYS_SHOWN, to, settings.lowLimit, settings.highLimit)) {
            val row = LayoutInflater.from(this).inflate(R.layout.row_part, box, false)
            row.findViewById<TextView>(R.id.label).text = dayName(this, day.start, today)
            val text = row.findViewById<TextView>(R.id.text)
            val bar = row.findViewById<RangeBarView>(R.id.bar)
            val stats = day.stats
            val mean = stats.mean
            val low = stats.lowest
            val high = stats.highest
            if (mean == null || low == null || high == null) {
                text.text = getString(R.string.analysis_day_none)
                bar.visibility = View.GONE
            } else {
                var line = getString(
                    R.string.analysis_day_line,
                    FindingTexts.glucose(mean, unit),
                    day.inRangePercent.toString() + " %",
                    GlucoseFormat.value(low, unit),
                    GlucoseFormat.value(high, unit)
                )
                if (day.lows > 0) {
                    line = resources.getQuantityString(R.plurals.analysis_day_lows, day.lows, line, day.lows)
                }
                text.text = line
                bar.show(stats.rangePercents)
            }
            box.addView(row)
        }
        findViewById<TextView>(R.id.daysNote).text = getString(
            R.string.analysis_days_note,
            GlucoseFormat.value(StatisticsCalculator.LOW_BELOW, unit),
            FindingTexts.glucose(StatisticsCalculator.HIGH_ABOVE, unit)
        )
    }

    // ---------------------------------------------------------------- the last days: what stands out

    private fun renderPeriod(now: Long, unit: GlucoseUnit) {
        val days = settings.statisticsDays
        for ((id, value) in DAYS) {
            findViewById<View>(id).isSelected = value == days
        }
        val low = settings.lowLimit
        val high = settings.highLimit
        val to = now + MINUTE
        val from = to - days * StatisticsCalculator.DAY
        // the period before is read too, for "more (or less) time in range than before"
        val values = settings.correction.applyAll(store.between(from - days * StatisticsCalculator.DAY, to))
        val analysis = AnalysisCalculator.compute(values, from, to, low, high)
        lastAnalysis = analysis

        findViewById<TextView>(R.id.analysisPeriodLine).text = getString(R.string.stats_period, days, Texts.dateTime(this, from), Texts.dateTime(this, now))
        val enough = analysis.stats.slots >= AnalysisCalculator.MIN_QUARTERS
        findViewById<View>(R.id.analysisEmptyCard).visibility = if (enough) View.GONE else View.VISIBLE
        findViewById<View>(R.id.analysisContent).visibility = if (enough) View.VISIBLE else View.GONE
        if (!enough) {
            aiText = ""
            return
        }

        val findings = findViewById<LinearLayout>(R.id.findingsBox)
        findings.removeAllViews()
        for (finding in analysis.findings) {
            addFinding(findings, finding.level, FindingTexts.text(this, finding, unit, days))
        }

        val goals = findViewById<LinearLayout>(R.id.goalsBox)
        goals.removeAllViews()
        for (goal in analysis.goals) {
            addGoal(goals, goal, unit)
        }

        val parts = findViewById<LinearLayout>(R.id.partsBox)
        parts.removeAllViews()
        for (part in analysis.parts) {
            val row = LayoutInflater.from(this).inflate(R.layout.row_part, parts, false)
            row.findViewById<TextView>(R.id.label).text = getString(
                when (part.part) {
                    DayPart.NIGHT -> R.string.part_night
                    DayPart.MORNING -> R.string.part_morning
                    DayPart.AFTERNOON -> R.string.part_afternoon
                    DayPart.EVENING -> R.string.part_evening
                }
            )
            val text = row.findViewById<TextView>(R.id.text)
            val bar = row.findViewById<RangeBarView>(R.id.bar)
            val mean = part.mean
            if (mean == null) {
                text.text = getString(R.string.part_no_values)
                bar.visibility = View.GONE
            } else {
                var line = getString(R.string.part_values, FindingTexts.glucose(mean, unit), part.belowPercent.toString() + " %", part.abovePercent.toString() + " %")
                if (part.lowEvents > 0) {
                    line = resources.getQuantityString(R.plurals.part_low_events, part.lowEvents, line, part.lowEvents)
                }
                text.text = line
                bar.show(intArrayOf(0, part.below, part.inRange, part.above, 0))
            }
            parts.addView(row)
        }
        findViewById<TextView>(R.id.partsNote).text = getString(
            R.string.analysis_parts_note,
            FindingTexts.glucose(StatisticsCalculator.LOW_BELOW, unit),
            FindingTexts.glucose(StatisticsCalculator.HIGH_ABOVE, unit)
        )

        val lows = findViewById<LinearLayout>(R.id.lowsBox)
        lows.removeAllViews()
        if (analysis.lows.isEmpty()) {
            addSentence(lows, getString(R.string.analysis_lows_none))
        } else {
            for (episode in analysis.lows.takeLast(LOWS_SHOWN).reversed()) {
                addSentence(
                    lows,
                    getString(
                        R.string.analysis_low_line,
                        Texts.timeOrDateTime(this, episode.start, now),
                        GlucoseFormat.duration(episode.minutes),
                        ReportWords.value(Math.round(episode.extreme).toInt(), unit)
                    )
                )
            }
            val more = analysis.lows.size - LOWS_SHOWN
            if (more > 0) {
                addSentence(lows, resources.getQuantityString(R.plurals.analysis_lows_more, more, more))
            }
        }
        findViewById<TextView>(R.id.lowsNote).text = getString(R.string.analysis_lows_note, FindingTexts.glucose(StatisticsCalculator.LOW_BELOW, unit))

        // what the app itself says about the period: always there, in the language below
        val language = settings.answerLanguage
        showAdvisor(values, from, to, low, high, unit, language)

        // the text for an AI app
        for ((id, value) in LANGUAGES) {
            findViewById<View>(id).isSelected = value == language
        }
        val lastDay = AnalysisCalculator.hours(values, to - StatisticsCalculator.DAY, to)
        val dayList = AnalysisCalculator.days(values, now, Math.min(days, AI_DAYS), to, low, high)
        aiText = AiReport.build(analysis, lastDay, dayList, unit, low, high, language, fromLinkUp = settings.linkUpEnabled)
        val report = findViewById<TextView>(R.id.aiReport)
        report.text = aiText
        report.visibility = if (textShown) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.aiShow).setText(if (textShown) R.string.ai_hide else R.string.ai_show)
        renderAi(now)
    }

    /** The button that asks the AI, and its last answer. */
    private fun renderAi(now: Long) {
        val ask = findViewById<Button>(R.id.aiAsk)
        val ready = settings.aiReady
        ask.setText(if (ready) R.string.ai_ask else R.string.ai_set_up)
        ask.isEnabled = !asking
        findViewById<View>(R.id.aiQuestion).visibility = if (ready) View.VISIBLE else View.GONE

        val state = findViewById<TextView>(R.id.aiAskState)
        when {
            asking -> {
                state.setText(R.string.ai_asking)
                state.setTextColor(getColor(R.color.text_secondary))
                state.visibility = View.VISIBLE
            }
            aiProblem.isNotEmpty() -> {
                state.text = aiProblem
                state.setTextColor(getColor(R.color.red))
                state.visibility = View.VISIBLE
            }
            else -> state.visibility = View.GONE
        }

        val answer = settings.aiAnswer
        val box = findViewById<View>(R.id.aiAnswerBox)
        if (answer.isEmpty()) {
            box.visibility = View.GONE
        } else {
            findViewById<TextView>(R.id.aiAnswer).text = answer
            findViewById<TextView>(R.id.aiAnswerMeta).text =
                getString(R.string.ai_answer_meta, Texts.timeOrDateTime(this, settings.aiAnswerTime, now), settings.aiAnswerModel)
            box.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- ask an AI

    /**
     * Sends the text to the AI service the user has set up, and shows the answer. Without a
     * key the button opens the screen where the service is set up.
     */
    private fun askAi() {
        if (!settings.aiReady) {
            startActivity(Intent(this, AiSetupActivity::class.java))
            return
        }
        if (asking || aiText.isEmpty()) {
            return
        }
        val config = AiConfig(settings.aiProvider, settings.aiUrlInUse, settings.aiKey, settings.aiModelInUse)
        val system = AiPrompt.system(settings.answerLanguage)
        val user = AiPrompt.user(aiText, findViewById<EditText>(R.id.aiQuestion).text.toString())
        val transport = Net.transport
        asking = true
        aiProblem = ""
        renderAi(AppClock.now())
        Net.patient.execute {
            val answer = try {
                AiClient(transport).ask(config, system, user)
            } catch (e: Exception) {
                AiAnswer("", AiProblem.SERVER, Net.words(e))
            }
            handler.post {
                asking = false
                val problem = answer.problem
                if (problem == null) {
                    settings.aiAnswer = answer.text
                    settings.aiAnswerTime = AppClock.now()
                    settings.aiAnswerModel = config.model
                } else {
                    aiProblem = AiWords.problem(this, problem, answer.detail)
                }
                if (!isDestroyed) {
                    renderAi(AppClock.now())
                }
            }
        }
    }

    /** Hands the text to an app the user chooses. The app itself sends nothing anywhere. */
    private fun send() {
        if (aiText.isEmpty()) {
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ai_subject))
            .putExtra(Intent.EXTRA_TEXT, aiText)
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.ai_chooser)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.ai_no_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun copy() {
        if (aiText.isEmpty()) {
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ai_subject), aiText))
        Toast.makeText(this, R.string.ai_copied, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- rows

    private fun addRow(box: LinearLayout, label: String, text: String) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_label_value, box, false)
        row.findViewById<TextView>(R.id.label).text = label
        row.findViewById<TextView>(R.id.text).text = text
        box.addView(row)
    }

    private fun addSentence(box: LinearLayout, words: String) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_sentence, box, false)
        row.findViewById<TextView>(R.id.text).text = Texts.unbroken(words)
        box.addView(row)
    }

    /**
     * Writes what the app says about the period into the card above the AI. The sentences
     * are made by [Advisor] from the values on the phone: no internet, no key, no setup.
     */
    private fun showAdvisor(values: List<Glucose>, from: Long, to: Long, low: Int, high: Int, unit: GlucoseUnit, language: AnswerLanguage) {
        val box = findViewById<LinearLayout>(R.id.advisorBox)
        box.removeAllViews()
        val facts = Advisor.facts(values, from, to, low, high, unit)
        for (block in Advisor.tell(facts, language)) {
            box.addView(
                TextView(this).apply {
                    setTextAppearance(R.style.AdvisorTitle)
                    text = block.title
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = (12 * resources.displayMetrics.density).toInt()
                    }
                }
            )
            for (line in block.lines) {
                box.addView(
                    TextView(this@AnalysisActivity).apply {
                        setTextAppearance(R.style.AdvisorLine)
                        text = line
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = (4 * resources.displayMetrics.density).toInt()
                        }
                    }
                )
            }
        }
    }

    private fun addFinding(box: LinearLayout, level: FindingLevel, words: String) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_finding, box, false)
        val levelView = row.findViewById<TextView>(R.id.level)
        levelView.text = FindingTexts.levelWord(this, level)
        levelView.setTextColor(getColor(FindingTexts.levelColor(level)))
        levelView.setBackgroundResource(FindingTexts.levelBackground(level))
        row.findViewById<TextView>(R.id.text).text = Texts.unbroken(words)
        box.addView(row)
    }

    private fun addGoal(box: LinearLayout, goal: Goal, unit: GlucoseUnit) {
        fun whole(percent: Int): String = "$percent %"
        val row = LayoutInflater.from(this).inflate(R.layout.row_goal, box, false)
        val label = when (goal.kind) {
            GoalKind.IN_RANGE -> getString(
                R.string.goal_in_range,
                GlucoseFormat.value(StatisticsCalculator.LOW_BELOW, unit),
                FindingTexts.glucose(StatisticsCalculator.HIGH_ABOVE, unit),
                whole(goal.limit)
            )
            GoalKind.LOW -> getString(R.string.goal_under, FindingTexts.glucose(StatisticsCalculator.LOW_BELOW, unit), whole(goal.limit))
            GoalKind.VERY_LOW -> getString(R.string.goal_under, FindingTexts.glucose(StatisticsCalculator.VERY_LOW_BELOW, unit), whole(goal.limit))
            GoalKind.HIGH -> getString(R.string.goal_over, FindingTexts.glucose(StatisticsCalculator.HIGH_ABOVE, unit), whole(goal.limit))
            GoalKind.VERY_HIGH -> getString(R.string.goal_over, FindingTexts.glucose(StatisticsCalculator.VERY_HIGH_ABOVE, unit), whole(goal.limit))
            GoalKind.VARIATION -> getString(R.string.goal_variation, whole(goal.limit))
        }
        val measured = if (goal.kind == GoalKind.VARIATION) whole(Math.round(goal.percent).toInt()) else FindingTexts.percent(goal.percent)
        val color = getColor(if (goal.met) R.color.green else R.color.red)
        row.findViewById<TextView>(R.id.label).text = label
        val text = row.findViewById<TextView>(R.id.text)
        text.text = getString(R.string.day_with_word, measured, getString(if (goal.met) R.string.goal_met else R.string.goal_not_met))
        text.setTextColor(color)
        val mark = row.findViewById<ImageView>(R.id.mark)
        mark.setImageResource(if (goal.met) R.drawable.ic_check else R.drawable.ic_cross)
        mark.setColorFilter(color)
        box.addView(row)
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE

        /** How many days "day by day" shows, how many low events are listed, how many days go into the text for an AI. */
        const val DAYS_SHOWN = 7
        private const val LOWS_SHOWN = 5
        private const val AI_DAYS = 14

        private val DAYS = mapOf(R.id.days1 to 1, R.id.days7 to 7, R.id.days14 to 14, R.id.days30 to 30, R.id.days90 to 90)
        private val HOURS = mapOf(R.id.hours3 to 3, R.id.hours6 to 6, R.id.hours12 to 12, R.id.hours24 to 24)
        private val LANGUAGES = mapOf(R.id.aiEnglish to AnswerLanguage.ENGLISH, R.id.aiSomali to AnswerLanguage.SOMALI, R.id.aiArabic to AnswerLanguage.ARABIC)

        /** "Today · Tue, Nov 10, 2026", "Yesterday · …", or the day alone. */
        fun dayName(context: Context, dayStart: Long, today: Long): String {
            val day = Texts.day(context, dayStart)
            return when (dayStart) {
                today -> context.getString(R.string.day_with_word, context.getString(R.string.today), day)
                AnalysisCalculator.addDays(today, -1) -> context.getString(R.string.day_with_word, context.getString(R.string.yesterday), day)
                else -> day
            }
        }
    }
}
