package com.glucosescan.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the analysis as a PDF: the answer of the AI, then the numbers it was made from.
 * The file can be saved in the Downloads folder and shared with any app the user picks.
 *
 * The wrapping of the text is counted here in plain words, so that it can be tested
 * without drawing anything. The drawing itself happens on the phone, in [build].
 */
object PdfExporter {

    /** One line of the text, cut into pieces that fit the page: at a space when there is one. */
    fun wrapLine(line: String, most: Int): List<String> {
        if (line.length <= most) {
            return listOf(line)
        }
        val out = ArrayList<String>()
        var rest = line
        while (rest.length > most) {
            var cut = rest.lastIndexOf(' ', most)
            if (cut <= 0) {
                cut = most
            }
            out.add(rest.substring(0, cut))
            rest = rest.substring(cut).trimStart()
        }
        out.add(rest)
        return out
    }

    /** The whole text as page lines. Empty lines stay, so the sections keep their air. */
    fun wrap(text: String, most: Int): List<String> {
        val out = ArrayList<String>()
        for (line in text.lines()) {
            out.addAll(wrapLine(line, most))
        }
        return out
    }

    /** The lines cut into pages of [most] lines each, in the order they were given. */
    fun pages(lines: List<String>, most: Int): List<List<String>> {
        if (lines.isEmpty()) {
            return listOf(listOf(""))
        }
        val out = ArrayList<List<String>>()
        var start = 0
        while (start < lines.size) {
            out.add(lines.subList(start, Math.min(lines.size, start + most)))
            start += most
        }
        return out
    }

    /** The name of the file: walhad-analysis-2026-11-16-0312.pdf */
    fun fileName(now: Long): String {
        val format = SimpleDateFormat("'walhad-analysis-'yyyy-MM-dd-HHmm", Locale.US)
        return format.format(Date(now)) + ".pdf"
    }

    /** Draws the text into a PDF and writes it into the cache of the app. */
    fun build(context: Context, title: String, body: String, now: Long): File {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 44
        val leading = 15f

        val folder = File(context.cacheDir, "export")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        val file = File(folder, fileName(now))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = 10.5f
        paint.color = Color.rgb(26, 28, 30)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        titlePaint.textSize = 17f
        titlePaint.setFakeBoldText(true)
        titlePaint.color = Color.rgb(11, 94, 215)
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        notePaint.textSize = 8.5f
        notePaint.color = Color.rgb(120, 128, 140)

        val per = ((pageHeight - 2 * margin - 64) / leading).toInt()
        val parts = pages(wrap(body, 92), per)

        val document = PdfDocument()
        for (p in parts.indices) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, p + 1).create())
            val canvas: Canvas = page.canvas
            var y = margin + 14f
            if (p == 0) {
                if (title.isNotEmpty()) {
                    canvas.drawText(title, margin.toFloat(), y, titlePaint)
                }
                y += 26f
                val stamp = SimpleDateFormat("EEEE d MMM yyyy, HH:mm", Locale.US).format(Date(now))
                canvas.drawText("Made " + stamp, margin.toFloat(), y, notePaint)
                y += 22f
            }
            for (line in parts[p]) {
                if (line.isNotEmpty()) {
                    canvas.drawText(line, margin.toFloat(), y, paint)
                }
                y += leading
            }
            canvas.drawText(
                "Walhad Glucose Scan - page " + (p + 1) + " of " + parts.size + " - not medical advice",
                margin.toFloat(), pageHeight - margin / 2f, notePaint
            )
            document.finishPage(page)
        }
        file.outputStream().use { out -> document.writeTo(out) }
        document.close()
        return file
    }

    /** The address under which PdfProvider serves the file to other apps. */
    fun uriFor(context: Context, file: File): Uri {
        return Uri.Builder()
            .scheme("content")
            .authority(context.packageName + ".pdf")
            .appendPath("export")
            .appendPath(file.name)
            .build()
    }

    /** Hands the PDF to the app the user picks. */
    fun share(context: Context, file: File, title: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, title))
    }

    /**
     * Copies the PDF into the Downloads folder (Android 10 and newer; no permission needed).
     * Returns the name it was saved under, or null when this Android cannot do it:
     * the caller then shares the file instead, so the user can save it from there.
     */
    fun saveToDownloads(context: Context, file: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        val values = ContentValues()
        values.put(MediaStore.Downloads.DISPLAY_NAME, file.name)
        values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
        values.put(MediaStore.Downloads.IS_PENDING, 1)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val out = resolver.openOutputStream(uri) ?: return null
        out.use { dest -> file.inputStream().use { src -> src.copyTo(dest) } }
        val done = ContentValues()
        done.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, done, null, null)
        return file.name
    }
}
