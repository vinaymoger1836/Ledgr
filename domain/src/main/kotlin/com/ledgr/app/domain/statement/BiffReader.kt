package com.ledgr.app.domain.statement

import java.io.ByteArrayOutputStream

/**
 * Legacy `.xls` (Excel 97-2003, BIFF8) in pure Kotlin (ADR-016 replaces ADR-008's POI HSSF). Reads the OLE2 container,
 * then the workbook stream: shared strings, number formats, the first visible worksheet's cells. Dates arrive as
 * `yyyy-MM-dd`, numbers as plain decimal text (decoded from the IEEE bits with big integers, never a `Double`).
 *
 * Out of scope and reported as `unsupported`: Excel 5/95 (BIFF5), password-protected workbooks.
 */
object BiffReader {
    fun read(bytes: ByteArray): Grid {
        val stream = Ole2(bytes).workbookStream()
        return Workbook(stream).firstSheet()
    }

    // ───────────────────────────── OLE2 compound file ─────────────────────────────

    private class Ole2(private val b: ByteArray) {
        private val sectorSize: Int
        private val miniSectorSize: Int
        private val fat: IntArray
        private val miniCutoff: Long
        private val dirStart: Int
        private val miniFatStart: Int

        init {
            if (b.size < 512) throw corrupt()
            val shift = u16(0x1E)
            val miniShift = u16(0x20)
            if (shift !in 7..16 || miniShift !in 4..12) throw corrupt()
            sectorSize = 1 shl shift
            miniSectorSize = 1 shl miniShift
            dirStart = i32(0x30)
            miniCutoff = u32(0x38)
            miniFatStart = i32(0x3C)
            val fatSectors = ArrayList<Int>()
            for (i in 0 until 109) {
                val sid = i32(0x4C + i * 4)
                if (sid >= 0) fatSectors += sid
            }
            var difat = i32(0x44)
            var guard = 0
            while (difat >= 0 && guard++ < 10_000) {
                val off = intOffset(sectorOffset(difat))
                val per = sectorSize / 4 - 1
                for (i in 0 until per) {
                    val sid = i32(off + i * 4)
                    if (sid >= 0) fatSectors += sid
                }
                difat = i32(off + per * 4)
            }
            val fatList = ArrayList<Int>()
            for (sid in fatSectors) {
                val off = intOffset(sectorOffset(sid))
                for (i in 0 until sectorSize / 4) fatList += i32(off + i * 4)
                if (fatList.size > 5_000_000) throw corrupt()
            }
            fat = fatList.toIntArray()
        }

        fun workbookStream(): ByteArray {
            val dir = chainBytes(dirStart, fat, sectorSize) { sectorOffset(it) }
            var root: Entry? = null
            var wanted: Entry? = null
            var i = 0
            while (i + 128 <= dir.size) {
                val nameLen = (dir[i + 64].toInt() and 0xFF) or ((dir[i + 65].toInt() and 0xFF) shl 8)
                val type = dir[i + 66].toInt()
                if (nameLen in 2..64) {
                    val name = String(dir, i, nameLen - 2, Charsets.UTF_16LE)
                    val start = le32(dir, i + 116)
                    val size = le32(dir, i + 120).toLong() and 0xFFFFFFFFL
                    val e = Entry(name, start, size)
                    if (type == 5) root = e
                    if (type == 2 && (name.equals("Workbook", true) || (wanted == null && name.equals("Book", true)))) wanted = e
                }
                i += 128
            }
            val entry = wanted ?: throw StatementException(StatementException.UNSUPPORTED, "This is not an Excel workbook")
            if (entry.size > StatementLimits.MAX_ENTRY_BYTES) throw StatementException(StatementException.ZIP_BOMB, "The workbook is too large")
            return if (entry.size < miniCutoff) {
                val rootEntry = root ?: throw corrupt()
                val mini = chainBytes(rootEntry.start, fat, sectorSize) { sectorOffset(it) }.copyOf(rootEntry.size.toInt().coerceAtMost(Int.MAX_VALUE))
                val miniFat = chainBytes(miniFatStart, fat, sectorSize) { sectorOffset(it) }
                val miniTable = IntArray(miniFat.size / 4) { le32(miniFat, it * 4) }
                readMini(mini, miniTable, entry, miniSectorSize)
            } else {
                chainBytes(entry.start, fat, sectorSize) { sectorOffset(it) }.copyOf(entry.size.toInt())
            }
        }

