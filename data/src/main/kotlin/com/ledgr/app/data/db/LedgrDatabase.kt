package com.ledgr.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema history: v1 (P0) account + category; v2 (P1) own_identity, counterparty, counterparty_alias and
 * category.sort_order / merged_into_id; v3 (P2) raw_sms and scan_run (ADR-012); v4 (P3) txn, balance_snapshot and
 * link_proposal (ADR-013). See [Migrations]. Later tables arrive with their phases.
 */
@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        OwnIdentityEntity::class,
        CounterpartyEntity::class,
        CounterpartyAliasEntity::class,
        RawSmsEntity::class,
        ScanRunEntity::class,
        TxnEntity::class,
        BalanceSnapshotEntity::class,
        LinkProposalEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class LedgrDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun ownIdentityDao(): OwnIdentityDao
    abstract fun counterpartyDao(): CounterpartyDao
    abstract fun counterpartyAliasDao(): CounterpartyAliasDao
    abstract fun rawSmsDao(): RawSmsDao
    abstract fun scanRunDao(): ScanRunDao
    abstract fun txnDao(): TxnDao
    abstract fun balanceSnapshotDao(): BalanceSnapshotDao
    abstract fun linkProposalDao(): LinkProposalDao

    companion object {
        const val NAME = "ledgr.db"
    }
}
