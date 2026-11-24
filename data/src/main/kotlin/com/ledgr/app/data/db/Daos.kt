package com.ledgr.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.paging.PagingSource
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.ReconState
import com.ledgr.app.domain.ledger.Source
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.ledger.TxnStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category ORDER BY sort_order, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM category")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun get(id: Long): CategoryEntity?

    @Query("SELECT * FROM category ORDER BY sort_order, id")
    suspend fun getAll(): List<CategoryEntity>

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("UPDATE category SET sort_order = :order WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Int)

    @Query("UPDATE category SET parent_id = :target WHERE parent_id = :source")
    suspend fun repointChildren(source: Long, target: Long)

    @Query("UPDATE category SET hidden = 1, merged_into_id = :target WHERE id = :source")
    suspend fun markMerged(source: Long, target: Long)

    /** Keeps merge chains flat: anything that was merged into [source] now points at [target]. */
    @Query("UPDATE category SET merged_into_id = :target WHERE merged_into_id = :source")
    suspend fun repointMerged(source: Long, target: Long)

    // Blocking variants for RuleDataSync, which runs inside one transaction on a background thread.
    @Query("SELECT `key` FROM category")
    fun allKeysBlocking(): List<String>

    @Query("SELECT MAX(sort_order) FROM category")
    fun maxSortOrderBlocking(): Int?

    @Insert
    fun insertBlocking(category: CategoryEntity): Long

    @Query("SELECT id FROM category WHERE `key` = :key")
    fun idForKeyBlocking(key: String): Long?
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account ORDER BY id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account ORDER BY id")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun get(id: Long): AccountEntity?

    @Query("SELECT * FROM account WHERE bank_id = :bankId ORDER BY id")
    suspend fun getByBank(bankId: String): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM account WHERE bank_id = :bankId AND last4 = :last4 AND id != :excludeId")
    suspend fun countSameBankAndLast4(bankId: String, last4: String, excludeId: Long): Int

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("UPDATE account SET sender_headers = :json WHERE id = :id")
    suspend fun setSenderHeaders(id: Long, json: String)
}

@Dao
interface OwnIdentityDao {
    @Query("SELECT * FROM own_identity ORDER BY kind, value COLLATE NOCASE")
    fun observeAll(): Flow<List<OwnIdentityEntity>>

    @Query("SELECT * FROM own_identity")
    suspend fun getAll(): List<OwnIdentityEntity>

    /** Returns -1 when the same value already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(identity: OwnIdentityEntity): Long

    @Query("DELETE FROM own_identity WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CounterpartyDao {
    @Query("SELECT * FROM counterparty WHERE type = 'MERCHANT' ORDER BY display_name COLLATE NOCASE")
    fun observeMerchants(): Flow<List<CounterpartyEntity>>

    /** Every counterparty (merchants and people), for names and avatars on Home. */
    @Query("SELECT * FROM counterparty")
    fun observeAll(): Flow<List<CounterpartyEntity>>

    @Query("SELECT * FROM counterparty WHERE id = :id")
    suspend fun get(id: Long): CounterpartyEntity?

    @Query("SELECT * FROM counterparty")
    suspend fun getAll(): List<CounterpartyEntity>

