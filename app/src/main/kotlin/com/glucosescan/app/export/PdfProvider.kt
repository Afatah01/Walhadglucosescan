package com.glucosescan.app.export

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Serves the PDFs that PdfExporter wrote to the app the user picked for sharing.
 * It gives out only files that sit in the export folder of the cache, and only to
 * apps that hold the temporary permission the share intent gave them.
 */
class PdfProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val name = uri.lastPathSegment ?: return null
        if (name.isEmpty() || name.contains('/') || name.contains("..")) {
            return null
        }
        val folder = context?.cacheDir ?: return null
        val file = File(File(folder, "export"), name)
        if (!file.exists() || !file.isFile) {
            return null
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null

    override fun getType(uri: Uri): String = "application/pdf"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
}
