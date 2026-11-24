package com.ledgr.app.data.profile

import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.OwnIdentityEntity
import com.ledgr.app.domain.model.OwnIdentityKind
import com.ledgr.app.domain.profile.NameVariant
import com.ledgr.app.domain.profile.UpiId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

sealed interface IdentityAddResult {
    data object Added : IdentityAddResult

    /** Not a `name@handle` VPA, or not a plausible name. */
    data object Invalid : IdentityAddResult
    data object Duplicate : IdentityAddResult
}

/** The user's own UPI IDs and name spellings, used for own-transfer detection (docs/04 §4). */
@Singleton
class IdentityRepository @Inject constructor(
    private val db: DatabaseManager,
) {
    val identities: Flow<List<OwnIdentityEntity>> = db.observe { it.ownIdentityDao().observeAll() }

    suspend fun add(kind: OwnIdentityKind, input: String): IdentityAddResult {
        val value = when (kind) {
            OwnIdentityKind.UPI_ID -> UpiId.normalize(input)
            OwnIdentityKind.NAME_VARIANT -> NameVariant.normalize(input)
        } ?: return IdentityAddResult.Invalid
        val rowId = db.withDb { it.ownIdentityDao().insert(OwnIdentityEntity(kind = kind, value = value)) }
        return if (rowId == -1L) IdentityAddResult.Duplicate else IdentityAddResult.Added
    }

    suspend fun remove(id: Long) = db.withDb { it.ownIdentityDao().delete(id) }
}