    @Query("SELECT * FROM counterparty WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<CounterpartyEntity>

    @Query("SELECT COUNT(*) FROM counterparty WHERE type = 'MERCHANT' AND display_name = :name COLLATE NOCASE AND id != :excludeId")
    suspend fun countMerchantsNamed(name: String, excludeId: Long): Int

    @Query("DELETE FROM counterparty WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insert(counterparty: CounterpartyEntity): Long

    @Update
    suspend fun update(counterparty: CounterpartyEntity)

    @Query("UPDATE counterparty SET default_category_id = :target WHERE default_category_id = :source")
    suspend fun repointDefaultCategory(source: Long, target: Long)

    // Blocking variants for RuleDataSync.
    @Query("SELECT merchant_key FROM counterparty WHERE merchant_key IS NOT NULL")
    fun merchantKeysBlocking(): List<String>

    @Insert
    fun insertBlocking(counterparty: CounterpartyEntity): Long
}

@Dao
interface CounterpartyAliasDao {
    @Query("SELECT * FROM counterparty_alias ORDER BY raw_key")
    fun observeAll(): Flow<List<CounterpartyAliasEntity>>

    @Query("SELECT * FROM counterparty_alias")
    suspend fun getAll(): List<CounterpartyAliasEntity>

    @Query("SELECT * FROM counterparty_alias WHERE raw_key = :rawKey")
    suspend fun get(rawKey: String): CounterpartyAliasEntity?

    /** Fails if the key already maps somewhere; the repository checks first and reports who owns it. */
    @Insert
    suspend fun insert(alias: CounterpartyAliasEntity)

    @Query("DELETE FROM counterparty_alias WHERE raw_key = :rawKey")
    suspend fun delete(rawKey: String)
}

/** `(provider_id, body_hash)` of a stored SMS, with its row id. */
data class StoredSmsKey(
    val id: Long,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "body_hash") val bodyHash: String,
    /** False once the body was purged: such a row can no longer be re-evaluated. */
    @ColumnInfo(name = "has_body") val hasBody: Boolean,
    @ColumnInfo(name = "user_decision") val userDecision: UserDecision?,
)

/** One group of the scan report: how many SMS got an outcome + reason. */
data class ReasonCount(
    val outcome: String,
    val reason: String,
    val n: Int,
)

@Dao
interface RawSmsDao {
    /** The stored rows among [providerIds] (a page of at most a few hundred ids). */
    @Query(
        "SELECT id, provider_id, body_hash, body IS NOT NULL AS has_body, user_decision " +
            "FROM raw_sms WHERE provider_id IN (:providerIds)",
    )
    suspend fun storedKeys(providerIds: List<Long>): List<StoredSmsKey>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<RawSmsEntity>): List<Long>

    /** Re-evaluation of a stored message with the current rules. Never touches the body or hash. */
    @Query(
        "UPDATE raw_sms SET sender_header = :header, sender_suffix = :suffix, outcome = :outcome, reason = :reason, " +
            "template_id = :templateId, rules_version = :rulesVersion WHERE id = :id",
    )
    suspend fun updateVerdict(
        id: Long,
        header: String?,
        suffix: String,
        outcome: String,
        reason: String,
        templateId: String?,
        rulesVersion: String,
    )

    @Query("SELECT outcome, reason, COUNT(*) AS n FROM raw_sms WHERE received_at >= :from AND received_at < :to GROUP BY outcome, reason")
    fun observeCounts(from: Long, to: Long): Flow<List<ReasonCount>>

    @Query("SELECT COUNT(*) FROM raw_sms WHERE received_at >= :from AND received_at < :to")
    fun observeTotal(from: Long, to: Long): Flow<Int>

    /** Newest first. [outcome] / [reason] null means "any". */
    @Query(
        "SELECT * FROM raw_sms WHERE received_at >= :from AND received_at < :to " +
            "AND (:outcome IS NULL OR outcome = :outcome) AND (:reason IS NULL OR reason = :reason) " +
            "ORDER BY received_at DESC, id DESC LIMIT :limit",
    )
    fun observeFiltered(from: Long, to: Long, outcome: String?, reason: String?, limit: Int): Flow<List<RawSmsEntity>>

    @Query("SELECT * FROM raw_sms WHERE id = :id")
    suspend fun get(id: Long): RawSmsEntity?

    /** Needs review → Unparsed: bank SMS the pipeline could not turn into a transaction. Newest first. */
    @Query("SELECT * FROM raw_sms WHERE outcome = 'REVIEW' AND reason IN (:reasons) ORDER BY received_at DESC, id DESC LIMIT :limit")
    fun observeReview(reasons: List<String>, limit: Int): Flow<List<RawSmsEntity>>

    @Query("SELECT COUNT(*) FROM raw_sms WHERE outcome = 'REVIEW' AND reason IN (:reasons)")
    fun observeReviewCount(reasons: List<String>): Flow<Int>

