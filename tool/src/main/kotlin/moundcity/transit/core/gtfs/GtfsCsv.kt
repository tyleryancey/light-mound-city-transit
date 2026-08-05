package moundcity.transit.core.gtfs

import java.io.Reader

/** One parsed CSV record, addressed by header name only (build plan 1.5). */
class GtfsRow internal constructor(
    private val columns: Map<String, Int>,
    private val cells: List<String>,
) {
    /** Missing trailing cells read as empty; an unknown column name is a caller bug. */
    operator fun get(name: String): String {
        val idx = columns[name] ?: throw IllegalArgumentException(
            "no column '$name'; file has ${columns.keys}"
        )
        return if (idx < cells.size) cells[idx] else ""
    }
}

/**
 * RFC 4180 reader for GTFS files: quoted fields may contain commas, doubled
 * quotes, and line breaks; rows end in LF or CRLF; a UTF-8 BOM on the first
 * header name is stripped (the feed is exported with one).
 */
object GtfsCsv {

    fun forEachRow(reader: Reader, onRow: (GtfsRow) -> Unit) {
        var columns: Map<String, Int>? = null
        parseRecords(reader) { cells ->
            val cols = columns
            if (cols == null) {
                val header = cells.mapIndexed { i, name ->
                    (if (i == 0) name.removePrefix("\uFEFF") else name) to i
                }.toMap()
                columns = header
            } else {
                onRow(GtfsRow(cols, cells))
            }
        }
    }

    private fun parseRecords(reader: Reader, onRecord: (List<String>) -> Unit) {
        val field = StringBuilder()
        var cells = mutableListOf<String>()
        var inQuotes = false
        var sawAnything = false

        fun endField() {
            cells.add(field.toString())
            field.setLength(0)
        }

        fun endRecord() {
            endField()
            onRecord(cells)
            cells = mutableListOf()
            sawAnything = false
        }

        var c = reader.read()
        while (c != -1) {
            val ch = c.toChar()
            if (inQuotes) {
                when (ch) {
                    '"' -> {
                        val next = reader.read()
                        if (next != -1 && next.toChar() == '"') {
                            field.append('"')
                        } else {
                            inQuotes = false
                            c = next
                            continue
                        }
                    }
                    else -> field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> if (field.isEmpty()) inQuotes = true else field.append(ch)
                    ',' -> { endField(); sawAnything = true }
                    '\r' -> { /* consumed; the LF that follows ends the record */ }
                    '\n' -> endRecord()
                    else -> { field.append(ch); sawAnything = true }
                }
            }
            c = reader.read()
        }
        if (field.isNotEmpty() || cells.isNotEmpty() || sawAnything) endRecord()
    }
}
