package com.ledgr.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.TxnStatus
import com.ledgr.app.domain.statement.FileType
import com.ledgr.app.domain.statement.MatchStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ColumnMapDao {
    @Query("SELECT * FROM column_map WHERE bank_id = :bankId AND file_type = :fileType")
    suspend fun forBank(bankId: String, fileType: FileType): List<ColumnMapEntity>

    @Query("SELECT * FROM column_map WHERE bank_id = :bankId AND file_type = :fileType AND header_signature = :signature")
    suspend fun find(bankId: String, fileType: FileType, signature: String): ColumnMapEntity?

    @Query("SELECT * FROM column_map WHERE id = :id")
    suspend fun get(id: Long): ColumnMapEntity?

    @Insert
    suspend fun insert(row: ColumnMapEntity): Long

    @Update
    suspend fun update(row: ColumnMapEntity)

    @Query("DELETE FROM column_map WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface StatementImportDao {
    @Insert
    suspend fun insert(row: StatementImportEntity): Long

    @Update
    suspend fun update(row: StatementImportEntity)

    @Query("SELECT * FROM statement_import WHERE id = :id")
    suspend fun get(id: Long): StatementImportEntity?

    @Query("SELECT * FROM statement_import WHERE id = :id")
    fun observe(id: Long): Flow<StatementImportEntity?>

    @Query("SELECT * FROM statement_import ORDER BY imported_at DESC, id DESC")
    fun observeAll(): Flow<List<StatementImportEntity>>

    /** Removes the import; its lines and matches go with it (cascade). Transactions added from it stay. */
    @Query("DELETE FROM statement_import WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface StatementLineDao {
    @Insert
    suspend fun insertAll(rows: List<StatementLineEntity>): List<Long>

    @Query("SELECT * FROM statement_line WHERE import_id = :importId ORDER BY ordinal")
    suspend fun byImport(importId: Long): List<StatementLineEntity>

    @Query("SELECT * FROM statement_line WHERE id = :id")
    suspend fun get(id: Long): StatementLineEntity?
}

/** A recon_match row with the statement line and the ledger transaction it points at, for the results screen. */
data class ReconRow(
    @ColumnInfo(name = "match_id") val matchId: Long,
    val status: MatchStatus,
    @ColumnInfo(name = "score_pm") val scorePm: Int,
    val reasons: String,
    @ColumnInfo(name = "resolved_by_user") val resolvedByUser: Boolean,
    @ColumnInfo(name = "line_id") val lineId: Long?,
    @ColumnInfo(name = "txn_id") val txnId: Long?,
    @ColumnInfo(name = "line_ordinal") val lineOrdinal: Int?,
    @ColumnInfo(name = "line_date") val lineDate: String?,
    @ColumnInfo(name = "line_description") val lineDescription: String?,
    @ColumnInfo(name = "line_ref") val lineRef: String?,
    @ColumnInfo(name = "line_debit") val lineDebit: Long?,
    @ColumnInfo(name = "line_credit") val lineCredit: Long?,
    @ColumnInfo(name = "line_balance") val lineBalance: Long?,
    @ColumnInfo(name = "txn_date") val txnDate: String?,
    @ColumnInfo(name = "txn_direction") val txnDirection: Direction?,
    @ColumnInfo(name = "txn_amount") val txnAmount: Long?,
    @ColumnInfo(name = "txn_raw") val txnRaw: String?,
    @ColumnInfo(name = "txn_ref") val txnRef: String?,
    @ColumnInfo(name = "txn_balance") val txnBalance: Long?,
    @ColumnInfo(name = "txn_account") val txnAccountId: Long?,
    @ColumnInfo(name = "txn_status") val txnStatus: TxnStatus?,
    @ColumnInfo(name = "txn_counterparty") val txnCounterparty: String?,
    @ColumnInfo(name = "txn_category") val txnCategory: String?,
    @ColumnInfo(name = "txn_edited") val txnEdited: String?,
)

@Dao
interface ReconMatchDao {
    @Insert
    suspend fun insertAll(rows: List<ReconMatchEntity>): List<Long>

    @Insert
    suspend fun insert(row: ReconMatchEntity): Long

    @Update
    suspend fun update(row: ReconMatchEntity)

    @Query("SELECT * FROM recon_match WHERE id = :id")
    suspend fun get(id: Long): ReconMatchEntity?

    @Query("SELECT * FROM recon_match WHERE import_id = :importId")
    suspend fun byImport(importId: Long): List<ReconMatchEntity>

    /** A re-run replaces everything the person has not decided. */
    @Query("DELETE FROM recon_match WHERE import_id = :importId AND resolved_by_user = 0")
    suspend fun deleteUndecided(importId: Long)

    @Query("DELETE FROM recon_match WHERE id = :id")
    suspend fun delete(id: Long)

    /** Live: the results screen re-renders when a match, a statement line or a ledger transaction changes. */
    @Query(
        "SELECT m.id AS match_id, m.status AS status, m.score_pm AS score_pm, m.reasons AS reasons, " +
            "m.resolved_by_user AS resolved_by_user, m.statement_line_id AS line_id, m.txn_id AS txn_id, " +
            "l.ordinal AS line_ordinal, l.txn_date AS line_date, l.description AS line_description, l.ref_no AS line_ref, " +
            "l.debit AS line_debit, l.credit AS line_credit, l.balance AS line_balance, " +
            "t.local_date AS txn_date, t.direction AS txn_direction, t.amount AS txn_amount, t.raw_counterparty AS txn_raw, " +
            "t.ref_no AS txn_ref, t.balance_after AS txn_balance, t.account_id AS txn_account, t.status AS txn_status, " +
            "c.display_name AS txn_counterparty, cat.name AS txn_category, t.user_edited_fields AS txn_edited " +
            "FROM recon_match m " +
            "LEFT JOIN statement_line l ON l.id = m.statement_line_id " +
            "LEFT JOIN txn t ON t.id = m.txn_id " +
            "LEFT JOIN counterparty c ON c.id = t.counterparty_id " +
            "LEFT JOIN category cat ON cat.id = t.category_id " +
            "WHERE m.import_id = :importId",
    )
    fun observeRows(importId: Long): Flow<List<ReconRow>>
}
