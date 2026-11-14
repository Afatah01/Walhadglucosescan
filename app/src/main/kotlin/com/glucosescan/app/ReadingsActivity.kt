package com.glucosescan.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.GlucoseUnit
import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.Settings
import com.glucosescan.app.ui.Texts

/** The list of the saved values, newest first, with a line for each day. */
class ReadingsActivity : BaseActivity() {
    /**
     * One line of the list: a day title (glucose is null) or a value. [sensorValue] is the
     * value before the correction; it is the same as the shown one if there is no correction.
     */
    private class Item(val dayTitle: String?, val glucose: Glucose?, val sensorValue: Int = 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_readings)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val settings = Settings(this)
        val unit = settings.unit ?: GlucoseUnit.MGDL
        val low = settings.lowLimit
        val high = settings.highLimit
        val sensorValues = GlucoseStore.get(this).newest(MAX_ROWS)

        // with a correction, the list shows the corrected values and says so
        val correction = settings.correction
        if (correction.isActive) {
            val note = findViewById<TextView>(R.id.correctedNote)
            note.text = getString(R.string.readings_corrected_note, correction.sensorSerial)
            note.visibility = View.VISIBLE
        }

        val items = ArrayList<Item>()
        var previous: Glucose? = null
        for (sensorValue in sensorValues) {
            val glucose = correction.apply(sensorValue)
            if (previous == null || !Texts.sameDay(previous.timestamp, glucose.timestamp)) {
                items.add(Item(Texts.day(this, glucose.timestamp), null))
            }
            items.add(Item(null, glucose, sensorValue.value))
            previous = glucose
        }

        val list = findViewById<ListView>(R.id.list)
        val empty = findViewById<View>(R.id.empty)
        if (items.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }

        val textColor = getColor(R.color.text)
        val red = getColor(R.color.red)
        val inflater = LayoutInflater.from(this)
        list.adapter = object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getViewTypeCount(): Int = 2
            override fun getItemViewType(position: Int): Int = if (items[position].glucose == null) 0 else 1
            override fun areAllItemsEnabled(): Boolean = false
            override fun isEnabled(position: Int): Boolean = false

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = items[position]
                val glucose = item.glucose
                if (glucose == null) {
                    val view = convertView ?: inflater.inflate(R.layout.row_day, parent, false)
                    (view as TextView).text = item.dayTitle
                    return view
                }
                val view = convertView ?: inflater.inflate(R.layout.row_reading, parent, false)
                view.findViewById<TextView>(R.id.time).text = Texts.timeOfDay(this@ReadingsActivity, glucose.timestamp)
                val text = view.findViewById<TextView>(R.id.text)
                text.text = Texts.valueWithUnit(this@ReadingsActivity, glucose, unit)
                // as on the main screen: a correction never hides a low sensor value
                val outside = glucose.value < low || glucose.value > high || item.sensorValue < low
                text.setTextColor(if (outside) red else textColor)
                view.findViewById<View>(R.id.dot).setBackgroundResource(if (outside) R.drawable.dot_red else R.drawable.dot_green)
                return view
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val MAX_ROWS = 3000
    }
}
