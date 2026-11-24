package com.ledgr.app.domain.statement

import java.io.ByteArrayInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/**
 * XLSX (docs/06 §2, ADR-008): unzip, then read `sharedStrings.xml`, `styles.xml`, `workbook.xml` and the first visible
 * sheet with an `XmlPullParser`. Date cells come out as `yyyy-MM-dd`; numbers as plain decimal text (never a `Double`).
 * DOCTYPE and entity processing are off, so an XML bomb or external entity has nothing to work with.
 */
object XlsxReader {
    fun read(bytes: ByteArray, newParser: () -> XmlPullParser = ::defaultParser): Grid {
        val zip = SafeZip(bytes)
        val workbook = zip.read("xl/workbook.xml") ?: throw StatementException(StatementException.CORRUPT, "The workbook has no sheets")
        val sheetsInfo = readWorkbook(workbook, newParser)
        val rels = zip.read("xl/_rels/workbook.xml.rels")?.let { readRels(it, newParser) }.orEmpty()
        val shared = zip.read("xl/sharedStrings.xml")?.let { readSharedStrings(it, newParser) }.orEmpty()
        val styles = zip.read("xl/styles.xml")?.let { readStyles(it, newParser) } ?: DateStyles(emptySet())

        val firstVisible = sheetsInfo.sheets.firstOrNull { it.state == "visible" || it.state.isEmpty() }
            ?: throw StatementException(StatementException.NO_ROWS, "The workbook has no visible sheet")
        val path = firstVisible.relId?.let { rels[it] }?.let(::resolveTarget) ?: "xl/worksheets/sheet1.xml"
        val sheet = zip.read(path) ?: throw StatementException(StatementException.CORRUPT, "The first sheet is missing from the workbook")
        return readSheet(sheet, shared, styles, sheetsInfo.date1904, newParser)
    }

