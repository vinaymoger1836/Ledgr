package com.ledgr.app.domain.statement

/**
 * One glyph (or word) as a PDF text extractor reports it. Positions are 1/10 point, `y` measured from the top of the
 * page, so a later line has a larger `y`. The Android extractor and the JVM golden extractor both produce these; everything
 * after this point is shared, pure code (ADR-016).
 */
data class TextGlyph(val page: Int, val x: Int, val y: Int, val width: Int, val size: Int, val text: String)

/** A run of text on one line with no big gap in it: a candidate cell. [x] to [xEnd] is its horizontal extent. */
data class Phrase(val x: Int, val xEnd: Int, val text: String)

data class PdfLine(val page: Int, val y: Int, val phrases: List<Phrase>)

/** The header line of a PDF statement and the column dividers its phrases suggest (1/10 pt, left to right). */
data class PdfHeader(val lineIndex: Int, val phrases: List<Phrase>, val dividers: List<Int>) {
    /** The header texts, the same input the column-map signature uses for every other file type. */
    val cells: List<String> get() = phrases.map { it.text }
}

/**
 * docs/06 §2 (PDF): glyphs to lines by y, lines to phrases by x gaps, phrases to cells by column dividers. Dividers come from
 * the header row's positions (auto) or from the saved/dragged `pdf_columns`.
 */
object PdfLayout {
    /** Group glyphs into lines and phrases. Geometry thresholds are percentages of the font size from `statement_profiles.yaml`. */
    fun lines(glyphs: List<TextGlyph>, rules: PdfLayoutRules): List<PdfLine> {
        val out = ArrayList<PdfLine>()
        for ((page, onPage) in glyphs.groupBy { it.page }.toSortedMap()) {
            val sorted = onPage.filter { it.text.isNotEmpty() }.sortedWith(compareBy({ it.y }, { it.x }))
            var i = 0
            while (i < sorted.size) {
                val first = sorted[i]
                val tolerance = maxOf(1, first.size * rules.lineTolerancePct / 100)
                val cluster = ArrayList<TextGlyph>()
                while (i < sorted.size && sorted[i].y - first.y <= tolerance) cluster += sorted[i++]
                val phrases = phrases(cluster.sortedBy { it.x }, rules)
                if (phrases.isNotEmpty()) out += PdfLine(page, first.y, phrases)
            }
        }
        return out
    }

    private fun phrases(glyphs: List<TextGlyph>, rules: PdfLayoutRules): List<Phrase> {
        val result = ArrayList<Phrase>()
        var text = StringBuilder()
        var start = 0
        var end = 0
        var open = false
        fun close() {
            val t = text.toString().trim()
            if (open && t.isNotEmpty()) result += Phrase(start, end, t)
            text = StringBuilder()
            open = false
        }
        for (g in glyphs) {
            if (g.text.isBlank()) {
                // A real space glyph: remember the gap by moving on, and make sure a space separates what follows.
                if (open && !text.endsWith(" ")) text.append(' ')
                end = maxOf(end, g.x + g.width)
                continue
            }
            if (!open) {
                start = g.x
                open = true
            } else {
                val gap = g.x - end
                val phraseGap = g.size * rules.phraseGapPct / 100
                val spaceGap = g.size * rules.spaceGapPct / 100
                if (gap > phraseGap) {
                    close()
                    start = g.x
                    open = true
                } else if (gap > spaceGap && !text.endsWith(" ")) {
                    text.append(' ')
                }
            }
            text.append(g.text)
            end = maxOf(end, g.x + g.width)
        }
        close()
        return result
    }

    /** The first line that looks like a header (at least `min_header_hits` phrases that name a field), with auto dividers. */
    fun findHeader(lines: List<PdfLine>, profiles: StatementProfiles): PdfHeader? {
        val index = SynonymIndex(profiles)
        for ((i, line) in lines.withIndex()) {
            if (i >= profiles.headerScanRows * 3) break
            val hits = line.phrases.count { index.classify(it.text) != null }
            if (hits >= profiles.minHeaderHits) return PdfHeader(i, line.phrases, autoDividers(line.phrases))
        }
        return null
    }

    /** One divider in the middle of each gap between neighbouring header phrases. */
    fun autoDividers(phrases: List<Phrase>): List<Int> =
        phrases.zipWithNext { a, b -> (a.xEnd + b.x) / 2 }

    /** Cut every line into cells at [dividers]; a phrase belongs to the column its left edge falls in. */
    fun grid(lines: List<PdfLine>, dividers: List<Int>): Grid {
        val sorted = dividers.sorted()
        val rows = ArrayList<GridRow>(lines.size)
        for ((n, line) in lines.withIndex()) {
            val cells = MutableList(sorted.size + 1) { StringBuilder() }
            for (p in line.phrases) {
                val col = sorted.count { it <= p.x }
                if (cells[col].isNotEmpty()) cells[col].append(' ')
                cells[col].append(p.text)
            }
            var end = cells.size
            while (end > 0 && cells[end - 1].isEmpty()) end--
            if (end > 0) rows += GridRow(n + 1, cells.subList(0, end).map { it.toString().trim().take(StatementLimits.MAX_CELL_CHARS) })
        }
        if (rows.size > StatementLimits.MAX_ROWS) throw StatementException(StatementException.TOO_LARGE, "The PDF has too many lines")
        return Grid(rows)
    }
}
