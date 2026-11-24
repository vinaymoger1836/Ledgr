package com.ledgr.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.db.AccountEntity
import com.ledgr.app.data.profile.AccountRepository
import com.ledgr.app.data.profile.AccountSaveResult
import com.ledgr.app.data.profile.CategoryRepository
import com.ledgr.app.data.profile.IdentityRepository
import com.ledgr.app.data.profile.MerchantRepository
import com.ledgr.app.data.rules.RulesLoader
import com.ledgr.app.domain.model.AccountType
import com.ledgr.app.domain.profile.Last4
import com.ledgr.app.domain.profile.SenderHeaders
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A bank from `banks.yaml`, as the pickers need it. */
data class BankOption(
    val id: String,
    val name: String,
    /** 0xAARRGGBB card colour. */
    val color: Int,
    /** Registry headers (account + card), upper-case. */
    val headers: List<String>,
    /** True while the registry headers have not been checked against the owner's inbox. */
    val unverified: Boolean,
)

/** One account as a card on the Profile screen. */
data class AccountCardUi(
    val id: Long,
    val nickname: String,
    val bankName: String,
    val bankColor: Int,
    val type: AccountType,
    val last4: String,
    val headerCount: Int,
    val active: Boolean,
)

enum class HeaderNote { Confirmed, Unverified, Listed, Manual }

data class HeaderChoice(val header: String, val selected: Boolean, val note: HeaderNote)

enum class AccountFormError { Last4, Nickname, Duplicate, SaveFailed }

/** The add/edit account sheet. */
data class AccountEditorUi(
    val id: Long,
    val bankId: String,
    val type: AccountType,
    val nickname: String,
    val last4: String,
    val active: Boolean,
    val headers: List<HeaderChoice>,
    val manualHeader: String,
    val manualHeaderInvalid: Boolean,
    val error: AccountFormError?,
    val saving: Boolean,
    val confirmDelete: Boolean,
) {
    val isNew: Boolean get() = id == 0L
}

data class ProfileUiState(
    val loaded: Boolean = false,
    val accounts: List<AccountCardUi> = emptyList(),
    val banks: List<BankOption> = emptyList(),
    val identityCount: Int = 0,
    val visibleCategoryCount: Int = 0,
    val merchantCount: Int = 0,
    val editor: AccountEditorUi? = null,
)

sealed interface ProfileIntent {
    data object OpenAdd : ProfileIntent
    data class OpenEdit(val accountId: Long) : ProfileIntent
    data object CloseEditor : ProfileIntent
    data class SetBank(val bankId: String) : ProfileIntent
    data class SetType(val type: AccountType) : ProfileIntent
    data class SetNickname(val text: String) : ProfileIntent
    data class SetLast4(val text: String) : ProfileIntent
    data class ToggleHeader(val header: String) : ProfileIntent
    data class SetManualHeader(val text: String) : ProfileIntent
    data object AddManualHeader : ProfileIntent
    data class SetActive(val active: Boolean) : ProfileIntent
    data object Save : ProfileIntent
    data object RequestDelete : ProfileIntent
    data object CancelDelete : ProfileIntent
    data object ConfirmDelete : ProfileIntent
}

