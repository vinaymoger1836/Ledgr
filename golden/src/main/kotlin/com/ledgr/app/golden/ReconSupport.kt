package com.ledgr.app.golden

import com.ledgr.app.domain.statement.Analysis
import com.ledgr.app.domain.statement.ColumnMap
import com.ledgr.app.domain.statement.FileSniffer
import com.ledgr.app.domain.statement.FileType
import com.ledgr.app.domain.statement.RawStatement
import com.ledgr.app.domain.statement.StatementAnalyser
import com.ledgr.app.domain.statement.StatementProfiles
import kotlinx.serialization.Serializable

@Serializable
internal data class MapDump(
    val fields: Map<String, Int>,
    val dateFormat: String,
    val amountStyle: String,
    val pdfColumns: List<Int>?,
)

@Serializable
internal data class LineDump(
    val ordinal: Int,
    val row: Int,
    val date: String,
    val valueDate: String?,
    val description: String,
    val ref: String?,
    val debit: Long,
    val credit: Long,
    val balance: Long?,
)

@Serializable
internal data class WarningDump(val code: String, val row: Int?, val detail: String)

/** The readable result of reading one statement file; what G-RECON-* pin. */
@Serializable
internal data class StatementDump(
    val type: String,
    val headerRow: Int?,
    val headerCells: List<String>,
    val signature: String?,
    val needsMapping: Boolean,
    val missing: List<String>,
    val map: MapDump?,
    val periodFrom: String?,
    val periodTo: String?,
    val opening: Long?,
    val closing: Long?,
    val skippedRows: Int,
    val warnings: List<WarningDump>,
    val lines: List<LineDump>,
)

internal fun ColumnMap.dump() = MapDump(fields.toSortedMap(), dateFormat, amountStyle.name, pdfColumns)

internal fun Analysis.dump(type: FileType): StatementDump {
    val s = statement
    return StatementDump(
        type = type.name,
        headerRow = header?.let { grid.rows[it.position].index },
        headerCells = header?.cells.orEmpty(),
        signature = signature,
        needsMapping = needsMapping,
        missing = guess?.missing?.map { it.key }.orEmpty(),
        map = map?.dump(),
        periodFrom = s?.periodFrom?.toString(),
        periodTo = s?.periodTo?.toString(),
        opening = s?.opening,
        closing = s?.closing,
        skippedRows = s?.skippedRows ?: 0,
        warnings = s?.warnings.orEmpty().map { WarningDump(it.code, it.row, it.detail) },
        lines = s?.lines.orEmpty().map {
            LineDump(it.ordinal, it.sourceRow, it.date.toString(), it.valueDate?.toString(), it.description, it.refNo, it.debit, it.credit, it.balance)
        },
    )
}

/** Detect by content, read, analyse: the same steps the app takes for CSV, XLSX and XLS. */
internal fun analyseTabular(bytes: ByteArray, profiles: StatementProfiles, bank: String?): Pair<FileType, Analysis> {
    val type = FileSniffer.detect(bytes)
    val grid = StatementAnalyser.read(type, bytes)
    return type to StatementAnalyser.analyse(RawStatement.Tabular(grid), profiles, bank)
}