    /** Needs review → Suspicious. */
    @Query("SELECT * FROM raw_sms WHERE outcome = 'QUARANTINE' ORDER BY received_at DESC, id DESC LIMIT :limit")
    fun observeQuarantined(limit: Int): Flow<List<RawSmsEntity>>

    @Query("SELECT COUNT(*) FROM raw_sms WHERE outcome = 'QUARANTINE'")
    fun observeQuarantinedCount(): Flow<Int>

    /** A person's decision about one message ("Ignore this message", "Delete"). Never touches the body or hash. */
    @Query("UPDATE raw_sms SET outcome = :outcome, reason = :reason, user_decision = :decision WHERE id = :id")
    suspend fun decide(id: Long, outcome: String, reason: String, decision: UserDecision?)

    /** A failure while writing the ledger for this message: keep the SMS, flag it, leave any decision alone. */
    @Query("UPDATE raw_sms SET outcome = :outcome, reason = :reason WHERE id = :id")
    suspend fun setOutcome(id: Long, outcome: String, reason: String)
}

@Dao
interface ScanRunDao {
    @Insert
    suspend fun insert(run: ScanRunEntity): Long

    @Update
    suspend fun update(run: ScanRunEntity)

    /** The latest run of [kind] for [month], for the scan report. */
    @Query("SELECT * FROM scan_run WHERE month = :month AND kind = :kind ORDER BY id DESC LIMIT 1")
    fun observeLatest(month: String, kind: String): Flow<ScanRunEntity?>

    @Query("SELECT * FROM scan_run ORDER BY id DESC LIMIT 1")
    fun observeLatestAny(): Flow<ScanRunEntity?>

    /** Months a person-started month scan finished (`yyyy-MM`): the other source of the month picker dots. */
    @Query("SELECT DISTINCT month FROM scan_run WHERE kind = 'MONTH' AND status = 'DONE'")
    fun observeScannedMonths(): Flow<List<String>>

    /** A process that died mid-scan leaves RUNNING rows behind; callers hold the scan lock, so any is stale. */
    @Query("UPDATE scan_run SET status = 'FAILED', finished_at = :now WHERE status = 'RUNNING'")
    suspend fun failStaleRunning(now: Long)
}

/** One group of stored transactions, summed in SQL. `LedgerRules` decides what each group counts as. */
data class DailyFactRow(
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "counterparty_id") val counterpartyId: Long?,
    val kind: TxnKind,
    val status: TxnStatus,
    val linked: Int,
    val amount: Long,
    val count: Int,
)

/** One stored payment for the recurring detector: a [DailyFactRow] of a single row plus the raw payee text. */
data class SpendEventRow(
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "counterparty_id") val counterpartyId: Long?,
    val kind: TxnKind,
    val status: TxnStatus,
    val linked: Int,
    val amount: Long,
    @ColumnInfo(name = "raw_counterparty") val rawCounterparty: String?,
)

/**
 * One line of the statement-style table (docs/07 §3). [balance] is `balance_after` from the SMS when there is one,
 * else the opening balance plus the account's running total when the account has an opening balance, else null
 * (never invented). [balanceStored] says which of the two it is.
 */
data class StatementRow(
    val id: Long,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    val kind: TxnKind,
    val direction: Direction,
    val amount: Long,
    val status: TxnStatus,
    val linked: Int,
    @ColumnInfo(name = "counterparty_id") val counterpartyId: Long?,
    @ColumnInfo(name = "counterparty_name") val counterpartyName: String?,
    @ColumnInfo(name = "raw_counterparty") val rawCounterparty: String?,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "ref_no") val refNo: String?,
    val balance: Long?,
    @ColumnInfo(name = "balance_stored") val balanceStored: Int,
    val source: Source,
    @ColumnInfo(name = "recon_state") val reconState: ReconState,
    val note: String?,
    @ColumnInfo(name = "raw_body") val rawBody: String?,
)

/**
 * The filters of the statement-style table as SQL parameters. Every one is optional: `null` (or `0` for the `use*`
 * flags that guard the list parameters) means "no filter". Built from the screen's filter state by
 * `StatementRepository`, never from raw user text: [search] arrives already wrapped and escaped for LIKE.
 */
