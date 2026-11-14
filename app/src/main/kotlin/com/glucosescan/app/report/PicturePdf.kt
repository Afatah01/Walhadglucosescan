package com.glucosescan.app.report

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Writes a PDF file whose pages are pictures.
 *
 * It needs nothing from Android but the pixels of the pictures, so it works on every phone,
 * and it can be tried on a computer. The app uses it when the PDF maker of Android fails.
 *
 * The file is plain PDF 1.4: the catalogue, the list of pages, a few words about the
 * document, and for each page one picture (red, green and blue of each pixel, packed with
 * "deflate") that is stretched over the whole paper.
 */
object PicturePdf {
    private const val ROWS_AT_ONCE = 64

    /**
     * @param pageWidth the width of the paper in points (1/72 inch)
     * @param pageHeight the height of the paper in points
     * @param pages how many pages the file gets
     * @param title the title of the document; PDF apps show it
     * @param producer the app that made the file
     * @param created when the file was made, written as PDF wants it ("D:20261110143220+03'00'"), or null
     * @param picture gives the picture of page 0, 1, and so on. This writer recycles it after use.
     */
    fun write(out: OutputStream, pageWidth: Int, pageHeight: Int, pages: Int, title: String, producer: String, created: String?, picture: (Int) -> Bitmap) {
        require(pages > 0 && pageWidth > 0 && pageHeight > 0)
        val file = Counting(out)

        /** Where each object starts in the file; object 1 is at index 0. */
        val starts = ArrayList<Long>()

        fun begin(number: Int) {
            check(number == starts.size + 1) { "objects are written in the order of their numbers" }
            starts.add(file.count)
            file.ascii("$number 0 obj\n")
        }

        // The second line holds four bytes over 127: they tell programs that this is not a text file.
        file.ascii("%PDF-1.4\n")
        file.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        // 1 the catalogue, 2 the list of pages, 3 about the document; then three objects for each
        // page: the page, what is drawn on it, and its picture.
        begin(1)
        file.ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        begin(2)
        val kids = (0 until pages).joinToString(" ") { "${4 + 3 * it} 0 R" }
        file.ascii("<< /Type /Pages /Kids [$kids] /Count $pages >>\nendobj\n")
        begin(3)
        file.ascii(
            "<< /Title (" + escape(title) + ") /Producer (" + escape(producer) + ")" +
                (if (created != null) " /CreationDate (" + escape(created) + ")" else "") + " >>\nendobj\n"
        )

        for (page in 0 until pages) {
            val pageObject = 4 + 3 * page
            val bitmap = picture(page)
            val width = bitmap.width
            val height = bitmap.height
            val packed = try {
                pack(bitmap)
            } finally {
                bitmap.recycle()
            }

            begin(pageObject)
            file.ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageWidth $pageHeight] " +
                    "/Resources << /XObject << /Im$page ${pageObject + 2} 0 R >> >> /Contents ${pageObject + 1} 0 R >>\nendobj\n"
            )

            // "put the picture on the paper, as wide and as high as the paper"
            val drawing = "q\n$pageWidth 0 0 $pageHeight 0 0 cm\n/Im$page Do\nQ\n"
            begin(pageObject + 1)
            file.ascii("<< /Length ${drawing.length} >>\nstream\n" + drawing + "endstream\nendobj\n")

            begin(pageObject + 2)
            file.ascii(
                "<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceRGB " +
                    "/BitsPerComponent 8 /Filter /FlateDecode /Length ${packed.size} >>\nstream\n"
            )
            file.write(packed)
            file.ascii("\nendstream\nendobj\n")
        }

        // The table at the end says where each object starts. Each line of it is exactly 20 bytes long.
        val table = file.count
        file.ascii("xref\n0 ${starts.size + 1}\n")
        file.ascii("0000000000 65535 f \n")
        for (start in starts) {
            file.ascii(String.format(Locale.US, "%010d 00000 n \n", start))
        }
        file.ascii("trailer\n<< /Size ${starts.size + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$table\n%%EOF\n")
        file.flush()
    }

    /** Red, green and blue of every pixel, row by row from the top, packed. A see-through pixel is put on white paper. */
    private fun pack(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val buffer = ByteArrayOutputStream(Math.max(4096, width * height / 16))
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            DeflaterOutputStream(buffer, deflater, 16 * 1024).use { packer ->
                val pixels = IntArray(width * ROWS_AT_ONCE)
                val bytes = ByteArray(width * ROWS_AT_ONCE * 3)
                var top = 0
                while (top < height) {
                    val rows = Math.min(ROWS_AT_ONCE, height - top)
                    bitmap.getPixels(pixels, 0, width, 0, top, width, rows)
                    var at = 0
                    for (index in 0 until width * rows) {
                        val pixel = pixels[index]
                        val alpha = pixel ushr 24
                        var red = (pixel shr 16) and 0xFF
                        var green = (pixel shr 8) and 0xFF
                        var blue = pixel and 0xFF
                        if (alpha != 255) {
                            red = (red * alpha + 255 * (255 - alpha) + 127) / 255
                            green = (green * alpha + 255 * (255 - alpha) + 127) / 255
                            blue = (blue * alpha + 255 * (255 - alpha) + 127) / 255
                        }
                        bytes[at++] = red.toByte()
                        bytes[at++] = green.toByte()
                        bytes[at++] = blue.toByte()
                    }
                    packer.write(bytes, 0, at)
                    top += rows
                }
            }
        } finally {
            deflater.end()
        }
        return buffer.toByteArray()
    }

    /** Words between the round brackets of a PDF text: only plain letters, and the three signs that need a backslash. */
    internal fun escape(words: String): String {
        val result = StringBuilder()
        for (letter in words) {
            when {
                letter == '(' || letter == ')' || letter == '\\' -> result.append('\\').append(letter)
                letter.code in 32..126 -> result.append(letter)
                else -> result.append('?')
            }
        }
        return result.toString()
    }

    /** Counts the bytes that went out: the table at the end of the file needs to know where things are. */
    private class Counting(private val out: OutputStream) {
        var count = 0L
            private set

        fun write(bytes: ByteArray) {
            out.write(bytes)
            count += bytes.size
        }

        fun ascii(words: String) {
            write(words.toByteArray(Charsets.US_ASCII))
        }

        fun flush() {
            out.flush()
        }
    }
}
