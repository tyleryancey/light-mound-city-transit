package moundcity.transit.core.gtfs

import java.io.StringReader
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Build plan 1.5: parse by header name, never by position — Metro's
 * stop_times.txt puts trip_id third. The feed is exported with a UTF-8 BOM
 * (Python reads it with encoding='utf-8-sig'), so the reader must strip it or
 * the first column's name silently becomes "﻿trip_id".
 */
class GtfsCsvTest {

    private fun rows(csv: String): List<List<String?>> {
        val out = mutableListOf<List<String?>>()
        GtfsCsv.forEachRow(StringReader(csv)) { row -> out.add(listOf(row["a"], row["b"])) }
        return out
    }

    @Test
    fun accessIsByHeaderNameNotPosition() {
        val out = mutableListOf<Pair<String, String>>()
        GtfsCsv.forEachRow(StringReader("x,trip_id,y\n1,T7,2\n")) { row ->
            out.add(row["trip_id"] to row["x"])
        }
        assertEquals(listOf("T7" to "1"), out, "columns must be addressed by name wherever they sit")
    }

    @Test
    fun utf8BomIsStrippedFromFirstHeaderName() {
        val out = mutableListOf<String>()
        GtfsCsv.forEachRow(StringReader("\uFEFFstop_id,stop_name\n42,Broadway\n")) { row ->
            out.add(row["stop_id"])
        }
        assertEquals(listOf("42"), out, "a BOM glued to the first header name must not hide the column")
    }

    @Test
    fun quotedFieldKeepsCommasAndEscapedQuotes() {
        val out = mutableListOf<String>()
        GtfsCsv.forEachRow(StringReader("a,b\n\"BROADWAY, 4 SOUTH\",\"say \"\"hi\"\"\"\n")) { row ->
            out.add(row["a"]); out.add(row["b"])
        }
        assertEquals(listOf("BROADWAY, 4 SOUTH", "say \"hi\""), out, "RFC 4180 quoting")
    }

    @Test
    fun crlfLineEndingsDoNotLeakCarriageReturnsIntoValues() {
        val out = mutableListOf<String>()
        GtfsCsv.forEachRow(StringReader("a,b\r\n1,2\r\n")) { row -> out.add(row["b"]) }
        assertEquals(listOf("2"), out, "trailing CR must be stripped from the last field of a CRLF row")
    }

    @Test
    fun bareCarriageReturnTerminatesTheRecordLikePythonsCsvModule() {
        // Review finding (Phase 1b): a lone CR is a row terminator to Python's csv
        // module; swallowing it silently would diverge the port from the oracle.
        val out = mutableListOf<Pair<String, String>>()
        GtfsCsv.forEachRow(StringReader("a,b\r1,2\r3,4")) { row -> out.add(row["a"] to row["b"]) }
        assertEquals(listOf("1" to "2", "3" to "4"), out, "classic-Mac CR line endings parse as two records")
    }

    @Test
    fun blankLinesAreSkippedLikePythonsCsvModule() {
        val out = mutableListOf<Pair<String, String>>()
        GtfsCsv.forEachRow(StringReader("a,b\n1,2\n\n3,4\n")) { row -> out.add(row["a"] to row["b"]) }
        assertEquals(listOf("1" to "2", "3" to "4"), out, "a blank line yields no record, matching csv.DictReader")
    }

    @Test
    fun unknownColumnThrows() {
        assertFailsWith<IllegalArgumentException>("asking for a column the file lacks must fail loudly") {
            GtfsCsv.forEachRow(StringReader("a,b\n1,2\n")) { row -> row["nope"] }
        }
    }

    @Test
    fun realStopTimesHeaderHasTripIdThirdAndReaderResolvesItByName() {
        ZipFile(FixturePaths.gtfsZip).use { zip ->
            val entry = zip.getEntry("stop_times.txt")
            var first: Triple<String, String, String>? = null
            zip.getInputStream(entry).bufferedReader().use { r ->
                GtfsCsv.forEachRow(r) { row ->
                    if (first == null) {
                        first = Triple(row["trip_id"], row["stop_id"], row["departure_time"])
                    }
                }
            }
            val (trip, stop, dep) = first!!
            assertEquals(true, trip.isNotEmpty() && stop.isNotEmpty(), "first row resolves trip_id and stop_id by name")
            assertEquals(true, dep.matches(Regex("""\d{1,2}:\d{2}:00""")), "departure_time is HH:MM:00 (A7)")
        }
    }

    @Test
    fun realCalendarParsesAllSevenRows() {
        ZipFile(FixturePaths.gtfsZip).use { zip ->
            var n = 0
            zip.getInputStream(zip.getEntry("calendar.txt")).bufferedReader().use { r ->
                GtfsCsv.forEachRow(r) { row -> row["service_id"]; n++ }
            }
            assertEquals(7, n, "the fixture calendar.txt has exactly 7 service rows")
        }
    }
}
