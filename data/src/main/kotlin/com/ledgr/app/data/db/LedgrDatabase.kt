package com.ledgr.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema history: v1 (P0) account + category; v2 (P1) own_identity, counterparty, counterparty_alias and
 * category.sort_order / merged_into_id (see [Migrations]). Later tables arrive with their phases.
 */
@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        OwnIdentityEntity::class,
        CounterpartyEntity::class,
        CounterpartyAliasEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LedgrDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun ownIdentityDao(): OwnIdentityDao
    abstract fun counterpartyDao(): CounterpartyDao
    abstract fun counterpartyAliasDao(): CounterpartyAliasDao

    companion object {
        const val NAME = "ledgr.db"
    }
}
