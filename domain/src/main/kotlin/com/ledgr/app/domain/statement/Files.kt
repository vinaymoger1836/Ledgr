package com.ledgr.app.domain.statement

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Hard limits for untrusted statement files (CLAUDE.md rule 12). These are safety bounds, not rules data. */
object StatementLimits {
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    const val MAX_ZIP_ENTRIES = 200
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_TOTAL_INFLATED = 100L * 1024 * 1024

    /** A declared expansion ratio above this (for an entry over 1 MB) is refused before inflating anything. */
    const val MAX_RATIO = 200L
    const val MAX_ROWS = 200_000
    const val MAX_CELLS_PER_ROW = 200
    const val MAX_CELL_CHARS = 2_000

    /** Wall-clock budget for reading one file (the caller enforces it; docs/06, CLAUDE.md rule 12). */
    const val PARSE_TIMEOUT_MS = 60_000L
}

/** Decides the file type from the bytes (docs/06 §1.2). Never looks at a name or an extension. */
object FileSniffer {
    private val PDF_MAGIC = "%PDF".toByteArray(Charsets.US_ASCII)
    private val OLE_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    /** @throws StatementException `unsupported` for a type this app cannot read (other zips, HTML, binary), `too_large`, `no_rows` for an empty file. */
    fun detect(bytes: ByteArray): FileType {
        if (bytes.isEmpty()) throw StatementException(StatementException.NO_ROWS, "The file is empty")
        if (bytes.size > StatementLimits.MAX_FILE_BYTES) throw StatementException(StatementException.TOO_LARGE, "The file is larger than 20 MB")
        if (startsWithin(bytes, PDF_MAGIC, 1024)) return FileType.PDF
        if (bytes.size >= OLE_MAGIC.size && OLE_MAGIC.indices.all { bytes[it] == OLE_MAGIC[it] }) return FileType.XLS
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val names = try {
                SafeZip(bytes).names()
            } catch (e: StatementException) {
                throw e
            }
            if (names.any { it == "xl/workbook.xml" }) return FileType.XLSX
            throw StatementException(StatementException.UNSUPPORTED, "This zip file is not an Excel workbook")
        }
        val head = bytes.copyOf(minOf(bytes.size, 4096))
        if (looksBinary(head)) throw StatementException(StatementException.UNSUPPORTED, "This file is not a statement type the app can read")
        val text = String(head, Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\t', '\r', '\n').lowercase()
        if (text.startsWith("<") && ("<html" in text || "<table" in text || "<!doctype" in text)) {
            throw StatementException(StatementException.UNSUPPORTED, "This is a web page saved as a spreadsheet; export CSV or XLSX instead")
        }
        return FileType.CSV
    }

    private fun startsWithin(bytes: ByteArray, magic: ByteArray, window: Int): Boolean {
        val last = minOf(bytes.size - magic.size, window)
        for (i in 0..last) {
            if (magic.indices.all { bytes[i + it] == magic[it] }) return true
        }
        return false
    }

    /** UTF-16 text (BOM) is fine; otherwise NUL bytes mean it is not text. */
    private fun looksBinary(head: ByteArray): Boolean {
        if (head.size >= 2 && ((head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) || (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()))) return false
        return head.count { it == 0.toByte() } > head.size / 100
    }
}

/**
 * A minimal, defensive ZIP reader (docs/06 §1, CLAUDE.md rule 12). It reads the central directory, refuses absurd archives
 * before inflating, and counts bytes **while inflating**, so a lying header cannot overrun the limits.
 */
class SafeZip(private val bytes: ByteArray) {
    private class Entry(val name: String, val method: Int, val compSize: Long, val size: Long, val localOffset: Long)

    private val entries: List<Entry> = readDirectory()
    private var inflatedTotal = 0L

    fun names(): List<String> = entries.map { it.name }

