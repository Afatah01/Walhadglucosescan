package com.glucosescan.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.glucosescan.app.core.Analysis
import com.glucosescan.app.core.AnalysisCalculator
import com.glucosescan.app.core.FindingLevel
import com.glucosescan.app.core.Forecaster
import com.glucosescan.app.core.OutlookRisk
import com.glucosescan.app.core.Outlooks
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.ScanStatus
import com.glucosescan.app.core.StatisticsCalculator
import com.glucosescan.app.core.Verdict
import com.glucosescan.app.cloud.Backup
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LinkUpState
import com.glucosescan.app.data.ValueSource
import com.glucosescan.app.live.LinkUpWords
import com.glucosescan.app.data.LiveState
import com.glucosescan.app.data.Settings
import com.glucosescan.app.libre.SensorInfo
import com.glucosescan.app.live.LiveBus
import com.glucosescan.app.live.LiveNotifications
import com.glucosescan.app.live.LiveService
import com.glucosescan.app.nfc.ScanRunner
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.AppFont
import com.glucosescan.app.ui.ChartView
import com.glucosescan.app.ui.CurrentValue
import com.glucosescan.app.ui.CurrentValues
import com.glucosescan.app.ui.DisplayState
import com.glucosescan.app.ui.FindingTexts
import com.glucosescan.app.ui.GlucoseFormat
import com.glucosescan.app.ui.PulseDotView
import com.glucosescan.app.ui.ReportWords
import com.glucosescan.app.ui.ScaleView
import com.glucosescan.app.ui.Texts

/**
 * The main screen: the value, where it heads, the chart, the numbers of the last 24 hours, a
 * short analysis and the sensor. While it is open, the phone scans.
 */
class MainActivity : Activity(), NfcAdapter.ReaderCallback {
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private var nfcAdapter: NfcAdapter? = null
    private var ready = false
    private var resumed = false

    private val handler = Handler(Looper.getMainLooper())

    /** Shows the screen anew when a scan starts or ends and when the live connection has news. */
    private val refresh = Runnable { render() }

    /** True while a sensor is read that opened the app (see [handleIntent]). */
    @Volatile
    private var intentScanPending = false