data class StatementQuery(
    val fromDate: String,
    val toDate: String,
    val accountId: Long? = null,
    val useCategories: Boolean = false,
    val categoryIds: List<Long> = emptyList(),
    val counterpartyId: Long? = null,
    val useKinds: Boolean = false,
    val kinds: List<String> = emptyList(),
    val useSources: Boolean = false,
    val sources: List<String> = emptyList(),
    val useRecon: Boolean = false,
    val recon: List<String> = emptyList(),
    val direction: String? = null,
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
    val search: String? = null,
    /** 0 date newest first, 1 date oldest first, 2 amount high to low, 3 amount low to high. */
    val sort: Int = 0,
)

// Shared by the page query, the footer query and the export, so all three always select the same rows.
private const val STATEMENT_FROM =
    " FROM txn t LEFT JOIN counterparty cp ON cp.id = t.counterparty_id " +
        "LEFT JOIN raw_sms r ON r.id = t.raw_sms_id LEFT JOIN account a ON a.id = t.account_id "

private const val STATEMENT_WHERE =
    " WHERE t.local_date BETWEEN :fromDate AND :toDate AND t.status = 'ACTIVE' " +
        "AND (:accountId IS NULL OR t.account_id = :accountId) " +
        "AND (:useCategories = 0 OR t.category_id IN (:categoryIds)) " +
        "AND (:counterpartyId IS NULL OR t.counterparty_id = :counterpartyId) " +
        "AND (:useKinds = 0 OR t.kind IN (:kinds)) " +
        "AND (:useSources = 0 OR t.source IN (:sources)) " +
        "AND (:useRecon = 0 OR t.recon_state IN (:recon)) " +
        "AND (:direction IS NULL OR t.direction = :direction) " +
        "AND (:minAmount IS NULL OR t.amount >= :minAmount) " +
        "AND (:maxAmount IS NULL OR t.amount <= :maxAmount) " +
        "AND (:search IS NULL OR cp.display_name LIKE :search ESCAPE '\\' OR t.raw_counterparty LIKE :search ESCAPE '\\' " +
        "OR t.ref_no LIKE :search ESCAPE '\\' OR t.note LIKE :search ESCAPE '\\' OR r.body LIKE :search ESCAPE '\\') "

private const val STATEMENT_SELECT =
    "SELECT t.id AS id, t.account_id AS account_id, t.local_date AS local_date, t.occurred_at AS occurred_at, " +
        "t.kind AS kind, t.direction AS direction, t.amount AS amount, t.status AS status, " +
        "CASE WHEN t.refund_of IS NULL THEN 0 ELSE 1 END AS linked, t.counterparty_id AS counterparty_id, " +
        "cp.display_name AS counterparty_name, t.raw_counterparty AS raw_counterparty, t.category_id AS category_id, " +
        "t.ref_no AS ref_no, " +
        "CASE WHEN t.balance_after IS NOT NULL THEN t.balance_after ELSE " +
        "(SELECT a.opening_balance + COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END), 0) " +
        "FROM txn p WHERE p.account_id = t.account_id AND p.status = 'ACTIVE' AND " +
        "(p.occurred_at < t.occurred_at OR (p.occurred_at = t.occurred_at AND p.id <= t.id))) END AS balance, " +
        "CASE WHEN t.balance_after IS NOT NULL THEN 1 ELSE 0 END AS balance_stored, " +
        "t.source AS source, t.recon_state AS recon_state, t.note AS note, r.body AS raw_body "

private const val STATEMENT_ORDER =
    " ORDER BY CASE WHEN :sort = 0 THEN t.occurred_at END DESC, CASE WHEN :sort = 1 THEN t.occurred_at END ASC, " +
        "CASE WHEN :sort = 2 THEN t.amount END DESC, CASE WHEN :sort = 3 THEN t.amount END ASC, t.id DESC"

@Dao
interface TxnDao {
    @Insert
    suspend fun insert(txn: TxnEntity): Long

