package com.ledgr.app.data.profile

import androidx.room.withTransaction
import com.ledgr.app.data.db.AccountEntity
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.domain.profile.Last4
import com.ledgr.app.domain.profile.SenderHeaders
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

sealed interface AccountSaveResult {
    data class Saved(val id: Long) : AccountSaveResult
    data object InvalidLast4 : AccountSaveResult
    data object BlankNickname : AccountSaveResult

    /** Another account of the same bank already ends in these four digits. */
    data object Duplicate : AccountSaveResult
}

sealed interface HeaderAssignResult {
    /** The header is now on [accounts] account(s) of the bank. */
    data class Assigned(val accounts: Int) : HeaderAssignResult

    /** No account of that bank exists yet; nothing was changed. */
    data object NoAccountForBank : HeaderAssignResult
}

/**
 * Accounts (docs/05 `account`). Only the last four digits are stored (ADR-006): [save] refuses anything else,
 * so no caller can persist a longer number.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val db: DatabaseManager,
) {
    val accounts: Flow<List<AccountEntity>> = db.observe { it.accountDao().observeAll() }

    suspend fun get(id: Long): AccountEntity? = db.withDb { it.accountDao().get(id) }

    suspend fun save(account: AccountEntity): AccountSaveResult = db.withDb { database ->
        val nickname = account.nickname.trim()
        when {
            !Last4.isValid(account.last4) -> AccountSaveResult.InvalidLast4
            nickname.isEmpty() -> AccountSaveResult.BlankNickname
            database.accountDao().countSameBankAndLast4(account.bankId, account.last4, account.id) > 0 ->
                AccountSaveResult.Duplicate
            else -> {
                val clean = account.copy(nickname = nickname)
                val id = if (clean.id == 0L) {
                    database.accountDao().insert(clean)
                } else {
                    database.accountDao().update(clean)
                    clean.id
                }
                AccountSaveResult.Saved(id)
            }
        }
    }

    /**
     * Deletes an account that has no transactions. Once an account has any, it can only be archived (the "In use"
     * switch), so history is never orphaned (P1 report). Returns false when it was kept for that reason.
     */
    suspend fun delete(id: Long): Boolean = db.withDb { database ->
        if (database.txnDao().countForAccount(id) > 0) return@withDb false
        database.accountDao().get(id)?.let { database.accountDao().delete(it) }
        true
    }

    /**
     * "This sender header belongs to [bankId]" (docs/03 §3). The header goes onto every account of that bank and
     * is taken off every other bank's accounts, so one header never belongs to two banks.
     */
    suspend fun assignHeader(bankId: String, header: String): HeaderAssignResult = db.withDb { database ->
        database.withTransaction {
            val dao = database.accountDao()
            val all = dao.getAll()
            val ofBank = all.filter { it.bankId == bankId }
            if (ofBank.isEmpty()) return@withTransaction HeaderAssignResult.NoAccountForBank
            for (account in all) {
                val current = SenderHeaders.decode(account.senderHeaders)
                val updated = if (account.bankId == bankId) {
                    if (header in current) current else current + header
                } else {
                    current - header
                }
                if (updated != current) dao.setSenderHeaders(account.id, SenderHeaders.encode(updated))
            }
            HeaderAssignResult.Assigned(ofBank.size)
        }
    }

    /** Takes [header] off every account. */
    suspend fun unassignHeader(header: String) = db.withDb { database ->
        database.withTransaction {
            val dao = database.accountDao()
            for (account in dao.getAll()) {
                val current = SenderHeaders.decode(account.senderHeaders)
                if (header in current) dao.setSenderHeaders(account.id, SenderHeaders.encode(current - header))
            }
        }
    }
}
