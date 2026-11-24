package com.ledgr.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgr.app.domain.model.AccountType
import com.ledgr.app.domain.model.CategoryKind

/** docs/05 §1 `account`. Only the last 4 digits are ever stored (ADR-006). */
@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_id") val bankId: String,
    val type: AccountType,
    val nickname: String,
    val last4: String,
    /** Chip / accent colour (ARGB). */
    val color: Int,
    /** JSON list of confirmed sender headers for this bank. */
    @ColumnInfo(name = "sender_headers", defaultValue = "[]") val senderHeaders: String = "[]",
    /** Paise; optional, for balance charts. */
    @ColumnInfo(name = "opening_balance") val openingBalance: Long? = null,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true,
)

/** docs/05 §1 `category`. Colour is an index into the fixed category palette. */
@Entity(
    tableName = "category",
    indices = [Index(value = ["key"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val name: String,
    val icon: String,
    val color: Int,
    val kind: CategoryKind,
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    @ColumnInfo(name = "is_default", defaultValue = "1") val isDefault: Boolean = true,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)
