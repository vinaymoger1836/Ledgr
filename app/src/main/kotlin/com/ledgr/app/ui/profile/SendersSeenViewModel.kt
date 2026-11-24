package com.ledgr.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.profile.AccountRepository
import com.ledgr.app.data.profile.HeaderAssignResult
import com.ledgr.app.data.rules.RulesLoader
import com.ledgr.app.data.sms.SendersResult
import com.ledgr.app.data.sms.SendersSnapshot
import com.ledgr.app.data.sms.SmsInboxSenders
import com.ledgr.app.domain.profile.SenderHeaders
import com.ledgr.app.domain.sender.SenderSuffix
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

/** Where we are with the READ_SMS permission (docs/03 §1). */
enum class SmsAccess {
    /** Haven't looked yet. */
    Checking,

    /** Not granted; show the explainer and ask. */
    NeedsPermission,

    /** The system refused or never showed the dialog (sideloaded app: "restricted settings"); show the help. */
    Blocked,
    Granted,
}

data class SenderRowUi(
    val header: String,
    val messageCount: Int,
    val lastSeen: Long,
    /** Per-suffix counts, largest first. */
    val suffixes: List<Pair<SenderSuffix, Int>>,
    /** The bank the owner already assigned this header to. */
    val assigned: BankOption?,
    /** A bank whose list names this header (unverified until the owner assigns it). */
    val suggested: BankOption?,
    /** Every message used the promotional suffix: such senders never create transactions (docs/04 L2). */
    val promotionalOnly: Boolean,
) {
    val isLikelyBank: Boolean get() = assigned != null || suggested != null
}

sealed interface SendersMessage {
    data class Assigned(val header: String, val bank: String, val accounts: Int) : SendersMessage
    data class Unassigned(val header: String) : SendersMessage
    data object Failed : SendersMessage
}

data class SendersUiState(
    val access: SmsAccess = SmsAccess.Checking,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    /** Every header seen (largest first). The screen filters by [showAll]. */
    val rows: List<SenderRowUi> = emptyList(),
    val likelyCount: Int = 0,
    val showAll: Boolean = false,
    val phoneMessages: Int = 0,
    val otherMessages: Int = 0,
    val totalMessages: Int = 0,
    val banks: List<BankOption> = emptyList(),
    val bankIdsWithAccounts: Set<String> = emptySet(),
    /** The header the bank picker is open for. */
    val pickerHeader: String? = null,
    val pickerCurrentBankId: String? = null,
    /** Set when the chosen bank has no account yet. */
    val noAccountBank: BankOption? = null,
    val message: SendersMessage? = null,
)

sealed interface SendersIntent {
    /** Screen shown or resumed: re-check the permission and (re)load. */
    data object Refresh : SendersIntent
    data class PermissionResult(val granted: Boolean) : SendersIntent
    data class SetShowAll(val all: Boolean) : SendersIntent
    data class OpenPicker(val header: String) : SendersIntent
    data object ClosePicker : SendersIntent
    data class Assign(val header: String, val bankId: String) : SendersIntent
    data class Unassign(val header: String) : SendersIntent
    data object DismissNoAccount : SendersIntent
    data object MessageShown : SendersIntent
}

private data class Local(
    val access: SmsAccess = SmsAccess.Checking,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val snapshot: SendersSnapshot? = null,
    val showAll: Boolean = false,
    val pickerHeader: String? = null,
    val noAccountBankId: String? = null,
    val message: SendersMessage? = null,
)

