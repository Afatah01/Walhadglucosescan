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
import com.glucosescan.app.core.ControlLevel
import com.glucosescan.app.core.ControlSummary
import com.glucosescan.app.core.ControlSummaryOf
import com.glucosescan.app.core.Forecaster
import com.glucosescan.app.core.GlucoseBand
import com.glucosescan.app.core.HorizonEstimate
import com.glucosescan.app.core.Horizons
import com.glucosescan.app.core.PredictionConfidence
import com.glucosescan.app.core.PredictionConfidenceWord
import com.glucosescan.app.core.Thresholds
import com.glucosescan.app.core.OutlookConfidence
import com.glucosescan.app.core.OutlookDirection
import com.glucosescan.app.core.OutlookRisk
import com.glucosescan.app.core.OutlookSeverity
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
        renderSensor(now, unit)
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
        outlookSeverity = OutlookSeverity.NORMAL
        val ack = findViewById<View>(R.id.outlookAck)
        ack.visibility = View.GONE
        ack.setOnClickListener { }
        for (id in listOf(R.id.outlookThresholds, R.id.outlookSeverity, R.id.outlookWarning, R.id.outlook60Note)) {
            findViewById<View>(id).visibility = View.GONE
        }

        // The three cards always stand there. With no line to stand on, they say so instead of
        // showing a number that was not measured.
        val unavailable = getString(R.string.day_none)
        for (id in listOf(
            R.id.outlook15, R.id.outlook30, R.id.outlook60,
            R.id.outlookTrend15, R.id.outlookTrend30, R.id.outlookTrend60,
            R.id.outlook15Time, R.id.outlook30Time, R.id.outlook60Time,
            R.id.outlook15Conf, R.id.outlook30Conf, R.id.outlook60Conf
        )) {
            findViewById<TextView>(id).text = unavailable
        }
        for (id in listOf(R.id.forecast15Card, R.id.forecast30Card, R.id.forecast60Card)) {
            findViewById<View>(id).setBackgroundResource(R.drawable.card_overview)
        }

        if (forecast == null || !current.isCurrent) {
            card.visibility = View.VISIBLE
            findViewById<TextView>(R.id.outlookDirection).text =
                if (!current.isCurrent) getString(R.string.no_current_value) else getString(R.string.forecast_insufficient)
            findViewById<TextView>(R.id.outlookConfidence).text = ""
            findViewById<TextView>(R.id.outlookNote).text = ""
            return
        }

        val lowLimit = settings.lowLimit
        val highLimit = settings.highLimit
        val nowValue = current.shown?.readableValue ?: 0

        // The mean of the values the line was built on: the anchor the 60-minute estimate bends
        // back towards. It is a mean of real readings, never a guess.
        val mean = current.shown?.let { getMeanOfWindow(now) }

        // 15, 30 and 60 minutes, all three at once, from the one engine.
        val fifteen = Horizons.at(forecast, now, 15, mean, lowLimit, highLimit)
        val thirty = Horizons.at(forecast, now, Forecaster.HORIZON_MINUTES, mean, lowLimit, highLimit)
        val sixty = Horizons.at(forecast, now, Forecaster.FAR_HORIZON_MINUTES, mean, lowLimit, highLimit)

        paintHorizon(R.id.forecast15Card, R.id.outlook15, R.id.outlookTrend15, R.id.outlook15Time, fifteen, unit)
        paintHorizon(R.id.forecast30Card, R.id.outlook30, R.id.outlookTrend30, R.id.outlook30Time, thirty, unit)
        paintHorizon(R.id.forecast60Card, R.id.outlook60, R.id.outlookTrend60, R.id.outlook60Time, sixty, unit)

        // The confidence comes from the values themselves, and the weaker 60-minute estimate says
        // so in one line under the cards rather than being hidden.
        val confidence = PredictionConfidence.of(forecast)
        findViewById<TextView>(R.id.outlookNote).text =
            getString(R.string.outlook_estimate_note2, forecast.spanMinutes, Texts.timeOfDay(this, now))
        if (confidence.word != PredictionConfidenceWord.HIGH) {
            findViewById<View>(R.id.outlook60Note).visibility = View.VISIBLE
        }
        findViewById<TextView>(R.id.outlookConfidence).text = getString(
            R.string.outlook_confidence_detail,
            getString(
                when (confidence.word) {
                    PredictionConfidenceWord.HIGH -> R.string.outlook_confidence_high
                    PredictionConfidenceWord.MODERATE -> R.string.outlook_confidence_moderate
                    PredictionConfidenceWord.LOW -> R.string.outlook_confidence_low
                }
            ),
            confidence.basedOn,
            confidence.spanMinutes
        )

        // Which way it is going, with the rate (the same figures as before).
        val outlook = Outlooks.of(forecast, now, nowValue, lowLimit, highLimit)
        if (outlook != null) {
            val perMinute = FindingTexts.glucose(Math.round(Math.abs(outlook.perMinute)).toInt(), unit)
            findViewById<TextView>(R.id.outlookDirection).text = Texts.unbroken(
                when (outlook.direction) {
                    OutlookDirection.RISING -> getString(R.string.outlook_direction_rising, perMinute)
                    OutlookDirection.FALLING -> getString(R.string.outlook_direction_falling, perMinute)
                    OutlookDirection.STEADY -> getString(R.string.outlook_direction_steady, perMinute)
                }
            )
        }

        // The times to the two limits, when the line reaches one inside three hours.
        val crossings = Thresholds.crossings(forecast, now, lowLimit, highLimit)
        if (crossings.isNotEmpty()) {
            val lines = crossings.joinToString("\n") { crossing ->
                getString(
                    if (crossing.rising) R.string.outlook_until_high else R.string.outlook_until_low,
                    crossing.minutes,
                    FindingTexts.glucose(crossing.limit, unit)
                )
            }
            val view = findViewById<TextView>(R.id.outlookThresholds)
            view.text = Texts.unbroken(lines)
            view.visibility = View.VISIBLE
        }

        // The severity of the whole panel: one word, one colour, and the words say the same.
        val minutes = current.minutesUntilBelowTarget(now)
        val risk = outlook?.risk ?: OutlookRisk.NONE
        val severity = when (risk) {
            OutlookRisk.SEVERE_LOW, OutlookRisk.LOW, OutlookRisk.LOW_SOON -> OutlookSeverity.URGENT
            OutlookRisk.VERY_HIGH, OutlookRisk.HIGH -> OutlookSeverity.WARNING
            OutlookRisk.NONE -> OutlookSeverity.NORMAL
        }
        outlookSeverity = severity
        paintSeverity(severity)

        val alert = findViewById<View>(R.id.forecastAlert)
        alert.setBackgroundResource(
            when (severity) {
                OutlookSeverity.URGENT -> R.drawable.card_risk
                OutlookSeverity.WARNING -> R.drawable.card_amber
                OutlookSeverity.NORMAL -> R.drawable.card_overview
            }
        )

        val warning = findViewById<TextView>(R.id.outlookWarning)
        val lowWords = FindingTexts.glucose(lowLimit, unit)
        val highWords = FindingTexts.glucose(highLimit, unit)
        if (severity == OutlookSeverity.URGENT) {
            warnsOfLow = true
            val words = when (risk) {
                OutlookRisk.SEVERE_LOW, OutlookRisk.LOW ->
                    getString(R.string.outlook_predicted_low, lowWords, FindingTexts.forecast(this, forecast.valueAt(now), unit), 0)
                else -> getString(
                    R.string.outlook_predicted_low,
                    lowWords,
                    FindingTexts.forecast(this, forecast.valueAt(now + Forecaster.HORIZON_MINUTES * 60_000L), unit),
                    minutes ?: Forecaster.HORIZON_MINUTES
                )
            }
            warning.text = Texts.unbroken(words)
            warning.setTextColor(getColor(R.color.p_red))
            warning.typeface = AppFont.bold(this)
            warning.visibility = View.VISIBLE
            ack.visibility = View.VISIBLE
            ack.setOnClickListener { ack.visibility = View.GONE }
        } else if (severity == OutlookSeverity.WARNING) {
            warning.text = Texts.unbroken(
                getString(
                    R.string.outlook_predicted_high,
                    highWords,
                    FindingTexts.forecast(this, forecast.valueAt(now + Forecaster.HORIZON_MINUTES * 60_000L), unit),
                    Forecaster.HORIZON_MINUTES
                )
            )
            warning.setTextColor(getColor(R.color.p_amber))
            warning.typeface = AppFont.bold(this)
            warning.visibility = View.VISIBLE
        }

        card.visibility = View.VISIBLE

        // What to do about it, from the phone, without any network.
        val advice = findViewById<TextView>(R.id.outlookAdvice)
        advice.visibility = View.VISIBLE
        advice.text = Texts.unbroken(
            when (risk) {
                OutlookRisk.SEVERE_LOW -> getString(R.string.outlook_advice_severe_low)
                OutlookRisk.LOW -> getString(R.string.outlook_advice_low)
                OutlookRisk.LOW_SOON -> if (minutes == null) getString(R.string.outlook_advice_none) else getString(R.string.outlook_advice_low_soon, minutes, lowWords)
                OutlookRisk.VERY_HIGH -> getString(R.string.outlook_advice_very_high)
                OutlookRisk.HIGH -> getString(R.string.outlook_advice_high)
                OutlookRisk.NONE -> getString(R.string.outlook_advice_none)
            }
        )
    }

    /** Paints one of the three horizon cards: the value, the arrow, the time, the colour. */
    private fun paintHorizon(cardId: Int, valueId: Int, trendId: Int, timeId: Int, estimate: HorizonEstimate, unit: GlucoseUnit) {
        val colour = when (estimate.band) {
            GlucoseBand.VERY_LOW -> R.color.p_red
            GlucoseBand.LOW -> R.color.p_red
            GlucoseBand.IN_RANGE -> R.color.p_green
            GlucoseBand.HIGH -> R.color.p_amber
            GlucoseBand.VERY_HIGH -> R.color.p_amber
        }
        val background = when (estimate.band) {
            GlucoseBand.VERY_LOW -> R.drawable.card_risk
            GlucoseBand.LOW -> R.drawable.card_risk
            GlucoseBand.IN_RANGE -> R.drawable.card_stability
            GlucoseBand.HIGH -> R.drawable.card_amber
            GlucoseBand.VERY_HIGH -> R.drawable.card_amber
        }
        findViewById<View>(cardId).setBackgroundResource(background)
        val value = findViewById<TextView>(valueId)
        value.text = GlucoseFormat.value(estimate.value, unit)
        value.setTextColor(getColor(colour))
        // the arrow sits beside the number, coloured with the card
        val trend = findViewById<TextView>(trendId)
        trend.text = estimate.arrow
        trend.setTextColor(getColor(colour))
        // the band word goes in its own small chip under the time
        val chipId = when (valueId) {
            R.id.outlook15 -> R.id.outlookBand15
            R.id.outlook30 -> R.id.outlookBand30
            else -> R.id.outlookBand60
        }
        val unitId = when (valueId) {
            R.id.outlook15 -> R.id.outlook15Unit
            R.id.outlook30 -> R.id.outlook30Unit
            else -> R.id.outlook60Unit
        }
        findViewById<TextView>(unitId).text = unit.label
        val chip = findViewById<TextView>(chipId)
        chip.text = getString(
            when (estimate.band) {
                GlucoseBand.VERY_LOW -> R.string.band_word_very_low
                GlucoseBand.LOW -> R.string.band_word_low
                GlucoseBand.IN_RANGE -> R.string.band_word_in
                GlucoseBand.HIGH -> R.string.band_word_high
                GlucoseBand.VERY_HIGH -> R.string.band_word_very_high
            }
        )
        chip.setTextColor(getColor(colour))
        findViewById<TextView>(timeId).text = Texts.timeOfDay(this, estimate.at)
        // each estimate says how much it is to be trusted, on its own card
        val confId = when (valueId) {
            R.id.outlook15 -> R.id.outlook15Conf
            R.id.outlook30 -> R.id.outlook30Conf
            else -> R.id.outlook60Conf
        }
        findViewById<TextView>(confId).text = getString(
            R.string.forecast_confidence_line,
            getString(
                when (estimate.confidence) {
                    PredictionConfidenceWord.HIGH -> R.string.forecast_conf_high
                    PredictionConfidenceWord.MODERATE -> R.string.forecast_conf_moderate
                    PredictionConfidenceWord.LOW -> R.string.forecast_conf_low
                }
            )
        )
    }

    /** The mean of the readings of the forecast window, or null when there are none. */
    private fun getMeanOfWindow(now: Long): Double? {
        val from = now - Forecaster.WINDOW_MINUTES * 60_000L
        val values = settings.correction.applyAll(store.since(from))
        if (values.isEmpty()) {
            return null
        }
        var sum = 0.0
        for (value in values) {
            sum += value.readableValue.toDouble()
        }
        return sum / values.size
    }

    /** The severity of the estimates card, said in words as well as in colour. */
    private fun paintSeverity(severity: OutlookSeverity) {
        val view = findViewById<TextView>(R.id.outlookSeverity)
        val title = findViewById<TextView>(R.id.outlookTitle)
        val colour = when (severity) {
            OutlookSeverity.URGENT -> R.color.p_red
            OutlookSeverity.WARNING -> R.color.p_amber
            OutlookSeverity.NORMAL -> R.color.p_text2
        }
        val word = when (severity) {
            OutlookSeverity.URGENT -> R.string.outlook_severity_urgent
            OutlookSeverity.WARNING -> R.string.outlook_severity_warning
            OutlookSeverity.NORMAL -> R.string.outlook_severity_normal
        }
        view.text = getString(word)
        view.setTextColor(getColor(colour))
        view.typeface = AppFont.bold(this)
        view.visibility = View.VISIBLE
        title.setTextColor(getColor(if (severity == OutlookSeverity.NORMAL) R.color.p_text else colour))
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

        // The five figures the design adds to the day: the tight range (70 to 140), the two
        // outer bands, how much of the day has readings, and how many readings there are.
        findViewById<TextView>(R.id.tileInTight).text = if (stats.hasValues) stats.inTightPercent.toString() + " %" else none
        findViewById<TextView>(R.id.tileVeryLow).text = if (stats.hasValues) stats.veryLowPercent.toString() + " %" else none
        findViewById<TextView>(R.id.tileVeryHigh).text = if (stats.hasValues) stats.veryHighPercent.toString() + " %" else none
        findViewById<TextView>(R.id.tileCoverage).text = if (stats.hasValues) stats.coveragePercent.toString() + " %" else none
        findViewById<TextView>(R.id.tileReadings).text =
            if (stats.hasValues) stats.readingCount.toString() else none
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

        // The judgement of the whole period, in one of four words, with the figures behind it.
        // It is never "Good" on a thin period: too little data says so instead.
        val summary = ControlSummaryOf.of(result.stats)
        controlSummary = summary
        val periodView = findViewById<TextView>(R.id.analysisPeriod)
        periodView.text = summary.word
        periodView.visibility = View.VISIBLE
        periodView.textSize = 13f
        periodView.typeface = AppFont.bold(this)
        periodView.setPadding(dp(14), dp(7), dp(14), dp(7))
        // A badge, not a word: the colour of the card the badge sits on, and the words on top.
        periodView.setBackgroundResource(
            when {
                !summary.judged -> R.drawable.pill_state_off
                summary.level == ControlLevel.GOOD -> R.drawable.pill_state_on
                summary.level == ControlLevel.NEEDS_ATTENTION -> R.drawable.p_card_amber
                else -> R.drawable.p_card_red
            }
        )
        periodView.setTextColor(
            getColor(
                when {
                    !summary.judged -> R.color.p_text2
                    summary.level == ControlLevel.GOOD -> R.color.p_green
                    summary.level == ControlLevel.NEEDS_ATTENTION -> R.color.p_amber
                    else -> R.color.p_red
                }
            )
        )
        val hint = findViewById<TextView>(R.id.analysisBadgeHint)
        hint.text = getString(R.string.analysis_badge_hint, days)
        hint.visibility = View.VISIBLE
        addSentence(box, summary.sentence)

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

    private fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    /** A plain sentence in a card: no badge, no colour beyond the body text. */
    private fun addSentence(box: LinearLayout, words: String) {
        val view = TextView(this)
        view.text = Texts.unbroken(words)
        view.setTextColor(getColor(R.color.p_text2))
        view.textSize = 13.5f
        view.setLineSpacing(0f, 1.3f)
        view.setLineSpacing(0f, 1.25f)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = Math.round(8 * resources.displayMetrics.density)
        view.layoutParams = params
        box.addView(view)
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
        /** premium amber-orange: a current value inside the target range (the hero of the Home page) */
        IN_RANGE(R.drawable.p_hero_premium),

        /** teal: a current value inside the range, with the calm colour of the brand */
        IN_RANGE_TEAL(R.drawable.p_hero_teal),

        /** amber: above the target range, but not dangerously so */
        HIGH(R.drawable.p_hero_amber),

        /** red: a low value, or a problem */
        ALERT(R.drawable.p_hero_red),

        /** blue: no current value */
        NO_VALUE(R.drawable.p_hero_blue)
    }

    internal var valueLook: ValueLook = ValueLook.NO_VALUE
        private set

    /** The band of the newest value as the screen shows it, or null with no current value. */
    internal var currentBand: GlucoseBand? = null
        private set

    /** The judgement of the last days, or null before it is counted. */
    internal var controlSummary: ControlSummary? = null
        private set

    /** How serious the card of estimates is. */
    internal var outlookSeverity: OutlookSeverity = OutlookSeverity.NORMAL
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
            // The five bands of the design: very low, low, in range, high, very high. The colour
            // follows the band, and the words on the chip say the same thing as the colour.
            val band = GlucoseBand.of(glucose.readableValue, settings.lowLimit, settings.highLimit)
            currentBand = band
            val look = when {
                sensorBelow || below || band == GlucoseBand.VERY_LOW -> ValueLook.ALERT
                band == GlucoseBand.HIGH || band == GlucoseBand.VERY_HIGH -> ValueLook.HIGH
                // inside the target the card breathes: green at the edges, teal in the middle
                glucose.readableValue in (settings.lowLimit + 12)..(settings.highLimit - 20) ->
                    ValueLook.IN_RANGE_TEAL
                else -> ValueLook.IN_RANGE
            }
            showValueLook(look)
            rangeChip.typeface = AppFont.bold(this)
            rangeChip.text = getString(
                when {
                    sensorBelow -> R.string.range_sensor_below
                    band == GlucoseBand.VERY_LOW -> R.string.band_very_low
                    band == GlucoseBand.LOW -> R.string.band_low
                    band == GlucoseBand.HIGH -> R.string.band_high
                    band == GlucoseBand.VERY_HIGH -> R.string.band_very_high
                    else -> R.string.band_in_range
                },
                FindingTexts.glucose(settings.lowLimit, unit),
                FindingTexts.glucose(settings.highLimit, unit)
            )
            // the status pill: solid red or amber when something is wrong, soft when all is well
            rangeChip.setBackgroundResource(
                when {
                    sensorBelow || below || band == GlucoseBand.VERY_LOW || band == GlucoseBand.LOW ->
                        R.drawable.pill_on_hero_solid
                    band == GlucoseBand.HIGH || band == GlucoseBand.VERY_HIGH -> R.drawable.pill_on_hero_amber
                    else -> R.drawable.pill_on_hero_soft
                }
            )
            rangeChip.visibility = View.VISIBLE
            if (sensorNote != null) {
                correctedNote.text = sensorNote
                correctedNote.visibility = View.VISIBLE
            }

            unitView.text = unit.label
            unitView.visibility = View.VISIBLE

            // The card of the reference has no second sentence under the number: the status pill
            // above says the same thing in fewer words, so this line stays out of the way.
            note.visibility = View.GONE

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

    /**
     * The sensor card: where the readings come from, whether they are arriving now, and when the
     * last one arrived.
     *
     * Two things never appear here. There is no battery or signal figure, because a scan over
     * NFC knows neither. And nothing is called LIVE because a link was agreed or a scan worked:
     * only a reading that has actually arrived says "receiving". NFC is a scan, not a connection,
     * and the card says so in words.
     */
    private fun renderSensor(now: Long, unit: GlucoseUnit) {
        val box = findViewById<LinearLayout>(R.id.sensorBox)
        box.removeAllViews()
        val progress = findViewById<ProgressBar>(R.id.sensorProgress)
        progress.visibility = View.GONE
        val chip = findViewById<TextView>(R.id.sensorSourceChip)

        val latest = store.latest()
        val ageMinutes = if (latest != null) Math.max(0L, (now - latest.timestamp) / 60_000L) else null

        // which source supplied the newest reading: the tables say, not the switches
        val sensorNewest = store.latestOf(ValueSource.SENSOR)
        val linkUpNewest = store.latestOf(ValueSource.LINKUP)
        val fromLinkUp = linkUpNewest != null && (sensorNewest == null || linkUpNewest.timestamp > sensorNewest.timestamp)
        chip.text = when {
            latest == null -> getString(R.string.sensor_none)
            fromLinkUp -> getString(R.string.sensor_source_linkup)
            else -> getString(R.string.sensor_source_sensor)
        }
        chip.setTextColor(getColor(if (latest == null) R.color.p_text2 else R.color.p_green))
        chip.setBackgroundResource(if (latest == null) R.drawable.pill_state_off else R.drawable.pill_state_on)

        // the reception state, in words: LIVE only while readings actually arrive
        val receiving = ageMinutes != null && ageMinutes <= FRESH_MINUTES
        val late = ageMinutes != null && ageMinutes > FRESH_MINUTES && ageMinutes <= STALE_MINUTES
        val stateWord = when {
            ageMinutes == null -> getString(R.string.sensor_quiet)
            receiving -> getString(R.string.sensor_receiving)
            late -> getString(R.string.sensor_late, Texts.ago(this, ageMinutes.toInt()))
            else -> getString(R.string.sensor_quiet)
        }

        // two columns of label/value pairs, as in the reference
        val rows = ArrayList<Triple<String, String, Boolean>>()
        rows.add(Triple(getString(R.string.sensor_last_reading), if (latest != null) Texts.timeOfDay(this, latest.timestamp) + " (" + Texts.ago(this, (ageMinutes ?: 0L).toInt()) + ")" else stateWord, !receiving))
        rows.add(Triple(getString(R.string.sensor_current), if (latest != null) Texts.valueWithUnit(this, latest, unit) else getString(R.string.day_none), latest == null))
        rows.add(Triple(getString(R.string.sensor_source_sensor), if (settings.valuesComeByThemselves && !settings.linkUpEnabled) stateWord else getString(R.string.sensor_inactive_source), !(settings.valuesComeByThemselves && !settings.linkUpEnabled && receiving)))
        rows.add(Triple(getString(R.string.sensor_nfc_note), getString(R.string.sensor_inactive_source), false))
        rows.add(Triple(getString(R.string.sensor_source_linkup), if (settings.linkUpEnabled) LinkUpWords.state(this, settings.linkUpState) else getString(R.string.sensor_linkup_off), !settings.linkUpEnabled))
        rows.add(Triple(getString(R.string.home_live_title), if (settings.linkUpEnabled) LinkUpWords.state(this, settings.linkUpState) else LiveNotifications.stateText(this, settings.liveState), !receiving))

        var index = 0
        while (index < rows.size) {
            val pair = LinearLayout(this)
            pair.orientation = LinearLayout.HORIZONTAL
            pair.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            for (column in 0..1) {
                val cell = LayoutInflater.from(this).inflate(R.layout.row_sensor_item, pair, false)
                val item = rows.getOrNull(index + column)
                if (item == null) {
                    cell.visibility = View.INVISIBLE
                } else {
                    cell.findViewById<TextView>(R.id.itemLabel).text = item.first
                    val value = cell.findViewById<TextView>(R.id.itemValue)
                    value.text = Texts.unbroken(item.second)
                    value.setTextColor(getColor(if (item.third) R.color.p_red else R.color.p_green))
                }
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (column == 1) params.marginStart = dp(12)
                cell.layoutParams = params
                pair.addView(cell)
            }
            box.addView(pair)
            index += 2
        }

        // the honest age of the sensor the last scan found; never a made-up battery
        val sensor = settings.lastSensor
        if (sensor != null) {
            val minutesSinceScan = Math.max(0L, (now - sensor.scanTime) / 60_000L).coerceAtMost(1_000_000L).toInt()
            val running = sensor.status == ScanStatus.OK || sensor.status == ScanStatus.STARTING
            val ageNow = if (running) sensor.age + minutesSinceScan else sensor.age
            val status = if (sensor.status == ScanStatus.OK && ageNow >= sensor.lifetime) ScanStatus.EXPIRED else sensor.status
            if (sensor.lifetime > 0 && (status == ScanStatus.OK || status == ScanStatus.EXPIRED)) {
                progress.progress = (ageNow.toLong() * 1000L / sensor.lifetime).coerceIn(0L, 1000L).toInt()
                progress.visibility = View.VISIBLE
            }
            val words = ArrayList<String>(3)
            words.add(Texts.stateLabel(this, status))
            words.add(sensor.typeLabel)
            if (status == ScanStatus.STARTING) {
                words.add(getString(R.string.sensor_warmup_left) + " " + GlucoseFormat.duration(Math.max(0, SensorInfo.WARMUP_MINUTES - ageNow)))
            }
            if (status == ScanStatus.OK) {
                words.add(getString(R.string.sensor_left) + " " + GlucoseFormat.duration(Math.max(0, sensor.lifetime - ageNow)))
            }
            val line = TextView(this)
            line.text = Texts.unbroken(words.joinToString(" · "))
            line.setTextColor(getColor(R.color.p_text2))
            line.textSize = 11f
            line.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            box.addView(line)
        }
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

        /**
         * A reading this recent means readings are arriving now. The sensor samples each minute,
         * so six minutes already allows for a few missed ones.
         */
        private const val FRESH_MINUTES = 6L

        /** After this a reading is shown as late rather than as arriving. */
        private const val STALE_MINUTES = 30L

        /** How often the analysis of the last days is counted anew. */
        private const val ANALYSIS_MILLIS = 5 * 60_000L

        /** The text sizes of the big number and its arrow, as in activity_main.xml. */
        private const val VALUE_TEXT_SP = 76f
        private const val ARROW_TEXT_SP = 42f
    }
}