    @Update
    suspend fun update(txn: TxnEntity)

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun get(id: Long): TxnEntity?

    @Query("SELECT * FROM txn WHERE raw_sms_id = :rawSmsId")
    suspend fun byRawSmsId(rawSmsId: Long): TxnEntity?

    /** Candidates for dedup and transfer / reversal linking: everything near in time, any account. */
    @Query("SELECT * FROM txn WHERE occurred_at BETWEEN :from AND :to AND status != 'DELETED'")
    suspend fun window(from: Long, to: Long): List<TxnEntity>

    /** Candidates for refund linking: the same counterparty over a longer window. */
    @Query(
        "SELECT * FROM txn WHERE counterparty_id = :counterpartyId AND occurred_at BETWEEN :from AND :to AND status != 'DELETED'",
    )
    suspend fun byCounterparty(counterpartyId: Long, from: Long, to: Long): List<TxnEntity>

    /** Refunds and reversals already linked to [originalIds], so a refund cannot exceed its original. */
    @Query("SELECT * FROM txn WHERE refund_of IN (:originalIds) AND status != 'DELETED'")
    suspend fun linkedTo(originalIds: List<Long>): List<TxnEntity>

    @Query("SELECT COUNT(*) FROM txn WHERE account_id = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    // Re-parse that no longer yields a transaction: detach whatever pointed at it.
    @Query("UPDATE txn SET transfer_pair_id = NULL WHERE transfer_pair_id = :id")
    suspend fun clearPairsTo(id: Long)

    @Query("UPDATE txn SET refund_of = NULL WHERE refund_of = :id")
    suspend fun clearRefundsTo(id: Long)

    @Query("UPDATE txn SET status = 'ACTIVE', duplicate_of = NULL WHERE duplicate_of = :id AND status = 'DUPLICATE'")
    suspend fun reviveDuplicatesOf(id: Long)

    /**
     * Sums by day, account, category, counterparty, kind, status and "linked", for [accountIds] (all accounts when
     * the list is empty is handled by the caller using [dailyFactsAll]). No status filter here: what counts is
     * decided only by `LedgerRules`.
     */
    @Query(
        "SELECT local_date, account_id, category_id, counterparty_id, kind, status, " +
            "CASE WHEN refund_of IS NULL THEN 0 ELSE 1 END AS linked, SUM(amount) AS amount, COUNT(*) AS count " +
            "FROM txn WHERE local_date BETWEEN :fromDate AND :toDate AND account_id IN (:accountIds) " +
            "GROUP BY local_date, account_id, category_id, counterparty_id, kind, status, linked",
    )
    suspend fun dailyFacts(fromDate: String, toDate: String, accountIds: List<Long>): List<DailyFactRow>

    @Query(
        "SELECT local_date, account_id, category_id, counterparty_id, kind, status, " +
            "CASE WHEN refund_of IS NULL THEN 0 ELSE 1 END AS linked, SUM(amount) AS amount, COUNT(*) AS count " +
            "FROM txn WHERE local_date BETWEEN :fromDate AND :toDate " +
            "GROUP BY local_date, account_id, category_id, counterparty_id, kind, status, linked",
    )
    suspend fun dailyFactsAll(fromDate: String, toDate: String): List<DailyFactRow>

    /** [dailyFactsAll] as a live query: Home re-renders the moment a scan or a new SMS writes a row. */
    @Query(
        "SELECT local_date, account_id, category_id, counterparty_id, kind, status, " +
            "CASE WHEN refund_of IS NULL THEN 0 ELSE 1 END AS linked, SUM(amount) AS amount, COUNT(*) AS count " +
            "FROM txn WHERE local_date BETWEEN :fromDate AND :toDate " +
            "GROUP BY local_date, account_id, category_id, counterparty_id, kind, status, linked",
    )
    fun observeDailyFacts(fromDate: String, toDate: String): Flow<List<DailyFactRow>>

    /**
     * Rows of one month for the Home recent list: only ACTIVE ones, the same rows `LedgerRules` counts, so the list
     * and the totals above it agree. Deleted rows, duplicates and reversed pairs stay in the database, out of sight.
     */
    @Query(
        "SELECT * FROM txn WHERE local_date BETWEEN :fromDate AND :toDate AND status = 'ACTIVE' " +
            "ORDER BY occurred_at DESC, id DESC",
    )
    fun observeRange(fromDate: String, toDate: String): Flow<List<TxnEntity>>

    @Query("SELECT * FROM txn WHERE id = :id")
    fun observe(id: Long): Flow<TxnEntity?>

    /**
     * Single payments for the recurring detector (docs/07 §4.1). ACTIVE rows only; `LedgerRules` still decides which
     * of them are spend.
     */
    @Query(
        "SELECT local_date, account_id, category_id, counterparty_id, kind, status, " +
            "CASE WHEN refund_of IS NULL THEN 0 ELSE 1 END AS linked, amount, raw_counterparty " +
            "FROM txn WHERE local_date BETWEEN :fromDate AND :toDate AND status = 'ACTIVE' AND direction = 'DEBIT'",
    )
    fun observeSpendEvents(fromDate: String, toDate: String): Flow<List<SpendEventRow>>

    // ── The statement-style table (docs/07 §3). The three queries share the same FROM / WHERE text. ──

    /** One page of the table. Room wraps this in LIMIT / OFFSET and re-queries when `txn` changes. */
    @Query(STATEMENT_SELECT + STATEMENT_FROM + STATEMENT_WHERE + STATEMENT_ORDER)
    fun statementPage(
        fromDate: String, toDate: String, accountId: Long?,
        useCategories: Int, categoryIds: List<Long>, counterpartyId: Long?,
        useKinds: Int, kinds: List<String>, useSources: Int, sources: List<String>, useRecon: Int, recon: List<String>,
        direction: String?, minAmount: Long?, maxAmount: Long?, search: String?, sort: Int,
    ): PagingSource<Int, StatementRow>

    /** Every row the filter matches, in table order, for the CSV export. */
    @Query(STATEMENT_SELECT + STATEMENT_FROM + STATEMENT_WHERE + STATEMENT_ORDER)
    suspend fun statementAll(
        fromDate: String, toDate: String, accountId: Long?,
        useCategories: Int, categoryIds: List<Long>, counterpartyId: Long?,
        useKinds: Int, kinds: List<String>, useSources: Int, sources: List<String>, useRecon: Int, recon: List<String>,
        direction: String?, minAmount: Long?, maxAmount: Long?, search: String?, sort: Int,
    ): List<StatementRow>

    /**
     * The filtered rows grouped like [observeDailyFacts], so the table footer and the day subtotals go through
     * `LedgerRules` and match the KPI row for the same filter.
     */
    @Query(
        "SELECT t.local_date AS local_date, t.account_id AS account_id, t.category_id AS category_id, " +
            "t.counterparty_id AS counterparty_id, t.kind AS kind, t.status AS status, " +
            "CASE WHEN t.refund_of IS NULL THEN 0 ELSE 1 END AS linked, SUM(t.amount) AS amount, COUNT(*) AS count " +
            STATEMENT_FROM + STATEMENT_WHERE +
            "GROUP BY t.local_date, t.account_id, t.category_id, t.counterparty_id, t.kind, t.status, linked",
    )
    fun observeStatementFacts(
        fromDate: String, toDate: String, accountId: Long?,
        useCategories: Int, categoryIds: List<Long>, counterpartyId: Long?,
        useKinds: Int, kinds: List<String>, useSources: Int, sources: List<String>, useRecon: Int, recon: List<String>,
        direction: String?, minAmount: Long?, maxAmount: Long?, search: String?,
    ): Flow<List<DailyFactRow>>

    /** Months that have at least one stored transaction (`yyyy-MM`), for the month picker dots. */
    @Query("SELECT DISTINCT substr(local_date, 1, 7) FROM txn WHERE status != 'DELETED'")
    fun observeMonthsWithData(): Flow<List<String>>

    /** "Apply to all from this merchant": every other row of the merchant whose category the person has not chosen. */
    @Query(
        "UPDATE txn SET category_id = :categoryId WHERE counterparty_id = :counterpartyId AND id != :exceptId " +
            "AND status != 'DELETED' AND user_edited_fields NOT LIKE '%\"CATEGORY\"%'",
    )
    suspend fun setCategoryForCounterparty(counterpartyId: Long, categoryId: Long, exceptId: Long): Int

    // Needs review → Check.
    @Query("SELECT * FROM txn WHERE check_reason IS NOT NULL AND status = 'ACTIVE' ORDER BY occurred_at DESC, id DESC LIMIT :limit")
    fun observeCheck(limit: Int): Flow<List<TxnEntity>>

    @Query("SELECT COUNT(*) FROM txn WHERE check_reason IS NOT NULL AND status = 'ACTIVE'")
    fun observeCheckCount(): Flow<Int>

    @Query("SELECT * FROM txn WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TxnEntity>

    @Query("SELECT * FROM txn WHERE id IN (:ids)")
    fun observeByIds(ids: List<Long>): Flow<List<TxnEntity>>
}

@Dao
interface BalanceSnapshotDao {
    /** One snapshot per account and instant; a re-scan changes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(snapshot: BalanceSnapshotEntity): Long

    @Query("SELECT * FROM balance_snapshot WHERE account_id = :accountId ORDER BY at DESC LIMIT 1")
    suspend fun latest(accountId: Long): BalanceSnapshotEntity?
}

@Dao
interface LinkProposalDao {
    /** Returns -1 when the same proposal already exists (any state), so a re-scan does not re-ask. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(proposal: LinkProposalEntity): Long

    @Query("SELECT * FROM link_proposal WHERE state = 'OPEN' ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun observeOpen(limit: Int): Flow<List<LinkProposalEntity>>

    @Query("SELECT COUNT(*) FROM link_proposal WHERE state = 'OPEN'")
    fun observeOpenCount(): Flow<Int>

    @Query("SELECT * FROM link_proposal WHERE id = :id")
    suspend fun get(id: Long): LinkProposalEntity?

    @Query("UPDATE link_proposal SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)
}

@Dao
interface RecurringOverrideDao {
    @Query("SELECT * FROM recurring_override")
    fun observeAll(): Flow<List<RecurringOverrideEntity>>

    /** One answer per series key: a new answer replaces the old one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: RecurringOverrideEntity): Long

    @Query("DELETE FROM recurring_override WHERE `key` = :key")
    suspend fun clear(key: String)
}

// Call sites pass one StatementQuery instead of 17 loose arguments; Room needs the flat form on the DAO itself.
fun TxnDao.statementPage(q: StatementQuery): PagingSource<Int, StatementRow> = statementPage(
    q.fromDate, q.toDate, q.accountId, q.useCategories.asInt(), q.categoryIds, q.counterpartyId,
    q.useKinds.asInt(), q.kinds, q.useSources.asInt(), q.sources, q.useRecon.asInt(), q.recon,
    q.direction, q.minAmount, q.maxAmount, q.search, q.sort,
)

suspend fun TxnDao.statementAll(q: StatementQuery): List<StatementRow> = statementAll(
    q.fromDate, q.toDate, q.accountId, q.useCategories.asInt(), q.categoryIds, q.counterpartyId,
    q.useKinds.asInt(), q.kinds, q.useSources.asInt(), q.sources, q.useRecon.asInt(), q.recon,
    q.direction, q.minAmount, q.maxAmount, q.search, q.sort,
)

fun TxnDao.observeStatementFacts(q: StatementQuery): Flow<List<DailyFactRow>> = observeStatementFacts(
    q.fromDate, q.toDate, q.accountId, q.useCategories.asInt(), q.categoryIds, q.counterpartyId,
    q.useKinds.asInt(), q.kinds, q.useSources.asInt(), q.sources, q.useRecon.asInt(), q.recon,
    q.direction, q.minAmount, q.maxAmount, q.search,
)

private fun Boolean.asInt(): Int = if (this) 1 else 0