        private fun readMini(mini: ByteArray, table: IntArray, entry: Entry, size: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var sid = entry.start
            var steps = 0
            while (sid >= 0 && steps++ <= table.size) {
                val off = sid.toLong() * size
                if (off + size > mini.size) throw corrupt()
                out.write(mini, off.toInt(), size)
                sid = table.getOrNull(sid) ?: break
            }
            return out.toByteArray().let { it.copyOf(minOf(it.size.toLong(), entry.size).toInt()) }
        }

        private class Entry(val name: String, val start: Int, val size: Long)

        /** Follows [table] from [start]; [offsetOf] gives the byte offset of a sector in the file. */
        private fun chainBytes(start: Int, table: IntArray, size: Int, offsetOf: (Int) -> Long): ByteArray {
            val out = ByteArrayOutputStream()
            var sid = start
            var steps = 0
            while (sid >= 0 && steps++ <= table.size) {
                val off = offsetOf(sid)
                if (off < 0 || off + size > b.size) throw corrupt()
                out.write(b, off.toInt(), size)
                if (out.size() > StatementLimits.MAX_ENTRY_BYTES) throw StatementException(StatementException.ZIP_BOMB, "The workbook is too large")
                sid = table.getOrNull(sid) ?: break
            }
            return out.toByteArray()
        }

        private fun sectorOffset(sid: Int): Long = (sid.toLong() + 1) * sectorSize

        private fun intOffset(off: Long): Int = if (off < 0 || off > b.size) throw corrupt() else off.toInt()

        private fun u16(p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

        private fun i32(p: Int): Int {
            if (p < 0 || p + 4 > b.size) throw corrupt()
            return le32(b, p)
        }

        private fun u32(p: Int): Long = i32(p).toLong() and 0xFFFFFFFFL

        private fun le32(a: ByteArray, p: Int): Int =
            (a[p].toInt() and 0xFF) or ((a[p + 1].toInt() and 0xFF) shl 8) or
                ((a[p + 2].toInt() and 0xFF) shl 16) or ((a[p + 3].toInt() and 0xFF) shl 24)
    }

    private fun corrupt() = StatementException(StatementException.CORRUPT, "The workbook is damaged")

    // ───────────────────────────── BIFF8 workbook ─────────────────────────────

    private class Record(val type: Int, val start: Int, val length: Int)

    private class Workbook(private val s: ByteArray) {
        private var date1904 = false
        private val sst = ArrayList<String>()
        private val formatCodes = HashMap<Int, String>()
        private val xfFormat = ArrayList<Int>()
        private val sheets = ArrayList<SheetInfo>()

        private class SheetInfo(val offset: Int, val state: Int, val kind: Int)

        private fun u8(p: Int) = s[p].toInt() and 0xFF

        private fun u16(p: Int) = u8(p) or (u8(p + 1) shl 8)

        private fun i32(p: Int) = u16(p) or (u16(p + 2) shl 16)

        private fun records(from: Int): Sequence<Record> = sequence {
            var p = from
            while (p + 4 <= s.size) {
                val type = u16(p)
                val len = u16(p + 2)
                if (p + 4 + len > s.size) throw corrupt()
                yield(Record(type, p + 4, len))
                p += 4 + len
            }
        }

        fun firstSheet(): Grid {
            readGlobals()
            val sheet = sheets.firstOrNull { it.state == 0 && it.kind == 0 }
                ?: throw StatementException(StatementException.NO_ROWS, "The workbook has no visible sheet")
            return readSheet(sheet.offset)
        }

