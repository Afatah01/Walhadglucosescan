package com.glucosescan.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.core.Statistics
import com.glucosescan.app.core.StatisticsCalculator
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings
import com.glucosescan.app.ui.AppClock
import com.glucosescan.app.ui.DayChartView
import com.glucosescan.app.ui.GlucoseFormat
import com.glucosescan.app.ui.RangeBarView
import com.glucosescan.app.ui.Texts
import java.util.Locale

/** Numbers about the last days: average, time in ranges, the typical day. */
class StatisticsActivity : BaseActivity() {
    private lateinit var settings: Settings
    private lateinit var store: GlucoseStore
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!noticeAccepted()) {
            return
        }
        settings = Settings(this)
        store = GlucoseStore.get(this)
        setContentWithBack(R.layout.activity_statistics)
        for ((id, days) in DAYS) {
            findViewById<View>(id).setOnClickListener {
                settings.statisticsDays = days
                render()
            }
        }
        ready = true
    }

    override fun onResume() {
        super.onResume()
        if (ready) {
            render()
        }
    }

    private fun render() {
        val now = AppClock.now()
        val days = settings.statisticsDays
        val unit = settings.unit ?: GlucoseUnit.MGDL
        for ((id, value) in DAYS) {
            findViewById<View>(id).isSelected = value == days
        }

        // The period ends with the minute that runs now. The numbers come from the values as
        // the app shows them: with the correction of the user, if there is one.
        val to = now + 60_000L
        val from = to - days * StatisticsCalculator.DAY
        val values = settings.correction.applyAll(store.between(from, to))
        val low = settings.lowLimit
        val high = settings.highLimit
        val stats = StatisticsCalculator.compute(values, from, to, low, high)
        last = stats

        findViewById<TextView>(R.id.statsPeriod).text = getString(R.string.stats_period, days, Texts.dateTime(this, from), Texts.dateTime(this, now))
        findViewById<View>(R.id.statsEmpty).visibility = if (stats.hasValues) View.GONE else View.VISIBLE
        findViewById<View>(R.id.statsContent).visibility = if (stats.hasValues) View.VISIBLE else View.GONE
        if (!stats.hasValues) {
            return
        }

        fun glucose(mgdl: Int): String = GlucoseFormat.value(mgdl, unit)
        fun withUnit(mgdl: Int): String = glucose(mgdl) + " " + unit.label

        // summary
        val summary = findViewById<LinearLayout>(R.id.summaryBox)
        summary.removeAllViews()
        stats.mean?.let { addRow(summary, getString(R.string.stats_average), withUnit(Math.round(it).toInt())) }
        stats.gmi?.let {
            val text = String.format(Locale.US, "%.1f", it) + PERCENT
            addRow(summary, getString(R.string.stats_gmi), if (stats.gmiIsReliable) text else getString(R.string.stats_gmi_weak, text))
        }
        stats.cv?.let { addRow(summary, getString(R.string.stats_variation), Math.round(it).toString() + PERCENT) }
        val lowest = stats.lowest
        val highest = stats.highest
        if (lowest != null && highest != null) {
            addRow(summary, getString(R.string.stats_lowest_highest), getString(R.string.stats_from_to, glucose(lowest), withUnit(highest)))
        }
        addRow(summary, getString(R.string.stats_coverage), stats.coveragePercent.toString() + PERCENT)
        addRow(summary, getString(R.string.stats_days_with_values), stats.days.toString())

        // time in ranges: the bar from very low to very high, the lines from very high down
        val percents = stats.rangePercents
        findViewById<RangeBarView>(R.id.rangeBar).show(percents)
        val veryLow = StatisticsCalculator.VERY_LOW_BELOW
        val lowBelow = StatisticsCalculator.LOW_BELOW
        val highAbove = StatisticsCalculator.HIGH_ABOVE
        val veryHigh = StatisticsCalculator.VERY_HIGH_ABOVE
        val labels = arrayOf(
            getString(R.string.stats_range_very_low, withUnit(veryLow)),
            getString(R.string.stats_range_low, glucose(veryLow), withUnit(lowBelow)),
            getString(R.string.stats_range_target, glucose(lowBelow), withUnit(highAbove)),
            getString(R.string.stats_range_high, glucose(highAbove), withUnit(veryHigh)),
            getString(R.string.stats_range_very_high, withUnit(veryHigh))
        )
        val ranges = findViewById<LinearLayout>(R.id.rangesBox)
        ranges.removeAllViews()
        for (k in 4 downTo 0) {
            val row = LayoutInflater.from(this).inflate(R.layout.row_range, ranges, false)
            row.findViewById<View>(R.id.patch).setBackgroundColor(getColor(RangeBarView.COLORS[k]))
            row.findViewById<TextView>(R.id.label).text = labels[k]
            row.findViewById<TextView>(R.id.text).text = percents[k].toString() + PERCENT
            ranges.addView(row)
        }
        findViewById<TextView>(R.id.targetLine).text =
            getString(R.string.stats_your_target, glucose(low), withUnit(high)) + ": " + stats.inTargetPercent + PERCENT

        findViewById<DayChartView>(R.id.dayChart).show(stats.hours, unit, low, high)
    }

    private fun addRow(box: LinearLayout, label: String, text: String) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_label_value, box, false)
        row.findViewById<TextView>(R.id.label).text = label
        row.findViewById<TextView>(R.id.text).text = text
        box.addView(row)
    }

    /** What the screen shows now. For tests. */
    internal var last: Statistics? = null
        private set

    companion object {
        private const val PERCENT = " %"
        private val DAYS = mapOf(R.id.days1 to 1, R.id.days7 to 7, R.id.days14 to 14, R.id.days30 to 30, R.id.days90 to 90)
    }
}
