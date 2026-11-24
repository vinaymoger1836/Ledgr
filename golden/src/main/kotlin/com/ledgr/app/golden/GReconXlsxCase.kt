package com.ledgr.app.golden

import com.ledgr.app.domain.statement.FileSniffer
import com.ledgr.app.domain.statement.StatementException
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.apache.poi.hssf.usermodel.HSSFWorkbook

@Serializable
internal data class XRow(val date: String, val text: String, val ref: String, val amount: String, val balance: String)

@Serializable
internal data class XSpec(
    val title: String,
    val bank: String,
    val preamble: List<String>,
    val header: List<String>,
    val fillerStrings: Int,
    val rows: List<XRow>,
)

@Serializable
internal data class XlsxCaseOut(
    val xlsx: StatementDump,
    val xlsType: String,
    val xlsLinesEqualXlsxLines: Boolean,
    val xlsHeaderRowEqual: Boolean,
    val guards: Map<String, String>,
)

/**
 * G-RECON-XLSX (docs/06 §5): Excel serial dates (built-in and custom date formats), signed amounts, a float-noise amount,
 * a hidden first sheet, and a shared-string table big enough to need CONTINUE records in the `.xls` twin. The same data
 * written as a legacy `.xls` (authored with POI, inside `:golden` only) must read to identical lines through the own BIFF8 reader.
 * Also pins the size, zip-bomb and file-type guards.
 */
internal object GReconXlsxCase : GoldenCase {
    override val id = "G-RECON-XLSX"
    override val expectedFile = "g-recon-xlsx.json"

    override fun run(context: GoldenContext): String {
        val profiles = GoldenRules.load(context).statementProfiles
        val spec = GoldenJson.json.decodeFromString(XSpec.serializer(), File(context.fixturesDir, "recon/xlsx/statement.json").readText())
        val xlsxBytes = buildXlsx(spec)
        val xlsBytes = buildXls(spec)

        val (xlsxType, xlsx) = analyseTabular(xlsxBytes, profiles, spec.bank)
        val (xlsType, xls) = analyseTabular(xlsBytes, profiles, spec.bank)
        val xlsxDump = xlsx.dump(xlsxType)
        val xlsDump = xls.dump(xlsType)

        val guards = sortedMapOf<String, String>()
        guards["empty"] = codeOf { FileSniffer.detect(ByteArray(0)) }
        guards["html"] = codeOf { FileSniffer.detect("<html><body><table><tr><td>x</td></tr></table></body></html>".toByteArray()) }
        guards["tooLarge"] = codeOf { FileSniffer.detect(ByteArray(20 * 1024 * 1024 + 1) { 'a'.code.toByte() }) }
        guards["otherZip"] = codeOf { FileSniffer.detect(zip("word/document.xml" to "x".toByteArray())) }
        guards["zipBomb"] = codeOf { FileSniffer.detect(zip("xl/workbook.xml" to ByteArray(70 * 1024 * 1024))) }
        guards["notAZipDirectory"] = codeOf { FileSniffer.detect(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0, 0, 0)) }

