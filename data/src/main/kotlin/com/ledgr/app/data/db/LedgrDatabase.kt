package com.ledgr.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** Schema v1 (P0): account + category only. P1 adds the rest with migrations. */
@Database(
    entities = [AccountEntity::class, CategoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LedgrDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        const val NAME = "ledgr.db"
    }
}
