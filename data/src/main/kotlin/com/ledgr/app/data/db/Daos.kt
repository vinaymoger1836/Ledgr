package com.ledgr.app.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category ORDER BY id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM category")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account ORDER BY id")
    fun observeAll(): Flow<List<AccountEntity>>
}
