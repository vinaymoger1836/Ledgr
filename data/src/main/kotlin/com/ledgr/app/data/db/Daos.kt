package com.ledgr.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

    @Query("SELECT * FROM counterparty WHERE id = :id")
    suspend fun get(id: Long): CounterpartyEntity?

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
    @Query("SELECT id, provider_id, body_hash, body IS NOT NULL AS has_body FROM raw_sms WHERE provider_id IN (:providerIds)")
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

    /** A process that died mid-scan leaves RUNNING rows behind; callers hold the scan lock, so any is stale. */
    @Query("UPDATE scan_run SET status = 'FAILED', finished_at = :now WHERE status = 'RUNNING'")
    suspend fun failStaleRunning(now: Long)
}
