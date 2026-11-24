package com.ledgr.app.golden

import com.ledgr.app.domain.statement.ColumnMap
import com.ledgr.app.domain.statement.FileSniffer
import com.ledgr.app.domain.statement.FileType
import com.ledgr.app.domain.statement.PdfLayout
import com.ledgr.app.domain.statement.RawStatement
import com.ledgr.app.domain.statement.StatementAnalyser
import com.ledgr.app.domain.statement.StatementException
import com.ledgr.app.domain.statement.TextGlyph
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

@Serializable
internal data class PdfEntry(val date: String, val text: List<String>, val ref: String, val debit: String, val credit: String, val balance: String)

@Serializable
internal data class PdfColumnsSpec(val date: Int, val narration: Int, val ref: Int, val debitRight: Int, val creditRight: Int, val balanceRight: Int)

@Serializable
internal data class PdfSpec(
    val bank: String,
    val password: String,
    val wrongPassword: String,
    val columns: PdfColumnsSpec,
    val header: List<String>,
    val pages: List<List<PdfEntry>>,
)

@Serializable
internal data class PhraseDump(val text: String, val x: Int, val xEnd: Int)

@Serializable
internal data class PdfCaseOut(
    val noPassword: String,
    val wrongPassword: String,
    val pageCount: Int,
    val headerPhrases: List<PhraseDump>,
    val autoDividers: List<Int>,
    val statement: StatementDump,
    val savedMapSkipsMapping: Boolean,
    val savedMapSameLines: Boolean,
    val draggedDividersSameLines: Boolean,
)

/**
 * G-RECON-PDF (docs/06 §5): a synthetic three-page PDF with a repeated header, page furniture, right-aligned amounts and
 * narrations that wrap onto a second and third line, encrypted with the password `test1234`. The PDF is generated at run
 * time with Apache PDFBox (golden only); glyph positions are read back with its text stripper and fed through the same
 * pure `:domain` layout and normaliser the phone uses. The phone's PdfBox-Android adapter differs only in package names.
 */
internal object GReconPdfCase : GoldenCase {
    override val id = "G-RECON-PDF"
    override val expectedFile = "g-recon-pdf.json"

    override fun run(context: GoldenContext): String {
        val profiles = GoldenRules.load(context).statementProfiles
        val spec = GoldenJson.json.decodeFromString(PdfSpec.serializer(), File(context.fixturesDir, "recon/pdf/statement.json").readText())
        val bytes = build(spec)
        check(FileSniffer.detect(bytes) == FileType.PDF)

        val noPassword = codeOf { extract(bytes, null) }
        val wrong = codeOf { extract(bytes, spec.wrongPassword) }
        val (pageCount, glyphs) = extract(bytes, spec.password)

        val lines = PdfLayout.lines(glyphs, profiles.pdf)
        val header = PdfLayout.findHeader(lines, profiles) ?: error("no header found in the generated PDF")
        val analysis = StatementAnalyser.analyse(RawStatement.Pdf(lines), profiles, spec.bank)
        val map = analysis.map ?: error("the generated PDF did not map by itself")
        val dump = analysis.dump(FileType.PDF)

        val again = StatementAnalyser.analyse(RawStatement.Pdf(lines), profiles, spec.bank, lookup = { sig -> if (sig == analysis.signature) map else null })
        val shifted = map.copy(pdfColumns = map.pdfColumns!!.map { it + 20 })
        val dragged = StatementAnalyser.analyse(RawStatement.Pdf(lines), profiles, spec.bank, override = shifted)

        return GoldenJson.finish(
            GoldenJson.json.encodeToString(
                PdfCaseOut(
                    noPassword = noPassword,
                    wrongPassword = wrong,
                    pageCount = pageCount,
                    headerPhrases = header.phrases.map { PhraseDump(it.text, it.x, it.xEnd) },
                    autoDividers = header.dividers,
                    statement = dump,
                    savedMapSkipsMapping = !again.needsMapping,
                    savedMapSameLines = again.dump(FileType.PDF).lines == dump.lines,
                    draggedDividersSameLines = dragged.dump(FileType.PDF).lines == dump.lines,
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

    /** What the JVM extractor does; the Android one maps `InvalidPasswordException` the same way. */
    fun extract(bytes: ByteArray, password: String?): Pair<Int, List<TextGlyph>> {
        val doc = try {
            Loader.loadPDF(bytes, password ?: "")
        } catch (e: InvalidPasswordException) {
            throw if (password == null) StatementException(StatementException.NEEDS_PASSWORD, "This PDF needs a password")
            else StatementException(StatementException.WRONG_PASSWORD, "That password did not open the PDF")
        }
        doc.use {
            val stripper = GlyphStripper()
            stripper.getText(it)
            return it.numberOfPages to stripper.glyphs
        }
    }

    private class GlyphStripper : PDFTextStripper() {
        val glyphs = ArrayList<TextGlyph>()

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (tp in textPositions) {
                glyphs += TextGlyph(
                    page = currentPageNo,
                    x = (tp.xDirAdj * 10).roundToInt(),
                    y = (tp.yDirAdj * 10).roundToInt(),
                    width = (tp.widthDirAdj * 10).roundToInt(),
                    size = (tp.fontSizeInPt * 10).roundToInt(),
                    text = tp.unicode,
                )
            }
        }
    }

    // ───────────────────────────── authoring the PDF ─────────────────────────────

    private const val FONT_SIZE = 8f

    private fun build(spec: PdfSpec): ByteArray {
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val c = spec.columns
            for ((pageIndex, entries) in spec.pages.withIndex()) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    fun text(x: Float, y: Float, s: String) {
                        cs.beginText()
                        cs.setFont(font, FONT_SIZE)
                        cs.newLineAtOffset(x, y)
                        cs.showText(s)
                        cs.endText()
                    }

                    fun right(edge: Int, y: Float, s: String) {
                        if (s.isNotEmpty()) text(edge - font.getStringWidth(s) / 1000f * FONT_SIZE, y, s)
                    }
                    text(c.date.toFloat(), 800f, "HDFC BANK LTD - Account Statement")
                    var y = 770f
                    text(c.date.toFloat(), y, spec.header[0])
                    text(c.narration.toFloat(), y, spec.header[1])
                    text(c.ref.toFloat(), y, spec.header[2])
                    right(c.debitRight, y, spec.header[3])
                    right(c.creditRight, y, spec.header[4])
                    right(c.balanceRight, y, spec.header[5])
                    y -= 18f
                    for (e in entries) {
                        text(c.date.toFloat(), y, e.date)
                        text(c.narration.toFloat(), y, e.text.first())
                        text(c.ref.toFloat(), y, e.ref)
                        right(c.debitRight, y, e.debit)
                        right(c.creditRight, y, e.credit)
                        right(c.balanceRight, y, e.balance)
                        for (more in e.text.drop(1)) {
                            y -= 10f
                            text(c.narration.toFloat(), y, more)
                        }
                        y -= 18f
                    }
                    text(250f, 40f, "Page ${pageIndex + 1} of ${spec.pages.size}")
                }
            }
            val policy = StandardProtectionPolicy("owner-secret-9", spec.password, AccessPermission())
            policy.encryptionKeyLength = 128
            policy.isPreferAES = true
            doc.protect(policy)
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }
}