        // Globals: from the first BOF to its EOF.
        private fun readGlobals() {
            val all = records(0).toList()
            if (all.isEmpty() || all[0].type != 0x0809) throw StatementException(StatementException.UNSUPPORTED, "This is not an Excel workbook")
            if (u16(all[0].start) != 0x0600) {
                throw StatementException(StatementException.UNSUPPORTED, "This is an Excel 5/95 file; export CSV or XLSX instead")
            }
            var i = 1
            while (i < all.size) {
                val r = all[i]
                when (r.type) {
                    0x000A -> return
                    0x002F -> throw StatementException(StatementException.UNSUPPORTED, "This workbook is password protected; export CSV or XLSX instead")
                    0x0022 -> date1904 = u16(r.start) == 1
                    0x00E0 -> xfFormat += u16(r.start + 2)
                    0x041E -> {
                        val id = u16(r.start)
                        formatCodes[id] = readUnicodeString(r.start + 2, 2).first
                    }
                    0x0085 -> {
                        val offset = i32(r.start)
                        val state = u8(r.start + 4)
                        val kind = u8(r.start + 5)
                        sheets += SheetInfo(offset, state, kind)
                    }
                    0x00FC -> {
                        val segments = arrayListOf(r)
                        while (i + 1 < all.size && all[i + 1].type == 0x003C) segments += all[++i]
                        readSst(segments)
                    }
                }
                i++
            }
        }

        // ── strings ──

        /** A string with a [lenBytes]-byte character count, then a flags byte. Returns the text and the position after it. */
        private fun readUnicodeString(p: Int, lenBytes: Int): Pair<String, Int> {
            val cch = if (lenBytes == 1) u8(p) else u16(p)
            var q = p + lenBytes
            val flags = u8(q++)
            var runs = 0
            var ext = 0
            if (flags and 8 != 0) {
                runs = u16(q)
                q += 2
            }
            if (flags and 4 != 0) {
                ext = i32(q)
                q += 4
            }
            val wide = flags and 1 != 0
            val byteCount = if (wide) cch * 2 else cch
            if (q + byteCount > s.size) throw corrupt()
            val text = if (wide) String(s, q, byteCount, Charsets.UTF_16LE) else String(s, q, byteCount, Charsets.ISO_8859_1)
            q += byteCount + runs * 4 + ext
            return text to q
        }

        private class Cursor(val segments: List<Record>) {
            var seg = 0
            var pos = segments[0].start
            val end get() = segments[seg].start + segments[seg].length
            val atEnd get() = seg >= segments.size - 1 && pos >= end
        }

        private fun readSst(segments: List<Record>) {
            val c = Cursor(segments)
            c.pos += 8 // total + unique counts
            val unique = i32(segments[0].start + 4)
            val limit = minOf(unique, StatementLimits.MAX_ROWS * 4)
            fun ensure(): Boolean {
                while (c.pos >= c.end) {
                    if (c.seg >= c.segments.size - 1) return false
                    c.seg++
                    c.pos = c.segments[c.seg].start
                }
                return true
            }
            fun byte(): Int {
                if (!ensure()) throw corrupt()
                return u8(c.pos++)
            }
            fun skip(n: Int) {
                var left = n
                while (left > 0) {
                    if (!ensure()) return
                    val take = minOf(left, c.end - c.pos)
                    c.pos += take
                    left -= take
                }
            }
            var count = 0
            while (count < limit && ensure()) {
                val cch = byte() or (byte() shl 8)
                val flags = byte()
                var runs = 0
                var ext = 0
                if (flags and 8 != 0) runs = byte() or (byte() shl 8)
                if (flags and 4 != 0) ext = byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24)
                var wide = flags and 1 != 0
                val sb = StringBuilder(cch)
                var remaining = cch
                while (remaining > 0) {
                    if (c.pos >= c.end) {
                        if (c.seg >= c.segments.size - 1) throw corrupt()
                        c.seg++
                        c.pos = c.segments[c.seg].start
                        wide = u8(c.pos++) and 1 != 0 // a continued string restarts with its own flags byte
                    }
                    val width = if (wide) 2 else 1
                    val take = minOf(remaining, (c.end - c.pos) / width)
                    if (take <= 0) throw corrupt()
                    sb.append(if (wide) String(s, c.pos, take * 2, Charsets.UTF_16LE) else String(s, c.pos, take, Charsets.ISO_8859_1))
                    c.pos += take * width
                    remaining -= take
                }
                skip(runs * 4)
                skip(ext)
                sst += sb.toString()
                count++
            }
        }