    fun defaultParser(): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        } catch (e: XmlPullParserException) {
            // Some parsers have no such switch; they do not resolve external entities either.
        }
        return parser
    }

    private class Sheet(val name: String, val state: String, val relId: String?)

    private class WorkbookInfo(val sheets: List<Sheet>, val date1904: Boolean)

    private class DateStyles(val dateXf: Set<Int>)

    private fun resolveTarget(target: String): String {
        val t = target.removePrefix("/")
        return if (t.startsWith("xl/")) t else "xl/$t"
    }

    private fun parse(bytes: ByteArray, newParser: () -> XmlPullParser, body: (XmlPullParser) -> Unit) {
        try {
            val p = newParser()
            p.setInput(ByteArrayInputStream(bytes), null)
            body(p)
        } catch (e: XmlPullParserException) {
            throw StatementException(StatementException.CORRUPT, "The workbook is damaged")
        } catch (e: java.io.IOException) {
            throw StatementException(StatementException.CORRUPT, "The workbook is damaged")
        }
    }

    private fun local(name: String?): String = name?.substringAfter(':').orEmpty()

    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i) == name) return p.getAttributeValue(i)
        }
        return null
    }

    /** `r:id` with whatever prefix the writer chose. */
    private fun relAttr(p: XmlPullParser): String? {
        for (i in 0 until p.attributeCount) {
            val n = p.getAttributeName(i)
            if (n == "id" || n.endsWith(":id")) return p.getAttributeValue(i)
        }
        return null
    }

    private fun readWorkbook(bytes: ByteArray, newParser: () -> XmlPullParser): WorkbookInfo {
        val sheets = ArrayList<Sheet>()
        var date1904 = false
        parse(bytes, newParser) { p ->
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (local(p.name)) {
                        "workbookPr" -> date1904 = attr(p, "date1904") in setOf("1", "true")
                        "sheet" -> sheets += Sheet(attr(p, "name").orEmpty(), attr(p, "state") ?: "visible", relAttr(p))
                    }
                }
                event = p.next()
            }
        }
        return WorkbookInfo(sheets, date1904)
    }

    private fun readRels(bytes: ByteArray, newParser: () -> XmlPullParser): Map<String, String> {
        val map = HashMap<String, String>()
        parse(bytes, newParser) { p ->
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && local(p.name) == "Relationship") {
                    val id = attr(p, "Id")
                    val target = attr(p, "Target")
                    if (id != null && target != null) map[id] = target
                }
                event = p.next()
            }
        }
        return map
    }

    private fun readSharedStrings(bytes: ByteArray, newParser: () -> XmlPullParser): List<String> {
        val strings = ArrayList<String>()
        parse(bytes, newParser) { p ->
            var current: StringBuilder? = null
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (local(p.name)) {
                        "si" -> current = StringBuilder()
                        "rPh" -> skipSubtree(p)
                        "t" -> current?.append(p.nextText())
                    }
                } else if (event == XmlPullParser.END_TAG && local(p.name) == "si") {
                    strings += current?.toString().orEmpty()
                    current = null
                    if (strings.size > StatementLimits.MAX_ROWS * 4) throw StatementException(StatementException.TOO_LARGE, "The workbook has too many strings")
                }
                event = p.next()
            }
        }
        return strings
    }

    private fun skipSubtree(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun readStyles(bytes: ByteArray, newParser: () -> XmlPullParser): DateStyles {
        val codes = HashMap<Int, String>()
        val xfFormat = ArrayList<Int>()
        parse(bytes, newParser) { p ->
            var inCellXfs = false
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (local(p.name)) {
                        "numFmt" -> {
                            val id = attr(p, "numFmtId")?.toIntOrNull()
                            val code = attr(p, "formatCode")
                            if (id != null && code != null) codes[id] = code
                        }
                        "cellXfs" -> inCellXfs = true
                        "xf" -> if (inCellXfs) xfFormat += attr(p, "numFmtId")?.toIntOrNull() ?: 0
                    }
                } else if (event == XmlPullParser.END_TAG && local(p.name) == "cellXfs") {
                    inCellXfs = false
                }
                event = p.next()
            }
        }
        val dateXf = xfFormat.indices.filter { SheetValues.isDateFormat(xfFormat[it], codes[xfFormat[it]]) }.toSet()
        return DateStyles(dateXf)
    }

    private fun columnOf(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') col = col * 26 + (ch - 'A' + 1) else if (ch in 'a'..'z') col = col * 26 + (ch - 'a' + 1) else break
        }
        return col - 1
    }

    private fun readSheet(
        bytes: ByteArray,
        shared: List<String>,
        styles: DateStyles,
        date1904: Boolean,
        newParser: () -> XmlPullParser,
    ): Grid {
        val rows = ArrayList<GridRow>()
        parse(bytes, newParser) { p ->
            var rowNo = 0
            var cells = ArrayList<String>()
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (local(p.name)) {
                        "row" -> {
                            rowNo = attr(p, "r")?.toIntOrNull() ?: (rowNo + 1)
                            cells = ArrayList()
                        }
                        "c" -> {
                            val col = attr(p, "r")?.let(::columnOf)?.takeIf { it >= 0 } ?: cells.size
                            if (col >= StatementLimits.MAX_CELLS_PER_ROW) {
                                skipSubtree(p)
                            } else {
                                val value = readCell(p, attr(p, "t").orEmpty(), attr(p, "s")?.toIntOrNull(), shared, styles, date1904)
                                while (cells.size < col) cells += ""
                                if (cells.size == col) cells += value.take(StatementLimits.MAX_CELL_CHARS)
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && local(p.name) == "row") {
                    if (cells.any { it.isNotBlank() }) {
                        if (rows.size >= StatementLimits.MAX_ROWS) throw StatementException(StatementException.TOO_LARGE, "The sheet has too many rows")
                        var end = cells.size
                        while (end > 0 && cells[end - 1].isEmpty()) end--
                        rows += GridRow(rowNo, cells.subList(0, end).map { it.trim() })
                    }
                }
                event = p.next()
            }
        }
        return Grid(rows)
    }

    /** Reads one `<c>` element up to and including its end tag. */
    private fun readCell(
        p: XmlPullParser,
        type: String,
        style: Int?,
        shared: List<String>,
        styles: DateStyles,
        date1904: Boolean,
    ): String {
        var v: String? = null
        val inline = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    when (local(p.name)) {
                        "v" -> {
                            v = p.nextText()
                        }
                        "t" -> inline.append(p.nextText())
                        "f" -> skipSubtree(p)
                        "rPh" -> skipSubtree(p)
                        else -> depth++
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return ""
            }
        }
        return when (type) {
            "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
            "inlineStr" -> inline.toString()
            "str" -> v.orEmpty()
            "b" -> if (v?.trim() == "1") "TRUE" else "FALSE"
            "e" -> ""
            "d" -> v.orEmpty().trim().take(10)
            else -> {
                val raw = v?.trim().orEmpty()
                if (raw.isEmpty()) {
                    inline.toString()
                } else if (style != null && style in styles.dateXf) {
                    SheetValues.isoDateOfSerial(raw, date1904) ?: SheetValues.plain(raw)
                } else {
                    SheetValues.plain(raw)
                }
            }
        }
    }
}
