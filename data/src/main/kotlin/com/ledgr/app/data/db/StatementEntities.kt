package com.ledgr.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgr.app.domain.statement.FileType
import com.ledgr.app.domain.statement.MatchStatus

/**
 * docs/05 `column_map` (schema v6, P6, ADR-016): which column of a bank's statement is what, saved so the next import with the
 * same header needs no mapping. [mapping] is the JSON of `ColumnMap` without its PDF columns; [pdfColumns] is a JSON list of
 * divider positions (1/10 pt) for a PDF, else null. Unique per bank, file type and header signature.
 */
@Entity(
    tableName = "column_map",
    indices = [Index(value = ["bank_id", "file_type", "header_signature"], unique = true)],
)
data class ColumnMapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_id") val bankId: String,
    @ColumnInfo(name = "file_type") val fileType: FileType,
    @ColumnInfo(name = "header_signature") val headerSignature: String,
    val mapping: String,
    @ColumnInfo(name = "pdf_columns") val pdfColumns: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * docs/05 `statement_import`: one imported statement. Dates are IST `yyyy-MM-dd`; money is paise. The statement file itself is
 * deleted after parsing (docs/09 §1); only these rows stay. [warningsJson] keeps the normaliser's and the integrity check's
 * warnings so the results screen can show them again.
 */
@Entity(
    tableName = "statement_import",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["account_id", "imported_at"])],
)
data class StatementImportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "bank_id") val bankId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_type") val fileType: FileType,
    @ColumnInfo(name = "period_from") val periodFrom: String,
    @ColumnInfo(name = "period_to") val periodTo: String,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "column_map_id") val columnMapId: Long?,
    @ColumnInfo(name = "opening_balance") val openingBalance: Long?,
    @ColumnInfo(name = "closing_balance") val closingBalance: Long?,
    @ColumnInfo(name = "line_count") val lineCount: Int,
    @ColumnInfo(name = "skipped_rows", defaultValue = "0") val skippedRows: Int = 0,
    @ColumnInfo(name = "warnings_json", defaultValue = "[]") val warningsJson: String = "[]",
    /** Share of statement lines paired with a ledger transaction, in basis points (0..10000). */
    @ColumnInfo(name = "match_rate_bp", defaultValue = "0") val matchRateBp: Int = 0,
)

/** docs/05 `statement_line`. [rawRow] is the JSON list of the source cells, kept so a mapping mistake can be seen. */
@Entity(
    tableName = "statement_line",
    foreignKeys = [
        ForeignKey(
            entity = StatementImportEntity::class,
            parentColumns = ["id"],
            childColumns = ["import_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["import_id", "ordinal"], unique = true)],
)
data class StatementLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "import_id") val importId: Long,
    val ordinal: Int,
    @ColumnInfo(name = "row_index") val rowIndex: Int,
    @ColumnInfo(name = "txn_date") val txnDate: String,
    @ColumnInfo(name = "value_date") val valueDate: String?,
    val description: String,
    @ColumnInfo(name = "ref_no") val refNo: String?,
    val debit: Long,
    val credit: Long,
    val balance: Long?,
    @ColumnInfo(name = "raw_row") val rawRow: String,
)

/**
 * docs/05 `recon_match` (ADR-016). One row per statement line or ledger transaction the reconciler placed: a pair, a
 * statement-only line ([txnId] null) or a ledger-only transaction ([statementLineId] null). [reasons] is a JSON list of reason
 * codes. A rejected pairing is remembered as a resolved STATEMENT_ONLY row that also names the transaction, so a re-run does not propose it again.
 * [resolvedByUser] rows are never changed by a re-run.
 */
@Entity(
    tableName = "recon_match",
    foreignKeys = [
        ForeignKey(
            entity = StatementImportEntity::class,
            parentColumns = ["id"],
            childColumns = ["import_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StatementLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["statement_line_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TxnEntity::class,
            parentColumns = ["id"],
            childColumns = ["txn_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["import_id"]),
        Index(value = ["statement_line_id"]),
        Index(value = ["txn_id"]),
    ],
)
data class ReconMatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "import_id") val importId: Long,
    @ColumnInfo(name = "statement_line_id") val statementLineId: Long?,
    @ColumnInfo(name = "txn_id") val txnId: Long?,
    val status: MatchStatus,
    @ColumnInfo(name = "score_pm") val scorePm: Int,
    val reasons: String,
    @ColumnInfo(name = "resolved_by_user", defaultValue = "0") val resolvedByUser: Boolean = false,
)
