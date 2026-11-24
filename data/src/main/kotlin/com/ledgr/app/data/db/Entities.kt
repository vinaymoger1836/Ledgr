package com.ledgr.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgr.app.domain.model.AccountType
import com.ledgr.app.domain.model.AliasSource
import com.ledgr.app.domain.model.CategoryKind
import com.ledgr.app.domain.model.CounterpartyType
import com.ledgr.app.domain.model.OwnIdentityKind

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

/**
 * docs/05 §1 `category`. Colour is an index into the fixed category palette.
 * [sortOrder] and [mergedIntoId] are P1 additions (ADR-011, proposed).
 */
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
    /** Position in the user's list (ascending); unique per row so a move is a swap. */
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    /** Set when this category was merged away: the category its transactions now belong to. */
    @ColumnInfo(name = "merged_into_id") val mergedIntoId: Long? = null,
)

/** docs/05 §1 `own_identity`: own VPAs and name spellings. The same value cannot be stored twice (any case). */
@Entity(
    tableName = "own_identity",
    indices = [Index(value = ["kind", "value"], unique = true)],
)
data class OwnIdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: OwnIdentityKind,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val value: String,
)

/**
 * docs/05 §1 `counterparty`. [icon] is `LETTER`, `CATEGORY` or `IMG:<file name>` (a picture in app-private
 * storage). [color] is a palette index 0..9, like `category.color`.
 */
@Entity(
    tableName = "counterparty",
    indices = [Index(value = ["merchant_key"], unique = true)],
)
data class CounterpartyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    val type: CounterpartyType,
    /** Id of the shipped `merchants.yaml` entry this row came from; null for user-made rows. */
    @ColumnInfo(name = "merchant_key") val merchantKey: String? = null,
    @ColumnInfo(name = "default_category_id") val defaultCategoryId: Long? = null,
    @ColumnInfo(defaultValue = "LETTER") val icon: String = ICON_LETTER,
    val color: Int,
) {
    companion object {
        const val ICON_LETTER = "LETTER"
        const val ICON_CATEGORY = "CATEGORY"
        const val ICON_IMAGE_PREFIX = "IMG:"
    }
}

/** docs/05 §1 `counterparty_alias`: a normalised raw text/VPA that always maps to one counterparty. */
@Entity(
    tableName = "counterparty_alias",
    foreignKeys = [
        ForeignKey(
            entity = CounterpartyEntity::class,
            parentColumns = ["id"],
            childColumns = ["counterparty_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["counterparty_id"])],
)
data class CounterpartyAliasEntity(
    @PrimaryKey @ColumnInfo(name = "raw_key") val rawKey: String,
    @ColumnInfo(name = "counterparty_id") val counterpartyId: Long,
    @ColumnInfo(name = "created_by") val createdBy: AliasSource,
)