/** Everything the sheet edits, before it is turned into [AccountEditorUi]. */
private data class Draft(
    val id: Long = 0,
    val bankId: String,
    val type: AccountType = AccountType.SAVINGS,
    val nickname: String = "",
    val last4: String = "",
    val active: Boolean = true,
    val selected: Set<String> = emptySet(),
    /** Headers saved on the account when the sheet opened. */
    val confirmed: Set<String> = emptySet(),
    /** Headers typed in by hand that are not in the bank's list. */
    val manual: Set<String> = emptySet(),
    val manualInput: String = "",
    val manualInvalid: Boolean = false,
    val error: AccountFormError? = null,
    val saving: Boolean = false,
    val confirmDelete: Boolean = false,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val accounts: AccountRepository,
    identities: IdentityRepository,
    categories: CategoryRepository,
    merchants: MerchantRepository,
    rulesLoader: RulesLoader,
) : ViewModel() {

    private val banks: StateFlow<List<BankOption>?> = flow {
        emit(
            rulesLoader.bundle().banks.banks.map {
                BankOption(it.id, it.name, it.argb, it.allHeaders, it.verify)
            },
        )
    }.catch { emit(emptyList()) } // a broken rules file is reported by the global database banner
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val draft = MutableStateFlow<Draft?>(null)

    private val lists = combine(
        accounts.accounts,
        identities.identities,
        categories.categories,
        merchants.merchants,
        banks,
    ) { accountRows, identityRows, categoryRows, merchantRows, bankRows ->
        val bankList = bankRows.orEmpty()
        ProfileUiState(
            loaded = bankRows != null,
            accounts = accountRows.map { row ->
                val bank = bankList.firstOrNull { it.id == row.bankId }
                AccountCardUi(
                    id = row.id,
                    nickname = row.nickname,
                    bankName = bank?.name ?: row.bankId,
                    bankColor = row.color,
                    type = row.type,
                    last4 = row.last4,
                    headerCount = SenderHeaders.decode(row.senderHeaders).size,
                    active = row.active,
                )
            },
            banks = bankList,
            identityCount = identityRows.size,
            visibleCategoryCount = categoryRows.count { !it.hidden },
            merchantCount = merchantRows.size,
        )
    }

    val state: StateFlow<ProfileUiState> = combine(lists, draft, banks) { base, d, bankRows ->
        base.copy(editor = d?.toUi(bankRows.orEmpty()))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    fun onIntent(intent: ProfileIntent) {
        when (intent) {
            ProfileIntent.OpenAdd -> banks.value?.firstOrNull()?.let { draft.value = Draft(bankId = it.id) }
            is ProfileIntent.OpenEdit -> openEdit(intent.accountId)
            ProfileIntent.CloseEditor -> draft.value = null
            is ProfileIntent.SetBank -> editDraft {
                if (it.bankId == intent.bankId) {
                    it
                } else {
                    it.copy(bankId = intent.bankId, selected = emptySet(), confirmed = emptySet(), manual = emptySet())
                }
            }
            is ProfileIntent.SetType -> editDraft { it.copy(type = intent.type) }
            is ProfileIntent.SetNickname -> editDraft { it.copy(nickname = intent.text.take(MAX_NICKNAME), error = null) }
            // Longer than 4 digits is refused (the old text stays), never truncated: see Last4.accept.
            is ProfileIntent.SetLast4 -> editDraft { it.copy(last4 = Last4.accept(it.last4, intent.text), error = null) }
            is ProfileIntent.ToggleHeader -> editDraft {
                it.copy(selected = if (intent.header in it.selected) it.selected - intent.header else it.selected + intent.header)
            }
            is ProfileIntent.SetManualHeader -> editDraft { it.copy(manualInput = intent.text.take(MAX_HEADER_INPUT), manualInvalid = false) }
            ProfileIntent.AddManualHeader -> addManualHeader()
            is ProfileIntent.SetActive -> editDraft { it.copy(active = intent.active) }
            ProfileIntent.Save -> save()
            ProfileIntent.RequestDelete -> editDraft { it.copy(confirmDelete = true) }
            ProfileIntent.CancelDelete -> editDraft { it.copy(confirmDelete = false) }
            ProfileIntent.ConfirmDelete -> delete()
        }
    }

    private fun editDraft(change: (Draft) -> Draft) {
        draft.update { current -> current?.let(change) }
    }

    private fun openEdit(accountId: Long) {
        viewModelScope.launch {
            val row = runCatching { accounts.get(accountId) }.getOrNull() ?: return@launch
            val headers = SenderHeaders.decode(row.senderHeaders).toSet()
            draft.value = Draft(
                id = row.id,
                bankId = row.bankId,
                type = row.type,
                nickname = row.nickname,
                last4 = row.last4,
                active = row.active,
                selected = headers,
                confirmed = headers,
            )
        }
    }

    private fun addManualHeader() {
        editDraft { d ->
            val header = SenderHeaders.fromInput(d.manualInput)
            if (header == null) {
                d.copy(manualInvalid = d.manualInput.isNotBlank())
            } else {
                val registry = banks.value.orEmpty().firstOrNull { it.id == d.bankId }?.headers.orEmpty()
                d.copy(
                    selected = d.selected + header,
                    manual = if (header in registry || header in d.confirmed) d.manual else d.manual + header,
                    manualInput = "",
                    manualInvalid = false,
                )
            }
        }
    }

    private fun save() {
        val d = draft.value ?: return
        if (d.saving) return
        val bank = banks.value.orEmpty().firstOrNull { it.id == d.bankId } ?: return
        if (!Last4.isValid(d.last4)) {
            editDraft { it.copy(error = AccountFormError.Last4) }
            return
        }
        if (d.nickname.isBlank()) {
            editDraft { it.copy(error = AccountFormError.Nickname) }
            return
        }
        editDraft { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val existing = if (d.id != 0L) accounts.get(d.id) else null
                accounts.save(
                    AccountEntity(
                        id = d.id,
                        bankId = d.bankId,
                        type = d.type,
                        nickname = d.nickname,
                        last4 = d.last4,
                        color = bank.color,
                        senderHeaders = SenderHeaders.encode(d.selected.toList()),
                        openingBalance = existing?.openingBalance,
                        active = d.active,
                    ),
                )
            }.getOrNull()
            when (result) {
                is AccountSaveResult.Saved -> draft.value = null
                AccountSaveResult.InvalidLast4 -> fail(AccountFormError.Last4)
                AccountSaveResult.BlankNickname -> fail(AccountFormError.Nickname)
                AccountSaveResult.Duplicate -> fail(AccountFormError.Duplicate)
                null -> fail(AccountFormError.SaveFailed)
            }
        }
    }

    private fun delete() {
        val d = draft.value ?: return
        viewModelScope.launch {
            val ok = runCatching { accounts.delete(d.id) }.isSuccess
            if (ok) draft.value = null else fail(AccountFormError.SaveFailed)
        }
    }

    private fun fail(error: AccountFormError) {
        editDraft { it.copy(saving = false, confirmDelete = false, error = error) }
    }

    private fun Draft.toUi(bankList: List<BankOption>): AccountEditorUi {
        val bank = bankList.firstOrNull { it.id == bankId }
        val registry = bank?.headers.orEmpty()
        val ordered = (registry + confirmed.filter { it !in registry } + manual.filter { it !in registry && it !in confirmed })
            .distinct()
        val choices = ordered.map { header ->
            val note = when {
                header in confirmed -> HeaderNote.Confirmed
                header in registry && bank?.unverified == true -> HeaderNote.Unverified
                header in registry -> HeaderNote.Listed
                else -> HeaderNote.Manual
            }
            HeaderChoice(header, header in selected, note)
        }
        return AccountEditorUi(
            id = id,
            bankId = bankId,
            type = type,
            nickname = nickname,
            last4 = last4,
            active = active,
            headers = choices,
            manualHeader = manualInput,
            manualHeaderInvalid = manualInvalid,
            error = error,
            saving = saving,
            confirmDelete = confirmDelete,
        )
    }

    private companion object {
        const val MAX_NICKNAME = 30
        const val MAX_HEADER_INPUT = 20
    }
}