@HiltViewModel
class SendersSeenViewModel @Inject constructor(
    private val inbox: SmsInboxSenders,
    private val accounts: AccountRepository,
    rulesLoader: RulesLoader,
) : ViewModel() {

    private val banks: StateFlow<List<BankOption>> = flow {
        emit(rulesLoader.bundle().banks.banks.map { BankOption(it.id, it.name, it.argb, it.allHeaders, it.verify) })
    }.catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val local = MutableStateFlow(Local())

    val state: StateFlow<SendersUiState> = combine(local, accounts.accounts, banks) { l, accountRows, bankList ->
        val bankById = bankList.associateBy { it.id }
        val assignedBank = HashMap<String, BankOption>()
        val currentBankId = HashMap<String, String>()
        for (account in accountRows) {
            val bank = bankById[account.bankId] ?: continue
            for (header in SenderHeaders.decode(account.senderHeaders)) {
                assignedBank.putIfAbsent(header, bank)
                currentBankId.putIfAbsent(header, bank.id)
            }
        }
        val registry = HashMap<String, BankOption>()
        for (bank in bankList) for (header in bank.headers) registry.putIfAbsent(header, bank)

        val rows = l.snapshot?.headers.orEmpty().map { seen ->
            val promotionalOnly = seen.suffixCounts.keys.all { it == SenderSuffix.P }
            SenderRowUi(
                header = seen.header,
                messageCount = seen.messageCount,
                lastSeen = seen.lastSeen,
                suffixes = seen.suffixCounts.entries.sortedByDescending { it.value }.map { it.key to it.value },
                assigned = assignedBank[seen.header],
                suggested = if (promotionalOnly) null else registry[seen.header],
                promotionalOnly = promotionalOnly,
            )
        }
        val likely = rows.count { it.isLikelyBank }
        SendersUiState(
            access = l.access,
            loading = l.loading,
            loadFailed = l.loadFailed,
            rows = rows,
            likelyCount = likely,
            // With nothing recognisable, showing an empty "likely" list would just look broken.
            showAll = l.showAll || likely == 0,
            phoneMessages = l.snapshot?.phoneNumberMessages ?: 0,
            otherMessages = l.snapshot?.otherMessages ?: 0,
            totalMessages = l.snapshot?.totalMessages ?: 0,
            banks = bankList,
            bankIdsWithAccounts = accountRows.map { it.bankId }.toSet(),
            pickerHeader = l.pickerHeader,
            pickerCurrentBankId = l.pickerHeader?.let(currentBankId::get),
            noAccountBank = l.noAccountBankId?.let(bankById::get),
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SendersUiState())

    fun onIntent(intent: SendersIntent) {
        when (intent) {
            SendersIntent.Refresh -> refresh()
            is SendersIntent.PermissionResult -> {
                if (intent.granted) {
                    local.update { it.copy(access = SmsAccess.Granted) }
                    load()
                } else {
                    local.update { it.copy(access = SmsAccess.Blocked) }
                }
            }
            is SendersIntent.SetShowAll -> local.update { it.copy(showAll = intent.all) }
            is SendersIntent.OpenPicker -> local.update { it.copy(pickerHeader = intent.header) }
            SendersIntent.ClosePicker -> local.update { it.copy(pickerHeader = null) }
            is SendersIntent.Assign -> assign(intent.header, intent.bankId)
            is SendersIntent.Unassign -> unassign(intent.header)
            SendersIntent.DismissNoAccount -> local.update { it.copy(noAccountBankId = null) }
            SendersIntent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun refresh() {
        if (inbox.hasPermission()) {
            local.update { it.copy(access = SmsAccess.Granted) }
            load()
        } else {
            // Keep "Blocked" (the help is already on screen) unless the permission was just revoked.
            local.update { if (it.access == SmsAccess.Blocked) it else it.copy(access = SmsAccess.NeedsPermission, snapshot = null) }
        }
    }

    private fun load() {
        if (local.value.loading) return
        local.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            when (val result = inbox.read()) {
                is SendersResult.Success -> local.update { it.copy(loading = false, snapshot = result.snapshot, access = SmsAccess.Granted) }
                SendersResult.PermissionDenied ->
                    local.update { it.copy(loading = false, snapshot = null, access = SmsAccess.NeedsPermission) }
                is SendersResult.Failure -> local.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    private fun assign(header: String, bankId: String) {
        local.update { it.copy(pickerHeader = null) }
        viewModelScope.launch {
            val result = runCatching { accounts.assignHeader(bankId, header) }.getOrNull()
            val bank = banks.value.firstOrNull { it.id == bankId }
            local.update {
                when (result) {
                    is HeaderAssignResult.Assigned ->
                        it.copy(message = SendersMessage.Assigned(header, bank?.name ?: bankId, result.accounts))
                    HeaderAssignResult.NoAccountForBank -> it.copy(noAccountBankId = bankId)
                    null -> it.copy(message = SendersMessage.Failed)
                }
            }
        }
    }

    private fun unassign(header: String) {
        local.update { it.copy(pickerHeader = null) }
        viewModelScope.launch {
            val ok = runCatching { accounts.unassignHeader(header) }.isSuccess
            local.update { it.copy(message = if (ok) SendersMessage.Unassigned(header) else SendersMessage.Failed) }
        }
    }
}
