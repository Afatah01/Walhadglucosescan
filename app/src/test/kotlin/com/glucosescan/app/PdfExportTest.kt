package com.glucosescan.app

import com.glucosescan.app.export.PdfExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wrapping of the text and the name of the PDF file: counted without drawing anything. */
class PdfExportTest {

    @Test
    fun aShortLineStaysOneLine() {
        assertEquals(listOf("Average glucose: 117 mg/dL"), PdfExporter.wrapLine("Average glucose: 117 mg/dL", 92))
    }

    @Test
    fun aLongLineIsCutAtASpace() {
        val words = List(30) { "glucose" }.joinToString(" ")
        val lines = PdfExporter.wrapLine(words, 20)
        assertTrue("it needs more than one line", lines.size > 1)
        for (line in lines) {
            assertTrue("too long: " + line, line.length <= 20)
        }
        assertEquals(words, lines.joinToString(" "))
    }

    @Test
    fun aLineWithoutSpacesIsCutHard() {
        val lines = PdfExporter.wrapLine("abcdefghijklmnopqrstuvwxyz", 10)
        assertEquals(listOf("abcdefghij", "klmnopqrst", "uvwxyz"), lines)
    }

    @Test
    fun thePagesHoldTheLinesInTheOrderTheyWereGiven() {
        val pages = PdfExporter.pages(listOf("a", "b", "c", "d", "e"), 2)
        assertEquals(3, pages.size)
        assertEquals(listOf("a", "b"), pages[0])
        assertEquals(listOf("c", "d"), pages[1])
        assertEquals(listOf("e"), pages[2])
    }

    @Test
    fun anEmptyTextIsStillOnePage() {
        val pages = PdfExporter.pages(emptyList(), 40)
        assertEquals(1, pages.size)
    }

    @Test
    fun theNameOfTheFileSaysWhenItWasMade() {
        val name = PdfExporter.fileName(1_794_310_340_000L)
        assertTrue(name.startsWith("walhad-analysis-"))
        assertTrue(name.endsWith(".pdf"))
        assertFalse("no folders inside the name", name.contains('/'))
        assertTrue(name.length == "walhad-analysis-2026-11-16-0312.pdf".length)
    }
}