        // ── the sheet ──

        private fun isDateXf(ixfe: Int): Boolean {
            val fmt = xfFormat.getOrNull(ixfe) ?: return false
            return SheetValues.isDateFormat(fmt, formatCodes[fmt])
        }

        private fun numberText(ixfe: Int, plain: String): String =
            if (isDateXf(ixfe)) SheetValues.isoDateOfSerial(plain, date1904) ?: plain else plain

        private fun readSheet(offset: Int): Grid {
            if (offset < 0 || offset >= s.size) throw corrupt()
            val cells = java.util.TreeMap<Int, java.util.TreeMap<Int, String>>()
            var pendingString: Pair<Int, Int>? = null
            var depth = 0
            fun put(row: Int, col: Int, text: String) {
                if (col >= StatementLimits.MAX_CELLS_PER_ROW || text.isEmpty()) return
                if (cells.size >= StatementLimits.MAX_ROWS && row !in cells) throw StatementException(StatementException.TOO_LARGE, "The sheet has too many rows")
                cells.getOrPut(row) { java.util.TreeMap() }[col] = text.take(StatementLimits.MAX_CELL_CHARS)
            }
            for (r in records(offset)) {
                val p = r.start
                when (r.type) {
                    0x0809 -> depth++
                    0x000A -> if (--depth <= 0) break
                    0x00FD -> put(u16(p), u16(p + 2), sst.getOrNull(i32(p + 6)).orEmpty())
                    0x0204 -> {
                        val cch = u16(p + 6)
                        val wide = u8(p + 8) and 1 != 0
                        val n = if (wide) cch * 2 else cch
                        if (p + 9 + n <= s.size) {
                            put(u16(p), u16(p + 2), if (wide) String(s, p + 9, n, Charsets.UTF_16LE) else String(s, p + 9, n, Charsets.ISO_8859_1))
                        }
                    }
                    0x0203 -> {
                        val bits = (i32(p + 6).toLong() and 0xFFFFFFFFL) or (i32(p + 10).toLong() shl 32)
                        put(u16(p), u16(p + 2), numberText(u16(p + 4), SheetValues.decimalOfBits(bits)))
                    }
                    0x027E -> put(u16(p), u16(p + 2), numberText(u16(p + 4), SheetValues.decimalOfRk(i32(p + 6))))
                    0x00BD -> {
                        val row = u16(p)
                        val first = u16(p + 2)
                        val n = (r.length - 6) / 6
                        for (k in 0 until n) {
                            val q = p + 4 + k * 6
                            put(row, first + k, numberText(u16(q), SheetValues.decimalOfRk(i32(q + 2))))
                        }
                    }
                    0x0006 -> {
                        val row = u16(p)
                        val col = u16(p + 2)
                        val ixfe = u16(p + 4)
                        if (u8(p + 12) == 0xFF && u8(p + 13) == 0xFF) {
                            if (u8(p + 6) == 0) pendingString = row to col
                        } else {
                            val bits = (i32(p + 6).toLong() and 0xFFFFFFFFL) or (i32(p + 10).toLong() shl 32)
                            put(row, col, numberText(ixfe, SheetValues.decimalOfBits(bits)))
                        }
                    }
                    0x0207 -> {
                        val target = pendingString
                        if (target != null && r.length >= 3) {
                            val cch = u16(p)
                            val wide = u8(p + 2) and 1 != 0
                            val n = if (wide) cch * 2 else cch
                            if (p + 3 + n <= s.size) {
                                put(target.first, target.second, if (wide) String(s, p + 3, n, Charsets.UTF_16LE) else String(s, p + 3, n, Charsets.ISO_8859_1))
                            }
                        }
                        pendingString = null
                    }
                }
            }
            val rows = ArrayList<GridRow>()
            for ((row, byCol) in cells) {
                val width = byCol.lastKey() + 1
                val list = ArrayList<String>(width)
                for (col in 0 until width) list += byCol[col].orEmpty().trim()
                if (list.any { it.isNotBlank() }) rows += GridRow(row + 1, list)
            }
            return Grid(rows)
        }
    }
}