    /** The bytes of [name] (inflated), or null when there is no such entry. */
    fun read(name: String): ByteArray? {
        val e = entries.firstOrNull { it.name == name } ?: return null
        return inflate(e)
    }

    private fun readDirectory(): List<Entry> {
        val eocd = findEocd() ?: throw StatementException(StatementException.BAD_ZIP, "The zip directory is missing")
        val count = u16(eocd + 10)
        val cdOffset = u32(eocd + 16)
        if (count > StatementLimits.MAX_ZIP_ENTRIES) throw StatementException(StatementException.ZIP_BOMB, "The workbook holds too many parts")
        if (cdOffset < 0 || cdOffset >= bytes.size) throw StatementException(StatementException.BAD_ZIP, "The zip directory is damaged")
        val result = ArrayList<Entry>(count)
        var p = cdOffset.toInt()
        repeat(count) {
            if (p + 46 > bytes.size || u32(p) != 0x02014b50L) throw StatementException(StatementException.BAD_ZIP, "The zip directory is damaged")
            val method = u16(p + 10)
            val comp = u32(p + 20)
            val size = u32(p + 24)
            val nameLen = u16(p + 28)
            val extraLen = u16(p + 30)
            val commentLen = u16(p + 32)
            val local = u32(p + 42)
            if (p + 46 + nameLen > bytes.size) throw StatementException(StatementException.BAD_ZIP, "The zip directory is damaged")
            val name = String(bytes, p + 46, nameLen, Charsets.UTF_8)
            if (size > StatementLimits.MAX_ENTRY_BYTES) throw StatementException(StatementException.ZIP_BOMB, "A part of the workbook is too large")
            if (size > 1024 * 1024 && comp > 0 && size / comp > StatementLimits.MAX_RATIO) {
                throw StatementException(StatementException.ZIP_BOMB, "A part of the workbook expands too much")
            }
            result += Entry(name, method, comp, size, local)
            p += 46 + nameLen + extraLen + commentLen
        }
        return result
    }

    private fun inflate(e: Entry): ByteArray {
        val h = e.localOffset.toInt()
        if (h < 0 || h + 30 > bytes.size || u32(h) != 0x04034b50L) throw StatementException(StatementException.BAD_ZIP, "A zip part is damaged")
        val start = h + 30 + u16(h + 26) + u16(h + 28)
        val end = start + e.compSize
        if (start < 0 || end > bytes.size) throw StatementException(StatementException.BAD_ZIP, "A zip part is damaged")
        val limit = minOf(StatementLimits.MAX_ENTRY_BYTES, StatementLimits.MAX_TOTAL_INFLATED - inflatedTotal)
        if (limit <= 0) throw StatementException(StatementException.ZIP_BOMB, "The workbook expands too much")
        val out = when (e.method) {
            0 -> {
                if (e.compSize > limit) throw StatementException(StatementException.ZIP_BOMB, "A part of the workbook is too large")
                bytes.copyOfRange(start, end.toInt())
            }
            8 -> inflateRaw(start, e.compSize.toInt(), limit)
            else -> throw StatementException(StatementException.UNSUPPORTED, "Unsupported zip compression")
        }
        inflatedTotal += out.size
        return out
    }

    private fun inflateRaw(start: Int, length: Int, limit: Long): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes, start, length)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (ex: DataFormatException) {
                    throw StatementException(StatementException.BAD_ZIP, "A zip part is damaged")
                }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > limit) throw StatementException(StatementException.ZIP_BOMB, "A part of the workbook expands too much")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun findEocd(): Int? {
        val min = maxOf(0, bytes.size - 22 - 65535)
        var i = bytes.size - 22
        while (i >= min) {
            if (u32(i) == 0x06054b50L) return i
            i--
        }
        return null
    }

    private fun u16(p: Int): Int = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)

    private fun u32(p: Int): Long =
        (u16(p).toLong()) or (u16(p + 2).toLong() shl 16)
}
