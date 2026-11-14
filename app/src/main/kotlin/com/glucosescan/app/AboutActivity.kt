package com.glucosescan.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.glucosescan.app.data.Settings

class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.version).text = getString(R.string.about_version, version)

        // the last scan, and the correction of the values if the user has set one up
        val settings = Settings(this)
        val correction = settings.correction
        val scanDetails = settings.lastScanDetails
        val details = if (correction.isActive) {
            (if (scanDetails.isEmpty()) "" else scanDetails + "\n") + correction.describe()
        } else {
            scanDetails
        }
        findViewById<TextView>(R.id.details).text = if (details.isEmpty()) getString(R.string.about_details_empty) else details

        val copy = findViewById<View>(R.id.copy)
        copy.isEnabled = details.isNotEmpty()
        copy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.about_details_title), details))
            if (Build.VERSION.SDK_INT < 33) { // newer Android versions show their own note
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }

        // The notices that the licenses of the open-source parts ask for.
        findViewById<View>(R.id.licenses).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.about_licenses_title)
                .setMessage(R.string.about_licenses_text)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
