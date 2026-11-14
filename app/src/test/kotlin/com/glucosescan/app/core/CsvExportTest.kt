package com.glucosescan.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CsvExportTest {
    private val zone = TimeZone.getTimeZone("Africa/Mogadishu") // three hours before London time, all year

    /** 1 January 2026, 12:00 local time (UTC+3). */
    private val noon = 1_767_258_000_000L
    private val minute = 60_000L

    private fun lines(text: String): List<String> = text.split("\r\n")

    @Test
    fun anEmptyFileHasTheHeaderOnly() {
        val text = CsvExport.build(emptyList(), { it }, zone)
        assertEquals("kind,time,mg/dL,mmol/L,sensor value mg/dL,note,sensor\r\n", text)
        assertEquals(CsvExport.HEADER + "\r\n", text)
    }

    @Test
    fun aValueBecomesOneLine() {
        val text = CsvExport.build(listOf(Glucose(noon, 104, null, true, "3MH001HM1LM")), { it }, zone)
        assertEquals(
            listOf(CsvExport.HEADER, "glucose (1 minute),2026-01-01T12:00:00+03:00,104,5.8,104,,3MH001HM1LM", ""),
            lines(text)
        )
    }

    @Test
    fun valuesAreInOrderOfTimeAndSayTheirKind() {
        val values = listOf(
            Glucose(noon, 120, null, true, "S"),
            Glucose(noon - 30 * minute, 90, null, false, "S"),
            Glucose(noon - 15 * minute, 100, null, false, null)
        )
        val rows = lines(CsvExport.build(values, { it }, zone))
        assertEquals(5, rows.size)
        assertEquals("glucose (15 minutes),2026-01-01T11:30:00+03:00,90,5.0,90,,S", rows[1])
        assertEquals("glucose (15 minutes),2026-01-01T11:45:00+03:00,100,5.6,100,,", rows[2])
        assertTrue(rows[3].startsWith("glucose (1 minute),2026-01-01T12:00:00+03:00,120,"))
    }

    @Test
    fun theShownValueAndTheSensorValueAreBothWritten() {
        val text = CsvExport.build(listOf(Glucose(noon, 44, null, true, "S")), { Glucose(it.timestamp, 51, null, true, "S") }, zone)
        assertEquals("glucose (1 minute),2026-01-01T12:00:00+03:00,51,2.8,44,,S", lines(text)[1])
    }

    @Test
    fun lowAndHighAreSpelledOut() {
        val rows = lines(CsvExport.build(listOf(Glucose(noon, 20, null, true, "S"), Glucose(noon + minute, 600, null, true, "S")), { it }, zone))
        assertEquals("glucose (1 minute),2026-01-01T12:00:00+03:00,39,2.2,39,LOW: below 40 mg/dL,S", rows[1])
        assertEquals("glucose (1 minute),2026-01-01T12:01:00+03:00,501,27.8,501,HIGH: above 500 mg/dL,S", rows[2])
    }

    @Test
    fun aTimeWestOfLondonHasAMinusSign() {
        val text = CsvExport.build(listOf(Glucose(noon, 104, null, true, "S")), { it }, TimeZone.getTimeZone("America/New_York"))
        assertTrue(text, text.contains(",2026-01-01T04:00:00-05:00,"))
    }

    @Test
    fun cellsWithCommasQuotesAndFormulasAreMadeSafe() {
        assertEquals("plain", CsvExport.field("plain"))
        assertEquals("\"a,b\"", CsvExport.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.field("say \"hi\""))
        assertEquals("\"two\nlines\"", CsvExport.field("two\nlines"))
        assertEquals("'=1+1", CsvExport.field("=1+1"))
        assertEquals("'+1", CsvExport.field("+1"))
        assertEquals("'-1", CsvExport.field("-1"))
        assertEquals("'@x", CsvExport.field("@x"))
        assertEquals("", CsvExport.field(""))
    }
}