    /** Shows the screen again every 20 seconds, so that the age of the value stays right. */
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MILLIS)
        }
    }

    private val nfcStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateReaderMode()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        store = GlucoseStore.get(this)

        // The safety notice comes first.
        if (!settings.noticeAccepted || settings.unit == null) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        BottomBar.setContent(this, R.layout.activity_main, BaseActivity.Tab.HOME)
        ready = true
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val ranges = mapOf(R.id.range3 to 3, R.id.range6 to 6, R.id.range12 to 12, R.id.range24 to 24)
        for ((id, hours) in ranges) {
            findViewById<View>(id).setOnClickListener {
                settings.chartHours = hours
                render()
            }
        }
        findViewById<View>(R.id.liveCard).setOnClickListener {
            startActivity(Intent(this, if (settings.linkUpEnabled) LinkUpActivity::class.java else LiveActivity::class.java))
        }
        findViewById<View>(R.id.offerYes).setOnClickListener { startActivity(Intent(this, LinkUpActivity::class.java)) }
        findViewById<View>(R.id.offerBluetooth).setOnClickListener { startActivity(Intent(this, LiveActivity::class.java)) }
        findViewById<View>(R.id.offerNo).setOnClickListener {
            settings.liveOfferHidden = true
            render()
        }
        findViewById<View>(R.id.analysisCard).setOnClickListener { startActivity(Intent(this, AnalysisActivity::class.java)) }
        // the big number: the cut of the letters that is made for large sizes, digits of one width
        findViewById<TextView>(R.id.value).apply {
            typeface = AppFont.display(this@MainActivity)
            fontFeatureSettings = "tnum"
        }
        findViewById<View>(R.id.nfcSettings).setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
            }
        }

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ready) {
            handleIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ready) {
            return
        }
        resumed = true
        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            // only Android itself can send this broadcast, and it comes from the NFC part of Android
            registerReceiver(nfcStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(nfcStateReceiver, filter)
        }
        updateReaderMode()
        analysis = null // the settings may have changed on another screen
        LiveBus.add(refresh)
        if (settings.valuesComeByThemselves) {
            LiveService.start(this) // does nothing new if it runs already
        }
        Backup.request(this) // the online copy, if the user has one
        handler.removeCallbacks(tick)
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        if (!ready) {
            return
        }
        resumed = false
        findViewById<PulseDotView>(R.id.liveDot).pulsing = false
        LiveBus.remove(refresh)
        handler.removeCallbacks(tick)
        unregisterReceiver(nfcStateReceiver)
        try {
            nfcAdapter?.disableReaderMode(this)
        } catch (e: RuntimeException) {
            // NFC service is gone; nothing to switch off
        }
    }

    /** While the screen is open, the phone looks for NFC-V tags and gives them to this app. */
    private fun updateReaderMode() {
        val adapter = nfcAdapter ?: return
        // Switching this on makes the phone look for tags anew. That would break a read that
        // is going on, so it waits until that read is done.
        if (!resumed || !adapter.isEnabled || intentScanPending) {
            return
        }
        val options = Bundle()
        // do not ask "is the tag still there" while the memory is read
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000)
        try {
            adapter.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, options)
        } catch (e: RuntimeException) {
            // NFC service is gone; the screen shows that NFC is off
        }
    }

    /** The phone found a tag while this screen is open. Called on a background thread. */
    override fun onTagDiscovered(tag: Tag) {
        scan(tag)
    }

    /** The phone opened the app because it touched a sensor (only if switched on in the settings). */
    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != NfcAdapter.ACTION_TECH_DISCOVERED) {
            return
        }
        @Suppress("DEPRECATION")
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            intentScanPending = true
            Thread({
                try {
                    scan(tag)
                } finally {
                    intentScanPending = false
                    handler.post { updateReaderMode() }
                }
            }, "sensor-scan").start()
        }
    }

    /** Reads the sensor (see [ScanRunner]) and shows what came of it. */
    private fun scan(tag: Tag) {
        ScanRunner.scan(this, tag)
        handler.post { render() }
    }

    // ---------------------------------------------------------------- showing

    private fun render() {
        if (!ready || isDestroyed) {
            return
        }
        val now = AppClock.now()
        val unit = settings.unit ?: GlucoseUnit.MGDL
        val current = CurrentValues.read(store, settings, now)
        renderValue(now, unit, current.fallsTowardsLow(now))
        renderScale(current, unit)
        renderOutlook(current, now, unit)
        renderLiveSign(current)
        renderScanStatus(now)
        renderLive()
        renderOffer()
        renderChart(now, unit, current)
        renderDay(now, unit)
        renderAnalysis(now, unit)
        renderSensor(now)
    }

    // ---------------------------------------------------------------- new in version 3

    /** The line under the big value: where the value lies between low and high. */
    private fun renderScale(current: CurrentValue, unit: GlucoseUnit) {
        val scale = findViewById<ScaleView>(R.id.scale)
        val shown = current.shown
        if (shown == null || !current.state.showsValue) {
            scale.visibility = View.GONE
            return
        }
        scale.show(shown.readableValue, settings.lowLimit, settings.highLimit, unit)
        scale.visibility = View.VISIBLE
    }

    /** True if the card "Next 30 minutes" warns that the value may go below the target range. For tests. */
    internal var warnsOfLow = false
        private set

    /**
     * Where the value heads in the next half hour (see [Forecaster]). Only with a current
     * value and enough values before it. The card turns yellow, with a sentence in bold, when
     * the forecast goes below the target range within 20 minutes.
     */
    private fun renderOutlook(current: CurrentValue, now: Long, unit: GlucoseUnit) {
        val card = findViewById<View>(R.id.outlookCard)
        val forecast = current.forecast
        warnsOfLow = false
        if (forecast == null || !current.isCurrent) {
            card.visibility = View.GONE
            return
        }
        val at = now + Forecaster.HORIZON_MINUTES * 60_000L
        findViewById<TextView>(R.id.outlookText).text = Texts.unbroken(getString(R.string.outlook_line, FindingTexts.forecast(this, forecast.valueAt(at), unit), Texts.timeOfDay(this, at)))
        findViewById<TextView>(R.id.outlookNote).text = getString(R.string.outlook_note, forecast.spanMinutes)

        val minutes = current.minutesUntilBelowTarget(now)
        val warning = findViewById<TextView>(R.id.outlookWarning)
        val title = findViewById<TextView>(R.id.outlookTitle)
        if (minutes != null) {
            warnsOfLow = true
            warning.text = Texts.unbroken(resources.getQuantityString(R.plurals.outlook_low_soon, minutes, FindingTexts.glucose(settings.lowLimit, unit), minutes))
            warning.visibility = View.VISIBLE
            card.setBackgroundResource(R.drawable.card_notice)
            title.setTextColor(getColor(R.color.notice_title))
            title.compoundDrawableTintList = getColorStateList(R.color.notice_title)
        } else {
            warning.visibility = View.GONE
            card.setBackgroundResource(R.drawable.card_background)
            title.setTextColor(getColor(R.color.text))
            title.compoundDrawableTintList = getColorStateList(R.color.blue)
        }
        card.visibility = View.VISIBLE

        // what to do about it, said on the phone, without any network
        val outlook = Outlooks.of(forecast, now, current.shown?.readableValue ?: 0, settings.lowLimit, settings.highLimit)
        val advice = findViewById<TextView>(R.id.outlookAdvice)
        if (outlook == null) {
            advice.visibility = View.GONE
        } else {
            advice.visibility = View.VISIBLE
            advice.text = Texts.unbroken(
                when (outlook.risk) {
                    OutlookRisk.SEVERE_LOW -> getString(R.string.outlook_advice_severe_low)
                    OutlookRisk.LOW -> getString(R.string.outlook_advice_low)
                    OutlookRisk.LOW_SOON -> if (minutes == null) getString(R.string.outlook_advice_none) else getString(R.string.outlook_advice_low_soon, minutes, FindingTexts.glucose(settings.lowLimit, unit))
                    OutlookRisk.VERY_HIGH -> getString(R.string.outlook_advice_very_high)
                    OutlookRisk.HIGH -> getString(R.string.outlook_advice_high)
                    OutlookRisk.NONE -> getString(R.string.outlook_advice_none)
                }
            )
        }
    }

    /** What the sign at the top says. For tests. */
    internal enum class LiveSign { NONE, LIVE, WAITING, PROBLEM }

    internal var liveSign = LiveSign.NONE
        private set

    /**
     * The sign at the top right, only while live values are on: "LIVE" with a green dot that
     * pulses while values come in; "WAITING" with a yellow dot while the connection is
     * being set up or a value is late; "PROBLEM" with a red dot when the user must do
     * something. The words are short so that the name of the app keeps its line.
     */
    private fun renderLiveSign(current: CurrentValue) {
        val pill = findViewById<View>(R.id.livePill)
        val dot = findViewById<PulseDotView>(R.id.liveDot)
        val words = findViewById<TextView>(R.id.livePillText)
        if (!settings.valuesComeByThemselves) {
            liveSign = LiveSign.NONE
            dot.pulsing = false
            pill.visibility = View.GONE
            return
        }
        val fresh = current.isCurrent && current.state.ageMinutes <= 2
        liveSign = if (settings.linkUpEnabled) {
            val state = settings.linkUpState
            when {
                state.needsUser -> LiveSign.PROBLEM
                state == LinkUpState.RECEIVING && fresh -> LiveSign.LIVE
                else -> LiveSign.WAITING
            }
        } else {
            val state = settings.liveState
            when {
                LiveNotifications.isProblem(state) -> LiveSign.PROBLEM
                state == LiveState.RECEIVING && fresh -> LiveSign.LIVE
                else -> LiveSign.WAITING
            }
        }
        words.setText(
            when (liveSign) {
                LiveSign.LIVE -> R.string.pill_live
                LiveSign.PROBLEM -> R.string.pill_problem
                else -> R.string.pill_waiting
            }
        )
        dot.setColor(
            getColor(
                when (liveSign) {
                    LiveSign.LIVE -> R.color.dot_live
                    LiveSign.PROBLEM -> R.color.dot_problem
                    else -> R.color.dot_waiting
                }
            )
        )
        dot.pulsing = resumed && liveSign == LiveSign.LIVE
        pill.visibility = View.VISIBLE
    }

    /** The offer to let the values come by themselves, while they do not. */
    private fun renderOffer() {
        val show = !settings.valuesComeByThemselves && !settings.liveOfferHidden
        findViewById<View>(R.id.liveOffer).visibility = if (show) View.VISIBLE else View.GONE
        // LibreLinkUp needs the internet only; the other way needs a phone with Bluetooth
        val bluetooth = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        findViewById<View>(R.id.offerBluetooth).visibility = if (bluetooth) View.VISIBLE else View.GONE
    }

    /** Four numbers of the last 24 hours. */
    private fun renderDay(now: Long, unit: GlucoseUnit) {
        val to = now + 60_000L
        val from = to - StatisticsCalculator.DAY
        val values = settings.correction.applyAll(store.between(from, to))
        val stats = StatisticsCalculator.compute(values, from, to, settings.lowLimit, settings.highLimit)
        val none = getString(R.string.day_none)
        val mean = stats.mean
        val lowest = stats.lowest
        val highest = stats.highest
        findViewById<TextView>(R.id.tileInTarget).text = if (stats.hasValues) stats.inTargetPercent.toString() + " %" else none
        findViewById<TextView>(R.id.tileAverage).text = if (mean != null) FindingTexts.glucose(mean, unit) else none
        findViewById<TextView>(R.id.tileLowest).text = if (lowest != null) FindingTexts.glucose(lowest, unit) else none
        findViewById<TextView>(R.id.tileHighest).text = if (highest != null) FindingTexts.glucose(highest, unit) else none

        // five more, so that the screen answers the questions people actually ask
        val percents = stats.rangePercents
        findViewById<TextView>(R.id.tileTimeLow).text = if (stats.hasValues) (percents[0] + percents[1]).toString() + " %" else none
        findViewById<TextView>(R.id.tileTimeHigh).text = if (stats.hasValues) (percents[3] + percents[4]).toString() + " %" else none
        val cv = stats.cv
        findViewById<TextView>(R.id.tileVariation).text = if (cv != null) Math.round(cv).toString() + " %" else none
        val gmi = stats.gmi
        findViewById<TextView>(R.id.tileGmi).text = if (gmi != null) String.format(java.util.Locale.US, "%.1f %%", gmi) else none
        // an event is 15 minutes or longer below or above the range
        val span = AnalysisCalculator.span(values, from, to, settings.lowLimit, settings.highLimit)
        val events = if (stats.hasValues) span.lows.size.toString() + " / " + span.highs.size.toString() else none
        findViewById<TextView>(R.id.tileEvents).text = events
    }

    /** The analysis of the last days. It is counted anew every 5 minutes and after a scan. */
    private var analysis: Analysis? = null
    private var analysisKey = ""
    private var analysisTime = 0L

    private fun renderAnalysis(now: Long, unit: GlucoseUnit) {
        val days = settings.statisticsDays
        val to = now + 60_000L
        val key = days.toString() + "/" + (store.latest()?.timestamp ?: 0L) / ANALYSIS_MILLIS + "/" + settings.lowLimit + "/" + settings.highLimit
        var result = analysis
        if (result == null || key != analysisKey || now < analysisTime || now - analysisTime >= ANALYSIS_MILLIS) {
            val from = to - days * StatisticsCalculator.DAY
            val values = settings.correction.applyAll(store.between(from - days * StatisticsCalculator.DAY, to))
            result = AnalysisCalculator.compute(values, from, to, settings.lowLimit, settings.highLimit)
            analysis = result
            analysisKey = key
            analysisTime = now
        }

        val box = findViewById<LinearLayout>(R.id.analysisBox)
        box.removeAllViews()
        findViewById<TextView>(R.id.analysisPeriod).visibility = View.GONE

        // how the last 24 hours were, in one sentence
        val dayFrom = to - StatisticsCalculator.DAY
        val day = AnalysisCalculator.span(settings.correction.applyAll(store.between(dayFrom, to)), dayFrom, to, settings.lowLimit, settings.highLimit)
        val verdict = Verdict.of(day)
        if (verdict != null) {
            addFinding(box, getString(R.string.day_title), verdict.level, ReportWords.verdict(verdict, unit))
        }
        // what stands out most in the last days
        val first = result.findings.firstOrNull()
        val enough = result.stats.slots >= AnalysisCalculator.MIN_QUARTERS
        if (first != null && enough) {
            addFinding(box, getString(R.string.analysis_period, FindingTexts.days(this, days)), first.level, FindingTexts.text(this, first, unit, days))
        }
        findViewById<View>(R.id.analysisEmpty).visibility = if (enough) View.GONE else View.VISIBLE
    }

    private fun addFinding(box: LinearLayout, caption: String, level: FindingLevel, words: String) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_finding, box, false)
        val captionView = row.findViewById<TextView>(R.id.caption)
        captionView.text = caption
        captionView.visibility = View.VISIBLE
        val levelView = row.findViewById<TextView>(R.id.level)
        levelView.text = FindingTexts.levelWord(this, level)
        levelView.setTextColor(getColor(FindingTexts.levelColor(level)))
        levelView.setBackgroundResource(FindingTexts.levelBackground(level))
        row.findViewById<TextView>(R.id.text).text = Texts.unbroken(words)
        box.addView(row)
    }

    /**
     * The colour of the big card with the value. The colour never stands alone: the words on
     * the card say the same.
     */
    internal enum class ValueLook(val background: Int) {
        /** green: a current value inside the target range */
        IN_RANGE(R.drawable.hero_good),

        /** red: a current value outside the target range (also LOW and HIGH), or a problem */
        ALERT(R.drawable.hero_alert),

        /** blue: no current value */
        NO_VALUE(R.drawable.hero_none)
    }

    internal var valueLook: ValueLook = ValueLook.NO_VALUE
        private set

    private fun showValueLook(look: ValueLook) {
        valueLook = look
        findViewById<View>(R.id.valueCard).setBackgroundResource(look.background)
    }

    /**
     * Makes the big number and its arrow smaller when they would not fit side by side, for
     * example on a small phone with big text. The number is never cut and never broken.
     */
    private fun fitBigValue(value: TextView, arrow: TextView) {
        val metrics = resources.displayMetrics
        val valueSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, VALUE_TEXT_SP, metrics)
        val arrowSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ARROW_TEXT_SP, metrics)
        value.setTextSize(TypedValue.COMPLEX_UNIT_PX, valueSize)
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_PX, arrowSize)

        // the room inside the card; before the first layout the card has no width yet
        val card = findViewById<View>(R.id.valueCard)
        val page = card.parent as View
        var cardWidth = resources.configuration.screenWidthDp * metrics.density - page.paddingLeft - page.paddingRight
        if (card.width > 0) {
            cardWidth = Math.min(cardWidth, card.width.toFloat())
        }
        var room = cardWidth - card.paddingLeft - card.paddingRight
        var needed = value.paint.measureText(value.text.toString())
        if (arrow.visibility == View.VISIBLE) {
            needed += arrow.paint.measureText(arrow.text.toString())
            room -= (arrow.layoutParams as ViewGroup.MarginLayoutParams).marginStart // the gap keeps its size
        }
        if (room > 0 && needed > room) {
            val factor = room / needed * 0.97f
            value.setTextSize(TypedValue.COMPLEX_UNIT_PX, valueSize * factor)
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_PX, arrowSize * factor)
        }
    }

    /** @param headsLow true: the value falls slowly and is below the target range, or will be soon */
    private fun renderValue(now: Long, unit: GlucoseUnit, headsLow: Boolean) {
        val value = findViewById<TextView>(R.id.value)
        val rangeChip = findViewById<TextView>(R.id.rangeChip)
        val arrow = findViewById<TextView>(R.id.arrow)
        val unitView = findViewById<TextView>(R.id.unit)
        val note = findViewById<TextView>(R.id.valueNote)
        val trendLine = findViewById<TextView>(R.id.trendLine)
        val trendBasis = findViewById<TextView>(R.id.trendBasis)
        val valueTime = findViewById<TextView>(R.id.valueTime)
        val lastValue = findViewById<TextView>(R.id.lastValue)
        val correctedNote = findViewById<TextView>(R.id.correctedNote)

        // "latest" is the sensor value as the formula gives it. "glucose" is what is shown: the
        // same value, or the corrected one if the user has set up a correction for this sensor.
        val correction = settings.correction
        val latest = store.latest()
        val recent = if (latest != null) correction.applyAll(store.since(latest.timestamp - 20 * 60_000L)) else emptyList()
        val state = DisplayState.of(recent, now)
        val glucose = state.glucose
        val sensorNote = if (latest != null && correction.appliesTo(latest)) {
            getString(R.string.corrected_note, Texts.valueWithUnit(this, latest, unit))
        } else {
            null
        }

        for (view in listOf(rangeChip, arrow, unitView, note, correctedNote, trendLine, trendBasis, valueTime, lastValue)) {
            view.visibility = View.GONE
        }

        if (state.showsValue && glucose != null) {
            // A correction must never hide a low value: the card is also red when only the
            // sensor value is below the target range.
            val below = glucose.value < settings.lowLimit
            val sensorBelow = sensorNote != null && latest != null && latest.value < settings.lowLimit
            val above = glucose.value > settings.highLimit
            value.text = Texts.valueText(this, glucose, unit)
            showValueLook(if (below || sensorBelow || above) ValueLook.ALERT else ValueLook.IN_RANGE)
            rangeChip.text = getString(
                when {
                    below -> R.string.range_below
                    sensorBelow -> R.string.range_sensor_below
                    above -> R.string.range_above
                    else -> R.string.range_in
                }
            )
            rangeChip.visibility = View.VISIBLE
            if (sensorNote != null) {
                correctedNote.text = sensorNote
                correctedNote.visibility = View.VISIBLE
            }

            unitView.text = unit.label
            unitView.visibility = View.VISIBLE

            val lowHigh = Texts.lowHighNote(this, glucose, unit)
            if (lowHigh != null) {
                note.text = lowHigh
                note.visibility = View.VISIBLE
            }

            val trend = state.trend
            val change = state.changePerMinute
            if (trend != null && change != null) {
                arrow.text = trend.arrow
                arrow.contentDescription = Texts.trendWord(this, trend)
                arrow.visibility = View.VISIBLE
                trendLine.text = getString(R.string.trend_line, Texts.trendWord(this, trend, headsLow), GlucoseFormat.change(change, unit), unit.label)
                trendLine.visibility = View.VISIBLE
                trendBasis.text = getString(R.string.trend_basis, state.changeMinutes)
                trendBasis.visibility = View.VISIBLE
            }

            valueTime.text = getString(R.string.value_time, Texts.timeOfDay(this, glucose.timestamp), Texts.ago(this, state.ageMinutes))
            // a value that is some minutes old gets a yellow patch with bold words
            val isRecent = state.kind == DisplayState.Kind.RECENT
            valueTime.setBackgroundResource(if (isRecent) R.drawable.pill_amber else R.drawable.pill_on_hero)
            valueTime.setTextColor(getColor(if (isRecent) R.color.on_amber else R.color.on_color))
            valueTime.typeface = if (isRecent) AppFont.bold(this) else AppFont.regular(this)
            valueTime.visibility = View.VISIBLE
            fitBigValue(value, arrow)
            return
        }

        // No value that may be shown big.
        value.text = getString(R.string.value_none)
        fitBigValue(value, arrow)
        note.visibility = View.VISIBLE
        when (state.kind) {
            DisplayState.Kind.CLOCK_WRONG -> {
                note.text = getString(R.string.clock_wrong)
                showValueLook(ValueLook.ALERT)
            }
            DisplayState.Kind.OLD -> {
                showValueLook(ValueLook.NO_VALUE)
                note.text = getString(R.string.no_current_value)
                if (glucose != null) {
                    val lastText = getString(
                        R.string.last_value,
                        Texts.valueWithUnit(this, glucose, unit),
                        Texts.ago(this, state.ageMinutes),
                        Texts.timeOrDateTime(this, glucose.timestamp, now)
                    )
                    lastValue.text = if (sensorNote != null) getString(R.string.two_lines, lastText, sensorNote) else lastText
                    lastValue.visibility = View.VISIBLE
                }
            }
            else -> {
                showValueLook(ValueLook.NO_VALUE)
                note.text = getString(R.string.no_value_yet)
            }
        }
    }

    private fun renderScanStatus(now: Long) {
        val status = findViewById<TextView>(R.id.scanStatus)
        val hint = findViewById<TextView>(R.id.scanHint)
        val nfcButton = findViewById<Button>(R.id.nfcSettings)
        val adapter = nfcAdapter

        nfcButton.visibility = View.GONE
        hint.setTextColor(getColor(R.color.text_secondary))
        when {
            adapter == null -> {
                hint.text = getString(R.string.nfc_missing)
                hint.setTextColor(getColor(R.color.red))
            }
            !adapter.isEnabled -> {
                hint.text = getString(R.string.nfc_off)
                hint.setTextColor(getColor(R.color.red))
                nfcButton.visibility = View.VISIBLE
            }
            else -> hint.text = getString(R.string.hint_scan)
        }

        if (ScanRunner.isScanning) {
            status.text = getString(R.string.reading_sensor)
            status.setTextColor(getColor(R.color.blue))
            status.visibility = View.VISIBLE
            return
        }
        val outcome = settings.lastOutcome
        if (outcome == null) {
            status.visibility = View.GONE
            return
        }
        var line = getString(R.string.scan_line, Texts.timeOrDateTime(this, outcome.time, now), Texts.scanMessage(this, outcome))
        if (settings.linkUpEnabled && !outcome.message.isProblem) {
            // The scan worked, but the big number does not come from it. Say what the scan gave.
            val scanned = store.table(ValueSource.SENSOR).latest()
            if (scanned != null && Math.abs(scanned.timestamp - outcome.time) <= 2 * 60_000L) {
                line = getString(R.string.scan_apart, line, Texts.valueWithUnit(this, scanned, settings.unit ?: GlucoseUnit.MGDL))
            }
        }
        status.text = Texts.unbroken(line)
        status.setTextColor(getColor(if (outcome.message.isProblem) R.color.red else R.color.green))
        status.visibility = View.VISIBLE
    }

    private fun renderChart(now: Long, unit: GlucoseUnit, current: CurrentValue) {
        val hours = settings.chartHours
        val ranges = mapOf(R.id.range3 to 3, R.id.range6 to 6, R.id.range12 to 12, R.id.range24 to 24)
        for ((id, value) in ranges) {
            findViewById<View>(id).isSelected = value == hours
        }
        val from = now - hours * 60 * 60_000L
        val values = settings.correction.applyAll(store.since(from))
        // the dotted line of the forecast, only while the value is current
        val forecast = if (current.isCurrent) current.forecast else null
        findViewById<ChartView>(R.id.chart).show(values, now, hours, unit, settings.lowLimit, settings.highLimit, forecast)
        findViewById<View>(R.id.chartForecastLegend).visibility = if (forecast == null) View.GONE else View.VISIBLE
    }

    /** One line about how the values come in, only while they come by themselves. */
    private fun renderLive() {
        val card = findViewById<View>(R.id.liveCard)
        if (!settings.valuesComeByThemselves) {
            card.visibility = View.GONE
            return
        }
        val text = findViewById<TextView>(R.id.liveState)
        val title = findViewById<TextView>(R.id.liveCardTitle)
        if (settings.linkUpEnabled) {
            val state = settings.linkUpState
            title.setText(R.string.home_linkup_title)
            text.text = LinkUpWords.state(this, state)
            text.setTextColor(getColor(if (state.isProblem) R.color.red else R.color.text))
        } else {
            val state = settings.liveState
            title.setText(R.string.home_live_title)
            text.text = LiveNotifications.stateText(this, state)
            text.setTextColor(getColor(if (LiveNotifications.isProblem(state)) R.color.red else R.color.text))
        }
        card.visibility = View.VISIBLE
    }

    private fun renderSensor(now: Long) {
        val box = findViewById<LinearLayout>(R.id.sensorBox)
        box.removeAllViews()
        val progress = findViewById<ProgressBar>(R.id.sensorProgress)
        progress.visibility = View.GONE
        val sensor = settings.lastSensor
        if (sensor == null) {
            addRow(box, getString(R.string.sensor_none), "", false)
            return
        }

        val minutesSinceScan = Math.max(0L, (now - sensor.scanTime) / 60_000L).coerceAtMost(1_000_000L).toInt()
        val running = sensor.status == ScanStatus.OK || sensor.status == ScanStatus.STARTING
        val ageNow = if (running) sensor.age + minutesSinceScan else sensor.age
        val status = if (sensor.status == ScanStatus.OK && ageNow >= sensor.lifetime) ScanStatus.EXPIRED else sensor.status
        val startTime = sensor.scanTime - sensor.age * 60_000L
        // a bar for the time of the sensor that is used; the rows below say the same in words
        if (sensor.lifetime > 0 && (status == ScanStatus.OK || status == ScanStatus.EXPIRED)) {
            progress.progress = (ageNow.toLong() * 1000L / sensor.lifetime).coerceIn(0L, 1000L).toInt()
            progress.visibility = View.VISIBLE
        }

        addRow(box, getString(R.string.sensor_state), Texts.stateLabel(this, status), status != ScanStatus.OK && status != ScanStatus.STARTING)
        if (status == ScanStatus.STARTING) {
            addRow(box, getString(R.string.sensor_warmup_left), GlucoseFormat.duration(Math.max(0, SensorInfo.WARMUP_MINUTES - ageNow)), false)
        }
        if (sensor.status != ScanStatus.NOT_STARTED && sensor.age > 0) {
            addRow(box, getString(R.string.sensor_start), Texts.dateTime(this, startTime), false)
            if (sensor.lifetime > 0) {
                addRow(box, getString(R.string.sensor_end), Texts.dateTime(this, startTime + sensor.lifetime * 60_000L), false)
            }
        }
        if (status == ScanStatus.OK) {
            addRow(box, getString(R.string.sensor_age), GlucoseFormat.duration(ageNow), false)
            addRow(box, getString(R.string.sensor_left), GlucoseFormat.duration(Math.max(0, sensor.lifetime - ageNow)), false)
        }
        addRow(box, getString(R.string.sensor_type), sensor.typeLabel, false)
        addRow(box, getString(R.string.sensor_region), sensor.regionLabel, false)
        addRow(box, getString(R.string.sensor_serial), sensor.serial ?: "–", false)

        addRow(box, getString(R.string.sensor_as_of, Texts.timeOrDateTime(this, sensor.scanTime, now)), "", false)
    }

    private fun addRow(box: LinearLayout, label: String, text: String, problem: Boolean) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_label_value, box, false)
        row.findViewById<TextView>(R.id.label).text = label
        val textView = row.findViewById<TextView>(R.id.text)
        textView.text = text
        if (problem) {
            textView.setTextColor(getColor(R.color.red))
            textView.typeface = AppFont.bold(this)
        }
        box.addView(row)
    }

    companion object {
        private const val REFRESH_MILLIS = 20_000L

        /** How often the analysis of the last days is counted anew. */
        private const val ANALYSIS_MILLIS = 5 * 60_000L

        /** The text sizes of the big number and its arrow, as in activity_main.xml. */
        private const val VALUE_TEXT_SP = 84f
        private const val ARROW_TEXT_SP = 48f
    }
}
