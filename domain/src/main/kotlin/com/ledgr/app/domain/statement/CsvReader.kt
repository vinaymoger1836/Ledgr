package com.ledgr.app.domain.statement

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * CSV and delimited text (docs/06 §2): BOM strip, delimiter sniffing (`,` `;` tab `|`), quoted fields with embedded
 * delimiters, quotes and line breaks. Blank rows are skipped but keep their number, so a row number always means the
 * record number in the file.
 */
object CsvReader {
    private val DELIMITERS = charArrayOf(',', ';', '\t', '|')

    fun read(bytes: ByteArray): Grid = parse(decode(bytes))

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            // Older bank exports are Windows-1252; ISO-8859-1 reads every byte and keeps digits and punctuation intact.
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    fun parse(text: String): Grid {
        val delimiter = sniff(text)
        val rows = ArrayList<GridRow>()
        var recordNo = 0
        var cells = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length

        fun endCell() {
            val s = cell.toString()
            cells.add(if (s.length > StatementLimits.MAX_CELL_CHARS) s.take(StatementLimits.MAX_CELL_CHARS) else s)
            cell.setLength(0)
            if (cells.size > StatementLimits.MAX_CELLS_PER_ROW) {
                throw StatementException(StatementException.CORRUPT, "A row has too many columns")
            }
        }

        fun endRecord() {
            endCell()
            recordNo++
            if (cells.any { it.isNotBlank() }) {
                if (rows.size >= StatementLimits.MAX_ROWS) throw StatementException(StatementException.TOO_LARGE, "The file has too many rows")
                rows += GridRow(recordNo, trimTrailingBlank(cells.map { it.trim() }))
            }
            cells = ArrayList()
        }

        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(c)
                }
            } else {
                when {
                    c == '"' && cell.isBlank() -> {
                        cell.setLength(0)
                        inQuotes = true
                    }
                    c == delimiter -> endCell()
                    c == '\r' -> {
                        if (i + 1 < n && text[i + 1] == '\n') i++
                        endRecord()
                    }
                    c == '\n' -> endRecord()
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || cells.isNotEmpty()) endRecord()
        return Grid(rows)
    }

    private fun trimTrailingBlank(cells: List<String>): List<String> {
        var end = cells.size
        while (end > 0 && cells[end - 1].isEmpty()) end--
        return cells.subList(0, end)
    }

    /** The delimiter that splits the most lines into the same, largest number of cells (outside quotes). */
    private fun sniff(text: String): Char {
        val lines = ArrayList<String>()
        var start = 0
        var inQuotes = false
        var i = 0
        // Walk once so a quoted line break does not split a record in the sample.
        while (i < text.length && lines.size < 30) {
            val c = text[i]
            if (c == '"') inQuotes = !inQuotes
            if (!inQuotes && (c == '\n' || c == '\r')) {
                if (i > start) lines += text.substring(start, i)
                start = i + 1
            }
            i++
        }
        if (start < text.length && lines.size < 30) lines += text.substring(start)
        var best = ','
        var bestScore = -1L
        for (d in DELIMITERS) {
            val counts = lines.map { countOutsideQuotes(it, d) }.filter { it > 0 }
            if (counts.isEmpty()) continue
            val mode = counts.groupingBy { it }.eachCount().entries.maxWith(compareBy({ it.value }, { it.key }))
            // Lines agreeing on the count matter most; then how many cells that gives.
            val score = mode.value.toLong() * 1000 + mode.key
            if (score > bestScore) {
                bestScore = score
                best = d
            }
        }
        return best
    }

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var count = 0
        var inQuotes = false
        for (c in line) {
            if (c == '"') inQuotes = !inQuotes else if (!inQuotes && c == d) count++
        }
        return count
    }
}