        return GoldenJson.finish(
            GoldenJson.json.encodeToString(
                XlsxCaseOut(
                    xlsx = xlsxDump,
                    xlsType = xlsType.name,
                    xlsLinesEqualXlsxLines = xlsDump.lines == xlsxDump.lines && xlsDump.map == xlsxDump.map,
                    xlsHeaderRowEqual = xlsDump.headerRow == xlsxDump.headerRow && xlsDump.signature == xlsxDump.signature,
                    guards = guards,
                ),
            ),
        )
    }

    private fun codeOf(block: () -> Unit): String =
        try {
            block()
            "accepted"
        } catch (e: StatementException) {
            e.code
        }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.setLevel(Deflater.BEST_COMPRESSION)
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun serial(date: String): Long = LocalDate.parse(date).toEpochDay() - LocalDate.of(1899, 12, 30).toEpochDay()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun filler(i: Int) = "Filler row $i ".padEnd(36, 'x') + if (i == 5) " ₹ rupee" else ""

    /** The workbook has a hidden first sheet (listed first, stored as sheet2.xml) and the statement as the visible one. */
    private fun buildXlsx(spec: XSpec): ByteArray {
        val strings = ArrayList<String>()
        fun si(text: String): Int {
            strings += text
            return strings.size - 1
        }
        val hidden = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        for (i in 1..spec.fillerStrings) hidden.append("""<row r="$i"><c r="A$i" t="s"><v>${si(filler(i))}</v></c></row>""")
        hidden.append("</sheetData></worksheet>")

        val sheet = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        var r = 1
        fun textRow(vararg cells: String) {
            sheet.append("""<row r="$r">""")
            cells.forEachIndexed { c, t -> sheet.append("""<c r="${'A' + c}$r" t="s"><v>${si(t)}</v></c>""") }
            sheet.append("</row>")
            r++
        }
        textRow(spec.title)
        spec.preamble.forEach { textRow(it) }
        r++ // a blank row
        textRow(*spec.header.toTypedArray())
        spec.rows.forEachIndexed { i, row ->
            val dateStyle = if (i < 2) 1 else 2
            sheet.append("""<row r="$r"><c r="A$r" s="$dateStyle"><v>${serial(row.date)}</v></c>""")
            sheet.append("""<c r="B$r" t="s"><v>${si(row.text)}</v></c>""")
            if (row.ref.isNotEmpty()) sheet.append("""<c r="C$r" t="s"><v>${si(row.ref)}</v></c>""")
            sheet.append("""<c r="D$r" s="3"><v>${row.amount}</v></c><c r="E$r" s="3"><v>${row.balance}</v></c></row>""")
            r++
        }
        sheet.append("</sheetData></worksheet>")

        val sst = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${strings.size}" uniqueCount="${strings.size}">""")
        for (s in strings) sst.append("<si><t xml:space=\"preserve\">${esc(s)}</t></si>")
        sst.append("</sst>")

        val styles = """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<numFmts count="1"><numFmt numFmtId="164" formatCode="dd\-mmm\-yy"/></numFmts>""" +
            """<cellStyleXfs count="1"><xf numFmtId="14"/></cellStyleXfs>""" +
            """<cellXfs count="4"><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="164"/><xf numFmtId="4"/></cellXfs></styleSheet>"""
        val workbook = """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<workbookPr/><sheets><sheet name="Archive" sheetId="1" state="hidden" r:id="rId1"/><sheet name="Statement" sheetId="2" r:id="rId2"/></sheets></workbook>"""
        val rels = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId2" Type="worksheet" Target="/xl/worksheets/sheet1.xml"/></Relationships>"""
        return zip(
            "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray(),
            "xl/workbook.xml" to workbook.toByteArray(),
            "xl/_rels/workbook.xml.rels" to rels.toByteArray(),
            "xl/styles.xml" to styles.toByteArray(),
            "xl/sharedStrings.xml" to sst.toString().toByteArray(),
            "xl/worksheets/sheet1.xml" to sheet.toString().toByteArray(),
            "xl/worksheets/sheet2.xml" to hidden.toString().toByteArray(),
        )
    }

    /** The same workbook as legacy BIFF8, authored with POI HSSF (golden only). */
    private fun buildXls(spec: XSpec): ByteArray {
        HSSFWorkbook().use { wb ->
            val archive = wb.createSheet("Archive")
            for (i in 1..spec.fillerStrings) archive.createRow(i - 1).createCell(0).setCellValue(filler(i))
            val sheet = wb.createSheet("Statement")
            wb.setSheetHidden(0, true)
            wb.setActiveSheet(1)
            val builtinDate = wb.createCellStyle().apply { dataFormat = 14 }
            val customDate = wb.createCellStyle().apply { dataFormat = wb.createDataFormat().getFormat("dd\\-mmm\\-yy") }
            val number = wb.createCellStyle().apply { dataFormat = 4 }
            var r = 0
            fun textRow(vararg cells: String) {
                val row = sheet.createRow(r++)
                cells.forEachIndexed { c, t -> row.createCell(c).setCellValue(t) }
            }
            textRow(spec.title)
            spec.preamble.forEach { textRow(it) }
            r++
            textRow(*spec.header.toTypedArray())
            spec.rows.forEachIndexed { i, x ->
                val row = sheet.createRow(r++)
                row.createCell(0).apply {
                    setCellValue(LocalDate.parse(x.date))
                    cellStyle = if (i < 2) builtinDate else customDate
                }
                row.createCell(1).setCellValue(x.text)
                if (x.ref.isNotEmpty()) row.createCell(2).setCellValue(x.ref)
                row.createCell(3).apply {
                    setCellValue(BigDecimal(x.amount).toDouble())
                    cellStyle = number
                }
                row.createCell(4).apply {
                    setCellValue(BigDecimal(x.balance).toDouble())
                    cellStyle = number
                }
            }
            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        }
    }
}
