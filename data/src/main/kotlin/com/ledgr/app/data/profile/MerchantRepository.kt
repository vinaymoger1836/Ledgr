package com.ledgr.app.data.profile

import android.net.Uri
import com.ledgr.app.data.db.CounterpartyAliasEntity
import com.ledgr.app.data.db.CounterpartyEntity
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.domain.model.AliasSource
import com.ledgr.app.domain.model.CounterpartyType
import com.ledgr.app.domain.profile.AliasKey
import com.ledgr.app.domain.profile.NameColor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

sealed interface MerchantSaveResult {
    data class Saved(val id: Long) : MerchantSaveResult
    data object BlankName : MerchantSaveResult
    data object DuplicateName : MerchantSaveResult
}

sealed interface AliasAddResult {
    data object Added : AliasAddResult
    data object Invalid : AliasAddResult

    /** The text already maps to [ownerName] (which may be this very merchant). */
    data class AlreadyMapped(val ownerName: String, val sameMerchant: Boolean) : AliasAddResult
}

/** Merchants (docs/05 `counterparty` of type MERCHANT) and their aliases (docs/03 §6 step 1: user aliases win). */
@Singleton
class MerchantRepository @Inject constructor(
    private val db: DatabaseManager,
    private val icons: MerchantIconStore,
) {
    val merchants: Flow<List<CounterpartyEntity>> = db.observe { it.counterpartyDao().observeMerchants() }
    val aliases: Flow<List<CounterpartyAliasEntity>> = db.observe { it.counterpartyAliasDao().observeAll() }

    /** The saved picture for a merchant's `icon` value, if any. */
    fun imageFile(icon: String) = icons.fileFor(icon)

    suspend fun add(name: String, defaultCategoryId: Long?): MerchantSaveResult = db.withDb { database ->
        val clean = cleanName(name)
        val dao = database.counterpartyDao()
        when {
            clean.isEmpty() -> MerchantSaveResult.BlankName
            dao.countMerchantsNamed(clean, excludeId = 0) > 0 -> MerchantSaveResult.DuplicateName
            else -> MerchantSaveResult.Saved(
                dao.insert(
                    CounterpartyEntity(
                        displayName = clean,
                        type = CounterpartyType.MERCHANT,
                        defaultCategoryId = defaultCategoryId,
                        icon = if (defaultCategoryId != null) CounterpartyEntity.ICON_CATEGORY else CounterpartyEntity.ICON_LETTER,
                        color = NameColor.indexFor(clean),
                    ),
                ),
            )
        }
    }

    suspend fun rename(id: Long, name: String): MerchantSaveResult = db.withDb { database ->
        val clean = cleanName(name)
        val dao = database.counterpartyDao()
        val row = dao.get(id)
        when {
            row == null -> MerchantSaveResult.Saved(id)
            clean.isEmpty() -> MerchantSaveResult.BlankName
            dao.countMerchantsNamed(clean, excludeId = id) > 0 -> MerchantSaveResult.DuplicateName
            else -> {
                dao.update(row.copy(displayName = clean))
                MerchantSaveResult.Saved(id)
            }
        }
    }

    suspend fun setDefaultCategory(id: Long, categoryId: Long?) = db.withDb { database ->
        val dao = database.counterpartyDao()
        dao.get(id)?.let { dao.update(it.copy(defaultCategoryId = categoryId)) }
    }

    /** Switches to the letter or category-icon avatar and removes any picture the merchant had. */
    suspend fun setIconMode(id: Long, mode: String) {
        require(mode == CounterpartyEntity.ICON_LETTER || mode == CounterpartyEntity.ICON_CATEGORY)
        val old = db.withDb { database ->
            val dao = database.counterpartyDao()
            val row = dao.get(id) ?: return@withDb null
            dao.update(row.copy(icon = mode))
            row.icon
        }
        if (old != null) icons.delete(old)
    }

    /** Uses a picture the user picked. Returns false if the image could not be read. */
    suspend fun setImage(id: Long, uri: Uri): Boolean {
        val stored = icons.import(uri) ?: return false
        val old = db.withDb { database ->
            val dao = database.counterpartyDao()
            val row = dao.get(id)
            if (row == null) {
                null
            } else {
                dao.update(row.copy(icon = stored))
                row.icon
            }
        }
        if (old == null) icons.delete(stored) else icons.delete(old)
        return true
    }

    /** Only merchants the user made can be deleted; shipped ones can be edited but not removed. */
    suspend fun delete(id: Long): Boolean {
        val icon = db.withDb { database ->
            val dao = database.counterpartyDao()
            val row = dao.get(id)
            if (row == null || row.merchantKey != null) {
                null
            } else {
                dao.delete(id) // aliases go with it (ON DELETE CASCADE)
                row.icon
            }
        } ?: return false
        icons.delete(icon)
        return true
    }

    suspend fun addAlias(counterpartyId: Long, raw: String): AliasAddResult = db.withDb { database ->
        val key = AliasKey.normalize(raw) ?: return@withDb AliasAddResult.Invalid
        val existing = database.counterpartyAliasDao().get(key)
        if (existing != null) {
            val owner = database.counterpartyDao().get(existing.counterpartyId)?.displayName.orEmpty()
            AliasAddResult.AlreadyMapped(owner, sameMerchant = existing.counterpartyId == counterpartyId)
        } else {
            database.counterpartyAliasDao().insert(
                CounterpartyAliasEntity(rawKey = key, counterpartyId = counterpartyId, createdBy = AliasSource.USER),
            )
            AliasAddResult.Added
        }
    }

    suspend fun removeAlias(rawKey: String) = db.withDb { it.counterpartyAliasDao().delete(rawKey) }

    private fun cleanName(name: String): String =
        name.trim().replace(Regex("""\s+"""), " ").take(MAX_NAME)

    companion object {
        const val MAX_NAME = 40
    }
}
