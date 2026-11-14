package com.glucosescan.app

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.glucosescan.app.data.Settings
import com.glucosescan.app.nfc.ScanRunner

/**
 * What every screen besides the Home screen shares: the phone scans a sensor on any screen
 * (and then shows the Home screen with the value), the arrow in the title bar goes back, and
 * the four main screens (Home, Analysis, Report, More) have the bar at the bottom.
 */
abstract class BaseActivity : Activity(), NfcAdapter.ReaderCallback {
    /** The four main screens, as they stand in the bar at the bottom. */
    enum class Tab(val viewId: Int) {
        HOME(R.id.tabHome),
        ANALYSIS(R.id.tabAnalysis),
        REPORT(R.id.tabReport),
        MORE(R.id.tabMore)
    }

    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    }

    /**
     * True if the safety notice was accepted. If not, the Home screen is opened (it shows the
     * notice) and this screen closes: call `return` then.
     */
    protected fun noticeAccepted(): Boolean {
        val settings = Settings(this)
        if (settings.noticeAccepted && settings.unit != null) {
            return true
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        return false
    }

    /** Shows a layout with the bar of the four main screens under it. */
    protected fun setContentWithBar(layoutId: Int, tab: Tab) {
        BottomBar.setContent(this, layoutId, tab)
    }

    /** Shows a layout of a screen that is opened from another one: the title bar gets a back arrow. */
    protected fun setContentWithBack(layoutId: Int) {
        setContentView(layoutId)
        actionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        try {
            if (!adapter.isEnabled) {
                return
            }
            val options = Bundle()
            options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000)
            adapter.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, options)
        } catch (e: RuntimeException) {
            // NFC service is gone; the Home screen says that NFC is off
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            nfcAdapter?.disableReaderMode(this)
        } catch (e: RuntimeException) {
            // nothing to switch off
        }
    }

    /**
     * The phone found a sensor while this screen is open. Called on a background thread. The
     * sensor is read here, while this screen stays open, and then the Home screen shows what
     * came of it.
     */
    override fun onTagDiscovered(tag: Tag) {
        val outcome = ScanRunner.scan(this, tag)
        if (outcome != null) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    startActivity(Intent(this, MainActivity::class.java))
                }
            }
        }
    }
}

/** The bar at the bottom with the four main screens. */
object BottomBar {
    /** Puts [layoutId] into the activity with the bar under it, and marks [selected]. */
    fun setContent(activity: Activity, layoutId: Int, selected: BaseActivity.Tab) {
        activity.setContentView(R.layout.screen_with_bar)
        val content = activity.findViewById<ViewGroup>(R.id.screenContent)
        LayoutInflater.from(activity).inflate(layoutId, content, true)
        // the four main screens have their own bar at the top, with the name of the screen
        activity.findViewById<TextView>(R.id.topTitle).text = activity.title
        for (tab in BaseActivity.Tab.values()) {
            val view = activity.findViewById<View>(tab.viewId)
            view.isSelected = tab == selected
            view.setOnClickListener {
                if (tab != selected) {
                    open(activity, tab, selected)
                }
            }
        }
    }

    private fun open(activity: Activity, tab: BaseActivity.Tab, from: BaseActivity.Tab) {
        val target = when (tab) {
            BaseActivity.Tab.HOME -> MainActivity::class.java
            BaseActivity.Tab.ANALYSIS -> AnalysisActivity::class.java
            BaseActivity.Tab.REPORT -> ReportActivity::class.java
            BaseActivity.Tab.MORE -> MoreActivity::class.java
        }
        // The Home screen stays under the others, so that "back" always ends there.
        activity.startActivity(Intent(activity, target))
        if (from != BaseActivity.Tab.HOME) {
            activity.finish()
        }
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}
