package com.glucosescan.app.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import com.glucosescan.app.R

/**
 * The letters of the app (Inter), for what the app draws or sets by itself: the charts, the
 * report, the big number. The screens get them from the theme. Android 8 and newer can load
 * letters that an app brings along; older phones use their own.
 */
object AppFont {
    fun regular(context: Context): Typeface = load(context, R.font.app_font, Typeface.NORMAL, Typeface.DEFAULT)

    /** The strong cut, for titles and for what must stand out. */
    fun bold(context: Context): Typeface = load(context, R.font.app_font, Typeface.BOLD, Typeface.DEFAULT_BOLD)

    /** The cut for very large numbers. */
    fun display(context: Context): Typeface = load(context, R.font.app_font_display, Typeface.BOLD, Typeface.DEFAULT_BOLD)

    private fun load(context: Context, id: Int, style: Int, fallback: Typeface): Typeface {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return Typeface.create(context.resources.getFont(id), style)
            } catch (e: RuntimeException) {
                // the phone cannot read the file: its own letters do
            }
        }
        return fallback
    }
}
